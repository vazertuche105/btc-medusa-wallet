package com.sparrowwallet.perseverus;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * Welcome dialog shown on first launch or when the user clicks the
 * Perseverus button in the Privacy tab.
 *
 * <p>Offers two paths:</p>
 * <ul>
 *   <li><b>Try BTC Medusa</b> — pay a $0.25 Lightning invoice for one trial
 *       scan token (expires end of next month)</li>
 *   <li><b>Sign Up</b> — the full subscription payment flow</li>
 * </ul>
 */
public class PerseverusWelcomeDialog extends Dialog<PerseverusWelcomeDialog.Result> {
    private static final Logger log = LoggerFactory.getLogger(PerseverusWelcomeDialog.class);

    public enum Result {
        TRY_FREE,
        SIGN_UP,
        CANCELLED
    }

    private static final ButtonType TRY_BUTTON = new ButtonType("Try BTC Medusa", ButtonBar.ButtonData.LEFT);
    private static final ButtonType SIGN_UP_BUTTON = new ButtonType("Sign Up", ButtonBar.ButtonData.OK_DONE);

    public PerseverusWelcomeDialog() {
        final DialogPane dialogPane = getDialogPane();
        setTitle("Welcome to BTC Medusa");
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());
        dialogPane.setPrefWidth(480);
        dialogPane.setPrefHeight(400);
        AppServices.moveToActiveWindowScreen(this);

        // ── Build content ──
        VBox content = new VBox(20);
        content.setAlignment(Pos.TOP_CENTER);
        content.setPadding(new Insets(10, 20, 10, 20));

        // Logo
        ImageView logo = new ImageView();
        logo.setFitHeight(100);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        try {
            URL logoUrl = getClass().getResource("/com/sparrowwallet/sparrow/image/perseverus-logo.png");
            if (logoUrl != null) {
                logo.setImage(new Image(logoUrl.toExternalForm()));
            }
        } catch (Exception e) {
            log.debug("Logo not available for welcome dialog");
        }
        // Soft white glow so the dark logo pops on dark backgrounds.
        javafx.scene.effect.DropShadow logoGlow = new javafx.scene.effect.DropShadow();
        logoGlow.setColor(javafx.scene.paint.Color.web("#ffffff"));
        logoGlow.setRadius(22);
        logoGlow.setSpread(0.30);
        logo.setEffect(logoGlow);

        // Title
        Label title = new Label("BTC Medusa");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        // Subtitle
        Label subtitle = new Label("Privacy-Preserving KYC Intelligence");
        subtitle.setStyle("-fx-font-size: 13px; -fx-opacity: 0.7;");

        // Description
        TextFlow description = new TextFlow();
        description.setTextAlignment(TextAlignment.CENTER);
        description.setLineSpacing(4);

        Text descText = new Text(
            "BTC Medusa scans your UTXOs against known KYC-flagged clusters "
            + "using zero-knowledge proofs. The server never learns which UTXOs "
            + "are yours \u2014 your privacy is cryptographically guaranteed.\n\n"
        );
        descText.setStyle("-fx-font-size: 12.5px;");

        Text trialText = new Text(
            "Use a lightning wallet to get a trial scan for just a quarter!"
        );
        trialText.setStyle("-fx-font-size: 12.5px; -fx-font-weight: bold;");

        description.getChildren().addAll(descText, trialText);

        // Pay-per-scan: no more free scans. Every scan is backed by a payment
        // (a $0.25 Lightning trial token, or a subscription), so there is no
        // free-scan counter to show here.
        content.getChildren().addAll(logo, title, subtitle, description);

        dialogPane.setContent(content);

        // ── Buttons ──
        dialogPane.getButtonTypes().addAll(TRY_BUTTON, SIGN_UP_BUTTON, ButtonType.CANCEL);

        // Style the Sign Up button
        Button signUpBtn = (Button) dialogPane.lookupButton(SIGN_UP_BUTTON);
        if (signUpBtn != null) {
            signUpBtn.setDefaultButton(true);
            signUpBtn.setPrefWidth(100);
        }

        // Style the Try button
        Button tryBtn = (Button) dialogPane.lookupButton(TRY_BUTTON);
        if (tryBtn != null) {
            tryBtn.setPrefWidth(130);
        }

        // ── Result converter ──
        setResultConverter(buttonType -> {
            if (buttonType == TRY_BUTTON) {
                return Result.TRY_FREE;
            } else if (buttonType == SIGN_UP_BUTTON) {
                return Result.SIGN_UP;
            }
            return Result.CANCELLED;
        });
    }
}
