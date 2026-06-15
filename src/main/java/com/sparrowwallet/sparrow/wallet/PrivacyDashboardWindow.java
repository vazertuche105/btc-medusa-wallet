package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.perseverus.PrivacyReport;
import com.sparrowwallet.perseverus.PrivacyReport.Finding;
import com.sparrowwallet.perseverus.PrivacyReport.Severity;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native JavaFX window rendering a {@link PrivacyReport} (a real am-i-exposed
 * scan result in demo mode): grade + score header, an urgency-coloured primary
 * recommendation banner, a severity ring, the full finding list with
 * descriptions and recommendations, and a transaction summary.
 *
 * <p>Prev/Next (and the Left/Right arrow keys) step through every scanned
 * UTXO's report in place. No WebView, no network I/O — everything is rendered
 * from data already decoded locally during the scan.
 */
public final class PrivacyDashboardWindow {

    private PrivacyDashboardWindow() {}

    public static void show(Window owner, PrivacyReport report) {
        show(owner, List.of(report), 0);
    }

    public static void show(Window owner, List<PrivacyReport> reports, int startIndex) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        final int[] idx = { Math.max(0, Math.min(startIndex, reports.size() - 1)) };

        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        if (owner != null) {
            stage.initOwner(owner);
        }

        VBox root = new VBox(12);
        root.setPadding(new Insets(16, 20, 20, 20));
        root.getStyleClass().add("perseverus-dashboard");

        Button prev = new Button("◀  Prev");
        Button next = new Button("Next  ▶");
        Label counter = new Label();
        counter.setStyle("-fx-font-size: 12px; -fx-opacity: 0.7;");
        Region lspace = new Region();
        Region rspace = new Region();
        HBox.setHgrow(lspace, Priority.ALWAYS);
        HBox.setHgrow(rspace, Priority.ALWAYS);
        HBox nav = new HBox(8, prev, lspace, counter, rspace, next);
        nav.setAlignment(Pos.CENTER);

        VBox content = new VBox(14);
        VBox.setVgrow(content, Priority.ALWAYS);

        Runnable render = () -> {
            PrivacyReport report = reports.get(idx[0]);
            stage.setTitle("Privacy report — " + shortTxid(report) + ":" + report.getTx().vout()
                    + "  (" + (idx[0] + 1) + " of " + reports.size() + ")");
            counter.setText("UTXO " + (idx[0] + 1) + " / " + reports.size());
            prev.setDisable(idx[0] <= 0);
            next.setDisable(idx[0] >= reports.size() - 1);
            content.getChildren().setAll(
                    buildHeader(report),
                    buildRecommendationBanner(report),
                    buildBody(report),
                    new Separator(),
                    buildTxSummary(report));
        };

        prev.setOnAction(e -> { if (idx[0] > 0) { idx[0]--; render.run(); } });
        next.setOnAction(e -> { if (idx[0] < reports.size() - 1) { idx[0]++; render.run(); } });

        root.getChildren().addAll(nav, content);
        render.run();

        Scene scene = new Scene(root, 800, 680);
        scene.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.LEFT) { if (!prev.isDisabled()) prev.fire(); }
            else if (ev.getCode() == KeyCode.RIGHT) { if (!next.isDisabled()) next.fire(); }
        });
        stage.setScene(scene);
        stage.show();
    }

    // ── Header: grade badge + score + profile ────────────────────────────

    private static HBox buildHeader(PrivacyReport report) {
        boolean ungraded = report.isUngraded();
        Label badge = new Label(ungraded ? "N/A" : report.getGrade());
        badge.setMinSize(72, 72);
        badge.setPrefSize(72, 72);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle(
                "-fx-background-color: " + report.getGradeColor() + ";"
                + "-fx-text-fill: white;"
                + "-fx-font-size: " + (ungraded ? "22px;" : "30px;")
                + "-fx-font-weight: bold;"
                + "-fx-background-radius: 10;");

        Label score = new Label(ungraded ? "Not graded" : report.getScore() + " / 100");
        score.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        Label profile = new Label("Transaction type: " + report.getProfile());
        profile.setStyle("-fx-font-size: 13px; -fx-opacity: 0.75;");

        VBox text = new VBox(4, score, profile);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox header = new HBox(18, badge, text);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    // ── Primary recommendation banner (urgency-coloured) ─────────────────

    private static VBox buildRecommendationBanner(PrivacyReport report) {
        String urgency = report.getUrgency() == null ? "" : report.getUrgency();
        Label tag = new Label(urgency.replace('-', ' ').toUpperCase(Locale.ROOT));
        tag.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold; -fx-opacity: 0.9;");

        Label headline = new Label(report.getVerdict());
        headline.setWrapText(true);
        headline.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");

        VBox box = new VBox(3, tag, headline);
        String detail = report.getRecommendationDetail();
        if (detail != null && !detail.isBlank()) {
            Label d = new Label(detail);
            d.setWrapText(true);
            d.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-opacity: 0.92;");
            box.getChildren().add(d);
        }
        box.setPadding(new Insets(12, 14, 12, 14));
        box.setStyle("-fx-background-color: " + report.getUrgencyColor() + "; -fx-background-radius: 8;");
        return box;
    }

    // ── Body: severity ring + findings list ──────────────────────────────

    private static HBox buildBody(PrivacyReport report) {
        HBox body = new HBox(20);
        body.getChildren().add(buildSeverityPanel(report));
        VBox findings = buildFindingsPanel(report);
        HBox.setHgrow(findings, Priority.ALWAYS);
        body.getChildren().add(findings);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private static VBox buildSeverityPanel(PrivacyReport report) {
        Map<Severity, Integer> counts = report.severityCounts();

        PieChart pie = new PieChart();
        pie.setLegendVisible(false);
        pie.setLabelsVisible(false);
        pie.setPrefSize(210, 210);
        pie.setMinSize(210, 210);

        for (Severity sev : Severity.values()) {
            int c = counts.getOrDefault(sev, 0);
            if (c <= 0) continue;
            pie.getData().add(new PieChart.Data(sev.getLabel() + " (" + c + ")", c));
        }
        if (pie.getData().isEmpty()) {
            pie.getData().add(new PieChart.Data("No findings", 1));
        }
        applySliceColours(pie, counts);

        Label title = new Label("Findings by severity");
        title.setStyle("-fx-font-weight: bold;");

        VBox legend = new VBox(4);
        for (Severity sev : Severity.values()) {
            int c = counts.getOrDefault(sev, 0);
            if (c <= 0) continue;
            legend.getChildren().add(legendRow(sev, c));
        }

        VBox box = new VBox(8, title, pie, legend);
        box.setAlignment(Pos.TOP_CENTER);
        box.setMinWidth(230);
        return box;
    }

    private static void applySliceColours(PieChart pie, Map<Severity, Integer> counts) {
        int i = 0;
        for (Severity sev : Severity.values()) {
            int c = counts.getOrDefault(sev, 0);
            if (c <= 0) continue;
            if (i < pie.getData().size()) {
                PieChart.Data slice = pie.getData().get(i);
                final String color = sev.getColor();
                slice.nodeProperty().addListener((obs, oldN, newN) -> {
                    if (newN != null) newN.setStyle("-fx-pie-color: " + color + ";");
                });
                if (slice.getNode() != null) {
                    slice.getNode().setStyle("-fx-pie-color: " + color + ";");
                }
            }
            i++;
        }
    }

    private static HBox legendRow(Severity sev, int count) {
        Region swatch = new Region();
        swatch.setMinSize(12, 12);
        swatch.setPrefSize(12, 12);
        swatch.setStyle("-fx-background-color: " + sev.getColor() + "; -fx-background-radius: 2;");
        Label label = new Label(sev.getLabel() + ": " + count);
        label.setStyle("-fx-font-size: 12px;");
        HBox row = new HBox(6, swatch, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox buildFindingsPanel(PrivacyReport report) {
        List<Finding> findings = report.sortedFindings();
        Label title = new Label("Findings (" + findings.size() + ")");
        title.setStyle("-fx-font-weight: bold;");

        VBox list = new VBox(10);
        list.setPadding(new Insets(2, 4, 2, 0));
        for (Finding f : findings) {
            list.getChildren().add(findingCard(f));
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox box = new VBox(8, title, scroll);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private static VBox findingCard(Finding f) {
        Label chip = new Label(f.severity().getLabel().toUpperCase(Locale.ROOT));
        chip.setStyle(
                "-fx-background-color: " + f.severity().getColor() + ";"
                + "-fx-text-fill: white;"
                + "-fx-font-size: 10px;"
                + "-fx-font-weight: bold;"
                + "-fx-padding: 1 6 1 6;"
                + "-fx-background-radius: 3;");

        Label name = new Label(f.name());
        name.setWrapText(true);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        HBox.setHgrow(name, Priority.ALWAYS);

        HBox top = new HBox(8, chip, name);
        top.setAlignment(Pos.CENTER_LEFT);
        if (f.confidence() != null && !f.confidence().isBlank()) {
            Label conf = new Label(f.confidence() + " confidence");
            conf.setStyle("-fx-font-size: 11px; -fx-opacity: 0.55;");
            top.getChildren().add(conf);
        }

        VBox card = new VBox(4, top);
        if (f.description() != null && !f.description().isBlank()) {
            Label desc = new Label(f.description());
            desc.setWrapText(true);
            desc.setStyle("-fx-font-size: 12px; -fx-opacity: 0.85;");
            card.getChildren().add(desc);
        }
        if (f.recommendation() != null && !f.recommendation().isBlank()) {
            Label rec = new Label("→ " + f.recommendation());
            rec.setWrapText(true);
            rec.setStyle("-fx-font-size: 12px; -fx-font-style: italic; -fx-opacity: 0.75;");
            card.getChildren().add(rec);
        }

        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle(
                "-fx-background-color: -fx-control-inner-background;"
                + "-fx-background-radius: 6;"
                + "-fx-border-color: " + f.severity().getColor() + ";"
                + "-fx-border-width: 0 0 0 3;"
                + "-fx-border-radius: 6;");
        return card;
    }

    // ── Transaction summary footer ───────────────────────────────────────

    private static HBox buildTxSummary(PrivacyReport report) {
        PrivacyReport.TxSummary tx = report.getTx();
        HBox row = new HBox(24);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(
                summaryItem("Value", String.format(Locale.ENGLISH, "%,d sats", tx.valueSats())),
                summaryItem("Inputs", String.valueOf(tx.numInputs())),
                summaryItem("Outputs", String.valueOf(tx.numOutputs())),
                summaryItem("Fee", String.format(Locale.ENGLISH, "%,d sats", tx.feeSats())),
                summaryItem("Block", tx.blockHeight() > 0 ? String.valueOf(tx.blockHeight()) : "Unconfirmed"));

        Label txid = new Label(tx.txid() + ":" + tx.vout());
        txid.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        VBox box = new VBox(6, row, txid);
        return new HBox(box);
    }

    private static VBox summaryItem(String label, String value) {
        Label l = new Label(label.toUpperCase(Locale.ROOT));
        l.setStyle("-fx-font-size: 10px; -fx-opacity: 0.55;");
        Label v = new Label(value);
        v.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        return new VBox(2, l, v);
    }

    private static String shortTxid(PrivacyReport report) {
        String txid = report.getTx().txid();
        return txid != null && txid.length() > 12 ? txid.substring(0, 12) + "…" : txid;
    }
}
