# The desktop app's Linux identity (roadmap item 74): one .desktop entry and one icon, shared by the
# Nix package (nix/desktop.nix) and the single-file bundle (nix/desktop-bundle.nix), so the menu
# shows the same app however it was installed. nix/desktop-entries.nix checks that they match, and
# e2e/desktop-tests/16-desktop-entry.spec.ts checks the running window against them.
{lib}: let
  packageJson = builtins.fromJSON (builtins.readFile ../desktop/package.json);

  # One name for the entry file and the window class, from the app's `desktopName` (package.json):
  # Electron derives its app_id — the X11 WM_CLASS — from that field, and a desktop environment
  # links a running window to its menu entry by the entry's file name or its StartupWMClass. Both
  # are this name (without the suffix, for the class), so every package is one app.
  desktopName = packageJson.desktopName;
  windowClass = lib.removeSuffix ".desktop" desktopName;
in {
  inherit desktopName windowClass;

  # The entry launching `exec` (each package passes its own launcher), line by line so its bytes do
  # not depend on Nix's multi-line indentation stripping; no trailing newline, so it can sit in a
  # shell here-document.
  entry = exec:
    lib.concatStringsSep "\n" [
      "[Desktop Entry]"
      "Type=Application"
      "Name=${packageJson.productName}"
      "Comment=Transcribe audio files with speaker diarization"
      "Exec=${exec}"
      "Icon=stt-app"
      "StartupWMClass=${windowClass}"
      "Terminal=false"
      "Categories=AudioVideo;Audio;Utility;"
    ];

  # The app's mark (the favicon, also the MSI's artwork as desktop/icon.png), installed as the
  # scalable hicolor icon named by the entry's Icon=.
  icon = ../frontend/src/shared/brand/favicon.svg;
}
