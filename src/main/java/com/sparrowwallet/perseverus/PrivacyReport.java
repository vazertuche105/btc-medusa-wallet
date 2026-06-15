package com.sparrowwallet.perseverus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Display model for a per-UTXO privacy assessment, rendered from a real
 * am-i-exposed scan result (see the bundled {@code heuristics_demo.json}).
 *
 * <p>Purely a presentation structure: a letter grade, a 0–100 score, a
 * primary recommendation (headline + detail + urgency), the list of
 * heuristic {@link Finding}s, and a transaction summary. It computes none of
 * these — the scan engine produces them and demo mode replays real samples.
 */
public final class PrivacyReport {

    /** Severity buckets, in render/sort order (critical first, good last). */
    public enum Severity {
        CRITICAL("Critical", "#c0392b"),
        HIGH("High",         "#e67e22"),
        MEDIUM("Medium",     "#d4a017"),
        LOW("Low",           "#7f8c8d"),
        INFO("Info",         "#5a7da0"),
        GOOD("Good",         "#27894a");

        private final String label;
        private final String color;

        Severity(String label, String color) {
            this.label = label;
            this.color = color;
        }

        public String getLabel() { return label; }
        public String getColor() { return color; }

        /** Parse an am-i-exposed severity string; unknown → INFO. */
        public static Severity from(String s) {
            if (s == null) return INFO;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "critical" -> CRITICAL;
                case "high" -> HIGH;
                case "medium" -> MEDIUM;
                case "low" -> LOW;
                case "good" -> GOOD;
                default -> INFO;
            };
        }
    }

    /** One heuristic finding from the scan engine. */
    public record Finding(
            String id,
            String name,
            String confidence,
            Severity severity,
            String description,
            String recommendation) {
    }

    /** Lightweight transaction context shown in the dashboard summary. */
    public record TxSummary(
            String txid,
            int vout,
            long valueSats,
            int blockHeight,
            String txType,
            int numInputs,
            int numOutputs,
            long feeSats) {
    }

    private final String grade;      // "A+", "A", "B", "C", "D", "F"
    private final int score;         // 0–100
    private final String verdict;    // primary recommendation headline
    private final String urgency;    // immediate | soon | when-convenient | positive
    private final String recommendationDetail;
    private final String profile;    // tx type, e.g. "peel-chain"
    private final List<Finding> findings;
    private final TxSummary tx;
    // True for a UTXO that the protocol intentionally does NOT grade (e.g. a
    // sub-2,000-sat transaction, a dust-only output, or a data carrier such as
    // an OP_RETURN / ordinal / runes / Counterparty tx). Such a report carries
    // no letter grade or score — only an explanation of the exclusion rules —
    // and is left out of the wallet's overall privacy average.
    private final boolean ungraded;

    public PrivacyReport(String grade, int score, String verdict, String urgency,
                         String recommendationDetail, String profile,
                         List<Finding> findings, TxSummary tx) {
        this(grade, score, verdict, urgency, recommendationDetail, profile, findings, tx, false);
    }

    private PrivacyReport(String grade, int score, String verdict, String urgency,
                          String recommendationDetail, String profile,
                          List<Finding> findings, TxSummary tx, boolean ungraded) {
        this.grade = grade;
        this.score = score;
        this.verdict = verdict;
        this.urgency = urgency;
        this.recommendationDetail = recommendationDetail;
        this.profile = profile;
        this.findings = List.copyOf(findings);
        this.tx = tx;
        this.ungraded = ungraded;
    }

    /**
     * Build the report shown for a UTXO the protocol does not grade. The
     * transaction was deliberately excluded from the filters by the build-side
     * skip rules, so there is no KYC assessment to display — instead the report
     * lists, as informational findings, the exact rules that cause a
     * transaction to be skipped. This is <em>not</em> a privacy flag; an
     * ungraded UTXO simply isn't assessed and does not affect the wallet grade.
     */
    public static PrivacyReport ungraded(TxSummary tx) {
        List<Finding> rules = List.of(
            new Finding("skip-dust-total", "Transaction value under 2,000 sats", "rule",
                Severity.INFO,
                "The whole transaction that created this output sends less than 2,000 satoshis "
                + "in total. Very-low-value transactions are not assessed.",
                "No action needed — this is not a privacy risk."),
            new Finding("skip-dust-output", "Dust outputs only", "rule",
                Severity.INFO,
                "Every output in the transaction is below the 546-sat network dust limit.",
                "No action needed."),
            new Finding("skip-op-return", "Data carrier (OP_RETURN only)", "rule",
                Severity.INFO,
                "The transaction carries only embedded data and no spendable payment output.",
                "No action needed."),
            new Finding("skip-ordinal", "Ordinal inscription", "rule",
                Severity.INFO,
                "The transaction is an ordinal inscription (witness-embedded content), not a "
                + "standard payment.",
                "No action needed."),
            new Finding("skip-runes", "Runes transfer", "rule",
                Severity.INFO,
                "The transaction is a Runes protocol message rather than a standard payment.",
                "No action needed."),
            new Finding("skip-asset", "Counterparty / Omni asset transfer", "rule",
                Severity.INFO,
                "The transaction is a Counterparty or Omni Layer asset transfer rather than a "
                + "standard bitcoin payment.",
                "No action needed.")
        );
        return new PrivacyReport(
            "—", -1,
            "No privacy grade for this UTXO",
            "", // neutral banner
            "This transaction is intentionally excluded from privacy grading. It is not a KYC "
            + "flag — it simply isn't assessed. A transaction is skipped when it matches any of "
            + "the rules below.",
            "ungraded",
            rules, tx, true);
    }

    public boolean isUngraded() { return ungraded; }

    public String getGrade() { return grade; }
    public int getScore() { return score; }
    public String getVerdict() { return verdict; }
    public String getUrgency() { return urgency; }
    public String getRecommendationDetail() { return recommendationDetail; }
    public String getProfile() { return profile; }
    public List<Finding> getFindings() { return findings; }
    public TxSummary getTx() { return tx; }

    /** CSS class for the grade badge, e.g. {@code grade-a}, {@code grade-f}. */
    public String getGradeStyleClass() {
        String g = grade == null ? "" : grade.toLowerCase(Locale.ROOT);
        if (g.startsWith("a")) return "grade-a";
        if (g.startsWith("b")) return "grade-b";
        if (g.startsWith("c")) return "grade-c";
        if (g.startsWith("d")) return "grade-d";
        return "grade-f";
    }

    /** Hex colour for the grade badge background, by score band. */
    public String getGradeColor() {
        if (ungraded) return "#7f8c8d"; // neutral grey — not graded, not a flag
        if (score >= 90) return "#27894a";
        if (score >= 80) return "#5a9e3a";
        if (score >= 70) return "#d4a017";
        if (score >= 60) return "#e67e22";
        return "#c0392b";
    }

    /** Hex colour for the primary-recommendation banner, by urgency. */
    public String getUrgencyColor() {
        String u = urgency == null ? "" : urgency.toLowerCase(Locale.ROOT);
        return switch (u) {
            case "immediate" -> "#c0392b";
            case "soon" -> "#e67e22";
            case "positive" -> "#27894a";
            default -> "#5a7da0"; // when-convenient / unknown
        };
    }

    /** Findings sorted by severity (critical → … → good). */
    public List<Finding> sortedFindings() {
        List<Finding> out = new ArrayList<>(findings);
        out.sort((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()));
        return out;
    }

    /** Count of findings by severity (for the severity ring). */
    public Map<Severity, Integer> severityCounts() {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Finding f : findings) {
            counts.merge(f.severity(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}
