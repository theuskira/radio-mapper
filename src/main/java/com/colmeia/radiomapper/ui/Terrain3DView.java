package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.ElevationChain;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.TerrainTiles;
import com.colmeia.radiomapper.geo.TileCache;
import com.colmeia.radiomapper.geo.TileSource;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.util.Log;
import com.colmeia.radiomapper.util.Settings;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.CheckBox;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * O terreno em três dimensões, para ver o obstáculo como ele é.
 *
 * <h3>Por que existe</h3>
 * O perfil lateral responde "o morro corta?" com precisão, mas só ao longo de
 * uma linha. O mapa mostra a área toda, visto de cima, onde relevo não
 * aparece. Entre os dois fica a pergunta que o instalador faz olhando do alto
 * da torre: <i>onde exatamente está o morro, e o que tem atrás dele?</i> É
 * essa que esta janela responde.
 *
 * <h3>Como a malha é montada</h3>
 * A grade de altitude vira uma {@link TriangleMesh}: um vértice por amostra,
 * dois triângulos por célula. Amostrar a nuvem inteira daria milhões de
 * vértices e nenhuma placa de vídeo agradeceria, então a área escolhida é
 * reamostrada num quadriculado fixo — detalhe suficiente para enxergar o
 * morro, leve o bastante para girar sem engasgar.
 *
 * A cor vem da altitude, por textura: uma faixa de 256 tons é desenhada uma
 * vez e cada vértice aponta para a posição dela que corresponde à sua cota.
 * Sai mais barato que colorir triângulo a triângulo e dá gradiente contínuo.
 *
 * <h3>Exagero vertical</h3>
 * Um enlace de 2 km com 60 m de desnível é, em escala real, quase uma placa
 * plana: o relevo que decide o enlace desaparece. Todo programa de topografia
 * exagera a vertical por isso, e aqui o fator fica à mão — com o aviso de que
 * o que se vê está esticado.
 */
public final class Terrain3DView {

    private Terrain3DView() {}

    /**
     * O alcance simulado de um rádio, pronto para tingir o relevo.
     *
     * Entra na janela inteiro, e quem decide o que aparece é a própria janela
     * — perguntar antes de abrir obrigava a fechar e reabrir só para comparar
     * um rádio com o outro, que é justamente o que se quer fazer aqui.
     *
     * @param faixas empacotamento do mapa: dBm na posição 0, polígono adiante
     */
    public record Cobertura(String nome,
                            com.colmeia.radiomapper.rf.BeamCoverage.Cobertura grade) {}

    /**
     * Quanto detalhe desenhar, e quanto esperar por ele.
     *
     * Os dois números que mandam no custo: quantos vértices tem a malha e
     * quantos pixels tem a textura vestida nela. Ambos crescem ao quadrado,
     * então dobrar o lado quadruplica o trabalho — e o trabalho não é só o
     * da abertura: cada vez que se liga um alcance ou a área da nuvem, a
     * textura inteira é repintada.
     *
     * Por isso é escolha do usuário, e não um número fixo: numa máquina
     * modesta, ou num projeto grande, esperar meio segundo a cada clique numa
     * caixa custa mais do que o detalhe entrega.
     */
    public enum Qualidade {
        RASCUNHO("Rascunho", 160, 1024),
        MEDIA("M\u00e9dia", 240, 1536),
        ALTA("Alta", 320, 2048),
        MAXIMA("M\u00e1xima", 460, 3072);

        private final String rotulo;
        private final int malha, fotoPx;

        Qualidade(String rotulo, int malha, int fotoPx) {
            this.rotulo = rotulo; this.malha = malha; this.fotoPx = fotoPx;
        }

        /** Vértices por lado da malha do relevo. */
        public int malha() { return malha; }

        /** Lado da textura montada, em pixels. */
        public int fotoPx() { return fotoPx; }

        @Override public String toString() { return rotulo; }
    }

    /** Margem em volta da área de interesse, como fração do lado. */
    private static final double FOLGA = 0.35;

    /**
     * Tamanho dos símbolos, como fração do lado da área desenhada.
     *
     * Símbolo tem que ser legível em qualquer zoom, mas não pode passar por
     * cima do que está ali para ser lido. Com os valores antigos, numa área de
     * 1,3 km a bola do ponto saía com 48 m de diâmetro e a linha do enlace com
     * 20 m de espessura — uma linha mais grossa que a torre é alta, cobrindo
     * justamente o relevo entre as duas pontas, que é o que se foi olhar.
     *
     * Os números abaixo dão, nessa mesma área e na vista de abertura, uma bola
     * de ~10 px, um mastro de ~3 px e uma linha de ~4 px: visíveis de relance,
     * e finos o bastante para se enxergar o terreno por baixo.
     */
    private static final double ESFERA = 1 / 170.0;
    private static final double MASTRO = 1 / 500.0;
    private static final double LINHA = 1 / 420.0;

    /**
     * @param coberturas alcance simulado dos rádios da cena. Entram todos; a
     *                   própria janela escolhe quais desenhar. Marcando mais
     *                   de um, cada lugar fica com o MELHOR sinal — que é o
     *                   que a rede entrega ali, e é diferente de olhar um
     *                   rádio de cada vez. Null ou vazio desenha só o relevo.
     */
    public static void show(Window owner, ElevationChain elevation,
                            NetworkPoint a, Radio radioA,
                            NetworkPoint b, Radio radioB,
                            List<Cobertura> coberturas) {
        show(owner, elevation, a, radioA, b, radioB, coberturas, null, null, null);
    }

    /**
     * @param base mapa base, usado onde a ortofoto não alcança
     * @param orto ortofoto do projeto, já carregada. Tem prioridade sobre o
     *             mapa base: é o levantamento do próprio voo, na resolução do
     *             voo, e do mesmo dia da nuvem de pontos — enquanto o satélite
     *             é de outra data e de resolução bem mais grossa.
     * @param pos  onde a ortofoto se apoia no mundo (canto noroeste e tamanho)
     */
    public static void show(Window owner, ElevationChain elevation,
                            NetworkPoint a, Radio radioA,
                            NetworkPoint b, Radio radioB,
                            List<Cobertura> coberturas, TileSource base,
                            List<Image> ortos,
                            List<com.colmeia.radiomapper.model.ImageLayer> camadas) {
        show(owner, elevation, a, radioA, b, radioB, coberturas, base, ortos, camadas, null);
    }

    /**
     * @param contornoNuvem anéis do levantamento .PLY, em coordenadas de
     *                      mundo. Marcam onde a altitude é centimétrica; fora
     *                      deles o relevo vem da fonte global, com ~30 m de
     *                      resolução — e no 3D, onde tudo vira a mesma
     *                      superfície lisa, não há outro jeito de distinguir.
     */
    public static void show(Window owner, ElevationChain elevation,
                            NetworkPoint a, Radio radioA,
                            NetworkPoint b, Radio radioB,
                            List<Cobertura> coberturas, TileSource base,
                            List<Image> ortos,
                            List<com.colmeia.radiomapper.model.ImageLayer> camadas,
                            List<double[]> contornoNuvem) {

        if (a == null) return;

        // Caixa a modelar: os dois pontos com folga, ou um quadrado em volta
        // do ponto único.
        double minX, maxX, minY, maxY;
        if (b != null) {
            minX = Math.min(a.getX(), b.getX());
            maxX = Math.max(a.getX(), b.getX());
            minY = Math.min(a.getY(), b.getY());
            maxY = Math.max(a.getY(), b.getY());
        } else {
            double raio = 800 / Mercator.groundScaleAt(Mercator.latOfWorldY(a.getY()));
            minX = a.getX() - raio; maxX = a.getX() + raio;
            minY = a.getY() - raio; maxY = a.getY() + raio;
        }
        double lado = Math.max(maxX - minX, maxY - minY);
        if (lado <= 0) lado = 1000;
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        double meio = lado * (0.5 + FOLGA);
        minX = cx - meio; maxX = cx + meio;
        minY = cy - meio; maxY = cy + meio;

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Terreno em 3D — " + a.getName()
                + (b == null ? "" : "  ↔  " + b.getName()));

        Label carregando = new Label("Levantando o terreno...");
        carregando.setStyle("-fx-text-fill: #ddd;");
        BorderPane raiz = new BorderPane(new javafx.scene.layout.StackPane(carregando));
        raiz.setStyle("-fx-background-color: #14171c;");
        stage.setScene(new Scene(raiz, 1000, 700));
        stage.show();

        final double fMinX = minX, fMaxX = maxX, fMinY = minY, fMaxY = maxY;

        // A foto do mapa base entra junto com o relevo: as duas leituras sao
        // de rede e nao ha por que fazer o usuario esperar duas vezes.
        final WritableImage[] imagem = { null };

        // Qualidade atual, e a vista em que a cena estava — trocar a
        // qualidade refaz tudo, e voltar a olhar de outro angulo depois disso
        // seria irritante.
        final Qualidade[] qual = { Settings.terrainQuality() };
        final double[] vista = { 89, 0 };
        final Runnable[] refazer = { () -> {} };

        refazer[0] = () -> {
        raiz.setCenter(new javafx.scene.layout.StackPane(carregando));
        raiz.setTop(null);
        raiz.setBottom(null);
        carregando.setText("Levantando o terreno...");
        final int LADO = qual[0].malha();
        final int FOTO = qual[0].fotoPx();
        Task<double[]> amostrar = new Task<>() {
            @Override protected double[] call() {
                imagem[0] = montarImagem(base, ortos, camadas, FOTO,
                        fMinX, fMinY, fMaxX, fMaxY);
                TerrainTiles.INSTANCE.prefetch(fMinX, fMinY, fMaxX, fMaxY, 20000);
                double[] alt = new double[LADO * LADO];
                int faltando = 0;
                double soma = 0, n = 0;
                // Cada vertice representa um quadrado de terreno, e e' esse
                // quadrado que ele pergunta. Ler um ponto so faz a malha pegar
                // o ruido da fonte em vez do relevo: numa nuvem de drone a
                // densidade varia com a linha de voo, e amostrar pontualmente
                // transforma essa variacao em serrilhado.
                double escalaM = Mercator.groundScaleAt(Mercator.latOfWorldY(
                        (fMinY + fMaxY) / 2));
                double meioPassoM = (fMaxX - fMinX) * escalaM / (LADO - 1.0) / 2;
                for (int r = 0; r < LADO; r++) {
                    double wy = fMinY + (fMaxY - fMinY) * r / (LADO - 1.0);
                    for (int c = 0; c < LADO; c++) {
                        double wx = fMinX + (fMaxX - fMinX) * c / (LADO - 1.0);
                        Double h = elevation == null ? null
                                : elevation.elevationOver(wx, wy, meioPassoM);
                        if (h == null) { alt[r * LADO + c] = Double.NaN; faltando++; }
                        else { alt[r * LADO + c] = h; soma += h; n++; }
                    }
                }
                if (n == 0) return null;
                // Onde não há dado, NÃO se inventa chão. Preencher com a média
                // desenharia um planalto liso em volta do levantamento, com
                // cara de terreno real — e alguém decidiria um enlace olhando
                // para relevo que ninguém mediu. O buraco fica buraco.
                Log.info("Terreno 3D (%s): %dx%d amostras, textura %d px, "
                        + "%d sem dado (%.0f%%)", qual[0], LADO, LADO, FOTO,
                        faltando, 100.0 * faltando / alt.length);
                return alt;
            }
        };

        amostrar.setOnSucceeded(e -> {
            double[] alt = amostrar.getValue();
            if (alt == null) {
                carregando.setText("Sem dado de altitude nesta área.\n"
                        + "Carregue uma nuvem .PLY ou ative o relevo do mapa.");
                return;
            }
            montar(stage, raiz, alt, LADO, fMinX, fMinY, fMaxX, fMaxY, a, radioA, b, radioB,
                   coberturas, imagem[0], contornoNuvem, qual, vista, refazer);
        });
        amostrar.setOnFailed(e -> {
            Throwable ex = amostrar.getException();
            carregando.setText("Falha ao levantar o terreno: "
                    + (ex == null ? "erro desconhecido" : ex.getMessage()));
        });

        Thread t = new Thread(amostrar, "terreno-3d");
        t.setDaemon(true);
        t.start();
        };
        refazer[0].run();
    }

    // ------------------------ Cena ------------------------

    private static void montar(Stage stage, BorderPane raiz, double[] alt, int lado,
                               double minX, double minY, double maxX, double maxY,
                               NetworkPoint a, Radio radioA, NetworkPoint b, Radio radioB,
                               List<Cobertura> coberturas, WritableImage foto,
                               List<double[]> contornoNuvem,
                               Qualidade[] qual, double[] vista, Runnable[] refazer) {

        double k = Mercator.groundScaleAt(Mercator.latOfWorldY((minY + maxY) / 2));
        double larguraM = (maxX - minX) * k;
        double profundM = (maxY - minY) * k;

        double minH = Double.MAX_VALUE, maxH = -Double.MAX_VALUE;
        int semDado = 0;
        for (double h : alt) {
            if (Double.isNaN(h)) { semDado++; continue; }
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        final double faixaH = Math.max(1, maxH - minH);
        double baseH = (minH + maxH) / 2;

        // Em que faixa de sinal cada vértice cai. Não depende do exagero
        // vertical, então é calculado uma vez e reaproveitado a cada ajuste.
        // Quais coberturas estao ligadas agora. Muda com as caixas de selecao
        // da propria janela, e cada mudanca refaz a textura e a classificacao
        // dos vertices — barato o bastante para responder na hora.
        final java.util.Set<Cobertura> ligadas = new java.util.LinkedHashSet<>();
        final boolean[] marcarNuvem = { false };
        final int[] linhas = { 1 };
        final int[][] faixaPorVertice = { new int[lado * lado] };

        MeshView terreno = new MeshView();
        PhongMaterial mat = new PhongMaterial();
        mat.setSpecularColor(Color.rgb(20, 20, 20));
        terreno.setMaterial(mat);
        terreno.setCullFace(CullFace.NONE);

        Group conteudo = new Group(terreno);

        // Pivô: as rotações agem aqui, e a câmera fica parada olhando para ele.
        Group pivo = new Group(conteudo);
        // Abre olhando de cima: e' a vista que casa com o mapa 2D, entao quem
        // chega aqui reconhece na hora o que esta vendo, e inclina depois se
        // quiser. -89 e nao -90 porque no zenite exato a rotacao em torno do
        // eixo vertical deixa de ter efeito visivel e a navegacao trava.
        // Retoma o angulo em que a cena estava. Trocar a qualidade refaz tudo
        // do zero, e voltar a mirar de novo a cada troca faria o usuario
        // desistir de experimentar.
        Rotate rotX = new Rotate(vista[0], Rotate.X_AXIS);
        Rotate rotY = new Rotate(vista[1], Rotate.Y_AXIS);
        rotX.angleProperty().addListener((o, x, y) -> vista[0] = y.doubleValue());
        rotY.angleProperty().addListener((o, x, y) -> vista[1] = y.doubleValue());
        // A ORDEM importa e estava trocada. Na lista de transformacoes do
        // JavaFX o ultimo item e' aplicado PRIMEIRO ao ponto, entao
        // addAll(rotY, rotX) inclinava e so depois girava — e o giro passava a
        // ser em torno de um eixo ja tombado. Resultado: ao girar, o mundo
        // rolava de lado, com a vertical apontando para a direita da tela.
        //
        // Com addAll(rotX, rotY) o rumo e' aplicado primeiro, em torno da
        // vertical do mundo, e a inclinacao depois. A vertical continua para
        // cima em qualquer rumo, que e' como se espera de um orbitador.
        pivo.getTransforms().addAll(rotX, rotY);

        // Camada so para deslocar a cena na tela. Fica FORA do pivo: assim o
        // arrasto do meio move na horizontal e na vertical da tela, e nao nos
        // eixos girados do terreno.
        Group desloc = new Group(pivo);

        PerspectiveCamera cam = new PerspectiveCamera(true);
        cam.setNearClip(0.5);
        cam.setFarClip(200_000);
        // Enquadra o que EXISTE, nao a caixa amostrada. Com metade da area
        // sem relevo — normal quando o voo nao cobre o quadrado inteiro — a
        // caixa e' muito maior que o terreno, e mirar nela deixava o
        // levantamento como um selo no meio da tela.
        double[] ext = extensaoComDado(alt, lado, larguraM, profundM);
        double ladoCaixa = Math.max(ext[2] - ext[0], ext[3] - ext[1]);
        if (ladoCaixa <= 0) ladoCaixa = Math.max(larguraM, profundM);
        // Torre e linha sao simbolos, e o tamanho deles tem que sair do que
        // aparece na tela. Medido sobre a caixa amostrada, ficavam finos demais
        // quando metade dela estava vazia — que e' o caso comum.
        final double escalaSimbolo = ladoCaixa;
        // Campo de visao vertical de 30 graus, com folga de 20%.
        double dist = ladoCaixa * 0.6 / Math.tan(Math.toRadians(15));
        cam.setTranslateZ(-dist);
        // Recentra no meio do que tem dado, e nao no meio da caixa vazia.
        conteudo.setTranslateX(-(ext[0] + ext[2]) / 2);
        conteudo.setTranslateZ(-(ext[1] + ext[3]) / 2);

        AmbientLight ambiente = new AmbientLight(Color.rgb(120, 120, 130));
        PointLight sol = new PointLight(Color.rgb(255, 245, 220));
        sol.setTranslateX(-larguraM);
        sol.setTranslateY(-faixaH * 40 - 2000);
        sol.setTranslateZ(-profundM);

        Group tudo = new Group(desloc, ambiente, sol);
        SubScene sub = new SubScene(tudo, 1000, 640, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.web("#14171c"));
        sub.setCamera(cam);

        Pane holder = new Pane(sub);
        sub.widthProperty().bind(holder.widthProperty());
        sub.heightProperty().bind(holder.heightProperty());

        // Os nomes ficam numa camada 2D por cima da cena, e não como texto
        // dentro dela: texto em 3D gira junto com o terreno e fica de costas
        // metade do tempo. Aqui cada rótulo é reposicionado projetando o topo
        // da antena para a tela, então acompanha a cena e continua legível de
        // qualquer ângulo.
        Label rotuloA = rotulo3D(a, radioA, Color.web("#29b6f6"));
        Label rotuloB = b == null ? null : rotulo3D(b, radioB, Color.web("#ffa726"));
        holder.getChildren().add(rotuloA);
        if (rotuloB != null) holder.getChildren().add(rotuloB);

        // ------------------------ Controles ------------------------
        Slider exagero = new Slider(1, 12, 3);
        exagero.setPrefWidth(180);
        exagero.setShowTickMarks(true);
        exagero.setMajorTickUnit(2);
        Label exageroTxt = new Label();
        exageroTxt.setStyle("-fx-text-fill: #ddd;");

        // Quanto detalhe, e quanto esperar por ele. Fica na barra, e nao
        // escondido nas preferencias, porque a escolha certa muda com o
        // projeto: numa area pequena da para pedir o maximo, numa grande a
        // mesma opcao deixa cada clique numa caixa custando meio segundo.
        javafx.scene.control.ChoiceBox<Qualidade> qualidade =
                new javafx.scene.control.ChoiceBox<>();
        qualidade.getItems().addAll(Qualidade.values());
        qualidade.setValue(qual[0]);
        qualidade.setTooltip(new javafx.scene.control.Tooltip(
                "Detalhe da malha e da textura.\n"
                + "Mais detalhe custa mais para abrir E a cada vez que se liga "
                + "um alcance ou a \u00e1rea da nuvem."));
        qualidade.setOnAction(ev -> {
            if (qualidade.getValue() == null || qualidade.getValue() == qual[0]) return;
            qual[0] = qualidade.getValue();
            Settings.setTerrainQuality(qual[0]);
            refazer[0].run();
        });

        CheckBox semMato = new CheckBox("Terreno nu");
        semMato.setSelected(true);
        semMato.setStyle("-fx-text-fill: #ddd;");
        semMato.setTooltip(new javafx.scene.control.Tooltip(
                "Tira a vegeta\u00e7\u00e3o e mostra o solo.\n"
                + "A nuvem do drone \u00e9 de superf\u00edcie: ela enxerga a copa das "
                + "\u00e1rvores, n\u00e3o o ch\u00e3o embaixo.\n"
                + "Isto muda s\u00f3 o desenho \u2014 o c\u00e1lculo de obstru\u00e7\u00e3o "
                + "e de alcance continua usando a superf\u00edcie inteira."));

        CheckBox mostrarLinha = new CheckBox("Linha do enlace");
        mostrarLinha.setSelected(b != null);
        mostrarLinha.setDisable(b == null);
        mostrarLinha.setStyle("-fx-text-fill: #ddd;");

        Label dica = new Label("girar: arrastar  ·  deslocar: botão do meio  ·  zoom: rolar");
        dica.setStyle("-fx-text-fill: #8a93a0; -fx-font-size: 11;");
        dica.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Nós que marcam o topo de cada antena, para os rótulos os seguirem.
        Node[] marcas = new Node[2];

        // Preenchido mais abaixo, quando a bola de navegacao existe; os botoes
        // sao criados antes dela e precisam de uma referencia para chamar.
        final Runnable[] aoMudarVista = { () -> {} };
        final Runnable[] sincronizarEixos = { () -> {} };

        Runnable posicionarRotulos = () -> {
            seguir(rotuloA, marcas[0], holder);
            seguir(rotuloB, marcas[1], holder);
        };

        // A versao sem vegetacao e' calculada uma vez so: a abertura nao
        // depende do exagero nem da cobertura, entao refazer a cada mexida no
        // slider seria trabalho jogado fora.
        // Janela de ~8 m: mata copa de arvore e telhado, e e' estreita demais
        // para tocar numa bancada de cava ou num morro.
        int raioMato = Math.max(1, (int) Math.round(8 / (larguraM / (lado - 1.0))));
        double[] altNu = semVegetacao(alt, lado, raioMato);

        Runnable reconstruir = () -> {
            double ex = exagero.getValue();
            double[] usar = semMato.isSelected() ? altNu : alt;
            terreno.setMesh(malha(usar, lado, larguraM, profundM, baseH, ex,
                    faixaPorVertice[0], linhas[0], foto != null));
            exageroTxt.setText(String.format("vertical %.0f×", ex));

            conteudo.getChildren().removeIf(n -> n != terreno);
            List<Node> novos = torres(alt, lado, minX, minY, maxX, maxY,
                    larguraM, profundM, baseH, ex,
                    escalaSimbolo, a, radioA, b, radioB, mostrarLinha.isSelected());
            conteudo.getChildren().addAll(novos);

            // As esferas entram na ordem A, B — e sao elas que marcam o topo.
            marcas[0] = marcas[1] = null;
            int achadas = 0;
            for (Node n : novos) {
                if (n instanceof Sphere && achadas < 2) marcas[achadas++] = n;
            }
            Platform.runLater(() -> aoMudarVista[0].run());
        };
        exagero.valueProperty().addListener((o, x, y) -> reconstruir.run());
        mostrarLinha.selectedProperty().addListener((o, x, y) -> reconstruir.run());
        semMato.selectedProperty().addListener((o, x, y) -> reconstruir.run());

        Button bDeCima = botao("De cima", "Vista a prumo, como o mapa");
        Button bInclinada = botao("Inclinada", "Vista de 35\u00b0, para ler o relevo");
        Button bEnquadrar = botao("Enquadrar", "Centraliza e volta ao zoom inicial");

        bDeCima.setOnAction(e -> {
            rotX.setAngle(89);
            rotY.setAngle(0);
            aoMudarVista[0].run();
        });
        bInclinada.setOnAction(e -> {
            rotX.setAngle(35);
            rotY.setAngle(-25);
            aoMudarVista[0].run();
        });
        bEnquadrar.setOnAction(e -> {
            desloc.setTranslateX(0);
            desloc.setTranslateY(0);
            cam.setTranslateZ(-dist);
            aoMudarVista[0].run();
        });

        Label rotExagero = new Label("Exagero:");
        rotExagero.setStyle("-fx-text-fill: #ddd;");
        rotExagero.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        exagero.setPrefWidth(130);
        exageroTxt.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        mostrarLinha.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Recalcula o tingimento com as coberturas ligadas no momento.
        Runnable aplicarCobertura = () -> {
            List<com.colmeia.radiomapper.rf.BeamCoverage.Cobertura> ativas =
                    new java.util.ArrayList<>();
            for (Cobertura cb : ligadas) ativas.add(cb.grade());
            double[] niveis = niveisDe(ativas);
            faixaPorVertice[0] = coberturaPorVertice(ativas, lado, niveis,
                    minX, minY, maxX, maxY);
            List<Color> cores = new java.util.ArrayList<>();
            for (double n : niveis) cores.add(MapPane.corDoNivel(n));

            if (foto != null) {
                // Com foto, a cobertura e' pintada NELA: a textura passa a ser
                // plana (cada vertice pega o pixel do seu lugar), e nao ha mais
                // uma linha por faixa para escolher.
                linhas[0] = 1;
                WritableImage pronta = tingirFoto(foto, faixaPorVertice[0], lado, cores);
                if (marcarNuvem[0]) {
                    // Quando o tingimento ja devolveu uma copia, o contorno
                    // risca nela mesmo; copiar de novo era percorrer alguns
                    // milhoes de pixels a toa a cada clique na caixa.
                    pronta = riscarContorno(pronta, pronta != foto,
                            contornoNuvem, minX, minY, maxX, maxY);
                }
                mat.setDiffuseMap(pronta);
                // Com foto a malha nao muda: as coordenadas de textura de cada
                // vertice sao a posicao dele na imagem, e isso independe da
                // cobertura. Refazer a malha aqui era reconstruir cem mil
                // vertices e duzentos mil triangulos para trocar um bitmap —
                // o que fazia ligar um alcance custar o mesmo que reabrir a
                // janela.
                exageroTxt.setText(String.format("vertical %.0f\u00d7", exagero.getValue()));
            } else {
                // Sem foto e outra historia: a faixa de sinal vira a LINHA da
                // textura que o vertice aponta, entao a malha carrega a
                // cobertura e precisa ser refeita.
                linhas[0] = 1 + cores.size();
                mat.setDiffuseMap(textura(cores));
                reconstruir.run();
            }
        };

        // Uma caixa por rádio simulado. Marcar e desmarcar aqui é o que
        // permite comparar "o que este cobre" com "o que os dois cobrem" sem
        // sair da tela.
        HBox caixas = new HBox(10);
        caixas.setAlignment(Pos.CENTER_LEFT);
        if (coberturas != null && !coberturas.isEmpty()) {
            Label rot = new Label("Alcance:");
            rot.setStyle("-fx-text-fill: #ddd;");
            rot.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            caixas.getChildren().add(rot);
            for (Cobertura cb : coberturas) {
                CheckBox cx = new CheckBox(cb.nome());
                cx.setStyle("-fx-text-fill: #ddd;");
                cx.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                cx.selectedProperty().addListener((o, x, y) -> {
                    if (y) ligadas.add(cb); else ligadas.remove(cb);
                    aplicarCobertura.run();
                });
                caixas.getChildren().add(cx);
            }
        } else {
            Label sem = new Label("Nenhum alcance simulado para estes r\u00e1dios \u2014 "
                    + "use Alcance no painel lateral.");
            sem.setStyle("-fx-text-fill: #8a93a0; -fx-font-size: 11;");
            caixas.getChildren().add(sem);
        }

        // Onde a altitude vem da nuvem, e onde vem do relevo global. Em 3D as
        // duas viram a mesma superficie lisa; sem a marca nao ha como saber
        // qual pedaco foi medido a centimetro.
        CheckBox cxNuvem = new CheckBox("\u00c1rea da nuvem");
        cxNuvem.setStyle("-fx-text-fill: #ddd;");
        cxNuvem.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        boolean temContorno = contornoNuvem != null && !contornoNuvem.isEmpty() && foto != null;
        cxNuvem.setDisable(!temContorno);
        cxNuvem.setTooltip(new javafx.scene.control.Tooltip(temContorno
                ? "Risca no terreno o limite do levantamento .PLY"
                : "Sem levantamento .PLY carregado, ou sem foto para riscar"));
        cxNuvem.selectedProperty().addListener((o, x, y) -> {
            marcarNuvem[0] = y;
            aplicarCobertura.run();
        });
        caixas.getChildren().addAll(
                new Separator(javafx.geometry.Orientation.VERTICAL), cxNuvem);

        // Quebra em duas linhas quando nao cabe, em vez de espremer.
        //
        // Numa HBox, controle que nao cabe e' cortado: a caixa de qualidade
        // virava "Ras." e "Terreno nu" virava "T..", o que nao da para ler nem
        // para clicar. Com a quebra, quem abre a janela estreita perde altura
        // de cena, que e' um preco visivel e reversivel — arrastar a janela
        // devolve a linha unica.
        Label rotQualidade = new Label("Qualidade:");
        rotQualidade.setStyle("-fx-text-fill: #ddd;");
        rotQualidade.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        qualidade.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        semMato.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        javafx.scene.layout.FlowPane barra = new javafx.scene.layout.FlowPane(10, 6,
                rotQualidade, qualidade,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                rotExagero, exagero, exageroTxt, semMato, mostrarLinha,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                bDeCima, bInclinada, bEnquadrar, dica);
        barra.setAlignment(Pos.CENTER_LEFT);
        barra.setRowValignment(javafx.geometry.VPos.CENTER);
        barra.setPadding(new Insets(8, 12, 8, 12));
        barra.setStyle("-fx-background-color: #1b1f26;");

        String buraco = semDado == 0 ? ""
                : String.format("  ·  %.0f%% da área sem dado de relevo (aparece vazada)",
                        100.0 * semDado / alt.length);
        // O rodape conta em que pé está a superfície desenhada. Com o filtro
        // ligado ela não é mais o que o rádio enxerga, e isso precisa estar
        // dito na tela — não só na dica da caixa.
        Label rodape = new Label();
        final double fMinH = minH, fMaxH = maxH;
        Runnable dizerRodape = () -> rodape.setText(String.format(
                "%.0f × %.0f m  ·  altitude %.0f a %.0f m  ·  malha %d×%d%s   —   %s",
                larguraM, profundM, fMinH, fMaxH, lado, lado, buraco,
                semMato.isSelected()
                        ? "vertical esticada, vegetação tirada do desenho "
                          + "(a obstrução é calculada com ela)"
                        : "a vertical está esticada, o terreno é mais suave do que parece"));
        dizerRodape.run();
        semMato.selectedProperty().addListener((o, x, y) -> dizerRodape.run());
        rodape.setStyle("-fx-text-fill: #8a93a0; -fx-font-size: 11; -fx-padding: 6 12 6 12;");

        // Eixos separados: arrastar mistura os dois, e quem quer so rodar em
        // torno da vertical acaba inclinando junto. Aqui cada um tem o seu
        // controle, com o valor a vista.
        Slider sGiro = new Slider(0, 360, 0);
        sGiro.setPrefWidth(190);
        Label lGiro = new Label();
        lGiro.setStyle("-fx-text-fill: #ddd;");
        lGiro.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        Slider sIncl = new Slider(INCL_MIN, INCL_MAX, 89);
        sIncl.setPrefWidth(190);
        Label lIncl = new Label();
        lIncl.setStyle("-fx-text-fill: #ddd;");
        lIncl.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        // Guarda para os sliders nao reagirem a si mesmos quando o arrasto na
        // cena os atualiza.
        final boolean[] ecoando = { false };
        Runnable sincronizarSliders = () -> {
            ecoando[0] = true;
            double g = ((rotY.getAngle() % 360) + 360) % 360;
            sGiro.setValue(g);
            sIncl.setValue(rotX.getAngle());
            lGiro.setText(String.format("%.0f\u00b0", g));
            lIncl.setText(String.format("%+.0f\u00b0", rotX.getAngle()));
            ecoando[0] = false;
        };
        sGiro.valueProperty().addListener((o, x, y) -> {
            if (ecoando[0]) return;
            rotY.setAngle(y.doubleValue());
            aoMudarVista[0].run();
        });
        sIncl.valueProperty().addListener((o, x, y) -> {
            if (ecoando[0]) return;
            rotX.setAngle(y.doubleValue());
            aoMudarVista[0].run();
        });

        Label rotG = new Label("Giro:");
        rotG.setStyle("-fx-text-fill: #ddd;");
        rotG.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Label rotI = new Label("Inclina\u00e7\u00e3o:");
        rotI.setStyle("-fx-text-fill: #ddd;");
        rotI.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        HBox eixos = new HBox(8, rotG, sGiro, lGiro,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                rotI, sIncl, lIncl,
                new Separator(javafx.geometry.Orientation.VERTICAL), caixas);
        eixos.setAlignment(Pos.CENTER_LEFT);
        eixos.setPadding(new Insets(6, 12, 6, 12));
        eixos.setStyle("-fx-background-color: #1b1f26;");

        sincronizarEixos[0] = sincronizarSliders;

        // A primeira montagem precisa das duas: aplicarCobertura define a
        // textura (sem ela o material fica sem mapa e a malha sai cinza) e
        // reconstruir cria a malha. Depois daqui, com foto, trocar a cobertura
        // mexe so na textura.
        aplicarCobertura.run();
        reconstruir.run();

        javafx.scene.layout.VBox topo = new javafx.scene.layout.VBox(barra, eixos);
        raiz.setTop(topo);
        raiz.setCenter(holder);
        raiz.setBottom(rodape);

        // Bola de navegacao: arrastar nela gira a cena com controle fino, sem
        // depender de acertar o arrasto no meio do terreno. O anel em volta e'
        // a bussola — as letras acompanham o giro e achatam quando a vista
        // inclina, que ja diz de que altura se esta olhando.
        Canvas bola = new Canvas(BOLA, BOLA);
        holder.getChildren().add(bola);

        // Que ponta o arrasto esta seguindo ("X+", "Z-", ...) e qual esta sob
        // o mouse. As duas alimentam o desenho: a primeira fica branca, a
        // segunda so acende, para a ponta dar para ser mirada antes do clique.
        final String[] travado = { null };
        final String[] sobre = { null };
        Runnable redesenharBola = () ->
                desenharBola(bola, rotX.getAngle(), rotY.getAngle(), travado[0], sobre[0]);
        aoMudarVista[0] = () -> {
            posicionarRotulos.run();
            redesenharBola.run();
            sincronizarEixos[0].run();
        };

        instalarOrbita(sub, rotX, rotY, cam, dist, desloc, sub, aoMudarVista[0]);
        instalarBola(bola, rotX, rotY, travado, sobre, aoMudarVista[0]);

        Runnable posicionarBola = () -> {
            bola.setLayoutX(holder.getWidth() - BOLA - 14);
            bola.setLayoutY(holder.getHeight() - BOLA - 14);
        };
        holder.widthProperty().addListener((o, x, y) -> { posicionarBola.run(); aoMudarVista[0].run(); });
        holder.heightProperty().addListener((o, x, y) -> { posicionarBola.run(); aoMudarVista[0].run(); });
        Platform.runLater(() -> { posicionarBola.run(); aoMudarVista[0].run(); });
    }

    /**
     * Caixa dos vértices que têm altitude, em coordenadas da cena.
     *
     * @return {minX, minZ, maxX, maxZ}
     */
    private static double[] extensaoComDado(double[] alt, int lado,
                                            double larguraM, double profundM) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int r = 0; r < lado; r++) {
            for (int c = 0; c < lado; c++) {
                if (Double.isNaN(alt[r * lado + c])) continue;
                double x = (c / (lado - 1.0) - 0.5) * larguraM;
                double z = -((r / (lado - 1.0)) - 0.5) * profundM;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
        }
        if (minX > maxX) return new double[] { -larguraM / 2, -profundM / 2,
                                                larguraM / 2, profundM / 2 };
        return new double[] { minX, minZ, maxX, maxZ };
    }

    /**
     * Botão esquerdo gira, botão do meio desloca, rolar aproxima.
     *
     * O deslocamento fica no botão do meio porque o esquerdo já gira: com um
     * botão só, sair do lugar exigia girar, aproximar e girar de volta. E ele
     * age num grupo FORA das rotações, então arrastar para a direita leva a
     * cena para a direita da TELA, e não para um eixo girado do terreno.
     */
    private static void instalarOrbita(SubScene sub, Rotate rotX, Rotate rotY,
                                       PerspectiveCamera cam, double distInicial,
                                       Group desloc, SubScene medida,
                                       Runnable aoMover) {
        final double[] ancora = new double[2];
        final double[] angulo = { rotX.getAngle(), rotY.getAngle() };
        final double[] pan = new double[2];
        final boolean[] deslocando = { false };

        sub.setOnMousePressed(ev -> {
            ancora[0] = ev.getSceneX();
            ancora[1] = ev.getSceneY();
            angulo[0] = rotX.getAngle();
            angulo[1] = rotY.getAngle();
            pan[0] = desloc.getTranslateX();
            pan[1] = desloc.getTranslateY();
            deslocando[0] = ev.isMiddleButtonDown();
            sub.setCursor(deslocando[0] ? javafx.scene.Cursor.MOVE : javafx.scene.Cursor.DEFAULT);
        });
        sub.setOnMouseDragged(ev -> {
            double dx = ev.getSceneX() - ancora[0];
            double dy = ev.getSceneY() - ancora[1];

            if (deslocando[0]) {
                // Pixel da tela para unidade de mundo: a que distância se está
                // muda o quanto um pixel vale, senão o arrasto fica lento de
                // longe e disparado de perto.
                double porPixel = porPixel(cam, medida);
                desloc.setTranslateX(pan[0] + dx * porPixel);
                desloc.setTranslateY(pan[1] + dy * porPixel);
                aoMover.run();
                return;
            }

            rotY.setAngle(angulo[1] + dx * 0.3);
            // Trava perto do zênite e do nadir: passar direto vira a cena de
            // cabeça para baixo e o usuario perde a referencia.
            rotX.setAngle(limitarInclinacao(angulo[0] - dy * 0.3));
            aoMover.run();
        });
        sub.setOnMouseReleased(ev -> {
            deslocando[0] = false;
            sub.setCursor(javafx.scene.Cursor.DEFAULT);
        });
        sub.setOnScroll(ev -> {
            double z = cam.getTranslateZ() * (ev.getDeltaY() > 0 ? 0.88 : 1 / 0.88);
            cam.setTranslateZ(Math.max(-distInicial * 8, Math.min(-distInicial * 0.05, z)));
            aoMover.run();
        });
    }

    /** Quantas unidades de mundo cabem num pixel de tela, no zoom atual. */
    private static double porPixel(PerspectiveCamera cam, SubScene sub) {
        double altura = Math.max(1, sub.getHeight());
        double d = Math.abs(cam.getTranslateZ());
        return 2 * d * Math.tan(Math.toRadians(15)) / altura;
    }

    // ------------------------ Bola de navegação ------------------------

    /** Lado do gizmo de eixos, em pixels. */
    private static final double BOLA = 116;

    /**
     * Inclinação: 90° é a prumo, 0° é o nível do solo.
     *
     * Passar de 0 colocaria a câmera embaixo do terreno — e dali as torres, o
     * mastro e a linha do enlace desaparecem atrás da superfície, porque o
     * terreno fica entre eles e quem olha.
     */
    private static final double INCL_MIN = 0;
    private static final double INCL_MAX = 90;

    private static double limitarInclinacao(double v) {
        return Math.max(INCL_MIN, Math.min(INCL_MAX, v));
    }

    private static Button botao(String texto, String dica) {
        Button b = new Button(texto);
        b.setTooltip(new javafx.scene.control.Tooltip(dica));
        b.setFocusTraversable(false);
        b.setStyle("-fx-font-size: 11;");
        // Sem isto o HBox encolhe o botao ate sobrar "De ci..." na tela.
        b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return b;
    }

    /**
     * Clicar numa ponta leva a vista para aquele lado; arrastar gira.
     *
     * <h3>Por que o clique é o que diferencia as seis pontas</h3>
     * A cena tem dois graus de liberdade — giro e inclinação — e o gizmo
     * mostra três eixos. Arrastando, não há como as três letras fazerem
     * coisas diferentes: o eixo vertical (Z) é o único que pode comandar o
     * giro, e os dois horizontais (X e Y) só podem comandar a inclinação, de
     * modo que arrastar em X e em Y dá no mesmo. Isso não é um defeito da
     * implementação, é aritmética.
     *
     * O que dá papel próprio a cada ponta é o clique: ele leva a câmera para
     * olhar ao longo daquele eixo, e aí as seis pontas viram seis vistas
     * distintas — de leste, de oeste, de norte, de sul, a prumo e rente ao
     * chão. É o mesmo gesto do cubo de vista dos editores 3D.
     *
     * <h3>Arrastar</h3>
     * Pegar um eixo ainda restringe o movimento, que é o que evita corrigir
     * de volta o que se mexeu sem querer: Z só gira, X e Y só inclinam, e o
     * espaço vazio continua livre para os dois.
     */
    private static void instalarBola(Canvas bola, Rotate rotX, Rotate rotY,
                                     String[] travado, String[] sobre, Runnable aoMover) {
        final double[] ancora = new double[2];
        final double[] angulo = new double[2];
        final boolean[] arrastou = { false };

        bola.setOnMouseMoved(ev -> {
            Eixo e = pontaSob(bola, ev.getX(), ev.getY(), rotX.getAngle(), rotY.getAngle());
            String chave = e == null ? null : chave(e);
            if (!java.util.Objects.equals(sobre[0], chave)) {
                sobre[0] = chave;
                aoMover.run();
            }
            bola.setCursor(e == null ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.HAND);
        });
        bola.setOnMouseExited(ev -> {
            if (sobre[0] != null) { sobre[0] = null; aoMover.run(); }
        });
        bola.setOnMousePressed(ev -> {
            ancora[0] = ev.getSceneX();
            ancora[1] = ev.getSceneY();
            angulo[0] = rotX.getAngle();
            angulo[1] = rotY.getAngle();
            arrastou[0] = false;
            Eixo e = pontaSob(bola, ev.getX(), ev.getY(), rotX.getAngle(), rotY.getAngle());
            travado[0] = e == null ? null : chave(e);
            sobre[0] = travado[0];
            aoMover.run();
            ev.consume();
        });
        bola.setOnMouseDragged(ev -> {
            double dx = ev.getSceneX() - ancora[0];
            double dy = ev.getSceneY() - ancora[1];
            // Tremida de dedo no clique nao conta como arrasto, senao o clique
            // numa ponta quase nunca chegaria a acontecer.
            if (Math.hypot(dx, dy) > 3) arrastou[0] = true;
            if (!arrastou[0]) { ev.consume(); return; }

            // Mais sensivel que no terreno: o gizmo e pequeno, e atravessa-lo
            // inteiro precisa dar uma volta util.
            boolean ehZ = travado[0] != null && travado[0].startsWith("Z");
            if (travado[0] == null || !ehZ) {
                rotX.setAngle(limitarInclinacao(angulo[0] - dy * 0.9));
            }
            if (travado[0] == null || ehZ) {
                rotY.setAngle(angulo[1] + dx * 0.9);
            }
            aoMover.run();
            ev.consume();
        });
        bola.setOnMouseReleased(ev -> {
            if (!arrastou[0] && travado[0] != null) {
                double[] vista = vistaDoEixo(travado[0], rotY.getAngle());
                rotX.setAngle(limitarInclinacao(vista[0]));
                rotY.setAngle(vista[1]);
            }
            travado[0] = null;
            arrastou[0] = false;
            aoMover.run();
            ev.consume();
        });
    }

    /** "X+", "Z-": a ponta, com o sinal, que é o que o clique precisa saber. */
    private static String chave(Eixo e) {
        return e.letra + (e.positivo ? "+" : "-");
    }

    /**
     * Para onde o clique numa ponta leva a câmera: {inclinação, giro}.
     *
     * A conta sai de {@link #projetar3}: a vista certa é a que deixa aquela
     * direção apontando para a câmera. Z para baixo seria olhar de dentro da
     * terra, que a inclinação não permite — vira a vista rente ao chão, que é
     * o outro extremo útil do mesmo eixo.
     */
    static double[] vistaDoEixo(String chave, double giroAtual) {
        switch (chave) {
            case "Z+": return new double[] { 90, giroAtual };   // a prumo
            case "Z-": return new double[] { 0, giroAtual };    // rente ao chao
            case "X+": return new double[] { 0, 90 };           // de leste
            case "X-": return new double[] { 0, -90 };          // de oeste
            case "Y+": return new double[] { 0, 180 };          // de norte
            case "Y-": return new double[] { 0, 0 };            // de sul
            default:   return new double[] { 90, giroAtual };
        }
    }

    /** O que aquela ponta faz, em palavras, para a leitura do rodapé. */
    private static String oQueFaz(String chave) {
        switch (chave) {
            case "Z+": return "de cima";
            case "Z-": return "do ch\u00e3o";
            case "X+": return "de leste";
            case "X-": return "de oeste";
            case "Y+": return "de norte";
            case "Y-": return "de sul";
            default:   return "";
        }
    }

    /** Qual ponta do gizmo está sob o cursor, ou null. */
    /**
     * Qual ponta está sob o cursor, ou null.
     *
     * Quando duas pontas quase se sobrepõem na tela — o que acontece em
     * vistas de perfil — ganha a que está na frente, que é a que o desenho
     * pintou por cima. Escolher só pela distância faria o clique pegar uma
     * ponta que o usuário nem está vendo.
     */
    private static Eixo pontaSob(Canvas c, double px, double py, double rx, double ry) {
        double cx = c.getWidth() / 2, cy = c.getHeight() / 2;
        double raio = c.getWidth() / 2 - 15;
        Eixo melhor = null;
        double perto = 0;
        for (Eixo e : eixosNaTela(rx, ry)) {
            double ex = cx + e.x * raio, ey = cy + e.y * raio;
            double d = Math.hypot(px - ex, py - ey);
            if (d > ACERTO) continue;
            if (melhor == null
                    || (Math.abs(d - perto) < 5 ? e.prof < melhor.prof : d < perto)) {
                perto = d;
                melhor = e;
            }
        }
        return melhor;
    }

    /** Folga para acertar uma ponta, em pixels. */
    private static final double ACERTO = 16;

    /**
     * Gizmo de eixos, como em editor 3D.
     *
     * Três eixos com as duas pontas: X para leste, Y para norte, Z para cima —
     * as letras de sempre, nos rumos que importam aqui. As pontas atrás são
     * desenhadas primeiro e ficam vazadas; as da frente, cheias e rotuladas.
     * Assim o desenho mostra de que lado se está olhando sem precisar de
     * legenda.
     *
     * Clicar numa ponta leva a câmera para aquele lado; arrastar gira.
     */
    private static void desenharBola(Canvas c, double rx, double ry,
                                     String travado, String sobre) {
        GraphicsContext g = c.getGraphicsContext2D();
        double w = c.getWidth(), h = c.getHeight();
        double cx = w / 2, cy = h / 2;
        double raio = w / 2 - 15;

        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#1b2028", 0.72));
        g.fillOval(cx - raio - 10, cy - raio - 10, (raio + 10) * 2, (raio + 10) * 2);

        Eixo[] eixos = eixosNaTela(rx, ry);
        // Do mais distante para o mais proximo: o que esta na frente cobre.
        java.util.Arrays.sort(eixos, (p, q) -> Double.compare(q.prof, p.prof));

        g.setFont(Font.font("System", FontWeight.BOLD, 11));
        for (Eixo e : eixos) {
            double px = cx + e.x * raio;
            double py = cy + e.y * raio;

            String chave = chave(e);
            boolean ativo = chave.equals(travado);
            boolean mirado = chave.equals(sobre);
            g.setStroke(ativo ? Color.WHITE
                    : e.cor.deriveColor(0, 1, mirado ? 1.35 : (e.positivo ? 1 : 0.6), 1));
            g.setLineWidth(ativo ? 3.4 : (mirado ? 3.0 : (e.positivo ? 2.2 : 1.4)));
            g.strokeLine(cx, cy, px, py);

            // Halo na ponta mirada: sem isto nao da para saber que o clique
            // vai pegar, e errar por 3 px parece o gizmo nao fazer nada.
            if (mirado || ativo) {
                g.setStroke(Color.web("#ffffff", 0.35));
                g.setLineWidth(1);
                g.strokeOval(px - 12, py - 12, 24, 24);
            }

            double r = ativo || mirado ? 9 : 7;
            if (e.positivo) {
                g.setFill(e.cor);
                g.fillOval(px - r, py - r, r * 2, r * 2);
                g.setFill(Color.web("#14171c"));
                g.fillText(e.letra, px - 3.5, py + 4);
            } else {
                g.setFill(Color.web("#1b2028"));
                g.fillOval(px - r, py - r, r * 2, r * 2);
                g.setStroke(e.cor.deriveColor(0, 1, 0.7, 1));
                g.setLineWidth(1.4);
                g.strokeOval(px - r, py - r, r * 2, r * 2);
            }
        }

        g.setFill(Color.web("#8a93a0"));
        g.setFont(Font.font("System", 9));
        // A leitura conta o que o gizmo vai fazer, nao so em que angulo esta:
        // e' onde o usuario descobre que a ponta tem clique.
        String chave = travado != null ? travado : sobre;
        g.setFill(chave == null ? Color.web("#8a93a0") : Color.web("#cfd6e0"));
        g.fillText(chave == null
                ? String.format("%.0f\u00b0 / %.0f\u00b0", rx, ((ry % 360) + 360) % 360)
                : chave + " \u00b7 " + oQueFaz(chave), 4, h - 4);
    }

    /** Uma ponta de eixo já projetada na tela. */
    private static final class Eixo {
        final double x, y, prof;
        final Color cor;
        final String letra;
        final boolean positivo;
        final double mundoX, mundoY, mundoZ;
        Eixo(double x, double y, double prof, Color cor, String letra, boolean positivo,
             double mx, double my, double mz) {
            this.x = x; this.y = y; this.prof = prof;
            this.cor = cor; this.letra = letra; this.positivo = positivo;
            this.mundoX = mx; this.mundoY = my; this.mundoZ = mz;
        }
    }

    /**
     * As seis pontas dos eixos, projetadas.
     *
     * Na cena, +X é leste, +Z é norte e -Y é cima (a altitude entra negada).
     * O gizmo mostra X leste, Y norte e Z cima, como esses nomes são usados
     * em mapa.
     */
    private static Eixo[] eixosNaTela(double rx, double ry) {
        Color vermelho = Color.web("#e8564a");
        Color verde = Color.web("#5ec269");
        Color azul = Color.web("#4a90e2");
        return new Eixo[] {
            ponta(1, 0, 0, rx, ry, vermelho, "X", true),
            ponta(-1, 0, 0, rx, ry, vermelho, "X", false),
            ponta(0, 0, 1, rx, ry, verde, "Y", true),
            ponta(0, 0, -1, rx, ry, verde, "Y", false),
            ponta(0, -1, 0, rx, ry, azul, "Z", true),
            ponta(0, 1, 0, rx, ry, azul, "Z", false),
        };
    }

    private static Eixo ponta(double dx, double dy, double dz, double rx, double ry,
                              Color cor, String letra, boolean positivo) {
        double[] p = projetar3(dx, dy, dz, rx, ry);
        return new Eixo(p[0], p[1], p[2], cor, letra, positivo, dx, dy, dz);
    }

    /**
     * Projeta uma direção do mundo, guardando a profundidade.
     *
     * Aplica as mesmas duas rotações da cena, na mesma ordem em que ela as
     * aplica — giro primeiro, inclinação depois. A terceira componente diz o
     * que está na frente e o que está atrás.
     */
    static double[] projetar3(double dx, double dy, double dz,
                              double rxGraus, double ryGraus) {
        double ry = Math.toRadians(ryGraus), rx = Math.toRadians(rxGraus);
        double x1 = dx * Math.cos(ry) + dz * Math.sin(ry);
        double y1 = dy;
        double z1 = -dx * Math.sin(ry) + dz * Math.cos(ry);
        double y2 = y1 * Math.cos(rx) - z1 * Math.sin(rx);
        double z2 = y1 * Math.sin(rx) + z1 * Math.cos(rx);
        return new double[] { x1, y2, z2 };
    }

    /**
     * Projeta uma direção horizontal do mundo para a tela.
     *
     * Aplica as mesmas duas rotações da cena, na mesma ordem, e descarta a
     * profundidade — o que sobra é para onde aquela direção aponta na tela.
     */
    static double[] projetar(double dx, double dz, double rxGraus, double ryGraus) {
        double ry = Math.toRadians(ryGraus), rx = Math.toRadians(rxGraus);
        // Giro em torno do eixo vertical.
        double x1 = dx * Math.cos(ry) + dz * Math.sin(ry);
        double z1 = -dx * Math.sin(ry) + dz * Math.cos(ry);
        // Inclinação em torno do eixo horizontal; y entra como zero.
        double y2 = -z1 * Math.sin(rx);
        double n = Math.hypot(x1, y2);
        if (n < 1e-9) return new double[] { 0, 0 };
        return new double[] { x1 / n, y2 / n };
    }

    // ------------------------ Malha ------------------------

    /**
     * Tira a vegetação do relevo, por abertura morfológica.
     *
     * Uma nuvem de drone é um modelo de SUPERFÍCIE: cada célula guarda o topo
     * do que existe ali, então uma mata vira um platô de 8 m de altura e uma
     * árvore isolada vira uma agulha. Esticado na vertical, o terreno fica
     * coberto de espinhos que não são relevo.
     *
     * A abertura é o filtro clássico para isso: primeiro o mínimo de cada
     * vizinhança (a copa desaparece, porque em volta dela há chão), depois o
     * máximo do resultado (o que o mínimo encolheu volta ao tamanho). O que
     * sobra é o que é mais largo que a janela — o morro, a bancada da cava, a
     * estrada. O que é mais estreito some.
     *
     * É só para o desenho. Obstrução de enlace continua sendo calculada sobre
     * a superfície cheia, porque para o rádio a árvore obstrui de verdade.
     *
     * @param lado vértices por linha da grade quadrada
     * @param raio metade da janela, em vértices
     */
    static double[] semVegetacao(double[] alt, int lado, int raio) {
        double[] ero = new double[alt.length];
        double[] out = new double[alt.length];
        // Separável: passar em linha e depois em coluna dá o mesmo que a
        // janela quadrada inteira, e custa 2n em vez de n².
        janela(alt, ero, lado, raio, true, true);
        janela(ero, out, lado, raio, false, true);
        janela(out, ero, lado, raio, true, false);
        janela(ero, out, lado, raio, false, false);
        // Onde não havia dado continua não havendo: o filtro não inventa chão.
        for (int i = 0; i < alt.length; i++) {
            if (Double.isNaN(alt[i])) out[i] = Double.NaN;
        }
        return out;
    }

    /** Mínimo ou máximo ao longo de uma linha ou de uma coluna. */
    private static void janela(double[] entra, double[] sai, int lado, int raio,
                               boolean porLinha, boolean minimo) {
        for (int a = 0; a < lado; a++) {
            for (int b = 0; b < lado; b++) {
                double melhor = Double.NaN;
                for (int d = -raio; d <= raio; d++) {
                    int bb = b + d;
                    if (bb < 0 || bb >= lado) continue;
                    double v = entra[porLinha ? a * lado + bb : bb * lado + a];
                    if (Double.isNaN(v)) continue;
                    if (Double.isNaN(melhor) || (minimo ? v < melhor : v > melhor)) melhor = v;
                }
                sai[porLinha ? a * lado + b : b * lado + a] = melhor;
            }
        }
    }

    private static TriangleMesh malha(double[] alt, int lado,
                                      double larguraM, double profundM,
                                      double baseH, double exagero,
                                      int[] faixaPorVertice, int linhas, boolean planar) {
        TriangleMesh m = new TriangleMesh();

        double minH = Double.MAX_VALUE, maxH = -Double.MAX_VALUE;
        for (double h : alt) {
            if (Double.isNaN(h)) continue;
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        double faixa = Math.max(1, maxH - minH);

        float[] pontos = new float[lado * lado * 3];
        float[] tex = new float[lado * lado * 2];
        float[] normais = new float[lado * lado * 3];
        for (int r = 0; r < lado; r++) {
            for (int c = 0; c < lado; c++) {
                int i = r * lado + c;
                double x = (c / (lado - 1.0) - 0.5) * larguraM;
                double z = -((r / (lado - 1.0)) - 0.5) * profundM;
                // Y do JavaFX cresce para BAIXO: altitude entra negativa para
                // o morro apontar para cima na tela.
                boolean vazio = Double.isNaN(alt[i]);
                double y = vazio ? 0 : -(alt[i] - baseH) * exagero;
                pontos[i * 3] = (float) x;
                pontos[i * 3 + 1] = (float) y;
                pontos[i * 3 + 2] = (float) z;
                // Vértice sem dado entra na lista (os índices precisam bater)
                // mas nenhuma face o referencia.
                if (planar) {
                    // Foto do mapa: cada vertice puxa o pixel do proprio lugar.
                    tex[i * 2] = (float) (c / (lado - 1.0));
                    tex[i * 2 + 1] = (float) (r / (lado - 1.0));
                } else {
                    tex[i * 2] = vazio ? 0f : (float) ((alt[i] - minH) / faixa);
                    // v escolhe a LINHA da textura: 0 e' relevo puro, as demais
                    // sao as faixas de sinal. Meio pixel de folga evita o
                    // filtro puxar cor da linha vizinha.
                    int linha = faixaPorVertice == null ? 0 : faixaPorVertice[i];
                    tex[i * 2 + 1] = (float) ((linha * LINHA_PX + LINHA_PX / 2.0)
                            / (linhas * (double) LINHA_PX));
                }
            }
        }

        // Uma normal por vértice, e não a que o JavaFX deduz por face.
        //
        // Sem isto cada triângulo recebe uma normal só e é preenchido com um
        // tom chapado: uma encosta lisa vira um mosaico de facetas, e o olho
        // lê mosaico como aspereza. Somando as normais das faces que tocam
        // cada vértice a iluminação passa a variar dentro do triângulo, e o
        // que sobra de relevo na tela é o relevo que existe no dado.
        int[] faces = new int[(lado - 1) * (lado - 1) * 18];
        int f = 0;
        for (int r = 0; r < lado - 1; r++) {
            for (int c = 0; c < lado - 1; c++) {
                int p00 = r * lado + c, p01 = p00 + 1;
                int p10 = (r + 1) * lado + c, p11 = p10 + 1;
                // Triângulo com um canto sem dado não é desenhado: melhor um
                // rasgo visível do que uma rampa inventada até o nada.
                if (!Double.isNaN(alt[p00]) && !Double.isNaN(alt[p10]) && !Double.isNaN(alt[p01])) {
                    faces[f++] = p00; faces[f++] = p00; faces[f++] = p00;
                    faces[f++] = p10; faces[f++] = p10; faces[f++] = p10;
                    faces[f++] = p01; faces[f++] = p01; faces[f++] = p01;
                    acumularNormal(normais, pontos, p00, p10, p01);
                }
                if (!Double.isNaN(alt[p01]) && !Double.isNaN(alt[p10]) && !Double.isNaN(alt[p11])) {
                    faces[f++] = p01; faces[f++] = p01; faces[f++] = p01;
                    faces[f++] = p10; faces[f++] = p10; faces[f++] = p10;
                    faces[f++] = p11; faces[f++] = p11; faces[f++] = p11;
                    acumularNormal(normais, pontos, p01, p10, p11);
                }
            }
        }
        normalizar(normais);

        m.setVertexFormat(javafx.scene.shape.VertexFormat.POINT_NORMAL_TEXCOORD);
        m.getPoints().setAll(pontos);
        m.getNormals().setAll(normais);
        m.getTexCoords().setAll(tex);
        m.getFaces().setAll(java.util.Arrays.copyOf(faces, f));
        return m;
    }

    /** Soma a normal de uma face aos três vértices que a formam. */
    private static void acumularNormal(float[] n, float[] p, int a, int b, int c) {
        double ax = p[a * 3], ay = p[a * 3 + 1], az = p[a * 3 + 2];
        double ux = p[b * 3] - ax, uy = p[b * 3 + 1] - ay, uz = p[b * 3 + 2] - az;
        double vx = p[c * 3] - ax, vy = p[c * 3 + 1] - ay, vz = p[c * 3 + 2] - az;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        // O terreno é um campo de alturas, então a normal sempre tem componente
        // para cima; se a ordem dos vértices saiu ao contrário, inverte-se. Sem
        // isto metade das faces ficaria preta.
        if (ny > 0) { nx = -nx; ny = -ny; nz = -nz; }
        for (int i : new int[] { a, b, c }) {
            n[i * 3] += (float) nx;
            n[i * 3 + 1] += (float) ny;
            n[i * 3 + 2] += (float) nz;
        }
    }

    private static void normalizar(float[] n) {
        for (int i = 0; i < n.length; i += 3) {
            double d = Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (d < 1e-9) { n[i] = 0; n[i + 1] = -1; n[i + 2] = 0; continue; }
            n[i] /= (float) d;
            n[i + 1] /= (float) d;
            n[i + 2] /= (float) d;
        }
    }

    /**
     * Faixa de cor por altitude: verde no baixo, ocre no meio, branco no alto.
     *
     * Paleta de carta topográfica de propósito — é a que se lê sem legenda,
     * porque todo mundo já viu mapa de relevo pintado assim.
     */
    /**
     * Costura os tiles do mapa base numa única imagem da área modelada.
     *
     * O zoom é escolhido para a imagem sair com detalhe parecido com o da
     * textura: buscar mais que isso gasta rede sem mudar o que se vê, e menos
     * deixa o terreno borrado de perto.
     *
     * Roda fora da thread da interface e ESPERA os tiles: devolver uma imagem
     * meio pronta faria o relevo aparecer remendado, com buracos que nunca se
     * preencheriam. Falhando, devolve null e a pintura por altitude assume.
     */
    private static WritableImage montarImagem(TileSource fonte, List<Image> ortos,
                                              List<com.colmeia.radiomapper.model.ImageLayer> camadas,
                                              int fotoPx,
                                              double minX, double minY,
                                              double maxX, double maxY) {
        double largura = maxX - minX;
        if (largura <= 0) return null;

        // As camadas visiveis com imagem carregada, da de TRAS para a
        // frente: e' a mesma ordem do mapa, e a de cima ganha o pixel.
        java.util.List<Image> fotos = new java.util.ArrayList<>();
        java.util.List<com.colmeia.radiomapper.model.ImageOverlay> onde =
                new java.util.ArrayList<>();
        if (ortos != null && camadas != null) {
            for (int i = 0; i < camadas.size() && i < ortos.size(); i++) {
                var c = camadas.get(i);
                var o = c.getOverlay();
                if (ortos.get(i) == null || !o.isVisible()) continue;
                if (o.getWidth() <= 0 || o.getHeight() <= 0) continue;
                fotos.add(ortos.get(i));
                onde.add(o);
            }
        }
        boolean temOrto = !fotos.isEmpty();
        boolean temTiles = fonte != null && fonte.isMap();
        if (!temOrto && !temTiles) return null;

        // Zoom em que a área ocupa cerca de fotoPx pixels.
        int zoom = temTiles ? fonte.minZoom() : 0;
        if (temTiles) {
            for (int z = fonte.minZoom(); z <= fonte.maxZoom(); z++) {
                double px = largura / Mercator.tileSpan(z) * Mercator.TILE;
                zoom = z;
                if (px >= fotoPx) break;
            }
        }

        double span = temTiles ? Mercator.tileSpan(zoom) : 1;
        int n = 1 << zoom;
        int tx0 = 0, tx1 = -1, ty0 = 0, ty1 = -1;
        if (temTiles) {
            tx0 = (int) Math.floor((minX + Mercator.MAX) / span);
            tx1 = (int) Math.floor((maxX + Mercator.MAX) / span);
            ty0 = (int) Math.floor((minY + Mercator.MAX) / span);
            ty1 = (int) Math.floor((maxY + Mercator.MAX) / span);
            // Teto de seguranca: area enorme em zoom alto pediria centenas de
            // tiles e travaria a abertura.
            if ((long) (tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 120) temTiles = false;
        }

        java.util.Map<String, Image> tiles = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<String> faltando = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                if (tx < 0 || ty < 0 || tx >= n || ty >= n) continue;
                String k = tx + "/" + ty;
                Image pronto = TileCache.INSTANCE.cached(fonte, zoom, tx, ty);
                if (pronto != null) { tiles.put(k, pronto); continue; }
                faltando.add(k);
                TileCache.INSTANCE.request(fonte, zoom, tx, ty, img -> {
                    if (img != null) tiles.put(k, img);
                    faltando.remove(k);
                });
            }
        }

        long limite = System.currentTimeMillis() + 25_000;
        while (!faltando.isEmpty() && System.currentTimeMillis() < limite) {
            try { Thread.sleep(60); } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (tiles.isEmpty() && !temOrto) return null;

        WritableImage out = new WritableImage(fotoPx, fotoPx);
        PixelWriter pw = out.getPixelWriter();
        java.util.Map<String, javafx.scene.image.PixelReader> leitores = new java.util.HashMap<>();
        Color vazio = Color.web("#20262e");

        int nFotos = fotos.size();
        javafx.scene.image.PixelReader[] lerFoto =
                new javafx.scene.image.PixelReader[nFotos];
        int[] larguras = new int[nFotos], alturas = new int[nFotos];
        for (int i = 0; i < nFotos; i++) {
            lerFoto[i] = fotos.get(i).getPixelReader();
            larguras[i] = (int) fotos.get(i).getWidth();
            alturas[i] = (int) fotos.get(i).getHeight();
        }

        for (int y = 0; y < fotoPx; y++) {
            double wy = minY + (maxY - minY) * y / (fotoPx - 1.0);
            for (int x = 0; x < fotoPx; x++) {
                double wx = minX + (maxX - minX) * x / (fotoPx - 1.0);

                // As ortofotos tem a palavra final onde cobrem. Da ultima para
                // a primeira: a de cima no mapa e' a de cima aqui tambem.
                boolean pintou = false;
                for (int i = nFotos - 1; i >= 0 && !pintou; i--) {
                    var o = onde.get(i);
                    double u = (wx - o.getX()) / o.getWidth();
                    double v = (wy - o.getY()) / o.getHeight();
                    if (u < 0 || u >= 1 || v < 0 || v >= 1) continue;
                    int ox = Math.min(larguras[i] - 1, (int) (u * larguras[i]));
                    int oy = Math.min(alturas[i] - 1, (int) (v * alturas[i]));
                    int argb = lerFoto[i].getArgb(ox, oy);
                    // GeoTIFF de voo costuma vir com borda transparente ou
                    // preta fora da area voada; ali vale o que houver por
                    // baixo, em vez de um retangulo vazado. Com varias
                    // camadas isso importa mais ainda: e' o que deixa a de
                    // baixo aparecer na franja da de cima.
                    if (((argb >>> 24) & 0xFF) > 8 && (argb & 0xFFFFFF) != 0) {
                        pw.setArgb(x, y, argb);
                        pintou = true;
                    }
                }
                if (pintou) continue;

                if (!temTiles) { pw.setColor(x, y, vazio); continue; }
                double fx = (wx + Mercator.MAX) / span;
                double fy = (wy + Mercator.MAX) / span;
                int tx = (int) Math.floor(fx);
                int ty = (int) Math.floor(fy);
                int px = (int) ((fx - tx) * Mercator.TILE);
                int py = (int) ((fy - ty) * Mercator.TILE);

                String k = tx + "/" + ty;
                var leitor = leitores.computeIfAbsent(k, kk -> {
                    Image img = tiles.get(kk);
                    return img == null ? null : img.getPixelReader();
                });
                if (leitor == null) { pw.setColor(x, y, vazio); continue; }
                int cx = Math.max(0, Math.min(Mercator.TILE - 1, px));
                int cy = Math.max(0, Math.min(Mercator.TILE - 1, py));
                pw.setArgb(x, y, leitor.getArgb(cx, cy));
            }
        }
        return out;
    }

    /**
     * Copia a foto pintando por cima as faixas de sinal.
     *
     * A classificação já existe por vértice da malha; aqui cada pixel pega a
     * célula em que cai. Fica mais grosseiro que o desenho 2D, e é o bastante:
     * a pergunta em 3D é "onde chega", não "onde termina no metro".
     */
    private static WritableImage tingirFoto(WritableImage foto, int[] faixaPorVertice, int lado,
                                            List<Color> cores) {
        int w = (int) foto.getWidth(), h = (int) foto.getHeight();
        if (cores.isEmpty() || faixaPorVertice == null) return foto;

        WritableImage out = new WritableImage(w, h);
        var leitor = foto.getPixelReader();
        PixelWriter pw = out.getPixelWriter();
        for (int y = 0; y < h; y++) {
            int r = Math.min(lado - 1, y * lado / h);
            for (int x = 0; x < w; x++) {
                int c = Math.min(lado - 1, x * lado / w);
                int faixa = faixaPorVertice[r * lado + c];
                Color base = leitor.getColor(x, y);
                if (faixa <= 0 || faixa > cores.size()) { pw.setColor(x, y, base); continue; }
                // Sobre foto de satelite, que e' escura e sem saturacao, uma
                // tinta leve some: a cobertura virava sombra. Puxa a cor para
                // perto do tom da faixa e clareia um pouco, para ela se impor
                // sem apagar o que da' para reconhecer embaixo.
                Color alvo = cores.get(faixa - 1);
                pw.setColor(x, y, base.interpolate(alvo, 0.62).deriveColor(0, 1.15, 1.12, 1));
            }
        }
        return out;
    }

    /**
     * Risca o limite do levantamento sobre a textura.
     *
     * Vai na imagem, e não como linha 3D: assim o traço acompanha o terreno
     * por baixo sem nenhum custo de desenho, e não some atrás de um morro nem
     * flutua sobre um vale — que é o que aconteceria com uma linha reta
     * ligando dois pontos distantes.
     */
    private static WritableImage riscarContorno(WritableImage base, boolean podeAlterar,
                                                List<double[]> aneis,
                                                double minX, double minY,
                                                double maxX, double maxY) {
        if (aneis == null || aneis.isEmpty()) return base;
        int w = (int) base.getWidth(), h = (int) base.getHeight();

        // Riscar direto na imagem recebida so vale quando ela e' descartavel.
        // Se for a foto original, alterar aqui estragaria a base de todos os
        // proximos desenhos — e o estrago so apareceria na terceira ou quarta
        // vez que se mexesse numa caixa.
        WritableImage out = podeAlterar ? base : new WritableImage(w, h);
        var leitor = base.getPixelReader();
        PixelWriter pw = out.getPixelWriter();
        if (!podeAlterar) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) pw.setArgb(x, y, leitor.getArgb(x, y));
            }
        }

        // Os aneis ja em pixel, para nao reconverter a cada linha varrida.
        List<double[]> emPixel = new java.util.ArrayList<>();
        for (double[] anel : aneis) {
            double[] q = new double[anel.length];
            for (int i = 0; i < anel.length; i += 2) {
                q[i] = px(anel[i], minX, maxX, w);
                q[i + 1] = py(anel[i + 1], minY, maxY, h);
            }
            emPixel.add(q);
        }

        // Fora da area levantada, o terreno escurece. So o traco nao bastaria:
        // quando a nuvem cobre exatamente a ortofoto, o contorno cai em cima da
        // silhueta da foto e some. Escurecendo, da para ver de relance ate onde
        // a altitude foi medida, em vez de estimada.
        double[] cortes = new double[64];
        for (int y = 0; y < h; y++) {
            int n = cruzamentos(emPixel, y + 0.5, cortes);
            if (n > cortes.length) {           // ring muito recortado: refaz maior
                cortes = new double[n + 16];
                n = cruzamentos(emPixel, y + 0.5, cortes);
            }
            java.util.Arrays.sort(cortes, 0, n);
            int k = 0;
            for (int x = 0; x < w; x++) {
                while (k < n && cortes[k] <= x) k++;
                if ((k & 1) == 1) continue;    // par-impar: impar = dentro
                pw.setColor(x, y, escurecer(leitor.getColor(x, y)));
            }
        }

        Color risco = Color.web("#b388ff");   // o mesmo violeta do contorno no mapa 2D
        int espessura = Math.max(3, w / 260);
        for (double[] q : emPixel) {
            int n = q.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                traco(pw, w, h, risco, espessura,
                        q[2 * i], q[2 * i + 1], q[2 * j], q[2 * j + 1]);
            }
        }
        return out;
    }

    /** Onde a linha y corta as arestas dos aneis. Devolve quantos cortes ha. */
    private static int cruzamentos(List<double[]> aneis, double y, double[] saida) {
        int n = 0;
        for (double[] q : aneis) {
            int m = q.length / 2;
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                double y0 = q[2 * i + 1], y1 = q[2 * j + 1];
                // Regra meio-aberta: um vertice exatamente na linha conta uma
                // vez so, senao a paridade vira do avesso naquela altura.
                if ((y0 <= y) == (y1 <= y)) continue;
                double t = (y - y0) / (y1 - y0);
                if (n < saida.length) saida[n] = q[2 * i] + (q[2 * j] - q[2 * i]) * t;
                n++;
            }
        }
        return n;
    }

    /** Tom de fora da area: escuro e sem cor, para nao competir com a foto. */
    private static Color escurecer(Color c) {
        double cinza = 0.30 * c.getRed() + 0.59 * c.getGreen() + 0.11 * c.getBlue();
        return new Color(cinza * 0.42, cinza * 0.44, cinza * 0.52, c.getOpacity());
    }

    private static double px(double wx, double minX, double maxX, int w) {
        return (wx - minX) / (maxX - minX) * (w - 1);
    }

    private static double py(double wy, double minY, double maxY, int h) {
        return (wy - minY) / (maxY - minY) * (h - 1);
    }

    /** Segmento de reta na imagem, com espessura. */
    private static void traco(PixelWriter pw, int w, int h, Color cor, int esp,
                              double x0, double y0, double x1, double y1) {
        double dist = Math.hypot(x1 - x0, y1 - y0);
        int passos = (int) Math.ceil(dist) + 1;
        for (int k = 0; k <= passos; k++) {
            double t = passos == 0 ? 0 : k / (double) passos;
            int cx = (int) Math.round(x0 + (x1 - x0) * t);
            int cy = (int) Math.round(y0 + (y1 - y0) * t);
            for (int dy = -esp; dy <= esp; dy++) {
                for (int dx = -esp; dx <= esp; dx++) {
                    int px = cx + dx, py = cy + dy;
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;
                    pw.setColor(px, py, cor);
                }
            }
        }
    }

    private static Color corDaAltitude(double t) {
        if (t < 0.45) return Color.web("#2e6b3e").interpolate(Color.web("#7a9b3f"), t / 0.45);
        if (t < 0.75) return Color.web("#7a9b3f").interpolate(Color.web("#b08d4a"), (t - 0.45) / 0.30);
        return Color.web("#b08d4a").interpolate(Color.web("#e8e2d6"), (t - 0.75) / 0.25);
    }

    /** Altura, em pixels, de cada faixa dentro da textura. */
    private static final int LINHA_PX = 4;

    /**
     * Textura do terreno: altitude no eixo horizontal, cobertura no vertical.
     *
     * A primeira linha e' o relevo puro. Cada linha seguinte e' o mesmo relevo
     * tingido pela cor de uma faixa de sinal, para a cobertura aparecer SOBRE o
     * terreno em vez de escondê-lo — continua dando para ver onde o morro sobe
     * dentro da area coberta, que e' a pergunta que trouxe alguem ao 3D.
     *
     * @param cores cor de cada faixa, da melhor para a pior
     */
    private static WritableImage textura(List<Color> cores) {
        int linhas = 1 + cores.size();
        WritableImage img = new WritableImage(256, linhas * LINHA_PX);
        PixelWriter pw = img.getPixelWriter();
        for (int i = 0; i < 256; i++) {
            Color base = corDaAltitude(i / 255.0);
            for (int y = 0; y < LINHA_PX; y++) pw.setColor(i, y, base);
            for (int f = 0; f < cores.size(); f++) {
                // Tingir demais apaga o sombreado e o 3D vira mancha chapada;
                // o relevo tem que continuar legivel por baixo da cobertura.
                Color tingido = base.interpolate(cores.get(f), 0.38);
                for (int y = 0; y < LINHA_PX; y++) {
                    pw.setColor(i, (f + 1) * LINHA_PX + y, tingido);
                }
            }
        }
        return img;
    }

    // ------------------------ Torres e enlace ------------------------

    private static List<Node> torres(double[] alt, int lado, double minX, double minY,
                                     double maxX, double maxY,
                                     double larguraM, double profundM, double baseH,
                                     double exagero, double escala,
                                     NetworkPoint a, Radio radioA,
                                     NetworkPoint b, Radio radioB, boolean comLinha) {
        java.util.ArrayList<Node> out = new java.util.ArrayList<>();

        Point3D topoA = torre(out, alt, lado, minX, minY, maxX, maxY, larguraM, profundM,
                baseH, exagero, escala, a, radioA, Color.web("#29b6f6"));
        Point3D topoB = b == null ? null
                : torre(out, alt, lado, minX, minY, maxX, maxY, larguraM, profundM,
                        baseH, exagero, escala, b, radioB, Color.web("#ffa726"));

        if (comLinha && topoA != null && topoB != null) {
            out.add(cilindroEntre(topoA, topoB, escala * LINHA, Color.web("#ffffff")));
        }
        return out;
    }

    /** Desenha o mastro e devolve a cota do topo da antena, em coordenadas da cena. */
    private static Point3D torre(List<Node> out, double[] alt, int lado,
                                 double minX, double minY, double maxX, double maxY,
                                 double larguraM, double profundM, double baseH,
                                 double exagero, double escala,
                                 NetworkPoint np, Radio radio, Color cor) {
        double fx = (np.getX() - minX) / (maxX - minX);
        double fy = (np.getY() - minY) / (maxY - minY);
        if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return null;

        int c = (int) Math.round(fx * (lado - 1));
        int r = (int) Math.round(fy * (lado - 1));
        // A amostra bem sob a torre pode ser uma das que ficaram sem dado. Um
        // NaN aqui propaga para a posicao do mastro e o JavaFX simplesmente
        // nao desenha o no — a torre sumia sem erro nenhum.
        double solo = soloPerto(alt, lado, r, c);
        if (Double.isNaN(solo)) {
            Log.warn("Terreno 3D: sem altitude sob \"%s\"; torre nao desenhada", np.getName());
            return null;
        }

        double x = (fx - 0.5) * larguraM;
        double z = -(fy - 0.5) * profundM;
        double ySolo = -(solo - baseH) * exagero;

        // A altura vem do cadastro, respeitando o modo de altitude: altura de
        // instalacao soma o solo, cota absoluta ja e' a final.
        double alturaAntena = radio == null ? 10 : Math.max(0, radio.heightAboveGroundM(solo));
        double h = alturaAntena * exagero;

        // O mastro tem a altura de verdade — antes um piso de tamanho o
        // esticava para caber na tela, e uma antena de 4 m aparecia como se
        // fosse de 30. Quem olha o desenho tem que poder confiar nele. Quando
        // a antena e' baixa demais para render um risco visivel, quem marca a
        // posicao e' a esfera no topo, que tem tamanho de simbolo.
        Cylinder mastro = new Cylinder(escala * MASTRO, Math.max(h, 0.001));
        PhongMaterial matMastro = new PhongMaterial(cor);
        // Auto-iluminado: o mastro e' marcador, e nao deve sumir na sombra do
        // morro justamente quando se olha o morro.
        matMastro.setSelfIlluminationMap(corSolida(cor.deriveColor(0, 1, 0.55, 1)));
        mastro.setMaterial(matMastro);
        mastro.setTranslateX(x);
        mastro.setTranslateZ(z);
        mastro.setTranslateY(ySolo - h / 2);
        out.add(mastro);

        Sphere topo = new Sphere(escala * ESFERA);
        PhongMaterial matTopo = new PhongMaterial(cor.brighter());
        matTopo.setSelfIlluminationMap(corSolida(cor.deriveColor(0, 1, 0.8, 1)));
        topo.setMaterial(matTopo);
        topo.setTranslateX(x);
        topo.setTranslateZ(z);
        topo.setTranslateY(ySolo - h);
        out.add(topo);

        return new Point3D(x, ySolo - h, z);
    }

    /**
     * Em que faixa de sinal cai cada vértice do terreno.
     *
     * Todos os níveis presentes, do melhor para o pior, sem repetir.
     *
     * Dois rádios podem ter sido simulados com bordas diferentes, e cada grade
     * traz a sua lista. A tela precisa de uma só, senão a mesma cor diria
     * coisas diferentes em cada pedaço do relevo.
     */
    private static double[] niveisDe(
            List<com.colmeia.radiomapper.rf.BeamCoverage.Cobertura> coberturas) {
        java.util.TreeSet<Double> set = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        if (coberturas != null) {
            for (var g : coberturas) {
                if (g == null) continue;
                for (double d : g.niveis()) set.add(d);
            }
        }
        double[] v = new double[set.size()];
        int i = 0;
        for (double d : set) v[i++] = d;
        return v;
    }

    /**
     * Em que faixa de sinal cada vértice do relevo cai.
     *
     * Consulta a MESMA grade que o mapa desenha, célula por célula, em vez de
     * testar polígonos: além de ser uma busca direta em vez de varrer
     * contornos, é o que garante que as duas telas concordem sobre onde há
     * sinal — inclusive sobre os buracos de sombra.
     *
     * @return índice da LINHA na textura: 0 = fora do alcance, 1..n = faixas
     */
    private static int[] coberturaPorVertice(
            List<com.colmeia.radiomapper.rf.BeamCoverage.Cobertura> coberturas,
            int lado, double[] niveis,
            double minX, double minY, double maxX, double maxY) {
        int[] out = new int[lado * lado];
        if (coberturas == null || coberturas.isEmpty() || niveis.length == 0) return out;

        for (int r = 0; r < lado; r++) {
            double wy = minY + (maxY - minY) * r / (lado - 1.0);
            for (int c = 0; c < lado; c++) {
                double wx = minX + (maxX - minX) * c / (lado - 1.0);
                // Com mais de um radio vale o MELHOR sinal do lugar: e' o que
                // um cliente ali receberia, escolhendo o melhor AP.
                int melhor = -1;
                for (var g : coberturas) {
                    if (g == null) continue;
                    int n = g.nivelEm(wx, wy);
                    if (n < 0) continue;
                    // A grade guarda o indice dentro dos SEUS niveis; a tela
                    // pinta pela lista unificada das coberturas ligadas.
                    int idx = indiceDoNivel(niveis, g.niveis()[n]);
                    if (idx >= 0 && (melhor < 0 || idx < melhor)) melhor = idx;
                }
                out[r * lado + c] = melhor < 0 ? 0 : melhor + 1;
            }
        }
        return out;
    }

    private static int indiceDoNivel(double[] niveis, double dbm) {
        for (int i = 0; i < niveis.length; i++) {
            if (niveis[i] == dbm) return i;
        }
        return -1;
    }

    /** Etiqueta flutuante com o nome do ponto e a altura da antena. */
    private static Label rotulo3D(NetworkPoint np, Radio radio, Color cor) {
        String alt = radio == null ? "" : String.format("  ·  %.0f m", radio.getAntennaHeightM());
        Label l = new Label(np.getName() + alt);
        l.setFont(Font.font("System", FontWeight.BOLD, 12));
        l.setStyle("-fx-background-color: rgba(20,23,28,0.78); -fx-padding: 2 6 2 6;"
                + "-fx-background-radius: 3; -fx-text-fill: " + paraHex(cor) + ";");
        l.setMouseTransparent(true);
        return l;
    }

    private static String paraHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /**
     * Põe a etiqueta em cima do nó 3D que ela descreve.
     *
     * {@code localToScene} já devolve a posição depois da projeção em
     * perspectiva, então basta converter para as coordenadas do painel.
     */
    private static void seguir(Label rotulo, Node marca, Pane holder) {
        if (rotulo == null) return;
        if (marca == null) { rotulo.setVisible(false); return; }
        // O "true" pede a cena RAIZ. Sem ele, um no dentro de SubScene
        // devolve coordenadas da propria SubScene, e converte-las de novo pelo
        // holder descontava a barra de cima duas vezes — os rotulos iam parar
        // fora da area visivel e se escondiam sozinhos.
        javafx.geometry.Point3D p = marca.localToScene(0, 0, 0, true);
        javafx.geometry.Point2D local = holder.sceneToLocal(p.getX(), p.getY());
        if (local == null) { rotulo.setVisible(false); return; }
        rotulo.applyCss();
        rotulo.autosize();
        rotulo.setLayoutX(local.getX() - rotulo.getWidth() / 2);
        rotulo.setLayoutY(local.getY() - rotulo.getHeight() - 10);
        // Fora da área visível o rótulo some, em vez de encostar na borda
        // apontando para nada.
        rotulo.setVisible(local.getX() >= 0 && local.getX() <= holder.getWidth()
                && local.getY() >= 0 && local.getY() <= holder.getHeight());
    }

    /** Imagem de um pixel, para usar uma cor chapada como mapa de material. */
    private static WritableImage corSolida(Color c) {
        WritableImage img = new WritableImage(2, 2);
        PixelWriter pw = img.getPixelWriter();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) pw.setColor(x, y, c);
        return img;
    }

    /** Altitude sob a torre, varrendo para os lados quando a amostra exata falta. */
    private static double soloPerto(double[] alt, int lado, int r, int c) {
        for (int raio = 0; raio < 12; raio++) {
            for (int dr = -raio; dr <= raio; dr++) {
                for (int dc = -raio; dc <= raio; dc++) {
                    int rr = r + dr, cc = c + dc;
                    if (rr < 0 || cc < 0 || rr >= lado || cc >= lado) continue;
                    double v = alt[rr * lado + cc];
                    if (!Double.isNaN(v)) return v;
                }
            }
        }
        return Double.NaN;
    }

    /**
     * Um cilindro ligando dois pontos do espaço.
     *
     * O Cylinder do JavaFX nasce em pé no eixo Y; para deitá-lo na direção
     * certa, gira-se em torno do eixo perpendicular aos dois — o produto
     * vetorial entre o Y e a direção desejada.
     */
    private static Node cilindroEntre(Point3D de, Point3D para, double raio, Color cor) {
        Point3D dir = para.subtract(de);
        double comprimento = dir.magnitude();
        Point3D meio = de.midpoint(para);

        Cylinder c = new Cylinder(raio, comprimento);
        PhongMaterial m = new PhongMaterial(cor);
        // Auto-iluminada pelo mesmo motivo dos mastros: a linha do enlace e' a
        // resposta que se veio buscar, e nao pode escurecer na sombra do morro.
        m.setSelfIlluminationMap(corSolida(cor.deriveColor(0, 1, 0.75, 1)));
        c.setMaterial(m);
        c.setTranslateX(meio.getX());
        c.setTranslateY(meio.getY());
        c.setTranslateZ(meio.getZ());

        Point3D eixoY = new Point3D(0, 1, 0);
        Point3D perpendicular = eixoY.crossProduct(dir);
        if (perpendicular.magnitude() > 1e-9) {
            c.getTransforms().add(new Rotate(eixoY.angle(dir), perpendicular));
        }
        return c;
    }
}
