package com.sparrowwallet.perseverus;

import java.util.Objects;

/**
 * Opaque output of {@link IssuanceClient#issuePack(int)}.
 *
 * <p>The blob is the binary payload produced by the Rust JNI layer —
 * see {@code client-native/src/jni_surface.rs} for the exact
 * serialisation. The Java side treats it as an immutable byte array
 * it stores and hands back to {@link SpendClient#spend}. It
 * <strong>MUST NOT</strong> be mutated between issue and spend.
 *
 * <p>Tokens are immediately spendable after issuance — the Rust side
 * publishes the Pedersen commitment to the bulletin board as part of
 * the issuePack call. No epoch rollover or waiting period is needed.
 */
public final class IssuedPack {
    private final int packSize;
    private final byte[] blob;

    public IssuedPack(int packSize, byte[] blob) {
        this.packSize = packSize;
        this.blob = Objects.requireNonNull(blob, "blob");
    }

    public int packSize() {
        return packSize;
    }

    /** Defensive copy — don't let callers mutate the underlying bytes. */
    public byte[] bytes() {
        return blob.clone();
    }

    /** Package-private accessor used by {@link SpendClient} to avoid
     *  a redundant copy on the hot path. The JNI side treats the
     *  argument as read-only. */
    byte[] rawBytes() {
        return blob;
    }
}
