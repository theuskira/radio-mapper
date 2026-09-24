package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.ElevationChain;
import com.colmeia.radiomapper.geo.ElevationSource;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.Utm;
import com.colmeia.radiomapper.model.NetworkPoint;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Locale;

/**
 * A coordenada de um ponto de rede: para levar daqui, ou para trazer de fora.
 *
 * <h3>Por que os dois lados na mesma tela</h3>
 * São o mesmo gesto em sentidos opostos. Quem está montando um ponto pega a
 * coordenada do GPS, do chamado ou do Google Maps e cola aqui; quem já montou
 * copia daqui para mandar ao técnico que vai ao campo. Separar isso em dois
 * lugares obrigaria a decorar em qual deles se está.
 *
 * <h3>Três formatos, não um</h3>
 * Decimal é o que se cola em mapa web. Graus-minutos-segundos é o que muito
 * GPS de campo mostra. UTM é o que aparece em levantamento topográfico e em
 * planta — e é o sistema em que as nuvens de pontos deste programa costumam
 * vir. Converter à mão entre eles na hora do serviço é onde se erra um dígito.
 */
public final class PointCoordDialog {

    private PointCoordDialog() {}

    /**
     * O que fazer quando o usuário confirma uma posição nova.
     *
     * Em modo mapa os dois números são latitude e longitude; em modo imagem
     * são x e y em pixels da imagem de fundo. Quem recebe sabe em qual modo
     * está, porque foi quem abriu esta tela.
     */
    public interface OnMove {
        void accept(double a, double b);
    }

    public static void show(Window owner, NetworkPoint np, boolean mapMode,
                            ElevationChain elevation, OnMove onMove) {

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Coordenadas do ponto");
        dlg.setHeaderText(np.getName());
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        if (!mapMode) {
            mostrarEmPixels(dlg, np, onMove);
            return;
        }

        double lat = Mercator.latOfWorldY(np.getY());
        double lon = Mercator.lonOfWorldX(np.getX());

        Label statusCopia = new Label();
        statusCopia.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 11;");

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        int r = 0;
        g.add(new Label("Decimal:"), 0, r);
        g.add(linhaCopiavel(decimal(lat, lon), statusCopia), 1, r++);
        g.add(new Label("Graus/min/seg:"), 0, r);
        g.add(linhaCopiavel(dms(lat, lon), statusCopia), 1, r++);
        g.add(new Label("UTM:"), 0, r);
        g.add(linhaCopiavel(utm(lat, lon), statusCopia), 1, r++);

        // A altitude do solo não é coordenada, mas é a pergunta que vem logo
        // depois dela em campo — e o programa já tem o relevo carregado.
        String alt = altitude(elevation, np);
        if (alt != null) {
            Label a = new Label(alt);
            a.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
            g.add(new Label("Altitude:"), 0, r);
            g.add(a, 1, r++);
        }

        // ------------------------ Entrada ------------------------
        TextField entrada = new TextField();
        entrada.setPromptText("cole aqui:  -11.013930, -39.302950");
        entrada.setPrefWidth(280);
        Button mover = new Button("Mover o ponto para cá");
        mover.setDisable(true);
        Label erro = new Label();
        erro.setWrapText(true);
        erro.setMaxWidth(440);
        erro.setStyle("-fx-font-size: 11;");

        // Guarda o que foi interpretado, para o botão não precisar reparsear.
        final double[] lido = { Double.NaN, Double.NaN };

        entrada.textProperty().addListener((o, a, b) -> {
            double[] p = interpretar(b);
            if (p == null) {
                lido[0] = lido[1] = Double.NaN;
                mover.setDisable(true);
                erro.setText(b == null || b.isBlank() ? ""
                        : "Não entendi. Aceita \"-11.0139, -39.3029\", "
                          + "\"11°00'50\\\"S 39°18'10\\\"W\" ou o que o Google Maps copia.");
                erro.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
                return;
            }
            lido[0] = p[0];
            lido[1] = p[1];
            mover.setDisable(false);
            double metros = Mercator.groundMeters(np.getX(), np.getY(),
                    Mercator.worldX(p[1]), Mercator.worldY(p[0]));
            erro.setText(String.format(Locale.US,
                    "Entendi %.6f, %.6f — %s daqui.", p[0], p[1], MapPane.formatRange(metros)));
            erro.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 11;");
        });

        mover.setOnAction(e -> {
            if (Double.isNaN(lido[0])) return;
            onMove.accept(lido[0], lido[1]);
            dlg.setResult(ButtonType.CLOSE);
            dlg.close();
        });

        Label ajuda = new Label("No Google Maps, clicar com o botão direito no lugar e escolher "
                + "a primeira linha copia a coordenada no formato que este campo aceita.");
        ajuda.setWrapText(true);
        ajuda.setMaxWidth(440);
        ajuda.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        VBox box = new VBox(10,
                new Label("Onde este ponto está:"), g, statusCopia,
                new Separator(),
                new Label("Levar o ponto para outra coordenada:"),
                new HBox(8, entrada, mover), erro, ajuda);
        box.setPadding(new Insets(12));
        box.setPrefWidth(500);
        dlg.getDialogPane().setContent(box);
        dlg.showAndWait();
    }

    /**
     * Sem mapa base a posição não é geográfica — e nem por isso deixa de ser
     * posição.
     *
     * Quem trabalha só com uma planta ou uma foto aérea sem georreferência
     * ainda precisa anotar onde a torre está, conferir com o desenho e mover
     * para um ponto exato. Recusar a tela aqui só obrigava a arrastar no olho.
     */
    private static void mostrarEmPixels(Dialog<ButtonType> dlg, NetworkPoint np, OnMove onMove) {
        Label statusCopia = new Label();
        statusCopia.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 11;");

        String atual = String.format(Locale.US, "%.0f, %.0f", np.getX(), np.getY());

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.add(new Label("Pixels x, y:"), 0, 0);
        g.add(linhaCopiavel(atual, statusCopia), 1, 0);

        Label explica = new Label("Este projeto está em modo imagem: a posição é medida em "
                + "pixels da imagem de fundo, contados do canto superior esquerdo. "
                + "Com um mapa base ativo, esta mesma tela mostra latitude e longitude.");
        explica.setWrapText(true);
        explica.setMaxWidth(440);
        explica.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        TextField entrada = new TextField();
        entrada.setPromptText("x, y   em pixels");
        entrada.setPrefWidth(280);
        Button mover = new Button("Mover o ponto para cá");
        mover.setDisable(true);
        Label eco = new Label();
        eco.setStyle("-fx-font-size: 11;");

        final double[] lido = { Double.NaN, Double.NaN };
        entrada.textProperty().addListener((o, a, b) -> {
            double[] p = interpretarPixels(b);
            if (p == null) {
                lido[0] = lido[1] = Double.NaN;
                mover.setDisable(true);
                eco.setText(b == null || b.isBlank() ? "" : "Informe dois números: x, y.");
                eco.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
                return;
            }
            lido[0] = p[0];
            lido[1] = p[1];
            mover.setDisable(false);
            double d = Math.hypot(p[0] - np.getX(), p[1] - np.getY());
            eco.setText(String.format(Locale.US, "Entendi x=%.0f, y=%.0f — %.0f px daqui.",
                    p[0], p[1], d));
            eco.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 11;");
        });

        mover.setOnAction(e -> {
            if (Double.isNaN(lido[0])) return;
            onMove.accept(lido[0], lido[1]);
            dlg.setResult(ButtonType.CLOSE);
            dlg.close();
        });

        VBox box = new VBox(10,
                new Label("Onde este ponto está:"), g, statusCopia, explica,
                new Separator(),
                new Label("Levar o ponto para outra posição:"),
                new HBox(8, entrada, mover), eco);
        box.setPadding(new Insets(12));
        box.setPrefWidth(500);
        dlg.getDialogPane().setContent(box);
        dlg.showAndWait();
    }

    /** Dois números soltos, separados por vírgula ou espaço. */
    static double[] interpretarPixels(String bruto) {
        if (bruto == null || bruto.isBlank()) return null;
        String t = bruto.trim().replace("(", "").replace(")", "");
        String[] p = t.contains(",") ? t.split(",", 2) : t.split("\\s+");
        if (p.length < 2) return null;
        try {
            double x = Double.parseDouble(p[0].trim().replace(',', '.'));
            double y = Double.parseDouble(p[1].trim().replace(',', '.'));
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
            return new double[] { x, y };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ------------------------ Formatos ------------------------

    static String decimal(double lat, double lon) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    static String dms(double lat, double lon) {
        return dms1(lat, "N", "S") + "  " + dms1(lon, "E", "W");
    }

    private static String dms1(double v, String pos, String neg) {
        String hemi = v >= 0 ? pos : neg;
        double x = Math.abs(v);
        int gr = (int) x;
        double restoMin = (x - gr) * 60;
        int mi = (int) restoMin;
        double se = (restoMin - mi) * 60;
        // Arredondar os segundos pode estourar para 60; sobe o minuto em vez
        // de escrever 60", que nenhum GPS aceita de volta.
        if (se >= 59.995) { se = 0; mi++; }
        if (mi >= 60) { mi = 0; gr++; }
        return String.format(Locale.US, "%d°%02d'%05.2f\"%s", gr, mi, se, hemi);
    }

    static String utm(double lat, double lon) {
        int zona = Utm.zoneFor(lon);
        boolean sul = lat < 0;
        double[] en = Utm.fromLatLon(lat, lon, zona, sul);
        return String.format(Locale.US, "%d%s  E %.0f  N %.0f",
                zona, sul ? "S" : "N", en[0], en[1]);
    }

    /** Solo sob o ponto, com o nome da fonte — ou null se ninguém sabe. */
    private static String altitude(ElevationChain elevation, NetworkPoint np) {
        if (elevation == null || !elevation.hasAny()) return null;
        Double h = elevation.elevationAt(np.getX(), np.getY());
        if (h == null) return "sem dado de relevo aqui";
        ElevationSource fonte = elevation.sourceAt(np.getX(), np.getY());
        return String.format(Locale.US, "solo %.1f m%s", h,
                fonte == null ? "" : "   (" + fonte.sourceName() + ")");
    }

    // ------------------------ Entrada ------------------------

    /**
     * Interpreta o que o usuário colou.
     *
     * Separa por vírgula ou por espaço e reaproveita o leitor do "Ir para
     * coordenadas", que já entende decimal, DMS e sufixo de hemisfério. Colar
     * do Google Maps precisa funcionar sem edição: é de onde a coordenada vem
     * na maioria das vezes.
     *
     * @return {lat, lon}, ou null quando não dá para entender
     */
    static double[] interpretar(String bruto) {
        if (bruto == null || bruto.isBlank()) return null;
        String t = bruto.trim().replace("(", "").replace(")", "");

        String[] partes = t.contains(",") ? t.split(",", 2) : t.split("\\s{2,}", 2);
        if (partes.length < 2) {
            // "lat lon" com um espaço só, sem vírgula: quebra no meio, o que
            // funciona para decimal puro.
            String[] p = t.split("\\s+");
            if (p.length == 2) partes = p;
            else return null;
        }

        Double la = GoToCoordDialog.parse(partes[0], true);
        Double lo = GoToCoordDialog.parse(partes[1], false);
        if (la == null || lo == null) return null;
        return new double[] { la, lo };
    }

    // ------------------------ Apoio ------------------------

    private static HBox linhaCopiavel(String texto, Label status) {
        TextField campo = new TextField(texto);
        campo.setEditable(false);
        campo.setPrefWidth(280);
        campo.setStyle("-fx-font-family: 'Consolas','Menlo','Monospaced'; -fx-font-size: 12;");
        Button copiar = new Button("Copiar");
        copiar.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(texto);
            Clipboard.getSystemClipboard().setContent(cc);
            campo.selectAll();
            campo.requestFocus();
            status.setText("Copiado: " + texto);
        });
        HBox h = new HBox(6, campo, copiar);
        HBox.setHgrow(campo, Priority.ALWAYS);
        return h;
    }
}
