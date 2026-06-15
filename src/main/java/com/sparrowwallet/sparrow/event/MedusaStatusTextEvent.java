package com.sparrowwallet.sparrow.event;

/**
 * Fired by the Privacy tab to push connection status text to the
 * bottom status bar, next to the Medusa shield icon.
 */
public class MedusaStatusTextEvent {
    private final String text;

    public MedusaStatusTextEvent(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
