# 86 — Mobile feasibility: can the app run on a phone with transcription and diarization fully on the device?

Status: spec-approved
Depends on: —
Blocks: — (after 1.0, decided 2026-10-31)

## Goal

Know, with measurements on a real phone, whether this app can be ported to Android and iOS as one
Rust application with transcription and diarization offline and on the device, which UI toolkit and
which shape of Rust core such a port would take, and what it would cost — enough to decide whether
to file the port as roadmap work, and how to shape it.

## Evidence

What the components already support (checked 2026-10-31):

- **Transcription**: whisper.cpp ships Android and iOS examples (`examples/whisper.android`,
  `whisper.android.java`, `whisper.objc`, `whisper.swiftui` in
  [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/master/examples)); the
  SwiftUI example documents Core ML acceleration.
- **Diarization**: sherpa-onnx runs offline speaker diarization on Android
  ([`android/SherpaOnnxSpeakerDiarization`](https://github.com/k2-fsa/sherpa-onnx/tree/master/android))
  and lists 3D-Speaker embedding models among its diarization models
  ([pre-trained models](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html)); the
  app's speaker model is a 3D-Speaker CAM++ ONNX model run through ONNX Runtime
  ([ADR 0003](../docs/decisions/0003-kotlin-diarization-campplus-onnx.md)), and ONNX Runtime has
  Android and iOS builds.
- **A Rust rewrite of the backend is already described**: `docs/backend-reimplementation.md` is the
  guide for a Rust backend that satisfies the HTTP/SSE contract, the database model and the
  processing behavior; the repository's Rust parts so far are clients of the Kotlin service (the
  tray, item 66; the TUI, item 67; the Slint window, item 70).

What in this app does not carry over as it is:

- **The backend is a JVM server**: Kotlin, Ktor, Exposed, Flyway and ONNX Runtime Java. iOS has no
  JVM, and a phone runs one frontend, so a local service with an HTTP server and its credential
  model (item 66) has no purpose there.
- **The engine runs tools as subprocesses**: ffmpeg and whisper-cli are spawned with `setsid` and
  paused with `SIGSTOP`/`SIGCONT` ([ADR 0020](../docs/decisions/0020-process-runner-jna-signals-process-groups.md),
  [ADR 0004](../docs/decisions/0004-chunked-transcription-sigstop-pause.md)). iOS apps cannot spawn
  processes, and Android restricts executing binaries an app writes itself; on a phone both tools
  would be linked as libraries, which changes how pause and cancel work.
- **ffmpeg on mobile**: the common packaging, ffmpeg-kit, is archived upstream
  ([arthenica/ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit), `archived: true`); decoding
  might move to a Rust decoder or the platforms' own (Android MediaCodec, iOS AVFoundation), which
  decode a different set of formats than the app's ffmpeg build.
- **The default model is 574 MB** (`large-v3-turbo-q5_0`,
  [ADR 0008](../docs/decisions/0008-default-whisper-model.md)); its speed, memory use, heat and
  battery cost on a phone are unmeasured, as are the smaller models' accuracy tradeoffs.
- **Long work in the background**: a transcription runs for minutes; phones suspend or limit apps
  that are not in the foreground, so the queue's one-task-after-another model needs a platform
  mechanism (for example an Android foreground service) or a different promise to the user.
- **The UI was not built for phones**: the History list clips at 390 px wide, accepted "because the
  app is not intended for phone use" (`docs/specs/frontend-backend-end-to-end.md`, Approval log,
  2026-10-11).
- **Distribution**: iOS apps reach devices through the App Store or TestFlight, which need the
  Apple Developer Program (US$99 a year) — the membership the user declined for macOS signing
  ([ADR 0060](../docs/decisions/0060-windows-store-and-unsigned-macos.md)). Android allows direct
  APK installs as well as store releases.

## Decided (consultation, 2026-10-31)

- **Both platforms** (the user): Android and iOS.
- **Where it is measured** (the user): Android on the user's own phone, which the user runs and
  reports (device model and OS version named in every result). iOS only in the Simulator, on GitHub
  macOS runners in a workflow the user dispatches: a weaker claim — it shows the app builds and
  works, not an iPhone's speed, memory limits, heat or battery.
- **Depth** (the user): engine benchmarks plus a thin end-to-end prototype (pick a recording,
  transcribe, diarize, show the turns).
- **One Rust application, no service** (the user): a phone runs one frontend, so the port is a
  single Rust app that embeds the backend's logic — no local service, no HTTP server, no
  multi-language local web app. The findings weigh a Rust core for mobile only (desktop stays
  Kotlin, two engines to keep in step under ADR 0063) against one Rust core for every platform
  (the pending Rust port of the backend, `docs/backend-reimplementation.md`), with the tradeoff.
- **UI toolkits compared** (the user): Slint (item 70's desktop choice), Tauri 2 mobile (the React
  UI in a web view) and Dioxus.
- **No bar fixed in advance** (the user): the default model and at least one smaller one are
  measured — speed, memory, battery, heat and accuracy — and the user judges from the numbers.
- **The whole app is in view** (the user): the prototype stays thin, but the findings map every
  feature of the app to its mobile mechanism and cost.
- **iOS distribution is decided after the research** (the user): the findings state what each path
  needs.
- **After 1.0** (the user): not a gate of item 78.
- **Prototypes live on a spike branch that never merges** (the user), as item 64's Tauri spike did;
  the findings, the ADR and the measurements reach `main`.
- **Exempt from ADR 0046's per-feature e2e test** (the user): a research item adds no app feature.

## Proposal

1. **Engine in Rust**: whisper.cpp through Rust bindings and the CAM++ diarization through ONNX
   Runtime in Rust, built for Android and iOS, run on the app's own fixtures; Android measured on
   the user's phone, iOS checked in the Simulator.
2. **Decoding and storage without ffmpeg's subprocess**: which of the app's upload formats a Rust or
   platform decoder handles on each platform; where models and audio live.
3. **The thin prototype in each toolkit**: Slint, Tauri 2 mobile and Dioxus, each picking a
   recording, transcribing and diarizing it and showing the turns, with pause and cancel of the
   running transcription; size, startup, build effort and code reuse compared.
4. **Platform constraints**: long work in the background, model download and storage, distribution
   per platform.
5. **Write it up**: a findings document with the measurements and the feature-by-feature mapping, an
   ADR with the recommendation (port, port one platform, or not now; which toolkit; which core
   shape), and, if a port is recommended, a draft roadmap item for it.

## Open questions

None. (All settled 2026-10-31, see "Decided (consultation, 2026-10-31)".)

## Test list (approved 2026-10-31; behaviors and measurements)

- [ ] (engine, Android) whisper.cpp through Rust bindings transcribes the app's LibriSpeech
      fixtures on the user's phone with the default model and at least one smaller one; recorded
      per model: real-time factor, model load time, peak memory, and the word error against the
      desktop app's transcript of the same fixtures
- [ ] (engine, Android) The CAM++ diarization in Rust gives the two-speaker fixture the same turns
      as the Kotlin backend, and its time and peak memory are recorded
- [ ] (engine, Android) A long recording transcribed on battery: battery drop, device temperature
      and whether the OS throttles or stops it are recorded
- [ ] (engine, iOS) The same engine builds for iOS and transcribes and diarizes the fixtures in the
      Simulator on a GitHub macOS runner, recorded as a functional result only
- [ ] (decode) Each of the app's upload-format fixtures (`backend/src/test/resources/upload-formats/`:
      19 per-extension samples and 4 variants) is decoded on Android and in the iOS Simulator without
      spawning ffmpeg, or listed as unsupported with the reason
- [ ] (prototype) In each of Slint, Tauri 2 mobile and Dioxus, a Rust app on the user's phone picks a
      recording, transcribes and diarizes it and shows the turns with speaker names; app size,
      startup time, build effort and reused code are recorded per toolkit, and the iOS Simulator
      build is attempted for each
- [ ] (prototype) Pausing and cancelling a running transcription work in the Android prototype
      without process signals, and a paused task resumes where it stopped
- [ ] (background) What happens to a running transcription when the app leaves the foreground is
      recorded for Android (on the phone) and for iOS (Simulator, plus the platform's documented
      rules), with the mechanism a port would use
- [ ] (whole app) Every feature of the app — queue, pause, cancel, retry, reorder, rename, merge and
      split speakers, segment edits, task merges, playback, downloads and sharing, history,
      settings — is mapped to its mobile mechanism and cost in the findings
- [ ] (core) The findings compare a mobile-only Rust core with one Rust core for every platform,
      with effort, risk and what each means for the Kotlin backend and ADR 0063
- [ ] (write-up) The findings document and the ADR are on `main`, every measurement naming its
      device, OS version, model, fixture and build; the iOS distribution paths and their
      requirements are stated; a draft roadmap item exists if a port is recommended

## e2e stance

Exempt from ADR 0046's per-feature e2e test (decided 2026-10-31): a research item adds no app
feature; its prototypes stay on a spike branch.

## Acceptance criteria

- The findings document and the ADR are on `main`, with every measurement naming its device, OS
  version, model, fixture and build, and the iOS results marked as Simulator-only.
- The recommendation answers the Goal: port or not, which platforms, which toolkit, which core shape,
  and the known costs; a draft roadmap item exists if a port is recommended.
- All test-list items ticked; user approved the diff.

## Approval log

- 2026-10-31: filed as a draft by the root agent at the user's request ("is it feasible to port
  this app to mobile and keep it with offline local analysis?"), after checking that no roadmap
  item, ADR or spec covers mobile and that the components' mobile support is known only in general.
- 2026-10-31: consultation with the user: Android and iOS; Android measured on the user's phone,
  iOS in the Simulator on GitHub macOS runners only; engine benchmarks plus a thin prototype; one
  Rust application embedding the backend's logic, no service or HTTP server, with mobile-only and
  all-platform Rust cores weighed; Slint, Tauri 2 mobile and Dioxus compared; no bar fixed in
  advance; the whole app mapped; iOS distribution decided after the research; after 1.0; a spike
  branch that never merges; exempt from ADR 0046. Test list proposed.
- 2026-10-31: the user approved the test list as written; status set to spec-approved.
