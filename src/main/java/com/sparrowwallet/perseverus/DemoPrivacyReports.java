package com.sparrowwallet.perseverus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.perseverus.PrivacyReport.Finding;
import com.sparrowwallet.perseverus.PrivacyReport.Severity;
import com.sparrowwallet.perseverus.PrivacyReport.TxSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DEMO-mode source of {@link PrivacyReport}s, backed by 50 real am-i-exposed
 * scan results bundled as {@code heuristics_demo.json}.
 *
 * <p>Each sample carries the engine's real grade, score, transaction type,
 * primary recommendation, and full finding list (titles, severities,
 * descriptions, recommendations). On a demo scan each wallet UTXO is assigned
 * one sample deterministically (reshuffled per scan via the cycle seed), so
 * the dashboard shows genuine findings. No network calls are made.
 */
public final class DemoPrivacyReports {

    private static final Logger log = LoggerFactory.getLogger(DemoPrivacyReports.class);
    private static final String RESOURCE = "/com/sparrowwallet/perseverus/heuristics_demo.json";

    private DemoPrivacyReports() {}

    /** A pre-built real sample ready to attach to any UTXO. */
    private static final class Sample {
        final String grade;
        final int score;
        final String txType;
        final String urgency;
        final String headline;
        final String detail;
        final int inputs;
        final int outputs;
        final long fee;
        final List<Finding> findings;

        Sample(String grade, int score, String txType, String urgency, String headline,
               String detail, int inputs, int outputs, long fee, List<Finding> findings) {
            this.grade = grade;
            this.score = score;
            this.txType = txType;
            this.urgency = urgency;
            this.headline = headline;
            this.detail = detail;
            this.inputs = inputs;
            this.outputs = outputs;
            this.fee = fee;
            this.findings = findings;
        }
    }

    private static volatile List<Sample> pool;
    /** Weighted index into {@link #pool}: good grades repeated so the rare
     *  green/A+ samples surface far more often than their raw ~1/50 share. */
    private static volatile int[] weightedIndex;

    private static List<Sample> pool() {
        List<Sample> p = pool;
        if (p == null) {
            synchronized (DemoPrivacyReports.class) {
                p = pool;
                if (p == null) {
                    p = load();
                    pool = p;
                    weightedIndex = buildWeightedIndex(p);
                }
            }
        }
        return p;
    }

    /** Selection weight by grade — boosts the scarce positive grades. */
    private static int weightFor(String grade) {
        String g = grade == null ? "" : grade.toLowerCase(java.util.Locale.ROOT);
        if (g.startsWith("a") || g.startsWith("b")) return 14; // A+/A/B (green)
        if (g.startsWith("c")) return 4;
        if (g.startsWith("d")) return 2;
        return 1;                                              // F
    }

    private static int[] buildWeightedIndex(List<Sample> p) {
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) {
            int w = weightFor(p.get(i).grade);
            for (int k = 0; k < w; k++) idxs.add(i);
        }
        int[] arr = new int[idxs.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = idxs.get(i);
        return arr;
    }

    private static List<Sample> load() {
        List<Sample> samples = new ArrayList<>();
        try (InputStream in = DemoPrivacyReports.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("[perseverus] demo dataset not found: {}", RESOURCE);
                return samples;
            }
            JsonArray entries = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();

            for (JsonElement el : entries) {
                JsonObject o = el.getAsJsonObject();

                List<Finding> findings = new ArrayList<>();
                if (o.has("findings") && o.get("findings").isJsonArray()) {
                    for (JsonElement fe : o.getAsJsonArray("findings")) {
                        JsonObject fo = fe.getAsJsonObject();
                        findings.add(new Finding(
                                str(fo, "id", ""),
                                str(fo, "title", "Finding"),
                                str(fo, "confidence", ""),
                                Severity.from(str(fo, "severity", "info")),
                                str(fo, "description", ""),
                                str(fo, "recommendation", "")));
                    }
                }

                JsonObject ti = obj(o, "txInfo");
                JsonObject rec = obj(o, "recommendation");
                samples.add(new Sample(
                        str(o, "grade", "F"),
                        intv(o, "score", 0),
                        str(o, "txType", "transaction"),
                        rec != null ? str(rec, "urgency", "when-convenient") : "when-convenient",
                        rec != null ? str(rec, "headline", "Privacy findings") : "Privacy findings",
                        rec != null ? str(rec, "detail", "") : "",
                        ti != null ? intv(ti, "inputs", 0) : 0,
                        ti != null ? intv(ti, "outputs", 0) : 0,
                        ti != null ? longv(ti, "fee", 0) : 0,
                        findings));
            }
            log.info("[perseverus] loaded {} demo scan samples", samples.size());
        } catch (Exception ex) {
            log.error("[perseverus] failed to load demo dataset", ex);
        }
        return samples;
    }

    // ── Public API (signature unchanged for PrivacyController) ─────────────

    public static PrivacyReport forRow(String txid, int vout, long valueSats,
                                       int blockHeight, long cycleSeed) {
        List<Sample> p = pool();
        if (p.isEmpty()) {
            return new PrivacyReport("F", 20, "Privacy findings", "when-convenient", "",
                    "transaction", List.of(),
                    new TxSummary(txid, vout, valueSats, blockHeight, "transaction", 0, 0, 0));
        }
        int[] w = weightedIndex;
        String key = txid + ":" + vout + ":" + cycleSeed;
        int sel = (w != null && w.length > 0)
                ? w[Math.floorMod(key.hashCode(), w.length)]
                : Math.floorMod(key.hashCode(), p.size());
        Sample s = p.get(sel);
        return new PrivacyReport(
                s.grade, s.score, s.headline, s.urgency, s.detail, s.txType, s.findings,
                new TxSummary(txid, vout, valueSats, blockHeight, s.txType, s.inputs, s.outputs, s.fee));
    }

    // ── JSON helpers ───────────────────────────────────────────────────────

    private static String str(JsonObject o, String k, String def) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : def;
    }
    private static int intv(JsonObject o, String k, int def) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : def;
    }
    private static long longv(JsonObject o, String k, long def) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsLong() : def;
    }
    private static JsonObject obj(JsonObject o, String k) {
        return (o.has(k) && o.get(k).isJsonObject()) ? o.getAsJsonObject(k) : null;
    }
}
