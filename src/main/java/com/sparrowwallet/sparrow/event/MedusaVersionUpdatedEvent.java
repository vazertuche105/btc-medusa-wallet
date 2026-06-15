package com.sparrowwallet.sparrow.event;

/**
 * Posted when a newer BTC Medusa plugin release is available (checked against
 * btcmedusa.com/version, independently of Sparrow's own update check).
 */
public class MedusaVersionUpdatedEvent {
    private final String version;

    public MedusaVersionUpdatedEvent(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }
}
