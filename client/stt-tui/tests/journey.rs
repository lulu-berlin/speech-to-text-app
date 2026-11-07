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

use terminal::Tui;

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
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn required(name: &str) -> String {
    std::env::var(name)
        .unwrap_or_else(|_| panic!("{name} is unset - run the journey with `gradle tuiJourney`"))
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

/// Whether `text` has a queue row for `name` (the rows carry a percentage; the notice does not).
fn queue_row<'a>(text: &'a str, name: &str) -> Option<&'a str> {
    text.lines()
        .find(|line| line.contains(name) && line.contains('%'))
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

    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        200,
        30,
    );
    tui.wait_for(WAIT, |text| text.contains("Queue"));

    // Upload by path: the task joins the queue.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
    tui.press(jfk.as_bytes());
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| queue_row(text, &name).is_some());

    // It runs and completes: a completed task leaves the queue (a failed one stays, marked FAILED).
    let screen = tui.wait_for(TRANSCRIBE, |text| {
        queue_row(text, &name).is_none_or(|row| row.contains("FAILED"))
    });
    assert!(
        queue_row(&screen, &name).is_none(),
        "the task failed:\n{screen}\nthe service's output:\n{}",
        service.output()
    );

    // The history lists it; Enter opens its transcript, which reads what JFK said.
    tui.press(b"h");
    tui.wait_for(WAIT, |text| {
        text.contains("History") && text.contains(&name)
    });
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

    tui.press(b"q");
    assert!(tui.exit_within(WAIT).is_some(), "the TUI quits");
    let _ = runtime.block_on(stt_client::quit(&support::dirs_in(state.path())));
}
