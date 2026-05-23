package gg.clipmaker.editor;

/**
 * Settings for the ClipEditor — controls which effects are applied
 * and clip timing around each highlight.
 */
public class EditorSettings {

    private int    preRollSeconds   = 10;    // seconds before kill to include
    private int    postRollSeconds  = 5;     // seconds after kill to include
    private boolean colorGrading    = true;  // apply cinematic colour grade
    private boolean showTextOverlays = true; // burn in kill count + player name
    private String musicFilePath    = null;  // null = no music
    private String playerNameFilter = null;  // null = include all players; set to filter to one player

    // ── Builder-style setters ─────────────────────────────────────────────────

    public EditorSettings preRollSeconds(int v)     { this.preRollSeconds = v;    return this; }
    public EditorSettings postRollSeconds(int v)    { this.postRollSeconds = v;   return this; }
    public EditorSettings colorGrading(boolean v)   { this.colorGrading = v;      return this; }
    public EditorSettings showTextOverlays(boolean v){ this.showTextOverlays = v; return this; }
    public EditorSettings musicFilePath(String v)   { this.musicFilePath = v;     return this; }
    public EditorSettings playerNameFilter(String v) { this.playerNameFilter = v; return this; }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int getPreRollSeconds()     { return preRollSeconds; }
    public int getPostRollSeconds()    { return postRollSeconds; }
    public boolean isColorGrading()    { return colorGrading; }
    public boolean isShowTextOverlays(){ return showTextOverlays; }
    public String getMusicFilePath()   { return musicFilePath; }
    public String getPlayerNameFilter(){ return playerNameFilter; }
}
