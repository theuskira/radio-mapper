package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.util.Log;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Escolhe quais rádios entram nas notificações de queda.
 *
 * Numa rede com centenas de rádios, marcar um a um é inviável — por isso o
 * filtro por texto combina com "marcar/desmarcar visíveis": filtra por
 * "cliente", desmarca todos os visíveis, pronto.
 *
 * As alterações só vão para o projeto no OK.
 */
public final class MonitoredRadiosDialog {

    private MonitoredRadiosDialog() {}

    /** Linha da tabela: o rádio, onde ele está, e o estado editável do check. */
    public static final class Row {
        private final Radio radio;
        private final String pointName;
        private final SimpleBooleanProperty monitored;

        Row(Radio radio, String pointName) {
            this.radio = radio;
            this.pointName = pointName;
            this.monitored = new SimpleBooleanProperty(radio.isMonitored());
        }

        public SimpleBooleanProperty monitoredProperty() { return monitored; }
        public String getPointName() { return pointName; }
        public String getName() {
            String n = radio.getName();
            return n == null || n.isBlank() ? radio.getHost() : n;
        }
        public String getHost() { return radio.getHost(); }
        public String getStatus() { return String.valueOf(radio.getStatus()); }
    }

    /** @return true se algo mudou e o chamador deve considerar o projeto sujo. */
    public static boolean show(Window owner, Project project) {
        if (project == null) return false;

        List<Row> all = new ArrayList<>();
        for (NetworkPoint p : project.getPoints()) {
            for (Radio r : p.getRadios()) all.add(new Row(r, p.getName()));
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Rádios monitorados");
        dlg.setHeaderText("Quais rádios geram notificação de queda");
        dlg.setResizable(true);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        if (all.isEmpty()) {
            dlg.getDialogPane().setContent(new Label(
                    "Este projeto ainda não tem rádios cadastrados."));
            dlg.showAndWait();
            return false;
        }

        ObservableList<Row> items = FXCollections.observableArrayList(all);
        FilteredList<Row> filtered = new FilteredList<>(items, x -> true);

        TableView<Row> table = new TableView<>(filtered);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Row, Boolean> cMon = new TableColumn<>("Monitorar");
        cMon.setCellValueFactory(c -> c.getValue().monitoredProperty());
        cMon.setCellFactory(CheckBoxTableCell.forTableColumn(cMon));
        cMon.setEditable(true);
        cMon.setPrefWidth(90);
        cMon.setMaxWidth(110);

        TableColumn<Row, String> cName = new TableColumn<>("Rádio");
        cName.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));

        TableColumn<Row, String> cHost = new TableColumn<>("Host/IP");
        cHost.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getHost()));

        TableColumn<Row, String> cPoint = new TableColumn<>("Ponto");
        cPoint.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getPointName()));

        TableColumn<Row, String> cStatus = new TableColumn<>("Status");
        cStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getStatus()));
        cStatus.setPrefWidth(90);

        table.getColumns().setAll(List.of(cMon, cName, cHost, cPoint, cStatus));

        TextField filter = new TextField();
        filter.setPromptText("filtrar por nome, host ou ponto");
        HBox.setHgrow(filter, Priority.ALWAYS);
        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase();
            filtered.setPredicate(row -> q.isEmpty()
                    || row.getName().toLowerCase().contains(q)
                    || row.getHost().toLowerCase().contains(q)
                    || row.getPointName().toLowerCase().contains(q));
        });

        Label count = new Label();
        Runnable updateCount = () -> {
            long on = items.stream().filter(x -> x.monitoredProperty().get()).count();
            count.setText(on + " de " + items.size() + " monitorado(s)"
                    + (filtered.size() < items.size() ? "  —  " + filtered.size() + " visível(is)" : ""));
        };
        items.forEach(row -> row.monitoredProperty().addListener((o, a, b) -> updateCount.run()));
        filtered.addListener((javafx.collections.ListChangeListener<Row>) c -> updateCount.run());
        updateCount.run();

        // Agem só sobre o que o filtro deixou visível: é o que torna viável
        // tratar centenas de radios sem clicar em cada um.
        Button checkVisible = new Button("Marcar visíveis");
        checkVisible.setOnAction(e -> filtered.forEach(x -> x.monitoredProperty().set(true)));
        Button uncheckVisible = new Button("Desmarcar visíveis");
        uncheckVisible.setOnAction(e -> filtered.forEach(x -> x.monitoredProperty().set(false)));

        VBox root = new VBox(8,
                new HBox(8, new Label("Filtro:"), filter),
                table,
                new HBox(8, checkVisible, uncheckVisible, spacer(), count));
        root.setPadding(new Insets(10));
        root.setPrefSize(720, 480);
        VBox.setVgrow(table, Priority.ALWAYS);
        dlg.getDialogPane().setContent(root);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return false;

        int changed = 0;
        for (Row row : items) {
            boolean want = row.monitoredProperty().get();
            if (row.radio.isMonitored() != want) {
                row.radio.setMonitored(want);
                changed++;
            }
        }
        if (changed > 0) {
            long on = items.stream().filter(x -> x.monitoredProperty().get()).count();
            Log.info("Radios monitorados atualizados: %d alterado(s), %d de %d ativos",
                    changed, on, items.size());
        }
        return changed > 0;
    }

    private static javafx.scene.Node spacer() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
