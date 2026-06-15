package com.sparrowwallet.perseverus;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.NewWalletTransactionsEvent;
import com.sparrowwallet.sparrow.event.WalletHistoryChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for incoming transactions on the Perseverus Payment child wallet
 * and triggers the auto-forward to the silent payment address.
 *
 * This is the glue between Sparrow's Electrum subscription system and
 * the {@link PerseverusPaymentManager#autoForward} method.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Created and registered when the user initiates a staged payment (Path 2)</li>
 *   <li>Sparrow's Electrum subscription detects funds at the staging address</li>
 *   <li>This listener receives {@code NewWalletTransactionsEvent}</li>
 *   <li>If the event's wallet is our payment wallet and value >= required amount,
 *       triggers auto-forward</li>
 *   <li>Unregisters itself after successful forwarding (one-shot)</li>
 * </ol>
 */
public class PerseverusPaymentListener {
    private static final Logger log = LoggerFactory.getLogger(PerseverusPaymentListener.class);

    private final PerseverusPaymentManager manager;
    private final Wallet paymentWallet;
    private final long expectedAmount;
    private final double feeRate;
    private final AtomicBoolean forwarded = new AtomicBoolean(false);

    /**
     * @param manager        the payment manager that will execute the forward
     * @param paymentWallet  the Perseverus Payment child wallet to watch
     * @param expectedAmount the minimum amount expected (subscription + forwarding fee)
     * @param feeRate        sats/vb for the forwarding tx
     */
    public PerseverusPaymentListener(
            PerseverusPaymentManager manager,
            Wallet paymentWallet,
            long expectedAmount,
            double feeRate) {
        this.manager = manager;
        this.paymentWallet = paymentWallet;
        this.expectedAmount = expectedAmount;
        this.feeRate = feeRate;
    }

    /**
     * Register this listener on Sparrow's event bus.
     * Call this after showing the user the staging address.
     */
    public void register() {
        EventManager.get().register(this);
        log.info("[perseverus] Payment listener registered, waiting for {} sats at payment wallet",
            expectedAmount);
    }

    /**
     * Unregister this listener. Called automatically after successful
     * forwarding, or manually if the user cancels.
     */
    public void unregister() {
        EventManager.get().unregister(this);
        log.info("[perseverus] Payment listener unregistered");
    }

    /**
     * Handles new transaction events. If the event is for our payment wallet
     * and the incoming value meets the threshold, triggers auto-forward.
     */
    @Subscribe
    public void onNewWalletTransactions(NewWalletTransactionsEvent event) {
        // Only care about our payment wallet
        if (!isOurPaymentWallet(event.getWallet())) {
            return;
        }

        long incomingValue = event.getTotalValue();
        log.info("[perseverus] Detected incoming tx on payment wallet: {} sats (need {} sats)",
            incomingValue, expectedAmount);

        if (incomingValue < expectedAmount) {
            log.warn("[perseverus] Incoming amount {} < expected {}. Waiting for more funds or correct amount.",
                incomingValue, expectedAmount);
            return;
        }

        // Prevent double-firing
        if (!forwarded.compareAndSet(false, true)) {
            log.info("[perseverus] Already forwarded, ignoring duplicate event");
            return;
        }

        // Execute auto-forward on a background thread
        Thread forwardThread = new Thread(() -> {
            try {
                // The payment wallet has its own seed, so we can decrypt
                // and sign without user interaction.
                //
                // If the payment wallet is encrypted, we need to decrypt it.
                // The decryption key should have been cached when the user
                // initiated the payment flow.
                Wallet decryptedPaymentWallet = paymentWallet;
                // TODO: handle encrypted case — cache the Key from the
                // initial password prompt and use it here:
                // if (paymentWallet.isEncrypted()) {
                //     decryptedPaymentWallet = paymentWallet.copy();
                //     decryptedPaymentWallet.decrypt(cachedKey);
                // }

                manager.autoForward(decryptedPaymentWallet, feeRate);
                log.info("[perseverus] Auto-forward complete!");

                // One-shot: unregister after success
                unregister();

            } catch (Exception e) {
                log.error("[perseverus] Auto-forward failed", e);
                forwarded.set(false); // Allow retry
                // TODO: notify user that manual forwarding may be needed
                // Could post an event to show an alert in the UI
            }
        }, "perseverus-auto-forward");

        forwardThread.setDaemon(true);
        forwardThread.start();
    }

    /**
     * Checks if the given wallet is our Perseverus Payment child wallet.
     */
    private boolean isOurPaymentWallet(Wallet wallet) {
        if (wallet == paymentWallet) {
            return true;
        }
        // Also check by name in case object identity differs after reload
        return wallet.getName() != null
            && wallet.getName().equals("Perseverus Payment")
            && !wallet.isMasterWallet();
    }
}
