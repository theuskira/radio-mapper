package com.colmeia.radiomapper;

import com.colmeia.radiomapper.ui.MainController;
import com.colmeia.radiomapper.util.Log;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    private MainController controller;

    @Override
    public void init() {
        Log.init();
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        Scene scene = new Scene(root, 1280, 800);
        stage.setTitle("Radio Mapper");
        stage.setScene(scene);
        stage.show();
        // Com a pilha inteira: registrar so o toString() escondia a causa real
        // e deixava um erro de encerramento impossivel de diagnosticar.
        Thread.setDefaultUncaughtExceptionHandler((t, ex) ->
                Log.error("Excecao nao tratada em %s:%n%s", t.getName(), stackOf(ex)));
    }

    @Override
    public void stop() {
        // O fechamento e "melhor esforco": se o controller falhar, o log ainda
        // precisa ser fechado, senao as ultimas linhas se perdem justamente
        // quando explicariam o problema.
        try {
            if (controller != null) controller.shutdown();
        } catch (Throwable ex) {
            Log.error("Falha ao encerrar:%n%s", stackOf(ex));
        } finally {
            Log.close();
        }
    }

    private static String stackOf(Throwable ex) {
        java.io.StringWriter sw = new java.io.StringWriter();
        ex.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
