package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.RasterInfo;
import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * "O que tem dentro deste arquivo?"
 *
 * Existe porque ortofoto e modelo de elevação são ambos ".tif", e só um deles
 * serve para calcular relevo. Sem uma resposta objetiva, o usuário carregaria
 * uma foto aérea esperando perfil de terreno e veria só uma linha reta.
 */
public final class RasterInfoDialog {

    private RasterInfoDialog() {}

    public static void show(Window owner, File preset) {
        File f = preset;
        if (f == null) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Analisar arquivo raster");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Raster", "*.tif", "*.tiff", "*.png", "*.jpg"),
                    new FileChooser.ExtensionFilter("Todos", "*.*"));
            f = fc.showOpenDialog(owner);
            if (f == null) return;
        }

        final File file = f;
        BusyDialog busy = new BusyDialog(owner, "Analisando " + file.getName() + "...");

        Task<RasterInfo.Result> task = new Task<>() {
            @Override protected RasterInfo.Result call() throws Exception {
                return RasterInfo.inspect(file);
            }
        };

        task.setOnSucceeded(e -> {
            busy.close();
            render(owner, file, task.getValue());
        });
        task.setOnFailed(e -> {
            busy.close();
            Throwable ex = task.getException();
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.initOwner(owner);
            a.setTitle("Falha na análise");
            a.setHeaderText("Não consegui ler " + file.getName());
            a.setContentText(ex == null ? "erro desconhecido" : ex.getMessage());
            a.showAndWait();
        });

        Thread t = new Thread(task, "raster-inspect");
        t.setDaemon(true);
        t.start();
        busy.show();
    }

    private static void render(Window owner, File file, RasterInfo.Result r) {
        Log.info("Analise de %s: %s, %dx%d, %d banda(s), %d bits, valores %.1f..%.1f",
                file.getName(), r.kind().name(), r.width(), r.height(),
                r.bands(), r.bitDepth(), r.min(), r.max());

        Label verdict = new Label(r.kind().toString());
        verdict.setStyle("-fx-font-weight: bold; -fx-font-size: 14; -fx-text-fill: "
                + switch (r.kind()) {
                    case ELEVATION -> "#2e7d32;";
                    case PHOTO -> "#1565c0;";
                    default -> "#b26500;";
                });

        Label why = new Label(r.explanation());
        why.setWrapText(true);
        why.setMaxWidth(520);

        TextArea details = new TextArea(String.format("""
                Arquivo:        %s
                Dimensões:      %d x %d pixels
                Bandas:         %d
                Bits/amostra:   %d %s
                Valor mínimo:   %.3f
                Valor máximo:   %.3f
                Amostras lidas: %d
                Sem dado:       %d
                Georreferência: %s""",
                file.getAbsolutePath(), r.width(), r.height(), r.bands(),
                r.bitDepth(), r.floatingPoint() ? "(ponto flutuante)" : (r.signed() ? "(com sinal)" : ""),
                r.min(), r.max(), r.sampled(), r.nodataCount(), r.crs()));
        details.setEditable(false);
        details.setPrefRowCount(10);
        details.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Monospaced'; -fx-font-size: 12;");

        Label next = new Label(switch (r.kind()) {
            case ELEVATION -> "Este arquivo serve como fonte de relevo.";
            case PHOTO -> "Use como imagem de fundo. Para o perfil de relevo, carregue "
                    + "à parte um MDT (pode ser SRTM ou Copernicus, gratuitos) ou um .PLY "
                    + "do levantamento de drone.";
            case GRAYSCALE -> "Provavelmente não serve para relevo. Se a origem for um MDT, "
                    + "peça o arquivo original em 16 bits ou float — a conversão para 8 bits "
                    + "destrói a escala em metros.";
            case UNKNOWN -> "Preciso ver esses números para dizer o que dá para fazer.";
        });
        next.setWrapText(true);
        next.setMaxWidth(520);
        next.setStyle("-fx-text-fill: #555;");

        VBox box = new VBox(10, verdict, why, details, new Separator(), next);
        box.setPadding(new Insets(14));
        box.setPrefWidth(560);

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Análise do arquivo");
        dlg.setHeaderText(file.getName());
        dlg.setResizable(true);
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Platform.runLater(dlg::showAndWait);
    }
}
