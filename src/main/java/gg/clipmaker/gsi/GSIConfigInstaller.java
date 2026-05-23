package gg.clipmaker.gsi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.prefs.Preferences;

/**
 * Automatically installs the GSI config file into CS2's cfg directory.
 * CS2 reads all gamestate_integration_*.cfg files on startup and begins
 * POSTing game state to our local server.
 *
 * The cfg path is discovered from the Windows Registry (Steam install path).
 */
public class GSIConfigInstaller {

    private static final Logger log = LoggerFactory.getLogger(GSIConfigInstaller.class);

    private static final String GSI_FILENAME = "gamestate_integration_cs2clipmaker.cfg";
    private static final String GSI_CONTENT =
        "\"CS2 Clip Maker GSI\"\n" +
        "{\n" +
        "    \"uri\"           \"http://127.0.0.1:3000/\"\n" +
        "    \"timeout\"       \"5.0\"\n" +
        "    \"heartbeat\"     \"10.0\"\n" +
        "    \"buffer\"        \"0.1\"\n" +
        "    \"throttle\"      \"0.1\"\n" +
        "    \"auth\"\n" +
        "    {\n" +
        "        \"token\"     \"cs2clipmaker_token_2024\"\n" +
        "    }\n" +
        "    \"data\"\n" +
        "    {\n" +
        "        \"provider\"            \"1\"\n" +
        "        \"map\"                 \"1\"\n" +
        "        \"round\"               \"1\"\n" +
        "        \"player_id\"           \"1\"\n" +
        "        \"player_state\"        \"1\"\n" +
        "        \"player_weapons\"      \"0\"\n" +
        "        \"player_match_stats\"  \"1\"\n" +
        "    }\n" +
        "}\n";

    /**
     * Tries to find Steam path via Windows registry, then writes the GSI cfg.
     * Returns the path where it was written, or null on failure.
     */
    public static String install() {
        String[] candidatePaths = buildCandidatePaths();

        for (String cfgDir : candidatePaths) {
            Path dir = Paths.get(cfgDir);
            if (Files.isDirectory(dir)) {
                Path target = dir.resolve(GSI_FILENAME);
                try {
                    Files.writeString(target, GSI_CONTENT, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("GSI config written to: {}", target);
                    saveInstalledPath(target.toString());
                    return target.toString();
                } catch (IOException e) {
                    log.warn("Could not write GSI config to {}: {}", target, e.getMessage());
                }
            }
        }

        log.error("Could not find CS2 cfg directory. Please install manually.");
        return null;
    }

    /**
     * Checks if the GSI config is already installed and valid.
     */
    public static boolean isInstalled() {
        String saved = getSavedPath();
        if (saved == null) return false;
        return Files.exists(Paths.get(saved));
    }

    /**
     * Removes the GSI config file (cleanup on uninstall).
     */
    public static void uninstall() {
        String saved = getSavedPath();
        if (saved != null) {
            try {
                Files.deleteIfExists(Paths.get(saved));
                log.info("GSI config removed: {}", saved);
            } catch (IOException e) {
                log.warn("Could not remove GSI config: {}", e.getMessage());
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static String[] buildCandidatePaths() {
        // Standard Steam library locations on Windows
        String userHome = System.getProperty("user.home");
        String progFiles = System.getenv("ProgramFiles(X86)");
        if (progFiles == null) progFiles = "C:\\Program Files (x86)";

        // CS2 cfg can be in two places depending on how the game was updated
        String base1 = progFiles + "\\Steam\\steamapps\\common\\Counter-Strike Global Offensive\\game\\csgo\\cfg";
        String base2 = progFiles + "\\Steam\\steamapps\\common\\Counter-Strike Global Offensive\\game\\core\\cfg";
        String base3 = userHome + "\\Steam\\steamapps\\common\\Counter-Strike Global Offensive\\game\\csgo\\cfg";
        String base4 = userHome + "\\Steam\\steamapps\\common\\Counter-Strike Global Offensive\\game\\core\\cfg";

        // Try to read actual Steam path from registry
        String steamPath = readSteamPathFromRegistry();
        if (steamPath != null) {
            return new String[]{
                steamPath + "\\steamapps\\common\\Counter-Strike Global Offensive\\game\\csgo\\cfg",
                steamPath + "\\steamapps\\common\\Counter-Strike Global Offensive\\game\\core\\cfg",
                base1, base2, base3, base4
            };
        }

        return new String[]{base1, base2, base3, base4};
    }

    /**
     * Reads Steam install path from Windows Registry via reg.exe query.
     * Avoids JNA/JNI dependency — uses a subprocess call.
     */
    private static String readSteamPathFromRegistry() {
        try {
            Process proc = new ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Valve\\Steam",
                "/v", "SteamPath"
            ).start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            // Parse:  SteamPath    REG_SZ    C:/Program Files (x86)/Steam
            for (String line : output.split("\n")) {
                if (line.trim().startsWith("SteamPath")) {
                    String[] parts = line.trim().split("\\s{2,}");
                    if (parts.length >= 3) {
                        return parts[2].trim().replace("/", "\\");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read Steam path from registry: {}", e.getMessage());
        }
        return null;
    }

    private static void saveInstalledPath(String path) {
        Preferences.userNodeForPackage(GSIConfigInstaller.class).put("gsiConfigPath", path);
    }

    private static String getSavedPath() {
        return Preferences.userNodeForPackage(GSIConfigInstaller.class).get("gsiConfigPath", null);
    }
}
