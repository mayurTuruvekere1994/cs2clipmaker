package gg.clipmaker.gsi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import gg.clipmaker.model.GameSession;
import gg.clipmaker.model.HighlightEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs a local HTTP server on port 3000.
 * CS2 POSTs its game state JSON here every ~100ms during a match.
 *
 * Detects highlight moments (2K / 3K / 4K / ACE) by tracking
 * round_kills changes in the player state block.
 */
public class CS2GSIServer {

    private static final Logger log = LoggerFactory.getLogger(CS2GSIServer.class);
    private static final int PORT = 3000;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    // State tracked between GSI ticks
    private int     lastRoundKills    = 0;
    private long    firstKillMs       = 0;   // wall-clock ms when this round's first kill landed
    private long    lastKillMs        = 0;   // wall-clock ms of the most recent kill this round
    private int     lastRoundHsKills  = 0;
    private String  pendingPlayerName = "";
    private String  lastPhase         = "";
    private boolean matchActive       = false;

    // Listeners
    private final List<Consumer<HighlightEvent>> highlightListeners = new ArrayList<>();
    private final List<Runnable> matchStartListeners = new ArrayList<>();
    private final List<Runnable> matchEndListeners = new ArrayList<>();
    private final List<Consumer<String>> playerNameListeners = new ArrayList<>();

    // Active session reference (set when recording starts)
    private GameSession activeSession;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", new GSIHandler());
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        log.info("GSI server started on port {}", PORT);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("GSI server stopped");
        }
    }

    public void setActiveSession(GameSession session) {
        this.activeSession = session;
        this.lastRoundKills = 0;
    }

    // ── Event listener registration ──────────────────────────────────────────

    public void addHighlightListener(Consumer<HighlightEvent> listener) {
        highlightListeners.add(listener);
    }

    public void addMatchStartListener(Runnable listener) {
        matchStartListeners.add(listener);
    }

    public void addMatchEndListener(Runnable listener) {
        matchEndListeners.add(listener);
    }

    public void addPlayerNameListener(Consumer<String> listener) {
        playerNameListeners.add(listener);
    }

    // ── GSI payload processing ───────────────────────────────────────────────

    private void processPayload(String body) {
        try {
            JsonNode root = mapper.readTree(body);

            // ── Player info ──────────────────────────────────────────────────
            JsonNode player = root.path("player");
            String playerName = player.path("name").asText("Player");

            playerNameListeners.forEach(l -> l.accept(playerName));
            if (activeSession != null) activeSession.setPlayerName(playerName);

            // ── Map / Phase ──────────────────────────────────────────────────
            JsonNode mapNode = root.path("map");
            String phase = mapNode.path("phase").asText("");
            String mapName = mapNode.path("name").asText("Unknown");

            if (activeSession != null && !mapName.isEmpty()) activeSession.setMapName(mapName);

            // ── Match start / end detection ──────────────────────────────────
            boolean inLive = "live".equals(phase);
            boolean wasActive = matchActive;

            if (inLive && !wasActive) {
                matchActive = true;
                resetRoundState();
                log.info("Match started — map: {}", mapName);
                matchStartListeners.forEach(Runnable::run);
            } else if (!inLive && wasActive && ("gameover".equals(phase) || "intermission".equals(phase))) {
                matchActive = false;
                // Flush any kills from the final round
                fireRoundHighlightIfPending();
                resetRoundState();
                log.info("Match ended");
                matchEndListeners.forEach(Runnable::run);
            }

            if (!inLive) return;

            // ── Kill tracking ────────────────────────────────────────────────
            JsonNode state = player.path("state");
            int roundKills   = state.path("round_kills").asInt(0);
            int roundHsKills = state.path("round_killhs").asInt(0);

            if (roundKills > lastRoundKills) {
                // Record timestamp of the very first kill of this round
                if (lastRoundKills == 0) firstKillMs = System.currentTimeMillis();
                lastKillMs       = System.currentTimeMillis();
                lastRoundHsKills = roundHsKills;
                pendingPlayerName = playerName;

                if (activeSession != null) {
                    activeSession.setTotalKills(
                        player.path("match_stats").path("kills").asInt(0));
                }

                log.debug("Kill {}/{} for {} this round", roundKills, roundKills, playerName);
                lastRoundKills = roundKills;
            }

            // ── Round end: freezetime fires after every round ─────────────────
            // Also catches a kills-went-backwards reset (e.g. disconnect/reconnect)
            JsonNode round = root.path("round");
            String roundPhase = round.path("phase").asText("");
            boolean newRound = "freezetime".equals(roundPhase) && lastRoundKills > 0;
            boolean killsReset = roundKills < lastRoundKills;

            if (newRound || killsReset) {
                fireRoundHighlightIfPending();
                resetRoundState();
            }

        } catch (Exception e) {
            log.error("Error processing GSI payload", e);
        }
    }

    /**
     * Emit one highlight event for the kills accumulated this round.
     * Called at freezetime or match end — never mid-round — so the type
     * reflects the player's FINAL kill count for the round.
     */
    private void fireRoundHighlightIfPending() {
        if (lastRoundKills < 1 || firstKillMs == 0) return;

        HighlightEvent.Type type = HighlightEvent.Type.fromKillCount(lastRoundKills);
        if (type == null) return;

        HighlightEvent event = new HighlightEvent(
            type, pendingPlayerName,
            firstKillMs, lastKillMs,
            lastRoundKills, lastRoundHsKills);

        log.info("Round highlight: {}", event);
        if (activeSession != null) activeSession.addHighlight(event);
        highlightListeners.forEach(l -> l.accept(event));
    }

    private void resetRoundState() {
        lastRoundKills   = 0;
        firstKillMs      = 0;
        lastKillMs       = 0;
        lastRoundHsKills = 0;
        pendingPlayerName = "";
    }

    // ── Inner HTTP handler ───────────────────────────────────────────────────

    private class GSIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    processPayload(body);
                }
            }
            // Always respond 200 OK — CS2 doesn't care about the body
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        }
    }
}
