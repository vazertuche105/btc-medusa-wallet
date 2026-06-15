package com.sparrowwallet.perseverus;

import java.util.Objects;

/**
 * Java-side owner of a Rust {@code perseverus_client_native::IssuanceClient}.
 *
 * <p>Holds an opaque {@code long} handle into Rust-owned memory and
 * frees it deterministically via {@link AutoCloseable#close()}. The
 * class is <em>not</em> thread-safe: each instance represents a single
 * logical wallet session and should be driven from a single thread,
 * or externally synchronised.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * try (IssuanceClient ic = IssuanceClient.open(baseUrl, pubkeyHex)) {
 *     IssuedPack pack = ic.issuePack(8);
 *     // ... persist pack for later spend ...
 *     // Tokens are immediately spendable — no epoch wait.
 * }
 * }</pre>
 */
public final class IssuanceClient implements AutoCloseable {
    private long handle;
    private final int defaultPackSize;

    private IssuanceClient(long handle, int defaultPackSize) {
        this.handle = handle;
        this.defaultPackSize = defaultPackSize;
    }

    /**
     * Allocate a fresh client bound to {@code baseUrl} and the
     * server's G₁ credential pubkey.
     *
     * @param baseUrl e.g. {@code http://localhost:3030}
     * @param serverPubkeyG1Hex compressed G1 affine, hex-encoded —
     *                          matches the {@code /server/pubkey} endpoint
     */
    public static IssuanceClient open(String baseUrl, String serverPubkeyG1Hex) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(serverPubkeyG1Hex, "serverPubkeyG1Hex");
        long h = Native.issuanceCreate(baseUrl, serverPubkeyG1Hex);
        if (h == 0) {
            throw new PerseverusException("IssuanceClient.create returned null handle");
        }
        return new IssuanceClient(h, 8);
    }

    /**
     * Issue, finalise, and publish a pack of {@code packSize} tokens
     * that expire at the end of {@code expirationMonth}.
     * The Pedersen commitment is published to the bulletin board
     * inside the Rust call — tokens are immediately spendable.
     *
     * @param packSize number of tokens in the pack
     * @param expirationMonth YYYYMM format (e.g. 202612 = December 2026)
     */
    public IssuedPack issuePack(int packSize, int expirationMonth) {
        checkOpen();
        if (packSize <= 0) {
            throw new IllegalArgumentException("packSize must be > 0");
        }
        if (expirationMonth <= 0) {
            throw new IllegalArgumentException("expirationMonth must be > 0 (YYYYMM format)");
        }
        byte[] blob = Native.issuanceIssuePack(handle, packSize, expirationMonth);
        return new IssuedPack(packSize, blob);
    }

    /**
     * Redeem a BTC silent-payment proof-of-payment code ({@code psbtc1.…}) for a
     * token pack via the server's {@code /btc/redeem} endpoint.
     *
     * @param packSize        number of tokens (must match what the code authorizes)
     * @param expirationMonth YYYYMM
     * @param code            the one-time code returned by the scanner's /subscribe
     */
    public IssuedPack redeemBtc(int packSize, int expirationMonth, String code) {
        checkOpen();
        if (packSize <= 0) {
            throw new IllegalArgumentException("packSize must be > 0");
        }
        if (expirationMonth <= 0) {
            throw new IllegalArgumentException("expirationMonth must be > 0 (YYYYMM format)");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        byte[] blob = Native.issuanceRedeemBtc(handle, packSize, expirationMonth, code);
        if (blob == null || blob.length == 0) {
            throw new PerseverusException("issuanceRedeemBtc returned an empty pack");
        }
        return new IssuedPack(packSize, blob);
    }

    /**
     * Claim a Lightning-paid token pack via {@code /ln/claim}, using the secret
     * nonce from {@code /ln/invoice} as the bearer credential.
     *
     * @param packSize        token count (must match what the invoice authorized)
     * @param expirationMonth YYYYMM
     * @param nonce           the secret nonce returned by /ln/invoice
     */
    public IssuedPack claimLn(int packSize, int expirationMonth, String nonce) {
        checkOpen();
        if (packSize <= 0) {
            throw new IllegalArgumentException("packSize must be > 0");
        }
        if (expirationMonth <= 0) {
            throw new IllegalArgumentException("expirationMonth must be > 0 (YYYYMM format)");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("nonce must not be blank");
        }
        byte[] blob = Native.issuanceClaimLn(handle, packSize, expirationMonth, nonce);
        if (blob == null || blob.length == 0) {
            throw new PerseverusException("issuanceClaimLn returned an empty pack");
        }
        return new IssuedPack(packSize, blob);
    }

    /** Convenience: issue a pack of the given size, expiring 12 months
     *  from now. */
    public IssuedPack issuePack(int packSize) {
        return issuePack(packSize, defaultExpirationMonth());
    }

    /** Convenience: issue a pack at this client's default size,
     *  expiring 12 months from now. */
    public IssuedPack issuePack() {
        return issuePack(defaultPackSize, defaultExpirationMonth());
    }

    /** Returns a YYYYMM value 12 months from the current date. */
    private static int defaultExpirationMonth() {
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate expiry = now.plusMonths(12);
        return expiry.getYear() * 100 + expiry.getMonthValue();
    }

    /**
     * Re-publish the Pedersen commitment from a previously issued pack
     * to the server's bulletin board. Call this after a server restart
     * for every persisted pack so its tokens become spendable again.
     *
     * @throws PerseverusException if the commitment cannot be published
     */
    public void republishCommitment(IssuedPack pack) {
        checkOpen();
        Native.issuanceRepublish(handle, pack.rawBytes());
    }

    private void checkOpen() {
        if (handle == 0) {
            throw new IllegalStateException("IssuanceClient already closed");
        }
    }

    @Override
    public void close() {
        if (handle != 0) {
            long h = handle;
            handle = 0;
            Native.issuanceDestroy(h);
        }
    }
}
