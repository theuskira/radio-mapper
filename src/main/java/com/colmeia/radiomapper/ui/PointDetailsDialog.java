package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioStatus;
import com.colmeia.radiomapper.model.RadioVendor;
import com.colmeia.radiomapper.util.Log;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.util.function.Function;

public final class PointDetailsDialog {

    private PointDetailsDialog() {}

    public static void show(NetworkPoint np, Window owner, Runnable onChanged) {
        show(np, owner, onChanged, null, null);
    }

    public static void show(NetworkPoint np, Window owner, Runnable onChanged,
                            Function<Radio, Boolean> editor) {
        show(np, owner, onChanged, editor, null);
    }

    /**
     * @param editor  opcional. Se fornecido, é chamado em vez de
     *                {@link RadioDialog#show(Radio, Window)} para abrir a
     *                edição de cada rádio — permite que o caller adicione
     *                comportamento (ex.: preview de feixe no mapa).
     *                Retornar {@code true} indica que houve alteração.
     * @param project opcional. Necessário para listar as estações conectadas,
     *                porque a tela cruza os MACs vizinhos com os rádios
     *                cadastrados para mostrar nome em vez de endereço cru.
     *                Sem ele, o botão de estações não aparece.
     */
    public static void show(NetworkPoint np, Window owner, Runnable onChanged,
                            Function<Radio, Boolean> editor,
                            com.colmeia.radiomapper.model.Project project) {
        Stage s = new Stage();
        s.initOwner(owner);
        s.initModality(Modality.NONE);
        s.setTitle("Ponto: " + np.getName());

        VBox list = new VBox(8);
        list.setPadding(new Insets(12));

        Label header = new Label(np.getName());
        header.setFont(Font.font("System", FontWeight.BOLD, 16));
        Label sub = new Label(np.getRadios().size() + " rádio(s)  ·  posição (" +
                (int) np.getX() + ", " + (int) np.getY() + ")");
        sub.setTextFill(Color.gray(0.5));

        list.getChildren().addAll(header, sub, new Separator());

        if (np.getRadios().isEmpty()) {
            Label empty = new Label("Sem rádios cadastrados neste ponto.");
            empty.setTextFill(Color.gray(0.4));
            list.getChildren().add(empty);
        } else {
            for (Radio r : np.getRadios()) {
                list.getChildren().add(buildRadioRow(r, s, editor, project, () -> {
                    s.close();
                    if (onChanged != null) onChanged.run();
                }));
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);

        Button close = new Button("Fechar");
        close.setOnAction(e -> s.close());
        HBox bar = new HBox(close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8));

        VBox root = new VBox(scroll, bar);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        s.setScene(new Scene(root, 560, 420));
        s.show();
    }

    private static HBox buildRadioRow(Radio r, Window owner,
                                       Function<Radio, Boolean> editor,
                                       com.colmeia.radiomapper.model.Project project,
                                       Runnable onEditRefresh) {
        Circle dot = new Circle(7, colorFor(r.getStatus()));
        dot.setStroke(Color.WHITE);
        dot.setStrokeWidth(1.5);

        String name = (r.getName() == null || r.getName().isBlank()) ? r.getHost() : r.getName();
        Label primary = new Label(name);
        primary.setFont(Font.font("System", FontWeight.BOLD, 13));

        Label vendor = new Label(r.getVendor().toString());
        vendor.setTextFill(Color.gray(0.45));

        Label hostStat = new Label(r.getHost() + "   ·   " + statusLabel(r));
        hostStat.setTextFill(Color.gray(0.35));

        VBox texts = new VBox(2, primary, vendor, hostStat);
        HBox.setHgrow(texts, Priority.ALWAYS);

        // Estações conectadas: o diálogo sonda o rádio na hora e cruza os MACs
        // vizinhos com o cadastro do projeto.
        Button stations = new Button("Estações...");
        stations.setTooltip(new javafx.scene.control.Tooltip(
                "Dispositivos conectados a este rádio, conforme ele mesmo informa"));
        stations.setOnAction(e -> RadioConnectionsDialog.show(r, project, owner));
        boolean semDados = project == null
                || r.getHost() == null || r.getHost().isBlank();
        stations.setDisable(semDados);
        if (project == null) {
            stations.setTooltip(new javafx.scene.control.Tooltip(
                    "Indisponível: esta janela foi aberta sem o projeto."));
        }

        Button open = new Button("Abrir no navegador");
        open.setOnAction(e -> openInBrowser(r));
        open.setDisable(r.getHost() == null || r.getHost().isBlank());

        Button edit = new Button("Editar...");
        edit.setOnAction(e -> {
            boolean changed = (editor != null)
                    ? Boolean.TRUE.equals(editor.apply(r))
                    : RadioDialog.show(r, owner);
            if (changed && onEditRefresh != null) onEditRefresh.run();
        });

        Region spacer = new Region(); spacer.setMinWidth(8);

        HBox row = new HBox(10, dot, texts, spacer, stations, open, edit);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-color: rgba(0,0,0,0.04); -fx-background-radius: 6;");
        return row;
    }

    private static String statusLabel(Radio r) {
        return switch (r.getStatus()) {
            case UP   -> "ONLINE";
            case DOWN -> r.getLastError().isBlank() ? "OFFLINE" : "OFFLINE: " + r.getLastError();
            default   -> "desconhecido";
        };
    }

    private static Color colorFor(RadioStatus s) {
        return switch (s) {
            case UP   -> Color.LIMEGREEN;
            case DOWN -> Color.CRIMSON;
            default   -> Color.GRAY;
        };
    }

    private static void openInBrowser(Radio r) {
        if (r.getHost() == null || r.getHost().isBlank()) return;
        String scheme = (r.getVendor() == RadioVendor.UBIQUITI_AIROS_6
                      || r.getVendor() == RadioVendor.UBIQUITI_AIROS_8) ? "https" : "http";
        String url = scheme + "://" + r.getHost();
        try {
            Desktop.getDesktop().browse(URI.create(url));
            Log.info("Abrindo no navegador: %s", url);
        } catch (Exception ex) {
            Log.warn("Falha ao abrir %s: %s", url, ex.getMessage());
        }
    }
}
