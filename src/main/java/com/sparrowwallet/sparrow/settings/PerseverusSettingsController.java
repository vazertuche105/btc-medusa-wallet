package com.sparrowwallet.sparrow.settings;

import com.sparrowwallet.perseverus.PerseverusService;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.event.MedusaTransportChangedEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Config.DecoyRange;
import com.sparrowwallet.sparrow.io.Config.PerseverusTransport;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PerseverusSettingsController extends SettingsDetailController {

    /** .onion URL — only reachable over Tor. */
    private static final String ONION_URL =
            "http://medusayl5rrmgnekpabcduw7onhvdowmfart2mulq3b64chgzng52had.onion";
    /** Clearnet URL — reachable via OHTTP or Direct. */
    private static final String CLEARNET_URL = "http://178.105.65.132:3030";

    /** Set while we programmatically revert a blocked/declined transport
     *  selection, so the value listener swallows the reverting change. */
    private boolean transportReverting = false;

    @FXML
    private ComboBox<PerseverusTransport> transportMode;

    @FXML
    private Label transportStatus;

    @FXML
    private TextField ohttpRelayUrl;

    @FXML
    private UnlabeledToggleSwitch torEnabled;

    @FXML
    private Label torStatus;

    @FXML
    private TextField serverUrl;

    @FXML
    private TextField serverPubkey;

    @FXML
    private TextField settingsDecoysField;

    @FXML
    private ComboBox<DecoyRange> decoyRange;

    @FXML
    private Slider scaleSlider;

    @FXML
    private Label scaleValueLabel;

    @FXML
    private Label spreadLabel;

    @FXML
    private Button exportTokens;

    @FXML
    private Button importTokens;

    @FXML
    private Label tokenBackupStatus;

    @Override
    public void initializeView(Config config) {
        // ── Transport mode combo ────────────────────────────────────────
        transportMode.getItems().setAll(PerseverusTransport.values());
        transportMode.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(PerseverusTransport item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setDisable(false);
                    setStyle("");
                    return;
                }
                // OHTTP isn't implemented yet — show it as "coming soon" and make
                // it unselectable so users can't pick a non-functional transport.
                boolean comingSoon = (item == PerseverusTransport.OHTTP);
                setText(item.getLabel() + (comingSoon ? " (coming soon)" : "")
                        + " — " + item.getDescription());
                setDisable(comingSoon);
                setStyle(comingSoon ? "-fx-opacity: 0.5;" : "");
            }
        });
        transportMode.setConverter(new StringConverter<>() {
            @Override
            public String toString(PerseverusTransport t) {
                return t == null ? null : t.getLabel();
            }

            @Override
            public PerseverusTransport fromString(String s) {
                return null; // not editable
            }
        });

        PerseverusTransport currentTransport = config.getPerseverusTransport();
        // OHTTP is not available yet — if it was previously saved, fall back to Tor.
        if (currentTransport == PerseverusTransport.OHTTP) {
            currentTransport = PerseverusTransport.TOR;
            config.setPerseverusTransport(PerseverusTransport.TOR);
        }
        transportMode.setValue(currentTransport);
        updateTransportStatus(currentTransport);
        updateTorStatus(currentTransport == PerseverusTransport.TOR);

        transportMode.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;

            // If we're reverting a blocked/declined selection, swallow this event.
            if (transportReverting) {
                transportReverting = false;
                return;
            }

            // OHTTP is not available yet — block and revert to the prior mode.
            if (newVal == PerseverusTransport.OHTTP) {
                PerseverusTransport prev = (oldVal != null && oldVal != PerseverusTransport.OHTTP)
                        ? oldVal : PerseverusTransport.TOR;
                transportReverting = true;
                Platform.runLater(() -> transportMode.setValue(prev));
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.initOwner(transportMode.getScene().getWindow());
                info.setTitle("OHTTP — Coming Soon");
                info.setHeaderText("OHTTP isn't available yet");
                info.setContentText("Oblivious HTTP is coming soon. For now, use Tor for IP privacy.");
                info.show();
                return;
            }

            // DIRECT = clearnet: warn loudly and require explicit opt-in.
            if (newVal == PerseverusTransport.DIRECT) {
                Alert warn = new Alert(Alert.AlertType.WARNING);
                warn.initOwner(transportMode.getScene().getWindow());
                warn.setTitle("Clearnet — Privacy Warning");
                warn.setHeaderText("Direct mode reveals your IP address");
                warn.setContentText("Connecting directly (clearnet) exposes your IP address to the "
                        + "BTC Medusa server, which can link your privacy queries back to you. Tor keeps "
                        + "your IP hidden.\n\nOnly use Direct if Tor is unavailable and you accept this "
                        + "trade-off. Continue on clearnet?");
                ButtonType proceed = new ButtonType("Use clearnet anyway", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                warn.getButtonTypes().setAll(proceed, cancel);
                java.util.Optional<ButtonType> choice = warn.showAndWait();
                if (choice.isEmpty() || choice.get() != proceed) {
                    PerseverusTransport prev = (oldVal != null && oldVal != PerseverusTransport.DIRECT)
                            ? oldVal : PerseverusTransport.TOR;
                    transportReverting = true;
                    Platform.runLater(() -> transportMode.setValue(prev));
                    return;
                }
            }

            // Apply the selection (TOR / AUTO / acknowledged DIRECT).
            config.setPerseverusTransport(newVal);
            updateTransportStatus(newVal);
            PerseverusService.configureNativeTransport();

            // Sync Tor toggle with transport mode selection
            torEnabled.setSelected(newVal == PerseverusTransport.TOR);
            updateTorStatus(newVal == PerseverusTransport.TOR);

            // Show/hide OHTTP relay field context
            ohttpRelayUrl.setDisable(newVal != PerseverusTransport.OHTTP);

            // Auto-switch server URL: .onion only works over Tor;
            // clearnet IP is needed for OHTTP and Direct.
            autoSwitchServerUrl(newVal, config);

            // Notify the status bar to update the shield icon color
            EventManager.get().post(new MedusaTransportChangedEvent(newVal));
        });

        // ── OHTTP Relay URL ─────────────────────────────────────────────
        ohttpRelayUrl.setText(config.getPerseverusOhttpRelayUrl());
        ohttpRelayUrl.setDisable(currentTransport != PerseverusTransport.OHTTP);
        ohttpRelayUrl.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                String url = ohttpRelayUrl.getText();
                if (url != null && !url.isBlank()) {
                    config.setPerseverusOhttpRelayUrl(url.strip());
                } else {
                    ohttpRelayUrl.setText(Config.DEFAULT_OHTTP_RELAY_URL);
                    config.setPerseverusOhttpRelayUrl(Config.DEFAULT_OHTTP_RELAY_URL);
                }
            }
        });

        // ── Tor toggle ──────────────────────────────────────────────────
        torEnabled.setSelected(currentTransport == PerseverusTransport.TOR);
        torEnabled.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            // Route through the dropdown's value listener (which applies config,
            // configures the native transport, and enforces the warnings).
            if (isSelected) {
                transportMode.setValue(PerseverusTransport.TOR);
            } else {
                // OHTTP isn't available yet, so turning Tor off falls back to
                // AUTO (Tor-first; never silently clearnet). To actually use
                // clearnet the user must pick "Direct" and accept the warning.
                transportMode.setValue(PerseverusTransport.AUTO);
            }
        });

        // ── Server URL ──────────────────────────────────────────────────
        String currentServerUrl = config.getPerseverusServerUrl();
        if (currentServerUrl != null) {
            serverUrl.setText(currentServerUrl);
        }
        serverUrl.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                String url = serverUrl.getText();
                if (url != null && !url.isBlank()) {
                    config.setPerseverusServerUrl(url.strip());
                    // Re-fetch pubkey when server URL changes
                    autoFetchServerPubkey(config);
                }
            }
        });

        // ── Server Pubkey ───────────────────────────────────────────────
        String currentPubkey = config.getPerseverusServerPubkey();
        if (currentPubkey != null && !currentPubkey.isBlank()) {
            serverPubkey.setText(currentPubkey);
        } else {
            // Auto-fetch pubkey from server if not yet configured
            autoFetchServerPubkey(config);
        }
        serverPubkey.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                String key = serverPubkey.getText();
                if (key != null && !key.isBlank()) {
                    config.setPerseverusServerPubkey(key.strip());
                }
            }
        });

        // ── Advanced Decoy Selection ────────────────────────────────
        int savedDecoys = config.getPerseverusDecoyCount();
        settingsDecoysField.setText(String.valueOf(savedDecoys));
        settingsDecoysField.setTextFormatter(new TextFormatter<>(change -> {
            String proposed = change.getControlNewText();
            if (proposed.isEmpty()) return change;
            if (!proposed.matches("\\d{1,3}")) return null;
            return change;
        }));
        settingsDecoysField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                int val = parseDecoys(settingsDecoysField.getText());
                settingsDecoysField.setText(String.valueOf(val));
                config.setPerseverusDecoyCount(val);
            }
        });

        // ── Range dropdown ─────────────────────────────────────────
        decoyRange.getItems().setAll(DecoyRange.values());
        decoyRange.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(DecoyRange item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        });
        decoyRange.setConverter(new StringConverter<>() {
            @Override
            public String toString(DecoyRange r) {
                return r == null ? null : r.getLabel();
            }
            @Override
            public DecoyRange fromString(String s) { return null; }
        });

        DecoyRange currentRange = config.getPerseverusDecoyRange();
        decoyRange.setValue(currentRange);

        // Scale slider — value represents spread in blocks (±blocks).
        // The underlying Laplace scale = spread / ln(20).
        double savedScale = config.getPerseverusDecoyScale();
        int savedSpread = (int) Math.ceil(savedScale * Math.log(20));
        // Clamp spread within current range bounds
        savedSpread = Math.max(currentRange.getSliderMin(),
                Math.min(currentRange.getSliderMax(), savedSpread));
        configureSliderForRange(currentRange, savedSpread);

        scaleSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int spread = (int) Math.round(newVal.doubleValue());
            scaleValueLabel.setText("±" + spread);
            updateSpreadLabel(spread);
            // Convert block spread back to Laplace scale for Config/Rust
            double scale = spread / Math.log(20);
            config.setPerseverusDecoyScale(scale);
        });

        decoyRange.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                config.setPerseverusDecoyRange(newVal);
                configureSliderForRange(newVal, newVal.getSliderDefault());
            }
        });

        // ── Token Backup (export / import) ──────────────────────────
        exportTokens.setOnAction(e -> exportTokens(config));
        importTokens.setOnAction(e -> importTokens(config));
    }

    /** Stable identity for a pack, used to de-duplicate on import. */
    private static String packKey(Config.PersistedPack p) {
        String b = (p.getBlob() == null) ? "" : Integer.toHexString(java.util.Arrays.hashCode(p.getBlob()));
        return p.getIssuedAt() + "|" + p.getPackSize() + "|" + b;
    }

    private void setBackupStatus(String msg, boolean ok) {
        tokenBackupStatus.setText(msg);
        tokenBackupStatus.setStyle("-fx-font-size: 11; -fx-text-fill: " + (ok ? "#2e7d32" : "#c0392b") + ";");
    }

    /** Save the wallet's remaining tokens to a user-chosen file. The file holds
     *  spendable secrets — treated like a private-key export. */
    private void exportTokens(Config config) {
        java.util.List<Config.PersistedPack> packs = config.getPerseverusPacks();
        if (packs == null || packs.isEmpty()) {
            setBackupStatus("No tokens to export.", false);
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export BTC Medusa tokens");
        fc.setInitialFileName("btcmedusa-tokens-backup.json");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = fc.showSaveDialog(exportTokens.getScene().getWindow());
        if (file == null) {
            return;
        }
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(packs, w);
            setBackupStatus("Exported " + packs.size() + " pack(s). Store this file securely and "
                    + "separately from your seed — it can spend your tokens.", true);
        } catch (Exception ex) {
            setBackupStatus("Export failed: " + ex.getMessage(), false);
        }
    }

    /** Merge tokens from a previously-exported file into this wallet. Existing
     *  packs are preserved; duplicates are skipped. The server's nullifier
     *  ledger still prevents any double-spend, so a stale backup is harmless. */
    private void importTokens(Config config) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Import BTC Medusa tokens");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = fc.showOpenDialog(importTokens.getScene().getWindow());
        if (file == null) {
            return;
        }
        try (java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            Config.PersistedPack[] arr =
                    new com.google.gson.Gson().fromJson(r, Config.PersistedPack[].class);
            if (arr == null || arr.length == 0) {
                setBackupStatus("No tokens found in that file.", false);
                return;
            }
            java.util.List<Config.PersistedPack> merged =
                    new java.util.ArrayList<>(config.getPerseverusPacks());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Config.PersistedPack p : merged) {
                seen.add(packKey(p));
            }
            int added = 0;
            for (Config.PersistedPack p : arr) {
                if (p != null && p.getBlob() != null && seen.add(packKey(p))) {
                    merged.add(p);
                    added++;
                }
            }
            config.setPerseverusPacks(merged); // persists via flush()
            setBackupStatus("Imported " + added + " new pack(s) (" + (arr.length - added)
                    + " already present). Reopen the Privacy tab to use them.", true);
        } catch (Exception ex) {
            setBackupStatus("Import failed: " + ex.getMessage(), false);
        }
    }

    private int parseDecoys(String text) {
        if (text == null || text.isBlank()) return 0;
        try {
            int n = Integer.parseInt(text.trim());
            return Math.max(0, Math.min(100, n));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateSpreadLabel(int spreadBlocks) {
        if (spreadBlocks < 144) {
            spreadLabel.setText("blocks (~" + Math.round(spreadBlocks * 10.0 / 144.0) / 10.0 + " days)");
        } else if (spreadBlocks < 1008) {
            spreadLabel.setText("blocks (~" + Math.round(spreadBlocks * 10.0 / 144.0) / 10.0 + " days)");
        } else {
            double weeks = Math.round(spreadBlocks * 10.0 / 1008.0) / 10.0;
            spreadLabel.setText("blocks (~" + weeks + " weeks)");
        }
    }

    /** Reconfigure slider min/max/value for a given range preset. */
    private void configureSliderForRange(DecoyRange range, int spread) {
        scaleSlider.setMin(range.getSliderMin());
        scaleSlider.setMax(range.getSliderMax());
        scaleSlider.setBlockIncrement(Math.max(1, (range.getSliderMax() - range.getSliderMin()) / 50.0));
        scaleSlider.setValue(spread);
        scaleValueLabel.setText("±" + spread);
        updateSpreadLabel(spread);
    }

    private void updateTransportStatus(PerseverusTransport transport) {
        switch (transport) {
            case DIRECT:
                transportStatus.setText("Direct — no privacy relay active");
                transportStatus.setStyle("-fx-text-fill: #f57f17;");
                break;
            case OHTTP:
                transportStatus.setText("OHTTP relay active — IP hidden from server");
                transportStatus.setStyle("-fx-text-fill: #2e7d32;");
                break;
            case TOR:
                transportStatus.setText("Tor active — maximum anonymity");
                transportStatus.setStyle("-fx-text-fill: #2e7d32;");
                break;
        }
    }

    private void updateTorStatus(boolean enabled) {
        if (enabled) {
            torStatus.setText("Enabled — embedded Arti client");
            torStatus.setStyle("-fx-text-fill: #2e7d32;");
        } else {
            torStatus.setText("Disabled");
            torStatus.setStyle("-fx-text-fill: #757575;");
        }
    }

    /**
     * Auto-fetch the server's VOPRF public key from {@code /server/pubkey}
     * in the background. Uses the native HTTP transport (Tor/OHTTP/Direct)
     * so the fetch respects the current privacy settings.
     *
     * <p>Called automatically when the settings view opens and the pubkey
     * field is empty. On success, populates the text field and persists
     * to config.
     */
    private void autoFetchServerPubkey(Config config) {
        String url = config.getPerseverusServerUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        String endpoint = url.replaceAll("/+$", "") + "/server/pubkey";

        Thread fetchThread = new Thread(() -> {
            try {
                String json = PerseverusService.nativeHttpGet(endpoint);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String pubkeyHex = obj.get("pubkey_hex").getAsString();

                if (pubkeyHex != null && !pubkeyHex.isBlank()) {
                    Platform.runLater(() -> {
                        serverPubkey.setText(pubkeyHex);
                        config.setPerseverusServerPubkey(pubkeyHex);
                    });
                }
            } catch (Exception e) {
                // Silently ignore — user can still enter pubkey manually.
                // Common failure: server unreachable, native lib not loaded.
            }
        }, "perseverus-pubkey-fetch");
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    /**
     * Automatically switch the server URL between .onion (Tor) and
     * clearnet (OHTTP / Direct). The .onion address is unreachable
     * without Tor, so we swap to the clearnet IP when switching away
     * from Tor, and swap back when returning to Tor.
     */
    private void autoSwitchServerUrl(PerseverusTransport transport, Config config) {
        String current = serverUrl.getText();
        if (transport == PerseverusTransport.TOR) {
            // Switching TO Tor — use .onion if currently on clearnet
            if (CLEARNET_URL.equals(current)) {
                serverUrl.setText(ONION_URL);
                config.setPerseverusServerUrl(ONION_URL);
            }
        } else {
            // Switching AWAY from Tor — use clearnet if currently on .onion
            if (current != null && current.contains(".onion")) {
                serverUrl.setText(CLEARNET_URL);
                config.setPerseverusServerUrl(CLEARNET_URL);
            }
        }
    }
}
