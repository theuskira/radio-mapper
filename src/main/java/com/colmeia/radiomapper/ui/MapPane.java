package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.ElevationSource;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.PlyElevation;
import com.colmeia.radiomapper.geo.TileSource;
import com.colmeia.radiomapper.model.ImageLayer;
import com.colmeia.radiomapper.model.ImageOverlay;
import com.colmeia.radiomapper.model.Link;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioStatus;
import com.colmeia.radiomapper.util.Settings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pane que mostra a imagem de fundo com zoom/pan e desenha pontos+links em cima.
 *
 * - Scroll do mouse: zoom (pivot no cursor)
 * - Botao esquerdo + arrastar no mapa: pan
 * - Botao esquerdo + arrastar EM CIMA de um ponto: move o ponto (tem
 *   prioridade sobre o pan)
 * - Botao direito: menu de contexto do que estiver sob o cursor
 * - Painel flutuante com Centralizar / + / - / Tela cheia
 * - Atalhos: +/-/0/F11
 */
public class MapPane extends Pane {

    private final Group worldLayer = new Group();
    private final TileLayer tileLayer = new TileLayer();
    /**
     * Uma ImageView por ortofoto, na ordem de pintura.
     *
     * Um projeto costuma ter mais de um voo, e o que interessa e' ver todos
     * encaixados no mesmo mapa. Antes havia uma so, entao abrir a segunda
     * apagava a primeira.
     */
    private final Group imagesLayer = new Group();

    /** As views por id de camada, para achar rapido a que se esta ajustando. */
    private final java.util.Map<String, ImageView> imageViews =
            new java.util.LinkedHashMap<>();

    /** As camadas como o projeto as descreve, na ordem em que sao pintadas. */
    private java.util.List<ImageLayer> layers = new java.util.ArrayList<>();

    /**
     * Tamanho LOGICO de cada imagem (o do arquivo original), por camada.
     *
     * A exibida pode estar subamostrada; o que define o espaco de coordenadas
     * dos pontos, em modo imagem, e' o tamanho original. Guardar por camada
     * tira a dependencia da ordem em que quem chama carrega a imagem e
     * descreve as camadas.
     */
    private final java.util.Map<String, double[]> tamanhoLogico =
            new java.util.HashMap<>();

    /** Qual delas recebe os puxadores e o arrasto. Null = nenhuma. */
    private String selectedLayerId;
    private final Group surveyLayer = new Group();
    private final Group simBeamLayer = new Group();
    private final Group beamsLayer = new Group();
    private final Group linksLayer = new Group();
    private final Group nodesLayer = new Group();
    private final VBox controlPanel = new VBox(4);
    private ToggleButton beamsToggle;
    private ToggleButton surveyToggle;

    /**
     * Area coberta pelo levantamento .PLY, desenhada como um contorno sobre
     * o mapa — a leitura de "ate onde eu tenho altitude de verdade".
     *
     * Guardada em coordenadas de mundo ja prontas; o MapPane so desenha. O
     * rotulo e o contorno tem escala propria porque o worldLayer inteiro e
     * escalado pelo zoom, e uma linha de 2 unidades de mundo viraria um
     * borrao de 40 px quando o usuario se aproxima.
     */
    private PlyElevation.Footprint surveyFootprint;
    private String surveyName = "";
    private javafx.scene.shape.Path surveyOutline;
    private Text surveyLabel;
    private Scale surveyLabelScale;

    /** Crédito do provedor de tiles — exigência de uso de todos eles. */
    private final Label attributionLabel = new Label();

    /** Coordenada e altitude sob o cursor. */
    private final Label readoutLabel = new Label();

    /**
     * De onde sai a altitude mostrada sob o cursor. Null (ou sem dado no
     * ponto) faz a leitura mostrar "—" em vez de inventar número.
     */
    private ElevationSource elevationSource;

    /** Contorno e puxadores de canto, visíveis só no modo de ajuste da imagem. */
    private final Group overlayHandles = new Group();
    /**
     * Nós dos puxadores, mantidos vivos entre eventos.
     *
     * Recriá-los durante o arrasto removeria da cena o próprio nó que está
     * recebendo os eventos, e o arrasto morreria no meio.
     */
    private javafx.scene.shape.Rectangle overlayOutline;
    private final javafx.scene.shape.Rectangle[] cornerNodes = new javafx.scene.shape.Rectangle[4];
    private ImageOverlay overlay;
    private boolean adjustingOverlay;
    private Runnable onOverlayChanged;
    /** Offset do cursor ao canto NO da imagem, durante o arrasto. */
    private double overlayDragDX;
    private double overlayDragDY;
    private javafx.scene.control.Slider opacitySlider;
    private Label opacityLabel;

    // Transformacao explicita com pivot em (0,0) - evita o pivot-em-centro
    // padrao do setScaleX/Y, que era o motivo do zoom "deslizar".
    private final Scale scaleTx = new Scale(1, 1, 0, 0);

    // Scales aplicados a cada Text para mante-los com tamanho VISUAL constante,
    // independente do zoom do mapa. Recriados a cada redraw().
    private final List<Scale> textCompensators = new ArrayList<>();

    private final ObjectProperty<Project> project = new SimpleObjectProperty<>();
    private final ObjectProperty<NetworkPoint> selectedPoint = new SimpleObjectProperty<>();

    /**
     * Seleção múltipla de pontos.
     *
     * Mantida separada de {@link #selectedPoint}, que segue existindo como "o
     * ponto em foco" para o painel lateral e o perfil. A seleção múltipla
     * serve para operações em lote — hoje, mover vários de uma vez.
     */
    private final java.util.Set<NetworkPoint> multiSelection = new java.util.LinkedHashSet<>();

    /** Retângulo do laço. Fica FORA do worldLayer para não escalar com o zoom. */
    private final javafx.scene.shape.Rectangle lasso = new javafx.scene.shape.Rectangle();
    private boolean lassoing;
    private double lassoX0, lassoY0;

    /** Posição de cada ponto no início do arrasto em lote. */
    private final java.util.Map<NetworkPoint, double[]> dragOrigins = new java.util.HashMap<>();
    private Runnable onSelectionChanged;

    /**
     * Nós desenhados, por ponto.
     *
     * Guardados para duas coisas: manter o tamanho constante NA TELA ao mudar
     * o zoom, e mover o lote selecionado sem reconstruir a camada inteira a
     * cada evento de arrasto.
     */
    private final java.util.Map<NetworkPoint, Circle> nodeCircles = new java.util.HashMap<>();
    private final java.util.Map<NetworkPoint, Text> nodeLabels = new java.util.HashMap<>();

    /** Raio do ponto em PIXELS DE TELA. Em unidades de mundo ele sumiria no zoom out. */
    private static final double NODE_RADIUS_PX = 8;

    private double scale = 1.0;
    // Dimensões LÓGICAS da imagem (pixels da imagem original em disco). Definem
    // o espaço de coordenadas dos pontos. A imagem exibida pode estar
    // subamostrada (ver ImageLoader.MAX_DISPLAY_DIM); o ImageView é esticado
    // de volta a estas dimensões para que as coordenadas continuem válidas.
    private double worldWidth;
    private double worldHeight;
    private double panAnchorX;
    private double panAnchorY;
    private double tx0;
    private double ty0;

    // Pan com botao esquerdo. `panning` evita depender de getButton() durante
    // o arrasto; `panDistance` separa um clique de um arrasto, para navegar no
    // modo "posicionar ponto" nao largar um ponto a cada arrasto.
    private boolean panning;
    private double panDistance;

    /** Folga em px abaixo da qual o arrasto ainda conta como clique. */
    private static final double PAN_CLICK_SLOP = 4;

    // estado do arrasto de ponto
    private double dragOffsetX;
    private double dragOffsetY;

    // modo "clique para posicionar novo ponto"
    private Consumer<Point2D> pendingPlacement;

    // acao do botao "Tela cheia" - registrada pelo controller para esconder
    // toolbar/painel lateral/status bar. Se null, cai no fullscreen do Stage.
    private Runnable fullScreenAction;

    // fabrica de menu de contexto do ponto (clique direito)
    private Function<NetworkPoint, ContextMenu> pointContextMenuFactory;
    // fabrica de menu de contexto do feixe (clique direito sobre o Arc/Circle)
    private BiFunction<NetworkPoint, Radio, ContextMenu> beamContextMenuFactory;
    // fabrica de menu de contexto do MAPA (clique direito no vazio). Recebe a
    // coordenada de mundo clicada, para acoes do tipo "criar ponto aqui".
    private Function<Point2D, ContextMenu> mapContextMenuFactory;
    // clique esquerdo sobre um feixe: seleciona o radio dono dele
    private BiConsumer<NetworkPoint, Radio> beamClickAction;

    /**
     * Clique num enlace do mapa.
     *
     * A linha ja tinha cursor de mao, prometendo que dava para clicar, e nao
     * acontecia nada — a promessa existia sem a acao atras dela.
     */
    private Consumer<Link> linkClickAction;

    /**
     * Avisa que um ponto mudou de lugar, ao final do arrasto.
     *
     * O perfil do enlace depende da posicao dos dois pontos; sem este aviso
     * ele continuava mostrando o caminho antigo ate ser reaberto, o que faz o
     * painel discordar do mapa que esta logo acima dele.
     */
    private Consumer<NetworkPoint> onPointMoved;
    // clique esquerdo no mapa vazio: limpa a selecao
    private Runnable backgroundClickAction;

    // preview de feixe enquanto o usuario edita um radio no dialogo: desenha
    // este feixe mesmo com o toggle global desligado, e mesmo que o radio
    // ainda nao esteja em np.getRadios() (caso de novo radio sendo criado).
    private Radio previewBeamRadio;
    private NetworkPoint previewBeamPoint;

    public MapPane() {
        setStyle("-fx-background-color: #1e1e1e;");
        setMinSize(0, 0);
        setPrefSize(1280, 720);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // Ordem: mapa base no fundo, depois a imagem; feixes ficam ENTRE a
        // imagem e os enlaces para nao cobrir linhas finas nem nodes (que
        // precisam continuar clicaveis).
        // Puxadores por ultimo: durante o ajuste eles precisam ficar por cima
        // de tudo para serem agarraveis.
        // A area do levantamento vai logo acima da imagem e abaixo de tudo
        // que e clicavel: e contexto de fundo, nao pode roubar clique de
        // ponto nem esconder linha de enlace.
        // O alcance simulado fica ACIMA do feixe cadastrado de proposito. O
        // feixe cadastrado e' o que alguem digitou; o lobo e' o que a fisica e
        // o relevo permitem. Desenhar o primeiro por cima do segundo escondia
        // justamente a resposta atras da afirmacao que ela contradiz.
        worldLayer.getChildren().addAll(tileLayer, imagesLayer, surveyLayer, beamsLayer,
                                        simBeamLayer, linksLayer, nodesLayer, hintLayer,
                                        overlayHandles);
        worldLayer.getTransforms().add(scaleTx);
        lasso.setFill(Color.DEEPSKYBLUE.deriveColor(0, 1, 1, 0.18));
        lasso.setStroke(Color.DEEPSKYBLUE);
        lasso.setStrokeWidth(1);
        lasso.getStrokeDashArray().setAll(6.0, 4.0);
        lasso.setMouseTransparent(true);
        lasso.setVisible(false);

        getChildren().addAll(worldLayer, buildControlPanel(), buildAttribution(),
                             buildReadout(), lasso);

        // Leitura de coordenada/altitude acompanha o cursor.
        addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> updateReadout(e));
        addEventHandler(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> updateReadout(e));
        addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> readoutLabel.setVisible(false));

        // clip: nada do worldLayer pode vazar para fora do MapPane
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        // Ao redimensionar: mantem o ponto central da viewport estavel.
        layoutBoundsProperty().addListener((obs, oldB, newB) -> {
            if (oldB.getWidth() > 0 && oldB.getHeight() > 0) {
                double dw = newB.getWidth() - oldB.getWidth();
                double dh = newB.getHeight() - oldB.getHeight();
                worldLayer.setTranslateX(worldLayer.getTranslateX() + dw / 2);
                worldLayer.setTranslateY(worldLayer.getTranslateY() + dh / 2);
            }
            updateTiles();
        });

        // Reposicionar painel flutuante quando o MapPane ou o proprio painel
        // mudam de tamanho (Pane nao da layout pass automatico aos filhos,
        // entao precisamos disparar manualmente).
        widthProperty().addListener((o, a, b) -> {
            positionControlPanel(); positionAttribution(); positionReadout();
        });
        heightProperty().addListener((o, a, b) -> {
            positionControlPanel(); positionAttribution(); positionReadout();
        });
        controlPanel.boundsInLocalProperty().addListener((o, a, b) -> positionControlPanel());
        attributionLabel.boundsInLocalProperty().addListener((o, a, b) -> positionAttribution());

        addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            // Menu aberto fica preso a um ponto da TELA; com o mapa se movendo
            // por baixo, ele passa a apontar para outro lugar sem avisar.
            Menus.fechar();
            double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            zoomAt(factor, e.getX(), e.getY());
            e.consume();
        });

        // Pan com o botao ESQUERDO, arrastando o mapa.
        //
        // FILTER e nao handler porque precisamos decidir ANTES do alvo: se o
        // press caiu sobre um ponto, quem manda e o arrasto do ponto, e o pan
        // nem comeca. Nao consumimos o press, para o ponto continuar
        // recebendo o dele normalmente.
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            // Qualquer clique no mapa dispensa o menu aberto. O auto-fechar do
            // JavaFX nao chega aqui: o filtro do proprio MapPane consome o
            // press antes, e o menu ficava pendurado sobre o mapa.
            Menus.fechar();
            panDistance = 0;   // zera sempre, qualquer botao
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (blocksPan(e.getTarget())) return;      // ponto ou imagem em ajuste tem prioridade

            // Ctrl + arrastar no vazio = laco de selecao, em vez de pan.
            if (e.isControlDown()) {
                lassoing = true;
                lassoX0 = e.getX();
                lassoY0 = e.getY();
                lasso.setX(lassoX0);
                lasso.setY(lassoY0);
                lasso.setWidth(0);
                lasso.setHeight(0);
                lasso.setVisible(true);
                e.consume();
                return;
            }

            panning = true;
            panAnchorX = e.getSceneX();
            panAnchorY = e.getSceneY();
            tx0 = worldLayer.getTranslateX();
            ty0 = worldLayer.getTranslateY();
        });

        // Menu de contexto do MAPA. Chega aqui por bolha: se o clique caiu num
        // ponto ou num feixe, o menu de la ja consumiu o evento e este nao roda.
        setOnContextMenuRequested(ev -> {
            if (mapContextMenuFactory == null) return;
            if (blocksPan(ev.getTarget())) return;          // painel flutuante/puxadores
            if (pendingPlacement != null) return;           // posicionando: nao atrapalha
            Point2D world = worldLayer.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            ContextMenu menu = mapContextMenuFactory.apply(world);
            Menus.mostrar(menu, this, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });

        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (lassoing) {
                lassoing = false;
                lasso.setVisible(false);
                applyLasso(e.isShiftDown());
                e.consume();
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY) panning = false;
            // panDistance sobrevive ate o proximo press: o MOUSE_CLICKED chega
            // depois do release e precisa saber se houve arrasto.
        });

        // Clique no vazio limpa a selecao. Chega aqui por bolha: pontos e
        // feixes consomem o clique deles, entao so o fundo aciona isto.
        addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (pendingPlacement != null) return;          // posicionando: outro fluxo
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (panDistance > PAN_CLICK_SLOP) return;      // foi arrasto do mapa
            if (blocksPan(e.getTarget())) return;          // painel flutuante
            if (backgroundClickAction != null) backgroundClickAction.run();
        });

        // captura clique apenas quando estamos no modo "posicionar ponto"
        addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (pendingPlacement == null) return;
            if (e.getButton() != MouseButton.PRIMARY) return;
            // Arrastou para achar o lugar? Isso foi pan, nao a escolha do ponto.
            // Sem isso, navegar no modo "posicionar" largaria um ponto no fim
            // de cada arrasto.
            if (panDistance > PAN_CLICK_SLOP) return;
            Point2D w = worldLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
            Consumer<Point2D> cb = pendingPlacement;
            cancelPlacePointMode();
            cb.accept(w);
            e.consume();
        });
        // Pelo flag `panning` em vez de getButton(): durante o arrasto o botao
        // nem sempre chega preenchido no evento.
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (lassoing) {
                double dx = e.getX() - lassoX0, dy = e.getY() - lassoY0;
                lasso.setX(Math.min(lassoX0, e.getX()));
                lasso.setY(Math.min(lassoY0, e.getY()));
                lasso.setWidth(Math.abs(dx));
                lasso.setHeight(Math.abs(dy));

                // Conta como deslocamento: sem isto o MOUSE_CLICKED que vem
                // depois do release acharia que foi clique no vazio e limparia
                // a selecao que o laco acabou de fazer.
                panDistance = Math.max(panDistance, Math.hypot(dx, dy));
                e.consume();
                return;
            }
            if (!panning) return;
            double dx = e.getSceneX() - panAnchorX;
            double dy = e.getSceneY() - panAnchorY;
            panDistance = Math.max(panDistance, Math.hypot(dx, dy));
            worldLayer.setTranslateX(tx0 + dx);
            worldLayer.setTranslateY(ty0 + dy);
            updateTiles();
            e.consume();   // arrastando o mapa, ninguem mais mexe
        });

        // atalhos de teclado quando o pane tem foco / na scene
        setFocusTraversable(true);
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) return;
            scene.setOnKeyPressed(ev -> {
                switch (ev.getCode()) {
                    case PLUS, ADD, EQUALS -> zoomCentered(1.25);
                    case MINUS, SUBTRACT -> zoomCentered(1 / 1.25);
                    case DIGIT0, NUMPAD0 -> fitToView();
                    case F11 -> toggleFullScreen();
                    case ESCAPE -> {
                        // ESC serve para desistir do que estiver em curso:
                        // primeiro o modo de posicionar, depois a seleção.
                        if (pendingPlacement != null) cancelPlacePointMode();
                        else if (!multiSelection.isEmpty()) clearSelection();
                        else return;
                    }
                    default -> { return; }
                }
                ev.consume();
            });
        });
    }

    public ObjectProperty<Project> projectProperty() { return project; }
    public ObjectProperty<NetworkPoint> selectedPointProperty() { return selectedPoint; }

    // ------------------------ Seleção múltipla ------------------------

    /** Pontos atualmente selecionados, em ordem de seleção. */
    public java.util.Set<NetworkPoint> selectedPoints() {
        return java.util.Collections.unmodifiableSet(multiSelection);
    }

    /** Avisado sempre que a seleção muda, para o chamador atualizar a interface. */
    public void setOnSelectionChanged(Runnable r) { this.onSelectionChanged = r; }

    public void clearSelection() {
        if (multiSelection.isEmpty()) return;
        multiSelection.clear();
        refreshSelectionVisuals();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    private void toggleSelected(NetworkPoint np) {
        if (!multiSelection.remove(np)) multiSelection.add(np);
        refreshSelectionVisuals();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    /**
     * Repinta só o anel de seleção.
     *
     * Antes isto chamava redraw(), que reconstrói a camada inteira — caro, e
     * pior: trocava os nós no meio de uma interação do usuário.
     */
    private void refreshSelectionVisuals() {
        for (var e : nodeCircles.entrySet()) {
            boolean sel = multiSelection.contains(e.getKey());
            e.getValue().setStroke(sel ? Color.DEEPSKYBLUE : Color.WHITE);
            sizeNode(e.getValue(), sel);
        }
    }

    /**
     * Seleciona os pontos dentro do retângulo desenhado.
     *
     * @param adicionar true mantém o que já estava selecionado (Shift junto)
     */
    private void applyLasso(boolean adicionar) {
        Project p = project.get();
        if (p == null) return;
        if (!adicionar) multiSelection.clear();

        // O retângulo está em coordenadas de TELA; os pontos, em mundo.
        // Converter os cantos uma vez é mais barato que converter cada ponto.
        Point2D a = worldLayer.sceneToLocal(localToScene(lasso.getX(), lasso.getY()));
        Point2D b = worldLayer.sceneToLocal(localToScene(
                lasso.getX() + lasso.getWidth(), lasso.getY() + lasso.getHeight()));
        double x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());

        for (NetworkPoint np : p.getPoints()) {
            if (np.getX() >= x0 && np.getX() <= x1 && np.getY() >= y0 && np.getY() <= y1) {
                multiSelection.add(np);
            }
        }
        refreshSelectionVisuals();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    /**
     * A imagem de fundo que está carregada, ou null.
     *
     * Serve ao terreno 3D, que a veste no relevo. Vem daqui, e não de uma
     * segunda leitura do arquivo: um GeoTIFF de voo tem centenas de MB, e o
     * que está aqui já veio subamostrado no tamanho que coube.
     */
    public Image currentImage() {
        ImageView v = viewAtiva();
        return v == null ? null : v.getImage();
    }

    /** As imagens carregadas, na ordem de pintura. Serve ao terreno 3D. */
    public java.util.List<Image> currentImages() {
        java.util.List<Image> out = new java.util.ArrayList<>();
        for (ImageLayer l : layers) {
            ImageView v = imageViews.get(l.getId());
            if (v != null && v.getImage() != null) out.add(v.getImage());
        }
        return out;
    }

    private ImageView viewAtiva() {
        ImageView v = selectedLayerId == null ? null : imageViews.get(selectedLayerId);
        if (v != null) return v;
        for (ImageLayer l : layers) {
            ImageView u = imageViews.get(l.getId());
            if (u != null && u.getImage() != null) return u;
        }
        return null;
    }

    /** Qual camada esta sendo ajustada, ou null. */
    public String selectedLayerId() { return selectedLayerId; }

    /**
     * A imagem ja decodificada desta camada.
     *
     * O MapPane guarda os bitmaps porque e' ele quem os desenha; quem pede
     * (o terreno 3D, a proporcao para encaixe) evita assim reler do disco um
     * GeoTIFF de centenas de MB.
     */
    public void setLayerImage(String layerId, Image image, double worldW, double worldH) {
        if (layerId == null) return;
        ImageView v = imageViews.computeIfAbsent(layerId, k -> criarImageView(k));
        v.setImage(image);
        if (image != null && worldW > 0 && worldH > 0) {
            // Estica a imagem (possivelmente subamostrada) ao tamanho logico,
            // para o espaco de coordenadas dos pontos nao mudar.
            v.setFitWidth(worldW);
            v.setFitHeight(worldH);
            v.setPreserveRatio(false);
            v.setSmooth(true);
        } else {
            v.setFitWidth(0);
            v.setFitHeight(0);
        }
        tamanhoLogico.put(layerId, new double[] { worldW, worldH });
        adotarTamanhoLogico();
        if (image != null) scheduleFitToView();
    }

    /**
     * Em modo imagem, o mundo e' a PRIMEIRA foto.
     *
     * Duas fotos nao podem ser o mundo ao mesmo tempo: e' delas que sai o
     * espaco de coordenadas dos pontos. Sobrepor varias e' coisa de modo
     * mapa, onde cada uma tem coordenadas proprias.
     */
    private void adotarTamanhoLogico() {
        double[] t = layers.isEmpty() ? null : tamanhoLogico.get(layers.get(0).getId());
        if (t == null && layers.isEmpty() && tamanhoLogico.size() == 1) {
            // Imagem carregada antes de as camadas serem descritas: e' a que ha.
            t = tamanhoLogico.values().iterator().next();
        }
        if (t == null) return;
        this.worldWidth = t[0];
        this.worldHeight = t[1];
    }

    private ImageView criarImageView(String layerId) {
        ImageView v = new ImageView();
        instalarArrasto(v, layerId);
        return v;
    }

    /**
     * Sincroniza o que se ve com as camadas do projeto.
     *
     * @param camadas na ordem de pintura — a ultima cobre as anteriores
     * @param ativa   id da que recebe puxadores e arrasto, ou null
     */
    public void applyLayers(java.util.List<ImageLayer> camadas, String ativa) {
        this.layers = camadas == null ? new java.util.ArrayList<>()
                                      : new java.util.ArrayList<>(camadas);
        this.selectedLayerId = ativa;

        // Fora as views de camadas que sumiram, senao a imagem removida
        // continuaria na tela ate o proximo projeto.
        java.util.Set<String> vivos = new java.util.HashSet<>();
        for (ImageLayer l : layers) vivos.add(l.getId());
        imageViews.keySet().removeIf(id -> !vivos.contains(id));

        imagesLayer.getChildren().clear();
        for (ImageLayer l : layers) {
            ImageView v = imageViews.computeIfAbsent(l.getId(), this::criarImageView);
            imagesLayer.getChildren().add(v);
        }

        ImageLayer sel = null;
        for (ImageLayer l : layers) {
            if (l.getId().equals(selectedLayerId)) sel = l;
        }
        if (sel == null && !layers.isEmpty()) {
            sel = layers.get(layers.size() - 1);
            this.selectedLayerId = sel.getId();
        }
        this.overlay = sel == null ? null : sel.getOverlay();

        tamanhoLogico.keySet().removeIf(id -> !vivos.contains(id));
        adotarTamanhoLogico();
        posicionarCamadas();
    }

    public void setImage(Image image) {
        setImage(image, image == null ? 0 : image.getWidth(),
                        image == null ? 0 : image.getHeight());
    }

    /**
     * Define a imagem de fundo com suas dimensões LÓGICAS (da imagem original).
     * A imagem exibida pode estar subamostrada; o ImageView é esticado de volta
     * a (worldW, worldH) para preservar o espaço de coordenadas dos pontos.
     */
    public void setImage(Image image, double worldW, double worldH) {
        if (image == null) {
            imageViews.clear();
            imagesLayer.getChildren().clear();
            layers = new java.util.ArrayList<>();
            selectedLayerId = null;
            overlay = null;
            this.worldWidth = worldW;
            this.worldHeight = worldH;
            posicionarCamadas();
            redraw();
            return;
        }
        // Uma imagem so: cria (ou reaproveita) a camada unica. Serve a quem
        // ainda pensa em termos de "a imagem", como o modo imagem.
        ImageLayer unica = layers.isEmpty() ? new ImageLayer() : layers.get(0);
        if (layers.isEmpty()) layers.add(unica);
        setLayerImage(unica.getId(), image, worldW, worldH);
        applyLayers(layers, unica.getId());
        redraw();
    }

    /** Chama fitToView() agora, ou quando o Pane ganhar dimensao real. */
    private void scheduleFitToView() {
        if (getWidth() > 0 && getHeight() > 0) {
            fitToView();
            return;
        }
        javafx.beans.InvalidationListener[] one = new javafx.beans.InvalidationListener[1];
        one[0] = obs -> {
            if (getWidth() > 0 && getHeight() > 0) {
                widthProperty().removeListener(one[0]);
                heightProperty().removeListener(one[0]);
                fitToView();
            }
        };
        widthProperty().addListener(one[0]);
        heightProperty().addListener(one[0]);
    }

    public Point2D screenToWorld(double sceneX, double sceneY) {
        return worldLayer.sceneToLocal(sceneX, sceneY);
    }

    // ------------------------ Zoom / pan / view ------------------------

    /**
     * Zoom pivotando em (px, py) - coordenadas locais do Pane.
     * Garante que o ponto sob o cursor permaneca no mesmo lugar visual.
     */
    private void zoomAt(double factor, double px, double py) {
        double newScale = Math.max(minScale(), Math.min(maxScale(), scale * factor));
        if (newScale == scale) return;
        // Posicao no "mundo" do ponto sob o cursor, ANTES da escala
        double wx = (px - worldLayer.getTranslateX()) / scale;
        double wy = (py - worldLayer.getTranslateY()) / scale;
        scale = newScale;
        scaleTx.setX(scale);
        scaleTx.setY(scale);
        // Reposiciona para que (wx, wy) continue debaixo de (px, py)
        worldLayer.setTranslateX(px - wx * scale);
        worldLayer.setTranslateY(py - wy * scale);
        updateZoomCompensations();
        updateTiles();
        buildOverlayHandles();   // puxadores tem tamanho fixo em tela
    }

    /**
     * Limites de escala. Os dois modos vivem em ordens de grandeza bem
     * diferentes: em modo imagem a unidade é o pixel (escala ~1), em modo mapa
     * é o metro de Mercator (escala ~1e-4 num zoom regional). Um clamp fixo
     * travaria o mapa num zoom só.
     */
    private double minScale() {
        if (!isMapMode()) return 0.001;
        return Mercator.scaleForZoom(2) * 0.5;   // um pouco além do mundo inteiro
    }

    private double maxScale() {
        if (!isMapMode()) return 50.0;
        // Deixa passar 2 níveis além do zoom nativo: o tile fica ampliado, mas
        // é o comportamento esperado de quem quer conferir detalhe fino.
        int max = Math.min(22, tileLayer.getSource().maxZoom() + 2);
        return Mercator.scaleForZoom(max);
    }

    private void zoomCentered(double factor) {
        zoomAt(factor, getWidth() / 2.0, getHeight() / 2.0);
    }

    /** Zoom in pelo centro da viewport — mesmo passo do botao "+" e da tecla "+". */
    public void zoomIn() { zoomCentered(1.25); }

    /** Zoom out pelo centro da viewport — mesmo passo do botao "-" e da tecla "-". */
    public void zoomOut() { zoomCentered(1 / 1.25); }

    /**
     * Estado do toggle "Feixes" do painel flutuante. Exposto para que o menu
     * "Exibir" possa espelhar o botao (bind bidirecional): alterar qualquer um
     * dos dois ja grava em Settings e redesenha, porque quem faz isso e o
     * listener registrado em buildControlPanel().
     */
    public javafx.beans.property.BooleanProperty beamsVisibleProperty() {
        return beamsToggle.selectedProperty();
    }

    public void fitToView() {
        if (isMapMode()) { fitMapToPoints(); return; }
        Image img = currentImage();
        double iw = worldWidth > 0 ? worldWidth : (img == null ? 0 : img.getWidth());
        double ih = worldHeight > 0 ? worldHeight : (img == null ? 0 : img.getHeight());
        if (img == null || iw <= 0 || ih <= 0 || getWidth() == 0 || getHeight() == 0) {
            scale = 1.0;
            scaleTx.setX(1); scaleTx.setY(1);
            worldLayer.setTranslateX(0); worldLayer.setTranslateY(0);
            return;
        }
        double sx = getWidth() / iw;
        double sy = getHeight() / ih;
        scale = Math.min(sx, sy) * 0.98;
        scaleTx.setX(scale); scaleTx.setY(scale);
        worldLayer.setTranslateX((getWidth() - iw * scale) / 2.0);
        worldLayer.setTranslateY((getHeight() - ih * scale) / 2.0);
        updateZoomCompensations();
    }

    // ------------------------ Sobreposicao da imagem ------------------------

    /**
     * O clique deve ser tratado por outra coisa que nao o pan do mapa?
     *
     * Camada de pontos, painel flutuante e puxadores sempre bloqueiam. A
     * imagem de fundo bloqueia apenas durante o ajuste da sobreposicao (e se
     * nao estiver travada) — fora disso ela e so fundo, e arrastar sobre ela
     * move o mapa, que e o esperado.
     *
     * Feixes ficam de fora de proposito: sao areas grandes e translucidas, e
     * se bloqueassem o pan o mapa ficaria intocavel em volta de cada antena.
     */
    private boolean blocksPan(javafx.event.EventTarget target) {
        if (!(target instanceof javafx.scene.Node n)) return false;
        for (javafx.scene.Node cur = n; cur != null; cur = cur.getParent()) {
            if (cur == nodesLayer || cur == controlPanel || cur == overlayHandles) return true;
            if (cur instanceof ImageView v && imageViews.containsValue(v)
                    && isOverlayDraggable()) return true;
            if (cur == this) return false;
        }
        return false;
    }

    private boolean isOverlayDraggable() {
        return adjustingOverlay && overlay != null && !overlay.isLocked()
                && viewAtiva() != null && viewAtiva().getImage() != null && isMapMode();
    }

    /** Avisado sempre que o usuário move ou redimensiona a imagem. */
    public void setOnOverlayChanged(Runnable r) { this.onOverlayChanged = r; }

    /** Liga/desliga o contorno com puxadores de canto. */
    public void setOverlayAdjustMode(boolean on) {
        this.adjustingOverlay = on;
        posicionarCamadas();
    }

    public boolean isOverlayAdjustMode() { return adjustingOverlay; }

    /**
     * Aplica posição, tamanho, opacidade e visibilidade da imagem.
     *
     * Em modo imagem (sem mapa base) nada disso vale: a imagem É o mundo,
     * ocupa de (0,0) ao tamanho lógico e fica opaca — deixá-la translúcida
     * sobre o fundo escuro do pane só deixaria o mapa lavado.
     */
    public void refreshLayers() { posicionarCamadas(); }

    /**
     * Põe cada camada no seu lugar, com o seu tamanho e a sua opacidade.
     *
     * Em modo imagem nada disso vale: a foto É o mundo, ocupa de (0,0) ao
     * tamanho lógico e fica opaca. E ali só a PRIMEIRA camada aparece —
     * duas fotos não podem ser o mundo ao mesmo tempo, porque é delas que sai
     * o espaço de coordenadas dos pontos. Sobrepor várias é coisa de modo
     * mapa, onde cada uma tem coordenadas próprias.
     */
    private void posicionarCamadas() {
        boolean modoMapa = isMapMode();
        boolean algumaImagem = false;

        for (int i = 0; i < layers.size(); i++) {
            ImageLayer l = layers.get(i);
            ImageView v = imageViews.get(l.getId());
            if (v == null) continue;
            boolean temImg = v.getImage() != null;
            if (temImg) algumaImagem = true;

            if (!modoMapa) {
                v.setX(0);
                v.setY(0);
                v.setFitWidth(worldWidth);
                v.setFitHeight(worldHeight);
                v.setOpacity(1.0);
                v.setVisible(temImg && i == 0);
                continue;
            }

            ImageOverlay o = l.getOverlay();
            v.setX(o.getX());
            v.setY(o.getY());
            v.setFitWidth(o.getWidth());
            v.setFitHeight(o.getHeight());
            v.setPreserveRatio(false);
            v.setOpacity(o.getOpacity());
            v.setVisible(temImg && o.isVisible());
            v.setCursor(podeArrastar(l) ? Cursor.MOVE : Cursor.DEFAULT);
        }

        if (!modoMapa) {
            overlayHandles.getChildren().clear();
            setOpacityControlVisible(false);
            return;
        }

        setOpacityControlVisible(algumaImagem && overlay != null);
        if (overlay == null || !algumaImagem) {
            overlayHandles.getChildren().clear();
            return;
        }
        if (opacitySlider != null && opacitySlider.getValue() != overlay.getOpacity()) {
            opacitySlider.setValue(overlay.getOpacity());
        }
        buildOverlayHandles();
    }

    /** Esta camada pode ser arrastada agora? */
    private boolean podeArrastar(ImageLayer l) {
        if (!adjustingOverlay || !isMapMode() || l == null || l.getOverlay().isLocked()) {
            return false;
        }
        ImageView v = imageViews.get(l.getId());
        return v != null && v.getImage() != null;
    }

    /**
     * Converte metros no chão para unidades de mundo.
     *
     * Em modo mapa o mundo está em metros de MERCATOR, que são maiores que os
     * do chão por 1/cos(latitude) — cerca de 9% no sul do Brasil e mais perto
     * dos polos. Desenhar um alcance de 5 km sem essa correção faria o setor
     * cobrir ~5,5 km no terreno.
     *
     * Em modo imagem o mundo está em pixels, e a escala vem do projeto porque
     * o programa não tem como adivinhar se a foto cobre um quarteirão ou uma
     * cidade.
     */
    private double groundMetersToWorld(double meters, double worldY) {
        if (meters <= 0) return 0;
        if (isMapMode()) {
            double k = Mercator.groundScaleAt(Mercator.latOfWorldY(worldY));
            return k <= 0 ? meters : meters / k;
        }
        Project p = project.get();
        double mpp = p == null ? 1.0 : p.getMetersPerPixel();
        return mpp <= 0 ? meters : meters / mpp;
    }

    /**
     * Angulo sem casa decimal a toa: 120 aparece como "120", 5,5 como "5,5".
     * Desde que os angulos viraram double, formatar com %d estourava
     * IllegalFormatConversionException.
     */
    static String deg(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    /** Alcance legível: metros abaixo de 1 km, quilômetros acima. */
    static String formatRange(double meters) {
        if (meters <= 0) return "não definido";
        return meters < 1000
                ? String.format("%.0f m", meters)
                : String.format("%.2f km", meters / 1000);
    }

    /** Proporção largura/altura da imagem carregada, ou 1 se não houver. */
    public double imageAspectRatio() {
        Image img = currentImage();
        if (img == null || img.getHeight() <= 0) return 1;
        return img.getWidth() / img.getHeight();
    }

    /**
     * Arrasto do corpo de uma imagem: move aquela sobreposição.
     *
     * Pegar uma imagem também a torna a ativa. Com várias empilhadas, obrigar
     * a escolher numa lista antes de arrastar seria um passo a mais para dizer
     * o que o gesto já diz — e o JavaFX entrega o evento à de cima, que é a
     * que está debaixo do cursor.
     */
    private void instalarArrasto(ImageView v, String layerId) {
        v.setOnMousePressed(ev -> {
            ImageLayer l = camada(layerId);
            if (ev.getButton() != MouseButton.PRIMARY || !podeArrastar(l)) return;
            if (!layerId.equals(selectedLayerId)) {
                selectedLayerId = layerId;
                overlay = l.getOverlay();
                posicionarCamadas();
                if (onLayerSelected != null) onLayerSelected.accept(layerId);
            }
            Point2D w = worldLayer.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            overlayDragDX = w.getX() - l.getOverlay().getX();
            overlayDragDY = w.getY() - l.getOverlay().getY();
            ev.consume();
        });
        v.setOnMouseDragged(ev -> {
            ImageLayer l = camada(layerId);
            if (ev.getButton() != MouseButton.PRIMARY || !podeArrastar(l)) return;
            Point2D w = worldLayer.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            l.getOverlay().setX(w.getX() - overlayDragDX);
            l.getOverlay().setY(w.getY() - overlayDragDY);
            posicionarCamadas();
            if (onOverlayChanged != null) onOverlayChanged.run();
            ev.consume();
        });
    }

    private ImageLayer camada(String id) {
        for (ImageLayer l : layers) {
            if (l.getId().equals(id)) return l;
        }
        return null;
    }

    /** Avisa quem desenha a lista lateral que a camada ativa mudou. */
    private java.util.function.Consumer<String> onLayerSelected;

    public void setOnLayerSelected(java.util.function.Consumer<String> c) {
        this.onLayerSelected = c;
    }

    /**
     * Redesenha contorno e puxadores. Tudo é dimensionado dividindo pela
     * escala para manter tamanho constante NA TELA — um puxador que encolhe
     * junto com o zoom vira impossível de agarrar.
     */
    /**
     * Garante que contorno e puxadores existam e estejam no lugar.
     *
     * Reconstrói os nós SÓ quando a composição muda (entrou/saiu do modo de
     * ajuste, travou/destravou). Durante o arrasto apenas reposiciona.
     *
     * Antes isto recriava tudo a cada evento de arrasto — e destruía o próprio
     * retângulo que estava recebendo o arrasto. O JavaFX então parava de
     * entregar os eventos e o redimensionamento "soltava sozinho".
     */
    private void buildOverlayHandles() {
        ImageView ativa = viewAtiva();
        boolean mostrar = adjustingOverlay && overlay != null && overlay.isPlaced()
                && ativa != null && ativa.getImage() != null && isMapMode();
        if (!mostrar) {
            overlayHandles.getChildren().clear();
            overlayOutline = null;
            java.util.Arrays.fill(cornerNodes, null);
            return;
        }

        boolean querCantos = !overlay.isLocked();
        boolean temCantos = cornerNodes[0] != null;
        if (overlayOutline == null || querCantos != temCantos) {
            createOverlayHandles(querCantos);
        }
        positionOverlayHandles();
    }

    private void createOverlayHandles(boolean comCantos) {
        overlayHandles.getChildren().clear();
        java.util.Arrays.fill(cornerNodes, null);

        overlayOutline = new javafx.scene.shape.Rectangle();
        overlayOutline.setFill(null);
        overlayOutline.setStroke(Color.DODGERBLUE);
        overlayOutline.setMouseTransparent(true);
        overlayHandles.getChildren().add(overlayOutline);

        if (!comCantos) return;   // travada: mostra o contorno, sem puxadores

        int i = 0;
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                cornerNodes[i++] = createCornerHandle(cx, cy);
            }
        }
        overlayHandles.getChildren().addAll(
                cornerNodes[0], cornerNodes[1], cornerNodes[2], cornerNodes[3]);
    }

    private javafx.scene.shape.Rectangle createCornerHandle(int cx, int cy) {
        javafx.scene.shape.Rectangle h = new javafx.scene.shape.Rectangle();
        h.setFill(Color.WHITE);
        h.setStroke(Color.DODGERBLUE);
        h.setCursor(cx == cy ? Cursor.NW_RESIZE : Cursor.NE_RESIZE);
        h.getProperties().put("cx", cx);
        h.getProperties().put("cy", cy);

        h.setOnMousePressed(javafx.event.Event::consume);
        h.setOnMouseDragged(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            Point2D w = worldLayer.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            resizeOverlayFromCorner(cx, cy, w);
            ev.consume();
        });
        return h;
    }

    /** Ajusta geometria e espessuras. Tamanhos divididos pela escala para
     *  manterem-se constantes na tela em qualquer zoom. */
    private void positionOverlayHandles() {
        if (overlayOutline == null || overlay == null) return;
        overlayOutline.setX(overlay.getX());
        overlayOutline.setY(overlay.getY());
        overlayOutline.setWidth(overlay.getWidth());
        overlayOutline.setHeight(overlay.getHeight());
        overlayOutline.setStrokeWidth(2 / scale);
        overlayOutline.getStrokeDashArray().setAll(10 / scale, 7 / scale);

        double size = 11 / scale;
        for (javafx.scene.shape.Rectangle h : cornerNodes) {
            if (h == null) continue;
            int cx = (int) h.getProperties().get("cx");
            int cy = (int) h.getProperties().get("cy");
            double px = overlay.getX() + cx * overlay.getWidth();
            double py = overlay.getY() + cy * overlay.getHeight();
            h.setX(px - size / 2);
            h.setY(py - size / 2);
            h.setWidth(size);
            h.setHeight(size);
            h.setStrokeWidth(2 / scale);
        }
    }

    /**
     * Redimensiona mantendo fixo o canto OPOSTO ao arrastado e preservando a
     * proporção da imagem — esticar em um eixo só distorceria uma foto aérea,
     * que é justamente o que não se quer num mapa.
     */
    private void resizeOverlayFromCorner(int cx, int cy, Point2D w) {
        double anchorX = (cx == 1) ? overlay.getX() : overlay.getX() + overlay.getWidth();
        double anchorY = (cy == 1) ? overlay.getY() : overlay.getY() + overlay.getHeight();

        double newW = Math.abs(w.getX() - anchorX);
        double minW = 10 / Math.max(scale, 1e-12);   // ~10 px de tela
        if (newW < minW) newW = minW;
        double newH = newW / Math.max(imageAspectRatio(), 1e-9);

        overlay.setWidth(newW);
        overlay.setHeight(newH);
        overlay.setX((cx == 1) ? anchorX : anchorX - newW);
        overlay.setY((cy == 1) ? anchorY : anchorY - newH);

        posicionarCamadas();
        if (onOverlayChanged != null) onOverlayChanged.run();
    }

    // ------------------------ Mapa base (tiles) ------------------------

    /** {@code true} quando o mundo está em metros de Mercator, não em pixels de imagem. */
    public boolean isMapMode() { return tileLayer.getSource().isMap(); }

    public TileSource getBasemap() { return tileLayer.getSource(); }

    /**
     * Troca o provedor de mapa base. Sair de/entrar em modo mapa muda o
     * significado das coordenadas, então quem chama já deve ter tratado os
     * pontos existentes — aqui só reposicionamos a câmera.
     */
    public void setBasemap(TileSource s) {
        TileSource next = s == null ? TileSource.NONE : s;
        if (next == tileLayer.getSource()) return;
        boolean wasMap = isMapMode();
        tileLayer.setSource(next);

        attributionLabel.setText(next.attribution());
        attributionLabel.setVisible(next.isMap());
        positionAttribution();

        // Trocar de modo muda o significado das coordenadas da imagem, entao
        // reaplica a sobreposicao com as regras do modo novo.
        posicionarCamadas();

        if (next.isMap() && !wasMap) {
            scheduleFitToView();
        } else if (!next.isMap() && wasMap) {
            scale = 1.0;
            scaleTx.setX(1); scaleTx.setY(1);
            scheduleFitToView();
        }
        updateTiles();
        redraw();
    }

    /** Recalcula a cobertura de tiles para a viewport atual. */
    private void updateTiles() {
        if (!isMapMode()) return;
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || scale <= 0) return;
        double tx = worldLayer.getTranslateX();
        double ty = worldLayer.getTranslateY();
        tileLayer.update(scale,
                (0 - tx) / scale, (0 - ty) / scale,
                (w - tx) / scale, (h - ty) / scale);
    }

    /** Centraliza a viewport numa coordenada geográfica, no zoom de tile informado. */
    public void centerOn(double lat, double lon, int zoom) {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        scale = Math.max(minScale(), Math.min(maxScale(), Mercator.scaleForZoom(zoom)));
        scaleTx.setX(scale);
        scaleTx.setY(scale);
        double wx = Mercator.worldX(Mercator.clampLon(lon));
        double wy = Mercator.worldY(Mercator.clampLat(lat));
        worldLayer.setTranslateX(w / 2 - wx * scale);
        worldLayer.setTranslateY(h / 2 - wy * scale);
        updateZoomCompensations();
        updateTiles();
    }

    /**
     * Enquadra os pontos do projeto. Sem pontos (ou com um só), cai na última
     * visão salva no projeto, que é o que faz sentido ao abrir um mapa vazio.
     */
    private void fitMapToPoints() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        Project p = project.get();
        List<NetworkPoint> pts = p == null ? List.of() : p.getPoints();

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (NetworkPoint np : pts) {
            minX = Math.min(minX, np.getX()); maxX = Math.max(maxX, np.getX());
            minY = Math.min(minY, np.getY()); maxY = Math.max(maxY, np.getY());
        }

        if (pts.isEmpty()) {
            if (p != null) centerOn(p.getViewLat(), p.getViewLon(), p.getViewZoom());
            else centerOn(-15.78, -47.93, 4);
            return;
        }

        double spanX = maxX - minX;
        double spanY = maxY - minY;
        if (spanX < 1 && spanY < 1) {
            // Um ponto só (ou todos no mesmo lugar): centraliza num zoom de bairro.
            centerOn(Mercator.latOfWorldY(minY), Mercator.lonOfWorldX(minX), 15);
            return;
        }

        double target = Math.min(w / Math.max(spanX, 1), h / Math.max(spanY, 1)) * 0.85;
        scale = Math.max(minScale(), Math.min(maxScale(), target));
        scaleTx.setX(scale);
        scaleTx.setY(scale);
        worldLayer.setTranslateX(w / 2 - ((minX + maxX) / 2) * scale);
        worldLayer.setTranslateY(h / 2 - ((minY + maxY) / 2) * scale);
        updateZoomCompensations();
        updateTiles();
    }

    /** Latitude do centro da viewport. Só faz sentido em modo mapa. */
    public double centerLat() { return Mercator.latOfWorldY(viewCenterWorld().getY()); }

    /** Longitude do centro da viewport. Só faz sentido em modo mapa. */
    public double centerLon() { return Mercator.lonOfWorldX(viewCenterWorld().getX()); }

    /** Zoom de tile equivalente à escala atual. */
    public int currentZoom() { return Mercator.zoomForScale(scale, 0, 22); }

    // ------------------------ Hipoteses sobre o enlace ------------------------

    /**
     * Onde a torre ficaria, e onde o terreno corta o enlace.
     *
     * As duas marcas vivem juntas porque contam a mesma historia: a primeira e
     * a hipotese que se esta testando no perfil, a segunda e o problema que
     * essa hipotese tenta resolver. Ficam fora do projeto — sao vista, e somem
     * quando o perfil fecha.
     */
    private final Group hintLayer = new Group();

    /**
     * Marca o lugar para onde a torre iria, sem move-la.
     *
     * Arrastar a torre no perfil mudava so o grafico; no mapa nada indicava
     * para onde ela estava indo, e era preciso aplicar para descobrir. Aqui a
     * posicao aparece antes da decisao.
     */
    public void setGhostTower(NetworkPoint origem, double worldX, double worldY) {
        if (origem == null) return;
        ghosts.put(origem, new double[] { worldX, worldY });
        redrawHints();
    }

    /** Tira o fantasma de um ponto; null tira todos. */
    public void clearGhostTower(NetworkPoint origem) {
        if (origem == null) ghosts.clear();
        else ghosts.remove(origem);
        redrawHints();
    }

    public void clearGhostTower() { clearGhostTower(null); }

    /**
     * Marca no mapa onde o terreno corta a visada do enlace.
     *
     * @param worldX worldY ponto da pior intrusao; NaN apaga
     * @param metros quanto o terreno sobe acima da linha de visada
     */
    public void setObstruction(double worldX, double worldY, double metros) {
        obstX = worldX;
        obstY = worldY;
        obstM = metros;
        temObst = !Double.isNaN(worldX) && !Double.isNaN(worldY) && metros > 0;
        redrawHints();
    }

    public void clearObstruction() {
        temObst = false;
        redrawHints();
    }

    private final java.util.Map<NetworkPoint, double[]> ghosts = new java.util.LinkedHashMap<>();
    private double obstX, obstY, obstM;
    private boolean temObst;

    /**
     * Marcas cujo tamanho e' dado em PIXELS de tela, nao em metros.
     *
     * O worldLayer inteiro e escalado pelo zoom. Um circulo de 9 unidades de
     * mundo e' um circulo de 9 METROS: parece razoavel olhando um enlace de 1
     * km e some por completo ao afastar o mapa. Os pontos de rede ja resolvem
     * isso dividindo o raio pela escala (ver sizeNode), e o fantasma precisa
     * seguir a mesma regra — senao ele deixa de parecer um ponto de rede, que
     * e' justamente o que ele esta representando.
     */
    private record MarcaPx(javafx.scene.shape.Shape forma, double raioPx, double espessuraPx,
                           double[] tracoPx, double cx, double cy) {}

    private final List<MarcaPx> marcasPx = new ArrayList<>();

    private void redrawHints() {
        hintLayer.getChildren().clear();
        hintCompensators.clear();
        marcasPx.clear();

        for (var e : ghosts.entrySet()) {
            NetworkPoint origem = e.getKey();
            double gx = e.getValue()[0], gy = e.getValue()[1];

            // Linha da posicao de hoje ate a proposta: mostra o quanto andou.
            Line guia = new Line(origem.getX(), origem.getY(), gx, gy);
            guia.setStroke(GHOST_COR);
            guia.setMouseTransparent(true);
            marcasPx.add(new MarcaPx(guia, 0, 2, new double[] { 6, 5 }, 0, 0));

            // O fantasma E' um ponto de rede: mesmo raio dos outros, so que
            // vazado e tracejado, para ler como "ficaria aqui" e nao "esta
            // aqui". Mesmo tamanho aparente em qualquer zoom.
            Circle alvo = new Circle(gx, gy, NODE_RADIUS_PX);
            alvo.setFill(GHOST_COR.deriveColor(0, 1, 1, 0.30));
            alvo.setStroke(GHOST_COR);
            alvo.setMouseTransparent(true);
            marcasPx.add(new MarcaPx(alvo, NODE_RADIUS_PX, 2.5, new double[] { 4, 3 }, gx, gy));

            // Rotulo na mesma posicao relativa do nome dos pontos de rede.
            Text rot = new Text(origem.getName() + " ficaria aqui");
            rot.setFont(Font.font("System", FontWeight.BOLD, 12));
            styleMapText(rot, GHOST_COR);
            rot.setMouseTransparent(true);
            positionLabel(rot, gx, gy);
            compensateHint(rot);

            hintLayer.getChildren().addAll(guia, alvo, rot);
        }

        if (temObst) {
            Circle halo = new Circle(obstX, obstY, NODE_RADIUS_PX + 5);
            halo.setFill(OBST_COR.deriveColor(0, 1, 1, 0.22));
            halo.setStroke(javafx.scene.paint.Color.TRANSPARENT);
            halo.setMouseTransparent(true);
            marcasPx.add(new MarcaPx(halo, NODE_RADIUS_PX + 5, 0, null, obstX, obstY));

            // Cruz: os extremos sao recalculados no zoom, porque o braco tambem
            // e' medido em pixels.
            Line a = new Line();
            Line b = new Line();
            for (Line l : new Line[] { a, b }) {
                l.setStroke(OBST_COR);
                l.setMouseTransparent(true);
            }
            marcasPx.add(new MarcaPx(a, NODE_RADIUS_PX, 3, null, obstX, obstY));
            marcasPx.add(new MarcaPx(b, NODE_RADIUS_PX, 3, null, obstX, obstY));

            Text rot = new Text(String.format("obstrui %.0f m", obstM));
            rot.setFont(Font.font("System", FontWeight.BOLD, 12));
            styleMapText(rot, OBST_COR);
            rot.setMouseTransparent(true);
            positionLabel(rot, obstX, obstY);
            compensateHint(rot);

            hintLayer.getChildren().addAll(halo, a, b, rot);
        }

        sizeHints();
    }

    /** Aplica a escala atual nas marcas medidas em pixels. */
    private void sizeHints() {
        double inv = scale <= 0 ? 1.0 : 1.0 / scale;
        for (MarcaPx m : marcasPx) {
            if (m.espessuraPx() > 0) m.forma().setStrokeWidth(m.espessuraPx() * inv);
            if (m.tracoPx() != null) {
                m.forma().getStrokeDashArray().setAll(
                        m.tracoPx()[0] * inv, m.tracoPx()[1] * inv);
            }
            if (m.forma() instanceof Circle c && m.raioPx() > 0) {
                c.setRadius(m.raioPx() * inv);
            } else if (m.forma() instanceof Line l && m.raioPx() > 0) {
                // Os dois bracos da cruz, distinguidos pela ordem de insercao.
                double r = m.raioPx() * inv;
                boolean primeiro = marcasPx.indexOf(m) % 2 == 1;
                l.setStartX(m.cx() - r);
                l.setEndX(m.cx() + r);
                l.setStartY(m.cy() + (primeiro ? -r : r));
                l.setEndY(m.cy() + (primeiro ? r : -r));
            }
        }
    }

    /**
     * Mantem o texto das marcas com tamanho de tela constante.
     *
     * Lista propria, separada de textCompensators: aquela e limpa a cada
     * redraw() dos nos, e estas marcas vivem por fora desse ciclo.
     */
    private final List<Scale> hintCompensators = new ArrayList<>();

    private void compensateHint(Text t) {
        double inv = scale == 0 ? 1.0 : 1.0 / scale;
        Scale sc = new Scale(inv, inv, t.getX(), t.getY());
        t.getTransforms().add(sc);
        hintCompensators.add(sc);
    }

    private void updateHintCompensators() {
        double inv = scale == 0 ? 1.0 : 1.0 / scale;
        for (Scale sc : hintCompensators) { sc.setX(inv); sc.setY(inv); }
    }

    private static final javafx.scene.paint.Color GHOST_COR = javafx.scene.paint.Color.web("#ffd54f");
    private static final javafx.scene.paint.Color OBST_COR = javafx.scene.paint.Color.web("#ff5252");

    // ------------------------ Feixe simulado ------------------------

    /**
     * Lobos de alcance calculado, por rádio.
     *
     * Não entram no projeto: dependem do relevo carregado e dos parâmetros da
     * simulação, e gravá-los guardaria um resultado que pode não valer mais
     * na próxima abertura. São vista, não dado.
     */
    /**
     * O alcance simulado, por rádio, como grade.
     *
     * Grade e não polígono: a sombra do relevo num terreno acidentado tem a
     * forma de uma mancha cheia de furos, e nenhum contorno por direção
     * representa isso. Com o contorno, o fundo de uma cava era pintado como
     * se tivesse sinal — 71% do que o desenho antigo preenchia, neste projeto,
     * estava na sombra.
     */
    private final java.util.Map<String, com.colmeia.radiomapper.rf.BeamCoverage.Cobertura>
            simBeams = new java.util.LinkedHashMap<>();

    /** Quais lobos saíram sem relevo — desenhados de outro jeito, ver redrawSimBeams. */
    private final java.util.Set<String> simSemRelevo = new java.util.HashSet<>();

    /** Desenha (ou remove, com polygon null) o alcance simulado de um rádio. */
    public void setSimulatedBeam(String radioId,
            com.colmeia.radiomapper.rf.BeamCoverage.Cobertura cob) {
        setSimulatedBeam(radioId, cob, true);
    }

    /**
     * @param comRelevo false quando a simulação não teve altitude com que
     *                  trabalhar. O lobo é então só o limite de RF em terreno
     *                  plano, e precisa parecer diferente: senão o usuário lê
     *                  como "o relevo permite isto" um desenho que não olhou
     *                  para relevo nenhum.
     */
    public void setSimulatedBeam(String radioId,
            com.colmeia.radiomapper.rf.BeamCoverage.Cobertura cob, boolean comRelevo) {
        if (radioId == null) return;
        if (cob == null || cob.vazia()) {
            simBeams.remove(radioId);
            simSemRelevo.remove(radioId);
        } else {
            simBeams.put(radioId, cob);
            if (comRelevo) simSemRelevo.remove(radioId);
            else simSemRelevo.add(radioId);
        }
        redrawSimBeams();
    }

    /**
    /** Liga ou desliga a pintura por qualidade, sem recalcular nada. */
    public void setSimBeamQuality(boolean v) {
        if (simQualidade == v) return;
        simQualidade = v;
        redrawSimBeams();
    }

    public boolean isSimBeamQuality() { return simQualidade; }

    /**
     * A grade de alcance deste rádio, como foi calculada.
     *
     * Serve ao terreno 3D, que precisa da MESMA grade para tingir o relevo —
     * refazer a conta lá daria outro resultado se algum parâmetro tivesse
     * mudado, e as duas telas passariam a discordar sobre onde há sinal.
     */
    public com.colmeia.radiomapper.rf.BeamCoverage.Cobertura simulatedCoverage(String radioId) {
        return simBeams.get(radioId);
    }

    private boolean simQualidade = Settings.simBeamQuality();

    public void clearSimulatedBeams() {
        if (simBeams.isEmpty()) return;
        simBeams.clear();
        simSemRelevo.clear();
        redrawSimBeams();
    }

    public boolean hasSimulatedBeams() { return !simBeams.isEmpty(); }

    /**
     * Uma imagem por rádio, esticada sobre a área que a varredura cobriu.
     *
     * A grade vira imagem porque é isso que ela é: uma célula por posição,
     * com ou sem sinal. Desenhá-la como imagem faz o buraco de sombra
     * aparecer como buraco, sem precisar contornar nada — e faz o custo de
     * redesenho não depender de quão recortada a cobertura ficou.
     *
     * Sem suavização de propósito: interpolar entre uma célula com sinal e a
     * vizinha sem sinal inventaria meio sinal na borda do buraco, que é
     * justamente o que se quer parar de fazer.
     */
    private void redrawSimBeams() {
        simBeamLayer.getChildren().clear();
        for (var e : simBeams.entrySet()) {
            var cob = e.getValue();
            boolean semRelevo = simSemRelevo.contains(e.getKey());

            javafx.scene.image.WritableImage img = pintarCobertura(cob, semRelevo);
            if (img == null) continue;

            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
            iv.setX(cob.minX());
            iv.setY(cob.minY());
            iv.setFitWidth(cob.maxX() - cob.minX());
            iv.setFitHeight(cob.maxY() - cob.minY());
            iv.setSmooth(false);
            // Contexto, não alvo: o feixe configurado e os pontos continuam
            // sendo o que se clica.
            iv.setMouseTransparent(true);
            simBeamLayer.getChildren().add(iv);
        }
    }

    /** A grade em cores: por nível quando pedido, em cor única quando não. */
    private javafx.scene.image.WritableImage pintarCobertura(
            com.colmeia.radiomapper.rf.BeamCoverage.Cobertura cob, boolean semRelevo) {
        if (cob == null || cob.vazia()) return null;
        int w = cob.cols(), h = cob.rows();
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(w, h);
        var pw = img.getPixelWriter();

        boolean porNivel = simQualidade && !semRelevo;
        Color unica = semRelevo ? SIM_BEAM_FILL_FLAT : SIM_BEAM_FILL;
        byte[] nivel = cob.nivel();
        double[] niveis = cob.niveis();

        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                byte n = nivel[r * w + c];
                if (n < 0) { pw.setArgb(c, r, 0); continue; }
                Color cor = porNivel && n < niveis.length
                        ? corDoNivel(niveis[n]).deriveColor(0, 1, 1, 0.42)
                        : unica;
                pw.setColor(c, r, cor);
            }
        }
        return img;
    }

    static Color corDoNivel(double dbm) {
        if (dbm >= -55) return Color.web("#21d07a");   // excelente
        if (dbm >= -65) return Color.web("#9ccc4f");   // bom
        if (dbm >= -75) return Color.web("#f2c037");   // aceitavel
        if (dbm >= -83) return Color.web("#f07c31");   // fraco
        return Color.web("#e1504a");                   // no limite
    }

    /** Verde-água: distingue do feixe cadastrado, que usa a cor do rádio. */
    private static final Color SIM_BEAM_STROKE = Color.web("#2ee6a8");
    private static final Color SIM_BEAM_FILL = Color.web("#2ee6a8", 0.16);

    /** Cinza tracejado: simulação que não teve relevo para olhar. */
    private static final Color SIM_BEAM_STROKE_FLAT = Color.web("#b0bec5");
    private static final Color SIM_BEAM_FILL_FLAT = Color.web("#b0bec5", 0.10);

    // ------------------------ Area do levantamento ------------------------

    /**
     * Passa a desenhar (ou apaga, com null) a area coberta pelo levantamento.
     *
     * @param fp   contorno em coordenadas de mundo, de
     *             {@link PlyElevation#footprint(int)}
     * @param nome como chamar a area no rotulo e na dica
     */
    public void setSurveyFootprint(PlyElevation.Footprint fp, String nome) {
        this.surveyFootprint = fp;
        this.surveyName = nome == null ? "" : nome;
        if (surveyToggle != null) {
            boolean tem = hasSurveyFootprint();
            surveyToggle.setVisible(tem);
            surveyToggle.setManaged(tem);
            positionControlPanel();
        }
        redrawSurvey();
    }

    /**
     * Contorno da área coberta pelo levantamento, em coordenadas de mundo.
     *
     * Serve ao terreno 3D, que marca nele onde a altitude vem da nuvem e onde
     * vem do relevo global. Sai daqui, já calculado, para as duas telas não
     * desenharem contornos que possam divergir.
     */
    public java.util.List<double[]> surveyRings() {
        return hasSurveyFootprint() ? java.util.List.copyOf(surveyFootprint.rings()) : null;
    }

    public boolean hasSurveyFootprint() {
        return surveyFootprint != null && !surveyFootprint.isEmpty();
    }

    /**
     * Redesenha o contorno com as condicoes atuais.
     *
     * Necessario porque o desenho depende do modo do projeto: carregar o
     * levantamento em modo imagem e so depois ligar um mapa base nao dispara
     * nada por si so, e a area ficaria invisivel sem motivo aparente.
     */
    public void refreshSurvey() { redrawSurvey(); }

    /** Liga/desliga o desenho sem esquecer o contorno. */
    public void setSurveyVisible(boolean v) {
        if (surveyToggle != null) surveyToggle.setSelected(v);
        else redrawSurvey();
    }

    /**
     * Enquadra o mapa na area do levantamento.
     *
     * @return false se nao ha levantamento para enquadrar
     */
    public boolean zoomToSurvey() {
        if (!hasSurveyFootprint()) return false;
        double[] cx = surveyBounds();
        if (cx == null) return false;
        // Uma folga em volta para o contorno nao encostar na borda da tela.
        double folga = Math.max(cx[2], cx[3]) * 0.08;
        fitToWorldRect(cx[0] - folga, cx[1] - folga,
                       cx[2] + 2 * folga, cx[3] + 2 * folga);
        return true;
    }

    /** Caixa do contorno em mundo: {x, y, largura, altura}. */
    private double[] surveyBounds() {
        if (!hasSurveyFootprint()) return null;
        double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE;
        double mny = Double.MAX_VALUE, mxy = -Double.MAX_VALUE;
        for (double[] anel : surveyFootprint.rings()) {
            for (int i = 0; i < anel.length; i += 2) {
                mnx = Math.min(mnx, anel[i]);
                mxx = Math.max(mxx, anel[i]);
                mny = Math.min(mny, anel[i + 1]);
                mxy = Math.max(mxy, anel[i + 1]);
            }
        }
        if (mxx <= mnx || mxy <= mny) return null;
        return new double[] { mnx, mny, mxx - mnx, mxy - mny };
    }

    /**
     * Redesenha o contorno.
     *
     * Um unico Path com regra par-impar, e nao um poligono por anel: assim
     * um anel interno vira buraco de verdade em vez de uma mancha cheia por
     * cima. Buraco no meio do voo e area sem dado, e precisa aparecer como
     * tal.
     */
    private void redrawSurvey() {
        surveyLayer.getChildren().clear();
        surveyOutline = null;
        surveyLabel = null;
        surveyLabelScale = null;

        Project p = project.get();
        boolean ligado = surveyToggle == null || surveyToggle.isSelected();
        // Fora do modo mapa, x/y dos pontos sao pixels da imagem: o contorno
        // esta em Mercator e cairia em qualquer lugar menos no lugar certo.
        if (!ligado || !hasSurveyFootprint() || p == null || !p.isMapMode()) return;

        javafx.scene.shape.Path path = new javafx.scene.shape.Path();
        path.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
        for (double[] anel : surveyFootprint.rings()) {
            if (anel.length < 6) continue;
            path.getElements().add(new javafx.scene.shape.MoveTo(anel[0], anel[1]));
            for (int i = 2; i < anel.length; i += 2) {
                path.getElements().add(new javafx.scene.shape.LineTo(anel[i], anel[i + 1]));
            }
            path.getElements().add(new javafx.scene.shape.ClosePath());
        }
        if (path.getElements().isEmpty()) return;

        path.setFill(SURVEY_FILL);
        path.setStroke(SURVEY_STROKE);
        path.setMouseTransparent(true);
        surveyOutline = path;
        surveyLayer.getChildren().add(path);

        double[] cx = surveyBounds();
        if (cx != null) {
            Text t = new Text(cx[0] + cx[2] / 2, cx[1] - 4, rotuloLevantamento());
            t.setFill(SURVEY_STROKE);
            t.setFont(Font.font("System", FontWeight.BOLD, 11));
            t.setTextOrigin(javafx.geometry.VPos.BOTTOM);
            t.setMouseTransparent(true);
            t.setEffect(new DropShadow(3, Color.BLACK));
            // Centraliza o rotulo sobre a area sem depender do layout: o Text
            // esta dentro de um Group escalado, entao a largura so e conhecida
            // agora.
            t.applyCss();
            t.setX(cx[0] + cx[2] / 2 - t.getLayoutBounds().getWidth() / 2);
            surveyLabelScale = new Scale(1, 1, t.getX(), t.getY());
            t.getTransforms().add(surveyLabelScale);
            surveyLabel = t;
            surveyLayer.getChildren().add(t);
        }
        updateSurveyScale();
    }

    private String rotuloLevantamento() {
        double km2 = surveyFootprint.areaM2() / 1e6;
        String area = km2 >= 1 ? String.format("%.2f km\u00b2", km2)
                               : String.format("%.0f mil m\u00b2", surveyFootprint.areaM2() / 1000);
        return (surveyName.isBlank() ? "Levantamento" : surveyName) + "  \u2014  " + area;
    }

    /**
     * Mantem a espessura da linha e o rotulo com tamanho de tela constante.
     *
     * O worldLayer inteiro e escalado pelo zoom, entao uma espessura fixa em
     * unidades de mundo engorda ate virar mancha quando o usuario se aproxima.
     */
    private void updateSurveyScale() {
        double inv = scale <= 0 ? 1.0 : 1.0 / scale;
        if (surveyOutline != null) {
            surveyOutline.setStrokeWidth(2.0 * inv);
            surveyOutline.getStrokeDashArray().setAll(9.0 * inv, 6.0 * inv);
        }
        if (surveyLabelScale != null) {
            surveyLabelScale.setX(inv);
            surveyLabelScale.setY(inv);
            surveyLabelScale.setPivotX(surveyLabel.getX());
            surveyLabelScale.setPivotY(surveyLabel.getY());
        }
    }

    /** Violeta: nao colide com os feixes (azul) nem com os enlaces (verde a vermelho). */
    private static final Color SURVEY_STROKE = Color.web("#b388ff");
    private static final Color SURVEY_FILL = Color.web("#b388ff", 0.13);

    /** Define de onde vem a altitude mostrada na leitura. Null desliga. */
    public void setElevationSource(ElevationSource src) {
        this.elevationSource = src;
        readoutLabel.setVisible(false);
    }

    public boolean hasElevationSource() { return elevationSource != null; }

    private Label buildReadout() {
        readoutLabel.setVisible(false);
        readoutLabel.setMouseTransparent(true);
        readoutLabel.setStyle("-fx-background-color: rgba(30,30,30,0.80);"
                + "-fx-text-fill: #f0f0f0; -fx-font-size: 11; -fx-padding: 3 8 3 8;"
                + "-fx-background-radius: 3;"
                + "-fx-font-family: 'Consolas', 'Menlo', 'Monospaced';");
        return readoutLabel;
    }

    private void updateReadout(javafx.scene.input.MouseEvent e) {
        Point2D w = worldLayer.sceneToLocal(e.getSceneX(), e.getSceneY());

        String pos;
        if (isMapMode()) {
            pos = String.format(java.util.Locale.US, "%.6f, %.6f",
                    Mercator.latOfWorldY(w.getY()), Mercator.lonOfWorldX(w.getX()));
        } else {
            pos = String.format("x %.0f   y %.0f", w.getX(), w.getY());
        }

        // Fora de modo mapa a altitude não faz sentido: sem georreferência não
        // há como saber a que lugar do planeta o pixel corresponde.
        String alt = "alt —";
        if (elevationSource != null && isMapMode()) {
            Double h = elevationSource.elevationAt(w.getX(), w.getY());
            if (h != null) alt = String.format("alt %.0f m", h);
        }

        readoutLabel.setText(pos + "   " + alt);
        readoutLabel.setVisible(true);
        positionReadout();
    }

    private void positionReadout() {
        readoutLabel.applyCss();
        readoutLabel.autosize();
        double h = readoutLabel.getHeight() > 0
                ? readoutLabel.getHeight() : readoutLabel.prefHeight(-1);
        double wdt = readoutLabel.getWidth() > 0
                ? readoutLabel.getWidth() : readoutLabel.prefWidth(-1);
        // Canto inferior direito, acima da barra de creditos do provedor.
        readoutLabel.setLayoutX(Math.max(6, getWidth() - wdt - 6));
        readoutLabel.setLayoutY(Math.max(0, getHeight() - h - 6));
    }

    private Label buildAttribution() {
        attributionLabel.setVisible(false);
        attributionLabel.setMouseTransparent(true);
        attributionLabel.setStyle("-fx-background-color: rgba(255,255,255,0.75);"
                + "-fx-text-fill: #222; -fx-font-size: 10; -fx-padding: 2 6 2 6;"
                + "-fx-background-radius: 3;");
        return attributionLabel;
    }

    private void positionAttribution() {
        attributionLabel.applyCss();
        attributionLabel.autosize();
        double hgt = attributionLabel.getHeight() > 0
                ? attributionLabel.getHeight() : attributionLabel.prefHeight(-1);
        attributionLabel.setLayoutX(6);
        attributionLabel.setLayoutY(Math.max(0, getHeight() - hgt - 6));
    }

    /** Enquadra um retângulo de mundo na viewport, com uma folga nas bordas. */
    public void fitToWorldRect(double x, double y, double w, double h) {
        double vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        double target = Math.min(vw / w, vh / h) * 0.9;
        scale = Math.max(minScale(), Math.min(maxScale(), target));
        scaleTx.setX(scale);
        scaleTx.setY(scale);
        worldLayer.setTranslateX(vw / 2 - (x + w / 2) * scale);
        worldLayer.setTranslateY(vh / 2 - (y + h / 2) * scale);
        updateZoomCompensations();
        updateTiles();
        buildOverlayHandles();
    }

    /** Largura visível da viewport, em unidades de mundo. */
    public double viewWorldWidth() {
        return scale <= 0 ? 0 : getWidth() / scale;
    }

    /** Centro da viewport atual, em coordenadas do mundo (pixel da imagem). */
    public Point2D viewCenterWorld() {
        return new Point2D(
                (getWidth() / 2.0 - worldLayer.getTranslateX()) / scale,
                (getHeight() / 2.0 - worldLayer.getTranslateY()) / scale);
    }

    /**
     * Entra no modo "posicionar ponto": cursor crosshair, e o proximo clique
     * com o botao esquerdo dispara o callback com as coordenadas (do mundo)
     * onde o usuario clicou. ESC ou cancelPlacePointMode() saem do modo.
     */
    public void enterPlacePointMode(Consumer<Point2D> onPlace) {
        pendingPlacement = onPlace;
        setCursor(Cursor.CROSSHAIR);
    }

    public void cancelPlacePointMode() {
        pendingPlacement = null;
        setCursor(Cursor.DEFAULT);
    }

    public boolean isPlacingPoint() { return pendingPlacement != null; }

    public void setFullScreenAction(Runnable r) { this.fullScreenAction = r; }

    public void setPointContextMenuFactory(Function<NetworkPoint, ContextMenu> f) {
        this.pointContextMenuFactory = f;
    }

    public void setMapContextMenuFactory(Function<Point2D, ContextMenu> f) {
        this.mapContextMenuFactory = f;
    }

    /** Centraliza a viewport numa coordenada de mundo, sem mexer no zoom. */
    public void centerOnWorld(double wx, double wy) {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        worldLayer.setTranslateX(w / 2 - wx * scale);
        worldLayer.setTranslateY(h / 2 - wy * scale);
        updateTiles();
    }

    /**
     * Leva a viewport até uma coordenada, garantindo um zoom em que dê para
     * ver o lugar.
     *
     * Só AUMENTA o zoom quando está muito afastado; se o usuário já está de
     * perto, o nível dele é preservado — "localizar" não deveria desfazer o
     * enquadramento de quem já estava trabalhando ali.
     */
    public void locate(double wx, double wy) {
        if (isMapMode()) {
            int z = Math.max(currentZoom(), MIN_LOCATE_ZOOM);
            centerOn(Mercator.latOfWorldY(wy), Mercator.lonOfWorldX(wx), z);
        } else {
            centerOnWorld(wx, wy);
        }
    }

    /** Zoom de quarteirão: perto o bastante para enxergar o ponto no contexto. */
    private static final int MIN_LOCATE_ZOOM = 16;

    /** Zoom mantendo fixa uma coordenada de mundo (a que o usuário clicou). */
    public void zoomAtWorld(double wx, double wy, double factor) {
        zoomAt(factor,
               wx * scale + worldLayer.getTranslateX(),
               wy * scale + worldLayer.getTranslateY());
    }

    /** O que fazer quando o usuário clica com o botão esquerdo num feixe. */
    public void setLinkClickAction(Consumer<Link> a) { this.linkClickAction = a; }

    public void setOnPointMoved(Consumer<NetworkPoint> a) { this.onPointMoved = a; }

    public void setBeamClickAction(BiConsumer<NetworkPoint, Radio> a) {
        this.beamClickAction = a;
    }

    /** O que fazer ao clicar no mapa vazio — tipicamente, limpar a seleção. */
    public void setBackgroundClickAction(Runnable a) {
        this.backgroundClickAction = a;
    }

    public void setBeamContextMenuFactory(BiFunction<NetworkPoint, Radio, ContextMenu> f) {
        this.beamContextMenuFactory = f;
    }

    private void toggleFullScreen() {
        if (fullScreenAction != null) {
            fullScreenAction.run();
            return;
        }
        // fallback caso ninguem tenha registrado: alterna fullscreen do Stage
        if (getScene() == null || !(getScene().getWindow() instanceof Stage stage)) return;
        stage.setFullScreen(!stage.isFullScreen());
    }

    // ------------------------ Painel de controles flutuante ------------------------

    private VBox buildControlPanel() {
        controlPanel.setAlignment(Pos.CENTER);
        controlPanel.setPadding(new Insets(6));
        controlPanel.setStyle("-fx-background-color: rgba(40,40,40,0.85);"
                + "-fx-background-radius: 6; -fx-border-color: #555;"
                + "-fx-border-radius: 6;");
        Button center = ctrlButton("Centralizar", "Centralizar (0)", e -> fitToView());
        Button zin   = ctrlButton("+", "Zoom in (+)",  e -> zoomCentered(1.25));
        Button zout  = ctrlButton("−", "Zoom out (-)", e -> zoomCentered(1 / 1.25));
        Button full  = ctrlButton("Tela cheia", "Tela cheia (F11)", e -> toggleFullScreen());
        beamsToggle = new ToggleButton("Feixes");
        beamsToggle.setTooltip(new Tooltip("Mostrar/ocultar feixes configurados nas antenas"));
        beamsToggle.setMinWidth(80);
        beamsToggle.setFocusTraversable(false);
        beamsToggle.setSelected(Settings.beamsVisible());
        beamsToggle.selectedProperty().addListener((o, a, b) -> {
            Settings.setBeamsVisible(b);
            redrawBeams();
        });
        // Opacidade da imagem sobre o mapa. Fica aqui, e nao num dialogo,
        // porque encaixar imagem em mapa e trabalho de olho: precisa ver o
        // efeito enquanto arrasta o controle.
        opacityLabel = new Label("Opacidade");
        opacityLabel.setStyle("-fx-text-fill: #ddd; -fx-font-size: 10;");
        opacitySlider = new javafx.scene.control.Slider(0, 1, 0.7);
        opacitySlider.setPrefWidth(80);
        opacitySlider.setMaxWidth(80);
        opacitySlider.setFocusTraversable(false);
        opacitySlider.setTooltip(new Tooltip("Transparencia da imagem sobre o mapa base"));
        opacitySlider.valueProperty().addListener((o, a, b) -> {
            if (overlay == null) return;
            overlay.setOpacity(b.doubleValue());
            ImageView v = viewAtiva();
            if (v != null) v.setOpacity(overlay.getOpacity());
            if (onOverlayChanged != null) onOverlayChanged.run();
        });
        setOpacityControlVisible(false);

        surveyToggle = new ToggleButton("\u00c1rea do voo");
        surveyToggle.setTooltip(new Tooltip(
                "Mostrar/ocultar o contorno da area coberta pelo levantamento .PLY"));
        surveyToggle.setMinWidth(80);
        surveyToggle.setFocusTraversable(false);
        surveyToggle.setSelected(Settings.surveyVisible());
        surveyToggle.selectedProperty().addListener((o, a, b) -> {
            Settings.setSurveyVisible(b);
            redrawSurvey();
        });
        // Sem levantamento carregado o botao nao teria o que ligar.
        surveyToggle.setVisible(false);
        surveyToggle.setManaged(false);

        controlPanel.getChildren().addAll(zin, zout, center, beamsToggle, surveyToggle, full,
                                          opacityLabel, opacitySlider);
        controlPanel.setMouseTransparent(false);
        return controlPanel;
    }

    /** Some com o controle de opacidade quando ele nao teria o que controlar. */
    private void setOpacityControlVisible(boolean on) {
        if (opacityLabel == null) return;
        opacityLabel.setVisible(on);
        opacityLabel.setManaged(on);
        opacitySlider.setVisible(on);
        opacitySlider.setManaged(on);
    }

    private Button ctrlButton(String text, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> h) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setMinWidth(80);
        b.setOnAction(h);
        b.setFocusTraversable(false);
        return b;
    }

    private void positionControlPanel() {
        // Pane nao roda layout pass nos filhos: forca o painel a se medir.
        controlPanel.applyCss();
        controlPanel.autosize();

        double cpW = controlPanel.getWidth();
        double cpH = controlPanel.getHeight();
        if (cpW <= 0) cpW = controlPanel.prefWidth(-1);
        if (cpH <= 0) cpH = controlPanel.prefHeight(-1);

        double margin = 12;
        double x = getWidth() - cpW - margin;
        double y = getHeight() - cpH - margin;
        controlPanel.setLayoutX(Math.max(margin, x));
        controlPanel.setLayoutY(Math.max(margin, y));
    }

    // ------------------------ Desenho ------------------------

    /**
     * Aplica halo escuro (DropShadow preto) atras do texto para garantir
     * legibilidade sobre fundos claros e escuros, SEM usar stroke (que borra
     * a fonte em tamanhos pequenos).
     */
    private static void styleMapText(Text t, javafx.scene.paint.Paint fill) {
        t.setFill(fill);
        t.setStroke(null);
        DropShadow halo = new DropShadow();
        halo.setColor(Color.BLACK);
        halo.setRadius(3);
        halo.setSpread(0.85);
        halo.setOffsetX(0);
        halo.setOffsetY(0);
        t.setEffect(halo);
    }

    /**
     * Adiciona uma Scale inversa ao Text para que ele mantenha tamanho visual
     * constante, com pivot na sua propria ancora (textX, textY).
     */
    private void compensateText(Text t) {
        double inv = scale == 0 ? 1.0 : 1.0 / scale;
        Scale s = new Scale(inv, inv, t.getX(), t.getY());
        t.getTransforms().add(s);
        textCompensators.add(s);
    }

    /** Tudo que precisa reagir ao zoom para manter o tamanho visual. */
    private void updateZoomCompensations() {
        updateTextCompensators();
        updateSurveyScale();
        updateHintCompensators();
        sizeHints();
    }

    private void updateTextCompensators() {
        double inv = scale == 0 ? 1.0 : 1.0 / scale;
        for (Scale s : textCompensators) {
            s.setX(inv);
            s.setY(inv);
        }
        updateNodeSizes();
    }

    /**
     * Mantém os pontos do mesmo tamanho NA TELA em qualquer zoom.
     *
     * O raio vive em unidades de mundo, que encolhem junto com o zoom; sem
     * esta compensação, afastar o mapa reduzia o ponto a menos de um pixel e
     * era impossível acertá-lo com o mouse para arrastar.
     */
    private void updateNodeSizes() {
        for (var e : nodeCircles.entrySet()) {
            sizeNode(e.getValue(), multiSelection.contains(e.getKey()));
        }
    }

    /** Reposiciona o círculo e o rótulo de um ponto, sem reconstruir a camada. */
    private void moveNodeShape(NetworkPoint np) {
        Circle c = nodeCircles.get(np);
        if (c != null) {
            c.setCenterX(np.getX());
            c.setCenterY(np.getY());
        }
        Text l = nodeLabels.get(np);
        if (l != null) positionLabel(l, np.getX(), np.getY());
    }

    private void sizeNode(Circle c, boolean selecionado) {
        double inv = scale <= 0 ? 1.0 : 1.0 / scale;
        c.setRadius(NODE_RADIUS_PX * inv);
        c.setStrokeWidth((selecionado ? 4 : 2) * inv);
    }

    /** Reconstroi tudo (pontos + links + feixes). Use ao mudar a estrutura do projeto. */
    public void redraw() {
        nodesLayer.getChildren().clear();
        textCompensators.clear();
        nodeCircles.clear();
        nodeLabels.clear();
        if (project.get() != null) {
            for (NetworkPoint np : project.get().getPoints()) {
                nodesLayer.getChildren().add(createNodeShape(np));
            }
        }
        redrawLinks();
        redrawBeams();
    }

    /**
     * Reconstroi a camada de feixes. Sai mais barato que redraw() inteiro
     * quando só os feixes mudam (toggle on/off). Também é chamado quando o
     * usuário arrasta um ponto, porque a origem do feixe acompanha o ponto.
     */
    public void redrawBeams() {
        beamsLayer.getChildren().clear();
        Project p = project.get();
        boolean showAll = beamsToggle != null && beamsToggle.isSelected();
        boolean hasPreview = previewBeamRadio != null && previewBeamPoint != null
                && previewBeamRadio.hasBeam();
        if (!showAll && !hasPreview) return;

        if (p != null && showAll) {
            for (NetworkPoint np : p.getPoints()) {
                for (Radio r : np.getRadios()) {
                    if (r == previewBeamRadio) continue; // o preview cuida desse
                    if (!r.isBeamVisible() || !r.hasBeam()) continue;
                    beamsLayer.getChildren().add(createBeamShape(np, r));
                }
            }
        }
        if (hasPreview) {
            beamsLayer.getChildren().add(createBeamShape(previewBeamPoint, previewBeamRadio));
        }
    }

    /** Inicia preview do feixe deste rádio no ponto dado, ignorando o toggle global. */
    public void beginBeamPreview(Radio r, NetworkPoint at) {
        this.previewBeamRadio = r;
        this.previewBeamPoint = at;
        redrawBeams();
    }

    /** Encerra o preview e volta ao estado normal (respeitando o toggle). */
    public void endBeamPreview() {
        this.previewBeamRadio = null;
        this.previewBeamPoint = null;
        redrawBeams();
    }

    /**
     * Cria a forma do feixe (setor ou círculo se 360°). Conversão de azimute:
     * JavaFX Arc mede ângulos com 0° em 3 horas e CCW visualmente — i.e.,
     * 90° = topo (12h), 180° = esquerda, 270° = base. Compass tem 0° no
     * topo e cresce em sentido horário, então o mapeamento é:
     *   javafxDeg = 90 − compassDeg.
     */
    private Shape createBeamShape(NetworkPoint np, Radio r) {
        double radius = groundMetersToWorld(r.getBeamRangeM(), np.getY());
        Color base = effectiveBeamColor(r);
        double op = r.getBeamOpacity();
        Shape shape;
        if (r.getBeamWidthDeg() >= 360) {
            shape = new Circle(np.getX(), np.getY(), radius);
        } else {
            double centerJavafx = 90 - r.getBeamAzimuthDeg();
            double startAngle = centerJavafx - r.getBeamWidthDeg() / 2.0;
            Arc arc = new Arc(np.getX(), np.getY(), radius, radius,
                              startAngle, r.getBeamWidthDeg());
            arc.setType(ArcType.ROUND);
            shape = arc;
        }
        Color fill = base.deriveColor(0, 1, 1, op);
        // hover ~60% mais opaco; stroke proporcionalmente mais forte que o fill.
        Color fillHover = base.deriveColor(0, 1, 1, Math.min(1.0, op * 1.6));
        Color stroke = base.deriveColor(0, 1, 1, Math.min(1.0, op * 3.5 + 0.1));
        shape.setFill(fill);
        shape.setStroke(stroke);
        shape.setStrokeWidth(1.5);
        shape.setCursor(Cursor.HAND);
        shape.setOnMouseEntered(e -> {
            shape.setFill(fillHover);
            shape.setStrokeWidth(2.5);
        });
        shape.setOnMouseExited(e -> {
            shape.setFill(fill);
            shape.setStrokeWidth(1.5);
        });

        Tooltip tip = new Tooltip(buildBeamTooltip(np, r));
        tip.setShowDelay(Duration.millis(150));
        tip.setHideDelay(Duration.millis(100));
        tip.setStyle("-fx-font-family: 'Consolas','Menlo','Monospaced'; -fx-font-size: 12;");
        Tooltip.install(shape, tip);

        // Clique esquerdo no feixe seleciona o rádio dono dele. O feixe NÃO
        // bloqueia o pan (é uma área grande e translúcida), então é preciso
        // distinguir clique de arrasto: se o cursor andou, foi pan do mapa.
        shape.setOnMouseClicked(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (panDistance > PAN_CLICK_SLOP) return;
            if (beamClickAction != null) {
                beamClickAction.accept(np, r);
                ev.consume();
            }
        });

        shape.setOnContextMenuRequested(ev -> {
            if (beamContextMenuFactory == null) return;
            ContextMenu menu = beamContextMenuFactory.apply(np, r);
            Menus.mostrar(menu, shape, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
        return shape;
    }

    /** Usa beamColor do rádio se definido; senão a cor padrão do status. */
    private static Color effectiveBeamColor(Radio r) {
        String hex = r.getBeamColor();
        if (hex != null && !hex.isBlank()) {
            try { return Color.web(hex); } catch (IllegalArgumentException ignored) {}
        }
        return statusColor(r.getStatus());
    }

    private static Color statusColor(RadioStatus s) {
        return switch (s) {
            case UP   -> Color.LIMEGREEN;
            case DOWN -> Color.CRIMSON;
            default   -> Color.DEEPSKYBLUE;
        };
    }

    private static String buildBeamTooltip(NetworkPoint np, Radio r) {
        String name = (r.getName() == null || r.getName().isBlank())
                ? (r.getHost() == null || r.getHost().isBlank() ? "(sem nome)" : r.getHost())
                : r.getName();
        String status = switch (r.getStatus()) {
            case UP   -> "ONLINE";
            case DOWN -> r.getLastError().isBlank() ? "OFFLINE" : "OFFLINE: " + r.getLastError();
            default   -> "desconhecido";
        };
        return String.format(
                "%s  (%s)%nHost: %s%nPonto: %s%nAbertura: %s°   Azimute: %s°   Alcance: %s%nStatus: %s",
                name, r.getVendor(), r.getHost(), np.getName(),
                deg(r.getBeamWidthDeg()), deg(r.getBeamAzimuthDeg()),
                formatRange(r.getBeamRangeM()), status);
    }

    private void redrawLinks() {
        linksLayer.getChildren().clear();

        Project p = project.get();
        if (p == null) return;

        int threshold = Settings.signalThresholdDbm();
        for (Link link : p.getLinks()) {
            NetworkPoint a = p.findPointOfRadio(link.getRadioAId()).orElse(null);
            NetworkPoint b = p.findPointOfRadio(link.getRadioBId()).orElse(null);
            Radio ra = p.findRadioById(link.getRadioAId()).orElse(null);
            Radio rb = p.findRadioById(link.getRadioBId()).orElse(null);
            if (a == null || b == null || ra == null || rb == null) continue;

            Line line = new Line(a.getX(), a.getY(), b.getX(), b.getY());
            line.setStrokeWidth(2.5);
            line.setStrokeLineCap(StrokeLineCap.ROUND);

            if (link.isManual()) {
                // Informado a mao: a ligacao existe, so nao foi medida. Traco
                // longo para nao virar o mesmo desenho do planejado, que e o
                // que AINDA nao existe.
                line.setStroke(MANUAL_LINK);
                line.getStrokeDashArray().setAll(12d, 4d);
            } else if (link.isPlanned()) {
                // Neutro e tracejado curto: precisa ler como "ainda nao existe",
                // sem entrar na escala de cores do sinal — que aqui nao tem
                // sinal nenhum para representar.
                line.setStroke(PLANNED_LINK);
                line.getStrokeDashArray().setAll(4d, 5d);
            } else {
                boolean fullyDown = ra.getStatus() == RadioStatus.DOWN
                                 && rb.getStatus() == RadioStatus.DOWN;
                line.setStroke(fullyDown ? Color.CRIMSON
                        : signalColor(link.getDisplaySignalDbm(), threshold));
                if (link.isStale()) {
                    line.getStrokeDashArray().setAll(8d, 6d);
                }
            }
            line.setCursor(Cursor.HAND);
            line.setOnMouseClicked(ev -> {
                if (ev.getButton() != MouseButton.PRIMARY) return;
                if (linkClickAction != null) linkClickAction.accept(link);
                ev.consume();
            });

            // hover: engrossa a linha para destacar
            line.setOnMouseEntered(e -> line.setStrokeWidth(5.0));
            line.setOnMouseExited(e -> line.setStrokeWidth(2.5));

            Tooltip tip = new Tooltip(buildLinkTooltip(p, link, ra, rb, a, b));
            tip.setShowDelay(Duration.millis(150));
            tip.setHideDelay(Duration.millis(100));
            tip.setStyle("-fx-font-family: 'Consolas','Menlo','Monospaced'; -fx-font-size: 12;");
            Tooltip.install(line, tip);

            Text label = new Text(
                    (a.getX() + b.getX()) / 2,
                    (a.getY() + b.getY()) / 2,
                    buildLinkLabel(link, linkDistanceM(a, b)));
            styleMapText(label, link.isPlanned() ? PLANNED_LINK
                    : link.isManual() ? MANUAL_LINK
                    : (link.isStale() ? Color.LIGHTSALMON : Color.WHITE));
            label.setFont(Font.font(11));
            label.setMouseTransparent(true);
            compensateText(label);

            linksLayer.getChildren().addAll(line, label);
        }
    }

    /** Cinza claro: enlace planejado nao tem sinal para colorir. */
    private static final Color PLANNED_LINK = Color.web("#d0d0d0");

    /** Ciano palido: existe de verdade, mas ninguem mediu. */
    private static final Color MANUAL_LINK = Color.web("#80deea");

    /** Texto curto exibido sobre a linha do enlace. */
    /**
     * Distância de chão entre as duas pontas de um enlace.
     *
     * Em modo mapa é distância real: metro de Mercator não é metro no chão,
     * e a diferença chega a 16% no sul do Brasil. Em modo imagem, é a escala
     * que o projeto declara para os pixels.
     */
    private double linkDistanceM(NetworkPoint a, NetworkPoint b) {
        if (isMapMode()) {
            return Mercator.groundMeters(a.getX(), a.getY(), b.getX(), b.getY());
        }
        Project p = project.get();
        double mpp = p == null ? 1.0 : p.getMetersPerPixel();
        return Math.hypot(b.getX() - a.getX(), b.getY() - a.getY()) * (mpp <= 0 ? 1 : mpp);
    }

    /**
     * @param distM distância entre as pontas. Vem primeiro no rótulo porque é
     *              a única informação que todo enlace tem — planejado, caído
     *              ou medido —, e é a que se procura primeiro ao olhar o mapa.
     */
    private static String buildLinkLabel(Link link, double distM) {
        String dist = distM > 0 ? formatRange(distM) : "";
        String sep = dist.isEmpty() ? "" : "  ·  ";

        if (link.isPlanned()) {
            return dist + sep + "planejado";
        }
        if (link.isManual()) {
            // Se o AP acabou reportando o sinal, mostra: e informacao real
            // sobre um enlace que o usuario teve que declarar.
            return dist + sep + (link.getDisplaySignalDbm() != 0
                    ? link.getDisplaySignalDbm() + " dBm (informado)"
                    : "informado à mão");
        }
        if (link.isStale()) {
            return dist + sep + "sem sinal (era " + link.getDisplaySignalDbm() + " dBm)";
        }
        StringBuilder sb = new StringBuilder();
        if (!dist.isEmpty()) sb.append(dist).append(sep);
        int a = link.getSignalDbm();
        int b = link.getSignalDbmReverse();
        if (a != 0 && b != 0)      sb.append(a).append(" / ").append(b).append(" dBm");
        else if (a != 0)            sb.append(a).append(" dBm");
        else if (b != 0)            sb.append(b).append(" dBm");
        else                        sb.append("— dBm");
        if (link.getTxBps() > 0 || link.getRxBps() > 0) {
            // throughput real (delta de bytes)
            sb.append("  ·  ↑ ").append(formatBps(link.getTxBps()))
              .append("  ↓ ").append(formatBps(link.getRxBps()));
        } else if (link.getTxMbps() > 0 || link.getRxMbps() > 0) {
            // sem amostra de throughput ainda - mostra o PHY rate
            sb.append("  ·  PHY ").append(link.getTxMbps())
              .append("/").append(link.getRxMbps()).append(" Mbps");
        }
        return sb.toString();
    }

    private static String formatSignal(int dbm) {
        return dbm == 0 ? "— (sem dado)" : dbm + " dBm";
    }

    /** Formata bits/s em K/M/G com 1 casa. */
    private static String formatBps(long bps) {
        if (bps >= 1_000_000_000L) return String.format("%.1f Gbps", bps / 1_000_000_000.0);
        if (bps >= 1_000_000L)     return String.format("%.1f Mbps", bps / 1_000_000.0);
        if (bps >= 1_000L)         return String.format("%.0f kbps", bps / 1_000.0);
        return bps + " bps";
    }

    private static String buildLinkTooltip(Project p, Link link, Radio ra, Radio rb,
                                            NetworkPoint pa, NetworkPoint pb) {
        StringBuilder sb = new StringBuilder();
        sb.append(pa.getName()).append("   ↔   ").append(pb.getName()).append('\n');
        sb.append(String.format("%-22s  %s @ %s%n",
                ra.getName().isBlank() ? ra.getHost() : ra.getName(),
                ra.getVendor(), ra.getHost()));
        sb.append(String.format("%-22s  %s @ %s%n",
                rb.getName().isBlank() ? rb.getHost() : rb.getName(),
                rb.getVendor(), rb.getHost()));
        sb.append('\n');
        if (link.isStale()) {
            sb.append("STATUS: STALE (sem confirmação na última sondagem)\n");
            sb.append(String.format("Último sinal conhecido: %d dBm%n", link.getDisplaySignalDbm()));
        } else {
            String aShort = ra.getName().isBlank() ? ra.getHost() : ra.getName();
            String bShort = rb.getName().isBlank() ? rb.getHost() : rb.getName();
            sb.append(String.format("Sinal visto por %s: %s%n",
                    aShort, formatSignal(link.getSignalDbm())));
            sb.append(String.format("Sinal visto por %s: %s%n",
                    bShort, formatSignal(link.getSignalDbmReverse())));
            if (link.getTxMbps() > 0 || link.getRxMbps() > 0) {
                sb.append(String.format("PHY rate:   TX %d / RX %d Mbps%n",
                        link.getTxMbps(), link.getRxMbps()));
            }
            if (link.getTxBps() > 0 || link.getRxBps() > 0) {
                sb.append(String.format("Throughput: ↑ %s  ↓ %s%n",
                        formatBps(link.getTxBps()), formatBps(link.getRxBps())));
            } else {
                sb.append("Throughput: — (aguardando 2ª amostra)\n");
            }
        }
        if (link.getLastSeen() != null) {
            sb.append("Visto: ").append(link.getLastSeen().toString());
        }
        return sb.toString();
    }

    private Group createNodeShape(NetworkPoint np) {
        Color fill = nodeColor(np);
        Circle c = new Circle(np.getX(), np.getY(), NODE_RADIUS_PX, fill);
        boolean sel = multiSelection.contains(np);
        // Selecionado ganha anel azul: precisa saltar aos olhos numa tela com
        // dezenas de pontos, antes de arrastar o lote errado.
        c.setStroke(sel ? Color.DEEPSKYBLUE : Color.WHITE);
        c.setCursor(Cursor.HAND);
        nodeCircles.put(np, c);
        sizeNode(c, sel);

        Text label = new Text(np.getName() == null ? "" : np.getName());
        label.setFont(Font.font("System", FontWeight.BOLD, 12));
        styleMapText(label, Color.WHITE);
        label.setMouseTransparent(true);
        positionLabel(label, np.getX(), np.getY());
        compensateText(label);
        nodeLabels.put(np, label);

        Group g = new Group(c, label);

        c.setOnMousePressed(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;

            // Ctrl + clique num ponto: entra ou sai da selecao, sem arrastar.
            if (ev.isControlDown()) {
                toggleSelected(np);
                ev.consume();
                return;
            }

            // Clique simples num ponto FORA da selecao desfaz a selecao: o
            // usuario esta claramente mudando de assunto. Se o ponto faz parte
            // dela, preserva, porque o gesto seguinte e mover o lote.
            if (!multiSelection.contains(np) && !multiSelection.isEmpty()) {
                multiSelection.clear();
                redraw();
                if (onSelectionChanged != null) onSelectionChanged.run();
            }

            selectedPoint.set(np);
            Point2D w = worldLayer.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            dragOffsetX = w.getX() - np.getX();
            dragOffsetY = w.getY() - np.getY();

            // Guarda a posicao de partida de cada selecionado: mover em lote
            // por DELTA acumulado evita que os pontos se amontoem no cursor.
            dragOrigins.clear();
            for (NetworkPoint s : multiSelection) {
                dragOrigins.put(s, new double[]{ s.getX(), s.getY() });
            }
            c.setCursor(Cursor.CLOSED_HAND);
            ev.consume();
        });

        c.setOnContextMenuRequested(ev -> {
            if (pointContextMenuFactory == null) return;
            selectedPoint.set(np);
            ContextMenu menu = pointContextMenuFactory.apply(np);
            Menus.mostrar(menu, c, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });

        c.setOnMouseDragged(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (ev.isControlDown()) return;   // Ctrl e para selecionar, nao mover

            Point2D w = worldLayer.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            double newX = w.getX() - dragOffsetX;
            double newY = w.getY() - dragOffsetY;

            if (multiSelection.size() > 1 && multiSelection.contains(np)) {
                // Lote: aplica o mesmo deslocamento a todos, a partir das
                // posicoes de partida guardadas no press.
                double[] origem = dragOrigins.get(np);
                if (origem != null) {
                    double dx = newX - origem[0], dy = newY - origem[1];
                    for (var entry : dragOrigins.entrySet()) {
                        NetworkPoint alvo = entry.getKey();
                        alvo.setX(entry.getValue()[0] + dx);
                        alvo.setY(entry.getValue()[1] + dy);
                        moveNodeShape(alvo);
                    }
                    // Move os nos existentes em vez de chamar redraw(): a
                    // reconstrucao completa da camada a cada evento de arrasto
                    // engasgava a interface e fazia o arrasto "soltar".
                    redrawLinks();
                    redrawBeams();
                    ev.consume();
                    return;
                }
            }

            np.setX(newX);
            np.setY(newY);
            c.setCenterX(newX);
            c.setCenterY(newY);
            positionLabel(label, newX, newY);
            redrawLinks();
            redrawBeams();
            ev.consume();
        });

        c.setOnMouseReleased(ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            c.setCursor(Cursor.HAND);
            // So no fim do arrasto: avisar a cada evento refaria o perfil
            // dezenas de vezes por segundo, e o perfil le relevo.
            if (onPointMoved != null) onPointMoved.accept(np);
        });

        return g;
    }

    /** Coloca o label um pouco a direita do circulo, alinhado verticalmente. */
    private static void positionLabel(Text label, double cx, double cy) {
        label.setX(cx + 12);
        label.setY(cy + 4);
        // mantem o pivot da Scale (se houver) na ancora atual
        for (var t : label.getTransforms()) {
            if (t instanceof Scale s) {
                s.setPivotX(label.getX());
                s.setPivotY(label.getY());
            }
        }
    }

    /**
     * Cor do ponto baseada no status dos seus radios:
     *  - todos UP / sem radios:  azul (default)
     *  - todos DOWN:             vermelho
     *  - misto:                  amarelo
     *  - apenas UNKNOWN:         cinza
     */
    private static Color nodeColor(NetworkPoint np) {
        int up = 0, down = 0, unknown = 0;
        for (Radio r : np.getRadios()) {
            switch (r.getStatus()) {
                case UP -> up++;
                case DOWN -> down++;
                default -> unknown++;
            }
        }
        int total = up + down + unknown;
        if (total == 0) return Color.DEEPSKYBLUE;
        if (down == total) return Color.CRIMSON;
        if (down > 0) return Color.GOLD;
        if (up == 0) return Color.GRAY; // tudo unknown
        return Color.DEEPSKYBLUE;
    }

    /**
     * Cor do enlace em função do sinal e do limite "bom" configurado pelo
     * usuário (Settings.signalThresholdDbm). O gradiente é relativo:
     *   acima de threshold+10dB → verde
     *   acima de threshold      → amarelo
     *   acima de threshold−10dB → laranja
     *   abaixo                  → vermelho
     */
    private static Color signalColor(int dbm, int threshold) {
        if (dbm == 0) return Color.GRAY;
        if (dbm > threshold + 10) return Color.LIMEGREEN;
        if (dbm > threshold)      return Color.GOLD;
        if (dbm > threshold - 10) return Color.ORANGE;
        return Color.CRIMSON;
    }
}
