//! Roadmap item 67, parity amendment: reassigning segments. `a` marks the cursor's segment; Enter
//! opens the list of where marked segments can go - New speaker, then every speaker - as the
//! browser's "Move to…" picker (the user, 2026-11-12), and Enter there moves them
//! (`POST …/speakers/reassign` with that speakerId); `n` moves them to a new speaker (`null`). The
//! answer's speakers relabel the transcript and the turns are read again.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{OpenTranscript, offers, open_transcript, turn};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
/// `turns.json` after segment 1 moved from Speaker 1 to Speaker 2.
const MOVED_TURNS: &str = r#"{"hasMerges":false,"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Speaker 2"}],"turns":[{"speakerId":0,"startMs":2400,"endMs":5200,"text":"Thanks for joining us today.","segments":[{"id":2,"startMs":2400,"endMs":5200,"text":"Thanks for joining us today.","edited":false}]},{"speakerId":1,"startMs":0,"endMs":2100,"text":"Good morning.","segments":[{"id":1,"startMs":0,"endMs":2100,"text":"Good morning.","edited":false}]},{"speakerId":1,"startMs":5800,"endMs":9100,"text":"Happy to be here.","segments":[{"id":3,"startMs":5800,"endMs":9100,"text":"Happy to be here.","edited":false}]},{"speakerId":0,"startMs":9600,"endMs":14250,"text":"Let's start with your background.","segments":[{"id":4,"startMs":9600,"endMs":14250,"text":"Let's start with your background.","edited":false}]}]}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn marked_segments_move_to_the_selected_speaker_or_a_new_one() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // `a` marks the first segment; Enter opens the list of where it can go, its keys in the footer.
    tui.press(b"a");
    tui.wait_for(WAIT, |text| text.contains("Speaker 1 (selected)"));
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("Move 1 segment to")
            && text.contains("New speaker")
            && offers(text, &["Enter move there", "Esc cancel"], &[])
    });

    // Esc closes it with nothing sent - waited for, since an Escape with the next key right behind
    // it reads as Alt and that key.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Move 1 segment to"));
    assert!(!service.command_arrived(), "a closed list moves nothing");

    // Enter opens it again; Down twice passes New speaker and Speaker 1, and Enter moves the
    // segment to Speaker 2 - without going up to the Speakers panel.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Move 1 segment to"));
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let reassign = service.next_command(WAIT);
    assert!(
        reassign
            .request()
            .line
            .starts_with("POST /api/tasks/3/speakers/reassign "),
        "{:?}",
        reassign.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&reassign.request().body).unwrap(),
        json!({ "segmentIds": [1], "speakerId": 1 })
    );
    reassign.answer(
        "200 OK",
        r#"{"speakers":[{"speakerId":1,"speaker":"Speaker 2"}]}"#,
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
    refetch.answer("200 OK", MOVED_TURNS);
    tui.wait_for(WAIT, |text| {
        turn(text, "00:00", "Speaker 2", "Good morning.")
    });

    // `n` assigns a marked segment to a new speaker: the cursor, kept on the first segment through
    // the re-read, goes twice right to Speaker 2's "Happy to be here." and `a` marks it.
    tui.press(b"l");
    tui.press(b"l");
    tui.press(b"a");
    tui.wait_for(WAIT, |text| text.contains("Speaker 2 (selected)"));
    tui.press(b"n");
    let reassign = service.next_command(WAIT);
    let body: Value = serde_json::from_str(&reassign.request().body).unwrap();
    assert_eq!(body["speakerId"], Value::Null, "{body}");
    assert_eq!(body["segmentIds"], json!([3]), "{body}");
    reassign.answer(
        "200 OK",
        r#"{"speakers":[{"speakerId":1,"speaker":"Speaker 2"},{"speakerId":2,"speaker":"Speaker 3"}]}"#,
    );
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
}

/// The review (R19): a list taller than the dialog scrolls with its cursor, so the option the user
/// moved to is always the one drawn.
#[test]
fn the_move_to_list_scrolls_with_its_cursor() {
    // Twenty speakers plus "New speaker": twenty-one options into the dialog's sixteen rows (24
    // terminal rows, two thirds, less its borders).
    let speakers: Vec<Value> = (0..20)
        .map(|index| json!({ "speakerId": index, "speaker": format!("Speaker {}", index + 1) }))
        .collect();
    let turns: Vec<Value> = (0..20)
        .map(|index| {
            json!({
                "speakerId": index,
                "startMs": index * 1000,
                "endMs": index * 1000 + 900,
                "text": format!("line {}", index + 1),
                "segments": [{
                    "id": index + 1,
                    "startMs": index * 1000,
                    "endMs": index * 1000 + 900,
                    "text": format!("line {}", index + 1),
                    "edited": false
                }]
            })
        })
        .collect();
    let turns = json!({ "hasMerges": false, "speakers": speakers, "turns": turns }).to_string();
    let OpenTranscript { mut tui, .. } = terminal::open_transcript_with(&turns);

    tui.press(b"a");
    tui.wait_for(WAIT, |text| text.contains("Speaker 1 (selected)"));
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Move 1 segment to"));
    assert!(
        tui.text().contains("New speaker"),
        "the list starts at New speaker:\n{}",
        tui.text()
    );

    // Down to the last option, well past the rows the dialog can show at once.
    for _ in 0..20 {
        tui.press(b"\x1b[B");
    }
    tui.wait_for_highlight(WAIT, "Speaker 20");
    let screen = tui.text();
    assert!(
        !screen.contains("New speaker"),
        "the top of the list scrolled away:\n{screen}"
    );
}
