package com.sparrowwallet.perseverus;

import java.util.Objects;

/**
 * High-level API for querying a single UTXO's KYC tag via the native
 * Perseverus filter + OPRF pipeline (Whitepaper Sections 7 + 9).
 *
 * <p>Each call is stateless — no handle management needed. The server
 * pubkey is fetched automatically from the server's {@code /server/pubkey}
 * endpoint.
 *
 * <p>This replaces the previous {@code perseverus-cli query} subprocess
 * call with a direct JNI invocation.
 */
public final class PrivacyQuery {

    private PrivacyQuery() {}

    /**
     * Tracks whether the authenticated native method is available.
     * Set to {@code false} on the first {@link UnsatisfiedLinkError}
     * so we don't pay the exception cost on every subsequent call.
     */
    private static volatile boolean authenticatedAvailable = true;

    /**
     * Result of a privacy query for a single UTXO.
     *
     * @param kycTag     human-readable tag (e.g. "Clean", "KYC Exchange: Coinbase")
     * @param tagType    category for scoring ("Clean", "Coinbase", "KYC Exchange",
     *                   "CoinJoin", "Unknown", "Error")
     * @param styleClass CSS class for the privacy table ("privacy-good",
     *                   "privacy-neutral", "privacy-bad", "privacy-error")
     * @param status     "OK" on success, error message on failure
     */
    public record Result(String kycTag, String tagType, String styleClass, String status) {}

    /**
     * Query the KYC tag for a single UTXO via the native pipeline
     * (unauthenticated — no token spend binding).
     *
     * @param serverUrl   Perseverus server base URL (e.g. "http://localhost:3030")
     * @param txid        transaction ID as a 32-byte array (big-endian, as from
     *                    {@code Sha256Hash.getBytes()})
     * @param vout        output index
     * @param blockHeight block height the UTXO was confirmed in
     * @param numDecoys   number of decoy blocks (0–10)
     * @param chainTip    current chain tip height
     * @return query result with tag, style, and status
     */
    public static Result query(String serverUrl, byte[] txid, int vout,
                               int blockHeight, int numDecoys, int chainTip) {
        return query(serverUrl, txid, vout, blockHeight, numDecoys, chainTip, null);
    }

    /**
     * Return the raw tag string from the native query (e.g. {@code "V3LEAN:e000d6"},
     * {@code "Clean"}, or a {@code "...\nLOG:..."} blob). Lets callers that want
     * to build a full report decode the 24-bit tag themselves rather than going
     * through the lossy {@link #parseTag} mapping. Throws on native/transport error.
     */
    public static String queryRaw(String serverUrl, byte[] txid, int vout,
                                  int blockHeight, int numDecoys, int chainTip) {
        return Native.queryUtxo(serverUrl, txid, vout, blockHeight, numDecoys, chainTip);
    }

    /** Strip the {@code \nLOG:...} suffix the native layer may append, returning just the tag token. */
    public static String tagToken(String raw) {
        if (raw == null) return "";
        int i = raw.indexOf("\nLOG:");
        return i >= 0 ? raw.substring(0, i) : raw;
    }

    /**
     * Authenticated query: binds the UTXO lookup to a previously
     * obtained OPRF gamma from a token spend, proving the caller holds
     * a valid credential without revealing their identity.
     *
     * <p>If {@code gamma} is non-null and the native library supports
     * the authenticated entry point, the gamma is included in the
     * server request. Otherwise falls back transparently to the
     * unauthenticated path.
     *
     * @param gamma compressed G1 affine blob from
     *              {@link PerseverusService#spend}, or {@code null} for
     *              unauthenticated query
     */
    public static Result query(String serverUrl, byte[] txid, int vout,
                               int blockHeight, int numDecoys, int chainTip,
                               byte[] gamma) {
        return queryWithRaw(serverUrl, txid, vout, blockHeight, numDecoys, chainTip, gamma).result();
    }

    /** The parsed display {@link Result} plus the raw tag string (e.g. {@code "V3LEAN:e000d6"}). */
    public record QueryOutcome(Result result, String rawTag) {}

    /**
     * {@code tagType} value for a UTXO the protocol intentionally does not
     * grade (its creating transaction was skipped by the build-side rules, so
     * it is absent from the filter). Rendered as a neutral "No grade" rather
     * than a red error, and excluded from the wallet's overall score.
     */
    public static final String UNGRADED_TAG_TYPE = "Ungraded";

    /** Neutral result for a UTXO that is absent from the filter by design. */
    private static QueryOutcome ungradedOutcome() {
        return new QueryOutcome(
                new Result("No grade", UNGRADED_TAG_TYPE, "privacy-neutral", "No grade"), null);
    }

    /**
     * True if the native layer reported the UTXO as absent from the filter —
     * i.e. its creating transaction was skipped at build time (dust/tiny-value
     * or a data carrier such as OP_RETURN/ordinal/runes). This is a by-design
     * "not graded" outcome, distinct from a transport or crypto failure.
     */
    private static boolean isNotInFilter(String msg) {
        return msg != null && msg.contains("not found in filter");
    }

    /**
     * Like {@link #query(String, byte[], int, int, int, int, byte[])} but also
     * returns the raw tag token so callers can build a full report from the
     * decoded 24-bit tag. {@code rawTag} is null on error.
     */
    public static QueryOutcome queryWithRaw(String serverUrl, byte[] txid, int vout,
                                            int blockHeight, int numDecoys, int chainTip,
                                            byte[] gamma) {
        Objects.requireNonNull(serverUrl, "serverUrl");
        Objects.requireNonNull(txid, "txid");
        if (txid.length != 32) {
            return new QueryOutcome(new Result("Error", "Error", "privacy-error",
                    "txid must be 32 bytes, got " + txid.length), null);
        }

        try {
            String tagStr;
            if (gamma != null && authenticatedAvailable) {
                try {
                    tagStr = Native.queryUtxoAuthenticated(serverUrl, txid, vout,
                            blockHeight, numDecoys, chainTip, gamma);
                } catch (UnsatisfiedLinkError e) {
                    authenticatedAvailable = false;
                    tagStr = Native.queryUtxo(serverUrl, txid, vout,
                            blockHeight, numDecoys, chainTip);
                }
            } else {
                tagStr = Native.queryUtxo(serverUrl, txid, vout,
                        blockHeight, numDecoys, chainTip);
            }

            return new QueryOutcome(parseTag(tagStr), tagStr);
        } catch (PerseverusException e) {
            if (isNotInFilter(e.getMessage())) {
                return ungradedOutcome();
            }
            return new QueryOutcome(new Result("Error", "Error", "privacy-error", e.getMessage()), null);
        } catch (Exception e) {
            if (isNotInFilter(e.getMessage())) {
                return ungradedOutcome();
            }
            return new QueryOutcome(new Result("Error", "Error", "privacy-error",
                    "Unexpected: " + e.getMessage()), null);
        }
    }

    /**
     * Parse the raw tag string returned by the Rust side into a
     * display-friendly result. The format matches what
     * {@code PrivacyController.parseCliOutput} expected from the CLI.
     *
     * <p>The Rust JNI layer may append {@code \nLOG:...} lines with
     * filter/decoy activity details. These are extracted and written
     * to {@link PrivacyLog} before parsing the tag itself.
     */
    public static Result parseTag(String tagStr) {
        if (tagStr == null || tagStr.isEmpty()) {
            return new Result("Unknown", "Unknown", "privacy-good", "OK");
        }

        // Extract and log any LOG: lines appended by the Rust layer
        String tag = tagStr;
        int firstLog = tagStr.indexOf("\nLOG:");
        if (firstLog >= 0) {
            tag = tagStr.substring(0, firstLog);
            String[] lines = tagStr.substring(firstLog).split("\n");
            for (String line : lines) {
                if (line.startsWith("LOG:")) {
                    PrivacyLog.get().info(line.substring(4));
                }
            }
        }

        return switch (tag) {
            case "Clean" -> new Result("Clean", "Clean", "privacy-good", "OK");
            case "Coinbase" -> new Result("Coinbase (Mining)", "Coinbase", "privacy-neutral", "OK");
            case "CoinJoin" -> new Result("CoinJoin", "CoinJoin", "privacy-good", "OK");
            case "Unknown" -> new Result("Unknown", "Unknown", "privacy-good", "OK");
            default -> {
                // v5 / v3-lean 24-bit tag: "V3LEAN:<6-hex>" (SPEC.md §2a)
                if (tag.startsWith("V3LEAN:")) {
                    try {
                        V3LeanTag d = V3LeanTag.fromTagString(tag);
                        yield new Result(d.summary(), "Grade " + d.gradeLabel(),
                                d.styleClass(), "OK");
                    } catch (NumberFormatException ex) {
                        yield new Result(tag, "Unknown", "privacy-error",
                                "bad v3-lean tag: " + tag);
                    }
                }
                // KycExchange("ExchangeName") format (legacy v3)
                if (tag.startsWith("KycExchange(\"") && tag.endsWith("\")")) {
                    String name = tag.substring(
                            "KycExchange(\"".length(),
                            tag.length() - "\")".length());
                    yield new Result("KYC Exchange: " + name, "KYC Exchange",
                            "privacy-bad", "OK");
                }
                yield new Result(tag, "Unknown", "privacy-good", "OK");
            }
        };
    }
}
