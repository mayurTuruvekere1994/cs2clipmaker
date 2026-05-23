# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build fat JAR (all deps bundled)
mvn clean package -DskipTests

# Output: target/cs2-clip-maker-1.0.0-fat.jar

# Run via Maven (development)
mvn javafx:run

# Run via JAR directly
java -jar target/cs2-clip-maker-1.0.0-fat.jar
```

## Windows Installer

After building the JAR, copy it to `installer/` alongside `ffmpeg.exe`, `jre/`, `icon.ico`, and `LICENSE.txt`, then:

```bat
cd installer
makensis cs2clipmaker.nsi
# Output: CS2ClipMaker-Setup-1.0.0.exe
```

## Architecture

The app detects CS2 kill highlights via Game State Integration (GSI), records the screen with FFmpeg during a match, then post-processes the raw recording into vertical 9:16 clips.

**Data flow:**
```
CS2 (HTTP POST JSON ~100ms) -> CS2GSIServer -> fires HighlightEvent (timestamped)
                                            -> fires match start/end events
                                                       |
                                               AppController (coordinator)
                                               |                    |
                                         ScreenRecorder        ClipEditor
                                         (FFmpeg gdigrab)    (FFmpeg trim+effects)
                                               |                    |
                                         raw/*.mp4          clips/<label>_<ts>_<n>.mp4
```

**Key classes:**
- `AppController` — central wiring: connects GSI events to recorder and editor; all UI callbacks dispatched via `Platform.runLater`. The JavaFX UI talks only to this class.
- `CS2GSIServer` — `com.sun.net.httpserver` on port 3000; tracks `round_kills` delta between ticks to detect 2K/3K/4K/ACE thresholds; resets on `freezetime` phase.
- `ScreenRecorder` — launches FFmpeg as a subprocess using `gdigrab desktop`; prefers NVENC > AMF > libx264 ultrafast; stops by writing `q` to FFmpeg stdin.
- `ClipEditor` — for each `HighlightEvent`, calculates offset from recording start time, then builds an FFmpeg filter chain: crop to 9:16, scale, curves colour grade, vignette, drawtext overlays, optional music amix.
- `GameSession` — holds recording start time (`Instant`), list of `HighlightEvent`s, and the raw video path; used to correlate wall-clock timestamps with video offsets.

**Threading model:** All non-UI work runs on a single `bgThread` (`Executors.newSingleThreadExecutor()`). UI updates always go through `Platform.runLater`.

## Output Paths

All output is under `%USERPROFILE%\Videos\CS2ClipMaker\`:
- `raw/` — full match recordings (large, temporary)
- `clips/` — final 1080x1920 MP4 clips
- `music/` — drop background MP3/AAC files here for the editor to pick up
- `logs/` — Logback log files

## Key Constraints

- **Windows only**: uses `gdigrab`, Windows font paths (`C:/Windows/Fonts/arial*.ttf`), NVENC/AMF detection, NSIS installer, and Windows registry for CS2 path detection.
- **CS2 must be in Windowed or Windowed Fullscreen** for GDI screen capture to work.
- **CS2 must be restarted** after first run for the GSI config to be picked up.
- GSI port is hardcoded to `3000` in `CS2GSIServer.java` and must match `gamestate_integration_cs2clipmaker.cfg`.
- Audio capture uses `virtual-audio-capturer` via DirectShow — requires a virtual audio device (e.g. VB-Cable) to be installed.
- `FFmpegLocator` searches: bundled next to JAR > system PATH > common Windows install paths.
- JavaFX version is 21.0.2; Java 17 target; fat JAR built via maven-shade-plugin.