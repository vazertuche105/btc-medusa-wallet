package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.sparrow.io.Config.PerseverusTransport;

/**
 * Fired when the user changes the BTC Medusa transport mode in settings.
 * The status bar listens for this to update the shield icon color.
 */
public class MedusaTransportChangedEvent {
    private final PerseverusTransport transport;

    public MedusaTransportChangedEvent(PerseverusTransport transport) {
        this.transport = transport;
    }

    public PerseverusTransport getTransport() {
        return transport;
    }
}
