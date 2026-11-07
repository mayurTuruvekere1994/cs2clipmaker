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
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

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
    private static final double MIN_HIGHLIGHTS_DURATION_SEC = 8 * 60.0; // 8 minutes
    private static final double MAX_SHORTS_DURATION_SEC     = 60.0;      // 60 seconds
    private static final double SHORTS_PRE_ROLL_SEC         = 2.0;
    private static final double SHORTS_POST_ROLL_SEC        = 1.0;

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
     * Processes highlights and writes three types of output to the clips directory:
     *   1. Per-round vertical shorts (9:16) — only for rounds with 3+ kills
     *   2. Match highlights landscape video (16:9) — all kills across the match
     *   3. Match highlights vertical shorts (9:16) — same but cropped to 9:16
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

        // Collect all events for this player (used for match highlights compilation).
        List<HighlightEvent> playerEvents = new ArrayList<>();

        for (int i = 0; i < highlights.size(); i++) {
            HighlightEvent event = highlights.get(i);

            if (playerFilter != null && !playerFilter.equalsIgnoreCase(event.getPlayerName())) {
                log.info("Skipping clip {}/{}: player '{}' does not match filter '{}'",
                        i + 1, highlights.size(), event.getPlayerName(), playerFilter);
                continue;
            }

            playerEvents.add(event);

            // Per-round shorts: only render for rounds with 3 or more kills.
            if (event.getRoundKills() < 3) {
                log.info("Skipping per-round short {}/{}: {} kill(s) (need 3+)",
                        i + 1, highlights.size(), event.getRoundKills());
                if (progressCallback != null) progressCallback.accept(i + 1, highlights.size());
                continue;
            }

            // Build per-kill windows (preRoll before each kill, postRoll after each kill).
            // Overlapping windows are merged; gaps wider than preRoll+postRoll are cut.
            List<double[]> segments = computeKillSegments(
                    event, recordingStartMs,
                    settings.getPreRollSeconds(), settings.getPostRollSeconds());

            String outputPath = AppPaths.nextClipPath(event.getType().label, i + 1);

            log.info("Processing per-round short {}/{}: {} → {} ({} segment(s))",
                    i + 1, highlights.size(), event.getType().label, outputPath, segments.size());

            String hookText = AIHookGenerator.generate(event);
            if (hookText == null) hookText = HookTextBank.pick(event);

            try {
                if (segments.size() == 1) {
                    renderClip(rawVideo, rawAudio,
                            segments.get(0)[0], segments.get(0)[1], event, outputPath, hookText);
                } else {
                    renderSegmentedClip(rawVideo, rawAudio, segments, event, outputPath, hookText);
                }
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

        // Match highlights: compile ALL player kills into one landscape + one shorts video.
        if (!playerEvents.isEmpty()) {
            try {
                List<String> hlOutputs = renderMatchHighlights(
                        rawVideo, rawAudio, playerEvents, recordingStartMs);
                outputs.addAll(hlOutputs);
            } catch (Exception e) {
                log.error("Failed to render match highlights", e);
            }
        }

        return outputs;
    }

    // ── FFmpeg render pipeline ───────────────────────────────────────────────

    private void renderClip(String rawVideo, String rawAudio, double startSec, double durationSec,
                            HighlightEvent event, String outputPath, String hookText) throws Exception {

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
        cmd.add("-vf"); cmd.add(buildVideoFilterChain(event, hookText));

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
    private String buildVideoFilterChain(HighlightEvent event, String hookText) {
        StringBuilder vf = new StringBuilder();

        // 1. Crop centre square, then scale to 1080x1920 (9:16 vertical)
        vf.append("crop=ih*9/16:ih:(iw-ih*9/16)/2:0,");
        vf.append("scale=1080:1920:flags=lanczos,");

        // 2. Cinematic colour grade
        if (settings.isColorGrading()) {
            vf.append("curves=");
            vf.append("r='0/0 0.1/0.05 0.5/0.55 0.9/0.95 1/1':");
            vf.append("g='0/0 0.1/0.08 0.5/0.52 1/1':");
            vf.append("b='0/0 0.1/0.12 0.5/0.48 1/0.95',");
            vf.append("vignette=PI/4,");
        }

        // 3. Text overlays
        if (settings.isShowTextOverlays()) {
            String playerName = event.getPlayerName();
            String killLabel  = event.getType().label;
            int    kills      = event.getRoundKills();

            vf.append(buildDrawText(killLabel, 540, 620, 72, "white", true));
            vf.append(",");
            vf.append(buildDrawText(playerName, 540, 710, 40, "#CCCCCC", false));
            vf.append(",");
            vf.append(buildDrawText(kills + " KILLS", 80, 1800, 36, "#FF4444", false));
        }

        // 4. Hook text — shown only for the first 3 seconds, centred near top
        if (hookText != null && !hookText.isBlank()) {
            if (!vf.toString().endsWith(",") && vf.length() > 0) vf.append(",");
            vf.append(buildHookDrawText(hookText, 56, 3.0));
        }

        String result = vf.toString();
        if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        return result;
    }

    // ── Match highlights compilation ─────────────────────────────────────────

    /**
     * Renders the full-match highlights in two formats:
     *   [0] landscape 16:9 — all kills, padded to at least 8 minutes
     *   [1] vertical 9:16 shorts — best kills only, max 60 seconds
     *
     * Both use the same N-input filter_complex concat approach as renderClip() so
     * audio and video are seeked identically for each segment, guaranteeing sync.
     * The old concat-demuxer approach caused drift because video keyframe seeking
     * and WAV byte seeking produced slightly different segment lengths per event.
     */
    private List<String> renderMatchHighlights(String rawVideo, String rawAudio,
                                                List<HighlightEvent> events,
                                                long recordingStartMs) throws Exception {
        List<String> outputs = new ArrayList<>();
        String playerName = events.get(0).getPlayerName();

        // ── Landscape: extend windows until total >= 8 minutes ───────────────
        double basePre  = settings.getPreRollSeconds();
        double basePost = settings.getPostRollSeconds();
        double totalSec = 0;
        for (HighlightEvent e : events) {
            double span = (e.getLastKillTimestampMs() - e.getTimestampMs()) / 1000.0;
            totalSec += basePre + span + basePost;
        }
        double extraHalf = 0;
        if (totalSec < MIN_HIGHLIGHTS_DURATION_SEC) {
            double extra = (MIN_HIGHLIGHTS_DURATION_SEC - totalSec) / events.size();
            extraHalf = extra / 2.0;
            log.info("Highlights total {}s < {}s minimum — adding {}s per event window",
                    (int) totalSec, (int) MIN_HIGHLIGHTS_DURATION_SEC,
                    String.format("%.1f", extra));
        }
        double effectivePre  = basePre  + extraHalf;
        double effectivePost = basePost + extraHalf;

        String landscapePath = AppPaths.nextHighlightPath("video");
        log.info("Rendering match highlights landscape ({} events): {}", events.size(), landscapePath);
        // Landscape video gets no hook text — it's meant for full-screen viewing, not Shorts
        renderHighlightsVideo(events, rawVideo, rawAudio, recordingStartMs,
                effectivePre, effectivePost, playerName, false, null, landscapePath);
        outputs.add(landscapePath);

        // ── Shorts: tightest pre/post roll, best kills first, max 60 s ───────
        List<HighlightEvent> shortsEvents = selectEventsForShorts(
                events, MAX_SHORTS_DURATION_SEC, SHORTS_PRE_ROLL_SEC, SHORTS_POST_ROLL_SEC);

        long seed = shortsEvents.get(0).getTimestampMs();
        String shortsHookText = AIHookGenerator.generateForHighlights(playerName);
        if (shortsHookText == null) shortsHookText = HookTextBank.pickForHighlights(seed);

        String shortsPath = AppPaths.nextHighlightPath("shorts");
        log.info("Rendering match highlights shorts ({} events, max {}s): {}",
                shortsEvents.size(), (int) MAX_SHORTS_DURATION_SEC, shortsPath);
        renderHighlightsVideo(shortsEvents, rawVideo, rawAudio, recordingStartMs,
                SHORTS_PRE_ROLL_SEC, SHORTS_POST_ROLL_SEC, playerName, true, shortsHookText, shortsPath);
        outputs.add(shortsPath);

        return outputs;
    }

    /**
     * Selects which events to include in the highlights shorts, prioritising the
     * highest kill-count rounds first and stopping once the total would exceed
     * maxTotalSec. Always includes at least one event. Returns events in
     * chronological order so playback feels natural.
     */
    private List<HighlightEvent> selectEventsForShorts(List<HighlightEvent> allEvents,
                                                        double maxTotalSec,
                                                        double preRoll, double postRoll) {
        List<HighlightEvent> sorted = new ArrayList<>(allEvents);
        sorted.sort((a, b) -> b.getRoundKills() - a.getRoundKills()); // best first

        List<HighlightEvent> selected = new ArrayList<>();
        double total = 0;
        for (HighlightEvent e : sorted) {
            double span = (e.getLastKillTimestampMs() - e.getTimestampMs()) / 1000.0;
            double seg  = preRoll + span + postRoll;
            if (total + seg <= maxTotalSec || selected.isEmpty()) {
                selected.add(e);
                total += seg;
            }
        }
        selected.sort(Comparator.comparingLong(HighlightEvent::getTimestampMs));
        return selected;
    }

    /**
     * Renders a highlights video by feeding each event as its own pair of
     * -ss/-t -i inputs (video + audio), identical to what renderClip() does for
     * per-round shorts. Audio and video are seeked together, so no drift can
     * accumulate across segments.
     *
     * The filter_complex uses a two-stage approach:
     *   Stage 1 (per-segment): crop/scale/grade/vignette applied to each segment independently
     *   Stage 2 (post-concat): text overlays including hook text, applied once after concat
     *                          so enable='lt(t,3)' counts from the VIDEO start, not each segment start
     *
     * Audio: each WASAPI WAV segment is normalised with aformat before concat to avoid
     * sample-format mismatches with WAVE_FORMAT_EXTENSIBLE (32-bit float) input.
     */
    private void renderHighlightsVideo(List<HighlightEvent> events,
                                        String rawVideo, String rawAudio,
                                        long recordingStartMs,
                                        double preRoll, double postRoll,
                                        String playerName, boolean vertical,
                                        String hookText,
                                        String outputPath) throws Exception {
        Files.createDirectories(Paths.get(outputPath).getParent());

        int N = events.size();
        boolean hasAudio = rawAudio != null && new File(rawAudio).exists();
        String musicFile = settings.getMusicFilePath();
        boolean hasMusic = musicFile != null && new File(musicFile).exists();

        double[] starts    = new double[N];
        double[] durations = new double[N];
        for (int i = 0; i < N; i++) {
            HighlightEvent e   = events.get(i);
            double offset      = (e.getTimestampMs() - recordingStartMs) / 1000.0;
            double span        = (e.getLastKillTimestampMs() - e.getTimestampMs()) / 1000.0;
            starts[i]          = Math.max(0, offset - preRoll);
            durations[i]       = preRoll + span + postRoll;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        for (int i = 0; i < N; i++) {
            cmd.add("-ss"); cmd.add(String.format("%.3f", starts[i]));
            cmd.add("-t");  cmd.add(String.format("%.3f", durations[i]));
            cmd.add("-i");  cmd.add(rawVideo);
        }
        if (hasAudio) {
            for (int i = 0; i < N; i++) {
                cmd.add("-ss"); cmd.add(String.format("%.3f", starts[i]));
                cmd.add("-t");  cmd.add(String.format("%.3f", durations[i]));
                cmd.add("-i");  cmd.add(rawAudio);
            }
        }
        if (hasMusic) {
            cmd.add("-stream_loop"); cmd.add("-1");
            cmd.add("-i"); cmd.add(musicFile);
        }

        // Stage 1: per-segment pre-concat filter (geo + grade — NO text overlays)
        String preConcat  = buildHighlightsPreConcatFilter(vertical);
        // Stage 2: post-concat text overlays including hook (applied once, timer is correct)
        String postConcat = buildHighlightsPostConcatFilter(playerName, vertical, hookText);

        StringBuilder fc = new StringBuilder();

        // Apply pre-concat filter to each segment
        for (int i = 0; i < N; i++) {
            fc.append("[").append(i).append(":v]")
              .append(preConcat)
              .append("[pv").append(i).append("];");
        }
        // Concat all segments
        for (int i = 0; i < N; i++) fc.append("[pv").append(i).append("]");
        fc.append("concat=n=").append(N).append(":v=1:a=0[vconcat];");
        // Apply post-concat filter (text overlays + hook)
        fc.append("[vconcat]").append(postConcat.isEmpty() ? "null" : postConcat).append("[vout]");

        // Audio: normalise each segment's format (WASAPI produces WAVE_FORMAT_EXTENSIBLE /
        // 32-bit float which concat rejects without an explicit aformat conversion first)
        if (hasAudio) {
            int aBase = N;
            for (int i = 0; i < N; i++) {
                fc.append(";[").append(aBase + i).append(":a]")
                  .append("aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=stereo")
                  .append("[af").append(i).append("]");
            }
            fc.append(";");
            for (int i = 0; i < N; i++) fc.append("[af").append(i).append("]");
            fc.append("concat=n=").append(N).append(":v=0:a=1[acat]");
            if (hasMusic) {
                int mIdx = 2 * N;
                fc.append(";[acat]volume=0.6[game];[").append(mIdx).append(":a]volume=0.7[music];")
                  .append("[game][music]amix=inputs=2:duration=first[aout]");
            }
        } else if (hasMusic) {
            fc.append(";[").append(N).append(":a]volume=0.7[aout]");
        }

        cmd.add("-filter_complex"); cmd.add(fc.toString());
        cmd.add("-map"); cmd.add("[vout]");

        if (hasAudio && hasMusic) {
            cmd.add("-map"); cmd.add("[aout]");
            cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("128k");
        } else if (hasAudio) {
            cmd.add("-map"); cmd.add("[acat]");
            cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("128k");
        } else if (hasMusic) {
            cmd.add("-map"); cmd.add("[aout]");
            cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("128k");
        } else {
            cmd.add("-an");
        }

        cmd.add("-c:v");      cmd.add("libx264");
        cmd.add("-preset");   cmd.add("fast");
        cmd.add("-crf");      cmd.add("20");
        cmd.add("-pix_fmt");  cmd.add("yuv420p");
        cmd.add("-color_primaries"); cmd.add("bt709");
        cmd.add("-color_trc");       cmd.add("bt709");
        cmd.add("-colorspace");      cmd.add("bt709");
        cmd.add("-color_range");     cmd.add("tv");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-y");
        cmd.add(outputPath);

        log.info("FFmpeg highlights cmd ({} segs): {}", N, String.join(" ", cmd));

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String ffmpegOut = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            log.error("FFmpeg highlights exited {} for {}:\n{}", exitCode, outputPath, ffmpegOut);
            throw new RuntimeException("FFmpeg highlights failed with exit code " + exitCode);
        }
    }

    // ── Per-kill gap cutting ─────────────────────────────────────────────────

    /**
     * Computes merged [startSec, durationSec] windows from the event's individual
     * kill timestamps. Each kill gets its own preRoll/postRoll window; overlapping
     * windows are merged. Gaps wider than preRoll+postRoll become hard cuts.
     */
    private List<double[]> computeKillSegments(HighlightEvent event, long recordingStartMs,
                                                double preRoll, double postRoll) {
        List<Long> kills = event.getKillTimestamps();
        if (kills == null || kills.isEmpty()) {
            double start = Math.max(0, (event.getTimestampMs() - recordingStartMs) / 1000.0 - preRoll);
            double span  = (event.getLastKillTimestampMs() - event.getTimestampMs()) / 1000.0;
            return List.of(new double[]{start, preRoll + span + postRoll});
        }

        List<double[]> windows = new ArrayList<>();
        for (long ts : kills) {
            double offset = (ts - recordingStartMs) / 1000.0;
            windows.add(new double[]{Math.max(0, offset - preRoll), offset + postRoll});
        }
        windows.sort(Comparator.comparingDouble(w -> w[0]));

        List<double[]> merged = new ArrayList<>();
        for (double[] w : windows) {
            if (merged.isEmpty() || w[0] > merged.get(merged.size() - 1)[1]) {
                merged.add(new double[]{w[0], w[1]});
            } else {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], w[1]);
            }
        }

        return merged.stream()
                .map(w -> new double[]{w[0], w[1] - w[0]})
                .collect(Collectors.toList());
    }

    /**
     * Renders a per-round short from multiple disjoint kill windows, hard-cutting
     * the gaps between them. Audio is normalised per-segment before concat to avoid
     * WASAPI WAVE_FORMAT_EXTENSIBLE mismatches.
     */
    private void renderSegmentedClip(String rawVideo, String rawAudio,
                                      List<double[]> segments, HighlightEvent event,
                                      String outputPath, String hookText) throws Exception {
        Files.createDirectories(Paths.get(outputPath).getParent());

        int N = segments.size();
        boolean hasAudio = rawAudio != null && new File(rawAudio).exists();
        String musicFile = settings.getMusicFilePath();
        boolean hasMusic = musicFile != null && new File(musicFile).exists();

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        for (double[] seg : segments) {
            cmd.add("-ss"); cmd.add(String.format("%.3f", seg[0]));
            cmd.add("-t");  cmd.add(String.format("%.3f", seg[1]));
            cmd.add("-i");  cmd.add(rawVideo);
        }
        if (hasAudio) {
            for (double[] seg : segments) {
                cmd.add("-ss"); cmd.add(String.format("%.3f", seg[0]));
                cmd.add("-t");  cmd.add(String.format("%.3f", seg[1]));
                cmd.add("-i");  cmd.add(rawAudio);
            }
        }
        if (hasMusic) {
            cmd.add("-stream_loop"); cmd.add("-1");
            cmd.add("-i"); cmd.add(musicFile);
        }

        String preConcat  = buildHighlightsPreConcatFilter(true); // per-round shorts are always vertical
        String postConcat = buildPerRoundPostConcatFilter(event, hookText);

        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < N; i++) {
            fc.append("[").append(i).append(":v]")
              .append(preConcat)
              .append("[pv").append(i).append("];");
        }
        for (int i = 0; i < N; i++) fc.append("[pv").append(i).append("]");
        fc.append("concat=n=").append(N).append(":v=1:a=0[vconcat];");
        fc.append("[vconcat]").append(postConcat.isEmpty() ? "null" : postConcat).append("[vout]");

        if (hasAudio) {
            int aBase = N;
            for (int i = 0; i < N; i++) {
                fc.append(";[").append(aBase + i).append(":a]")
                  .append("aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=stereo")
                  .append("[af").append(i).append("]");
            }
            fc.append(";");
            for (int i = 0; i < N; i++) fc.append("[af").append(i).append("]");
            fc.append("concat=n=").append(N).append(":v=0:a=1[acat]");
            if (hasMusic) {
                int mIdx = 2 * N;
                fc.append(";[acat]volume=0.6[game];[").append(mIdx).append(":a]volume=0.7[music];")
                  .append("[game][music]amix=inputs=2:duration=first[aout]");
            }
        } else if (hasMusic) {
            fc.append(";[").append(N).append(":a]volume=0.7[aout]");
        }

        cmd.add("-filter_complex"); cmd.add(fc.toString());
        cmd.add("-map"); cmd.add("[vout]");

        if (hasAudio && hasMusic) {
            cmd.add("-map"); cmd.add("[aout]");
            cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("128k");
        } else if (hasAudio) {
            cmd.add("-map"); cmd.add("[acat]");
            cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("128k");
        } else if (hasMusic) {
            cmd.add("-map"); cmd.add("[aout]");
            cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("128k");
        } else {
            cmd.add("-an");
        }

        cmd.add("-c:v");      cmd.add("libx264");
        cmd.add("-preset");   cmd.add("fast");
        cmd.add("-crf");      cmd.add("20");
        cmd.add("-pix_fmt");  cmd.add("yuv420p");
        cmd.add("-color_primaries"); cmd.add("bt709");
        cmd.add("-color_trc");       cmd.add("bt709");
        cmd.add("-colorspace");      cmd.add("bt709");
        cmd.add("-color_range");     cmd.add("tv");
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-y");
        cmd.add(outputPath);

        log.info("FFmpeg segmented clip cmd ({} segs, cutting {} gap(s)): {}",
                N, N - 1, String.join(" ", cmd));

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String ffmpegOut = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            log.error("FFmpeg segmented clip exited {}:\n{}", exitCode, ffmpegOut);
            throw new RuntimeException("FFmpeg segmented clip failed with exit code " + exitCode);
        }
    }

    /**
     * Text overlays for a per-round short, applied post-concat so hook enable='lt(t,3)'
     * counts from the start of the final video rather than from each kill segment.
     */
    private String buildPerRoundPostConcatFilter(HighlightEvent event, String hookText) {
        StringBuilder vf = new StringBuilder();
        if (settings.isShowTextOverlays()) {
            vf.append(buildDrawText(event.getType().label, 540, 620, 72, "white", true));
            vf.append(",");
            vf.append(buildDrawText(event.getPlayerName(), 540, 710, 40, "#CCCCCC", false));
            vf.append(",");
            vf.append(buildDrawText(event.getRoundKills() + " KILLS", 80, 1800, 36, "#FF4444", false));
        }
        if (hookText != null && !hookText.isBlank()) {
            if (vf.length() > 0) vf.append(",");
            vf.append(buildHookDrawText(hookText, 56, 3.0));
        }
        String result = vf.toString();
        if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        return result;
    }

    /** Geo + colour grade only — applied per-segment so each input is normalised before concat. */
    private String buildHighlightsPreConcatFilter(boolean vertical) {
        StringBuilder vf = new StringBuilder();
        if (vertical) {
            vf.append("crop=ih*9/16:ih:(iw-ih*9/16)/2:0,");
            vf.append("scale=1080:1920:flags=lanczos");
        } else {
            vf.append("scale=1920:1080:flags=lanczos");
        }
        if (settings.isColorGrading()) {
            vf.append(",curves=");
            vf.append("r='0/0 0.1/0.05 0.5/0.55 0.9/0.95 1/1':");
            vf.append("g='0/0 0.1/0.08 0.5/0.52 1/1':");
            vf.append("b='0/0 0.1/0.12 0.5/0.48 1/0.95'");
            vf.append(",vignette=PI/4");
        }
        return vf.toString();
    }

    /**
     * Text overlays applied AFTER concat so enable='lt(t,X)' counts from the
     * start of the full video, not from the start of each kill segment.
     * Returns empty string if nothing to render (caller falls back to null/passthrough).
     */
    private String buildHighlightsPostConcatFilter(String playerName, boolean vertical, String hookText) {
        StringBuilder vf = new StringBuilder();
        if (settings.isShowTextOverlays()) {
            if (vertical) {
                vf.append(buildDrawText("MATCH HIGHLIGHTS", 540, 420, 60, "white", true));
                vf.append(",");
                vf.append(buildDrawText(playerName, 540, 510, 36, "#CCCCCC", false));
            } else {
                vf.append(buildDrawText("MATCH HIGHLIGHTS", 960, 180, 60, "white", true));
                vf.append(",");
                vf.append(buildDrawText(playerName, 960, 260, 36, "#CCCCCC", false));
            }
        }
        if (hookText != null && !hookText.isBlank()) {
            if (vf.length() > 0) vf.append(",");
            vf.append(buildHookDrawText(hookText, 56, 3.0));
        }
        String result = vf.toString();
        if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        return result;
    }

    /**
     * Builds an attention-grabbing hook text overlay, centred horizontally,
     * shown only for the first {@code durationSec} seconds of the clip.
     * Long text is wrapped at 3 words per line so it never overflows the frame.
     */
    private String buildHookDrawText(String hookText, int size, double durationSec) {
        String fontFile = "'C\\:/Windows/Fonts/arialbd.ttf'";
        int lineHeight = size + 10;

        // Split into lines of max 3 words each
        String[] words = hookText.trim().split("\\s+");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < words.length; i += 3) {
            StringBuilder line = new StringBuilder();
            for (int j = i; j < Math.min(i + 3, words.length); j++) {
                if (j > i) line.append(' ');
                line.append(words[j]);
            }
            lines.add(line.toString());
        }

        StringBuilder result = new StringBuilder();
        for (int li = 0; li < lines.size(); li++) {
            if (li > 0) result.append(",");
            int y = 150 + li * lineHeight;
            String lineText = lines.get(li);
            try {
                Path textFile = Files.createTempFile("hook_", ".txt");
                Files.writeString(textFile, lineText, StandardCharsets.UTF_8);
                textFile.toFile().deleteOnExit();
                String textFilePath = "'"
                    + textFile.toString().replace('\\', '/').replaceFirst("^([A-Za-z]):", "$1\\\\:")
                    + "'";
                result.append(String.format(
                    "drawtext=fontfile=%s:textfile=%s:x=(w-tw)/2:y=%d:fontsize=%d:" +
                    "fontcolor=white:borderw=4:bordercolor=black@0.9:shadowx=3:shadowy=3:" +
                    "enable='lt(t,%.1f)'",
                    fontFile, textFilePath, y, size, durationSec
                ));
            } catch (Exception e) {
                log.error("Failed to create textfile for hook drawtext: {}", e.getMessage());
                String safe = lineText.replaceAll("[^\\x20-\\x7E]", "?")
                    .replace("\\", "\\\\").replace("'", "\\'")
                    .replace(":", "\\:").replace(",", "\\,");
                result.append(String.format(
                    "drawtext=fontfile=%s:text='%s':x=(w-tw)/2:y=%d:fontsize=%d:" +
                    "fontcolor=white:borderw=4:bordercolor=black@0.9:shadowx=3:shadowy=3:" +
                    "enable='lt(t,%.1f)'",
                    fontFile, safe, y, size, durationSec
                ));
            }
        }
        return result.toString();
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
