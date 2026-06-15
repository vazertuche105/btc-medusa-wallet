package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.perseverus.IssuedPack;
import com.sparrowwallet.perseverus.PerseverusException;
import com.sparrowwallet.perseverus.PerseverusLabelStore;
import com.sparrowwallet.perseverus.PerseverusPaymentManager;
import com.sparrowwallet.perseverus.PerseverusService;
import com.sparrowwallet.perseverus.PerseverusSignUpWizard;
import com.sparrowwallet.perseverus.PerseverusWelcomeDialog;
import com.sparrowwallet.perseverus.PrivacyLog;
import com.sparrowwallet.perseverus.PrivacyQuery;
import com.sparrowwallet.perseverus.PrivacyReport;
import com.sparrowwallet.perseverus.DemoPrivacyReports;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.silentpayments.SilentPayment;
import com.sparrowwallet.drongo.silentpayments.SilentPaymentAddress;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for the Perseverus Privacy Score tab in Sparrow.
 *
 * Reads the wallet's UTXOs, queries the Perseverus server for each one
 * via the perseverus-cli subprocess, and displays results with a
 * computed privacy score.
 */
public class PrivacyController extends WalletFormController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(PrivacyController.class);

    /** Default Perseverus server URL — .onion for production (routed via Tor).
     *  During testing, override in the Privacy tab UI with the clearnet IP. */
    private static final String DEFAULT_SERVER_URL = "http://medusayl5rrmgnekpabcduw7onhvdowmfart2mulq3b64chgzng52had.onion";
    private static final String CLEARNET_SERVER_IP = "http://178.105.65.132:3030";

    /** Default number of decoy blocks per query for privacy */
    private static final int DEFAULT_DECOYS = 10;
    private static final int MAX_DECOYS = 100;

    // ── FXML-injected nodes ──

    @FXML private Label privacyScore;
    @FXML private Label letterGrade;
    @FXML private Label utxoSummary;
    @FXML private Label scanStatus;
    @FXML private Hyperlink scanStatusTxLink;
    /** The broadcast txid currently shown as a clickable link in the status row, or null. */
    private volatile String statusTxid;
    @FXML private ProgressBar scanProgress;
    @FXML private HBox scanProgressBox;
    @FXML private Label scanProgressLabel;
    @FXML private Label downloadHelpIcon;
    @FXML private Label downloadingLabel;
    @FXML private ImageView perseverusLogo;
    @FXML private TableView<UtxoRow> resultsTable;
    @FXML private TableColumn<UtxoRow, Boolean> selectColumn;
    @FXML private TableColumn<UtxoRow, String> txidColumn;
    @FXML private TableColumn<UtxoRow, String> voutColumn;
    @FXML private TableColumn<UtxoRow, String> valueColumn;
    @FXML private TableColumn<UtxoRow, String> heightColumn;
    @FXML private TableColumn<UtxoRow, String> kycTagColumn;
    @FXML private TableColumn<UtxoRow, String> statusColumn;
    @FXML private TableColumn<UtxoRow, Void> detailsColumn;
    @FXML private Button perseverusButton;
    @FXML private TextField serverUrl;
    @FXML private TextField decoysField;
    @FXML private Label decoyLabel;
    @FXML private Region resetArea;
    @FXML private Button scanButton;
    @FXML private Button scanSelectedButton;
    @FXML private Button settingsButton;
    @FXML private HBox makePaymentRow;

    // ── Issuance controls (created programmatically in Settings dialog) ──
    private TextField serverPubkeyField;
    private Button connectButton;
    private Label connectionStatus;
    private TextField packSizeField;
    private Button issueButton;
    private Label issueStatus;

    // ── Packs history controls ──
    private TableView<PackRow> packsTable;
    private TableColumn<PackRow, String> packSizeColumn;
    private TableColumn<PackRow, String> packRemainingColumn;
    private TableColumn<PackRow, String> packTimeColumn;
    private TableColumn<PackRow, String> packExpiresColumn;
    private TableColumn<PackRow, String> packStatusColumn;

    // ── Spend controls ──
    private Button bootstrapButton;
    private Label bootstrapStatus;
    private TextField spendIndexField;
    private TextField spendInputField;
    private Button spendButton;
    private Label spendResultLabel;

    // ── Demo mode ──
    private CheckBox demoModeToggle;
    private String savedRealServerUrl;
    private String savedRealPubkey;
    private Button refreshPacksButton;
    private Button clearPacksButton;

    /** The settings dialog stage, kept alive so state persists. */
    private Stage settingsStage;

    private final ObservableList<UtxoRow> rows = FXCollections.observableArrayList();
    private volatile boolean scanning = false;
    private boolean demoMode = false;

    // ── Shared state (singleton across all accounts / wallets) ──

    /**
     * Global state shared by every PrivacyController instance.
     * Token packs, the server connection, and bootstrap status are
     * app-wide resources — a token issued in one account is usable
     * by any other account or wallet open in the same Sparrow process.
     */
    private static final class Shared {
        final ObservableList<PackRow> packRows = FXCollections.observableArrayList();
        volatile PerseverusService service;
        volatile boolean bootstrapped;
        volatile boolean manualConnectInitiated;
        volatile boolean autoConnectStarted;
        PackRow selectedPack;
        volatile String pendingHotPaymentTxid;   // set by wizard after broadcast
        volatile String pendingHotPaymentPlan;
        volatile String latestPaymentStatus;     // broadcast by any instance after confirmation/issuance
        volatile int currentMonth;                // latest known server month (YYYYMM) for pack status
    }
    private static final Shared shared = new Shared();

    /** Currently selected pack for spending; updated by table selection or latest issue. */
    private PackRow selectedPack;

    /** Registry of KYC labels keyed by "txid:vout", populated as scans complete.
     *  Read by the UTXOs tab to offer one-click label import. */
    private static final Map<String, String> privacyLabels = new java.util.concurrent.ConcurrentHashMap<>();

    /** Raw scan-report inputs keyed by "txid:vout", used to rebuild the full
     *  PrivacyReport (grade/score) after a restart without re-querying. Value
     *  is encoded as "V3LEAN:hhhhhh;in=N;out=N;fee=N;val=N". */
    private static final Map<String, String> reportData = new java.util.concurrent.ConcurrentHashMap<>();

    public static String getPrivacyLabel(String txid, int vout) {
        return privacyLabels.get(txid + ":" + vout);
    }

    public static boolean hasPrivacyLabels() {
        return !privacyLabels.isEmpty();
    }

    public static java.util.Map<String, String> getPrivacyLabels() {
        return java.util.Collections.unmodifiableMap(privacyLabels);
    }

    // ── Initialization ──

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        // Log every status line change to perseverus.log (captures all setText calls)
        scanStatus.textProperty().addListener((obs, oldText, newText) -> {
            if (newText != null && !newText.isEmpty() && !newText.equals(oldText)) {
                String walletName = getWalletForm() != null && getWalletForm().getWallet() != null
                        ? getWalletForm().getWallet().getFullDisplayName() : "unknown";
                PrivacyLog.get().info("[STATUS " + walletName + "] " + newText);
            }
            // Show the clickable txid link while the status is a broadcast/waiting
            // message (and hide it otherwise). Centralized here so every code path
            // that sets a "Payment broadcast …" status gets the link for free.
            refreshStatusTxLink();
        });

        resultsTable.setEditable(true);

        // Load logo only if the resource is actually present (avoids broken-image placeholder)
        try {
            URL logoUrl = getClass().getResource("/com/sparrowwallet/sparrow/image/perseverus-logo.png");
            if (logoUrl != null) {
                perseverusLogo.setImage(new Image(logoUrl.toExternalForm()));
                perseverusLogo.setVisible(true);
                perseverusLogo.setManaged(true);
                // Soft white glow so the dark logo stands out on the dark tab.
                javafx.scene.effect.DropShadow logoGlow = new javafx.scene.effect.DropShadow();
                logoGlow.setColor(javafx.scene.paint.Color.web("#ffffff"));
                logoGlow.setRadius(20);
                logoGlow.setSpread(0.30);
                perseverusLogo.setEffect(logoGlow);
            }
        } catch (Exception e) {
            log.debug("Perseverus logo not available: {}", e.getMessage());
        }

        // Download help icon — black circle with white "?" and Tor tooltip
        downloadHelpIcon.setText("?");
        downloadHelpIcon.setStyle(
            "-fx-background-color: #222222; -fx-text-fill: white; -fx-font-size: 9px; " +
            "-fx-font-weight: bold; -fx-background-radius: 50; -fx-padding: 0 4 0 4; " +
            "-fx-min-width: 16; -fx-min-height: 16; -fx-max-width: 16; -fx-max-height: 16; " +
            "-fx-alignment: center; -fx-cursor: hand;"
        );
        Tooltip dlHelpTip = new Tooltip(
            "Downloads may be slower than expected when using Tor.\n" +
            "Tor routes your connection through multiple encrypted relays\n" +
            "for maximum privacy, which adds latency to each request.\n" +
            "This is normal and ensures your IP address stays hidden."
        );
        dlHelpTip.setStyle("-fx-font-size: 13px;");
        dlHelpTip.setWrapText(true);
        dlHelpTip.setMaxWidth(400);
        downloadHelpIcon.setTooltip(dlHelpTip);
        downloadHelpIcon.setVisible(false);
        downloadHelpIcon.setManaged(false);

        // Checkbox column
        selectColumn.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);

        // Set up remaining table columns
        txidColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().txidShort()));
        txidColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!empty && getIndex() >= 0 && getIndex() < rows.size()) {
                    Tooltip tooltip = new Tooltip(rows.get(getIndex()).getTxid());
                    setTooltip(tooltip);
                }
            }
        });
        voutColumn.setCellValueFactory(cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getVout())));
        valueColumn.setCellValueFactory(cd -> new SimpleStringProperty(String.format("%,d", cd.getValue().getValueSats())));
        heightColumn.setCellValueFactory(cd -> {
            int h = cd.getValue().getBlockHeight();
            return new SimpleStringProperty(h > 0 ? String.valueOf(h) : "Unconfirmed");
        });
        // Grade column — coloured letter-grade badge driven by the row's report.
        kycTagColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getGrade()));
        kycTagColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(null);
                setText(null);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                UtxoRow row = (UtxoRow) getTableRow().getItem();
                PrivacyReport report = row.getReport();
                if (report == null) {
                    setText("—");
                    return;
                }
                // Intentionally-ungraded UTXOs show a neutral "No grade" chip
                // instead of a letter grade; clicking it opens the same dashboard,
                // which explains the rules that exclude a transaction from grading.
                Label badge = new Label(report.isUngraded() ? "No grade" : report.getGrade());
                badge.setStyle(
                        "-fx-background-color: " + report.getGradeColor() + ";"
                        + "-fx-text-fill: white;"
                        + "-fx-font-weight: bold;"
                        + "-fx-padding: 1 8 1 8;"
                        + "-fx-background-radius: 4;"
                        + "-fx-cursor: hand;");
                // The grade badge doubles as a button: clicking it opens the
                // dashboard, same as the row's "Details" link.
                badge.setOnMouseClicked(ev -> openPrivacyDashboard(row));
                setGraphic(badge);
            }
        });
        // Status column — shows the row's status text, or a ZK-proof progress
        // bar while the row is "proving" during a demo scan.
        statusColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getStatus()));
        statusColumn.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(0);
            private UtxoRow boundRow;
            private final javafx.beans.value.ChangeListener<Number> progListener =
                    (obs, ov, nv) -> refreshDisplay();
            {
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setPrefWidth(110);
            }
            private void refreshDisplay() {
                if (boundRow == null) { setText(null); setGraphic(null); return; }
                double p = boundRow.getProofProgress();
                if (p >= 0) {
                    bar.setProgress(Math.min(1.0, p));
                    setText(null);
                    setGraphic(bar);
                } else {
                    setGraphic(null);
                    setText(boundRow.getStatus());
                }
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                UtxoRow row = (empty || getTableRow() == null) ? null
                        : (UtxoRow) getTableRow().getItem();
                if (boundRow != row) {
                    if (boundRow != null) boundRow.proofProgressProperty().removeListener(progListener);
                    boundRow = row;
                    if (boundRow != null) boundRow.proofProgressProperty().addListener(progListener);
                }
                refreshDisplay();
            }
        });

        // Details column — per-row link that opens the privacy dashboard window.
        detailsColumn.setCellFactory(col -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink("Details");
            // Listener on the *row's* selection (cell selection doesn't fire in
            // row-selection mode), so we can flip the link colour to white when
            // the row is highlighted blue and back to default blue otherwise.
            private final javafx.beans.value.ChangeListener<Boolean> rowSelListener =
                    (obs, wasSel, isSel) -> updateLinkColour(Boolean.TRUE.equals(isSel));
            {
                link.setOnAction(e -> {
                    UtxoRow row = getTableRow() == null ? null : (UtxoRow) getTableRow().getItem();
                    if (row != null && row.getReport() != null) {
                        openPrivacyDashboard(row);
                    }
                });
                tableRowProperty().addListener((obs, oldRow, newRow) -> {
                    if (oldRow != null) oldRow.selectedProperty().removeListener(rowSelListener);
                    if (newRow != null) newRow.selectedProperty().addListener(rowSelListener);
                    updateLinkColour(newRow != null && newRow.isSelected());
                });
            }
            private void updateLinkColour(boolean selected) {
                link.setStyle(selected ? "-fx-text-fill: white;" : "");
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                UtxoRow row = (UtxoRow) getTableRow().getItem();
                setGraphic(row.getReport() != null ? link : null);
                updateLinkColour(getTableRow().isSelected());
            }
        });

        resultsTable.setItems(rows);

        // Decoys field: digits only, max 3 chars, clamped to 0..MAX_DECOYS on focus loss
        decoysField.setTextFormatter(new TextFormatter<>(change -> {
            String proposed = change.getControlNewText();
            if (proposed.isEmpty()) return change;
            if (!proposed.matches("\\d{1,3}")) return null;
            return change;
        }));
        // Load persisted decoy count from Config
        int savedDecoys = Config.get().getPerseverusDecoyCount();
        decoysField.setText(String.valueOf(savedDecoys));
        decoysField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                int val = currentDecoys();
                decoysField.setText(String.valueOf(val));
                Config.get().setPerseverusDecoyCount(val);
            }
        });

        // ── Tooltip + click handler on "Decoy #" label ────────────
        String decoyExplanation =
                "Decoy blocks disguise which block filter your wallet actually needs.\n\n"
              + "For each real UTXO block, the wallet downloads additional random "
              + "'decoy' filters from nearby blocks. The server sees all requests "
              + "but cannot tell which block contains your real transaction.\n\n"
              + "More decoys = stronger privacy but larger download.\n\n"
              + "The 'Scale' setting (in Settings → Advanced Decoy Selection) "
              + "controls how far decoys spread from your real block. A higher "
              + "scale spreads decoys over a wider range of blocks, making it "
              + "harder for an observer to guess your real block.\n\n"
              + "Example: with 5 decoys and scale 5.0, each UTXO downloads "
              + "6 block filters (1 real + 5 decoy) spread across roughly "
              + "±15 blocks around the real one.";
        Tooltip decoyTooltip = new Tooltip(decoyExplanation);
        decoyTooltip.setWrapText(true);
        decoyTooltip.setMaxWidth(350);
        decoyLabel.setTooltip(decoyTooltip);
        decoyLabel.setStyle("-fx-underline: true; -fx-cursor: hand;");
        decoyLabel.setOnMouseClicked(event -> {
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle("Decoy Blocks");
            info.setHeaderText("What are decoy blocks?");
            info.getDialogPane().setPrefWidth(480);
            info.setContentText(decoyExplanation);
            info.showAndWait();
        });

        // Load persisted server URL from config, if available
        String savedUrl = Config.get().getPerseverusServerUrl();
        if (savedUrl != null && !savedUrl.isBlank()) {
            serverUrl.setText(savedUrl);
        }

        // Initialize settings dialog controls eagerly so scan logic can reference them
        initSettingsControls();

        // Populate rows from the wallet's current UTXOs
        refreshUtxoRows();

        // Restore previous scan results so KYC tags survive restarts
        restoreScanResults();

        // Auto-connect + auto-bootstrap if saved URL and pubkey are present.
        // This runs in the background so the UI doesn't block.
        autoConnectAndBootstrap();

        // Show welcome dialog every time the Privacy tab opens until
        // the user has signed up. Trial users and first-time visitors
        // always see it; only paid subscribers skip it.
        // Skip the welcome dialog if there's an in-progress BTC payment
        // — the user already signed up and is waiting for confirmation.
        if (!Config.get().isPerseverusWelcomed() && !Config.get().isPerseverusSuppressWelcome()
                && !Config.get().hasPerseverusPendingPayment()) {
            // Defer so the tab is fully rendered before the dialog appears
            Platform.runLater(this::showWelcomeDialog);
        }

        // Update the Perseverus button text with trial status
        updatePerseverusButtonLabel();

        // Resume polling for any pending BTC payment that survived a restart
        resumePendingPaymentPolling();
    }

    /**
     * Sync this controller's UI elements to the shared connection state.
     * Called by controllers that initialize after the first one has
     * already started (or completed) auto-connect. If the connection
     * is still in progress, a background poller waits for it.
     */
    private void syncUiToSharedState() {
        // If the service is already connected, update UI immediately
        if (shared.service != null) {
            applySharedStateToUi();
            return;
        }
        // Otherwise, poll until the first controller's auto-connect finishes
        Thread poller = new Thread(() -> {
            try {
                for (int i = 0; i < 60; i++) { // up to 30 seconds
                    Thread.sleep(500);
                    if (shared.service != null) {
                        Platform.runLater(this::applySharedStateToUi);
                        return;
                    }
                }
                log.debug("Sync poller timed out — auto-connect may have failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "perseverus-ui-sync");
        poller.setDaemon(true);
        poller.start();
    }

    /** Push shared connection/bootstrap state into this controller's UI controls. */
    private void applySharedStateToUi() {
        String ver = shared.service != null ? PerseverusService.nativeVersion() : "?";
        if (connectionStatus != null) {
            connectionStatus.setText("Connected (v" + ver + ")");
            connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            connectionStatus.getStyleClass().add("issuance-connected");
        }
        if (connectButton != null) {
            connectButton.setDisable(false);
            connectButton.setText("Reconnect");
        }
        if (issueButton != null) issueButton.setDisable(false);
        if (bootstrapButton != null) {
            if (shared.bootstrapped) {
                bootstrapButton.setDisable(true);
            } else {
                bootstrapButton.setDisable(false);
            }
        }
        if (bootstrapStatus != null) {
            if (shared.bootstrapped) {
                bootstrapStatus.setText("Ready");
                bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
                bootstrapStatus.getStyleClass().add("issuance-connected");
            } else {
                bootstrapStatus.setText("Bootstrapping...");
            }
        }
        if (spendButton != null) spendButton.setDisable(!shared.bootstrapped);
        // Update pubkey field if the shared service fetched a newer one
        String savedPubkey = Config.get().getPerseverusServerPubkey();
        if (serverPubkeyField != null && savedPubkey != null && !savedPubkey.isBlank()) {
            if (!savedPubkey.equals(serverPubkeyField.getText().trim())) {
                serverPubkeyField.setText(savedPubkey);
            }
        }
        if (!shared.packRows.isEmpty()) {
            if (packsTable != null) {
                packsTable.setVisible(true);
                packsTable.setManaged(true);
            }
        }
        log.info("UI synced to shared connection state (bootstrapped={})", shared.bootstrapped);
    }

    /**
     * Attempt to connect to the saved server and bootstrap the spend
     * client automatically on launch. Runs on a background thread so
     * the wallet UI stays responsive. Failures are silently logged —
     * the user can always connect manually via Settings.
     */
    private void autoConnectAndBootstrap() {
        // Only one controller should auto-connect — the first one wins.
        // Other controllers sync their UI to the shared state instead.
        if (shared.autoConnectStarted) {
            syncUiToSharedState();
            return;
        }
        shared.autoConnectStarted = true;

        String url = serverUrl.getText().trim();
        String pubkeyHex = serverPubkeyField != null ? serverPubkeyField.getText().trim() : "";
        if (url.isBlank()) {
            return; // no server URL — skip
        }
        // If the pubkey is missing or a demo placeholder, we'll fetch the
        // live key from the server during connect (line below). Don't bail.
        boolean needsFreshPubkey = pubkeyHex.isBlank()
                || pubkeyHex.equals("demo-pubkey-not-a-real-bls-point");

        String transportLabel = Config.get().getPerseverusTransport().getLabel();

        Thread t = new Thread(() -> {
            try {
                // ── Phase 1: connect to server ──
                setStatus("Connecting to server via " + transportLabel + "...");

                // Fetch the server's live OPRF pubkey.
                // Try native transport first (Tor/.onion), then clearnet fallback.
                String livePubkey = fetchServerPubkey(url);
                if ((livePubkey == null || livePubkey.isBlank()) && needsFreshPubkey) {
                    // Tor might not be ready yet — try clearnet IP directly
                    log.info("Auto-connect: Tor pubkey fetch failed, trying clearnet fallback");
                    livePubkey = fetchServerPubkeyJava(CLEARNET_SERVER_IP + "/server/pubkey");
                }
                final String effectivePubkey;
                if (livePubkey != null && !livePubkey.isBlank()) {
                    effectivePubkey = livePubkey;
                    if (!livePubkey.equals(pubkeyHex)) {
                        log.info("Auto-connect: server pubkey {} — using live key",
                                needsFreshPubkey ? "fetched" : "changed");
                    }
                } else if (!needsFreshPubkey) {
                    effectivePubkey = pubkeyHex;
                } else {
                    log.warn("Auto-connect: no pubkey saved and couldn't fetch from server");
                    setStatus("Connection failed — server pubkey unavailable");
                    return;
                }

                PerseverusService svc = PerseverusService.open(url, effectivePubkey);
                String ver = PerseverusService.nativeVersion();
                PrivacyLog.get().connect(url + " (auto)", ver, transportLabel);
                setStatus("Connected to server via " + transportLabel + " (v" + ver + ")");

                // If the server key changed, old packs are invalid —
                // they were signed with the old key and will fail DLEQ
                // verification on spend.  Guard: if needsFreshPubkey is true,
                // pubkeyHex was blank/demo — that's first boot, not a key rotation.
                final boolean keyChanged = !needsFreshPubkey && !effectivePubkey.equals(pubkeyHex);
                if (keyChanged && !shared.packRows.isEmpty() && !shared.manualConnectInitiated) {
                    log.info("Server key changed — clearing {} stale pack(s)", shared.packRows.size());
                    Platform.runLater(() -> {
                        if (shared.manualConnectInitiated) return;
                        shared.packRows.clear();
                        packsTable.setVisible(false);
                        packsTable.setManaged(false);
                        selectedPack = null;
                        persistPacks();
                    });
                }

                Platform.runLater(() -> {
                    // If the user clicked Reconnect while we were
                    // running, bail — the manual connect owns the
                    // service and UI state now.
                    if (shared.manualConnectInitiated) {
                        log.info("Auto-connect callback skipped — manual connect in progress");
                        return;
                    }
                    shared.service = svc;
                    // Update the pubkey field and persist if it changed
                    if (!effectivePubkey.equals(serverPubkeyField.getText().trim())) {
                        serverPubkeyField.setText(effectivePubkey);
                        Config.get().setPerseverusServerPubkey(effectivePubkey);
                    }
                    connectionStatus.setText("Connected (v" + ver + ")");
                    connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
                    connectionStatus.getStyleClass().add("issuance-connected");
                    connectButton.setDisable(false);
                    connectButton.setText("Reconnect");
                    issueButton.setDisable(false);
                    bootstrapButton.setDisable(false);
                });

                // ── Phase 2: bootstrap spend client (download proving key) ──
                if (shared.manualConnectInitiated) {
                    log.info("Auto-connect skipping bootstrap — manual connect owns lifecycle");
                    return;
                }
                setStatus("Downloading proving key via " + transportLabel + "...");
                PrivacyLog.get().bootstrapStart();
                long bt0 = System.currentTimeMillis();
                svc.bootstrap();
                long bElapsed = System.currentTimeMillis() - bt0;
                PrivacyLog.get().bootstrapComplete(bElapsed);

                Platform.runLater(() -> {
                    if (shared.manualConnectInitiated) return;
                    shared.bootstrapped = true;
                    bootstrapStatus.setText("Ready");
                    bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
                    bootstrapStatus.getStyleClass().add("issuance-connected");
                    bootstrapButton.setDisable(true);
                    spendButton.setDisable(false);
                });
                log.info("Auto-connect + bootstrap succeeded in {}ms", bElapsed);

                // ── Phase 3: re-publish pack commitments ──
                int republished = 0;
                int republishFailed = 0;
                int toRepublish = 0;
                for (PackRow pr : shared.packRows) {
                    if (pr.remaining() > 0) toRepublish++;
                }
                if (toRepublish > 0) {
                    setStatus("Re-publishing " + toRepublish + " pack commitment(s)...");
                    PrivacyLog.get().republishStart(toRepublish);
                }
                long republishT0 = System.currentTimeMillis();
                for (PackRow pr : shared.packRows) {
                    if (pr.remaining() > 0) {
                        try {
                            svc.republishCommitment(pr.pack);
                            republished++;
                        } catch (Exception re) {
                            republishFailed++;
                            log.warn("Failed to re-publish commitment for pack: {}",
                                    re.getMessage());
                        }
                    }
                }
                if (toRepublish > 0) {
                    long republishElapsed = System.currentTimeMillis() - republishT0;
                    log.info("Re-published {} pack commitment(s) to bulletin board",
                            republished);
                    PrivacyLog.get().republishComplete(republished, republishFailed, republishElapsed);
                }

                // ── Done — push status to bottom status bar ──
                setStatus("");
                final String readyText = "Ready — connected via " + transportLabel;
                Platform.runLater(() -> EventManager.get().post(new MedusaStatusTextEvent(readyText)));
            } catch (Throwable e) {
                log.error("Auto-connect failed (will require manual connect)", e);
                PrivacyLog.get().warn("Auto-connect failed: " + e.getMessage());
                setStatus("Connection failed — open Settings to connect manually");
            }
        }, "perseverus-auto-connect");
        t.setDaemon(true);
        t.start();
    }

    /** Update the status label on the Privacy tab from any thread. */
    private void setStatus(String message) {
        Platform.runLater(() -> downloadingLabel.setText(message));
    }

    /** Parse and clamp the decoys field to the valid range [0, MAX_DECOYS]. */
    private int currentDecoys() {
        String text = decoysField.getText();
        if (text == null || text.isBlank()) return DEFAULT_DECOYS;
        try {
            int n = Integer.parseInt(text.trim());
            if (n < 0) return 0;
            if (n > MAX_DECOYS) return MAX_DECOYS;
            return n;
        } catch (NumberFormatException ex) {
            return DEFAULT_DECOYS;
        }
    }

    /** Read the current Laplace scale from Config. Default 1335 ≈ ±4000 blocks
     *  (scale = spread / ln(20), so ~95% of decoys fall within ±spread). */
    private double currentScale() {
        double s = Config.get().getPerseverusDecoyScale();
        return s > 0 ? s : 1335.0;
    }

    private void refreshUtxoRows() {
        WalletUtxosEntry utxosEntry = getWalletForm().getWalletUtxosEntry();
        List<Entry> utxos = utxosEntry.getChildren();
        rows.clear();
        if (utxos != null) {
            for (Entry entry : utxos) {
                UtxoEntry utxoEntry = (UtxoEntry) entry;
                BlockTransactionHashIndex hashIndex = utxoEntry.getHashIndex();
                UtxoRow utxoRow = new UtxoRow(
                        hashIndex.getHash().toString(),
                        (int) hashIndex.getIndex(),
                        hashIndex.getValue(),
                        hashIndex.getHeight()
                );
                // Populate the report footer (inputs / outputs / fee) from the
                // wallet's own copy of the funding transaction, if available.
                try {
                    BlockTransaction bt = getWalletForm().getWallet()
                            .getWalletTransaction(hashIndex.getHash());
                    if (bt != null && bt.getTransaction() != null) {
                        int nIn = bt.getTransaction().getInputs().size();
                        int nOut = bt.getTransaction().getOutputs().size();
                        long fee = bt.getFee() != null ? bt.getFee() : 0L;
                        utxoRow.setTxMeta(nIn, nOut, fee);
                    }
                } catch (Exception ignored) {
                    // Footer just shows 0 if the tx detail isn't locally available.
                }
                rows.add(utxoRow);
            }
        }
        updateUtxoSummary();

        // Re-apply persisted scan results to rebuilt rows so results
        // don't vanish when switching accounts or receiving new transactions
        if (!privacyLabels.isEmpty() || !reportData.isEmpty()) {
            for (UtxoRow row : rows) {
                String key = row.getTxid() + ":" + row.getVout();
                String tag = privacyLabels.get(key);
                if (tag != null && !tag.isBlank()) {
                    row.applyResult(resultFromDisplayTag(tag));
                }
                applyPersistedReport(row);
            }
            resultsTable.refresh();
            updateScoreDisplay();
        }
    }

    /**
     * Create all issuance/spend controls as plain Java objects.
     * They live as instance fields so the scan logic can reference them,
     * and are placed into the Settings dialog the first time it opens.
     */
    private void initSettingsControls() {
        // ── Connection controls ──
        serverPubkeyField = new TextField();
        serverPubkeyField.setPromptText("Compressed BLS12-377 G1 point hex");
        serverPubkeyField.setPrefWidth(420);
        HBox.setHgrow(serverPubkeyField, Priority.ALWAYS);
        // The server key is pinned: it is fetched/persisted programmatically
        // and must not be user-editable (a swapped pubkey would let a fake
        // server's tokens verify). Display-only; Reconnect still works.
        serverPubkeyField.setEditable(false);
        serverPubkeyField.setFocusTraversable(false);
        serverPubkeyField.setTooltip(new Tooltip(
                "Pinned server OPRF public key (read-only)"));

        String savedPubkey = Config.get().getPerseverusServerPubkey();
        if (savedPubkey != null && !savedPubkey.isBlank()) {
            serverPubkeyField.setText(savedPubkey);
        }

        connectButton = new Button("Connect");
        connectButton.setGraphicTextGap(5);
        connectButton.setOnAction(this::connectService);

        connectionStatus = new Label("Not connected");
        connectionStatus.getStyleClass().add("issuance-status");

        // ── Pack controls ──
        packSizeField = new TextField(String.valueOf(DEFAULT_PACK_SIZE));
        packSizeField.setPrefWidth(50);
        packSizeField.setMaxWidth(50);
        packSizeField.setTextFormatter(new TextFormatter<>(change -> {
            String proposed = change.getControlNewText();
            if (proposed.isEmpty()) return change;
            if (!proposed.matches("\\d{1,3}")) return null;
            return change;
        }));
        packSizeField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) packSizeField.setText(String.valueOf(currentPackSize()));
        });

        issueButton = new Button("Issue Tokens");
        issueButton.setGraphicTextGap(5);
        issueButton.setOnAction(this::issueTokens);
        issueButton.setDisable(true);

        refreshPacksButton = new Button("Refresh");
        refreshPacksButton.setOnAction(e -> {
            // Reload pack state from Config — picks up spent-state changes
            // and any packs persisted by other sessions.
            shared.packRows.clear();
            List<Config.PersistedPack> saved = Config.get().getPerseverusPacks();
            int loaded = 0;
            if (saved != null) {
                for (Config.PersistedPack pp : saved) {
                    if (pp.getBlob() == null || pp.getBlob().length == 0) continue;
                    IssuedPack pack = new IssuedPack(pp.getPackSize(), pp.getBlob());
                    PackRow pr = new PackRow(pack, pp.getIssuedAt());
                    boolean[] spent = pp.getSpent();
                    if (spent != null) {
                        for (int i = 0; i < spent.length && i < pp.getPackSize(); i++) {
                            if (spent[i]) pr.markSpent(i);
                        }
                    }
                    shared.packRows.add(pr);
                    loaded++;
                }
            }
            if (!shared.packRows.isEmpty()) {
                selectedPack = shared.packRows.getFirst();
                packsTable.getSelectionModel().select(selectedPack);
                packsTable.setVisible(true);
                packsTable.setManaged(true);
            }
            int totalRemaining = 0;
            for (PackRow pr : shared.packRows) totalRemaining += pr.remaining();
            issueStatus.setText(loaded + " pack(s) loaded, " + totalRemaining + " token(s) remaining");
            issueStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            issueStatus.getStyleClass().add("issuance-connected");
            log.info("Refreshed packs from config: {} packs, {} tokens remaining", loaded, totalRemaining);
            packsTable.refresh();
            refreshServerMonthAsync();
        });

        clearPacksButton = new Button("Clear History");
        clearPacksButton.setOnAction(e -> {
            shared.packRows.clear();
            packsTable.setVisible(false);
            packsTable.setManaged(false);
            selectedPack = null;
            // Keep bootstrapped state — the spend client is still ready,
            // only the token packs were cleared. The user will re-issue.
            persistPacks();
            issueStatus.setText("History cleared");
            issueStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            log.info("Pack history cleared");
            PrivacyLog.get().clearHistory();
        });

        issueStatus = new Label("");
        issueStatus.getStyleClass().add("issuance-status");

        // ── Packs table ──
        packsTable = new TableView<>();
        packsTable.setPrefHeight(120);
        packsTable.setMaxHeight(160);
        packsTable.setVisible(false);
        packsTable.setManaged(false);
        packsTable.getStyleClass().add("packs-table");
        packsTable.setPlaceholder(new Label("No packs issued yet"));

        packSizeColumn = new TableColumn<>("Tokens");
        packSizeColumn.setPrefWidth(60);
        packRemainingColumn = new TableColumn<>("Remaining");
        packRemainingColumn.setPrefWidth(75);
        packTimeColumn = new TableColumn<>("Issued At");
        packTimeColumn.setPrefWidth(120);
        packExpiresColumn = new TableColumn<>("Expires");
        packExpiresColumn.setPrefWidth(90);
        packStatusColumn = new TableColumn<>("Status");
        packStatusColumn.setPrefWidth(110);
        packsTable.getColumns().addAll(packSizeColumn, packRemainingColumn, packTimeColumn, packExpiresColumn, packStatusColumn);

        packSizeColumn.setCellValueFactory(cd -> new SimpleStringProperty(String.valueOf(cd.getValue().packSize)));
        packRemainingColumn.setCellValueFactory(cd -> new SimpleStringProperty(String.valueOf(cd.getValue().remaining())));
        packTimeColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().issuedAt));
        packExpiresColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().expiresLabel()));
        packStatusColumn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().statusLabel(shared.currentMonth)));
        packStatusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || item == null) {
                    setStyle("");
                } else if (item.startsWith("Active")) {
                    setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;");
                } else if (item.startsWith("Expired")) {
                    setStyle("-fx-text-fill: #c62828;");
                } else {
                    setStyle("-fx-text-fill: #ef6c00;"); // Not yet active
                }
            }
        });
        packsTable.setItems(shared.packRows);
        packsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) selectedPack = newVal;
        });

        // ── Spend controls ──
        bootstrapButton = new Button("Bootstrap Spend Client");
        bootstrapButton.setGraphicTextGap(5);
        bootstrapButton.setOnAction(this::bootstrapSpend);
        bootstrapButton.setDisable(true);

        bootstrapStatus = new Label("Not bootstrapped");
        bootstrapStatus.getStyleClass().add("issuance-status");

        spendIndexField = new TextField("0");
        spendIndexField.setPrefWidth(50);
        spendIndexField.setMaxWidth(50);
        spendIndexField.setTextFormatter(new TextFormatter<>(change -> {
            String proposed = change.getControlNewText();
            if (proposed.isEmpty()) return change;
            if (!proposed.matches("\\d{1,3}")) return null;
            return change;
        }));

        spendInputField = new TextField();
        spendInputField.setPromptText("OPRF preimage e.g. outpoint bytes");
        spendInputField.setPrefWidth(320);
        HBox.setHgrow(spendInputField, Priority.ALWAYS);

        spendButton = new Button("Spend");
        spendButton.setGraphicTextGap(5);
        spendButton.setOnAction(this::executeSpend);
        spendButton.setDisable(true);

        spendResultLabel = new Label("");
        spendResultLabel.getStyleClass().add("issuance-status");

        // ── Demo toggle ──
        demoModeToggle = new CheckBox("Demo Mode");
        demoModeToggle.getStyleClass().add("demo-toggle");
        demoModeToggle.setOnAction(this::toggleDemoMode);

        // Restore persisted packs and demo mode from Config
        restorePersistedPacks();
    }

    /** Opens (or brings to front) the Perseverus Settings dialog. */
    @FXML
    public void openSettings(ActionEvent event) {
        if (settingsStage != null) {
            settingsStage.show();
            settingsStage.toFront();
            return;
        }

        settingsStage = new Stage();
        settingsStage.setTitle("Perseverus Settings");
        settingsStage.initModality(Modality.NONE);
        settingsStage.initOwner(resultsTable.getScene().getWindow());

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.getStyleClass().add("issuance-panel");

        // ── Header row ──
        HBox headerRow = new HBox(15);
        headerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label header = new Label("Token Issuance");
        header.getStyleClass().add("issuance-header");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        headerRow.getChildren().addAll(header, headerSpacer, demoModeToggle);
        root.getChildren().add(headerRow);

        // ── Connection row ──
        HBox connRow = new HBox(10);
        connRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        connRow.getChildren().addAll(new Label("Server Pubkey (G1 hex):"), serverPubkeyField, connectButton, connectionStatus);
        root.getChildren().add(connRow);

        // ── Scanner URL row ──
        // The SP scanner verifies the on-chain payment and returns the
        // proof-of-payment code used to mint tokens. Leave blank to use the
        // built-in default (localhost dev). In production set the scanner's
        // .onion or http URL here.
        TextField scannerUrlField = new TextField();
        scannerUrlField.setPromptText("http://127.0.0.1:8080  or  your scanner .onion / server URL");
        // Pre-fill with the effective scanner URL: the saved config value if
        // set, otherwise the built-in production default (the scanner onion) —
        // the same value issuance falls back to — so the field is never blank
        // and the user can see/keep the default without typing it.
        String curScanner = Config.get().getPerseverusScannerUrl();
        scannerUrlField.setText((curScanner != null && !curScanner.isBlank())
                ? curScanner
                : PerseverusSignUpWizard.scannerBaseUrl());
        HBox.setHgrow(scannerUrlField, Priority.ALWAYS);
        Label scannerSavedLabel = new Label();
        scannerSavedLabel.setStyle("-fx-text-fill: #27ae60; -fx-font-size: 11;");
        Button saveScannerButton = new Button("Save");
        saveScannerButton.setOnAction(e -> {
            String v = scannerUrlField.getText() == null ? "" : scannerUrlField.getText().trim();
            Config.get().setPerseverusScannerUrl(v.isBlank() ? null : v);
            scannerSavedLabel.setText("Saved ✓");
            log.info("[perseverus] Scanner URL set to {}", v.isBlank() ? "(cleared — using default)" : v);
        });
        scannerUrlField.textProperty().addListener((o, ov, nv) -> scannerSavedLabel.setText(""));
        HBox scannerRow = new HBox(10);
        scannerRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        scannerRow.getChildren().addAll(new Label("Scanner URL:"), scannerUrlField, saveScannerButton, scannerSavedLabel);
        root.getChildren().add(scannerRow);

        // ── Issue row ──
        // Pack Size + "Issue Tokens" are intentionally NOT in the layout:
        // tokens are only obtained through the paid sign-up flows (pack size
        // is fixed at 100; trials choose 1-99 in the wizard). The field and
        // button objects stay alive because currentPackSize() and the
        // connect/issue state toggles still reference them.
        HBox issueRow = new HBox(15);
        issueRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        issueRow.getChildren().addAll(refreshPacksButton, issueStatus);
        root.getChildren().add(issueRow);

        // ── Packs table ──
        root.getChildren().add(packsTable);

        // ── Log file link ──
        Hyperlink viewLogLink = new Hyperlink("View perseverus.log");
        viewLogLink.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().open(
                        new java.io.File(PrivacyLog.get().path()));
            } catch (Exception ex) {
                log.warn("Could not open log file: {}", ex.getMessage());
            }
        });
        Button clearLogButton = new Button("Clear Log");
        clearLogButton.setOnAction(e -> {
            PrivacyLog.get().clearLog();
            log.info("Perseverus log cleared");
        });
        Label logPathLabel = new Label(PrivacyLog.get().path());
        logPathLabel.setStyle("-fx-font-size: 10; -fx-text-fill: #888;");
        HBox logRow = new HBox(8, viewLogLink, clearLogButton, logPathLabel);
        logRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        root.getChildren().add(new Separator());
        root.getChildren().add(logRow);

        // ── Payment Wallet section (watch-only wallets only) ──
        Wallet settingsMw = getWalletForm().getWallet();
        settingsMw = settingsMw.isMasterWallet() ? settingsMw : settingsMw.getMasterWallet();
        PerseverusPaymentManager settingsMgr = new PerseverusPaymentManager(settingsMw, getWalletForm().getStorage());
        if (!settingsMgr.isHotWallet()) {
            root.getChildren().add(new Separator());
            Label payWalletHeader = new Label("Payment Wallet");
            payWalletHeader.getStyleClass().add("issuance-subheader");
            Button openPayWalletButton = new Button("Open Payment Account");
            openPayWalletButton.setOnAction(e -> openPaymentWalletTab());
            Label payWalletDesc = new Label("Open the BTC Medusa payment account tab to view transaction history.");
            payWalletDesc.setStyle("-fx-font-size: 11; -fx-text-fill: #888;");
            payWalletDesc.setWrapText(true);
            VBox payWalletBox = new VBox(6, payWalletHeader, payWalletDesc, openPayWalletButton);
            root.getChildren().add(payWalletBox);
        }

        Scene scene = new Scene(root, 900, 560);
        // Load the same stylesheets so issuance CSS classes work
        try {
            scene.getStylesheets().add(getClass().getResource("privacy.css").toExternalForm());
            scene.getStylesheets().add(getClass().getResource("wallet.css").toExternalForm());
            scene.getStylesheets().add(getClass().getResource("/com/sparrowwallet/sparrow/general.css").toExternalForm());
            // Follow the app theme — without this, the separate-stage dialog
            // renders light (modena) even when Sparrow is in dark mode.
            if (Config.get().getTheme() == com.sparrowwallet.sparrow.Theme.DARK) {
                scene.getStylesheets().add(getClass().getResource("/com/sparrowwallet/sparrow/darktheme.css").toExternalForm());
            }
        } catch (Exception e) {
            log.debug("Could not load dialog stylesheets: {}", e.getMessage());
        }

        settingsStage.setScene(scene);
        // Just hide (don't destroy) when user closes, so state persists
        settingsStage.setOnCloseRequest(e -> {
            e.consume();
            settingsStage.hide();
        });
        settingsStage.show();
    }

    // ── Persistence ──

    /** Save scan results (privacyLabels) to Config. */
    private void persistScanResults() {
        Config.get().setPerseverusScanResults(new java.util.LinkedHashMap<>(privacyLabels));
        Config.get().setPerseverusScanReports(new java.util.LinkedHashMap<>(reportData));
    }

    /** Restore scan results from Config into the privacyLabels registry
     *  and apply them to matching UtxoRows in the results table. */
    private void restoreScanResults() {
        Map<String, String> saved = Config.get().getPerseverusScanResults();
        Map<String, String> savedReports = Config.get().getPerseverusScanReports();
        if (savedReports != null && !savedReports.isEmpty()) {
            reportData.putAll(savedReports);
        }
        if ((saved == null || saved.isEmpty()) && reportData.isEmpty()) return;

        if (saved != null) privacyLabels.putAll(saved);

        // Apply to any matching UtxoRows already in the table
        int applied = 0;
        for (UtxoRow row : rows) {
            String key = row.getTxid() + ":" + row.getVout();
            String tag = saved != null ? saved.get(key) : null;
            if (tag != null && !tag.isBlank()) {
                row.applyResult(resultFromDisplayTag(tag));
                applied++;
            }
            // Rebuild the full report (grade badge + score contribution).
            applyPersistedReport(row);
        }
        if (applied > 0 || !reportData.isEmpty()) {
            resultsTable.refresh();
            // Fire event so UTXOs tab can show the Import button
            EventManager.get().post(new PrivacyLabelsUpdatedEvent());
            log.info("Restored {} scan results from previous session", applied);
            updateScoreDisplay();
        }
    }

    /** Recompute the overall score from current labels and update the
     *  score number + letter-grade badge. Safe to call on the FX thread. */
    private void updateScoreDisplay() {
        int score = computeOverallScore();
        if (score < 0) {
            // No scored UTXOs in THIS account — clear any stale score/grade so
            // an unscanned account never shows another account's phantom grade.
            privacyScore.setText("");
            privacyScore.getStyleClass().removeAll("privacy-score-high", "privacy-score-medium", "privacy-score-low");
            letterGrade.setText("");
            letterGrade.getStyleClass().removeAll("grade-a", "grade-b", "grade-c", "grade-d", "grade-f");
            return;
        }
        privacyScore.setText(score + " / 100");
        privacyScore.getStyleClass().removeAll("privacy-score-high", "privacy-score-medium", "privacy-score-low");
        if (score >= 70) {
            privacyScore.getStyleClass().add("privacy-score-high");
        } else if (score >= 40) {
            privacyScore.getStyleClass().add("privacy-score-medium");
        } else {
            privacyScore.getStyleClass().add("privacy-score-low");
        }
        String grade = letterGradeFor(score);
        letterGrade.setText(grade);
        letterGrade.getStyleClass().removeAll("grade-a", "grade-b", "grade-c", "grade-d", "grade-f");
        letterGrade.getStyleClass().add("grade-" + grade.toLowerCase());
    }

    /**
     * Rebuild a row's full PrivacyReport (grade/score/details) from the
     * persisted raw tag + tx meta in {@link #reportData}, so restored rows
     * show their grade badge and contribute to the overall score without
     * re-querying the server (and without spending tokens).
     */
    private static void applyPersistedReport(UtxoRow row) {
        String enc = reportData.get(row.getTxid() + ":" + row.getVout());
        if (enc == null || enc.isBlank()) return;
        try {
            String[] parts = enc.split(";");
            String token = parts[0];
            if (!token.startsWith("V3LEAN:")) return;
            int in = row.getNumInputs();
            int out = row.getNumOutputs();
            long fee = row.getFeeSats();
            long val = row.getValueSats();
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].split("=", 2);
                if (kv.length != 2) continue;
                switch (kv[0]) {
                    case "in" -> in = Integer.parseInt(kv[1]);
                    case "out" -> out = Integer.parseInt(kv[1]);
                    case "fee" -> fee = Long.parseLong(kv[1]);
                    case "val" -> val = Long.parseLong(kv[1]);
                    default -> { }
                }
            }
            com.sparrowwallet.perseverus.V3LeanTag t =
                    com.sparrowwallet.perseverus.V3LeanTag.fromTagString(token);
            PrivacyReport report = com.sparrowwallet.perseverus.V3LeanReportBuilder.build(
                    t, row.getTxid(), row.getVout(), row.getBlockHeight(), val, in, out, fee);
            if (report != null) row.setReport(report);
        } catch (Exception ignored) {
            // Corrupt entry — leave the row without a rebuilt report.
        }
    }

    /** Convert a display-friendly KYC tag back into a Result for table display. */
    private static PrivacyQuery.Result resultFromDisplayTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return new PrivacyQuery.Result("—", "Unknown", "", "Not scanned");
        }
        return switch (tag) {
            case "Clean" -> new PrivacyQuery.Result("Clean", "Clean", "privacy-good", "OK (restored)");
            case "Coinbase (Mining)" -> new PrivacyQuery.Result("Coinbase (Mining)", "Coinbase", "privacy-neutral", "OK (restored)");
            case "CoinJoin" -> new PrivacyQuery.Result("CoinJoin", "CoinJoin", "privacy-good", "OK (restored)");
            case "Unknown" -> new PrivacyQuery.Result("Unknown", "Unknown", "privacy-good", "OK (restored)");
            default -> {
                if (tag.startsWith("KYC Exchange:")) {
                    yield new PrivacyQuery.Result(tag, "KYC Exchange", "privacy-bad", "OK (restored)");
                }
                yield new PrivacyQuery.Result(tag, "Unknown", "privacy-good", "OK (restored)");
            }
        };
    }

    /** Save all current packs to Config so they survive app restarts. */
    private void persistPacks() {
        List<Config.PersistedPack> persisted = new java.util.ArrayList<>();
        int totalTokens = 0, totalSpent = 0;
        for (PackRow pr : shared.packRows) {
            persisted.add(new Config.PersistedPack(
                    pr.packSize,
                    pr.pack.bytes(),
                    pr.getSpentArray(),
                    pr.issuedAt
            ));
            totalTokens += pr.packSize;
            boolean[] spent = pr.getSpentArray();
            if (spent != null) {
                for (boolean s : spent) { if (s) totalSpent++; }
            }
        }
        Config.get().setPerseverusPacks(persisted);
        PrivacyLog.get().packPersisted(persisted.size(), totalTokens, totalSpent);
        log.info("[perseverus] Persisted {} packs ({} tokens, {} spent)", persisted.size(), totalTokens, totalSpent);
    }

    /** Restore packs from Config into packRows on tab init. */
    private void restorePersistedPacks() {
        // Only restore once — all controllers share the same pack list.
        if (!shared.packRows.isEmpty()) return;

        List<Config.PersistedPack> saved = Config.get().getPerseverusPacks();
        if (saved == null || saved.isEmpty()) return;

        for (Config.PersistedPack pp : saved) {
            if (pp.getBlob() == null || pp.getBlob().length == 0) continue;
            IssuedPack pack = new IssuedPack(pp.getPackSize(), pp.getBlob());
            PackRow pr = new PackRow(pack, pp.getIssuedAt());
            // Restore spent state
            boolean[] spent = pp.getSpent();
            if (spent != null) {
                for (int i = 0; i < spent.length && i < pp.getPackSize(); i++) {
                    if (spent[i]) pr.markSpent(i);
                }
            }
            shared.packRows.add(pr);
        }

        if (!shared.packRows.isEmpty()) {
            int totalTokens = 0, totalSpent = 0;
            for (PackRow pr : shared.packRows) {
                totalTokens += pr.packSize;
                boolean[] spent = pr.getSpentArray();
                if (spent != null) { for (boolean s : spent) { if (s) totalSpent++; } }
            }
            PrivacyLog.get().packRestored(shared.packRows.size(), totalTokens, totalSpent);
            log.info("[perseverus] Restored {} packs ({} tokens, {} spent)",
                    shared.packRows.size(), totalTokens, totalSpent);

            selectedPack = shared.packRows.getFirst();
            packsTable.getSelectionModel().select(selectedPack);
            packsTable.setVisible(true);
            packsTable.setManaged(true);
            refreshServerMonthAsync();
        }

        // Restore demo mode
        boolean savedDemo = Config.get().isPerseverusDemoMode();
        if (savedDemo) {
            demoMode = true;
            demoModeToggle.setSelected(true);
        }
    }

    /**
     * Reload packs from Config into shared.packRows.
     * Called after Stripe payment completes so newly issued tokens
     * are immediately available for scanning.
     */
    private void reloadPacksFromConfig() {
        shared.packRows.clear();

        List<Config.PersistedPack> saved = Config.get().getPerseverusPacks();
        if (saved == null || saved.isEmpty()) return;

        for (Config.PersistedPack pp : saved) {
            if (pp.getBlob() == null || pp.getBlob().length == 0) continue;
            IssuedPack pack = new IssuedPack(pp.getPackSize(), pp.getBlob());
            PackRow pr = new PackRow(pack, pp.getIssuedAt());
            boolean[] spent = pp.getSpent();
            if (spent != null) {
                for (int i = 0; i < spent.length && i < pp.getPackSize(); i++) {
                    if (spent[i]) pr.markSpent(i);
                }
            }
            shared.packRows.add(pr);
        }

        if (!shared.packRows.isEmpty()) {
            selectedPack = shared.packRows.getFirst();
            packsTable.getSelectionModel().select(selectedPack);
            packsTable.setVisible(true);
            packsTable.setManaged(true);

            int totalRemaining = 0;
            for (PackRow pr : shared.packRows) totalRemaining += pr.remaining();
            log.info("[perseverus] Packs reloaded after payment: {} pack(s), {} tokens available",
                    shared.packRows.size(), totalRemaining);
            refreshServerMonthAsync();
        }
    }

    /**
     * Fetch the current server month (GET /epoch over Tor) on a background
     * thread and update {@link Shared#currentMonth}, then refresh the packs
     * table so the Status column reflects active/expired/not-yet-active.
     * Falls back to the local calendar month if the server is unreachable.
     */
    private void refreshServerMonthAsync() {
        new Thread(() -> {
            int month;
            try {
                month = com.sparrowwallet.perseverus.SpendClient.currentMonth();
            } catch (Throwable t) {
                java.time.LocalDate now = java.time.LocalDate.now();
                month = now.getYear() * 100 + now.getMonthValue();
            }
            final int resolved = month;
            Platform.runLater(() -> {
                shared.currentMonth = resolved;
                if (packsTable != null) {
                    packsTable.refresh();
                    // Surface the first currently-active pack so the user
                    // doesn't have to hunt for it among future-dated packs.
                    for (PackRow pr : shared.packRows) {
                        if ("Active".equals(pr.statusLabel(resolved))) {
                            packsTable.getSelectionModel().select(pr);
                            packsTable.scrollTo(pr);
                            break;
                        }
                    }
                }
            });
        }, "perseverus-epoch").start();
    }

    /** Render a YYYYMM month as e.g. "Jul 2026"; returns "—" for 0/invalid. */
    private static String formatMonth(int yyyymm) {
        if (yyyymm <= 0) return "—";
        int year = yyyymm / 100;
        int mon = yyyymm % 100;
        if (mon < 1 || mon > 12) return String.valueOf(yyyymm);
        String[] names = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                          "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return names[mon - 1] + " " + year;
    }

    private void updateUtxoSummary() {
        int count = rows.size();
        utxoSummary.setText(count + " unspent output" + (count != 1 ? "s" : ""));
    }

    // ── Perseverus welcome + trial ──

    @FXML
    public void openPerseverusWelcome(ActionEvent event) {
        showWelcomeDialog();
    }

    private void showWelcomeDialog() {
        PerseverusWelcomeDialog dlg = new PerseverusWelcomeDialog();
        dlg.initOwner(resultsTable.getScene().getWindow());
        dlg.initModality(Modality.WINDOW_MODAL);

        Optional<PerseverusWelcomeDialog.Result> result = dlg.showAndWait();

        // Do NOT mark as welcomed on dismiss or trial — the dialog
        // should keep appearing until the user actually signs up.
        result.ifPresent(r -> {
            switch (r) {
                case TRY_FREE -> {
                    log.info("[perseverus] User selected trial — $0.25 Lightning scan");
                    launchTrialFlow();
                }
                case SIGN_UP -> {
                    log.info("[perseverus] User selected sign up");
                    launchSignUpWizard();
                }
                case CANCELLED -> {
                    log.info("[perseverus] User dismissed welcome dialog");
                }
            }
        });
    }

    /**
     * Launches the 3-step sign-up payment wizard.
     * Hot wallet path: builds, signs, and broadcasts a silent payment tx.
     */
    /**
     * Launches the $0.25 Lightning trial popup: pay a quarter, get one scan
     * token (expires end of next month). Replaces the old free-3-scan trial.
     */
    private void launchTrialFlow() {
        Wallet wallet = getWalletForm().getWallet();
        Storage storage = getWalletForm().getStorage();
        if (storage == null) {
            log.error("[perseverus] Cannot launch trial — no storage for wallet");
            return;
        }
        PerseverusSignUpWizard wizard = new PerseverusSignUpWizard(wallet, storage);
        wizard.initOwner(resultsTable.getScene().getWindow());
        wizard.initModality(javafx.stage.Modality.WINDOW_MODAL);
        wizard.startTrial();   // switch to the trial screen before showing
        Optional<PerseverusSignUpWizard.Result> result = wizard.showAndWait();
        if (result.isPresent() && result.get() == PerseverusSignUpWizard.Result.TRIAL_TOKEN) {
            log.info("[perseverus] Trial token(s) issued via Lightning");
            scanStatus.setText("Trial scans ready — tokens issued");
            reloadPacksFromConfig();
        }
        // Always refresh the label: a purchase sets it to "BTC Medusa", and the
        // hidden reset hotspot clears it back to "Trial".
        updatePerseverusButtonLabel();
    }

    private void launchSignUpWizard() {
        Wallet wallet = getWalletForm().getWallet();
        Storage storage = getWalletForm().getStorage();

        if (storage == null) {
            log.error("[perseverus] Cannot launch sign-up wizard — no storage for wallet");
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Error");
            err.setHeaderText(null);
            err.setContentText("Unable to access wallet storage. Please try again.");
            PrivacyLog.get().info("POPUP [Error]: Unable to access wallet storage");
            err.showAndWait();
            return;
        }

        PerseverusPaymentManager manager = new PerseverusPaymentManager(
                wallet.isMasterWallet() ? wallet : wallet.getMasterWallet(), storage);

        PerseverusSignUpWizard wizard = new PerseverusSignUpWizard(wallet, storage);
        wizard.initOwner(resultsTable.getScene().getWindow());
        wizard.initModality(javafx.stage.Modality.WINDOW_MODAL);

        Optional<PerseverusSignUpWizard.Result> result = wizard.showAndWait();

        result.ifPresent(r -> {
            switch (r) {
                case PAYMENT_SENT -> {
                    log.info("[perseverus] Sign-up payment sent successfully");
                    updatePerseverusButtonLabel();
                    scanStatus.setText("Subscription active. Scan your wallet.");
                }
                case STRIPE_PAYMENT -> {
                    log.info("[perseverus] Stripe payment completed — tokens issued");
                    updatePerseverusButtonLabel();
                    scanStatus.setText("Subscription active — tokens issued via Stripe");
                    // Reload packs from Config so newly issued tokens are
                    // available for scanning without a manual Refresh.
                    reloadPacksFromConfig();
                }
                case BTC_PAYMENT_IN_PROGRESS -> {
                    String status = wizard.getBtcPaymentStatus();
                    log.info("[perseverus] BTC payment in progress — dialog closed early: {}", status);
                    scanStatus.setText(status != null ? status : "BTC payment broadcast — waiting for confirmation...");
                }
                case MANUAL_PAYMENT -> {
                    log.info("[perseverus] Manual payment selected — {} plan",
                            wizard.getSelectedPlan().getLabel());
                    manualPaymentPlan = wizard.getSelectedPlan();
                    showMakePaymentButton();
                }
                case AUTOMATIC_WATCH_ONLY -> {
                    log.info("[perseverus] Automatic watch-only payment — {} plan",
                            wizard.getSelectedPlan().getLabel());
                    manualPaymentPlan = wizard.getSelectedPlan();
                    // Automatic mode: the user opted to let the wallet pick inputs,
                    // so auto-selection is allowed here.
                    executeMakePayment(true);
                }
                case CANCELLED -> {
                    // nothing to do
                }
            }
        });
    }

    // ── Manual payment state ──
    private PerseverusPaymentManager.Plan manualPaymentPlan;
    private Button makePaymentButton;

    // ── Watch-only payment state (Path 2) ──
    // When the user broadcasts tx1 (to staging address), we listen for it
    // to arrive at the payment wallet, then auto-forward to the SP address.
    private volatile boolean watchOnlyPaymentPending = false;
    private volatile Address pendingStagingAddress;
    private volatile double pendingFeeRate;
    private volatile String pendingTx1Id;

    // ── Watch-only tx1+tx2 confirmation tracking ──
    // After tx1 is broadcast to staging and tx2 is auto-forwarded,
    // we watch for BOTH to confirm via Sparrow's native wallet sync.
    private volatile boolean watchOnlyTx1Pending = false;
    private volatile String watchOnlyTx1TxidHex;
    private volatile boolean watchOnlyTx1Confirmed = false;
    private volatile boolean watchOnlyTx2Pending = false;
    private volatile String watchOnlyTx2TxidHex;
    private volatile boolean watchOnlyTx2Confirmed = false;

    // ── Hot wallet payment tracking ──
    // After the user broadcasts from the Send tab, we detect the
    // "BTC Medusa" labeled transaction and track its confirmation.
    private volatile boolean hotWalletPaymentPending = false;
    private volatile String hotWalletPaymentLabel;
    private volatile String hotWalletPaymentTxid;
    private volatile String lastSyncedPaymentStatus;  // tracks what we last copied from shared.latestPaymentStatus
    // Guard against detecting pre-existing transactions as new payments
    private final java.util.Set<String> paymentStartedKnownTxids = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    // Guard against double token issuance from concurrent event handlers
    private static final java.util.concurrent.atomic.AtomicBoolean issuingTokens = new java.util.concurrent.atomic.AtomicBoolean(false);
    // Static guard: only ONE controller instance may fire the auto-forward.
    // Multiple PrivacyController instances exist (one per wallet tab); without
    // this guard, each instance would independently arm watchOnlyPaymentPending
    // and fire a duplicate auto-forward when tx1 arrives.
    private static final java.util.concurrent.atomic.AtomicBoolean autoForwardFiring = new java.util.concurrent.atomic.AtomicBoolean(false);
    // Failsafe: one-shot flag — after first wallet sync completes, scan the
    // payment child wallet for orphaned UTXOs that were never forwarded.
    private static final java.util.concurrent.atomic.AtomicBoolean orphanedUtxoCheckDone = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Payment status popup — shown during manual payment flow
    private volatile Stage paymentPopup;
    private volatile Label paymentPopupTitle;
    private volatile Label paymentPopupStatus;
    private volatile ProgressIndicator paymentPopupSpinner;
    private volatile VBox paymentPopupContent;
    private volatile javafx.scene.layout.HBox paymentPopupTxidRow;
    // Stash popup params so we can show it later (on broadcast, not on Make Payment click)
    private volatile long pendingPopupAmount;
    private volatile String pendingPopupLabel;

    /**
     * Shows the "Make Payment" button on its own row below the scan buttons.
     * The user selects UTXOs in the table, then clicks this button
     * to navigate to the Send tab with the SP address pre-filled.
     */
    private void showMakePaymentButton() {
        // Already showing?
        if (makePaymentButton != null) {
            return;
        }

        // Detect watch-only to adjust label and messaging
        Wallet wallet = getWalletForm().getWallet();
        Wallet masterWallet = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        boolean isWatchOnly = !manager.isHotWallet();

        String buttonLabel;
        if (isWatchOnly) {
            // Show the total including forwarding fee — just do the math,
            // don't call getStagingInfo() here (that creates the child wallet)
            Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
            double feeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();
            long totalAmount = manualPaymentPlan.getAmountSats() + manager.calculateForwardingFee(feeRate);
            buttonLabel = "Make Payment (" + String.format("%,d sats", totalAmount) + ")";
        } else {
            buttonLabel = "Make Payment (" + String.format("%,d sats", manualPaymentPlan.getAmountSats()) + ")";
        }

        makePaymentButton = new Button(buttonLabel);
        makePaymentButton.setStyle("-fx-font-weight: bold;");
        makePaymentButton.setMinWidth(200);
        makePaymentButton.setGraphicTextGap(5);
        org.controlsfx.glyphfont.Glyph payIcon =
                new org.controlsfx.glyphfont.Glyph("Font Awesome 5 Free Solid", "BITCOIN");
        payIcon.setFontSize(12);
        makePaymentButton.setGraphic(payIcon);

        // Manual flow: require the user to have selected at least one UTXO.
        makePaymentButton.setOnAction(e -> executeMakePayment(false));

        makePaymentRow.getChildren().clear();
        makePaymentRow.getChildren().add(makePaymentButton);
        makePaymentRow.setVisible(true);
        makePaymentRow.setManaged(true);

        if (isWatchOnly) {
            scanStatus.setText("Select UTXOs, then click Make Payment (2-tx watch-only flow)");
        } else {
            scanStatus.setText("Select UTXOs, then click Make Payment");
        }
    }

    /**
     * Removes the "Make Payment" button if it's showing.
     */
    private void hideMakePaymentButton() {
        if (makePaymentButton != null) {
            makePaymentRow.getChildren().clear();
            makePaymentRow.setVisible(false);
            makePaymentRow.setManaged(false);
            makePaymentButton = null;
            // Don't clear manualPaymentPlan here — it may still be needed
            // by the watch-only auto-forward listener. It's cleared when
            // the forward completes or the user cancels.
        }
    }

    /**
     * Registers a payment session with the SP scanner, navigates to the
     * Send tab with the scanner-returned address pre-filled, and starts
     * a background poller that watches for on-chain confirmation.
     *
     * <p>Flow:
     * <ol>
     *   <li>POST /payments to SP scanner → get payment_id + address</li>
     *   <li>Navigate to Send tab with that address + amount</li>
     *   <li>Poll GET /payments/{id} every 3s in background</li>
     *   <li>On "confirmed" → issue VOPRF tokens via connected service</li>
     * </ol>
     *
     * <p>Falls back to the hardcoded SP address if the scanner is
     * unreachable (e.g. not running locally).
     */
    private void executeMakePayment(boolean autoSelect) {
        if (manualPaymentPlan == null) {
            return;
        }

        // ── Step 0: Check if quoted price is stale ──
        PerseverusPaymentManager.StaleQuote stale =
                PerseverusPaymentManager.checkPriceStaleness(manualPaymentPlan);
        if (stale != null) {
            Alert requote = new Alert(Alert.AlertType.CONFIRMATION);
            requote.setTitle("Price Update Required");
            String dir = stale.pctChange >= 0 ? "increase" : "decrease";
            requote.setHeaderText("The BTC price has changed since your quote.");
            requote.setContentText(String.format(
                    "%d minutes have passed since the price was quoted.\n\n"
                    + "Original price: %,d sats  (BTC @ $%,.0f)\n"
                    + "Current price:  %,d sats  (BTC @ $%,.0f)\n\n"
                    + "That's a %.1f%% %s. Would you like to proceed at the new price?",
                    stale.minutesElapsed,
                    stale.quotedSats, stale.quotedBtcPrice,
                    stale.currentSats, stale.currentBtcPrice,
                    Math.abs(stale.pctChange) * 100, dir));
            ButtonType proceed = new ButtonType("Pay " + String.format("%,d", stale.currentSats) + " sats");
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            requote.getButtonTypes().setAll(proceed, cancel);
            PrivacyLog.get().info("POPUP [Price Update Required]: original="
                    + stale.quotedSats + " sats, current=" + stale.currentSats
                    + " sats, " + dir + "=" + String.format("%.1f%%", Math.abs(stale.pctChange) * 100));
            Optional<ButtonType> choice = requote.showAndWait();
            if (choice.isEmpty() || choice.get() != proceed) {
                // User declined — update the button label with new price and bail
                PrivacyLog.get().info("POPUP [Price Update Required]: user DECLINED requote");
                showMakePaymentButton();
                return;
            }
            PrivacyLog.get().info("POPUP [Price Update Required]: user ACCEPTED requote");
            log.info("[perseverus] User accepted requote: {} sats", manualPaymentPlan.getAmountSats());
            PrivacyLog.get().info("Requote accepted: " + manualPaymentPlan.getAmountSats() + " sats");
            // Update the button label to reflect new price
            showMakePaymentButton();
        }

        Wallet wallet = getWalletForm().getWallet();
        long amount = manualPaymentPlan.getAmountSats();

        PrivacyLog.get().paymentBtcStart(manualPaymentPlan.getLabel(), amount);

        // ── Step 1: Get SP address ──
        String spAddress = PerseverusPaymentManager.getSpAddressString();
        log.info("[perseverus] SP address: {}", spAddress);

        // ── Step 2: Gather selected UTXOs ──
        Set<String> selectedKeys = new HashSet<>();
        for (UtxoRow row : resultsTable.getItems()) {
            if (row.isSelected()) {
                selectedKeys.add(row.getTxid() + ":" + row.getVout());
            }
        }

        List<BlockTransactionHashIndex> selectedUtxos = new ArrayList<>();
        for (BlockTransactionHashIndex utxo : wallet.getSpendableUtxos().keySet()) {
            String key = utxo.getHashAsString() + ":" + utxo.getIndex();
            if (selectedKeys.contains(key)) {
                selectedUtxos.add(utxo);
            }
        }

        if (selectedUtxos.isEmpty() && !autoSelect) {
            // Manual mode: the user must explicitly choose which UTXO(s) to
            // spend. Do NOT silently fall back to auto-selecting coins — warn
            // and abort so they can tick a UTXO and try again.
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.initOwner(resultsTable.getScene().getWindow());
            warn.setTitle("Select a UTXO");
            warn.setHeaderText("No UTXO selected");
            warn.setContentText("Select at least one UTXO (tick its checkbox) to fund this "
                    + "payment, then click Make Payment again.");
            PrivacyLog.get().info("POPUP [Select a UTXO]: manual payment blocked — no UTXO selected");
            warn.showAndWait();
            showMakePaymentButton();   // keep the button so they can retry
            return;
        }

        if (selectedUtxos.isEmpty()) {
            // Automatic mode: pick the minimum UTXOs needed instead of all.
            // Estimate total amount: subscription + forwarding tx fee (~111 vB)
            // + tx1 overhead per input (~58 vB) + tx1 base (~53 vB).
            Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
            double estFeeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();
            long forwardingFeeEstimate = (long) Math.ceil(111.0 * estFeeRate);  // tx2: 1-in 1-out P2TR
            long tx1BaseEstimate = (long) Math.ceil(53.0 * estFeeRate);         // tx1 fixed overhead
            long tx1PerInputEstimate = (long) Math.ceil(58.0 * estFeeRate);     // per P2TR input
            long targetAmount = amount + forwardingFeeEstimate + tx1BaseEstimate + tx1PerInputEstimate;

            List<BlockTransactionHashIndex> allUtxos = new ArrayList<>(wallet.getSpendableUtxos().keySet());
            // Sort ascending by value for single-UTXO selection
            allUtxos.sort((a, b) -> Long.compare(a.getValue(), b.getValue()));

            // First try: smallest single UTXO that covers the target
            for (BlockTransactionHashIndex utxo : allUtxos) {
                if (utxo.getValue() >= targetAmount) {
                    selectedUtxos.add(utxo);
                    break;
                }
            }

            // If no single UTXO suffices, accumulate from largest down
            if (selectedUtxos.isEmpty()) {
                allUtxos.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                long accumulated = 0;
                for (BlockTransactionHashIndex utxo : allUtxos) {
                    selectedUtxos.add(utxo);
                    accumulated += utxo.getValue();
                    // Recalculate target with additional input fee
                    long adjustedTarget = amount + forwardingFeeEstimate + tx1BaseEstimate
                            + (tx1PerInputEstimate * selectedUtxos.size());
                    if (accumulated >= adjustedTarget) break;
                }
            }

            log.info("[perseverus] Automatic UTXO selection: picked {} of {} UTXOs (target≈{} sats)",
                    selectedUtxos.size(), allUtxos.size(), targetAmount);
        }

        // ── Step 3: Navigate to Send tab ──
        // For watch-only wallets: send to a staging child wallet address (normal
        // taproot), then the hot child wallet auto-forwards to the SP address.
        // For hot wallets: send directly to the SP address.
        Wallet masterWallet = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        boolean isWatchOnly = !manager.isHotWallet();

        EventManager.get().post(new SendActionEvent(wallet, selectedUtxos, true));

        final List<BlockTransactionHashIndex> lockedUtxos = selectedUtxos;

        if (isWatchOnly) {
            // Watch-only: get staging address from the payment child wallet
            try {
                PrivacyLog.get().info("═══ WATCH-ONLY PAYMENT FLOW (2-TX) INITIATED ═══");
                PrivacyLog.get().info("  Wallet: " + wallet.getDisplayName());
                PrivacyLog.get().info("  Plan: " + manualPaymentPlan.getLabel()
                        + ", amount=" + manualPaymentPlan.getAmountSats() + " sats");
                PrivacyLog.get().info("  Selected UTXOs for TX1: " + selectedUtxos.size());
                for (BlockTransactionHashIndex utxo : selectedUtxos) {
                    PrivacyLog.get().info(String.format("    %s:%d  value=%d sats  height=%s",
                            utxo.getHashAsString(), utxo.getIndex(), utxo.getValue(),
                            utxo.getHeight() > 0 ? String.valueOf(utxo.getHeight()) : "unconfirmed"));
                }

                Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
                double feeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();
                PrivacyLog.get().info("  Fee rate: " + feeRate + " sat/vB"
                        + (nextBlockRate != null ? " (next-block median)" : " (fallback)"));

                PerseverusPaymentManager.StagingInfo staging = manager.getStagingInfo(feeRate);
                Address stagingAddress = staging.address();
                long totalAmount = staging.totalAmount();

                PrivacyLog.get().info("  Staging address: " + stagingAddress);
                PrivacyLog.get().info("  Breakdown: subscription=" + staging.subscriptionAmount()
                        + " + forwardingFee=" + staging.forwardingFee() + " = total=" + totalAmount + " sats");

                Payment stagingPayment = new Payment(
                        stagingAddress, "BTC Medusa " + manualPaymentPlan.getLabel(), totalAmount, false);
                Platform.runLater(() -> {
                    EventManager.get().post(new SpendUtxoEvent(wallet, lockedUtxos,
                            List.of(stagingPayment), null, null, false, null, true));
                });

                // Arm the auto-forward listener
                pendingStagingAddress = stagingAddress;
                pendingFeeRate = feeRate;
                watchOnlyPaymentPending = true;

                log.info("[perseverus] Watch-only: sending {} sats to staging address {}, "
                        + "auto-forward will sweep to SP address",
                        totalAmount, stagingAddress);
                PrivacyLog.get().info("  → Navigating to Send tab. User must sign with HW wallet and broadcast.");
                PrivacyLog.get().info("  → Auto-forward listener armed. Waiting for tx1 to arrive at child wallet.");
            } catch (Exception e) {
                log.error("[perseverus] Failed to get staging info for watch-only wallet", e);
                PrivacyLog.get().info("WATCH-ONLY PAYMENT ERROR: " + e.getClass().getSimpleName()
                        + " — " + e.getMessage());
                AppServices.showErrorDialog("Watch-only Payment Error",
                        "Could not create staging address: " + e.getMessage());
                return;
            }
        } else {
            // Hot wallet: send directly to the SP address
            SilentPaymentAddress spAddr = SilentPaymentAddress.from(spAddress);
            SilentPayment payment = new SilentPayment(
                    spAddr, "BTC Medusa " + manualPaymentPlan.getLabel(), amount, false);
            Platform.runLater(() -> {
                EventManager.get().post(new SpendUtxoEvent(wallet, lockedUtxos,
                        List.of(payment), null, null, false, null, true));
            });
        }

        // ── Step 4: Persist pending payment state and arm confirmation watcher ──
        // Both hot and watch-only wallets track confirmation via Sparrow's
        // native wallet sync — no external SP scanner needed.
        if (isWatchOnly) {
            Config.get().setPerseverusPendingPayment(
                    "watch-only", null, manualPaymentPlan.name(), 0);
            Config.get().setPerseverusPendingWatchOnly(true);
            // Remember the quoted subscription sats so a relaunch can detect BTC
            // price drift and rebuild the unbroadcast tx at the current rate.
            Config.get().setPerseverusPendingQuotedSats(manualPaymentPlan.getAmountSats());
            scanStatus.setText("Sign with hardware wallet, then broadcast to start 2-tx flow");
            // Stash amount/label for the popup — it will be shown when tx1 is
            // detected at the child wallet (after the user signs + broadcasts).
            pendingPopupAmount = amount;
            pendingPopupLabel = manualPaymentPlan.getLabel();
        } else {
            // Hot wallet: persist pending state, arm the "BTC Medusa" label watcher.
            // The txid is unknown until the user broadcasts from the Send tab.
            // walletHistoryChanged will detect the outgoing tx by its label.
            Config.get().setPerseverusPendingPayment(
                    "hot-wallet", null, manualPaymentPlan.name(), 0);
            Config.get().setPerseverusPendingWatchOnly(false);

            // Snapshot all current txids so we don't falsely detect pre-existing transactions
            paymentStartedKnownTxids.clear();
            Wallet walletSnapshot = getWalletForm().getWallet();
            for (Sha256Hash txHash : walletSnapshot.getTransactions().keySet()) {
                paymentStartedKnownTxids.add(txHash.toString());
            }
            PrivacyLog.get().info("PAYMENT ARM: snapshotted " + paymentStartedKnownTxids.size() + " known txids");

            hotWalletPaymentPending = true;
            hotWalletPaymentLabel = "BTC Medusa " + manualPaymentPlan.getLabel();

            pendingPopupAmount = amount;
            pendingPopupLabel = manualPaymentPlan.getLabel();

            scanStatus.setText("Send " + String.format("%,d", amount)
                    + " sats, then wait for confirmation");
        }

        hideMakePaymentButton();
    }

    /**
     * On startup, check if there's a pending BTC payment that was broadcast
     * before the last shutdown. If so, resume tracking via Sparrow's native
     * wallet sync so the user doesn't lose track of their payment.
     */
    private void resumePendingPaymentPolling() {
        if (!Config.get().hasPerseverusPendingPayment()) {
            return;
        }

        String savedTxid = Config.get().getPerseverusPendingTxid();
        String savedPlan = Config.get().getPerseverusPendingPlan();
        long savedTime = Config.get().getPerseverusPendingTimestamp();
        long minutesAgo = (System.currentTimeMillis() - savedTime) / 60_000;
        boolean isWatchOnly = Config.get().isPerseverusPendingWatchOnly();

        log.info("[perseverus] Found pending payment from {}min ago — txid={}, plan={}, watchOnly={}",
                minutesAgo, savedTxid, savedPlan, isWatchOnly);

        if (isWatchOnly) {
            resumeWatchOnlyPayment(savedTxid, savedPlan);
        } else {
            resumeHotWalletPayment(savedTxid, savedPlan);
        }
    }

    /**
     * Resume a watch-only pending payment by relying on Sparrow's native
     * wallet sync. Ensures the payment child wallet tab is open, checks
     * if tx2 has already confirmed, and if not, arms a listener that
     * checks on every new block.
     */
    private void resumeWatchOnlyPayment(String savedTxid, String savedPlan) {
        String tx2Hex = Config.get().getPerseverusPendingTx2Txid();
        String tx1Hex = Config.get().getPerseverusPendingTx1Txid();

        PrivacyLog.get().info("═══ RESUMING WATCH-ONLY PAYMENT ═══");
        PrivacyLog.get().info("  savedTxid: " + (savedTxid != null ? savedTxid : "null"));
        PrivacyLog.get().info("  savedPlan: " + savedPlan);
        PrivacyLog.get().info("  tx1Hex: " + (tx1Hex != null ? tx1Hex : "null (tx1 not yet detected)"));
        PrivacyLog.get().info("  tx2Hex: " + (tx2Hex != null ? tx2Hex : "null (tx2 not yet broadcast)"));

        // Arm tx1 confirmation tracking if we have a persisted tx1 txid
        if (tx1Hex != null && !tx1Hex.isBlank()) {
            watchOnlyTx1TxidHex = tx1Hex;
            watchOnlyTx1Pending = true;
            watchOnlyTx1Confirmed = false;
            PrivacyLog.get().info("  TX1 tracking armed: " + tx1Hex);
        }

        // The payment child wallet tab is opened on first creation
        // (via ensurePaymentWallet → ChildWalletsAddedEvent) and is
        // automatically loaded from disk on restart.  Do NOT reopen it
        // here — the user asked that the BTC Medusa tab only appear
        // when explicitly requested from settings.

        if (tx2Hex == null || tx2Hex.isBlank()) {
            // tx2 was never broadcast. Check if tx1 ("BTC Medusa" label) has
            // already been broadcast and arrived at the child wallet. If it has
            // and the child wallet has spendable funds, the auto-forward listener
            // will pick it up. If not, the user still needs to sign/broadcast.
            log.info("[perseverus] Watch-only resume: no tx2 yet — arming auto-forward listener");
            PrivacyLog.get().info("  State: TX2 not yet broadcast — checking child wallet for TX1 arrival");

            Wallet masterWallet = getWalletForm().getWallet();
            masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
            Storage storage = getWalletForm().getStorage();
            PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
            Wallet paymentWallet = manager.getPaymentWallet();

            // Re-fetch fee rate for auto-forward
            Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
            pendingFeeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();
            PrivacyLog.get().info("  Fee rate for future TX2: " + pendingFeeRate + " sat/vB"
                    + (nextBlockRate != null ? " (next-block)" : " (fallback)"));

            if (paymentWallet != null) {
                long spendableBalance = paymentWallet.getSpendableUtxos().keySet().stream()
                        .mapToLong(BlockTransactionHashIndex::getValue).sum();
                PrivacyLog.get().info("  Child wallet found. Spendable balance: " + spendableBalance + " sats");
                PrivacyLog.get().info("  Child wallet UTXOs: " + paymentWallet.getSpendableUtxos().size());
                for (BlockTransactionHashIndex utxo : paymentWallet.getSpendableUtxos().keySet()) {
                    PrivacyLog.get().info(String.format("    %s:%d  value=%d  height=%s",
                            utxo.getHashAsString(), utxo.getIndex(), utxo.getValue(),
                            utxo.getHeight() > 0 ? String.valueOf(utxo.getHeight()) : "mempool"));
                }
                if (spendableBalance > 0) {
                    // tx1 already arrived at the child wallet but tx2 was never
                    // broadcast (previous auto-forward failed, or app crashed).
                    // Directly retry the auto-forward rather than waiting for a
                    // newWalletTransactions event that won't fire for old txs.
                    log.info("[perseverus] Watch-only resume: child wallet has {} spendable sats — "
                            + "retrying auto-forward NOW", spendableBalance);
                    PrivacyLog.get().info("  → TX1 already arrived! Retrying auto-forward immediately.");
                    scanStatus.setText("Staged payment detected — retrying auto-forward...");

                    // Check if tx1 is already confirmed (UTXO height > 0)
                    if (tx1Hex != null && !tx1Hex.isBlank()) {
                        BlockTransaction tx1Bt = paymentWallet.getTransactions()
                                .get(Sha256Hash.wrap(tx1Hex));
                        if (tx1Bt != null && tx1Bt.getHeight() > 0) {
                            watchOnlyTx1Confirmed = true;
                            watchOnlyTx1Pending = false;
                            PrivacyLog.get().info("  TX1 already confirmed at height " + tx1Bt.getHeight());
                        }
                    }

                    // Kick off auto-forward retry on a background thread
                    final Wallet fwMaster = masterWallet;
                    final Wallet fwPayment = paymentWallet;
                    final double fwFeeRate = pendingFeeRate;
                    final String fwTx1Id = tx1Hex != null ? tx1Hex : "unknown";
                    Thread retryThread = new Thread(() -> {
                        // Use the same static guard as the regular auto-forward
                        if (!autoForwardFiring.compareAndSet(false, true)) {
                            log.debug("[perseverus] Another auto-forward already in progress — skipping retry");
                            return;
                        }
                        try {
                            PerseverusPaymentManager retryManager =
                                    new PerseverusPaymentManager(fwMaster, storage);
                            PrivacyLog.get().info("═══ AUTO-FORWARD RETRY ON RESTART ═══");
                            Sha256Hash tx2Txid = retryManager.autoForward(fwPayment, fwFeeRate);
                            PrivacyLog.get().paymentBtcBroadcast(tx2Txid.toString(), 0);

                            // Persist the tx2 state
                            String plan = savedPlan != null ? savedPlan : "MONTHLY";
                            Config.get().setPerseverusPendingPayment(
                                    "watch-only", tx2Txid.toString(), plan, 0);
                            Config.get().setPerseverusPendingWatchOnly(true);
                            Config.get().setPerseverusPendingTx2Txid(tx2Txid.toString());

                            // Save tx2 label
                            try {
                                String pn = savedPlan != null ? savedPlan : "Monthly";
                                PerseverusLabelStore ls = new PerseverusLabelStore(fwMaster, storage);
                                ls.putLabel(tx2Txid.toString(), "BTC Medusa " + pn + " — Payment");
                            } catch (Exception e) {
                                log.warn("[perseverus] Failed to save tx2 label on retry", e);
                            }

                            // Arm confirmation watcher
                            watchOnlyTx2Pending = true;
                            watchOnlyTx2TxidHex = tx2Txid.toString();
                            watchOnlyPaymentPending = false;

                            log.info("[perseverus] Auto-forward retry SUCCESS: {}", tx2Txid);
                            Platform.runLater(() -> {
                                scanStatus.setText("Payment forwarded — waiting for confirmation...");
                            });
                        } catch (Exception e) {
                            log.error("[perseverus] Auto-forward retry FAILED", e);
                            PrivacyLog.get().info("AUTO-FORWARD RETRY FAILED: " + e.getMessage());
                            Platform.runLater(() -> {
                                scanStatus.setText("Auto-forward retry failed — will retry on next restart");
                            });
                        } finally {
                            autoForwardFiring.set(false);
                        }
                    }, "perseverus-auto-forward-retry");
                    retryThread.setDaemon(true);
                    retryThread.start();
                } else if (tx1Hex != null && !tx1Hex.isBlank()) {
                    // tx1 was already broadcast (txid is persisted) but the child
                    // wallet hasn't synced yet, so spendable balance is 0.
                    // The newWalletTransactions listener will trigger auto-forward
                    // once the wallet syncs and sees the tx1 UTXO.
                    PrivacyLog.get().info("  → TX1 already broadcast (" + tx1Hex + ") but child wallet not yet synced. Waiting for sync.");
                    scanStatus.setText("Payment in progress — waiting for wallet sync to resume auto-forward...");
                } else {
                    // Nothing broadcast yet (e.g. user closed at the sign/export-PSBT
                    // screen). Prompt to resume: re-check the BTC price and re-open
                    // the Send tab so they can sign at the current rate (or cancel).
                    PrivacyLog.get().info("  → No funds + no TX1. Prompting to resume the unbroadcast payment.");
                    scanStatus.setText("Pending payment — checking current rate…");
                    promptResumeUnbroadcastPayment(savedPlan);
                }
            } else {
                // Child wallet not loaded — recreate it with deterministic seed so
                // Electrum can discover existing UTXOs on sync.
                PrivacyLog.get().info("  Child wallet NOT found — recreating with deterministic seed");
                try {
                    manager.ensurePaymentWallet();
                    PrivacyLog.get().info("  Child wallet recreated — will detect TX1 after Electrum sync");
                    if (tx1Hex != null && !tx1Hex.isBlank()) {
                        scanStatus.setText("Payment in progress — wallet recreated, syncing...");
                    } else {
                        scanStatus.setText("Waiting for staged payment — sign and broadcast with hardware wallet");
                    }
                } catch (Exception e) {
                    log.error("[perseverus] Failed to recreate payment wallet on resume", e);
                    PrivacyLog.get().info("  FAILED to recreate child wallet: " + e.getMessage());
                    scanStatus.setText("Payment in progress — waiting for wallet sync...");
                }
            }

            watchOnlyPaymentPending = true;
            PrivacyLog.get().info("  → Auto-forward listener ARMED (watchOnlyPaymentPending=true)");
            return;
        }

        log.info("[perseverus] Watch-only resume: checking tx2 {} confirmation via Sparrow sync", tx2Hex);
        PrivacyLog.get().info("  State: TX2 already broadcast. Checking confirmation status...");
        PrivacyLog.get().info("  TX2 txid: " + tx2Hex);
        scanStatus.setText("Checking payment confirmation...");

        // Check if tx2 is already confirmed in the child wallet
        Wallet masterWallet = getWalletForm().getWallet();
        masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        Wallet paymentWallet = manager.getPaymentWallet();

        if (paymentWallet != null) {
            PrivacyLog.get().info("  Child wallet found. Total transactions: " + paymentWallet.getTransactions().size());
            Sha256Hash tx2Hash = Sha256Hash.wrap(tx2Hex);
            Map<Sha256Hash, BlockTransaction> txMap =
                    paymentWallet.getTransactions();
            BlockTransaction tx2 = txMap.get(tx2Hash);

            if (tx2 != null && tx2.getHeight() > 0) {
                // tx2 is already confirmed — issue tokens immediately
                // (tx2 spending tx1 implies tx1 is also confirmed)
                watchOnlyTx1Confirmed = true;
                watchOnlyTx1Pending = false;
                watchOnlyTx2Confirmed = true;
                log.info("[perseverus] Watch-only resume: tx2 already confirmed at height {}",
                        tx2.getHeight());
                PrivacyLog.get().info("  TX2 CONFIRMED at height " + tx2.getHeight() + " — issuing tokens!");
                scanStatus.setText("Payment confirmed — issuing tokens...");
                issueTokensAfterSpConfirmation();
                return;
            }

            if (tx2 != null) {
                log.info("[perseverus] Watch-only resume: tx2 found but unconfirmed (height={})",
                        tx2.getHeight());
                PrivacyLog.get().info("  TX2 found but UNCONFIRMED (height=" + tx2.getHeight()
                        + "). Waiting for next block...");
                scanStatus.setText("Payment forwarded — waiting for block confirmation...");
            } else {
                log.info("[perseverus] Watch-only resume: tx2 not yet visible in wallet — waiting for sync");
                PrivacyLog.get().info("  TX2 NOT visible in child wallet yet — waiting for Sparrow sync");
                scanStatus.setText("Waiting for payment sync — Sparrow is syncing the payment wallet...");
            }
        } else {
            // Child wallet not loaded — recreate it with deterministic seed.
            // addWalletTab fires WalletOpenedEvent → Electrum subscriptions → sync.
            log.warn("[perseverus] Watch-only resume: payment wallet not found — recreating");
            PrivacyLog.get().info("  WARNING: Child wallet NOT found — recreating with deterministic seed");
            try {
                manager.ensurePaymentWallet();
                PrivacyLog.get().info("  Child wallet recreated — waiting for Electrum sync");
                scanStatus.setText("Recreating payment wallet — waiting for sync...");
            } catch (Exception e) {
                log.error("[perseverus] Failed to recreate payment wallet", e);
                PrivacyLog.get().info("  FAILED to recreate child wallet: " + e.getMessage());
                scanStatus.setText("Payment wallet not found — waiting for wallet sync...");
            }
        }

        // Arm the confirmation watcher — on each new block or wallet history
        // change, we'll re-check if tx2 has confirmed.
        watchOnlyTx2Pending = true;
        watchOnlyTx2TxidHex = tx2Hex;

        // Stash popup params
        try {
            PerseverusPaymentManager.Plan plan =
                    PerseverusPaymentManager.Plan.valueOf(savedPlan);
            pendingPopupAmount = plan.getAmountSats();
            pendingPopupLabel = plan.getLabel();
        } catch (Exception ignored) {}
    }

    /**
     * On relaunch, an unbroadcast watch-only payment (user closed at the
     * sign/export-PSBT screen) is resumed here. We re-fetch the BTC price on a
     * background thread, then prompt: if the price drifted &gt;5% from the quoted
     * amount we say so, and on "Open to sign" we rebuild the staging payment at
     * the current rate and re-open the Send tab. "Cancel payment" clears the
     * pending state. (Per design option A: prompt, then open.)
     */
    private void promptResumeUnbroadcastPayment(String savedPlan) {
        final Wallet wallet = getWalletForm().getWallet();
        final Wallet masterWallet = wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
        final Storage storage = getWalletForm().getStorage();
        final long quotedSats = Config.get().getPerseverusPendingQuotedSats();
        PerseverusPaymentManager.Plan p;
        try { p = PerseverusPaymentManager.Plan.valueOf(savedPlan); }
        catch (Exception e) { p = PerseverusPaymentManager.Plan.MONTHLY; }
        final PerseverusPaymentManager.Plan plan = p;

        new Thread(() -> {
            // Refresh price off the UI thread (updates the Plan's static sats).
            double price = PerseverusPaymentManager.fetchBtcPrice();
            if (price <= 0) {
                price = PerseverusPaymentManager.fetchFallbackPrice();
            }
            final long freshSats = plan.getAmountSats();
            Double nextRate = AppServices.getNextBlockMedianFeeRate();
            final double feeRate = nextRate != null ? nextRate : AppServices.getFallbackFeeRate();

            Platform.runLater(() -> {
                boolean changed = quotedSats > 0
                        && Math.abs(freshSats - quotedSats) > quotedSats * 0.05;
                javafx.scene.control.Alert dlg =
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                dlg.initOwner(resultsTable.getScene().getWindow());
                dlg.setTitle("Resume BTC Medusa Payment");
                if (changed) {
                    dlg.setHeaderText("BTC price moved — your payment was recalculated.");
                    dlg.setContentText(String.format(
                            "You have a pending %s payment that was never broadcast.%n%n"
                            + "Quoted:  %,d sats%nCurrent: %,d sats (today's rate)%n%n"
                            + "Open the transaction to sign and broadcast at the current rate, or cancel it.",
                            plan.getLabel(), quotedSats, freshSats));
                } else {
                    dlg.setHeaderText("You have a pending payment to sign.");
                    dlg.setContentText(String.format(
                            "Your %s payment (%,d sats) was never broadcast.%n%n"
                            + "Open the transaction to sign and broadcast, or cancel it.",
                            plan.getLabel(), freshSats));
                }
                javafx.scene.control.ButtonType openBtn =
                        new javafx.scene.control.ButtonType("Open to sign");
                javafx.scene.control.ButtonType cancelBtn = new javafx.scene.control.ButtonType(
                        "Cancel payment", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
                dlg.getButtonTypes().setAll(openBtn, cancelBtn);

                java.util.Optional<javafx.scene.control.ButtonType> choice = dlg.showAndWait();
                if (choice.isEmpty() || choice.get() == cancelBtn) {
                    Config.get().clearPerseverusPendingPayment();
                    watchOnlyPaymentPending = false;
                    scanStatus.setText("Pending payment cancelled");
                    PrivacyLog.get().info("  Pending unbroadcast payment cancelled by user");
                    return;
                }

                // Open to sign: rebuild staging at the current rate and re-open the
                // Send tab (user re-selects inputs, signs with hardware, broadcasts).
                try {
                    PerseverusPaymentManager mgr = new PerseverusPaymentManager(masterWallet, storage);
                    PerseverusPaymentManager.StagingInfo staging = mgr.getStagingInfo(feeRate);
                    Address stagingAddress = staging.address();
                    long totalAmount = staging.totalAmount();
                    Config.get().setPerseverusPendingQuotedSats(freshSats);
                    pendingStagingAddress = stagingAddress;
                    pendingFeeRate = feeRate;
                    watchOnlyPaymentPending = true;
                    Payment stagingPayment = new Payment(stagingAddress,
                            "BTC Medusa " + plan.getLabel(), totalAmount, false);
                    EventManager.get().post(new SpendUtxoEvent(wallet, java.util.List.of(),
                            java.util.List.of(stagingPayment), null, null, false, null, true));
                    scanStatus.setText("Sign with your hardware wallet, then broadcast");
                    PrivacyLog.get().info("  Reopened Send tab for pending payment: "
                            + totalAmount + " sats to " + stagingAddress);
                } catch (Exception e) {
                    log.error("[perseverus] Failed to reopen pending payment on resume", e);
                    scanStatus.setText("Could not reopen payment — re-create it from Settings");
                }
            });
        }, "perseverus-resume-prompt").start();
    }

    /**
     * Resume a hot wallet pending payment by checking the wallet's
     * transaction history for the "BTC Medusa" labeled transaction.
     * No external SP scanner needed — Sparrow's native sync handles it.
     */
    private void resumeHotWalletPayment(String savedTxid, String savedPlan) {
        log.info("[perseverus] Hot wallet resume: txid={}, plan={}", savedTxid, savedPlan);

        // Stash popup params
        try {
            PerseverusPaymentManager.Plan plan =
                    PerseverusPaymentManager.Plan.valueOf(savedPlan);
            pendingPopupAmount = plan.getAmountSats();
            pendingPopupLabel = plan.getLabel();
        } catch (Exception ignored) {}

        if (savedTxid != null && !savedTxid.isBlank()) {
            // We already know the txid — check if it's confirmed
            Wallet wallet = getWalletForm().getWallet();
            Sha256Hash txHash = Sha256Hash.wrap(savedTxid);
            BlockTransaction tx = wallet.getTransactions().get(txHash);

            if (tx != null && tx.getHeight() > 0) {
                // Already confirmed — issue tokens immediately
                log.info("[perseverus] Hot wallet resume: tx {} already confirmed at height {}",
                        savedTxid, tx.getHeight());
                scanStatus.setText("Payment confirmed — issuing tokens...");
                issueTokensAfterSpConfirmation();
                return;
            }

            // Not yet confirmed — arm the watcher
            hotWalletPaymentTxid = savedTxid;
            String shortTxid = savedTxid.substring(0, Math.min(12, savedTxid.length()));
            if (tx != null) {
                scanStatus.setText("Payment broadcast (" + shortTxid + "...) — waiting for confirmation...");
            } else {
                scanStatus.setText("Waiting for payment sync (" + shortTxid + "...)...");
            }
        } else {
            // No broadcast txid was ever persisted — meaning the user did NOT
            // complete a broadcast in the session that created this pending
            // payment (e.g. they closed at the sign/Send screen).
            //
            // SECURITY: we must NOT scan the wallet's history for any tx merely
            // *labeled* "BTC Medusa" and treat it as "the" payment. Such a tx
            // could be from a PRIOR, already-redeemed subscription (the label is
            // reused for every payment), and concluding "confirmed" from it
            // would issue tokens with no fresh payment. Issuance is bound to the
            // exact txid we broadcast — nothing else. With no persisted txid,
            // there is no payment to confirm here.
            //
            // We still arm the LIVE watcher (snapshot-guarded) so a genuinely
            // NEW broadcast made after this point is detected, and we never
            // auto-issue from pre-existing history.
            hotWalletPaymentPending = true;
            hotWalletPaymentLabel = "BTC Medusa";

            Wallet wallet = getWalletForm().getWallet();
            paymentStartedKnownTxids.clear();
            for (Sha256Hash txHash : wallet.getTransactions().keySet()) {
                paymentStartedKnownTxids.add(txHash.toString());
            }
            PrivacyLog.get().info("PAYMENT RESUME ARM: snapshotted " + paymentStartedKnownTxids.size()
                    + " known txids (will NOT auto-issue from pre-existing history)");

            // No payment was broadcast. Do not auto-issue. Inform the user; the
            // armed watcher will pick up a genuinely new broadcast if they make
            // one, and issuance still requires a verified proof-of-payment.
            log.info("[perseverus] Hot wallet resume: no broadcast txid — not issuing; awaiting a fresh payment");
            scanStatus.setText("No completed payment found — start a new payment to get tokens");
        }
    }

    /**
     * Ensures the Perseverus Payment child wallet tab is open so Sparrow
     * syncs it. If the child wallet exists but isn't visible as a tab,
     * posts a ChildWalletsAddedEvent to open it.
     */
    private void ensurePaymentWalletTabOpen() {
        Wallet masterWallet = getWalletForm().getWallet();
        masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        Wallet paymentWallet = manager.getPaymentWallet();

        if (paymentWallet == null) {
            log.warn("[perseverus] Cannot open payment wallet tab — wallet not found");
            return;
        }

        // Check if the payment wallet is already in the open wallets map
        if (AppServices.get().getOpenWallets().containsKey(paymentWallet)) {
            log.debug("[perseverus] Payment wallet tab already open");
            return;
        }

        // Post event to add the child wallet tab
        log.info("[perseverus] Opening payment wallet tab for Sparrow sync");
        final Wallet mw = masterWallet;
        Platform.runLater(() -> {
            EventManager.get().post(
                    new ChildWalletsAddedEvent(storage, mw, paymentWallet));
        });
    }

    /**
     * Opens (or navigates to) the Perseverus Payment child wallet tab.
     * If the child wallet doesn't exist yet, shows an info message.
     * If the tab is already open, selects it; otherwise opens it first.
     */
    private void openPaymentWalletTab() {
        Wallet mw = getWalletForm().getWallet();
        mw = mw.isMasterWallet() ? mw : mw.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        PerseverusPaymentManager manager = new PerseverusPaymentManager(mw, storage);
        Wallet paymentWallet = manager.getPaymentWallet();

        // Create the payment wallet if it doesn't exist yet
        if (paymentWallet == null) {
            try {
                paymentWallet = manager.ensurePaymentWallet();
            } catch (Exception ex) {
                log.error("[perseverus] Failed to create payment wallet", ex);
                javafx.scene.control.Alert err = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR);
                err.setTitle("Payment Wallet");
                err.setHeaderText("Failed to Create Payment Wallet");
                err.setContentText(ex.getMessage());
                err.initOwner(settingsStage);
                err.showAndWait();
                return;
            }
        }

        if (AppServices.get().getOpenWallets().containsKey(paymentWallet)) {
            // Already open — just select it
            EventManager.get().post(new com.sparrowwallet.sparrow.event.FunctionActionEvent(
                    Function.TRANSACTIONS, paymentWallet));
        } else {
            // Open it then select it
            final Wallet masterRef = mw;
            final Wallet pw = paymentWallet;
            EventManager.get().post(
                    new ChildWalletsAddedEvent(storage, masterRef, paymentWallet));
            Platform.runLater(() -> EventManager.get().post(
                    new com.sparrowwallet.sparrow.event.FunctionActionEvent(
                            Function.TRANSACTIONS, pw)));
        }

        // Apply encrypted labels to the child wallet's transactions
        final Wallet pwRef = paymentWallet;
        final Wallet mwRef = mw;
        Platform.runLater(() -> applyLabelsToPaymentWallet(mwRef, pwRef));
    }

    /**
     * Decrypts the label store and applies labels to matching transactions
     * in the payment child wallet.  Safe to call multiple times — only
     * overwrites labels that are currently null/empty.
     */
    private void applyLabelsToPaymentWallet(Wallet masterWallet, Wallet paymentWallet) {
        try {
            PerseverusLabelStore labelStore = new PerseverusLabelStore(
                    masterWallet, getWalletForm().getStorage());
            Map<String, String> labels = labelStore.loadLabels();
            if (labels.isEmpty()) {
                return;
            }

            Map<Sha256Hash, BlockTransaction> txMap = paymentWallet.getTransactions();
            int applied = 0;
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                try {
                    Sha256Hash hash = Sha256Hash.wrap(entry.getKey());
                    BlockTransaction btx = txMap.get(hash);
                    if (btx != null) {
                        String existing = btx.getLabel();
                        if (existing == null || existing.isBlank()) {
                            btx.setLabel(entry.getValue());
                            applied++;
                        }
                    }
                } catch (Exception ignore) {
                    // Bad txid format — skip
                }
            }

            // Also label the UTXOs (BlockTransactionHashIndex) for the Addresses view
            for (WalletNode node : paymentWallet.getNode(KeyPurpose.RECEIVE).getChildren()) {
                for (BlockTransactionHashIndex utxo : node.getTransactionOutputs()) {
                    String txid = utxo.getHashAsString();
                    String label = labels.get(txid);
                    if (label != null && (utxo.getLabel() == null || utxo.getLabel().isBlank())) {
                        utxo.setLabel(label);
                        applied++;
                    }
                }
            }

            if (applied > 0) {
                log.info("[perseverus] Applied {} labels to payment wallet transactions", applied);
                PrivacyLog.get().info("Applied " + applied + " labels to BTC Medusa payment wallet");
            }
        } catch (Exception e) {
            log.warn("[perseverus] Failed to apply labels to payment wallet", e);
        }
    }

    /**
     * Opens a styled popup window showing the payment lifecycle.
     * The poller thread updates the labels via Platform.runLater.
     * Must be called on the FX thread.
     */
    /** Apply the app theme to a separate-stage dialog scene so it matches
     *  Sparrow's light/dark setting instead of defaulting to light modena. */
    private void applyDialogTheme(Scene scene) {
        try {
            scene.getStylesheets().add(getClass().getResource("/com/sparrowwallet/sparrow/general.css").toExternalForm());
            if (Config.get().getTheme() == com.sparrowwallet.sparrow.Theme.DARK) {
                scene.getStylesheets().add(getClass().getResource("/com/sparrowwallet/sparrow/darktheme.css").toExternalForm());
            }
        } catch (Exception e) {
            log.debug("Could not apply dialog theme: {}", e.getMessage());
        }
    }

    private void showPaymentStatusPopup(long amount, String planLabel) {
        if (paymentPopup != null && paymentPopup.isShowing()) {
            return; // already showing
        }

        Stage popup = new Stage();
        popup.initModality(Modality.NONE);
        popup.initOwner(resultsTable.getScene().getWindow());
        popup.setTitle("BTC Medusa — Payment");
        popup.setResizable(false);

        VBox root = new VBox(12);
        root.setPadding(new Insets(24));
        root.setAlignment(javafx.geometry.Pos.CENTER);
        root.setStyle("-fx-background-color: -fx-background;");

        // Logo
        ImageView logo = null;
        try {
            Image img = new Image(getClass().getResourceAsStream("/image/perseverus-white.png"));
            logo = new ImageView(img);
            logo.setFitWidth(50);
            logo.setFitHeight(50);
            logo.setPreserveRatio(true);
        } catch (Exception ignored) {}

        Label title = new Label("Waiting for Payment");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        title.setAlignment(javafx.geometry.Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        Label planInfo = new Label(planLabel + " — " + String.format("%,d sats", amount));
        planInfo.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        planInfo.setAlignment(javafx.geometry.Pos.CENTER);
        planInfo.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(40, 40);

        Label status = new Label("Send payment, then wait for confirmation...");
        status.setStyle("-fx-font-size: 13px;");
        status.setWrapText(true);
        status.setAlignment(javafx.geometry.Pos.CENTER);
        status.setMaxWidth(400);

        Label closeHint = new Label("It's OK to close this window. Updates will still be provided on the Privacy tab.");
        closeHint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7; -fx-font-style: italic;");
        closeHint.setWrapText(true);
        closeHint.setAlignment(javafx.geometry.Pos.CENTER);
        closeHint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        closeHint.setMaxWidth(400);

        Region spacer = new Region();
        spacer.setPrefHeight(5);

        Button closeBtn = new Button("Close");
        closeBtn.setPrefWidth(100);
        closeBtn.setStyle("-fx-font-size: 13px;");
        closeBtn.setOnAction(e -> popup.close());

        VBox content = new VBox(12);
        content.setAlignment(javafx.geometry.Pos.CENTER);
        if (logo != null) content.getChildren().add(logo);
        content.getChildren().addAll(title, planInfo, spinner, status, closeHint, spacer, closeBtn);

        root.getChildren().add(content);
        Scene paymentScene = new Scene(root, 460, 380);
        applyDialogTheme(paymentScene);
        popup.setScene(paymentScene);

        // Save references for the poller to update
        paymentPopup = popup;
        paymentPopupTitle = title;
        paymentPopupStatus = status;
        paymentPopupSpinner = spinner;
        paymentPopupContent = content;

        popup.show();
    }

    /**
     * Transforms the payment popup into a success screen.
     * Must be called on the FX thread.
     */
    private void showPaymentPopupSuccess(int tokenCount) {
        if (paymentPopup == null || !paymentPopup.isShowing() || paymentPopupContent == null) {
            return;
        }
        paymentPopupContent.getChildren().clear();

        ImageView logo = null;
        try {
            Image img = new Image(getClass().getResourceAsStream("/image/perseverus-white.png"));
            logo = new ImageView(img);
            logo.setFitWidth(50);
            logo.setFitHeight(50);
            logo.setPreserveRatio(true);
        } catch (Exception ignored) {}

        Label title = new Label("Payment Complete!");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #27ae60;");
        title.setAlignment(javafx.geometry.Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        Label subtitle = new Label("Your BTC Medusa subscription is now active.");
        subtitle.setStyle("-fx-font-size: 13px;");
        subtitle.setWrapText(true);
        subtitle.setAlignment(javafx.geometry.Pos.CENTER);
        subtitle.setMaxWidth(Double.MAX_VALUE);

        Label tokenInfo = new Label(tokenCount + " privacy tokens have been issued.\n"
                + "Each token allows one privacy scan.");
        tokenInfo.setStyle("-fx-font-size: 12px; -fx-opacity: 0.8;");
        tokenInfo.setWrapText(true);
        tokenInfo.setAlignment(javafx.geometry.Pos.CENTER);
        tokenInfo.setMaxWidth(400);

        Region spacer = new Region();
        spacer.setPrefHeight(10);

        Button closeBtn = new Button("Close");
        closeBtn.setPrefWidth(100);
        closeBtn.setStyle("-fx-font-size: 13px;");
        closeBtn.setOnAction(e -> paymentPopup.close());

        if (logo != null) paymentPopupContent.getChildren().add(logo);
        paymentPopupContent.getChildren().addAll(title, subtitle, tokenInfo, spacer, closeBtn);
    }

    /**
     * Issues VOPRF tokens after payment confirmation (detected via Sparrow sync).
     * Uses the connected PerseverusService (same as the Issue button).
     */
    /**
     * Show the broadcast txid in the payment popup as a clickable mempool.space
     * link plus a one-click Copy button (so the user can paste it privately),
     * and set the "waiting for confirmation" status. Idempotent — updates the
     * existing row if already shown.
     */
    /**
     * Record the broadcast txid that should back the clickable link in the
     * Privacy tab's "Status:" row, then refresh visibility. Pass {@code null}
     * to forget it. Must be called on the FX thread.
     */
    private void setStatusTxid(String txidHex) {
        statusTxid = (txidHex == null || txidHex.isBlank()) ? null : txidHex;
        refreshStatusTxLink();
    }

    /**
     * Show or hide the clickable mempool.space link beside the status text. The
     * link appears whenever the status is a broadcast/waiting message and a
     * pending-payment txid is known (from {@link #setStatusTxid}, the hot-wallet
     * txid, the shared pending txid, or the forwarded tx2). Driven by the status
     * listener in {@link #initializeView()}, so it covers every code path that
     * sets a "Payment broadcast …" status. Must be called on the FX thread.
     */
    private void refreshStatusTxLink() {
        if (scanStatusTxLink == null) {
            return;
        }
        String txid = statusTxid;
        if ((txid == null || txid.isBlank())) txid = hotWalletPaymentTxid;
        if ((txid == null || txid.isBlank()) && shared != null) txid = shared.pendingHotPaymentTxid;
        if ((txid == null || txid.isBlank())) txid = watchOnlyTx2TxidHex;

        String text = (scanStatus != null) ? scanStatus.getText() : null;
        boolean waiting = text != null
                && (text.contains("broadcast") || text.toLowerCase().contains("waiting for"));

        if (txid == null || txid.isBlank() || !waiting) {
            scanStatusTxLink.setVisible(false);
            scanStatusTxLink.setManaged(false);
            scanStatusTxLink.setOnAction(null);
            return;
        }
        final String full = txid;
        String shortTxid = full.length() > 16
                ? full.substring(0, 10) + "…" + full.substring(full.length() - 6)
                : full;
        scanStatusTxLink.setText(shortTxid + " ↗");
        scanStatusTxLink.setStyle("-fx-font-family: monospace;");
        scanStatusTxLink.setTooltip(new Tooltip("Open in mempool.space\n" + full));
        scanStatusTxLink.setOnAction(e -> {
            String network = PerseverusPaymentManager.isTestnet() ? "/testnet" : "";
            AppServices.get().getApplication().getHostServices()
                    .showDocument("https://mempool.space" + network + "/tx/" + full);
        });
        scanStatusTxLink.setVisible(true);
        scanStatusTxLink.setManaged(true);
    }

    private void setPaymentPopupTxid(String txidHex) {
        if (paymentPopupStatus != null) {
            paymentPopupStatus.setText("Payment seen — waiting for block confirmation…");
        }
        if (paymentPopupContent == null || txidHex == null || txidHex.isBlank()) {
            return;
        }
        String shortTxid = txidHex.length() > 20
                ? txidHex.substring(0, 10) + "…" + txidHex.substring(txidHex.length() - 6)
                : txidHex;

        Hyperlink txidLink = new Hyperlink(shortTxid);
        txidLink.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        txidLink.setTooltip(new Tooltip("Open in mempool.space\n" + txidHex));
        txidLink.setOnAction(e -> {
            String network = PerseverusPaymentManager.isTestnet() ? "/testnet" : "";
            AppServices.get().getApplication().getHostServices()
                    .showDocument("https://mempool.space" + network + "/tx/" + txidHex);
        });

        Button copyBtn = new Button("Copy");
        copyBtn.setStyle("-fx-font-size: 10px;");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(txidHex);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("Copied ✓");
        });

        if (paymentPopupTxidRow == null
                || !paymentPopupContent.getChildren().contains(paymentPopupTxidRow)) {
            paymentPopupTxidRow = new javafx.scene.layout.HBox(8, txidLink, copyBtn);
            paymentPopupTxidRow.setAlignment(javafx.geometry.Pos.CENTER);
            int idx = (paymentPopupStatus != null)
                    ? paymentPopupContent.getChildren().indexOf(paymentPopupStatus) : -1;
            if (idx >= 0) {
                paymentPopupContent.getChildren().add(idx + 1, paymentPopupTxidRow);
            } else {
                paymentPopupContent.getChildren().add(paymentPopupTxidRow);
            }
        } else {
            paymentPopupTxidRow.getChildren().setAll(txidLink, copyBtn);
        }
    }

    private void issueTokensAfterSpConfirmation() {
        // Guard against double-issuance from concurrent event handlers.
        // Static CAS prevents concurrent calls across all PrivacyController instances.
        if (!issuingTokens.compareAndSet(false, true)) {
            log.warn("[perseverus] Token issuance already in progress — skipping duplicate call");
            PrivacyLog.get().info("ISSUE GUARD: duplicate call blocked (static CAS)");
            return;
        }

        // Second guard: if pending payment was already cleared (tokens already issued
        // by another controller instance that completed before we acquired the CAS),
        // release the lock and bail out.
        if (!Config.get().hasPerseverusPendingPayment()) {
            log.info("[perseverus] Token issuance skipped — no pending payment in Config (already issued)");
            PrivacyLog.get().info("ISSUE GUARD: no pending payment — tokens already issued");
            issuingTokens.set(false);
            return;
        }

        // If we're not connected to the Perseverus server yet, do NOT clear the
        // pending state. On relaunch the confirmation often fires before
        // auto-connect/bootstrap finishes; clearing here would lose the payment.
        // Leave everything intact, make sure the connection is in flight, and
        // retry once it's up.
        if (shared.service == null) {
            issuingTokens.set(false);
            autoConnectAndBootstrap();   // idempotent — ensures connect is starting
            Platform.runLater(() -> scanStatus.setText(
                    "Payment confirmed — connecting to issue tokens…"));
            log.info("[perseverus] Payment confirmed but not connected yet — waiting for connection to issue");
            PrivacyLog.get().info("ISSUE: confirmed but service not up — will retry once connected (pending kept)");
            new Thread(() -> {
                for (int i = 0; i < 60; i++) {           // up to ~5 minutes
                    if (shared.service != null) {
                        Platform.runLater(this::issueTokensAfterSpConfirmation);
                        return;
                    }
                    try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
                }
                log.warn("[perseverus] Still not connected — payment left pending for next launch/connect");
            }, "perseverus-issue-wait").start();
            return;
        }

        // Connected. Determine the confirmed silent-payment tx + scanner. The
        // proof-of-payment is NOT read from disk — it is computed FRESH at claim
        // time from the broadcast tx (see acquireSigningWalletAndProve), so the
        // broadcast step never persists a signature. Capture identifiers before
        // touching pending state.
        final boolean watchOnly = Config.get().isPerseverusPendingWatchOnly();
        String txidTmp = watchOnly
                ? Config.get().getPerseverusPendingTx2Txid()
                : Config.get().getPerseverusPendingTxid();
        if ((txidTmp == null || txidTmp.isBlank()) && hotWalletPaymentTxid != null) {
            txidTmp = hotWalletPaymentTxid;
        }
        final String spTxid = (txidTmp != null && !txidTmp.isBlank()) ? txidTmp : null;
        // Resolve the scanner URL the SAME way the payment-registration path does
        // (configured URL → localhost dev fallback). Previously this path alone
        // hard-required a config value, so a confirmed payment could dead-end at
        // the mint step even though the rest of the flow had reached the scanner.
        String scannerUrlTmp = PerseverusSignUpWizard.scannerBaseUrl();
        final String scannerUrl = (scannerUrlTmp != null && !scannerUrlTmp.isBlank()) ? scannerUrlTmp : null;

        // ── PAYMENT GATE ──────────────────────────────────────────────────
        // We need a scanner to prove payment TO, and the confirmed tx to prove
        // it WITH. Missing either → refuse; never mint ungated. (Pending kept.)
        if (scannerUrl == null || spTxid == null) {
            issuingTokens.set(false);
            final String why = scannerUrl == null
                    ? "no BTC Medusa scanner URL is configured"
                    : "no confirmed payment transaction was found";
            log.warn("[perseverus] Refusing BTC issuance — {} (pending kept, NOT issuing ungated)", why);
            PrivacyLog.get().info("ISSUE REFUSED: " + why + " — ungated issuance not permitted");
            Platform.runLater(() -> {
                scanStatus.setText("Cannot issue tokens — " + why);
                Alert err = new Alert(Alert.AlertType.WARNING);
                err.initOwner(resultsTable.getScene().getWindow());
                err.setTitle("Cannot Issue Tokens");
                err.setHeaderText("Token issuance requires proof of a Bitcoin payment.");
                err.setContentText("Tokens are only issued after the BTC Medusa scanner verifies a "
                        + "confirmed on-chain payment to the subscription address.\n\n"
                        + "Reason: " + why + ".\n\n"
                        + (scannerUrl == null
                           ? "Set the scanner URL in Settings, then retry."
                           : "Make a payment to the BTC Medusa address to obtain tokens."));
                PrivacyLog.get().info("POPUP [Cannot Issue Tokens]: " + why);
                err.showAndWait();
            });
            return;
        }

        // Acquire a signing wallet (decrypt if needed), compute the proof from
        // the confirmed tx, then redeem. issuingTokens stays held until the flow
        // completes or aborts (released inside).
        acquireSigningWalletAndProve(watchOnly, spTxid, scannerUrl);
    }

    /**
     * Obtain a wallet that can sign the proof-of-payment for {@code spTxid}, then
     * hand off to {@link #proveAndRedeem}. For the watch-only flow the funding
     * wallet is the deterministic child payment wallet (keys always available);
     * for a hot wallet it is the master wallet, decrypted first if encrypted.
     */
    private void acquireSigningWalletAndProve(boolean watchOnly, String spTxid, String scannerUrl) {
        Wallet master = getWalletForm().getWallet();
        master = master.isMasterWallet() ? master : master.getMasterWallet();
        final Sha256Hash txid;
        try {
            txid = Sha256Hash.wrap(spTxid);
        } catch (Exception e) {
            issuingTokens.set(false);
            Platform.runLater(() -> scanStatus.setText("Cannot issue tokens — bad payment txid"));
            return;
        }

        if (watchOnly) {
            // Child payment wallet is a deterministic hot wallet — no password.
            Storage storage = getWalletForm().getStorage();
            PerseverusPaymentManager manager = new PerseverusPaymentManager(master, storage);
            Wallet child = manager.getPaymentWallet();
            if (child == null) {
                issuingTokens.set(false);
                Platform.runLater(() -> scanStatus.setText("Cannot issue tokens — payment wallet unavailable"));
                return;
            }
            proveAndRedeem(child, false, txid, scannerUrl);
            return;
        }

        if (!master.isEncrypted()) {
            proveAndRedeem(master, false, txid, scannerUrl);
            return;
        }

        // Hot encrypted: prompt for the wallet password and decrypt a copy so we
        // can recover the funding input key and sign the claim.
        final Wallet masterF = master;
        Platform.runLater(() -> {
            com.sparrowwallet.sparrow.control.WalletPasswordDialog dlg =
                    new com.sparrowwallet.sparrow.control.WalletPasswordDialog(
                            masterF.getMasterName(),
                            com.sparrowwallet.sparrow.control.WalletPasswordDialog.PasswordRequirement.LOAD);
            dlg.initOwner(resultsTable.getScene().getWindow());
            java.util.Optional<com.sparrowwallet.drongo.SecureString> pw = dlg.showAndWait();
            if (pw.isEmpty()) {
                // Declined — keep pending so they can authorize later.
                issuingTokens.set(false);
                scanStatus.setText("Subscription needs your wallet password to authorize");
                PrivacyLog.get().info("ISSUE: user declined password — subscription deferred (pending kept)");
                return;
            }
            scanStatus.setText("Authorizing subscription…");
            Storage.DecryptWalletService dec =
                    new Storage.DecryptWalletService(masterF.copy(), pw.get());
            dec.setOnSucceeded(ev -> proveAndRedeem(dec.getValue(), true, txid, scannerUrl));
            dec.setOnFailed(ev -> {
                issuingTokens.set(false);
                log.error("[perseverus] claim-time wallet decryption failed", dec.getException());
                scanStatus.setText("Could not authorize — incorrect password");
            });
            dec.start();
        });
    }

    /**
     * Compute the claim-time proof from {@code spTxid} using {@code signingWallet}
     * and redeem a token pack from the scanner. Runs the network work on a
     * background thread. Clears pending only once the proof is in hand (committed
     * to this attempt). Always releases the {@code issuingTokens} guard.
     *
     * @param clearPrivateAfter wipe {@code signingWallet}'s private keys when done
     *                          (true for a decrypted master copy)
     */
    private void proveAndRedeem(Wallet signingWallet, boolean clearPrivateAfter,
                                Sha256Hash spTxid, String scannerUrl) {
        final int packSize = currentPackSize();
        PrivacyLog.get().issueStart(packSize);
        Thread issueThread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                Wallet master = getWalletForm().getWallet();
                master = master.isMasterWallet() ? master : master.getMasterWallet();
                PerseverusPaymentManager manager =
                        new PerseverusPaymentManager(master, getWalletForm().getStorage());

                // Compute the proof fresh from the confirmed tx (no stored signature).
                PerseverusPaymentManager.SpPaymentProof proof =
                        manager.computePaymentProofFromTx(signingWallet, spTxid);
                if (proof == null) {
                    issuingTokens.set(false);
                    Platform.runLater(() -> {
                        scanStatus.setText("Cannot issue tokens — could not prove payment from tx");
                        Alert err = new Alert(Alert.AlertType.WARNING);
                        err.initOwner(resultsTable.getScene().getWindow());
                        err.setTitle("Cannot Issue Tokens");
                        err.setHeaderText("Could not prove the Bitcoin payment.");
                        err.setContentText("The payment transaction must have a single input owned by "
                                + "this wallet so we can sign the subscription claim with its key.\n\n"
                                + "Your payment is safe; this only blocks automatic token issuance.");
                        PrivacyLog.get().info("POPUP [Cannot Issue Tokens]: proof-from-tx returned null");
                        err.showAndWait();
                    });
                    return;
                }

                // Committed to this attempt — clear pending so we don't double-issue.
                Platform.runLater(() -> {
                    Config.get().clearPerseverusPendingPayment();
                    PrivacyLog.get().info("ISSUE: cleared pending payment state (proof in hand)");
                });

                java.time.LocalDate expDate = java.time.LocalDate.now().plusMonths(12);
                int expMonth = expDate.getYear() * 100 + expDate.getMonthValue();
                log.info("[perseverus] BTC issuance via claim-time proof (scanner {})", scannerUrl);
                IssuedPack pack = shared.service.redeemBtcSubscription(
                        scannerUrl, packSize, expMonth,
                        proof.pubkeyHex(), proof.nonceHex(), proof.signatureHex());
                long elapsed = System.currentTimeMillis() - t0;
                PrivacyLog.get().issueComplete(pack.packSize(), elapsed);

                Platform.runLater(() -> {
                    PackRow packRow = new PackRow(pack);
                    shared.packRows.addFirst(packRow);
                    selectedPack = packRow;
                    packsTable.getSelectionModel().select(packRow);
                    packsTable.setVisible(true);
                    packsTable.setManaged(true);

                    Config.get().setPerseverusWelcomed(true);
                    Config.get().setPerseverusTrialMode(false);
                    updatePerseverusButtonLabel();
                    persistPacks();

                    String statusMsg = "Subscription active — " + pack.packSize() + " tokens issued via Bitcoin payment";
                    scanStatus.setText(statusMsg);
                    shared.latestPaymentStatus = statusMsg;
                    lastSyncedPaymentStatus = statusMsg;
                    log.info("[perseverus] SP payment complete — {} tokens issued", pack.packSize());
                    PrivacyLog.get().info("SP payment flow complete — " + pack.packSize() + " tokens issued");

                    if (paymentPopup != null && paymentPopup.isShowing()) {
                        showPaymentPopupSuccess(pack.packSize());
                    } else if (watchOnlyPopup != null && watchOnlyPopup.isShowing()) {
                        showWatchOnlyPopupSuccess(pack.packSize());
                    } else {
                        Alert success = new Alert(Alert.AlertType.INFORMATION);
                        success.initOwner(resultsTable.getScene().getWindow());
                        success.setTitle("Payment Complete");
                        success.setHeaderText("BTC Medusa subscription is now active!");
                        success.setContentText(pack.packSize() + " privacy tokens have been issued.\n"
                                + "Each token allows one privacy scan.");
                        PrivacyLog.get().info("POPUP [Payment Complete]: " + pack.packSize()
                                + " tokens issued — subscription active");
                        success.showAndWait();
                    }
                });
            } catch (Exception e) {
                PrivacyLog.get().issueFailed(packSize, e.getMessage());
                log.error("[perseverus] Token issuance after SP payment failed", e);
                Platform.runLater(() -> {
                    scanStatus.setText("Payment confirmed — token issuance failed: " + e.getMessage());
                    Alert err = new Alert(Alert.AlertType.WARNING);
                    err.initOwner(resultsTable.getScene().getWindow());
                    err.setTitle("Token Issuance Failed");
                    err.setHeaderText("Payment was confirmed, but token issuance failed.");
                    err.setContentText("Error: " + e.getMessage() + "\n\n"
                            + "Your payment is safe. Re-open BTC Medusa to retry the subscription.");
                    PrivacyLog.get().info("POPUP [Token Issuance Failed]: " + e.getMessage());
                    err.showAndWait();
                });
            } finally {
                if (clearPrivateAfter && signingWallet != null) {
                    try { signingWallet.clearPrivate(); } catch (Exception ignored) {}
                }
                issuingTokens.set(false);
            }
        }, "sp-token-issuance");
        issueThread.setDaemon(true);
        issueThread.start();
    }

    private void updatePerseverusButtonLabel() {
        if (perseverusButton == null) return;

        // "Trial" until the user has bought at least one trial scan (or
        // subscribed / demo), after which it reads "BTC Medusa" — persisted, so
        // it stays that way forever unless cleared via the hidden reset hotspot.
        if (Config.get().isPerseverusWelcomed()
                || Config.get().isPerseverusTrialPurchased()
                || Config.get().isPerseverusDemoMode()) {
            perseverusButton.setText("BTC Medusa");
        } else {
            perseverusButton.setText("Trial");
        }
    }

    // ── Scan entry points ──

    @FXML
    public void startScan(ActionEvent event) {
        runScan(new ArrayList<>(rows), true);
    }

    @FXML
    public void startSelectedScan(ActionEvent event) {
        List<UtxoRow> selected = rows.stream()
                .filter(UtxoRow::isSelected)
                .collect(Collectors.toList());
        if (selected.isEmpty()) {
            scanStatus.setText("No UTXOs selected");
            return;
        }
        // Skip already-scanned UTXOs but keep errors for rescan.
        // This prevents wasting tokens on rows that already have results.
        List<UtxoRow> needsScan = selected.stream()
                .filter(r -> !r.isScanned())
                .collect(Collectors.toList());
        if (needsScan.isEmpty()) {
            scanStatus.setText("All selected UTXOs have already been scanned");
            return;
        }
        int skipped = selected.size() - needsScan.size();
        if (skipped > 0) {
            log.info("Scan Selected: skipping {} already-scanned UTXO(s), scanning {} remaining",
                    skipped, needsScan.size());
        }
        runScan(needsScan, false);
    }

    /**
     * Invisible reset button for demos: clears all scan state so the presenter
     * can re-run a scan without restarting the app.
     */
    @FXML
    public void resetDemo(javafx.scene.input.MouseEvent event) {
        if (scanning) {
            return;
        }
        for (UtxoRow r : rows) {
            r.clearToDefault();
        }
        privacyLabels.clear();
        reportData.clear();
        persistScanResults();          // wipe saved KYC tags from Config
        persistPacks();                // wipe saved packs from Config
        shared.autoConnectStarted = false; // allow re-connect after reset
        // Reset trial state so demo can be re-run
        Config.get().setPerseverusTrialScansUsed(0);
        Config.get().setPerseverusTrialMode(false);
        Config.get().setPerseverusTrialPurchased(false);  // button reverts to "Trial"
        Config.get().setPerseverusWelcomed(false);
        updatePerseverusButtonLabel();
        EventManager.get().post(new PrivacyLabelsUpdatedEvent());
        privacyScore.setText("");
        privacyScore.getStyleClass().removeAll("privacy-score-high", "privacy-score-medium", "privacy-score-low");
        letterGrade.setText("");
        letterGrade.getStyleClass().removeAll("grade-a", "grade-b", "grade-c", "grade-d", "grade-f");
        scanStatus.setText("Not scanned");
        scanProgress.setProgress(0);
        scanProgress.setVisible(false);
        scanProgress.setManaged(false);
        scanProgressLabel.setText("");
        scanProgressBox.setVisible(false);
        scanProgressBox.setManaged(false);
        downloadHelpIcon.setVisible(false);
        downloadHelpIcon.setManaged(false);
        downloadingLabel.setText("");
        decoysField.setText(String.valueOf(DEFAULT_DECOYS));
        resultsTable.refresh();

        // Reset issuance state
        if (shared.service != null) {
            try { shared.service.close(); } catch (Exception e) { /* ignore */ }
            shared.service = null;
        }
        selectedPack = null;
        shared.packRows.clear();
        packsTable.setVisible(false);
        packsTable.setManaged(false);
        connectionStatus.setText("Not connected");
        connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
        issueStatus.setText("");
        connectButton.setText("Connect");
        connectButton.setDisable(false);
        issueButton.setDisable(true);

        // Reset spend state
        shared.bootstrapped = false;
        bootstrapButton.setDisable(true);
        bootstrapStatus.setText("Not bootstrapped");
        bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
        spendButton.setDisable(true);
        spendIndexField.setText("0");
        spendInputField.setText("");
        spendResultLabel.setText("");
        spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
    }

    // ── Issuance actions ──

    /** Parse pack size, clamped to [1, 200]. */
    private static final int DEFAULT_PACK_SIZE = 100;

    private int currentPackSize() {
        String text = packSizeField.getText();
        if (text == null || text.isBlank()) return DEFAULT_PACK_SIZE;
        try {
            int n = Integer.parseInt(text.trim());
            if (n < 1) return 1;
            if (n > 200) return 200;
            return n;
        } catch (NumberFormatException ex) {
            return DEFAULT_PACK_SIZE;
        }
    }

    // ── Demo mode ──

    private static final java.util.Random DEMO_RNG = new java.util.Random();
    /** Average encrypted block-filter size used to model demo download time (~40 KB). */
    private static final int DEMO_FILTER_BYTES = 40 * 1024;
    /** Modelled Tor download throughput for the demo (~100 KB/s). */
    private static final double DEMO_TOR_SPEED_BPS = 100.0 * 1024;

    /** Cycles through 0–4 mapping to grades F → D → C → B → A. */
    private int demoScanCycle = 0;
    private int demoPackCounter = 0;

    public void toggleDemoMode(ActionEvent event) {
        demoMode = demoModeToggle.isSelected();
        Config.get().setPerseverusDemoMode(demoMode);
        // Apply demo-active style to the settings dialog root if open
        if (settingsStage != null && settingsStage.getScene() != null) {
            VBox root = (VBox) settingsStage.getScene().getRoot();
            if (demoMode) {
                if (!root.getStyleClass().contains("demo-active")) {
                    root.getStyleClass().add("demo-active");
                }
            } else {
                root.getStyleClass().remove("demo-active");
            }
        }
        if (demoMode) {
            // Disconnect the real server first
            if (shared.service != null) {
                try { shared.service.close(); } catch (Exception e) { /* ignore */ }
                shared.service = null;
                PrivacyLog.get().disconnect("demo mode enabled");
            }
            shared.bootstrapped = false;
            bootstrapButton.setDisable(true);
            bootstrapStatus.setText("Not bootstrapped");
            bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendButton.setDisable(true);

            // Save real values before overwriting with demo placeholders
            savedRealServerUrl = serverUrl.getText();
            savedRealPubkey = serverPubkeyField.getText();
            if (serverUrl.getText().isBlank() || serverUrl.getText().equals(DEFAULT_SERVER_URL)) {
                serverUrl.setText("http://demo-server:3030");
            }
            if (serverPubkeyField.getText().isBlank()) {
                serverPubkeyField.setText("demo-pubkey-not-a-real-bls-point");
            }
            connectionStatus.setText("Not connected");
            connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            connectButton.setText("Connect");
            log.info("Demo mode enabled — disconnected from real server");
        } else {
            // Restore the real server URL and pubkey
            if (savedRealServerUrl != null && !savedRealServerUrl.isBlank()) {
                serverUrl.setText(savedRealServerUrl);
            } else {
                serverUrl.setText(DEFAULT_SERVER_URL);
            }
            if (savedRealPubkey != null && !savedRealPubkey.isBlank()
                    && !savedRealPubkey.equals("demo-pubkey-not-a-real-bls-point")) {
                serverPubkeyField.setText(savedRealPubkey);
            }
            log.info("Demo mode disabled — restored real server settings");

            // Auto-reconnect to the real server
            autoConnectAndBootstrap();
        }
    }

    /** Generate a fake IssuedPack for demo mode. */
    private IssuedPack demoIssuePack(int packSize) {
        // Build a fake blob: 4 bytes packSize LE + (packSize * 48) random "tokens"
        int blobLen = 4 + packSize * 48;
        byte[] blob = new byte[blobLen];
        DEMO_RNG.nextBytes(blob);
        return new IssuedPack(packSize, blob);
    }

    /**
     * Generate a deterministic KYC tag for demo mode that produces the
     * target letter grade for the current cycle.
     *
     * Cycle order: F(0) → D(1) → C(2) → B(3) → A(4), then wraps.
     *
     * For each grade we need a specific ratio of "clean" (10-point) vs
     * "KYC Exchange" (0-point) UTXOs to land in that grade's score band:
     *   F  = score < 60  → ~20% clean, 80% KYC
     *   D  = 60–69       → ~65% clean, 35% KYC
     *   C  = 70–79       → ~75% clean, 25% KYC
     *   B  = 80–89       → ~85% clean, 15% KYC
     *   A  = 90–100      → ~95% clean, 5% KYC (with some variety)
     *
     * @param utxoIndex  0-based index of the current UTXO in the scan
     * @param totalUtxos total UTXOs being scanned
     */
    private PrivacyQuery.Result demoQueryResult(int utxoIndex, int totalUtxos) {
        // Target clean ratios for each grade
        double cleanRatio = switch (demoScanCycle % 5) {
            case 0 -> 0.20;  // F: score ~20
            case 1 -> 0.65;  // D: score ~65
            case 2 -> 0.75;  // C: score ~75
            case 3 -> 0.85;  // B: score ~85
            default -> 0.95; // A: score ~95
        };

        int cleanCount = Math.max(1, (int) Math.round(totalUtxos * cleanRatio));
        // Ensure at least 1 KYC for grades F–D so it's visible
        if (demoScanCycle % 5 <= 1 && cleanCount >= totalUtxos) {
            cleanCount = totalUtxos - 1;
        }

        if (utxoIndex < cleanCount) {
            // Vary the "clean" labels for visual interest
            return switch (utxoIndex % 4) {
                case 0 -> new PrivacyQuery.Result("Clean", "Clean", "privacy-good", "OK");
                case 1 -> new PrivacyQuery.Result("Unknown", "Unknown", "privacy-good", "OK");
                case 2 -> new PrivacyQuery.Result("CoinJoin", "CoinJoin", "privacy-good", "OK");
                default -> new PrivacyQuery.Result("Clean", "Clean", "privacy-good", "OK");
            };
        } else {
            // KYC labels
            return utxoIndex % 2 == 0
                    ? new PrivacyQuery.Result("KYC Exchange: Coinbase", "KYC Exchange", "privacy-bad", "OK")
                    : new PrivacyQuery.Result("KYC Exchange: Kraken", "KYC Exchange", "privacy-bad", "OK");
        }
    }

    /** Generate a fake 48-byte gamma for demo mode. */
    private static byte[] demoGamma() {
        byte[] gamma = new byte[48];
        DEMO_RNG.nextBytes(gamma);
        return gamma;
    }

    // ── Connection ──

    /**
     * Fetch the server's current OPRF pubkey from {@code /server/pubkey}.
     * Returns the hex string, or {@code null} on failure. This is called
     * on connect so the wallet always uses the server's live key —
     * critical after a server restart that generates a fresh OPRF key.
     *
     * <p>Uses {@link Native#httpGet(String)} so the request is routed
     * through the configured transport (Tor / OHTTP / Direct), which is
     * required for .onion server addresses.
     */
    private String fetchServerPubkey(String baseUrl) {
        String fullUrl = baseUrl.replaceAll("/+$", "") + "/server/pubkey";
        try {
            String json = PerseverusService.nativeHttpGet(fullUrl);
            if (json == null || json.isBlank()) return null;
            // Response is JSON: {"pubkey_hex":"..."}
            int idx = json.indexOf("\"pubkey_hex\"");
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx);
            int quote1 = json.indexOf('"', colon + 1);
            int quote2 = json.indexOf('"', quote1 + 1);
            if (quote1 < 0 || quote2 < 0) return null;
            return json.substring(quote1 + 1, quote2);
        } catch (UnsatisfiedLinkError e) {
            log.warn("Native httpGet not available — falling back to Java HTTP for pubkey fetch");
            return fetchServerPubkeyJava(fullUrl);
        } catch (Exception e) {
            log.debug("Failed to fetch server pubkey via native transport: {}", e.getMessage());
            return null;
        }
    }

    /** Java HTTP fallback for pubkey fetch — only works for clearnet URLs. */
    private String fetchServerPubkeyJava(String fullUrl) {
        try {
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                String json = sb.toString();
                int idx = json.indexOf("\"pubkey_hex\"");
                if (idx < 0) return null;
                int colon = json.indexOf(':', idx);
                int quote1 = json.indexOf('"', colon + 1);
                int quote2 = json.indexOf('"', quote1 + 1);
                if (quote1 < 0 || quote2 < 0) return null;
                return json.substring(quote1 + 1, quote2);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch server pubkey via Java HTTP: {}", e.getMessage());
            return null;
        }
    }

    public void connectService(ActionEvent event) {
        if (demoMode) {
            demoPackCounter = 0;
            connectionStatus.setText("Connected (demo)");
            connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            connectionStatus.getStyleClass().add("issuance-connected");
            connectButton.setText("Reconnect");
            issueButton.setDisable(false);
            bootstrapButton.setDisable(false);
            return;
        }

        String url = serverUrl.getText().trim();
        String pubkeyHex = serverPubkeyField.getText().trim();

        if (url.isBlank()) {
            setConnectionError("Server URL is empty");
            return;
        }
        if (pubkeyHex.isBlank()) {
            setConnectionError("Server pubkey is required");
            return;
        }

        // Signal the auto-connect thread to stop touching the UI —
        // the user is taking over with a manual connect.
        shared.manualConnectInitiated = true;

        String transportLabel = Config.get().getPerseverusTransport().getLabel();

        connectButton.setDisable(true);
        connectionStatus.setText("Connecting...");
        connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");

        // Persist settings
        Config.get().setPerseverusServerUrl(url);
        Config.get().setPerseverusServerPubkey(pubkeyHex);

        Thread connectThread = new Thread(() -> {
            try {
                // Close any existing service first
                if (shared.service != null) {
                    try { shared.service.close(); } catch (Exception e) { /* ignore */ }
                }

                setStatus("Connecting to server via " + transportLabel + "...");

                // Fetch the server's live OPRF pubkey — critical after a
                // server restart that generates a fresh key.
                String livePubkey = fetchServerPubkey(url);
                final String effectivePubkey;
                if (livePubkey != null && !livePubkey.isBlank()) {
                    effectivePubkey = livePubkey;
                    if (!livePubkey.equals(pubkeyHex)) {
                        log.info("Server pubkey changed — updating from {} to {}",
                                pubkeyHex.substring(0, Math.min(16, pubkeyHex.length())) + "...",
                                livePubkey.substring(0, Math.min(16, livePubkey.length())) + "...");
                    }
                } else {
                    effectivePubkey = pubkeyHex;
                    log.warn("Could not fetch server pubkey — using saved value");
                }

                PerseverusService svc = PerseverusService.open(url, effectivePubkey);
                String ver = PerseverusService.nativeVersion();
                PrivacyLog.get().connect(url, ver, transportLabel);
                setStatus("Connected to server via " + transportLabel + " (v" + ver + ")");

                // If the server key changed, old packs are invalid
                final boolean keyChanged = !effectivePubkey.equals(pubkeyHex);
                if (keyChanged && !shared.packRows.isEmpty()) {
                    log.info("Server key changed — clearing {} stale pack(s)", shared.packRows.size());
                    Platform.runLater(() -> {
                        shared.packRows.clear();
                        packsTable.setVisible(false);
                        packsTable.setManaged(false);
                        selectedPack = null;
                        persistPacks();
                    });
                }

                Platform.runLater(() -> {
                    shared.service = svc;
                    // Reset bootstrap state — the new service hasn't been
                    // bootstrapped yet, even if the old one was.
                    shared.bootstrapped = false;
                    bootstrapStatus.setText("Bootstrapping...");
                    bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
                    bootstrapButton.setDisable(true);
                    spendButton.setDisable(true);
                    // Update the pubkey field and persist if it changed
                    if (!effectivePubkey.equals(serverPubkeyField.getText().trim())) {
                        serverPubkeyField.setText(effectivePubkey);
                        Config.get().setPerseverusServerPubkey(effectivePubkey);
                    }
                    connectionStatus.setText("Connected (v" + ver + ")");
                    connectionStatus.getStyleClass().add("issuance-connected");
                    connectButton.setDisable(false);
                    connectButton.setText("Reconnect");
                    issueButton.setDisable(false);
                });

                // Auto-bootstrap the new service so the user doesn't
                // have to click Bootstrap manually after every reconnect.
                setStatus("Downloading proving key via " + transportLabel + "...");
                PrivacyLog.get().bootstrapStart();
                long bt0 = System.currentTimeMillis();
                svc.bootstrap();
                long bElapsed = System.currentTimeMillis() - bt0;
                PrivacyLog.get().bootstrapComplete(bElapsed);

                // Re-publish commitments for persisted packs
                int republished = 0;
                int republishFailed = 0;
                int toRepublish = 0;
                for (PackRow pr : shared.packRows) {
                    if (pr.remaining() > 0) toRepublish++;
                }
                if (toRepublish > 0) {
                    setStatus("Re-publishing " + toRepublish + " pack commitment(s)...");
                    PrivacyLog.get().republishStart(toRepublish);
                }
                long republishT0 = System.currentTimeMillis();
                for (PackRow pr : shared.packRows) {
                    if (pr.remaining() > 0) {
                        try {
                            svc.republishCommitment(pr.pack);
                            republished++;
                        } catch (Exception re) {
                            republishFailed++;
                            log.warn("Failed to re-publish commitment for pack: {}",
                                    re.getMessage());
                        }
                    }
                }
                if (toRepublish > 0) {
                    long republishElapsed = System.currentTimeMillis() - republishT0;
                    log.info("Re-published {} pack commitment(s) to bulletin board",
                            republished);
                    PrivacyLog.get().republishComplete(republished, republishFailed, republishElapsed);
                }

                Platform.runLater(() -> {
                    shared.bootstrapped = true;
                    bootstrapStatus.setText("Ready");
                    bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
                    bootstrapStatus.getStyleClass().add("issuance-connected");
                    bootstrapButton.setDisable(true);
                    spendButton.setDisable(false);
                });
                log.info("Reconnect + auto-bootstrap succeeded in {}ms", bElapsed);
                setStatus("");
                final String readyText2 = "Ready — connected via " + transportLabel;
                Platform.runLater(() -> EventManager.get().post(new MedusaStatusTextEvent(readyText2)));
            } catch (PerseverusException e) {
                log.error("Failed to connect Perseverus service", e);
                setStatus("Connection failed");
                Platform.runLater(() -> {
                    setConnectionError(e.getMessage());
                    connectButton.setDisable(false);
                });
            } catch (Throwable e) {
                log.error("Unexpected error connecting Perseverus service", e);
                setStatus("Connection via " + transportLabel + " failed");
                Platform.runLater(() -> {
                    setConnectionError("Connection failed: " + e.getMessage());
                    connectButton.setDisable(false);
                });
            }
        }, "perseverus-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void setConnectionError(String msg) {
        connectionStatus.setText(msg);
        connectionStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
        connectionStatus.getStyleClass().add("issuance-error");
    }

    public void issueTokens(ActionEvent event) {
        if (demoMode) {
            int packSize = currentPackSize();
            IssuedPack pack = demoIssuePack(packSize);
            demoPackCounter++;
            PackRow packRow = new PackRow(pack);
            shared.packRows.addFirst(packRow);
            selectedPack = packRow;
            packsTable.getSelectionModel().select(packRow);
            packsTable.setVisible(true);
            packsTable.setManaged(true);
            issueStatus.setText("Issued " + packSize + " demo tokens");
            issueStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            issueStatus.getStyleClass().add("issuance-connected");
            persistPacks();
            return;
        }

        if (shared.service == null) {
            issueStatus.setText("Not connected");
            issueStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            issueStatus.getStyleClass().add("issuance-error");
            return;
        }

        int packSize = currentPackSize();

        issueButton.setDisable(true);
        issueStatus.setText("Issuing " + packSize + " tokens...");
        issueStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");

        final int finalPackSize = packSize;
        PrivacyLog.get().issueStart(packSize);
        Thread issueThread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                IssuedPack pack = shared.service.issuePack(finalPackSize);
                long elapsed = System.currentTimeMillis() - t0;
                PrivacyLog.get().issueComplete(pack.packSize(), elapsed);
                Platform.runLater(() -> {
                    PackRow packRow = new PackRow(pack);
                    shared.packRows.addFirst(packRow); // newest on top
                    selectedPack = packRow;
                    packsTable.getSelectionModel().select(packRow);
                    packsTable.setVisible(true);
                    packsTable.setManaged(true);

                    issueStatus.setText("Issued " + pack.packSize() + " tokens");
                    issueStatus.getStyleClass().add("issuance-connected");
                    issueButton.setDisable(false);
                    log.info("Issued pack: size={}, blobLen={}",
                            pack.packSize(), pack.bytes().length);
                    persistPacks();
                });
            } catch (PerseverusException e) {
                PrivacyLog.get().issueFailed(finalPackSize, e.getMessage());
                log.error("Token issuance failed", e);
                Platform.runLater(() -> {
                    issueStatus.setText("Issuance failed: " + e.getMessage());
                    issueStatus.getStyleClass().add("issuance-error");
                    issueButton.setDisable(false);
                });
            } catch (Exception e) {
                PrivacyLog.get().issueFailed(finalPackSize, e.getMessage());
                log.error("Unexpected error during token issuance", e);
                Platform.runLater(() -> {
                    issueStatus.setText("Error: " + e.getMessage());
                    issueStatus.getStyleClass().add("issuance-error");
                    issueButton.setDisable(false);
                });
            }
        }, "perseverus-issue");
        issueThread.setDaemon(true);
        issueThread.start();
    }

    // ── Spend actions ──

    public void bootstrapSpend(ActionEvent event) {
        if (demoMode) {
            shared.bootstrapped = true;
            bootstrapStatus.setText("Ready (demo)");
            bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            bootstrapStatus.getStyleClass().add("issuance-connected");
            bootstrapButton.setDisable(true);
            spendButton.setDisable(false);
            return;
        }

        if (shared.service == null) {
            bootstrapStatus.setText("Connect first");
            bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");
            bootstrapStatus.getStyleClass().add("issuance-error");
            return;
        }
        if (shared.bootstrapped) {
            bootstrapStatus.setText("Already bootstrapped");
            return;
        }

        bootstrapButton.setDisable(true);
        bootstrapStatus.setText("Downloading PK + G₂ pubkey...");
        bootstrapStatus.getStyleClass().removeAll("issuance-connected", "issuance-error");

        PrivacyLog.get().bootstrapStart();
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                shared.service.bootstrap();
                long elapsed = System.currentTimeMillis() - t0;
                PrivacyLog.get().bootstrapComplete(elapsed);

                // Re-publish commitments for persisted packs
                int republished = 0;
                int republishFailed = 0;
                int toRepublish = 0;
                for (PackRow pr : shared.packRows) {
                    if (pr.remaining() > 0) toRepublish++;
                }
                if (toRepublish > 0) {
                    PrivacyLog.get().republishStart(toRepublish);
                }
                long republishT0 = System.currentTimeMillis();
                for (PackRow pr : shared.packRows) {
                    if (pr.remaining() > 0) {
                        try {
                            shared.service.republishCommitment(pr.pack);
                            republished++;
                        } catch (Exception re) {
                            republishFailed++;
                            log.warn("Failed to re-publish commitment for pack: {}",
                                    re.getMessage());
                        }
                    }
                }
                if (toRepublish > 0) {
                    long republishElapsed = System.currentTimeMillis() - republishT0;
                    log.info("Re-published {} pack commitment(s) to bulletin board",
                            republished);
                    PrivacyLog.get().republishComplete(republished, republishFailed, republishElapsed);
                }

                Platform.runLater(() -> {
                    shared.bootstrapped = true;
                    bootstrapStatus.setText("Ready");
                    bootstrapStatus.getStyleClass().add("issuance-connected");
                    bootstrapButton.setDisable(true); // idempotent — stay disabled
                    spendButton.setDisable(false);
                });
            } catch (PerseverusException e) {
                PrivacyLog.get().bootstrapFailed(e.getMessage());
                log.error("Spend bootstrap failed", e);
                Platform.runLater(() -> {
                    bootstrapStatus.setText("Failed: " + e.getMessage());
                    bootstrapStatus.getStyleClass().add("issuance-error");
                    bootstrapButton.setDisable(false);
                });
            } catch (Exception e) {
                PrivacyLog.get().bootstrapFailed(e.getMessage());
                log.error("Unexpected error during spend bootstrap", e);
                Platform.runLater(() -> {
                    bootstrapStatus.setText("Error: " + e.getMessage());
                    bootstrapStatus.getStyleClass().add("issuance-error");
                    bootstrapButton.setDisable(false);
                });
            }
        }, "perseverus-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    public void executeSpend(ActionEvent event) {
        if (demoMode && shared.bootstrapped && selectedPack != null) {
            int spendIdx;
            try { spendIdx = Integer.parseInt(spendIndexField.getText().trim()); }
            catch (NumberFormatException e) { spendIdx = 0; }
            if (spendIdx >= 0 && spendIdx < selectedPack.pack.packSize()) {
                selectedPack.markSpent(spendIdx);
                packsTable.refresh();
                persistPacks();
                byte[] gamma = demoGamma();
                String preview = bytesToHex(gamma).substring(0, 32) + "...";
                spendResultLabel.setText("γ = " + preview + " (48 bytes, demo)");
                spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
                spendResultLabel.getStyleClass().add("issuance-connected");
            } else {
                spendResultLabel.setText("Token # must be 0–" + (selectedPack.pack.packSize() - 1));
                spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
                spendResultLabel.getStyleClass().add("issuance-error");
            }
            return;
        }

        if (shared.service == null || !shared.bootstrapped) {
            spendResultLabel.setText("Bootstrap the spend client first");
            spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendResultLabel.getStyleClass().add("issuance-error");
            return;
        }
        if (selectedPack == null) {
            spendResultLabel.setText("Issue tokens first (or select a pack from the table)");
            spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendResultLabel.getStyleClass().add("issuance-error");
            return;
        }
        if (selectedPack.remaining() <= 0) {
            spendResultLabel.setText("All tokens in this pack have been spent — issue a new pack");
            spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendResultLabel.getStyleClass().add("issuance-error");
            return;
        }

        int spendIdx;
        try {
            spendIdx = Integer.parseInt(spendIndexField.getText().trim());
        } catch (NumberFormatException e) {
            spendResultLabel.setText("Invalid token index");
            spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendResultLabel.getStyleClass().add("issuance-error");
            return;
        }
        if (spendIdx < 0 || spendIdx >= selectedPack.pack.packSize()) {
            spendResultLabel.setText("Token # must be 0–" + (selectedPack.pack.packSize() - 1));
            spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendResultLabel.getStyleClass().add("issuance-error");
            return;
        }

        String inputHex = spendInputField.getText().trim();
        byte[] inputBytes = hexToBytes(inputHex);
        if (inputBytes == null || inputBytes.length == 0) {
            spendResultLabel.setText("Invalid hex input");
            spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");
            spendResultLabel.getStyleClass().add("issuance-error");
            return;
        }

        spendButton.setDisable(true);
        spendResultLabel.setText("Spending token " + spendIdx + "...");
        spendResultLabel.getStyleClass().removeAll("issuance-connected", "issuance-error");

        final int idx = spendIdx;
        final PackRow spendingPack = selectedPack;
        PrivacyLog.get().spendStart(idx, "(manual) input=" + inputHex.substring(0, Math.min(16, inputHex.length())) + "...");
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                byte[] gamma = shared.service.spend(spendingPack.pack, idx, inputBytes);
                long elapsed = System.currentTimeMillis() - t0;
                PrivacyLog.get().spendComplete(idx, gamma.length, elapsed);
                String gammaHex = bytesToHex(gamma);
                Platform.runLater(() -> {
                    spendingPack.markSpent(idx);
                    packsTable.refresh();

                    String preview = gammaHex.length() > 32
                            ? gammaHex.substring(0, 32) + "..."
                            : gammaHex;
                    spendResultLabel.setText("γ = " + preview + " (" + gamma.length + " bytes, " + elapsed + "ms)");
                    spendResultLabel.getStyleClass().add("issuance-connected");
                    spendButton.setDisable(false);
                    log.info("Spend succeeded: token={}, gammaLen={}, elapsed={}ms", idx, gamma.length, elapsed);
                    persistPacks();
                });
            } catch (PerseverusException e) {
                long elapsed = System.currentTimeMillis() - t0;
                PrivacyLog.get().spendFailed(idx, e.getMessage() + " (after " + elapsed + "ms)");
                log.error("Spend execution failed", e);
                Platform.runLater(() -> {
                    spendResultLabel.setText("Spend failed: " + e.getMessage());
                    spendResultLabel.getStyleClass().add("issuance-error");
                    spendButton.setDisable(false);
                });
            } catch (Exception e) {
                log.error("Unexpected error during spend", e);
                Platform.runLater(() -> {
                    spendResultLabel.setText("Error: " + e.getMessage());
                    spendResultLabel.getStyleClass().add("issuance-error");
                    spendButton.setDisable(false);
                });
            }
        }, "perseverus-spend");
        t.setDaemon(true);
        t.start();
    }

    /** Convert bytes to lowercase hex string. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ── Scan logic ──

    /**
     * @param skipScanned if true, already-scanned UTXOs are excluded
     *                    (used by "Scan All" to avoid wasting tokens).
     *                    "Scan Selected" passes false so the user can
     *                    rescan specific UTXOs (e.g. ones that errored).
     */
    private void runScan(List<UtxoRow> targets, boolean skipScanned) {
        if (scanning) {
            return;
        }

        // ── Pay-per-scan gate ──
        // There are no free scans anymore: trial users scan by consuming
        // purchased token packs. If trial mode is active but there are no token
        // packs (none bought yet, or all used/expired), there's nothing to scan
        // with — prompt to buy another scan or subscribe.
        if (Config.get().isPerseverusTrialMode() && !Config.get().isPerseverusDemoMode()
                && Config.get().getPerseverusPacks().isEmpty()) {
            Alert needToken = new Alert(Alert.AlertType.INFORMATION);
            needToken.setTitle("Trial Scan Needed");
            needToken.setHeaderText(null);
            needToken.setContentText("You don't have any scan tokens. Pay $0.25 with "
                + "Lightning for another trial scan, or sign up for a full subscription.");
            ButtonType buyBtn = new ButtonType("Get a Scan", ButtonBar.ButtonData.OK_DONE);
            needToken.getButtonTypes().setAll(buyBtn, ButtonType.CANCEL);
            PrivacyLog.get().info("POPUP [Trial Scan Needed]: no token packs — prompting to buy");
            Optional<ButtonType> choice = needToken.showAndWait();
            if (choice.isPresent() && choice.get() == buyBtn) {
                showWelcomeDialog();
            }
            return;
        }

        // Capture effectively-final copies after trial gate may have modified them
        final List<UtxoRow> effectiveTargets = targets;
        final boolean effectiveSkipScanned = skipScanned;

        // Restore any cached results first — if we already have a KYC tag
        // saved for a UTXO, fill it in without burning a token.
        int restored = 0;
        for (UtxoRow r : effectiveTargets) {
            if (!r.isScanned()) {
                String key = r.getTxid() + ":" + r.getVout();
                String cachedTag = privacyLabels.get(key);
                if (cachedTag != null && !cachedTag.isBlank()) {
                    r.applyResult(resultFromDisplayTag(cachedTag));
                    restored++;
                }
            }
        }
        if (restored > 0) {
            resultsTable.refresh();
            log.info("Restored {} cached scan result(s) without using tokens", restored);
        }

        // Drop unconfirmed UTXOs and (optionally) already-scanned ones
        List<UtxoRow> scannable = effectiveTargets.stream()
                .filter(r -> r.getBlockHeight() > 0)
                .filter(r -> !effectiveSkipScanned || !r.isScanned())
                .collect(Collectors.toList());

        if (scannable.isEmpty()) {
            // Check if all targets were already scanned vs just no confirmed UTXOs
            boolean allScanned = effectiveTargets.stream()
                    .filter(r -> r.getBlockHeight() > 0)
                    .allMatch(UtxoRow::isScanned);
            if (allScanned && !targets.isEmpty()) {
                scanStatus.setText("All UTXOs have been scanned");
            } else {
                long unconfirmedCount = effectiveTargets.stream()
                        .filter(r -> r.getBlockHeight() <= 0).count();
                if (unconfirmedCount > 0) {
                    scanStatus.setText(unconfirmedCount + " unconfirmed UTXO"
                            + (unconfirmedCount > 1 ? "s" : "")
                            + " — wait for block confirmation to scan");
                } else {
                    scanStatus.setText("No confirmed UTXOs to scan");
                }
            }
            return;
        }

        // Determine if we can authenticate scans with token spends.
        // Requires: connected service + bootstrapped spend client + pack with tokens.
        final boolean canAuthenticate = shared.service != null && shared.bootstrapped;

        // Trial mode: two paths depending on whether the service is ready.
        //   A) Service connected + bootstrapped → auto-issue trial tokens,
        //      scan authenticated against the real server.
        //   C) Service NOT ready → fall back to demo scan with simulated
        //      results so the user still sees the feature in action.
        boolean inTrialMode = Config.get().isPerseverusTrialMode()
                && !Config.get().isPerseverusTrialExhausted()
                && !Config.get().isPerseverusDemoMode();
        // PAY-PER-SCAN: never auto-issue tokens during a scan. This was legacy
        // free-trial behaviour that minted a pack sized to the UTXO count via
        // the ungated issuePack() — a free-mint. Scanning must only consume
        // tokens the user has actually purchased; the token-availability checks
        // below now apply (block at 0 tokens, confirm when fewer than UTXOs).
        final boolean trialAutoIssue = false;
        final boolean trialDemoFallback = inTrialMode && !canAuthenticate;

        // Count available tokens across ALL packs — tokens are pooled
        // so a scan of 13 can drain 3 from one pack and 10 from another.
        // Never auto-issue: the user must issue tokens explicitly
        // (except for trial mode, where tokens are auto-issued below).
        int totalTokensAvailable = 0;
        for (PackRow pr : shared.packRows) {
            totalTokensAvailable += pr.remaining();
        }

        // Block scan entirely when no tokens are available.
        // Skip this check for trial auto-issue (tokens will be issued
        // on the scan thread) and trial demo fallback (no tokens needed).
        if (canAuthenticate && totalTokensAvailable == 0
                && !trialAutoIssue && !trialDemoFallback) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No Tokens Available");
            alert.setHeaderText(null);
            alert.setContentText("No tokens are available to scan with. "
                    + "Issue tokens first.");
            alert.getButtonTypes().setAll(ButtonType.OK);
            PrivacyLog.get().info("POPUP [No Tokens Available]: scan blocked — no tokens");
            alert.showAndWait();
            return;
        }

        // If we have tokens but fewer than UTXOs to scan, confirm with
        // the user before proceeding — only the first N will be scanned.
        // Skip for trial (budget already capped by the trial gate above).
        if (canAuthenticate && totalTokensAvailable < scannable.size()
                && !trialAutoIssue && !trialDemoFallback) {
            int avail = totalTokensAvailable;
            int requested = scannable.size();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Insufficient Tokens");
            confirm.setHeaderText(null);
            confirm.setContentText("You are attempting to scan " + requested
                    + " UTXOs but only have " + avail + " token"
                    + (avail != 1 ? "s" : "") + " remaining across all packs. "
                    + "Only the first " + avail + " will be scanned.");
            confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            ((Button) confirm.getDialogPane().lookupButton(ButtonType.OK)).setText("Continue");
            PrivacyLog.get().info("POPUP [Insufficient Tokens]: " + avail
                    + " tokens available, " + requested + " UTXOs requested");

            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
                return;
            }
            // Trim scan list to what we can authenticate
            scannable = new ArrayList<>(scannable.subList(0, avail));
        }

        // Determine the current month (server /epoch clock, Tor) so we only
        // spend tokens from packs that are CURRENTLY ACTIVE. A pack that is
        // not yet active or already expired cannot produce a valid spend
        // proof (the Groth16 circuit enforces start_month ≤ current ≤
        // expiration), so draining it would waste tokens and fail server-side.
        // Always re-fetch the authoritative clock at scan time (don't reuse
        // the cached display value) so advancing the server clock between
        // scans correctly expires/activates packs for spending.
        int curMonth;
        try {
            curMonth = com.sparrowwallet.perseverus.SpendClient.currentMonth();
        } catch (Throwable t) {
            java.time.LocalDate now = java.time.LocalDate.now();
            curMonth = now.getYear() * 100 + now.getMonthValue();
        }
        shared.currentMonth = curMonth; // keep the display in sync
        final int scanMonth = curMonth;
        log.info("[perseverus] Scan month (server /epoch clock): {}", scanMonth);

        // Build the ordered list of packs to drain during the scan.
        // Only active packs (start ≤ now ≤ expiration), soonest-to-expire
        // first (use-it-or-lose-it), tie-broken by largest-remaining to
        // minimise pack switches. If the month can't be determined we treat
        // packs without a valid month range (legacy/0) as spendable.
        List<PackRow> scanPacks = shared.packRows.stream()
                .filter(pr -> pr.remaining() > 0)
                .filter(pr -> {
                    int start = pr.startMonth();
                    int exp = pr.expirationMonth();
                    if (start <= 0 || exp <= 0) return true; // legacy pack, no range
                    return scanMonth >= start && scanMonth <= exp;
                })
                .sorted((a, b) -> {
                    int ea = a.expirationMonth(), eb = b.expirationMonth();
                    if (ea != eb) return Integer.compare(ea, eb); // soonest expiry first
                    return Integer.compare(b.remaining(), a.remaining());
                })
                .collect(Collectors.toList());

        // Capture the (possibly trimmed) scan list as effectively final
        // so it can be used inside the background thread lambda.
        final List<UtxoRow> finalScannable = scannable;

        // Reset status on rows we're about to scan
        for (UtxoRow r : finalScannable) {
            r.resetResult();
        }
        resultsTable.refresh();

        scanning = true;
        scanButton.setDisable(true);
        scanSelectedButton.setDisable(true);
        scanProgress.setVisible(true);
        scanProgress.setManaged(true);
        scanProgressBox.setVisible(true);
        scanProgressBox.setManaged(true);
        scanProgress.setProgress(0);
        scanStatus.setText("Scanning...");

        if (demoMode || trialDemoFallback) {
            if (trialDemoFallback) {
                log.info("[perseverus] Trial scan using demo fallback — server not connected");
                scanStatus.setText("Trial scan (demo)...");
            }
            runDemoScan(finalScannable);
            return;
        }

        String server = serverUrl.getText().trim();
        // Persist the server URL if the user changed it
        Config.get().setPerseverusServerUrl(server);
        final int decoys = currentDecoys();

        // Chain tip: Sparrow's known tip, wallet's stored tip, or UTXO height + cushion
        Integer chainTip = AppServices.getCurrentBlockHeight();
        if (chainTip == null || chainTip <= 0) {
            Integer stored = getWalletForm().getWallet().getStoredBlockHeight();
            chainTip = (stored != null && stored > 0) ? stored : 840_000;
        }
        final int tip = chainTip;

        final int total = finalScannable.size();
        int tokensAvail = 0;
        for (PackRow pr : scanPacks) { tokensAvail += pr.remaining(); }
        PrivacyLog.get().scanStart(total, tokensAvail, canAuthenticate, decoys, tip);

        Thread scanThread = new Thread(() -> {
          try {
            long scanT0 = System.currentTimeMillis();
            int completed = 0;
            int privacyPoints = 0;
            int scorable = 0;
            int authenticated = 0;

            // ── Trial auto-issue: issue a pack of exactly N tokens
            // on the background thread so we don't block the FX thread.
            // The freshly issued pack replaces whatever was in scanPacks.
            // Each attempt has a 20-second timeout; up to 3 attempts with
            // status updates so the user can see progress.
            if (trialAutoIssue) {
                int trialSize = finalScannable.size();
                log.info("[perseverus] Trial auto-issuing {} token(s)", trialSize);

                final int TRIAL_MAX_ATTEMPTS = 3;
                final int TRIAL_TIMEOUT_SECS = 20;
                IssuedPack trialPack = null;

                for (int attempt = 1; attempt <= TRIAL_MAX_ATTEMPTS; attempt++) {
                    final int att = attempt;
                    if (attempt == 1) {
                        Platform.runLater(() -> scanStatus.setText(
                                "Issuing trial tokens via Tor..."));
                    } else {
                        Platform.runLater(() -> scanStatus.setText(
                                "Retrying token issuance (attempt " + att + "/" + TRIAL_MAX_ATTEMPTS + ")..."));
                    }

                    try {
                        trialPack = CompletableFuture.supplyAsync(
                                () -> shared.service.issuePack(trialSize))
                                .get(TRIAL_TIMEOUT_SECS, TimeUnit.SECONDS);
                        break; // success
                    } catch (TimeoutException te) {
                        log.warn("[perseverus] Trial issuance attempt {}/{} timed out after {}s",
                                attempt, TRIAL_MAX_ATTEMPTS, TRIAL_TIMEOUT_SECS);
                        if (attempt == TRIAL_MAX_ATTEMPTS) {
                            Platform.runLater(() -> scanStatus.setText(
                                    "Server not responding — running demo scan..."));
                        }
                    } catch (java.util.concurrent.ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        log.warn("[perseverus] Trial issuance attempt {}/{} failed: {}",
                                attempt, TRIAL_MAX_ATTEMPTS, cause.getMessage());
                        if (attempt == TRIAL_MAX_ATTEMPTS) {
                            Platform.runLater(() -> scanStatus.setText(
                                    "Token issuance failed — running demo scan..."));
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (trialPack != null) {
                    PackRow trialPackRow = new PackRow(trialPack);
                    scanPacks.clear();
                    scanPacks.add(trialPackRow);
                    Platform.runLater(() -> {
                        shared.packRows.addFirst(trialPackRow);
                        if (packsTable != null) {
                            packsTable.setVisible(true);
                            packsTable.setManaged(true);
                        }
                        persistPacks();
                        scanStatus.setText("Scanning...");
                    });
                    log.info("[perseverus] Trial pack issued: {} tokens", trialSize);
                } else {
                    // All attempts failed — fall back to demo scan
                    log.warn("[perseverus] All trial issuance attempts exhausted — "
                            + "falling back to demo scan");
                    Platform.runLater(() -> runDemoScan(finalScannable));
                    return; // exit scan thread — demo scan handles the rest
                }
            }

            // ── Phase 1: Batch spend ─────────────────────────────────
            // Collect all pack/index/input triples, then call spendBatch
            // once. This generates all Groth16 proofs locally, fetches
            // board leaves once, and sends a single POST to the server —
            // reducing N Tor round trips to 1.
            //
            // Progress: proofs occupy 0–40%, server send 40–50%, queries 50–100%.
            byte[][] gammas = new byte[finalScannable.size()][];

            if (canAuthenticate && !scanPacks.isEmpty()) {
                // Pre-compute the pack, token index, and outpoint for each UTXO
                java.util.List<IssuedPack> batchPacks = new java.util.ArrayList<>();
                java.util.List<Integer> batchSpendIdxs = new java.util.ArrayList<>();
                java.util.List<byte[]> batchInputs = new java.util.ArrayList<>();
                java.util.List<Integer> batchUtxoIdxs = new java.util.ArrayList<>();
                java.util.List<PackRow> batchPackRows = new java.util.ArrayList<>();

                int packIdx = 0;
                PackRow currentPack = scanPacks.get(0);
                int tokenIdx = nextUnspentIndex(currentPack, 0);

                for (int i = 0; i < finalScannable.size(); i++) {
                    if (currentPack == null || tokenIdx < 0 || tokenIdx >= currentPack.pack.packSize()) {
                        break;
                    }
                    UtxoRow row = finalScannable.get(i);
                    byte[] txidBytes = hexToBytes(row.getTxid());
                    if (txidBytes == null) continue;

                    // v5 filters are keyed on the txid ALONE (per-transaction),
                    // in internal byte order (the reverse of the display hex).
                    // The OPRF input — hence the gamma used to decrypt — must
                    // match that, so spend over the internal txid, NOT the
                    // 36-byte outpoint (which would give v·H2C(outpoint) and a
                    // wrong decryption key).
                    byte[] oprfInput = reverseBytes(txidBytes);
                    batchPacks.add(currentPack.pack);
                    batchSpendIdxs.add(tokenIdx);
                    batchInputs.add(oprfInput);
                    batchUtxoIdxs.add(i);
                    batchPackRows.add(currentPack);

                    PrivacyLog.get().spendStart(tokenIdx, row.txidShort() + ":" + row.getVout());

                    tokenIdx = nextUnspentIndex(currentPack, tokenIdx + 1);
                    if (tokenIdx < 0) {
                        packIdx++;
                        if (packIdx < scanPacks.size()) {
                            currentPack = scanPacks.get(packIdx);
                            tokenIdx = nextUnspentIndex(currentPack, 0);
                            log.info("Pack exhausted, advancing to next pack ({} remaining)",
                                    currentPack.remaining());
                        } else {
                            currentPack = null;
                        }
                    }
                }

                if (!batchPacks.isEmpty()) {
                    final int batchSize = batchPacks.size();

                    // Start a progress-simulation timer: each proof takes ~250ms,
                    // so we tick the progress bar forward every 250ms to show activity.
                    // Progress range: 0.0 → 0.90 for proofs, then "Sending..." near full.
                    final java.util.concurrent.atomic.AtomicInteger proofTick = new java.util.concurrent.atomic.AtomicInteger(0);
                    java.util.Timer proofTimer = new java.util.Timer("proof-progress", true);
                    proofTimer.scheduleAtFixedRate(new java.util.TimerTask() {
                        @Override
                        public void run() {
                            int tick = proofTick.incrementAndGet();
                            // Filter download runs concurrently with proof generation.
                            // Surface it here so it's visible even when the download
                            // finishes within the proof-gen window (common over Tor).
                            String filterStatus = "";
                            try {
                                long[] dl = PerseverusService.prefetchProgress();
                                if (dl != null && dl[1] > 0) {
                                    filterStatus = String.format(java.util.Locale.ENGLISH,
                                            "  ·  filters %.1f / %.1f MB",
                                            dl[0] / (1024.0 * 1024.0), dl[1] / (1024.0 * 1024.0));
                                } else if (dl != null) {
                                    filterStatus = "  ·  downloading filters…";
                                }
                            } catch (Throwable ignored) {}
                            final String fs = filterStatus;
                            if (tick <= batchSize) {
                                double progress = 0.90 * tick / batchSize;
                                final int t = tick;
                                Platform.runLater(() -> {
                                    scanProgress.setProgress(progress);
                                    scanProgressLabel.setText("Generating ZK proof " + t + " / " + batchSize + fs);
                                });
                            } else if (tick == batchSize + 1) {
                                Platform.runLater(() -> {
                                    scanProgress.setProgress(0.92);
                                    scanProgressLabel.setText("Sending proofs to server...");
                                    scanStatus.setText("Sending " + batchSize + " proof(s) to server...");
                                });
                            } else if (tick == batchSize + 5) {
                                // ~1.25s after send started — show waiting message
                                Platform.runLater(() -> {
                                    scanProgress.setProgress(0.94);
                                    scanProgressLabel.setText("Waiting for server response...");
                                });
                            } else if (tick == batchSize + 17) {
                                // ~4s in — reassure user
                                Platform.runLater(() -> {
                                    scanProgress.setProgress(0.96);
                                    scanProgressLabel.setText("Server verifying proofs...");
                                });
                            } else if (tick == batchSize + 33) {
                                // ~8s in — long wait over Tor
                                Platform.runLater(() -> {
                                    scanProgress.setProgress(0.97);
                                    scanProgressLabel.setText("Still waiting (Tor can be slow)...");
                                });
                            }
                        }
                    }, 250, 250);

                    Platform.runLater(() -> {
                        scanProgress.setProgress(0);
                        scanProgressLabel.setText("Generating ZK proof 1 / " + batchSize);
                        scanStatus.setText("Generating proofs & downloading filters...");
                    });

                    PrivacyLog.get().batchSpendStart(batchSize, total);
                    IssuedPack[] packsArr = batchPacks.toArray(new IssuedPack[0]);
                    int[] idxArr = batchSpendIdxs.stream().mapToInt(Integer::intValue).toArray();
                    byte[][] inputsArr = batchInputs.toArray(new byte[0][]);

                    // ── Prefetch filters in parallel with proof generation ──
                    // Collect all block heights from the scan rows and fire off
                    // a background JNI call that downloads them concurrently.
                    // By the time the batch spend returns, filters will be warm.
                    int[] blockHeights = finalScannable.stream()
                            .mapToInt(UtxoRow::getBlockHeight)
                            .filter(h -> h > 0)
                            .toArray();
                    // With resample-until-exact, the count is deterministic:
                    // real blocks + (real blocks × decoys_per_block), no dedup loss.
                    final int estimatedFilterCount = blockHeights.length + (blockHeights.length * decoys);
                    final double scale = currentScale();
                    PrivacyLog.get().filterDownloadStart(estimatedFilterCount, decoys);
                    final long filterDlStartTime = System.currentTimeMillis();
                    log.info("[perseverus] Filter prefetch: {} real blocks + {} decoys = {} total filters",
                            blockHeights.length, blockHeights.length * decoys, estimatedFilterCount);
                    Thread prefetchThread = new Thread(() -> {
                        try {
                            PerseverusService.prefetchFiltersWithDecoys(server, blockHeights, decoys, tip, scale);
                        } catch (Exception e) {
                            log.warn("[perseverus] Filter prefetch failed (non-fatal): {}", e.getMessage());
                        }
                    }, "filter-prefetch");
                    prefetchThread.setDaemon(true);
                    prefetchThread.start();

                    long spendT0 = System.currentTimeMillis();
                    try {
                        byte[][] batchResults = shared.service.spendBatch(packsArr, idxArr, inputsArr);
                        proofTimer.cancel();

                        long spendElapsed = System.currentTimeMillis() - spendT0;
                        log.info("[perseverus] Batch spend completed: {} items in {}ms",
                                batchSize, spendElapsed);

                        // Retrieve actual per-proof Groth16 generation timings from Rust
                        long[] proofTimings = null;
                        try {
                            proofTimings = PerseverusService.lastBatchProofTimingsMs();
                        } catch (UnsatisfiedLinkError e) {
                            log.debug("spendBatchProofTimingsMs not available — using average");
                        }

                        // Wait for filter prefetch to finish, showing real download progress.
                        if (prefetchThread.isAlive()) {
                            // Show indeterminate (animated) bar while waiting for server response
                            boolean isTor = Config.get().getPerseverusTransport() == Config.PerseverusTransport.TOR;
                            Platform.runLater(() -> {
                                scanProgress.setProgress(-1);
                                scanProgressLabel.setText("Downloading " + estimatedFilterCount + " block filters...");
                                downloadingLabel.setText("Preparing filter download...");
                                scanStatus.setText("Downloading block filters...");
                                downloadHelpIcon.setVisible(isTor);
                                downloadHelpIcon.setManaged(isTor);
                            });

                            // Poll download progress every 200ms
                            long dlStartTime = 0;
                            boolean etaVisible = false;
                            int dlLogCounter = 0;
                            while (prefetchThread.isAlive()) {
                                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                                try {
                                    long[] prog = PerseverusService.prefetchProgress();
                                    long downloaded = prog[0];
                                    long totalBytes = prog[1];
                                    if (totalBytes > 0) {
                                        // Record when data first starts flowing
                                        if (dlStartTime == 0) {
                                            dlStartTime = System.currentTimeMillis();
                                        }

                                        // Switch to determinate progress once data starts flowing
                                        double pct = Math.min((double) downloaded / totalBytes, 1.0);
                                        String dlMB = String.format("%.1f", downloaded / (1024.0 * 1024.0));
                                        String totalMB = String.format("%.1f", totalBytes / (1024.0 * 1024.0));

                                        // Compute ETA from download rate
                                        String etaText = "";
                                        long elapsed = System.currentTimeMillis() - dlStartTime;
                                        if (elapsed > 2_000 && downloaded > 0) {
                                            double bytesPerMs = (double) downloaded / elapsed;
                                            long remainingBytes = totalBytes - downloaded;
                                            long etaMs = (long) (remainingBytes / bytesPerMs);
                                            int etaSec = (int) (etaMs / 1000);

                                            if (etaSec > 30 || etaVisible) {
                                                // Once we start showing ETA, keep showing it
                                                etaVisible = true;
                                                int mins = etaSec / 60;
                                                int secs = etaSec % 60;
                                                if (mins > 0) {
                                                    etaText = String.format("Est. time remaining: %dmin %02dsec", mins, secs);
                                                } else {
                                                    etaText = String.format("Est. time remaining: %dsec", secs);
                                                }
                                            }
                                        }

                                        final String aboveFinal = etaText;

                                        Platform.runLater(() -> {
                                            scanProgress.setProgress(pct);
                                            downloadingLabel.setText(aboveFinal);
                                            scanProgressLabel.setText("Downloading " + estimatedFilterCount
                                                    + " Block Filters " + dlMB + " / " + totalMB + " MB");
                                        });

                                        // Log progress every ~5 seconds (25 × 200ms)
                                        dlLogCounter++;
                                        if (dlLogCounter % 25 == 0) {
                                            int etaForLog = etaVisible ? (int) ((totalBytes - downloaded) /
                                                    ((double) downloaded / (System.currentTimeMillis() - dlStartTime)) / 1000) : 0;
                                            PrivacyLog.get().filterDownloadProgress(downloaded, totalBytes, etaForLog);
                                        }
                                    }
                                    // else: keep indeterminate animation while server is preparing response
                                } catch (Exception ignored) {}
                            }
                        }
                        try { prefetchThread.join(5_000); } catch (InterruptedException ignored) {}

                        {
                            long[] finalProg = PerseverusService.prefetchProgress();
                            long filterDlElapsed = System.currentTimeMillis() - filterDlStartTime;
                            PrivacyLog.get().filterDownloadComplete(finalProg[1], filterDlElapsed);
                            log.info("[perseverus] Filter download complete: {} filters, {} bytes in {}ms",
                                    estimatedFilterCount, finalProg[1], filterDlElapsed);
                        }

                        Platform.runLater(() -> {
                            scanProgress.setProgress(1.0);
                            scanProgressLabel.setText("Filters downloaded");
                            downloadingLabel.setText("");
                            scanStatus.setText("Querying UTXOs...");
                            downloadHelpIcon.setVisible(false);
                            downloadHelpIcon.setManaged(false);
                        });

                        // Brief pause so the user sees the full bar before it resets
                        try { Thread.sleep(400); } catch (InterruptedException ignored) {}

                        Platform.runLater(() -> {
                            scanProgress.setProgress(0);
                            scanProgressLabel.setText("Querying UTXO 0 / " + total);
                        });

                        for (int b = 0; b < batchResults.length; b++) {
                            int utxoIdx = batchUtxoIdxs.get(b);
                            int spentIdx = batchSpendIdxs.get(b);
                            PackRow spendPack = batchPackRows.get(b);

                            if (batchResults[b] != null) {
                                gammas[utxoIdx] = batchResults[b];
                                long proofMs = (proofTimings != null && b < proofTimings.length)
                                        ? proofTimings[b] : spendElapsed / batchSize;
                                PrivacyLog.get().spendComplete(spentIdx,
                                        batchResults[b].length, proofMs);
                                authenticated++;
                                final int fSpentIdx = spentIdx;
                                final PackRow fPack = spendPack;
                                Platform.runLater(() -> {
                                    fPack.markSpent(fSpentIdx);
                                    packsTable.refresh();
                                });
                            } else {
                                // Server rejected this token (typically 409 nullifier
                                // already spent). Mark it spent so it's never re-selected.
                                PrivacyLog.get().spendFailed(spentIdx,
                                        "batch entry returned null — marking token as spent");
                                log.warn("Batch spend entry {} (token {}) returned null "
                                        + "— marking spent to prevent re-selection", b, spentIdx);
                                final int fSpentIdx2 = spentIdx;
                                final PackRow fPack2 = spendPack;
                                Platform.runLater(() -> {
                                    fPack2.markSpent(fSpentIdx2);
                                    packsTable.refresh();
                                });
                            }
                        }

                        int batchFailed = batchSize - authenticated;
                        PrivacyLog.get().batchSpendComplete(batchSize, authenticated, batchFailed, spendElapsed);

                        // Persist updated spent markers so they survive restarts
                        Platform.runLater(() -> persistPacks());

                    } catch (Exception e) {
                        proofTimer.cancel();
                        long spendElapsed = System.currentTimeMillis() - spendT0;
                        log.warn("[perseverus] Batch spend failed after {}ms: {} — "
                                + "falling back to individual spends", spendElapsed, e.getMessage());
                        PrivacyLog.get().batchSpendFallback(e.getMessage());
                        Platform.runLater(() -> scanStatus.setText("Batch failed — spending individually..."));

                        // ── Fallback: individual spend() calls ──
                        for (int b = 0; b < batchPacks.size(); b++) {
                            int utxoIdx = batchUtxoIdxs.get(b);
                            int spentIdx = batchSpendIdxs.get(b);
                            PackRow spendPack = batchPackRows.get(b);
                            final int fb = b;

                            Platform.runLater(() -> {
                                double pct = 0.40 * (fb + 1) / batchSize;
                                scanProgress.setProgress(pct);
                                scanProgressLabel.setText("Spending token " + (fb + 1) + " / " + batchSize + " (fallback)");
                            });

                            long fbT0 = System.currentTimeMillis();
                            try {
                                byte[] gamma = shared.service.spend(
                                        spendPack.pack, spentIdx, batchInputs.get(b));
                                long fbElapsed = System.currentTimeMillis() - fbT0;
                                if (gamma != null) {
                                    gammas[utxoIdx] = gamma;
                                    PrivacyLog.get().spendComplete(spentIdx,
                                            gamma.length, fbElapsed);
                                    authenticated++;
                                    final int fIdx = spentIdx;
                                    final PackRow fPack = spendPack;
                                    Platform.runLater(() -> {
                                        fPack.markSpent(fIdx);
                                        packsTable.refresh();
                                    });
                                    log.debug("Fallback spend {} ok in {}ms", b, fbElapsed);
                                }
                            } catch (Exception ex) {
                                long fbElapsed = System.currentTimeMillis() - fbT0;
                                PrivacyLog.get().spendFailed(spentIdx,
                                        ex.getMessage() + " (fallback, " + fbElapsed + "ms)");
                                log.warn("Fallback spend {} failed: {}", b, ex.getMessage());
                            }
                        }
                        Platform.runLater(() -> {
                            scanProgress.setProgress(0.50);
                            scanStatus.setText("Querying...");
                        });
                    }
                }
            }

            // ── Phase 2: Query with gammas, then reveal one at a time ──
            // Run all queries first (fast — reads from in-memory cache),
            // then reveal results to the user with a staggered delay.
            List<PrivacyQuery.Result> queryResults = new java.util.ArrayList<>();
            List<String> authStatuses = new java.util.ArrayList<>();
            List<PrivacyReport> rowReports = new java.util.ArrayList<>();

            Platform.runLater(() -> {
                scanProgress.setProgress(0);
                scanProgressLabel.setText("Decrypting UTXO 1 / " + total);
                scanStatus.setText("Decrypting KYC tags...");
            });

            for (int i = 0; i < finalScannable.size(); i++) {
                UtxoRow row = finalScannable.get(i);
                completed++;

                byte[] txidBytes = hexToBytes(row.getTxid());
                byte[] gamma = gammas[i];

                // Log the query call
                PrivacyLog.get().scanQuery(completed, total, row.txidShort(),
                        row.getVout(), row.getBlockHeight(), gamma != null);
                long queryT0 = System.currentTimeMillis();

                PrivacyQuery.Result qr;
                PrivacyReport rowReport = null;
                if (txidBytes == null) {
                    qr = new PrivacyQuery.Result("Error", "Error", "privacy-error",
                            "Invalid txid hex");
                } else {
                    PrivacyQuery.QueryOutcome outcome = PrivacyQuery.queryWithRaw(server, txidBytes,
                            row.getVout(), row.getBlockHeight(), decoys, tip, gamma);
                    qr = outcome.result();
                    String token = PrivacyQuery.tagToken(outcome.rawTag());
                    if (token != null && token.startsWith("V3LEAN:")) {
                        com.sparrowwallet.perseverus.V3LeanTag t =
                                com.sparrowwallet.perseverus.V3LeanTag.fromTagString(token);
                        rowReport = com.sparrowwallet.perseverus.V3LeanReportBuilder.build(
                                t, row.getTxid(), row.getVout(), row.getBlockHeight(),
                                row.getValueSats(), row.getNumInputs(), row.getNumOutputs(),
                                row.getFeeSats());
                        // Persist the raw tag + tx meta so the report (grade/
                        // score) can be rebuilt exactly after an app restart.
                        reportData.put(row.getTxid() + ":" + row.getVout(),
                                token + ";in=" + row.getNumInputs()
                                + ";out=" + row.getNumOutputs()
                                + ";fee=" + row.getFeeSats()
                                + ";val=" + row.getValueSats());
                    } else if (PrivacyQuery.UNGRADED_TAG_TYPE.equals(qr.tagType())) {
                        // UTXO is absent from the filter by design (its creating
                        // tx was skipped at build time). Show a neutral, clickable
                        // "No grade" report explaining the exclusion rules rather
                        // than a red error. Not persisted — a re-scan reproduces it.
                        rowReport = PrivacyReport.ungraded(new PrivacyReport.TxSummary(
                                row.getTxid(), row.getVout(), row.getValueSats(),
                                row.getBlockHeight(), "ungraded",
                                row.getNumInputs(), row.getNumOutputs(), row.getFeeSats()));
                    }
                }
                long queryElapsed = System.currentTimeMillis() - queryT0;
                PrivacyLog.get().scanQueryResult(completed, total, row.txidShort(),
                        row.getVout(), qr.tagType(), qr.status(), queryElapsed);

                queryResults.add(qr);
                rowReports.add(rowReport);
                authStatuses.add(gamma != null ? " [ZK ✓]" : "");

                switch (qr.tagType()) {
                    case "Clean", "Unknown", "CoinJoin" -> { privacyPoints += 10; scorable++; }
                    case "Coinbase" -> { privacyPoints += 5; scorable++; }
                    case "KYC Exchange" -> { scorable++; }
                    // Error → skip
                }

                // Store label in the shared registry so the UTXOs tab can import it.
                // Errors and intentionally-ungraded UTXOs contribute no label, so
                // they never affect the overall score (computeOverallScore reads
                // privacyLabels) and don't surface a tag on the UTXOs tab.
                if (!"Error".equals(qr.tagType())
                        && !PrivacyQuery.UNGRADED_TAG_TYPE.equals(qr.tagType())) {
                    privacyLabels.put(row.getTxid() + ":" + row.getVout(), qr.kycTag());

                    // Increment trial counter (if in trial mode)
                    if (Config.get().isPerseverusTrialMode() && !Config.get().isPerseverusDemoMode()) {
                        int used = Config.get().getPerseverusTrialScansUsed() + 1;
                        Config.get().setPerseverusTrialScansUsed(used);
                        log.info("[perseverus] Trial scan {}/{} used",
                            used, Config.PERSEVERUS_FREE_TRIAL_LIMIT);
                    }
                }
            }

            // Reveal results one at a time with a staggered delay
            for (int i = 0; i < finalScannable.size(); i++) {
                final int idx = i;
                final int reveal = i + 1;
                final UtxoRow row = finalScannable.get(i);
                final PrivacyQuery.Result finalQr = queryResults.get(i);
                final String authStatus = authStatuses.get(i);
                final PrivacyReport finalReport = rowReports.get(i);

                Platform.runLater(() -> {
                    double pct = (double) reveal / total;
                    scanProgress.setProgress(pct);
                    scanProgressLabel.setText("Decrypting UTXO " + reveal + " / " + total);
                    row.applyResult(new PrivacyQuery.Result(
                            finalQr.kycTag(), finalQr.tagType(), finalQr.styleClass(),
                            finalQr.status() + authStatus));
                    if (finalReport != null) {
                        row.setReport(finalReport);
                    }
                    resultsTable.refresh();
                    updatePerseverusButtonLabel();
                    EventManager.get().post(new PrivacyLabelsUpdatedEvent());
                });

                if (i < finalScannable.size() - 1) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }

            // Compute score from ALL scanned UTXOs (not just this batch)
            // so partial re-scans don't reset the grade.
            final int score = computeOverallScore();
            final int authCount = authenticated;
            long scanElapsed = System.currentTimeMillis() - scanT0;
            PrivacyLog.get().scanComplete(total, authenticated, score >= 0 ? score : 0, scanElapsed);

            Platform.runLater(() -> {
                scanning = false;
                scanButton.setDisable(false);
                scanSelectedButton.setDisable(false);
                scanProgress.setProgress(1.0);
                String authSuffix = authCount > 0
                        ? " (" + authCount + "/" + total + " ZK-authenticated)"
                        : "";
                scanStatus.setText("Scan complete" + authSuffix);
                scanProgressLabel.setText(total + " UTXOs scanned");

                if (score >= 0) {
                    privacyScore.setText(score + " / 100");
                    privacyScore.getStyleClass().removeAll("privacy-score-high", "privacy-score-medium", "privacy-score-low");
                    if (score >= 70) {
                        privacyScore.getStyleClass().add("privacy-score-high");
                    } else if (score >= 40) {
                        privacyScore.getStyleClass().add("privacy-score-medium");
                    } else {
                        privacyScore.getStyleClass().add("privacy-score-low");
                    }

                    String grade = letterGradeFor(score);
                    letterGrade.setText(grade);
                    letterGrade.getStyleClass().removeAll("grade-a", "grade-b", "grade-c", "grade-d", "grade-f");
                    letterGrade.getStyleClass().add("grade-" + grade.toLowerCase());
                }
                // If score == -1, no scorable labels exist — leave the grade unchanged

                // Persist state after scan
                if (authCount > 0) persistPacks();
                persistScanResults();
            });
          } catch (Exception ex) {
            log.error("[perseverus] Scan thread crashed unexpectedly", ex);
            PrivacyLog.get().info("SCAN CRASH: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            Platform.runLater(() -> {
                scanning = false;
                scanButton.setDisable(false);
                scanSelectedButton.setDisable(false);
                scanStatus.setText("Scan failed: " + ex.getMessage());
            });
          }
        }, "perseverus-scan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /** Convert a 0–100 score to a letter grade (standard US scale). */
    /**
     * Find an existing pack with enough remaining tokens, or issue a
     * new one sized for the scan. Returns null if issuance fails.
     * Called on the FX thread before the scan thread starts.
     */
    /**
     * Demo-mode scan: simulates the full scan flow with fake data,
     * including token spending if bootstrapped and packs are available.
     */
    private void runDemoScan(List<UtxoRow> scannable) {
        // Auto-issue a demo pack if bootstrapped and we need tokens
        final boolean demoAuth = shared.bootstrapped;
        if (demoAuth && (selectedPack == null || selectedPack.remaining() < scannable.size())) {
            IssuedPack pack = demoIssuePack(Math.max(scannable.size(), 8));
            PackRow pr = new PackRow(pack);
            shared.packRows.addFirst(pr);
            selectedPack = pr;
            packsTable.getSelectionModel().select(pr);
            packsTable.setVisible(true);
            packsTable.setManaged(true);
        }
        final PackRow demoPack = demoAuth ? selectedPack : null;

        final int total = scannable.size();
        final int currentCycle = demoScanCycle;
        // Decoy count drives how many block filters we (pretend to) download:
        // each UTXO fetches its real block plus `decoys` decoy blocks.
        final int decoys = currentDecoys();
        final String[] gradeNames = {"F", "D", "C", "B", "A"};
        log.info("[perseverus] Demo scan cycle {} — target grade: {}",
                currentCycle, gradeNames[currentCycle % 5]);

        // Clear previous labels and row results so computeOverallScore()
        // reflects only this scan's results (needed for accurate grade cycling)
        privacyLabels.clear();
        reportData.clear();
        for (UtxoRow r : scannable) {
            r.clearToDefault();
        }
        resultsTable.refresh();
        privacyScore.setText("");
        letterGrade.setText("");
        letterGrade.getStyleClass().removeAll("grade-a", "grade-b", "grade-c", "grade-d", "grade-f");

        Thread scanThread = new Thread(() -> {
          try {
            final int n = scannable.size();

            // Precompute per-row reports + overall score so the reveal is instant.
            final PrivacyReport[] reports = new PrivacyReport[n];
            int scoreSum = 0;
            for (int i = 0; i < n; i++) {
                UtxoRow r = scannable.get(i);
                reports[i] = DemoPrivacyReports.forRow(
                        r.getTxid(), r.getVout(), r.getValueSats(), r.getBlockHeight(), currentCycle);
                scoreSum += reports[i].getScore();
            }
            final int reportScoreSum = scoreSum;

            // ── Phase 1: ZK proof generation — one ~1s progress bar per row ──
            int authenticated = 0;
            int tokenIdx = 0;
            final boolean[] rowAuth = new boolean[n];
            for (int i = 0; i < n; i++) {
                final UtxoRow row = scannable.get(i);
                final int idx = i;

                // Demo spend: consume a token to authenticate this proof.
                if (demoPack != null && tokenIdx < demoPack.pack.packSize()) {
                    final int spentIdx = tokenIdx;
                    Platform.runLater(() -> {
                        demoPack.markSpent(spentIdx);
                        packsTable.refresh();
                    });
                    rowAuth[i] = true;
                    authenticated++;
                    tokenIdx++;
                }

                Platform.runLater(() -> {
                    scanProgress.setProgress((double) idx / Math.max(1, n));
                    scanProgressLabel.setText("Generating ZK proof " + (idx + 1) + " / " + n);
                    row.setStatus("Proving…");
                    row.setProofProgress(0);
                });

                final int steps = 20;
                final int proofMs = 425 + DEMO_RNG.nextInt(200); // ~0.43–0.62s (2x faster)
                for (int s = 1; s <= steps; s++) {
                    try { Thread.sleep(proofMs / steps); }
                    catch (InterruptedException e) { return; }
                    final double p = (double) s / steps;
                    Platform.runLater(() -> row.setProofProgress(p));
                }
                Platform.runLater(() -> {
                    row.setProofProgress(-1);
                    row.setStatus("ZK proof ✓");
                    resultsTable.refresh();
                });
            }

            // ── Phase 2: block-filter download (modelled over a slow Tor link) ──
            // Each UTXO downloads its real block filter plus `decoys` decoy
            // filters, so total filters = UTXOs × (1 + decoys).
            final int blocks = Math.max(1, n * (1 + decoys));
            final long totalBytes = (long) blocks * DEMO_FILTER_BYTES;
            final double speedBps = DEMO_TOR_SPEED_BPS;          // ~100 KB/s over Tor
            final double totalSec = totalBytes / speedBps;
            final long downloadMs = (long) (totalSec * 1000);
            final double totalMb = totalBytes / 1_048_576.0;

            final int tickMs = 250;
            final int dsteps = Math.max(1, (int) (downloadMs / tickMs));
            for (int s = 1; s <= dsteps; s++) {
                try { Thread.sleep(tickMs); }
                catch (InterruptedException e) { return; }
                final double p = (double) s / dsteps;
                final double doneMb = totalMb * p;
                final double remainSec = totalSec * (1 - p);
                Platform.runLater(() -> {
                    scanProgress.setProgress(p);
                    scanProgressLabel.setText(String.format(java.util.Locale.ENGLISH,
                            "Downloading %d block filters — %.1f / %.1f MB · %s remaining · 100 KB/s",
                            blocks, doneMb, totalMb, formatEta(remainSec)));
                });
            }

            // ── Phase 3: decrypt + reveal grades (short stagger per row) ──
            Platform.runLater(() -> {
                scanProgress.setProgress(1.0);
                scanProgressLabel.setText("Decrypting privacy grades…");
            });
            privacyLabels.clear();
        reportData.clear();
            for (int i = 0; i < n; i++) {
                final UtxoRow row = scannable.get(i);
                final PrivacyReport report = reports[i];
                final PrivacyQuery.Result qr = demoQueryResult(i, n);
                final String authStatus = rowAuth[i] ? " [ZK ✓]" : "";

                privacyLabels.put(row.getTxid() + ":" + row.getVout(), qr.kycTag());

                // Trial-fallback (not full demo) increments the free-scan counter.
                if (Config.get().isPerseverusTrialMode() && !Config.get().isPerseverusDemoMode()) {
                    Config.get().setPerseverusTrialScansUsed(
                            Config.get().getPerseverusTrialScansUsed() + 1);
                }

                Platform.runLater(() -> {
                    row.applyResult(new PrivacyQuery.Result(
                            qr.kycTag(), qr.tagType(), qr.styleClass(), "OK" + authStatus));
                    row.setReport(report);
                    resultsTable.refresh();
                    updatePerseverusButtonLabel();
                    EventManager.get().post(new PrivacyLabelsUpdatedEvent());
                });
                // Reveal grades one row at a time, half a second apart.
                try { Thread.sleep(500); }
                catch (InterruptedException e) { return; }
            }

            final int score = n > 0 ? Math.round((float) reportScoreSum / n) : -1;
            final int authCount = authenticated;

            Platform.runLater(() -> {
                scanning = false;
                scanButton.setDisable(false);
                scanSelectedButton.setDisable(false);
                scanProgress.setProgress(1.0);
                String authSuffix = authCount > 0
                        ? " (" + authCount + "/" + n + " ZK-authenticated)"
                        : "";
                scanStatus.setText("Scan complete (demo)" + authSuffix);
                scanProgressLabel.setText(n + " UTXOs scanned");

                if (score >= 0) {
                    privacyScore.setText(score + " / 100");
                    privacyScore.getStyleClass().removeAll("privacy-score-high", "privacy-score-medium", "privacy-score-low");
                    if (score >= 70) privacyScore.getStyleClass().add("privacy-score-high");
                    else if (score >= 40) privacyScore.getStyleClass().add("privacy-score-medium");
                    else privacyScore.getStyleClass().add("privacy-score-low");

                    String grade = letterGradeFor(score);
                    letterGrade.setText(grade);
                    letterGrade.getStyleClass().removeAll("grade-a", "grade-b", "grade-c", "grade-d", "grade-f");
                    letterGrade.getStyleClass().add("grade-" + grade.toLowerCase());
                }

                if (authCount > 0) persistPacks();
                persistScanResults();

                // Advance to next grade in the cycle: F → D → C → B → A → F ...
                demoScanCycle = (demoScanCycle + 1) % 5;
            });
          } catch (Exception ex) {
            log.error("[perseverus] Demo scan crashed", ex);
            Platform.runLater(() -> {
                scanning = false;
                scanButton.setDisable(false);
                scanSelectedButton.setDisable(false);
                scanStatus.setText("Demo scan failed: " + ex.getMessage());
            });
          }
        }, "perseverus-demo-scan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private PackRow findOrIssueScanPack(int tokensNeeded) {
        // Find the pack with the most remaining tokens.
        // All tokens are immediately spendable after issuance.
        PackRow best = null;
        for (PackRow pr : shared.packRows) {
            if (pr.remaining() > 0) {
                if (best == null || pr.remaining() > best.remaining()) {
                    best = pr;
                }
            }
        }
        if (best != null) {
            selectedPack = best;
            packsTable.getSelectionModel().select(best);
            if (best.remaining() < tokensNeeded) {
                log.info("Using pack with {} remaining tokens for {} UTXOs — "
                        + "excess UTXOs will query unauthenticated",
                        best.remaining(), tokensNeeded);
            }
            return best;
        }
        // No pack with tokens — try to auto-issue one.
        try {
            int size = Math.max(tokensNeeded, 8); // at least 8, or enough for the scan
            IssuedPack pack = shared.service.issuePack(size);
            PackRow pr = new PackRow(pack);
            shared.packRows.addFirst(pr);
            selectedPack = pr;
            packsTable.getSelectionModel().select(pr);
            packsTable.setVisible(true);
            packsTable.setManaged(true);
            log.info("Auto-issued pack for scan: size={}", size);
            persistPacks();
            return pr;
        } catch (Exception e) {
            log.warn("Auto-issue failed, scan will be unauthenticated: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Return the first unspent token index in {@code pack} at or after
     * {@code from}, or {@code -1} if none remain.
     */
    private static int nextUnspentIndex(PackRow pack, int from) {
        boolean[] spent = pack.getSpentArray();
        for (int i = from; i < spent.length; i++) {
            if (!spent[i]) return i;
        }
        return -1;
    }

    /**
     * Build a 36-byte outpoint (txid || vout_LE) used as the OPRF
     * preimage when spending a token during scan.
     */
    private static byte[] buildOutpoint(byte[] txid, int vout) {
        if (txid == null || txid.length != 32) return new byte[36];
        byte[] outpoint = new byte[36];
        System.arraycopy(txid, 0, outpoint, 0, 32);
        outpoint[32] = (byte) (vout & 0xFF);
        outpoint[33] = (byte) ((vout >> 8) & 0xFF);
        outpoint[34] = (byte) ((vout >> 16) & 0xFF);
        outpoint[35] = (byte) ((vout >> 24) & 0xFF);
        return outpoint;
    }

    /** Return a reversed copy of the byte array (display↔internal txid order). */
    private static byte[] reverseBytes(byte[] in) {
        if (in == null) return null;
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    /**
     * Compute overall privacy score from ALL entries in privacyLabels.
     * This ensures the score/grade always reflects the full picture,
     * not just the most recent scan batch.
     */
    private int computeOverallScore() {
        int points = 0;
        int scorable = 0;
        // Only THIS account's UTXOs contribute to its score. privacyLabels is a
        // global cache keyed by txid:vout (shared across account tabs), so we
        // iterate this controller's own rows — otherwise an account with no
        // scans would inherit another account's grade (phantom score).
        for (UtxoRow row : rows) {
            String tag = privacyLabels.get(row.getTxid() + ":" + row.getVout());
            if (tag == null || tag.isBlank()) continue;
            if (tag.startsWith("Grade ")) {
                // v3-lean (v5 filter) label, e.g. "Grade F · …". Score by band.
                //
                // Each band maps to a 0-100 value INSIDE the matching
                // letterGradeFor() range (A>=90, B>=80, C>=70, D>=60, F<60),
                // so a wallet whose scanned UTXOs are all one band shows
                // exactly that band as its overall grade. (Previously a C
                // row earned 6/10 = 60%, which letterGradeFor mapped to "D"
                // — a single Grade C scan displayed an overall "D".)
                String band = tag.substring(6).split(" ")[0];
                points += switch (band) {
                    case "A+" -> 98;
                    case "A", "A+/A/B" -> 95;
                    case "B" -> 85;
                    case "C" -> 75;
                    case "D" -> 65;
                    default -> 0; // F
                };
                scorable++;
            } else if (tag.equals("Clean") || tag.equals("Unknown") || tag.equals("CoinJoin")) {
                points += 95; scorable++;   // A — no adverse history
            } else if (tag.equals("Coinbase (Mining)")) {
                points += 75; scorable++;   // C — neutral midline (was half-credit)
            } else if (tag.startsWith("KYC Exchange")) {
                scorable++;  // 0 points — F
            }
            // Other/unrecognized tags are not scorable
        }
        // Simple average of per-UTXO scores, already on the 0-100 grade scale.
        return scorable > 0 ? Math.round((float) points / scorable) : -1;
    }

    /** Format a remaining-seconds estimate as "1m 05s" or "42s". */
    private static String formatEta(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        if (s >= 60) {
            return String.format(java.util.Locale.ENGLISH, "%dm %02ds", s / 60, s % 60);
        }
        return s + "s";
    }

    private static String letterGradeFor(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    /**
     * Open the native privacy dashboard for a scanned UTXO. Renders the
     * row's {@link PrivacyReport} (grade, score, findings, summary) in a
     * separate window. No network I/O — everything is already decoded.
     */
    private void openPrivacyDashboard(UtxoRow row) {
        if (row == null || row.getReport() == null) {
            return;
        }
        // Collect reports for all scanned rows (in table order) so the
        // dashboard's Prev/Next can step through them; open at this row.
        List<PrivacyReport> reports = new ArrayList<>();
        int startIndex = 0;
        for (UtxoRow r : rows) {
            if (r.getReport() != null) {
                if (r == row) {
                    startIndex = reports.size();
                }
                reports.add(r.getReport());
            }
        }
        javafx.stage.Window owner = resultsTable.getScene() != null
                ? resultsTable.getScene().getWindow() : null;
        PrivacyDashboardWindow.show(owner, reports, startIndex);
    }

    /** Convert a hex string to a byte array, or {@code null} on failure. */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Event handlers ──

    @Override
    @Subscribe
    public void walletTabsClosed(WalletTabsClosedEvent event) {
        super.walletTabsClosed(event);

        // Only close our resources if THIS wallet's tabs are being closed
        boolean isOurWallet = event.getClosedWalletTabData().stream()
                .anyMatch(td -> td.getWalletForm() == walletForm);
        if (!isOurWallet) {
            return;
        }

        if (settingsStage != null) {
            settingsStage.close();
            settingsStage = null;
        }
        if (shared.service != null) {
            try { shared.service.close(); } catch (Exception e) { /* swallow */ }
            shared.service = null;
        }
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if (event.getWallet().equals(walletForm.getWallet())) {
            Platform.runLater(this::refreshUtxoRows);
        }
    }

    /**
     * Called by the sign-up wizard after broadcasting a hot wallet payment,
     * so the controller can detect confirmation even when the SP scanner is unavailable.
     */
    public static void notifyHotWalletPayment(String txid, String plan) {
        shared.pendingHotPaymentTxid = txid;
        shared.pendingHotPaymentPlan = plan;
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        // Adopt payment txid from wizard if not yet set.
        // Don't null shared.pendingHotPaymentTxid — other account tabs need to adopt too.
        if (hotWalletPaymentTxid == null && shared.pendingHotPaymentTxid != null) {
            hotWalletPaymentTxid = shared.pendingHotPaymentTxid;
            log.info("[perseverus] Adopted hot wallet payment txid from wizard: {}", hotWalletPaymentTxid);
            PrivacyLog.get().info("Adopted payment txid from wizard: " + hotWalletPaymentTxid);

            // Also update status
            String shortTxid = hotWalletPaymentTxid.length() > 12
                    ? hotWalletPaymentTxid.substring(0, 12) + "..." : hotWalletPaymentTxid;
            Platform.runLater(() -> scanStatus.setText("BTC payment broadcast (" + shortTxid + ") — waiting for confirmation..."));
        }

        // Sync payment status from other account tabs (e.g. "Subscription active" after token issuance)
        String sharedStatus = shared.latestPaymentStatus;
        if (sharedStatus != null && !sharedStatus.equals(lastSyncedPaymentStatus)) {
            lastSyncedPaymentStatus = sharedStatus;
            Platform.runLater(() -> scanStatus.setText(sharedStatus));
        }

        if (event.getWallet().equals(walletForm.getWallet())) {
            Platform.runLater(this::refreshUtxoRows);
        }

        // Detect hot wallet payment broadcast: look for "BTC Medusa" labeled
        // transaction in the master wallet's history.
        if (hotWalletPaymentPending && event.getWallet().equals(walletForm.getWallet())) {
            detectHotWalletPaymentBroadcast();
        }

        // Check pending payment confirmations (both hot and watch-only)
        if (hotWalletPaymentTxid != null) {
            checkHotWalletPaymentConfirmation();
        }
        if (watchOnlyTx2Pending || watchOnlyTx1Pending) {
            checkWatchOnlyTx2Confirmation();
        }

        // Fallback tx1 detection: if watchOnlyPaymentPending and
        // newWalletTransactions hasn't fired for the child wallet (Electrum
        // subscription race), poll the child wallet's balance on every sync.
        if (watchOnlyPaymentPending) {
            checkChildWalletForTx1Arrival();
        }

        // Failsafe: after first sync, check the payment child wallet for
        // orphaned UTXOs — funds that arrived but were never forwarded and
        // whose pending state was lost from Config (e.g. crash, bug, etc.).
        if (event.getWallet().equals(walletForm.getWallet())) {
            checkForOrphanedPaymentUtxos();
        }
    }

    @Subscribe
    public void walletBlockHeightChanged(WalletBlockHeightChangedEvent event) {
        // Adopt payment txid from wizard if not yet set.
        // Don't null shared.pendingHotPaymentTxid — other account tabs need to adopt too.
        if (hotWalletPaymentTxid == null && shared.pendingHotPaymentTxid != null) {
            hotWalletPaymentTxid = shared.pendingHotPaymentTxid;
            log.info("[perseverus] Adopted hot wallet payment txid from wizard: {}", hotWalletPaymentTxid);
            PrivacyLog.get().info("Adopted payment txid from wizard: " + hotWalletPaymentTxid);

            String shortTxid = hotWalletPaymentTxid.length() > 12
                    ? hotWalletPaymentTxid.substring(0, 12) + "..." : hotWalletPaymentTxid;
            Platform.runLater(() -> scanStatus.setText("BTC payment broadcast (" + shortTxid + ") — waiting for confirmation..."));
        }

        // Sync payment status from other account tabs (e.g. "Subscription active" after token issuance)
        String sharedStatus = shared.latestPaymentStatus;
        if (sharedStatus != null && !sharedStatus.equals(lastSyncedPaymentStatus)) {
            lastSyncedPaymentStatus = sharedStatus;
            Platform.runLater(() -> scanStatus.setText(sharedStatus));
        }

        // On each new block, check if any pending payment has confirmed
        if (hotWalletPaymentTxid != null) {
            checkHotWalletPaymentConfirmation();
        }
        if (watchOnlyTx2Pending || watchOnlyTx1Pending) {
            checkWatchOnlyTx2Confirmation();
        }
    }

    /**
     * Fallback tx1 detection for watch-only payments.
     *
     * The primary detection path is newWalletTransactions on the child wallet.
     * But if the child wallet was just created (during getStagingInfo), Electrum
     * may not have subscribed to its addresses yet, so that event never fires.
     *
     * This method is called from walletHistoryChanged on EVERY wallet sync.
     * It polls the child wallet's spendable balance and, if funds have arrived,
     * triggers the same auto-forward logic that newWalletTransactions would.
     */
    private void checkChildWalletForTx1Arrival() {
        if (!watchOnlyPaymentPending) {
            return;
        }
        // Don't collide with an in-flight auto-forward
        if (!autoForwardFiring.compareAndSet(false, true)) {
            log.debug("[perseverus] checkChildWalletForTx1Arrival: skipped — autoForwardFiring held");
            return;
        }

        boolean threadStarted = false;
        try {
            Wallet masterWallet = getWalletForm().getWallet();
            masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
            Storage storage = getWalletForm().getStorage();
            if (storage == null) { return; }

            PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
            if (manager.isHotWallet()) { return; }

            Wallet paymentWallet = manager.getPaymentWallet();
            if (paymentWallet == null) {
                // Child wallet not on disk — create it with deterministic seed.
                // Same keys will be generated, and Electrum will discover UTXOs on sync.
                try {
                    PrivacyLog.get().info("FALLBACK: creating child wallet for tx1 detection");
                    paymentWallet = manager.ensurePaymentWallet();
                    // Just created — it hasn't synced yet, so balance will be 0.
                    // Return and wait for the next walletHistoryChanged after Electrum syncs.
                    PrivacyLog.get().info("  Child wallet created — waiting for Electrum sync");
                    return;
                } catch (Exception e) {
                    log.warn("[perseverus] FALLBACK: failed to create payment wallet", e);
                    return;
                }
            }

            long spendableBalance = paymentWallet.getSpendableUtxos().keySet().stream()
                    .mapToLong(BlockTransactionHashIndex::getValue).sum();
            if (spendableBalance <= 0) {
                // Log periodically so we can diagnose stalls. Also check unconfirmed txs.
                int totalTxCount = paymentWallet.getTransactions().size();
                int utxoCount = paymentWallet.getSpendableUtxos().size();
                log.debug("[perseverus] checkChildWalletForTx1Arrival: child wallet balance=0 (txs={}, utxos={}), waiting for TX1 to arrive",
                        totalTxCount, utxoCount);
                return;
            }

            // Funds are in the child wallet — tx1 has arrived!
            watchOnlyPaymentPending = false;

            // Derive the tx1 txid from the spendable UTXO.  The child wallet may
            // contain many historical transactions from prior payments — we must NOT
            // blindly pick the first entry from the transactions map.  Instead, find
            // the UTXO that pays to our pendingStagingAddress, or fall back to the
            // UTXO with the largest unconfirmed/most-recent value.
            String tx1Txid = "unknown";
            for (BlockTransactionHashIndex utxo : paymentWallet.getSpendableUtxos().keySet()) {
                // The UTXO's hash is the txid of the transaction that created it
                String utxoTxid = utxo.getHash().toString();
                // If we have a staging address, match the UTXO's output against it
                if (pendingStagingAddress != null) {
                    BlockTransaction btx = paymentWallet.getTransactions().get(utxo.getHash());
                    if (btx != null && btx.getTransaction() != null) {
                        for (TransactionOutput txOut : btx.getTransaction().getOutputs()) {
                            try {
                                Address outAddr = txOut.getScript().getToAddress();
                                if (pendingStagingAddress.equals(outAddr)) {
                                    tx1Txid = utxoTxid;
                                    break;
                                }
                            } catch (Exception e) { /* non-standard script */ }
                        }
                    }
                    if (!"unknown".equals(tx1Txid)) break;
                } else {
                    // No staging address — just use the most recent (unconfirmed preferred)
                    tx1Txid = utxoTxid;
                    break;
                }
            }
            // Final fallback: if we still didn't find it, take any spendable UTXO's parent
            if ("unknown".equals(tx1Txid)) {
                for (BlockTransactionHashIndex utxo : paymentWallet.getSpendableUtxos().keySet()) {
                    tx1Txid = utxo.getHash().toString();
                    break;
                }
            }
            pendingTx1Id = tx1Txid;

            log.info("[perseverus] FALLBACK: tx1 detected in child wallet via walletHistoryChanged poll — balance={} sats, tx1={}",
                    spendableBalance, tx1Txid);
            PrivacyLog.get().info("═══ TX1 DETECTED (FALLBACK — walletHistoryChanged poll) ═══");
            PrivacyLog.get().info("  tx1 txid: " + tx1Txid);
            PrivacyLog.get().info("  Spendable balance in child wallet: " + spendableBalance + " sats");
            PrivacyLog.get().info("  Fee rate for TX2: " + pendingFeeRate + " sat/vB");

            // Arm tx1 confirmation tracking
            watchOnlyTx1TxidHex = tx1Txid;
            watchOnlyTx1Pending = true;
            watchOnlyTx1Confirmed = false;
            watchOnlyTx2Confirmed = false;
            Config.get().setPerseverusPendingTx1Txid(tx1Txid);

            // Save tx1 label
            String planName = manualPaymentPlan != null ? manualPaymentPlan.getLabel() : "Monthly";
            try {
                PerseverusLabelStore labelStore = new PerseverusLabelStore(
                        masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet(),
                        getWalletForm().getStorage());
                labelStore.putLabel(tx1Txid, "BTC Medusa " + planName + " — Staging");
            } catch (Exception e) {
                log.warn("[perseverus] Failed to save tx1 label in fallback", e);
            }

            // Show popup
            final String finalTx1Id = tx1Txid;
            Platform.runLater(() -> {
                if (pendingPopupAmount > 0 && pendingPopupLabel != null) {
                    showWatchOnlyPaymentPopup(pendingPopupAmount, pendingPopupLabel);
                }
                updateWatchOnlyPopupTx1(finalTx1Id);
            });

            // Auto-forward on background thread
            final Wallet fwMaster = masterWallet;
            final Wallet fwPayment = paymentWallet;
            Thread forwardThread = new Thread(() -> {
                try {
                    PerseverusPaymentManager fwManager = new PerseverusPaymentManager(fwMaster, storage);
                    Sha256Hash tx2Txid = fwManager.autoForward(fwPayment, pendingFeeRate);
                    PrivacyLog.get().paymentBtcBroadcast(tx2Txid.toString(), 0);

                    String plan = manualPaymentPlan != null ? manualPaymentPlan.name() : "MONTHLY";
                    Config.get().setPerseverusPendingPayment("watch-only", tx2Txid.toString(), plan, 0);
                    Config.get().setPerseverusPendingWatchOnly(true);
                    Config.get().setPerseverusPendingTx2Txid(tx2Txid.toString());

                    // Save tx2 label
                    try {
                        String pn = manualPaymentPlan != null ? manualPaymentPlan.getLabel() : "Monthly";
                        PerseverusLabelStore ls = new PerseverusLabelStore(fwMaster, storage);
                        ls.putLabel(tx2Txid.toString(), "BTC Medusa " + pn + " — Payment");
                    } catch (Exception e) {
                        log.warn("[perseverus] Failed to save tx2 label in fallback", e);
                    }

                    watchOnlyTx2Pending = true;
                    watchOnlyTx2TxidHex = tx2Txid.toString();

                    Platform.runLater(() -> {
                        scanStatus.setText("Payment forwarded to BTC Medusa — waiting for confirmation...");
                        updateWatchOnlyPopupTx2(tx2Txid.toString());
                    });
                } catch (Exception e) {
                    log.error("[perseverus] FALLBACK auto-forward failed", e);
                    PrivacyLog.get().info("FALLBACK auto-forward FAILED: " + e.getMessage());
                    Platform.runLater(() -> {
                        scanStatus.setText("Auto-forward failed — will retry on next sync");
                    });
                    // Re-arm so we retry on next walletHistoryChanged
                    watchOnlyPaymentPending = true;
                } finally {
                    autoForwardFiring.set(false);
                }
            }, "perseverus-fallback-forward");
            forwardThread.setDaemon(true);
            forwardThread.start();
            threadStarted = true; // thread owns autoForwardFiring now
        } finally {
            // Release only if we didn't hand off to the thread
            if (!threadStarted) {
                autoForwardFiring.set(false);
            }
        }
    }

    /**
     * Last-resort failsafe: after the wallet has synced at least once,
     * ensure the "BTC Medusa" payment child wallet exists and check for
     * orphaned UTXOs — funds that arrived but were never forwarded.
     *
     * This covers the truly rare edge case where:
     * - The user broadcast tx1 (to the staging child wallet)
     * - But ALL pending payment state was lost (Config cleared, crash, bug)
     * - The child wallet file might not have been persisted to disk
     *
     * Because the child wallet uses a deterministic seed (derived from
     * the master wallet's xpub), creating a "new" child wallet generates
     * the same keys. Electrum will discover the existing UTXOs during sync.
     *
     * Two phases:
     *   Phase 1 (this method): create wallet if missing, check for UTXOs.
     *           If the wallet was just created, it won't have synced yet —
     *           arm watchOnlyPaymentPending so the newWalletTransactions
     *           handler will pick up UTXOs when they sync.
     *   Phase 2 (newWalletTransactions): when UTXOs arrive at the child
     *           wallet, the existing auto-forward logic handles forwarding.
     *
     * Fires at most once per app lifetime (static AtomicBoolean).
     */
    private void checkForOrphanedPaymentUtxos() {
        // One-shot: skip if already checked this session, or if there's
        // already an active pending payment (the normal flow handles it).
        if (!orphanedUtxoCheckDone.compareAndSet(false, true)) {
            return;
        }
        if (Config.get().hasPerseverusPendingPayment()) {
            return; // normal resume path is handling it
        }
        if (watchOnlyPaymentPending || watchOnlyTx2Pending || hotWalletPaymentPending) {
            return; // in-flight payment in progress
        }

        Wallet masterWallet = getWalletForm().getWallet();
        masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        if (storage == null) {
            return;
        }

        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        if (manager.isHotWallet()) {
            return; // failsafe only applies to watch-only wallets
        }

        // ── Phase 1a: ensure the child wallet exists ──
        // If it was never persisted (e.g. crash before save), create it.
        // The deterministic seed means the same keys are generated, so
        // Electrum will rediscover any existing UTXOs during sync.
        Wallet paymentWallet = manager.getPaymentWallet();
        boolean walletJustCreated = false;
        if (paymentWallet == null) {
            try {
                PrivacyLog.get().info("═══ FAILSAFE: creating child wallet for orphan scan ═══");
                paymentWallet = manager.ensurePaymentWallet();
                walletJustCreated = true;
                PrivacyLog.get().info("  Child wallet created — waiting for Electrum sync to discover UTXOs");
            } catch (Exception e) {
                log.warn("[perseverus] FAILSAFE: failed to create payment wallet", e);
                PrivacyLog.get().info("FAILSAFE: failed to create child wallet: " + e.getMessage());
                orphanedUtxoCheckDone.set(false); // retry later
                return;
            }
        }

        // ── Phase 1b: check current UTXOs ──
        long spendableBalance = paymentWallet.getSpendableUtxos().keySet().stream()
                .mapToLong(BlockTransactionHashIndex::getValue).sum();

        if (spendableBalance > 0) {
            // UTXOs already present — forward immediately
            PrivacyLog.get().info("═══ FAILSAFE: ORPHANED PAYMENT UTXO DETECTED ═══");
            PrivacyLog.get().info("  Spendable balance in child wallet: " + spendableBalance + " sats");
            for (BlockTransactionHashIndex utxo : paymentWallet.getSpendableUtxos().keySet()) {
                PrivacyLog.get().info(String.format("    %s:%d  value=%d  height=%s",
                        utxo.getHashAsString(), utxo.getIndex(), utxo.getValue(),
                        utxo.getHeight() > 0 ? String.valueOf(utxo.getHeight()) : "mempool"));
            }
            failsafeAutoForward(masterWallet, paymentWallet, storage);
        } else if (walletJustCreated) {
            // Wallet was just created — no UTXOs yet because it hasn't synced.
            // Arm the watchOnlyPaymentPending flag so that when Electrum sync
            // discovers UTXOs and fires newWalletTransactions, the existing
            // auto-forward handler will pick them up.
            PrivacyLog.get().info("  FAILSAFE: child wallet is empty (not yet synced). Arming auto-forward listener.");
            Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
            pendingFeeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();
            watchOnlyPaymentPending = true;
            Platform.runLater(() -> scanStatus.setText("Scanning payment wallet for orphaned funds..."));
            log.info("[perseverus] FAILSAFE: armed watchOnlyPaymentPending for newly-created child wallet");
        }
        // else: wallet existed and is empty — nothing to do
    }

    /**
     * Background thread that auto-forwards orphaned UTXOs from the payment
     * child wallet to the BTC Medusa SP address. Called by the failsafe
     * when it finds spendable funds with no pending payment state.
     */
    private void failsafeAutoForward(Wallet masterWallet, Wallet paymentWallet, Storage storage) {
        Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
        final double feeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();
        PrivacyLog.get().info("  Fee rate for failsafe TX2: " + feeRate + " sat/vB");

        Platform.runLater(() -> scanStatus.setText("Orphaned payment detected — forwarding to BTC Medusa..."));

        final Wallet fwMaster = masterWallet;
        final Wallet fwPayment = paymentWallet;
        Thread failsafeThread = new Thread(() -> {
            if (!autoForwardFiring.compareAndSet(false, true)) {
                log.debug("[perseverus] FAILSAFE: another auto-forward in progress — will retry next restart");
                return;
            }
            try {
                PerseverusPaymentManager fwManager =
                        new PerseverusPaymentManager(fwMaster, storage);
                PrivacyLog.get().info("═══ FAILSAFE AUTO-FORWARD ═══");
                Sha256Hash tx2Txid = fwManager.autoForward(fwPayment, feeRate);
                PrivacyLog.get().paymentBtcBroadcast(tx2Txid.toString(), 0);
                PrivacyLog.get().info("  FAILSAFE: auto-forward SUCCESS — tx2: " + tx2Txid);

                // Persist pending state so the normal confirmation flow takes over
                Config.get().setPerseverusPendingPayment(
                        "watch-only", tx2Txid.toString(), "MONTHLY", 0);
                Config.get().setPerseverusPendingWatchOnly(true);
                Config.get().setPerseverusPendingTx2Txid(tx2Txid.toString());

                // Save tx2 label
                try {
                    PerseverusLabelStore ls = new PerseverusLabelStore(fwMaster, storage);
                    ls.putLabel(tx2Txid.toString(), "BTC Medusa — Payment (recovered)");
                } catch (Exception e) {
                    log.warn("[perseverus] FAILSAFE: failed to save tx2 label", e);
                }

                // Arm confirmation watcher
                watchOnlyTx2Pending = true;
                watchOnlyTx2TxidHex = tx2Txid.toString();

                log.info("[perseverus] FAILSAFE: orphaned payment forwarded — tx2: {}", tx2Txid);
                Platform.runLater(() -> {
                    scanStatus.setText("Recovered payment forwarded — waiting for confirmation...");
                });
            } catch (Exception e) {
                log.error("[perseverus] FAILSAFE: auto-forward failed", e);
                PrivacyLog.get().info("FAILSAFE AUTO-FORWARD FAILED: " + e.getMessage());
                Platform.runLater(() -> {
                    scanStatus.setText("Orphaned funds in payment wallet — auto-forward failed, will retry on next restart");
                });
                // Reset the one-shot flag so it retries on next walletHistoryChanged
                orphanedUtxoCheckDone.set(false);
            } finally {
                autoForwardFiring.set(false);
            }
        }, "perseverus-failsafe-forward");
        failsafeThread.setDaemon(true);
        failsafeThread.start();
    }

    /**
     * Check if watch-only tx1 and/or tx2 have confirmed in the payment child wallet.
     * Called on wallet history changes and new blocks.
     * Updates the popup labels with confirmation status and only issues tokens
     * once tx2 has confirmed (which implies tx1 is also confirmed or in the same block).
     */
    private void checkWatchOnlyTx2Confirmation() {
        if (!watchOnlyTx2Pending && !watchOnlyTx1Pending) {
            return;
        }

        Wallet masterWallet = getWalletForm().getWallet();
        masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        if (storage == null) {
            log.debug("[perseverus] checkWatchOnlyConfirmation: no storage — skipping");
            return;
        }

        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        Wallet paymentWallet = manager.getPaymentWallet();
        if (paymentWallet == null) {
            log.debug("[perseverus] checkWatchOnlyConfirmation: payment wallet null — skipping");
            return;
        }

        Map<Sha256Hash, BlockTransaction> txMap = paymentWallet.getTransactions();

        // ── Check tx1 confirmation ──
        if (watchOnlyTx1Pending && watchOnlyTx1TxidHex != null && !watchOnlyTx1Confirmed) {
            Sha256Hash tx1Hash = Sha256Hash.wrap(watchOnlyTx1TxidHex);
            BlockTransaction tx1 = txMap.get(tx1Hash);
            if (tx1 != null && tx1.getHeight() > 0) {
                watchOnlyTx1Confirmed = true;
                watchOnlyTx1Pending = false;
                log.info("[perseverus] Watch-only tx1 {} confirmed at height {}",
                        watchOnlyTx1TxidHex, tx1.getHeight());
                PrivacyLog.get().info("═══ TX1 CONFIRMED ═══");
                PrivacyLog.get().info("  txid: " + watchOnlyTx1TxidHex);
                PrivacyLog.get().info("  height: " + tx1.getHeight());
                final int h = tx1.getHeight();
                Platform.runLater(() -> {
                    if (watchOnlyTx1Label != null) {
                        watchOnlyTx1Label.setText("TX 1 — to staging address:  ✓ Confirmed (block " + h + ")");
                        watchOnlyTx1Label.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
                    }
                    if (!watchOnlyTx2Confirmed) {
                        scanStatus.setText("Staging confirmed — waiting for payment confirmation...");
                        if (watchOnlyPopupStatus != null) {
                            watchOnlyPopupStatus.setText("Staging confirmed — waiting for payment confirmation...");
                        }
                    }
                });
            }
        }

        // ── Check tx2 confirmation ──
        if (watchOnlyTx2Pending && watchOnlyTx2TxidHex != null && !watchOnlyTx2Confirmed) {
            Sha256Hash tx2Hash = Sha256Hash.wrap(watchOnlyTx2TxidHex);
            BlockTransaction tx2 = txMap.get(tx2Hash);

            if (tx2 != null && tx2.getHeight() > 0) {
                // tx2 confirmed — also mark tx1 as confirmed (tx2 spends tx1)
                watchOnlyTx2Confirmed = true;
                watchOnlyTx2Pending = false;
                watchOnlyTx1Confirmed = true;
                watchOnlyTx1Pending = false;
                log.info("[perseverus] Watch-only tx2 {} confirmed at height {}",
                        watchOnlyTx2TxidHex, tx2.getHeight());
                PrivacyLog.get().info("═══ TX2 CONFIRMED ═══");
                PrivacyLog.get().info("  txid: " + watchOnlyTx2TxidHex);
                PrivacyLog.get().info("  height: " + tx2.getHeight());
                PrivacyLog.get().info("  → Both transactions confirmed — issuing tokens now...");

                final int h2 = tx2.getHeight();
                Platform.runLater(() -> {
                    scanStatus.setText("Payment confirmed — issuing tokens...");
                    if (watchOnlyTx2Label != null) {
                        watchOnlyTx2Label.setText("TX 2 — forwarded to BTC Medusa:  ✓ Confirmed (block " + h2 + ")");
                        watchOnlyTx2Label.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
                    }
                    // Also update tx1 label if it wasn't already
                    if (watchOnlyTx1Label != null && !watchOnlyTx1Label.getText().contains("✓")) {
                        watchOnlyTx1Label.setText("TX 1 — to staging address:  ✓ Confirmed");
                        watchOnlyTx1Label.setStyle("-fx-font-size: 11px; -fx-opacity: 0.8;");
                    }
                    if (watchOnlyPopupStatus != null) {
                        watchOnlyPopupStatus.setText("Both transactions confirmed — issuing tokens...");
                    }
                });

                issueTokensAfterSpConfirmation();
            } else if (tx2 != null) {
                // tx2 visible but unconfirmed
                log.debug("[perseverus] checkWatchOnlyConfirmation: tx2 visible but unconfirmed (height={})",
                        tx2.getHeight());
                Platform.runLater(() -> {
                    if (!watchOnlyTx1Confirmed) {
                        scanStatus.setText("Both transactions broadcast — waiting for block confirmation...");
                    } else {
                        scanStatus.setText("Payment forwarded — waiting for block confirmation...");
                    }
                });
            } else {
                log.debug("[perseverus] checkWatchOnlyConfirmation: tx2 {} not yet in wallet txMap (size={})",
                        watchOnlyTx2TxidHex.substring(0, 12), txMap.size());
            }
        }

        // ── tx1 pending but tx2 not yet broadcast (auto-forward hasn't completed) ──
        if (watchOnlyTx1Pending && !watchOnlyTx2Pending && watchOnlyTx2TxidHex == null) {
            Platform.runLater(() -> {
                scanStatus.setText("Staging transaction broadcast — waiting for auto-forward...");
            });
        }
    }

    /**
     * Detect when the hot wallet payment has been broadcast by scanning
     * the master wallet's transactions for one labeled "BTC Medusa ...".
     * Called from walletHistoryChanged when hotWalletPaymentPending is true.
     */
    private void detectHotWalletPaymentBroadcast() {
        if (!hotWalletPaymentPending || hotWalletPaymentLabel == null) {
            return;
        }

        Wallet wallet = getWalletForm().getWallet();
        Map<Sha256Hash, BlockTransaction> txMap = wallet.getTransactions();

        for (Map.Entry<Sha256Hash, BlockTransaction> entry : txMap.entrySet()) {
            BlockTransaction tx = entry.getValue();
            String label = tx.getLabel();
            String txidStr = entry.getKey().toString();
            if (label != null && label.contains("BTC Medusa")) {
                // Skip transactions that already existed when we armed the watcher
                if (paymentStartedKnownTxids.contains(txidStr)) {
                    PrivacyLog.get().info("DETECT: skipping pre-existing tx " + txidStr + " (height=" + tx.getHeight() + ")");
                    continue;
                }
                // Found it — this is our NEW payment
                hotWalletPaymentPending = false;
                hotWalletPaymentTxid = txidStr;

                log.info("[perseverus] Hot wallet payment detected: txid={}, label={}, height={}",
                        hotWalletPaymentTxid, label, tx.getHeight());
                PrivacyLog.get().paymentBtcBroadcast(hotWalletPaymentTxid, 0);

                // Persist the txid so we can track on restart
                String plan = Config.get().getPerseverusPendingPlan();
                Config.get().setPerseverusPendingPayment(
                        "hot-wallet", hotWalletPaymentTxid, plan != null ? plan : "MONTHLY", 0);

                if (tx.getHeight() > 0) {
                    // Already confirmed — issue tokens immediately
                    log.info("[perseverus] Hot wallet payment already confirmed at height {}", tx.getHeight());
                    Platform.runLater(() -> scanStatus.setText("Payment confirmed — issuing tokens..."));
                    issueTokensAfterSpConfirmation();
                } else {
                    // Unconfirmed — show popup and wait for confirmation
                    Platform.runLater(() -> {
                        scanStatus.setText("Payment broadcast — waiting for block confirmation...");
                        setStatusTxid(hotWalletPaymentTxid);
                        if (pendingPopupAmount > 0 && pendingPopupLabel != null) {
                            showPaymentStatusPopup(pendingPopupAmount, pendingPopupLabel);
                        }
                        if (paymentPopupTitle != null) paymentPopupTitle.setText("Payment Broadcast");
                        setPaymentPopupTxid(hotWalletPaymentTxid);
                    });
                }
                return;
            }
        }
    }

    /**
     * Check if the hot wallet payment transaction has confirmed.
     * Called from walletHistoryChanged and walletBlockHeightChanged.
     */
    private void checkHotWalletPaymentConfirmation() {
        if (hotWalletPaymentTxid == null) {
            return;
        }

        Wallet wallet = getWalletForm().getWallet();
        Sha256Hash txHash = Sha256Hash.wrap(hotWalletPaymentTxid);
        BlockTransaction tx = wallet.getTransactions().get(txHash);

        if (tx != null && tx.getHeight() > 0) {
            // Confirmed!
            String txid = hotWalletPaymentTxid;
            hotWalletPaymentTxid = null; // don't fire again
            shared.pendingHotPaymentTxid = null;  // allow other instances to stop adopting
            shared.pendingHotPaymentPlan = null;

            log.info("[perseverus] Hot wallet payment {} confirmed at height {}",
                    txid, tx.getHeight());
            PrivacyLog.get().info("Hot wallet payment confirmed at height " + tx.getHeight());

            Platform.runLater(() -> {
                scanStatus.setText("Payment confirmed — issuing tokens...");
                if (paymentPopupTitle != null) {
                    paymentPopupTitle.setText("Payment Confirmed");
                    paymentPopupTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #27ae60;");
                }
                if (paymentPopupStatus != null) paymentPopupStatus.setText("Issuing privacy tokens...");
            });

            issueTokensAfterSpConfirmation();
        }
    }

    @Subscribe
    public void medusaTransportChanged(MedusaTransportChangedEvent event) {
        // Transport changed in settings — update the server URL field and
        // close the existing connection so the next scan reconnects with
        // the new transport.
        Platform.runLater(() -> {
            String newUrl = Config.get().getPerseverusServerUrl();
            if (newUrl != null && !newUrl.isBlank()) {
                serverUrl.setText(newUrl);
            }
            // Close current service so next scan opens a fresh connection
            if (shared.service != null) {
                try { shared.service.close(); } catch (Exception e) { /* swallow */ }
                shared.service = null;
            }
            String transportLabel = event.getTransport().getLabel();
            setStatus("Transport changed to " + transportLabel + " — reconnect on next scan");
        });
    }

    /**
     * Listens for new transactions on the Perseverus Payment child wallet.
     * When the user broadcasts tx1 (to the staging address), Sparrow detects
     * it as an incoming transaction on the child wallet. This triggers the
     * auto-forward: the child wallet builds, signs, and broadcasts tx2
     * (a silent payment to the BTC Medusa SP address).
     */
    @Subscribe
    public void newWalletTransactions(NewWalletTransactionsEvent event) {
        // Diagnostic: log every event so we can see if child wallet events arrive
        if (watchOnlyPaymentPending || hotWalletPaymentPending) {
            PrivacyLog.get().info("newWalletTransactions: wallet=" + event.getWallet().getDisplayName()
                    + "  txCount=" + event.getBlockTransactions().size()
                    + "  watchOnlyPending=" + watchOnlyPaymentPending
                    + "  hotPending=" + hotWalletPaymentPending);
        }

        // ── Hot wallet: detect manual payment broadcast by label ──
        if (hotWalletPaymentPending && hotWalletPaymentLabel != null) {
            Wallet eventWallet = event.getWallet();
            if (eventWallet.equals(walletForm.getWallet())) {
                for (BlockTransaction btx : event.getBlockTransactions()) {
                    String label = btx.getLabel();
                    String txidStr = btx.getHashAsString();
                    if (label != null && label.contains("BTC Medusa")
                            && !paymentStartedKnownTxids.contains(txidStr)) {
                        // Found our payment broadcast
                        hotWalletPaymentPending = false;
                        hotWalletPaymentTxid = txidStr;

                        log.info("[perseverus] Hot wallet payment detected via newWalletTransactions: txid={}, label={}, height={}",
                                txidStr, label, btx.getHeight());
                        PrivacyLog.get().paymentBtcBroadcast(txidStr, 0);

                        // Persist the txid
                        String plan = Config.get().getPerseverusPendingPlan();
                        Config.get().setPerseverusPendingPayment(
                                "hot-wallet", txidStr, plan != null ? plan : "MONTHLY", 0);

                        // Notify other account tabs
                        shared.pendingHotPaymentTxid = txidStr;
                        shared.pendingHotPaymentPlan = plan;

                        if (btx.getHeight() > 0) {
                            Platform.runLater(() -> scanStatus.setText("Payment confirmed — issuing tokens..."));
                            issueTokensAfterSpConfirmation();
                        } else {
                            Platform.runLater(() -> {
                                scanStatus.setText("Payment broadcast — waiting for confirmation...");
                                setStatusTxid(txidStr);
                                if (pendingPopupAmount > 0 && pendingPopupLabel != null) {
                                    showPaymentStatusPopup(pendingPopupAmount, pendingPopupLabel);
                                }
                                if (paymentPopupTitle != null) paymentPopupTitle.setText("Payment Broadcast");
                                setPaymentPopupTxid(txidStr);
                            });
                        }
                        return;
                    }
                }
            }
        }

        if (!watchOnlyPaymentPending) {
            return;
        }

        // ── Detect TX1 broadcast from the Deposit (master) wallet ──
        // When the user signs + broadcasts from the Send tab, the MASTER wallet
        // sees the outgoing transaction. We detect it here to update the status
        // and show the popup, even though the child wallet may not have synced yet.
        Wallet eventWallet = event.getWallet();
        if (eventWallet.equals(walletForm.getWallet()) && pendingStagingAddress != null) {
            for (BlockTransaction btx : event.getBlockTransactions()) {
                if (btx.getTransaction() != null) {
                    for (TransactionOutput txOut : btx.getTransaction().getOutputs()) {
                        try {
                            Address outAddr = txOut.getScript().getToAddress();
                            if (pendingStagingAddress.equals(outAddr)) {
                                String tx1Txid = btx.getHashAsString();
                                int height = btx.getHeight();
                                log.info("[perseverus] TX1 broadcast detected from Deposit wallet: txid={}, height={}, value={} sats",
                                        tx1Txid, height, txOut.getValue());
                                PrivacyLog.get().info("═══ TX1 BROADCAST DETECTED (from Deposit wallet) ═══");
                                PrivacyLog.get().info("  txid: " + tx1Txid);
                                PrivacyLog.get().info("  value to staging address: " + txOut.getValue() + " sats");
                                PrivacyLog.get().info("  height: " + (height > 0 ? String.valueOf(height) : "mempool"));

                                // Arm tx1 tracking
                                pendingTx1Id = tx1Txid;
                                watchOnlyTx1TxidHex = tx1Txid;
                                watchOnlyTx1Pending = true;
                                watchOnlyTx1Confirmed = height > 0;
                                Config.get().setPerseverusPendingTx1Txid(tx1Txid);

                                // Save tx1 label
                                String planName = manualPaymentPlan != null ? manualPaymentPlan.getLabel() : "Monthly";
                                try {
                                    Wallet mw = getWalletForm().getWallet();
                                    mw = mw.isMasterWallet() ? mw : mw.getMasterWallet();
                                    PerseverusLabelStore labelStore = new PerseverusLabelStore(mw, getWalletForm().getStorage());
                                    labelStore.putLabel(tx1Txid, "BTC Medusa " + planName + " — Staging");
                                } catch (Exception e) {
                                    log.warn("[perseverus] Failed to save tx1 label from newWalletTransactions", e);
                                }

                                // Show popup and update status
                                final String finalTx1Id = tx1Txid;
                                final boolean confirmed = height > 0;
                                Platform.runLater(() -> {
                                    if (pendingPopupAmount > 0 && pendingPopupLabel != null) {
                                        showWatchOnlyPaymentPopup(pendingPopupAmount, pendingPopupLabel);
                                    }
                                    updateWatchOnlyPopupTx1(finalTx1Id);
                                    if (confirmed) {
                                        scanStatus.setText("Staging confirmed — waiting for auto-forward...");
                                    } else {
                                        scanStatus.setText("Staging transaction broadcast — waiting for child wallet sync...");
                                    }
                                });

                                // Don't return — fall through to the child wallet check below
                                // in case the child wallet ALSO received it in the same event batch
                                break;
                            }
                        } catch (Exception e) {
                            // getToAddress can throw for non-standard scripts — skip
                        }
                    }
                }
            }
        }

        // Static guard: only one PrivacyController instance may fire the
        // auto-forward.  Multiple instances receive the same event; without
        // this, each one independently starts an auto-forward thread.
        if (!autoForwardFiring.compareAndSet(false, true)) {
            log.debug("[perseverus] Another controller instance is already handling auto-forward — skipping");
            watchOnlyPaymentPending = false;
            return;
        }

        // Only care about the payment child wallet, not the master wallet
        Wallet masterWallet = getWalletForm().getWallet();
        masterWallet = masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet();
        Storage storage = getWalletForm().getStorage();
        if (storage == null) {
            autoForwardFiring.set(false);
            return;
        }

        PerseverusPaymentManager manager = new PerseverusPaymentManager(masterWallet, storage);
        Wallet paymentWallet = manager.getPaymentWallet();
        if (paymentWallet == null || !eventWallet.equals(paymentWallet)) {
            autoForwardFiring.set(false);
            return;
        }

        // Guard: ignore sync events for old transactions — only act if the
        // child wallet actually has spendable funds now.
        long spendableBalance = paymentWallet.getSpendableUtxos().keySet().stream()
                .mapToLong(BlockTransactionHashIndex::getValue).sum();
        if (spendableBalance <= 0) {
            log.debug("[perseverus] newWalletTransactions on payment wallet but no spendable "
                    + "balance — ignoring (likely a sync of old txs)");
            autoForwardFiring.set(false);
            return;
        }

        // Funds arrived at the payment wallet — this is tx1
        // Grab the txid of the incoming transaction for the confirmation dialog
        String tx1Txid = "unknown";
        if (!event.getBlockTransactions().isEmpty()) {
            tx1Txid = event.getBlockTransactions().get(0).getHashAsString();
        }
        pendingTx1Id = tx1Txid;
        watchOnlyPaymentPending = false; // only fire once

        log.info("[perseverus] Funds arrived at staging address — tx1: {}", tx1Txid);
        log.info("[perseverus] Auto-forwarding to BTC Medusa SP address...");
        PrivacyLog.get().info("═══ TX1 DETECTED AT CHILD WALLET ═══");
        PrivacyLog.get().info("  tx1 txid: " + tx1Txid);
        PrivacyLog.get().info("  Spendable balance in child wallet: " + spendableBalance + " sats");
        PrivacyLog.get().info("  Incoming transactions in event: " + event.getBlockTransactions().size());
        for (int i = 0; i < event.getBlockTransactions().size(); i++) {
            BlockTransaction btx = event.getBlockTransactions().get(i);
            PrivacyLog.get().info(String.format("    event_tx[%d]: %s  height=%d",
                    i, btx.getHashAsString(), btx.getHeight()));
        }
        PrivacyLog.get().info("  Fee rate for TX2: " + pendingFeeRate + " sat/vB");
        PrivacyLog.get().info("  → Starting auto-forward to BTC Medusa SP address...");

        final String finalTx1Id = tx1Txid;

        // Arm tx1 confirmation tracking and persist to Config for restart survival
        watchOnlyTx1TxidHex = tx1Txid;
        watchOnlyTx1Pending = true;
        watchOnlyTx1Confirmed = false;
        watchOnlyTx2Confirmed = false;
        Config.get().setPerseverusPendingTx1Txid(tx1Txid);

        // Save tx1 label to encrypted label store
        String planName = manualPaymentPlan != null ? manualPaymentPlan.getLabel() : "Monthly";
        try {
            PerseverusLabelStore labelStore = new PerseverusLabelStore(
                    masterWallet.isMasterWallet() ? masterWallet : masterWallet.getMasterWallet(),
                    getWalletForm().getStorage());
            labelStore.putLabel(tx1Txid, "BTC Medusa " + planName + " — Staging");
        } catch (Exception e) {
            log.warn("[perseverus] Failed to save tx1 label", e);
        }

        // Show the watch-only popup NOW (tx1 has been broadcast and detected)
        // and immediately show tx1 info
        Platform.runLater(() -> {
            if (pendingPopupAmount > 0 && pendingPopupLabel != null) {
                showWatchOnlyPaymentPopup(pendingPopupAmount, pendingPopupLabel);
            }
            updateWatchOnlyPopupTx1(finalTx1Id);
        });

        // Auto-forward on a background thread
        Thread forwardThread = new Thread(() -> {
            try {
                // The payment wallet already has its own seed, so it can sign
                Sha256Hash tx2Txid = manager.autoForward(paymentWallet, pendingFeeRate);
                PrivacyLog.get().paymentBtcBroadcast(tx2Txid.toString(), 0);

                // Persist pending payment as watch-only — no SP scanner needed.
                // Sparrow's native wallet sync will detect tx2 confirmation
                // in the payment child wallet.
                String plan = manualPaymentPlan != null ? manualPaymentPlan.name() : "MONTHLY";
                Config.get().setPerseverusPendingPayment(
                        "watch-only", tx2Txid.toString(), plan, 0);
                Config.get().setPerseverusPendingWatchOnly(true);
                Config.get().setPerseverusPendingTx2Txid(tx2Txid.toString());

                // Save tx2 label to encrypted label store
                try {
                    Wallet mw = getWalletForm().getWallet();
                    mw = mw.isMasterWallet() ? mw : mw.getMasterWallet();
                    String pn = manualPaymentPlan != null ? manualPaymentPlan.getLabel() : "Monthly";
                    PerseverusLabelStore ls = new PerseverusLabelStore(mw, getWalletForm().getStorage());
                    ls.putLabel(tx2Txid.toString(), "BTC Medusa " + pn + " — Payment");
                } catch (Exception e) {
                    log.warn("[perseverus] Failed to save tx2 label", e);
                }

                pendingPopupAmount = manualPaymentPlan != null ? manualPaymentPlan.getAmountSats() : 0;
                pendingPopupLabel = manualPaymentPlan != null ? manualPaymentPlan.getLabel() : "Monthly";

                // Arm the confirmation watcher — on each new block, we check
                // the payment wallet for tx2 confirmation via Sparrow's sync.
                watchOnlyTx2Pending = true;
                watchOnlyTx2TxidHex = tx2Txid.toString();

                log.info("[perseverus] Watch-only: tx2 broadcast {} — waiting for Sparrow sync confirmation", tx2Txid);

                Platform.runLater(() -> {
                    scanStatus.setText("Payment forwarded to BTC Medusa — waiting for confirmation...");
                    updateWatchOnlyPopupTx2(tx2Txid.toString());
                });
            } catch (Exception e) {
                log.error("[perseverus] Auto-forward failed", e);
                Platform.runLater(() -> {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.initOwner(resultsTable.getScene().getWindow());
                    err.setTitle("Auto-Forward Failed");
                    err.setHeaderText("The staging payment was received, but the forwarding transaction failed.");
                    err.setContentText(
                        "Transaction 1 (to staging): " + finalTx1Id + "\n\n"
                        + "Error: " + e.getMessage() + "\n\n"
                        + "The funds are in the Perseverus Payment child wallet. "
                        + "You can retry by sending them manually to the BTC Medusa SP address."
                    );
                    PrivacyLog.get().info("POPUP [Auto-Forward Failed]: tx1=" + finalTx1Id
                            + ", error=" + e.getMessage());
                    err.showAndWait();
                });
            } finally {
                autoForwardFiring.set(false);
            }
        }, "perseverus-auto-forward");
        forwardThread.setDaemon(true);
        forwardThread.start();
    }

    // ── Watch-only popup fields ──
    private volatile Stage watchOnlyPopup;
    private volatile Label watchOnlyPopupTitle;
    private volatile Label watchOnlyPopupStatus;
    private volatile ProgressIndicator watchOnlyPopupSpinner;
    private volatile VBox watchOnlyPopupContent;
    private volatile Hyperlink watchOnlyTx1Link;
    private volatile Hyperlink watchOnlyTx2Link;
    private volatile Label watchOnlyTx1Label;
    private volatile Label watchOnlyTx2Label;

    /**
     * Opens a live popup for the watch-only 2-tx payment flow.
     * Shows status updates and clickable txid links as the flow progresses.
     * Must be called on the FX thread.
     */
    private void showWatchOnlyPaymentPopup(long amount, String planLabel) {
        if (watchOnlyPopup != null && watchOnlyPopup.isShowing()) {
            return;
        }

        Stage popup = new Stage();
        popup.initModality(Modality.NONE);
        popup.initOwner(resultsTable.getScene().getWindow());
        popup.setTitle("BTC Medusa — Watch-Only Payment");
        popup.setResizable(false);

        VBox root = new VBox(12);
        root.setPadding(new Insets(24));
        root.setAlignment(javafx.geometry.Pos.CENTER);
        root.setStyle("-fx-background-color: -fx-background;");

        // Logo
        ImageView logo = null;
        try {
            Image img = new Image(getClass().getResourceAsStream("/image/perseverus-white.png"));
            logo = new ImageView(img);
            logo.setFitWidth(50);
            logo.setFitHeight(50);
            logo.setPreserveRatio(true);
        } catch (Exception ignored) {}

        Label title = new Label("Watch-Only Payment");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        title.setAlignment(javafx.geometry.Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        Label planInfo = new Label(planLabel + " — " + String.format("%,d sats", amount));
        planInfo.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        planInfo.setAlignment(javafx.geometry.Pos.CENTER);
        planInfo.setMaxWidth(Double.MAX_VALUE);

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(40, 40);

        Label status = new Label("Sign with hardware wallet and broadcast...");
        status.setStyle("-fx-font-size: 13px;");
        status.setWrapText(true);
        status.setAlignment(javafx.geometry.Pos.CENTER);
        status.setMaxWidth(420);

        // TX1 row (initially hidden)
        Label tx1Lbl = new Label("TX 1 — to staging address:");
        tx1Lbl.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        tx1Lbl.setVisible(false);
        tx1Lbl.setManaged(false);
        Hyperlink tx1Link = new Hyperlink("");
        tx1Link.setStyle("-fx-font-size: 10.5px; -fx-font-family: monospace;");
        tx1Link.setWrapText(true);
        tx1Link.setMaxWidth(420);
        tx1Link.setVisible(false);
        tx1Link.setManaged(false);

        // TX2 row (initially hidden)
        Label tx2Lbl = new Label("TX 2 — forwarded to BTC Medusa:");
        tx2Lbl.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        tx2Lbl.setVisible(false);
        tx2Lbl.setManaged(false);
        Hyperlink tx2Link = new Hyperlink("");
        tx2Link.setStyle("-fx-font-size: 10.5px; -fx-font-family: monospace;");
        tx2Link.setWrapText(true);
        tx2Link.setMaxWidth(420);
        tx2Link.setVisible(false);
        tx2Link.setManaged(false);

        Region spacer = new Region();
        spacer.setPrefHeight(5);

        Button closeBtn = new Button("Close");
        closeBtn.setPrefWidth(100);
        closeBtn.setStyle("-fx-font-size: 13px;");
        closeBtn.setOnAction(e -> popup.close());

        VBox content = new VBox(8);
        content.setAlignment(javafx.geometry.Pos.CENTER);
        if (logo != null) content.getChildren().add(logo);
        content.getChildren().addAll(title, planInfo, spinner, status,
                tx1Lbl, tx1Link, tx2Lbl, tx2Link, spacer, closeBtn);

        root.getChildren().add(content);
        Scene watchOnlyScene = new Scene(root, 480, 420);
        applyDialogTheme(watchOnlyScene);
        popup.setScene(watchOnlyScene);

        // Save references for live updates
        watchOnlyPopup = popup;
        watchOnlyPopupTitle = title;
        watchOnlyPopupStatus = status;
        watchOnlyPopupSpinner = spinner;
        watchOnlyPopupContent = content;
        watchOnlyTx1Label = tx1Lbl;
        watchOnlyTx1Link = tx1Link;
        watchOnlyTx2Label = tx2Lbl;
        watchOnlyTx2Link = tx2Link;

        // When the user closes the popup, update the Privacy tab status line
        // with the current confirmation phase so they can still track progress.
        popup.setOnHidden(e -> {
            if (watchOnlyTx1Confirmed && watchOnlyTx2Confirmed) {
                // Both already confirmed — nothing to track
                return;
            }
            String statusMsg;
            if (watchOnlyTx2Confirmed) {
                statusMsg = "Payment confirmed — issuing tokens...";
            } else if (watchOnlyTx1Confirmed && watchOnlyTx2Pending) {
                statusMsg = "Staging confirmed — waiting for payment confirmation...";
            } else if (watchOnlyTx2Pending && !watchOnlyTx1Confirmed) {
                statusMsg = "Both transactions broadcast — waiting for block confirmation...";
            } else if (watchOnlyTx1Pending) {
                statusMsg = "Staging transaction broadcast — waiting for confirmation...";
            } else {
                statusMsg = "Watch-only payment in progress...";
            }
            final String msg = statusMsg;
            Platform.runLater(() -> scanStatus.setText(msg));
        });

        PrivacyLog.get().info("POPUP [Watch-Only Payment]: plan=" + planLabel + ", amount=" + amount);
        popup.show();
    }

    /**
     * Updates the watch-only popup with tx1 info (funds arrived at staging).
     * Must be called on FX thread.
     */
    private void updateWatchOnlyPopupTx1(String tx1Id) {
        if (watchOnlyPopupStatus != null) {
            watchOnlyPopupStatus.setText("Funds received — auto-forwarding to BTC Medusa...");
        }
        if (watchOnlyTx1Label != null) {
            watchOnlyTx1Label.setVisible(true);
            watchOnlyTx1Label.setManaged(true);
        }
        if (watchOnlyTx1Link != null) {
            watchOnlyTx1Link.setText(tx1Id);
            watchOnlyTx1Link.setVisible(true);
            watchOnlyTx1Link.setManaged(true);
            watchOnlyTx1Link.setOnAction(e -> {
                String network = PerseverusPaymentManager.isTestnet() ? "/testnet" : "";
                String url = "https://mempool.space" + network + "/tx/" + tx1Id;
                AppServices.get().getApplication().getHostServices().showDocument(url);
            });
        }
    }

    /**
     * Updates the watch-only popup with tx2 info (forwarded to SP address).
     * Must be called on FX thread.
     */
    private void updateWatchOnlyPopupTx2(String tx2Id) {
        if (watchOnlyPopupStatus != null) {
            watchOnlyPopupStatus.setText("Payment forwarded — waiting for block confirmation...");
        }
        if (watchOnlyPopupTitle != null) {
            watchOnlyPopupTitle.setText("Payment Forwarded");
        }
        if (watchOnlyTx2Label != null) {
            watchOnlyTx2Label.setVisible(true);
            watchOnlyTx2Label.setManaged(true);
        }
        if (watchOnlyTx2Link != null) {
            watchOnlyTx2Link.setText(tx2Id);
            watchOnlyTx2Link.setVisible(true);
            watchOnlyTx2Link.setManaged(true);
            watchOnlyTx2Link.setOnAction(e -> {
                String network = PerseverusPaymentManager.isTestnet() ? "/testnet" : "";
                String url = "https://mempool.space" + network + "/tx/" + tx2Id;
                AppServices.get().getApplication().getHostServices().showDocument(url);
            });
        }
    }

    /**
     * Transforms the watch-only popup into a success screen after token issuance.
     * Must be called on FX thread.
     */
    private void showWatchOnlyPopupSuccess(int tokenCount) {
        if (watchOnlyPopup == null || !watchOnlyPopup.isShowing() || watchOnlyPopupContent == null) {
            return;
        }

        if (watchOnlyPopupSpinner != null) {
            watchOnlyPopupSpinner.setVisible(false);
            watchOnlyPopupSpinner.setManaged(false);
        }
        if (watchOnlyPopupTitle != null) {
            watchOnlyPopupTitle.setText("Subscription Active");
        }
        if (watchOnlyPopupStatus != null) {
            watchOnlyPopupStatus.setText("Payment confirmed — " + tokenCount + " tokens issued!");
        }
    }

    // ── Data classes ──

    /**
     * A single row in the results table. Mutable so the checkbox state and
     * post-scan fields can update in place.
     */
    public static class UtxoRow {
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        private final String txid;
        private final int vout;
        private final long valueSats;
        private final int blockHeight;
        // Transaction footer metadata (from the wallet's own copy of the tx),
        // used to populate the privacy report footer. 0 if the tx isn't known.
        private int numInputs;
        private int numOutputs;
        private long feeSats;
        private String kycTag = "—";
        private String tagType = "Unknown";
        private String styleClass = "";
        private String status = "Not scanned";
        private PrivacyReport report;
        // Transient per-row scan animation: -1 = no bar (show status text),
        // 0..1 = ZK-proof progress bar fraction. Demo mode only.
        private final javafx.beans.property.SimpleDoubleProperty proofProgress =
                new javafx.beans.property.SimpleDoubleProperty(-1);

        public UtxoRow(String txid, int vout, long valueSats, int blockHeight) {
            this.txid = txid;
            this.vout = vout;
            this.valueSats = valueSats;
            this.blockHeight = blockHeight;
            if (blockHeight <= 0) {
                this.status = "Unconfirmed";
            }
        }

        public SimpleBooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }

        public String getTxid() { return txid; }
        public int getVout() { return vout; }
        public long getValueSats() { return valueSats; }
        public int getBlockHeight() { return blockHeight; }
        public int getNumInputs() { return numInputs; }
        public int getNumOutputs() { return numOutputs; }
        public long getFeeSats() { return feeSats; }
        public void setTxMeta(int numInputs, int numOutputs, long feeSats) {
            this.numInputs = numInputs;
            this.numOutputs = numOutputs;
            this.feeSats = feeSats;
        }
        public String getKycTag() { return kycTag; }
        public String getTagType() { return tagType; }
        public String getStyleClass() { return styleClass; }
        public String getStatus() { return status; }
        public PrivacyReport getReport() { return report; }
        public void setReport(PrivacyReport report) { this.report = report; }
        /** Letter grade for the Grade column, or em-dash if not yet scanned. */
        public String getGrade() { return report != null ? report.getGrade() : "—"; }
        public void setStatus(String status) { this.status = status; }
        public javafx.beans.property.SimpleDoubleProperty proofProgressProperty() { return proofProgress; }
        public double getProofProgress() { return proofProgress.get(); }
        public void setProofProgress(double v) { proofProgress.set(v); }

        public String txidShort() {
            return txid.length() > 16 ? txid.substring(0, 16) + "..." : txid;
        }

        /** True if this UTXO has already been scanned successfully. */
        public boolean isScanned() {
            return status != null && status.startsWith("OK");
        }

        void resetResult() {
            this.kycTag = "—";
            this.tagType = "Unknown";
            this.styleClass = "";
            this.status = "Scanning...";
            this.report = null;
            this.proofProgress.set(-1);
        }

        void clearToDefault() {
            this.selected.set(false);
            this.kycTag = "—";
            this.tagType = "Unknown";
            this.styleClass = "";
            this.status = "Not scanned";
            this.report = null;
            this.proofProgress.set(-1);
        }

        void applyResult(PrivacyQuery.Result qr) {
            this.kycTag = qr.kycTag();
            this.tagType = qr.tagType();
            this.styleClass = qr.styleClass();
            this.status = qr.status();
        }
    }

    /**
     * A row in the issued packs history table. Tracks which tokens
     * have been spent so the "Remaining" column stays current.
     */
    public static class PackRow {
        final IssuedPack pack;
        final int packSize;
        final String issuedAt;
        private final boolean[] spent;

        PackRow(IssuedPack pack) {
            this.pack = pack;
            this.packSize = pack.packSize();
            this.issuedAt = java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            this.spent = new boolean[packSize];
        }

        /** Constructor for restoring a persisted pack with a saved timestamp. */
        PackRow(IssuedPack pack, String issuedAt) {
            this.pack = pack;
            this.packSize = pack.packSize();
            this.issuedAt = issuedAt != null ? issuedAt : "restored";
            this.spent = new boolean[packSize];
        }

        /** Defensive copy of the spent array for persistence. */
        boolean[] getSpentArray() {
            return spent.clone();
        }

        /** Number of tokens not yet spent. */
        int remaining() {
            int count = 0;
            for (boolean s : spent) {
                if (!s) count++;
            }
            return count;
        }

        /** Mark a token index as spent. */
        void markSpent(int idx) {
            if (idx >= 0 && idx < spent.length) {
                spent[idx] = true;
            }
        }

        /** First 24 hex chars of the blob + ellipsis. */
        /** Read a little-endian u32 from the pack blob at {@code off}, or 0 if out of range. */
        private int u32le(int off) {
            byte[] b = pack.bytes();
            if (b == null || off + 4 > b.length) return 0;
            return (b[off] & 0xFF)
                    | ((b[off + 1] & 0xFF) << 8)
                    | ((b[off + 2] & 0xFF) << 16)
                    | ((b[off + 3] & 0xFF) << 24);
        }

        /** Month (YYYYMM) this pack activates. Blob offset 21, u32 LE. */
        int startMonth() { return u32le(21); }

        /** Month (YYYYMM) this pack expires (inclusive). Blob offset 17, u32 LE. */
        int expirationMonth() { return u32le(17); }

        /** Human-readable expiration, e.g. "Jul 2026". */
        String expiresLabel() {
            return formatMonth(expirationMonth());
        }

        /**
         * Status relative to the given current server month (YYYYMM):
         * "Not yet active", "Active", or "Expired". If months are missing
         * (0) or the current month is unknown, returns "—".
         */
        String statusLabel(int currentMonth) {
            int start = startMonth();
            int exp = expirationMonth();
            if (currentMonth <= 0 || start <= 0 || exp <= 0) return "—";
            if (currentMonth < start) return "Not yet active";
            if (currentMonth > exp) return "Expired";
            return "Active";
        }
    }
}
