package com.sparrowwallet.perseverus;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.crypto.ECDSASignature;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.silentpayments.SilentPayment;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentAddress;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.ChildWalletsAddedEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages Perseverus subscription payments via two paths:
 *
 * <h3>Path 1 — Hot wallet (has seed)</h3>
 * Constructs a silent payment transaction directly from the main wallet.
 * The wallet can auto-sign since it holds the private keys. One on-chain tx.
 *
 * <h3>Path 2 — Watch-only wallet (hardware signer)</h3>
 * Creates a child "Perseverus Payment" account with a fresh BIP39 seed.
 * User sends funds from their main wallet to the child account's taproot
 * address (signs on hardware device — a normal payment). The child account
 * then auto-constructs and auto-signs a silent payment tx forwarding the
 * funds to the Perseverus silent payment address. Two on-chain txs, but
 * the user only interacts with the first.
 *
 * <h3>Fee handling</h3>
 * For Path 2, the amount shown to the user includes the forwarding tx fee
 * so the child wallet can pay the miner for the second hop. The user's
 * own tx fee for Path 1 or the first hop of Path 2 is handled normally
 * by Sparrow's coin selection.
 */
public class PerseverusPaymentManager {
    private static final Logger log = LoggerFactory.getLogger(PerseverusPaymentManager.class);

    // ── BTC Medusa silent payment addresses ────────────────────────────────
    // Mainnet — production address (funds go to BTC Medusa)
    private static final String MAINNET_SP_ADDRESS =
        "sp1qqt30l93e7vuyn07mrpuapktg0pw8ajczndywl4rj3k8me2fzdr2nvq52qzftfj89876gef6qz8fuk35kzlv0dh80sjf075j0lr28rdgpgvce2ej8";

    // Testnet — set via Config so you can point payments at your own wallet.
    // Use Config.get().setPerseverusTestnetSpAddress(addr) to configure.
    // Set it to your own testnet SP address → coins come back to you.

    /**
     * Returns the appropriate SP address for the current network.
     * On mainnet: hardcoded production address.
     * On testnet/signet/regtest: reads from Config. If not configured,
     * logs a warning and uses the mainnet address (tx will likely fail
     * on testnet since it's a different network encoding).
     */
    private String getSpAddress() {
        if (Network.get() != Network.MAINNET) {
            String configured = Config.get().getPerseverusTestnetSpAddress();
            if (configured != null && !configured.isBlank()) {
                log.info("[perseverus] Using configured testnet SP address");
                return configured;
            }
            log.warn("[perseverus] No testnet SP address configured! "
                    + "Set one via Settings → Perseverus → Testnet SP Address, "
                    + "or payments will fail on testnet.");
        }
        return MAINNET_SP_ADDRESS;
    }

    /**
     * Returns true if running on a non-mainnet network (testnet, signet, regtest).
     */
    public static boolean isTestnet() {
        return Network.get() != Network.MAINNET;
    }

    /**
     * Returns the SP address string for the current network (static version).
     * Used by PrivacyController to pre-fill the Send tab.
     */
    public static String getSpAddressString() {
        if (Network.get() != Network.MAINNET) {
            String configured = Config.get().getPerseverusTestnetSpAddress();
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        return MAINNET_SP_ADDRESS;
    }

    // ── Subscription pricing ─────────────────────────────────────────────
    // Target USD prices — sat amounts are computed from live BTC price
    public static final double MONTHLY_USD = 10.0;
    public static final double ANNUAL_USD  = 100.0;

    /** Server-hosted fallback price endpoint — a static JSON file you maintain.
     *  Fetched via Tor when CoinGecko is unavailable, so the user's IP
     *  is never revealed to the BTC Medusa server during price queries. */
    public static final String FALLBACK_PRICE_PATH = "/price.json";

    // Mutable sat amounts — updated by fetchBtcPrice(), can be overridden
    private static volatile long monthlyAmountSats = 0;       // 0 = not yet fetched
    private static volatile long annualAmountSats  = 0;       // 0 = not yet fetched
    private static volatile double lastBtcPrice = 0;          // USD per BTC
    private static volatile long lastPriceFetchTime = 0;      // System.currentTimeMillis()

    /** Maximum percentage the quoted price can be below the current price
     *  before requiring a requote. 5% means if BTC dropped enough that the
     *  correct sats amount is >5% higher than what was quoted, we requote. */
    public static final double PRICE_STALENESS_THRESHOLD = 0.05; // 5%

    // Legacy constants — point to the mutable fields
    public static long MONTHLY_AMOUNT_SATS = monthlyAmountSats;
    public static long ANNUAL_AMOUNT_SATS  = annualAmountSats;

    /** Update sat prices from a live BTC/USD price. */
    public static void updatePriceFromBtc(double btcPriceUsd) {
        if (btcPriceUsd <= 0) return;
        lastBtcPrice = btcPriceUsd;
        lastPriceFetchTime = System.currentTimeMillis();
        // sats = (usd / btcPrice) * 100_000_000
        monthlyAmountSats = Math.round((MONTHLY_USD / btcPriceUsd) * 100_000_000L);
        annualAmountSats  = Math.round((ANNUAL_USD / btcPriceUsd) * 100_000_000L);
        MONTHLY_AMOUNT_SATS = monthlyAmountSats;
        ANNUAL_AMOUNT_SATS  = annualAmountSats;
        log.info("[perseverus] Price updated: BTC=${}, monthly={}sats, annual={}sats",
                String.format("%.2f", btcPriceUsd), monthlyAmountSats, annualAmountSats);
    }

    /** When true, {@link #checkPriceStaleness} is bypassed (dev/testing override). */
    private static volatile boolean priceOverrideStalenessSkip = false;

    /** Override the monthly sat amount (dev/testing).
     * @param enforceStaleness if true, staleness checks still apply to the overridden price;
     *                         if false (default), staleness checks are skipped. */
    public static void overrideMonthlyAmount(long sats, boolean enforceStaleness) {
        monthlyAmountSats = sats;
        MONTHLY_AMOUNT_SATS = sats;
        priceOverrideStalenessSkip = !enforceStaleness;
        log.info("[perseverus] Monthly amount overridden to {} sats (staleness check {})",
                sats, enforceStaleness ? "ENABLED" : "DISABLED");
    }

    /** Get the last fetched BTC price, or 0 if never fetched. */
    public static double getLastBtcPrice() { return lastBtcPrice; }

    /** Milliseconds since last price fetch, or Long.MAX_VALUE if never fetched. */
    public static long getMillisSinceLastPriceFetch() {
        return lastPriceFetchTime == 0 ? Long.MAX_VALUE
                : System.currentTimeMillis() - lastPriceFetchTime;
    }

    /**
     * Re-fetch the BTC price and check if the quoted amount for the given plan
     * is stale (i.e. BTC dropped enough that the user would be underpaying).
     *
     * <p>Returns {@code null} if the price is still within threshold, or a
     * {@link StaleQuote} with updated amounts if a requote is needed.
     *
     * <p>Call this from a background thread right before broadcasting.
     */
    public static StaleQuote checkPriceStaleness(Plan plan) {
        if (priceOverrideStalenessSkip) {
            log.debug("[perseverus] Staleness check skipped — price override active without enforcement");
            return null;
        }
        long quotedSats = plan.getAmountSats();
        double oldPrice = lastBtcPrice;
        long oldFetchTime = lastPriceFetchTime;

        double freshPrice = fetchBtcPrice();
        if (freshPrice <= 0) {
            // Can't fetch — let the payment through at the quoted price
            log.warn("[perseverus] Price staleness check failed — proceeding with quoted price");
            return null;
        }

        long freshSats = plan.getAmountSats(); // updated by fetchBtcPrice()

        // ── Gate on the ACTUAL BTC price movement, not the raw sats delta ──
        // The quoted sats are only a meaningful staleness signal if they were
        // derived from a genuine prior price. If the BTC price hasn't materially
        // moved since the quote, any difference in sats is a baseline artifact
        // (e.g. a default/override amount that was never price-derived) and we
        // must NOT claim "the BTC price has changed." Without this guard a
        // non-price-derived baseline (e.g. 1,000 sats) produces a bogus
        // "1547% increase" even though BTC is sitting at the same price.
        if (oldPrice <= 0) {
            // No genuine prior price to compare against — don't warn.
            return null;
        }
        double pricePctChange = (freshPrice - oldPrice) / oldPrice;
        if (Math.abs(pricePctChange) <= PRICE_STALENESS_THRESHOLD) {
            // BTC price effectively unchanged — the quoted amount is still valid.
            return null;
        }

        // BTC price genuinely moved. Report the change in what the user pays (sats).
        if (quotedSats <= 0) {
            return null;
        }
        double pctChange = (double)(freshSats - quotedSats) / quotedSats; // signed: + = more sats
        if (Math.abs(pctChange) <= PRICE_STALENESS_THRESHOLD) {
            // Sats amount barely moved — not worth a requote.
            return null;
        }

        long minutesElapsed = (System.currentTimeMillis() - oldFetchTime) / 60_000;
        log.info("[perseverus] Price stale: quoted={}sats, current={}sats ({}{}% change, BTC ${}->${}, {}min elapsed)",
                quotedSats, freshSats,
                pctChange >= 0 ? "+" : "", String.format("%.1f", pctChange * 100),
                String.format("%.0f", oldPrice), String.format("%.0f", freshPrice), minutesElapsed);

        return new StaleQuote(quotedSats, freshSats, oldPrice, freshPrice,
                minutesElapsed, pctChange);
    }

    /** Returned by {@link #checkPriceStaleness} when a requote is needed. */
    public static class StaleQuote {
        public final long quotedSats;
        public final long currentSats;
        public final double quotedBtcPrice;
        public final double currentBtcPrice;
        public final long minutesElapsed;
        /** Signed change in the sats amount the user pays: positive = more sats
         *  (BTC dropped), negative = fewer sats (BTC rose). */
        public final double pctChange;

        StaleQuote(long quotedSats, long currentSats, double quotedBtcPrice,
                   double currentBtcPrice, long minutesElapsed, double pctChange) {
            this.quotedSats = quotedSats;
            this.currentSats = currentSats;
            this.quotedBtcPrice = quotedBtcPrice;
            this.currentBtcPrice = currentBtcPrice;
            this.minutesElapsed = minutesElapsed;
            this.pctChange = pctChange;
        }
    }

    /**
     * Fetch the current BTC/USD price from CoinGecko's public API.
     * Blocks briefly — call from a background thread or at startup.
     * Returns the price, or -1 on failure.
     *
     * <p><b>Always uses clearnet (direct HTTP)</b> — never routed through
     * Tor or OHTTP. Querying a public price API reveals nothing about
     * BTC Medusa usage, and Tor would add unnecessary latency.
     * This method uses {@link java.net.HttpURLConnection} directly,
     * bypassing any transport configuration.
     */
    public static double fetchBtcPrice() {
        try {
            java.net.URL url = new java.net.URL(
                    "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() == 200) {
                String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // Parse minimal JSON: {"bitcoin":{"usd":94000.0}}
                int idx = body.indexOf("\"usd\":");
                if (idx >= 0) {
                    String numStr = body.substring(idx + 6).replaceAll("[^0-9.]", "");
                    // Take just the number (stop at any non-numeric after first char)
                    StringBuilder sb = new StringBuilder();
                    boolean dotSeen = false;
                    for (char c : numStr.toCharArray()) {
                        if (Character.isDigit(c)) { sb.append(c); }
                        else if (c == '.' && !dotSeen) { sb.append(c); dotSeen = true; }
                        else break;
                    }
                    double price = Double.parseDouble(sb.toString());
                    updatePriceFromBtc(price);
                    return price;
                }
            }
            log.warn("[perseverus] BTC price fetch failed: HTTP {}", conn.getResponseCode());
        } catch (Exception e) {
            log.warn("[perseverus] BTC price fetch error: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Fetch BTC/USD price from mempool.space's public price API
     * ({@code https://mempool.space/api/v1/prices} → {@code {"USD":60749,...}}).
     * Used to size the $0.25 trial invoice in sats. Direct HTTP (no Tor) — a
     * public price lookup reveals nothing about BTC Medusa usage.
     *
     * @return USD price per BTC, or -1 on failure.
     */
    public static double fetchMempoolPrice() {
        try {
            java.net.URL url = new java.net.URL("https://mempool.space/api/v1/prices");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() == 200) {
                String body = new String(conn.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                int idx = body.indexOf("\"USD\":");
                if (idx >= 0) {
                    StringBuilder sb = new StringBuilder();
                    boolean dotSeen = false;
                    for (char c : body.substring(idx + 6).toCharArray()) {
                        if (Character.isDigit(c)) { sb.append(c); }
                        else if (c == '.' && !dotSeen) { sb.append(c); dotSeen = true; }
                        else if (sb.length() > 0) break;
                    }
                    if (sb.length() > 0) {
                        double price = Double.parseDouble(sb.toString());
                        if (price > 0) {
                            updatePriceFromBtc(price);
                            return price;
                        }
                    }
                }
            }
            log.warn("[perseverus] mempool price fetch failed: HTTP {}", conn.getResponseCode());
        } catch (Exception e) {
            log.warn("[perseverus] mempool price fetch error: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Convert a USD amount to sats using the mempool.space price (falls back to
     * the CoinGecko feed if mempool is unreachable). Rounds UP so the invoice is
     * never short of the target. Returns 0 if no price source is available.
     */
    public static long usdToSats(double usd) {
        double price = fetchMempoolPrice();
        if (price <= 0) {
            price = fetchBtcPrice();
        }
        if (price <= 0) {
            return 0;
        }
        return (long) Math.ceil((usd / price) * 100_000_000L);
    }

    /**
     * Fetch BTC/USD price from a static JSON file hosted on the BTC Medusa
     * server. This is the fallback when CoinGecko (or any public API) is
     * unavailable. You maintain this file on the server — update it as
     * often as you like (e.g. via a cron job).
     *
     * <p>Expected JSON format: {@code {"btc_usd": 94000.0}}
     *
     * <p><b>Routes through Tor</b> via {@link Native#httpGet(String)} so
     * the user's IP is never revealed to the BTC Medusa server. This is
     * critical because if someone blocks CoinGecko (e.g. by turning off
     * internet and then reconnecting only to our server), we must still
     * get an authoritative price without compromising privacy.
     *
     * <p>The server is the payment authority — it knows what price it
     * will accept. If this also fails, sign-up cannot proceed, but the
     * user also has no way to pay (no connectivity).
     *
     * @return the price, or -1 on failure
     */
    public static double fetchFallbackPrice() {
        try {
            // Ensure native transport is configured for Tor before the request
            PerseverusService.configureNativeTransport();

            String serverUrl = Config.get().getPerseverusServerUrl();
            String baseUrl = serverUrl != null ? serverUrl : "http://medusayl5rrmgnekpabcduw7onhvdowmfart2mulq3b64chgzng52had.onion";
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String fullUrl = baseUrl + FALLBACK_PRICE_PATH;

            log.info("[perseverus] Fetching fallback price via Tor from {}", fullUrl);
            String body = Native.httpGet(fullUrl);

            // Parse: {"btc_usd": 94000.0}
            int idx = body.indexOf("\"btc_usd\":");
            if (idx < 0) {
                idx = body.indexOf("\"usd\":");
            }
            if (idx >= 0) {
                String after = body.substring(idx);
                int colonIdx = after.indexOf(':');
                String numPart = after.substring(colonIdx + 1).trim();
                StringBuilder sb = new StringBuilder();
                boolean dotSeen = false;
                for (char c : numPart.toCharArray()) {
                    if (Character.isDigit(c)) { sb.append(c); }
                    else if (c == '.' && !dotSeen) { sb.append(c); dotSeen = true; }
                    else if (sb.length() > 0) break;
                }
                if (sb.length() > 0) {
                    double price = Double.parseDouble(sb.toString());
                    updatePriceFromBtc(price);
                    log.info("[perseverus] Fallback price from server (via Tor): BTC=${}",
                            String.format("%.2f", price));
                    return price;
                }
            }
            log.warn("[perseverus] Server fallback price response did not contain a valid price");
        } catch (UnsatisfiedLinkError e) {
            log.warn("[perseverus] Native httpGet not available — cannot fetch fallback price via Tor. " +
                    "Rebuild client-native with httpGet support.");
        } catch (Exception e) {
            log.warn("[perseverus] Server fallback price fetch via Tor failed: {}", e.getMessage());
        }
        return -1;
    }

    /** Subscription plan durations. */
    public enum Plan {
        // MONTHLY is the constant name (kept for persistence/back-compat); it is
        // presented to users as a one-time token pack, not a recurring plan.
        MONTHLY("One Time",  1),
        ANNUAL ("Annual",  12);

        private final String label;
        private final int months;

        Plan(String label, int months) {
            this.label = label;
            this.months = months;
        }

        public String getLabel()  { return label; }
        public int    getMonths() { return months; }

        /** Returns the current sat amount (may change after price fetch or override). */
        public long getAmountSats() {
            return this == MONTHLY ? monthlyAmountSats : annualAmountSats;
        }

        /** e.g. "13,000 sats (~$10 one-time)" */
        public String displayPrice() {
            if (this == MONTHLY) {
                return String.format("%,d sats (~$%.0f one-time)", getAmountSats(), MONTHLY_USD);
            } else {
                return String.format("%,d sats (~$%.0f/year — save 17%%)", getAmountSats(), ANNUAL_USD);
            }
        }
    }

    /** Back-compat: returns the current monthly amount (updated by price fetch / override). */
    private static long getDefaultSubscriptionSats() { return monthlyAmountSats; }

    // ── Estimated vbytes for the forwarding tx (1 P2TR in → 1 P2TR out) ──
    // Overhead(10.5) + P2TR input(57.5) + P2TR output(43) ≈ 111 vbytes
    private static final int FORWARDING_TX_VBYTES = 111;

    // ── Account index for the Perseverus Payment child wallet ─────────────
    // Uses a high hardened index to avoid collisions with standard accounts
    // and Whirlpool accounts (which use 2147483644–2147483646).
    public static final int PERSEVERUS_ACCOUNT_INDEX = 2147483640;

    private final Wallet masterWallet;
    private final Storage storage;

    public PerseverusPaymentManager(Wallet masterWallet, Storage storage) {
        if (!masterWallet.isMasterWallet()) {
            throw new IllegalArgumentException("Must be the master wallet");
        }
        this.masterWallet = masterWallet;
        this.storage = storage;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Detection
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the master wallet has a BIP39 seed and can construct
     * + sign a silent payment transaction directly (Path 1).
     */
    public boolean isHotWallet() {
        return masterWallet.getKeystores().size() == 1
            && masterWallet.getKeystores().get(0).hasSeed();
    }

    /**
     * Returns true if a Perseverus Payment child wallet already exists.
     */
    public boolean hasPaymentWallet() {
        return getPaymentWallet() != null;
    }

    /**
     * Retrieves the existing Perseverus Payment child wallet, or null.
     */
    public Wallet getPaymentWallet() {
        for (Wallet child : masterWallet.getChildWallets()) {
            if (!child.isNested()) {
                for (Keystore ks : child.getKeystores()) {
                    List<ChildNumber> path = ks.getKeyDerivation().getDerivation();
                    if (!path.isEmpty() && path.get(path.size() - 1).num() == PERSEVERUS_ACCOUNT_INDEX) {
                        return child;
                    }
                }
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fee calculation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Calculates the forwarding fee for the second-hop tx at the given rate.
     *
     * @param feeRate sats per vbyte (from the user's fee slider)
     * @return fee in satoshis for the 1-in-1-out P2TR forwarding tx
     */
    public long calculateForwardingFee(double feeRate) {
        return (long) Math.ceil(FORWARDING_TX_VBYTES * feeRate);
    }

    /**
     * Returns the total amount the user must send to the staging address.
     * This is the subscription price PLUS the forwarding tx fee.
     *
     * @param feeRate sats per vbyte
     */
    public long totalStagingAmount(double feeRate) {
        return getDefaultSubscriptionSats() + calculateForwardingFee(feeRate);
    }

    /**
     * Returns the subscription amount (what Perseverus actually receives).
     */
    public long getSubscriptionAmount() {
        return getDefaultSubscriptionSats();
    }

    /**
     * Returns the subscription amount for a specific plan.
     */
    public static long getSubscriptionAmount(Plan plan) {
        return plan.getAmountSats();
    }

    // ═════════════════════════════════════════════════════════════════════
    // PATH 1 — Hot wallet: direct silent payment
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Constructs, signs, and broadcasts a silent payment transaction
     * directly from the master wallet to the Perseverus address.
     *
     * Call this when {@link #isHotWallet()} returns true.
     *
     * @param feeRate        sats per vbyte from the user's fee slider
     * @param decryptedWallet the decrypted copy of the master wallet
     *                        (caller must prompt for password if encrypted)
     * @return the broadcast txid
     */
    public Sha256Hash payDirect(Wallet decryptedWallet, double feeRate) throws Exception {
        return payDirect(decryptedWallet, feeRate, Plan.MONTHLY);
    }

    /**
     * Constructs, signs, and broadcasts a silent payment transaction
     * directly from the master wallet to the BTC Medusa address.
     *
     * Call this when {@link #isHotWallet()} returns true.
     *
     * @param feeRate        sats per vbyte from the user's fee slider
     * @param plan           the subscription plan (MONTHLY or ANNUAL)
     * @param decryptedWallet the decrypted copy of the master wallet
     * @return the broadcast txid
     */
    public Sha256Hash payDirect(Wallet decryptedWallet, double feeRate, Plan plan) throws Exception {
        PreparedPayment prepared = buildTransaction(decryptedWallet, feeRate, plan);
        return signAndBroadcast(decryptedWallet, prepared);
    }

    /**
     * Builds a silent payment transaction without signing or broadcasting.
     * Used by the manual payment flow so the user can review UTXOs.
     *
     * @return a {@link PreparedPayment} containing the PSBT and metadata
     */
    public PreparedPayment buildTransaction(Wallet decryptedWallet, double feeRate, Plan plan) throws Exception {
        long amount = plan.getAmountSats();
        log.info("[perseverus] Building silent payment tx — {} plan, {} sats, feeRate={} sat/vB",
                plan.getLabel(), amount, feeRate);
        PrivacyLog.get().info(String.format("BUILD TX: plan=%s, amount=%d sats, feeRate=%.1f sat/vB",
                plan.getLabel(), amount, feeRate));

        // Log available UTXOs before coin selection
        Map<BlockTransactionHashIndex, WalletNode> spendable = decryptedWallet.getSpendableUtxos();
        PrivacyLog.get().info(String.format("  Available UTXOs: %d total", spendable.size()));
        long totalAvailable = 0;
        for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry : spendable.entrySet()) {
            BlockTransactionHashIndex utxo = entry.getKey();
            long val = utxo.getValue();
            totalAvailable += val;
            PrivacyLog.get().info(String.format("    %s:%d  value=%d sats  height=%d  status=%s",
                    utxo.getHashAsString(), utxo.getIndex(), val,
                    utxo.getHeight(),
                    utxo.getSpentBy() != null ? "SPENT" : "unspent"));
        }
        PrivacyLog.get().info(String.format("  Total available: %d sats", totalAvailable));

        SilentPaymentAddress spAddr = SilentPaymentAddress.from(getSpAddress());
        SilentPayment payment = new SilentPayment(spAddr, "BTC Medusa " + plan.getLabel(), amount, false);

        // Compute proper coin selection parameters (same as SendController)
        double minRelayFeeRate = AppServices.getMinimumRelayFeeRate() != null
                ? AppServices.getMinimumRelayFeeRate() : Transaction.DEFAULT_MIN_RELAY_FEE;
        long noInputsFee = decryptedWallet.getNoInputsFee(List.of(payment), feeRate);
        long costOfChange = decryptedWallet.getCostOfChange(feeRate, minRelayFeeRate);
        // Ensure costOfChange is at least the dust threshold — at low fee rates the
        // economic cost can be tiny but the network still rejects sub-dust outputs.
        // P2TR dust threshold = 330 sats (highest among standard types; covers P2WPKH 294 too)
        final long DUST_THRESHOLD = 330;
        costOfChange = Math.max(costOfChange, DUST_THRESHOLD);

        // TXO filters — exclude spent, frozen, and immature coinbase UTXOs
        // (same as Sparrow's SendController.getTxoFilters())
        List<TxoFilter> txoFilters = List.of(
            new SpentTxoFilter(), new FrozenTxoFilter(), new CoinbaseTxoFilter(decryptedWallet));

        // Build the transaction with BnB + Knapsack fallback (like Sparrow's Send tab)
        TransactionParameters params = new TransactionParameters(
            List.of(new BnBUtxoSelector(noInputsFee, costOfChange),
                    new KnapsackUtxoSelector(noInputsFee)),           // coin selection
            txoFilters,                                               // filter spent/frozen/coinbase
            List.of(payment),                                         // payments
            List.of(),                                                // no OP_RETURNs
            Set.of(),                                                 // no excluded change nodes
            feeRate,                                                  // user-selected fee rate
            feeRate,                                                  // long-term fee rate
            minRelayFeeRate,
            null,                                                     // no fixed fee
            AppServices.getCurrentBlockHeight(),                      // current block height
            false,                                                    // don't group by address
            false,                                                    // exclude mempool outputs
            true                                                      // allow RBF
        );

        PrivacyLog.get().info(String.format("  Coin selector: BnB+Knapsack, target=%d sats, noInputsFee=%d, costOfChange=%d, currentBlockHeight=%d",
                amount, noInputsFee, costOfChange, AppServices.getCurrentBlockHeight()));

        WalletTransaction walletTx = decryptedWallet.createWalletTransaction(params);

        // Post-construction dust check: Sparrow's internal costOfChange uses economic cost
        // (fee to create + spend the change later), which can be far below the network's
        // dust relay threshold at low fee rates.  If the wallet created a change output
        // below dust, rebuild with an explicit fee that absorbs the dust into fee.
        Map<WalletNode, Long> changeMap = walletTx.getChangeMap();
        if (!changeMap.isEmpty() && changeMap.values().stream().anyMatch(amt -> amt < DUST_THRESHOLD)) {
            long dustChange = changeMap.values().stream().mapToLong(Long::longValue).sum();
            long originalFee = walletTx.getFee();
            long absorbedFee = originalFee + dustChange;
            PrivacyLog.get().info(String.format("  Dust change detected (%d sats < %d threshold) — rebuilding with fee=%d to absorb",
                    dustChange, DUST_THRESHOLD, absorbedFee));

            TransactionParameters noChangeParams = new TransactionParameters(
                List.of(new BnBUtxoSelector(noInputsFee, costOfChange),
                        new KnapsackUtxoSelector(noInputsFee)),
                txoFilters,
                List.of(payment),
                List.of(),
                Set.of(),
                minRelayFeeRate,                      // must equal minRelayFeeRate when fee is explicit
                minRelayFeeRate,
                minRelayFeeRate,
                absorbedFee,                          // explicit fee — forces no change
                AppServices.getCurrentBlockHeight(),
                false,
                false,
                true
            );
            walletTx = decryptedWallet.createWalletTransaction(noChangeParams);
        }

        PSBT psbt = walletTx.createPSBT();

        // Log selected inputs
        PrivacyLog.get().info(String.format("  TX constructed: %d input(s), %d output(s)",
                psbt.getPsbtInputs().size(), psbt.getTransaction().getOutputs().size()));
        for (int i = 0; i < psbt.getPsbtInputs().size(); i++) {
            PSBTInput input = psbt.getPsbtInputs().get(i);
            var outpoint = psbt.getTransaction().getInputs().get(i).getOutpoint();
            long inputVal = input.getUtxo() != null ? input.getUtxo().getValue() : -1;
            PrivacyLog.get().info(String.format("    INPUT[%d]: %s:%d  value=%d sats",
                    i, outpoint.getHash(), outpoint.getIndex(), inputVal));
        }
        for (int i = 0; i < psbt.getTransaction().getOutputs().size(); i++) {
            var output = psbt.getTransaction().getOutputs().get(i);
            PrivacyLog.get().info(String.format("    OUTPUT[%d]: %d sats  script=%s",
                    i, output.getValue(), ScriptType.getType(output.getScript())));
        }
        long fee = psbt.getFee();
        PrivacyLog.get().info(String.format("  Fee: %d sats (%.1f sat/vB effective)",
                fee, (double) fee / psbt.getTransaction().getVirtualSize()));

        // Compute silent payment outputs (ECDH derivation) — needed
        // before the user reviews, so the output address is resolved.
        Map<PSBTInput, WalletNode> signingNodes = decryptedWallet.getSigningNodes(psbt);
        decryptedWallet.computeSilentPaymentOutputs(psbt, signingNodes);

        PrivacyLog.get().info("  Silent payment outputs computed (ECDH derivation done)");

        return new PreparedPayment(walletTx, psbt, signingNodes, plan);
    }

    /**
     * Signs and broadcasts a previously built transaction.
     */
    public Sha256Hash signAndBroadcast(Wallet decryptedWallet, PreparedPayment prepared) throws Exception {
        log.info("[perseverus] Signing and broadcasting {} payment", prepared.plan().getLabel());
        PrivacyLog.get().info("SIGN+BROADCAST: plan=" + prepared.plan().getLabel());

        decryptedWallet.sign(prepared.signingNodes());
        PrivacyLog.get().info("  Signing complete");

        decryptedWallet.finalise(prepared.psbt());
        PrivacyLog.get().info("  PSBT finalized");

        Transaction signedTx = prepared.psbt().extractTransaction();
        PrivacyLog.get().info(String.format("  Signed TX: txid=%s, size=%.0f vB, weight=%s WU",
                signedTx.getTxId(), signedTx.getVirtualSize(), signedTx.getWeightUnits()));
        log.info("[perseverus] Broadcasting tx: {}", signedTx.getTxId());

        return broadcast(signedTx, prepared.psbt().getFee());
    }

    /** BIP-352 proof-of-payment artifacts (all hex). */
    public record SpPaymentProof(String pubkeyHex, String nonceHex, String signatureHex) {}

    /** Tag domain-separator the scanner expects (must match api.py CLAIM_TAG). */
    private static final byte[] SP_CLAIM_TAG =
            "perseverus-sp-claim-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * Compute a proof that the caller controls the key that funded this payment,
     * for the server's BIP-352 proof-of-payment issuance. Requires a SINGLE
     * input (so the signing key is unambiguous and equals the payment's a_sum);
     * returns {@code null} if the payment used multiple inputs (caller falls
     * back to the ungated issuance path).
     *
     * The signature is DER ECDSA over SHA-256("perseverus-sp-claim-v1" ‖
     * compressed_pubkey ‖ nonce), matching the scanner's verification.
     */
    public SpPaymentProof computePaymentProof(Wallet decryptedWallet, PreparedPayment prepared)
            throws com.sparrowwallet.drongo.wallet.MnemonicException {
        Map<BlockTransactionHashIndex, WalletNode> selected = prepared.walletTx().getSelectedUtxos();
        if (selected.size() != 1) {
            log.warn("[perseverus] proof-of-payment needs a single input; tx has {} — skipping (ungated fallback)",
                    selected.size());
            return null;
        }
        WalletNode node = selected.values().iterator().next();
        Keystore keystore = node.getWallet().isNested()
                ? decryptedWallet.getChildWallet(node.getWallet().getName()).getKeystores().get(0)
                : decryptedWallet.getKeystores().get(0);
        ECKey key = keystore.getKey(node);
        return signClaim(key);
    }

    /**
     * Claim-time proof-of-payment: derive the proof from an ALREADY-BROADCAST
     * silent-payment transaction rather than from a {@link PreparedPayment}.
     *
     * <p>This is the preferred path. The wallet broadcasts the payment with no
     * proof; later, after the tx confirms, the subscription step recovers the
     * funding input's key from {@code signingWallet} and signs the claim fresh
     * (ideally over a new Tor circuit). Nothing is persisted at broadcast time.
     *
     * <p>{@code signingWallet} must own the transaction's input and be able to
     * produce its private key (a decrypted hot/master wallet, or the
     * deterministic child payment wallet for the watch-only flow). Returns
     * {@code null} if the tx isn't found, has multiple inputs (a_sum ambiguous),
     * or the funding input isn't owned by {@code signingWallet}.
     *
     * @param signingWallet wallet that funded the payment (private keys available)
     * @param spTxid        the broadcast silent-payment transaction id
     */
    public SpPaymentProof computePaymentProofFromTx(Wallet signingWallet, Sha256Hash spTxid)
            throws com.sparrowwallet.drongo.wallet.MnemonicException {
        if (signingWallet == null || spTxid == null) {
            return null;
        }
        BlockTransaction bt = signingWallet.getTransactions().get(spTxid);
        if (bt == null || bt.getTransaction() == null) {
            log.warn("[perseverus] proof: SP tx {} not found in signing wallet — cannot prove", spTxid);
            return null;
        }
        com.sparrowwallet.drongo.protocol.Transaction tx = bt.getTransaction();
        if (tx.getInputs().size() != 1) {
            log.warn("[perseverus] proof: SP tx {} has {} inputs (a_sum ambiguous) — cannot prove",
                    spTxid, tx.getInputs().size());
            return null;
        }
        com.sparrowwallet.drongo.protocol.TransactionInput in = tx.getInputs().get(0);
        Sha256Hash prevHash = in.getOutpoint().getHash();
        long prevIndex = in.getOutpoint().getIndex();

        // Map the spent outpoint back to the wallet node that owned it. getWalletTxos()
        // includes spent outputs, so the funding UTXO is still resolvable post-spend.
        WalletNode signingNode = signingWallet.getWalletTxos().entrySet().stream()
                .filter(e -> e.getKey().getHash().equals(prevHash) && e.getKey().getIndex() == prevIndex)
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (signingNode == null) {
            log.warn("[perseverus] proof: funding input {}:{} not owned by signing wallet — cannot prove",
                    prevHash, prevIndex);
            return null;
        }
        Keystore keystore = signingNode.getWallet().getKeystores().get(0);
        ECKey key = keystore.getKey(signingNode);
        return signClaim(key);
    }

    /** Sign the scanner's proof-of-payment challenge with the funding input key. */
    private SpPaymentProof signClaim(ECKey key) {
        byte[] pubkey = key.getPubKey(); // 33-byte compressed = a_sum for a single input

        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);

        byte[] claim = new byte[SP_CLAIM_TAG.length + pubkey.length + nonce.length];
        System.arraycopy(SP_CLAIM_TAG, 0, claim, 0, SP_CLAIM_TAG.length);
        System.arraycopy(pubkey, 0, claim, SP_CLAIM_TAG.length, pubkey.length);
        System.arraycopy(nonce, 0, claim, SP_CLAIM_TAG.length + pubkey.length, nonce.length);

        Sha256Hash digest = Sha256Hash.wrap(Sha256Hash.hash(claim)); // single SHA-256
        ECDSASignature sig = key.signEcdsa(digest);
        byte[] der = sig.encodeToDER();

        return new SpPaymentProof(Utils.bytesToHex(pubkey), Utils.bytesToHex(nonce), Utils.bytesToHex(der));
    }

    /**
     * A built but unsigned payment transaction, ready for review.
     */
    public record PreparedPayment(
        WalletTransaction walletTx,
        PSBT psbt,
        Map<PSBTInput, WalletNode> signingNodes,
        Plan plan
    ) {
        /** The fee in satoshis. */
        public long fee() {
            return psbt.getFee();
        }

        /** The total amount being sent (subscription + fee). */
        public long totalSpent() {
            return plan.getAmountSats() + fee();
        }

        /** Returns a summary of selected UTXOs for display. */
        public java.util.List<String> inputSummaries() {
            java.util.List<String> summaries = new java.util.ArrayList<>();
            for (var entry : walletTx.getSelectedUtxos().entrySet()) {
                BlockTransactionHashIndex utxo = entry.getKey();
                String txid = utxo.getHashAsString();
                String shortTxid = txid.length() > 16
                        ? txid.substring(0, 8) + "..." + txid.substring(txid.length() - 8)
                        : txid;
                summaries.add(String.format("%s:%d  %,d sats",
                        shortTxid, utxo.getIndex(), utxo.getValue()));
            }
            return summaries;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PATH 2 — Watch-only: staged payment via child hot wallet
    // ═════════════════════════════════════════════════════════════════════

    // ── Step 2a: Create the child wallet ──────────────────────────────────

    /**
     * Creates the Perseverus Payment child wallet with a deterministically
     * derived BIP39 seed. The child wallet is a P2TR hot wallet that can
     * sign autonomously. It appears as a sub-tab in the Sparrow UI.
     *
     * <p>The seed is derived from the parent wallet's identity so it can
     * always be recovered without storing additional backup material:</p>
     * <pre>
     *   entropy = SHA256( SHA256(xpub_bytes) || master_fingerprint_bytes )
     *   seed    = BIP39( entropy[0..15] )   // 128-bit → 12-word mnemonic
     * </pre>
     *
     * If the child wallet already exists, returns it without creating a new one.
     *
     * @return the Perseverus Payment child wallet
     */
    public Wallet ensurePaymentWallet() throws Exception {
        Wallet existing = getPaymentWallet();
        if (existing != null) {
            log.info("[perseverus] Payment wallet already exists");
            PrivacyLog.get().info("  Child wallet already exists: " + existing.getDisplayName());
            return existing;
        }

        log.info("[perseverus] Creating Perseverus Payment child wallet");
        PrivacyLog.get().info("  Creating new BTC Medusa child wallet (P2TR, deterministic seed)");

        // ── Deterministic seed derivation ──
        // Step 1: Get the parent wallet's xpub and master fingerprint
        Keystore parentKeystore = masterWallet.getKeystores().get(0);
        byte[] xpubBytes = parentKeystore.getExtendedPublicKey().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String fingerprintHex = parentKeystore.getKeyDerivation().getMasterFingerprint();
        byte[] fingerprintBytes = com.sparrowwallet.drongo.Utils.hexToBytes(fingerprintHex);

        // Step 2: SHA256(xpub) → h1
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        byte[] h1 = sha256.digest(xpubBytes);

        // Step 3: SHA256(h1 || fingerprint) → h2  (32 bytes of deterministic entropy)
        sha256.reset();
        sha256.update(h1);
        sha256.update(fingerprintBytes);
        byte[] h2 = sha256.digest();

        // Step 4: Use first 16 bytes as BIP39 entropy (128-bit → 12-word mnemonic)
        byte[] entropy = java.util.Arrays.copyOf(h2, 16);
        DeterministicSeed freshSeed = new DeterministicSeed(entropy, "", System.currentTimeMillis());
        log.info("[perseverus] Derived deterministic seed from xpub + master fingerprint");

        // P2TR derivation: m/86'/0'/PERSEVERUS_ACCOUNT_INDEX'
        // (mainnet=0, testnet=1 — ScriptType handles this)
        List<ChildNumber> derivation = ScriptType.P2TR.getDefaultDerivation(PERSEVERUS_ACCOUNT_INDEX);

        // Build a Keystore from the fresh seed
        Keystore freshKeystore = Keystore.fromSeed(freshSeed, PolicyType.SINGLE_HD, derivation);
        freshKeystore.setLabel("BTCMedusa");

        // Create the child wallet manually (we can't use addChildWallet
        // because that derives from the parent's keystore — we want an
        // independent seed). Use 1-arg constructor so keystores is a
        // mutable ArrayList, then set policyType/scriptType/keystore.
        Wallet paymentWallet = new Wallet("BTC Medusa");
        paymentWallet.setPolicyType(PolicyType.SINGLE_HD);
        paymentWallet.setScriptType(ScriptType.P2TR);
        paymentWallet.getKeystores().add(freshKeystore);
        paymentWallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2TR, paymentWallet.getKeystores(), 1));
        paymentWallet.setMasterWallet(masterWallet);
        masterWallet.getChildWallets().add(paymentWallet);

        // Encrypt with the same key as the master wallet if it's encrypted
        // (caller must have already decrypted the master to get the key)

        // Fire event so Sparrow creates a sub-tab for the child wallet.
        // The sub-tab is REQUIRED — addWalletTab initialises the WalletForm,
        // registers it with EventManager, and fires WalletOpenedEvent which
        // sets up Electrum address subscriptions. Without this, the child
        // wallet never syncs and the auto-forward cannot detect tx1.
        EventManager.get().post(new ChildWalletsAddedEvent(storage, masterWallet, paymentWallet));

        // Persist alongside the parent
        storage.saveWallet(masterWallet);

        log.info("[perseverus] Payment wallet created, derivation: {}", derivation);
        log.info("[perseverus] Wallet isValid={}, scriptType={}, policyType={}", paymentWallet.isValid(), paymentWallet.getScriptType(), paymentWallet.getPolicyType());
        log.info("[perseverus] Keystore label='{}', scriptName='{}', source={}, model={}", freshKeystore.getLabel(), freshKeystore.getScriptName(), freshKeystore.getSource(), freshKeystore.getWalletModel());
        log.info("[perseverus] Descriptor: {}", paymentWallet.getDefaultPolicy().getMiniscript().toString());
        try {
            paymentWallet.checkWallet();
            log.info("[perseverus] checkWallet() PASSED");
        } catch(Exception e) {
            log.error("[perseverus] checkWallet() FAILED: {}", e.getMessage(), e);
        }
        return paymentWallet;
    }

    // ── Step 2b: Get the staging address + total amount ───────────────────

    /**
     * Returns the staging address and total amount the user should send.
     * The total includes the subscription amount + the forwarding tx fee.
     *
     * @param feeRate sats per vbyte from the user's fee slider
     * @return staging payment info
     */
    public StagingInfo getStagingInfo(double feeRate) throws Exception {
        PrivacyLog.get().info("GET STAGING INFO:");
        Wallet paymentWallet = ensurePaymentWallet();

        // Get first unused receive address from the payment wallet.
        // Use max(Sparrow's fresh index, persisted last-used + 1) to avoid
        // address reuse when the wallet hasn't been synced yet.
        WalletNode freshNode = paymentWallet.getFreshNode(KeyPurpose.RECEIVE);
        int lastUsed = Config.get().getPerseverusLastStagingIndex();
        int minIndex = lastUsed + 1;
        if (freshNode.getIndex() < minIndex) {
            PrivacyLog.get().info("  getFreshNode returned index " + freshNode.getIndex()
                    + " but last persisted index is " + lastUsed + " — advancing to " + minIndex);
            WalletNode receiveNode = paymentWallet.getNode(KeyPurpose.RECEIVE);
            if (minIndex >= receiveNode.getChildren().size()) {
                receiveNode.fillToIndex(paymentWallet, minIndex);
            }
            for (WalletNode child : receiveNode.getChildren()) {
                if (child.getIndex() == minIndex) {
                    freshNode = child;
                    break;
                }
            }
        }
        // Persist this index so the next payment advances further
        Config.get().setPerseverusLastStagingIndex(freshNode.getIndex());

        Address stagingAddress = paymentWallet.getAddress(freshNode);
        PrivacyLog.get().info("  Fresh receive node: " + freshNode.getDerivationPath()
                + " (index " + freshNode.getIndex() + ")");
        PrivacyLog.get().info("  Staging address: " + stagingAddress);

        long forwardingFee = calculateForwardingFee(feeRate);
        long totalAmount = getDefaultSubscriptionSats() + forwardingFee;

        log.info("[perseverus] Staging address: {}", stagingAddress);
        log.info("[perseverus] Subscription: {} sats + forwarding fee: {} sats = total: {} sats",
            getDefaultSubscriptionSats(), forwardingFee, totalAmount);
        PrivacyLog.get().info(String.format("  Amounts: subscription=%d + forwardingFee=%d = total=%d sats (feeRate=%.1f)",
                getDefaultSubscriptionSats(), forwardingFee, totalAmount, feeRate));

        return new StagingInfo(stagingAddress, totalAmount, getDefaultSubscriptionSats(), forwardingFee, feeRate);
    }

    /**
     * Staging payment details shown to the user.
     */
    public record StagingInfo(
        Address address,
        long totalAmount,        // what user sends (subscription + forwarding fee)
        long subscriptionAmount, // what Perseverus receives
        long forwardingFee,      // fee for the auto-forwarding tx
        double feeRate           // sats/vb used for forwarding fee calculation
    ) {
        /**
         * Returns a formatted breakdown string for the UI.
         * e.g. "50,000 sats + 222 sats (forwarding fee) = 50,222 sats"
         */
        public String breakdown() {
            return String.format("%,d sats + %,d sats (network fee) = %,d sats",
                subscriptionAmount, forwardingFee, totalAmount);
        }
    }

    // ── Step 2c: Auto-forward when funds arrive ───────────────────────────

    /**
     * Called when the payment wallet detects an incoming UTXO at the
     * staging address. Constructs a silent payment transaction sweeping
     * the full UTXO (minus miner fee) to the Perseverus address, signs
     * it, and broadcasts.
     *
     * This runs automatically in the background — the user never sees it.
     *
     * @param paymentWallet    the decrypted Perseverus Payment child wallet
     * @param feeRate          sats per vbyte (same rate the user selected)
     * @return the broadcast txid of the forwarding tx
     */
    public Sha256Hash autoForward(Wallet paymentWallet, double feeRate) throws Exception {
        log.info("[perseverus] Path 2: auto-forwarding from payment wallet to silent payment address");
        PrivacyLog.get().info("AUTO-FORWARD (TX2): starting auto-forward from child wallet to SP address");
        PrivacyLog.get().info("  SP address: " + getSpAddress());
        PrivacyLog.get().info("  Fee rate: " + feeRate + " sat/vB");
        PrivacyLog.get().info("  Subscription amount: " + getDefaultSubscriptionSats() + " sats");

        // Log available UTXOs in the child wallet
        Map<BlockTransactionHashIndex, WalletNode> childUtxos = paymentWallet.getSpendableUtxos();
        PrivacyLog.get().info("  Child wallet spendable UTXOs: " + childUtxos.size());
        long childBalance = 0;
        for (Map.Entry<BlockTransactionHashIndex, WalletNode> entry : childUtxos.entrySet()) {
            BlockTransactionHashIndex utxo = entry.getKey();
            long val = utxo.getValue();
            childBalance += val;
            PrivacyLog.get().info(String.format("    %s:%d  value=%d sats  height=%s  mempool=%s",
                    utxo.getHashAsString(), utxo.getIndex(), val,
                    utxo.getHeight() > 0 ? String.valueOf(utxo.getHeight()) : "unconfirmed",
                    utxo.getHeight() <= 0 ? "YES" : "no"));
        }
        PrivacyLog.get().info("  Child wallet total balance: " + childBalance + " sats");

        SilentPaymentAddress spAddr = SilentPaymentAddress.from(getSpAddress());

        // Sweep the entire balance minus fee → send-max to silent payment address.
        // Use childBalance as the payment amount instead of getDefaultSubscriptionSats()
        // because: (a) the subscription amount was already calculated when TX1 was built,
        // and the child wallet contains exactly subscription + forwardingFee;
        // (b) getDefaultSubscriptionSats() may return 0 if BTC price hasn't been fetched yet.
        // We want to send everything minus the miner fee to the SP address.
        // Pad fee estimate by 50% to ensure coin selector has room.
        // Any overshoot produces dust change that gets absorbed into the fee.
        long estimatedFee = (long) Math.ceil(FORWARDING_TX_VBYTES * feeRate * 1.5);
        long sweepAmount = childBalance - estimatedFee;
        if (sweepAmount <= 0) {
            throw new Exception("Child wallet balance (" + childBalance + " sats) too low to cover fee (" + estimatedFee + " sats)");
        }
        PrivacyLog.get().info("  Sweep amount: " + sweepAmount + " sats (balance=" + childBalance + " - estFee=" + estimatedFee + ")");

        SilentPayment payment = new SilentPayment(spAddr, "BTC Medusa Subscription", sweepAmount, false);

        double minRelayFee = AppServices.getMinimumRelayFeeRate() != null
                ? AppServices.getMinimumRelayFeeRate() : Transaction.DEFAULT_MIN_RELAY_FEE;
        int currentBlockHeight = AppServices.getCurrentBlockHeight();
        long noInputsFee = paymentWallet.getNoInputsFee(List.of(payment), feeRate);
        long costOfChange = paymentWallet.getCostOfChange(feeRate, minRelayFee);
        // P2TR dust threshold = 330 sats (highest standard type)
        final long DUST_THRESHOLD = 330;
        costOfChange = Math.max(costOfChange, DUST_THRESHOLD);
        PrivacyLog.get().info(String.format("  TX params: BnB+Knapsack target=%d, feeRate=%.1f, minRelay=%.1f, noInputsFee=%d, costOfChange=%d, blockHeight=%d, includeMempool=true, RBF=true",
                sweepAmount, feeRate, minRelayFee, noInputsFee, costOfChange, currentBlockHeight));

        // TXO filters — exclude spent, frozen, and immature coinbase UTXOs
        List<TxoFilter> childTxoFilters = List.of(
            new SpentTxoFilter(), new FrozenTxoFilter(), new CoinbaseTxoFilter(paymentWallet));

        TransactionParameters params = new TransactionParameters(
            List.of(new BnBUtxoSelector(noInputsFee, costOfChange),
                    new KnapsackUtxoSelector(noInputsFee)),
            childTxoFilters,
            List.of(payment),
            List.of(),
            Set.of(),
            feeRate,
            feeRate,
            minRelayFee,
            null,
            currentBlockHeight,
            false,
            true,    // include mempool outputs (the staging UTXO may be unconfirmed)
            true     // enable RBF so the fee can be bumped if it underestimates
        );

        WalletTransaction walletTx = paymentWallet.createWalletTransaction(params);

        // Post-construction dust check (same as buildTransaction)
        Map<WalletNode, Long> changeMap = walletTx.getChangeMap();
        if (!changeMap.isEmpty() && changeMap.values().stream().anyMatch(amt -> amt < DUST_THRESHOLD)) {
            long dustChange = changeMap.values().stream().mapToLong(Long::longValue).sum();
            long originalFee = walletTx.getFee();
            long absorbedFee = originalFee + dustChange;
            PrivacyLog.get().info(String.format("  Dust change detected (%d sats < %d threshold) — rebuilding with fee=%d to absorb",
                    dustChange, DUST_THRESHOLD, absorbedFee));

            TransactionParameters noChangeParams = new TransactionParameters(
                List.of(new BnBUtxoSelector(noInputsFee, costOfChange),
                        new KnapsackUtxoSelector(noInputsFee)),
                childTxoFilters,
                List.of(payment),
                List.of(),
                Set.of(),
                minRelayFee,                          // must equal minRelayFeeRate when fee is explicit
                minRelayFee,
                minRelayFee,
                absorbedFee,
                currentBlockHeight,
                false,
                true,
                true     // enable RBF (fee-bumpable) on the dust-absorb rebuild too
            );
            walletTx = paymentWallet.createWalletTransaction(noChangeParams);
        }

        PSBT psbt = walletTx.createPSBT();

        // Log constructed TX details
        PrivacyLog.get().info(String.format("  TX2 constructed: %d input(s), %d output(s)",
                psbt.getPsbtInputs().size(), psbt.getTransaction().getOutputs().size()));
        for (int i = 0; i < psbt.getPsbtInputs().size(); i++) {
            PSBTInput input = psbt.getPsbtInputs().get(i);
            var outpoint = psbt.getTransaction().getInputs().get(i).getOutpoint();
            long inputVal = input.getUtxo() != null ? input.getUtxo().getValue() : -1;
            PrivacyLog.get().info(String.format("    INPUT[%d]: %s:%d  value=%d sats",
                    i, outpoint.getHash(), outpoint.getIndex(), inputVal));
        }
        for (int i = 0; i < psbt.getTransaction().getOutputs().size(); i++) {
            var output = psbt.getTransaction().getOutputs().get(i);
            PrivacyLog.get().info(String.format("    OUTPUT[%d]: %d sats  script=%s",
                    i, output.getValue(), ScriptType.getType(output.getScript())));
        }
        long fee = psbt.getFee();
        PrivacyLog.get().info(String.format("  TX2 fee: %d sats (%.1f sat/vB effective)",
                fee, (double) fee / psbt.getTransaction().getVirtualSize()));

        // Derive silent payment outputs, sign, finalise
        Map<PSBTInput, WalletNode> signingNodes = paymentWallet.getSigningNodes(psbt);
        PrivacyLog.get().info("  Signing nodes found: " + signingNodes.size());
        PrivacyLog.get().info("  Computing silent payment outputs (ECDH)...");
        try {
            paymentWallet.computeSilentPaymentOutputs(psbt, signingNodes);
        } catch (Throwable e) {
            PrivacyLog.get().info("  FAILED at computeSilentPaymentOutputs: " + e.getClass().getName() + " — " + e.getMessage());
            logStackTrace(e);
            throw wrapIfNeeded(e);
        }
        PrivacyLog.get().info("  Signing TX2...");
        try {
            paymentWallet.sign(signingNodes);
        } catch (Throwable e) {
            PrivacyLog.get().info("  FAILED at sign: " + e.getClass().getName() + " — " + e.getMessage());
            logStackTrace(e);
            throw wrapIfNeeded(e);
        }
        PrivacyLog.get().info("  Finalising TX2...");
        try {
            paymentWallet.finalise(psbt);
        } catch (Throwable e) {
            PrivacyLog.get().info("  FAILED at finalise: " + e.getClass().getName() + " — " + e.getMessage());
            logStackTrace(e);
            throw wrapIfNeeded(e);
        }

        Transaction signedTx;
        try {
            signedTx = psbt.extractTransaction();
        } catch (Throwable e) {
            PrivacyLog.get().info("  FAILED at extractTransaction: " + e.getClass().getName() + " — " + e.getMessage());
            logStackTrace(e);
            throw wrapIfNeeded(e);
        }
        PrivacyLog.get().info(String.format("  TX2 signed: txid=%s, vsize=%.0f, weight=%s",
                signedTx.getTxId(), signedTx.getVirtualSize(), signedTx.getWeightUnits()));
        log.info("[perseverus] Broadcasting forwarding tx: {}", signedTx.getTxId());

        try {
            return broadcast(signedTx, psbt.getFee());
        } catch (Throwable e) {
            PrivacyLog.get().info("  FAILED at broadcast: " + e.getClass().getName() + " — " + e.getMessage());
            logStackTrace(e);
            throw wrapIfNeeded(e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Broadcast helper
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Broadcasts a signed transaction via the connected Electrum server.
     * Runs synchronously on the calling thread.
     */
    private Sha256Hash broadcast(Transaction tx, Long fee) throws Exception {
        PrivacyLog.get().info(String.format("  Broadcasting to electrum server: txid=%s, fee=%d sats",
                tx.getTxId(), fee != null ? fee : -1));
        try {
            ElectrumServer electrumServer = new ElectrumServer();
            Sha256Hash txid = electrumServer.broadcastTransaction(tx, fee);
            log.info("[perseverus] Broadcast successful: {}", txid);
            PrivacyLog.get().info("  Broadcast SUCCESS: " + txid);
            return txid;
        } catch (Exception e) {
            PrivacyLog.get().info("  Broadcast FAILED: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            log.error("[perseverus] Broadcast failed: {}", e.getMessage());
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Convenience: top-level entry point
    // ─────────────────────────────────────────────────────────────────────

    /**
     * High-level entry point called by the Perseverus UI controller.
     *
     * <ul>
     *   <li>Hot wallet → auto-constructs, signs, and broadcasts a silent
     *       payment tx. Returns the txid immediately.</li>
     *   <li>Watch-only → creates the payment child wallet (if needed),
     *       returns a {@link StagingInfo} with the address and amount
     *       for the user to send to. The forwarding tx happens later
     *       via {@link #autoForward} when funds arrive.</li>
     * </ul>
     *
     * @param decryptedWallet decrypted master wallet (for hot) or null (watch-only)
     * @param feeRate         sats per vbyte
     * @return either a txid string (hot wallet, done) or StagingInfo (watch-only, awaiting payment)
     */
    public Object initiate(Wallet decryptedWallet, double feeRate) throws Exception {
        if (isHotWallet() && decryptedWallet != null) {
            Sha256Hash txid = payDirect(decryptedWallet, feeRate);
            return txid;
        } else {
            return getStagingInfo(feeRate);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Diagnostic helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Log the full stack trace to PrivacyLog so we can debug auto-forward failures. */
    private static void logStackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        for (String line : sw.toString().split("\n")) {
            PrivacyLog.get().info("    TRACE: " + line);
        }
    }

    /** Wrap Throwable in RuntimeException if it isn't already an Exception, so we can rethrow. */
    private static Exception wrapIfNeeded(Throwable t) {
        if (t instanceof Exception) {
            return (Exception) t;
        }
        return new RuntimeException("Wrapped non-Exception throwable: " + t.getClass().getName(), t);
    }
}
