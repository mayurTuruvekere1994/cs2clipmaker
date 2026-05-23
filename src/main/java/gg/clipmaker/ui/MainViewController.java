package gg.clipmaker.ui;

import gg.clipmaker.model.HighlightEvent;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * JavaFX controller for MainView.fxml.
 * Delegates all business logic to AppController.
 */
public class MainViewController implements Initializable {

    // ── FXML bindings ────────────────────────────────────────────────────────
    @FXML private Label         statusLabel;
    @FXML private Circle        statusDot;
    @FXML private Button        btnStartManual;
    @FXML private Button        btnStopManual;
    @FXML private Button        btnOpenClipsFolder;
    @FXML private ListView<String> highlightList;
    @FXML private ListView<String> clipsList;
    @FXML private ProgressBar   progressBar;
    @FXML private Label         progressLabel;

    // Settings panel
    @FXML private CheckBox      chkColorGrading;
    @FXML private CheckBox      chkTextOverlays;
    @FXML private Spinner<Integer> spnPreRoll;
    @FXML private Spinner<Integer> spnPostRoll;
    @FXML private Label         lblMusicFile;
    @FXML private Button        btnPickMusic;
    @FXML private Button        btnClearMusic;

    // ── Business logic ────────────────────────────────────────────────────────
    private final AppController controller = new AppController();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Wire spinners
        spnPreRoll.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(3, 30, 10));
        spnPostRoll.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 15, 5));
        spnPreRoll.valueProperty().addListener((obs, o, n) ->
            controller.getEditorSettings().preRollSeconds(n));
        spnPostRoll.valueProperty().addListener((obs, o, n) ->
            controller.getEditorSettings().postRollSeconds(n));

        // Wire checkboxes
        chkColorGrading.setSelected(true);
        chkColorGrading.selectedProperty().addListener((obs, o, n) ->
            controller.getEditorSettings().colorGrading(n));
        chkTextOverlays.setSelected(true);
        chkTextOverlays.selectedProperty().addListener((obs, o, n) ->
            controller.getEditorSettings().showTextOverlays(n));

        // Wire controller callbacks
        controller.onStatus(msg -> Platform.runLater(() -> {
            statusLabel.setText(msg);
            updateDot(msg);
        }));

        controller.onHighlight(event -> Platform.runLater(() ->
            highlightList.getItems().add(0, formatHighlight(event))
        ));

        controller.onMatchStart(() -> Platform.runLater(() -> {
            btnStartManual.setDisable(true);
            btnStopManual.setDisable(false);
            setRecordingState(true);
        }));

        controller.onMatchEnd(() -> Platform.runLater(() -> {
            btnStartManual.setDisable(false);
            btnStopManual.setDisable(true);
            setRecordingState(false);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressLabel.setText("Processing clips...");
        }));

        controller.onClipsReady(clips -> Platform.runLater(() -> {
            progressBar.setProgress(1.0);
            progressLabel.setText(clips.size() + " clips ready ✅");
            clips.forEach(path -> clipsList.getItems().add(0, new File(path).getName()));
        }));

        controller.onError(err -> Platform.runLater(() -> {
            showError(err);
            progressBar.setProgress(0);
            progressLabel.setText("Error");
        }));

        // Initialise (installs GSI config, starts server)
        controller.initialize();

        // Initial UI state
        btnStopManual.setDisable(true);
        progressBar.setProgress(0);
        progressLabel.setText("Waiting for CS2...");
    }

    // ── Button handlers ──────────────────────────────────────────────────────

    @FXML
    private void onStartManual() {
        controller.manualStartRecording();
        btnStartManual.setDisable(true);
        btnStopManual.setDisable(false);
        setRecordingState(true);
    }

    @FXML
    private void onStopManual() {
        controller.manualStopAndProcess();
        btnStartManual.setDisable(false);
        btnStopManual.setDisable(true);
        setRecordingState(false);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText("Processing clips...");
    }

    @FXML
    private void onOpenClipsFolder() {
        try {
            File dir = new File(gg.clipmaker.util.AppPaths.CLIPS_DIR);
            if (!dir.exists()) dir.mkdirs();
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            showError("Could not open folder: " + e.getMessage());
        }
    }

    @FXML
    private void onPickMusic() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Background Music");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.aac", "*.wav", "*.ogg", "*.m4a")
        );
        File file = chooser.showOpenDialog(btnPickMusic.getScene().getWindow());
        if (file != null) {
            controller.getEditorSettings().musicFilePath(file.getAbsolutePath());
            lblMusicFile.setText(file.getName());
        }
    }

    @FXML
    private void onClearMusic() {
        controller.getEditorSettings().musicFilePath(null);
        lblMusicFile.setText("No music selected");
    }

    @FXML
    private void onClipDoubleClick() {
        String selected = clipsList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            File clip = new File(gg.clipmaker.util.AppPaths.CLIPS_DIR, selected);
            try {
                Desktop.getDesktop().open(clip);
            } catch (Exception e) {
                showError("Could not open clip: " + e.getMessage());
            }
        }
    }

    // ── Called from App on window close ─────────────────────────────────────
    public void shutdown() {
        controller.shutdown();
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setRecordingState(boolean recording) {
        statusDot.setFill(recording ? Color.RED : Color.LIMEGREEN);
    }

    private void updateDot(String msg) {
        if (msg.contains("🔴")) statusDot.setFill(Color.RED);
        else if (msg.contains("🟢") || msg.contains("✅")) statusDot.setFill(Color.LIMEGREEN);
        else if (msg.contains("⚠️") || msg.contains("⚙️")) statusDot.setFill(Color.ORANGE);
        else if (msg.contains("❌")) statusDot.setFill(Color.CRIMSON);
    }

    private String formatHighlight(HighlightEvent e) {
        return String.format("%-12s  %s  (%d kills, %d HS)",
            e.getType().label, e.getPlayerName(), e.getRoundKills(), e.getRoundHsKills());
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        alert.setTitle("CS2 Clip Maker — Error");
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
