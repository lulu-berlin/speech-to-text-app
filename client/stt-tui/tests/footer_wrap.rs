//! Roadmap item 67, second amendment (the user's report, 2026-11-07: "instructions should never be
//! clipped"): no key hint is ever cut off. As the designs draw it (the design pass, 2026-11-15), a
//! page's footer is one row: its hints whole while they fit, then `? more`, and the `?` reference
//! lists every one left out - on every page and width.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, screen_text, table_cells};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

/// The queue's hints with `snapshot.json`'s queue and its running task selected: the upload area
/// has no focus, so its own hint does not show; the queue's own pause is the header's.
const QUEUE: &[&str] = &[
    "tui.hint.move",
    "tui.hint.reorder",
    "tui.hint.pause",
    "tui.hint.cancel",
    "tui.hint.upload",
    "tui.hint.pages",
    "tui.hint.theme",
    "tui.hint.quit",
    "tui.hint.help",
];
/// History with `interview.mp3` selected: it completed, so there is nothing to retry.
const HISTORY: &[&str] = &[
    "tui.hint.move",
    "tui.hint.open",
    "tui.hint.search",
    "tui.hint.filter",
    "tui.hint.delete",
    "tui.hint.pages",
    "tui.hint.back",
    "tui.hint.theme",
    "tui.hint.quit",
    "tui.hint.help",
];
/// `turns.json`'s transcript with the cursor on its first segment: no merges, nothing edited and
/// nothing marked, so no `R`eset merges, `revert`, `assign` or `new speaker`, and Enter plays.
const TRANSCRIPT: &[&str] = &[
    "tui.hint.transcript",
    "tui.hint.segmentMove",
    "tui.hint.page",
    "tui.hint.start",
    "tui.hint.end",
    "tui.hint.play",
    "tui.hint.edit",
    "tui.hint.rename",
    "tui.hint.merge",
    "tui.hint.mark",
    "tui.hint.markTurn",
    "tui.hint.playFrom",
    "tui.hint.download",
    "tui.hint.delete",
    "tui.hint.back",
    "tui.hint.theme",
    "tui.hint.help",
];
const SETTINGS: &[&str] = &[
    "tui.hint.move",
    "tui.hint.choose",
    "tui.hint.pages",
    "tui.hint.back",
    "tui.hint.theme",
    "tui.hint.quit",
    "tui.hint.help",
];

/// The catalog's hints `keys`, as a colour terminal shows them (a marked key drawn highlighted,
/// without its brackets).
fn hints(keys: &[&str]) -> Vec<String> {
    keys.iter()
        .map(|key| stt_client::text(key, &[]).replace(['[', ']'], ""))
        .collect()
}

/// A screen row's three-space-separated parts, trimmed.
fn parts(line: &str) -> Vec<String> {
    line.split("   ")
        .map(|part| part.trim().to_string())
        .filter(|part| !part.is_empty())
        .collect()
}

/// Waits until the footer is the screen's one last row - the row above it names none of the hints
/// `keys` - holding each hint whole or ending with `? more`; returns the hints it left out.
fn wait_footer(tui: &Tui, keys: &[&str]) -> Vec<String> {
    let hints = hints(keys);
    let mut left = Vec::new();
    tui.wait_for_screen(WAIT, |screen| {
        let text = screen_text(screen);
        let lines: Vec<&str> = text.lines().collect();
        let [.., above, footer] = lines.as_slice() else {
            return false;
        };
        let (above, footer) = (parts(above), parts(footer));
        if hints.iter().any(|hint| above.contains(hint)) {
            return false;
        }
        let missing: Vec<String> = hints
            .iter()
            .filter(|hint| !footer.contains(hint))
            .cloned()
            .collect();
        if !missing.is_empty() && footer.last().map(String::as_str) != Some("? more") {
            return false;
        }
        left = missing;
        true
    });
    left
}

/// Whether a screen row holds the reference's table row for `hint` (as the footer shows it): its
/// key cell and description, `?` and `help` for `? help`, `p` and `pause` for `pause`.
fn table_lists(screen: &str, hint: &str) -> bool {
    screen.lines().any(|line| {
        line.split('│')
            .any(|cell| match table_cells(cell).as_slice() {
                [words] => *words == hint,
                [key, words] => {
                    *words == hint
                        || format!("{key} {words}") == hint
                        || format!("{key}{words}") == hint
                }
                _ => false,
            })
    })
}

/// Opens the `?` reference titled `title`, scrolls its table down until it has shown a row for each
/// hint of `left`, and closes it.
fn reference_lists(tui: &mut Tui, title: &str, left: &[String]) {
    // The reference drawn down to its closing line: a screen still being repainted (a Windows
    // ConPTY repaints on its own schedule) can show its title before its rows.
    let closes = stt_client::text("tui.help.close", &[]).replace(['[', ']'], "");
    let whole = |text: &str| text.contains(title) && text.contains(&closes);
    tui.press(b"?");
    let mut screen = tui.wait_for(WAIT, whole);
    let mut seen: Vec<&String> = Vec::new();
    loop {
        seen.extend(left.iter().filter(|hint| table_lists(&screen, hint)));
        if left.iter().all(|hint| seen.contains(&hint)) {
            break;
        }
        assert!(
            screen.contains("↓ more"),
            "the reference lists {:?}:\n{screen}",
            left.iter()
                .filter(|hint| !seen.contains(hint))
                .collect::<Vec<_>>()
        );
        tui.press(b"\x1b[B");
        let before = screen.clone();
        screen = tui.wait_for(WAIT, |text| text != before && whole(text));
    }
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains(title));
}

#[test]
fn every_page_footer_is_one_row_and_the_rest_are_in_the_reference() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 80, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    let left = wait_footer(&tui, QUEUE);
    reference_lists(&mut tui, "Queue keys", &left);

    tui.press(b"2");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    let left = wait_footer(&tui, HISTORY);
    reference_lists(&mut tui, "History keys", &left);

    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    let left = wait_footer(&tui, TRANSCRIPT);
    reference_lists(&mut tui, "Transcript keys", &left);
    // Narrower still, the transcript's footer stays one row and leaves more to the reference.
    tui.resize(50, 24);
    let left = wait_footer(&tui, TRANSCRIPT);
    reference_lists(&mut tui, "Transcript keys", &left);
    tui.resize(80, 24);
    // The redraw at 80 columns: only there is the footer wider than 50.
    tui.wait_for(WAIT, |text| {
        text.lines()
            .last()
            .is_some_and(|line| line.trim_end().chars().count() > 50)
    });

    tui.press(b"3");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Restore default settings"));
    let left = wait_footer(&tui, SETTINGS);
    reference_lists(&mut tui, "Settings keys", &left);
}
