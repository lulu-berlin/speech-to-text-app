//! Roadmap item 67: with the service running, the TUI attaches with the per-install secret from
//! `config.json` and shows the queue from the service's event stream - every queued task's file
//! name, status and progress, in queue order - as the browser does.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::io::Write;
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use terminal::Tui;

/// The contract's own `snapshot` event (ADR 0029): tasks 5 (`lecture.m4a`, RUNNING at 0.42) and 7
/// (`memo.wav`, QUEUED at 0.0) are the queue, in that order.
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

#[test]
fn with_the_service_running_the_tui_attaches_with_the_secret_and_shows_the_live_queue() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let events_authorization = Arc::new(Mutex::new(None));
    let seen = Arc::clone(&events_authorization);
    thread::spawn(move || {
        for stream in listener.incoming() {
            let mut stream = stream.unwrap();
            let request = support::read(&mut stream);
            if request.line.starts_with("GET /api/events ") {
                *seen.lock().unwrap() = request.header("Authorization").map(str::to_owned);
                // The event stream stays open, as the service's does; the snapshot is its first
                // event. The pretty-printed example goes out as one `data:` line per JSON line,
                // which SSE joins back together.
                let data: String = SNAPSHOT
                    .lines()
                    .map(|line| format!("data: {line}\n"))
                    .collect();
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\nevent: snapshot\n{data}\n"
                )
                .unwrap();
                stream.flush().unwrap();
                thread::spawn(move || {
                    let _open = stream;
                    thread::park();
                });
            } else {
                let (status, body) = support::healthy();
                support::respond(&mut stream, status, &body);
            }
        }
    });
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), port);

    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 24);
    let screen = tui.wait_for(Duration::from_secs(20), |text| {
        text.contains("lecture.m4a") && text.contains("memo.wav")
    });

    let lines: Vec<&str> = screen.lines().collect();
    let row = |name: &str| {
        lines
            .iter()
            .position(|line| line.contains(name))
            .unwrap_or_else(|| panic!("no row for {name}:\n{screen}"))
    };
    let (lecture, memo) = (row("lecture.m4a"), row("memo.wav"));
    assert!(lecture < memo, "queue order:\n{screen}");
    assert!(lines[lecture].contains("RUNNING"), "{screen}");
    assert!(lines[lecture].contains("42%"), "{screen}");
    assert!(lines[memo].contains("QUEUED"), "{screen}");
    assert!(lines[memo].contains("0%"), "{screen}");
    assert_eq!(
        events_authorization.lock().unwrap().as_deref(),
        Some("Bearer the-install-secret"),
        "the event stream is asked for with the secret from config.json"
    );
}
