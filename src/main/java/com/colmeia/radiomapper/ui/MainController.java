package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.discovery.TopologyBuilder;
import com.colmeia.radiomapper.geo.ElevationChain;
import com.colmeia.radiomapper.geo.GeoTiffReader;
import com.colmeia.radiomapper.geo.TerrainTiles;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.PlyElevation;
import com.colmeia.radiomapper.geo.TileCache;
import com.colmeia.radiomapper.history.RadioHistory;
import com.colmeia.radiomapper.geo.TileSource;
import com.colmeia.radiomapper.io.AutoSaver;
import com.colmeia.radiomapper.io.ImageLoader;
import com.colmeia.radiomapper.notify.NotificationService;
import com.colmeia.radiomapper.io.ProjectIO;
import com.colmeia.radiomapper.model.ImageLayer;
import com.colmeia.radiomapper.model.ImageOverlay;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioStatus;
import com.colmeia.radiomapper.model.Link;
import com.colmeia.radiomapper.ssh.SshSessionPool;
import com.colmeia.radiomapper.util.Log;
import com.colmeia.radiomapper.util.Settings;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class MainController {

    @FXML private BorderPane root;
    @FXML private MapPane mapPane;
    @FXML private ListView<NetworkPoint> pointsList;
    @FXML private ListView<Radio> radiosList;
    @FXML private ListView<Radio> allRadiosList;
    @FXML private ListView<com.colmeia.radiomapper.model.Router> routersList;
    @FXML private ComboBox<String> allRadiosSort;
    @FXML private Label allRadiosSummary;
    @FXML private Label statusLabel;
    @FXML private CheckMenuItem autoSyncMenu;
    /**
     * Ãšnico controle de exibiÃ§Ã£o que sobrou no menu: Ã© um MODO de trabalho
     * transitÃ³rio, nÃ£o uma preferÃªncia. Tipo de mapa, feixes e opÃ§Ãµes da
     * sobreposiÃ§Ã£o vivem em Ferramentas > ConfiguraÃ§Ãµes.
     */
    @FXML private CheckMenuItem imageAdjustItem;
    @FXML private javafx.scene.control.Menu imageMenu;
    @FXML private javafx.scene.control.SeparatorMenuItem imageListStart;
    @FXML private javafx.scene.control.SeparatorMenuItem imageListEnd;

    /**
     * Qual imagem as a\u00e7\u00f5es do menu afetam.
     *
     * Com v\u00e1rias sobrepostas, "reencaixar a imagem" e "remover a imagem"
     * deixam de ter um alvo \u00f3bvio. A ativa \u00e9 a \u00faltima
     * carregada, ou a que o usu\u00e1rio arrastou por \u00faltimo \u2014
     * pegar uma imagem no mapa j\u00e1 diz qual \u00e9.
     */
    private String camadaAtivaId;

    private ImageLayer camadaAtiva() {
        ImageLayer l = project == null ? null : project.imageById(camadaAtivaId);
        if (l != null) return l;
        if (project == null || project.getImages().isEmpty()) return null;
        ImageLayer ultima = project.getImages().get(project.getImages().size() - 1);
        camadaAtivaId = ultima.getId();
        return ultima;
    }

    /** Repinta as camadas e refaz o menu que as lista. */
    private void atualizarCamadas() {
        camadaAtiva();                       // normaliza o id, se ficou orfao
        mapPane.applyLayers(project.getImages(), camadaAtivaId);
        syncImageMenu();
    }
    @FXML private CheckMenuItem sidePanelItem;
    @FXML private javafx.scene.layout.VBox sidePanel;
    @FXML private BeamProfilePane profilePane;

    private boolean fullMap;

    private final ObservableList<NetworkPoint> pointsObs = FXCollections.observableArrayList();
    private final ObservableList<Radio> radiosObs = FXCollections.observableArrayList();
    private final ObservableList<com.colmeia.radiomapper.model.Router> routersObs =
            FXCollections.observableArrayList();
    private final ObservableList<Radio> allRadiosObs = FXCollections.observableArrayList();

    private Project project = new Project();

    // auto-sync
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auto-sync");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> autoTask;

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Cadeia de altitude: PLY, depois GeoTIFF, depois o relevo global do mapa. */
    private final ElevationChain elevation = new ElevationChain();

    /** Grava o projeto sozinho; ver AutoSaver para como a mudanca e detectada. */
    private final AutoSaver autoSaver = new AutoSaver(
            () -> project,
            name -> setStatus("Salvo automaticamente em " + name
                    + " Ã s " + LocalTime.now().format(HHMMSS) + "."));

    @FXML
    public void initialize() {
        // Projeto inicial ja com o mapa base e a area da ultima sessao. Se
        // houver lastProject, restoreSession() troca este por ele mais adiante.
        project = newProjectWithDefaults();
        mapPane.projectProperty().set(project);
        pointsList.setItems(pointsObs);
        radiosList.setItems(radiosObs);
        routersList.setItems(routersObs);
        // Roteador fora do ar precisa saltar da lista pelo mesmo motivo que um
        // radio: e o que se procura quando algo parou de funcionar.
        routersList.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(com.colmeia.radiomapper.model.Router rt, boolean vazio) {
                super.updateItem(rt, vazio);
                if (vazio || rt == null) { setText(null); setStyle(""); return; }
                setText(rt.displayName() + "  \u00b7  " + rt.getHost()
                        + (rt.getInterfaces().isEmpty() ? ""
                           : "  \u00b7  " + rt.getInterfaces().size() + " interfaces"));
                switch (rt.getStatus()) {
                    case DOWN -> setStyle("-fx-text-fill: white; -fx-background-color: #b00020; -fx-font-weight: bold;");
                    case UP   -> setStyle("-fx-text-fill: #2e7d32;");
                    default   -> setStyle("-fx-text-fill: gray;");
                }
            }
        });

        pointsList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            radiosObs.setAll(n == null ? List.of() : n.getRadios());
            routersObs.setAll(n == null ? List.of() : n.getRouters());
            mapPane.selectedPointProperty().set(n);
        });

        mapPane.selectedPointProperty().addListener((obs, o, n) -> {
            if (n != null) pointsList.getSelectionModel().select(n);
        });

        // Duplo clique na lista tambem localiza: e o gesto que se espera numa
        // lista de lugares, e evita ter que mirar no botao.
        pointsList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() != 2) return;
            NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
            if (np != null) locatePoint(np);
        });

        // Clique direito nas duas listas: editar, abrir no navegador, estaÃ§Ãµes.
        installRadioMenu(radiosList);
        installRadioMenu(allRadiosList);

        applySidePanelVisibility();

        // O intervalo de auto-sync e o limite de sinal agora moram na tela de
        // configuracoes (Ferramentas > Configuracoes...). Aqui so refletimos o
        // estado atual no menu.
        autoSyncMenu.setSelected(Settings.autoSync());

        // Feixes: o botao do painel flutuante e a fonte da verdade na tela, e
        // ele mesmo grava em Settings. Aqui so alinhamos com o valor salvo.
        mapPane.beamsVisibleProperty().set(Settings.beamsVisible());

        // Lista global de radios: items + sort + cell colorida
        allRadiosList.setItems(allRadiosObs);
        allRadiosList.setCellFactory(lv -> new AllRadiosCell());
        allRadiosSort.getItems().setAll("Nome", "IP/Host", "Status (offline 1Âº)", "Por ponto");
        allRadiosSort.getSelectionModel().select(0);
        allRadiosSort.valueProperty().addListener((o, a, b) -> refreshAllRadios());

        // duplo clique no item: abre edicao do radio
        allRadiosList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                Radio r = allRadiosList.getSelectionModel().getSelectedItem();
                if (r != null && editRadio(r, null)) {
                    refreshAllRadios();
                    radiosList.refresh();
                    mapPane.redraw();
                }
            }
        });

        // botao "Tela cheia" e F11: esconde toolbar/painel/status, mostrando so o mapa
        mapPane.setFullScreenAction(this::toggleFullMap);

        // botao direito no ponto: menu de contexto com acoes
        mapPane.setPointContextMenuFactory(this::buildPointContextMenu);

        // Arrastar a torre no perfil e' hipotese; aplicar e' que move o ponto
        // de verdade, com o mesmo caminho de refresh de um arrasto no mapa.
        profilePane.setOnRelocate((ponto, novoX, novoY) -> {
            ponto.setX(novoX);
            ponto.setY(novoY);
            refreshAll();
            mapPane.locate(novoX, novoY);
            setStatus("Ponto \"" + ponto.getName() + "\" movido pela an\u00e1lise do perfil.");
            Log.info("Ponto \"%s\" movido pelo perfil para %.6f, %.6f", ponto.getName(),
                    com.colmeia.radiomapper.geo.Mercator.latOfWorldY(novoY),
                    com.colmeia.radiomapper.geo.Mercator.lonOfWorldX(novoX));
        });

        // As marcas que so o perfil sabe calcular, situadas no mapa: para onde
        // a torre iria, e em que ponto do chao o morro entra na frente.
        profilePane.setMapHints(new BeamProfilePane.MapHints() {
            @Override public void torreEm(NetworkPoint ponto, double wx, double wy) {
                if (Double.isNaN(wx) || Double.isNaN(wy)) mapPane.clearGhostTower(ponto);
                else mapPane.setGhostTower(ponto, wx, wy);
            }
            @Override public void obstrucaoEm(double wx, double wy, double metros) {
                if (metros <= 0 || Double.isNaN(wx)) mapPane.clearObstruction();
                else mapPane.setObstruction(wx, wy, metros);
            }
        });

        // Botao "Ver em 3D" do painel de perfil: e' de la que se esta olhando
        // o enlace quando a duvida sobre o relevo aparece.
        profilePane.setOnOpen3D(this::abrirTerreno3D);

        // Clique na linha do enlace abre o perfil dele. O cursor de mao ja
        // prometia isso; faltava a acao.
        mapPane.setLinkClickAction(link -> {
            Radio a = project.findRadioById(link.getRadioAId()).orElse(null);
            Radio b = project.findRadioById(link.getRadioBId()).orElse(null);
            Radio alvo = a != null ? a : b;
            if (alvo == null) {
                setStatus("Este enlace aponta para um r\u00e1dio que n\u00e3o existe mais.");
                return;
            }
            NetworkPoint np = project.findPointOfRadio(alvo.getId()).orElse(null);
            if (np != null) selectRadioFromMap(np, alvo);
        });

        // Arrastar um ponto muda o caminho do enlace: sem isto o perfil
        // continuava mostrando o trajeto antigo ate ser reaberto.
        mapPane.setOnPointMoved(ponto -> {
            Radio sel = selectedRadio();
            if (sel != null) showProfile(sel);
            limparCacheDeAlcance();
            if (mapPane.hasSimulatedBeams()) {
                mapPane.clearSimulatedBeams();
                setStatus("Ponto movido \u2014 o alcance simulado saiu do mapa, "
                        + "simule de novo na posi\u00e7\u00e3o nova.");
            }
        });

        // botao direito sobre o feixe: editar / ocultar
        mapPane.setBeamContextMenuFactory(this::buildBeamContextMenu);

        // botao esquerdo sobre o feixe: seleciona o radio e abre o perfil
        mapPane.setBeamClickAction(this::selectRadioFromMap);

        // clique no mapa vazio: limpa a selecao e fecha o perfil de baixo
        mapPane.setBackgroundClickAction(() -> {
            radiosList.getSelectionModel().clearSelection();
            allRadiosList.getSelectionModel().clearSelection();
            mapPane.clearSelection();
            hideProfile();
        });

        // Selecao multipla de pontos: so avisa na barra de status quantos estao
        // marcados, para o usuario saber o que um arrasto vai mover.
        mapPane.setOnSelectionChanged(() -> {
            int n = mapPane.selectedPoints().size();
            if (n == 0) setStatus("Pronto.");
            else if (n == 1) setStatus("1 ponto selecionado â€” arraste para mover.");
            else setStatus(n + " pontos selecionados â€” arraste qualquer um para mover todos.");
        });

        // botao direito no mapa vazio: criar ponto aqui, navegar, trocar base
        mapPane.setMapContextMenuFactory(this::buildMapContextMenu);

        // Altitude sob o cursor. A cadeia ja nasce com o relevo global como
        // ultimo recurso; PLY e GeoTIFF entram na frente quando existirem.
        TerrainTiles.INSTANCE.setEnabled(Settings.terrainTilesEnabled());
        elevation.setTerrain(TerrainTiles.INSTANCE);
        mapPane.setElevationSource(elevation);

        // Perfil lateral: aparece ao selecionar um radio com feixe, nas duas
        // listas. So faz sentido com feixe definido, entao some sozinho.
        radiosList.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> showProfile(b));
        allRadiosList.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> showProfile(b));

        refreshAll();

        // Restaura o Ãºltimo projeto aberto e o estado do auto-sync.
        // Adiado para depois do initialize() para garantir que setStatus/Alert
        // tenham uma Scene vÃ¡lida (mainWindow() depende disso).
        Platform.runLater(() -> {
            restoreSession();
            autoSaver.start();   // depois de restaurar, para nao gravar por cima
        });
    }

    /**
     * Projeto vazio jÃ¡ com o mapa base e a Ã¡rea que o usuÃ¡rio vinha usando.
     * Um projeto carregado do disco traz os seus prÃ³prios valores e nÃ£o passa
     * por aqui â€” o basemap define o sistema de coordenadas dos pontos, entÃ£o
     * ele pertence ao projeto; isto Ã© sÃ³ o ponto de partida de um projeto novo.
     */
    private Project newProjectWithDefaults() {
        Project p = new Project();
        p.setBasemap(TileSource.parse(Settings.defaultBasemapName(), TileSource.NONE));
        p.setViewLat(Settings.lastViewLat());
        p.setViewLon(Settings.lastViewLon());
        p.setViewZoom(Settings.lastViewZoom());
        return p;
    }

    /** Carrega lastProject (se ainda existe no disco) e reativa auto-sync. */
    private void restoreSession() {
        String last = Settings.lastProject();
        File candidate = (last == null || last.isBlank()) ? null : new File(last);
        // Sem projeto escolhido, cai no workspace do auto-save: e ele que
        // guarda o trabalho de quem nunca clicou em "Salvar projeto".
        if ((candidate == null || !candidate.isFile())
                && java.nio.file.Files.isRegularFile(AutoSaver.WORKSPACE)) {
            candidate = AutoSaver.WORKSPACE.toFile();
        }

        if (Settings.restoreLastProject() && candidate != null) {
            File f = candidate;
            if (f.isFile()) {
                try {
                    project = ProjectIO.load(f);
                    refreshAll();
                    // Continua gravando onde estava: workspace segue workspace,
                    // arquivo escolhido segue o arquivo escolhido.
                    autoSaver.setTarget(f.toPath().equals(AutoSaver.WORKSPACE) ? null : f);
                    int totalRadios = project.getPoints().stream().mapToInt(p -> p.getRadios().size()).sum();
                    setStatus("Projeto restaurado: " + f.getName());
                    Log.info("Projeto restaurado: %s (%d pontos, %d rÃ¡dios, %d links)",
                            f.getAbsolutePath(), project.getPoints().size(), totalRadios,
                            project.getLinks().size());
                    // Silencioso: se a imagem sumiu do disco, isso vai para o
                    // log, mas nao recebe o usuario com um erro na abertura.
                    loadProjectImageAsync(false);
                    loadProjectPlyAsync();
                } catch (Exception ex) {
                    Log.warn("Falha ao restaurar %s: %s", f.getAbsolutePath(), ex.getMessage());
                }
            } else {
                Log.info("Projeto anterior nÃ£o encontrado: %s", last);
            }
        }
        if (Settings.autoSync()) {
            autoSyncMenu.setSelected(true);
            startAuto();
        }
    }

    /**
     * Menu do clique direito no mapa vazio. Recebe a coordenada de mundo
     * clicada, e Ã© por isso que as aÃ§Ãµes sÃ£o "aqui" â€” criar ponto, centralizar
     * e aproximar usam o lugar exato onde o cursor estava, nÃ£o o centro da tela.
     */
    private ContextMenu buildMapContextMenu(Point2D world) {
        ContextMenu menu = new ContextMenu();
        boolean mapMode = project.isMapMode();

        MenuItem novo = new MenuItem("Criar ponto de rede aqui");
        novo.setOnAction(e -> createPointAt(world));

        MenuItem centralizar = new MenuItem("Centralizar aqui");
        centralizar.setOnAction(e -> mapPane.centerOnWorld(world.getX(), world.getY()));

        MenuItem aproximar = new MenuItem("Aproximar aqui");
        aproximar.setOnAction(e -> mapPane.zoomAtWorld(world.getX(), world.getY(), 2.0));

        MenuItem afastar = new MenuItem("Afastar");
        afastar.setOnAction(e -> mapPane.zoomAtWorld(world.getX(), world.getY(), 0.5));

        MenuItem enquadrar = new MenuItem("Enquadrar tudo");
        enquadrar.setOnAction(e -> mapPane.fitToView());

        menu.getItems().addAll(novo, new SeparatorMenuItem(),
                centralizar, aproximar, afastar, enquadrar);

        // Coordenada sÃ³ existe de verdade em modo mapa; em modo imagem seria
        // um par de pixels, que nÃ£o serve para colar em GPS nem em chamado.
        if (mapMode) {
            MenuItem copiar = new MenuItem("Copiar coordenadas");
            copiar.setOnAction(e -> {
                String txt = describePosition(world);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(txt);
                Clipboard.getSystemClipboard().setContent(cc);
                setStatus("Coordenadas copiadas: " + txt);
            });
            MenuItem ir = new MenuItem("Ir para coordenadas...");
            ir.setOnAction(e -> onGoToCoords());
            menu.getItems().addAll(new SeparatorMenuItem(), copiar, ir);
        }

        Menu base = new Menu("Mapa base");
        for (TileSource s : TileSource.values()) {
            RadioMenuItem mi = new RadioMenuItem(s.label());
            mi.setSelected(s == project.getBasemap());
            mi.setOnAction(e -> applyBasemap(s));
            base.getItems().add(mi);
        }

        CheckMenuItem feixes = new CheckMenuItem("Feixes das antenas");
        feixes.setSelected(mapPane.beamsVisibleProperty().get());
        feixes.setOnAction(e -> mapPane.beamsVisibleProperty().set(feixes.isSelected()));

        menu.getItems().addAll(new SeparatorMenuItem(), base, feixes);
        return menu;
    }

    private ContextMenu buildPointContextMenu(NetworkPoint np) {
        ContextMenu menu = new ContextMenu();

        MenuItem details = new MenuItem("Detalhes / rÃ¡dios...");
        details.setOnAction(e -> PointDetailsDialog.show(np,
                mapPane.getScene().getWindow(),
                () -> { radiosObs.setAll(np.getRadios()); mapPane.redraw(); },
                radio -> editRadio(radio, np),
                project));

        MenuItem coords = new MenuItem("Coordenadas...");
        coords.setOnAction(e -> showPointCoords(np));

        // Atalho de um clique, para o caso comum de so precisar mandar a
        // coordenada para alguem.
        MenuItem copiarCoord = new MenuItem("Copiar coordenadas");
        copiarCoord.setOnAction(e -> copyPointCoords(np));

        MenuItem moveTo = new MenuItem("Mover para coordenadas...");
        moveTo.setOnAction(e -> movePointToCoords(np));

        MenuItem rename = new MenuItem("Renomear ponto...");
        rename.setOnAction(e -> {
            TextInputDialog dlg = new TextInputDialog(np.getName());
            dlg.initOwner(mainWindow());
            dlg.setHeaderText("Novo nome");
            dlg.showAndWait().ifPresent(n -> {
                if (n.isBlank()) return;
                String old = np.getName();
                np.setName(n);
                pointsList.refresh();
                mapPane.redraw();
                Log.info("Ponto renomeado: \"%s\" -> \"%s\"", old, n);
            });
        });

        MenuItem addRadio = new MenuItem("Adicionar rÃ¡dio...");
        addRadio.setOnAction(e -> {
            Radio r = new Radio();
            if (editRadio(r, np)) {
                np.getRadios().add(r);
                radiosObs.setAll(np.getRadios());
                refreshAllRadios();
                mapPane.redraw();
                Log.info("RÃ¡dio adicionado: %s @ %s no ponto \"%s\"",
                        r.getVendor(), r.getHost(), np.getName());
            }
        });

        MenuItem openAll = new MenuItem("Abrir todos no navegador");
        openAll.setOnAction(e -> {
            for (Radio r : np.getRadios()) openRadioInBrowser(r);
        });
        openAll.setDisable(np.getRadios().isEmpty());

        // Submenu "Conectados ao rÃ¡dio": para cada rÃ¡dio do ponto, abre um
        // diÃ¡logo com a lista de vizinhos retornada pelo prÃ³prio rÃ¡dio.
        Menu connections = new Menu("Conectados ao rÃ¡dio");
        if (np.getRadios().isEmpty()) {
            MenuItem empty = new MenuItem("(sem rÃ¡dios neste ponto)");
            empty.setDisable(true);
            connections.getItems().add(empty);
        } else {
            for (Radio r : np.getRadios()) {
                String label = (r.getName() == null || r.getName().isBlank())
                        ? (r.getHost() == null || r.getHost().isBlank() ? "(sem nome)" : r.getHost())
                        : r.getName() + "  Â·  " + r.getHost();
                MenuItem mi = new MenuItem(label);
                mi.setOnAction(e -> RadioConnectionsDialog.show(r, project, mainWindow()));
                connections.getItems().add(mi);
            }
        }

        MenuItem remove = new MenuItem("Remover ponto");
        remove.setOnAction(e -> {
            project.getPoints().remove(np);
            refreshAll();
            Log.warn("Ponto removido: %s (%d rÃ¡dios)", np.getName(), np.getRadios().size());
        });

        menu.getItems().addAll(details, new SeparatorMenuItem(),
                coords, copiarCoord, moveTo,
                new SeparatorMenuItem(), rename, addRadio, connections, openAll,
                new SeparatorMenuItem(), remove);
        return menu;
    }

    private void openRadioInBrowser(Radio r) {
        if (r.getHost() == null || r.getHost().isBlank()) return;
        String scheme = (r.getVendor() == com.colmeia.radiomapper.model.RadioVendor.UBIQUITI_AIROS_6
                      || r.getVendor() == com.colmeia.radiomapper.model.RadioVendor.UBIQUITI_AIROS_8) ? "https" : "http";
        String url = scheme + "://" + r.getHost();
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            Log.info("Abrindo no navegador: %s", url);
        } catch (Exception ex) {
            Log.warn("Falha ao abrir %s: %s", url, ex.getMessage());
        }
    }

    private void toggleFullMap() {
        fullMap = !fullMap;
        setChromeVisible(!fullMap);
        Log.info("Modo \"sÃ³ mapa\": %s", fullMap ? "ligado" : "desligado");
    }

    private void setChromeVisible(boolean visible) {
        for (Node n : new Node[]{root.getTop(), root.getRight(), root.getBottom(), root.getLeft()}) {
            if (n == null) continue;
            n.setVisible(visible);
            n.setManaged(visible);
        }
        // Sair do modo "so o mapa" nao pode reabrir o painel lateral de quem
        // o tinha fechado: restaura a escolha do usuario, nao um padrao.
        if (visible) applySidePanelVisibility();
    }

    @FXML private void onToggleSidePanel() {
        Settings.setSidePanelVisible(sidePanelItem.isSelected());
        applySidePanelVisibility();
    }

    private void applySidePanelVisibility() {
        boolean on = Settings.sidePanelVisible();
        if (sidePanelItem != null) sidePanelItem.setSelected(on);
        if (sidePanel == null) return;
        // managed junto com visible: so esconder deixaria o espaco reservado,
        // e o mapa nao ganharia a largura.
        sidePanel.setVisible(on);
        sidePanel.setManaged(on);
    }

    private void refreshAll() {
        pointsObs.setAll(project.getPoints());
        mapPane.projectProperty().set(project);
        // Historico de quedas e por projeto: o do anterior nao vale mais.
        NotificationService.INSTANCE.reset();
        // Projeto carregado/novo pode trazer outro mapa base que o atual.
        mapPane.setBasemap(project.getBasemap());
        atualizarCamadas();
        NetworkPoint sel = pointsList.getSelectionModel().getSelectedItem();
        routersObs.setAll(sel == null ? List.of() : sel.getRouters());
        mapPane.redraw();
        mapPane.refreshSurvey();
        refreshAllRadios();
    }

    private void refreshAllRadios() {
        java.util.List<Radio> all = new java.util.ArrayList<>();
        for (NetworkPoint p : project.getPoints()) all.addAll(p.getRadios());

        java.util.Comparator<Radio> cmp;
        String sel = allRadiosSort == null ? "Nome" : allRadiosSort.getValue();
        if (sel == null) sel = "Nome";
        switch (sel) {
            case "IP/Host" -> cmp = (a, b) -> compareHost(a.getHost(), b.getHost());
            case "Status (offline 1Âº)" -> cmp = (a, b) -> {
                int sa = statusOrder(a.getStatus()), sb = statusOrder(b.getStatus());
                if (sa != sb) return sa - sb;
                return safe(a.getName()).compareToIgnoreCase(safe(b.getName()));
            };
            case "Por ponto" -> cmp = (a, b) -> {
                String pa = project.findPointOfRadio(a.getId()).map(NetworkPoint::getName).orElse("");
                String pb = project.findPointOfRadio(b.getId()).map(NetworkPoint::getName).orElse("");
                int c = pa.compareToIgnoreCase(pb);
                if (c != 0) return c;
                return safe(a.getName()).compareToIgnoreCase(safe(b.getName()));
            };
            default -> cmp = (a, b) -> safe(a.getName()).compareToIgnoreCase(safe(b.getName()));
        }
        all.sort(cmp);
        allRadiosObs.setAll(all);

        int up = 0, down = 0;
        for (Radio r : all) {
            if (r.getStatus() == RadioStatus.UP) up++;
            else if (r.getStatus() == RadioStatus.DOWN) down++;
        }
        if (allRadiosSummary != null) {
            allRadiosSummary.setText(String.format("(%d total Â· %d online Â· %d offline)",
                    all.size(), up, down));
        }
    }

    private static int statusOrder(RadioStatus s) {
        return switch (s) { case DOWN -> 0; case UNKNOWN -> 1; case UP -> 2; };
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Ordena IPv4 numericamente; cai pra ordem lexicografica fora desse formato. */
    private static int compareHost(String a, String b) {
        a = safe(a); b = safe(b);
        String[] pa = a.split("\\."), pb = b.split("\\.");
        if (pa.length == 4 && pb.length == 4) {
            try {
                for (int i = 0; i < 4; i++) {
                    int diff = Integer.parseInt(pa[i].trim()) - Integer.parseInt(pb[i].trim());
                    if (diff != 0) return diff;
                }
                return 0;
            } catch (NumberFormatException ignored) {}
        }
        return a.compareToIgnoreCase(b);
    }

    /** Linha da lista global: nome + IP + vendor + ponto; offline em vermelho. */
    private class AllRadiosCell extends ListCell<Radio> {
        @Override
        protected void updateItem(Radio r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setText(null);
                setStyle("");
                return;
            }
            String point = project.findPointOfRadio(r.getId()).map(NetworkPoint::getName).orElse("â€”");
            String name = (r.getName() == null || r.getName().isBlank()) ? r.getHost() : r.getName();
            setText(String.format("%-15s  %s  Â·  %s  Â·  [%s]",
                    safe(r.getHost()), name, r.getVendor(), point));
            switch (r.getStatus()) {
                case DOWN -> setStyle("-fx-text-fill: white; -fx-background-color: #b00020; -fx-font-weight: bold;");
                case UP   -> setStyle("-fx-text-fill: #2e7d32;");
                default   -> setStyle("-fx-text-fill: gray;");
            }
        }
    }

    @FXML private void onNewProject() {
        // Guarda o workspace antes: sem isso, comecar um projeto novo apagaria
        // em silencio um trabalho que so existia no auto-save.
        autoSaver.backupWorkspaceBeforeReset();
        autoSaver.setTarget(null);
        project = newProjectWithDefaults();
        mapPane.setImage(null);
        // O levantamento pertencia ao projeto anterior: mante-lo faria o
        // relevo do projeto novo vir de uma area que ele nao conhece.
        plyElevation = null;
        elevation.setPly(null);
        mapPane.setSurveyFootprint(null, null);
        mapPane.clearSimulatedBeams();
        limparCacheDeAlcance();
        refreshAll();
        Settings.setLastProject("");
        setStatus("Novo projeto.");
        Log.info("Novo projeto criado");
    }

    /**
     * Enlaces desenhados a mao, para planejar o que ainda nao existe.
     *
     * O perfil e o link budget nunca precisaram de conexao â€” so de dois
     * radios com coordenada. O que faltava era poder dizer quais sao os dois
     * sem esperar que a descoberta os encontrasse.
     */
    @FXML private void onPlannedLinks() {
        if (PlannedLinkDialog.show(mainWindow(), project, elevation)) {
            refreshAll();
            setStatus("Enlaces planejados atualizados.");
        }
    }

    /**
     * Simula o alcance real do radio selecionado.
     *
     * O alcance do cadastro e so o raio do desenho; aqui ele vem da potencia,
     * das antenas das duas pontas e do relevo. Ver {@link BeamSimDialog}.
     */
    @FXML private void onSimulateBeam() {
        Radio r = selectedRadio();
        if (r == null) {
            // Barra de status passa despercebida quando o clique nao fez
            // nada visivel; aqui o nada precisa ser explicado.
            error("Nenhum r\u00e1dio selecionado",
                    "Escolha um r\u00e1dio na lista \"R\u00e1dios do ponto\" (ou clique "
                    + "no feixe dele no mapa) e use Alcance de novo.");
            return;
        }
        simulateBeamOf(r);
    }

    private void simulateBeamOf(Radio r) {
        NetworkPoint p = project.findPointOfRadio(r.getId()).orElse(null);
        if (p == null) return;
        if (!project.isMapMode()) {
            error("Sem mapa base", "A simula\u00e7\u00e3o de alcance precisa de um mapa base: "
                    + "sem ele as coordenadas dos pontos s\u00e3o pixels da imagem, e n\u00e3o h\u00e1 "
                    + "metro de ch\u00e3o para medir.");
            return;
        }
        BeamSimDialog.show(mainWindow(), r, p, project, elevation, project.isMapMode(),
                simCache,
                (radioId, cobertura, comRelevo) -> {
                    mapPane.setSimulatedBeam(radioId, cobertura, comRelevo);
                    setStatus(cobertura == null
                            ? "Alcance simulado removido do mapa."
                            : comRelevo
                              ? "Alcance simulado desenhado no mapa, cortado pelo relevo."
                              : "Alcance simulado desenhado \u2014 SEM relevo, s\u00f3 o limite de RF.");
                },
                ligado -> {
                    mapPane.setSimBeamQuality(ligado);
                    setStatus(ligado
                            ? "Alcance colorido por qualidade do sinal."
                            : "Alcance em cor \u00fanica.");
                },
                aplicado -> {
                    // Tudo que e' desenhado a partir do radio: o feixe no mapa,
                    // a pre-visualizacao de enlace e as listas. Sem isto, quem
                    // aplicava uma altura nova continuava vendo o perfil da
                    // altura antiga ate fechar e reabrir a janela.
                    mapPane.redrawBeams();
                    showProfile(aplicado);
                    radiosList.refresh();
                    refreshAllRadios();
                    setStatus("Par\u00e2metros aplicados a "
                            + nomeDoRadio(aplicado) + ".");
                });
    }

    /**
     * Ultimo lobo calculado por radio.
     *
     * Reabrir a simulacao sem ter mudado nada devolvia o mesmo desenho depois
     * de varrer o terreno de novo â€” e a varredura e' a parte cara. O cache
     * some junto com o projeto: os ids sao unicos, mas guardar resultado de
     * uma rede que nao esta mais aberta so gastaria memoria.
     */
    private final java.util.Map<String, String> simAssinatura = new java.util.HashMap<>();
    private final java.util.Map<String, com.colmeia.radiomapper.rf.BeamCoverage.Result> simResultado =
            new java.util.HashMap<>();

    private final BeamSimDialog.Cache simCache = new BeamSimDialog.Cache() {
        @Override public com.colmeia.radiomapper.rf.BeamCoverage.Result get(String radioId, String assinatura) {
            return assinatura.equals(simAssinatura.get(radioId)) ? simResultado.get(radioId) : null;
        }
        @Override public void put(String radioId, String assinatura,
                                  com.colmeia.radiomapper.rf.BeamCoverage.Result r) {
            simAssinatura.put(radioId, assinatura);
            simResultado.put(radioId, r);
        }
    };

    private void limparCacheDeAlcance() {
        simAssinatura.clear();
        simResultado.clear();
    }

    /**
     * Abre o terreno em 3D em volta do que estiver selecionado.
     *
     * Com um enlace selecionado, enquadra as duas pontas: e' o caso em que o
     * relevo no meio do caminho e a pergunta. Com um ponto so, um quadrado em
     * volta dele.
     */
    @FXML private void onTerrain3D() {
        if (!project.isMapMode()) {
            error("Sem mapa base", "O terreno 3D precisa de coordenada geogr\u00e1fica para "
                    + "consultar altitude. Escolha um mapa base em Exibir > Mapa base.");
            return;
        }
        if (!elevation.hasAny()) {
            error("Sem relevo", "Nenhuma fonte de altitude ativa. Carregue uma nuvem .PLY "
                    + "em Configura\u00e7\u00f5es, ou deixe o relevo do mapa ligado.");
            return;
        }
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        Radio r = selectedRadio();
        if (np == null && r != null) np = project.findPointOfRadio(r.getId()).orElse(null);
        if (np == null) {
            error("Nada selecionado", "Escolha um ponto de rede (ou um r\u00e1dio) para o "
                    + "terreno 3D saber que peda\u00e7o do mapa montar.");
            return;
        }

        // Um caminho so: o menu e o botao do perfil abrem a mesma janela, com
        // as mesmas coberturas, e a escolha do que mostrar acontece la dentro.
        abrirTerreno3D();
    }

    /**
     * Abre o terreno 3D pedido pelo painel de perfil.
     *
     * @param cobertura radios cujo alcance simulado deve tingir o relevo.
     *                  So entra quem ja foi simulado: recalcular aqui daria
     *                  outro resultado se algum parametro tivesse mudado, e o
     *                  3D passaria a discordar do mapa.
     */
    private void abrirTerreno3D() {
        Radio r = selectedRadio();
        NetworkPoint np = r == null ? null : project.findPointOfRadio(r.getId()).orElse(null);
        if (np == null) np = pointsList.getSelectionModel().getSelectedItem();
        if (np == null) return;

        if (!elevation.hasAny()) {
            error("Sem relevo", "Nenhuma fonte de altitude ativa. Carregue uma nuvem .PLY "
                    + "em Configura\u00e7\u00f5es, ou deixe o relevo do mapa ligado.");
            return;
        }

        NetworkPoint outroPonto = null;
        Radio outroRadio = null;
        if (r != null) {
            Link l = linkOf(r);
            if (l != null) {
                outroRadio = partnerOf(l, r);
                if (outroRadio != null) {
                    outroPonto = project.findPointOfRadio(outroRadio.getId()).orElse(null);
                }
            }
        }

        // Vao TODOS os que ja foram simulados; a janela 3D decide o que
        // mostrar. Perguntar aqui obrigava a fechar e reabrir so para comparar
        // um radio com o outro.
        java.util.List<Terrain3DView.Cobertura> coberturas = new java.util.ArrayList<>();
        for (Radio alvo : new Radio[] { r, outroRadio }) {
            if (alvo == null) continue;
            var grade = mapPane.simulatedCoverage(alvo.getId());
            if (grade == null || grade.vazia()) continue;
            coberturas.add(new Terrain3DView.Cobertura(nomeDoRadio(alvo), grade));
        }

        // A ortofoto do projeto tem prioridade sobre o mapa base: e' do mesmo
        // voo da nuvem, na resolucao do voo. O satelite fica de reserva para o
        // que ficou fora dela.
        Terrain3DView.show(mainWindow(), elevation, np, r, outroPonto, outroRadio,
                coberturas.isEmpty() ? null : coberturas, project.getBasemap(),
                mapPane.currentImages(), project.getImages(), mapPane.surveyRings(),
                com.colmeia.radiomapper.rf.BeamReach.de(r, simuladoDoRadio(r)).metros(),
                com.colmeia.radiomapper.rf.BeamReach.de(
                        outroRadio, simuladoDoRadio(outroRadio)).metros());
    }

    private static String nomeDoRadio(Radio r) {
        if (r.getName() != null && !r.getName().isBlank()) return r.getName();
        return r.getHost() == null || r.getHost().isBlank() ? "r\u00e1dio" : r.getHost();
    }

    @FXML private void onClearSimulatedBeams() {
        limparCacheDeAlcance();
        if (!mapPane.hasSimulatedBeams()) {
            setStatus("N\u00e3o h\u00e1 alcance simulado no mapa.");
            return;
        }
        mapPane.clearSimulatedBeams();
        setStatus("Alcances simulados removidos.");
    }

    @FXML private void onZoomToSurvey() {
        if (!project.isMapMode()) {
            setStatus("A \u00e1rea do levantamento s\u00f3 aparece com um mapa base ativo.");
            return;
        }
        if (!mapPane.zoomToSurvey()) {
            setStatus("Nenhum levantamento carregado \u2014 use Configura\u00e7\u00f5es > Levantamento .PLY.");
            return;
        }
        mapPane.setSurveyVisible(true);
        setStatus("Mostrando a \u00e1rea coberta pelo levantamento.");
    }

    @FXML private void onShowLog() {
        LogViewerDialog.show(mapPane.getScene().getWindow());
    }

    // ------------------------ Menu: Ferramentas / Exibir / Ajuda ------------------------

    /** Liga os botÃµes de carregar arquivo da tela de configuraÃ§Ãµes. */
    private final SettingsDialog.Hooks settingsHooks = new SettingsDialog.Hooks() {
        @Override public void loadImage(Runnable onDone) {
            pickAndLoadImage(onDone);
        }
        @Override public String currentImagePath() {
            ImageLayer l = camadaAtiva();
            return l == null ? "" : l.getPath();
        }

        @Override public ImageLayer currentLayer() { return camadaAtiva(); }

        @Override public void refreshLayers() { atualizarCamadas(); }

        @Override public void reloadImages() { loadProjectImageAsync(false); }

        @Override public void loadPly(Runnable onDone) {
            double lat = project.isMapMode() ? mapPane.centerLat() : project.getViewLat();
            double lon = project.isMapMode() ? mapPane.centerLon() : project.getViewLon();
            PlyLoadDialog.show(mainWindow(), lat, lon, pe -> {
                adoptPly(pe);
                setStatus("Levantamento carregado: " + pe.file().getName()
                        + " (" + pe.pointsUsed() + " pontos).");
                onDone.run();
            });
        }
        @Override public void clearPly() {
            plyElevation = null;
            elevation.setPly(null);
            mapPane.setSurveyFootprint(null, null);
            project.setPlyPath("");
            project.setPlyUtmZone(0);
            project.setPlyCellSizeM(0);
            setStatus("Levantamento removido â€” o relevo volta Ã s fontes seguintes.");
        }
        @Override public com.colmeia.radiomapper.geo.PlyElevation currentPly() {
            return plyElevation;
        }
    };

    private PlyElevation plyElevation;

    /**
     * Passa a usar este levantamento e anota no projeto como ele foi lido.
     *
     * O caminho sozinho nÃ£o permite reabrir: sem o CRS e o fuso a releitura
     * teria que adivinhar, e adivinhar diferente colocaria o relevo em outro
     * lugar. Ver {@link #loadProjectPlyAsync}.
     */
    private void adoptPly(PlyElevation pe) {
        plyElevation = pe;
        elevation.setPly(pe);
        project.setPlyPath(pe.file().getAbsolutePath());
        project.setPlyCrs(pe.crs());
        project.setPlyUtmZone(pe.utmZone());
        project.setPlyUtmSouth(pe.utmSouth());
        project.setPlyCellSizeM(pe.resolutionMeters());
        showSurveyFootprint(pe);
    }

    /**
     * Desenha no mapa ate onde o levantamento cobre.
     *
     * O contorno sai da propria grade carregada, entao nao ha leitura de
     * arquivo aqui: e percorrer a matriz que ja esta em memoria.
     */
    private void showSurveyFootprint(PlyElevation pe) {
        if (pe == null) {
            mapPane.setSurveyFootprint(null, null);
            return;
        }
        try {
            PlyElevation.Footprint fp = pe.footprint(SURVEY_FOOTPRINT_BLOCKS);
            mapPane.setSurveyFootprint(fp, pe.file().getName());
            Log.info("Area do levantamento %s: %.2f km2 em %d contorno(s), passo %.0f m",
                    pe.file().getName(), fp.areaM2() / 1e6, fp.rings().size(), fp.stepM());
        } catch (RuntimeException ex) {
            Log.warn("Nao consegui desenhar a area do levantamento: %s", ex.getMessage());
            mapPane.setSurveyFootprint(null, null);
        }
    }

    /**
     * Blocos no lado maior do contorno.
     *
     * Mais blocos deixam a borda mais fiel e o desenho mais pesado. Em 160 o
     * passo fica na casa da dezena de metros num voo de alguns quilometros â€”
     * abaixo do que se distingue no zoom em que se olha a area inteira.
     */
    private static final int SURVEY_FOOTPRINT_BLOCKS = 160;

    /**
     * Recarrega o levantamento apontado pelo projeto, se ainda existir.
     *
     * Em segundo plano e em silÃªncio: milhÃµes de pontos levam alguns segundos
     * e a abertura do projeto nÃ£o deve travar por causa disso. Falha vai para
     * o log e para a barra de status â€” o relevo simplesmente cai na prÃ³xima
     * fonte da cadeia, que Ã© o comportamento correto quando o arquivo sumiu.
     */
    private void loadProjectPlyAsync() {
        plyElevation = null;
        elevation.setPly(null);
        mapPane.setSurveyFootprint(null, null);
        String path = project.getPlyPath();
        if (path == null || path.isBlank()) return;

        File f = new File(path);
        if (!f.isFile()) {
            Log.warn("Levantamento do projeto nao encontrado: %s", path);
            setStatus("Levantamento do projeto nÃ£o encontrado: " + f.getName());
            return;
        }

        PlyElevation.Crs crs = project.getPlyCrs();
        int zona = project.getPlyUtmZone();
        boolean sul = project.isPlyUtmSouth();
        // Projeto gravado antes destes campos existirem: so tem o caminho.
        // Reler com o fuso errado seria pior do que nao reler, entao aqui o
        // arquivo tem que dizer onde fica.
        if (crs == PlyElevation.Crs.UTM && zona <= 0) {
            var det = detectPlyCrs(f);
            if (det == null) {
                Log.warn("Levantamento %s sem fuso gravado no projeto e sem CRS declarado "
                        + "no arquivo â€” carregue de novo pelas configuracoes.", f.getName());
                setStatus("Levantamento " + f.getName() + " precisa ser carregado de novo "
                        + "(o projeto nÃ£o guarda o fuso).");
                return;
            }
            crs = det.crs(); zona = det.utmZone(); sul = det.south();
        }
        double cell = project.getPlyCellSizeM() > 0 ? project.getPlyCellSizeM() : 1.0;

        final PlyElevation.Crs fCrs = crs;
        final int fZona = zona;
        final boolean fSul = sul;
        setStatus("Carregando levantamento " + f.getName() + "...");
        Task<PlyElevation> t = new Task<>() {
            @Override protected PlyElevation call() throws Exception {
                return PlyElevation.load(f, fCrs, fZona, fSul, cell, 1);
            }
        };
        t.setOnSucceeded(e -> {
            adoptPly(t.getValue());
            setStatus("Levantamento carregado: " + f.getName()
                    + " (" + t.getValue().pointsUsed() + " pontos).");
            mapPane.redraw();
        });
        t.setOnFailed(e -> {
            Throwable ex = t.getException();
            Log.warn("Falha ao recarregar levantamento %s: %s",
                    f.getName(), ex == null ? "?" : ex.getMessage());
            setStatus("Falha ao carregar o levantamento " + f.getName() + ".");
        });
        Thread th = new Thread(t, "ply-reload");
        th.setDaemon(true);
        th.start();
    }

    /** Le o CRS declarado pelo arquivo, para projetos antigos sem fuso gravado. */
    private static com.colmeia.radiomapper.geo.PlyGeoref.Detected detectPlyCrs(File f) {
        try {
            return com.colmeia.radiomapper.geo.PlyGeoref.detect(
                    f, com.colmeia.radiomapper.geo.PlyReader.peek(f));
        } catch (Exception ex) {
            return null;
        }
    }

    @FXML private void onSettings() {
        var out = SettingsDialog.show(mainWindow(), project, settingsHooks);
        if (!out.saved()) return;
        applySettings();
        // O mapa base vem como pedido, nao como fato: applyBasemap() e quem
        // sabe avisar que trocar reinterpreta as coordenadas dos pontos.
        if (out.basemap() != project.getBasemap()) applyBasemap(out.basemap());
        atualizarCamadas();
    }

    @FXML private void onMailSettings() {
        if (NotificationSettingsDialog.show(mainWindow(), project.getName())) {
            NotificationService.INSTANCE.reloadConfig();
            var cfg = NotificationService.INSTANCE.config();
            setStatus(cfg.isEnabled()
                    ? "NotificaÃ§Ãµes por e-mail ativas (" + cfg.getRecipients().size() + " destinatÃ¡rio(s))."
                    : "NotificaÃ§Ãµes por e-mail desativadas.");
        }
    }

    // ------------------------ Menu: Imagem de fundo ------------------------

    @FXML private void onToggleImageAdjust() {
        boolean on = imageAdjustItem.isSelected();
        mapPane.setOverlayAdjustMode(on);
        setStatus(on
                ? "Ajuste da imagem: arraste para mover, use os cantos para redimensionar."
                : "Ajuste da imagem encerrado.");
    }

    /**
     * Reaplica a georreferÃªncia sob demanda â€” Ãºtil quando a opÃ§Ã£o automÃ¡tica
     * estÃ¡ desligada, ou depois de mexer na imagem sem querer.
     */
    @FXML private void onApplyGeoreference() {
        if (!project.isMapMode()) {
            error("Sem mapa base", "Escolha um mapa base primeiro: sem ele nÃ£o hÃ¡ "
                    + "sistema de coordenadas onde encaixar a imagem.");
            return;
        }
        ImageLayer camada = camadaAtiva();
        String p = camada == null ? "" : camada.getPath();
        if (p.isBlank()) {
            error("Sem imagem", "Este projeto nÃ£o tem imagem de fundo carregada.");
            return;
        }
        File f = new File(p);
        if (!f.isFile()) {
            error("Arquivo nÃ£o encontrado", "A imagem do projeto nÃ£o estÃ¡ mais em: " + p);
            return;
        }
        // Ignora a preferÃªncia: aqui o usuÃ¡rio pediu explicitamente.
        boolean wasAuto = Settings.geotiffAuto();
        Settings.setGeotiffAuto(true);
        try {
            if (!applyGeoreference(f, camada)) {
                setStatus("A imagem nÃ£o traz coordenadas utilizÃ¡veis â€” encaixe manual.");
            }
            syncImageMenu();
        } finally {
            Settings.setGeotiffAuto(wasAuto);
        }
    }

    /** Reencaixa a imagem sobre os pontos, para quando ela "se perde". */
    @FXML private void onResetImagePlacement() {
        if (!project.isMapMode()) {
            error("Sem mapa base", "O reenquadramento sÃ³ existe com mapa base: "
                    + "sem ele a imagem Ã© o prÃ³prio mapa e jÃ¡ ocupa tudo.");
            return;
        }
        ImageLayer camada = camadaAtiva();
        if (camada == null) {
            error("Sem imagem", "Este projeto n\u00e3o tem imagem de fundo carregada.");
            return;
        }
        camada.getOverlay().setWidth(0);   // marca como "nao posicionada"
        placeOverlayIfNeeded(camada);
        atualizarCamadas();
        // A mensagem vem de placeOverlayIfNeeded(), que sabe se usou os
        // pontos ou o centro da tela.
    }

    /**
     * Diz se um arquivo Ã© foto aÃ©rea ou modelo de elevaÃ§Ã£o. JÃ¡ vem apontado
     * para a imagem do projeto, se houver â€” que Ã© o caso mais comum de dÃºvida.
     */
    @FXML private void onInspectRaster() {
        ImageLayer camada = camadaAtiva();
        String p = camada == null ? "" : camada.getPath();
        File preset = (!p.isBlank() && new File(p).isFile()) ? new File(p) : null;
        RasterInfoDialog.show(mainWindow(), preset);
    }

    @FXML private void onRemoveImage() {
        ImageLayer camada = camadaAtiva();
        if (camada == null) {
            setStatus("Nenhuma imagem para remover.");
            return;
        }
        // Tira SO a camada ativa. Com varias carregadas, "remover a imagem"
        // apagando todas seria destruir trabalho que ninguem pediu para
        // destruir.
        String nome = camada.displayName();
        project.getImages().remove(camada);
        camadaAtivaId = null;
        atualizarCamadas();
        setStatus("Imagem removida: " + nome);
        Log.info("Imagem de fundo removida do projeto: %s", camada.getPath());
    }

    /** Alinha o check do modo de ajuste com o estado real do MapPane. */
    private void syncImageMenu() {
        imageAdjustItem.setSelected(mapPane.isOverlayAdjustMode());
        montarListaDeImagens();
    }

    /**
     * Refaz a lista de imagens dentro do menu.
     *
     * Uma entrada por camada, entre os dois separadores. A marca diz qual
     * est\u00e1 ativa — a que as demais a\u00e7\u00f5es do menu afetam — e a
     * caixa liga e desliga aquela imagem sem mexer nas outras.
     *
     * Vai no menu, e n\u00e3o num painel lateral, porque a lista muda pouco e
     * ocupar espa\u00e7o fixo na tela por causa dela sairia caro num mapa.
     */
    private void montarListaDeImagens() {
        if (imageMenu == null || imageListStart == null || imageListEnd == null) return;
        var itens = imageMenu.getItems();
        int ini = itens.indexOf(imageListStart);
        int fim = itens.indexOf(imageListEnd);
        if (ini < 0 || fim < 0 || fim <= ini) return;
        itens.remove(ini + 1, fim);

        var imagens = project.getImages();
        if (imagens.isEmpty()) {
            MenuItem vazio = new MenuItem("(nenhuma imagem carregada)");
            vazio.setDisable(true);
            itens.add(ini + 1, vazio);
            return;
        }

        String ativo = camadaAtiva() == null ? null : camadaAtiva().getId();
        int pos = ini + 1;
        // De tr\u00e1s para a frente: a de cima no mapa aparece em cima na
        // lista, que \u00e9 como se l\u00ea uma pilha.
        for (int i = imagens.size() - 1; i >= 0; i--) {
            ImageLayer l = imagens.get(i);
            CheckMenuItem it = new CheckMenuItem(
                    (l.getId().equals(ativo) ? "\u25cf  " : "\u25cb  ") + l.displayName());
            it.setSelected(l.getOverlay().isVisible());
            it.setOnAction(e -> {
                // Clicar escolhe a camada; a marca segue a visibilidade dela.
                boolean eraAtiva = l.getId().equals(camadaAtivaId);
                camadaAtivaId = l.getId();
                if (eraAtiva) l.getOverlay().setVisible(it.isSelected());
                atualizarCamadas();
                setStatus(l.displayName() + (l.getOverlay().isVisible()
                        ? " \u2014 ativa" : " \u2014 ativa (oculta)"));
            });
            itens.add(pos++, it);
        }
    }

    @FXML private void onImageToFront() { moverCamada(true); }

    @FXML private void onImageToBack() { moverCamada(false); }

    /** Muda a ordem de pintura da camada ativa. */
    private void moverCamada(boolean paraFrente) {
        ImageLayer l = camadaAtiva();
        if (l == null) { setStatus("Nenhuma imagem carregada."); return; }
        var imgs = project.getImages();
        int i = imgs.indexOf(l);
        int j = paraFrente ? imgs.size() - 1 : 0;
        if (i == j) {
            setStatus(l.displayName() + " j\u00e1 est\u00e1 "
                    + (paraFrente ? "na frente." : "atr\u00e1s."));
            return;
        }
        imgs.remove(i);
        imgs.add(j, l);
        atualizarCamadas();
        setStatus(l.displayName() + (paraFrente ? " veio para a frente."
                                                : " foi para tr\u00e1s."));
    }

    @FXML private void onRadioHistory() {
        RadioHistoryDialog.show(mainWindow());
    }

    @FXML private void onMonitoredRadios() {
        if (MonitoredRadiosDialog.show(mainWindow(), project)) {
            // RÃ¡dio que saiu do monitoramento nÃ£o deve carregar histÃ³rico de
            // quedas antigo se for remarcado depois.
            NotificationService.INSTANCE.reset();
            long on = project.getPoints().stream()
                    .flatMap(p -> p.getRadios().stream())
                    .filter(Radio::isMonitored).count();
            setStatus("RÃ¡dios monitorados: " + on + ".");
        }
    }

    /**
     * Reaplica na UI o que a tela de configuracoes gravou. Chamado so depois de
     * um OK â€” em Cancelar nada mudou, entao nada precisa ser refeito.
     */
    private void applySettings() {
        mapPane.beamsVisibleProperty().set(Settings.beamsVisible());

        // setSelected nao dispara onAction, entao o agendamento e explicito.
        autoSyncMenu.setSelected(Settings.autoSync());
        if (Settings.autoSync()) startAuto(); else stopAuto();

        // O limite de sinal muda a cor dos enlaces e a contagem de fracos.
        mapPane.redraw();
        refreshWeakStatus();
    }

    @FXML private void onExit() {
        Window w = mainWindow();
        if (w instanceof Stage s) s.close();  // dispara App.stop() -> shutdown()
        else Platform.exit();
    }

    /**
     * Troca o mapa base do projeto.
     *
     * Entrar ou sair do modo mapa muda o SIGNIFICADO de x/y dos pontos (pixels
     * da imagem vs metros de Mercator). Reinterpretar coordenadas em silÃªncio
     * jogaria os pontos para o outro lado do planeta, entÃ£o quando hÃ¡ pontos
     * marcados o usuÃ¡rio decide explicitamente o que fazer com eles.
     */
    private void applyBasemap(TileSource s) {
        if (s == project.getBasemap()) return;

        boolean modeChange = s.isMap() != project.isMapMode();
        if (modeChange && !project.getPoints().isEmpty()) {
            ButtonType keep = new ButtonType("Manter (vou reposicionar)", ButtonBar.ButtonData.OTHER);
            ButtonType clear = new ButtonType("Apagar os pontos", ButtonBar.ButtonData.OTHER);
            ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);

            Alert a = new Alert(Alert.AlertType.WARNING);
            a.initOwner(mainWindow());
            a.setTitle("Mudar o modo do mapa");
            a.setHeaderText(s.isMap()
                    ? "Passar para mapa geogrÃ¡fico"
                    : "Voltar para somente imagem");
            a.setContentText(String.format(
                    "Este projeto tem %d ponto(s) com coordenadas em %s.\n\n"
                    + "No modo novo as coordenadas passam a ser %s, entÃ£o os pontos "
                    + "existentes vÃ£o aparecer em lugares sem sentido atÃ© serem "
                    + "reposicionados.\n\nO que fazer com eles?",
                    project.getPoints().size(),
                    project.isMapMode() ? "metros de Mercator" : "pixels da imagem",
                    s.isMap() ? "metros de Mercator" : "pixels da imagem"));
            a.getButtonTypes().setAll(keep, clear, cancel);

            var choice = a.showAndWait();
            if (choice.isEmpty() || choice.get() == cancel) return;
            if (choice.get() == clear) {
                int n = project.getPoints().size();
                project.getPoints().clear();
                project.getLinks().clear();
                Log.warn("Mapa base alterado: %d ponto(s) apagados a pedido do usuario", n);
            }
        }

        project.setBasemap(s);
        // Vira a semente dos proximos projetos novos: sem isso, quem ainda nao
        // salvou um .rmap perderia a escolha toda vez que fechasse o programa.
        Settings.setDefaultBasemapName(s.name());
        mapPane.setBasemap(s);
        refreshAll();

        setStatus(s.isMap()
                ? "Mapa base: " + s.label() + "."
                : "Mapa base desligado (somente imagem).");
        Log.info("Mapa base: %s", s.name());
    }

    @FXML private void onGoToCoords() {
        if (!project.isMapMode()) {
            error("Sem mapa base", "Escolha um mapa base em Exibir > Mapa base "
                    + "antes de navegar por coordenadas.");
            return;
        }
        var t = GoToCoordDialog.show(mainWindow(),
                mapPane.centerLat(), mapPane.centerLon(), mapPane.currentZoom());
        if (t == null) return;
        mapPane.centerOn(t.lat(), t.lon(), t.zoom());
        setStatus(String.format(java.util.Locale.US,
                "Centralizado em %.6f, %.6f (zoom %d).", t.lat(), t.lon(), t.zoom()));
    }

    @FXML private void onFitView() { mapPane.fitToView(); }

    @FXML private void onZoomIn() { mapPane.zoomIn(); }

    @FXML private void onZoomOut() { mapPane.zoomOut(); }

    @FXML private void onToggleFullMap() { toggleFullMap(); }

    @FXML private void onAbout() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(mainWindow());
        a.setTitle("Sobre");
        a.setHeaderText("Radio Mapper");
        a.setContentText("Mapeador visual de pontos de rede WISP (Mikrotik / Ubiquiti).\n\n"
                + "Atalhos do mapa: +/- zoom, 0 centraliza, F11 mostra so o mapa,\n"
                + "botao direito arrasta (pan) e abre menus de contexto.");
        a.showAndWait();
    }

    @FXML private void onOpenImage() {
        pickAndLoadImage(null);
    }

    /** Escolhe e carrega a imagem de fundo. Compartilhado com a tela de configuraÃ§Ãµes. */
    private void pickAndLoadImage(Runnable onDone) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Abrir imagem de fundo");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Imagens", "*.tif", "*.tiff", "*.png", "*.jpg", "*.jpeg", "*.bmp"),
                new FileChooser.ExtensionFilter("Todos", "*.*"));
        File f = fc.showOpenDialog(mapPane.getScene().getWindow());
        if (f == null) return;

        // Cada arquivo escolhido vira uma camada nova, empilhada por cima.
        // Antes, abrir a segunda imagem trocava o caminho da unica que havia
        // e a primeira sumia sem aviso.
        ImageLayer camada = new ImageLayer(f.getAbsolutePath());
        project.getImages().add(camada);
        camadaAtivaId = camada.getId();

        loadImageAsync(f, camada, true, li -> {
            setStatus("Imagem: " + f.getName()
                    + "  (" + (int) li.worldWidth() + "x" + (int) li.worldHeight() + ")");
            Log.info("Imagem carregada: %s (%.0fx%.0f)",
                    f.getAbsolutePath(), li.worldWidth(), li.worldHeight());
            if (onDone != null) onDone.run();
        });
    }

    /**
     * Carrega e aplica a imagem SEM travar a interface.
     *
     * Decodificar um GeoTIFF de centenas de megapixels leva segundos; fazer
     * isso na thread do JavaFX congelava a janela inteira atÃ© terminar â€” era
     * exatamente o travamento ao abrir TIFF. Agora a leitura vai para uma
     * thread prÃ³pria e a janela mostra um "aguarde" enquanto isso.
     *
     * @param reportError false para carregamentos silenciosos (restaurar
     *                    sessÃ£o), onde um diÃ¡logo de erro seria intrusivo
     * @param onOk        roda na thread do JavaFX, com a imagem jÃ¡ aplicada
     */
    private void loadImageAsync(File f, ImageLayer camada, boolean reportError,
                                Consumer<ImageLoader.Loaded> onOk) {
        BusyDialog busy = new BusyDialog(mainWindow(),
                "Carregando " + f.getName() + "...\nImagens grandes podem demorar alguns segundos.");

        Task<ImageLoader.Loaded> task = new Task<>() {
            @Override protected ImageLoader.Loaded call() throws Exception {
                return ImageLoader.load(f);
            }
        };

        task.setOnSucceeded(ev -> {
            busy.close();
            ImageLoader.Loaded li = task.getValue();
            mapPane.setLayerImage(camada.getId(), li.image(),
                    li.worldWidth(), li.worldHeight());
            if (!applyGeoreference(f, camada)) placeOverlayIfNeeded(camada);
            atualizarCamadas();
            if (onOk != null) onOk.accept(li);
        });

        task.setOnFailed(ev -> {
            busy.close();
            Throwable ex = task.getException();
            String msg = ex == null ? "erro desconhecido" : ex.getMessage();
            Log.error("Falha ao abrir imagem %s: %s", f.getAbsolutePath(), msg);
            if (reportError) error("Falha ao abrir imagem", msg);
        });

        Thread t = new Thread(task, "image-loader");
        t.setDaemon(true);
        t.start();
        busy.show();
    }

    /**
     * Primeira colocaÃ§Ã£o da imagem sobre o mapa: centrada na Ã¡rea visÃ­vel,
     * ocupando 60% da largura. Sem isso ela nasceria com tamanho zero, ou no
     * meio do AtlÃ¢ntico, e o usuÃ¡rio teria que caÃ§Ã¡-la para poder ajustar.
     */
    /**
     * Mostra (ou esconde) o perfil lateral do feixe do rÃ¡dio selecionado.
     *
     * SÃ³ faz sentido para rÃ¡dio com feixe configurado: sem alcance e sem
     * abertura nÃ£o hÃ¡ o que cortar contra o relevo. O painel Ã© escondido com
     * managed=false para nÃ£o ocupar espaÃ§o quando nÃ£o tem conteÃºdo.
     */
    /**
     * Clique num feixe do mapa: seleciona o ponto e o rÃ¡dio nas listas.
     *
     * NÃ£o chama showProfile() direto â€” selecionar na lista jÃ¡ dispara o
     * listener que abre o perfil, e assim o mapa e o painel lateral ficam
     * mostrando a mesma coisa, sem dois caminhos para o mesmo estado.
     */
    private void selectRadioFromMap(NetworkPoint np, Radio r) {
        if (np == null || r == null) return;
        pointsList.getSelectionModel().select(np);
        radiosObs.setAll(np.getRadios());
        radiosList.getSelectionModel().select(r);
        radiosList.scrollTo(r);
    }

    private void showProfile(Radio r) {
        if (r == null) { hideProfile(); return; }

        NetworkPoint p = project.findPointOfRadio(r.getId()).orElse(null);
        if (p == null) { hideProfile(); return; }

        // Enlace tem prioridade sobre feixe solto: se este radio esta ligado a
        // outro, o que interessa e o caminho entre os dois, nao o cone solto.
        Link enlace = linkOf(r);
        Radio outro = enlace == null ? null : partnerOf(enlace, r);
        if (outro != null) {
            NetworkPoint po = project.findPointOfRadio(outro.getId()).orElse(null);
            if (po != null) {
                profilePane.setVisible(true);
                profilePane.setManaged(true);
                // O sinal medido vai junto: comparar estimativa com leitura real
                // e o que torna a conta util em vez de academica.
                int s = enlace.getDisplaySignalDbm();
                profilePane.setPlanned(enlace.isPlanned());
                profilePane.showLink(r, p, outro, po, elevation, project.isMapMode(),
                        enlace.hasMeasurement() ? (double) s : null);
                return;
            }
        }

        // Um AP sem estacao associada continua sendo um AP: o feixe dele e'
        // o que se quer ver para decidir onde por a proxima estacao. Sem
        // alcance digitado, o numero vem da varredura (se ja houver) ou do
        // orcamento, e a tela diz de onde veio.
        var alc = com.colmeia.radiomapper.rf.BeamReach.de(
                r, simuladoDoRadio(r));
        if (!alc.vale()) { hideProfile(); return; }
        profilePane.setVisible(true);
        profilePane.setManaged(true);
        profilePane.showBeam(r, p, elevation, project.isMapMode(),
                alc.metros(), alc.origem().toString());
    }

    /** Alcance ja apurado pela varredura para este radio, ou null. */
    private Double simuladoDoRadio(Radio r) {
        if (r == null) return null;
        var cob = mapPane.simulatedCoverage(r.getId());
        if (cob == null || cob.vazia()) return null;
        double k = com.colmeia.radiomapper.geo.Mercator.groundScaleAt(
                com.colmeia.radiomapper.geo.Mercator.latOfWorldY(
                        (cob.minY() + cob.maxY()) / 2));
        return (cob.maxX() - cob.minX()) / 2 * (k <= 0 ? 1 : k);
    }

    private void hideProfile() {
        profilePane.clear();
        profilePane.setVisible(false);
        profilePane.setManaged(false);
    }

    /**
     * O rÃ¡dio do outro lado de um enlace ativo, ou null.
     *
     * Enlaces obsoletos (stale) sÃ£o ignorados: perfilar um caminho que nÃ£o
     * existe mais sÃ³ confundiria.
     */
    private Link linkOf(Radio r) {
        for (Link l : project.getLinks()) {
            if (l.isStale()) continue;
            if (r.getId().equals(l.getRadioAId()) || r.getId().equals(l.getRadioBId())) {
                if (partnerOf(l, r) != null) return l;
            }
        }
        return null;
    }

    private Radio partnerOf(Link l, Radio r) {
        String outroId = r.getId().equals(l.getRadioAId()) ? l.getRadioBId() : l.getRadioAId();
        return outroId == null ? null : project.findRadioById(outroId).orElse(null);
    }

    /** Recarrega TODAS as imagens do projeto que ainda existam no disco. */
    private void loadProjectImageAsync(boolean reportError) {
        mapPane.setImage(null);
        for (ImageLayer camada : new java.util.ArrayList<>(project.getImages())) {
            carregarCamada(camada, reportError);
        }
        atualizarCamadas();
    }

    private void carregarCamada(ImageLayer camada, boolean reportError) {
        String p = camada.getPath();
        if (p == null || p.isBlank()) return;
        File img = new File(p);
        if (!img.isFile()) {
            Log.warn("Imagem do projeto nao encontrada: %s", p);
            if (reportError) setStatus("Imagem do projeto nÃ£o encontrada: " + p);
            return;
        }
        loadImageAsync(img, camada, reportError, null);
    }

    /**
     * Posiciona a imagem pelas coordenadas do prÃ³prio GeoTIFF.
     *
     * @return true se conseguiu â€” nesse caso quem chama NÃƒO deve cair no
     *         posicionamento manual centrado na tela
     */
    private boolean applyGeoreference(File f, ImageLayer camada) {
        if (!project.isMapMode() || !Settings.geotiffAuto() || camada == null) return false;

        // Fuso escolhido Ã  mÃ£o numa carga anterior deste projeto: aplica direto,
        // sem perguntar de novo a cada abertura.
        // O fuso escolhido \u00e0 m\u00e3o fica na CAMADA: cada voo pode ter
        // sido entregue num CRS, e guardar um s\u00f3 no projeto fazia a
        // segunda imagem herdar o fuso da primeira.
        GeoTiffReader.UtmOverride override = camada.getUtmZone() > 0
                ? new GeoTiffReader.UtmOverride(camada.getUtmZone(), camada.isUtmSouth())
                : null;

        GeoTiffReader.Bounds b;
        try {
            var opt = GeoTiffReader.read(f, override);
            if (opt.isEmpty()) return false;       // imagem comum, sem georreferÃªncia
            b = opt.get();
        } catch (GeoTiffReader.NotGeoreferenced ex) {
            Log.warn("GeoTIFF nao utilizavel (%s): EPSG=%d â€” %s",
                    f.getName(), ex.epsg(), ex.getMessage());
            if (ex.modelBounds() != null) {
                Log.warn("  coordenadas cruas do arquivo: X %.2f..%.2f  Y %.2f..%.2f",
                        ex.modelBounds()[0], ex.modelBounds()[2],
                        ex.modelBounds()[1], ex.modelBounds()[3]);
            }

            // Se os nÃºmeros tÃªm cara de UTM, o sistema pode ser informado Ã 
            // mÃ£o â€” o catÃ¡logo EPSG Ã© grande demais para enumerar, mas o fuso
            // o usuÃ¡rio sabe (ou confere pela latitude/longitude resultante).
            if (ex.looksLikeUtm()) {
                double hintLat = project.isMapMode() ? mapPane.centerLat() : project.getViewLat();
                double hintLon = project.isMapMode() ? mapPane.centerLon() : project.getViewLon();
                var escolhido = GeoTiffCrsDialog.show(mainWindow(), f.getName(), ex,
                        hintLat, hintLon);
                if (escolhido != null) {
                    camada.setUtmZone(escolhido.zone());
                    camada.setUtmSouth(escolhido.south());
                    Log.info("Fuso informado manualmente para %s: UTM %d%s",
                            f.getName(), escolhido.zone(), escolhido.south() ? "S" : "N");
                    return applyGeoreference(f, camada);   // tenta de novo com a escolha
                }
                return false;
            }

            Alert a = new Alert(Alert.AlertType.WARNING);
            a.initOwner(mainWindow());
            a.setTitle("GeorreferÃªncia nÃ£o utilizada");
            a.setHeaderText("A imagem tem coordenadas, mas nÃ£o consegui aplicÃ¡-las");
            a.setContentText(ex.getMessage()
                    + "\n\nA imagem foi encaixada sobre os pontos para ajuste manual."
                    + "\n\nO detalhe tÃ©cnico tambÃ©m foi registrado no log "
                    + "(Ferramentas > Log).");
            a.showAndWait();
            return false;
        }

        ImageOverlay o = camada.getOverlay();
        double wx1 = Mercator.worldX(b.minLon());
        double wx2 = Mercator.worldX(b.maxLon());
        // Y do mundo cresce para o sul: a latitude MAIOR vira o menor Y.
        double wy1 = Mercator.worldY(b.maxLat());
        double wy2 = Mercator.worldY(b.minLat());

        o.setX(Math.min(wx1, wx2));
        o.setY(Math.min(wy1, wy2));
        o.setWidth(Math.abs(wx2 - wx1));
        o.setHeight(Math.abs(wy2 - wy1));
        o.setVisible(true);
        o.setOpacity(Settings.imageDefaultOpacity());
        if (Settings.geotiffLockAfter()) o.setLocked(true);
        camada.setGeoreferenced(true);

        atualizarCamadas();
        // Leva a cÃ¢mera atÃ© a imagem: nÃ£o adianta posicionar certo se o
        // usuÃ¡rio continua olhando para o outro lado do planeta.
        mapPane.fitToWorldRect(o.getX(), o.getY(), o.getWidth(), o.getHeight());

        String msg = "Imagem posicionada pelo GeoTIFF (" + b.crs() + ").";
        setStatus(b.warning() == null ? msg : msg + " " + b.warning());
        Log.info("Imagem georreferenciada: %s, %s", f.getName(), b.crs());
        if (b.warning() != null) Log.warn("GeoTIFF: %s", b.warning());
        return true;
    }

    private void placeOverlayIfNeeded(ImageLayer camada) {
        if (!project.isMapMode() || camada == null) return;
        ImageOverlay o = camada.getOverlay();
        if (o.isPlaced()) return;

        double aspect = mapPane.imageAspectRatio();
        o.setOpacity(Settings.imageDefaultOpacity());

        // Prioridade: os EQUIPAMENTOS. Eles estÃ£o em coordenada real e nÃ£o se
        // movem; a imagem sem georreferÃªncia Ã© que vai atÃ© eles. Mover a
        // cÃ¢mera atÃ© a imagem, como era antes, tirava os pontos de vista.
        var pts = project.getPoints();
        if (!pts.isEmpty()) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (NetworkPoint np : pts) {
                minX = Math.min(minX, np.getX()); maxX = Math.max(maxX, np.getX());
                minY = Math.min(minY, np.getY()); maxY = Math.max(maxY, np.getY());
            }

            // Com margem, para os pontos das bordas nÃ£o ficarem na quina da
            // imagem. Ponto Ãºnico (ou todos juntos) nÃ£o define extensÃ£o, entÃ£o
            // cai num tamanho de bairro.
            double needW = Math.max((maxX - minX) * 1.3, 500);
            double needH = Math.max((maxY - minY) * 1.3, 500);

            // MantÃ©m a proporÃ§Ã£o e garante que a imagem cubra todos os pontos.
            double w = (needW / needH > aspect) ? needW : needH * aspect;
            o.placeCentered((minX + maxX) / 2, (minY + maxY) / 2, w, aspect);

            Log.info("Imagem posicionada sobre os %d ponto(s) do projeto: "
                    + "%.0fx%.0f m em (%.0f, %.0f)",
                    pts.size(), o.getWidth(), o.getHeight(), o.getX(), o.getY());
            setStatus("Imagem encaixada sobre os pontos do projeto. "
                    + "Use Exibir > Imagem de fundo > Ajustar sobre o mapa para alinhar.");
            return;
        }

        // Sem pontos nÃ£o hÃ¡ o que priorizar: centraliza na Ã¡rea visÃ­vel.
        Point2D c = mapPane.viewCenterWorld();
        double span = mapPane.viewWorldWidth() * 0.6;
        if (span <= 0) return;
        o.placeCentered(c.getX(), c.getY(), span, aspect);
        Log.info("Imagem posicionada no centro da tela (projeto sem pontos): "
                + "%.0fx%.0f m em (%.0f, %.0f)",
                o.getWidth(), o.getHeight(), o.getX(), o.getY());
    }

    @FXML private void onLoadProject() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Radio Mapper (*.rmap)", "*.rmap"));
        File f = fc.showOpenDialog(mapPane.getScene().getWindow());
        if (f == null) return;
        try {
            project = ProjectIO.load(f);
            refreshAll();
            Settings.setLastProject(f.getAbsolutePath());
            autoSaver.setTarget(f);
            int totalRadios = project.getPoints().stream().mapToInt(p -> p.getRadios().size()).sum();
            setStatus("Projeto carregado: " + f.getName());
            Log.info("Projeto carregado: %s (%d pontos, %d rÃ¡dios, %d links)",
                    f.getAbsolutePath(), project.getPoints().size(), totalRadios, project.getLinks().size());
            loadProjectImageAsync(true);
            loadProjectPlyAsync();
        } catch (Exception ex) {
            Log.error("Falha ao carregar %s: %s", f.getAbsolutePath(), ex.getMessage());
            error("Falha ao carregar", ex.getMessage());
        }
    }

    @FXML private void onSaveProject() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Radio Mapper (*.rmap)", "*.rmap"));
        File f = fc.showSaveDialog(mapPane.getScene().getWindow());
        if (f == null) return;
        try {
            captureView();
            ProjectIO.save(project, f);
            Settings.setLastProject(f.getAbsolutePath());
            autoSaver.setTarget(f);   // dali em diante o auto-save grava aqui
            setStatus("Salvo: " + f.getName());
            Log.info("Projeto salvo: %s", f.getAbsolutePath());
        } catch (Exception ex) {
            Log.error("Falha ao salvar %s: %s", f.getAbsolutePath(), ex.getMessage());
            error("Falha ao salvar", ex.getMessage());
        }
    }

    @FXML private void onAddPoint() {
        setStatus("Clique no mapa para posicionar o novo ponto (ESC cancela).");
        mapPane.enterPlacePointMode(this::createPointAt);
    }

    /**
     * Pergunta o nome e cria o ponto na coordenada informada. Serve tanto ao
     * modo "clique para posicionar" quanto ao "criar ponto aqui" do menu de
     * contexto â€” o segundo Ã© um clique a menos para o mesmo resultado.
     */
    private void createPointAt(Point2D world) {
        TextInputDialog dlg = new TextInputDialog("Ponto " + (project.getPoints().size() + 1));
        dlg.initOwner(mainWindow());
        dlg.setHeaderText("Nome do ponto de rede");
        dlg.setContentText("PosiÃ§Ã£o: " + describePosition(world));
        Optional<String> r = dlg.showAndWait();
        if (r.isEmpty() || r.get().isBlank()) {
            setStatus("Cancelado.");
            return;
        }
        NetworkPoint np = new NetworkPoint(r.get(), world.getX(), world.getY());
        project.getPoints().add(np);
        refreshAll();
        pointsList.getSelectionModel().select(np);
        setStatus(String.format("Ponto \"%s\" criado em %s.", r.get(), describePosition(world)));
        Log.info("Ponto criado: %s em %s", r.get(), describePosition(world));
    }

    /**
     * PosiÃ§Ã£o em linguagem humana. Em modo mapa, metros de Mercator nÃ£o dizem
     * nada a ninguÃ©m â€” o que se confere contra um GPS Ã© latitude/longitude.
     */
    private String describePosition(Point2D world) {
        if (project.isMapMode()) {
            return String.format(java.util.Locale.US, "%.6f, %.6f",
                    Mercator.latOfWorldY(world.getY()), Mercator.lonOfWorldX(world.getX()));
        }
        return String.format("(%.0f, %.0f)", world.getX(), world.getY());
    }

    /** Arrasta o mapa atÃ© o ponto selecionado na lista. */
    @FXML private void onLocatePoint() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        if (np == null) {
            setStatus("Selecione um ponto na lista para localizÃ¡-lo.");
            return;
        }
        locatePoint(np);
    }

    /**
     * Move um ponto para uma coordenada digitada, em vez de arrastar.
     *
     * Arrastar Ã© impreciso quando a coordenada veio de um GPS ou de um
     * relatÃ³rio. Se o ponto faz parte de uma seleÃ§Ã£o mÃºltipla, o grupo inteiro
     * anda junto, preservando o arranjo relativo entre eles.
     */
    /** Abre a tela de coordenadas do ponto: copiar de la, ou colar para mover. */
    private void showPointCoords(NetworkPoint np) {
        if (np == null) { setStatus("Selecione um ponto."); return; }
        boolean mapa = project.isMapMode();
        PointCoordDialog.show(mainWindow(), np, mapa, elevation, (um, dois) -> {
            if (mapa) aplicarCoordenada(np, um, dois);
            else aplicarPixels(np, um, dois);
        });
    }

    @FXML private void onPointCoords() {
        showPointCoords(pointsList.getSelectionModel().getSelectedItem());
    }

    /** Copia a coordenada do ponto sem abrir tela nenhuma. */
    private void copyPointCoords(NetworkPoint np) {
        if (np == null) return;
        // Sem mapa base a posicao ainda existe, so nao e geografica.
        String txt = project.isMapMode()
                ? PointCoordDialog.decimal(
                        Mercator.latOfWorldY(np.getY()), Mercator.lonOfWorldX(np.getX()))
                : String.format(java.util.Locale.US, "%.0f, %.0f", np.getX(), np.getY());
        ClipboardContent cc = new ClipboardContent();
        cc.putString(txt);
        Clipboard.getSystemClipboard().setContent(cc);
        setStatus("Coordenadas de \"" + np.getName() + "\" copiadas: " + txt);
    }

    /**
     * Leva o ponto para a coordenada informada.
     *
     * Passa pelo mesmo caminho do arrasto no mapa â€” inclusive avisando o
     * perfil â€”, senao o painel de baixo continuaria mostrando o trajeto antigo
     * depois de mover por coordenada.
     */
    private void aplicarCoordenada(NetworkPoint np, double lat, double lon) {
        double novoX = Mercator.worldX(lon);
        double novoY = Mercator.worldY(lat);
        np.setX(novoX);
        np.setY(novoY);
        refreshAll();
        mapPane.locate(novoX, novoY);
        Radio sel = selectedRadio();
        if (sel != null) showProfile(sel);
        limparCacheDeAlcance();
        mapPane.clearSimulatedBeams();
        setStatus(String.format(java.util.Locale.US,
                "\"%s\" movido para %.6f, %.6f.", np.getName(), lat, lon));
        Log.info("Ponto \"%s\" movido por coordenada para %.6f, %.6f", np.getName(), lat, lon);
    }

    /**
     * Leva o ponto para uma posicao em pixels â€” o caso sem mapa base.
     *
     * Nao mexe no perfil nem no alcance simulado porque nenhum dos dois roda
     * em modo imagem: sem coordenada geografica nao ha relevo para consultar.
     */
    private void aplicarPixels(NetworkPoint np, double x, double y) {
        np.setX(x);
        np.setY(y);
        refreshAll();
        mapPane.locate(x, y);
        setStatus(String.format(java.util.Locale.US,
                "\"%s\" movido para x=%.0f, y=%.0f.", np.getName(), x, y));
        Log.info("Ponto \"%s\" movido por pixel para %.0f, %.0f", np.getName(), x, y);
    }

    private void movePointToCoords(NetworkPoint np) {
        if (!project.isMapMode()) {
            error("Sem mapa base", "Mover por coordenada exige mapa base: sem ele as "
                    + "posiÃ§Ãµes sÃ£o pixels da imagem, nÃ£o latitude e longitude.");
            return;
        }
        double lat = Mercator.latOfWorldY(np.getY());
        double lon = Mercator.lonOfWorldX(np.getX());
        var alvo = GoToCoordDialog.show(mainWindow(), lat, lon, mapPane.currentZoom());
        if (alvo == null) return;

        double novoX = Mercator.worldX(alvo.lon());
        double novoY = Mercator.worldY(alvo.lat());
        double dx = novoX - np.getX(), dy = novoY - np.getY();

        var sel = mapPane.selectedPoints();
        if (sel.size() > 1 && sel.contains(np)) {
            for (NetworkPoint outro : new java.util.ArrayList<>(sel)) {
                outro.setX(outro.getX() + dx);
                outro.setY(outro.getY() + dy);
            }
            setStatus(String.format("%d pontos movidos junto com \"%s\".", sel.size(), np.getName()));
        } else {
            np.setX(novoX);
            np.setY(novoY);
            setStatus(String.format(java.util.Locale.US,
                    "\"%s\" movido para %.6f, %.6f.", np.getName(), alvo.lat(), alvo.lon()));
        }

        refreshAll();
        mapPane.locate(novoX, novoY);
        Log.info("Ponto \"%s\" movido para %.6f, %.6f", np.getName(), alvo.lat(), alvo.lon());
    }

    private void locatePoint(NetworkPoint np) {
        mapPane.locate(np.getX(), np.getY());
        mapPane.selectedPointProperty().set(np);
        setStatus("Localizado: " + np.getName() + "  " + describePosition(
                new Point2D(np.getX(), np.getY())) + ".");
    }

    @FXML private void onRenamePoint() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        if (np == null) return;
        TextInputDialog dlg = new TextInputDialog(np.getName());
        dlg.initOwner(mainWindow());
        dlg.setHeaderText("Novo nome do ponto");
        dlg.showAndWait().ifPresent(n -> { np.setName(n); pointsList.refresh(); });
    }

    @FXML private void onRemovePoint() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        if (np == null) return;
        project.getPoints().remove(np);
        refreshAll();
        Log.warn("Ponto removido: %s (%d rÃ¡dios)", np.getName(), np.getRadios().size());
    }

    @FXML private void onAddRadio() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        if (np == null) { setStatus("Selecione um ponto primeiro."); return; }
        Radio r = new Radio();
        if (editRadio(r, np)) {
            np.getRadios().add(r);
            radiosObs.setAll(np.getRadios());
            refreshAllRadios();
            mapPane.redraw();
            Log.info("RÃ¡dio adicionado: %s @ %s no ponto \"%s\"", r.getVendor(), r.getHost(), np.getName());
        }
    }

    @FXML private void onEditRadio() {
        Radio r = radiosList.getSelectionModel().getSelectedItem();
        if (r == null) return;
        if (editRadio(r, null)) {
            radiosList.refresh();
            refreshAllRadios();
            mapPane.redraw();
            Log.info("RÃ¡dio editado: %s @ %s", r.getVendor(), r.getHost());
        }
    }

    /**
     * RÃ¡dio selecionado, olhando primeiro a lista do ponto e depois a lista
     * geral. As duas estÃ£o na tela ao mesmo tempo, e o usuÃ¡rio pode ter
     * selecionado em qualquer uma delas.
     */
    private Radio selectedRadio() {
        Radio r = radiosList.getSelectionModel().getSelectedItem();
        return r != null ? r : allRadiosList.getSelectionModel().getSelectedItem();
    }

    @FXML private void onOpenRadioBrowser() {
        Radio r = selectedRadio();
        if (r == null) {
            setStatus("Selecione um rÃ¡dio para abrir no navegador.");
            return;
        }
        if (r.getHost() == null || r.getHost().isBlank()) {
            setStatus("Este rÃ¡dio nÃ£o tem host/IP cadastrado.");
            return;
        }
        openRadioInBrowser(r);
    }

    /** AÃ§Ãµes de rÃ¡dio no clique direito, para as duas listas. */
    private ContextMenu buildRadioListMenu(Radio r) {
        ContextMenu menu = new ContextMenu();

        MenuItem edit = new MenuItem("Editar rÃ¡dio...");
        edit.setOnAction(e -> {
            NetworkPoint at = project.findPointOfRadio(r.getId()).orElse(null);
            if (editRadio(r, at)) {
                refreshAllRadios();
                radiosList.refresh();
                mapPane.redraw();
            }
        });

        MenuItem browser = new MenuItem("Abrir no navegador");
        browser.setDisable(r.getHost() == null || r.getHost().isBlank());
        browser.setOnAction(e -> openRadioInBrowser(r));

        MenuItem stations = new MenuItem("EstaÃ§Ãµes conectadas...");
        stations.setDisable(r.getHost() == null || r.getHost().isBlank());
        stations.setOnAction(e -> RadioConnectionsDialog.show(r, project, mainWindow()));

        menu.getItems().addAll(edit, browser, stations);
        return menu;
    }

    /** Liga o menu de contexto numa lista de rÃ¡dios. */
    private void installRadioMenu(ListView<Radio> list) {
        list.setContextMenu(null);
        list.setOnContextMenuRequested(ev -> {
            Radio r = list.getSelectionModel().getSelectedItem();
            if (r == null) return;
            Menus.mostrar(buildRadioListMenu(r), list, ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
    }

    // ------------------------ Roteadores ------------------------

    @FXML private void onAddRouter() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        if (np == null) { setStatus("Selecione um ponto primeiro."); return; }
        var rt = new com.colmeia.radiomapper.model.Router();
        if (RouterDialog.show(mainWindow(), rt, np.getName())) {
            np.getRouters().add(rt);
            routersObs.setAll(np.getRouters());
            setStatus("Roteador adicionado: " + rt.displayName());
            Log.info("Roteador adicionado: %s @ %s no ponto \"%s\"",
                    rt.displayName(), rt.getHost(), np.getName());
        }
    }

    @FXML private void onEditRouter() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        var rt = routersList.getSelectionModel().getSelectedItem();
        if (rt == null) { setStatus("Selecione um roteador."); return; }
        if (RouterDialog.show(mainWindow(), rt, np == null ? "" : np.getName())) {
            routersList.refresh();
            setStatus("Roteador atualizado: " + rt.displayName());
        }
    }

    @FXML private void onRouterTraffic() {
        var rt = routersList.getSelectionModel().getSelectedItem();
        if (rt == null) { setStatus("Selecione um roteador."); return; }
        if (rt.getInterfaces().isEmpty()) {
            setStatus("Este roteador ainda n\u00e3o teve as interfaces lidas \u2014 "
                    + "abra Editar e use \"Ler interfaces\".");
            return;
        }
        TrafficMonitorDialog.show(mainWindow(), rt, rt.getInterfaces().get(0).getName());
    }

    @FXML private void onRemoveRouter() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        var rt = routersList.getSelectionModel().getSelectedItem();
        if (np == null || rt == null) return;
        np.getRouters().remove(rt);
        routersObs.setAll(np.getRouters());
        Log.warn("Roteador removido: %s @ %s do ponto \"%s\"",
                rt.displayName(), rt.getHost(), np.getName());
    }

    @FXML private void onRemoveRadio() {
        NetworkPoint np = pointsList.getSelectionModel().getSelectedItem();
        Radio r = radiosList.getSelectionModel().getSelectedItem();
        if (np == null || r == null) return;
        np.getRadios().remove(r);
        radiosObs.setAll(np.getRadios());
        refreshAllRadios();
        Log.warn("RÃ¡dio removido: %s @ %s do ponto \"%s\"", r.getVendor(), r.getHost(), np.getName());
    }

    @FXML private void onSync() {
        if (syncing.get()) { setStatus("JÃ¡ sincronizando..."); return; }
        setStatus("Sincronizando rÃ¡dios...");
        new Thread(this::syncOnce, "manual-sync").start();
    }

    @FXML private void onToggleAuto() {
        Settings.setAutoSync(autoSyncMenu.isSelected());
        if (autoSyncMenu.isSelected()) startAuto();
        else stopAuto();
    }

    private void startAuto() {
        stopAuto();
        int interval = Settings.autoSyncIntervalSec();
        autoTask = scheduler.scheduleWithFixedDelay(
                this::syncOnce, 0, interval, TimeUnit.SECONDS);
        setStatus("Auto-sincronizaÃ§Ã£o ligada (a cada " + interval + "s).");
    }

    private void stopAuto() {
        if (autoTask != null) { autoTask.cancel(false); autoTask = null; }
    }

    /** Executa uma rodada de sondagem na thread chamadora. Anti-overlap. */
    /**
     * Uma rodada de sondagem.
     *
     * Engole qualquer exceÃ§Ã£o de propÃ³sito: isto roda dentro de um
     * {@code scheduleWithFixedDelay}, que CANCELA a tarefa se ela lanÃ§ar. Sem
     * esta rede, um erro isolado numa rodada desligava a auto-sincronizaÃ§Ã£o
     * em silÃªncio, e o usuÃ¡rio sÃ³ descobriria quando percebesse que os status
     * pararam de atualizar.
     */
    private void syncOnce() {
        if (!syncing.compareAndSet(false, true)) return;
        try {
            syncOnceUnguarded();
        } catch (Throwable ex) {
            Log.error("Falha na sincronizacao: %s: %s",
                    ex.getClass().getSimpleName(), ex.getMessage());
        } finally {
            syncing.set(false);
        }
    }

    private void syncOnceUnguarded() {
        {
            TopologyBuilder.Result res = TopologyBuilder.refresh(project);

            // O historico vem ANTES da notificacao, e nao depende dela estar
            // ligada: e dele que sai "caiu as 03:12, estava online ha 4 dias".
            RadioHistory.INSTANCE.observe(project);

            // Avalia quedas/retornos aqui mesmo, ainda fora da thread do
            // JavaFX: o envio do e-mail pode demorar e nao pode travar a UI.
            NotificationService.INSTANCE.onSyncCompleted(project);

            int threshold = Settings.signalThresholdDbm();
            int weak = 0;
            for (Link l : project.getLinks()) {
                if (l.isStale()) continue;
                int s = l.getDisplaySignalDbm();
                if (s != 0 && s < threshold) weak++;
            }
            final int weakCount = weak;
            Platform.runLater(() -> {
                String base = String.format("[%s] %d rÃ¡dios (%d down), %d enlaces ativos",
                        LocalTime.now().format(HHMMSS),
                        res.totalRadios, res.radiosDown, res.linksActive);
                if (res.linksStale > 0) {
                    base += String.format(", %d removidos", res.linksStale);
                }
                if (weakCount > 0) {
                    base += String.format(", %d fracos (< %d dBm)", weakCount, threshold);
                }
                setStatus(base + ".");
                mapPane.redraw();
                radiosList.refresh();
                pointsList.refresh();
                refreshAllRadios();
            });
        }
    }

    /**
     * Grava a visÃ£o atual no projeto, para reabrir onde o usuÃ¡rio parou.
     * SÃ³ faz sentido em modo mapa â€” em modo imagem o enquadramento vem da
     * prÃ³pria imagem.
     */
    private void captureView() {
        if (!project.isMapMode()) return;
        double lat = mapPane.centerLat();
        double lon = mapPane.centerLon();
        int zoom = mapPane.currentZoom();
        project.setViewLat(lat);
        project.setViewLon(lon);
        project.setViewZoom(zoom);
        // Tambem nas preferencias: e o que faz um projeto NOVO abrir na regiao
        // onde se estava trabalhando, em vez de no centro do Brasil.
        Settings.setLastView(lat, lon, zoom);
    }

    /**
     * Chamado pelo App.stop() ao fechar a janela.
     *
     * Cada etapa Ã© isolada: uma falha em qualquer uma delas nÃ£o pode impedir
     * as seguintes. Antes, uma exceÃ§Ã£o aqui abortava o resto â€” sessÃµes SSH
     * ficavam abertas, o histÃ³rico nÃ£o era gravado e o arquivo de log nem
     * chegava a ser fechado.
     */
    public void shutdown() {
        step("gravar enquadramento", this::captureView);
        step("auto-save final", autoSaver::stop);
        step("parar auto-sync", () -> { stopAuto(); scheduler.shutdownNow(); });
        step("encerrar sondagem", TopologyBuilder::shutdown);
        step("fechar sessoes SSH", SshSessionPool.INSTANCE::closeAll);
        step("encerrar cache de tiles", TileCache.INSTANCE::shutdown);
        step("encerrar relevo", TerrainTiles.INSTANCE::shutdown);
        step("encerrar notificacoes", NotificationService.INSTANCE::shutdown);
        step("gravar historico", RadioHistory.INSTANCE::flush);
    }

    private void step(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable ex) {
            Log.warn("Falha ao %s no encerramento: %s: %s",
                    what, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void setStatus(String s) { statusLabel.setText(s); }

    /** Atualiza a barra com a contagem de enlaces abaixo do limite atual. */
    private void refreshWeakStatus() {
        int threshold = Settings.signalThresholdDbm();
        int weak = 0, active = 0;
        for (Link l : project.getLinks()) {
            if (l.isStale()) continue;
            active++;
            int s = l.getDisplaySignalDbm();
            if (s != 0 && s < threshold) weak++;
        }
        setStatus(String.format("Limite: %d dBm â€” %d enlaces ativos, %d abaixo do limite.",
                threshold, active, weak));
    }

    private Window mainWindow() {
        return mapPane.getScene() == null ? null : mapPane.getScene().getWindow();
    }

    boolean editRadio(Radio r, NetworkPoint at) {
        return editRadio(r, at, false);
    }

    /**
     * Abre o RadioDialog com preview de feixe ativo: enquanto o usuÃ¡rio
     * mexe nos spinners/bÃºssola, o feixe Ã© redesenhado no mapa em tempo
     * real. Se o rÃ¡dio ainda nÃ£o estiver vinculado a um ponto (fluxo
     * "Adicionar rÃ¡dio"), passe explicitamente o ponto destino em {@code at}.
     * Com {@code focusBeamTab=true} a aba "Feixe" jÃ¡ vem selecionada.
     */
    boolean editRadio(Radio r, NetworkPoint at, boolean focusBeamTab) {
        NetworkPoint pt = at != null ? at : project.findPointOfRadio(r.getId()).orElse(null);
        if (pt != null) mapPane.beginBeamPreview(r, pt);
        try {
            return RadioDialog.show(r, mainWindow(), mapPane::redrawBeams, focusBeamTab, project);
        } finally {
            mapPane.endBeamPreview();
        }
    }

    /** Menu de clique direito sobre o feixe. */
    private ContextMenu buildBeamContextMenu(NetworkPoint np, Radio r) {
        ContextMenu menu = new ContextMenu();

        String label = (r.getName() == null || r.getName().isBlank())
                ? (r.getHost() == null || r.getHost().isBlank() ? "(rÃ¡dio)" : r.getHost())
                : r.getName();
        MenuItem header = new MenuItem(label + "  Â·  feixe");
        header.setDisable(true);

        MenuItem editBeam = new MenuItem("Editar feixe...");
        editBeam.setOnAction(e -> {
            if (editRadio(r, np, true)) {
                radiosList.refresh();
                refreshAllRadios();
                mapPane.redraw();
            }
        });

        MenuItem hideBeam = new MenuItem("Ocultar este feixe");
        hideBeam.setOnAction(e -> {
            r.setBeamVisible(false);
            mapPane.redrawBeams();
            Log.info("Feixe ocultado: %s @ %s (ponto \"%s\")",
                    r.getVendor(), r.getHost(), np.getName());
        });

        MenuItem simular = new MenuItem("Simular alcance real...");
        simular.setOnAction(e -> simulateBeamOf(r));

        MenuItem planejar = new MenuItem("Planejar enlace a partir daqui...");
        planejar.setOnAction(e -> onPlannedLinks());

        menu.getItems().addAll(header, new SeparatorMenuItem(), editBeam, hideBeam,
                new SeparatorMenuItem(), simular, planejar);
        return menu;
    }

    private void error(String header, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.initOwner(mainWindow());
        a.setHeaderText(header);
        a.setContentText(msg);
        a.showAndWait();
    }
}
