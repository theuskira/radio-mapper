package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.notify.MailConfig;
import com.colmeia.radiomapper.notify.MailSender;
import com.colmeia.radiomapper.notify.NotificationService;
import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Configuração das notificações por e-mail de rádio offline.
 *
 * Trabalha sobre uma cópia da {@link MailConfig}: cancelar não deixa rastro,
 * e o "enviar teste" usa exatamente o que está na tela, não o que está salvo —
 * senão testar uma senha nova exigiria salvar antes.
 */
public final class NotificationSettingsDialog {

    private NotificationSettingsDialog() {}

    /** @return true se o usuário salvou (o chamador deve recarregar o serviço). */
    public static boolean show(Window owner, String projectName) {
        MailConfig cfg = NotificationService.INSTANCE.config().copy();

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Notificações por e-mail");
        dlg.setHeaderText("Avisar por e-mail quando um rádio monitorado cair");
        dlg.setResizable(true);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        CheckBox enabled = new CheckBox("Ativar notificações por e-mail");
        enabled.setSelected(cfg.isEnabled());

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ------------------------ Servidor ------------------------
        TextField host = new TextField(cfg.getHost());
        host.setPromptText("smtp.seuprovedor.com.br");
        Spinner<Integer> port = intSpinner(1, 65535, cfg.getPort());
        ComboBox<MailConfig.Security> security = new ComboBox<>();
        security.getItems().setAll(MailConfig.Security.values());
        security.setValue(cfg.getSecurity());
        security.setPrefWidth(240);
        TextField user = new TextField(cfg.getUser());
        PasswordField password = new PasswordField();
        password.setText(cfg.getPassword());
        TextField from = new TextField(cfg.getFrom());
        from.setPromptText("alertas@suaempresa.com.br");
        TextField fromName = new TextField(cfg.getFromName());

        // Trocar a criptografia ajusta a porta, mas só se ela ainda estiver
        // num padrão conhecido — porta customizada do usuário é preservada.
        security.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            int cur = port.getValue() == null ? 0 : port.getValue();
            if (cur == 25 || cur == 465 || cur == 587) {
                port.getValueFactory().setValue(switch (b) {
                    case NONE -> 25;
                    case SSL -> 465;
                    case STARTTLS -> 587;
                });
            }
        });

        Label testResult = new Label();
        testResult.setWrapText(true);
        testResult.setMaxWidth(460);
        Button testBtn = new Button("Enviar e-mail de teste");

        GridPane gSrv = grid();
        int r = 0;
        gSrv.add(new Label("Servidor SMTP:"), 0, r); gSrv.add(host, 1, r++);
        gSrv.add(new Label("Porta:"), 0, r);         gSrv.add(port, 1, r++);
        gSrv.add(new Label("Criptografia:"), 0, r);  gSrv.add(security, 1, r++);
        gSrv.add(new Separator(), 0, r++, 2, 1);
        gSrv.add(new Label("Usuário:"), 0, r);       gSrv.add(user, 1, r++);
        gSrv.add(new Label("Senha:"), 0, r);         gSrv.add(password, 1, r++);
        gSrv.add(hint("Deixe usuário em branco para servidor sem autenticação. "
                + "Em Gmail ou Microsoft 365, gere uma SENHA DE APLICATIVO — a senha "
                + "normal da conta é recusada."), 0, r++, 2, 1);
        gSrv.add(new Separator(), 0, r++, 2, 1);
        gSrv.add(new Label("Remetente:"), 0, r);     gSrv.add(from, 1, r++);
        gSrv.add(new Label("Nome exibido:"), 0, r);  gSrv.add(fromName, 1, r++);
        gSrv.add(new HBox(8, testBtn), 0, r++, 2, 1);
        gSrv.add(testResult, 0, r, 2, 1);
        tabs.getTabs().add(new Tab("Servidor", scroll(gSrv)));

        // ------------------------ Destinatários ------------------------
        TextArea recipients = new TextArea(String.join("; ", cfg.getRecipients()));
        recipients.setPrefRowCount(8);
        recipients.setWrapText(true);
        recipients.setPromptText("fulano@empresa.com; beltrano@empresa.com; noc@empresa.com");
        Label recipCount = new Label();
        Runnable updateCount = () -> {
            List<String> list = MailConfig.splitRecipients(recipients.getText());
            long bad = list.stream().filter(s -> !MailConfig.looksLikeEmail(s)).count();
            recipCount.setText(list.size() + " destinatário(s)"
                    + (bad > 0 ? " — " + bad + " com formato suspeito" : ""));
            recipCount.setStyle(bad > 0 ? "-fx-text-fill: #b00020;" : "-fx-text-fill: #2e7d32;");
        };
        recipients.textProperty().addListener((o, a, b) -> updateCount.run());
        updateCount.run();

        VBox vRec = new VBox(6,
                new Label("Quem recebe os avisos (separados por ponto e vírgula):"),
                recipients,
                recipCount,
                hint("Separe com ponto e vírgula. Vírgula e quebra de linha também "
                        + "funcionam, para colar lista pronta de outro lugar. Espaço não "
                        + "separa — um endereço com espaço no meio é recusado na validação, "
                        + "em vez de virar dois endereços errados. Todos recebem no campo Para."));
        vRec.setPadding(new Insets(10));
        VBox.setVgrow(recipients, Priority.ALWAYS);
        tabs.getTabs().add(new Tab("Destinatários", vRec));

        // ------------------------ Mensagem ------------------------
        TextField subject = new TextField(cfg.getSubjectTemplate());
        TextArea body = new TextArea(cfg.getBodyTemplate());
        body.setPrefRowCount(9);
        // Multilinha: o formato padrão já ocupa 4 linhas (queda, último online,
        // tempo online), e um TextField engoliria as quebras.
        TextArea line = new TextArea(cfg.getLineTemplate());
        line.setPrefRowCount(4);
        CheckBox notifyRecovery = new CheckBox("Avisar também quando o rádio voltar");
        notifyRecovery.setSelected(cfg.isNotifyRecovery());
        TextField recoverySubject = new TextField(cfg.getRecoverySubjectTemplate());
        recoverySubject.disableProperty().bind(notifyRecovery.selectedProperty().not());
        Button previewBtn = new Button("Ver prévia");

        GridPane gMsg = grid();
        r = 0;
        gMsg.add(new Label("Assunto:"), 0, r);  gMsg.add(subject, 1, r++);
        gMsg.add(new Label("Corpo:"), 0, r);    gMsg.add(body, 1, r++);
        gMsg.add(new Label("Bloco por rádio:"), 0, r); gMsg.add(line, 1, r++);
        gMsg.add(hint("O corpo usa {lista}, que repete o bloco acima para cada rádio. "
                + "{caiu} é o momento da queda, {ultimoOnline} a última vez que respondeu "
                + "e {tempoOnline} quanto tempo ficou no ar antes de cair — os três vêm do "
                + "histórico, então dependem do rádio já ter sido visto online antes."),
                 0, r++, 2, 1);
        gMsg.add(new Separator(), 0, r++, 2, 1);
        gMsg.add(notifyRecovery, 0, r++, 2, 1);
        gMsg.add(new Label("Assunto do retorno:"), 0, r); gMsg.add(recoverySubject, 1, r++);
        gMsg.add(new Separator(), 0, r++, 2, 1);
        gMsg.add(hint("Disponíveis: " + String.join("  ", MailConfig.placeholders())),
                 0, r++, 2, 1);
        gMsg.add(new HBox(8, previewBtn), 0, r, 2, 1);
        GridPane.setHgrow(subject, Priority.ALWAYS);
        GridPane.setHgrow(body, Priority.ALWAYS);
        tabs.getTabs().add(new Tab("Mensagem", scroll(gMsg)));

        // ------------------------ Regras ------------------------
        Spinner<Integer> failures = intSpinner(1, 20, cfg.getFailuresBeforeAlert());
        Spinner<Integer> aggregation = intSpinner(0, 3600, cfg.getAggregationSeconds());
        Spinner<Integer> reAlert = intSpinner(0, 1440, cfg.getReAlertMinutes());

        GridPane gRul = grid();
        r = 0;
        gRul.add(new Label("Confirmar queda após:"), 0, r);
        gRul.add(new HBox(6, failures, new Label("leituras seguidas")), 1, r++);
        gRul.add(hint("1 avisa na primeira leitura DOWN — é o padrão, para o aviso sair "
                + "no momento da queda. Subir para 2 ou 3 filtra timeout isolado de SSH, "
                + "ao custo de um ou dois ciclos de sincronização de atraso."), 0, r++, 2, 1);

        gRul.add(new Label("Agrupar quedas por:"), 0, r);
        gRul.add(new HBox(6, aggregation, new Label("segundos")), 1, r++);
        gRul.add(hint("Zero (padrão) envia imediatamente, um e-mail por queda. Se o "
                + "backbone cair, dezenas de rádios somem juntos e viram dezenas de "
                + "e-mails; pôr 30 ou 60 aqui junta tudo num só, ao custo de esperar "
                + "essa janela antes do primeiro aviso."), 0, r++, 2, 1);

        gRul.add(new Label("Repetir alerta após:"), 0, r);
        gRul.add(new HBox(6, reAlert, new Label("minutos")), 1, r++);
        gRul.add(hint("Intervalo mínimo entre dois alertas do MESMO rádio, para o caso "
                + "de enlace oscilando. Zero desativa a proteção."), 0, r++, 2, 1);

        gRul.add(new Separator(), 0, r++, 2, 1);
        gRul.add(hint("Atenção: a vigilância só acontece com o Radio Mapper aberto e "
                + "sincronizando. Fechou o programa, não há notificação."), 0, r, 2, 1);
        tabs.getTabs().add(new Tab("Regras", scroll(gRul)));

        // ------------------------ Ações ------------------------
        Runnable collect = () -> {
            cfg.setEnabled(enabled.isSelected());
            cfg.setHost(host.getText());
            cfg.setPort(port.getValue() == null ? MailConfig.DEF_PORT : port.getValue());
            cfg.setSecurity(security.getValue());
            cfg.setUser(user.getText());
            cfg.setPassword(password.getText());
            cfg.setFrom(from.getText());
            cfg.setFromName(fromName.getText());
            cfg.setRecipients(recipients.getText());
            cfg.setSubjectTemplate(subject.getText());
            cfg.setBodyTemplate(body.getText());
            cfg.setLineTemplate(line.getText());
            cfg.setNotifyRecovery(notifyRecovery.isSelected());
            cfg.setRecoverySubjectTemplate(recoverySubject.getText());
            cfg.setFailuresBeforeAlert(failures.getValue() == null ? 3 : failures.getValue());
            cfg.setAggregationSeconds(aggregation.getValue() == null ? 60 : aggregation.getValue());
            cfg.setReAlertMinutes(reAlert.getValue() == null ? 30 : reAlert.getValue());
        };

        previewBtn.setOnAction(e -> {
            collect.run();
            TextArea ta = new TextArea(NotificationService.preview(cfg, projectName, false));
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setPrefRowCount(16);
            Dialog<ButtonType> pv = new Dialog<>();
            pv.initOwner(dlg.getDialogPane().getScene().getWindow());
            pv.setTitle("Prévia");
            pv.setHeaderText("Assim o e-mail vai sair (com dados de exemplo)");
            pv.getDialogPane().setContent(ta);
            pv.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            pv.setResizable(true);
            pv.showAndWait();
        });

        testBtn.setOnAction(e -> {
            collect.run();
            String why = cfg.whyCannotSend();
            if (why != null) {
                testResult.setStyle("-fx-text-fill: #b00020;");
                testResult.setText("Falta configurar: " + why);
                return;
            }
            testBtn.setDisable(true);
            testResult.setStyle("-fx-text-fill: #555;");
            testResult.setText("Enviando...");

            MailConfig snapshot = cfg.copy();
            // Rede na thread do JavaFX congelaria a janela pelo timeout inteiro.
            new Thread(() -> {
                String ok, err;
                try {
                    MailSender.send(snapshot,
                            "[Radio Mapper] E-mail de teste",
                            "Se você recebeu esta mensagem, as notificações do Radio Mapper "
                            + "estão configuradas corretamente.\n\nServidor: "
                            + snapshot.getHost() + ":" + snapshot.getPort()
                            + "\nCriptografia: " + snapshot.getSecurity()
                            + "\nDestinatários: " + snapshot.getRecipients().size());
                    ok = "Enviado. Confira a caixa de entrada de "
                            + String.join(", ", snapshot.getRecipients()) + ".";
                    err = null;
                } catch (MailSender.SendException ex) {
                    ok = null;
                    err = ex.getMessage();
                    Log.warn("Teste de e-mail falhou: %s", ex.getMessage());
                }
                final String fOk = ok, fErr = err;
                Platform.runLater(() -> {
                    testBtn.setDisable(false);
                    testResult.setStyle(fErr == null
                            ? "-fx-text-fill: #2e7d32;" : "-fx-text-fill: #b00020;");
                    testResult.setText(fErr == null ? fOk : fErr);
                });
            }, "mail-test").start();
        });

        VBox root = new VBox(8, enabled, tabs);
        root.setPadding(new Insets(10));
        root.setPrefSize(640, 520);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dlg.getDialogPane().setContent(root);

        // Salvar com notificações ligadas e configuração quebrada não faz sentido.
        Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            collect.run();
            if (!cfg.isEnabled()) return;   // desligado pode ficar incompleto
            String why = cfg.whyCannotSend();
            if (why != null) {
                ev.consume();
                tabs.getSelectionModel().select(why.contains("destinatário") ? 1 : 0);
                testResult.setStyle("-fx-text-fill: #b00020;");
                testResult.setText("Não dá para ativar: " + why);
            }
        });

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return false;

        collect.run();
        cfg.save();
        Log.info("Notificacoes por e-mail salvas: ativo=%s, servidor=%s:%d, %d destinatario(s)",
                cfg.isEnabled(), cfg.getHost(), cfg.getPort(), cfg.getRecipients().size());
        return true;
    }

    // ------------------------ Helpers ------------------------

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(10));
        return g;
    }

    private static ScrollPane scroll(javafx.scene.Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private static Label hint(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        l.setWrapText(true);
        l.setMaxWidth(500);
        return l;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int value) {
        Spinner<Integer> sp = new Spinner<>();
        sp.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                min, max, Math.max(min, Math.min(max, value)), 1));
        sp.setEditable(true);
        sp.setPrefWidth(110);
        return sp;
    }
}
