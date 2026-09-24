package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.util.function.Consumer;

public final class LogViewerDialog {

    private static Stage current;

    private LogViewerDialog() {}

    /** Mostra a janela (ou traz a existente para a frente). */
    public static void show(Window owner) {
        if (current != null) {
            current.toFront();
            current.requestFocus();
            return;
        }

        Stage s = new Stage();
        current = s;
        s.initOwner(owner);
        s.initModality(Modality.NONE);
        s.setTitle("Log");

        TextArea ta = new TextArea(String.join("\n", Log.snapshot()));
        ta.setEditable(false);
        ta.setWrapText(false);
        ta.setStyle("-fx-font-family: 'Consolas', 'Menlo', 'Monospaced'; -fx-font-size: 12;");

        Consumer<String> listener = line -> Platform.runLater(() -> {
            ta.appendText("\n" + line);
            ta.positionCaret(ta.getText().length());
        });
        Log.addListener(listener);

        Label path = new Label(Log.getLogFile() == null
                ? "Arquivo de log nao disponivel"
                : "Arquivo: " + Log.getLogFile().getAbsolutePath());

        Button openFolder = new Button("Abrir pasta");
        openFolder.setOnAction(e -> openLogFolder());

        Button clear = new Button("Limpar tela");
        clear.setOnAction(e -> ta.clear());

        Button close = new Button("Fechar");
        close.setOnAction(e -> s.close());

        HBox bar = new HBox(8, openFolder, clear, close);
        bar.setPadding(new Insets(6, 8, 8, 8));

        BorderPane root = new BorderPane(ta);
        root.setTop(wrapPath(path));
        root.setBottom(bar);

        s.setScene(new Scene(root, 900, 520));
        s.setOnHidden(e -> {
            Log.removeListener(listener);
            current = null;
        });
        s.show();
        Platform.runLater(() -> ta.positionCaret(ta.getText().length()));
    }

    private static HBox wrapPath(Label l) {
        HBox h = new HBox(l);
        h.setPadding(new Insets(8, 8, 4, 8));
        return h;
    }

    private static void openLogFolder() {
        if (Log.getLogFile() == null) return;
        try {
            Desktop.getDesktop().open(Log.getLogFile().getParentFile());
        } catch (Exception ex) {
            Log.warn("Nao consegui abrir a pasta de log: %s", ex.getMessage());
        }
    }
}
