package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.ElevationChain;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.TerrainTiles;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.rf.ApAim;
import com.colmeia.radiomapper.rf.BeamCoverage;
import com.colmeia.radiomapper.rf.LinkBudget;
import com.colmeia.radiomapper.rf.LinkPeer;
import com.colmeia.radiomapper.rf.StationAim;
import com.colmeia.radiomapper.util.Log;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Até onde o rádio alcança, recalculado enquanto se mexe nos parâmetros.
 *
 * <h3>O que mudou e por quê</h3>
 * A primeira versão perguntava tudo e recalculava do zero a cada abertura,
 * buscando relevo de um quadrado do tamanho do teto de busca — dezenas de
 * vezes maior que a fatia que um setor realmente varre. Abrir a tela custava
 * segundos mesmo quando nada havia mudado. Três correções:
 *
 * <ul>
 *   <li><b>Só busca o relevo que vai olhar.</b> O alcance pelo orçamento de
 *       enlace é conta fechada, então dá para saber o raio ANTES de varrer; e
 *       um setor só precisa da caixa do seu arco.</li>
 *   <li><b>Não repete o que já sabe.</b> Mesma assinatura de entrada, mesmo
 *       lobo: o resultado anterior volta na hora, sem tocar em rede.</li>
 *   <li><b>Os parâmetros do rádio ficam aqui.</b> Mexer na potência, na altura
 *       ou na inclinação redesenha sozinho — que é o trabalho de planejamento,
 *       e antes exigia fechar, editar o rádio e abrir de novo.</li>
 * </ul>
 *
 * <h3>Quem está do outro lado</h3>
 * Alcance é propriedade de um par, não de uma antena. Mas na maior parte dos
 * casos o projeto já sabe quem é o par — a estação tem o AP no cadastro, o
 * ponto a ponto tem o outro lado do enlace — e perguntar o que já está lá era
 * fazer digitar de novo, com chance de digitar errado. Ver {@link LinkPeer}.
 *
 * <h3>Hipótese e projeto</h3>
 * Os campos editam uma CÓPIA do rádio. O mapa mostra a hipótese; o cadastro só
 * muda para quem clicar em aplicar. É a mesma separação do arraste de torre no
 * perfil.
 */
public final class BeamSimDialog {

    private BeamSimDialog() {}

    public interface OnSimulated {
        /**
         * @param comRelevo false quando a simulação não teve altitude com que
         *                  trabalhar — o mapa desenha diferente nesse caso
         * @param faixas    recortes por nível de sinal, do melhor para o pior;
         *                  cada um com o dBm na posição 0 e o polígono adiante
         */
        void accept(String radioId, BeamCoverage.Cobertura cobertura, boolean comRelevo);
    }

    /** Guarda o último resultado por rádio, para não recalcular à toa. */
    public interface Cache {
        BeamCoverage.Result get(String radioId, String assinatura);

        /**
         * @param prm com que parâmetros se varreu — guardar isto é o que
         *            permite refazer a mesma varredura depois, quando o
         *            rádio muda de lugar e ninguém está com esta janela
         *            aberta para reinformar nada
         */
        void put(String radioId, String assinatura, BeamCoverage.Result r,
                 BeamCoverage.Params prm);
    }

    /** Espera este tanto depois da última mudança antes de recalcular. */
    private static final Duration ESPERA = Duration.millis(350);

    /** Liga/desliga a pintura por qualidade — nao recalcula nada. */
    public interface OnQualityToggle {
        void accept(boolean ligado);
    }

    /**
     * Os parametros foram gravados no radio.
     *
     * A janela do alcance e' uma hipotese ate alguem clicar em aplicar; dali
     * em diante o radio mudou de verdade, e quem desenha a partir dele -- a
     * pre-visualizacao de enlace, o feixe no mapa, as listas -- precisa
     * saber. Sem este aviso a mudanca so aparecia ao fechar e reabrir o
     * perfil, e quem estava comparando duas alturas via o desenho da anterior.
     */
    public interface OnApplied {
        void accept(Radio radio);
    }

    public static void show(Window owner, Radio radio, NetworkPoint p, Project project,
                            ElevationChain elevation, boolean mapMode,
                            Cache cache, OnSimulated onSimulated) {
        show(owner, radio, p, project, elevation, mapMode, cache, onSimulated, null, null);
    }

    public static void show(Window owner, Radio radio, NetworkPoint p, Project project,
                            ElevationChain elevation, boolean mapMode,
                            Cache cache, OnSimulated onSimulated,
                            OnQualityToggle onQuality) {
        show(owner, radio, p, project, elevation, mapMode, cache, onSimulated,
             onQuality, null);
    }

    public static void show(Window owner, Radio radio, NetworkPoint p, Project project,
                            ElevationChain elevation, boolean mapMode,
                            Cache cache, OnSimulated onSimulated,
                            OnQualityToggle onQuality, OnApplied onApplied) {

        // Cópia: tudo que se mexe aqui é hipótese até alguém aplicar.
        Radio r = copiar(radio);
        // Passa o relevo: sem ele nao da para descontar o solo de um par
        // cadastrado com altitude absoluta.
        LinkPeer.Peer par = LinkPeer.of(radio, project, elevation);

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Alcance simulado");
        dlg.setHeaderText(nome(radio) + "  ·  " + p.getName() + "  ·  " + radio.getRole());
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        // Janela solta, e nao modal: mexer no alcance e' comparar com o mapa,
        // e com a janela travando o resto era preciso fechar, arrastar o mapa
        // e abrir de novo a cada olhada. Continua pertencendo a janela
        // principal (fecha junto, fica por cima), mas nao bloqueia.
        dlg.initModality(javafx.stage.Modality.NONE);
        dlg.setResizable(true);

        // ------------------------ Parâmetros do rádio ------------------------
        Spinner<Double> potencia = Spinners.decimal(-10, 40, r.getTxPowerDbm(), 1, 1);
        Spinner<Double> ganho = Spinners.decimal(0, 50, r.getAntennaGainDbi(), 0.5, 1);
        Spinner<Double> altura = Spinners.decimal(-500, 9000, r.getAntennaHeightM(), 1, 1);
        Spinner<Double> azimute = Spinners.decimal(0, 360, r.getBeamAzimuthDeg(), 5, 1);
        Spinner<Double> aberturaH = Spinners.decimal(0, 360, r.getBeamWidthDeg(), 5, 1);
        Spinner<Double> aberturaV = Spinners.decimal(0, 180, r.getBeamVerticalWidthDeg(), 1, 1);
        Spinner<Double> tilt = Spinners.decimal(-90, 90, r.getBeamTiltDeg(), 0.5, 1);

        // Como ler o numero da altura. Faltava aqui, e e' justamente aqui que
        // mais pesa: esta tela existe para experimentar altura contra o
        // terreno, e 250 lido como mastro poe a antena 250 m acima de um morro
        // que ja tem 250 -- o alcance sai o dobro do real. Quem tem a cota de
        // GPS precisa poder dize-lo sem sair para o cadastro.
        ComboBox<com.colmeia.radiomapper.model.AltitudeMode> modoAlt = new ComboBox<>();
        modoAlt.getItems().setAll(com.colmeia.radiomapper.model.AltitudeMode.values());
        modoAlt.setValue(r.getAltitudeMode());
        modoAlt.setMaxWidth(Double.MAX_VALUE);

        Label rotAltura = new Label("Altura:");
        Label dicaAlt = new Label();
        dicaAlt.setWrapText(true);
        dicaAlt.setMaxWidth(500);
        dicaAlt.setStyle("-fx-text-fill: #8a93a0; -fx-font-size: 11;");

        Runnable ajustarModo = () -> {
            boolean abs = modoAlt.getValue()
                    == com.colmeia.radiomapper.model.AltitudeMode.ABSOLUTA;
            rotAltura.setText(abs ? "Altitude:" : "Altura:");
            // Mastro nao desce abaixo do solo; cota pode, em terreno litoraneo.
            Spinners.setRange(altura, abs ? -500 : 0, 9000);
            dicaAlt.setText(abs
                    ? "O n\u00famero j\u00e1 \u00e9 a cota do centro da antena; o relevo "
                      + "N\u00c3O \u00e9 somado. Use quando a cota vier de GPS ou "
                      + "levantamento \u2014 e quando o relevo do projeto estiver suspeito."
                    : "Medida do solo at\u00e9 o centro da antena. O programa soma a "
                      + "altitude do terreno sob o ponto.");
        };
        modoAlt.valueProperty().addListener((o, a, b) -> ajustarModo.run());
        ajustarModo.run();

        GridPane gr = new GridPane();
        gr.setHgap(8); gr.setVgap(5);
        int lr = 0;
        gr.add(new Label("Potência:"), 0, lr); gr.add(campo(potencia, "dBm"), 1, lr);
        gr.add(new Label("Antena:"), 2, lr); gr.add(campo(ganho, "dBi"), 3, lr++);
        gr.add(rotAltura, 0, lr); gr.add(campo(altura, "m"), 1, lr);
        gr.add(new Label("Azimute:"), 2, lr); gr.add(campo(azimute, "°"), 3, lr++);
        gr.add(new Label("Medida:"), 0, lr); gr.add(modoAlt, 1, lr, 3, 1); lr++;
        gr.add(dicaAlt, 0, lr, 4, 1); lr++;
        gr.add(new Label("Abertura H:"), 0, lr); gr.add(campo(aberturaH, "°"), 1, lr);
        gr.add(new Label("Abertura V:"), 2, lr); gr.add(campo(aberturaV, "°"), 3, lr++);
        gr.add(new Label("Inclinação:"), 0, lr); gr.add(campo(tilt, "°"), 1, lr++);

        Label avisoAbertura = new Label();
        avisoAbertura.setWrapText(true);
        avisoAbertura.setMaxWidth(500);
        avisoAbertura.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
        avisoAbertura.setManaged(false);
        avisoAbertura.setVisible(false);

        // ------------------------ A outra ponta ------------------------
        Spinner<Double> minRssi = Spinners.decimal(-110, -30, -70, 1, 0);
        Spinner<Double> ganhoLonge = Spinners.decimal(0, 50, par.gainDbi(), 0.5, 1);
        Spinner<Double> caboLonge = Spinners.decimal(0, 20, par.cableDb(), 0.1, 1);
        // A altura do receptor e' o que MAIS mexe no alcance quando ha morro:
        // ela decide o que enxerga por cima dele. Assumir 5 m num enlace entre
        // torres de 30 m fazia a simulacao parar bem antes do enlace que o
        // perfil mostra fechando — as duas telas discordando por suposicao, nao
        // por calculo.
        Spinner<Double> alturaRx = Spinners.decimal(0, 200, par.alturaM(), 1, 0);
        Spinner<Double> tetoKm = Spinners.decimal(0.5, 120, 30, 1, 1);
        CheckBox porQualidade = new CheckBox("Colorir por qualidade do sinal");
        porQualidade.setSelected(com.colmeia.radiomapper.util.Settings.simBeamQuality());
        porQualidade.setTooltip(new Tooltip("Verde no sinal forte, esquentando ate "
                + "vermelho na borda. Desligado, o lobo sai em cor unica."));

        CheckBox usarRelevo = new CheckBox("Cortar no terreno (visada)");
        usarRelevo.setSelected(elevation.hasAny());
        usarRelevo.setDisable(!elevation.hasAny());
        if (!elevation.hasAny()) usarRelevo.setText("Cortar no terreno — sem fonte de relevo");

        Label dePar = new Label((par.real()
                ? String.format("Do projeto: %s (%.0f dBi, antena a %.0f m) — ",
                        par.nome(), par.gainDbi(), par.alturaM())
                : "") + par.origem());
        dePar.setWrapText(true);
        dePar.setMaxWidth(500);
        dePar.setStyle(par.real() ? "-fx-text-fill: #2e7d32; -fx-font-size: 11;"
                                  : "-fx-text-fill: #666; -fx-font-size: 11;");

        GridPane go = new GridPane();
        go.setHgap(8); go.setVgap(5);
        int lo = 0;
        go.add(new Label("Sinal mínimo:"), 0, lo); go.add(campo(minRssi, "dBm"), 1, lo);
        go.add(new Label("Antena do par:"), 2, lo); go.add(campo(ganhoLonge, "dBi"), 3, lo++);
        go.add(new Label("Cabo do par:"), 0, lo); go.add(campo(caboLonge, "dB"), 1, lo);
        go.add(new Label("Altura do rx:"), 2, lo); go.add(campo(alturaRx, "m"), 3, lo++);
        go.add(new Label("Não passar de:"), 0, lo); go.add(campo(tetoKm, "km"), 1, lo);
        go.add(usarRelevo, 2, lo++, 2, 1);
        go.add(porQualidade, 0, lo++, 4, 1);

        TextArea saida = new TextArea();
        saida.setEditable(false);
        // Onze linhas: a recomendação de apontamento tem quatro campos
        // com o porquê de cada um, e com sete o leitor precisava rolar para
        // ver se sobrou conselho embaixo.
        saida.setPrefRowCount(11);
        saida.setWrapText(true);
        saida.setStyle("-fx-font-family: 'Consolas','Menlo','Monospaced'; -fx-font-size: 11;");

        Button aplicar = new Button("Aplicar ao rádio");
        aplicar.setDisable(true);
        Button limpar = new Button("Tirar do mapa");

        Label aviso = new Label("Espaço livre e visada geométrica: sem difração, vegetação, "
                + "chuva ou exigência de Fresnel livre. Onde isto diz que não chega, não "
                + "chega; onde diz que chega, ainda pode render mal.");
        aviso.setWrapText(true);
        aviso.setMaxWidth(500);
        aviso.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");

        // ------------------------ Motor ------------------------
        double k = Mercator.groundScaleAt(Mercator.latOfWorldY(p.getY()));
        double worldPerM = k <= 0 ? 1 : 1 / k;

        final boolean[] ocupado = { false };
        final boolean[] refazer = { false };

        // Leitura AO VIVO: le o texto e nao mexe nele. Esta tela recalcula
        // sozinha 350 ms depois da ultima tecla, e um commit aqui reescreveria
        // o campo no meio da digitacao -- ver Spinners.lido.
        Runnable lerCampos = () -> {
            r.setTxPowerDbm(Spinners.lido(potencia, r.getTxPowerDbm()));
            r.setAntennaGainDbi(Spinners.lido(ganho, r.getAntennaGainDbi()));
            // O modo antes da altura: getAntennaHeightM() aplica piso zero
            // quando e' mastro, e gravar a altura com o modo velho podia zerar
            // uma cota negativa antes de o modo novo entrar.
            if (modoAlt.getValue() != null) r.setAltitudeMode(modoAlt.getValue());
            r.setAntennaHeightM(Spinners.lido(altura, r.getAntennaHeightM()));
            r.setBeamAzimuthDeg(Spinners.lido(azimute, r.getBeamAzimuthDeg()));
            r.setBeamWidthDeg(Spinners.lido(aberturaH, r.getBeamWidthDeg()));
            r.setBeamVerticalWidthDeg(Spinners.lido(aberturaV, r.getBeamVerticalWidthDeg()));
            r.setBeamTiltDeg(Spinners.lido(tilt, r.getBeamTiltDeg()));
        };

        Runnable[] simular = new Runnable[1];
        simular[0] = () -> {
            if (!mapMode) {
                saida.setText("A simulação precisa de um mapa base: sem ele as coordenadas "
                        + "dos pontos são pixels da imagem, e não há metro de chão para medir.");
                return;
            }
            lerCampos.run();
            if (r.getFrequencyMhz() <= 0) {
                saida.setText("Este rádio não tem frequência informada. Sem ela não há perda "
                        + "de percurso para calcular — preencha em Editar rádio.");
                return;
            }

            boolean varreTudo = r.getBeamWidthDeg() <= 0 || r.getBeamWidthDeg() >= 360;
            avisoAbertura.setText(varreTudo
                    ? "Abertura horizontal em " + (r.getBeamWidthDeg() <= 0 ? "zero" : "360°")
                      + ": a varredura cobre todas as direções. Se a antena é setorial, "
                      + "informe a abertura acima — corrige o desenho e reduz muito o relevo "
                      + "que precisa ser buscado."
                    : "");
            avisoAbertura.setManaged(varreTudo);
            avisoAbertura.setVisible(varreTudo);

            BeamCoverage.Params prm = new BeamCoverage.Params(
                    Spinners.lido(minRssi, -70),
                    Spinners.lido(ganhoLonge, par.gainDbi()),
                    Spinners.lido(caboLonge, par.cableDb()),
                    Spinners.lido(alturaRx, par.alturaM()),
                    Spinners.lido(tetoKm, 30) * 1000,
                    usarRelevo.isSelected());

            String assinatura = BeamCoverage.signature(r, prm, p.getX(), p.getY());
            BeamCoverage.Result guardado = cache == null ? null : cache.get(radio.getId(), assinatura);
            if (guardado != null) {
                // Nada mudou: devolve o que já estava calculado, sem tocar em rede.
                saida.setText(relatorio(radio, r, guardado, prm, par, true));
                onSimulated.accept(radio.getId(),
                        guardado.valid() ? guardado.cobertura() : null, guardado.terrainUsed());
                aplicar.setDisable(!mudou(radio, r));
                return;
            }

            // Já há uma varredura em curso: marca para refazer no fim, em vez
            // de empilhar threads a cada tecla.
            if (ocupado[0]) { refazer[0] = true; return; }
            ocupado[0] = true;
            saida.setText("Varrendo o terreno...");

            Task<BeamCoverage.Result> t = new Task<>() {
                @Override protected BeamCoverage.Result call() {
                    if (prm.useTerrain()) {
                        // Só o que a varredura vai olhar. Antes era um quadrado
                        // do tamanho do teto de busca — 13x13 tiles de relevo a
                        // cada abertura, mesmo para um setor estreito.
                        double[] bb = BeamCoverage.sweepBoundsWorld(r, prm, p.getX(), p.getY(), worldPerM);
                        if (bb != null) {
                            TerrainTiles.INSTANCE.prefetch(bb[0], bb[1], bb[2], bb[3], 20000);
                        }
                    }
                    return BeamCoverage.simulate(r, p.getX(), p.getY(), elevation, worldPerM, prm);
                }
            };
            t.setOnSucceeded(ev -> {
                ocupado[0] = false;
                BeamCoverage.Result res = t.getValue();
                if (cache != null) cache.put(radio.getId(), assinatura, res, prm);
                saida.setText(relatorio(radio, r, res, prm, par, false));
                onSimulated.accept(radio.getId(),
                        res.valid() ? res.cobertura() : null, res.terrainUsed());
                aplicar.setDisable(!mudou(radio, r));
                if (refazer[0]) { refazer[0] = false; simular[0].run(); }
            });
            t.setOnFailed(ev -> {
                ocupado[0] = false;
                Throwable ex = t.getException();
                saida.setText("Falha ao simular: " + (ex == null ? "erro desconhecido" : ex.getMessage()));
                Log.warn("Falha ao simular alcance de %s: %s", nome(radio),
                        ex == null ? "?" : ex.getMessage());
            });
            Thread th = new Thread(t, "beam-sim");
            th.setDaemon(true);
            th.start();
        };

        // Espera o usuário parar de mexer: recalcular a cada tecla, numa
        // varredura que lê milhares de pontos, deixaria o campo travado.
        PauseTransition debounce = new PauseTransition(ESPERA);
        debounce.setOnFinished(e -> simular[0].run());

        for (Spinner<Double> sp : java.util.List.of(potencia, ganho, altura, azimute,
                aberturaH, aberturaV, tilt, minRssi, ganhoLonge, caboLonge, alturaRx, tetoKm)) {
            sp.valueProperty().addListener((o, a, b) -> debounce.playFromStart());
            sp.getEditor().textProperty().addListener((o, a, b) -> debounce.playFromStart());
        }
        usarRelevo.selectedProperty().addListener((o, a, b) -> simular[0].run());

        // Trocar a cor nao muda a conta: o mapa so repinta o que ja tem.
        porQualidade.selectedProperty().addListener((o, a, b) -> {
            com.colmeia.radiomapper.util.Settings.setSimBeamQuality(b);
            if (onQuality != null) onQuality.accept(b);
        });

        // ------------------------ Recomendacao ------------------------
        // Duas perguntas diferentes atras do mesmo botao, porque sao a MESMA
        // pergunta do ponto de vista de quem usa: "como melhoro este radio?".
        // Para um AP isso e' area coberta; para uma estacao, que nao cobre
        // area nenhuma, e' o apontamento para o AP dela.
        boolean ehEstacao = radio.getRole().isStation();

        Button recomendar = new Button(ehEstacao ? "Recomendar apontamento"
                                                 : "Recomendar cen\u00e1rio");
        recomendar.setTooltip(new javafx.scene.control.Tooltip(ehEstacao
                ? "Calcula pot\u00eancia, azimute, inclina\u00e7\u00e3o e altura para "
                  + "esta esta\u00e7\u00e3o pegar o melhor sinal do AP dela."
                : "Varre o terreno com v\u00e1rias alturas, azimutes e "
                  + "inclina\u00e7\u00f5es e diz qual arranjo cobre mais ch\u00e3o. "
                  + "Leva dezenas de varreduras \u2014 uns 20 s."));
        Button usar = new Button(ehEstacao ? "Usar o recomendado" : "Usar a recomendada");
        usar.setDisable(true);

        final double[] recomendada = { Double.NaN };
        final StationAim.Plano[] plano = { null };
        final ApAim.Plano[] planoAp = { null };

        recomendar.setOnAction(e -> {
            if (!mapMode) {
                saida.setText("A recomenda\u00e7\u00e3o precisa de um mapa base.");
                return;
            }
            if (ehEstacao) {
                lerCampos.run();
                recomendar.setDisable(true);
                usar.setDisable(true);
                saida.setText("Levantando o terreno at\u00e9 o AP...");
                Task<StationAim.Plano> tp = new Task<>() {
                    @Override protected StationAim.Plano call() {
                        return StationAim.planejar(r, p, project, elevation);
                    }
                };
                tp.setOnSucceeded(ev -> {
                    recomendar.setDisable(false);
                    StationAim.Plano pl = tp.getValue();
                    plano[0] = pl.vale() ? pl : null;
                    usar.setDisable(plano[0] == null || !pl.temMudanca());
                    saida.setText(pl.vale() ? relatorioMira(pl) : pl.nota());
                });
                tp.setOnFailed(ev -> {
                    recomendar.setDisable(false);
                    Throwable ex = tp.getException();
                    saida.setText("Falha ao recomendar: "
                            + (ex == null ? "erro desconhecido" : ex.getMessage()));
                });
                Thread thp = new Thread(tp, "mira-estacao");
                thp.setDaemon(true);
                thp.start();
                return;
            }
            lerCampos.run();
            recomendar.setDisable(true);
            usar.setDisable(true);
            saida.setText("Varrendo o cen\u00e1rio... cada teste refaz a varredura "
                    + "inteira do terreno, e s\u00e3o dezenas.");

            BeamCoverage.Params prmR = new BeamCoverage.Params(
                    Spinners.lido(minRssi, -70),
                    Spinners.lido(ganhoLonge, par.gainDbi()),
                    Spinners.lido(caboLonge, par.cableDb()),
                    Spinners.lido(alturaRx, par.alturaM()),
                    Spinners.lido(tetoKm, 30) * 1000,
                    usarRelevo.isSelected());
            Task<ApAim.Plano> t = new Task<>() {
                @Override protected ApAim.Plano call() {
                    return ApAim.planejar(r, p.getX(), p.getY(), elevation, worldPerM,
                            prmR, 60, par.origem(),
                            // A varredura roda fora da thread do JavaFX: o
                            // recado tem que voltar para ela antes de tocar
                            // na tela.
                            msg -> Platform.runLater(() -> saida.setText(msg)));
                }
            };
            t.setOnSucceeded(ev -> {
                recomendar.setDisable(false);
                ApAim.Plano pl = t.getValue();
                planoAp[0] = pl.vale() ? pl : null;
                usar.setDisable(planoAp[0] == null || !pl.temMudanca());
                saida.setText(pl.vale() ? relatorioCenario(pl) : pl.nota());
            });
            t.setOnFailed(ev -> {
                recomendar.setDisable(false);
                Throwable ex = t.getException();
                saida.setText("Falha ao recomendar: "
                        + (ex == null ? "erro desconhecido" : ex.getMessage()));
            });
            Thread th = new Thread(t, "cenario-ap");
            th.setDaemon(true);
            th.start();
        });

        usar.setOnAction(e -> {
            if (planoAp[0] != null) {
                for (ApAim.Eixo x : planoAp[0].eixos()) {
                    if (!x.muda()) continue;
                    if (x.campo().startsWith("Altura")) {
                        altura.getValueFactory().setValue(x.melhor());
                    } else if (x.campo().startsWith("Azimute")) {
                        azimute.getValueFactory().setValue(x.melhor());
                    } else if (x.campo().startsWith("Inclina")) {
                        tilt.getValueFactory().setValue(x.melhor());
                    }
                }
                usar.setDisable(true);
                return;
            }
            if (plano[0] != null) {
                StationAim.Plano pl = plano[0];
                potencia.getValueFactory().setValue(pl.potenciaDbm());
                azimute.getValueFactory().setValue(pl.azimuteDeg());
                tilt.getValueFactory().setValue(pl.inclinacaoDeg());
                if (!pl.semRelevo()) altura.getValueFactory().setValue(pl.alturaM());
                usar.setDisable(true);
                return;
            }
            if (Double.isNaN(recomendada[0])) return;
            altura.getValueFactory().setValue(recomendada[0]);
            usar.setDisable(true);
            // Mexer na altura ja dispara nova simulacao pelo mesmo caminho de
            // qualquer outro campo; nao ha nada de especial a fazer aqui.
        });

        aplicar.setOnAction(e -> {
            lerCampos.run();
            copiarPara(r, radio);
            aplicar.setDisable(true);
            saida.setText("Parâmetros gravados no rádio.\n\n" + saida.getText());
            Log.info("Parametros de %s alterados pela simulacao de alcance", nome(radio));
            // O rádio mudou de verdade: quem desenha a partir dele precisa
            // redesenhar agora, e não só quando esta janela fechar.
            if (onApplied != null) onApplied.accept(radio);
        });

        limpar.setOnAction(e -> {
            onSimulated.accept(radio.getId(), null, false);
            saida.setText("Lobo removido do mapa.");
        });

        VBox box = new VBox(9,
                new Label("Parâmetros do rádio (hipótese — o mapa acompanha):"), gr, avisoAbertura,
                new Separator(),
                new Label("Do outro lado:"), go, dePar,
                new Separator(),
                new HBox(8, aplicar, limpar, recomendar, usar), saida, aviso);
        box.setPadding(new Insets(12));
        box.setPrefWidth(580);
        dlg.getDialogPane().setContent(box);

        Platform.runLater(simular[0]);
        // show(), nao showAndWait(): esperar aqui seria bloquear o mapa de
        // novo, so que pelo lado de ca.
        dlg.show();
    }

    // ------------------------ Apoio ------------------------

    /**
     * O plano de cen\u00e1rio de um AP em texto.
     *
     * Mostra a \u00e1rea ao lado de cada campo porque \u00e9 ela que justifica
     * a sugest\u00e3o: "gire para 70\u00b0" n\u00e3o convence ningu\u00e9m a
     * subir na torre; "gire para 70\u00b0 e cubra 16 km\u00b2 a mais" convence
     * \u2014 e d\u00e1 para discordar com n\u00famero na m\u00e3o.
     */
    private static String relatorioCenario(ApAim.Plano pl) {
        StringBuilder sb = new StringBuilder();
        sb.append("O QUE O TERRENO RECOMENDA PARA ESTE AP\n");
        sb.append(String.format("(%d varreduras do relevo)%n%n", pl.varreduras()));

        sb.append(String.format("%-12s %9s %10s %12s%n",
                "campo", "hoje", "sugerido", "area"));
        for (ApAim.Eixo x : pl.eixos()) {
            sb.append(String.format("%-12s %8.1f%s %9.1f%s %8.2f km2  %s%n",
                    x.campo(), x.atual(), x.unidade(), x.melhor(), x.unidade(),
                    x.areaMelhorM2() / 1e6, x.muda()
                        ? String.format("<-- mexer (%+.0f%%)", x.ganhoPct()) : ""));
            sb.append("             ").append(x.porque()).append('\n');
        }
        sb.append('\n').append(pl.nota());
        return sb.toString();
    }

    /**
     * O plano de apontamento em texto.
     *
     * Mostra o valor de hoje ao lado do sugerido, inclusive quando sao
     * iguais: "n\u00e3o mexa nisto" tamb\u00e9m \u00e9 resposta, e omitir a
     * linha deixaria a d\u00favida de se o campo chegou a ser olhado. A coluna
     * do porqu\u00ea existe para a tela nao virar or\u00e1culo \u2014 quem
     * instala precisa poder discordar com conhecimento de causa.
     */
    private static String relatorioMira(StationAim.Plano pl) {
        StringBuilder sb = new StringBuilder();
        sb.append("COMO APONTAR ESTA ESTA\u00c7\u00c3O\n");
        sb.append(String.format("para %s, a %s de dist\u00e2ncia%n%n",
                pl.parNome(), MapPane.formatRange(pl.distanciaM())));

        sb.append(String.format("%-12s %10s %10s%n", "campo", "hoje", "sugerido"));
        for (StationAim.Ajuste aj : pl.ajustes()) {
            sb.append(String.format("%-12s %8.1f %s %8.1f %s  %s%n",
                    aj.campo(), aj.atual(), aj.unidade(), aj.sugerido(), aj.unidade(),
                    aj.muda() ? "<-- mexer" : ""));
            sb.append("             ").append(aj.porque()).append('\n');
        }

        if (!Double.isNaN(pl.rssiPlanoDbm())) {
            sb.append(String.format(
                    "%nSinal estimado no AP: %.0f dBm hoje \u2192 %.0f dBm (%s)%n",
                    pl.rssiAtualDbm(), pl.rssiPlanoDbm(),
                    com.colmeia.radiomapper.rf.LinkBudget.quality(pl.rssiPlanoDbm())));
        }
        if (!Double.isNaN(pl.fresnelPct())) {
            sb.append(String.format(
                    "Fresnel livre: %.0f%% \u2192 %.0f%% (a regra de campo pede 60%%)%n",
                    pl.fresnelPct(), pl.fresnelPlanoPct()));
        }
        sb.append('\n').append(pl.nota());
        return sb.toString();
    }

    private static HBox campo(Spinner<Double> sp, String unidade) {
        sp.setPrefWidth(88);
        sp.setEditable(true);
        Label u = new Label(unidade);
        u.setStyle("-fx-font-size: 11;");
        return new HBox(4, sp, u);
    }

    /** Cópia só do que a simulação lê — o resto não influencia o lobo. */
    private static Radio copiar(Radio o) {
        Radio c = new Radio();
        c.setId(o.getId());
        c.setName(o.getName());
        c.setRole(o.getRole());
        // O uplink nao e' editavel aqui, mas e' por ele que se descobre para
        // quem esta estacao aponta -- sem copia-lo, a recomendacao de
        // apontamento perderia o AP informado a mao no cadastro.
        c.setUplinkRadioId(o.getUplinkRadioId());
        c.setFrequencyMhz(o.getFrequencyMhz());
        c.setTxPowerDbm(o.getTxPowerDbm());
        c.setAntennaGainDbi(o.getAntennaGainDbi());
        c.setCableLossDb(o.getCableLossDb());
        c.setBeamAzimuthDeg(o.getBeamAzimuthDeg());
        c.setBeamWidthDeg(o.getBeamWidthDeg());
        c.setBeamVerticalWidthDeg(o.getBeamVerticalWidthDeg());
        c.setBeamTiltDeg(o.getBeamTiltDeg());
        c.setAltitudeMode(o.getAltitudeMode());
        c.setAntennaHeightM(o.getAntennaHeightM());
        return c;
    }

    /** Grava no rádio real só o que esta tela edita. */
    private static void copiarPara(Radio de, Radio para) {
        para.setTxPowerDbm(de.getTxPowerDbm());
        para.setAntennaGainDbi(de.getAntennaGainDbi());
        // O modo ANTES da altura: getAntennaHeightM() poe piso zero quando e'
        // mastro, e gravar uma cota negativa com o modo velho a zeraria.
        para.setAltitudeMode(de.getAltitudeMode());
        para.setAntennaHeightM(de.getAntennaHeightM());
        para.setBeamAzimuthDeg(de.getBeamAzimuthDeg());
        para.setBeamWidthDeg(de.getBeamWidthDeg());
        para.setBeamVerticalWidthDeg(de.getBeamVerticalWidthDeg());
        para.setBeamTiltDeg(de.getBeamTiltDeg());
    }

    private static boolean mudou(Radio original, Radio hipotese) {
        // O modo entra na comparacao: trocar so ele muda o significado da
        // altura, e sem isto o botao de aplicar nem acendia.
        return original.getAltitudeMode() != hipotese.getAltitudeMode()
            || original.getTxPowerDbm() != hipotese.getTxPowerDbm()
            || original.getAntennaGainDbi() != hipotese.getAntennaGainDbi()
            || original.getAntennaHeightM() != hipotese.getAntennaHeightM()
            || original.getBeamAzimuthDeg() != hipotese.getBeamAzimuthDeg()
            || original.getBeamWidthDeg() != hipotese.getBeamWidthDeg()
            || original.getBeamVerticalWidthDeg() != hipotese.getBeamVerticalWidthDeg()
            || original.getBeamTiltDeg() != hipotese.getBeamTiltDeg();
    }

    private static String relatorio(Radio original, Radio r, BeamCoverage.Result res,
                                    BeamCoverage.Params prm, LinkPeer.Peer par, boolean doCache) {
        if (!res.valid()) return res.note();

        StringBuilder sb = new StringBuilder();
        if (doCache) sb.append("(sem recalcular: nada mudou desde a última vez)\n\n");
        sb.append(String.format("Alcance só por potência (no eixo): %.0f m%n", res.budgetRangeM()));
        if (res.terrainUsed()) {
            sb.append(String.format("Com o terreno:  máximo %.0f m  ·  médio %.0f m  ·  mínimo %.0f m%n",
                    res.maxReachM(), res.meanReachM(), res.minReachM()));
            sb.append(String.format("%d de %d direções cortadas pelo relevo%n",
                    res.blockedRays(), res.rays()));
        } else {
            sb.append(String.format("SEM relevo:  máximo %.0f m  ·  médio %.0f m%n",
                    res.maxReachM(), res.meanReachM()));
        }

        double cadastro = original.getBeamRangeM();
        if (cadastro > 0) {
            sb.append(String.format("Cadastro diz %.0f m — simulação dá %+.0f m em média%n",
                    cadastro, res.meanReachM() - cadastro));
        }
        sb.append(String.format("%nBorda em %.0f dBm (%s), com %.0f dBi a %.0f m do chao "
                + "do outro lado (%s).%n",
                prm.minRssiDbm(), LinkBudget.quality(prm.minRssiDbm()),
                prm.farGainDbi(), prm.rxHeightM(),
                par.real() ? par.nome() : "estimado pelo papel"));
        sb.append("O alcance vale para um receptor NESSA altura. Um mais alto enxerga por "
                + "cima de morros que cortam este lobo, e por isso um enlace especifico "
                + "pode fechar alem da borda desenhada.\n");
        if (mudou(original, r)) {
            sb.append("\nHIPÓTESE: os parâmetros acima ainda não estão no cadastro do rádio.\n");
        }
        if (!res.faixas().isEmpty()) {
            sb.append("\nAte onde cada nivel chega:\n");
            double celula = res.cobertura() == null ? 0
                    : res.cobertura().larguraCelula();
            for (BeamCoverage.Faixa f : res.faixas()) {
                sb.append(String.format("  %+.0f dBm (%-10s) max %5.0f m   medio %5.0f m%n",
                        f.dbm(), f.qualidade(), f.maxM(), f.meanM()));
            }
            if (res.cobertura() != null) {
                // Area de verdade: soma das celulas com sinal, ja descontados
                // os buracos de sombra. O "max" acima e' so a direcao que foi
                // mais longe, e nao diz nada sobre o que ficou pelo caminho.
                double m2 = res.cobertura().celulasCobertas()
                        * (2.0 * res.maxReachM() / res.cobertura().cols())
                        * (2.0 * res.maxReachM() / res.cobertura().rows());
                sb.append(String.format("%nArea com sinal: %.2f km\u00b2 "
                        + "(so o que a antena enxerga; os buracos de sombra "
                        + "nao entram).%n", m2 / 1e6));
            }
        }
        sb.append('\n').append(res.note());
        return sb.toString();
    }

    private static String nome(Radio r) {
        if (r.getName() != null && !r.getName().isBlank()) return r.getName();
        return r.getHost() == null || r.getHost().isBlank() ? "rádio" : r.getHost();
    }
}
