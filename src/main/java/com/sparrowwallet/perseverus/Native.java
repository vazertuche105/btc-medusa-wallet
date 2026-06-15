package com.sparrowwallet.perseverus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Raw JNI entry points into the {@code perseverus-client-native} Rust
 * crate. Method signatures here mirror the {@code Java_*} symbols
 * exported from {@code client-native/src/jni_surface.rs} exactly — if
 * either side changes, both must.
 *
 * <p>Callers should <em>not</em> use this class directly: it returns
 * raw handles (opaque {@code long} pointers into Rust-owned memory)
 * that are trivial to leak. Use {@link IssuanceClient},
 * {@link SpendClient}, or {@link PerseverusService} instead — they own
 * the handles as {@link AutoCloseable} and free them deterministically.
 *
 * <p>On failure, the Rust side throws
 * {@link PerseverusException} via {@code JNIEnv::throw_new} rather
 * than returning an error code, so every native call either succeeds
 * or propagates a checked exception.
 *
 * <h2>Library loading</h2>
 *
 * The static initialiser tries, in order:
 * <ol>
 *   <li>the absolute path in system property
 *       {@code perseverus.library.path} — useful for dev workflows
 *       where the {@code cdylib} lives under
 *       {@code perseverus/target/release/};</li>
 *   <li>{@link System#loadLibrary(String)} with the platform library
 *       name ({@code perseverus_client_native}) — works when the
 *       shared object is on {@code java.library.path};</li>
 *   <li>unpack a bundled copy from the classpath resource
 *       {@code /perseverus-natives/{os}-{arch}/{libname}} to a temp
 *       file and {@link System#load(String)} it.</li>
 * </ol>
 *
 * If none of those succeed, class initialisation fails with a clear
 * message — we'd rather fail loud at startup than segfault on the
 * first spend.
 */
final class Native {
    private static final Logger log = LoggerFactory.getLogger(Native.class);

    /** System property clients can set to a concrete {@code .dylib/.so/.dll} path. */
    static final String LIB_PATH_PROP = "perseverus.library.path";

    private static final String LIB_NAME = "perseverus_client_native";

    static {
        loadNativeLibrary();
    }

    private Native() {
    }

    private static void loadNativeLibrary() {
        // 1. Explicit override.
        String override = System.getProperty(LIB_PATH_PROP);
        if (override != null && !override.isEmpty()) {
            log.info("Loading Perseverus native library from {}={}", LIB_PATH_PROP, override);
            System.load(override);
            return;
        }

        // 2. java.library.path — System.loadLibrary prepends/appends
        //    the platform prefix/suffix for us.
        try {
            System.loadLibrary(LIB_NAME);
            log.info("Loaded Perseverus native library '{}' from java.library.path", LIB_NAME);
            return;
        } catch (UnsatisfiedLinkError e) {
            log.debug("{} not on java.library.path ({}) — trying classpath resources",
                    LIB_NAME, e.getMessage());
        }

        // 3. Unpack from classpath resources. Try multiple resource
        //    layouts: the Sparrow convention (/native/{os}/{arch}/),
        //    and the perseverus-natives convention.
        String[] candidates = {
            resolveNativeResourcePath(),   // /native/osx/aarch64/...
            resolveResourcePath(),          // /perseverus-natives/macos-aarch64/...
        };
        String resource = null;
        for (String candidate : candidates) {
            if (Native.class.getResource(candidate) != null) {
                resource = candidate;
                break;
            }
            log.debug("Native lib not at classpath resource {}", candidate);
        }
        if (resource == null) {
            throw new UnsatisfiedLinkError(
                    "Perseverus native library not found at classpath resources "
                            + java.util.Arrays.toString(candidates)
                            + " and not on java.library.path; "
                            + "set -D" + LIB_PATH_PROP + "=/path/to/lib"
                            + mappedLibName() + " for dev builds.");
        }
        try (InputStream in = Native.class.getResourceAsStream(resource)) {
            Path tmp = Files.createTempFile("perseverus-", "-" + mappedLibName());
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            log.info("Unpacked Perseverus native library from {} to {}", resource, tmp);
            System.load(tmp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UnsatisfiedLinkError(
                    "Failed to unpack Perseverus native library from " + resource + ": " + e);
        }
    }

    private static String resolveResourcePath() {
        String os = osToken();
        String arch = archToken();
        return "/perseverus-natives/" + os + "-" + arch + "/" + mappedLibName();
    }

    /** Sparrow convention: /native/{os}/{arch}/{libname} where os is
     *  "osx" for macOS, "linux", or "windows". */
    private static String resolveNativeResourcePath() {
        String os = sparrowOsToken();
        String arch = archToken();
        return "/native/" + os + "/" + arch + "/" + mappedLibName();
    }

    private static String sparrowOsToken() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        throw new UnsatisfiedLinkError("Unsupported OS: " + os);
    }

    private static String osToken() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        throw new UnsatisfiedLinkError("Unsupported OS for Perseverus native: " + os);
    }

    private static String archToken() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        if (arch.equals("x86_64") || arch.equals("amd64")) return "x86_64";
        throw new UnsatisfiedLinkError("Unsupported CPU arch for Perseverus native: " + arch);
    }

    private static String mappedLibName() {
        // System.mapLibraryName gives us e.g. "libperseverus_client_native.dylib"
        // on macOS, which is exactly what the cdylib spits out.
        return System.mapLibraryName(LIB_NAME);
    }

    // ─────────────────────────────────────────────────────────────────
    // Smoke test
    // ─────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────
    // UTXO privacy query (filter + OPRF flow)
    // ─────────────────────────────────────────────────────────────────

    /** Run the full 7-step private query for a single UTXO.
     *  Returns the KYC tag as a human-readable string:
     *  "Clean", "Coinbase", "CoinJoin", "Unknown", or
     *  "KycExchange(\"<name>\")". */
    static native String queryUtxo(String serverUrl, byte[] txid, int vout,
                                   int blockHeight, int numDecoys, int chainTip);

    /**
     * Authenticated variant of {@link #queryUtxo} that binds the query
     * to a previously obtained OPRF evaluation (gamma from a token
     * spend).  The server verifies that the gamma corresponds to a valid
     * spend before returning the KYC tag, closing the loop between
     * credential issuance and privacy-preserving lookups.
     *
     * <p>If the Rust native library does not yet export this symbol,
     * callers should catch {@link UnsatisfiedLinkError} and fall back to
     * the unauthenticated {@link #queryUtxo}.
     *
     * @param gamma compressed G1 affine blob returned by
     *              {@link #spendExecute}, or {@code null} to behave like
     *              the unauthenticated variant
     */
    static native String queryUtxoAuthenticated(String serverUrl, byte[] txid,
                                                int vout, int blockHeight,
                                                int numDecoys, int chainTip,
                                                byte[] gamma);

    // ─────────────────────────────────────────────────────────────────
    // Smoke test
    // ─────────────────────────────────────────────────────────────────

    /** Returns the Rust crate version string. Cheap, no-arg — useful
     *  for startup probes to confirm the cdylib loaded. */
    static native String version();

    /** Returns the hex-encoded compressed BLS12-377 G1 generator point.
     *  Test-only — gives Java-side tests a known-valid pubkey for
     *  lifecycle testing without hardcoding an ark-version-dependent
     *  constant. */
    static native String testG1GeneratorHex();

    // ─────────────────────────────────────────────────────────────────
    // Transport configuration
    // ─────────────────────────────────────────────────────────────────

    /**
     * Configure the privacy transport mode for all subsequent HTTP calls.
     * Must be called before any issuance/spend/query operations.
     *
     * @param mode one of "DIRECT", "OHTTP", "TOR", or "AUTO".
     *             AUTO tries Tor first, falls back to OHTTP if Tor is
     *             unavailable, then Direct as a last resort.
     * @param ohttpRelayUrl the OHTTP relay URL (used when mode is "OHTTP"
     *                      or "AUTO"); may be null for other modes
     */
    static native void configureTransport(String mode, String ohttpRelayUrl);

    /**
     * Perform an HTTP GET request through the currently configured
     * transport (Tor, OHTTP, or Direct). Returns the response body as
     * a UTF-8 string, or throws {@link PerseverusException} on failure.
     *
     * <p>Used by the sign-up wizard to privately fetch the BTC price
     * from our server when the public CoinGecko API is unavailable.
     * Routing through Tor ensures the user's IP is never revealed to
     * the BTC Medusa server during the price query.
     *
     * @param url the full URL to fetch (e.g. "https://api.btcmedusa.com/price.json")
     * @return the response body as a string
     */
    static native String httpGet(String url);

    /**
     * Perform an HTTP POST with a JSON body through the currently
     * configured transport (Direct, Tor SOCKS5, or OHTTP) and return
     * the response body as a UTF-8 string.
     *
     * <p>Used by the Stripe payment flow to call {@code /stripe/checkout},
     * {@code /stripe/redeem}, etc. on the Perseverus server. Routing
     * through Tor ensures the user's IP is never revealed during payment.
     *
     * @param url      the full URL to POST to
     * @param jsonBody the JSON request body
     * @return the response body as a string
     */
    static native String httpPost(String url, String jsonBody);

    /** Like {@link #httpPost(String, String)} but over a fresh, isolated Tor
     *  circuit. Used for the BTC proof-of-payment {@code /subscribe} call so it
     *  can't be correlated with the payment broadcast circuit. */
    static native String httpPostIsolated(String url, String jsonBody);

    // ─────────────────────────────────────────────────────────────────
    // IssuanceClient surface
    // ─────────────────────────────────────────────────────────────────

    /** Allocate an {@code IssuanceClient} bound to {@code baseUrl} and
     *  {@code serverPubkeyG1Hex}. Returns an opaque handle — treat as
     *  a pointer, free via {@link #issuanceDestroy(long)}. */
    static native long issuanceCreate(String baseUrl, String serverPubkeyG1Hex);

    /** Free an {@code IssuanceClient} handle. No-op on {@code 0}.
     *  Double-free is undefined behaviour — the Java wrappers guard
     *  against it with a single-shot close flag. */
    static native void issuanceDestroy(long handle);

    /** Issue, finalise, and publish commitment to bulletin board.
     *  Returns the opaque blob the spend side consumes; format
     *  documented in {@code jni_surface.rs}. Tokens are immediately
     *  spendable after this call. */
    static native byte[] issuanceIssuePack(long handle, int packSize, int expirationMonth);

    /** Issue a token pack via the Stripe {@code /stripe/redeem} endpoint.
     *  Sends blinded tokens + the one-time redemption code in a single
     *  HTTP request. The server validates the code, signs the tokens,
     *  and deletes the code — severing the link between payment and tokens.
     *
     *  <p>After issuance, the attribution entry is published to the board
     *  via an isolated Tor circuit (same privacy guarantee as
     *  {@link #issuanceIssuePack}).
     *
     *  @param handle          IssuanceClient handle from {@link #issuanceCreate}
     *  @param packSize        number of tokens (typically 100 for CC)
     *  @param expirationMonth YYYYMM format (e.g. 202612)
     *  @param redemptionCode  one-time code from Stripe webhook
     *  @return opaque pack blob, same format as {@link #issuanceIssuePack} */
    static native byte[] issuanceRedeemPack(long handle, int packSize, int expirationMonth, String redemptionCode);

    /** Redeem a BTC silent-payment proof-of-payment code ({@code psbtc1.…}) for
     *  a token pack via the server's {@code /btc/redeem} endpoint.
     *  @param handle          IssuanceClient handle from {@link #issuanceCreate}
     *  @param packSize        number of tokens (must match the code's authorized count)
     *  @param expirationMonth YYYYMM format
     *  @param code            one-time code from the SP scanner's /subscribe
     *  @return opaque pack blob, same format as {@link #issuanceIssuePack} */
    static native byte[] issuanceRedeemBtc(long handle, int packSize, int expirationMonth, String code);

    /** Claim a Lightning-paid token pack via {@code /ln/claim}, using the secret
     *  nonce from {@code /ln/invoice} as the bearer credential.
     *  @return opaque pack blob, same format as {@link #issuanceIssuePack} */
    static native byte[] issuanceClaimLn(long handle, int packSize, int expirationMonth, String nonce);

    /** Yearly plan: redeem ONE code for 13 monthly packs in a single batch
     *  request. {@code baseMonth} (YYYYMM, from the server /epoch clock) anchors
     *  the schedule — a bridge pack (start=M, expiration=M+1) plus 12 single-month
     *  packs (M+2..M+13). Returns one opaque pack blob per pack (same format as
     *  {@link #issuanceRedeemPack}); persist each.
     *  @return array of pack blobs (13 entries) */
    static native byte[][] issuanceRedeemYearly(long handle, int packSize, int baseMonth, String redemptionCode);

    /** Re-publish the attribution entry from an existing pack blob to
     *  the server's bulletin board. Needed after a server restart wipes
     *  the in-memory board — without this, persisted packs are
     *  unspendable. Throws {@link PerseverusException} on failure. */
    static native void issuanceRepublish(long handle, byte[] issuedPackBytes);

    // ─────────────────────────────────────────────────────────────────
    // SpendClient surface
    // ─────────────────────────────────────────────────────────────────

    /** Bootstrap a {@code SpendClient} by fetching the Groth16 proving
     *  key and G₂ credential pubkey from {@code baseUrl}. If {@code cachePath}
     *  is non-null the PK is loaded from (or saved to) that local file,
     *  avoiding a multi-MB download over Tor on every launch. */
    static native long spendBootstrap(String baseUrl, String serverPubkeyG1Hex, String cachePath);

    /** Free a {@code SpendClient} handle. No-op on {@code 0}. */
    static native void spendDestroy(long handle);

    /** Execute a spend. Produces γ = v·P_i (= σ_i) as a compressed G1
     *  affine blob. Input bytes are the OPRF preimage (e.g. the
     *  outpoint encoding). currentMonth is in YYYYMM format (e.g.
     *  202604); the circuit enforces expiration_month ≥ current_month. */
    static native byte[] spendExecute(long handle, byte[] issuedPack, int spendIdx,
                                      byte[] input, int currentMonth);

    /**
     * Batch variant of {@link #spendExecute}. Generates all Groth16
     * proofs locally, fetches board leaves once, and sends a single
     * POST to {@code /oprf/evaluate/batch}. Each entry in the returned
     * {@code byte[][]} is a compressed γ blob, or {@code null} if that
     * individual spend failed (remaining entries are still valid).
     *
     * <p>This amortises Tor round-trip latency from N calls to 1,
     * cutting scan time for N UTXOs from ~N×1.1s to ~0.25N+1.1s.
     *
     * @param handle       SpendClient handle from {@link #spendBootstrap}
     * @param issuedPacks  array of opaque pack blobs (one per spend)
     * @param spendIndices token index within each pack
     * @param inputs       OPRF preimage bytes for each spend
     * @param currentMonth current YYYYMM for expiration check
     * @return array of compressed γ blobs (null entries for failures)
     */
    static native byte[][] spendBatchExecute(long handle, byte[][] issuedPacks,
                                             int[] spendIndices, byte[][] inputs,
                                             int currentMonth);

    /**
     * Per-proof Groth16 generation timings (milliseconds) from the most
     * recent {@link #spendBatchExecute} call. One entry per proof, same
     * order as the spend items. Call immediately after spendBatchExecute.
     */
    static native long[] spendBatchProofTimingsMs();

    /**
     * Pre-fetch filters for the given block heights in parallel. Each
     * height triggers a filter existence check → build (if missing) →
     * download, all concurrently via Rust threads. Call this alongside
     * the batch spend so filters are warm by the time queries start.
     *
     * <p>Errors for individual heights are logged but do not fail the
     * call — the query phase will retry any that failed.
     *
     * @param serverUrl    Perseverus server base URL
     * @param blockHeights array of block heights to prefetch
     */
    static native void prefetchFilters(String serverUrl, int[] blockHeights);

    /**
     * Prefetch filters for the given block heights AND their Laplace decoy
     * blocks. Decoy heights are generated in Rust using the same algorithm
     * as the query phase, so the in-memory cache will have everything ready.
     */
    static native void prefetchFiltersWithDecoys(String serverUrl, int[] blockHeights, int numDecoys, int chainTip, double scale);

    /**
     * Poll filter download progress. Returns a 2-element long array:
     * [downloadedBytes, totalBytes]. Both are 0 before a prefetch starts.
     * Java calls this from a timer to drive the progress bar.
     */
    static native long[] prefetchProgress();
}
