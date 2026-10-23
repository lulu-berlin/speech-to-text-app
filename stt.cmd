@echo off
rem Roadmap item 49: the Windows launcher for the no-Nix path. It runs the same Gradle
rem `run` task as ./gradlew run: the managed whisper-cli/ffmpeg/models, JDK 21, the
rem frontend build and the backend, with %APPDATA%\stt-app as the data directory.
rem A user-set STT_* variable wins over Gradle's defaults. Stop with Ctrl-C.
setlocal
cd /d "%~dp0"
call gradlew.bat run %*
exit /b %ERRORLEVEL%
