package com.sparrowwallet.perseverus;

import com.sparrowwallet.perseverus.PrivacyReport.Finding;
import com.sparrowwallet.perseverus.PrivacyReport.Severity;
import com.sparrowwallet.perseverus.PrivacyReport.TxSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a full {@link PrivacyReport} (grade, score, primary recommendation,
 * finding cards, severity breakdown) from a decoded 24-bit {@link V3LeanTag}.
 *
 * <p>This is the wallet-side reconstruction described in SPEC.md §2a.2: the tag
 * carries finding <em>state</em>, and this class supplies the static template
 * (title / description / recommendation / severity) per finding, plus the
 * transaction footer (value / inputs / outputs / fee) which the caller passes
 * in from its own copy of the raw transaction. The exact 0–100 score is not in
 * the tag (only the 4-way grade band), so a representative score per band is
 * synthesized for display.
 */
public final class V3LeanReportBuilder {

    private V3LeanReportBuilder() {}

    /**
     * @param txidHex     display-order txid hex (for the footer)
     * @param valueSats   output value, or 0 if unknown (e.g. test lookup)
     * @param numInputs   tx input count, or 0 if unknown
     * @param numOutputs  tx output count, or 0 if unknown
     * @param feeSats     tx fee, or 0 if unknown
     */
    public static PrivacyReport build(V3LeanTag t, String txidHex, int vout, int height,
                                      long valueSats, int numInputs, int numOutputs, long feeSats) {
        List<Finding> findings = new ArrayList<>();

        // ── CRITICAL ──
        if (t.deterministicCap) {
            findings.add(new Finding("compound-deterministic-cap",
                    "Deterministic privacy failure - score capped", "deterministic confidence",
                    Severity.CRITICAL,
                    "A 100% certain privacy leak was detected. The score is capped at F because no amount of positive signals can offset a deterministic identification.",
                    "Fix the deterministic issue before addressing other findings."));
        }
        if (t.addressReuseIo) {
            findings.add(new Finding("h2-same-address-io",
                    "Same address in input and output - change revealed", "deterministic confidence",
                    Severity.CRITICAL,
                    "One of the spendable outputs goes back to an address that was also an input. This is a 100% deterministic link - that output is certainly change, revealing which other outputs are payments and the exact payment amount.",
                    "Use a wallet that generates a new change address for every transaction (HD wallets). Never send change back to the same address."));
        }
        if (t.chainTaint) {
            findings.add(new Finding("alarm-toxic-merge",
                    "Chain taint / toxic merge in ancestry", "high confidence",
                    Severity.CRITICAL,
                    "This transaction's ancestry includes a privacy-fatal merge or cross-cluster link. The damage is on-chain and irreversible.",
                    "Avoid spending these UTXOs in ways that further extend the linkage. Run a backward trace on your own node for detail."));
        }
        if (t.entityClass == 3) {
            findings.add(new Finding("entity-ofac-match",
                    "Sanctions / OFAC entity match", "high confidence",
                    Severity.CRITICAL,
                    "An address in this transaction matches a sanctions / OFAC list.",
                    "Treat these coins as toxic; consult compliance guidance before transacting."));
        } else if (t.entityClass == 2) {
            findings.add(new Finding("entity-behavior-darknet",
                    "Darknet-market-linked entity detected", "high confidence",
                    Severity.CRITICAL,
                    "An address in this transaction is associated with a darknet market or compromised mixer.",
                    "These coins carry strong forensic association; avoid linking them to KYC identities."));
        }
        if (t.dustClass >= 2) {
            findings.add(new Finding("dust-attack",
                    "Dust attack or dust co-spend detected", "high confidence",
                    Severity.CRITICAL,
                    "This transaction co-spends dust with other coins, which confirms common ownership to an attacker who planted the dust.",
                    "Mark dust UTXOs as do-not-spend in coin control; never co-spend them with real funds."));
        }
        if (t.consolidationClass != 0) {
            findings.add(new Finding("consolidation",
                    "Consolidation pattern (" + V3LeanTag.CONSOL[t.consolidationClass] + ")", "high confidence",
                    Severity.CRITICAL,
                    "Multiple UTXOs were combined, collapsing separate histories into one cluster that chain analysis treats as a single wallet.",
                    "Pre-mix through CoinJoin before consolidating, or avoid consolidation entirely."));
        }

        // ── HIGH ──
        if (t.cioh) {
            findings.add(new Finding("h3-cioh",
                    "Common input ownership heuristic fires", "high confidence",
                    Severity.HIGH,
                    "Multiple inputs were spent together; standard chain analysis treats them all as one wallet.",
                    "Use CoinJoin or avoid combining UTXOs from different sources in one transaction."));
        }
        if (t.isPeelChain) {
            findings.add(new Finding("peel-chain",
                    "Peel chain detected (2+ consecutive hops)", "medium confidence",
                    Severity.HIGH,
                    "This transaction is part of a peel chain - a sequence of transactions each with one input and two outputs, where one output feeds the next. The pattern is highly identifiable and links every downstream UTXO into one wallet's spending behaviour.",
                    "Insert a CoinJoin between spends, or rotate to a fresh wallet."));
        }
        if (t.multisigEscrow) {
            findings.add(new Finding("h17-multisig",
                    "Multisig / escrow product detected", "medium confidence",
                    Severity.HIGH,
                    "The script structure reveals participation in a known escrow / multisig product.",
                    "Be aware that this product is identifiable on-chain."));
        }
        if (t.dustClass == 1) {
            findings.add(new Finding("dust-outputs",
                    "Dust outputs present", "medium confidence",
                    Severity.HIGH,
                    "This transaction has outputs below the dust threshold, which can act as tracking beacons.",
                    "Mark dust UTXOs as do-not-spend in coin control."));
        }
        if (t.entityClass == 1) {
            findings.add(new Finding("entity-behavior-exchange",
                    "Known entity involved", "medium confidence",
                    Severity.HIGH,
                    "An exchange, gambling site, or other identified service is involved in this transaction.",
                    "Assume this counterparty can link the transaction to any KYC identity it holds for you."));
        }

        // ── MEDIUM ──
        if (t.entropyClass >= 2) {
            findings.add(new Finding("h5-low-entropy",
                    "Very low transaction entropy", "medium confidence",
                    Severity.MEDIUM,
                    "The transaction has near-zero entropy: there is essentially one valid interpretation of which inputs fund which outputs.",
                    "Use transactions with more balanced amounts / multiple participants (CoinJoin)."));
        } else if (t.entropyClass == 1) {
            findings.add(new Finding("h5-low-entropy",
                    "Low transaction entropy", "low confidence",
                    Severity.MEDIUM,
                    "The input/output mapping is largely determinable from the amounts.",
                    "Prefer amounts that don't trivially reveal the change output."));
        }
        if (t.opReturn) {
            findings.add(new Finding("h7-op-return",
                    "OP_RETURN metadata present", "medium confidence",
                    Severity.MEDIUM,
                    "This transaction embeds OP_RETURN data, which can carry protocol metadata that aids identification.",
                    "Avoid attaching data payments to private spends."));
        }

        // ── LOW (wallet fingerprint) ──
        int fpSignals = (t.rbfSignaled ? 1 : 0) + (t.noLocktime ? 1 : 0) + (t.legacyVersion ? 1 : 0);
        if (fpSignals > 0) {
            findings.add(new Finding("h11-wallet-fingerprint",
                    fpSignals + " wallet fingerprinting signal" + (fpSignals == 1 ? "" : "s"), "low confidence",
                    Severity.LOW,
                    "Wallet-specific transaction construction signals (nSequence / nLockTime / nVersion) narrow down which wallet software created this transaction.",
                    "Different wallet software exposes different fingerprints; this is informational."));
        }
        if (t.rbfSignaled) {
            findings.add(new Finding("h6-rbf", "RBF signaled", "low confidence", Severity.LOW,
                    "nSequence indicates this transaction opted into Replace-By-Fee.",
                    "Informational - common and not directly de-anonymising."));
        }
        if (t.noLocktime) {
            findings.add(new Finding("h11-no-locktime", "No anti-fee-sniping (nLockTime = 0)", "low confidence",
                    Severity.LOW,
                    "nLockTime is 0, so the transaction does not use anti-fee-sniping. Some wallets always set a recent locktime.",
                    "Informational - reveals wallet construction style."));
        }
        if (t.legacyVersion) {
            findings.add(new Finding("h11-legacy-version", "Legacy transaction version (< 2)", "low confidence",
                    Severity.LOW,
                    "The transaction version is below 2, a wallet-fingerprinting signal.",
                    "Informational."));
        }
        if (t.anonSetNone) {
            findings.add(new Finding("anon-set-none", "No anonymity set", "low confidence", Severity.LOW,
                    "All output amounts are unique, so there is no ambiguity set hiding the payment among equal-value outputs.",
                    "Equal-value outputs (as in CoinJoin) provide an anonymity set."));
        }

        // ── GOOD ──
        if (!t.scriptMixed) {
            findings.add(new Finding("script-uniform", "Uniform script types", "good", Severity.GOOD,
                    "All inputs and outputs use the same script type, avoiding the script-type change fingerprint.",
                    "Good - keep using a single address type per transaction where possible."));
        }
        if (t.isCoinjoin) {
            findings.add(new Finding("h4-coinjoin", "CoinJoin participation", "good", Severity.GOOD,
                    "This transaction participates in a CoinJoin, breaking deterministic input-output links.",
                    "Good - CoinJoin is privacy-positive."));
        }

        // ── grade / score / profile ──
        String grade = V3LeanTag.GRADE[t.grade].equals("A+/A/B") ? "B" : V3LeanTag.GRADE[t.grade];
        int score = synthScore(t);
        String profile = t.isPeelChain ? "peel-chain" : (t.isCoinjoin ? "coinjoin" : "transaction");

        String[] rec = primaryRecommendation(t);

        TxSummary tx = new TxSummary(txidHex, vout, valueSats, height, profile, numInputs, numOutputs, feeSats);
        return new PrivacyReport(grade, score, rec[0], rec[1], rec[2], profile, findings, tx);
    }

    private static int synthScore(V3LeanTag t) {
        // The tag carries only a 4-way grade band; the 0-100 number shown in
        // the dashboard is synthesized here. Each band's value must sit
        // INSIDE the universal letter ranges used everywhere else in the app
        // (A>=90, B>=80, C>=70, D>=60, F<60) so the number and the letter
        // never contradict each other. (Previously D showed "35/100" — a
        // number any user would read as an F.) These same band values are
        // what computeOverallScore() averages, so per-UTXO numbers visibly
        // add up to the wallet's overall score.
        return switch (t.grade) {
            case 0 -> 85;            // A+/A/B → displayed as "B" (80-89)
            case 1 -> 75;            // C (70-79)
            case 2 -> 65;            // D (60-69)
            default -> t.deterministicCap ? 30 : 45; // F (<60); capped leaks rank lowest
        };
    }

    /** Returns {verdict, urgency, detail} per the SPEC §6 cascade. */
    private static String[] primaryRecommendation(V3LeanTag t) {
        if (t.deterministicCap || t.addressReuseIo) {
            return new String[]{"Your wallet sends change back to the input address", "immediate",
                    "Switch to a wallet that generates fresh change addresses for every transaction. This is a critical privacy failure - the change output is 100% identifiable."};
        }
        if (t.entityClass >= 2) {
            return new String[]{t.entityClass == 3 ? "Sanctioned-entity exposure" : "Darknet-market-linked entity",
                    "immediate", "These coins carry strong forensic association. Avoid linking them to any KYC identity."};
        }
        if (t.dustClass >= 2) {
            return new String[]{"Dust attack or dust co-spend detected", "immediate",
                    "Mark the dust UTXOs as do-not-spend in coin control and never co-spend them with real funds."};
        }
        if (t.consolidationClass != 0) {
            return new String[]{"UTXO consolidation links your history", "soon",
                    "Pre-mix through CoinJoin before consolidating, or avoid consolidation."};
        }
        if (t.chainTaint) {
            return new String[]{"Cluster / taint signals in ancestry", "soon",
                    "Investigate this UTXO's history on your own node before spending."};
        }
        if (t.isPeelChain) {
            return new String[]{"Part of a peel chain", "soon",
                    "Insert a CoinJoin between spends or rotate to a fresh wallet."};
        }
        if (t.cioh) {
            return new String[]{"Common-input-ownership clustering", "soon",
                    "Avoid combining UTXOs from different sources in one transaction."};
        }
        if (t.entityClass == 1) {
            return new String[]{"Known entity involved in this transaction", "when-convenient",
                    "Assume this counterparty can link the transaction to your KYC identity."};
        }
        if (t.multisigEscrow) {
            return new String[]{"Multisig / escrow product detected", "when-convenient",
                    "This product is identifiable on-chain."};
        }
        if (t.isCoinjoin) {
            return new String[]{"CoinJoin participation - privacy protected", "positive",
                    "Good - this transaction breaks deterministic links."};
        }
        return new String[]{"No significant privacy issues", "when-convenient",
                "No critical or high-severity findings for this transaction."};
    }
}
