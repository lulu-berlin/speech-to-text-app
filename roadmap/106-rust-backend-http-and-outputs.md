# 106 — The backend rewritten in Rust, phase 7: the remaining HTTP surface and the outputs

Status: spec-approved
Depends on: [105](105-rust-backend-engine-and-events.md)
Blocks: [107](107-rust-backend-app-mode.md)

## Goal

Every remaining route and the transcript downloads: tasks and the queue, settings, speakers and
turns, segment edits and revert, static files and the SPA fallback, the security responses, the
tray and launch routes, the database backup, the error bodies — and the `txt`, `srt` and `vtt`
outputs with their speaker labels. All 41 wire examples match byte for byte.

## Evidence

The rest of `stt/http` (40 suites, 91 scenarios) plus `stt/outputs` (8 suites, 11 scenarios): the
`SpeakerRoutesSpec`'s 25 reassignment scenarios, the 7 segment-edit scenarios, the settings and
frontend-selection suites, the queue reorder and command suites, the static/SPA/security suites,
the download suites and `UnknownApiPath`/`UnknownTask`/`InternalError`. The wire examples in
`docs/api/` are the contract (ADR 0029); the outputs' formats are `docs/specs/transcript-formats.md`
and the render suites.

## Proposal

1. **Routes**: the same paths, methods, status codes, headers and JSON bodies as the Kotlin
   router, including the error envelope and the `404` shapes; the upload and audio routes built
   earlier (item 102) join the same router.
2. **Wire examples**: a test per example comparing the exact bytes, so key order, number
   formatting and null handling are pinned, not assumed.
3. **Security**: the trusted-origin check, the credential and launch-token routes, the browser
   refusal, loopback-only behavior, and the static files' cache headers.
4. **Outputs**: `txt`, `srt` and `vtt` rendering with speaker labels and the timestamp rounding
   the suites pin; the download filename rules.
5. **Static UI**: serving the built frontend and the SPA fallback exactly as today, so item 108's
   switch serves the same UI.

## Decided (consultation, 2026-11-10)

- **The web framework**: `axum` + `tower-http` on the `tokio` runtime the rest of the service
  uses. Item 102 already brings the router skeleton; this item completes it.

## Open questions
- **Byte-exact JSON**: whether the port mirrors key order through struct field order or sorts
  keys; the examples decide, but confirm the numbers' formatting (`REAL` progress, timestamps).
- **Static file semantics**: the Kotlin server's `Range`, `ETag`/`Last-Modified` and index
  behavior — which of them the suites pin and which should be preserved by hand.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list.

- [ ] `http/AllowedExtensionsContractSpec.kt` — 1 scenario
- [ ] `http/BackupDatabaseFileSpec.kt` — 1 scenario
- [ ] `http/BrowserRefusalSpec.kt` — 1 scenario
- [ ] `http/CredentialSpec.kt` — 1 scenario
- [ ] `http/DatabaseDependencySpec.kt` — 2 scenarios
- [ ] `http/FrontendsSpec.kt` — 2 scenarios
- [ ] `http/HealthSpec.kt` — 1 scenario
- [ ] `http/InternalErrorSpec.kt` — 1 scenario
- [ ] `http/LanguagesContractSpec.kt` — 1 scenario
- [ ] `http/LaunchTokenSpec.kt` — 1 scenario
- [ ] `http/QueuePauseResumeSpec.kt` — 1 scenario
- [ ] `http/QueueReorderMismatchSpec.kt` — 2 scenarios
- [ ] `http/QueueReorderSpec.kt` — 1 scenario
- [ ] `http/SegmentEditRoutesSpec.kt` — 7 scenarios
- [ ] `http/SegmentRevertSpec.kt` — 1 scenario
- [ ] `http/SettingsDefaultFrontendSpec.kt` — 1 scenario
- [ ] `http/SettingsFallbackSpec.kt` — 2 scenarios
- [ ] `http/SettingsResetSpec.kt` — 1 scenario
- [ ] `http/SettingsRoutesSpec.kt` — 3 scenarios
- [ ] `http/SettingsTerminalCommandSpec.kt` — 1 scenario
- [ ] `http/SettingsUpdateSpec.kt` — 2 scenarios
- [ ] `http/SpaFallbackSpec.kt` — 2 scenarios
- [ ] `http/SpeakerRoutesSpec.kt` — 25 scenarios
- [ ] `http/StaleDefaultFrontendSpec.kt` — 1 scenario
- [ ] `http/StaticDisabledSpec.kt` — 1 scenario
- [ ] `http/StaticFilesSpec.kt` — 1 scenario
- [ ] `http/TaskCommandsIllegalSpec.kt` — 1 scenario
- [ ] `http/TaskCommandsSpec.kt` — 5 scenarios
- [ ] `http/TaskDeletePendingSpec.kt` — 1 scenario
- [ ] `http/TaskDeleteSpec.kt` — 1 scenario
- [ ] `http/TaskListSpec.kt` — 1 scenario
- [ ] `http/TaskRoutesSpec.kt` — 5 scenarios
- [ ] `http/TranscriptDownloadErrorsSpec.kt` — 1 scenario
- [ ] `http/TranscriptDownloadSpec.kt` — 2 scenarios
- [ ] `http/TrayRoutesSpec.kt` — 1 scenario
- [ ] `http/TrustedOriginSpec.kt` — 1 scenario
- [ ] `http/TurnsSpec.kt` — 4 scenarios
- [ ] `http/UnknownApiPathSpec.kt` — 2 scenarios
- [ ] `http/UnknownTaskSpec.kt` — 1 scenario
- [ ] `http/UpgradeCrashScratchIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `outputs/DownloadFileNameSpec.kt` — 1 scenario
- [ ] `outputs/NoSpeechSpec.kt` — 2 scenarios
- [ ] `outputs/RenderSrtSpec.kt` — 1 scenario
- [ ] `outputs/RenderTxtSpec.kt` — 1 scenario
- [ ] `outputs/RenderVttSpec.kt` — 1 scenario
- [ ] `outputs/SpeakerLabelsSpec.kt` — 1 scenario
- [ ] `outputs/SpeakersOffSpec.kt` — 2 scenarios
- [ ] `outputs/TimestampsSpec.kt` — 2 scenarios
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes; the full journey suite runs
against the Rust backend in item 108.

## Verification and CI

Local verification as in every phase (cargo fmt/clippy/test and the parity suites, run by the
user); the merge gate is the existing CI unchanged, with no Rust job until item 108.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100).
- 2026-11-10: the user approved the item and its test list, with `axum` + `tower-http`;
  `spec-approved`.
