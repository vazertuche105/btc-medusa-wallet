package com.sparrowwallet.perseverus;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for the 24-bit v3-lean privacy tag (SPEC.md §2a).
 *
 * <p>A v5 filter carries, per transaction, an OPRF-encrypted codebook code; the
 * native layer decrypts it and maps it through the global codebook to this
 * 24-bit value, which it hands to Java as the string {@code "V3LEAN:<6-hex>"}.
 * This class unpacks the bit fields and provides display helpers (grade band,
 * CSS style class, human-readable finding summary).
 *
 * <p>Bit layout (little-endian 24-bit integer, bit 0 = LSB):
 * <pre>
 *   0      version             0 = v3-lean
 *   1-2    grade               00 A+/A/B · 01 C · 10 D · 11 F
 *   3      is_coinjoin         (GOOD)
 *   4      is_peel_chain       (HIGH)
 *   5      cioh                (HIGH)
 *   6      address_reuse_io    (CRITICAL)
 *   7      deterministic_cap   (CRITICAL — score capped at F)
 *   8-9    dust_class          00 none · 01 outputs · 10 spending · 11 attack
 *   10-11  consolidation_class 00 none · 01 fan-in · 10 fan-out · 11 cross/other
 *   12-13  entity_class        00 none · 01 known · 10 darknet · 11 ofac
 *   14     multisig_escrow     (HIGH)
 *   15     chain_taint         (CRITICAL)
 *   16-17  entropy_class       00 normal · 01 low · 10 zero · 11 zero-sweep
 *   18     script_mixed        1 = mixed types (0 = uniform, GOOD)
 *   19     op_return
 *   20     rbf_signaled
 *   21     no_locktime
 *   22     legacy_version
 *   23     anon_set_none
 * </pre>
 */
public final class V3LeanTag {

    public static final String[] GRADE   = {"A+/A/B", "C", "D", "F"};
    public static final String[] DUST    = {"none", "dust-outputs", "dust-spending", "dust-attack"};
    public static final String[] CONSOL  = {"none", "fan-in", "fan-out", "cross-or-other"};
    public static final String[] ENTITY  = {"none", "known-entity", "darknet", "ofac-or-sanctioned"};
    public static final String[] ENTROPY = {"normal", "low", "zero", "zero-sweep"};

    public final int version, grade, dustClass, consolidationClass, entityClass, entropyClass;
    public final boolean isCoinjoin, isPeelChain, cioh, addressReuseIo, deterministicCap,
                         multisigEscrow, chainTaint, scriptMixed, opReturn,
                         rbfSignaled, noLocktime, legacyVersion, anonSetNone;

    private static int bits(int tag, int off, int w) { return (tag >>> off) & ((1 << w) - 1); }

    /** Decode the low 24 bits of {@code tag}. */
    public V3LeanTag(int tag) {
        version            = bits(tag, 0, 1);
        grade              = bits(tag, 1, 2);
        isCoinjoin         = bits(tag, 3, 1) != 0;
        isPeelChain        = bits(tag, 4, 1) != 0;
        cioh               = bits(tag, 5, 1) != 0;
        addressReuseIo     = bits(tag, 6, 1) != 0;
        deterministicCap   = bits(tag, 7, 1) != 0;
        dustClass          = bits(tag, 8, 2);
        consolidationClass = bits(tag, 10, 2);
        entityClass        = bits(tag, 12, 2);
        multisigEscrow     = bits(tag, 14, 1) != 0;
        chainTaint         = bits(tag, 15, 1) != 0;
        entropyClass       = bits(tag, 16, 2);
        scriptMixed        = bits(tag, 18, 1) != 0;
        opReturn           = bits(tag, 19, 1) != 0;
        rbfSignaled        = bits(tag, 20, 1) != 0;
        noLocktime         = bits(tag, 21, 1) != 0;
        legacyVersion      = bits(tag, 22, 1) != 0;
        anonSetNone        = bits(tag, 23, 1) != 0;
    }

    /** Parse the {@code "V3LEAN:<hex>"} string the native layer returns. */
    public static V3LeanTag fromTagString(String s) {
        String hex = s.startsWith("V3LEAN:") ? s.substring("V3LEAN:".length()) : s;
        return new V3LeanTag(Integer.parseInt(hex.trim(), 16) & 0x00FF_FFFF);
    }

    /** Grade band label, e.g. {@code "A+/A/B"}, {@code "C"}, {@code "D"}, {@code "F"}. */
    public String gradeLabel() {
        return GRADE[grade];
    }

    /**
     * CSS style class for the privacy table, by grade band:
     * A/B → good, C → neutral, D/F → bad.
     */
    public String styleClass() {
        return switch (grade) {
            case 0 -> "privacy-good";
            case 1 -> "privacy-neutral";
            default -> "privacy-bad"; // D, F
        };
    }

    /** The salient findings, highest-severity first, for display. */
    public List<String> findings() {
        List<String> f = new ArrayList<>();
        if (deterministicCap)                 f.add("deterministic leak (score capped)");
        if (addressReuseIo)                   f.add("change sent back to an input address");
        if (chainTaint)                       f.add("chain taint / toxic merge");
        if (entityClass == 3)                 f.add("OFAC / sanctioned entity");
        if (entityClass == 2)                 f.add("darknet-linked entity");
        if (dustClass >= 2)                   f.add("dust attack / co-spend");
        if (consolidationClass != 0)          f.add("consolidation (" + CONSOL[consolidationClass] + ")");
        if (cioh)                             f.add("common-input-ownership");
        if (isPeelChain)                      f.add("peel chain");
        if (multisigEscrow)                   f.add("multisig / escrow");
        if (entityClass == 1)                 f.add("known entity");
        if (dustClass == 1)                   f.add("dust outputs");
        if (entropyClass >= 2)                f.add("very low entropy");
        if (opReturn)                         f.add("OP_RETURN metadata");
        if (isCoinjoin)                       f.add("CoinJoin (privacy-positive)");
        return f;
    }

    /** A one-line label for the privacy table's tag column. */
    public String summary() {
        List<String> f = findings();
        String head = "Grade " + gradeLabel();
        if (f.isEmpty()) {
            return head;
        }
        // Show the top finding inline; the rest are available in the report.
        String top = f.get(0);
        return f.size() == 1 ? head + " · " + top
                             : head + " · " + top + " (+" + (f.size() - 1) + " more)";
    }
}
