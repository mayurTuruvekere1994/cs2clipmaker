
package gg.clipmaker.recorder;

import gg.clipmaker.util.FFmpegLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ScreenRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScreenRecorder.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private Process              ffmpegProcess;
    private WasapiLoopbackCapture audioCapture;
    private Thread               audioCaptureThread;

    private String  outputPath;
    private String  audioCapturePath;
    private boolean recording = false;
    private long    recordingStartMs;

    private final String           ffmpegPath;
    private final String           outputDir;
    private final RecorderSettings settings;

    public ScreenRecorder(String outputDir, RecorderSettings settings) {
        this.outputDir  = outputDir;
        this.settings   = settings;
        this.ffmpegPath = FFmpegLocator.find();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public String startRecording() throws IOException {
        if (recording) {
            log.warn("Already recording — ignoring start call");
            return outputPath;
        }

        Files.createDirectories(Paths.get(outputDir));
        String timestamp = LocalDateTime.now().format(TS);
        outputPath       = outputDir + File.separator + "raw_"   + timestamp + ".mp4";
        audioCapturePath = outputDir + File.separator + "audio_" + timestamp + ".wav";

        // Start WASAPI loopback audio capture on a dedicated thread — independent of FFmpeg
        if (settings.isRecordAudio()) {
            audioCapture       = new WasapiLoopbackCapture(audioCapturePath);
            audioCaptureThread = new Thread(audioCapture, "wasapi-loopback");
            audioCaptureThread.setDaemon(true);
            audioCaptureThread.start();
            log.info("WASAPI loopback audio capture thread started → {}", audioCapturePath);
        }

        // Start FFmpeg for video-only capture
        if (launchFFmpeg(outputPath)) {
            log.info("Recording started (video-only, audio captured separately) → {}", outputPath);
            return outputPath;
        }

        throw new IOException("FFmpeg failed to start. Check logs for details.");
    }

    public String stopRecording() {
        if (!recording || ffmpegProcess == null) {
            log.warn("Not currently recording");
            return outputPath;
        }

        // Stop FFmpeg
        try {
            ffmpegProcess.getOutputStream().write('q');
            ffmpegProcess.getOutputStream().flush();
            ffmpegProcess.waitFor();
        } catch (Exception e) {
            log.error("Error stopping FFmpeg", e);
            ffmpegProcess.destroyForcibly();
        }

        // Stop audio capture and wait for WAV to be finalised
        if (audioCapture != null) {
            audioCapture.stop();
            try { audioCaptureThread.join(8000); } catch (InterruptedException ignored) {}
            if (!audioCapture.isSuccess()) {
                log.warn("WASAPI audio capture produced no data — clips will have no audio");
                audioCapturePath = null;
            }
            audioCapture = null;
        }

        recording = false;
        long durationMs = System.currentTimeMillis() - recordingStartMs;
        log.info("Recording stopped. Duration: {}s → {}", durationMs / 1000, outputPath);
        return outputPath;
    }

    public boolean isRecording()          { return recording; }
    public String  getOutputPath()        { return outputPath; }
    public long    getRecordingStartMs()  { return recordingStartMs; }
    public String  getAudioCapturePath()  { return audioCapturePath; }

    // ── FFmpeg video-only launcher ────────────────────────────────────────────

    private boolean launchFFmpeg(String output) throws IOException {
        List<String> cmd = buildFFmpegCommand(output);
        log.info("Starting FFmpeg: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // Timestamp the start NOW — before the 1500 ms wait — so recordingStartMs
        // matches when gdigrab actually begins capturing, keeping A/V in sync.
        recordingStartMs = System.currentTimeMillis();
        ffmpegProcess = pb.start();

        // Drain FFmpeg output on a daemon thread so the pipe never stalls
        InputStream     ffmpegOut = ffmpegProcess.getInputStream();
        StringBuilder   captured  = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(ffmpegOut))) {
                String line;
                while ((line = br.readLine()) != null) captured.append(line).append('\n');
            } catch (Exception ignored) {}
        }, "ffmpeg-drainer");
        drainer.setDaemon(true);
        drainer.start();

        // Give FFmpeg time to initialise — early exit means it failed
        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (!ffmpegProcess.isAlive()) {
            try { drainer.join(500); } catch (InterruptedException ignored) {}
            log.error("FFmpeg exited immediately:\n{}", captured);
            return false;
        }

        recording = true;
        return true;
    }

    private List<String> buildFFmpegCommand(String output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);

        // Video input — gdigrab desktop capture (video only, audio handled by WasapiLoopbackCapture)
        cmd.add("-f");         cmd.add("gdigrab");
        cmd.add("-framerate"); cmd.add(String.valueOf(settings.getFps()));
        cmd.add("-i");         cmd.add("desktop");

        // Video encoder — NVENC > AMF > libx264
        if (settings.isUseNvenc() && isEncoderAvailable("h264_nvenc")) {
            cmd.add("-c:v");     cmd.add("h264_nvenc");
            cmd.add("-preset");  cmd.add("p2");
            cmd.add("-rc");      cmd.add("vbr");
            cmd.add("-cq");      cmd.add("28");
        } else if (isEncoderAvailable("h264_amf")) {
            cmd.add("-c:v");     cmd.add("h264_amf");
            cmd.add("-quality"); cmd.add("speed");
        } else {
            cmd.add("-c:v");    cmd.add("libx264");
            cmd.add("-preset"); cmd.add("ultrafast");
            cmd.add("-crf");    cmd.add("28");
        }

        // Normalise color space — gdigrab+NVENC tags output as bt470bg/unknown which
        // causes WMP and some mobile players to refuse playback. bt709 is the correct
        // standard for HD screen recordings.
        cmd.add("-color_primaries"); cmd.add("bt709");
        cmd.add("-color_trc");       cmd.add("bt709");
        cmd.add("-colorspace");      cmd.add("bt709");
        cmd.add("-color_range");     cmd.add("tv");

        cmd.add("-an");          // no audio stream in the video file
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-y");
        cmd.add(output);

        return cmd;
    }

    private boolean isEncoderAvailable(String encoder) {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-encoders")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.contains(encoder);
        } catch (Exception e) {
            return false;
        }
    }
}
