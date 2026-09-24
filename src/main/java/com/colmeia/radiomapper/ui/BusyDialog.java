package com.colmeia.radiomapper.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Janelinha modal de "aguarde", para operações longas que não dá para medir.
 *
 * O indicador é indeterminado de propósito: a decodificação de um TIFF pelo
 * ImageIO não reporta progresso, e uma barra fingindo porcentagem seria
 * mentira. O que importa é o usuário saber que o programa está trabalhando,
 * e não travado.
 */
public final class BusyDialog {

    private final Stage stage;

    public BusyDialog(Window owner, String message) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(46, 46);

        Label label = new Label(message);
        label.setWrapText(true);
        label.setMaxWidth(320);

        VBox box = new VBox(12, spin, label);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(22, 28, 22, 28));
        box.setStyle("-fx-background-color: white; -fx-border-color: #bbb;");

        stage = new Stage(StageStyle.UNDECORATED);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        stage.setScene(new Scene(box));
        stage.setResizable(false);
    }

    public void show() { stage.show(); }

    public void close() { stage.close(); }
}
