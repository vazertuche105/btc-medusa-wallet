package com.sparrowwallet.perseverus;

/**
 * Stub — this class existed for the epoch-based flow and is no longer
 * used. Retained as an empty shell to avoid breaking any reflection-based
 * references in Sparrow's existing config deserialization. Can be removed
 * once all persisted configs have been migrated.
 *
 * @deprecated replaced by the bulletin board flow — tokens are
 *             immediately spendable after issuance
 */
@Deprecated
public final class RolledEpochInfo {
    private RolledEpochInfo() {}
}
