package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.model.AltitudeMode;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioVendor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

public final class RadioDialog {

    private RadioDialog() {}

    public static boolean show(Radio radio, Window owner) {
        return show(radio, owner, null, false);
    }

    public static boolean show(Radio radio, Window owner, Runnable onPreview) {
        return show(radio, owner, onPreview, false);
    }

    /**
     * @param onPreview opcional. Chamado a cada alteração dos campos do feixe
     *                  (com os valores já gravados em {@code radio}) para
     *                  que o chamador possa redesenhar a pré-visualização.
     *                  Em Cancelar, os valores do feixe são revertidos e
     *                  o callback é chamado mais uma vez para limpar o preview.
     * @param focusBeamTab se {@code true}, abre o diálogo já na aba "Feixe".
     */
    public static boolean show(Radio radio, Window owner, Runnable onPreview, boolean focusBeamTab) {
        return show(radio, owner, onPreview, focusBeamTab, null);
    }

    /** "Ponto \u00b7 R\u00e1dio", que e como se identifica um radio fora do proprio ponto. */
    private static String rotuloDe(com.colmeia.radiomapper.model.Project project, Radio x) {
        String nome = x.getName() == null || x.getName().isBlank()
                ? (x.getHost() == null || x.getHost().isBlank() ? "r\u00e1dio" : x.getHost())
                : x.getName();
        if (project == null) return nome;
        return project.findPointOfRadio(x.getId())
                .map(p -> p.getName() + "  \u00b7  " + nome)
                .orElse(nome);
    }

    /**
     * @param project projeto de onde sair a lista de APs candidatos para a
     *                associacao manual. Null esconde esse campo — e o que
     *                acontece em qualquer chamada que nao tenha o projeto em
     *                maos, em vez de mostrar uma lista vazia sem explicacao.
     */
    public static boolean show(Radio radio, Window owner, Runnable onPreview,
                               boolean focusBeamTab,
                               com.colmeia.radiomapper.model.Project project) {
        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Rádio");
        dlg.setHeaderText("Dados do rádio");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // ---- Aba "Geral" ----
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(6); g.setPadding(new Insets(10));

        TextField name = new TextField(radio.getName());

        ComboBox<com.colmeia.radiomapper.model.RadioRole> role = new ComboBox<>();
        role.getItems().setAll(com.colmeia.radiomapper.model.RadioRole.values());
        role.setValue(radio.getRole());
        role.setPrefWidth(240);

        ComboBox<RadioVendor> vendor = new ComboBox<>();
        vendor.getItems().setAll(RadioVendor.values());
        vendor.setValue(radio.getVendor() == null ? RadioVendor.MIKROTIK_V6 : radio.getVendor());
        TextField host = new TextField(radio.getHost());
        TextField port = new TextField(String.valueOf(radio.getSshPort()));
        TextField user = new TextField(radio.getSshUser());
        PasswordField pass = new PasswordField(); pass.setText(radio.getSshPassword());
        TextField mac = new TextField(radio.getMac());
        TextField notes = new TextField(radio.getNotes());

        // ---- Associacao informada a mao ----
        // So faz sentido para quem se associa a alguem: AP nao tem uplink
        // sem fio, e "somente ping" nao entra em topologia nenhuma.
        ComboBox<Radio> uplink = new ComboBox<>();
        Label uplinkLabel = new Label("Conectado no AP:");
        Label uplinkHint = new Label();
        uplinkHint.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        uplinkHint.setWrapText(true);
        uplinkHint.setMaxWidth(330);
        uplink.setPrefWidth(240);
        uplink.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Radio x, boolean vazio) {
                super.updateItem(x, vazio);
                setText(vazio || x == null ? "(nenhum \u2014 usar o que os r\u00e1dios reportarem)"
                                           : rotuloDe(project, x));
            }
        });
        uplink.setButtonCell(uplink.getCellFactory().call(null));
        if (project != null) {
            uplink.getItems().add(null);
            for (var p : project.getPoints()) {
                for (Radio cand : p.getRadios()) {
                    if (cand == radio) continue;
                    if (!cand.getRole().isAp()) continue;
                    uplink.getItems().add(cand);
                }
            }
            if (radio.hasManualUplink()) {
                project.findRadioById(radio.getUplinkRadioId()).ifPresent(uplink::setValue);
            }
        }

        Runnable atualizaUplink = () -> {
            var papel = role.getValue();
            boolean mostra = project != null && papel != null && papel.isStation();
            uplinkLabel.setVisible(mostra); uplinkLabel.setManaged(mostra);
            uplink.setVisible(mostra); uplink.setManaged(mostra);
            uplinkHint.setVisible(mostra); uplinkHint.setManaged(mostra);
            if (mostra) {
                uplinkHint.setText(uplink.getItems().size() <= 1
                        ? "Nenhum r\u00e1dio com papel de AP cadastrado ainda."
                        : "Use quando o r\u00e1dio n\u00e3o entrega a tabela de registro. O enlace "
                          + "aparece no mapa e sobrevive \u00e0 sincroniza\u00e7\u00e3o; se os r\u00e1dios "
                          + "passarem a report\u00e1-lo, vira enlace descoberto sozinho.");
            }
            // Fabricante nao importa para quem so responde ping.
            boolean soPing = papel != null && papel.monitorOnly();
            vendor.setDisable(soPing);
        };
        role.valueProperty().addListener((o, a, b) -> atualizaUplink.run());

        int r = 0;
        g.add(new Label("Nome:"), 0, r); g.add(name, 1, r++);
        g.add(new Label("Papel na rede:"), 0, r); g.add(role, 1, r++);
        g.add(uplinkLabel, 0, r); g.add(uplink, 1, r++);
        g.add(uplinkHint, 1, r++);
        g.add(new Label("Fabricante:"), 0, r); g.add(vendor, 1, r++);
        g.add(new Label("Host/IP:"), 0, r); g.add(host, 1, r++);
        g.add(new Label("Porta SSH:"), 0, r); g.add(port, 1, r++);
        g.add(new Label("Usuário:"), 0, r); g.add(user, 1, r++);
        g.add(new Label("Senha:"), 0, r); g.add(pass, 1, r++);
        g.add(new Label("MAC (wireless):"), 0, r); g.add(mac, 1, r++);
        g.add(new Label("Notas:"), 0, r); g.add(notes, 1, r++);

        // Mesmo flag da tela Ferramentas > Radios monitorados; aqui e o lugar
        // onde se procura ao cadastrar um radio novo.
        CheckBox monitored = new CheckBox("Notificar por e-mail se este rádio cair");
        monitored.setSelected(radio.isMonitored());
        g.add(monitored, 1, r++);

        // ---- Aba "Feixe" ----
        // Snapshot pra reverter em Cancelar (preview escreve direto no radio).
        final double origAz = radio.getBeamAzimuthDeg();
        final double origWidth = radio.getBeamWidthDeg();
        final double origDist = radio.getBeamRangeM();
        final boolean origVisible = radio.isBeamVisible();
        final String origColor = radio.getBeamColor();
        final double origOpacity = radio.getBeamOpacity();

        // Todos decimais: abertura de 5,5° e azimute de 127,5° sao valores
        // de catalogo, e arredondar distorce o plano vertical do feixe.
        Spinner<Double> width = Spinners.decimal(0, 360, origWidth, 0.5);
        Spinner<Double> dist = Spinners.decimal(0, 100_000, origDist, 10);
        Spinner<Double> az = Spinners.decimal(0, 360, origAz, 0.5);
        CheckBox visible = new CheckBox("Exibir este feixe quando os feixes estiverem ligados");
        visible.setSelected(origVisible);

        // Cor e opacidade. "Auto" usa a cor do status (UP/DOWN/UNKNOWN).
        CheckBox autoColor = new CheckBox("Cor automática (segue status do rádio)");
        autoColor.setSelected(origColor == null || origColor.isBlank());
        ColorPicker color = new ColorPicker();
        try {
            color.setValue(autoColor.isSelected() ? Color.DEEPSKYBLUE : Color.web(origColor));
        } catch (IllegalArgumentException ex) {
            color.setValue(Color.DEEPSKYBLUE);
        }
        color.setDisable(autoColor.isSelected());

        Slider opacity = new Slider(0.0, 1.0, origOpacity);
        opacity.setShowTickMarks(true);
        opacity.setShowTickLabels(true);
        opacity.setMajorTickUnit(0.25);
        opacity.setBlockIncrement(0.05);
        opacity.setPrefWidth(220);
        Label opacityPct = new Label(formatPct(origOpacity));

        CompassControl compass = new CompassControl();
        compass.setAzimuth(origAz);
        compass.setBeamWidth(origWidth);

        ElevationControl elevation = new ElevationControl();
        elevation.setTilt(radio.getBeamTiltDeg());
        elevation.setBeamWidth(radio.getBeamVerticalWidthDeg());
        elevation.setAntennaHeight(radio.getAntennaHeightM());
        elevation.setAbsoluteAltitude(radio.isAbsoluteAltitude());

        // ── Sincronização spinner ↔ bússola + preview ──
        // Para evitar loop infinito spinner↔bússola, cada listener só atualiza
        // o outro se o valor diverge. Toda mudança grava no radio e dispara
        // o callback de preview.
        Runnable pushPreview = () -> {
            // value(), nao commit(): comitar reescreve o texto do campo, e
            // fazer isso durante a edicao atrapalharia quem esta digitando.
            radio.setBeamWidthDeg(Spinners.value(width, origWidth));
            radio.setBeamRangeM(Spinners.value(dist, origDist));
            radio.setBeamAzimuthDeg(Spinners.value(az, origAz));
            radio.setBeamVisible(visible.isSelected());
            radio.setBeamColor(autoColor.isSelected() ? "" : toHex(color.getValue()));
            radio.setBeamOpacity(opacity.getValue());
            if (onPreview != null) onPreview.run();
        };

        compass.azimuthProperty().addListener((o, a, b) -> {
            if (az.getValue() == null || Math.abs(az.getValue() - b.doubleValue()) > 1e-6) {
                az.getValueFactory().setValue(b.doubleValue());
            }
            pushPreview.run();
        });
        az.valueProperty().addListener((o, a, b) -> {
            if (b != null && Math.abs(compass.getAzimuth() - b) > 1e-6) compass.setAzimuth(b);
            pushPreview.run();
        });
        width.valueProperty().addListener((o, a, b) -> {
            if (b != null) compass.setBeamWidth(b);   // setor acompanha a abertura
            pushPreview.run();
        });
        dist.valueProperty().addListener((o, a, b) -> pushPreview.run());
        visible.selectedProperty().addListener((o, a, b) -> pushPreview.run());
        autoColor.selectedProperty().addListener((o, a, b) -> {
            color.setDisable(b);
            pushPreview.run();
        });
        color.valueProperty().addListener((o, a, b) -> pushPreview.run());
        opacity.valueProperty().addListener((o, a, b) -> {
            opacityPct.setText(formatPct(b.doubleValue()));
            pushPreview.run();
        });

        Spinner<Double> vWidth = Spinners.decimal(0, 180, radio.getBeamVerticalWidthDeg(), 0.5);
        Spinner<Double> tilt = Spinners.decimal(-90, 90, radio.getBeamTiltDeg(), 0.5);
        Spinner<Double> height = Spinners.decimal(-500, 9000, radio.getAntennaHeightM(), 1);

        // A pergunta que decide como o numero acima e lido. Sem ela o programa
        // teria de adivinhar, e adivinhar errado move a antena dezenas ou
        // centenas de metros no perfil de relevo.
        ComboBox<AltitudeMode> altMode = new ComboBox<>();
        altMode.getItems().setAll(AltitudeMode.values());
        altMode.setValue(radio.getAltitudeMode());
        altMode.setMaxWidth(Double.MAX_VALUE);

        Label heightLabel = new Label();
        Label ajudaAltitude = new Label();
        ajudaAltitude.setWrapText(true);
        ajudaAltitude.setMaxWidth(320);

        Runnable aplicaModo = () -> {
            boolean abs = altMode.getValue() == AltitudeMode.ABSOLUTA;
            heightLabel.setText(abs ? "Altitude da antena (m):" : "Altura da antena (m):");
            // Mastro nao desce abaixo do solo; cota pode, em terreno litoraneo.
            Spinners.setRange(height, abs ? -500 : 0, 9000);
            elevation.setAbsoluteAltitude(abs);

            double v = Spinners.value(height, 0);
            String aviso = "";
            if (!abs && v > 200) {
                aviso = "\n⚠ " + String.format("%.0f", v) + " m de mastro e mais alto que "
                        + "qualquer torre comum — o valor nao seria uma altitude absoluta?";
            } else if (abs && v > 0 && v <= 60) {
                // Faixa tipica de mastro. Ha sitios de litoral nessa cota, entao
                // e so um alerta em laranja: quem sabe o que fez, ignora.
                aviso = "\n⚠ " + String.format("%.0f", v) + " m de altitude e cota quase no nivel do mar "
                        + "— confira se nao e a altura de instalacao.";
            }
            ajudaAltitude.setText((abs
                    ? "O numero ja e a cota final do centro da antena (GPS, levantamento). "
                      + "O relevo NAO e somado."
                    : "Medida do solo ate o centro da antena. O programa soma a altitude do "
                      + "terreno sob o ponto para achar a cota da antena.") + aviso);
            ajudaAltitude.setStyle(aviso.isEmpty()
                    ? "-fx-text-fill: #666; -fx-font-size: 11;"
                    : "-fx-text-fill: #b26a00; -fx-font-size: 11;");
        };
        altMode.valueProperty().addListener((o, a, b) -> aplicaModo.run());

        Spinner<Double> gain = Spinners.decimal(0, 50, radio.getAntennaGainDbi(), 0.5);
        Spinner<Double> txPower = Spinners.decimal(-10, 40, radio.getTxPowerDbm(), 1);
        Spinner<Double> freq = Spinners.decimal(0, 90000, radio.getFrequencyMhz(), 5);
        Spinner<Double> cableLoss = Spinners.decimal(0, 20, radio.getCableLossDb(), 0.1);

        // Espelha spinner e controle vertical nos dois sentidos. A guarda de
        // igualdade evita o ping-pong infinito entre os dois listeners.
        vWidth.valueProperty().addListener((o, a, b) -> {
            if (b != null) elevation.setBeamWidth(b);
        });
        tilt.valueProperty().addListener((o, a, b) -> {
            if (b != null && elevation.getTilt() != b) elevation.setTilt(b);
        });
        elevation.tiltProperty().addListener((o, a, b) -> {
            if (tilt.getValue() == null || Math.abs(tilt.getValue() - b.doubleValue()) > 1e-6) {
                tilt.getValueFactory().setValue(b.doubleValue());
            }
        });
        height.valueProperty().addListener((o, a, b) -> {
            if (b != null) elevation.setAntennaHeight(b);   // mastro acompanha a altura
            aplicaModo.run();                               // reavalia o aviso de valor suspeito
        });
        aplicaModo.run();

        GridPane bg = new GridPane();
        bg.setHgap(8); bg.setVgap(6); bg.setPadding(new Insets(10));
        int br = 0;
        bg.add(new Label("Abertura horizontal (°):"), 0, br); bg.add(width, 1, br++);
        bg.add(new Label("Alcance (metros):"),        0, br); bg.add(dist, 1, br++);
        bg.add(new Label("Azimute (° compass):"),     0, br); bg.add(az, 1, br++);
        bg.add(new Label("0° = Norte (cima), 90° = Leste, sentido horário."), 0, br, 2, 1); br++;

        // Plano vertical: o que permite cruzar o feixe com o relevo.
        bg.add(new Separator(), 0, br, 2, 1); br++;
        bg.add(new Label("Abertura vertical (°):"), 0, br); bg.add(vWidth, 1, br++);
        bg.add(new Label("Inclinação (°):"),        0, br); bg.add(tilt, 1, br++);
        bg.add(new Label("A altura informada é:"), 0, br); bg.add(altMode, 1, br++);
        bg.add(heightLabel, 0, br); bg.add(height, 1, br++);
        bg.add(ajudaAltitude, 0, br, 2, 1); br++;
        bg.add(new Label("Inclinação negativa aponta para baixo (downtilt)."), 0, br, 2, 1); br++;

        // RF: o que permite estimar sinal no perfil de enlace.
        bg.add(new Separator(), 0, br, 2, 1); br++;
        bg.add(new Label("Ganho da antena (dBi):"), 0, br); bg.add(gain, 1, br++);
        bg.add(new Label("Potência TX (dBm):"),     0, br); bg.add(txPower, 1, br++);
        bg.add(new Label("Frequência central (MHz):"), 0, br); bg.add(freq, 1, br++);
        bg.add(new Label("Perda de cabo (dB):"),    0, br); bg.add(cableLoss, 1, br++);

        Label ajudaRf = new Label(
                "Frequência de OPERAÇÃO, não largura de canal: 5800 para 5,8 GHz, "
                + "5180 para o canal 36, 2412 para o canal 1. A largura (20/40/80 MHz) "
                + "não entra nesta conta.\n"
                + "Com estes campos nas duas pontas, o perfil do enlace estima o sinal.");
        ajudaRf.setWrapText(true);
        ajudaRf.setMaxWidth(320);
        ajudaRf.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        bg.add(ajudaRf, 0, br, 2, 1); br++;
        bg.add(new Separator(), 0, br, 2, 1); br++;

        bg.add(visible, 0, br, 2, 1); br++;
        bg.add(new Label("Cor:"), 0, br); bg.add(new HBox(8, color, autoColor), 1, br++);
        bg.add(new Label("Opacidade:"), 0, br); bg.add(new HBox(8, opacity, opacityPct), 1, br++);

        VBox compassBox = new VBox(4,
                new Label("Horizontal — arraste a agulha:"), compass,
                new Label("Vertical — arraste a inclinação:"), elevation);
        compassBox.setAlignment(Pos.TOP_CENTER);

        HBox beamPane = new HBox(16, bg, compassBox);
        beamPane.setPadding(new Insets(4));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab tGeneral = new Tab("Geral", g);
        Tab tBeam = new Tab("Feixe", beamPane);
        tabs.getTabs().addAll(tGeneral, tBeam);
        if (focusBeamTab) tabs.getSelectionModel().select(tBeam);

        dlg.getDialogPane().setContent(tabs);

        var result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            // Reverte tudo do feixe e pede um redraw final pra limpar o preview.
            radio.setBeamAzimuthDeg(origAz);
            radio.setBeamWidthDeg(origWidth);
            radio.setBeamRangeM(origDist);
            radio.setBeamVisible(origVisible);
            radio.setBeamColor(origColor);
            radio.setBeamOpacity(origOpacity);
            if (onPreview != null) onPreview.run();
            return false;
        }

        radio.setName(name.getText());
        radio.setRole(role.getValue());
        // Papel que nao se associa nao guarda uplink: deixar o id la faria a
        // sincronizacao recriar um enlace que o usuario ja tinha abandonado.
        radio.setUplinkRadioId(role.getValue() != null && role.getValue().isStation()
                && uplink.getValue() != null ? uplink.getValue().getId() : "");
        radio.setVendor(vendor.getValue());
        radio.setHost(host.getText().trim());
        try { radio.setSshPort(Integer.parseInt(port.getText().trim())); }
        catch (NumberFormatException e) { radio.setSshPort(22); }
        radio.setSshUser(user.getText().trim());
        radio.setSshPassword(pass.getText());
        radio.setMac(mac.getText().trim());
        radio.setNotes(notes.getText());
        radio.setMonitored(monitored.isSelected());

        // Os campos de feixe já foram gravados pelo pushPreview a cada mudança.
        // Reaplica aqui pra garantir o valor final caso o usuário tenha digitado
        // sem disparar listener (commit em foco).
        radio.setBeamWidthDeg(Spinners.commit(width, origWidth));
        radio.setBeamRangeM(Spinners.commit(dist, origDist));
        radio.setBeamAzimuthDeg(Spinners.commit(az, origAz));
        radio.setBeamVisible(visible.isSelected());
        radio.setBeamColor(autoColor.isSelected() ? "" : toHex(color.getValue()));
        radio.setBeamOpacity(opacity.getValue());

        // Plano vertical: nao entra no preview do mapa (que e visto de cima),
        // entao e gravado so aqui.
        radio.setBeamVerticalWidthDeg(Spinners.commit(vWidth, radio.getBeamVerticalWidthDeg()));
        radio.setBeamTiltDeg(Spinners.commit(tilt, radio.getBeamTiltDeg()));
        // Modo antes do valor: o getter usa o modo para decidir se corta
        // negativo, e o fallback do commit passa por esse getter.
        radio.setAltitudeMode(altMode.getValue());
        radio.setAntennaHeightM(Spinners.commit(height, radio.getAntennaHeightM()));
        radio.setAntennaGainDbi(Spinners.commit(gain, radio.getAntennaGainDbi()));
        radio.setTxPowerDbm(Spinners.commit(txPower, radio.getTxPowerDbm()));
        radio.setFrequencyMhz(Spinners.commit(freq, radio.getFrequencyMhz()));
        radio.setCableLossDb(Spinners.commit(cableLoss, radio.getCableLossDb()));
        return true;
    }

    private static String toHex(Color c) {
        if (c == null) return "";
        int rr = (int) Math.round(c.getRed()   * 255);
        int gg = (int) Math.round(c.getGreen() * 255);
        int bb = (int) Math.round(c.getBlue()  * 255);
        return String.format("#%02x%02x%02x", rr, gg, bb);
    }

    private static String formatPct(double v) {
        return String.format("%3d%%", (int) Math.round(v * 100));
    }


}
