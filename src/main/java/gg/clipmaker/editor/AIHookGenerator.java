package gg.clipmaker.editor;

import gg.clipmaker.model.HighlightEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Calls the Anthropic Claude API to generate a short, punchy hook text for a clip.
 * Falls back gracefully (returns null) when the API key is absent or the call fails —
 * the caller is expected to fall back to HookTextBank in that case.
 *
 * API key resolution order:
 *   1. ANTHROPIC_API_KEY environment variable
 *   2. %USERPROFILE%\.cs2clipmaker\anthropic_key.txt
 */
public class AIHookGenerator {

    private static final Logger log = LoggerFactory.getLogger(AIHookGenerator.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Generates a hook text for a per-round clip. Returns null on any failure. */
    public static String generate(HighlightEvent event) {
        String apiKey = loadApiKey();
        if (apiKey == null) return null;

        String killDesc = switch (event.getType()) {
            case TRIPLE_KILL -> "3 kills (3K)";
            case QUAD_KILL   -> "4 kills (4K)";
            case ACE         -> "5 kills (ACE — wiped the whole enemy team)";
            default          -> event.getRoundKills() + " kills";
        };
        String prompt = "Generate ONE short attention-grabbing hook text (5-8 words, ALL CAPS, "
                + "no punctuation except periods or ellipsis) for a CS2 gaming short video where "
                + event.getPlayerName() + " got " + killDesc + " in one round. "
                + "Make it hype and punchy for TikTok/Reels. Return only the text, nothing else.";

        return callApi(apiKey, prompt);
    }

    /** Generates a hook text for the match highlights shorts. Returns null on any failure. */
    public static String generateForHighlights(String playerName) {
        String apiKey = loadApiKey();
        if (apiKey == null) return null;

        String prompt = "Generate ONE short attention-grabbing hook text (5-8 words, ALL CAPS, "
                + "no punctuation except periods or ellipsis) for a CS2 gaming highlights reel "
                + "featuring " + playerName + ". Make it hype and punchy for TikTok/Reels. "
                + "Return only the text, nothing else.";

        return callApi(apiKey, prompt);
    }

    private static String callApi(String apiKey, String prompt) {
        try {
            // Escape the prompt for embedding in JSON
            String escapedPrompt = prompt
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", " ")
                    .replace("\r", "");

            String body = "{\"model\":\"" + MODEL + "\",\"max_tokens\":30,\"messages\":"
                    + "[{\"role\":\"user\",\"content\":\"" + escapedPrompt + "\"}]}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Anthropic API returned HTTP {}: {}", resp.statusCode(), resp.body());
                return null;
            }

            // Extract text from {"content":[{"type":"text","text":"..."}], ...}
            String json = resp.body();
            int textIdx = json.indexOf("\"text\":\"");
            if (textIdx < 0) {
                log.warn("Unexpected Anthropic response shape: {}", json);
                return null;
            }
            int start = textIdx + 8;
            int end   = json.indexOf("\"", start);
            if (end < 0) return null;

            String text = json.substring(start, end)
                    .replace("\\n", " ").replace("\\\"", "\"").trim();
            if (text.isEmpty()) return null;

            log.info("AI hook generated: {}", text);
            return text.toUpperCase();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("AIHookGenerator call failed (will fall back to HookTextBank): {}", e.getMessage());
            return null;
        }
    }

    private static String loadApiKey() {
        // 1. Environment variable
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) return key.strip();

        // 2. Key file: %USERPROFILE%\.cs2clipmaker\anthropic_key.txt
        try {
            String userHome = System.getProperty("user.home");
            java.nio.file.Path keyFile = Paths.get(userHome, ".cs2clipmaker", "anthropic_key.txt");
            if (Files.exists(keyFile)) {
                key = Files.readString(keyFile).strip();
                if (!key.isBlank()) return key;
            }
        } catch (Exception e) {
            log.debug("Could not read API key file: {}", e.getMessage());
        }

        return null;
    }
}
