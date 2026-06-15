package com.sparrowwallet.perseverus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.io.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encrypted label storage for BTC Medusa child wallet transactions.
 * <p>
 * Labels are stored in a separate file ({@code btcmedusa-labels.enc}) alongside
 * the wallet file.  The file is encrypted with AES-256-GCM using a key derived
 * from {@code SHA-256(xpub_bytes || master_fingerprint_bytes)}.
 * <p>
 * File format:  12-byte IV ‖ AES-GCM ciphertext (JSON map of txid → label).
 */
public class PerseverusLabelStore {
    private static final Logger log = LoggerFactory.getLogger(PerseverusLabelStore.class);
    private static final String LABEL_FILENAME = "btcmedusa-labels.enc";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    private final File labelFile;
    private final SecretKeySpec encKey;

    /**
     * Creates a label store for the given wallet.
     *
     * @param masterWallet the master (parent) wallet — used to derive the encryption key
     * @param storage      the Storage associated with the master wallet — used to locate the file
     */
    public PerseverusLabelStore(Wallet masterWallet, Storage storage) {
        // Derive encryption key: SHA-256(xpub_bytes || fingerprint_bytes)
        Keystore ks = masterWallet.getKeystores().get(0);
        byte[] xpubBytes = ks.getExtendedPublicKey().toString().getBytes(StandardCharsets.UTF_8);
        String fingerprintHex = ks.getKeyDerivation().getMasterFingerprint();
        byte[] fingerprintBytes = com.sparrowwallet.drongo.Utils.hexToBytes(fingerprintHex);

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(xpubBytes);
            sha256.update(fingerprintBytes);
            byte[] keyBytes = sha256.digest();
            this.encKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive label encryption key", e);
        }

        // Label file lives next to the wallet file
        File walletFile = storage.getWalletFile();
        this.labelFile = new File(walletFile.getParentFile(), LABEL_FILENAME);
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Save a label for a transaction.  If the file already exists, existing
     * labels are preserved and the new one is merged in (or overwritten if
     * the txid already has a label).
     */
    public void putLabel(String txidHex, String label) {
        Map<String, String> labels = loadLabels();
        labels.put(txidHex, label);
        saveLabels(labels);
        log.info("[perseverus-labels] Saved label for {}: {}", shorten(txidHex), label);
        PrivacyLog.get().info("LABEL STORED: " + shorten(txidHex) + " → " + label);
    }

    /**
     * Save multiple labels at once (merge into existing).
     */
    public void putLabels(Map<String, String> newLabels) {
        Map<String, String> labels = loadLabels();
        labels.putAll(newLabels);
        saveLabels(labels);
        log.info("[perseverus-labels] Saved {} labels", newLabels.size());
    }

    /**
     * Load all persisted labels.  Returns an empty map if the file doesn't
     * exist or decryption fails.
     */
    public Map<String, String> loadLabels() {
        if (!labelFile.exists()) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] raw = Files.readAllBytes(labelFile.toPath());
            if (raw.length < GCM_IV_LENGTH + 1) {
                log.warn("[perseverus-labels] Label file too short — returning empty");
                return new LinkedHashMap<>();
            }

            // Split IV and ciphertext
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[raw.length - GCM_IV_LENGTH];
            System.arraycopy(raw, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            String json = new String(plaintext, StandardCharsets.UTF_8);
            LinkedHashMap<String, String> labels = GSON.fromJson(json, MAP_TYPE);
            log.debug("[perseverus-labels] Loaded {} labels from {}", labels.size(), labelFile.getName());
            return labels != null ? labels : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("[perseverus-labels] Failed to decrypt label file: {} — returning empty", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Delete the label file (e.g. when the user requests label clearing).
     */
    public boolean deleteLabels() {
        if (labelFile.exists()) {
            boolean deleted = labelFile.delete();
            log.info("[perseverus-labels] Deleted label file: {}", deleted);
            return deleted;
        }
        return true;
    }

    // ── Internals ───────────────────────────────────────────────────────

    private void saveLabels(Map<String, String> labels) {
        try {
            String json = GSON.toJson(labels);
            byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);

            // Fresh random IV for each write
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Write IV || ciphertext
            byte[] output = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, output, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, output, GCM_IV_LENGTH, ciphertext.length);

            Files.write(labelFile.toPath(), output);
            log.debug("[perseverus-labels] Wrote {} labels to {}", labels.size(), labelFile.getName());
        } catch (Exception e) {
            log.error("[perseverus-labels] Failed to save labels", e);
        }
    }

    private static String shorten(String txid) {
        return txid != null && txid.length() > 12 ? txid.substring(0, 12) + "..." : txid;
    }
}
