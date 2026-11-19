//! Roadmap item 67, parity amendment: an open transcript follows the events the browser follows.
//! `segment-edited` and `speakers-reassigned` for the open task re-read its turns; events for another
//! task leave it alone.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{OpenTranscript, open_transcript};

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn segment_edited_and_speakers_reassigned_re_read_the_open_transcript() {
    let OpenTranscript {
        service,
        mut tui,
        mut stream,
        state: _state,
    } = open_transcript();

    // Someone edits a segment: the event names the task and the segment, and the turns are read again.
    stream.send(
        "segment-edited",
        r#"{"id":3,"segment":{"id":2,"startMs":2400,"endMs":5200,"text":"Thanks for joining us today.!","edited":true}}"#,
    );
    let read = service.next_get_from(&tui, WAIT);
    assert!(
        read.request().line.starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        read.request().line
    );
    let turns: Value = serde_json::from_str(include_str!("../../../docs/api/turns.json")).unwrap();
    let mut edited = turns.clone();
    edited["turns"][0]["segments"][1]["text"] = json!("Thanks for joining us today.!");
    edited["turns"][0]["segments"][1]["edited"] = json!(true);
    read.answer("200 OK", &edited.to_string());
    tui.wait_for(WAIT, |text| text.contains("Thanks for joining us today.!"));

    // Someone reassigns segments: the speakers come with the event and the turns are read again.
    stream.send(
        "speakers-reassigned",
        r#"{"id":3,"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Bob"}]}"#,
    );
    let read = service.next_get_from(&tui, WAIT);
    assert!(
        read.request().line.starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        read.request().line
    );
    let mut renamed = turns.clone();
    renamed["speakers"][1]["speaker"] = json!("Bob");
    read.answer("200 OK", &renamed.to_string());
    tui.wait_for(WAIT, |text| {
        text.contains("00:05  Bob")
            && text.lines().any(|line| {
                line.strip_prefix('│')
                    .is_some_and(|row| row.starts_with("       Happy to be here."))
            })
    });

    // An event for another task does not re-read this transcript: an event for this one behind it
    // re-reads it once - events are handled in order, so a read the first had started would be
    // ahead of it.
    stream.send(
        "segment-edited",
        r#"{"id":9,"segment":{"id":12,"startMs":0,"endMs":10,"text":"elsewhere","edited":true}}"#,
    );
    stream.send(
        "segment-edited",
        r#"{"id":3,"segment":{"id":1,"startMs":0,"endMs":10,"text":"Good morning.","edited":false}}"#,
    );
    service
        .next_get(WAIT)
        .answer("200 OK", &renamed.to_string());
    tui.settle();
    assert!(
        !service.get_arrived(),
        "another task's edit leaves this transcript alone"
    );
}
