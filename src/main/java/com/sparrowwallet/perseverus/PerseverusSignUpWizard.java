package com.sparrowwallet.perseverus;

import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.PrivacyController;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import com.sparrowwallet.perseverus.PrivacyLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javafx.scene.input.MouseEvent;

/**
 * Sign-up wizard for BTC Medusa subscription payment.
 *
 * <p>Step 1: Choose payment method (Credit Card / Bitcoin)</p>
 *
 * <p><b>Bitcoin path:</b></p>
 * <p>Step 2: Choose plan (Monthly / Annual) — priced in sats</p>
 * <p>Step 3: Review transaction details</p>
 * <p>Step 4: Confirm, decrypt wallet, build + sign + broadcast</p>
 *
 * <p><b>Credit Card path:</b></p>
 * <p>Step 2: Choose plan (One-Time / Monthly) — priced in USD</p>
 * <p>Step 3: Open Stripe Checkout in browser, auto-poll for confirmation</p>
 * <p>Step 4: Redeem payment code → VOPRF token issuance</p>
 */
public class PerseverusSignUpWizard extends Dialog<PerseverusSignUpWizard.Result> {
    private static final Logger log = LoggerFactory.getLogger(PerseverusSignUpWizard.class);

    public enum Result {
        PAYMENT_SENT,
        STRIPE_PAYMENT,
        MANUAL_PAYMENT,
        AUTOMATIC_WATCH_ONLY,
        BTC_PAYMENT_IN_PROGRESS,
        TRIAL_TOKEN,
        CANCELLED
    }

    /** Payment method selection. */
    private enum PaymentMethod { CREDIT_CARD, BITCOIN, LIGHTNING, CASHU }

    /** Stripe plan selection (maps to server-side price IDs). */
    private enum StripePlan {
        ONETIME("One-Time Token Pack", "$9.99", "onetime"),
        MONTHLY("Monthly Subscription", "$7.99/month", "monthly"),
        YEARLY("Yearly Subscription", "$96/year", "yearly");

        private final String label;
        private final String priceLabel;
        private final String apiValue;

        StripePlan(String label, String priceLabel, String apiValue) {
            this.label = label;
            this.priceLabel = priceLabel;
            this.apiValue = apiValue;
        }
    }

    /** Clearnet API endpoint — used for Stripe flows where Tor adds
     *  latency with zero privacy benefit (the user opens a clearnet
     *  Stripe page in their browser immediately after). */
    private static final String CLEARNET_SERVER_URL = "http://178.105.65.132:3030";

    /** SP scanner API — production default is the scanner's Tor hidden service,
     *  reached over the wallet's native Tor transport. Override via Config
     *  (Settings → Scanner URL), e.g. point at http://127.0.0.1:8080 for local
     *  dev against a scanner on the same machine. */
    private static final String SP_SCANNER_URL =
            "http://lhcmv4eqbzms2iilvntgmzugmc2y4hnhqnu34jv735iyepyh4xsxhxad.onion";

    /** Resolve the SP scanner base URL: the configured (onion) URL if set,
     *  otherwise the localhost dev default. Trailing slashes are trimmed.
     *  Public so the issuance path resolves the scanner the same way the
     *  payment-registration path does (never dead-ends on a blank config). */
    public static String scannerBaseUrl() {
        String configured = Config.get().getPerseverusScannerUrl();
        String url = (configured != null && !configured.isBlank()) ? configured : SP_SCANNER_URL;
        return url.replaceAll("/+$", "");
    }

    // ── Wizard state ──
    private int currentStep = 1;
    private PaymentMethod selectedMethod;
    private PerseverusPaymentManager.Plan selectedPlan;  // BTC path
    private StripePlan selectedStripePlan;                 // CC path
    private double feeRate;
    private volatile boolean stripePollingActive;
    private volatile boolean stripeRedeemed;
    private volatile boolean lnPollingActive;

    // ── References for payment ──
    private final Wallet wallet;
    private final Storage storage;

    // ── UI elements ──
    private final VBox contentArea;
    private final Button backButton;
    private final Button nextButton;
    private final Label stepIndicator;
    private Label priceFetchStatus;
    private Sha256Hash broadcastTxid;
    private volatile boolean btcTokensIssued;
    private volatile String btcPaymentStatus;  // for status line when popup closed early

    // ── Plan selection radio buttons ──
    private ToggleGroup planToggle;

    public PerseverusSignUpWizard(Wallet wallet, Storage storage) {
        this.wallet = wallet;
        this.storage = storage;
        Double nextBlockRate = AppServices.getNextBlockMedianFeeRate();
        this.feeRate = nextBlockRate != null ? nextBlockRate : AppServices.getFallbackFeeRate();

        final DialogPane dialogPane = getDialogPane();
        setTitle("BTC Medusa — Sign Up");
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());
        dialogPane.setPrefWidth(500);
        dialogPane.setPrefHeight(440);
        AppServices.moveToActiveWindowScreen(this);

        // ── Content area ──
        contentArea = new VBox(15);
        contentArea.setAlignment(Pos.TOP_CENTER);
        contentArea.setPadding(new Insets(10, 25, 10, 25));
        dialogPane.setContent(contentArea);

        // ── Buttons ──
        ButtonType backType = new ButtonType("Back", ButtonBar.ButtonData.LEFT);
        ButtonType nextType = new ButtonType("Next", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(backType, nextType, ButtonType.CANCEL);

        backButton = (Button) dialogPane.lookupButton(backType);
        nextButton = (Button) dialogPane.lookupButton(nextType);
        backButton.setPrefWidth(80);
        nextButton.setPrefWidth(100);
        nextButton.setDefaultButton(true);

        // Step indicator label
        stepIndicator = new Label("Step 1 of 4");
        stepIndicator.setStyle("-fx-opacity: 0.6; -fx-font-size: 11px;");

        // Intercept button clicks — we handle navigation ourselves
        backButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            goBack();
        });
        nextButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            goNext();
        });

        // Result converter
        setResultConverter(buttonType -> {
            if (trialRedeemed) {
                return Result.TRIAL_TOKEN;
            }
            if (stripeRedeemed) {
                return Result.STRIPE_PAYMENT;
            }
            if (broadcastTxid != null && btcTokensIssued) {
                return Result.PAYMENT_SENT;
            }
            if (broadcastTxid != null) {
                return Result.BTC_PAYMENT_IN_PROGRESS;
            }
            return Result.CANCELLED;
        });

        // On close, stop any active polling
        setOnCloseRequest(e -> stripePollingActive = false);

        // Show step 1 — payment method choice
        showStepPaymentMethod();
    }

    // ═════════════════════════════════════════════════════════════════════
    // STEP 1 — Choose Payment Method
    // ═════════════════════════════════════════════════════════════════════

    private void showStepPaymentMethod() {
        currentStep = 1;
        backButton.setVisible(false);
        backButton.setManaged(false);
        nextButton.setText("Next");
        nextButton.setDisable(false);
        stepIndicator.setText("Step 1");

        contentArea.getChildren().clear();

        // Logo
        ImageView logo = createLogo(72);

        // Title
        Label title = new Label("Choose Payment Method");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        // Description
        Label desc = new Label("100 privacy scans per token pack with ZK-authenticated queries.");
        desc.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        desc.setWrapText(true);
        desc.setTextAlignment(TextAlignment.CENTER);
        desc.setAlignment(Pos.CENTER);
        desc.setMaxWidth(400);

        Region spacer = new Region();
        spacer.setPrefHeight(15);

        // Payment method cards
        ToggleGroup methodToggle = new ToggleGroup();

        StackPane ccCard = createMethodCard(
            PaymentMethod.CREDIT_CARD,
            "Credit Card",
            "Pay with Visa, Mastercard, etc.",
            "",
            methodToggle
        );

        StackPane btcCard = createMethodCard(
            PaymentMethod.BITCOIN,
            "Bitcoin",
            "Pay directly from this wallet",
            "",
            methodToggle
        );

        StackPane lnCard = createMethodCard(
            PaymentMethod.LIGHTNING,
            "Lightning",
            "Pay via Lightning Network",
            "",
            methodToggle
        );

        StackPane cashuCard = createMethodCard(
            PaymentMethod.CASHU,
            "Cashu",
            "Pay with Cashu ecash",
            "",
            methodToggle
        );

        // No default selection — Next is disabled until user picks one
        nextButton.setDisable(true);
        selectedMethod = null;

        methodToggle.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedMethod = (PaymentMethod) ((RadioButton) newVal).getUserData();
                // Only enable Next for implemented payment methods.
                // Cashu and Credit Card are "Coming Soon" (disabled below).
                nextButton.setDisable(selectedMethod == PaymentMethod.CASHU
                        || selectedMethod == PaymentMethod.CREDIT_CARD);
            }
        });

        HBox topRow = new HBox(15, ccCard, btcCard);
        topRow.setAlignment(Pos.CENTER);
        HBox bottomRow = new HBox(15, lnCard, cashuCard);
        bottomRow.setAlignment(Pos.CENTER);
        VBox methodGrid = new VBox(15, topRow, bottomRow);
        methodGrid.setAlignment(Pos.CENTER);

        contentArea.getChildren().addAll(logo, title, desc, spacer, methodGrid);
    }

    private StackPane createMethodCard(PaymentMethod method, String name,
                                   String subtitle, String hint, ToggleGroup group) {
        // Content layer — centered radio, subtitle, hint (same as original layout)
        VBox content = new VBox(8);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20, 20, 20, 20));

        RadioButton radio = new RadioButton(name);
        radio.setToggleGroup(group);
        radio.setUserData(method);
        radio.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label sub = new Label(subtitle);
        sub.setStyle("-fx-font-size: 12px;");
        sub.setWrapText(true);
        sub.setTextAlignment(TextAlignment.CENTER);
        sub.setAlignment(Pos.CENTER);
        sub.setMaxWidth(Double.MAX_VALUE);

        Label hintLabel = new Label(hint);
        hintLabel.setStyle("-fx-font-size: 10px; -fx-opacity: 0.5;");
        hintLabel.setWrapText(true);
        hintLabel.setTextAlignment(TextAlignment.CENTER);
        hintLabel.setAlignment(Pos.CENTER);
        hintLabel.setMaxWidth(Double.MAX_VALUE);
        if (hint == null || hint.isEmpty()) {
            hintLabel.setVisible(false);
            hintLabel.setManaged(false);
        }

        content.getChildren().addAll(radio, sub, hintLabel);

        // Icon overlay — pinned to top-right corner
        javafx.scene.Node icon = createPaymentIcon(method);
        icon.setMouseTransparent(true);

        // Card = StackPane so icon floats over content
        StackPane card = new StackPane(content, icon);
        StackPane.setAlignment(content, Pos.CENTER);
        StackPane.setAlignment(icon, Pos.TOP_RIGHT);
        StackPane.setMargin(icon, new Insets(6, 6, 0, 0));
        card.setPrefWidth(200);
        card.setPrefHeight(120);
        card.setMinHeight(120);
        card.setMaxHeight(120);
        card.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-border-width: 1;");

        card.setOnMouseClicked(e -> radio.setSelected(true));

        radio.selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                card.setStyle("-fx-border-color: #5b9bd5; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; -fx-border-width: 2; "
                        + "-fx-background-color: rgba(91,155,213,0.08);");
            } else {
                card.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; -fx-border-width: 1;");
            }
        });

        // Cashu and Credit Card are not yet available — grey them out, show a
        // bold "Coming Soon" badge, and make them unselectable.
        if (method == PaymentMethod.CASHU || method == PaymentMethod.CREDIT_CARD) {
            hintLabel.setText("Coming Soon");
            hintLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-opacity: 0.9;");
            hintLabel.setVisible(true);
            hintLabel.setManaged(true);
            radio.setDisable(true);
            card.setOpacity(0.5);
            card.setOnMouseClicked(null);   // not clickable
        }

        return card;
    }

    /**
     * Creates a small icon for each payment method, drawn with JavaFX shapes.
     */
    private javafx.scene.Node createPaymentIcon(PaymentMethod method) {
        double size = 24;
        switch (method) {
            case CREDIT_CARD -> {
                // Credit card rectangle with stripe
                javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(size, size * 0.7);
                var gc = canvas.getGraphicsContext2D();
                gc.setFill(javafx.scene.paint.Color.web("#6c757d"));
                gc.fillRoundRect(0, 0, size, size * 0.7, 4, 4);
                gc.setFill(javafx.scene.paint.Color.web("#ffc107"));
                gc.fillRect(0, size * 0.2, size, size * 0.15);
                gc.setFill(javafx.scene.paint.Color.WHITE);
                gc.fillRoundRect(3, size * 0.45, 8, 3, 1, 1);
                return canvas;
            }
            case BITCOIN -> {
                // Orange circle with ₿
                javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(size, size);
                var gc = canvas.getGraphicsContext2D();
                gc.setFill(javafx.scene.paint.Color.web("#f7931a"));
                gc.fillOval(0, 0, size, size);
                gc.setFill(javafx.scene.paint.Color.WHITE);
                gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 14));
                gc.fillText("₿", size / 2 - 4.5, size / 2 + 5);
                return canvas;
            }
            case LIGHTNING -> {
                // Lightning bolt from image resource
                ImageView bolt = new ImageView();
                bolt.setFitHeight(size);
                bolt.setFitWidth(size);
                bolt.setPreserveRatio(true);
                bolt.setSmooth(true);
                try {
                    URL boltUrl = getClass().getResource("/com/sparrowwallet/sparrow/image/lightning-bolt.png");
                    if (boltUrl != null) {
                        bolt.setImage(new Image(boltUrl.toExternalForm()));
                    }
                } catch (Exception e) {
                    log.debug("Lightning bolt icon not available");
                }
                return bolt;
            }
            case CASHU -> {
                // Green circle with a nut/dollar symbol for Cashu
                javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(size, size);
                var gc = canvas.getGraphicsContext2D();
                gc.setFill(javafx.scene.paint.Color.web("#2ecc71"));
                gc.fillOval(0, 0, size, size);
                gc.setFill(javafx.scene.paint.Color.WHITE);
                gc.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 14));
                gc.fillText("C", size / 2 - 4.5, size / 2 + 5);
                return canvas;
            }
            default -> {
                return new Region();
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // BTC PATH — Step 2: Choose BTC Plan
    // ═════════════════════════════════════════════════════════════════════

    /** True for the payment methods that render the sats-priced plan screen
     *  ({@link #showStepBtcPlan()}): on-chain Bitcoin and Lightning. Credit card
     *  uses the USD Stripe plan UI instead. */
    private boolean usesSatsPlanScreen() {
        return selectedMethod == PaymentMethod.BITCOIN
                || selectedMethod == PaymentMethod.LIGHTNING;
    }

    private void showStepBtcPlan() {
        currentStep = 2;
        backButton.setVisible(true);
        backButton.setManaged(true);
        nextButton.setText("Next");
        stepIndicator.setText("Step 2 of 4");

        contentArea.getChildren().clear();

        // Logo
        ImageView logo = createLogo(60);

        // Title
        Label title = new Label("Choose Your Plan");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label desc = new Label("");
        desc.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        desc.setWrapText(true);
        desc.setManaged(false);
        desc.setVisible(false);
        desc.setTextAlignment(TextAlignment.CENTER);

        Region spacer = new Region();
        spacer.setPrefHeight(10);

        planToggle = new ToggleGroup();

        boolean priceReady = PerseverusPaymentManager.getLastBtcPrice() > 0;

        String monthlySatsStr = priceReady
                ? String.format("%,d sats", PerseverusPaymentManager.Plan.MONTHLY.getAmountSats())
                : "Loading...";
        String annualSatsStr = priceReady
                ? String.format("%,d sats", PerseverusPaymentManager.Plan.ANNUAL.getAmountSats())
                : "Loading...";
        String monthlyFiat = priceReady
                ? String.format("$%.0f one-time (BTC $%,.0f)",
                    PerseverusPaymentManager.MONTHLY_USD, PerseverusPaymentManager.getLastBtcPrice())
                : String.format("~$%.0f one-time", PerseverusPaymentManager.MONTHLY_USD);
        String annualFiat = priceReady
                ? String.format("$%.0f/year — save 17%% (BTC $%,.0f)",
                    PerseverusPaymentManager.ANNUAL_USD, PerseverusPaymentManager.getLastBtcPrice())
                : String.format("~$%.0f/year — save 17%%", PerseverusPaymentManager.ANNUAL_USD);

        VBox monthlyCard = createPlanCard(
            PerseverusPaymentManager.Plan.MONTHLY, "One Time",
            monthlySatsStr, monthlyFiat, planToggle
        );
        VBox annualCard = createPlanCard(
            PerseverusPaymentManager.Plan.ANNUAL, "Annual",
            annualSatsStr, annualFiat, planToggle
        );

        nextButton.setDisable(!priceReady);

        planToggle.getToggles().get(0).setSelected(true);
        selectedPlan = PerseverusPaymentManager.Plan.MONTHLY;

        planToggle.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedPlan = (PerseverusPaymentManager.Plan) ((RadioButton) newVal).getUserData();
            }
        });

        HBox planCards = new HBox(15, monthlyCard, annualCard);
        planCards.setAlignment(Pos.CENTER);

        // Price fetch status
        priceFetchStatus = new Label();
        priceFetchStatus.setWrapText(true);
        priceFetchStatus.setTextAlignment(TextAlignment.CENTER);
        priceFetchStatus.setMaxWidth(400);
        priceFetchStatus.setVisible(false);
        priceFetchStatus.setManaged(false);

        // Hidden override hotspot
        Region overrideHotspot = new Region();
        overrideHotspot.setPrefSize(50, 50);
        overrideHotspot.setMinSize(50, 50);
        overrideHotspot.setMaxSize(50, 50);
        overrideHotspot.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
        overrideHotspot.setOnMouseClicked((MouseEvent e) -> showPriceOverrideDialog());

        Region growSpacer = new Region();
        VBox.setVgrow(growSpacer, Priority.ALWAYS);

        HBox bottomRow = new HBox(overrideHotspot);
        bottomRow.setAlignment(Pos.BOTTOM_LEFT);

        contentArea.getChildren().addAll(logo, title, desc, spacer, planCards,
                priceFetchStatus, growSpacer, bottomRow);

        // Start BTC price fetch if not already loaded
        if (!priceReady) {
            startBtcPriceFetch();
        }
    }

    private void startBtcPriceFetch() {
        Thread priceFetch = new Thread(() -> {
            double price = PerseverusPaymentManager.fetchBtcPrice();
            if (price > 0) {
                Platform.runLater(() -> {
                    // Bitcoin AND Lightning share the sats-priced plan screen, so
                    // refresh it for either once the price arrives — otherwise the
                    // Lightning flow stays stuck on "Loading..." until the user
                    // navigates back and forward.
                    if (currentStep == 2 && usesSatsPlanScreen()) {
                        showStepBtcPlan();
                    }
                });
                return;
            }

            log.info("[perseverus] CoinGecko unavailable, falling back to server price via Tor");
            Platform.runLater(() -> {
                if (currentStep == 2 && priceFetchStatus != null) {
                    priceFetchStatus.setText(
                            "Public price API unavailable. Fetching privately via Tor...");
                    priceFetchStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #e67e22;");
                    priceFetchStatus.setVisible(true);
                    priceFetchStatus.setManaged(true);
                }
            });

            price = PerseverusPaymentManager.fetchFallbackPrice();
            if (price > 0) {
                Platform.runLater(() -> {
                    // Bitcoin AND Lightning share the sats-priced plan screen, so
                    // refresh it for either once the price arrives — otherwise the
                    // Lightning flow stays stuck on "Loading..." until the user
                    // navigates back and forward.
                    if (currentStep == 2 && usesSatsPlanScreen()) {
                        showStepBtcPlan();
                    }
                });
                return;
            }

            log.warn("[perseverus] All price sources failed");
            Platform.runLater(() -> {
                if (currentStep == 2 && priceFetchStatus != null) {
                    priceFetchStatus.setText(
                            "Unable to fetch BTC price. Check your internet connection.");
                    priceFetchStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #e74c3c;");
                    priceFetchStatus.setVisible(true);
                    priceFetchStatus.setManaged(true);
                }
            });
        }, "btc-price-fetch");
        priceFetch.setDaemon(true);
        priceFetch.start();
    }

    private VBox createPlanCard(PerseverusPaymentManager.Plan plan, String name,
                                String satsPrice, String fiatHint, ToggleGroup group) {
        VBox card = new VBox(8);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(15, 20, 15, 20));
        card.setPrefWidth(190);
        card.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-border-width: 1;");

        RadioButton radio = new RadioButton(name);
        radio.setToggleGroup(group);
        radio.setUserData(plan);
        radio.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label price = new Label(satsPrice);
        price.setStyle("-fx-font-size: 15px;");

        Label hint = new Label(fiatHint);
        hint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");

        card.getChildren().addAll(radio, price, hint);
        card.setOnMouseClicked(e -> radio.setSelected(true));

        radio.selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                card.setStyle("-fx-border-color: #5b9bd5; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; -fx-border-width: 2; "
                        + "-fx-background-color: rgba(91,155,213,0.08);");
            } else {
                card.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; -fx-border-width: 1;");
            }
        });

        return card;
    }

    // ═════════════════════════════════════════════════════════════════════
    // BTC PATH — Step 3: Review Transaction
    // ═════════════════════════════════════════════════════════════════════

    private void showStepBtcReview() {
        currentStep = 3;
        backButton.setVisible(true);
        backButton.setManaged(true);
        nextButton.setText("Confirm & Pay");
        stepIndicator.setText("Step 3 of 4");

        contentArea.getChildren().clear();

        Double currentRate = AppServices.getNextBlockMedianFeeRate();
        if (currentRate != null) {
            feeRate = currentRate;
        }

        long amount = selectedPlan.getAmountSats();
        long estimatedFee = (long) Math.ceil(111 * feeRate);

        PerseverusPaymentManager feeCalc = new PerseverusPaymentManager(
                wallet.isMasterWallet() ? wallet : wallet.getMasterWallet(), storage);
        boolean watchOnly = !feeCalc.isHotWallet();
        long forwardingFee = watchOnly ? feeCalc.calculateForwardingFee(feeRate) : 0;
        long total = amount + estimatedFee + forwardingFee;

        Label title = new Label("Review Payment");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Region spacer1 = new Region();
        spacer1.setPrefHeight(5);

        VBox details = new VBox(12);
        details.setPadding(new Insets(15, 20, 15, 20));
        details.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-border-width: 1;");

        details.getChildren().addAll(
            detailRow("Plan:", selectedPlan.getLabel()),
            detailRow("Duration:", selectedPlan.getMonths() + " month"
                    + (selectedPlan.getMonths() > 1 ? "s" : "")),
            new Separator(),
            detailRow("Subscription:", String.format("%,d sats", amount)),
            detailRow("Est. network fee:", String.format("~%,d sats (%.1f sat/vB)", estimatedFee, feeRate))
        );

        if (watchOnly) {
            details.getChildren().add(
                detailRow("Forwarding fee:", String.format("~%,d sats", forwardingFee))
            );
        }

        details.getChildren().addAll(
            new Separator(),
            detailRow("Est. total:", String.format("~%,d sats", total))
        );

        HBox totalRow = (HBox) details.getChildren().get(details.getChildren().size() - 1);
        for (var node : totalRow.getChildren()) {
            if (node instanceof Label l) {
                l.setStyle(l.getStyle() + " -fx-font-weight: bold;");
            }
        }

        Region spacer2 = new Region();
        spacer2.setPrefHeight(5);

        PerseverusPaymentManager mgr = new PerseverusPaymentManager(
                wallet.isMasterWallet() ? wallet : wallet.getMasterWallet(), storage);

        TextFlow paymentNote = new TextFlow();
        paymentNote.setTextAlignment(TextAlignment.CENTER);
        paymentNote.setLineSpacing(3);
        String noteStr;
        if (mgr.isHotWallet()) {
            noteStr = "Payment is sent via silent payment (BIP-352) to the "
                    + "BTC Medusa address. Your wallet will build, sign, and "
                    + "broadcast the transaction automatically.";
        } else {
            noteStr = "Watch-only wallet detected. You will be returned to "
                    + "the Privacy tab to select UTXOs, then sign on your "
                    + "hardware device via the Send screen.";
        }
        Text noteText = new Text(noteStr);
        noteText.setStyle("-fx-font-size: 11.5px; -fx-opacity: 0.7;");
        noteText.setFill(noteFill());
        paymentNote.getChildren().add(noteText);

        nextButton.setDisable(false);
        contentArea.getChildren().addAll(title, spacer1, details, spacer2, paymentNote);

        if (PerseverusPaymentManager.isTestnet()) {
            Label testnetBadge = new Label("TESTNET — no real funds will be spent");
            testnetBadge.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; "
                    + "-fx-text-fill: #e67e22; -fx-padding: 4 10 4 10; "
                    + "-fx-border-color: #e67e22; -fx-border-radius: 4; "
                    + "-fx-background-radius: 4;");
            testnetBadge.setMaxWidth(Double.MAX_VALUE);
            testnetBadge.setAlignment(Pos.CENTER);
            contentArea.getChildren().add(testnetBadge);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // BTC PATH — Step 4: Confirm, Decrypt, Broadcast
    // ═════════════════════════════════════════════════════════════════════

    private void showStepBtcConfirm() {
        currentStep = 4;
        backButton.setVisible(false);
        backButton.setManaged(false);
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        stepIndicator.setText("Step 4 of 4");

        // Rename Cancel → Close for the broadcast/waiting screens
        Button cancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) {
            cancelBtn.setText("Close");
        }

        contentArea.getChildren().clear();

        ImageView logo = createLogo(60);

        Label title = new Label("Processing Payment...");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(48, 48);

        Label statusLabel = new Label("Preparing transaction...");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setMaxWidth(380);

        contentArea.getChildren().addAll(logo, title, spinner, statusLabel);

        executePayment(title, statusLabel, spinner);
    }

    private void executePayment(Label title, Label statusLabel, ProgressIndicator spinner) {
        PerseverusPaymentManager manager = new PerseverusPaymentManager(
                wallet.isMasterWallet() ? wallet : wallet.getMasterWallet(), storage);

        if (wallet.isEncrypted()) {
            WalletPasswordDialog dlg = new WalletPasswordDialog(
                    wallet.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
            dlg.initOwner(getDialogPane().getScene().getWindow());
            Optional<SecureString> password = dlg.showAndWait();

            if (password.isEmpty()) {
                showStepBtcReview();
                return;
            }

            statusLabel.setText("Decrypting wallet...");
            Storage.DecryptWalletService decryptService =
                    new Storage.DecryptWalletService(wallet.copy(), password.get());

            decryptService.setOnSucceeded(event -> {
                Wallet decrypted = decryptService.getValue();
                broadcastPayment(decrypted, manager, title, statusLabel, spinner);
            });

            decryptService.setOnFailed(event -> {
                log.error("Wallet decryption failed", decryptService.getException());
                showPaymentError("Incorrect password: " + decryptService.getException().getMessage());
            });

            decryptService.start();
        } else {
            broadcastPayment(wallet, manager, title, statusLabel, spinner);
        }
    }

    private void broadcastPayment(Wallet decryptedWallet, PerseverusPaymentManager manager,
                                  Label title, Label statusLabel, ProgressIndicator spinner) {
        Platform.runLater(() -> statusLabel.setText("Registering payment with scanner..."));

        Thread payThread = new Thread(() -> {
            try {
                // ── Step 0: Check if quoted price is stale ──
                PerseverusPaymentManager.StaleQuote stale =
                        PerseverusPaymentManager.checkPriceStaleness(selectedPlan);
                if (stale != null) {
                    // Price has moved — ask the user to accept the new quote
                    java.util.concurrent.CompletableFuture<Boolean> accepted =
                            new java.util.concurrent.CompletableFuture<>();
                    Platform.runLater(() -> {
                        Alert requote = new Alert(Alert.AlertType.CONFIRMATION);
                        requote.initOwner(getDialogPane().getScene().getWindow());
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
                        PrivacyLog.get().info("POPUP [Price Update Required]: original="
                                + stale.quotedSats + " sats, current=" + stale.currentSats
                                + " sats, " + dir + "=" + String.format("%.1f%%", Math.abs(stale.pctChange) * 100));
                        ButtonType proceed = new ButtonType("Pay " + String.format("%,d", stale.currentSats) + " sats");
                        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                        requote.getButtonTypes().setAll(proceed, cancel);
                        Optional<ButtonType> choice = requote.showAndWait();
                        accepted.complete(choice.isPresent() && choice.get() == proceed);
                    });
                    if (!accepted.get()) {
                        // User declined — go back to the review screen
                        Platform.runLater(this::showStepBtcReview);
                        if (wallet.isEncrypted()) {
                            decryptedWallet.clearPrivate();
                        }
                        return;
                    }
                    // User accepted the new price — selectedPlan amounts are already
                    // updated by the fetchBtcPrice() call inside checkPriceStaleness.
                    log.info("[perseverus] User accepted requote: {} sats", selectedPlan.getAmountSats());
                    PrivacyLog.get().info("Requote accepted: " + selectedPlan.getAmountSats() + " sats");
                }

                // ── Build, sign, broadcast the silent payment ──
                // We do NOT contact the server before the payment confirms: the
                // server's scanner detects payments autonomously, and the wallet
                // watches its own broadcast tx confirm locally. Token issuance
                // (Phase 2: signed with the payment key over a fresh circuit)
                // only happens AFTER confirmation.
                Platform.runLater(() -> statusLabel.setText("Building silent payment transaction..."));

                // Build and broadcast the payment ONLY. No proof-of-payment is
                // computed or persisted here: the subscription claim is signed
                // later, at issuance time, directly from the confirmed tx (see
                // PrivacyController.acquireSigningWalletAndProve). This keeps the
                // send step a pure payment and avoids storing a signature on disk.
                PerseverusPaymentManager.PreparedPayment prepared =
                        manager.buildTransaction(decryptedWallet, feeRate, selectedPlan);

                Sha256Hash txid = manager.signAndBroadcast(decryptedWallet, prepared);
                broadcastTxid = txid;
                btcPaymentStatus = "BTC payment broadcast — waiting for confirmation...";
                PrivacyLog.get().paymentBtcBroadcast(txid.toString(), selectedPlan.getAmountSats());

                // Persist pending payment so confirmation-watching resumes after a
                // restart. Save the current tip height so we can detect the block.
                long tipHeight = fetchTipHeight();
                Config.get().setPerseverusPendingPayment(
                        "hot-wallet-direct",
                        txid.toString(), selectedPlan.name(), tipHeight);

                // Watch for confirmation locally (the wallet sent this tx, so it
                // sees it confirm via wallet sync). PrivacyController issues tokens
                // once it detects the confirmed transaction.
                PrivacyController.notifyHotWalletPayment(txid.toString(), selectedPlan.name());

                if (wallet.isEncrypted()) {
                    decryptedWallet.clearPrivate();
                }

                Platform.runLater(() -> {
                    title.setText("Payment Broadcast");
                    statusLabel.setText("Payment broadcast — waiting for confirmation…");
                });
                showBroadcastPendingScreen(title, statusLabel, spinner, txid);
            } catch (Exception e) {
                log.error("[perseverus] Payment failed", e);
                if (wallet.isEncrypted()) {
                    decryptedWallet.clearPrivate();
                }
                Platform.runLater(() -> showPaymentError(e.getMessage()));
            }
        }, "perseverus-payment");
        payThread.setDaemon(true);
        payThread.start();
    }

    /**
     * Shows a "payment broadcast, waiting for confirmation" screen and closes
     * the wizard. Token issuance will be handled by the PrivacyController
     * when it detects the confirmed transaction via Sparrow's wallet sync.
     */
    private void showBroadcastPendingScreen(Label title, Label statusLabel,
                                            ProgressIndicator spinner, Sha256Hash txid) {
        Platform.runLater(() -> {
            title.setText("Payment Broadcast");
            spinner.setVisible(false);
            spinner.setManaged(false);

            String msg = "Your payment has been broadcast. Tokens will be issued\n"
                    + "automatically once the transaction receives 1 confirmation.\n\n"
                    + "You can close this window — the Privacy tab will update\n"
                    + "when confirmation is detected.";
            statusLabel.setText(msg);
            statusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.9;");

            // Add clickable txid link below the status message
            if (txid != null && statusLabel.getParent() instanceof VBox parentBox) {
                int idx = parentBox.getChildren().indexOf(statusLabel);

                Label txidLabel = new Label("Transaction ID:");
                txidLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");

                String txidStr = txid.toString();
                Hyperlink txidLink = new Hyperlink(txidStr);
                txidLink.setStyle("-fx-font-size: 10.5px; -fx-font-family: monospace;");
                txidLink.setWrapText(true);
                txidLink.setMaxWidth(420);
                txidLink.setOnAction(e -> {
                    String network = PerseverusPaymentManager.isTestnet() ? "/testnet" : "";
                    String url = "https://mempool.space" + network + "/tx/" + txidStr;
                    AppServices.get().getApplication().getHostServices().showDocument(url);
                });

                // Insert after statusLabel
                if (idx >= 0 && idx < parentBox.getChildren().size() - 1) {
                    parentBox.getChildren().add(idx + 1, txidLabel);
                    parentBox.getChildren().add(idx + 2, txidLink);
                } else {
                    parentBox.getChildren().addAll(txidLabel, txidLink);
                }
            }

            PrivacyLog.get().info("POPUP [Payment Broadcast]: " + msg.replace("\n", " "));
        });
    }

    /**
     * Issues VOPRF tokens after BTC payment is confirmed.
     * Uses the same Native.issuanceXxx JNI path as the Stripe flow.
     * Called from the payment/poller thread — updates UI via Platform.runLater.
     */
    private void issueTokensAfterBtcConfirmation(Label title, Label statusLabel,
                                                  ProgressIndicator spinner, Sha256Hash txid) {
        Platform.runLater(() -> statusLabel.setText("Payment confirmed — issuing privacy tokens..."));

        try {
            String serverUrl = CLEARNET_SERVER_URL;
            String pubkeyJson = clearnetGet(CLEARNET_SERVER_URL + "/server/pubkey");
            String pubkeyHex = parseJsonField(pubkeyJson, "pubkey_hex");

            int packSize = 100;
            int expirationMonth = currentExpirationMonth();

            long handle = Native.issuanceCreate(serverUrl, pubkeyHex);
            try {
                byte[] packBlob = Native.issuanceIssuePack(handle, packSize, expirationMonth);

                if (packBlob == null || packBlob.length == 0) {
                    throw new RuntimeException("Token issuance returned empty pack");
                }

                // Persist the token pack
                Config.PersistedPack persistedPack = new Config.PersistedPack(
                    packSize, packBlob, new boolean[packSize],
                    java.time.Instant.now().toString()
                );
                Config.get().addPerseverusPack(persistedPack);
                PrivacyLog.get().issueComplete(packSize, 0);
                btcTokensIssued = true;
                btcPaymentStatus = "Subscription active — " + packSize + " tokens issued";
                log.info("[perseverus] BTC payment tokens issued: {} tokens persisted", packSize);

                // Payment fully settled — clear pending state
                Config.get().clearPerseverusPendingPayment();

                Platform.runLater(() -> {
                    Config.get().setPerseverusWelcomed(true);
                    Config.get().setPerseverusTrialMode(false);
                    showPaymentSuccess(txid);
                });
            } finally {
                Native.issuanceDestroy(handle);
            }
        } catch (Exception e) {
            log.error("[perseverus] Token issuance after BTC payment failed", e);
            PrivacyLog.get().issueFailed(100, e.getMessage());
            // Payment is confirmed — clear pending state even though tokens failed
            Config.get().clearPerseverusPendingPayment();
            // Still mark as subscribed — the payment is confirmed, tokens can be issued later
            Platform.runLater(() -> {
                Config.get().setPerseverusWelcomed(true);
                Config.get().setPerseverusTrialMode(false);
                showPaymentSuccess(txid);
                // Show a warning that tokens need to be issued manually
                Alert warn = new Alert(Alert.AlertType.WARNING);
                warn.initOwner(getDialogPane().getScene().getWindow());
                warn.setTitle("Token Issuance");
                warn.setHeaderText("Payment confirmed, but token issuance failed.");
                warn.setContentText("Error: " + e.getMessage() + "\n\n"
                        + "Your payment is safe. Tokens will be issued when you next connect.");
                PrivacyLog.get().info("POPUP [Token Issuance Failed]: " + e.getMessage()
                        + " — payment confirmed, tokens deferred");
                warn.showAndWait();
            });
        }
    }

    private void showPaymentSuccess(Sha256Hash txid) {
        PrivacyLog.get().info("POPUP [Payment Sent]: txid=" + txid.toString()
                + ", plan=" + selectedPlan.getLabel()
                + ", amount=" + selectedPlan.getAmountSats() + " sats");
        contentArea.getChildren().clear();

        ImageView logo = createLogo(60);

        Label title = new Label("Payment Sent!");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #27ae60;");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        Label subtitle = new Label("Your BTC Medusa subscription is now active.");
        subtitle.setStyle("-fx-font-size: 13px;");
        subtitle.setWrapText(true);
        subtitle.setAlignment(Pos.CENTER);
        subtitle.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        spacer.setPrefHeight(5);

        Label planLabel = new Label(selectedPlan.getLabel() + " plan — "
                + String.format("%,d sats", selectedPlan.getAmountSats()));
        planLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        planLabel.setAlignment(Pos.CENTER);
        planLabel.setMaxWidth(Double.MAX_VALUE);

        Label txidLabel = new Label("Transaction ID:");
        txidLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");

        // Clickable txid — opens in mempool.space
        String txidStr = txid.toString();
        Hyperlink txidLink = new Hyperlink(txidStr);
        txidLink.setStyle("-fx-font-size: 10.5px; -fx-font-family: monospace;");
        txidLink.setWrapText(true);
        txidLink.setMaxWidth(420);
        txidLink.setOnAction(e -> {
            String network = PerseverusPaymentManager.isTestnet() ? "/testnet" : "";
            String url = "https://mempool.space" + network + "/tx/" + txidStr;
            AppServices.get().getApplication().getHostServices().showDocument(url);
        });

        // Hide all dialog buttons, use standalone Close button
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        backButton.setVisible(false);
        backButton.setManaged(false);
        Button cancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) {
            cancelBtn.setVisible(false);
            cancelBtn.setManaged(false);
        }

        Button closeBtn = new Button("Close");
        closeBtn.setPrefWidth(100);
        closeBtn.setStyle("-fx-font-size: 13px;");
        closeBtn.setOnAction(e -> {
            setResult(Result.PAYMENT_SENT);
            close();
        });

        Region spacer2 = new Region();
        spacer2.setPrefHeight(10);

        contentArea.getChildren().addAll(logo, title, subtitle, spacer,
                planLabel, txidLabel, txidLink, spacer2, closeBtn);
    }

    private void showPaymentError(String message) {
        PrivacyLog.get().info("POPUP [Payment Failed]: " + message);
        contentArea.getChildren().clear();

        Label title = new Label("Payment Failed");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #e17055;");

        Label errorMsg = new Label(message);
        errorMsg.setStyle("-fx-font-size: 12px;");
        errorMsg.setWrapText(true);
        errorMsg.setMaxWidth(400);

        Region spacer = new Region();
        spacer.setPrefHeight(10);

        backButton.setVisible(true);
        backButton.setManaged(true);
        nextButton.setVisible(true);
        nextButton.setManaged(true);
        nextButton.setText("Retry");
        nextButton.setDisable(false);

        contentArea.getChildren().addAll(title, spacer, errorMsg);
    }

    // ═════════════════════════════════════════════════════════════════════
    // BTC PATH — Payment Mode Choice (Automatic vs Manual)
    // ═════════════════════════════════════════════════════════════════════

    private void showPaymentModeChoice() {
        PerseverusPaymentManager manager = new PerseverusPaymentManager(
                wallet.isMasterWallet() ? wallet : wallet.getMasterWallet(), storage);

        boolean watchOnly = !manager.isHotWallet();

        ButtonType automaticType = new ButtonType("Automatic", ButtonBar.ButtonData.OK_DONE);
        ButtonType manualType = new ButtonType("Manual", ButtonBar.ButtonData.LEFT);

        Alert choice = new Alert(Alert.AlertType.NONE);
        choice.initOwner(getDialogPane().getScene().getWindow());
        choice.setTitle("Payment Mode");
        choice.setHeaderText("How would you like to pay?");
        if (watchOnly) {
            choice.setContentText(
                "Automatic — uses optimal coin selection to create the "
                + "staging transaction, then opens the Send screen for "
                + "hardware wallet signing.\n\n"
                + "Manual — returns to the Privacy tab so you can select "
                + "which UTXOs to spend, then opens the Send screen with "
                + "the payment address pre-filled."
            );
        } else {
            choice.setContentText(
                "Automatic — builds, signs, and broadcasts the transaction "
                + "in one step using optimal coin selection.\n\n"
                + "Manual — returns to the Privacy tab so you can select "
                + "which UTXOs to spend, then opens the Send screen with "
                + "the payment address pre-filled."
            );
        }
        choice.getButtonTypes().setAll(automaticType, manualType, ButtonType.CANCEL);
        PrivacyLog.get().info("POPUP [Payment Mode]: Automatic vs Manual choice shown"
                + (watchOnly ? " (watch-only)" : " (hot wallet)"));

        Button autoBtn = (Button) choice.getDialogPane().lookupButton(automaticType);
        if (autoBtn != null) {
            autoBtn.setDefaultButton(true);
        }

        Optional<ButtonType> result = choice.showAndWait();
        if (result.isPresent() && result.get() == automaticType) {
            if (watchOnly) {
                // Watch-only automatic: return to PrivacyController which will
                // create the staging tx and navigate to Send tab automatically
                setResult(Result.AUTOMATIC_WATCH_ONLY);
                close();
            } else {
                showStepBtcConfirm();
            }
        } else if (result.isPresent() && result.get() == manualType) {
            setResult(Result.MANUAL_PAYMENT);
            close();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CC PATH — Step 2: Choose Stripe Plan
    // ═════════════════════════════════════════════════════════════════════

    private void showStepStripePlan() {
        currentStep = 2;
        backButton.setVisible(true);
        backButton.setManaged(true);
        nextButton.setText("Next");
        nextButton.setDisable(false);
        stepIndicator.setText("Step 2 of 4");

        contentArea.getChildren().clear();

        ImageView logo = createLogo(60);

        Label title = new Label("Choose Your Plan");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label desc = new Label("Pay with credit card via Stripe. Your payment is "
                + "converted into privacy tokens — we never learn what you query.");
        desc.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        desc.setWrapText(true);
        desc.setTextAlignment(TextAlignment.CENTER);
        desc.setMaxWidth(400);

        Region spacer = new Region();
        spacer.setPrefHeight(10);

        ToggleGroup stripeToggle = new ToggleGroup();

        VBox onetimeCard = createStripeCard(
            StripePlan.ONETIME,
            StripePlan.ONETIME.priceLabel,
            "100 privacy tokens — no recurring charge",
            stripeToggle
        );
        VBox monthlyCard = createStripeCard(
            StripePlan.MONTHLY,
            StripePlan.MONTHLY.priceLabel,
            "100 tokens/month — cancel anytime",
            stripeToggle
        );
        VBox yearlyCard = createStripeCard(
            StripePlan.YEARLY,
            StripePlan.YEARLY.priceLabel,
            "13 monthly packs (100 each) — best value",
            stripeToggle
        );

        stripeToggle.getToggles().get(0).setSelected(true);
        selectedStripePlan = StripePlan.ONETIME;

        stripeToggle.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedStripePlan = (StripePlan) ((RadioButton) newVal).getUserData();
            }
        });

        HBox planCards = new HBox(15, onetimeCard, monthlyCard, yearlyCard);
        planCards.setAlignment(Pos.CENTER);

        Region spacer2 = new Region();
        spacer2.setPrefHeight(10);

        TextFlow privacyNote = new TextFlow();
        privacyNote.setTextAlignment(TextAlignment.CENTER);
        privacyNote.setLineSpacing(3);
        Text noteText = new Text(
            "Privacy guarantee: after payment, a one-time redemption code "
            + "is exchanged for blinded tokens. The code is deleted immediately — "
            + "your queries are cryptographically unlinkable to your payment.");
        noteText.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        noteText.setFill(noteFill());
        privacyNote.getChildren().add(noteText);

        contentArea.getChildren().addAll(logo, title, desc, spacer, planCards,
                spacer2, privacyNote);
    }

    private VBox createStripeCard(StripePlan plan, String priceStr,
                                   String description, ToggleGroup group) {
        VBox card = new VBox(8);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setPrefWidth(195);
        card.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-border-width: 1;");

        RadioButton radio = new RadioButton(plan.label);
        radio.setToggleGroup(group);
        radio.setUserData(plan);
        radio.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        Label price = new Label(priceStr);
        price.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label desc = new Label(description);
        desc.setStyle("-fx-font-size: 10.5px; -fx-opacity: 0.6;");
        desc.setWrapText(true);
        desc.setTextAlignment(TextAlignment.CENTER);
        desc.setMaxWidth(170);

        card.getChildren().addAll(radio, price, desc);
        card.setOnMouseClicked(e -> radio.setSelected(true));

        radio.selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                card.setStyle("-fx-border-color: #5b9bd5; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; -fx-border-width: 2; "
                        + "-fx-background-color: rgba(91,155,213,0.08);");
            } else {
                card.setStyle("-fx-border-color: #555; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; -fx-border-width: 1;");
            }
        });

        return card;
    }

    // ═════════════════════════════════════════════════════════════════════
    // CC PATH — Step 3: Open Stripe Checkout + Auto-Poll
    // ═════════════════════════════════════════════════════════════════════

    private void showStepStripeCheckout() {
        currentStep = 3;
        backButton.setVisible(true);
        backButton.setManaged(true);
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        stepIndicator.setText("Step 3 of 4");

        contentArea.getChildren().clear();

        ImageView logo = createLogo(60);

        Label title = new Label("Complete Payment");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(48, 48);

        Label statusLabel = new Label("Creating checkout session...");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        statusLabel.setWrapText(true);
        statusLabel.setTextAlignment(TextAlignment.CENTER);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setMaxWidth(380);

        contentArea.getChildren().addAll(logo, title, spinner, statusLabel);

        // Generate a nonce for this checkout session
        String nonce = UUID.randomUUID().toString();

        Thread checkoutThread = new Thread(() -> {
            try {
                // Use clearnet for Stripe flows — no privacy benefit from
                // Tor here since the user opens a clearnet Stripe page in
                // their browser immediately after.
                String serverUrl = CLEARNET_SERVER_URL;

                // Step A: Create Stripe Checkout Session via our server
                String checkoutUrl = serverUrl + "/stripe/checkout";
                String requestJson = String.format(
                    "{\"plan\":\"%s\",\"nonce\":\"%s\"}",
                    selectedStripePlan.apiValue, nonce
                );

                log.info("[perseverus] Creating Stripe checkout (clearnet): plan={}, nonce={}",
                        selectedStripePlan.apiValue, nonce);
                PrivacyLog.get().paymentStripeStart(selectedStripePlan.apiValue);

                String responseJson = clearnetPost(checkoutUrl, requestJson);
                log.info("[perseverus] Checkout response: {}", responseJson);

                // Parse checkout URL from response
                // Response: {"checkout_url":"https://checkout.stripe.com/...","nonce":"..."}
                String stripeUrl = parseJsonField(responseJson, "checkout_url");
                if (stripeUrl == null || stripeUrl.isEmpty()) {
                    throw new RuntimeException("Server did not return a checkout URL");
                }

                // Step B: Open in system browser
                Platform.runLater(() -> {
                    statusLabel.setText("Opening payment page in your browser...\n\n"
                            + "Complete the payment there, then return here.\n"
                            + "This window will detect your payment automatically.");
                });

                // Open URL in system default browser
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(stripeUrl));

                Platform.runLater(() -> {
                    statusLabel.setText("Waiting for payment confirmation...\n\n"
                            + "Complete the payment in your browser.\n"
                            + "This window will update automatically.");
                    // Re-enable back button so user can cancel
                    backButton.setVisible(true);
                    backButton.setManaged(true);
                });

                // Step C: Poll for payment confirmation (also clearnet)
                stripePollingActive = true;
                String statusUrl = serverUrl + "/stripe/status/" + nonce;
                String redemptionCode = null;

                while (stripePollingActive) {
                    Thread.sleep(3000); // Poll every 3 seconds

                    if (!stripePollingActive) break;

                    try {
                        String statusJson = clearnetGet(statusUrl);
                        String status = parseJsonField(statusJson, "status");

                        if ("ready".equals(status)) {
                            redemptionCode = parseJsonField(statusJson, "redemption_code");
                            log.info("[perseverus] Payment confirmed — redemption code received");
                            PrivacyLog.get().paymentStripeConfirmed(selectedStripePlan.apiValue);
                            break;
                        }
                    } catch (Exception pollEx) {
                        log.debug("[perseverus] Poll error (will retry): {}", pollEx.getMessage());
                    }
                }

                if (!stripePollingActive) {
                    // User cancelled
                    return;
                }

                if (redemptionCode == null) {
                    throw new RuntimeException("Payment confirmation timed out");
                }

                // Step D: Redeem tokens via Tor — blinded tokens + redemption
                // code sent in one request to /stripe/redeem over .onion
                String torServerUrl = Config.get().getPerseverusServerUrl();
                if (torServerUrl == null || torServerUrl.isEmpty()) {
                    torServerUrl = "http://medusayl5rrmgnekpabcduw7onhvdowmfart2mulq3b64chgzng52had.onion";
                }
                final String code = redemptionCode;
                final String srvUrl = torServerUrl;
                Platform.runLater(() -> {
                    statusLabel.setText("Payment confirmed! Issuing privacy tokens...");
                });

                redeemStripeTokens(srvUrl, code, title, statusLabel, spinner);

            } catch (Exception e) {
                log.error("[perseverus] Stripe checkout failed", e);
                stripePollingActive = false;
                Platform.runLater(() -> {
                    showPaymentError("Stripe checkout failed: " + e.getMessage());
                });
            }
        }, "stripe-checkout");
        checkoutThread.setDaemon(true);
        checkoutThread.start();
    }

    // ═════════════════════════════════════════════════════════════════════
    // CC PATH — Step 4: Redeem Tokens
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Exchange the redemption code for VOPRF tokens via the issuance
     * client. This is the privacy boundary — after this call, the
     * redemption code is deleted and token spends are unlinkable.
     */
    private void redeemStripeTokens(String serverUrl, String redemptionCode,
                                     Label title, Label statusLabel,
                                     ProgressIndicator spinner) {
        try {
            // Get server pubkey for issuance client
            String pubkeyHex = Config.get().getPerseverusServerPubkey();
            if (pubkeyHex == null || pubkeyHex.isEmpty()) {
                // Fetch from server (clearnet is fine — pubkey is public)
                String pubkeyJson = clearnetGet(CLEARNET_SERVER_URL + "/server/pubkey");
                pubkeyHex = parseJsonField(pubkeyJson, "pubkey_hex");
            }

            // Use the issuance client to prepare blinded tokens and
            // redeem them via the payment-gated /stripe/redeem endpoint.
            // Blinded tokens + redemption code are sent in ONE request,
            // maintaining the privacy firewall: the code is the ONLY link
            // between "who paid" and "who got tokens."
            int packSize = 100; // Standard token pack size
            int expirationMonth = currentExpirationMonth();

            // Create a temporary issuance client (routed via Tor)
            long handle = Native.issuanceCreate(serverUrl, pubkeyHex);
            try {
                Platform.runLater(() -> {
                    statusLabel.setText("Generating blinded tokens...");
                });

                int totalTokens;
                if (selectedStripePlan == StripePlan.YEARLY) {
                    // Yearly: ONE code → 13 monthly packs in a single batch
                    // request. baseMonth (server /epoch) anchors the schedule;
                    // the native layer lays out the bridge + 12 monthly packs.
                    int baseMonth = serverMonth(serverUrl);
                    byte[][] packBlobs = Native.issuanceRedeemYearly(
                        handle, packSize, baseMonth, redemptionCode
                    );
                    if (packBlobs == null || packBlobs.length == 0) {
                        throw new RuntimeException("Yearly issuance returned no packs");
                    }
                    for (byte[] blob : packBlobs) {
                        if (blob == null || blob.length == 0) continue;
                        Config.get().addPerseverusPack(new Config.PersistedPack(
                            packSize, blob, new boolean[packSize],
                            java.time.Instant.now().toString()));
                    }
                    totalTokens = packBlobs.length * packSize;
                    log.info("[perseverus] Yearly: {} packs issued ({} tokens), base month {}",
                            packBlobs.length, totalTokens, baseMonth);
                } else {
                    // One-time / monthly: a single pack via /stripe/redeem.
                    byte[] packBlob = Native.issuanceRedeemPack(
                        handle, packSize, expirationMonth, redemptionCode
                    );
                    if (packBlob == null || packBlob.length == 0) {
                        throw new RuntimeException("Token issuance returned empty pack");
                    }
                    Config.get().addPerseverusPack(new Config.PersistedPack(
                        packSize, packBlob, new boolean[packSize],
                        java.time.Instant.now().toString()));
                    totalTokens = packSize;
                    log.info("[perseverus] Stripe tokens redeemed: {} tokens issued and persisted", packSize);
                }

                stripeRedeemed = true;
                PrivacyLog.get().paymentStripeRedeemed(totalTokens);

                final int shownTokens = totalTokens;
                Platform.runLater(() -> {
                    Config.get().setPerseverusWelcomed(true);
                    Config.get().setPerseverusTrialMode(false);
                    showStripeSuccess(shownTokens);
                });

            } finally {
                Native.issuanceDestroy(handle);
            }

        } catch (Exception e) {
            log.error("[perseverus] Token redemption failed", e);
            PrivacyLog.get().paymentStripeFailed(e.getMessage());
            Platform.runLater(() -> {
                showPaymentError("Token issuance failed: " + e.getMessage());
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Lightning (phoenixd) flow
    // ═════════════════════════════════════════════════════════════════════

    /** Resolve the server URL (onion), preferring the configured value. */
    private String lnServerUrl() {
        String url = Config.get().getPerseverusServerUrl();
        if (url == null || url.isEmpty()) {
            url = "http://medusayl5rrmgnekpabcduw7onhvdowmfart2mulq3b64chgzng52had.onion";
        }
        return url.replaceAll("/+$", "");
    }

    /** Sats amount for the selected plan. The plan screen already fetched the
     *  live price (and may carry a manual price override), so we use the selected
     *  plan's current sats directly — re-fetching here would clobber an override. */
    private long lnPlanSats() {
        PerseverusPaymentManager.Plan p = selectedPlan != null
                ? selectedPlan
                : PerseverusPaymentManager.Plan.MONTHLY;
        return p.getAmountSats();
    }

    /** Step 3 (Lightning): generate the BOLT11 invoice automatically for the
     *  selected plan's sats (honouring any price override), then show the QR. */
    private void showStepLnInvoice() {
        currentStep = 3;
        backButton.setVisible(true);
        backButton.setManaged(true);
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        stepIndicator.setText("Step 3 of 4");

        long realSats = lnPlanSats();
        if (realSats <= 0) {
            showPaymentError("Could not determine the price — go back and choose a plan again.");
            return;
        }
        generateLnInvoice(realSats);   // shows its own spinner, then the QR
    }

    /** Request the BOLT11 invoice (circuit A) for the chosen sats, then show QR. */
    private void generateLnInvoice(long amountSat) {
        contentArea.getChildren().clear();
        ImageView logo = createLogo(60);
        Label title = new Label("Pay with Lightning");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(40, 40);
        Label statusLabel = new Label("Creating invoice…");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        VBox box = new VBox(12, logo, title, spinner, statusLabel);
        box.setAlignment(Pos.CENTER);
        contentArea.getChildren().add(box);

        lnPollingActive = true;
        Thread t = new Thread(() -> {
            try {
                String srvUrl = lnServerUrl();
                String resp = Native.httpPost(srvUrl + "/ln/invoice",
                        String.format("{\"amount_sat\":%d}", amountSat));
                String invoice = parseJsonField(resp, "invoice");
                String nonce = parseJsonField(resp, "nonce");
                long amt = parseJsonLong(resp, "amount_sat");
                if (invoice == null || invoice.isBlank() || nonce == null || nonce.isBlank()) {
                    throw new RuntimeException("invoice request failed: "
                            + (resp == null ? "(null)" : resp));
                }
                Platform.runLater(() -> showLnInvoiceScreen(invoice, nonce, amt, srvUrl));
            } catch (Exception e) {
                log.error("[perseverus] LN invoice request failed", e);
                Platform.runLater(() -> showPaymentError("Lightning invoice failed: " + e.getMessage()));
            }
        }, "perseverus-ln-invoice");
        t.setDaemon(true);
        t.start();
    }

    /** Render the invoice (QR + copy + open-in-wallet) and start polling. */
    private void showLnInvoiceScreen(String invoice, String nonce, long amountSat, String srvUrl) {
        contentArea.getChildren().clear();

        ImageView logo = createLogo(46);
        Label title = new Label("Pay with Lightning");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label amt = new Label(amountSat > 0 ? String.format("%,d sats", amountSat) : "");
        amt.setStyle("-fx-font-size: 13px; -fx-opacity: 0.8;");

        ImageView qr = new ImageView();
        javafx.scene.image.Image qrImg = lnQrImage(invoice);
        if (qrImg != null) {
            qr.setImage(qrImg);
            qr.setFitWidth(220);
            qr.setFitHeight(220);
            qr.setPreserveRatio(true);
        }

        String shortInv = invoice.length() > 26
                ? invoice.substring(0, 14) + "…" + invoice.substring(invoice.length() - 8) : invoice;
        Label invLabel = new Label(shortInv);
        invLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 10px; -fx-opacity: 0.7;");

        Button copyBtn = new Button("Copy invoice");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(invoice);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("Copied ✓");
        });
        HBox btns = new HBox(10, copyBtn);
        btns.setAlignment(Pos.CENTER);

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(26, 26);
        Label status = new Label("Scan with your Lightning wallet to pay…");
        status.setStyle("-fx-font-size: 12px;");
        status.setWrapText(true);
        status.setTextAlignment(TextAlignment.CENTER);
        status.setAlignment(Pos.CENTER);
        status.setMaxWidth(360);

        VBox box = new VBox(8, logo, title, amt, qr, invLabel, btns, spinner, status);
        box.setAlignment(Pos.CENTER);
        contentArea.getChildren().add(box);

        Thread poll = new Thread(() -> pollLnAndClaim(nonce, srvUrl, status), "perseverus-ln-poll");
        poll.setDaemon(true);
        poll.start();
    }

    /** Poll /ln/status until paid (or expiry), then claim the subscription pack. */
    private void pollLnAndClaim(String nonce, String srvUrl, Label status) {
        pollLnAndClaim(nonce, srvUrl, status, false);
    }

    /** Poll /ln/status until paid (or expiry), then claim. {@code trial=true}
     *  claims a single trial scan token; otherwise the full subscription pack. */
    private void pollLnAndClaim(String nonce, String srvUrl, Label status, boolean trial) {
        long start = System.currentTimeMillis();
        long timeoutMs = 60L * 60 * 1000; // matches invoice expiry
        while (lnPollingActive && System.currentTimeMillis() - start < timeoutMs) {
            try { Thread.sleep(3_000); } catch (InterruptedException e) { return; }
            String json;
            try {
                json = Native.httpGet(srvUrl + "/ln/status/" + nonce);
            } catch (Throwable e) {
                continue; // transient
            }
            if (json != null && json.contains("\"paid\":true")) {
                lnPollingActive = false;
                Platform.runLater(() -> status.setText(trial
                        ? "Payment received — unlocking your trial scan…"
                        : "Payment received — issuing tokens…"));
                if (trial) {
                    claimTrialToken(nonce, srvUrl);
                } else {
                    claimLnTokens(nonce, srvUrl);
                }
                return;
            }
        }
    }

    /** Once paid, claim the blind-signed pack over a fresh circuit and persist it. */
    private void claimLnTokens(String nonce, String srvUrl) {
        new Thread(() -> {
            try {
                String pubkeyHex = Config.get().getPerseverusServerPubkey();
                if (pubkeyHex == null || pubkeyHex.isEmpty()) {
                    pubkeyHex = parseJsonField(clearnetGet(CLEARNET_SERVER_URL + "/server/pubkey"), "pubkey_hex");
                }
                int packSize = 100;
                int expirationMonth = currentExpirationMonth();
                long handle = Native.issuanceCreate(srvUrl, pubkeyHex);
                try {
                    byte[] blob = Native.issuanceClaimLn(handle, packSize, expirationMonth, nonce);
                    if (blob == null || blob.length == 0) {
                        throw new RuntimeException("Lightning claim returned an empty pack");
                    }
                    Config.get().addPerseverusPack(new Config.PersistedPack(
                            packSize, blob, new boolean[packSize], java.time.Instant.now().toString()));
                    stripeRedeemed = true; // reuse the "tokens issued in wizard" close path
                    final int tokens = packSize;
                    PrivacyLog.get().info("Lightning payment complete — " + tokens + " tokens issued");
                    Platform.runLater(() -> {
                        Config.get().setPerseverusWelcomed(true);
                        Config.get().setPerseverusTrialMode(false);
                        showStripeSuccess(tokens);
                    });
                } finally {
                    Native.issuanceDestroy(handle);
                }
            } catch (Exception e) {
                log.error("[perseverus] Lightning claim failed", e);
                Platform.runLater(() -> showPaymentError("Token issuance failed: " + e.getMessage()));
            }
        }, "perseverus-ln-claim").start();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Trial — pay a $0.25 Lightning invoice for ONE scan token
    // ═════════════════════════════════════════════════════════════════════

    private volatile boolean trialRedeemed;
    /** Number of trial scans to purchase (1..99). Drives both the invoice amount
     *  ($0.25 each) and the number of tokens issued. Set via "More Scans". */
    private int trialQuantity = 1;

    /**
     * Entry point for the "Try BTC Medusa" trial: one scan token bought with a
     * $0.25 Lightning invoice (priced via mempool.space). Call right after
     * constructing the wizard and before {@code showAndWait()} — it replaces the
     * step-1 content with the trial screen.
     */
    public void startTrial() {
        setTitle("BTC Medusa — Trial");
        // The trial screen (QR + buttons + status + More Scans) is taller than the
        // wizard's default 440px, which clips the dialog's button bar. Give it room.
        getDialogPane().setPrefHeight(620);
        getDialogPane().setMinHeight(620);
        backButton.setVisible(false);
        backButton.setManaged(false);
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        if (stepIndicator != null) stepIndicator.setText("");
        contentArea.getChildren().clear();

        ImageView logo = createLogo(60);
        Label title = new Label("Trial Scan");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(36, 36);
        Label status = new Label("Pricing a quarter in sats…");
        status.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        VBox box = new VBox(12, logo, title, spinner, status);
        box.setAlignment(Pos.CENTER);
        contentArea.getChildren().add(box);

        new Thread(() -> {
            long sats = PerseverusPaymentManager.usdToSats(0.25);   // mempool.space price
            Platform.runLater(() -> {
                if (sats <= 0) {
                    showPaymentError("Couldn't fetch the BTC price to size the invoice. Try again.");
                    return;
                }
                generateTrialInvoice(sats);
            });
        }, "perseverus-trial-price").start();
    }

    /** Request a BOLT11 invoice for {@code trialQuantity} trial tokens, show QR. */
    private void generateTrialInvoice(long amountSat) {
        contentArea.getChildren().clear();
        ImageView logo = createLogo(56);
        Label title = new Label("Trial Scan");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(36, 36);
        Label status = new Label("Creating invoice…");
        status.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        VBox box = new VBox(12, logo, title, spinner, status);
        box.setAlignment(Pos.CENTER);
        contentArea.getChildren().add(box);

        lnPollingActive = true;
        Thread t = new Thread(() -> {
            try {
                String srvUrl = lnServerUrl();
                // tokens:trialQuantity — server clamps to 1..=100 and authorizes
                // exactly that many scan tokens for this invoice.
                String resp = Native.httpPost(srvUrl + "/ln/invoice",
                        String.format("{\"amount_sat\":%d,\"tokens\":%d}", amountSat, trialQuantity));
                String invoice = parseJsonField(resp, "invoice");
                String nonce = parseJsonField(resp, "nonce");
                long amt = parseJsonLong(resp, "amount_sat");
                if (invoice == null || invoice.isBlank() || nonce == null || nonce.isBlank()) {
                    throw new RuntimeException("invoice request failed: "
                            + (resp == null ? "(null)" : resp));
                }
                Platform.runLater(() -> showTrialInvoiceScreen(invoice, nonce,
                        amt > 0 ? amt : amountSat, srvUrl));
            } catch (Exception e) {
                log.error("[perseverus] trial LN invoice request failed", e);
                Platform.runLater(() -> showPaymentError("Lightning invoice failed: " + e.getMessage()));
            }
        }, "perseverus-trial-invoice");
        t.setDaemon(true);
        t.start();
    }

    /** Render the trial invoice QR + the CashApp/Lightning hint, then poll. */
    private void showTrialInvoiceScreen(String invoice, String nonce, long amountSat, String srvUrl) {
        contentArea.getChildren().clear();

        ImageView logo = createLogo(44);
        Label title = new Label(trialQuantity > 1
                ? String.format("Trial — %d scans — $%.2f", trialQuantity, 0.25 * trialQuantity)
                : "Trial Scan — $0.25");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label amt = new Label(amountSat > 0 ? String.format("%,d sats", amountSat) : "");
        amt.setStyle("-fx-font-size: 12px; -fx-opacity: 0.8;");

        ImageView qr = new ImageView();
        javafx.scene.image.Image qrImg = lnQrImage(invoice);
        if (qrImg != null) {
            qr.setImage(qrImg);
            qr.setFitWidth(210);
            qr.setFitHeight(210);
            qr.setPreserveRatio(true);
        }

        // "Pay with CashApp or any Lightning wallet." — CashApp opens cash.app.
        Label payPre = new Label("Pay with ");
        payPre.setStyle("-fx-font-size: 12px;");
        Hyperlink cashApp = new Hyperlink("CashApp");
        cashApp.setStyle("-fx-font-size: 12px;");
        cashApp.setOnAction(e -> {
            try {
                AppServices.get().getApplication().getHostServices().showDocument("https://cash.app");
            } catch (Exception ignored) {}
        });
        Label payPost = new Label(" or any Lightning wallet.");
        payPost.setStyle("-fx-font-size: 12px;");
        HBox payRow = new HBox(0, payPre, cashApp, payPost);
        payRow.setAlignment(Pos.CENTER);

        Button copyBtn = new Button("Copy invoice");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(invoice);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("Copied ✓");
        });
        HBox btns = new HBox(10, copyBtn);
        btns.setAlignment(Pos.CENTER);

        ProgressIndicator spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(24, 24);
        Label status = new Label("Scan to pay — your trial scan unlocks on payment…");
        status.setStyle("-fx-font-size: 12px;");
        status.setWrapText(true);
        status.setTextAlignment(TextAlignment.CENTER);
        status.setAlignment(Pos.CENTER);
        status.setMaxWidth(360);

        // "More Scans": buy several trial scans at once (sits above the Cancel
        // button in the dialog button bar).
        Button moreScans = new Button("More Scans");
        moreScans.setOnAction(e -> showMoreScansDialog());

        VBox box = new VBox(8, logo, title, amt, qr, payRow, btns, spinner, status, moreScans);
        box.setAlignment(Pos.CENTER);

        // Hidden override hotspot (lower-left corner): a fully transparent click
        // target — NO visible text, ever (not even on hover). Click to override
        // the invoice amount in sats (testing / custom amounts).
        Region overrideHotspot = new Region();
        overrideHotspot.setPrefSize(40, 26);
        overrideHotspot.setMinSize(40, 26);
        overrideHotspot.setMaxSize(40, 26);
        overrideHotspot.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
        overrideHotspot.setOnMouseClicked(e -> showTrialAmountOverride(amountSat));

        StackPane root = new StackPane(box, overrideHotspot);
        root.setMaxWidth(Double.MAX_VALUE);
        root.setMaxHeight(Double.MAX_VALUE);
        StackPane.setAlignment(box, Pos.CENTER);
        StackPane.setAlignment(overrideHotspot, Pos.BOTTOM_LEFT);
        // Negative margins cancel out the contentArea padding (25 left, 10 bottom)
        // so the hotspot sits flush in the lower-left corner of the dialog.
        StackPane.setMargin(overrideHotspot, new Insets(0, 0, -10, -25));
        VBox.setVgrow(root, Priority.ALWAYS);
        contentArea.getChildren().add(root);

        Thread poll = new Thread(() -> pollLnAndClaim(nonce, srvUrl, status, true),
                "perseverus-trial-poll");
        poll.setDaemon(true);
        poll.start();
    }

    /** Hidden override: prompt for a custom sats amount, then regenerate the
     *  trial invoice (still authorizing the current trial quantity). */
    private void showTrialAmountOverride(long currentSats) {
        javafx.scene.control.TextInputDialog dlg =
                new javafx.scene.control.TextInputDialog(String.valueOf(currentSats));
        dlg.initOwner(getDialogPane().getScene().getWindow());
        dlg.setTitle("Override Trial Amount");
        dlg.setHeaderText("Set the trial invoice amount in sats.");
        dlg.setContentText("Sats:");
        java.util.Optional<String> res = dlg.showAndWait();
        res.ifPresent(s -> {
            try {
                long sats = Long.parseLong(s.trim());
                if (sats > 0) {
                    lnPollingActive = false;   // stop polling the old invoice
                    generateTrialInvoice(sats); // new invoice + QR at the new amount
                }
            } catch (NumberFormatException ignored) {
                // ignore non-numeric input — keep the current invoice
            }
        });
    }

    /** "More Scans": ask how many trial scans to buy (1..99), then rebuild the
     *  invoice for that quantity (amount = $0.25 each, tokens = quantity). */
    private void showMoreScansDialog() {
        javafx.scene.control.TextInputDialog dlg =
                new javafx.scene.control.TextInputDialog(String.valueOf(Math.max(1, trialQuantity)));
        dlg.initOwner(getDialogPane().getScene().getWindow());
        dlg.setTitle("More Scans");
        dlg.setHeaderText("How many trial scans would you like to purchase?");
        dlg.setContentText("Scans:");
        // Digits only, max two (1..99).
        dlg.getEditor().setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().matches("\\d{0,2}") ? change : null));
        java.util.Optional<String> res = dlg.showAndWait();
        res.ifPresent(s -> {
            int n;
            try { n = Integer.parseInt(s.trim()); }
            catch (NumberFormatException ex) { return; }
            if (n < 1) n = 1;
            if (n > 99) n = 99;
            trialQuantity = n;
            lnPollingActive = false;   // stop polling the old invoice
            final int qty = n;
            // Price the new quantity off the UI thread, then regenerate.
            new Thread(() -> {
                long sats = PerseverusPaymentManager.usdToSats(0.25 * qty);
                Platform.runLater(() -> {
                    if (sats <= 0) {
                        showPaymentError("Couldn't fetch the BTC price. Try again.");
                        return;
                    }
                    generateTrialInvoice(sats);
                });
            }, "perseverus-trial-qty").start();
        });
    }

    /** Claim the trial token pack (expires end of next month) and persist it. */
    private void claimTrialToken(String nonce, String srvUrl) {
        new Thread(() -> {
            try {
                String pubkeyHex = Config.get().getPerseverusServerPubkey();
                if (pubkeyHex == null || pubkeyHex.isEmpty()) {
                    pubkeyHex = parseJsonField(clearnetGet(CLEARNET_SERVER_URL + "/server/pubkey"), "pubkey_hex");
                }
                int packSize = Math.max(1, trialQuantity);
                int expirationMonth = currentExpirationMonth();   // end of next month
                long handle = Native.issuanceCreate(srvUrl, pubkeyHex);
                try {
                    byte[] blob = Native.issuanceClaimLn(handle, packSize, expirationMonth, nonce);
                    if (blob == null || blob.length == 0) {
                        throw new RuntimeException("Lightning claim returned an empty pack");
                    }
                    Config.get().addPerseverusPack(new Config.PersistedPack(
                            packSize, blob, new boolean[packSize], java.time.Instant.now().toString()));
                    trialRedeemed = true;
                    final int issued = packSize;
                    PrivacyLog.get().info("Trial Lightning payment complete — " + issued
                            + " scan token(s) issued (exp " + expirationMonth + ")");
                    Platform.runLater(() -> {
                        // Trial mode keeps the scan gate happy; the purchased flag
                        // flips the Privacy-tab button to "BTC Medusa" for good
                        // (until cleared via the hidden reset hotspot). Not a full
                        // subscription, so the welcome dialog stays available.
                        Config.get().setPerseverusTrialMode(true);
                        Config.get().setPerseverusTrialPurchased(true);
                        showTrialSuccess(issued);
                    });
                } finally {
                    Native.issuanceDestroy(handle);
                }
            } catch (Exception e) {
                log.error("[perseverus] trial claim failed", e);
                Platform.runLater(() -> showPaymentError("Token issuance failed: " + e.getMessage()));
            }
        }, "perseverus-trial-claim").start();
    }

    /** Trial success screen with a Close button. */
    private void showTrialSuccess(int count) {
        contentArea.getChildren().clear();
        ImageView logo = createLogo(56);
        Label title = new Label(count > 1 ? "Trial Scans Ready!" : "Trial Scan Ready!");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #27ae60;");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);
        Label line1 = new Label("Payment received — " + count + " privacy scan"
                + (count > 1 ? "s are" : " is") + " ready to use.");
        line1.setStyle("-fx-font-size: 13px;");
        line1.setAlignment(Pos.CENTER);
        line1.setMaxWidth(Double.MAX_VALUE);
        Label line2 = new Label("Expires at the end of next month. Pay again any time for more scans.");
        line2.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");
        line2.setWrapText(true);
        line2.setTextAlignment(TextAlignment.CENTER);
        line2.setMaxWidth(340);

        nextButton.setVisible(false); nextButton.setManaged(false);
        backButton.setVisible(false); backButton.setManaged(false);
        Button cancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) { cancelBtn.setVisible(false); cancelBtn.setManaged(false); }

        Button closeBtn = new Button("Close");
        closeBtn.setPrefWidth(100);
        closeBtn.setOnAction(e -> { trialRedeemed = true; setResult(Result.TRIAL_TOKEN); close(); });

        VBox box = new VBox(12, logo, title, line1, line2, closeBtn);
        box.setAlignment(Pos.CENTER);
        contentArea.getChildren().add(box);
    }

    /** Render a BOLT11 invoice as a QR image (uppercased for QR density). */
    private javafx.scene.image.Image lnQrImage(String data) {
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    data.toUpperCase(java.util.Locale.ROOT),
                    com.google.zxing.BarcodeFormat.QR_CODE, 440, 440,
                    java.util.Map.of(com.google.zxing.EncodeHintType.MARGIN, 2));
            java.awt.image.BufferedImage bi =
                    com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix);
            return javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
        } catch (Exception e) {
            log.warn("[perseverus] QR generation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Extract {@code "field":<int>} from a flat JSON object; -1 if absent. */
    private static long parseJsonLong(String json, String field) {
        if (json == null) return -1;
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) return -1;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return -1;
        int j = colon + 1;
        while (j < json.length() && !Character.isDigit(json.charAt(j)) && json.charAt(j) != '-') j++;
        int k = j;
        if (k < json.length() && json.charAt(k) == '-') k++;
        while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
        if (k > j) {
            try { return Long.parseLong(json.substring(j, k)); } catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    private void showStripeSuccess(int tokenCount) {
        contentArea.getChildren().clear();

        ImageView logo = createLogo(60);

        Label title = new Label("Tokens Issued!");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #27ae60;");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        Label line1 = new Label("Your BTC Medusa subscription is now active.");
        line1.setStyle("-fx-font-size: 13px;");
        line1.setAlignment(Pos.CENTER);
        line1.setMaxWidth(Double.MAX_VALUE);

        Label line2 = new Label(tokenCount + " privacy tokens are ready to use.");
        line2.setStyle("-fx-font-size: 13px;");
        line2.setAlignment(Pos.CENTER);
        line2.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        spacer.setPrefHeight(10);

        TextFlow privacyNote = new TextFlow();
        privacyNote.setTextAlignment(TextAlignment.CENTER);
        Text noteText = new Text(
            "Your payment receipt has been securely deleted. "
            + "Future privacy queries are cryptographically unlinkable "
            + "to this payment. We will never know what you scan.");
        noteText.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        noteText.setFill(noteFill());
        privacyNote.getChildren().add(noteText);

        // Hide all dialog buttons — only the standalone Close button remains
        nextButton.setVisible(false);
        nextButton.setManaged(false);
        backButton.setVisible(false);
        backButton.setManaged(false);
        Button cancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) {
            cancelBtn.setVisible(false);
            cancelBtn.setManaged(false);
        }

        // Add a standalone Close button that actually closes the dialog
        Button closeBtn = new Button("Close");
        closeBtn.setPrefWidth(100);
        closeBtn.setStyle("-fx-font-size: 13px;");
        closeBtn.setOnAction(e -> {
            stripeRedeemed = true;
            setResult(Result.STRIPE_PAYMENT);
            close();
        });

        Region spacer2 = new Region();
        spacer2.setPrefHeight(10);

        contentArea.getChildren().addAll(logo, title, line1, line2, spacer,
                privacyNote, spacer2, closeBtn);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Hidden Price Override (BTC path only)
    // ═════════════════════════════════════════════════════════════════════

    private void showPriceOverrideDialog() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(getDialogPane().getScene().getWindow());
        dlg.setTitle("Price Override");
        dlg.setHeaderText("Override Monthly Rate");

        TextField satsField = new TextField(
                String.valueOf(PerseverusPaymentManager.Plan.MONTHLY.getAmountSats()));
        satsField.setPromptText("e.g. 1000");

        CheckBox enforceStalenessCb = new CheckBox("Enforce price staleness check");
        enforceStalenessCb.setSelected(false);

        VBox content = new VBox(10,
                new Label("Monthly sats:"),
                satsField,
                enforceStalenessCb);
        content.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Auto-select text so user can immediately type an override value
        Platform.runLater(() -> {
            satsField.requestFocus();
            satsField.selectAll();
        });

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                long override = Long.parseLong(satsField.getText().trim().replace(",", ""));
                if (override > 0) {
                    PerseverusPaymentManager.overrideMonthlyAmount(
                            override, enforceStalenessCb.isSelected());
                    showStepBtcPlan();
                }
            } catch (NumberFormatException ex) {
                log.warn("[perseverus] Invalid override value: {}", satsField.getText());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Navigation
    // ═════════════════════════════════════════════════════════════════════

    private void goBack() {
        switch (currentStep) {
            case 2 -> showStepPaymentMethod();
            case 3 -> {
                stripePollingActive = false; // Stop polling if active
                lnPollingActive = false;
                if (selectedMethod == PaymentMethod.CREDIT_CARD) {
                    showStepStripePlan();   // CC uses the USD Stripe plan UI
                } else {
                    // Bitcoin AND Lightning share the sats-priced plan screen.
                    showStepBtcPlan();
                }
            }
            case 4 -> {
                if (selectedMethod == PaymentMethod.CREDIT_CARD) {
                    showStepStripePlan();
                } else {
                    showStepBtcReview();
                }
            }
        }
    }

    private void goNext() {
        switch (currentStep) {
            case 1 -> {
                if (selectedMethod == PaymentMethod.CREDIT_CARD) {
                    showStepStripePlan();   // CC uses the USD Stripe plan UI
                } else {
                    // Bitcoin AND Lightning share the sats-priced plan screen
                    // (One Time + Annual).
                    showStepBtcPlan();
                }
            }
            case 2 -> {
                if (selectedMethod == PaymentMethod.CREDIT_CARD) {
                    showStepStripeCheckout();
                } else if (selectedMethod == PaymentMethod.LIGHTNING) {
                    showStepLnInvoice();
                } else {
                    if (selectedPlan == null) return;
                    showStepBtcReview();
                }
            }
            case 3 -> {
                if (selectedMethod == PaymentMethod.BITCOIN) {
                    showPaymentModeChoice();
                }
            }
            case 4 -> {
                // Success screen — close the dialog
                if (broadcastTxid != null) {
                    setResult(Result.PAYMENT_SENT);
                    close();
                } else if (stripeRedeemed) {
                    setResult(Result.STRIPE_PAYMENT);
                    close();
                } else {
                    // Retry
                    if (selectedMethod == PaymentMethod.CREDIT_CARD) {
                        showStepStripeCheckout();
                    } else {
                        showPaymentModeChoice();
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Accessors
    // ═════════════════════════════════════════════════════════════════════

    public PerseverusPaymentManager.Plan getSelectedPlan() {
        return selectedPlan;
    }

    /** Returns a human-readable status string for the Privacy tab status line,
     *  or null if no BTC payment is in progress. */
    public String getBtcPaymentStatus() {
        return btcPaymentStatus;
    }

    public Sha256Hash getBroadcastTxid() {
        return broadcastTxid;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════

    private ImageView createLogo(double height) {
        ImageView logo = new ImageView();
        logo.setFitHeight(height);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        try {
            URL logoUrl = getClass().getResource("/com/sparrowwallet/sparrow/image/perseverus-logo.png");
            if (logoUrl != null) {
                logo.setImage(new Image(logoUrl.toExternalForm()));
            }
        } catch (Exception e) {
            log.debug("Logo not available for sign-up wizard");
        }
        // Soft white glow so the dark logo stands out on dark backgrounds.
        javafx.scene.effect.DropShadow glow = new javafx.scene.effect.DropShadow();
        glow.setColor(javafx.scene.paint.Color.web("#ffffff"));
        glow.setRadius(20);
        glow.setSpread(0.30);
        logo.setEffect(glow);
        return logo;
    }

    /** Secondary-note text colour that follows the app theme. JavaFX {@code Text}
     *  nodes default to black and don't inherit the theme's text colour the way
     *  Labels do, so dark-mode notes need their fill set explicitly. */
    private static javafx.scene.paint.Color noteFill() {
        return Config.get().getTheme() == com.sparrowwallet.sparrow.Theme.DARK
                ? javafx.scene.paint.Color.web("#c8c8c8")
                : javafx.scene.paint.Color.web("#555555");
    }

    private HBox detailRow(String label, String value) {
        Label left = new Label(label);
        left.setStyle("-fx-font-size: 12.5px;");
        left.setMinWidth(130);

        Label right = new Label(value);
        right.setStyle("-fx-font-size: 12.5px;");
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox row = new HBox(10, left, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Current epoch month (YYYYMM) from the SERVER's /epoch clock (honours the
     * simulated test clock), so issuance aligns with what the spend circuit
     * checks. Falls back to the local system month. Reads the configured server
     * URL from Config; the request rides the configured transport.
     */
    private int serverMonth(String serverUrl) {
        java.time.LocalDate now = java.time.LocalDate.now();
        int local = now.getYear() * 100 + now.getMonthValue();
        try {
            String url = (serverUrl != null && !serverUrl.isBlank())
                    ? serverUrl
                    : com.sparrowwallet.sparrow.io.Config.get().getPerseverusServerUrl();
            if (url != null && !url.isBlank()) {
                String json = Native.httpGet(url.replaceAll("/+$", "") + "/epoch");
                int i = json.indexOf("\"month\"");
                if (i >= 0) {
                    int j = json.indexOf(':', i) + 1;
                    while (j < json.length() && !Character.isDigit(json.charAt(j))) j++;
                    int k = j;
                    while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
                    if (k > j) return Integer.parseInt(json.substring(j, k));
                }
            }
        } catch (Throwable ignored) {
            // fall back to local month
        }
        return local;
    }

    /** Add n months to a YYYYMM value with proper year rollover. */
    private static int addMonthsYyyymm(int yyyymm, int n) {
        int y = yyyymm / 100, mo = yyyymm % 100;
        int total = y * 12 + (mo - 1) + n;
        return (total / 12) * 100 + (total % 12 + 1);
    }

    /**
     * Expiration month (YYYYMM) for the single-pack plans (one-time / monthly):
     * a ~1-month window from the server's epoch clock. The yearly plan does NOT
     * use this — it builds a per-pack schedule in the native layer.
     */
    private int currentExpirationMonth() {
        return addMonthsYyyymm(serverMonth(null), 1);
    }

    /**
     * Simple JSON field parser — avoids pulling in a JSON library for
     * trivial response parsing. Finds the first occurrence of
     * {@code "key":"value"} and returns the value string.
     */
    private static String parseJsonField(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Clearnet HTTP helpers (Stripe flow only — no Tor)
    // ═════════════════════════════════════════════════════════════════════

    /** Shared HttpClient for clearnet Stripe calls — no proxy. */
    private static final HttpClient CLEARNET_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * POST JSON to the given URL over clearnet (no Tor, no OHTTP).
     * Used exclusively for Stripe checkout/redeem calls where privacy
     * transport adds latency with no benefit.
     */
    private static String clearnetPost(String url, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = CLEARNET_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(resp.body().length(), 500)));
        }
        return resp.body();
    }

    /**
     * GET from the given URL over clearnet (no Tor).
     * Used for polling Stripe payment status.
     */
    private static String clearnetGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = CLEARNET_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": "
                    + resp.body().substring(0, Math.min(resp.body().length(), 500)));
        }
        return resp.body();
    }

    /**
     * Fetch the current chain tip height from mempool.space.
     * Returns 0 if the request fails.
     */
    private static long fetchTipHeight() {
        try {
            String body = clearnetGet("https://mempool.space/api/blocks/tip/height");
            return Long.parseLong(body.trim());
        } catch (Exception e) {
            log.debug("[perseverus] Failed to fetch tip height: {}", e.getMessage());
            return 0;
        }
    }

    // ── Simple JSON field parsers (no dependency on a JSON library) ──

    private static String jsonStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == 'n') return null; // null
        if (json.charAt(i) != '"') return null;
        int start = i + 1;
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private static int jsonIntField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return 0;
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            sb.append(json.charAt(i));
            i++;
        }
        if (sb.length() == 0) return 0;
        try { return Integer.parseInt(sb.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
