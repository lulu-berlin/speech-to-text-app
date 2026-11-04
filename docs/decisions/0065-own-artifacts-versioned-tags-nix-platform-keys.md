# 0065 — We publish our own artifacts, their tags name what determines their content, and platform keys are Nix system names

Status: accepted · Date: 2026-11-03

## Context

Three routes downloaded whisper-cli from places we do not control: the Gradle bootstrap took the
jiang1997 `whisper.cpp-release` prebuilds for Linux and Intel macOS, and the Nix package, the
desktop package and the installers took a mix of that prebuild and our own assets. A nixpkgs bump
moved the source build's out path and invalidated every pinned artifact at once, which showed that
a bump is a release operation, not a pin refresh. In the same tree, `versions.json` and the code
around it spoke two platform vocabularies: Nix system names (`x86_64-linux`, the keys of
`nix/desktop-artifacts.nix`) and hand-minted download keys (`linux-x64`, `darwin-arm64`,
`windows-x64`). Intel macOS was already unsupported (Apple discontinued the platform and the pinned
nixpkgs dropped `x86_64-darwin`) while `darwin-x64` entries survived as dead data.

## Decision

- **One whisper.cpp version and flag set, published by us, on every route.** The development
  shell, the Nix packages, Docker, the Gradle bootstrap on Linux, macOS Apple Silicon and Windows,
  and the packaged installers ship 1.9.5 built with our flags (Vulkan on Linux, Metal on Apple
  Silicon, the self-contained static build on Windows). No route downloads a third-party
  whisper.cpp prebuild. On Linux the Nix build is not relocatable: its ELF interpreter and
  RUNPATH name `/nix/store` paths, so the Gradle bootstrap and the `.deb` cannot run it on a plain
  host. The Nix routes use the Nix source asset; the Gradle/desktop path uses a portable glibc
  asset built by the manual `linux-whisper-asset` job from the same upstream tag and flags
  (static whisper libraries, the system Vulkan loader), published under the same version tag. The
  two Linux outputs are toolchain variants of one version/config, not two versions.
- **Platform keys are Nix system names.** `versions.json`'s `downloads` and `supportedPlatforms`,
  the Kotlin bootstrap mapping and `desktop/verified-download.ts` use `x86_64-linux`,
  `aarch64-darwin` and `x86_64-windows`. Node's `win-x64` and Electron's `win32-x64` stay inside
  the functions that translate to them. Intel macOS is unsupported; Windows on ARM64 is out of
  scope until an item ships its asset (`aarch64-windows` is already a Nix double, so that item
  needs no schema change).
- **A release tag names the artifact's upstream version, for every family** (the user,
  2026-11-03): `whisper-cpp-<version>` (`whisper-cpp-1.9.5`), `ffmpeg-<version>`
  (`ffmpeg-8.1.3`) and `mesa-<version>` (`mesa-26.2.6`). The content is that upstream version
  built with our fixed configure lists, so a bump moves the tag, the file names and every URL
  together. A same-version rebuild (a flag, recipe or configure-list change) takes a suffix
  (`-2`, then `-3`); a published release asset is never replaced. This supersedes the earlier
  `desktop-ffmpeg-vN`/`desktop-mesa-vN` exception.
- **Whisper assets are `.tar.gz` on every system**, one archive format, because the Gradle
  bootstrap cannot read zstd; the size cost is accepted over a second copy of every asset.
- **A pin is valid only for the source build it was made from.** url, sha256, the source build's
  out path and its direct references are recorded together; a nixpkgs bump or recipe change
  invalidates every pin and forces a republish, so the bump and the artifacts ship as one change.
- **The user publishes; agents never upload.** Each producer (`nix build .#desktop-*-asset`, the
  manual CI jobs) prints the pin; the user uploads the tar and pastes the printed values.
- **Checks hold the vocabulary together**: every download key and supported platform is a Nix
  double, every supported platform pins every downloaded tool, the whisper tag/names/pins carry
  the manifest version, and a stale pin fails at evaluation naming both store paths.

## Consequences

- A nixpkgs bump leaves `nix flake check` red from the bump until the user republishes and pastes
  the printed pins; that intermediate state is expected and stays on one branch and one pull
  request.
- `checkVersions` and the flake checks fail on a download key that is not a Nix system name, on a
  supported platform without a tool pin, and on a whisper asset whose tag does not name the
  manifest version.
- Platform detection fails fast with the supported list on Intel macOS, Windows ARM64 and Linux
  ARM64, instead of resolving a download that does not exist.
- Docs name the current versions and tags; the specs' approval logs keep their historical text.
- The next platform (for example `aarch64-windows`) adds its key to `supportedPlatforms`, its
  assets, and its pins; no manifest schema change is needed.