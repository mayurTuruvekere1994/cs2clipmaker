package gg.clipmaker.editor;

import gg.clipmaker.model.GameSession;
import gg.clipmaker.model.HighlightEvent;
import gg.clipmaker.util.AppPaths;
import gg.clipmaker.util.FFmpegLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Produces final vertical (9:16) short clips from a raw screen recording.
 *
 * For each HighlightEvent in the session:
 *   1. Trim ±preRoll / postRoll seconds around the kill timestamp
 *   2. Crop + scale to 1080×1920 (9:16 vertical for TikTok/Reels)
 *   3. Apply cinematic color grading (curves filter)
 *   4. Burn in text overlays (kill count + player name)
 *   5. Mix in background music (ducked under game audio)
 *   6. Export as MP4
 */
public class ClipEditor {

    private static final Logger log = LoggerFactory.getLogger(ClipEditor.class);

    private final String ffmpegPath;
    private final EditorSettings settings;

    // Progress callback: (highlightIndex, totalHighlights)
    private BiConsumer<Integer, Integer> progressCallback;

    public ClipEditor(EditorSettings settings) {
        this.ffmpegPath = FFmpegLocator.find();
        this.settings   = settings;
    }

    public void setProgressCallback(BiConsumer<Integer, Integer> cb) {
        this.progressCallback = cb;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Processes all highlights in the session and writes clips to the clips directory.
     * Blocking — run on a background thread.
     *
     * @return list of output clip file paths
     */
    public List<String> processSession(GameSession session) {
        List<String> outputs = new ArrayList<>();
        List<HighlightEvent> highlights = session.getHighlights();
        String rawVideo = session.getRawVideoPath();
        String rawAudio = session.getRawAudioPath(); // WAV from WasapiLoopbackCapture, may be null

        if (rawVideo == null || !new File(rawVideo).exists()) {
            log.error("Raw video not found: {}", rawVideo);
            return outputs;
        }
        if (rawAudio != null && !new File(rawAudio).exists()) {
            log.warn("Raw audio file missing, clips will have no audio: {}", rawAudio);
            rawAudio = null;
        }
        if (rawAudio != null) log.info("Using captured audio: {}", rawAudio);

        long recordingStartMs = session.getStartTime().toEpochMilli();
        String playerFilter = settings.getPlayerNameFilter();

        for (int i = 0; i < highlights.size(); i++) {
            HighlightEvent event = highlights.get(i);

            if (playerFilter != null && !playerFilter.equalsIgnoreCase(event.getPlayerName())) {
                log.info("Skipping clip {}/{}: player '{}' does not match filter '{}'",
                        i + 1, highlights.size(), event.getPlayerName(), playerFilter);
                continue;
            }

            // timestampMs = first kill; lastKillTimestampMs = last kill in the round.
            // Clip spans from (firstKill - preRoll) to (lastKill + postRoll), so every
            // kill in the sequence is always captured regardless of how spread out they are.
            double eventOffsetSec   = (event.getTimestampMs() - recordingStartMs) / 1000.0;
            double killSpanSec      = (event.getLastKillTimestampMs() - event.getTimestampMs()) / 1000.0;
            double clipStart        = Math.max(0, eventOffsetSec - settings.getPreRollSeconds());
            double clipDuration     = settings.getPreRollSeconds() + killSpanSec + settings.getPostRollSeconds();

            String outputPath = AppPaths.nextClipPath(event.getType().label, i + 1);

            log.info("Processing clip {}/{}: {} → {}", i + 1, highlights.size(),
                    event.getType().label, outputPath);

            try {
                renderClip(rawVideo, rawAudio, clipStart, clipDuration, event, outputPath);
                event.setExportedClipPath(outputPath);
                outputs.add(outputPath);
                log.info("Clip written: {}", outputPath);
            } catch (Exception e) {
                log.error("Failed to render clip for {}", event, e);
            }

            if (progressCallback != null) {
                progressCallback.accept(i + 1, highlights.size());
            }
        }

        return outputs;
    }

    // ── FFmpeg render pipeline ───────────────────────────────────────────────

    private void renderClip(String rawVideo, String rawAudio, double startSec, double durationSec,
                            HighlightEvent event, String outputPath) throws Exception {

        Files.createDirectories(Paths.get(outputPath).getParent());

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        // ── Input 0: raw gameplay video (seek to event offset) ────────────────
        cmd.add("-ss"); cmd.add(String.format("%.3f", startSec));
        cmd.add("-i");  cmd.add(rawVideo);

        // ── Input 1: captured game audio WAV (same seek offset) ───────────────
        boolean hasAudio = rawAudio != null && new File(rawAudio).exists();
        if (hasAudio) {
            cmd.add("-ss"); cmd.add(String.format("%.3f", startSec));
            cmd.add("-i");  cmd.add(rawAudio);
        }

        // ── Input 2: background music (optional) ──────────────────────────────
        String musicFile = settings.getMusicFilePath();
        boolean hasMusic = musicFile != null && new File(musicFile).exists();
        if (hasMusic) {
            cmd.add("-stream_loop"); cmd.add("-1");
            cmd.add("-i");          cmd.add(musicFile);
        }

        // ── Duration ──────────────────────────────────────────────────────────
        cmd.add("-t"); cmd.add(String.format("%.3f", durationSec));

        // ── Video filter chain ────────────────────────────────────────────────
        cmd.add("-vf"); cmd.add(buildVideoFilterChain(event));

        // ── Audio routing ─────────────────────────────────────────────────────
        if (hasAudio && hasMusic) {
            // Mix captured game audio with background music
            int musicIdx = 2;
            cmd.add("-filter_complex");
            cmd.add("[1:a]volume=0.6[game];[" + musicIdx + ":a]volume=0.7[music];" +
                    "[game][music]amix=inputs=2:duration=first[aout]");
            cmd.add("-map"); cmd.add("0:v");
            cmd.add("-map"); cmd.add("[aout]");
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("128k");
        } else if (hasAudio) {
            cmd.add("-map"); cmd.add("0:v");
            cmd.add("-map"); cmd.add("1:a");
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("128k");
        } else if (hasMusic) {
            cmd.add("-map"); cmd.add("0:v");
            cmd.add("-map"); cmd.add("1:a");
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("128k");
        } else {
            cmd.add("-map"); cmd.add("0:v");
            cmd.add("-an");
        }

        // ── Output encoding ───────────────────────────────────────────────────
        cmd.add("-c:v");      cmd.add("libx264");
        cmd.add("-preset");   cmd.add("fast");
        cmd.add("-crf");      cmd.add("20");
        cmd.add("-pix_fmt");  cmd.add("yuv420p");      // 8-bit 4:2:0 — universally compatible
        cmd.add("-color_primaries"); cmd.add("bt709"); // normalise away any bt470bg tags
        cmd.add("-color_trc");       cmd.add("bt709"); // from the raw recording
        cmd.add("-colorspace");      cmd.add("bt709");
        cmd.add("-color_range");     cmd.add("tv");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-y");
        cmd.add(outputPath);

        log.info("FFmpeg cmd: {}", String.join(" ", cmd));

        Process proc = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();

        // Log FFmpeg output at debug level
        String ffmpegOut = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            log.error("FFmpeg exited with code {}:\n{}", exitCode, ffmpegOut);
            throw new RuntimeException("FFmpeg failed with exit code " + exitCode);
        }
    }

    /**
     * Builds the FFmpeg video filter chain:
     *   crop → scale to 9:16 → cinematic colour grade → text overlays
     */
    private String buildVideoFilterChain(HighlightEvent event) {
        StringBuilder vf = new StringBuilder();

        // 1. Crop centre square, then scale to 1080x1920 (9:16 vertical)
        //    For a 1920x1080 source: crop the middle 1080x1080, pad to 1920 height
        vf.append("crop=ih*9/16:ih:(iw-ih*9/16)/2:0,");     // centre crop
        vf.append("scale=1080:1920:flags=lanczos,");          // scale to 9:16

        // 2. Cinematic colour grade (curves filter)
        //    Slightly crushes blacks, boosts mids — Allstars-style warm/cinematic look
        if (settings.isColorGrading()) {
            vf.append("curves=");
            vf.append("r='0/0 0.1/0.05 0.5/0.55 0.9/0.95 1/1':");
            vf.append("g='0/0 0.1/0.08 0.5/0.52 1/1':");
            vf.append("b='0/0 0.1/0.12 0.5/0.48 1/0.95',");

            // Slight vignette for cinematic feel
            vf.append("vignette=PI/4,");
        }

        // 3. Text overlays (drawtext filter)
        if (settings.isShowTextOverlays()) {
            String playerName = event.getPlayerName();
            String killLabel  = event.getType().label;
            int    kills      = event.getRoundKills();

            // Kill label — large, centred, near top third
            vf.append(buildDrawText(killLabel, 540, 400, 72, "white", true));
            vf.append(",");

            // Player name — smaller, below kill label
            vf.append(buildDrawText(playerName, 540, 490, 40, "#CCCCCC", false));
            vf.append(",");

            // Kill count badge — bottom left
            vf.append(buildDrawText(kills + " KILLS", 80, 1800, 36, "#FF4444", false));
        }

        // Remove trailing comma if present
        String result = vf.toString();
        if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        return result;
    }

    /**
     * Builds an FFmpeg drawtext filter fragment.
     * Uses textfile= to safely handle Unicode (Chinese, etc.) player names.
     */
    private String buildDrawText(String text, int x, int y, int size,
                                  String color, boolean bold) {
        // FFmpeg filtergraph has two escaping levels:
        //   level 1 (filtergraph): '...' strips the quotes and passes content literally to level 2
        //   level 2 (option value): \: is required so the drive colon isn't treated as an option separator
        // Correct form: 'C\:/path' — single quotes protect \: from being consumed at level 1,
        // then \: does its job at level 2.
        String fontFile = bold
            ? "'C\\:/Windows/Fonts/arialbd.ttf'"
            : "'C\\:/Windows/Fonts/arial.ttf'";

        try {
            Path textFile = Files.createTempFile("dt_", ".txt");
            Files.writeString(textFile, text, StandardCharsets.UTF_8);
            textFile.toFile().deleteOnExit();
            // forward slashes + \: for the drive colon, wrapped in single quotes
            String textFilePath = "'"
                + textFile.toString().replace('\\', '/').replaceFirst("^([A-Za-z]):", "$1\\\\:")
                + "'";
            return String.format(
                "drawtext=fontfile=%s:textfile=%s:x=%d:y=%d:fontsize=%d:" +
                "fontcolor=%s:borderw=3:bordercolor=black@0.8:shadowx=2:shadowy=2",
                fontFile, textFilePath, x, y, size, color
            );
        } catch (Exception e) {
            log.error("Failed to create textfile for drawtext, falling back to ASCII: {}", e.getMessage());
            String safe = text.replaceAll("[^\\x20-\\x7E]", "?")
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace(",", "\\,");
            return String.format(
                "drawtext=fontfile=%s:text='%s':x=%d:y=%d:fontsize=%d:" +
                "fontcolor=%s:borderw=3:bordercolor=black@0.8:shadowx=2:shadowy=2",
                fontFile, safe, x, y, size, color
            );
        }
    }

}
