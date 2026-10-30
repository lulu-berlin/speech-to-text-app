# Item 74, test-list item "The Nix package and the bundle carry matching .desktop entries and icon":
# the Nix package ships the shared entry (named by package.json's desktopName, with
# StartupWMClass) and the app's mark as its hicolor icon;
# the single-file bundle writes the same entry and icon into the user's XDG data directory when asked
# with `--install-desktop-entry` (the user's choice, 2026-10-29: nothing is written unless asked).
# The two entries differ only in Exec, each launching its own app. `--remove-desktop-entry` undoes
# the bundle's install exactly, leaving the rest of the data directory alone.
{
  pkgs,
  desktop,
  bundle,
  icon,
  desktopName,
  windowClass,
}:
pkgs.runCommand "desktop-entries" {
  nativeBuildInputs = [pkgs.coreutils pkgs.diffutils pkgs.gnugrep];
} ''
  fail() {
    echo "desktop-entries: $1" >&2
    exit 1
  }

  # The real bundle, run the way a user runs the file they downloaded: by the build's POSIX shell
  # (the launcher's /usr/bin/env shebang does not exist in the sandbox), under a timeout, so a flag
  # the launcher does not implement falls through to unpacking and fails here instead of stalling.
  install_from() {
    timeout 120 sh "$1" --install-desktop-entry ||
      fail "stt-app-bundle --install-desktop-entry from $1 exited with $?"
  }
  remove_from() {
    timeout 120 sh "$1" --remove-desktop-entry ||
      fail "stt-app-bundle --remove-desktop-entry from $1 exited with $?"
  }

  # The Nix package: its entry launches its own bin/stt-app, and its icon is the app's mark.
  entry=${desktop}/share/applications/${desktopName}
  [ -f "$entry" ] || fail "the Nix package has no share/applications/${desktopName}"
  for line in "Type=Application" "Name=Speech-to-Text" "Icon=stt-app" "StartupWMClass=${windowClass}" "Exec=${desktop}/bin/stt-app"; do
    grep -qxF "$line" "$entry" || fail "the Nix package's entry lacks the line '$line'"
  done
  cmp -s ${desktop}/share/icons/hicolor/scalable/apps/stt-app.svg ${icon} ||
    fail "the Nix package's hicolor icon is not the app's mark (frontend/src/shared/brand/favicon.svg)"

  # The bundle, asked: a link to it, so Exec names the path the user runs.
  export HOME=$TMPDIR/home XDG_DATA_HOME=$TMPDIR/data
  mkdir -p "$HOME"
  ln -s ${bundle}/bin/stt-app-bundle "$TMPDIR/stt-app-bundle"
  install_from "$TMPDIR/stt-app-bundle"
  installed=$XDG_DATA_HOME/applications/${desktopName}
  [ -f "$installed" ] || fail "the bundle's --install-desktop-entry wrote no $installed"
  grep -qxF "Exec=\"$TMPDIR/stt-app-bundle\"" "$installed" ||
    fail "the bundle's entry does not launch the bundle file: $(grep '^Exec=' "$installed")"
  grep -v '^Exec=' "$entry" > package.entry
  grep -v '^Exec=' "$installed" > bundle.entry
  diff -u package.entry bundle.entry ||
    fail "the bundle's entry differs from the Nix package's beyond Exec"
  cmp -s "$XDG_DATA_HOME/icons/hicolor/scalable/apps/stt-app.svg" ${icon} ||
    fail "the bundle's installed hicolor icon is not the app's mark"

  # Removing undoes exactly that (roadmap item 74, discovered): the entry and the icon go, and
  # what else the data directory holds stays.
  touch "$XDG_DATA_HOME/keep"
  remove_from "$TMPDIR/stt-app-bundle"
  if [ -e "$installed" ]; then fail "the bundle's --remove-desktop-entry left $installed"; fi
  if [ -e "$XDG_DATA_HOME/icons/hicolor/scalable/apps/stt-app.svg" ]; then
    fail "the bundle's --remove-desktop-entry left its icon"
  fi
  [ -e "$XDG_DATA_HOME/keep" ] || fail "removing the entry deleted unrelated data"

  # A bundle stored where the path has a space and a reserved character (roadmap item 74,
  # discovered): the desktop-entry spec splits an unquoted Exec on whitespace and requires `\`, `"`,
  # `` ` `` and `$` escaped inside double quotes, so the value must be quoted and the `$` escaped or
  # the menu entry launches nothing. The expected line is written out here as the spec states it,
  # not computed by the launcher's own quoting.
  special="$TMPDIR/a dir \$"
  expected="Exec=\"$TMPDIR/a dir \\\$/bundle\""
  mkdir -p "$special"
  ln -s ${bundle}/bin/stt-app-bundle "$special/bundle"
  install_from "$special/bundle"
  grep -qxF "$expected" "$installed" ||
    fail "the bundle's entry does not quote its Exec as the spec requires: $(grep '^Exec=' "$installed")"
  remove_from "$special/bundle"
  if [ -e "$installed" ]; then fail "removal from a special path left the entry"; fi
  echo ok > $out
''
