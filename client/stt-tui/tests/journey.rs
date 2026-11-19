//! Roadmap item 67, the end-to-end journey: the real `stt-tui` in a pseudo-terminal against the real
//! per-user service - started from the backend's `installDist` launcher with `service`, as the
//! Playwright app-mode specs start it - uploads `jfk.wav`, waits for the task to complete, opens it
//! from the history and reads the transcript the tiny whisper model wrote.
//!
//! It needs the service and its tools, so plain `cargo test` skips it; `gradle tuiJourney` runs it
//! with the launcher in `STT_JOURNEY_SERVICE` and the fixtures (`TEST_JFK_WAV`,
//! `TEST_WHISPER_TINY_EN_MODEL`) in the environment, the managed tools on `PATH`.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::thread;
use std::time::{Duration, Instant};

use terminal::{Tui, token};

/// How long the service may take to answer its first health check: a cold JVM on a loaded runner.
const START: Duration = Duration::from_secs(180);
/// How long the transcription may take: the tiny model on 11 s of audio, diarization included.
const TRANSCRIBE: Duration = Duration::from_secs(600);
const WAIT: Duration = Duration::from_secs(60);

/// The running service; killed when the test ends, however it ends.
struct RunningService {
    child: Child,
    log: PathBuf,
}

impl RunningService {
    fn output(&self) -> String {
        fs::read_to_string(&self.log).unwrap_or_default()
    }
}

impl Drop for RunningService {
    fn drop(&mut self) {
        // On Windows the launcher is a .bat under cmd, which starts the JVM as its child: killing
        // cmd alone would leave the service running, so the whole tree goes. On Unix the start
        // script `exec`s Java, so the child is the JVM itself.
        if cfg!(windows) {
            let _ = Command::new("taskkill")
                .args(["/T", "/F", "/PID", &self.child.id().to_string()])
                .output();
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn required(name: &str) -> String {
    std::env::var(name)
        .unwrap_or_else(|_| panic!("{name} is unset - run the journey with `gradle tuiJourney`"))
}

/// `clip` (16-bit PCM) said twice with 1.5 s of silence between, written into `into`: two
/// utterances apart, which whisper splits into at least two segments - `jfk.wav` alone is one
/// segment on the macOS runner's build (run 43802779592), and one segment carries one speaker.
fn twice(clip: &Path, into: &Path) -> PathBuf {
    let bytes = fs::read(clip).unwrap();
    // The RIFF chunks after the 12-byte header: `fmt ` is kept, `data` holds the samples.
    let (mut format, mut samples) = (None, None);
    let mut at = 12;
    while at + 8 <= bytes.len() {
        let size = u32::from_le_bytes(bytes[at + 4..at + 8].try_into().unwrap()) as usize;
        let body = &bytes[at + 8..(at + 8 + size).min(bytes.len())];
        match &bytes[at..at + 4] {
            b"fmt " => format = Some(body),
            b"data" => samples = Some(body),
            _ => {}
        }
        at += 8 + size + size % 2;
    }
    let format = format.expect("a WAV has a fmt chunk");
    let samples = samples.expect("a WAV has a data chunk");
    // The format's bytes per second and per sample frame: 1.5 s of zero samples between.
    let rate = u32::from_le_bytes(format[8..12].try_into().unwrap()) as usize;
    let frame = u16::from_le_bytes(format[12..14].try_into().unwrap()) as usize;
    let silence = vec![0u8; rate * 3 / 2 / frame * frame];
    let data = [samples, &silence, samples].concat();
    let mut wav = Vec::new();
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&((4 + 8 + format.len() + 8 + data.len()) as u32).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&(format.len() as u32).to_le_bytes());
    wav.extend_from_slice(format);
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&(data.len() as u32).to_le_bytes());
    wav.extend_from_slice(&data);
    let path = into.join("jfk-twice.wav");
    fs::write(&path, wav).unwrap();
    path
}

/// Starts the service on a free port with its data in `data` and its bootstrap files in `state`,
/// transcribing with the tiny model.
fn start_service(launcher: &str, state: &Path, data: &Path, tiny_model: &str) -> RunningService {
    let log = state.join("service.log");
    let output = File::create(&log).unwrap();
    let mut command = if cfg!(windows) {
        // The installDist launcher is a .bat, which only cmd runs.
        let mut command = Command::new("cmd");
        command.args(["/c", launcher]);
        command
    } else {
        // A POSIX shell script: run by the `sh` on PATH rather than its `#!/bin/sh`, which a Nix
        // sandbox does not have.
        let mut command = Command::new("sh");
        command.arg(launcher);
        command
    };
    let child = command
        .arg("service")
        .env("STT_DATA_DIR", data)
        .env("STT_WHISPER_MODEL", tiny_model)
        .env("STT_PORT", support::closed_port().to_string())
        .env("STT_CONFIG_DIR", state)
        .env("STT_RUNTIME_DIR", state)
        .stdout(output.try_clone().unwrap())
        .stderr(output)
        .spawn()
        .unwrap_or_else(|error| panic!("cannot start {launcher}: {error}"));
    RunningService { child, log }
}

/// Waits until the service in `state` answers its health check with its secret, as a client finds
/// it - or fails at once, with its output, if it exits first.
fn wait_until_up(service: &mut RunningService, state: &Path, runtime: &tokio::runtime::Runtime) {
    let dirs = support::dirs_in(state);
    let deadline = Instant::now() + START;
    while runtime.block_on(stt_client::locate(&dirs)).is_err() {
        if let Some(status) = service.child.try_wait().unwrap() {
            panic!(
                "the service exited ({status}) before answering; its output:\n{}",
                service.output()
            );
        }
        if Instant::now() >= deadline {
            panic!(
                "the service did not answer within {START:?}; its output:\n{}",
                service.output()
            );
        }
        thread::sleep(Duration::from_millis(500));
    }
}

#[test]
#[ignore = "drives the real service: run it with `gradle tuiJourney`"]
fn an_upload_completes_and_its_transcript_reads_back() {
    let launcher = required("STT_JOURNEY_SERVICE");
    let tiny_model = required("TEST_WHISPER_TINY_EN_MODEL");
    let jfk = required("TEST_JFK_WAV");
    let name = Path::new(&jfk)
        .file_name()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    let state = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mut service = start_service(&launcher, state.path(), data.path(), &tiny_model);
    wait_until_up(&mut service, state.path(), &runtime);

    // The audio plays on the silent real-time output: the journey has no sound card to rely on,
    // and its playhead still walks with the clock as a device's does.
    let mut tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        200,
        30,
        &[("STT_AUDIO_SINK", "null")],
    );
    tui.wait_for(WAIT, |text| text.contains("Queue"));

    // Upload by path: the task joins the queue. The write gate refuses a write while the event
    // stream is not live, and a cold service answers reads before its stream connects, so the
    // header's live marker is awaited first.
    tui.wait_for(WAIT, |text| text.contains("● Live"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(jfk.as_bytes());
    // The typed path's frame, its file name in view, before Enter sends it.
    tui.wait_for(WAIT, |text| text.contains(&name));
    tui.press(b"\r");
    // The notice says it was queued; the queue row's percentage is gone as soon as it starts
    // running, so the row cannot stand in for this.
    tui.wait_for(WAIT, |text| text.contains(&format!("queued {name}")));

    // It runs and finishes, and History lists it - COMPLETED, or the run failed. The wait reads a
    // row that holds the name with a finished status: a running queue row has none, and the
    // upload's notice carries the name on every page without one, so neither satisfies it early.
    // (An empty History shows its mascot, not its filter line, so the page itself is not awaited.)
    tui.press(b"H");
    let history = tui.wait_for(TRANSCRIBE, |text| {
        text.lines().any(|line| {
            line.contains(&name)
                && (line.contains("COMPLETED")
                    || line.contains("FAILED")
                    || line.contains("CANCELED"))
        })
    });
    assert!(
        history
            .lines()
            .any(|line| line.contains(&name) && line.contains("COMPLETED")),
        "the task did not complete:\n{history}\nthe service's output:\n{}",
        service.output()
    );
    tui.press(b"\r");
    let transcript = tui.wait_for(WAIT, |text| text.contains("Speakers"));
    let transcript = if transcript.contains("what your country can do") {
        transcript
    } else {
        tui.wait_for(WAIT, |text| text.contains("what your country can do"))
    };
    assert!(
        transcript.contains("00:00"),
        "the turn starts at the beginning:\n{transcript}"
    );

    // Space plays the task's real clip - the service's FLAC, decoded by the player - from the
    // start: the bar reads the playhead over the task's length, and nothing failed.
    tui.press(b" ");
    let playing = tui.wait_for(WAIT, |text| text.contains('⏸') && text.contains("0:00 / "));
    assert!(
        !playing.contains("cannot play the audio"),
        "the clip plays:\n{playing}"
    );
    let length = playing
        .lines()
        .find_map(|line| line.split("0:00 / ").nth(1))
        .and_then(|rest| rest.split_whitespace().next())
        .expect("the bar names the task's length")
        .to_string();

    // The playhead advances through the clip, and at its end the bar stands at its length, stopped.
    tui.wait_for(WAIT, |text| text.contains(&format!("0:02 / {length}")));
    tui.wait_for(WAIT, |text| {
        text.contains(&format!("{length} / {length}")) && text.contains('▶')
    });

    // Ctrl-C quits from every page; `q` walks back one page first, which the transcript would take.
    tui.press(b"\x03");
    assert!(tui.exit_within(WAIT).is_some(), "the TUI quits");
    let _ = runtime.block_on(stt_client::quit(&support::dirs_in(state.path())));
}

#[test]
#[ignore = "drives the real service: run it with `gradle tuiJourney`"]
fn the_tui_draws_the_mode_the_service_stored() {
    let launcher = required("STT_JOURNEY_SERVICE");
    let tiny_model = required("TEST_WHISPER_TINY_EN_MODEL");
    let state = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mut service = start_service(&launcher, state.path(), data.path(), &tiny_model);
    wait_until_up(&mut service, state.path(), &runtime);

    // The browser's write, through the client: the whole settings, one mode changed.
    let store = |mode: &str| {
        let dirs = support::dirs_in(state.path());
        runtime.block_on(async move {
            let service = stt_client::locate(&dirs).await.unwrap();
            let mut settings = stt_client::settings(&service).await.unwrap();
            settings.theme_mode = mode.to_string();
            stt_client::save_settings(&service, &settings)
                .await
                .unwrap();
        });
    };
    store("light");

    // The TUI started against that service draws the mode its settings hold.
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
    );
    tui.wait_for(WAIT, |text| text.contains("● Live"));
    // The queue's upload line draws its `—` separator in the border role.
    tui.wait_for_cell(WAIT, "—", |cell| {
        cell.fgcolor() == token(&["colors", "border"], "light")
    });

    // Whoever stores dark next - the browser, the settings page - the service says so and the
    // open TUI repaints without a key.
    store("dark");
    tui.wait_for_cell(WAIT, "—", |cell| {
        cell.fgcolor() == token(&["colors", "border"], "dark")
    });

    tui.press(b"\x03");
    assert!(tui.exit_within(WAIT).is_some(), "the TUI quits");
    let _ = runtime.block_on(stt_client::quit(&support::dirs_in(state.path())));
}

#[test]
#[ignore = "drives the real service: run it with `gradle tuiJourney`"]
fn r_resets_the_merges_the_service_reports() {
    let launcher = required("STT_JOURNEY_SERVICE");
    let tiny_model = required("TEST_WHISPER_TINY_EN_MODEL");
    let jfk = required("TEST_JFK_WAV");
    let state = tempfile::tempdir().unwrap();
    let data = tempfile::tempdir().unwrap();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mut service = start_service(&launcher, state.path(), data.path(), &tiny_model);
    wait_until_up(&mut service, state.path(), &runtime);
    let dirs = support::dirs_in(state.path());

    // A task diarized into two speakers, then merged into one: the service reports the merge. The
    // clip is `jfk.wav` said twice, apart: the clip alone is one segment on some whisper builds,
    // and one segment carries one speaker.
    let clips = tempfile::tempdir().unwrap();
    let clip = twice(Path::new(&jfk), clips.path());
    let upload = runtime.block_on(async {
        let service = stt_client::locate(&dirs).await.unwrap();
        stt_client::upload(
            &service,
            &clip,
            &stt_client::UploadOptions {
                language: "en".to_string(),
                speakers: stt_client::Speakers::Count(2),
                max_speakers: 4,
            },
        )
        .await
        .unwrap()
    });
    let id = upload.id;
    let deadline = Instant::now() + TRANSCRIBE;
    let turns = loop {
        let read = runtime.block_on(async {
            let service = stt_client::locate(&dirs).await.unwrap();
            stt_client::turns(&service, id).await
        });
        if let Ok(turns) = read {
            break turns;
        }
        assert!(
            Instant::now() < deadline,
            "task {id} did not finish in time"
        );
        thread::sleep(Duration::from_millis(250));
    };
    let speakers: Vec<i64> = turns
        .speakers
        .iter()
        .map(|speaker| speaker.speaker_id)
        .collect();
    assert!(
        speakers.len() >= 2,
        "the clip said twice is diarized into two speakers: {turns:?}"
    );
    let merged = runtime.block_on(async {
        let service = stt_client::locate(&dirs).await.unwrap();
        stt_client::merge_speakers(&service, id, &speakers[..2], speakers[1])
            .await
            .unwrap();
        stt_client::turns(&service, id).await.unwrap()
    });
    assert!(merged.has_merges, "the merge is stored");

    // The TUI opens that transcript and `R` on its body resets the task's merges.
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
    );
    tui.wait_for(WAIT, |text| text.contains("● Live"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("jfk"));
    tui.press(b"\r");
    tui.wait_for(TRANSCRIBE, |text| text.contains("Speakers"));
    tui.press(b"R");

    let deadline = Instant::now() + WAIT;
    loop {
        let read = runtime.block_on(async {
            let service = stt_client::locate(&dirs).await.unwrap();
            stt_client::turns(&service, id).await.unwrap()
        });
        if !read.has_merges && read.speakers.len() == speakers.len() {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "R did not reset the merges: {:?}",
            read.speakers
        );
        thread::sleep(Duration::from_millis(100));
    }

    tui.press(b"\x03");
    assert!(tui.exit_within(WAIT).is_some(), "the TUI quits");
    let _ = runtime.block_on(stt_client::quit(&support::dirs_in(state.path())));
}
