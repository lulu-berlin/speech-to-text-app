//! Roadmap item 67, the second review (S14, ADR 0070): the pty harness accepts a screen only when it
//! equals the last frame the program reports having drawn. A repaint that pauses half done - as a
//! Windows ConPTY's may, after the frame's markers - is not accepted however long the pause is.
//! A shell script stands in for the TUI, so the pause is the test's own and the half screen certain.

#![cfg(unix)]

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::panic::{self, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use terminal::Tui;

const COLS: u16 = 40;
const ROWS: u16 = 10;

/// The style token of a cell drawn in the terminal's own colours, without attributes - as the
/// frame report writes it (ADR 0070): `foreground/background/attributes`.
const PLAIN: &str = "default/default/";

/// A shell script in `dir` standing in for the TUI: it reports a frame of `reported` on the sixth
/// row, its characters in `style` - every row as wide as the screen, below the stenographer's
/// tile, which the comparison leaves out - then runs `paint`.
fn fake_program(dir: &Path, reported: &str, style: &str, paint: &str) -> PathBuf {
    let width = usize::from(COLS);
    let frame: Vec<String> = (0..ROWS)
        .flat_map(|row| {
            let text = if row == 5 { reported } else { "" };
            let styles: Vec<&str> = (0..width)
                .map(|cell| {
                    if cell < text.chars().count() {
                        style
                    } else {
                        PLAIN
                    }
                })
                .collect();
            [format!("{text:<width$}"), styles.join(" ")]
        })
        .collect();
    let drawn = dir.join("frame");
    fs::write(&drawn, frame.join("\n")).unwrap();
    let program = dir.join("paint.sh");
    fs::write(
        &program,
        format!(
            "#!/bin/sh\n\
             [ -n \"$STT_TUI_FRAME_LOG\" ] && cp '{}' \"$STT_TUI_FRAME_LOG\"\n\
             {paint}",
            drawn.display()
        ),
    )
    .unwrap();
    fs::set_permissions(&program, fs::Permissions::from_mode(0o755)).unwrap();
    program
}

#[test]
fn a_half_painted_screen_is_not_accepted_while_its_output_pauses() {
    let dir = tempfile::tempdir().unwrap();
    // `ABCDEF` reported, then painted in two parts a second apart: `ABC`, then `DEF`.
    let program = fake_program(
        dir.path(),
        "ABCDEF",
        PLAIN,
        "printf '\\033[2J\\033[6;1HABC'\n\
         sleep 1\n\
         printf 'DEF'\n\
         sleep 60\n",
    );

    let tui = Tui::start(&program, dir.path(), COLS, ROWS);
    let screen = tui.wait_for(Duration::from_secs(20), |text| text.contains("ABC"));
    assert!(
        screen.contains("ABCDEF"),
        "the wait took the half-painted screen:\n{screen}"
    );
}

/// The second review (S7): a screen the predicate holds of while it keeps changing - never the
/// frame reported - makes the wait give up at its timeout rather than read on for ever.
#[test]
fn a_wait_on_a_screen_that_never_settles_gives_up_at_its_timeout() {
    let dir = tempfile::tempdir().unwrap();
    // `DONE` reported, never painted: a counter repainted on that row without end.
    let program = fake_program(
        dir.path(),
        "DONE",
        PLAIN,
        "printf '\\033[2J'\n\
         i=0\n\
         while :; do printf '\\033[6;1H%s' \"$i\"; i=$((i+1)); sleep 0.001; done\n",
    );

    // The wait, on a predicate that always holds, gets half a second; the test allows it ten
    // before calling it a wait that never ends.
    let tui = Tui::start(&program, dir.path(), COLS, ROWS);
    let (sender, outcome) = mpsc::channel();
    thread::spawn(move || {
        let waited = panic::catch_unwind(AssertUnwindSafe(|| {
            tui.wait_for_screen(Duration::from_millis(500), |_| true);
        }));
        let _ = sender.send(waited.is_err());
    });
    let gave_up = outcome
        .recv_timeout(Duration::from_secs(10))
        .expect("the wait gave up within ten seconds");
    assert!(gave_up, "the wait accepted a screen that never settled");
}

/// The second review (S8): a repaint that changes only colours is not the frame reported, so a
/// wait reads the colours the program reported, not the ones painted before them.
#[test]
fn a_screen_in_the_colours_before_the_frame_is_not_accepted() {
    let dir = tempfile::tempdir().unwrap();
    // `ABC` reported in red (palette colour 1); painted green first, red a second later.
    let program = fake_program(
        dir.path(),
        "ABC",
        "idx:1/default/",
        "printf '\\033[2J\\033[6;1H\\033[32mABC\\033[0m'\n\
         sleep 1\n\
         printf '\\033[6;1H\\033[31mABC\\033[0m'\n\
         sleep 60\n",
    );

    let tui = Tui::start(&program, dir.path(), COLS, ROWS);
    let scene = tui.wait_for_scene(Duration::from_secs(20), |text| text.contains("ABC"));
    assert_eq!(
        scene.fg("ABC"),
        vt100::Color::Idx(1),
        "the wait took the screen painted before the frame's colours:\n{}",
        scene.text()
    );
}
