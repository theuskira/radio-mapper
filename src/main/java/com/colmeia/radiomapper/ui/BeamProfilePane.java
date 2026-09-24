package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.ElevationChain;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.TerrainTiles;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.rf.LinkBudget;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.util.function.DoubleUnaryOperator;

/**
 * Perfil lateral contra o relevo, em dois modos.
 *
 * <b>Feixe único</b>: o corte do terreno ao longo do azimute de um rádio, com
 * o envelope vertical dele. Responde "até onde enxergo nessa direção".
 *
 * <b>Enlace</b>: quando os dois rádios estão ligados, desenha os DOIS feixes
 * se encontrando — um saindo de cada ponta — e destaca a região onde eles se
 * sobrepõem. Responde as perguntas que importam num enlace: o terreno corta o
 * caminho? cada antena está apontada de fato para a outra? e quanto dos dois
 * feixes efetivamente se cruza?
 *
 * <h3>Curvatura da Terra</h3>
 * A Terra cai debaixo do enlace: ~6 m em 10 km, mais que a altura de muita
 * antena. Usa-se o raio efetivo 4/3, convenção de rádio-enlace, porque a
 * atmosfera encurva o sinal para baixo e compensa parte da queda.
 */
public class BeamProfilePane extends VBox {

    private static final double EARTH_EFFECTIVE_R = 6371000.0 * 4.0 / 3.0;
    private static final int SAMPLES = 240;
    /**
     * Folgas do desenho.
     *
     * As laterais são generosas de propósito: as antenas ficam nas duas
     * extremidades do eixo, e com pouca folga o mastro e o nome do rádio
     * encostavam na borda do painel, ficando ilegíveis.
     */
    private static final double PAD_LEFT = 58, PAD_RIGHT = 58, PAD_TOP = 26, PAD_BOTTOM = 30;

    private enum Mode { EMPTY, SINGLE, LINK }

    private final Canvas canvas = new Canvas(800, 200);
    private final Label header = new Label();
    private final Label verdict = new Label();
    /** Indicadores numéricos — mais fáceis de varrer que um parágrafo. */
    private final javafx.scene.layout.FlowPane stats = new javafx.scene.layout.FlowPane(14, 4);
    /** Azimute do enlace, guardado para exibir. */
    private double linkAzimuth;

    private Mode mode = Mode.EMPTY;
    private Radio radioA, radioB;
    private double[] dist;
    private double[] ground;
    private double baseA, baseB;   // altitude do solo sob cada torre, na posicao atual
    private double totalM;

    /**
     * Deslocamento de cada torre ao longo do eixo do enlace, em metros.
     *
     * Existe para responder "e se eu subisse o morro mais 200 m?" sem ter que
     * arrastar o ponto no mapa, reabrir o perfil e comparar de memoria. O
     * terreno ao longo da linha ja foi amostrado; mover a torre e' so escolher
     * outro ponto de partida dentro do que ja esta em maos.
     *
     * Negativo em A afasta a torre do outro lado; positivo aproxima. Em B e' o
     * contrario, porque B mora na ponta direita do grafico.
     *
     * Enquanto e' diferente de zero, o desenho mostra uma hipotese, nao o
     * projeto — dai o marcador e o botao de aplicar.
     */
    private double deslocA, deslocB;
    /** Ponto de cada ponta no mapa, para poder aplicar o deslocamento. */
    private NetworkPoint pontoA, pontoB;
    /** Unidades de mundo por metro de chao neste enlace. */
    private double worldPorMetro = 1;
    /** Vetor unitario A->B em coordenadas de mundo. */
    private double dirWorldX, dirWorldY;
    /** Qual torre esta sendo arrastada: 0 nenhuma, 1 = A, 2 = B. */
    private int arrastandoTorre;

    private javafx.scene.control.Button btnAplicar, btnDesfazer;
    private Label lblDeslocamento;
    private javafx.scene.control.Button btn3D;

    /**
     * Quem abre o terreno 3D a partir daqui.
     *
     * O perfil é onde se está olhando o enlace quando a dúvida aparece; mandar
     * o usuário ao menu principal para ver o mesmo enlace em relevo era uma
     * volta sem motivo.
     */
    public interface OnOpen3D {
        void abrir();
    }

    private OnOpen3D onOpen3D;

    public void setOnOpen3D(OnOpen3D h) {
        this.onOpen3D = h;
        if (btn3D != null) btn3D.setDisable(h == null);
    }
    /**
     * Faixa desenhada no eixo X, em metros.
     *
     * Vai ALÉM das antenas dos dois lados: sem isso os mastros ficavam
     * exatamente na borda do gráfico, sem nada atrás, e não dava para ver se
     * o terreno sobe logo atrás da torre — que é o que decide se vale subir
     * mais a antena ou mudar de lugar.
     */
    private double plotMinD, plotMaxD;
    /** Índices em {@link #dist} mais próximos das duas antenas. */
    private int idxA, idxB;
    /** Sinal realmente medido no enlace, quando há. Null = enlace sem leitura. */
    private Double medidoDbm;
    /**
     * Enlace desenhado a mão para planejamento, ainda sem nada no ar.
     *
     * Muda só o texto: o cálculo é o mesmo, porque sempre foi estimativa. O
     * que muda é a leitura de "sem sinal medido" — num enlace real isso é
     * sintoma, aqui é o esperado.
     */
    private boolean planned;
    private boolean ready;
    private String message = "Selecione um rádio com feixe para ver o perfil.";

    // ------------------------ Estado da vista ------------------------
    /**
     * Zoom e deslocamento aplicados sobre o enquadramento automático.
     *
     * A escala natural espreme um enlace de 10 km em 800 px: dá para ver a
     * forma geral, mas não os 2 m de folga sobre um morro no meio do caminho —
     * que é justamente o que decide o enlace. O zoom existe para isso.
     *
     * O deslocamento é em pixels de tela, em torno do centro da área de
     * plotagem, e fica limitado para o gráfico nunca ser arrastado para fora
     * da vista: em zoom 1 não há o que deslocar, e o arrasto trava sozinho.
     */
    private double zoom = 1, panX = 0, panY = 0;
    private static final double ZOOM_MIN = 1, ZOOM_MAX = 40;

    /** Qual feixe está sob o cursor: 0 = nenhum, 1 = A, 2 = B. */
    private int hovered = 0;
    /** Última posição do mouse no canvas; -1 quando o cursor saiu. */
    private double mouseX = -1, mouseY = -1;
    private boolean arrastando;
    private double arrastoX, arrastoY;
    /** Faixa do enquadramento automático (antes do zoom), preenchida ao pintar. */
    private double fitD0, fitD1, fitH0, fitH1;

    public BeamProfilePane() {
        setPadding(new Insets(6, 8, 6, 8));
        setSpacing(4);
        setStyle("-fx-background-color: #232323; -fx-border-color: #3a3a3a; -fx-border-width: 1 0 0 0;");

        header.setStyle("-fx-text-fill: #e8e8e8;");
        header.setFont(Font.font("System", FontWeight.BOLD, 12));
        verdict.setStyle("-fx-text-fill: #bbb; -fx-font-size: 11;");
        verdict.setWrapText(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // So aparecem quando ha o que aplicar: botao permanentemente
        // desabilitado vira ruido que se aprende a ignorar.
        btnAplicar = new javafx.scene.control.Button("Aplicar no mapa");
        btnAplicar.setStyle("-fx-font-size: 11;");
        btnAplicar.setOnAction(e -> aplicarDeslocamento());
        btnDesfazer = new javafx.scene.control.Button("Desfazer");
        btnDesfazer.setStyle("-fx-font-size: 11;");
        btnDesfazer.setOnAction(e -> resetTorres());
        lblDeslocamento = new Label();
        lblDeslocamento.setStyle("-fx-text-fill: #ffcc80; -fx-font-size: 11;");
        mostrarBotoesMover(false);

        btn3D = new javafx.scene.control.Button("Ver em 3D");
        btn3D.setStyle("-fx-font-size: 11;");
        btn3D.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        btn3D.setOnAction(e -> { if (onOpen3D != null) onOpen3D.abrir(); });
        btn3D.setDisable(true);
        btn3D.setVisible(false);
        btn3D.setManaged(false);

        HBox bar = new HBox(10, header, spacer, lblDeslocamento, btn3D, btnDesfazer, btnAplicar);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Pane holder = new Pane(canvas);
        holder.setMinHeight(150);
        VBox.setVgrow(holder, Priority.ALWAYS);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());
        installViewGestures();

        stats.setPadding(new Insets(2, 0, 0, 0));

        getChildren().addAll(bar, stats, holder, verdict);
        setPrefHeight(270);
        setMinHeight(200);
        clear();
    }

    /**
     * Rolar amplia, arrastar desloca, duplo clique volta ao enquadramento.
     *
     * O zoom é ancorado no ponto sob o cursor — ampliar leva a vista para
     * onde se está olhando, em vez de para o centro do gráfico, que é o que
     * faz procurar um obstáculo específico não virar caça ao tesouro.
     */
    private void installViewGestures() {
        canvas.setOnScroll(ev -> {
            if (!ready) return;
            double fator = Math.pow(1.0015, ev.getDeltaY());
            zoomAt(ev.getX(), ev.getY(), zoom * fator);
            ev.consume();
        });
        canvas.setOnMousePressed(ev -> {
            // Pegar a torre tem prioridade sobre deslocar a vista: quem clicou
            // em cima do mastro queria a torre, nao o pan.
            arrastandoTorre = ready && mode == Mode.LINK ? torreSob(ev.getX(), ev.getY()) : 0;
            arrastando = arrastandoTorre == 0;
            arrastoX = ev.getX();
            arrastoY = ev.getY();
            if (arrastandoTorre != 0) canvas.setCursor(Cursor.H_RESIZE);
            else if (zoom > 1) canvas.setCursor(Cursor.CLOSED_HAND);
        });
        canvas.setOnMouseDragged(ev -> {
            if (!ready) return;
            if (arrastandoTorre != 0) {
                moverTorre(arrastandoTorre, distAtPixel(ev.getX()));
                mouseX = ev.getX();
                mouseY = ev.getY();
                return;   // moverTorre ja redesenha
            }
            if (!arrastando) return;
            panX += ev.getX() - arrastoX;
            panY += ev.getY() - arrastoY;
            arrastoX = ev.getX();
            arrastoY = ev.getY();
            mouseX = ev.getX();
            mouseY = ev.getY();
            draw();
        });
        canvas.setOnMouseReleased(ev -> {
            arrastando = false;
            arrastandoTorre = 0;
            canvas.setCursor(zoom > 1 ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        });
        canvas.setOnMouseMoved(ev -> {
            mouseX = ev.getX();
            mouseY = ev.getY();
            // Cursor de redimensionar avisa que ali da para pegar a torre; sem
            // isso a funcao existiria sem nada indicando que existe.
            if (ready && mode == Mode.LINK && torreSob(ev.getX(), ev.getY()) != 0) {
                canvas.setCursor(Cursor.H_RESIZE);
            } else {
                canvas.setCursor(zoom > 1 ? Cursor.OPEN_HAND : Cursor.DEFAULT);
            }
            draw();
        });
        canvas.setOnMouseExited(ev -> {
            // Sem isto o feixe continuaria destacado depois que o mouse saiu.
            mouseX = mouseY = -1;
            draw();
        });
        canvas.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) resetView();
        });
    }

    /**
     * Qual torre esta sob o cursor: 1 = A, 2 = B, 0 = nenhuma.
     *
     * A area sensivel e' uma faixa em torno do mastro, larga o bastante para
     * pegar com o mouse e estreita o bastante para nao roubar o pan.
     */
    private int torreSob(double px, double py) {
        if (dist == null) return 0;
        double xa = screenX(posA()), xb = screenX(posB());
        double alvo = 14;
        boolean naFaixaVertical = py >= PAD_TOP && py <= PAD_TOP + plotH();
        if (!naFaixaVertical) return 0;
        if (Math.abs(px - xa) <= alvo) return 1;
        if (Math.abs(px - xb) <= alvo) return 2;
        return 0;
    }

    /**
     * Move uma torre para a posicao indicada no eixo.
     *
     * Limites: cada torre fica dentro do trecho amostrado e nao passa da
     * outra — duas torres invertidas dariam um enlace de comprimento negativo
     * e todo o resto da conta viraria absurdo.
     */
    private void moverTorre(int qual, double novaPos) {
        double folga = 50;   // nao deixa as duas se encostarem
        if (qual == 1) {
            double min = plotMinD;
            double max = posB() - folga;
            deslocA = Math.max(min, Math.min(max, novaPos));
        } else {
            double min = posA() + folga;
            double max = plotMaxD;
            deslocB = Math.max(min, Math.min(max, novaPos)) - totalM;
        }
        recomputarBases();
        buildLinkStats();
        draw();
    }

    /** Devolve as torres ao lugar do projeto. */
    public void resetTorres() {
        if (!movido()) return;
        deslocA = deslocB = 0;
        recomputarBases();
        buildLinkStats();
        draw();
    }

    /**
     * Mostra o botão só quando há o que abrir.
     *
     * A escolha de quais feixes desenhar ficou DENTRO da janela 3D: decidir
     * antes de abrir obrigava a fechar e reabrir só para comparar um rádio com
     * o outro, que é justamente o que se quer fazer lá.
     */
    private void montarMenu3D() {
        if (btn3D == null) return;
        boolean tem = ready && radioA != null && onOpen3D != null;
        btn3D.setVisible(tem);
        btn3D.setManaged(tem);
        btn3D.setDisable(!tem);
    }

    /** Mostra ou esconde os controles de "torre fora do lugar". */
    private void mostrarBotoesMover(boolean v) {
        if (btnAplicar == null) return;
        btnAplicar.setVisible(v); btnAplicar.setManaged(v);
        btnDesfazer.setVisible(v); btnDesfazer.setManaged(v);
        lblDeslocamento.setVisible(v); lblDeslocamento.setManaged(v);
        btnAplicar.setDisable(onRelocate == null);
        if (v) {
            StringBuilder sb = new StringBuilder("hipotese: ");
            if (deslocA != 0) sb.append(String.format("%s %+.0f m  ", nameOf(radioA), deslocA));
            if (deslocB != 0) sb.append(String.format("%s %+.0f m", nameOf(radioB), deslocB));
            lblDeslocamento.setText(sb.toString().trim());
        }
    }

    /**
     * Quem aplica no mapa o deslocamento explorado aqui.
     *
     * O perfil nao mexe no projeto por conta propria: arrastar e' hipotese, e
     * so vira edicao quando alguem pede. Null desliga o botao de aplicar.
     */
    public interface OnRelocate {
        void accept(NetworkPoint ponto, double novoX, double novoY);
    }

    private OnRelocate onRelocate;

    public void setOnRelocate(OnRelocate r) { this.onRelocate = r; }

    /**
     * O que o perfil descobriu e o mapa precisa mostrar.
     *
     * O perfil e o mapa contam a mesma historia por dois angulos: um de lado,
     * outro de cima. Estas duas marcas sao o que so o perfil sabe calcular e
     * so o mapa sabe situar — onde a torre iria parar, e em que ponto do chao
     * o morro entra na frente.
     */
    public interface MapHints {
        /** Torre deslocada na hipotese. {@code ponto} null limpa o fantasma. */
        void torreEm(NetworkPoint ponto, double worldX, double worldY);

        /** Pior intrusao do terreno. {@code metros} <= 0 limpa a marca. */
        void obstrucaoEm(double worldX, double worldY, double metros);
    }

    private MapHints mapHints;

    public void setMapHints(MapHints h) { this.mapHints = h; }

    /**
     * Converte uma distancia no eixo do enlace para coordenada de mundo.
     *
     * A origem do eixo e' a posicao ORIGINAL da torre A — a mesma de onde as
     * amostras de terreno sairam —, entao a conta vale mesmo com as torres
     * deslocadas.
     */
    private double[] eixoParaMundo(double d) {
        if (pontoA == null) return null;
        return new double[] {
            pontoA.getX() + dirWorldX * d * worldPorMetro,
            pontoA.getY() + dirWorldY * d * worldPorMetro
        };
    }

    /** Manda ao mapa o que mudou: fantasmas das torres e a obstrucao. */
    private void publicarHints() {
        if (mapHints == null) return;

        if (pontoA != null) {
            if (deslocA != 0) {
                double[] w = eixoParaMundo(posA());
                mapHints.torreEm(pontoA, w[0], w[1]);
            } else {
                mapHints.torreEm(pontoA, Double.NaN, Double.NaN);
            }
        }
        if (pontoB != null) {
            if (deslocB != 0) {
                double[] w = eixoParaMundo(posB());
                mapHints.torreEm(pontoB, w[0], w[1]);
            } else {
                mapHints.torreEm(pontoB, Double.NaN, Double.NaN);
            }
        }

        if (piorObstM > 0 && piorObstD != Double.MAX_VALUE) {
            double[] w = eixoParaMundo(piorObstD);
            if (w != null) mapHints.obstrucaoEm(w[0], w[1], piorObstM);
        } else {
            mapHints.obstrucaoEm(Double.NaN, Double.NaN, 0);
        }
    }

    /** Pior intrusao do terreno na visada: distancia no eixo e quantos metros. */
    private double piorObstD = Double.MAX_VALUE;
    private double piorObstM;

    /** Recalcula onde o terreno mais invade a visada. */
    private void acharPiorObstrucao() {
        piorObstD = Double.MAX_VALUE;
        piorObstM = 0;
        if (dist == null || mode != Mode.LINK) return;
        double ang = angleAtoB();
        for (int i = 1; i < dist.length - 1; i++) {
            if (Double.isNaN(ground[i]) || !entreAntenas(i)) continue;
            double excesso = ground[i] - lineA(dist[i], ang);
            if (excesso > piorObstM) { piorObstM = excesso; piorObstD = dist[i]; }
        }
    }

    /**
     * Aplica ao mapa a posicao explorada, movendo os pontos deslocados.
     *
     * Tudo e' lido ANTES do primeiro aviso. Quem recebe o aviso move o ponto
     * e manda refazer este painel — e refazer zera os deslocamentos, o ponto
     * de referencia e a direcao do enlace. Lendo campo por campo ao longo do
     * metodo, a segunda torre era calculada com o estado ja zerado: ela
     * simplesmente nao saia do lugar, e quem tinha movido as duas via so uma
     * ser gravada.
     */
    public void aplicarDeslocamento() {
        if (onRelocate == null || !movido()) return;

        NetworkPoint a = pontoA, b = pontoB;
        double da = deslocA, db = deslocB;
        double dx = dirWorldX, dy = dirWorldY, wpm = worldPorMetro;

        // Zera antes de avisar: se o aviso refizer o painel, o estado ja esta
        // limpo e a reconstrucao nao briga com o que sobrou daqui.
        deslocA = deslocB = 0;

        if (da != 0 && a != null) {
            onRelocate.accept(a, a.getX() + dx * da * wpm, a.getY() + dy * da * wpm);
        }
        if (db != 0 && b != null) {
            onRelocate.accept(b, b.getX() + dx * db * wpm, b.getY() + dy * db * wpm);
        }
        if (mapHints != null) {
            if (a != null) mapHints.torreEm(a, Double.NaN, Double.NaN);
            if (b != null) mapHints.torreEm(b, Double.NaN, Double.NaN);
        }
    }

    private void zoomAt(double px, double py, double novo) {
        double z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, novo));
        if (z == zoom) return;
        // Mantem fixo o ponto sob o cursor: resolve s = c + u*z + p para o
        // novo p, com u = deslocamento do ponto em relacao ao centro.
        double cx = PAD_LEFT + plotW() / 2;
        double cy = PAD_TOP + plotH() / 2;
        panX += (px - cx - panX) / zoom * (zoom - z);
        panY += (py - cy - panY) / zoom * (zoom - z);
        zoom = z;
        canvas.setCursor(zoom > 1 ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        draw();
    }

    /** Fator de ampliacao atual. 1 = enquadramento automatico. */
    double viewZoom() { return zoom; }

    /** Volta ao enquadramento automatico — o estado em que o perfil nasce. */
    public void resetView() {
        zoom = 1; panX = 0; panY = 0;
        canvas.setCursor(Cursor.DEFAULT);
        draw();
    }

    public void clear() {
        resetView();
        mode = Mode.EMPTY;
        radioA = radioB = null;
        planned = false;
        deslocA = deslocB = 0;
        montarMenu3D();
        piorObstM = 0;
        piorObstD = Double.MAX_VALUE;
        mostrarBotoesMover(false);
        if (mapHints != null) {
            if (pontoA != null) mapHints.torreEm(pontoA, Double.NaN, Double.NaN);
            if (pontoB != null) mapHints.torreEm(pontoB, Double.NaN, Double.NaN);
            mapHints.obstrucaoEm(Double.NaN, Double.NaN, 0);
        }
        pontoA = pontoB = null;
        ready = false;
        header.setText("Perfil");
        verdict.setText("Selecione um rádio com feixe para ver o perfil.");
        stats.getChildren().clear();
        draw();
    }

    // ------------------------ Indicadores ------------------------

    /** Um par rótulo/valor da barra de indicadores. */
    private static javafx.scene.Node stat(String rotulo, String valor, String cor) {
        Label l = new Label(rotulo);
        l.setStyle("-fx-text-fill: #8a8a8a; -fx-font-size: 10;");
        Label v = new Label(valor);
        v.setStyle("-fx-text-fill: " + cor + "; -fx-font-size: 12; -fx-font-weight: bold;");
        VBox box = new VBox(0, l, v);
        return box;
    }

    private static javafx.scene.Node stat(String rotulo, String valor) {
        return stat(rotulo, valor, "#e8e8e8");
    }

    private static final String VERDE = "#7ed321";
    private static final String AMARELO = "#f5a623";
    private static final String VERMELHO = "#ff5252";
    /** As mesmas cores dos feixes no gráfico, para o texto amarrar ao desenho. */
    private static final String COR_A = "#4fc3f7";
    private static final String COR_B = "#ffb74d";

    // ------------------------ Entradas ------------------------

    /** Perfil de um feixe só, ao longo do azimute do rádio. */
    public void showBeam(Radio r, NetworkPoint p, ElevationChain elev, boolean mapMode) {
        showBeam(r, p, elev, mapMode, r == null ? 0 : r.getBeamRangeM(), "do cadastro");
    }

    /**
     * @param alcanceM ate onde perfilar, em metros de chao
     * @param origem   de onde saiu esse numero, para o cabecalho nao deixar
     *                 passar um teto por medida
     */
    public void showBeam(Radio r, NetworkPoint p, ElevationChain elev, boolean mapMode,
                         double alcanceM, String origem) {
        if (r == null || p == null) { clear(); return; }
        deslocA = deslocB = 0;
        mostrarBotoesMover(false);
        mode = Mode.SINGLE;
        radioA = r; radioB = null;
        // Feixe solto nao e enlace: a marca do enlace anterior nao pode pegar
        // carona no proximo desenho.
        planned = false;
        header.setText("Feixe: " + nameOf(r) + "   (" + p.getName() + ")"
                + "  — " + MapPane.formatRange(alcanceM) + " " + origem);

        if (!checkPreconditions(mapMode, alcanceM > 0,
                "Sem alcance para perfilar: informe o alcance na aba Feixe, ou "
                + "preencha frequência, ganho e potência para o "
                + "programa estimar.")) return;

        double az = Math.toRadians(r.getBeamAzimuthDeg());
        double dirX = Math.sin(az), dirY = -Math.cos(az);
        linkAzimuth = r.getBeamAzimuthDeg();
        sample(p.getX(), p.getY(), dirX, dirY, alcanceM, elev, () -> {
            baseB = Double.NaN;
            buildBeamStats();
        });
    }

    /**
     * Perfil do enlace entre dois rádios: os dois feixes se encontrando.
     *
     * @param a ponta considerada "AP" (esquerda do gráfico)
     * @param b ponta considerada "cliente" (direita)
     */
    /** Marca o próximo showLink() como enlace planejado. */
    public void setPlanned(boolean v) { this.planned = v; }

    public void showLink(Radio a, NetworkPoint pa, Radio b, NetworkPoint pb,
                         ElevationChain elev, boolean mapMode, Double sinalMedidoDbm) {
        if (a == null || b == null || pa == null || pb == null) { clear(); return; }
        mode = Mode.LINK;
        radioA = a; radioB = b;
        medidoDbm = sinalMedidoDbm;
        // Setas dizem quem está de que lado do gráfico.
        header.setText((planned ? "Enlace planejado:  ◀ " : "Enlace:  ◀ ")
                + nameOf(a) + "   ·   " + nameOf(b) + " ▶");

        double dx = pb.getX() - pa.getX();
        double dy = pb.getY() - pa.getY();
        double worldDist = Math.hypot(dx, dy);
        if (!checkPreconditions(mapMode, worldDist > 0,
                "Os dois rádios estão no mesmo ponto — não há enlace para perfilar.")) return;

        // Distância de MUNDO para distância no CHÃO: em Mercator elas diferem
        // por cos(latitude), o que em 10 km já dá quase 1 km de erro no sul.
        double k = Mercator.groundScaleAt(Mercator.latOfWorldY((pa.getY() + pb.getY()) / 2));
        double groundDist = worldDist * (k <= 0 ? 1 : k);

        // Azimute compass da direção A→B, para mostrar junto dos números.
        linkAzimuth = (Math.toDegrees(Math.atan2(dx / worldDist, -dy / worldDist)) + 360) % 360;

        pontoA = pa;
        pontoB = pb;
        dirWorldX = dx / worldDist;
        dirWorldY = dy / worldDist;
        worldPorMetro = (k <= 0 ? 1 : 1 / k);
        deslocA = deslocB = 0;

        sample(pa.getX(), pa.getY(), dx / worldDist, dy / worldDist, groundDist, elev, () -> {
            baseB = nearestValid(ground, idxB);
            buildLinkStats();
        });
    }

    private boolean checkPreconditions(boolean mapMode, boolean ok, String failMsg) {
        if (!mapMode) {
            ready = false;
            verdict.setText("O perfil precisa de mapa base: sem ele não há coordenada "
                    + "geográfica para consultar altitude.");
            draw();
            return false;
        }
        if (!ok) {
            ready = false;
            verdict.setText(failMsg);
            draw();
            return false;
        }
        return true;
    }

    /**
     * Amostra o terreno numa direção. Vai para uma thread própria porque a
     * fonte de relevo pode precisar baixar tiles, e isso na thread do JavaFX
     * congelaria a janela.
     */
    private void sample(double x0, double y0, double dirX, double dirY,
                        double rangeM, ElevationChain elev, Runnable onDone) {
        // Perfil novo, enquadramento novo: manter o zoom do enlace anterior
        // deixaria a vista parada num canto sem relacao com este.
        resetView();
        ready = false;
        verdict.setText("Levantando o terreno...");
        draw();

        new Thread(() -> {
            double lat = Mercator.latOfWorldY(y0);
            double k = Mercator.groundScaleAt(lat);
            double worldPerM = k <= 0 ? 1 : 1 / k;

            // Amostra um pedaço ANTES da primeira antena e DEPOIS da segunda.
            // A margem é generosa de propósito: é o espaço em que a torre pode
            // ser arrastada para procurar um lugar que escape do obstáculo. Sem
            // ela a exploração esbarraria na borda do gráfico logo no começo.
            double margem = Math.max(300, rangeM * 0.30);
            double inicio = -margem, fim = rangeM + margem;

            double bx0 = x0 + dirX * inicio * worldPerM, by0 = y0 + dirY * inicio * worldPerM;
            double bx1 = x0 + dirX * fim * worldPerM,    by1 = y0 + dirY * fim * worldPerM;
            TerrainTiles.INSTANCE.prefetch(Math.min(bx0, bx1), Math.min(by0, by1),
                                           Math.max(bx0, bx1), Math.max(by0, by1), 20000);

            double[] d = new double[SAMPLES];
            double[] g = new double[SAMPLES];
            int faltando = 0;
            for (int i = 0; i < SAMPLES; i++) {
                double dm = inicio + (fim - inicio) * i / (double) (SAMPLES - 1);
                Double h = elev == null ? null
                        : elev.elevationAt(x0 + dirX * dm * worldPerM, y0 + dirY * dm * worldPerM);
                d[i] = dm;
                g[i] = h == null ? Double.NaN : h;
                if (h == null) faltando++;
            }

            final int semDado = faltando;
            Platform.runLater(() -> {
                dist = d;
                ground = g;
                totalM = rangeM;
                plotMinD = inicio;
                plotMaxD = fim;
                idxA = indiceMaisProximo(d, 0);
                idxB = indiceMaisProximo(d, rangeM);
                // Altitude do SOLO sob a antena. Se faltar dado bem no ponto,
                // usa a amostra válida mais próxima — cair para zero colocaria
                // a antena no nível do mar e inverteria toda a análise.
                baseA = nearestValid(g, idxA);
                ready = semDado < SAMPLES;
                if (!ready) {
                    verdict.setText("Sem dado de altitude nesta área. "
                            + "Verifique a fonte de relevo em Configurações > Relevo.");
                } else {
                    onDone.run();
                    if (semDado > 0) {
                        verdict.setText(verdict.getText()
                                + "  (" + semDado + " de " + SAMPLES + " amostras sem dado)");
                    }
                }
                draw();
            });
        }, "beam-profile").start();
    }

    // ------------------------ Geometria ------------------------

    private static double earthDrop(double d) { return (d * d) / (2 * EARTH_EFFECTIVE_R); }

    /** Índice cuja distância mais se aproxima do valor pedido. */
    private static int indiceMaisProximo(double[] d, double alvo) {
        int melhor = 0;
        double dif = Double.MAX_VALUE;
        for (int i = 0; i < d.length; i++) {
            double x = Math.abs(d[i] - alvo);
            if (x < dif) { dif = x; melhor = i; }
        }
        return melhor;
    }

    /** Posicao da torre A no eixo, em metros. Zero quando nao foi movida. */
    private double posA() { return deslocA; }

    /** Posicao da torre B no eixo. */
    private double posB() { return totalM + deslocB; }

    /** Distancia efetiva entre as duas torres, ja com os deslocamentos. */
    private double spanM() { return Math.max(1, posB() - posA()); }

    /** Alguma torre foi movida? O desenho entao e' hipotese, nao projeto. */
    private boolean movido() { return deslocA != 0 || deslocB != 0; }

    /** A amostra está no trecho entre as duas antenas (fora das margens)? */
    private boolean entreAntenas(int i) {
        return dist[i] >= posA() && dist[i] <= posB();
    }

    /** Altitude do solo numa posicao qualquer do eixo. */
    private double soloEm(double d) {
        if (dist == null || dist.length == 0) return 0;
        return nearestValid(ground, indiceMaisProximo(dist, d));
    }

    /** Recalcula o solo sob cada torre depois de mover alguma. */
    private void recomputarBases() {
        baseA = soloEm(posA());
        if (mode == Mode.LINK) baseB = soloEm(posB());
    }

    /** Amostra válida mais próxima do índice dado, varrendo para os dois lados. */
    private static double nearestValid(double[] g, int from) {
        if (!Double.isNaN(g[from])) return g[from];
        for (int step = 1; step < g.length; step++) {
            int a = from - step, b = from + step;
            if (a >= 0 && !Double.isNaN(g[a])) return g[a];
            if (b < g.length && !Double.isNaN(g[b])) return g[b];
        }
        return 0;
    }

    /**
     * Cota do topo de cada antena, respeitando o modo escolhido no cadastro:
     * altura de instalação soma o terreno, altitude absoluta não.
     */
    private double antennaTopA() { return radioA.antennaTopM(baseA); }
    private double antennaTopB() { return radioB.antennaTopM(baseB); }

    /**
     * "solo 254 + antena 30 = 284 m", ou "cota 284 m (solo 254, mastro 30 m)"
     * quando a altitude foi informada pronta — deixa explícito de onde saiu o
     * número, para dar para conferir em vez de confiar no desenho.
     *
     * No modo absoluto a conta é feita ao contrário: o mastro é deduzido, e se
     * ele sai negativo é sinal de altitude trocada com altura de instalação —
     * o erro que mais estraga um perfil. Por isso vem avisado, não escondido.
     */
    private static String alturaTexto(Radio r, double base, double topo) {
        if (!r.isAbsoluteAltitude()) {
            return String.format("solo %.0f + antena %.0f = %.0f m",
                    base, r.getAntennaHeightM(), topo);
        }
        double mastro = topo - base;
        String s = String.format("cota %.0f m (solo %.0f", topo, base);
        s += mastro >= 0 ? String.format(", mastro %.0f m)", mastro)
                         : String.format(", %.0f m ABAIXO do solo)", -mastro);
        return s;
    }

    /**
     * Altura de uma linha saindo do rádio A, no ângulo dado.
     *
     * A distância é medida a partir da torre, que nem sempre está na origem
     * do eixo: arrastá-la move o vértice do feixe junto.
     */
    private double lineA(double d, double angleDeg) {
        double fwd = d - posA();
        return antennaTopA() + fwd * Math.tan(Math.toRadians(angleDeg)) - earthDrop(fwd);
    }

    /** Idem para o rádio B, que aponta de volta: a distância é medida da direita. */
    private double lineB(double d, double angleDeg) {
        double back = posB() - d;
        return antennaTopB() + back * Math.tan(Math.toRadians(angleDeg)) - earthDrop(back);
    }

    private double loA() { return radioA.getBeamTiltDeg() - radioA.getBeamVerticalWidthDeg() / 2.0; }
    private double hiA() { return radioA.getBeamTiltDeg() + radioA.getBeamVerticalWidthDeg() / 2.0; }
    private double loB() { return radioB.getBeamTiltDeg() - radioB.getBeamVerticalWidthDeg() / 2.0; }
    private double hiB() { return radioB.getBeamTiltDeg() + radioB.getBeamVerticalWidthDeg() / 2.0; }

    /** Ângulo geométrico de A para B, já com a curvatura embutida. */
    private double angleAtoB() {
        double s = spanM();
        return Math.toDegrees(Math.atan(
                (antennaTopB() - antennaTopA() + earthDrop(s)) / s));
    }

    private double angleBtoA() {
        double s = spanM();
        return Math.toDegrees(Math.atan(
                (antennaTopA() - antennaTopB() + earthDrop(s)) / s));
    }

    // ------------------------ Vereditos ------------------------

    /** Indicadores do modo feixe unico. */
    private void buildBeamStats() {
        montarMenu3D();
        stats.getChildren().clear();

        double primeira = -1, folgaMin = Double.MAX_VALUE, terrenoMax = -Double.MAX_VALUE;
        for (int i = 1; i < dist.length; i++) {
            if (Double.isNaN(ground[i]) || !entreAntenas(i)) continue;
            terrenoMax = Math.max(terrenoMax, ground[i]);
            double folga = lineA(dist[i], radioA.getBeamTiltDeg()) - ground[i];
            folgaMin = Math.min(folgaMin, folga);
            if (folga < 0 && primeira < 0) primeira = dist[i];
        }

        stats.getChildren().addAll(
                stat("Alcance", MapPane.formatRange(totalM)),
                stat("Azimute", MapPane.deg(linkAzimuth) + "°"),
                stat("Abertura", MapPane.deg(radioA.getBeamWidthDeg()) + "° H  /  "
                        + MapPane.deg(radioA.getBeamVerticalWidthDeg()) + "° V"),
                stat("Inclinacao", String.format("%+.1f°", radioA.getBeamTiltDeg())),
                stat("Antena de " + nameOf(radioA),
                        alturaTexto(radioA, baseA, antennaTopA()), COR_A),
                stat("Terreno max.", terrenoMax == -Double.MAX_VALUE ? "--"
                        : String.format("%.0f m", terrenoMax)));

        if (primeira >= 0) {
            stats.getChildren().add(stat("Visada",
                    "obstruida em " + MapPane.formatRange(primeira), VERMELHO));
            verdict.setText("O terreno cruza o eixo do feixe: nesta direcao o alcance "
                    + "efetivo termina antes do configurado.");
        } else if (folgaMin != Double.MAX_VALUE) {
            stats.getChildren().add(stat("Folga min.",
                    String.format("%.0f m", folgaMin), folgaMin > 10 ? VERDE : AMARELO));
            verdict.setText("Visada livre em toda a extensao do feixe.");
        } else {
            verdict.setText("Sem dado de altitude suficiente para avaliar.");
        }
    }

    /** Indicadores do modo enlace. */
    private void buildLinkStats() {
        stats.getChildren().clear();
        mostrarBotoesMover(movido());
        montarMenu3D();
        acharPiorObstrucao();
        publicarHints();

        double angAB = angleAtoB();
        double primeira = -1, folgaMin = Double.MAX_VALUE;
        for (int i = 1; i < dist.length - 1; i++) {
            if (Double.isNaN(ground[i]) || !entreAntenas(i)) continue;
            double folga = lineA(dist[i], angAB) - ground[i];
            folgaMin = Math.min(folgaMin, folga);
            if (folga < 0 && primeira < 0) primeira = dist[i];
        }

        double topoA = antennaTopA(), topoB = antennaTopB();

        stats.getChildren().addAll(
                stat("Distancia", MapPane.formatRange(spanM())),
                stat("Azimute", MapPane.deg(linkAzimuth) + "°"),
                stat("Desnivel", String.format("%+.0f m", topoB - topoA)),
                // Nome e cor iguais aos do gráfico: azul à esquerda, laranja
                // à direita. Sem isso não dava para saber qual cone era qual.
                stat("◀ " + nameOf(radioA) + "  (esquerda)",
                        alturaTexto(radioA, baseA, topoA), COR_A),
                stat(nameOf(radioB) + " ▶  (direita)",
                        alturaTexto(radioB, baseB, topoB), COR_B));

        if (primeira >= 0) {
            stats.getChildren().add(stat("Visada",
                    "obstruida em " + MapPane.formatRange(primeira), VERMELHO));
        } else if (folgaMin != Double.MAX_VALUE) {
            stats.getChildren().add(stat("Folga min.",
                    String.format("%.0f m", folgaMin), folgaMin > 10 ? VERDE : AMARELO));
        }

        stats.getChildren().add(alignStat("Mira de " + nameOf(radioA), radioA, angAB));
        stats.getChildren().add(alignStat("Mira de " + nameOf(radioB), radioB, angleBtoA()));

        Double pct = overlapPercent();
        if (pct != null && primeira >= 0) {
            // Com a visada cortada, o cruzamento dos cones nao diz nada sobre
            // o enlace: dois cones podem se sobrepor perfeitamente atras de um
            // morro. Mostrar "100%" em verde ao lado de "obstruida" fazia a
            // tela se contradizer.
            stats.getChildren().add(stat("Miras se cruzam",
                    String.format("%.0f%% \u2014 mas o terreno corta antes", pct), VERMELHO));
        } else if (pct != null) {
            // "Miras", e nao "feixes": isto mede so o apontamento das duas
            // antenas, sem olhar para o terreno. Ver overlapPercent().
            stats.getChildren().add(stat("Miras se cruzam",
                    String.format("%.0f%%", pct),
                    pct >= 50 ? VERDE : (pct > 0 ? AMARELO : VERMELHO)));
        }

        Double fres = fresnelWorstPercent();
        if (fres != null) {
            stats.getChildren().add(stat("Fresnel livre",
                    String.format("%.0f%%", fres),
                    fres >= 60 ? VERDE : (fres > 0 ? AMARELO : VERMELHO)));
        }

        LinkBudget.Result rb = null;
        if (radioA.hasRfData() || radioB.hasRfData()) {
            rb = LinkBudget.compute(radioA, radioB, spanM(), angAB, angleBtoA(), 0, 0);
            if (rb.valid()) {
                stats.getChildren().add(stat("Perda percurso",
                        String.format("%.1f dB", rb.fspl())));
                stats.getChildren().add(stat("Sinal estimado",
                        String.format("%.0f dBm", rb.rssiAtoB()),
                        rb.rssiAtoB() >= -65 ? VERDE : (rb.rssiAtoB() >= -80 ? AMARELO : VERMELHO)));
            }
        }
        if (medidoDbm != null) {
            if (rb != null && rb.valid()) {
                double delta = medidoDbm - rb.rssiAtoB();
                stats.getChildren().add(stat("Sinal medido",
                        String.format("%.0f dBm  (%+.0f)", medidoDbm, delta),
                        delta > -10 ? VERDE : VERMELHO));
            } else {
                stats.getChildren().add(stat("Sinal medido",
                        String.format("%.0f dBm", medidoDbm)));
            }
        }

        if (planned) {
            stats.getChildren().add(stat("Situação", "planejado", AMARELO));
        }
        verdict.setText(linkVerdict(primeira, fres, pct));
    }

    /** Frase unica dizendo o que fazer com tudo isso. */
    private String linkVerdict(double primeira, Double fresnel, Double overlap) {
        StringBuilder sb = new StringBuilder();
        if (primeira >= 0) {
            sb.append("Terreno obstrui a visada: sem remover o obstaculo ou subir as "
                    + "antenas, o enlace nao fecha como esta.");
        } else if (fresnel != null && fresnel < 60) {
            sb.append("Visada limpa a olho, mas a zona de Fresnel esta comprimida: "
                    + "e a causa classica de enlace que fecha e rende mal.");
        } else {
            sb.append("Caminho livre.");
        }
        if (overlap != null && overlap <= 0) {
            sb.append("  As miras nao se cruzam no meio do caminho: confira "
                    + "inclinacao e abertura vertical das duas antenas.");
        }
        if (movido()) {
            sb.append("  Hipotese: ha torre fora do lugar do projeto. "
                    + "Use Aplicar para mover de verdade, ou Desfazer.");
        }
        if (!radioA.hasRfData() || !radioB.hasRfData()) {
            sb.append("  Preencha ganho, potencia e frequencia nas duas pontas para "
                    + "estimar o sinal.");
        }
        if (planned) {
            sb.append("  Enlace ainda nao existe no ar: os numeros sao estimativa de "
                    + "visada limpa, nao medicao.");
        }
        return sb.toString();
    }

    /** Alinhamento de uma ponta: angulo do alvo e se cai dentro do feixe. */
    private javafx.scene.Node alignStat(String rotulo, Radio r, double alvoDeg) {
        if (r.getBeamVerticalWidthDeg() <= 0) {
            return stat(rotulo, "abertura V nao informada", AMARELO);
        }
        double lo = r.getBeamTiltDeg() - r.getBeamVerticalWidthDeg() / 2.0;
        double hi = r.getBeamTiltDeg() + r.getBeamVerticalWidthDeg() / 2.0;
        boolean dentro = alvoDeg >= lo && alvoDeg <= hi;
        double margem = dentro ? Math.min(alvoDeg - lo, hi - alvoDeg)
                               : (alvoDeg < lo ? lo - alvoDeg : alvoDeg - hi);
        return stat(rotulo,
                String.format("%+.1f°  %s %.1f°", alvoDeg,
                        dentro ? "dentro por" : "FORA por", margem),
                dentro ? VERDE : VERMELHO);
    }

    /** Pior cobertura de Fresnel ao longo do caminho, em %. Null sem frequencia. */
    private Double fresnelWorstPercent() {
        double f = freqMhz();
        if (f <= 0) return null;
        double ang = angleAtoB();
        double pior = Double.MAX_VALUE;
        for (int i = 1; i < dist.length - 1; i++) {
            if (Double.isNaN(ground[i]) || !entreAntenas(i)) continue;
            double r = LinkBudget.fresnelRadius(dist[i] / 1000.0,
                    (posB() - dist[i]) / 1000.0, f / 1000.0);
            if (Double.isNaN(r) || r <= 0) continue;
            pior = Math.min(pior, (lineA(dist[i], ang) - ground[i]) / r * 100);
        }
        return pior == Double.MAX_VALUE ? null : pior;
    }

    /** Sobreposicao dos feixes no meio do caminho, em % do menor. */
    /**
     * Quanto os dois cones verticais se sobrepoem no meio do caminho.
     *
     * <h3>O que isto NAO mede</h3>
     * Terreno. E' geometria de apontamento pura: mede se as duas antenas
     * estao miradas uma na outra o bastante para os cones se cruzarem. Dois
     * cones podem se sobrepor 100% com um morro inteiro no meio — por isso o
     * indicador se chama "miras se cruzam" e vem marcado em vermelho quando a
     * visada esta cortada. Quem responde pela obstrucao e' a folga da visada e
     * a Fresnel, logo acima na mesma faixa.
     *
     * Tambem e' uma amostra unica, no ponto medio: serve para dizer se as
     * antenas se enxergam angularmente, nao para descrever o caminho todo.
     */
    private Double overlapPercent() {
        if (radioA.getBeamVerticalWidthDeg() <= 0 || radioB.getBeamVerticalWidthDeg() <= 0) {
            return null;
        }
        double mid = (posA() + posB()) / 2;
        double aLo = Math.min(lineA(mid, loA()), lineA(mid, hiA()));
        double aHi = Math.max(lineA(mid, loA()), lineA(mid, hiA()));
        double bLo = Math.min(lineB(mid, loB()), lineB(mid, hiB()));
        double bHi = Math.max(lineB(mid, loB()), lineB(mid, hiB()));
        double inter = Math.min(aHi, bHi) - Math.max(aLo, bLo);
        if (inter <= 0) return 0.0;
        double menor = Math.min(aHi - aLo, bHi - bLo);
        return menor <= 0 ? 0.0 : Math.min(100, inter / menor * 100);
    }

    /** Frequência do enlace, se alguma ponta informar. */
    private double freqMhz() {
        double f = radioA.getFrequencyMhz();
        return f > 0 ? f : radioB.getFrequencyMhz();
    }

    /**
     * A regra de campo é manter 60% da primeira zona de Fresnel livre. Abaixo
     * disso o sinal degrada mesmo com "visada" aparentemente limpa — é a causa
     * clássica de enlace que funciona mal sem obstáculo visível.
     */

    /** Sinal estimado, e comparação com o medido quando o enlace está ativo. */

    /** "AP: alvo a −2,3°, feixe −8°±5° → dentro (margem 0,7°)". */

    /**
     * Fração dos feixes que se sobrepõe, avaliada no meio do caminho — que é
     * onde a comparação faz sentido: nas pontas cada feixe vira um ponto.
     */

    // ------------------------ Desenho ------------------------

    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.setFill(Color.web("#1b1b1b"));
        gc.fillRect(0, 0, w, h);
        if (w < 80 || h < 60) return;

        if (!ready || mode == Mode.EMPTY || dist == null) {
            gc.setFill(Color.web("#777"));
            gc.setFont(Font.font("System", 12));
            gc.fillText(message, PAD_LEFT, h / 2);
            return;
        }

        double plotW = w - PAD_LEFT - PAD_RIGHT;
        double plotH = h - PAD_TOP - PAD_BOTTOM;
        // Eixo cobre as margens; os feixes, so o trecho entre as antenas.
        double d0 = plotMinD, d1 = plotMaxD;
        double maxD = totalM;

        double minH = Double.MAX_VALUE, maxH = -Double.MAX_VALUE;
        for (double g : ground) {
            if (Double.isNaN(g)) continue;
            minH = Math.min(minH, g); maxH = Math.max(maxH, g);
        }
        for (double dm : new double[]{0, maxD / 2, maxD}) {
            for (double a : new double[]{loA(), hiA(), radioA.getBeamTiltDeg()}) {
                double v = lineA(dm, a);
                minH = Math.min(minH, v); maxH = Math.max(maxH, v);
            }
            if (mode == Mode.LINK) {
                for (double a : new double[]{loB(), hiB(), radioB.getBeamTiltDeg()}) {
                    double v = lineB(dm, a);
                    minH = Math.min(minH, v); maxH = Math.max(maxH, v);
                }
            }
        }
        if (minH == Double.MAX_VALUE) { minH = 0; maxH = 100; }
        double margem = Math.max(10, (maxH - minH) * 0.12);
        minH -= margem; maxH += margem;
        final double fMin = minH, fMax = maxH;

        // Enquadramento automatico -> tela, com o zoom e o arrasto por cima.
        // Guardado em campo porque a mesma conta serve para desenhar, para
        // saber o que esta sob o cursor e para rotular os eixos: uma unica
        // definicao de vista, sem chance de as tres divergirem.
        fitD0 = d0; fitD1 = d1; fitH0 = fMin; fitH1 = fMax;
        clampPan(plotW, plotH);
        DoubleUnaryOperator sx = this::screenX;
        DoubleUnaryOperator sy = this::screenY;
        DoubleUnaryOperator dAt = this::distAtPixel;
        DoubleUnaryOperator hAt = this::heightAtPixel;

        hovered = dentroDoPlot(mouseX, mouseY, w, h)
                ? beamUnder(distAtPixel(mouseX), heightAtPixel(mouseY)) : 0;

        // Tudo que e "conteudo" fica preso a area de plotagem: ampliado, o
        // desenho invadiria as margens e passaria por cima dos rotulos.
        gc.save();
        gc.beginPath();
        gc.rect(PAD_LEFT, PAD_TOP, plotW, plotH);
        gc.clip();

        drawGrid(gc, plotW, plotH, maxD, sx, sy, dAt, hAt);

        // Feixe do rádio A (e do B, no modo enlace).
        wedge(gc, sx, sy, maxD, loA(), hiA(), Color.DEEPSKYBLUE, true, hovered == 1);
        if (mode == Mode.LINK) {
            wedge(gc, sx, sy, maxD, loB(), hiB(), Color.ORANGE, false, hovered == 2);
            drawOverlap(gc, sx, sy, maxD);
        }

        // Eixos dos feixes.
        gc.setStroke(Color.web("#4fc3f7"));
        gc.setLineWidth(2);
        seg(gc, sx, sy, 0, lineA(0, radioA.getBeamTiltDeg()),
                        maxD, lineA(maxD, radioA.getBeamTiltDeg()));
        if (mode == Mode.LINK) {
            gc.setStroke(Color.web("#ffb74d"));
            seg(gc, sx, sy, 0, lineB(0, radioB.getBeamTiltDeg()),
                            maxD, lineB(maxD, radioB.getBeamTiltDeg()));
            // Linha reta entre as antenas: a visada que precisa estar livre.
            gc.setStroke(Color.web("#ffffff"));
            gc.setLineWidth(1.2);
            gc.setLineDashes(6, 5);
            double ang = angleAtoB();
            seg(gc, sx, sy, posA(), lineA(posA(), ang), posB(), lineA(posB(), ang));
            gc.setLineDashes(null);
        }

        drawTerrain(gc, sx, sy, plotH);
        drawObstruction(gc, sx, sy);
        // Nomes com a cor do respectivo feixe: azul de um lado, laranja do
        // outro, casando com os envelopes desenhados acima.
        drawMast(gc, sx, sy, posA(), baseA, antennaTopA(),
                Color.web("#4fc3f7"), nameOf(radioA), false);
        if (mode == Mode.LINK) {
            drawMast(gc, sx, sy, posB(), baseB, antennaTopB(),
                    Color.web("#ffb74d"), nameOf(radioB), true);
        }
        gc.restore();

        drawAxisLabels(gc, w, h, plotW, plotH, maxD, sx, sy, dAt, hAt);
        drawCursorReadout(gc, w, h);
        drawHoverBadge(gc, w);
        drawViewHint(gc, w);
    }

    /**
     * Limita o arrasto para o conteudo nunca sair de vista.
     *
     * Ampliado z vezes, o desenho ocupa plotW*z em torno do centro: deslocar
     * mais que a metade da sobra deixaria uma faixa vazia na tela. Em zoom 1
     * a sobra e zero e o arrasto simplesmente nao anda — nao ha o que mostrar
     * fora do enquadramento.
     */
    private void clampPan(double plotW, double plotH) {
        double maxX = plotW * (zoom - 1) / 2, maxY = plotH * (zoom - 1) / 2;
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    // ---- Vista: metros <-> pixels. Tudo passa por aqui. ----

    private double plotW() { return Math.max(1, canvas.getWidth() - PAD_LEFT - PAD_RIGHT); }
    private double plotH() { return Math.max(1, canvas.getHeight() - PAD_TOP - PAD_BOTTOM); }

    double screenX(double metros) {
        double cx = PAD_LEFT + plotW() / 2;
        double base = PAD_LEFT + ((metros - fitD0) / (fitD1 - fitD0)) * plotW();
        return cx + (base - cx) * zoom + panX;
    }

    double screenY(double altitude) {
        double cy = PAD_TOP + plotH() / 2;
        double base = PAD_TOP + plotH() - ((altitude - fitH0) / (fitH1 - fitH0)) * plotH();
        return cy + (base - cy) * zoom + panY;
    }

    /** Distância em metros sob um pixel horizontal — inversa de {@link #screenX}. */
    double distAtPixel(double px) {
        double cx = PAD_LEFT + plotW() / 2;
        double base = (px - panX - cx) / zoom + cx;
        return fitD0 + (base - PAD_LEFT) / plotW() * (fitD1 - fitD0);
    }

    /** Altitude sob um pixel vertical — inversa de {@link #screenY}. */
    double heightAtPixel(double py) {
        double cy = PAD_TOP + plotH() / 2;
        double base = (py - panY - cy) / zoom + cy;
        return fitH0 + (PAD_TOP + plotH() - base) / plotH() * (fitH1 - fitH0);
    }

    private boolean dentroDoPlot(double x, double y, double w, double h) {
        return x >= PAD_LEFT && x <= w - PAD_RIGHT && y >= PAD_TOP && y <= h - PAD_BOTTOM;
    }

    /**
     * Em qual feixe esta o ponto (distancia, altitude)?
     *
     * So vale entre as antenas: nas margens do grafico nao existe feixe, e
     * prolongar o cone ali sugeriria cobertura que o radio nao tem.
     */
    private int beamUnder(double d, double hh) {
        if (dist == null || d < posA() || d > posB()) return 0;
        boolean emA = radioA != null && radioA.getBeamVerticalWidthDeg() > 0
                && entre(lineA(d, loA()), lineA(d, hiA()), hh);
        boolean emB = mode == Mode.LINK && radioB != null && radioB.getBeamVerticalWidthDeg() > 0
                && entre(lineB(d, loB()), lineB(d, hiB()), hh);
        if (emA && emB) {
            // Na regiao de cruzamento ganha o cone em que o ponto esta mais
            // "no centro", medido em FRACAO da abertura daquele feixe ali.
            //
            // Comparar a distancia crua ate cada eixo nao serve: perto do
            // meio do enlace os dois eixos quase se tocam, e a escolha passava
            // a ser decidida por centimetros de curvatura da Terra. Em fracao,
            // apontar dentro do cone estreito — que e o alvo dificil —
            // seleciona o cone estreito.
            double relA = fracaoDoCone(d, hh, true);
            double relB = fracaoDoCone(d, hh, false);
            return relA <= relB ? 1 : 2;
        }
        return emA ? 1 : emB ? 2 : 0;
    }

    /** 0 = em cima do eixo, 1 = na borda do cone. */
    private double fracaoDoCone(double d, double hh, boolean deA) {
        Radio r = deA ? radioA : radioB;
        double eixo = deA ? lineA(d, r.getBeamTiltDeg()) : lineB(d, r.getBeamTiltDeg());
        double borda = deA ? lineA(d, hiA()) : lineB(d, hiB());
        double meia = Math.abs(borda - eixo);
        return meia < 1e-9 ? 0 : Math.abs(hh - eixo) / meia;
    }

    private static boolean entre(double a, double b, double v) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    /** Nome do feixe destacado, junto ao cursor. */
    private void drawHoverBadge(GraphicsContext gc, double w) {
        if (hovered == 0) return;
        Radio r = hovered == 1 ? radioA : radioB;
        String txt = nameOf(r) + "  ·  " + MapPane.deg(r.getBeamVerticalWidthDeg()) + "° V  ·  "
                + String.format("%+.1f°", r.getBeamTiltDeg());
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        double largura = txt.length() * 6.2 + 14;
        double bx = Math.min(mouseX + 12, w - largura - 4);
        double by = Math.max(PAD_TOP + 14, mouseY - 12);
        gc.setFill(Color.web("#000000", 0.72));
        gc.fillRoundRect(bx, by - 13, largura, 19, 6, 6);
        gc.setFill(Color.web(hovered == 1 ? COR_A : COR_B));
        gc.fillText(txt, bx + 7, by);
    }

    /**
     * Leitura sob o cursor: distancia, altitude e cota do terreno.
     *
     * Ampliado, o olho perde a referencia das marcas do eixo; o numero exato
     * sob o cursor e o que permite medir a folga sobre um morro sem contar
     * quadradinhos.
     */
    private void drawCursorReadout(GraphicsContext gc, double w, double h) {
        if (!dentroDoPlot(mouseX, mouseY, w, h)) return;
        double d = distAtPixel(mouseX), alt = heightAtPixel(mouseY);
        double solo = groundAtDistance(d);
        String txt = (Math.abs(d) < 1000 ? String.format("%.0f m", d)
                                         : String.format("%.2f km", d / 1000))
                + "   ·   " + String.format("%.0f m", alt);
        if (!Double.isNaN(solo)) {
            txt += "   ·   solo " + String.format("%.0f m", solo)
                    + String.format("   (%+.0f m)", alt - solo);
        }
        gc.setFont(Font.font("System", 10));
        gc.setFill(Color.web("#9a9a9a"));
        gc.fillText(txt, PAD_LEFT + 2, PAD_TOP - 10);
    }

    /** Terreno na distancia pedida, interpolado entre as duas amostras vizinhas. */
    private double groundAtDistance(double d) {
        if (dist == null || dist.length < 2) return Double.NaN;
        if (d < dist[0] || d > dist[dist.length - 1]) return Double.NaN;
        for (int i = 1; i < dist.length; i++) {
            if (dist[i] < d) continue;
            double g0 = ground[i - 1], g1 = ground[i];
            if (Double.isNaN(g0) || Double.isNaN(g1)) return Double.isNaN(g1) ? g0 : g1;
            double t = (d - dist[i - 1]) / (dist[i] - dist[i - 1]);
            return g0 + (g1 - g0) * t;
        }
        return ground[ground.length - 1];
    }

    /** Diz que da para ampliar, e em quanto esta. */
    /** Texto da dica no rodape do grafico. */
    private String textoDaDica() {
        if (mode == Mode.LINK) {
            return movido()
                    ? "torre fora do lugar do projeto \u2014 Aplicar grava, Desfazer volta"
                    : "arraste a torre para procurar posicao sem obstrucao  \u00b7  rolar amplia";
        }
        return "rolar amplia  \u00b7  arrastar desloca  \u00b7  duplo clique reenquadra";
    }

    private void drawViewHint(GraphicsContext gc, double w) {
        gc.setFont(Font.font("System", 10));
        gc.setFill(Color.web("#777"));
        gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
        String txt = zoom > 1.01
                ? String.format("zoom %.1f×  ·  duplo clique restaura  ·  %s", zoom, textoDaDica())
                : textoDaDica();
        gc.fillText(txt, w - 6, PAD_TOP - 10);
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
    }

    /**
     * @param destaque cursor sobre este feixe: sobe a opacidade do
     *                 preenchimento e reforca o contorno. Num enlace os dois
     *                 cones se sobrepoem, e translucidos nos dois e dificil
     *                 seguir qual e qual — passar o mouse resolve sem precisar
     *                 apagar o outro.
     */
    private void wedge(GraphicsContext gc, DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                       double maxD, double lo, double hi, Color color, boolean fromA,
                       boolean destaque) {
        if ((fromA ? radioA : radioB).getBeamVerticalWidthDeg() <= 0) return;
        gc.setFill(color.deriveColor(0, 1, 1, destaque ? 0.42 : 0.16));
        gc.beginPath();
        gc.moveTo(sx.applyAsDouble(0), sy.applyAsDouble(fromA ? lineA(0, hi) : lineB(0, hi)));
        gc.lineTo(sx.applyAsDouble(maxD), sy.applyAsDouble(fromA ? lineA(maxD, hi) : lineB(maxD, hi)));
        gc.lineTo(sx.applyAsDouble(maxD), sy.applyAsDouble(fromA ? lineA(maxD, lo) : lineB(maxD, lo)));
        gc.lineTo(sx.applyAsDouble(0), sy.applyAsDouble(fromA ? lineA(0, lo) : lineB(0, lo)));
        gc.closePath();
        gc.fill();
        gc.setStroke(color.deriveColor(0, 1, 1, destaque ? 1.0 : 0.5));
        gc.setLineWidth(destaque ? 2 : 1);
        gc.stroke();
    }

    /**
     * Região onde os dois feixes cobrem o mesmo espaço. É a resposta visual
     * para "quanto do feixe do cliente está pegando o feixe do AP": em cada
     * distância, o intervalo comum entre os dois envelopes.
     */
    private void drawOverlap(GraphicsContext gc, DoubleUnaryOperator sx,
                             DoubleUnaryOperator sy, double maxD) {
        if (radioA.getBeamVerticalWidthDeg() <= 0 || radioB.getBeamVerticalWidthDeg() <= 0) return;

        int n = 60;
        double[] xs = new double[n], top = new double[n], bot = new double[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            double d = maxD * i / (double) (n - 1);
            double aLo = Math.min(lineA(d, loA()), lineA(d, hiA()));
            double aHi = Math.max(lineA(d, loA()), lineA(d, hiA()));
            double bLo = Math.min(lineB(d, loB()), lineB(d, hiB()));
            double bHi = Math.max(lineB(d, loB()), lineB(d, hiB()));
            double lo = Math.max(aLo, bLo), hi = Math.min(aHi, bHi);
            if (hi <= lo) continue;
            xs[count] = d; top[count] = hi; bot[count] = lo; count++;
        }
        if (count < 2) return;

        gc.setFill(Color.web("#7cfc00", 0.30));
        gc.beginPath();
        gc.moveTo(sx.applyAsDouble(xs[0]), sy.applyAsDouble(top[0]));
        for (int i = 1; i < count; i++) gc.lineTo(sx.applyAsDouble(xs[i]), sy.applyAsDouble(top[i]));
        for (int i = count - 1; i >= 0; i--) gc.lineTo(sx.applyAsDouble(xs[i]), sy.applyAsDouble(bot[i]));
        gc.closePath();
        gc.fill();
    }

    private void drawTerrain(GraphicsContext gc, DoubleUnaryOperator sx,
                             DoubleUnaryOperator sy, double plotH) {
        gc.setFill(Color.web("#4a5d23"));
        gc.beginPath();
        boolean started = false;
        for (int i = 0; i < dist.length; i++) {
            if (Double.isNaN(ground[i])) continue;
            double x = sx.applyAsDouble(dist[i]), y = sy.applyAsDouble(ground[i]);
            if (!started) { gc.moveTo(x, PAD_TOP + plotH); started = true; }
            gc.lineTo(x, y);
        }
        if (!started) return;
        gc.lineTo(sx.applyAsDouble(dist[dist.length - 1]), PAD_TOP + plotH);
        gc.closePath();
        gc.fill();

        gc.setStroke(Color.web("#8fbc4a"));
        gc.setLineWidth(1.5);
        gc.beginPath();
        started = false;
        for (int i = 0; i < dist.length; i++) {
            if (Double.isNaN(ground[i])) { started = false; continue; }
            double x = sx.applyAsDouble(dist[i]), y = sy.applyAsDouble(ground[i]);
            if (!started) { gc.moveTo(x, y); started = true; } else gc.lineTo(x, y);
        }
        gc.stroke();
    }

    /** Onde o terreno passa acima da linha que interessa. */
    private void drawObstruction(GraphicsContext gc, DoubleUnaryOperator sx, DoubleUnaryOperator sy) {
        double ang = mode == Mode.LINK ? angleAtoB() : radioA.getBeamTiltDeg();
        gc.setStroke(Color.web("#ff3b30"));
        gc.setLineWidth(4);
        for (int i = 1; i < dist.length; i++) {
            if (Double.isNaN(ground[i]) || Double.isNaN(ground[i - 1])) continue;
            if (!entreAntenas(i) || !entreAntenas(i - 1)) continue;
            if (ground[i - 1] > lineA(dist[i - 1], ang) && ground[i] > lineA(dist[i], ang)) {
                seg(gc, sx, sy, dist[i - 1], ground[i - 1], dist[i], ground[i]);
            }
        }
        if (mode == Mode.LINK) marcarPiorObstrucao(gc, sx, sy, ang);
    }

    /**
     * Onde exatamente a visada morre, e por quantos metros.
     *
     * O trecho vermelho ja dizia que ha obstrucao em algum lugar; faltava o
     * numero que decide o que fazer. "Sobe 8 m" e "sobe 60 m" levam a decisoes
     * opostas — uma e' um mastro maior, a outra e' mudar de morro — e ate aqui
     * as duas apareciam iguais na tela.
     *
     * Marca o PIOR ponto, nao o primeiro: e' ele que manda na altura
     * necessaria. O primeiro so diz onde o problema comeca.
     */
    private void marcarPiorObstrucao(GraphicsContext gc, DoubleUnaryOperator sx,
                                     DoubleUnaryOperator sy, double ang) {
        if (piorObstM <= 0 || piorObstD == Double.MAX_VALUE) return;
        int pior = indiceMaisProximo(dist, piorObstD);
        double intrusao = piorObstM;

        double x = sx.applyAsDouble(dist[pior]);
        double yTerreno = sy.applyAsDouble(ground[pior]);
        double yVisada = sy.applyAsDouble(lineA(dist[pior], ang));

        // Coluna tracejada do chao ate a linha de visada: o vao que falta.
        gc.setStroke(Color.web("#ff3b30"));
        gc.setLineWidth(1.6);
        gc.setLineDashes(5, 4);
        gc.strokeLine(x, yTerreno, x, yVisada);
        gc.setLineDashes(null);

        gc.setFill(Color.web("#ff3b30"));
        gc.fillOval(x - 3.5, yTerreno - 3.5, 7, 7);

        String txt = String.format("obstrui %.0f m  \u00b7  %s",
                intrusao, MapPane.formatRange(dist[pior] - posA()));
        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        double larg = txt.length() * 6.0;
        double bx = Math.min(Math.max(x - larg / 2, PAD_LEFT + 2),
                             PAD_LEFT + plotW() - larg - 2);
        double by = Math.min(yTerreno, yVisada) - 16;
        gc.setFill(Color.web("#1a1a1a", 0.85));
        gc.fillRoundRect(bx - 4, by - 11, larg + 8, 15, 4, 4);
        gc.setFill(Color.web("#ff8a80"));
        gc.fillText(txt, bx, by);
    }

    /**
     * Desenha um mastro com a antena, o nome do rádio e a altura.
     *
     * A cor do nome é a mesma do feixe daquela ponta, que é o que amarra o
     * texto ao desenho: sem isso, num enlace com dois envelopes sobrepostos
     * não dava para saber qual cone saía de qual antena.
     *
     * @param aDireita alinha o texto à esquerda do mastro — usado na ponta
     *                 direita, onde escrever para fora sairia do painel
     */
    /**
     * @param topoM cota do topo da antena, já resolvida conforme o modo de
     *              altitude do rádio — este método não soma nada.
     */
    private void drawMast(GraphicsContext gc, DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                          double d, double base, double topoM, Color color,
                          String nome, boolean aDireita) {
        double heightM = topoM - base;
        double x = sx.applyAsDouble(d);
        double topo = sy.applyAsDouble(topoM);

        gc.setStroke(Color.web("#dddddd"));
        gc.setLineWidth(2);
        gc.strokeLine(x, sy.applyAsDouble(base), x, topo);
        gc.setFill(color);
        gc.fillOval(x - 4.5, topo - 4.5, 9, 9);

        gc.setTextAlign(aDireita ? javafx.scene.text.TextAlignment.RIGHT
                                 : javafx.scene.text.TextAlignment.LEFT);
        double tx = aDireita ? x - 8 : x + 8;

        gc.setFont(Font.font("System", FontWeight.BOLD, 11));
        gc.setFill(color);
        gc.fillText(encurta(nome), tx, topo - 14);

        gc.setFont(Font.font("System", 10));
        gc.setFill(heightM < 0 ? Color.web("#ef5350") : Color.web("#bbb"));
        gc.fillText(String.format("%.0f m", heightM), tx, topo - 2);

        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);   // restaura o padrão
    }

    /** Nome longo vira "Torre Centro…" para não invadir o gráfico. */
    private static String encurta(String s) {
        if (s == null) return "";
        return s.length() <= 18 ? s : s.substring(0, 17) + "…";
    }

    /**
     * Linhas da grade, dentro do recorte.
     *
     * As marcas sao geradas a partir do que esta VISIVEL, nao de divisoes
     * fixas do total: ampliado 10x, cinco marcas espalhadas pelo enlace
     * inteiro deixariam a tela sem nenhuma referencia.
     */
    private void drawGrid(GraphicsContext gc, double plotW, double plotH, double maxD,
                          DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                          DoubleUnaryOperator dAt, DoubleUnaryOperator hAt) {
        gc.setStroke(Color.web("#333"));
        gc.setLineWidth(1);
        for (double hh : ticksY(hAt, plotH)) {
            double y = sy.applyAsDouble(hh);
            gc.strokeLine(PAD_LEFT, y, PAD_LEFT + plotW, y);
        }
        for (double dm : ticksX(dAt, plotW, maxD)) {
            double x = sx.applyAsDouble(dm);
            gc.strokeLine(x, PAD_TOP, x, PAD_TOP + plotH);
        }
    }

    /** Numeros dos eixos, FORA do recorte: eles vivem nas margens. */
    private void drawAxisLabels(GraphicsContext gc, double w, double h, double plotW, double plotH,
                                double maxD, DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                                DoubleUnaryOperator dAt, DoubleUnaryOperator hAt) {
        gc.setFill(Color.web("#888"));
        gc.setFont(Font.font("System", 10));
        for (double hh : ticksY(hAt, plotH)) {
            gc.fillText(String.format("%.0f m", hh), 4, sy.applyAsDouble(hh) + 3);
        }
        for (double dm : ticksX(dAt, plotW, maxD)) {
            gc.fillText(dm < 1000 ? String.format("%.0f m", dm)
                                  : String.format("%.2f km", dm / 1000),
                        sx.applyAsDouble(dm) - 16, h - 8);
        }
    }

    private double[] ticksY(DoubleUnaryOperator hAt, double plotH) {
        double h1 = hAt.applyAsDouble(PAD_TOP), h0 = hAt.applyAsDouble(PAD_TOP + plotH);
        return ticks(h0, h1, 4);
    }

    /**
     * Marcas de distancia limitadas a [0, alcance]: as margens do grafico
     * existem para mostrar o terreno atras das torres, nao para serem medidas.
     */
    private double[] ticksX(DoubleUnaryOperator dAt, double plotW, double maxD) {
        double d0 = Math.max(0, dAt.applyAsDouble(PAD_LEFT));
        double d1 = Math.min(maxD, dAt.applyAsDouble(PAD_LEFT + plotW));
        return ticks(d0, d1, 5);
    }

    /** Valores "redondos" cobrindo a faixa: 1, 2, 5, 10, 20, 50... */
    private static double[] ticks(double lo, double hi, int alvo) {
        if (!(hi > lo)) return new double[0];
        double bruto = (hi - lo) / alvo;
        double mag = Math.pow(10, Math.floor(Math.log10(bruto)));
        double n = bruto / mag;
        double passo = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (double v = Math.ceil(lo / passo) * passo; v <= hi + passo * 1e-9; v += passo) {
            out.add(v);
            if (out.size() > 40) break;   // guarda contra passo degenerado
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static void seg(GraphicsContext gc, DoubleUnaryOperator sx, DoubleUnaryOperator sy,
                            double d1, double h1, double d2, double h2) {
        gc.strokeLine(sx.applyAsDouble(d1), sy.applyAsDouble(h1),
                      sx.applyAsDouble(d2), sy.applyAsDouble(h2));
    }

    private static String nameOf(Radio r) {
        return r.getName() == null || r.getName().isBlank() ? r.getHost() : r.getName();
    }
}
