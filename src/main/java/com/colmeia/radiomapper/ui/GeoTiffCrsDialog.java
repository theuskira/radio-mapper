package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.GeoTiffReader;
import com.colmeia.radiomapper.geo.Utm;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Pergunta em que fuso UTM está um GeoTIFF cujo código EPSG não reconhecemos.
 *
 * O catálogo EPSG tem milhares de códigos — só para o Brasil há famílias de
 * SIRGAS 2000, SAD69, SAD69(96), Córrego Alegre e outras, cada uma com um
 * bloco por fuso. Enumerar todos seria enxugar gelo, e chutar poria a imagem
 * no lugar errado sem avisar.
 *
 * A saída é mostrar os números crus do arquivo, propor um fuso a partir de
 * onde o usuário está olhando, e — o que mais importa — <b>mostrar a
 * latitude/longitude que resultaria</b>, para ele conferir antes de aceitar.
 */
public final class GeoTiffCrsDialog {

    private GeoTiffCrsDialog() {}

    /**
     * @param hintLon longitude da área onde o usuário está trabalhando, usada
     *                para sugerir o fuso
     * @return o fuso escolhido, ou null se o usuário desistiu
     */
    public static GeoTiffReader.UtmOverride show(Window owner, String fileName,
                                                 GeoTiffReader.NotGeoreferenced problema,
                                                 double hintLat, double hintLon) {

        double[] b = problema.modelBounds();
        if (b == null) return null;

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Sistema de coordenadas do GeoTIFF");
        dlg.setHeaderText(fileName);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Label motivo = new Label(problema.getMessage());
        motivo.setWrapText(true);
        motivo.setMaxWidth(470);
        motivo.setStyle("-fx-text-fill: #b26500;");

        TextArea crus = new TextArea(String.format("""
                Código EPSG no arquivo: %s
                Coordenadas do arquivo (não interpretadas):
                   X:  %.2f  ..  %.2f
                   Y:  %.2f  ..  %.2f
                   largura: %.0f      altura: %.0f""",
                problema.epsg() == 0 ? "não declarado" : String.valueOf(problema.epsg()),
                b[0], b[2], b[1], b[3], b[2] - b[0], b[3] - b[1]));
        crus.setEditable(false);
        crus.setPrefRowCount(6);
        crus.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Monospaced'; -fx-font-size: 12;");

        Spinner<Integer> zone = new Spinner<>(1, 60, Utm.zoneFor(hintLon), 1);
        zone.setEditable(true);
        zone.setPrefWidth(90);
        ComboBox<String> hemi = new ComboBox<>();
        hemi.getItems().setAll("Sul", "Norte");
        hemi.setValue(hintLat < 0 ? "Sul" : "Norte");

        Label resultado = new Label();
        resultado.setWrapText(true);
        resultado.setMaxWidth(470);

        Runnable preview = () -> {
            int z = zone.getValue() == null ? 23 : zone.getValue();
            boolean sul = "Sul".equals(hemi.getValue());
            double[] sw = Utm.toLatLon(b[0], b[1], z, sul);
            double[] ne = Utm.toLatLon(b[2], b[3], z, sul);
            double distKm = haversine(hintLat, hintLon,
                    (sw[0] + ne[0]) / 2, (sw[1] + ne[1]) / 2);
            resultado.setText(String.format(java.util.Locale.US,
                    "Com este fuso a imagem cairia em:%n"
                    + "   lat %.6f .. %.6f%n   lon %.6f .. %.6f%n"
                    + "Distância até onde você está olhando: %.1f km",
                    Math.min(sw[0], ne[0]), Math.max(sw[0], ne[0]),
                    Math.min(sw[1], ne[1]), Math.max(sw[1], ne[1]), distKm));
            // Perto da área de trabalho = provavelmente certo. Longe = engano.
            resultado.setStyle(distKm < 50
                    ? "-fx-text-fill: #2e7d32; -fx-font-size: 12;"
                    : "-fx-text-fill: #b00020; -fx-font-size: 12;");
        };
        zone.valueProperty().addListener((o, a, c) -> preview.run());
        hemi.valueProperty().addListener((o, a, c) -> preview.run());
        preview.run();

        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(6); g.setPadding(new Insets(10, 0, 4, 0));
        g.add(new Label("Fuso UTM:"), 0, 0); g.add(zone, 1, 0);
        g.add(new Label("Hemisfério:"), 0, 1); g.add(hemi, 1, 1);

        Label dica = new Label("A sugestão vem da área que você está vendo no mapa. "
                + "Confira a latitude/longitude acima antes de aceitar: se a distância "
                + "for grande, o fuso está errado.");
        dica.setWrapText(true);
        dica.setMaxWidth(470);
        dica.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        VBox box = new VBox(10, motivo, crus, g, resultado, new Separator(), dica);
        box.setPadding(new Insets(12));
        box.setPrefWidth(510);
        dlg.getDialogPane().setContent(box);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return null;
        return new GeoTiffReader.UtmOverride(
                zone.getValue() == null ? 23 : zone.getValue(),
                "Sul".equals(hemi.getValue()));
    }

    /** Distância aproximada na superfície, só para dizer "perto" ou "longe". */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
