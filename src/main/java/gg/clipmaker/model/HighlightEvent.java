package gg.clipmaker.model;

/**
 * Represents a kill highlight moment detected during a CS2 match.
 * Bookmarked by timestamp so the recorder can extract the clip later.
 */
public class HighlightEvent {

    public enum Type {
        SINGLE_KILL(1, "KILL"),
        DOUBLE_KILL(2, "DOUBLE KILL"),
        TRIPLE_KILL(3, "TRIPLE KILL"),
        QUAD_KILL(4, "QUAD KILL"),
        ACE(5, "ACE");

        public final int killCount;
        public final String label;

        Type(int killCount, String label) {
            this.killCount = killCount;
            this.label = label;
        }

        public static Type fromKillCount(int count) {
            for (Type t : values()) {
                if (t.killCount == count) return t;
            }
            return count >= 5 ? ACE : null;
        }
    }

    private final Type type;
    private final String playerName;
    private final long timestampMs;        // wall-clock ms of the FIRST kill — clip anchors here
    private final long lastKillTimestampMs; // wall-clock ms of the LAST kill — drives clip duration
    private final int roundKills;
    private final int roundHsKills;
    private String exportedClipPath;       // filled in after editor produces the clip

    public HighlightEvent(Type type, String playerName,
                          long firstKillMs, long lastKillMs,
                          int roundKills, int roundHsKills) {
        this.type = type;
        this.playerName = playerName;
        this.timestampMs = firstKillMs;
        this.lastKillTimestampMs = lastKillMs;
        this.roundKills = roundKills;
        this.roundHsKills = roundHsKills;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Type getType()                 { return type; }
    public String getPlayerName()         { return playerName; }
    public long getTimestampMs()          { return timestampMs; }
    public long getLastKillTimestampMs()  { return lastKillTimestampMs; }
    public int getRoundKills()            { return roundKills; }
    public int getRoundHsKills()          { return roundHsKills; }
    public String getExportedClipPath()   { return exportedClipPath; }
    public void setExportedClipPath(String path) { this.exportedClipPath = path; }

    @Override
    public String toString() {
        return String.format("[%s] %s — %d kills (%d HS) @ %dms",
                type.label, playerName, roundKills, roundHsKills, timestampMs);
    }
}
