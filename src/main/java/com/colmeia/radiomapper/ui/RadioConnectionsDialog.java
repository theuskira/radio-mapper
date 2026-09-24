package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.ssh.NeighborInfo;
import com.colmeia.radiomapper.ssh.RadioProbe;
import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * Mostra a lista de dispositivos (vizinhos) que estão conectados ao rádio
 * selecionado, conforme informado pelo próprio rádio via probe SSH.
 * Para cada vizinho exibe: nome, MAC e último IP — completando com dados
 * do projeto quando o MAC é conhecido.
 */
public final class RadioConnectionsDialog {

    private RadioConnectionsDialog() {}

    public static void show(Radio radio, Project project, Window owner) {
        Stage s = new Stage();
        s.initOwner(owner);
        s.initModality(Modality.NONE);
        String radioLabel = (radio.getName() == null || radio.getName().isBlank())
                ? radio.getHost() : radio.getName();
        s.setTitle("Conectados a: " + radioLabel);

        Label header = new Label(radioLabel);
        header.setFont(Font.font("System", FontWeight.BOLD, 16));
        Label sub = new Label(radio.getVendor() + "  ·  " + radio.getHost());
        sub.setTextFill(Color.gray(0.45));

        TableView<Row> table = new TableView<>();
        table.setPlaceholder(new Label("Sondando rádio..."));

        TableColumn<Row, String> cName = new TableColumn<>("Nome");
        cName.setCellValueFactory(new PropertyValueFactory<>("name"));
        cName.setPrefWidth(180);

        TableColumn<Row, String> cMac = new TableColumn<>("MAC");
        cMac.setCellValueFactory(new PropertyValueFactory<>("mac"));
        cMac.setPrefWidth(140);

        TableColumn<Row, String> cIp = new TableColumn<>("Último IP");
        cIp.setCellValueFactory(new PropertyValueFactory<>("lastIp"));
        cIp.setPrefWidth(120);

        TableColumn<Row, String> cSignal = new TableColumn<>("Sinal");
        cSignal.setCellValueFactory(new PropertyValueFactory<>("signal"));
        cSignal.setPrefWidth(80);

        TableColumn<Row, String> cRate = new TableColumn<>("TX/RX (Mbps)");
        cRate.setCellValueFactory(new PropertyValueFactory<>("rate"));
        cRate.setPrefWidth(110);

        table.getColumns().addAll(cName, cMac, cIp, cSignal, cRate);
        VBox.setVgrow(table, Priority.ALWAYS);

        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(16, 16);
        spin.setVisible(false);

        Label status = new Label("");
        status.setTextFill(Color.gray(0.45));

        Button refresh = new Button("Atualizar");
        Button close = new Button("Fechar");
        close.setOnAction(e -> s.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, spin, status, spacer, refresh, close);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8));

        VBox root = new VBox(8, header, sub, table, bottom);
        root.setPadding(new Insets(12));

        refresh.setOnAction(e -> probe(radio, project, table, status, spin, refresh));

        s.setScene(new Scene(root, 720, 460));
        s.show();

        // Sonda assim que a tela abre.
        probe(radio, project, table, status, spin, refresh);
    }

    private static void probe(Radio radio, Project project, TableView<Row> table,
                              Label status, ProgressIndicator spin, Button refresh) {
        spin.setVisible(true);
        refresh.setDisable(true);
        status.setText("Sondando " + radio.getHost() + "...");
        table.setPlaceholder(new Label("Sondando rádio..."));

        Thread t = new Thread(() -> {
            String err = null;
            List<NeighborInfo> neighbors = List.of();
            try {
                neighbors = RadioProbe.forRadio(radio).probe(radio);
            } catch (Exception ex) {
                err = ex.getMessage();
                Log.warn("Falha ao sondar %s @ %s: %s",
                        radio.getVendor(), radio.getHost(), err);
            }
            final String fErr = err;
            final List<NeighborInfo> fNeighbors = neighbors;
            Platform.runLater(() -> {
                spin.setVisible(false);
                refresh.setDisable(false);
                if (fErr != null) {
                    status.setText("Falha: " + fErr);
                    table.setItems(FXCollections.observableArrayList());
                    table.setPlaceholder(new Label("Sem dados (falha na sondagem)."));
                    return;
                }
                ObservableList<Row> rows = FXCollections.observableArrayList();
                for (NeighborInfo n : fNeighbors) rows.add(toRow(n, project));
                table.setItems(rows);
                table.setPlaceholder(new Label("Nenhum dispositivo conectado."));
                status.setText(fNeighbors.size() + " conectado(s).");
            });
        }, "radio-connections-probe");
        t.setDaemon(true);
        t.start();
    }

    private static Row toRow(NeighborInfo n, Project project) {
        String name = n.name;
        String lastIp = n.lastIp;
        // Se o MAC corresponde a um rádio do projeto, usa esses dados como
        // fallback / enriquecimento (o radio próprio costuma ter dados mais
        // confiáveis que os relatados pelo vizinho).
        Radio matched = project == null ? null
                : project.findRadioByMac(n.mac).orElse(null);
        if (matched != null) {
            if (name == null || name.isBlank()) {
                name = matched.getName() == null || matched.getName().isBlank()
                        ? matched.getHost() : matched.getName();
            }
            if (lastIp == null || lastIp.isBlank()) lastIp = matched.getHost();
            NetworkPoint pt = project.findPointOfRadio(matched.getId()).orElse(null);
            if (pt != null && (name == null || name.isBlank())) name = pt.getName();
            else if (pt != null) name = name + "  [" + pt.getName() + "]";
        }
        if (name == null) name = "";
        if (lastIp == null) lastIp = "";

        String signal = n.signalDbm == 0 ? "—" : (n.signalDbm + " dBm");
        String rate;
        if (n.txMbps > 0 || n.rxMbps > 0) {
            rate = n.txMbps + " / " + n.rxMbps;
        } else {
            rate = "—";
        }
        return new Row(name, n.mac, lastIp, signal, rate);
    }

    /** POJO público para o TableView (PropertyValueFactory exige getters públicos). */
    public static class Row {
        private final SimpleStringProperty name;
        private final SimpleStringProperty mac;
        private final SimpleStringProperty lastIp;
        private final SimpleStringProperty signal;
        private final SimpleStringProperty rate;

        public Row(String name, String mac, String lastIp, String signal, String rate) {
            this.name = new SimpleStringProperty(name);
            this.mac = new SimpleStringProperty(mac);
            this.lastIp = new SimpleStringProperty(lastIp);
            this.signal = new SimpleStringProperty(signal);
            this.rate = new SimpleStringProperty(rate);
        }

        public String getName() { return name.get(); }
        public String getMac() { return mac.get(); }
        public String getLastIp() { return lastIp.get(); }
        public String getSignal() { return signal.get(); }
        public String getRate() { return rate.get(); }
    }
}
