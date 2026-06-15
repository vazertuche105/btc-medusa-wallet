package com.sparrowwallet.perseverus;

/**
 * Thrown by the Rust-side JNI surface when a native call fails.
 *
 * <p>The Rust code catches panics with {@code std::panic::catch_unwind}
 * and converts both panics and propagated error strings into this
 * exception via {@code JNIEnv::throw_new}, so Java callers never see
 * a segfault or an unwind across the boundary — just a normal
 * unchecked exception with a descriptive message.
 */
public class PerseverusException extends RuntimeException {
    public PerseverusException(String message) {
        super(message);
    }

    public PerseverusException(String message, Throwable cause) {
        super(message, cause);
    }
}
