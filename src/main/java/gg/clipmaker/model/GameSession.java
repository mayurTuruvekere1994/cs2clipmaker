package gg.clipmaker.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks one CS2 match session: the raw recording file path and all
 * detected highlights within it.
 */
public class GameSession {

    public enum State { IDLE, RECORDING, PROCESSING, DONE, ERROR }

    private State state = State.IDLE;
    private Instant startTime;
    private Instant endTime;

    private String rawVideoPath;          // full path to the background recording
    private String rawAudioPath;          // WAV file from WasapiLoopbackCapture (may be null)
    private String mapName = "Unknown";
    private String playerName = "Player";
    private int totalKills = 0;

    private final List<HighlightEvent> highlights = new ArrayList<>();

    public GameSession() {
        this.startTime = Instant.now();
    }

    // ── Highlight management ─────────────────────────────────────────────────

    public void addHighlight(HighlightEvent event) {
        highlights.add(event);
    }

    public List<HighlightEvent> getHighlights() {
        return Collections.unmodifiableList(highlights);
    }

    public int getHighlightCount() { return highlights.size(); }

    // ── State ────────────────────────────────────────────────────────────────

    public void finish() {
        this.endTime = Instant.now();
        this.state = State.PROCESSING;
    }

    public long getDurationSeconds() {
        Instant end = endTime != null ? endTime : Instant.now();
        return end.getEpochSecond() - startTime.getEpochSecond();
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public State getState()                    { return state; }
    public void setState(State state)          { this.state = state; }
    public Instant getStartTime()              { return startTime; }
    public void setStartTime(Instant t)        { this.startTime = t; }
    public Instant getEndTime()                { return endTime; }
    public String getRawVideoPath()            { return rawVideoPath; }
    public void setRawVideoPath(String path)   { this.rawVideoPath = path; }
    public String getRawAudioPath()            { return rawAudioPath; }
    public void setRawAudioPath(String path)   { this.rawAudioPath = path; }
    public String getMapName()                 { return mapName; }
    public void setMapName(String mapName)     { this.mapName = mapName; }
    public String getPlayerName()              { return playerName; }
    public void setPlayerName(String name)     { this.playerName = name; }
    public int getTotalKills()                 { return totalKills; }
    public void setTotalKills(int totalKills)  { this.totalKills = totalKills; }
}
