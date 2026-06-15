package com.sparrowwallet.perseverus;

import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Config.PerseverusTransport;
import com.sparrowwallet.sparrow.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Single facade Sparrow talks to for the JNI-backed Perseverus flow.
 *
 * <p>Owns one {@link IssuanceClient} and one {@link SpendClient} for
 * the lifetime of the service. Construction is cheap; the expensive
 * bits are behind {@link #bootstrap()}, which triggers the PK download
 * on the spend side. Call {@link #bootstrap()} once per wallet session
 * (e.g. on the first scan button press).
 *
 * <p>This is deliberately a thin wrapper — no retries, no caching, no
 * background threads. Scheduling and error-UI belong to the caller
 * ({@code PrivacyController} today), which already handles those
 * concerns for the subprocess path.
 *
 * <p>Tokens are immediately spendable after issuance — no epoch
 * rollover or waiting period is needed.
 *
 * <pre>{@code
 * try (PerseverusService svc = PerseverusService.open(baseUrl, pubkeyHex)) {
 *     svc.bootstrap();               // heavy, once
 *     IssuedPack pack = svc.issuePack(8);
 *     byte[] gamma = svc.spend(pack, 0, outpointBytes);
 * }
 * }</pre>
 */
public final class PerseverusService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PerseverusService.class);

    private final String baseUrl;
    private final String serverPubkeyG1Hex;

    private IssuanceClient issuance;
    private SpendClient spend;

    private PerseverusService(String baseUrl, String serverPubkeyG1Hex) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.serverPubkeyG1Hex = Objects.requireNonNull(serverPubkeyG1Hex, "serverPubkeyG1Hex");
    }

    /** Open a fresh service. The {@link IssuanceClient} is allocated
     *  eagerly (it's cheap); the {@link SpendClient} waits for
     *  {@link #bootstrap()}.
     *
     *  <p>Reads the privacy transport mode from {@link Config} and
     *  pushes it to the native layer before any network calls. */
    public static PerseverusService open(String baseUrl, String serverPubkeyG1Hex) {
        // Configure native transport before any HTTP traffic
        configureNativeTransport();

        PerseverusService svc = new PerseverusService(baseUrl, serverPubkeyG1Hex);
        svc.issuance = IssuanceClient.open(baseUrl, serverPubkeyG1Hex);
        log.info("Perseverus service opened against {} (native version={}, transport={})",
                baseUrl, Native.version(), Config.get().getPerseverusTransport().getLabel());
        return svc;
    }

    /**
     * Push the current transport mode from {@link Config} to the native
     * layer. Called automatically by {@link #open} and can be called
     * again at any time to pick up config changes (e.g. user toggled
     * Tor in settings).
     */
    public static void configureNativeTransport() {
        PerseverusTransport transport = Config.get().getPerseverusTransport();
        // AUTO is not recognized by the Rust native layer — resolve it
        // to TOR (strongest privacy) before sending.
        if (transport == PerseverusTransport.AUTO) {
            transport = PerseverusTransport.TOR;
        }
        // Only pass the relay URL for OHTTP mode. For TOR mode the native
        // side defaults to socks5h://127.0.0.1:9050; passing the OHTTP relay
        // URL when mode is TOR would corrupt the SOCKS proxy address.
        String param = (transport == PerseverusTransport.OHTTP)
                ? Config.get().getPerseverusOhttpRelayUrl()
                : null;
        try {
            Native.configureTransport(transport.name(), param);
            log.info("Native transport configured: mode={}, param={}",
                    transport.name(), param != null ? param : "(default)");
        } catch (UnsatisfiedLinkError e) {
            log.warn("Native configureTransport not available — transport will default to DIRECT. " +
                    "Rebuild client-native with transport support to enable OHTTP/Tor.");
        }
    }

    /** Run the one-time spend bootstrap. Safe to call more than once
     *  — subsequent calls are no-ops so callers can trigger it
     *  idempotently from a UI event without tracking state
     *  externally. */
    public synchronized void bootstrap() {
        if (spend != null) {
            return;
        }
        File cacheFile = new File(Storage.getSparrowDir(), "perseverus_pk.bin");
        String cachePath = cacheFile.getAbsolutePath();
        // The Groth16 proving key is a public, fixed artifact of the trusted
        // setup — the same for every wallet and paired with the VK we already
        // ship. Bundle it and seed the cache so first launch needs no
        // /circuit/pk download (which 404s if the server hasn't installed one).
        seedBundledProvingKey(cacheFile);
        log.info("Bootstrapping Perseverus spend client (PK cache: {})", cachePath);
        long t0 = System.nanoTime();
        spend = SpendClient.bootstrap(baseUrl, serverPubkeyG1Hex, cachePath);
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        log.info("Spend client bootstrapped in {} ms", String.format("%.0f", ms));
    }

    /** Classpath location of the bundled Groth16 proving key. Present only when
     *  the build includes the resource (see build instructions); absent builds
     *  fall back to the server's {@code /circuit/pk}. */
    private static final String BUNDLED_PK_RESOURCE =
            "/com/sparrowwallet/perseverus/pk.bin";

    /** Populate the on-disk PK cache from the bundled proving-key resource so
     *  the native bootstrap loads it locally instead of downloading. When the
     *  resource is bundled it is treated as authoritative and OVERWRITES any
     *  existing cache — otherwise a stale {@code perseverus_pk.bin} from an
     *  earlier build (a different trusted setup) would shadow it and every
     *  spend proof would fail verification. If nothing is bundled, the existing
     *  cache / server download path is left untouched. */
    private static void seedBundledProvingKey(File cacheFile) {
        try (InputStream in = PerseverusService.class.getResourceAsStream(BUNDLED_PK_RESOURCE)) {
            if (in == null) {
                log.info("No bundled proving key on classpath; using existing cache or /circuit/pk");
                return;
            }
            File parent = cacheFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(in, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("Seeded PK cache from bundled proving key ({} bytes)", cacheFile.length());
        } catch (IOException e) {
            log.warn("Could not seed bundled proving key ({}); will fall back to /circuit/pk",
                    e.getMessage());
        }
    }

    /** Pre-fetch filters for the given block heights in parallel via
     *  the native library. Safe to call from any thread. Errors for
     *  individual heights are logged in Rust but do not propagate. */
    public static void prefetchFilters(String serverUrl, int[] blockHeights) {
        Native.prefetchFilters(serverUrl, blockHeights);
    }

    /** Pre-fetch filters for real block heights AND their Laplace decoy
     *  blocks. Decoy generation happens in Rust using the same algorithm
     *  as the query phase, so the in-memory cache has everything ready. */
    public static void prefetchFiltersWithDecoys(String serverUrl, int[] blockHeights, int numDecoys, int chainTip, double scale) {
        Native.prefetchFiltersWithDecoys(serverUrl, blockHeights, numDecoys, chainTip, scale);
    }

    /** Poll filter download progress: [downloadedBytes, totalBytes]. */
    public static long[] prefetchProgress() {
        return Native.prefetchProgress();
    }

    /** Issue (and publish commitment for) a fresh pack. Does not
     *  require {@link #bootstrap()} — issuance is independent of the
     *  spend circuit. Tokens are immediately spendable.
     *
     * @param packSize number of tokens
     * @param expirationMonth YYYYMM format (e.g. 202612)
     */
    private static final int ISSUE_MAX_RETRIES = 3;
    private static final long ISSUE_RETRY_DELAY_MS = 2_000;

    public IssuedPack issuePack(int packSize, int expirationMonth) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= ISSUE_MAX_RETRIES; attempt++) {
            try {
                return issuance.issuePack(packSize, expirationMonth);
            } catch (Exception e) {
                lastError = e;
                if (attempt < ISSUE_MAX_RETRIES) {
                    try { Thread.sleep(ISSUE_RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw new PerseverusException("issuePack failed after " + ISSUE_MAX_RETRIES + " attempts: " + lastError.getMessage());
    }

    /**
     * BTC silent-payment issuance (proof-of-payment gated). Sends the proof
     * (computed at payment time) to the scanner's {@code /subscribe} over a
     * FRESH Tor circuit, receives a one-time {@code psbtc1.…} code, then blind-
     * signs a pack at {@code /btc/redeem}. This is the privacy boundary: the
     * subscription request rides a different circuit than the payment broadcast.
     *
     * @param scannerBaseUrl  the SP scanner base URL (onion), no trailing slash
     * @param packSize        token count (must match the code's authorized count)
     * @param expirationMonth YYYYMM
     * @param pubkeyHex       33-byte compressed input pubkey (hex) = payment a_sum
     * @param nonceHex        random nonce used in the claim (hex)
     * @param signatureHex    DER ECDSA over the claim (hex)
     */
    public IssuedPack redeemBtcSubscription(String scannerBaseUrl, int packSize, int expirationMonth,
                                            String pubkeyHex, String nonceHex, String signatureHex) {
        String url = scannerBaseUrl.replaceAll("/+$", "") + "/subscribe";
        String body = String.format(
                "{\"payment_pubkey\":\"%s\",\"nonce\":\"%s\",\"signature\":\"%s\"}",
                pubkeyHex, nonceHex, signatureHex);

        // The scanner detects the payment independently (block scan), which may
        // lag a little behind the wallet's own confirmation. So /subscribe can
        // return 404 ("no confirmed payment found") briefly — retry over fresh
        // circuits until the scanner catches up, then give up.
        final int SUB_MAX_ATTEMPTS = 15;
        final long SUB_RETRY_MS = 8_000;
        String resp = null;
        String code = null;
        for (int attempt = 1; attempt <= SUB_MAX_ATTEMPTS; attempt++) {
            try {
                // Fresh isolated circuit so /subscribe can't be correlated with the broadcast.
                resp = Native.httpPostIsolated(url, body);
                code = jsonStringField(resp, "code");
                if (code != null && !code.isBlank()) {
                    break;
                }
            } catch (Throwable e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean notYet = msg.contains("no confirmed payment") || msg.contains("404");
                if (!notYet || attempt == SUB_MAX_ATTEMPTS) {
                    throw new PerseverusException("scanner /subscribe failed: " + msg);
                }
                log.info("Perseverus BTC subscribe: scanner not caught up yet (attempt {}/{}), retrying…",
                        attempt, SUB_MAX_ATTEMPTS);
            }
            if (attempt < SUB_MAX_ATTEMPTS) {
                try { Thread.sleep(SUB_RETRY_MS); } catch (InterruptedException ignored) {}
            }
        }
        if (code == null || code.isBlank()) {
            throw new PerseverusException("scanner /subscribe returned no code: "
                    + (resp == null ? "(null)" : resp));
        }
        // The code authorizes a specific token count; the server rejects a
        // mismatch. Use the authorized count from the response, not the guess.
        int authorized = jsonIntField(resp, "tokens");
        int effectivePackSize = authorized > 0 ? authorized : packSize;
        log.info("Perseverus BTC subscription: got issuance code, redeeming pack of {}", effectivePackSize);
        return issuance.redeemBtc(effectivePackSize, expirationMonth, code);
    }

    /** Minimal extractor for {@code "field":<int>} from a flat JSON object. */
    private static int jsonIntField(String json, String field) {
        if (json == null) return -1;
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) return -1;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return -1;
        int j = colon + 1;
        while (j < json.length() && !Character.isDigit(json.charAt(j)) && json.charAt(j) != '-') j++;
        int k = j;
        if (k < json.length() && json.charAt(k) == '-') k++;
        while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
        if (k > j) {
            try { return Integer.parseInt(json.substring(j, k)); } catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    /** Minimal extractor for {@code "field":"value"} from a flat JSON object. */
    private static String jsonStringField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** Issue a pack with a default expiration 12 months from now. */
    public IssuedPack issuePack(int packSize) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= ISSUE_MAX_RETRIES; attempt++) {
            try {
                return issuance.issuePack(packSize);
            } catch (Exception e) {
                lastError = e;
                if (attempt < ISSUE_MAX_RETRIES) {
                    try { Thread.sleep(ISSUE_RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw new PerseverusException("issuePack failed after " + ISSUE_MAX_RETRIES + " attempts: " + lastError.getMessage());
    }

    /** Re-publish the Pedersen commitment for a previously issued pack.
     *  Call this for every persisted pack after reconnecting to the
     *  server so the bulletin board has the commitments and the tokens
     *  are spendable. */
    public void republishCommitment(IssuedPack pack) {
        issuance.republishCommitment(pack);
    }

    /** Execute a spend. Requires {@link #bootstrap()} to have run. */
    public byte[] spend(IssuedPack pack, int spendIdx, byte[] input) {
        if (spend == null) {
            throw new IllegalStateException(
                    "PerseverusService.spend called before bootstrap(); " +
                            "call bootstrap() once after open() before any spend");
        }
        return spend.spend(pack, spendIdx, input);
    }

    /**
     * Batch spend: generates all Groth16 proofs locally, fetches board
     * leaves once, and sends a single POST to
     * {@code /oprf/evaluate/batch}. Requires {@link #bootstrap()}.
     *
     * @return array of compressed γ blobs; null entries indicate
     *         individual failures (remaining entries are still valid)
     */
    public byte[][] spendBatch(IssuedPack[] packs, int[] spendIdxs, byte[][] inputs) {
        if (spend == null) {
            throw new IllegalStateException(
                    "PerseverusService.spendBatch called before bootstrap(); " +
                            "call bootstrap() once after open() before any spend");
        }
        return spend.spendBatch(packs, spendIdxs, inputs);
    }

    /** Closes both sub-clients. Idempotent. */
    @Override
    public synchronized void close() {
        RuntimeException suppressed = null;
        if (spend != null) {
            try { spend.close(); } catch (RuntimeException e) { suppressed = e; }
            spend = null;
        }
        if (issuance != null) {
            try {
                issuance.close();
            } catch (RuntimeException e) {
                if (suppressed == null) {
                    suppressed = e;
                } else {
                    suppressed.addSuppressed(e);
                }
            }
            issuance = null;
        }
        if (suppressed != null) {
            throw suppressed;
        }
    }

    /** Per-proof Groth16 generation timings (milliseconds) from the
     *  most recent {@link #spendBatch} call. Call immediately after
     *  spendBatch to retrieve actual per-proof times. */
    public static long[] lastBatchProofTimingsMs() {
        return SpendClient.lastBatchProofTimingsMs();
    }

    /** Native library version string — handy for startup logs and
     *  for the Privacy tab to display alongside the server URL. */
    public static String nativeVersion() {
        return Native.version();
    }

    /** HTTP GET routed through the configured native transport
     *  (Tor / OHTTP / Direct). Returns the response body as a string.
     *  Throws {@link UnsatisfiedLinkError} if the native library
     *  doesn't expose {@code httpGet}. */
    public static String nativeHttpGet(String url) {
        return Native.httpGet(url);
    }
}
