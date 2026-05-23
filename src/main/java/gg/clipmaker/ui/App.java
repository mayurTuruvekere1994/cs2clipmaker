package gg.clipmaker.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX application entry point.
 */
public class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private MainViewController mainController;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Scene scene = new Scene(loader.load(), 900, 680);

        // Load stylesheet
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());

        mainController = loader.getController();

        stage.setTitle("CS2 Clip Maker");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(e -> {
            mainController.shutdown();
            Platform.exit();
            System.exit(0);
        });

        stage.show();
        log.info("CS2 Clip Maker started");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
