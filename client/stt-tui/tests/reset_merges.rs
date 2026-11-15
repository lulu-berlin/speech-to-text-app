//! Roadmap item 67, parity amendment: resetting all merges. `R` on a transcript whose turns report
//! merges sends DELETE /api/tasks/{id}/speakers/merges, the answer relabels the speakers and the
//! turns are read again (a reset splits them). Without merges, `R` sends nothing.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, WIDE, turn};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
/// `turns.json` after a merge of Speaker 1 into Speaker 2.
const MERGED_TURNS: &str = r#"{"hasMerges":true,"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Speaker 2"}],"turns":[{"speakerId":1,"startMs":0,"endMs":14250,"text":"Good morning. Thanks for joining us today. Happy to be here. Let's start with your background.","segments":[]}]}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn reset_sends_only_when_merges_exist_and_the_turns_are_read_again() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        WIDE,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // A transcript without merges: `R` sends nothing.
    tui.press(b"H");
    // The tab bar always says "History": wait for the list itself, or Enter can land before it.
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));
    // The footer names the body's reset only when the service reports merges: absent here, with the
    // rest of the footer drawn (Enter plays from the cursor's segment while nothing is marked).
    tui.wait_for(WAIT, |text| {
        !text.contains("Reset merges") && text.contains("play from here")
    });
    tui.press(b"R");
    assert!(!service.command_arrived(), "no merges, no request");
    // It says so too: the key also resets a speaker's name on the Speakers panel, so silence here
    // read as "the reset is broken".
    tui.wait_for(WAIT, |text| text.contains("no merges to reset"));
    tui.press(b"b");

    // A transcript with merges: `R` resets them; the speakers come back and the turns are re-read.
    tui.press(b"\r");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", MERGED_TURNS);
    // Speaker 2's merged turn at 00:00: its time and name on one row (the fixture's turn carries
    // no segments, so no text rows follow it).
    tui.wait_for(WAIT, |text| text.contains("00:00  Speaker 2"));
    // The service's merges put the reset back in the footer.
    tui.wait_for(WAIT, |text| text.contains("Reset merges"));
    tui.press(b"R");
    let reset = service.next_command(WAIT);
    assert!(
        reset
            .request()
            .line
            .starts_with("DELETE /api/tasks/3/speakers/merges "),
        "{:?}",
        reset.request().line
    );
    reset.answer(
        "200 OK",
        r#"{"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Speaker 2"},{"speakerId":2,"speaker":"Speaker 3"}]}"#,
    );
    let refetch = service.next_get_from(&tui, WAIT);
    assert!(
        refetch
            .request()
            .line
            .starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        refetch.request().line
    );
    refetch.answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| {
        turn(text, "00:00", "Speaker 1", "Good morning.")
    });
}
