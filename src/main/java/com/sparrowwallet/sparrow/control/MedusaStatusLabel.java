package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Config.PerseverusTransport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.controlsfx.glyphfont.Glyph;

/**
 * Status bar widget showing the BTC Medusa connection status.
 * Displays a shield icon + optional status text, right-justified.
 *   green  = Tor (maximum anonymity)
 *   yellow = OHTTP (IP hidden, fallback relay)
 *   orange = Direct (no privacy relay)
 */
public class MedusaStatusLabel extends HBox {
    private final Label iconLabel;
    private final Label statusText;

    public MedusaStatusLabel() {
        setAlignment(Pos.CENTER_RIGHT);
        setSpacing(4);
        setPadding(OsType.getCurrent() == OsType.WINDOWS ? new Insets(0, 0, 1, 5) : new Insets(1, 0, 0, 5));

        iconLabel = new Label();
        iconLabel.getStyleClass().add("medusa-status");
        iconLabel.setGraphic(getIcon());

        statusText = new Label();
        statusText.getStyleClass().add("medusa-status-text");
        statusText.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #333333;");

        getChildren().addAll(statusText, iconLabel);
        update();
    }

    public void update() {
        PerseverusTransport transport = Config.get().getPerseverusTransport();
        iconLabel.getStyleClass().removeAll("medusa-protected", "medusa-fallback", "medusa-unprotected");
        switch (transport) {
            case DIRECT:
                iconLabel.setTooltip(new Tooltip("BTC Medusa: Direct — no privacy relay"));
                iconLabel.getStyleClass().add("medusa-unprotected");
                break;
            case OHTTP:
                iconLabel.setTooltip(new Tooltip("BTC Medusa: OHTTP relay — IP hidden from server"));
                iconLabel.getStyleClass().add("medusa-fallback");
                break;
            case TOR:
                iconLabel.setTooltip(new Tooltip("BTC Medusa: Tor — maximum anonymity"));
                iconLabel.getStyleClass().add("medusa-protected");
                break;
        }
    }

    /** Set the connection status text shown next to the shield icon. */
    public void setStatusText(String text) {
        statusText.setText(text != null ? text : "");
    }

    private Node getIcon() {
        Glyph shield = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.SHIELD_ALT);
        shield.setFontSize(OsType.getCurrent() == OsType.WINDOWS ? 14 : 15);
        return shield;
    }
}
