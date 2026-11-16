//! Roadmap item 67, the second amendment's theme: in a colour terminal the TUI draws from the shared
//! colour tokens (`ui-theme/colors.json`) in the stored theme mode - the page in the background
//! role, panels in border with their titles in accent, the statuses in their group's colours, the
//! selected row in the accent tint and the speakers in their tints. `NO_COLOR` is the existing
//! test's: it emits no SGR at all.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// The settings example in the given theme mode, so the TUI draws in it.
fn settings_in(mode: &str) -> String {
    let mut settings: Value =
        serde_json::from_str(include_str!("../../../docs/api/settings.json")).unwrap();
    settings["themeMode"] = json!(mode);
    settings.to_string()
}

#[test]
fn the_tui_draws_from_the_shared_tokens_in_the_stored_mode() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("dark"));
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Dark: the page in the background role, rules in border, the open tab on its accent chip. The
    // theme arrives with the startup settings, so the test waits for a status's colour, not the
    // text.
    let pending = token(&["colors", "status", "pending", "text"], "dark");
    tui.wait_for_cell(WAIT, "QUEUED", |cell| cell.fgcolor() == pending);
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("Transcribing"));
    let rule = scene.row_of("Queue 2 tasks") + 1;
    assert_eq!(
        scene.cell(rule, 2).unwrap().fg,
        token(&["colors", "border"], "dark"),
        "the queue's rule"
    );
    assert_eq!(
        scene.fg("Queue"),
        token(&["colors", "background"], "dark"),
        "the open tab's text on its chip"
    );
    assert_eq!(
        scene.bg("Queue"),
        token(&["colors", "accent"], "dark"),
        "the open tab's chip"
    );
    assert_eq!(
        scene.bg("lecture.m4a"),
        token(&["colors", "accentTint"], "dark"),
        "the selected row"
    );

    // The statuses in their group's colour: the running phase active, QUEUED pending on its
    // group's background - and, in History, FAILED problem.
    assert_eq!(
        scene.fg("Transcribing"),
        token(&["colors", "accent"], "dark")
    );
    assert_eq!(scene.fg("QUEUED"), pending);
    assert_eq!(
        scene.bg("QUEUED"),
        token(&["colors", "status", "pending", "background"], "dark")
    );
    tui.press(b"H");
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("FAILED")
    });
    assert_eq!(
        scene.fg("FAILED"),
        token(&["colors", "status", "problem", "text"], "dark")
    );

    // The speakers wear their tints: the first two entries of the palette.
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("Speaker 2"));
    assert_eq!(
        scene.fg("Speaker 1"),
        token(&["colors", "speakerTints"], "dark")
    );
}

#[test]
fn the_stored_light_mode_draws_the_light_tokens() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("light"));
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    let border = token(&["colors", "border"], "light");
    // The queue's upload line draws its `—` separator in the border role.
    tui.wait_for_cell(WAIT, "—", |cell| cell.fgcolor() == border);
    // A failed status is History's: call.ogg in the snapshot the stream sends.
    tui.press(b"H");
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("FAILED"));
    let rule = scene.row_of("/ Search") + 1;
    assert_eq!(
        scene.cell(rule, 2).unwrap().fg,
        token(&["colors", "border"], "light"),
        "History's rule in the light mode"
    );
    assert_eq!(
        scene.fg("FAILED"),
        token(&["colors", "status", "problem", "text"], "light"),
        "a failed status in the light mode"
    );
}

/// The open tab's text (the background token) and its chip (the accent token) in the dark mode, as
/// a terminal without true colour draws them. The xterm palette's cube levels are 0, 95, 135,
/// 175, 215, 255 (entry 16 + 36r + 6g + b) and its grey ramp 8 + 10i (entry 232 + i):
/// - background `#14161a` = (20, 22, 26): the cube's nearest is (0, 0, 0), distance² 1560; grey
///   18 (entry 233) is (2² + 4² + 8²) = 84 away, grey 28 is 104 → entry 233.
/// - accent `#7fb2e5` = (127, 178, 229): the cube's nearest levels are 135, 175, 215 → entry
///   16 + 72 + 18 + 4 = 110, distance² 64 + 9 + 196 = 269; the nearest grey, 178, is 5202 away
///   → entry 110.
///
/// The Windows console always draws 24-bit colour, so there the tokens arrive exact.
fn without_true_colour() -> (vt100::Color, vt100::Color) {
    if cfg!(target_os = "windows") {
        (
            token(&["colors", "background"], "dark"),
            token(&["colors", "accent"], "dark"),
        )
    } else {
        (vt100::Color::Idx(233), vt100::Color::Idx(110))
    }
}

#[test]
fn a_terminal_without_true_colour_gets_the_nearest_256_colour_entries() {
    let (background, accent) = without_true_colour();
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        120,
        24,
        &[("COLORTERM", "")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("dark"));
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    // The open tab is drawn on the stored mode's chip once the startup settings are in: before
    // they land the TUI draws in what `auto` resolves to on this machine, light on a light OS.
    tui.wait_for_cell(WAIT, "Queue", |cell| cell.bgcolor() == accent);
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("memo.wav"));
    assert_eq!(scene.fg("Queue"), background, "the open tab's text");
    assert_eq!(scene.bg("Queue"), accent, "the open tab's chip");
}
