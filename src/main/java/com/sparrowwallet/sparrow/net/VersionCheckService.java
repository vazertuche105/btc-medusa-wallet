package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Version;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.VersionUpdatedEvent;
import com.sparrowwallet.sparrow.event.MedusaVersionUpdatedEvent;
import javafx.application.Platform;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SignatureException;
import java.util.Map;

public class VersionCheckService extends ScheduledService<VersionUpdatedEvent> {
    private static final Logger log = LoggerFactory.getLogger(VersionCheckService.class);
    private static final String VERSION_CHECK_URL = "https://www.sparrowwallet.com/version";
    // BTC Medusa publishes its own signed plugin version here, e.g.
    //   {"version":"1.1.0","signatures":{"<address>":"<base64 sig>"}}
    // Checked independently of (and in addition to) the Sparrow update check.
    private static final String MEDUSA_VERSION_CHECK_URL = "https://www.btcmedusa.com/version";
    // The legacy (P2PKH, "1...") Bitcoin address whose key signs every BTC
    // Medusa release. The update notice is shown ONLY when the version file's
    // signature recovers THIS address — so a tampered/spoofed file is rejected.
    // Replace the placeholder with your real signing address before release;
    // until then, update notices are fail-closed (never shown).
    private static final String MEDUSA_SIGNING_ADDRESS = "REPLACE_WITH_YOUR_P2PKH_SIGNING_ADDRESS";

    private static String version;

    @Override
    protected Task<VersionUpdatedEvent> createTask() {
        return new Task<>() {
            protected VersionUpdatedEvent call() {
                // BTC Medusa plugin update check — independent of Sparrow's.
                // Posts its own event (on the FX thread) so the status bar can
                // show a separate "BTC Medusa X available" notice.
                checkMedusaVersion();

                try {
                    VersionCheck versionCheck = getVersionCheck();
                    version = versionCheck.version;
                    if(isNewer(versionCheck) && verifySignature(versionCheck)) {
                        return new VersionUpdatedEvent(versionCheck.version);
                    }
                } catch(IOException e) {
                    log.error("Error retrieving version check file", e);
                }

                return null;
            }
        };
    }

    private void checkMedusaVersion() {
        try {
            HttpClientService httpClientService = AppServices.getHttpClientService();
            VersionCheck medusaCheck = httpClientService.requestJson(MEDUSA_VERSION_CHECK_URL, VersionCheck.class, null);
            if(medusaCheck != null && medusaCheck.version != null
                    && isNewerThan(medusaCheck.version, SparrowWallet.MEDUSA_VERSION)
                    && verifyMedusaSignature(medusaCheck)) {
                String newVersion = medusaCheck.version;
                Platform.runLater(() -> EventManager.get().post(new MedusaVersionUpdatedEvent(newVersion)));
            }
        } catch(Exception e) {
            log.debug("BTC Medusa version check failed: {}", e.getMessage());
        }
    }

    /**
     * Verify the version file was signed by the BTC Medusa release key. Mirrors
     * Sparrow's own scheme: the {@code version} string is signed with the
     * release key (Bitcoin message signing), and the recovered P2PKH address
     * must equal {@link #MEDUSA_SIGNING_ADDRESS}. Returns false (fail-closed) on
     * a missing/unrecognized/invalid signature, so only authentic releases ever
     * trigger an update notice.
     */
    private boolean verifyMedusaSignature(VersionCheck versionCheck) {
        if(versionCheck.signatures == null) {
            log.warn("BTC Medusa version file has no signature; ignoring");
            return false;
        }
        try {
            for(String addressString : versionCheck.signatures.keySet()) {
                if(!addressString.equals(MEDUSA_SIGNING_ADDRESS)) {
                    log.warn("Unrecognized signing address for BTC Medusa version check " + addressString);
                    continue;
                }

                String signature = versionCheck.signatures.get(addressString);
                ECKey signedMessageKey = ECKey.signedMessageToKey(versionCheck.version, signature, false);
                Address providedAddress = Address.fromString(addressString);
                Address signedMessageAddress = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, signedMessageKey);

                if(providedAddress.equals(signedMessageAddress)) {
                    return true;
                } else {
                    log.warn("Invalid signature for BTC Medusa version check " + signature + " from address " + addressString);
                }
            }
        } catch(SignatureException e) {
            log.error("Error in BTC Medusa version check signature", e);
        } catch(InvalidAddressException e) {
            log.error("Error in BTC Medusa version check address", e);
        }

        return false;
    }

    private boolean isNewerThan(String candidate, String current) {
        try {
            return new Version(candidate).compareTo(new Version(current)) > 0;
        } catch(IllegalArgumentException e) {
            log.error("Invalid versions to compare: " + candidate + " to " + current, e);
            return false;
        }
    }

    private VersionCheck getVersionCheck() throws IOException {
        if(log.isInfoEnabled()) {
            log.info("Requesting application version check from " + VERSION_CHECK_URL);
        }

        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            return httpClientService.requestJson(VERSION_CHECK_URL, VersionCheck.class, null);
        } catch(Exception e) {
            throw new IOException(e);
        }
    }

    private boolean verifySignature(VersionCheck versionCheck) {
        try {
            for(String addressString : versionCheck.signatures.keySet()) {
                if(!addressString.equals("1LiJx1HQ49L2LzhBwbgwXdHiGodvPg5YaV")) {
                    log.warn("Invalid address for version check " + addressString);
                    continue;
                }

                String signature = versionCheck.signatures.get(addressString);
                ECKey signedMessageKey = ECKey.signedMessageToKey(versionCheck.version, signature, false);
                Address providedAddress = Address.fromString(addressString);
                Address signedMessageAddress = ScriptType.P2PKH.getAddress(PolicyType.SINGLE_HD, signedMessageKey);

                if(providedAddress.equals(signedMessageAddress)) {
                    return true;
                } else {
                    log.warn("Invalid signature for version check " + signature + " from address " + addressString);
                }
            }
        } catch(SignatureException e) {
            log.error("Error in version check signature", e);
        } catch(InvalidAddressException e) {
            log.error("Error in version check address", e);
        }

        return false;
    }

    private boolean isNewer(VersionCheck versionCheck) {
        try {
            Version versionCheckVersion = new Version(versionCheck.version);
            Version currentVersion = new Version(SparrowWallet.APP_VERSION);
            return versionCheckVersion.compareTo(currentVersion) > 0;
        } catch(IllegalArgumentException e) {
            log.error("Invalid versions to compare: " + versionCheck.version + " to " + SparrowWallet.APP_VERSION, e);
        }

        return false;
    }

    public static String getVersion() {
        return version;
    }

    private static class VersionCheck {
        public String version;
        public Map<String, String> signatures;
    }
}
