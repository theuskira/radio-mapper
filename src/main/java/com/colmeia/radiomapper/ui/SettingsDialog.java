package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.TileSource;
import com.colmeia.radiomapper.model.ImageOverlay;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.util.Log;
import com.colmeia.radiomapper.util.Settings;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;

/**
 * Tela de configurações do app. Reúne o que antes vivia solto na barra
 * superior (intervalo de auto-sync, limite de sinal) mais as preferências que
 * só existiam implícitas em {@link Settings}.
 *
 * Nada é gravado enquanto o usuário não confirmar: os controles trabalham em
 * cima de valores locais e só no OK é que {@link Settings} é tocado. Cancelar
 * descarta tudo, inclusive um "Esquecer" pendente.
 */
public final class SettingsDialog {

    private SettingsDialog() {}

    /**
     * Resultado da edição.
     *
     * O mapa base sai daqui como <em>pedido</em>, não como fato consumado:
     * trocá-lo pode reinterpretar as coordenadas dos pontos, e quem sabe
     * avisar sobre isso é o controller. O diálogo não decide sozinho.
     */
    public record Outcome(boolean saved, TileSource basemap) {}

    /**
     * Ações que o diálogo não sabe fazer sozinho.
     *
     * Carregar imagem e PLY envolve escolher arquivo, ler em segundo plano e
     * reaplicar no mapa — coisas do controller. O diálogo só oferece o botão e
     * pergunta o estado atual para mostrar na tela.
     */
    public interface Hooks {
        void loadImage(Runnable onDone);
        String currentImagePath();

        /** A camada que o mapa est\u00e1 ajustando, ou null. */
        com.colmeia.radiomapper.model.ImageLayer currentLayer();

        /** Redesenha as camadas depois de mexer na lista. */
        void refreshLayers();

        /** Rel\u00ea os arquivos \u2014 usado ao trocar a qualidade. */
        void reloadImages();
        void loadPly(Runnable onDone);
        void clearPly();
        com.colmeia.radiomapper.geo.PlyElevation currentPly();
    }

    /**
     * @param project o projeto atual — o diálogo edita mapa base e sobreposição
     *                da imagem, que são estado DELE, não preferência global
     * @param hooks   pode ser null; nesse caso os botões de carregar ficam inertes
     */
    public static Outcome show(Window owner, Project project, Hooks hooks) {
        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Configurações");
        dlg.setHeaderText("Preferências do Radio Mapper");
        dlg.setResizable(true);

        ButtonType reset = new ButtonType("Restaurar padrões", ButtonBar.ButtonData.LEFT);
        dlg.getDialogPane().getButtonTypes().addAll(reset, ButtonType.OK, ButtonType.CANCEL);

        // ------------------------ Exibição ------------------------
        // Tudo que muda o que aparece na tela AGORA, incluindo o que pertence
        // ao projeto. Antes estava espalhado por dois submenus.
        // Os controles abaixo valem para a camada ATIVA. Com varias imagens
        // sobrepostas nao ha "a imagem" — cada uma tem a sua visibilidade, a
        // sua opacidade e a sua trava, e mexer aqui mexe na que esta
        // selecionada no mapa.
        com.colmeia.radiomapper.model.ImageLayer camada =
                hooks == null ? project.firstImage() : hooks.currentLayer();
        if (camada == null) camada = project.firstImage();
        ImageOverlay ov = camada == null ? new ImageOverlay() : camada.getOverlay();
        boolean hasImage = camada != null && camada.hasFile();
        int quantasImagens = project.getImages().size();

        ComboBox<TileSource> basemap = new ComboBox<>();
        basemap.getItems().setAll(TileSource.values());
        basemap.setValue(project.getBasemap());
        basemap.setPrefWidth(240);

        CheckBox beams = new CheckBox("Mostrar os feixes das antenas");
        beams.setSelected(Settings.beamsVisible());

        CheckBox imgVisible = new CheckBox(quantasImagens > 1
                ? "Mostrar esta imagem sobre o mapa"
                : "Mostrar a imagem sobre o mapa");
        imgVisible.setSelected(ov.isVisible());

        CheckBox imgLocked = new CheckBox(quantasImagens > 1
                ? "Travar a posi\u00e7\u00e3o desta imagem"
                : "Travar a posi\u00e7\u00e3o da imagem");
        imgLocked.setSelected(ov.isLocked());

        Slider imgCurrentOpacity = new Slider(0, 1, ov.getOpacity());
        imgCurrentOpacity.setPrefWidth(200);
        imgCurrentOpacity.setShowTickMarks(true);
        imgCurrentOpacity.setMajorTickUnit(0.25);
        Label curOpacityValue = new Label();
        curOpacityValue.setMinWidth(45);
        Runnable showCur = () ->
                curOpacityValue.setText(Math.round(imgCurrentOpacity.getValue() * 100) + "%");
        imgCurrentOpacity.valueProperty().addListener((o, a, b) -> showCur.run());
        showCur.run();

        // Sobreposição só existe com mapa base E imagem: sem um dos dois não há
        // o que sobrepor, e deixar o controle ativo sugeriria que há.
        var semSobreposicao = basemap.valueProperty().isEqualTo(TileSource.NONE);
        imgVisible.disableProperty().bind(semSobreposicao.or(new javafx.beans.property.SimpleBooleanProperty(!hasImage)));
        imgLocked.disableProperty().bind(imgVisible.disableProperty());
        imgCurrentOpacity.disableProperty().bind(imgVisible.disableProperty());

        GridPane gView = section();
        int r = 0;
        gView.add(new Label("Tipo de mapa:"), 0, r); gView.add(basemap, 1, r++);
        gView.add(hint("Sem mapa base, as coordenadas dos pontos são pixels da imagem. "
                + "Com mapa base, viram metros de Web Mercator — por isso trocar com "
                + "pontos já marcados pede confirmação."), 0, r++, 2, 1);
        gView.add(beams, 0, r++, 2, 1);

        // Escala só existe em modo imagem: com mapa base, a escala vem da
        // projeção e o programa a conhece.
        Spinner<Double> mpp = new Spinner<>(0.001, 10000.0, project.getMetersPerPixel(), 0.1);
        mpp.setEditable(true);
        mpp.setPrefWidth(120);
        mpp.disableProperty().bind(basemap.valueProperty().isNotEqualTo(TileSource.NONE));
        gView.add(new Label("Escala da imagem:"), 0, r);
        gView.add(row(mpp, new Label("metros por pixel")), 1, r++);
        gView.add(hint("Sem mapa base, o programa não tem como saber se a foto cobre um "
                + "quarteirão ou uma cidade. Este valor é o que faz o alcance dos feixes, "
                + "medido em metros, ser desenhado no tamanho certo. Meça uma distância "
                + "conhecida na imagem para calibrar."), 0, r++, 2, 1);

        // Carregar imagem aqui tambem: e onde se procura configuracao de
        // exibicao, e evita ter que sair do dialogo para trocar o fundo.
        Label imgStatus = new Label();
        imgStatus.setWrapText(true);
        imgStatus.setMaxWidth(430);
        Runnable refreshImg = () -> {
            String path = hooks == null
                    ? (project.firstImage() == null ? "" : project.firstImage().getPath())
                    : hooks.currentImagePath();
            boolean tem = path != null && !path.isBlank();
            int n = project.getImages().size();
            // Com varias, dizer so o caminho de uma esconderia as outras.
            imgStatus.setText(!tem ? "Nenhuma imagem carregada."
                    : n > 1 ? path + "\n(" + n + " imagens carregadas; os controles "
                              + "acima valem para esta)"
                            : path);
            imgStatus.setStyle(tem ? "-fx-text-fill: #2e7d32; -fx-font-size: 11;"
                                   : "-fx-text-fill: #888; -fx-font-size: 11;");
        };
        refreshImg.run();

        // A pilha de ortofotos. Do topo para a base, que e' a ordem de
        // prioridade: a de cima cobre as de baixo onde se sobrepoem.
        javafx.scene.control.ListView<com.colmeia.radiomapper.model.ImageLayer> listaImgs =
                new javafx.scene.control.ListView<>();
        listaImgs.setPrefHeight(110);
        listaImgs.setPlaceholder(new Label("Nenhuma ortofoto carregada."));
        listaImgs.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(
                    com.colmeia.radiomapper.model.ImageLayer l, boolean vazio) {
                super.updateItem(l, vazio);
                if (vazio || l == null) { setText(null); return; }
                int pos = project.getImages().indexOf(l);
                boolean topo = pos == project.getImages().size() - 1;
                setText((topo ? "\u25b2 " : "   ") + l.displayName()
                        + (l.getOverlay().isVisible() ? "" : "   (oculta)")
                        + (l.getOverlay().isLocked() ? "   (travada)" : ""));
            }
        });

        Runnable recarregarLista = () -> {
            var sel = listaImgs.getSelectionModel().getSelectedItem();
            // Do topo para a base: a ultima da lista do projeto e' a que
            // cobre as outras, entao ela aparece em primeiro.
            var invertida = new java.util.ArrayList<>(project.getImages());
            java.util.Collections.reverse(invertida);
            listaImgs.getItems().setAll(invertida);
            if (sel != null && invertida.contains(sel)) {
                listaImgs.getSelectionModel().select(sel);
            }
        };
        recarregarLista.run();

        Button subir = new Button("\u2191");
        subir.setTooltip(new javafx.scene.control.Tooltip("Sobe na pilha (cobre mais)"));
        Button descer = new Button("\u2193");
        descer.setTooltip(new javafx.scene.control.Tooltip("Desce na pilha (fica coberta)"));
        Button remover = new Button("Remover");
        Button loadImg = new Button("Adicionar...");

        Runnable atualizarBotoes = () -> {
            var sel = listaImgs.getSelectionModel().getSelectedItem();
            boolean tem = sel != null;
            int pos = tem ? project.getImages().indexOf(sel) : -1;
            subir.setDisable(!tem || pos == project.getImages().size() - 1);
            descer.setDisable(!tem || pos == 0);
            remover.setDisable(!tem);
        };
        listaImgs.getSelectionModel().selectedItemProperty()
                .addListener((o, a2, b2) -> atualizarBotoes.run());
        atualizarBotoes.run();

        // Mover na lista mexe na ORDEM DO PROJETO, que e' a de pintura.
        java.util.function.IntConsumer mover = passo -> {
            var sel = listaImgs.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            var imgs = project.getImages();
            int i = imgs.indexOf(sel);
            int j = i + passo;
            if (i < 0 || j < 0 || j >= imgs.size()) return;
            imgs.remove(i);
            imgs.add(j, sel);
            if (hooks != null) hooks.refreshLayers();
            recarregarLista.run();
            listaImgs.getSelectionModel().select(sel);
            atualizarBotoes.run();
        };
        subir.setOnAction(e -> mover.accept(+1));
        descer.setOnAction(e -> mover.accept(-1));

        remover.setOnAction(e -> {
            var sel = listaImgs.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            project.getImages().remove(sel);
            if (hooks != null) hooks.refreshLayers();
            recarregarLista.run();
            refreshImg.run();
            atualizarBotoes.run();
        });

        loadImg.setOnAction(e -> {
            if (hooks == null) return;
            hooks.loadImage(() -> {
                refreshImg.run();
                recarregarLista.run();
                atualizarBotoes.run();
            });
        });

        // Qualidade: quanto de cada arquivo entra na memoria. Com varias
        // ortofotos sobrepostas o consumo soma, e o lado cresce ao quadrado.
        ComboBox<com.colmeia.radiomapper.io.ImageLoader.Qualidade> qualImg = new ComboBox<>();
        qualImg.getItems().setAll(com.colmeia.radiomapper.io.ImageLoader.Qualidade.values());
        qualImg.setValue(Settings.imageQuality());
        qualImg.setMaxWidth(Double.MAX_VALUE);

        gView.add(new Separator(), 0, r++, 2, 1);
        gView.add(new Label("Ortofotos carregadas:"), 0, r);
        gView.add(row(loadImg, subir, descer, remover), 1, r++);
        gView.add(listaImgs, 0, r++, 2, 1);
        gView.add(hint("Do topo para a base: a primeira da lista cobre as de baixo onde "
                + "se sobrep\u00f5em. A \u00faltima imagem carregada entra no topo. "
                + "Use as setas para mudar a ordem."), 0, r++, 2, 1);
        gView.add(new Label("Qualidade das imagens:"), 0, r);
        gView.add(row(qualImg), 1, r++);
        gView.add(hint("Maior lado que cada arquivo ocupa na mem\u00f3ria. Dobrar o lado "
                + "quadruplica a mem\u00f3ria, e com v\u00e1rias ortofotos isso soma \u2014 "
                + "baixe aqui se o programa ficar lento. Mudar recarrega os arquivos."),
                0, r++, 2, 1);
        gView.add(imgStatus, 0, r++, 2, 1);

        gView.add(new Label("Sobreposição:"), 0, r);
        gView.add(imgVisible, 1, r++);
        gView.add(imgLocked, 1, r++);
        gView.add(new Label("Opacidade:"), 0, r);
        gView.add(row(imgCurrentOpacity, curOpacityValue), 1, r++);
        gView.add(hint(hasImage
                ? "Vale para a imagem deste projeto. O mesmo controle está no painel "
                  + "flutuante do mapa, para ajustar vendo o resultado."
                : "Este projeto ainda não tem imagem de fundo. Carregue uma em "
                  + "Exibir > Imagem de fundo > Abrir imagem."), 0, r, 2, 1);

        // ------------------------ Sincronização ------------------------
        CheckBox autoSync = new CheckBox("Sincronizar automaticamente");
        autoSync.setSelected(Settings.autoSync());

        Spinner<Integer> interval = intSpinner(
                Settings.MIN_INTERVAL, Settings.MAX_INTERVAL, Settings.autoSyncIntervalSec());
        // Intervalo só faz sentido com auto-sync ligado.
        interval.disableProperty().bind(autoSync.selectedProperty().not());

        GridPane gSync = section();
        r = 0;
        gSync.add(autoSync, 0, r, 2, 1); r++;
        gSync.add(new Label("Intervalo:"), 0, r);
        gSync.add(withSuffix(interval, "segundos"), 1, r); r++;
        gSync.add(hint("Com o pool de sessões SSH, 1 s é viável em redes pequenas. "
                + "Em redes grandes, prefira 10 s ou mais para não saturar os rádios."), 0, r++, 2, 1);

        Spinner<Integer> parallel = intSpinner(1, 128, Settings.probeParallelism());
        Spinner<Integer> probeTimeout = intSpinner(5, 300, Settings.probeTimeoutSec());

        gSync.add(new Separator(), 0, r++, 2, 1);
        gSync.add(new Label("Rádios em paralelo:"), 0, r);
        gSync.add(row(parallel, new Label("por vez")), 1, r++);
        gSync.add(hint("Quantos rádios são sondados ao mesmo tempo. Mais paralelismo "
                + "encurta a rodada numa rede grande; valores muito altos abrem muitas "
                + "conexões SSH de uma vez e podem pesar na rede."), 0, r++, 2, 1);

        gSync.add(new Label("Prazo da rodada:"), 0, r);
        gSync.add(row(probeTimeout, new Label("segundos")), 1, r++);
        gSync.add(hint("Prazo da rodada INTEIRA, não de cada rádio. Rádio que não "
                + "responder dentro dele é marcado como caído e a rodada segue. "
                + "Abaixo de ~23 s, um rádio lento porém vivo pode ser dado como caído, "
                + "porque só a conexão SSH já admite 8 s e o socket, 15 s."), 0, r, 2, 1);

        // ------------------------ Enlaces ------------------------
        Spinner<Integer> threshold = intSpinner(
                Settings.MIN_THRESHOLD, Settings.MAX_THRESHOLD, Settings.signalThresholdDbm());

        GridPane gLink = section();
        r = 0;
        gLink.add(new Label("Sinal mínimo aceitável:"), 0, r);
        gLink.add(withSuffix(threshold, "dBm"), 1, r); r++;
        gLink.add(hint("Enlaces abaixo desse valor aparecem destacados no mapa e "
                + "entram na contagem de fracos na barra de status."), 0, r, 2, 1);

        // ------------------------ GeoTIFF ------------------------
        CheckBox geoAuto = new CheckBox("Posicionar a imagem pelas coordenadas do GeoTIFF");
        geoAuto.setSelected(Settings.geotiffAuto());

        CheckBox geoLock = new CheckBox("Travar a imagem depois de posicionar por georreferência");
        geoLock.setSelected(Settings.geotiffLockAfter());
        geoLock.disableProperty().bind(geoAuto.selectedProperty().not());

        Slider imgOpacity = new Slider(0, 1, Settings.imageDefaultOpacity());
        imgOpacity.setPrefWidth(200);
        imgOpacity.setShowTickMarks(true);
        imgOpacity.setMajorTickUnit(0.25);
        Label opacityValue = new Label();
        opacityValue.setMinWidth(45);
        Runnable showOpacity = () ->
                opacityValue.setText(Math.round(imgOpacity.getValue() * 100) + "%");
        imgOpacity.valueProperty().addListener((o, a, b) -> showOpacity.run());
        showOpacity.run();

        GridPane gImg = section();
        r = 0;
        gImg.add(geoAuto, 0, r++, 2, 1);
        gImg.add(hint("Um GeoTIFF traz as próprias coordenadas. Com isto ligado, a imagem "
                + "cai sozinha no lugar certo do mapa em vez de precisar de encaixe manual. "
                + "Sistemas reconhecidos: geográfico (graus), Web Mercator e UTM "
                + "(WGS84, SIRGAS 2000 e SAD69)."), 0, r++, 2, 1);
        gImg.add(geoLock, 0, r++, 2, 1);
        gImg.add(hint("A posição veio do arquivo e está certa — travar evita desfazê-la "
                + "com um arrasto distraído. Dá para destravar em Exibir > Imagem de fundo."),
                0, r++, 2, 1);
        gImg.add(new Label("Opacidade inicial:"), 0, r);
        gImg.add(row(imgOpacity, opacityValue), 1, r++);
        gImg.add(hint("Usada ao carregar uma imagem sobre um mapa base. Depois dá para "
                + "ajustar ao vivo no controle deslizante do painel do mapa."), 0, r++, 2, 1);

        gImg.add(new Separator(), 0, r++, 2, 1);
        gImg.add(title("Fontes de altitude"), 0, r++, 2, 1);
        gImg.add(hint("A altitude é procurada nesta ordem, e a primeira que tiver dado "
                + "no ponto responde:\n"
                + "   1. Levantamento .PLY  (resolução centimétrica, o terreno de hoje)\n"
                + "   2. GeoTIFF de elevação  (MDT, se o arquivo tiver altitude)\n"
                + "   3. Relevo global do mapa  (~30 m, cobre o planeta inteiro)"),
                0, r++, 2, 1);

        // Levantamento .PLY: a fonte de maior prioridade da cadeia.
        Label plyStatus = new Label();
        plyStatus.setWrapText(true);
        plyStatus.setMaxWidth(430);
        Runnable refreshPly = () -> {
            var ply = hooks == null ? null : hooks.currentPly();
            plyStatus.setText(ply == null
                    ? "Nenhum levantamento carregado."
                    : String.format("%s — %d pontos, grade %dx%d (~%.2f m), altura %.1f..%.1f m",
                            ply.file().getName(), ply.pointsUsed(), ply.cols(), ply.rows(),
                            ply.resolutionMeters(), ply.minZ(), ply.maxZ()));
            plyStatus.setStyle(ply == null ? "-fx-text-fill: #888; -fx-font-size: 11;"
                                           : "-fx-text-fill: #2e7d32; -fx-font-size: 11;");
        };
        refreshPly.run();

        Button loadPly = new Button("Carregar .PLY...");
        loadPly.setOnAction(e -> {
            if (hooks != null) hooks.loadPly(refreshPly);
        });
        Button clearPly = new Button("Remover");
        clearPly.setOnAction(e -> {
            if (hooks != null) hooks.clearPly();
            refreshPly.run();
        });

        gImg.add(new Label("Levantamento .PLY:"), 0, r);
        gImg.add(row(loadPly, clearPly), 1, r++);
        gImg.add(plyStatus, 0, r++, 2, 1);
        gImg.add(hint("Nuvem de pontos ou malha de drone. É a fonte de maior prioridade: "
                + "resolução centimétrica e o terreno como está hoje, incluindo árvores e "
                + "construções — que é o que de fato obstrui um enlace."), 0, r++, 2, 1);
        gImg.add(new Separator(), 0, r++, 2, 1);

        CheckBox terrain = new CheckBox("Usar o relevo global do mapa como último recurso");
        terrain.setSelected(Settings.terrainTilesEnabled());
        gImg.add(terrain, 0, r++, 2, 1);
        gImg.add(hint("Os mapas base (OSM, satélite, relevo) são figuras e não carregam "
                + "altitude — o OpenTopoMap desenha curvas de nível, mas são pixels. "
                + "Esta opção usa um serviço separado de tiles de terreno, onde a altitude "
                + "vem codificada nas cores. É gratuito e sem chave, mas faz requisições "
                + "de rede; desligue para trabalhar totalmente offline."), 0, r, 2, 1);

        // ------------------------ Inicialização ------------------------
        CheckBox restore = new CheckBox("Reabrir o último projeto ao iniciar");
        restore.setSelected(Settings.restoreLastProject());

        String last = Settings.lastProject();
        Label lastLabel = new Label(last == null || last.isBlank() ? "(nenhum)" : last);
        lastLabel.setWrapText(true);
        lastLabel.setMaxWidth(300);

        // "Esquecer" fica pendente até o OK, para o Cancelar poder desfazer.
        boolean[] forget = { false };
        Button forgetBtn = new Button("Esquecer");
        forgetBtn.setDisable(last == null || last.isBlank());
        forgetBtn.setOnAction(e -> {
            forget[0] = true;
            lastLabel.setText("(será esquecido ao confirmar)");
            forgetBtn.setDisable(true);
        });

        GridPane gStart = section();
        r = 0;
        gStart.add(restore, 0, r, 2, 1); r++;
        gStart.add(new Label("Último projeto:"), 0, r);
        gStart.add(row(lastLabel, forgetBtn), 1, r);

        // ------------------------ Log ------------------------
        File logFile = Log.getLogFile();
        Label logLabel = new Label(logFile == null ? "(indisponível)" : logFile.getAbsolutePath());
        logLabel.setWrapText(true);
        logLabel.setMaxWidth(300);

        Button openLogDir = new Button("Abrir pasta");
        openLogDir.setDisable(logFile == null);
        openLogDir.setOnAction(e -> openLogFolder());

        GridPane gLog = section();
        gLog.add(new Label("Arquivo:"), 0, 0);
        gLog.add(row(logLabel, openLogDir), 1, 0);

        // ------------------------ Montagem ------------------------
        // Abas, e não uma coluna só: com seis seções empilhadas o diálogo
        // passava da altura da tela, escondia parte do conteúdo e empurrava os
        // botões OK/Cancelar para fora — ficava impossível de fechar.
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("Exibição", gView),
                tab("Sincronização", gSync),
                tab("Enlaces", gLink),
                tab("Relevo", gImg),
                tab("Inicialização", gStart),
                tab("Log", gLog));

        // Tamanho fixo e modesto: o conteúdo rola dentro da aba, a janela não
        // cresce, e os botões continuam sempre visíveis.
        tabs.setPrefSize(600, 400);
        dlg.getDialogPane().setContent(tabs);
        dlg.getDialogPane().setPrefSize(620, 480);

        // "Restaurar padrões" repovoa os controles sem fechar o diálogo: o
        // usuário ainda precisa confirmar no OK.
        Button resetBtn = (Button) dlg.getDialogPane().lookupButton(reset);
        resetBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            ev.consume();
            autoSync.setSelected(Settings.DEF_AUTO_SYNC);
            interval.getValueFactory().setValue(Settings.DEF_AUTO_SYNC_INTERVAL);
            threshold.getValueFactory().setValue(Settings.DEF_SIGNAL_THRESHOLD);
            parallel.getValueFactory().setValue(Settings.DEF_PROBE_PARALLELISM);
            probeTimeout.getValueFactory().setValue(Settings.DEF_PROBE_TIMEOUT);
            beams.setSelected(Settings.DEF_BEAMS_VISIBLE);
            restore.setSelected(Settings.DEF_RESTORE_LAST_PROJECT);
            geoAuto.setSelected(Settings.DEF_GEOTIFF_AUTO);
            geoLock.setSelected(Settings.DEF_GEOTIFF_LOCK);
            imgOpacity.setValue(Settings.DEF_IMAGE_OPACITY);
        });

        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return new Outcome(false, project.getBasemap());
        }

        // Spinner editável não confirma sozinho o texto digitado.
        commitEditor(interval);
        commitEditor(threshold);
        commitEditor(parallel);
        commitEditor(probeTimeout);

        Settings.setAutoSync(autoSync.isSelected());
        Settings.setAutoSyncIntervalSec(interval.getValue());
        Settings.setSignalThresholdDbm(threshold.getValue());
        Settings.setProbeParallelism(parallel.getValue());
        Settings.setProbeTimeoutSec(probeTimeout.getValue());
        Settings.setBeamsVisible(beams.isSelected());
        Settings.setRestoreLastProject(restore.isSelected());
        Settings.setGeotiffAuto(geoAuto.isSelected());
        Settings.setGeotiffLockAfter(geoLock.isSelected());
        Settings.setImageDefaultOpacity(imgOpacity.getValue());
        Settings.setTerrainTilesEnabled(terrain.isSelected());
        com.colmeia.radiomapper.geo.TerrainTiles.INSTANCE.setEnabled(terrain.isSelected());
        if (forget[0]) Settings.setLastProject("");

        // Estado do projeto. O mapa base NÃO é gravado aqui: vai como pedido
        // no Outcome, para o controller poder avisar sobre as coordenadas.
        // Trocar a qualidade so vale relendo os arquivos: a subamostragem
        // acontece na LEITURA, e a imagem em memoria ja veio no tamanho antigo.
        var qualAntes = Settings.imageQuality();
        if (qualImg.getValue() != null && qualImg.getValue() != qualAntes) {
            Settings.setImageQuality(qualImg.getValue());
            if (hooks != null) hooks.reloadImages();
        }

        ov.setVisible(imgVisible.isSelected());
        ov.setLocked(imgLocked.isSelected());
        ov.setOpacity(imgCurrentOpacity.getValue());
        if (mpp.getValue() != null) project.setMetersPerPixel(mpp.getValue());

        Log.info("Configuracoes salvas: auto=%s/%ds, limite=%d dBm, feixes=%s, restaurar=%s%s",
                autoSync.isSelected(), interval.getValue(), threshold.getValue(),
                beams.isSelected(), restore.isSelected(),
                forget[0] ? ", ultimo projeto esquecido" : "");

        TileSource chosen = basemap.getValue() == null ? TileSource.NONE : basemap.getValue();
        return new Outcome(true, chosen);
    }

    // ------------------------ Helpers ------------------------

    /** Uma aba com rolagem própria, para seção longa não estourar a janela. */
    private static Tab tab(String title, javafx.scene.Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent;");
        return new Tab(title, sp);
    }

    private static GridPane section() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(4, 0, 8, 12));
        return g;
    }

    private static Label title(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private static Label hint(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        l.setWrapText(true);
        l.setMaxWidth(460);
        return l;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int value) {
        Spinner<Integer> sp = new Spinner<>();
        sp.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value, 1));
        sp.setEditable(true);
        sp.setPrefWidth(110);
        return sp;
    }

    private static HBox withSuffix(Spinner<Integer> sp, String suffix) {
        return row(sp, new Label(suffix));
    }

    private static HBox row(javafx.scene.Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Aplica o texto digitado no editor do spinner; se for inválido, restaura o valor atual. */
    private static void commitEditor(Spinner<Integer> sp) {
        try {
            sp.getValueFactory().setValue(Integer.parseInt(sp.getEditor().getText().trim()));
        } catch (NumberFormatException | NullPointerException ex) {
            sp.getEditor().setText(String.valueOf(sp.getValue()));
        }
    }

    private static void openLogFolder() {
        File f = Log.getLogFile();
        if (f == null) return;
        try {
            Desktop.getDesktop().open(f.getParentFile());
        } catch (Exception ex) {
            Log.warn("Nao consegui abrir a pasta de log: %s", ex.getMessage());
        }
    }
}
