package gg.clipmaker.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the FFmpeg executable.
 *
 * Priority:
 * 1. Bundled ffmpeg.exe next to the running JAR (installed by NSIS)
 * 2. ffmpeg.exe on the system PATH
 * 3. Common install locations (e.g. C:\ffmpeg\bin\ffmpeg.exe)
 */
public class FFmpegLocator {

    private static final Logger log = LoggerFactory.getLogger(FFmpegLocator.class);
    private static String cached = null;

    public static String find() {
        if (cached != null) return cached;

        // 1. Bundled next to JAR
        String jarDir = getJarDirectory();
        if (jarDir != null) {
            Path bundled = Paths.get(jarDir, "ffmpeg.exe");
            if (Files.exists(bundled)) {
                cached = bundled.toAbsolutePath().toString();
                log.info("FFmpeg found (bundled): {}", cached);
                return cached;
            }
        }

        // 2. System PATH
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true).start();
            p.waitFor();
            if (p.exitValue() == 0) {
                cached = "ffmpeg";
                log.info("FFmpeg found on system PATH");
                return cached;
            }
        } catch (Exception ignored) {}

        // 3. Common Windows install locations
        String[] commonPaths = {
            "C:\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
            "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe",
            System.getProperty("user.home") + "\\ffmpeg\\bin\\ffmpeg.exe"
        };

        for (String p : commonPaths) {
            if (new File(p).exists()) {
                cached = p;
                log.info("FFmpeg found at: {}", cached);
                return cached;
            }
        }

        log.error("FFmpeg not found! Please ensure ffmpeg.exe is bundled with the installer.");
        return "ffmpeg"; // last resort — will fail with a clear error at runtime
    }

    private static String getJarDirectory() {
        try {
            String path = FFmpegLocator.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            return new File(path).getParent();
        } catch (Exception e) {
            return null;
        }
    }
}
