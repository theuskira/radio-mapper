package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.history.RadioHistory;
import com.colmeia.radiomapper.model.RadioStatus;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Histórico de disponibilidade: em que estado cada rádio está, desde quando,
 * quando esteve online pela última vez e quantas vezes caiu.
 *
 * Selecionar uma linha mostra as transições recentes daquele rádio — é onde
 * se enxerga enlace oscilando, que é o padrão que um resumo esconde.
 */
public final class RadioHistoryDialog {

    private RadioHistoryDialog() {}

    public static void show(Window owner) {
        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Histórico dos rádios");
        dlg.setHeaderText("Disponibilidade registrada pelo Radio Mapper");
        dlg.setResizable(true);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ObservableList<RadioHistory.Entry> items =
                FXCollections.observableArrayList(RadioHistory.INSTANCE.snapshot());
        FilteredList<RadioHistory.Entry> filtered = new FilteredList<>(items, x -> true);

        TableView<RadioHistory.Entry> table = new TableView<>(filtered);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(
                "Nenhum registro ainda. O histórico começa na primeira sincronização."));

        table.getColumns().setAll(List.of(
                col("Rádio", e -> e.name),
                col("Host/IP", e -> e.host),
                colStatus(),
                col("Desde", e -> RadioHistory.stamp(e.since, "-")),
                col("Há", e -> RadioHistory.humanDuration(e.currentDurationSec())),
                col("Última vez online", e -> RadioHistory.stamp(e.lastUpAt, "nunca")),
                col("Último tempo online", e -> e.lastUptimeSec > 0
                        ? RadioHistory.humanDuration(e.lastUptimeSec) : "-"),
                col("Quedas", e -> String.valueOf(e.downCount))));

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setPrefRowCount(9);
        detail.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Monospaced'; -fx-font-size: 12;");
        detail.setPromptText("Selecione um rádio para ver as transições recentes.");

        table.getSelectionModel().selectedItemProperty().addListener((o, a, e) ->
                detail.setText(e == null ? "" : describe(e)));

        TextField filter = new TextField();
        filter.setPromptText("filtrar por nome ou host");
        HBox.setHgrow(filter, Priority.ALWAYS);
        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase();
            filtered.setPredicate(e -> q.isEmpty()
                    || nz(e.name).toLowerCase().contains(q)
                    || nz(e.host).toLowerCase().contains(q));
        });

        CheckBox onlyDown = new CheckBox("Só os que estão offline");
        onlyDown.selectedProperty().addListener((o, a, b) -> {
            String q = filter.getText() == null ? "" : filter.getText().trim().toLowerCase();
            filtered.setPredicate(e -> {
                if (b && e.status != RadioStatus.DOWN) return false;
                return q.isEmpty()
                        || nz(e.name).toLowerCase().contains(q)
                        || nz(e.host).toLowerCase().contains(q);
            });
        });

        Label where = new Label("Arquivo: " + RadioHistory.INSTANCE.fileLocation());
        where.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        Button clear = new Button("Apagar histórico");
        clear.setOnAction(ev -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.initOwner(dlg.getDialogPane().getScene().getWindow());
            a.setTitle("Apagar histórico");
            a.setHeaderText("Apagar todo o histórico de disponibilidade?");
            a.setContentText("Isso remove o registro de " + items.size() + " rádio(s), "
                    + "incluindo tempos de atividade acumulados. Não dá para desfazer, "
                    + "e as próximas notificações vão sair sem \"tempo online\" até "
                    + "haver registro novo.");
            var r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                RadioHistory.INSTANCE.clear();
                items.clear();
                detail.clear();
            }
        });

        Button refresh = new Button("Atualizar");
        refresh.setOnAction(ev -> {
            items.setAll(RadioHistory.INSTANCE.snapshot());
            detail.clear();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox root = new VBox(8,
                new HBox(8, new Label("Filtro:"), filter, onlyDown),
                table,
                new Label("Transições recentes:"),
                detail,
                new HBox(8, refresh, clear, spacer, where));
        root.setPadding(new Insets(10));
        root.setPrefSize(940, 600);
        VBox.setVgrow(table, Priority.ALWAYS);
        dlg.getDialogPane().setContent(root);
        dlg.showAndWait();
    }

    private static String describe(RadioHistory.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(nz(e.name)).append("  (").append(nz(e.host)).append(")\n");
        sb.append("Estado atual: ").append(e.status)
          .append("  desde ").append(RadioHistory.stamp(e.since, "-"))
          .append("  (").append(RadioHistory.humanDuration(e.currentDurationSec())).append(")\n");
        sb.append("Última vez online: ").append(RadioHistory.stamp(e.lastUpAt, "nunca")).append('\n');
        sb.append("Quedas registradas: ").append(e.downCount).append("\n\n");

        if (e.transitions == null || e.transitions.isEmpty()) {
            sb.append("(sem transições registradas)");
            return sb.toString();
        }
        sb.append("Quando                 Mudanca            Durou antes\n");
        sb.append("---------------------------------------------------------\n");
        for (RadioHistory.Transition t : e.transitions) {
            sb.append(String.format("%-22s %-18s %s%n",
                    RadioHistory.stamp(t.at, "-"),
                    t.from + " -> " + t.to,
                    RadioHistory.humanDuration(t.previousDurationSec)));
        }
        return sb.toString();
    }

    private static TableColumn<RadioHistory.Entry, String> col(
            String title, java.util.function.Function<RadioHistory.Entry, String> get) {
        TableColumn<RadioHistory.Entry, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new SimpleStringProperty(nz(get.apply(cd.getValue()))));
        return c;
    }

    /** Status colorido: offline tem que saltar aos olhos numa lista longa. */
    private static TableColumn<RadioHistory.Entry, String> colStatus() {
        TableColumn<RadioHistory.Entry, String> c = new TableColumn<>("Status");
        c.setCellValueFactory(cd -> new SimpleStringProperty(String.valueOf(cd.getValue().status)));
        c.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty ? null : v);
                if (empty || v == null) { setStyle(""); return; }
                setStyle(switch (v) {
                    case "DOWN" -> "-fx-text-fill: #c62828; -fx-font-weight: bold;";
                    case "UP" -> "-fx-text-fill: #2e7d32;";
                    default -> "-fx-text-fill: gray;";
                });
            }
        });
        c.setPrefWidth(90);
        return c;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
