# 0060 — Windows releases through the Microsoft Store (MSIX); the macOS DMG stays unsigned with a documented Gatekeeper bypass

Status: accepted · Date: 2026-10-23

## Context

Item 50 shipped the first desktop artifacts **unsigned** and without auto-update: no signing
identity, no hardened runtime, no notarization, and the macOS Gatekeeper "unidentified developer"
notice was accepted "until asked otherwise" (`docs/specs/electron-desktop-app.md`). Item 49's
per-user MSI is unsigned the same way.

Roadmap item 78 prepares the first stable release. The user decided in the 2026-10-23 consultations:

- **Windows**: Microsoft Store MSIX is the signing route. Microsoft signs Store-published packages,
  so there is no developer certificate, no hardware token and no subscription. The portable zip and
  the per-user MSI stay unsigned direct downloads.
- **macOS**: the **DMG stays unsigned**. Developer ID signing and notarization require an Apple
  Developer Program membership (US$99/year, renewed annually). Apple's published fee waiver covers
  only nonprofits, accredited educational institutions and government entities, requires a legal
  entity (D-U-N-S number, organization domain) and is not available to an individual; open-source
  status is not a waiver category. The user does not take on an annual subscription for this.

## Decision

- **Windows**: the Store package is an **MSIX** published through the Microsoft Store; Microsoft
  signs it on publish, so there is no developer certificate, no hardware token and no SmartScreen
  gate for Store installs. The portable zip and the per-user MSI stay unsigned direct downloads,
  documented as such (SmartScreen warns until the user chooses to proceed); they are not the
  recommended path.
- **macOS**: the DMG is unsigned and unnotarized. The release notes and the README document the
  exact first-launch steps (download, then right-click → Open, or System Settings → Privacy &
  Security → Allow Anyway). The Nix path (`nix run`, `nix build .#desktop`) is unaffected: files Nix
  builds carry no quarantine attribute, so Gatekeeper never prompts there; the single-file bundle
  for Linux has no counterpart on macOS.
- **Data location** (Windows): the Store package aims for the `unvirtualizedResources` restricted
  capability so it keeps writing `%APPDATA%\stt-app` (and `%LOCALAPPDATA%\stt-app\models`), like the
  zip and MSI builds — one data directory across all three channels. If the Store rejects the
  capability, the package's writes are virtualized into its own data container, the limitation is
  documented in the release notes, and the export/backup path is the documented way out.
- **Secrets**: none for signing. The Store submission is the user's manual step (Store account,
  Partner Center), like every other publish.
- **Scope**: this applies to the artifacts a release publishes (Linux `.deb` and bundle, macOS DMG,
  Windows MSIX, zip and MSI). Developer builds and the local `./gradlew` outputs stay unsigned.
- **No auto-update**: no updater is added; on Windows the Store updates the MSIX, elsewhere a new
  release is announced through the release page (item 78).

## Consequences

- Linux and Windows releases carry no signing cost; the Windows path pays a one-time Store
  registration fee (about US$19 for individuals; verify at signup) and each submission goes through
  Store review (hours to days), so a Windows hotfix is a resubmission, not an instant upload.
- The macOS DMG shows the Gatekeeper block on first launch. The documentation must make the two-step
  bypass unmissable; a notarized DMG becomes possible later without changing the artifacts, should
  the project ever have a Developer Program membership (a sponsor, a nonprofit host, or paid
  membership) — the release workflow then adds `codesign` + `notarytool` + staple.
- MSIX data has two forms: with `unvirtualizedResources` it is the shared `%APPDATA%\stt-app`;
  without it, it lives in the package container — preserved across updates (same package family) but
  removed on uninstall and invisible to the zip/MSI builds.
- The MSIX has package identity, so its data and settings are tied to the Store identity; the zip
  and MSI builds are unaffected and remain the offline path.
- Item 50's spec line points here; the release notes state the unsigned macOS status instead of
  hiding it.
