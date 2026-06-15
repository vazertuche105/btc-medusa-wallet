package com.sparrowwallet.sparrow.event;

/**
 * Fired by the Privacy tab whenever the set of available privacy labels changes
 * (a scan completed, or the demo was reset). Other tabs (e.g. UTXOs) listen
 * for this to show/hide the "Import Privacy" button.
 */
public class PrivacyLabelsUpdatedEvent {
}
