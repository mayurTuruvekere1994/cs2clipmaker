; ============================================================
; CS2 Clip Maker — NSIS Windows Installer Script
; ============================================================
; Prerequisites (place in installer/ folder before running makensis):
;   - cs2-clip-maker-1.0.0-fat.jar  (built by: mvn package)
;   - jre\                           (JRE 17, download from adoptium.net)
;   - ffmpeg.exe                     (from ffmpeg.org/download.html — Windows build)
;   - icon.ico                       (app icon)
; Run: makensis cs2clipmaker.nsi
; ============================================================

!define APP_NAME      "CS2 Clip Maker"
!define APP_VERSION   "1.0.0"
!define APP_PUBLISHER "CS2ClipMaker"
!define APP_EXE       "CS2ClipMaker.exe"
!define APP_JAR       "cs2-clip-maker-1.0.0-fat.jar"
!define INSTALL_DIR   "$PROGRAMFILES64\CS2ClipMaker"
!define REG_KEY       "Software\Microsoft\Windows\CurrentVersion\Uninstall\CS2ClipMaker"

; Modern UI
!include "MUI2.nsh"
!include "FileFunc.nsh"

Name          "${APP_NAME} ${APP_VERSION}"
OutFile       "CS2ClipMaker-Setup-${APP_VERSION}.exe"
InstallDir    "${INSTALL_DIR}"
RequestExecutionLevel admin

; Icons
!define MUI_ICON   "icon.ico"
!define MUI_UNICON "icon.ico"

; Pages
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE      "..\LICENSE.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

; ── Install section ──────────────────────────────────────────────────────────
Section "CS2 Clip Maker" SecMain

    SetOutPath "$INSTDIR"

    ; App JAR
    File "${APP_JAR}"

    ; Bundled JRE 17 (full JRE so user doesn't need Java installed)
    File /r "jre\"

    ; FFmpeg binary
    File "ffmpeg.exe"

    ; App icon
    File "icon.ico"

    ; Write launcher batch (hidden) + Launch4j-style wrapper
    ; We use a small launcher script that calls the bundled JRE
    FileOpen  $0 "$INSTDIR\launch.bat" w
    FileWrite $0 '@echo off$\r$\n'
    FileWrite $0 'start "" "$INSTDIR\jre\bin\javaw.exe" ^$\r$\n'
    FileWrite $0 '  --module-path "$INSTDIR\jre\lib" ^$\r$\n'
    FileWrite $0 '  -jar "$INSTDIR\${APP_JAR}"$\r$\n'
    FileClose $0

    ; Write a proper .exe launcher using a VBScript wrapper (no console window)
    FileOpen  $0 "$INSTDIR\${APP_EXE}.vbs" w
    FileWrite $0 'Set WshShell = CreateObject("WScript.Shell")$\r$\n'
    FileWrite $0 'WshShell.Run """$INSTDIR\jre\bin\javaw.exe"" -jar ""$INSTDIR\${APP_JAR}""", 0, False$\r$\n'
    FileClose $0

    ; Create a real .exe by copying wscript.exe and renaming (simplest no-dependency approach)
    ; For a proper .exe, use Launch4j in your build pipeline instead.
    ; This batch will work as a double-click launcher in the meantime.
    CopyFiles "$WINDIR\System32\wscript.exe" "$INSTDIR\${APP_EXE}"

    ; Desktop shortcut
    CreateShortcut "$DESKTOP\${APP_NAME}.lnk" \
        "$INSTDIR\jre\bin\javaw.exe" \
        "-jar `"$INSTDIR\${APP_JAR}`"" \
        "$INSTDIR\icon.ico" 0

    ; Start menu shortcut
    CreateDirectory "$SMPROGRAMS\${APP_NAME}"
    CreateShortcut  "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" \
        "$INSTDIR\jre\bin\javaw.exe" \
        "-jar `"$INSTDIR\${APP_JAR}`"" \
        "$INSTDIR\icon.ico" 0
    CreateShortcut  "$SMPROGRAMS\${APP_NAME}\Uninstall.lnk" \
        "$INSTDIR\Uninstall.exe"

    ; Registry: Add/Remove Programs entry
    WriteRegStr   HKLM "${REG_KEY}" "DisplayName"     "${APP_NAME}"
    WriteRegStr   HKLM "${REG_KEY}" "DisplayVersion"  "${APP_VERSION}"
    WriteRegStr   HKLM "${REG_KEY}" "Publisher"       "${APP_PUBLISHER}"
    WriteRegStr   HKLM "${REG_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr   HKLM "${REG_KEY}" "UninstallString" "$INSTDIR\Uninstall.exe"
    WriteRegStr   HKLM "${REG_KEY}" "DisplayIcon"     "$INSTDIR\icon.ico"
    WriteRegDWORD HKLM "${REG_KEY}" "NoModify"        1
    WriteRegDWORD HKLM "${REG_KEY}" "NoRepair"        1

    ; Estimate install size
    ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
    IntFmt $0 "0x%08X" $0
    WriteRegDWORD HKLM "${REG_KEY}" "EstimatedSize" "$0"

    ; Write uninstaller
    WriteUninstaller "$INSTDIR\Uninstall.exe"

    ; Auto-register Windows Firewall exception so CS2 GSI can reach our local server
    ExecWait 'netsh advfirewall firewall add rule name="CS2 Clip Maker GSI" \
              protocol=TCP dir=in localport=3000 action=allow'

SectionEnd

; ── Uninstall section ────────────────────────────────────────────────────────
Section "Uninstall"

    ; Remove GSI config from CS2 (app removes it on shutdown, but clean up here too)
    ; The Java app writes this file — we delete the known filename
    Delete "$PROGRAMFILES64\Steam\steamapps\common\Counter-Strike Global Offensive\game\csgo\cfg\gamestate_integration_cs2clipmaker.cfg"
    Delete "$PROGRAMFILES64\Steam\steamapps\common\Counter-Strike Global Offensive\game\core\cfg\gamestate_integration_cs2clipmaker.cfg"

    ; Remove firewall rule
    ExecWait 'netsh advfirewall firewall delete rule name="CS2 Clip Maker GSI"'

    ; Remove installed files
    Delete "$INSTDIR\${APP_JAR}"
    Delete "$INSTDIR\ffmpeg.exe"
    Delete "$INSTDIR\icon.ico"
    Delete "$INSTDIR\launch.bat"
    Delete "$INSTDIR\${APP_EXE}.vbs"
    Delete "$INSTDIR\${APP_EXE}"
    Delete "$INSTDIR\Uninstall.exe"
    RMDir  /r "$INSTDIR\jre"
    RMDir  "$INSTDIR"

    ; Remove shortcuts
    Delete "$DESKTOP\${APP_NAME}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\Uninstall.lnk"
    RMDir  "$SMPROGRAMS\${APP_NAME}"

    ; Remove registry entries
    DeleteRegKey HKLM "${REG_KEY}"

SectionEnd
