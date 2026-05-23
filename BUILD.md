# CS2 Clip Maker — Build & Install Guide

## What This App Does

Runs in the background while you play CS2 on Steam.
- Detects **2K / 3K / 4K / ACE** highlight moments automatically via CS2's Game State Integration (GSI) — the same mechanism Allstars uses, but running 100% locally on your PC.
- Records your screen using FFmpeg (GPU-accelerated, minimal FPS impact).
- After the match ends, auto-produces **vertical 9:16 MP4 clips** (TikTok / Reels ready) with cinematic colour grading, kill count overlay, player name, and optional background music.

---

## Prerequisites

| Tool | Where to get it |
|------|----------------|
| Java 17 JDK | https://adoptium.net (Eclipse Temurin 17) |
| Maven 3.8+ | https://maven.apache.org/download |
| FFmpeg (Windows) | https://ffmpeg.org/download.html → Windows builds by BtbN |
| NSIS (for installer) | https://nsis.sourceforge.io |

---

## Step 1 — Build the JAR

```bash
# From the project root (where pom.xml is)
mvn clean package -DskipTests

# Output: target/cs2-clip-maker-1.0.0-fat.jar
```

---

## Step 2 — Run Directly (Development)

```bash
# Windows Command Prompt
java -jar target\cs2-clip-maker-1.0.0-fat.jar

# Or with Maven JavaFX plugin
mvn javafx:run
```

On first run the app will:

---

## Step 2b — Run in Debug Mode

### Option A — Maven (recommended, no JAR build needed)

```bash
mvn javafx:run "-Djavafx.jvmArgs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
```

- Change `suspend=n` to `suspend=y` if you want the JVM to **wait** for the debugger before starting.
- Then attach your IDE's remote debugger to `localhost:5005`.

**IntelliJ:** Run → Edit Configurations → + → Remote JVM Debug → Host: `localhost`, Port: `5005` → Debug.

**VS Code:** Add to `.vscode/launch.json`:
```json
{
  "type": "java",
  "name": "Attach to CS2ClipMaker",
  "request": "attach",
  "hostName": "localhost",
  "port": 5005
}
```

---

### Option B — Fat JAR with remote debug

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar target\cs2-clip-maker-1.0.0-fat.jar
```

Then attach your IDE to `localhost:5005` the same way as above.

---

### Option C — Run directly from IDE (easiest)

Import the project as a Maven project in IntelliJ or VS Code with the Java extension pack. The IDE resolves JavaFX from the Maven cache automatically.

- **IntelliJ:** Right-click `gg.clipmaker.ui.Launcher` → Debug. No extra VM options needed.
- **VS Code:** Open Run & Debug → create a launch config targeting `gg.clipmaker.ui.Launcher`.

---

On first run the app will:
1. Auto-detect your CS2 installation via the Windows registry.
2. Write `gamestate_integration_cs2clipmaker.cfg` into CS2's cfg folder.
3. Start listening on `http://127.0.0.1:3000/` for CS2 events.

**Restart CS2 after first run** so it picks up the new GSI config.

---

## Step 3 — Build the Windows Installer

### 3a — Gather installer assets

Place these files inside the `installer/` folder:

```
installer/
  cs2clipmaker.nsi                ← already here
  cs2-clip-maker-1.0.0-fat.jar   ← copy from target/
  ffmpeg.exe                      ← from ffmpeg.org Windows build (bin/ffmpeg.exe)
  icon.ico                        ← your app icon (256x256 recommended)
  LICENSE.txt                     ← your licence text
  jre/                            ← full JRE 17 folder from adoptium.net
```

### 3b — Download JRE 17

1. Go to https://adoptium.net
2. Download **JRE 17** → **Windows x64** → **ZIP**
3. Extract the ZIP — rename the extracted folder to `jre`
4. Place the `jre/` folder inside `installer/`

### 3c — Download FFmpeg

1. Go to https://github.com/BtbN/FFmpeg-Builds/releases
2. Download `ffmpeg-master-latest-win64-gpl.zip`
3. Extract → copy `bin/ffmpeg.exe` into `installer/`

### 3d — Compile the installer

```bat
cd installer
makensis cs2clipmaker.nsi

:: Output: CS2ClipMaker-Setup-1.0.0.exe
```

The installer:
- Bundles the JAR + JRE + FFmpeg — user needs nothing pre-installed.
- Creates a Desktop shortcut and Start Menu entry.
- Adds a Windows Firewall rule allowing the GSI port (3000).
- Registers the app in Add/Remove Programs.

---

## How It Works (Technical)

```
CS2 (Steam)
  │  sends HTTP POST JSON every ~100ms to localhost:3000
  ▼
CS2GSIServer.java          ← parses kill events
  │  fires HighlightEvent when round_kills reaches 2 / 3 / 4 / 5
  ▼
AppController.java         ← coordinates everything
  ├── ScreenRecorder.java  ← FFmpeg gdigrab capturing your screen
  └── (after match ends)
        ClipEditor.java    ← FFmpeg trims + effects pipeline
              │
              ▼
        %USERPROFILE%\Videos\CS2ClipMaker\clips\
              ace_20240501_143022_1.mp4   ← 1080×1920, ready to upload
```

### GSI Config installed at:
```
<SteamPath>\steamapps\common\Counter-Strike Global Offensive\game\csgo\cfg\
  gamestate_integration_cs2clipmaker.cfg
```

---

## Clip Output

All clips are saved to:
```
C:\Users\<YourName>\Videos\CS2ClipMaker\clips\
```

- Format: **MP4**, H.264, AAC audio
- Resolution: **1080 × 1920** (9:16 vertical)
- Effects: Cinematic colour grade · Kill count overlay · Player name · Optional music

Double-click any clip in the app's **Exported Clips** list to preview it immediately.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| No clips after match | Check that CS2 was restarted after first run. Only Competitive and Wingman modes are supported. |
| GSI config not found | Manually copy `gamestate_integration_cs2clipmaker.cfg` (from `installer/` folder) into your CS2 cfg directory. |
| Black recording | CS2 must be in **Windowed** or **Windowed Fullscreen** mode for GDI capture. |
| FFmpeg not found | Place `ffmpeg.exe` in the same folder as the JAR, or add FFmpeg to your system PATH. |
| Port 3000 in use | Another app is using port 3000. Edit the port in `CS2GSIServer.java` and the `.cfg` file, then rebuild. |

---

## Project Structure

```
cs2clipmaker/
├── pom.xml
├── installer/
│   └── cs2clipmaker.nsi
└── src/main/
    ├── java/gg/clipmaker/
    │   ├── gsi/
    │   │   ├── CS2GSIServer.java        ← HTTP server, kill detection
    │   │   └── GSIConfigInstaller.java  ← auto-writes CS2 cfg file
    │   ├── recorder/
    │   │   ├── ScreenRecorder.java      ← FFmpeg screen capture
    │   │   └── RecorderSettings.java
    │   ├── editor/
    │   │   ├── ClipEditor.java          ← FFmpeg trim + effects pipeline
    │   │   └── EditorSettings.java
    │   ├── model/
    │   │   ├── HighlightEvent.java
    │   │   └── GameSession.java
    │   ├── ui/
    │   │   ├── App.java                 ← JavaFX entry point
    │   │   ├── AppController.java       ← business logic coordinator
    │   │   └── MainViewController.java  ← FXML controller
    │   └── util/
    │       ├── FFmpegLocator.java
    │       └── AppPaths.java
    └── resources/
        ├── fxml/MainView.fxml
        ├── css/dark-theme.css
        └── logback.xml
```
