/**
 * JNI bridge between Sparrow and the Rust
 * {@code perseverus-client-native} crate.
 *
 * <p>The package has four layers, from low to high:
 *
 * <ol>
 *   <li>{@link com.sparrowwallet.perseverus.Native} — raw
 *       {@code native} method declarations mirroring the Rust
 *       {@code Java_*} symbols plus the cdylib loader. Package-private.</li>
 *   <li>{@link com.sparrowwallet.perseverus.IssuanceClient} and
 *       {@link com.sparrowwallet.perseverus.SpendClient} —
 *       {@link java.lang.AutoCloseable} wrappers that own the native
 *       handles.</li>
 *   <li>{@link com.sparrowwallet.perseverus.IssuedPack} — immutable
 *       value holding the opaque pack blob passed from issuance to
 *       spend.</li>
 *   <li>{@link com.sparrowwallet.perseverus.PerseverusService} —
 *       single facade Sparrow UI code calls into.</li>
 * </ol>
 *
 * <p>All failures surface as
 * {@link com.sparrowwallet.perseverus.PerseverusException}. Panics on
 * the Rust side are caught via {@code catch_unwind} and converted to
 * this exception, so the JNI boundary never unwinds across the JVM.
 */
package com.sparrowwallet.perseverus;
