# API contract examples

Example JSON for every response body, SSE event payload and error of the HTTP API. The contract itself is the "Contract" section of [roadmap item 12](../../roadmap/12-http-api-and-sse.md) (moved to `docs/specs/` when the item is done). The rule for these files is [ADR 0029](../decisions/0029-contract-first-frontend-shared-api-examples.md).

## Rules

- The frontend's MSW handlers serve these files and no other mock data (item 13).
- Item 12's route tests check every real response and event payload against the examples of its shape:
  - the same keys;
  - the same JSON value types;
  - `null` only where an example of that shape has `null`.
- The values in the files are illustrative.
- A shape can have several files (`<shape>.<variant>.json`). Together they define the shape: a field is nullable if `null` appears for it in any variant, and its type is the type of its non-null values.
- A contract change edits these files and item 12's contract section in the same commit.

## Shapes

| Shape | Files | Used by |
| ----- | ----- | ------- |
| Task | `task.queued.json`, `task.running.json`, `task.diarizing.json`, `task.paused.json`, `task.completed.json`, `task.failed.json`, `task.canceled.json` | `GET /api/tasks/{id}`; `201` of `POST /api/tasks`; `200` of `POST /api/tasks/{id}/pause\|resume\|cancel\|retry`; event `task-updated` |
| Task list | `task-list.json` | `GET /api/tasks` |
| Queue | `queue.json` | `GET /api/queue`; `200` of `POST /api/queue/pause\|resume` and `PUT /api/queue/order`; event `queue-updated` |
| Snapshot | `snapshot.json` | event `snapshot` (first event of every `/api/events` connection) |
| Task progress | `task-progress.json`, `task-progress.speakers-off.json` | event `task-progress` |
| Task deleted | `task-deleted.json` | event `task-deleted` |
| Turns | `turns.json`, `turns.speakers-off.json` | `GET /api/tasks/{id}/turns` |
| Speakers | `speakers.json` | `200` of `PUT /api/tasks/{id}/speakers/{speakerId}`, `POST /api/tasks/{id}/speakers/merge` and `DELETE /api/tasks/{id}/speakers/merges` (item 22) |
| Speakers event | `speakers-renamed.json`, `speakers-merged.json` | events `speakers-renamed` and `speakers-merged` (item 22) |
| Heartbeat | `heartbeat.json` | event `heartbeat`: sent after 5 s without any other event; carries no state (item 29, [ADR 0044](../decisions/0044-sse-heartbeat-and-client-watchdog.md)) |
| Settings | `settings.json`, `settings.fixed.json`, `settings.fallback.json` | `GET /api/settings`; `200` of `PUT /api/settings` and `DELETE /api/settings/device-fallback` (item 32; `gpuNames` added by item 45) |
| Settings event | `settings-updated.json`, `settings-updated.defaults.json`, `settings-updated.fallback.json` | event `settings-updated` (item 32) |
| Error | `error.json`, `error.invalid-language.json` (mock addition, item 14), `error.invalid-device.json` (item 32), `error.upload-too-large.json` (item 41) | every error response except `duplicate-audio` |
| Error (duplicate) | `error.duplicate-audio.json` | `409 duplicate-audio` of `POST /api/tasks` |

Responses without a body (`204` of `DELETE /api/tasks/{id}`), the transcript downloads (item 10's formats) and `/audio` (FLAC) have no example here.

The upload endpoint's item-41 refusals use the Error shape: `413 upload-too-large` (the message names the configured
limit, `error.upload-too-large.json`) and `400 multiple-files` (a second file part; the first is discarded). The
queue view renders both server messages inline without special-casing the codes.

## Task transition table

`task-transitions.json` is contract data, not a response. It lists:
- `transitions`: the allowed status transitions per status (item 04, amended by item 11 for retry, by item 42 for a RUNNING task whose process was killed, and by item 42 again for a QUEUED task whose start failed, ADR 0057/0058);
- `commands`: for each task command endpoint, the statuses it accepts and the status it requests.

Delete is not a transition and works on any task.

- The frontend's state machine reads this table, so the UI only offers commands the task's status allows (item 13).
- A backend test checks that `TaskStatus` has exactly these transitions (item 11).

## Languages

`languages.json` is contract data, not a response: the language codes whisper-cli 1.8.4 accepts, with their names, plus `auto` (auto-detect), which is the default for uploads.

- The upload form's language select reads it (item 14).
- Item 12 validates the `language` upload field against it (`400 invalid-language` otherwise).

It was extracted from whisper.cpp's own C API (`whisper_lang_max_id`, `whisper_lang_str`, `whisper_lang_str_full`), not written by hand. When the whisper-cpp version changes, regenerate it the same way and update `whisperVersion`. From the repository root, with the devenv shell's `whisper-cli` and `gcc` on `PATH`:

```sh
L=$(dirname "$(dirname "$(readlink -f "$(command -v whisper-cli)")")")/lib
cat > /tmp/langs.c <<'EOF'
#include <stdio.h>
int whisper_lang_max_id(void);
const char *whisper_lang_str(int id);
const char *whisper_lang_str_full(int id);
int main(void) {
  int n = whisper_lang_max_id();
  printf("{\n  \"whisperVersion\": \"1.8.4\",\n  \"default\": \"auto\",\n  \"languages\": [\n    { \"code\": \"auto\", \"name\": \"auto-detect\" }");
  for (int i = 0; i <= n; i++) printf(",\n    { \"code\": \"%s\", \"name\": \"%s\" }", whisper_lang_str(i), whisper_lang_str_full(i));
  printf("\n  ]\n}\n");
  return 0;
}
EOF
gcc -o /tmp/langs /tmp/langs.c -L"$L" -lwhisper -Wl,-rpath,"$L" && /tmp/langs > docs/api/languages.json
```

## Upload allow-list

`allowed-extensions.json` is contract data, not a response: the extensions an upload accepts, checked case-insensitively (the content type is ignored).

- The queue's upload validation reads it; any other extension is rejected client-side before any request (item 14).
- The upload route validates the file's extension against a packaged copy of it (`400 unsupported-format` otherwise, item 12), pinned to this file by `AllowedExtensionsContractSpec` (item 28).

The order of the array is meaningful: it is also the order the backend's rejection message lists the allowed extensions. An extension belongs in the list only when whisper.cpp / ffmpeg on this project's stack can actually decode it.
