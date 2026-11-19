//! Roadmap item 67, the review (R8): the queue draws its task rows only in the rows' area. When
//! the upload box, the header and five finished notices leave that area no height, no task row is
//! drawn - not over the notices' first line, above their "Finished" heading.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn no_task_row_is_drawn_outside_the_rows_area_however_short_the_terminal() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), state.path(), 80, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Five tasks finish: five notices under the queue's rows.
    for id in 101..106 {
        stream.send(
            "task-updated",
            &format!(
                r#"{{"id":{id},"filename":"done-{id}.wav","status":"COMPLETED","transcriptionProgress":1.0}}"#
            ),
        );
    }
    tui.wait_for(WAIT, |text| text.contains("done-101.wav"));
    let finished = stt_client::text("tui.queue.finished", &[]);

    // Row by row shorter: the queue's rows end at least one line above the notices' blank first
    // line - a row drawn on that line, right above "Finished", is outside the rows' area.
    for rows in (12..=24).rev() {
        tui.resize(80, rows);
        tui.settle();
        let screen = tui.text();
        let lines: Vec<&str> = screen.lines().collect();
        let Some(heading) = lines
            .iter()
            .position(|line| line.trim_start().starts_with(&finished))
        else {
            continue;
        };
        for (at, line) in lines.iter().enumerate() {
            if line.contains("lecture.m4a") || line.contains("memo.wav") {
                assert!(
                    at + 2 <= heading,
                    "at {rows} rows a task row sits on line {at}, above \"{finished}\" on {heading}:\n{screen}"
                );
            }
        }
    }
}
