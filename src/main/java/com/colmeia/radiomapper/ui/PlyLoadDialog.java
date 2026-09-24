package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.PlyElevation;
import com.colmeia.radiomapper.geo.PlyGeoref;
import com.colmeia.radiomapper.geo.PlyReader;
import com.colmeia.radiomapper.geo.Utm;
import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * Carrega um levantamento .PLY como fonte de relevo.
 *
 * O PLY não guarda sistema de coordenadas: o arquivo diz "x=712345, y=8781234"
 * e nada mais. Por isso o diálogo primeiro MEDE a extensão do arquivo, mostra
 * os números e propõe um CRS — que o usuário confirma ou corrige. Errar aqui
 * joga o levantamento para outro lugar do planeta, então é melhor mostrar os
 * números e perguntar do que adivinhar em silêncio.
 *
 * A proposta vem da melhor fonte disponível, nesta ordem: o CRS declarado
 * pelo próprio arquivo (comentário do cabeçalho ou .json ao lado, ver
 * {@link PlyGeoref}) e, na falta dele, a ordem de grandeza dos números. A
 * primeira acerta o fuso; a segunda só sabe dizer que "parece UTM".
 */
public final class PlyLoadDialog {

    private PlyLoadDialog() {}

    /** Amostragem do reconhecimento: 1 de cada N pontos, só para medir extensão. */
    private static final int PEEK_STRIDE = 37;

    public interface OnLoaded {
        void accept(PlyElevation elevation);
    }

    public static void show(Window owner, double hintLat, double hintLon, OnLoaded onLoaded) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Carregar levantamento (.PLY)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Polygon File Format", "*.ply"),
                new FileChooser.ExtensionFilter("Todos", "*.*"));
        File file = fc.showOpenDialog(owner);
        if (file == null) return;

        BusyDialog busy = new BusyDialog(owner,
                "Analisando " + file.getName() + "...\nLendo a extensão do levantamento.");

        Task<Object[]> peek = new Task<>() {
            @Override protected Object[] call() throws Exception {
                PlyReader.Header h = PlyReader.peek(file);
                PlyElevation.Summary s = PlyElevation.summarize(file, PEEK_STRIDE);
                return new Object[] { h, s };
            }
        };

        peek.setOnSucceeded(e -> {
            busy.close();
            Object[] res = peek.getValue();
            PlyReader.Header h = (PlyReader.Header) res[0];
            ask(owner, file, h, (PlyElevation.Summary) res[1],
                    PlyGeoref.detect(file, h), hintLat, hintLon, onLoaded);
        });
        peek.setOnFailed(e -> {
            busy.close();
            Throwable ex = peek.getException();
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.initOwner(owner);
            a.setTitle("Falha ao ler o PLY");
            a.setHeaderText("Não consegui interpretar " + file.getName());
            a.setContentText(ex == null ? "erro desconhecido" : ex.getMessage());
            a.showAndWait();
        });

        Thread t = new Thread(peek, "ply-peek");
        t.setDaemon(true);
        t.start();
        busy.show();
    }

    private static void ask(Window owner, File file, PlyReader.Header header,
                            PlyElevation.Summary s, PlyGeoref.Detected det,
                            double hintLat, double hintLon, OnLoaded onLoaded) {

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Levantamento .PLY");
        dlg.setHeaderText(file.getName());
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextArea info = new TextArea(String.format("""
                Vértices declarados: %d
                Formato:             %s
                Amostrados:          %d (1 de cada %d)

                X:  %.3f  ..  %.3f
                Y:  %.3f  ..  %.3f
                Z:  %.2f  ..  %.2f  (altura)""",
                header.vertexCount(), header.format(), s.vertexCount(), PEEK_STRIDE,
                s.minX(), s.maxX(), s.minY(), s.maxY(), s.minZ(), s.maxZ()));
        info.setEditable(false);
        info.setPrefRowCount(9);
        info.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Monospaced'; -fx-font-size: 12;");

        ComboBox<PlyElevation.Crs> crs = new ComboBox<>();
        crs.getItems().setAll(PlyElevation.Crs.values());
        crs.setValue(det != null ? det.crs() : s.guessCrs());
        crs.setPrefWidth(220);

        // Sem declaração no arquivo, o fuso sai da posição atual do mapa —
        // que só acerta se o usuário já estiver olhando para a área do voo.
        int zonePadrao = det != null && det.utmZone() > 0 ? det.utmZone() : Utm.zoneFor(hintLon);
        Spinner<Integer> zone = new Spinner<>(1, 60, zonePadrao, 1);
        zone.setEditable(true);
        zone.setPrefWidth(90);
        ComboBox<String> hemi = new ComboBox<>();
        hemi.getItems().setAll("Sul", "Norte");
        boolean sulPadrao = det != null && det.crs() == PlyElevation.Crs.UTM ? det.south() : hintLat < 0;
        hemi.setValue(sulPadrao ? "Sul" : "Norte");

        var naoUtm = crs.valueProperty().isNotEqualTo(PlyElevation.Crs.UTM);
        zone.disableProperty().bind(naoUtm);
        hemi.disableProperty().bind(naoUtm);

        Spinner<Double> cell = Spinners.decimal(0.05, 50, 1.0, 0.5, 2);

        Label palpite = new Label(det != null
                ? "Detectado: " + PlyGeoref.describe(det) + " — " + det.evidence()
                : "Nada declarado no arquivo. Palpite pelo formato dos números: " + s.guessCrs());
        palpite.setWrapText(true);
        palpite.setMaxWidth(430);
        palpite.setStyle(det != null
                ? "-fx-text-fill: #2e7d32; -fx-font-size: 11;"
                : "-fx-text-fill: #666; -fx-font-size: 11;");

        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(6); g.setPadding(new Insets(10));
        int r = 0;
        g.add(new Label("Coordenadas em:"), 0, r); g.add(crs, 1, r++);
        g.add(palpite, 0, r++, 2, 1);
        g.add(new Label("Fuso UTM:"), 0, r); g.add(zone, 1, r++);
        g.add(new Label("Hemisfério:"), 0, r); g.add(hemi, 1, r++);
        g.add(new Label("Resolução da grade:"), 0, r);
        g.add(new javafx.scene.layout.HBox(6, cell, new Label("metros")), 1, r++);

        String textoAviso = det != null
                ? "O sistema acima veio do próprio arquivo. Confira mesmo assim: errar "
                  + "o sistema joga o relevo para outro lugar do planeta, e a altitude "
                  + "some no mapa."
                : "O PLY não guarda sistema de coordenadas — confira os números acima. "
                  + "Se o levantamento é do mesmo voo da ortofoto, use o mesmo fuso dela. "
                  + "Errar o sistema joga o relevo para outro lugar do planeta, e a "
                  + "altitude some no mapa.";
        if (det != null && det.warning() != null) textoAviso = det.warning() + "\n\n" + textoAviso;
        Label aviso = new Label(textoAviso);
        aviso.setWrapText(true);
        aviso.setMaxWidth(430);
        aviso.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");

        VBox box = new VBox(10, info, g, aviso);
        box.setPadding(new Insets(12));
        box.setPrefWidth(470);
        dlg.getDialogPane().setContent(box);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;

        PlyElevation.Crs chosen = crs.getValue();
        int z = zone.getValue() == null ? 23 : zone.getValue();
        boolean south = "Sul".equals(hemi.getValue());
        double cellM = Spinners.commit(cell, 1.0);

        BusyDialog busy = new BusyDialog(owner,
                "Carregando " + file.getName() + "...\nMilhões de pontos podem levar um minuto.");

        Task<PlyElevation> load = new Task<>() {
            @Override protected PlyElevation call() throws Exception {
                // stride 1: aqui queremos a nuvem inteira, nao a amostra.
                return PlyElevation.load(file, chosen, z, south, cellM, 1);
            }
        };
        load.setOnSucceeded(e -> {
            busy.close();
            PlyElevation pe = load.getValue();
            onLoaded.accept(pe);
        });
        load.setOnFailed(e -> {
            busy.close();
            Throwable ex = load.getException();
            Log.warn("Falha ao carregar PLY: %s", ex == null ? "?" : ex.getMessage());
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.initOwner(owner);
            a.setTitle("Falha ao carregar");
            a.setHeaderText("Não consegui carregar " + file.getName());
            a.setContentText(ex == null ? "erro desconhecido" : ex.getMessage());
            a.showAndWait();
        });

        Thread t = new Thread(load, "ply-load");
        t.setDaemon(true);
        t.start();
        Platform.runLater(busy::show);
    }
}
