package gg.clipmaker.recorder;

/**
 * Configuration for the screen recorder.
 */
public class RecorderSettings {

    private int fps = 60;
    private boolean useNvenc = true;       // Prefer GPU encoding
    private boolean recordAudio = true;    // Capture game audio
    private int preRollSeconds = 10;       // Seconds before kill to include in clip
    private int postRollSeconds = 5;       // Seconds after kill to include in clip

    // ── Builder-style setters ─────────────────────────────────────────────────

    public RecorderSettings fps(int fps)                        { this.fps = fps; return this; }
    public RecorderSettings useNvenc(boolean v)                 { this.useNvenc = v; return this; }
    public RecorderSettings recordAudio(boolean v)              { this.recordAudio = v; return this; }
    public RecorderSettings preRollSeconds(int v)               { this.preRollSeconds = v; return this; }
    public RecorderSettings postRollSeconds(int v)              { this.postRollSeconds = v; return this; }

    // ── Getters ──────────────────────────────────────────────────────────────

    public int getFps()                { return fps; }
    public boolean isUseNvenc()        { return useNvenc; }
    public boolean isRecordAudio()     { return recordAudio; }
    public int getPreRollSeconds()     { return preRollSeconds; }
    public int getPostRollSeconds()    { return postRollSeconds; }
}
