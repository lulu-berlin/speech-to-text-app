//! Roadmap item 67 (the user, 2026-11-12: "make sure that none appear when it cannot be used"):
//! History's footer offers `open` and `delete` only with a row selected, `retry` only on a failed or
//! canceled one, and `move` only with two rows or more - of the list as its search and filter show it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, offers};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn history_offers_a_key_only_while_it_can_act() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"H");

    // interview.mp3 completed: it opens and deletes, but there is nothing to retry.
    tui.wait_for(WAIT, |screen| {
        screen.contains("interview.mp3")
            && offers(
                screen,
                &[
                    "↑↓/j/k move",
                    "Enter open",
                    "Del delete",
                    "/search",
                    "filter",
                ],
                &["retry"],
            )
    });

    // call.ogg failed: it retries.
    tui.press(b"j");
    tui.wait_for(WAIT, |screen| offers(screen, &["retry", "Enter open"], &[]));

    // A search nothing matches leaves no row: no row keys, and the search and the filter stay.
    tui.press(b"/");
    tui.press(b"zzz\r");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["/search", "filter"],
            &["↑↓/j/k move", "Enter open", "Del delete", "retry"],
        )
    });

    // One row left: nowhere to move to. Escape clears the search first - waited for, since an
    // Escape with the next key right behind it reads as Alt and that key.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |screen| screen.contains("call.ogg"));
    tui.press(b"/");
    tui.press(b"call\r");
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["Enter open", "retry"], &["↑↓/j/k move"])
    });
}
