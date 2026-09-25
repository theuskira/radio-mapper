package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.PlyElevation;
import com.colmeia.radiomapper.geo.PlyCheck;
import com.colmeia.radiomapper.geo.PlyGeoref;
import com.colmeia.radiomapper.geo.PlyReader;
import com.colmeia.radiomapper.geo.TerrainTiles;
import com.colmeia.radiomapper.geo.Utm;
import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBar;
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
        /**
         * @param afericao o que a comparacao com o relevo de referencia achou,
         *                 ou null se nao houve segunda opiniao. Vem junto para
         *                 quem adota a nuvem poder mostrar o veredito depois,
         *                 sem ter de refazer a conta.
         */
        void accept(PlyElevation elevation, PlyCheck.Resultado afericao);
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

        // O tipo declarado no arquivo limita a resolucao util. Coordenada em
        // float perto de um northing UTM de milhoes so representa degraus de
        // ~1 m: pedir grade mais fina que o degrau nao traz detalhe nenhum,
        // traz listra vazia entre as linhas onde os pontos caem. O piso do
        // spinner passa a ser o degrau, para o numero na tela nao prometer o
        // que o arquivo nao tem.
        double passo = s.xyStepMeters(crs.getValue());
        double piso = Math.max(0.05, passo);
        Spinner<Double> cell = Spinners.decimal(piso, 50, Math.max(piso, 1.0), 0.5, 2);

        Label avisoPasso = new Label();
        avisoPasso.setWrapText(true);
        avisoPasso.setMaxWidth(430);
        avisoPasso.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
        Runnable mostrarPasso = () -> {
            double p = s.xyStepMeters(crs.getValue());
            // O piso acompanha o sistema escolhido, senao trocar para graus
            // deixaria o spinner aceitar um numero que a confirmacao depois
            // corrigiria por baixo do pano.
            if (cell.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f) {
                double novoPiso = Math.max(0.05, p);
                f.setMin(novoPiso);
                if (f.getValue() != null && f.getValue() < novoPiso) f.setValue(novoPiso);
            }
            boolean grosso = p > 0.15;
            avisoPasso.setManaged(grosso);
            avisoPasso.setVisible(grosso);
            if (!grosso) { avisoPasso.setText(""); return; }
            avisoPasso.setText(String.format(
                    "As coordenadas deste arquivo estao gravadas em '%s' e, na magnitude "
                    + "em que estao, so conseguem representar degraus de %.2f m. O "
                    + "levantamento nao e mais fino que isso, por mais denso que pareca: "
                    + "os pontos chegam arredondados, e uma grade mais fina que o degrau "
                    + "so rende listras vazias entre as linhas onde eles caem. A resolucao "
                    + "minima ficou travada ai. Para recuperar o detalhe, gere o .PLY com "
                    + "coordenadas em 'double', ou subtraia uma origem local antes de "
                    + "gravar em 'float'.",
                    tipoXY(header), p));
        };
        mostrarPasso.run();
        // O degrau em metros depende do sistema: em graus ele vale 111 km a mais.
        crs.valueProperty().addListener((o, a1, b1) -> mostrarPasso.run());

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
        g.add(avisoPasso, 0, r++, 2, 1);

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
        // O piso do spinner foi montado com o CRS palpitado; se o usuario
        // trocou o sistema no meio, a conversao do degrau para metros mudou
        // junto. Vale o do sistema que ele confirmou.
        double cellM = Math.max(Math.max(0.05, s.xyStepMeters(chosen)),
                                Spinners.commit(cell, 1.0));

        BusyDialog busy = new BusyDialog(owner,
                "Carregando " + file.getName() + "...\nMilhões de pontos podem levar um minuto.");

        Task<Object[]> load = new Task<>() {
            @Override protected Object[] call() throws Exception {
                // stride 1: aqui queremos a nuvem inteira, nao a amostra.
                PlyElevation pe = PlyElevation.load(file, chosen, z, south, cellM, 1);

                // Segunda opiniao sobre a altitude. O relevo global e grosseiro,
                // mas e independente do levantamento — e e justamente isso que
                // permite ver deformacao que a nuvem nao denuncia sozinha.
                PlyCheck.Resultado af = null;
                if (TerrainTiles.INSTANCE.isEnabled()) {
                    double[] b = pe.worldBounds();
                    TerrainTiles.INSTANCE.prefetch(b[0], b[1], b[2], b[3], 30000);
                    af = PlyCheck.aferir(pe, TerrainTiles.INSTANCE, 40);
                }
                return new Object[] { pe, af };
            }
        };
        load.setOnSucceeded(e -> {
            busy.close();
            Object[] res2 = load.getValue();
            PlyElevation pe = (PlyElevation) res2[0];
            PlyCheck.Resultado af = (PlyCheck.Resultado) res2[1];
            if (af != null && !af.aprovado() && !confirmarApesarDaAfericao(owner, file, af)) {
                Log.info("Levantamento %s descartado pelo usuario apos a afericao", file.getName());
                return;
            }
            onLoaded.accept(pe, af);
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

    /** Tipo declarado para x/y, para o aviso dizer de onde vem o degrau. */
    private static String tipoXY(PlyReader.Header h) {
        String t = h.vertexTypes() == null ? null : h.vertexTypes().get("x");
        return t == null ? "?" : t;
    }

    /**
     * A nuvem discorda do relevo de referencia. Mostra o quanto e deixa decidir.
     *
     * A nuvem NAO e alterada em nenhum caso — nem aqui, nem depois. O que esta
     * em jogo e so se ela entra na cadeia de altitude, porque, entrando, ela
     * passa na frente de todas as outras fontes e e a altura dela que vai para
     * o perfil do enlace.
     */
    private static boolean confirmarApesarDaAfericao(Window owner, File file,
                                                     PlyCheck.Resultado af) {
        Alert a = new Alert(Alert.AlertType.WARNING);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Afericao do levantamento");
        a.setHeaderText(file.getName() + ": " + af.veredito());

        TextArea nums = new TextArea(PlyCheck.descrever(af));
        nums.setEditable(false);
        nums.setPrefRowCount(9);
        nums.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Monospaced'; -fx-font-size: 12;");

        Label expl = new Label(
                "Erro grande de altitude costuma vir de bloco ajustado so pelo GPS das "
                + "fotos, sem ponto de controle e sem PPK: a superficie fica limpa de "
                + "perto e arqueada ao longo do voo. A nuvem nao denuncia isso sozinha, "
                + "porque e coerente consigo mesma.\n\n"
                + "Usando assim, a altitude da nuvem passa na frente de todas as outras "
                + "fontes, e o perfil do enlace vai enxergar o relevo acima — inclusive "
                + "onde ele discorda. Nada na nuvem sera alterado de um jeito ou de outro.");
        expl.setWrapText(true);
        expl.setMaxWidth(470);
        expl.setStyle("-fx-font-size: 11;");

        VBox box = new VBox(10, nums, expl);
        box.setPadding(new Insets(4));
        box.setPrefWidth(500);
        a.getDialogPane().setContent(box);

        ButtonType usar = new ButtonType("Usar assim mesmo", ButtonBar.ButtonData.OK_DONE);
        ButtonType nao = new ButtonType("Nao carregar", ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(usar, nao);
        return a.showAndWait().orElse(nao) == usar;
    }
}
