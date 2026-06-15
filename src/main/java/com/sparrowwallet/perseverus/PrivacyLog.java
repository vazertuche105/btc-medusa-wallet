package com.sparrowwallet.perseverus;

import com.sparrowwallet.sparrow.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Dedicated, human-readable log for all Perseverus privacy operations.
 *
 * <p>Writes to {@code perseverus.log} inside the Sparrow data directory
 * (e.g. {@code ~/.sparrow/perseverus.log} on Linux/macOS). Every line
 * is timestamped with millisecond precision so proof timings can be
 * measured by visual inspection.
 *
 * <p>Thread-safe — all writes are synchronized on the singleton.
 */
public final class PrivacyLog {

    private static final Logger log = LoggerFactory.getLogger(PrivacyLog.class);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    // INSTANCE must be declared AFTER TS_FMT — the constructor calls
    // writeLine() which uses TS_FMT.format().
    private static final PrivacyLog INSTANCE = new PrivacyLog();

    private PrintWriter writer;
    private File logFile;

    private PrivacyLog() {
        try {
            logFile = new File(Storage.getSparrowDir(), "perseverus.log");
            writer = new PrintWriter(new FileWriter(logFile, true), true);
            writer.println();
            writer.println("─────────────────────────────────────────────────────────────────");
            writer.println("─────────────────────────────────────────────────────────────────");
            writer.println();
            writeLine("===== Perseverus log opened =====");
        } catch (Exception e) {
            log.warn("Could not open perseverus.log: {}", e.getMessage());
        }
    }

    public static PrivacyLog get() {
        return INSTANCE;
    }

    /** Return the absolute path of the log file (for display in the UI). */
    public String path() {
        return logFile != null ? logFile.getAbsolutePath() : "(unavailable)";
    }

    // ── High-level operations ──────────────────────────────────────

    public synchronized void connect(String serverUrl, String version, String transport) {
        writeLine("CONNECT  server=%s  version=%s  transport=%s", serverUrl, version, transport);
    }

    public synchronized void disconnect(String reason) {
        writeLine("DISCONNECT  reason=%s", reason);
    }

    public synchronized void issueStart(int size) {
        writeLine("ISSUE    start    size=%d", size);
    }

    public synchronized void issueComplete(int size, long elapsedMs) {
        writeLine("ISSUE    done     size=%d  elapsed=%dms", size, elapsedMs);
    }

    public synchronized void issueFailed(int size, String error) {
        writeLine("ISSUE    FAILED   size=%d  error=%s", size, error);
    }

    public synchronized void bootstrapStart() {
        writeLine("BOOTSTRAP start");
    }

    public synchronized void bootstrapComplete(long elapsedMs) {
        writeLine("BOOTSTRAP done     elapsed=%dms", elapsedMs);
    }

    public synchronized void bootstrapFailed(String error) {
        writeLine("BOOTSTRAP FAILED   error=%s", error);
    }

    public synchronized void spendStart(int tokenIdx, String outpointShort) {
        writeLine("SPEND    start    token=#%d  outpoint=%s", tokenIdx, outpointShort);
    }

    public synchronized void spendComplete(int tokenIdx, int gammaLen, long elapsedMs) {
        writeLine("SPEND    done     token=#%d  gammaLen=%d  elapsed=%dms  (Groth16 proof inside)",
                tokenIdx, gammaLen, elapsedMs);
    }

    public synchronized void spendFailed(int tokenIdx, String error) {
        writeLine("SPEND    FAILED   token=#%d  error=%s", tokenIdx, error);
    }

    public synchronized void scanStart(int utxoCount, int tokensAvailable,
                                       boolean authenticated, int numDecoys, int chainTip) {
        writeLine("SCAN     start    utxos=%d  tokens=%d  authenticated=%s  decoys=%d  chainTip=%d",
                utxoCount, tokensAvailable, authenticated, numDecoys, chainTip);
    }

    public synchronized void scanQuery(int index, int total, String txidShort, int vout,
                                        int blockHeight, boolean hasGamma) {
        writeLine("SCAN     query    [%d/%d]  %s:%d  block=%d  gamma=%s",
                index, total, txidShort, vout, blockHeight, hasGamma ? "yes" : "no");
    }

    public synchronized void scanQueryResult(int index, int total, String txidShort, int vout,
                                              String tagType, String status, long elapsedMs) {
        writeLine("SCAN     result   [%d/%d]  %s:%d  tag=%s  status=%s  elapsed=%dms",
                index, total, txidShort, vout, tagType, status, elapsedMs);
    }

    public synchronized void scanComplete(int total, int authenticated, int score, long elapsedMs) {
        writeLine("SCAN     done     total=%d  authenticated=%d  score=%d  elapsed=%dms",
                total, authenticated, score, elapsedMs);
    }

    // ── Filter download ──────────────────────────────────────────

    public synchronized void filterDownloadStart(int filterCount, int decoys) {
        writeLine("FILTER   start    filters=%d  decoys=%d", filterCount, decoys);
    }

    public synchronized void filterDownloadProgress(long downloadedBytes, long totalBytes,
                                                      int etaSeconds) {
        String dlMB = String.format("%.1f", downloadedBytes / (1024.0 * 1024.0));
        String totalMB = String.format("%.1f", totalBytes / (1024.0 * 1024.0));
        String eta = etaSeconds > 0 ? String.format("  eta=%ds", etaSeconds) : "";
        writeLine("FILTER   progress %s/%s MB  (%.0f%%)%s",
                dlMB, totalMB, (double) downloadedBytes / totalBytes * 100, eta);
    }

    public synchronized void filterDownloadComplete(long totalBytes, long elapsedMs) {
        String totalMB = String.format("%.1f", totalBytes / (1024.0 * 1024.0));
        writeLine("FILTER   done     %s MB  elapsed=%dms", totalMB, elapsedMs);
    }

    // ── Pack persistence ──────────────────────────────────────────

    public synchronized void packPersisted(int packCount, int totalTokens, int totalSpent) {
        writeLine("PERSIST  packs=%d  totalTokens=%d  spent=%d", packCount, totalTokens, totalSpent);
    }

    public synchronized void packRestored(int packCount, int totalTokens, int totalSpent) {
        writeLine("RESTORE  packs=%d  totalTokens=%d  spent=%d", packCount, totalTokens, totalSpent);
    }

    // ── Commitment republish ──────────────────────────────────────

    public synchronized void republishStart(int packCount) {
        writeLine("REPUBLISH start   packs=%d", packCount);
    }

    public synchronized void republishComplete(int success, int failed, long elapsedMs) {
        writeLine("REPUBLISH done    success=%d  failed=%d  elapsed=%dms", success, failed, elapsedMs);
    }

    // ── Payment ───────────────────────────────────────────────────

    public synchronized void paymentStripeStart(String plan) {
        writeLine("PAYMENT  stripe   plan=%s  status=checkout_started", plan);
    }

    public synchronized void paymentStripeConfirmed(String plan) {
        writeLine("PAYMENT  stripe   plan=%s  status=confirmed", plan);
    }

    public synchronized void paymentStripeRedeemed(int tokenCount) {
        writeLine("PAYMENT  stripe   status=redeemed  tokens=%d", tokenCount);
    }

    public synchronized void paymentStripeFailed(String error) {
        writeLine("PAYMENT  stripe   status=FAILED  error=%s", error);
    }

    public synchronized void paymentBtcStart(String plan, long amountSats) {
        writeLine("PAYMENT  bitcoin  plan=%s  amount=%d sats  status=started", plan, amountSats);
    }

    public synchronized void paymentBtcBroadcast(String txid, long amountSats) {
        writeLine("PAYMENT  bitcoin  txid=%s  amount=%d sats  status=broadcast", txid, amountSats);
    }

    // ── Batch spend ───────────────────────────────────────────────

    public synchronized void batchSpendStart(int batchSize, int totalUtxos) {
        writeLine("BATCH    start    batchSize=%d  totalUtxos=%d", batchSize, totalUtxos);
    }

    public synchronized void batchSpendComplete(int batchSize, int succeeded, int failed, long elapsedMs) {
        writeLine("BATCH    done     batchSize=%d  succeeded=%d  failed=%d  elapsed=%dms",
                batchSize, succeeded, failed, elapsedMs);
    }

    public synchronized void batchSpendFallback(String reason) {
        writeLine("BATCH    FALLBACK reason=%s", reason);
    }

    // ── General ───────────────────────────────────────────────────

    public synchronized void clearHistory() {
        writeLine("CLEAR    packs history cleared");
    }

    public synchronized void info(String message) {
        writeLine("INFO     %s", message);
    }

    public synchronized void warn(String message) {
        writeLine("WARN     %s", message);
    }

    // ── Internal ───────────────────────────────────────────────────

    private void writeLine(String fmt, Object... args) {
        if (writer == null) return;
        String ts = TS_FMT.format(Instant.now());
        String msg = args.length > 0 ? String.format(fmt, args) : fmt;
        writer.println(ts + "  " + msg);
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null) return "(null)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Clear the log file contents and start fresh.
     * Re-opens the writer after truncating.
     */
    public synchronized void clearLog() {
        if (logFile == null) return;
        try {
            if (writer != null) {
                writer.close();
            }
            // Truncate by opening without append
            writer = new PrintWriter(new FileWriter(logFile, false), true);
            writeLine("===== Perseverus log cleared =====");
        } catch (IOException e) {
            log.warn("Could not clear perseverus.log: {}", e.getMessage());
        }
    }

    /** Flush and close the underlying writer (called on shutdown). */
    public synchronized void close() {
        if (writer != null) {
            writeLine("===== Perseverus log closed =====");
            writer.close();
            writer = null;
        }
    }
}
