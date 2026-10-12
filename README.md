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
| [Development environment (devenv)](#development-environment-devenv) | Working on the app | available |
| [Docker](#docker) | Using the app without Nix | planned ([roadmap item 19](roadmap/19-docker-image-and-compose.md)) |
| [Gradle without Nix](#gradle-without-nix) | Working on the app without Nix | planned ([roadmap item 33](roadmap/33-gradle-bootstrap-without-nix.md)) |

All ways need **Linux on x86-64**. A GPU is optional: whisper.cpp runs on any GPU with a **Vulkan** driver and falls back to the CPU otherwise. The first build of the package or the development shell downloads about 600 MB of models.

### Packaged app with Nix

Prerequisites: [Nix](https://nixos.org/download/) with flakes enabled (`experimental-features = nix-command flakes` in `nix.conf`). Everything else — Java, whisper.cpp, ffmpeg, the models and the built frontend — comes with the package.

```sh
git clone <repository-url> speech-to-text-app
cd speech-to-text-app
nix run .
```

Then open <http://localhost:8080>. `nix build .` builds the same app into `./result/bin/stt-app` without starting it. Details: [docs/specs/nix-flake-package.md](docs/specs/nix-flake-package.md).

### Development environment (devenv)

Prerequisites: [Nix](https://nixos.org/download/), [devenv](https://devenv.sh/getting-started/) and, recommended, [direnv](https://direnv.net/) (hooked into your shell). The development shell provides every tool and pins every version — JDK 21, Gradle, Node.js, pnpm, whisper.cpp, ffmpeg, SQLite, Playwright and the models ([ADR 0010](docs/decisions/0010-development-toolchain.md)); nothing else is installed by hand.

```sh
git clone <repository-url> speech-to-text-app
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

Not available yet. [Roadmap item 19](roadmap/19-docker-image-and-compose.md) adds a Docker image and a `docker-compose.yml`, with GPU passthrough, and documents it here.

### Gradle without Nix

Not available yet. [Roadmap item 33](roadmap/33-gradle-bootstrap-without-nix.md) adds a Gradle bootstrap that fetches the tools itself, for developers who do not use Nix, and documents it here.

## Configuration

The backend is configured with environment variables. The Nix package and the development shell set all of them; you only set them yourself to change something.

| Variable | Required | Meaning |
| --- | --- | --- |
| `STT_DATA_DIR` | yes (the Nix package has a default) | Where the database and the stored audio live — see [Where your data lives](#where-your-data-lives). |
| `STT_WHISPER_MODEL` | yes | The whisper.cpp model file. The pinned default is `large-v3-turbo-q5_0` ([ADR 0008](docs/decisions/0008-default-whisper-model.md)). |
| `STT_SPEAKER_MODEL` | yes | The CAM++ speaker-embedding model (ONNX) used for diarization ([ADR 0003](docs/decisions/0003-kotlin-diarization-campplus-onnx.md)). |
| `STT_PORT` | no | The HTTP port, default `8080`. |
| `STT_STATIC_DIR` | no | A built frontend (`frontend/dist`) to serve. Unset, the backend serves only the API. |

A missing required variable or an invalid port stops the app at startup with a message naming it. `whisper-cli` and `ffmpeg` are run from `PATH` ([ADR 0047](docs/decisions/0047-external-tools-resolved-from-path.md)).

The compute device (automatic, CPU or a specific GPU), the number of threads and the defaults for new uploads (language, speakers) are not environment variables: they are set on the app's **Settings** page and stored in the database ([docs/specs/settings-page.md](docs/specs/settings-page.md)).

## Where your data lives

Everything the app keeps — the queue, the transcripts, the speaker names and the audio — is in one directory:

| How you run it | Data directory |
| --- | --- |
| Nix package | `$STT_DATA_DIR` if set, else `${XDG_DATA_HOME:-~/.local/share}/stt-app` |
| `devenv up` and the devenv shell | `.devenv/state/stt` inside the repository (git-ignored, survives restarts) |
| `gradle run` | `$STT_DATA_DIR`, which you choose |

Inside it:

- `stt.db` — the SQLite database, with `stt.db-wal` and `stt.db-shm` next to it (write-ahead log). It holds the tasks, the transcripts, the speakers, the settings and the audio of every task that has been split into chunks.
- `work/` — per-task scratch space; the uploaded original stays here until the task has been split into chunks.
- `tmp/` — upload spools and the playback cache; cleared on every start.

**Backup**: stop the app, then copy the whole directory (`tmp/` may be left out). Restore by copying it back while the app is stopped. Copying only `stt.db` is not enough: the write-ahead log and the originals in `work/` are part of the state ([ADR 0017](docs/decisions/0017-database-location-and-connection-contract.md), [ADR 0024](docs/decisions/0024-audio-storage-original-until-chunking-flac-chunks.md)). If the app stops while a task runs, that task continues from its last finished chunk on the next start.

## Documentation

- [Architecture overview](docs/architecture.md) — a guided tour of how the app works and where each part lives in the code.
- [Specifications](docs/specs/) — one per finished roadmap item, with its test list.
- [Decisions](docs/decisions/README.md) — the architecture decision records.
- [API examples](docs/api/README.md) — an example of every response and live event of the HTTP API.
- [Roadmap](roadmap/README.md) — pending work.
- [AGENTS.md](AGENTS.md) — how this repository is developed: the rules, the workflow and the commands.

## Licenses and attributions

The repository does not declare a license yet. Third-party material:

- **Test fixtures** — clips from the LibriSpeech ASR corpus (CC BY 4.0); see [docs/attributions.md](docs/attributions.md).
- **Models** — the whisper.cpp model ([ADR 0008](docs/decisions/0008-default-whisper-model.md)) and the CAM++ speaker model ([ADR 0003](docs/decisions/0003-kotlin-diarization-campplus-onnx.md)) are downloaded from their publishers at build time, not stored in the repository; their sources are pinned in [`nix/models.nix`](nix/models.nix).
