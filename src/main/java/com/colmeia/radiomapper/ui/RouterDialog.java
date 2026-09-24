package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.model.Router;
import com.colmeia.radiomapper.model.RouterInterface;
import com.colmeia.radiomapper.model.RouterVendor;
import com.colmeia.radiomapper.ssh.RouterOsProbe;
import com.colmeia.radiomapper.util.Log;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Cadastro de um roteador e leitura das interfaces dele.
 *
 * <h3>Por que as interfaces são lidas aqui</h3>
 * Digitar "ether1" à mão é convite a erro de digitação que só aparece depois,
 * como um gráfico eternamente vazio. O equipamento sabe o nome das próprias
 * portas; então o diálogo pergunta a ele e oferece a lista. O resultado fica
 * guardado no projeto para a próxima abertura já ter o que mostrar sem
 * precisar de rede.
 */
public final class RouterDialog {

    private RouterDialog() {}

    /**
     * @return true se o usuário confirmou (o roteador já vem alterado)
     */
    public static boolean show(Window owner, Router r, String nomeDoPonto) {
        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Roteador");
        dlg.setHeaderText(r.getName().isBlank() ? "Novo roteador em " + nomeDoPonto
                                                : r.displayName() + "  ·  " + nomeDoPonto);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField nome = new TextField(r.getName());
        nome.setPromptText("como você chama este equipamento");
        ComboBox<RouterVendor> marca = new ComboBox<>();
        marca.getItems().setAll(RouterVendor.values());
        marca.setValue(r.getVendor());
        marca.setPrefWidth(240);

        TextField host = new TextField(r.getHost());
        host.setPromptText("IP ou hostname");
        Spinner<Integer> porta = new Spinner<>(1, 65535, r.getSshPort(), 1);
        porta.setEditable(true);
        porta.setPrefWidth(90);
        TextField usuario = new TextField(r.getSshUser());
        PasswordField senha = new PasswordField();
        senha.setText(r.getSshPassword());
        CheckBox monitorado = new CheckBox("Monitorar status nas sincronizações");
        monitorado.setSelected(r.isMonitored());
        TextArea notas = new TextArea(r.getNotes());
        notas.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(6);
        int row = 0;
        g.add(new Label("Nome:"), 0, row); g.add(nome, 1, row++);
        g.add(new Label("Equipamento:"), 0, row); g.add(marca, 1, row++);
        g.add(new Label("Host:"), 0, row); g.add(host, 1, row++);
        g.add(new Label("Porta SSH:"), 0, row); g.add(porta, 1, row++);
        g.add(new Label("Usuário:"), 0, row); g.add(usuario, 1, row++);
        g.add(new Label("Senha:"), 0, row); g.add(senha, 1, row++);
        g.add(monitorado, 1, row++);
        g.add(new Label("Notas:"), 0, row); g.add(notas, 1, row++);
        GridPane.setHgrow(nome, Priority.ALWAYS);

        // ------------------------ Interfaces ------------------------
        ListView<RouterInterface> lista = new ListView<>();
        lista.setPrefHeight(150);
        lista.getItems().setAll(r.getInterfaces());
        lista.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(RouterInterface i, boolean vazio) {
                super.updateItem(i, vazio);
                if (vazio || i == null) { setText(null); setStyle(""); return; }
                setText(i.describe());
                if (i.isDisabled()) setStyle("-fx-text-fill: #999;");
                else if (!i.isRunning()) setStyle("-fx-text-fill: #b26500;");
                else setStyle("-fx-text-fill: #2e7d32;");
            }
        });
        lista.setPlaceholder(new Label("Nenhuma interface lida ainda."));

        Button ler = new Button("Ler interfaces do equipamento");
        Button monitorar = new Button("Monitorar tráfego...");
        monitorar.setDisable(true);
        lista.getSelectionModel().selectedItemProperty().addListener(
                (o, a, b) -> monitorar.setDisable(b == null));

        Label estado = new Label();
        estado.setWrapText(true);
        estado.setMaxWidth(430);
        estado.setStyle("-fx-font-size: 11;");

        // Guarda o que foi digitado no objeto, para a sondagem usar os dados
        // atuais da tela e não os de quando o diálogo abriu.
        Runnable aplicar = () -> {
            r.setName(nome.getText().trim());
            r.setVendor(marca.getValue());
            r.setHost(host.getText().trim());
            r.setSshPort(porta.getValue() == null ? 22 : porta.getValue());
            r.setSshUser(usuario.getText().trim());
            r.setSshPassword(senha.getText());
            r.setMonitored(monitorado.isSelected());
            r.setNotes(notas.getText());
        };

        ler.setOnAction(e -> {
            aplicar.run();
            if (!r.getVendor().speaksRouterOs()) {
                estado.setText("Este equipamento está marcado como \"somente ping\": "
                        + "não há como listar interfaces sem SSH.");
                estado.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
                return;
            }
            if (r.getHost().isBlank() || r.getSshUser().isBlank()) {
                estado.setText("Preencha host e usuário antes de ler as interfaces.");
                estado.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
                return;
            }
            ler.setDisable(true);
            estado.setText("Conectando em " + r.getHost() + "...");
            estado.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

            Task<List<RouterInterface>> t = new Task<>() {
                @Override protected List<RouterInterface> call() throws Exception {
                    return RouterOsProbe.listInterfaces(r);
                }
            };
            t.setOnSucceeded(ev -> {
                ler.setDisable(false);
                List<RouterInterface> ifs = t.getValue();
                r.setInterfaces(ifs);
                lista.getItems().setAll(ifs);
                if (ifs.isEmpty()) {
                    estado.setText("O equipamento respondeu, mas nenhuma interface foi "
                            + "reconhecida na saída. Veja o log para o que veio.");
                    estado.setStyle("-fx-text-fill: #b26500; -fx-font-size: 11;");
                } else {
                    long ativas = ifs.stream().filter(RouterInterface::isRunning).count();
                    estado.setText(ifs.size() + " interface(s), " + ativas + " com link.");
                    estado.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 11;");
                }
                Log.info("Interfaces lidas de %s: %d", r.displayName(), ifs.size());
            });
            t.setOnFailed(ev -> {
                ler.setDisable(false);
                Throwable ex = t.getException();
                String msg = ex == null ? "erro desconhecido" : ex.getMessage();
                estado.setText("Não consegui ler: " + msg);
                estado.setStyle("-fx-text-fill: #b00020; -fx-font-size: 11;");
                Log.warn("Falha ao ler interfaces de %s: %s", r.displayName(), msg);
            });
            Thread th = new Thread(t, "router-interfaces");
            th.setDaemon(true);
            th.start();
        });

        monitorar.setOnAction(e -> {
            RouterInterface sel = lista.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            aplicar.run();
            TrafficMonitorDialog.show(owner, r, sel.getName());
        });

        Label ajuda = new Label("O tráfego é medido pela diferença dos contadores de bytes "
                + "entre duas leituras, então a taxa só aparece a partir da segunda. "
                + "A janela de tráfego mede enquanto estiver aberta e não grava nada.");
        ajuda.setWrapText(true);
        ajuda.setMaxWidth(430);
        ajuda.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        VBox box = new VBox(10, g, new Separator(),
                new Label("Interfaces:"), lista, new HBox(8, ler, monitorar), estado,
                new Separator(), ajuda);
        box.setPadding(new Insets(12));
        box.setPrefWidth(470);
        dlg.getDialogPane().setContent(box);

        var res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return false;
        aplicar.run();
        return true;
    }
}
