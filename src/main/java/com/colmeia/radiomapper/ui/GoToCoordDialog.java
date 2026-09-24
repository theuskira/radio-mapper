package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.Mercator;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * "Ir para coordenadas" — leva a viewport para uma latitude/longitude.
 *
 * Aceita tanto grau decimal ({@code -23.5505}) quanto grau/minuto/segundo
 * ({@code 23°33'01.8"S}), porque relatório de torre e GPS de campo costumam
 * vir em DMS e ninguém quer converter na mão.
 */
public final class GoToCoordDialog {

    /** Destino escolhido pelo usuário. */
    public record Target(double lat, double lon, int zoom) {}

    private GoToCoordDialog() {}

    public static Target show(Window owner, double currentLat, double currentLon, int currentZoom) {
        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Ir para coordenadas");
        dlg.setHeaderText("Latitude e longitude (decimal ou DMS)");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField lat = new TextField(String.format(java.util.Locale.US, "%.6f", currentLat));
        TextField lon = new TextField(String.format(java.util.Locale.US, "%.6f", currentLon));
        Spinner<Integer> zoom = new Spinner<>();
        zoom.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                2, 21, Math.max(2, Math.min(21, currentZoom)), 1));
        zoom.setEditable(true);
        zoom.setPrefWidth(90);

        Label error = new Label();
        error.setStyle("-fx-text-fill: #b00020; -fx-font-size: 11;");

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(10));
        int r = 0;
        g.add(new Label("Latitude:"), 0, r);  g.add(lat, 1, r++);
        g.add(new Label("Longitude:"), 0, r); g.add(lon, 1, r++);
        g.add(new Label("Zoom:"), 0, r);      g.add(zoom, 1, r++);
        g.add(error, 0, r, 2, 1);
        dlg.getDialogPane().setContent(g);

        Button ok = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            Double la = parse(lat.getText(), true);
            Double lo = parse(lon.getText(), false);
            if (la == null || lo == null) {
                error.setText("Não entendi as coordenadas. Ex: -23.5505 / 23°33'01.8\"S");
                ev.consume();
            }
        });

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return null;

        Double la = parse(lat.getText(), true);
        Double lo = parse(lon.getText(), false);
        if (la == null || lo == null) return null;
        return new Target(Mercator.clampLat(la), Mercator.clampLon(lo), zoom.getValue());
    }

    /**
     * Converte texto em grau decimal. Devolve {@code null} quando não dá para
     * interpretar — quem chama decide o que fazer.
     *
     * @param isLat usado só para validar a faixa (±90 vs ±180)
     */
    static Double parse(String raw, boolean isLat) {
        if (raw == null) return null;
        String s = raw.trim().replace(',', '.');
        if (s.isEmpty()) return null;

        // Hemisfério pode vir como sufixo (12.34S) ou prefixo (S 12.34).
        int sign = 1;
        String upper = s.toUpperCase(java.util.Locale.ROOT);
        for (String neg : new String[]{"S", "W", "O"}) {   // O = Oeste, em português
            if (upper.startsWith(neg) || upper.endsWith(neg)) { sign = -1; break; }
        }
        String body = upper.replaceAll("[NSEWO]", "").trim();

        Double value = parseDecimal(body);
        if (value == null) value = parseDms(body);
        if (value == null) return null;

        // Um sinal negativo explícito já resolve; não deixa "-12S" virar +12.
        double out = body.trim().startsWith("-") ? value : Math.abs(value) * sign;
        double limit = isLat ? 90 : 180;
        if (out < -limit || out > limit) return null;
        return out;
    }

    private static Double parseDecimal(String body) {
        try {
            return Double.valueOf(body);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Aceita 23 33 01.8, 23°33'01.8", 23:33:01.8 e variações com só graus+minutos. */
    private static Double parseDms(String body) {
        String[] parts = body.split("[^0-9.\\-]+");
        java.util.List<Double> nums = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            try { nums.add(Double.valueOf(p)); } catch (NumberFormatException ignored) { return null; }
        }
        if (nums.isEmpty() || nums.size() > 3) return null;

        double deg = nums.get(0);
        double min = nums.size() > 1 ? nums.get(1) : 0;
        double sec = nums.size() > 2 ? nums.get(2) : 0;
        if (min < 0 || min >= 60 || sec < 0 || sec >= 60) return null;

        double mag = Math.abs(deg) + min / 60.0 + sec / 3600.0;
        return deg < 0 ? -mag : mag;
    }
}
