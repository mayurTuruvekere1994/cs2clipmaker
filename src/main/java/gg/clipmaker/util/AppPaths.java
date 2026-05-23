package gg.clipmaker.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central place for all file-system paths used by the app.
 * All output goes under %USERPROFILE%\Videos\CS2ClipMaker\
 */
public class AppPaths {

    private static final String APP_NAME = "CS2ClipMaker";

    // Base: C:\Users\<name>\Videos\CS2ClipMaker\
    private static final String BASE_DIR = System.getProperty("user.home")
            + File.separator + "Videos"
            + File.separator + APP_NAME;

    public static final String RAW_RECORDINGS_DIR = BASE_DIR + File.separator + "raw";
    public static final String CLIPS_DIR          = BASE_DIR + File.separator + "clips";
    public static final String LOGS_DIR           = BASE_DIR + File.separator + "logs";
    public static final String MUSIC_DIR          = BASE_DIR + File.separator + "music";

    /**
     * Creates all required directories on first run.
     */
    public static void ensureDirectories() {
        for (String dir : new String[]{RAW_RECORDINGS_DIR, CLIPS_DIR, LOGS_DIR, MUSIC_DIR}) {
            try {
                Files.createDirectories(Paths.get(dir));
            } catch (Exception e) {
                System.err.println("Could not create directory: " + dir);
            }
        }
    }

    public static Path clipsPath()          { return Paths.get(CLIPS_DIR); }
    public static Path rawRecordingsPath()  { return Paths.get(RAW_RECORDINGS_DIR); }
    public static Path musicPath()          { return Paths.get(MUSIC_DIR); }

    /**
     * Returns a clip output path like:
     * .../clips/ace_20240501_143022_1.mp4
     */
    public static String nextClipPath(String label, int index) {
        String safe = label.toLowerCase().replace(" ", "_");
        String ts   = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return CLIPS_DIR + File.separator + safe + "_" + ts + "_" + index + ".mp4";
    }
}
