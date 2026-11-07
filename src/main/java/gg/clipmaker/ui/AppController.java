package gg.clipmaker.ui;

import gg.clipmaker.editor.ClipEditor;
import gg.clipmaker.editor.EditorSettings;
import gg.clipmaker.gsi.CS2GSIServer;
import gg.clipmaker.gsi.GSIConfigInstaller;
import gg.clipmaker.model.GameSession;
import gg.clipmaker.model.HighlightEvent;
import gg.clipmaker.recorder.RecorderSettings;
import gg.clipmaker.recorder.ScreenRecorder;
import gg.clipmaker.util.AppPaths;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Central coordinator — wires together:
 *   CS2GSIServer  (kill event detection)
 *   ScreenRecorder (FFmpeg background capture)
 *   ClipEditor    (post-match processing)
 *
 * The JavaFX UI talks only to this class.
 */
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    private final CS2GSIServer gsiServer;
    private ScreenRecorder recorder;
    private GameSession activeSession;

    private final ExecutorService bgThread = Executors.newSingleThreadExecutor();

    // ── UI callbacks (always dispatched on JavaFX thread) ────────────────────
    private Consumer<String>         onStatus;
    private Consumer<HighlightEvent> onHighlight;
    private Consumer<List<String>>   onClipsReady;
    private Consumer<String>         onError;
    private Runnable                 onMatchStart;
    private Runnable                 onMatchEnd;

    public AppController() {
        this.gsiServer = new CS2GSIServer();
    }

    // ── Initialisation ───────────────────────────────────────────────────────

    /**
     * Must be called once at startup.
     * Installs GSI config into CS2 and starts the local GSI HTTP server.
     */
    public void initialize() {
        AppPaths.ensureDirectories();

        // Install GSI config into CS2's cfg folder
        bgThread.submit(() -> {
            if (!GSIConfigInstaller.isInstalled()) {
                String path = GSIConfigInstaller.install();
                if (path != null) {
                    ui(() -> emit(onStatus, "✅ GSI config installed. Restart CS2 to activate."));
                } else {
                    ui(() -> emit(onStatus, "⚠️ Could not auto-install GSI config. See manual setup."));
                }
            } else {
                ui(() -> emit(onStatus, "✅ GSI config already installed."));
            }

            // Wire GSI events
            gsiServer.addHighlightListener(event -> {
                log.info("Highlight: {}", event);
                ui(() -> emit(onHighlight, event));
                ui(() -> emit(onStatus, "🎯 " + event.getType().label + " detected!"));
            });

            gsiServer.addMatchStartListener(() -> {
                log.info("Match started — beginning recording");
                bgThread.submit(() -> {
                    startRecordingSession();
                    ui(() -> emit(onStatus, "🔴 Recording started"));
                });
                ui(() -> { if (onMatchStart != null) onMatchStart.run(); });
            });

            gsiServer.addMatchEndListener(() -> {
                log.info("Match ended — stopping recording and processing clips");
                bgThread.submit(this::stopAndProcess);
                ui(() -> { if (onMatchEnd != null) onMatchEnd.run(); });
            });

            // Start the GSI HTTP server
            try {
                gsiServer.start();
                ui(() -> emit(onStatus, "🟢 Listening for CS2... Launch CS2 to begin."));
            } catch (Exception e) {
                log.error("Failed to start GSI server", e);
                ui(() -> emit(onError, "Failed to start GSI server: " + e.getMessage()));
            }
        });
    }

    // ── Recording lifecycle ──────────────────────────────────────────────────

    private void startRecordingSession() {
        if (recorder != null && recorder.isRecording()) return;
        activeSession = new GameSession();
        gsiServer.setActiveSession(activeSession);

        RecorderSettings recSettings = new RecorderSettings()
            .fps(30)  // 30 fps halves capture CPU load vs 60 fps — sufficient for highlights
            .useNvenc(true)
            .recordAudio(true);

        recorder = new ScreenRecorder(AppPaths.RAW_RECORDINGS_DIR, recSettings);

        try {
            String rawPath = recorder.startRecording();
            activeSession.setRawVideoPath(rawPath);
            // Sync session start time to the moment FFmpeg confirmed alive, not object construction
            activeSession.setStartTime(java.time.Instant.ofEpochMilli(recorder.getRecordingStartMs()));
            activeSession.setState(GameSession.State.RECORDING);
        } catch (Exception e) {
            log.error("Failed to start recording", e);
            ui(() -> emit(onError, "Recording failed to start: " + e.getMessage()));
        }
    }

    private void stopAndProcess() {
        if (recorder == null || !recorder.isRecording()) return;

        String rawVideo = recorder.stopRecording();
        activeSession.setRawVideoPath(rawVideo);
        activeSession.setRawAudioPath(recorder.getAudioCapturePath());
        activeSession.finish();

        if (activeSession.getHighlightCount() == 0) {
            ui(() -> emit(onStatus, "ℹ️ Match finished — no highlights detected."));
            return;
        }

        ui(() -> emit(onStatus, "⚙️ Processing " + activeSession.getHighlightCount() + " clips..."));

        // Run clip editing on background thread
        bgThread.submit(() -> {
            try {
                EditorSettings edSettings = buildEditorSettings();
                ClipEditor editor = new ClipEditor(edSettings);

                editor.setProgressCallback((done, total) ->
                    ui(() -> emit(onStatus, "⚙️ Clip " + done + "/" + total + " rendering..."))
                );

                List<String> clips = editor.processSession(activeSession);
                activeSession.setState(GameSession.State.DONE);

                deleteRawFiles(rawVideo, activeSession.getRawAudioPath());

                ui(() -> emit(onStatus, "✅ " + clips.size() + " clips ready in Videos/CS2ClipMaker/clips/"));
                ui(() -> emit(onClipsReady, clips));
            } catch (Exception e) {
                log.error("Clip processing failed", e);
                activeSession.setState(GameSession.State.ERROR);
                ui(() -> emit(onError, "Clip processing failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Manual trigger — lets user force-start recording without waiting for GSI match start.
     */
    public void manualStartRecording() {
        bgThread.submit(() -> {
            startRecordingSession();
            ui(() -> emit(onStatus, "🔴 Manual recording started"));
        });
    }

    /**
     * Manual trigger — stops recording and processes whatever was captured.
     */
    public void manualStopAndProcess() {
        bgThread.submit(this::stopAndProcess);
    }

    private void deleteRawFiles(String video, String audio) {
        for (String path : new String[]{video, audio}) {
            if (path == null) continue;
            java.io.File f = new java.io.File(path);
            if (f.delete()) {
                log.info("Deleted raw file: {}", path);
            } else if (f.exists()) {
                log.warn("Could not delete raw file: {}", path);
            }
        }
    }

    public void shutdown() {
        gsiServer.stop();
        if (recorder != null && recorder.isRecording()) recorder.stopRecording();
        bgThread.shutdownNow();
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private EditorSettings editorSettings = new EditorSettings()
            .playerNameFilter("maxthecoolest");

    public EditorSettings getEditorSettings() { return editorSettings; }

    private EditorSettings buildEditorSettings() {
        return editorSettings; // UI updates this object in place
    }

    // ── Callback registration ─────────────────────────────────────────────────

    public void onStatus(Consumer<String> cb)          { this.onStatus = cb; }
    public void onHighlight(Consumer<HighlightEvent> cb){ this.onHighlight = cb; }
    public void onClipsReady(Consumer<List<String>> cb) { this.onClipsReady = cb; }
    public void onError(Consumer<String> cb)           { this.onError = cb; }
    public void onMatchStart(Runnable cb)              { this.onMatchStart = cb; }
    public void onMatchEnd(Runnable cb)                { this.onMatchEnd = cb; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private <T> void emit(Consumer<T> cb, T value) {
        if (cb != null) cb.accept(value);
    }

    /** Dispatch a runnable to the JavaFX UI thread. */
    private void ui(Runnable r) {
        Platform.runLater(r);
    }
}
