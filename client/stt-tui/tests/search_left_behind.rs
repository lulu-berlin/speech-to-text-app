//! Roadmap item 67, the review (R2): leaving History while its search is open ends the search. On
//! the screen the user goes to - by a click on a tab, or by a double-click on a History row - the
//! letters, `q` and Escape are that screen's keys again, not characters of the search.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use portable_pty::ExitStatus;
use std::time::Duration;

use terminal::{EventService, Tui, history_controls, history_row, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// A TUI on History with its search open and `inter` typed, and what keeps it running: the
/// stand-in service, its event stream and the state directory.
struct Searching {
    service: EventService,
    tui: Tui,
    _stream: terminal::EventStream,
    _state: tempfile::TempDir,
    /// The screen with the search typed: only `interview.mp3` is left.
    screen: String,
}

/// Opens History, its search and types `inter`; see [`Searching`].
fn searching() -> Searching {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        history_row(text, "interview.mp3").is_some() && history_row(text, "call.ogg").is_some()
    });
    tui.press(b"/");
    tui.press(b"inter");
    let screen = tui.wait_for(WAIT, |text| {
        history_controls(text).contains("inter") && history_row(text, "call.ogg").is_none()
    });
    Searching {
        service,
        tui,
        _stream: stream,
        _state: state,
        screen,
    }
}

/// Whether `status` is a clean exit, as `q` ends the TUI.
fn quit_cleanly(status: Option<ExitStatus>) -> bool {
    status.is_some_and(|status| status.success())
}

#[test]
fn a_click_on_the_queue_tab_ends_the_search_so_q_quits_there() {
    // Every part bound: what `..` leaves out is dropped at once, the service and its stream with it.
    let Searching {
        service: _service,
        mut tui,
        _stream,
        _state,
        screen,
    } = searching();

    let (column, row) = position_of(&screen, "Queue");
    tui.click(column, row);
    tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));

    tui.press(b"q");
    assert!(
        quit_cleanly(tui.exit_within(WAIT)),
        "`q` on the queue quits, not types into History's search; screen:\n{}",
        tui.text()
    );
}

#[test]
fn a_double_click_on_a_row_ends_the_search_so_escape_goes_back_from_the_transcript() {
    let Searching {
        service,
        mut tui,
        _stream,
        _state,
        screen,
    } = searching();

    // Back to back: the second click well inside the double-click's 500 ms on any machine.
    let (_, clicked) = position_of(&screen, "interview.mp3");
    tui.click(3, clicked);
    tui.click(3, clicked);
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));

    // Escape leaves the transcript for History, as it does without a search.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        history_row(text, "interview.mp3").is_some() && !text.contains("Good morning.")
    });
}
