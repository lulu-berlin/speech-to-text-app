# Speech-to-Text

A self-hosted web app that transcribes audio files and tells the speakers apart. Drop in as many recordings as you like: they wait in a queue and are transcribed one after another on your own machine, with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) for the speech and a CAM++ speaker model for "who spoke when". Nothing leaves your computer.

- **Upload many files at once** — audio and common video containers (`mp3`, `wav`, `flac`, `ogg`, `opus`, `m4a`, `aac`, `wma`, `aiff`, `amr`, `webm`, `mp4`, `mov`, `mkv`, `3gp` and more; the full list is [`docs/api/allowed-extensions.json`](docs/api/allowed-extensions.json)). The same file uploaded twice is recognized and not processed again.
- **A queue you control** — pause and resume the whole queue or a single task, cancel, retry, delete, and reorder by drag and drop. Progress updates live while a task runs.
- **Transcription with speaker diarization** — pick the language or let it auto-detect; let the app find the number of speakers, fix it, or switch speakers off.
- **History** — every finished transcription stays available, also after a restart.
- **Playback** — listen to the recording with the transcript highlighted as it plays; the view follows along, and clicking a sentence jumps there.
- **Speakers** — rename a speaker ("Speaker 1" → "Alice") or merge two speakers into one; a merge can be undone.
- **Downloads** — the transcript as plain text (`.txt`) or subtitles (`.srt`, `.vtt`), labelled with the speaker names.
- **Settings** — choose the compute device (automatic, CPU or a GPU) and the defaults for new uploads.

| Upload and queue | Playback that follows the transcript | Rename and merge speakers |
| --- | --- | --- |
| ![Uploading files and watching the queue work](docs/images/upload-queue.gif) | ![Playback with the highlight following the transcript](docs/images/playback-follow.gif) | ![Renaming a speaker and merging two speakers](docs/images/speaker-rename-merge.gif) |

<details>
<summary>Screenshots (light and dark)</summary>

| | Light | Dark |
| --- | --- | --- |
| Queue with a running task | ![Queue, light](docs/images/screenshots/queue-running-light-desktop.png) | ![Queue, dark](docs/images/screenshots/queue-running-dark-desktop.png) |
| An upload in flight | ![Upload, light](docs/images/screenshots/queue-uploading-light-desktop.png) | ![Upload, dark](docs/images/screenshots/queue-uploading-dark-desktop.png) |
| History | ![History, light](docs/images/screenshots/history-light-desktop.png) | ![History, dark](docs/images/screenshots/history-dark-desktop.png) |
| Transcript during playback | ![Transcript, light](docs/images/screenshots/transcript-light-desktop.png) | ![Transcript, dark](docs/images/screenshots/transcript-dark-desktop.png) |
| A canceled task | ![Canceled task, light](docs/images/screenshots/status-light-desktop.png) | ![Canceled task, dark](docs/images/screenshots/status-dark-desktop.png) |

</details>

How it works inside — the components, the life of a task, the data model, the API and the frontend — is the [architecture overview](docs/architecture.md).

## Ways to run it

| | Who it is for | Status |
| --- | --- | --- |
| [Packaged app with Nix](#packaged-app-with-nix) | Using the app | available |
| [Desktop app](#desktop-app) | Using the app in its own window, without a browser | available on Linux |
| [Development environment (devenv)](#development-environment-devenv) | Working on the app | available |
| [Docker](#docker) | Using the app without Nix | available |
| [Gradle without Nix](#gradle-without-nix) | Working on the app without Nix | available |

All ways need **Linux on x86-64**; the Gradle path also has macOS builds, untested. A GPU is optional: whisper.cpp runs on any GPU with a **Vulkan** driver and falls back to the CPU otherwise. The first build of the package, the development shell or the Docker image downloads about 600 MB of models; the desktop app downloads them on its first start.

### Packaged app with Nix

Prerequisites: [Nix](https://nixos.org/download/) with flakes enabled (`experimental-features = nix-command flakes` in `nix.conf`). Everything else — Java, whisper.cpp, ffmpeg, the models and the built frontend — comes with the package.

```sh
git clone https://github.com/lulu-berlin/speech-to-text-app.git
cd speech-to-text-app
nix run .
```

Then open <http://localhost:8080>. `nix build .` builds the same app into `./result/bin/stt-app` without starting it. Details: [docs/specs/nix-flake-package.md](docs/specs/nix-flake-package.md).

### Desktop app

The same app in its own window: the window shows the UI that a bundled backend serves, and nothing needs a browser, a server or a port you choose. It comes in three forms with the same behavior; pick the one your machine can run:

| Form | For | Build it with | Needs on the machine |
| --- | --- | --- | --- |
| [`.deb`](#deb-debian-and-ubuntu) | Debian 13+, Ubuntu 23.10+ | `pnpm --dir desktop dist` | apt (it installs what the package declares) |
| [Nix package](#nix-package) | NixOS, or any Linux with Nix | `nix run .#desktop` | Nix |
| [Single-file bundle](#single-file-bundle-any-distribution) | any Linux on x86-64, also without glibc | `nix build .#desktop-bundle` | `sh`, `tar`, `zstd`, unprivileged user namespaces |

What all three do:

- **First start**: a screen says "Preparing the speech analysis models…" and downloads the two pinned models (about 600 MB, from GitHub releases only) into `${XDG_CACHE_HOME:-~/.cache}/stt-app/models`, checks each file's sha256 and offers **Retry** if a download fails; later starts reuse them. A model you set yourself with `STT_WHISPER_MODEL` or `STT_SPEAKER_MODEL` is used instead, and when the pinned models are already in a Nix store, they are taken from there.
- **Your data** is in the same directory as the Nix package's and `./gradlew run`'s, so all of them share one history ([Where your data lives](#where-your-data-lives)).
- **Private by default**: the backend listens only on `127.0.0.1`, on a free port picked at each start, and the window itself cannot reach the network ([ADR 0055](docs/decisions/0055-desktop-window-has-no-network-path.md)).
- **Quitting** the window stops the backend and every whisper.cpp and ffmpeg process it started.

Compared with the [Nix package](#packaged-app-with-nix) and [Docker](#docker), which serve the app at <http://localhost:8080> to any browser (with Docker, to the network you publish it on), the desktop app is one user's window app: the same backend, the same data format, no server to keep running.

#### `.deb` (Debian and Ubuntu)

Build it from the repository (JDK 21, Node.js and pnpm; the [development shell](#development-environment-devenv) provides all of them):

```sh
./gradlew -p backend installDist          # in the devenv shell: cd backend && gradle installDist
pnpm --dir frontend install && pnpm --dir frontend build
pnpm --dir desktop install && pnpm --dir desktop fetch:jdk && pnpm --dir desktop dist
sudo apt install ./desktop/dist/stt-app_0.1.0_amd64.deb
stt-app
```

The package carries its own Java runtime, whisper.cpp and ffmpeg; its whisper.cpp build needs glibc 2.38 or newer, hence Debian 13+ and Ubuntu 23.10+ (tested on Ubuntu 24.04). It uses the system's Vulkan driver for the GPU (not covered by the desktop tests).

#### Nix package

```sh
nix run .#desktop
```

`nix build .#desktop` builds it into `./result/bin/stt-app` without starting it. On NixOS it uses the system's GPU drivers (tested with an AMD and an NVIDIA GPU).

#### Single-file bundle (any distribution)

```sh
nix build .#desktop-bundle                  # or: nix bundle --bundler .#desktop .#packages.x86_64-linux.desktop
./result/bin/stt-app-bundle
```

One file of about 400 MB that a machine without Nix can run — copy `result/bin/stt-app-bundle` there. On its first start it unpacks itself once into `${XDG_CACHE_HOME:-~/.cache}/stt-app-bundle/` (about 1.2 GB) and then runs the app in a [bubblewrap](https://github.com/containers/bubblewrap) namespace that shows the whole machine — your files, with your permissions — except `/nix`, which is the bundle's own ([ADR 0053](docs/decisions/0053-linux-desktop-deb-flake-package-bundle.md)). It needs no root, no glibc of the host (tested on Alpine) and no packages besides `tar` and `zstd`, but it does need **unprivileged user namespaces**: Ubuntu 24.04 restricts them by default (AppArmor), and there the `.deb` is the way. On NixOS use the Nix package instead.

The bundle brings its own fonts (IBM Plex, which has no fonts for 15 of whisper's scripts yet — see [Known issues](docs/known-issues.md)) and its own GPU drivers for transcription:

| GPU | In the bundle | Tested |
| --- | --- | --- |
| AMD | Mesa RADV, shipped | yes (Radeon 680M, Ubuntu container) |
| NVIDIA with NVIDIA's driver | the installed driver, borrowed (it must match the kernel module) | yes (RTX 3050, Ubuntu container, driver 595.71.05) |
| NVIDIA with `nouveau` | Mesa NVK, shipped | no |
| Intel | Mesa ANV, shipped | no |

The window itself is drawn on the CPU; only transcription uses the GPU.

#### Testing the desktop app

Each form has its packaged end-to-end suite, run in a clean container from the development shell: `pnpm --dir e2e test:desktop` (the `.deb`, after building it), `test:desktop:nix` and `test:desktop:bundle`; on a machine with that GPU also `test:desktop:bundle:amd` and `test:desktop:bundle:nvidia`.

### Development environment (devenv)

Prerequisites: [Nix](https://nixos.org/download/), [devenv](https://devenv.sh/getting-started/) and, recommended, [direnv](https://direnv.net/) (hooked into your shell). The development shell provides every tool and pins every version — JDK 21, Gradle, Node.js, pnpm, whisper.cpp, ffmpeg, SQLite, Playwright and the models ([ADR 0010](docs/decisions/0010-development-toolchain.md)); nothing else is installed by hand.

The shell's `ffmpeg` is the app's audio-only static build from the pinned `desktop-ffmpeg-v2` asset (roadmap item 62), so it has neither `ffplay` nor `ffprobe`; use another player to listen to a file and another tool to probe media.

The shell's `whisper-cli` is the same pinned 1.8.4 Vulkan build `nix build .` and `.#desktop` ship, from the `desktop-whisper-cpp-v1` asset (roadmap item 63). Every system the flake declares must pin a whisper-cpp artifact — a flake check enforces it — so no machine compiles it.

The end-to-end fixture generator (`e2e/global-setup.mjs`) needs a full `ffmpeg` on `PATH` — the `wav` muxer, the `pcm_s16le` encoder and the `concat` filter; the shell's ffmpeg is the app's slim build, not a general-purpose one. Use `./gradlew e2e` (its managed tools) or prepend a Nix `ffmpeg_8-full` from the flake's pin when running `pnpm --dir e2e test` directly.

```sh
git clone https://github.com/lulu-berlin/speech-to-text-app.git
cd speech-to-text-app
direnv allow        # or: devenv shell
```

**Dev mode** — the whole app with one command, the frontend with hot reload and the backend rebuilt and restarted when its sources change ([docs/specs/dev-mode.md](docs/specs/dev-mode.md)):

```sh
devenv up
```

Open the Vite URL it prints (<http://localhost:5173>). Stop with Ctrl-C, then `q`, or run `devenv down`.

**The app as it ships, from source** — build the frontend once and let the backend serve it:

```sh
(cd frontend && pnpm install && pnpm build)
cd backend
STT_STATIC_DIR=../frontend/dist gradle run
```

Then open <http://localhost:8080>. Tests and the other commands are in [AGENTS.md § Commands](AGENTS.md#commands).

### Docker

Prerequisites: Docker with Compose v2. Nothing else: the image is built on your machine from this repository's Nix flake, inside a `nixos/nix` build container, so the host needs no Nix; nothing is pulled from or pushed to an app registry. On macOS and Windows, Docker Desktop runs the Linux image on the CPU only.

```sh
git clone https://github.com/lulu-berlin/speech-to-text-app.git
cd speech-to-text-app
docker compose up -d --build
```

Then open <http://localhost:8080>. The first build takes a while (about 11 minutes on the machine it was tested on: it downloads the pinned nixpkgs and the models and runs the backend's tests); later builds reuse Docker's cache until the flake or the sources change. The image is about 3.3 GB.

- `docker compose ps` — the status; a healthcheck calls `GET /api/health`.
- `docker compose logs -f stt-app` — the app's log.
- `docker compose stop` / `docker compose start` — a task interrupted by `stop` continues after `start`.
- `docker compose down` — removes the container; the data stays in the `stt-data` volume ([Where your data lives](#where-your-data-lives)). `docker compose down -v` deletes it.

The container runs as an unprivileged user (uid 1000) and writes only to its data volume and scratch space.

**GPU.** `docker-compose.yml` alone runs on the CPU. An override file adds a GPU; the Settings page then lists the GPUs the container sees, and the default device (automatic) uses the first one.

| GPU | The host needs | Start with |
| --- | --- | --- |
| NVIDIA | the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/cdi-support.html) with a CDI spec — on NixOS `hardware.nvidia-container-toolkit.enable = true;`, elsewhere `nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml` | `docker compose -f docker-compose.yml -f docker-compose.gpu-nvidia.yml up -d` |
| Intel / AMD | only the kernel's GPU driver: the image brings Mesa's Vulkan drivers | `STT_RENDER_GID=$(getent group render \| cut -d: -f3) docker compose -f docker-compose.yml -f docker-compose.gpu-vulkan.yml up -d` |

`STT_RENDER_GID` lets the container's user open the render device. Both variants were tested on NixOS (an AMD Radeon iGPU and an NVIDIA RTX 3050); NVIDIA on other distributions is untested. Details: [roadmap item 19](docs/specs/docker-image-and-compose.md).

### Gradle without Nix

For working on the app without Nix: the repository's `./gradlew` downloads everything else itself — Gradle 9.8, JDK 21, Node.js, pnpm, whisper-cli 1.8.4, ffmpeg 8.1 and the models — at the versions the Nix flake pins (the committed [`versions.json`](versions.json), which the flake checks), and verifies every file's SHA-256. Linux x86-64 is tested; the macOS builds (Intel and Apple Silicon) are best-effort and untested. This path is for people; AI agents working on the repository keep to Nix and devenv ([AGENTS.md](AGENTS.md)).

Prerequisites:

- **Java 21 or newer** (Gradle 9.8 itself runs on Java 17–27, but the backend compiles with `-Xjdk-release=21`, so a 17–20 launcher JVM cannot build it). The app is run with JDK 21, which Gradle downloads when your Java is another version.
- **Linux**: glibc 2.38 or newer (Ubuntu 24.04+, Debian 13+, Fedora 39+) and the system libraries the prebuilt tools are linked against — only this path needs them; Nix, devenv and Docker bring their own:
  - whisper-cli: `libstdc++6`, `libgomp1` and the Vulkan loader `libvulkan1` (needed even without a GPU);
  - ffmpeg: `libasound2` (`libasound2t64` on Ubuntu 24.04+ and Debian 13), `libpulse0`, `libxcb1`, `libxcb-shm0` and `libudev1`.

  The list is owned by `./gradlew runtimeSystemDeps`, so this README, the bootstrap and CI cannot
  drift. The integration tests depend on it, so a host that is missing a library fails there with the
  exact `sudo apt-get install …` line instead of a loader error inside a test; running the task as
  root installs the libraries. On Ubuntu 24.04, with Java and git:

  ```sh
  sudo apt-get install -y openjdk-25-jdk-headless git
  ./gradlew runtimeSystemDeps
  ```

- **macOS**: `setsid` (the app starts whisper-cli and ffmpeg through it), from Homebrew's `util-linux`, which does not put it on `PATH` by itself:

  ```sh
  brew install util-linux
  export PATH="$(brew --prefix util-linux)/bin:$PATH"
  ```

```sh
git clone https://github.com/lulu-berlin/speech-to-text-app.git
cd speech-to-text-app
./gradlew run
```

Then open <http://localhost:8080>; stop with Ctrl-C. The first run downloads about 850 MB (more when Gradle also has to download JDK 21) and builds the frontend and the backend. Later runs reuse the downloads and still check every file's SHA-256.

- `./gradlew dev` — dev mode, like `devenv up`: the Vite dev server on <http://localhost:5173> with hot reload, and the backend rebuilt and restarted when its sources change. Vite is ready first; the API answers once the backend has built and logs `Responding at`. Ctrl-C stops both.
- `./gradlew e2e` — the [end-to-end tests](e2e/) against the built app, with Gradle's own test fixtures and Chromium. On Linux, Chromium also needs system libraries from apt (Debian and Ubuntu only). `./gradlew e2eSystemDeps` installs them when run as root, for example in a container; run as yourself, it only checks them and, if some are missing, fails with the `sudo apt-get install …` line to run (Gradle never asks for your password):

  ```sh
  ./gradlew e2eSystemDeps
  ./gradlew e2e
  ```

- `./gradlew checkVersions` — checks that `versions.json` still matches the Gradle wrapper and the flake's locked nixpkgs.
- `./gradlew -p backend check` — the backend's tests. They need a UTF-8 locale, for example `export LANG=C.UTF-8` first; `./gradlew run` does not. A running Gradle daemon keeps the locale it started with, so after changing it run `./gradlew --stop` once.

Where things live:

- The downloads: `~/.gradle/stt-app/` (`dl/`, `tools/`, `models/`, `fixtures/`, `node/`, `ms-playwright/`), shared by every clone; `GRADLE_USER_HOME` moves it with the rest of Gradle's files. Deleting it is safe: the next run downloads again.
- The data: `${XDG_DATA_HOME:-~/.local/share}/stt-app`, the same directory as the Nix package's ([Where your data lives](#where-your-data-lives)).
- Every [`STT_*` variable](#configuration) you set yourself wins over Gradle's default, for example `STT_DATA_DIR` or `STT_PORT`.

## Configuration

The backend is configured with environment variables. The Nix package, the development shell, `./gradlew run`/`dev` and the desktop app set all of them; you only set them yourself to change something.

| Variable | Required | Meaning |
| --- | --- | --- |
| `STT_DATA_DIR` | yes (the Nix package and `./gradlew` have a default) | Where the database and the stored audio live — see [Where your data lives](#where-your-data-lives). |
| `STT_WHISPER_MODEL` | yes | The whisper.cpp model file. The pinned default is `large-v3-turbo-q5_0` ([ADR 0008](docs/decisions/0008-default-whisper-model.md)). |
| `STT_SPEAKER_MODEL` | yes | The CAM++ speaker-embedding model (ONNX) used for diarization ([ADR 0003](docs/decisions/0003-kotlin-diarization-campplus-onnx.md)). |
| `STT_PORT` | no | The HTTP port, default `8080`. |
| `STT_STATIC_DIR` | no | A built frontend (`frontend/dist`) to serve. Unset, the backend serves only the API. |
| `STT_HOST` | no | The interface the backend listens on, default `0.0.0.0` (every interface). The desktop app sets `127.0.0.1`. |
| `STT_MAX_UPLOAD_BYTES` | no | The largest upload in bytes, default 1 GiB (`1073741824`). An upload over it is refused with `413` `upload-too-large`; the limit bounds the file part's bytes, and a request that declares a larger body is refused outright. |

A missing required variable, an invalid port or an invalid upload size stops the app at startup with a message naming it. `whisper-cli` and `ffmpeg` are run from `PATH` ([ADR 0047](docs/decisions/0047-external-tools-resolved-from-path.md)).

The compute device (automatic, CPU or a specific GPU), the number of threads and the defaults for new uploads (language, speakers) are not environment variables: they are set on the app's **Settings** page and stored in the database ([docs/specs/settings-page.md](docs/specs/settings-page.md)).

## Where your data lives

Everything the app keeps — the queue, the transcripts, the speaker names and the audio — is in one directory:

| How you run it | Data directory |
| --- | --- |
| Nix package | `$STT_DATA_DIR` if set, else `${XDG_DATA_HOME:-~/.local/share}/stt-app` |
| Desktop app (`.deb`, Nix package, bundle) | `$STT_DATA_DIR` if set, else `${XDG_DATA_HOME:-~/.local/share}/stt-app` |
| `devenv up` and the devenv shell | `.devenv/state/stt` inside the repository (git-ignored, survives restarts) |
| `gradle run` | `$STT_DATA_DIR`, which you choose |
| `./gradlew run` and `./gradlew dev` | `$STT_DATA_DIR` if set, else `${XDG_DATA_HOME:-~/.local/share}/stt-app` |
| Docker | the named volume `stt-data`, mounted at `/data` in the container |

Inside it:

- `stt.db` — the SQLite database, with `stt.db-wal` and `stt.db-shm` next to it (write-ahead log). It holds the tasks, the transcripts, the speakers, the settings and every task's audio: the whole recording as one canonical FLAC (an upload's original lives here only while the task is being chunked).
- The app's working scratch — the materialized original, the decoded audio, whisper's inputs — lives outside it, under the OS temp directory; the app removes it as it works and empties it on every start.

**Backup**: stop the app, then copy `stt.db` — once the app has stopped cleanly, that one file holds the whole history and every task's audio (the write-ahead log is folded into it on a clean stop). Restore by copying it back while the app is stopped. If the app did not stop cleanly, copy `stt.db-wal` and `stt.db-shm` along with it: the write-ahead log is then still part of the state ([ADR 0017](docs/decisions/0017-database-location-and-connection-contract.md)). If the app stops while a task runs, that task continues from its last finished chunk on the next start.

With Docker, back up the volume while the app is stopped, and restore it the same way (the restored files belong to the container's user, uid 1000):

```sh
docker compose stop
docker run --rm -v stt-data:/data:ro -v "$PWD":/backup alpine:3 tar czf /backup/stt-data.tar.gz -C /data .
# restore: docker run --rm --user 1000:1000 -v stt-data:/data -v "$PWD":/backup alpine:3 tar xzf /backup/stt-data.tar.gz -C /data
docker compose start
```

**Upgrading a data directory from before the audio moved into the database** (roadmap item 44): the first start converts it. Every finished or partly transcribed task keeps its audio and its transcript. A task that had not been cut into chunks yet — still queued, paused, interrupted while chunking, or canceled or failed before it — kept its upload as a file, which is not carried over: it ends *failed* with "This task's audio from before the upgrade was not migrated. Delete the task and upload the file again." The old `work/` and `tmp/` directories are removed ([ADR 0051](docs/decisions/0051-canonical-clip-backfill-migration.md)).

## Documentation

- [Architecture overview](docs/architecture.md) — a guided tour of how the app works and where each part lives in the code.
- [Specifications](docs/specs/) — one per finished roadmap item, with its test list.
- [Decisions](docs/decisions/README.md) — the architecture decision records.
- [API examples](docs/api/README.md) — an example of every response and live event of the HTTP API.
- [Known issues](docs/known-issues.md) — the living backlog of open findings; an issue leaves it the moment a roadmap item plans it.
- [Roadmap](roadmap/README.md) — pending work.
- [AGENTS.md](AGENTS.md) — how this repository is developed: the rules, the workflow and the commands.

## Licenses and attributions

The app is licensed under the [BSD 3-Clause License](LICENSE). Third-party material:

- **Test fixtures** — clips from the LibriSpeech ASR corpus (CC BY 4.0); see [docs/attributions.md](docs/attributions.md).
- **Models** — the whisper.cpp model ([ADR 0008](docs/decisions/0008-default-whisper-model.md)) and the CAM++ speaker model ([ADR 0003](docs/decisions/0003-kotlin-diarization-campplus-onnx.md)) are downloaded at build time (the desktop app: at its first start), not stored in the repository: the whisper model from this repository's GitHub release `desktop-models-v1`, a byte-identical mirror of its publisher's file, and the CAM++ model from its publisher's GitHub release; both are pinned in [`nix/models.nix`](nix/models.nix).
- **Desktop bundle** — it carries the IBM Plex fonts (SIL Open Font License 1.1) and Mesa's Vulkan drivers (MIT); NVIDIA's driver is not shipped but borrowed from the machine it runs on.
