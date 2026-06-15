package com.sparrowwallet.perseverus;

import java.util.Objects;

/**
 * Java-side owner of a Rust {@code perseverus_client_native::SpendClient}.
 *
 * <p>Bootstraps once by fetching the Groth16 proving key and G₂
 * credential pubkey from the server ({@code /circuit/pk} and
 * {@code /server/pubkey/g2}) — this is a heavy call, sometimes tens
 * of MB — and retains them in Rust-owned memory for the lifetime of
 * the handle. Callers should do this once per wallet session, not per
 * spend.
 *
 * <p>Not thread-safe; see {@link IssuanceClient} for the contract.
 */
public final class SpendClient implements AutoCloseable {
    private long handle;

    private SpendClient(long handle) {
        this.handle = handle;
    }

    /** Bootstrap against {@code baseUrl} with the server's G₁ pubkey.
     *  PK is fetched fresh every time (no caching). */
    public static SpendClient bootstrap(String baseUrl, String serverPubkeyG1Hex) {
        return bootstrap(baseUrl, serverPubkeyG1Hex, null);
    }

    /** Bootstrap with an optional local PK cache path. If {@code cachePath}
     *  is non-null, the proving key is loaded from disk on subsequent
     *  launches instead of downloading over Tor each time. */
    public static SpendClient bootstrap(String baseUrl, String serverPubkeyG1Hex, String cachePath) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(serverPubkeyG1Hex, "serverPubkeyG1Hex");
        long h = Native.spendBootstrap(baseUrl, serverPubkeyG1Hex, cachePath);
        if (h == 0) {
            throw new PerseverusException("SpendClient.bootstrap returned null handle");
        }
        return new SpendClient(h);
    }

    /**
     * Execute a spend against token {@code spendIdx} of {@code pack},
     * binding the OPRF query to {@code input}.
     *
     * @param pack the pack returned by {@link IssuanceClient#issuePack}
     * @param spendIdx zero-based token index within the pack; must be
     *                 in {@code [0, pack.packSize())}
     * @param input the OPRF preimage bytes (e.g. outpoint encoding)
     * @param currentMonth current calendar month in YYYYMM format
     *                     (e.g. 202604); the circuit enforces
     *                     expiration_month ≥ current_month
     * @return γ = v·P_i (= σ_i), a compressed G1 affine blob
     */
    public byte[] spend(IssuedPack pack, int spendIdx, byte[] input, int currentMonth) {
        checkOpen();
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(input, "input");
        if (spendIdx < 0 || spendIdx >= pack.packSize()) {
            throw new IndexOutOfBoundsException(
                    "spendIdx=" + spendIdx + " out of range [0," + pack.packSize() + ")");
        }
        return Native.spendExecute(handle, pack.rawBytes(), spendIdx, input, currentMonth);
    }

    /** Convenience: spend using the current calendar month. */
    public byte[] spend(IssuedPack pack, int spendIdx, byte[] input) {
        return spend(pack, spendIdx, input, currentMonth());
    }

    /**
     * Batch spend: generates all Groth16 proofs, fetches board leaves
     * once, and sends a single POST to {@code /oprf/evaluate/batch}.
     *
     * <p>Each element of the returned array is a compressed γ blob, or
     * {@code null} if that individual spend failed (remaining entries
     * are still valid).
     *
     * @param packs       pack for each spend (may repeat if multiple
     *                    tokens come from the same pack)
     * @param spendIdxs   token index within each pack
     * @param inputs      OPRF preimage bytes for each spend
     * @return array of compressed γ blobs; null entries indicate failure
     */
    public byte[][] spendBatch(IssuedPack[] packs, int[] spendIdxs, byte[][] inputs) {
        return spendBatch(packs, spendIdxs, inputs, currentMonth());
    }

    /**
     * Batch spend with explicit currentMonth.
     */
    public byte[][] spendBatch(IssuedPack[] packs, int[] spendIdxs,
                               byte[][] inputs, int currentMonth) {
        checkOpen();
        if (packs.length != spendIdxs.length || packs.length != inputs.length) {
            throw new IllegalArgumentException(
                    "array length mismatch: packs=" + packs.length
                            + " spendIdxs=" + spendIdxs.length
                            + " inputs=" + inputs.length);
        }
        byte[][] rawPacks = new byte[packs.length][];
        for (int i = 0; i < packs.length; i++) {
            rawPacks[i] = packs[i].rawBytes();
        }
        return Native.spendBatchExecute(handle, rawPacks, spendIdxs, inputs, currentMonth);
    }

    /**
     * Per-proof Groth16 generation timings (milliseconds) from the most
     * recent {@link #spendBatch} call. One entry per proof, same order
     * as the spend items. Call immediately after spendBatch.
     */
    public static long[] lastBatchProofTimingsMs() {
        return Native.spendBatchProofTimingsMs();
    }

    /**
     * Returns the current epoch month (YYYYMM) used by the spend circuit's
     * activation/expiry checks. Prefers the SERVER's clock (GET /epoch), which
     * supports a simulated test clock so expiry can be exercised on a
     * controllable timeline. Falls back to the local system month if the server
     * is unreachable. The request rides the configured transport (Tor).
     */
    public static int currentMonth() {
        java.time.LocalDate now = java.time.LocalDate.now();
        int local = now.getYear() * 100 + now.getMonthValue();
        try {
            String url = com.sparrowwallet.sparrow.io.Config.get().getPerseverusServerUrl();
            if (url != null && !url.isBlank()) {
                String json = Native.httpGet(url.replaceAll("/+$", "") + "/epoch");
                Integer m = parseMonth(json);
                if (m != null && m > 0) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
            // network/native error → fall back to local clock
        }
        return local;
    }

    /** Extract the integer value of {@code "month":<n>} from a JSON string. */
    private static Integer parseMonth(String json) {
        if (json == null) return null;
        int i = json.indexOf("\"month\"");
        if (i < 0) return null;
        int j = json.indexOf(':', i) + 1;
        while (j < json.length() && !Character.isDigit(json.charAt(j))) j++;
        int k = j;
        while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
        if (k > j) {
            try { return Integer.parseInt(json.substring(j, k)); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private void checkOpen() {
        if (handle == 0) {
            throw new IllegalStateException("SpendClient already closed");
        }
    }

    @Override
    public void close() {
        if (handle != 0) {
            long h = handle;
            handle = 0;
            Native.spendDestroy(h);
        }
    }
}
