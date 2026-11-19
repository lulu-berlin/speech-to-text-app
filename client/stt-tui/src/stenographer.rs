//! The stenographer placeholder (roadmap item 67): a small tile at the left of the header and a
//! larger one on the empty queue and history, typing while a task runs and resting on its first
//! frame otherwise. The real artwork is the follow-up item's; the small tile is the signed-off
//! design's (`docs/design/tui/queue-dark.svg`).

/// The header tile's frames, three rows of `SMALL_WIDTH` columns: the pen moves and the paper turns.
pub const SMALL: [[&str; 3]; 2] = [
    [" ╭─────╮ ✎ ", " │ ◕ ◕ │▞▚ ", " ╰┬───┬╯▚▞ "],
    [" ╭─────╮  ✎", " │ ◕ ◕ │▚▞ ", " ╰┬───┬╯▞▚ "],
];
pub const SMALL_WIDTH: u16 = 11;

/// The empty pages' tile, in the same two frames: six rows, drawn from the panel's left edge.
pub const LARGE: [[&str; 6]; 2] = [
    [
        "╭─────────╮  ✎  ",
        "│  ◕   ◕  │ ▞▚▞▚",
        "│    ‿    │ ▚▞▚▞",
        "│         │     ",
        "╰─┬─────┬─╯     ",
        "  ┷     ┷       ",
    ],
    [
        "╭─────────╮    ✎",
        "│  ◕   ◕  │ ▚▞▚▞",
        "│    ‿    │ ▞▚▞▚",
        "│         │     ",
        "╰─┬─────┬─╯     ",
        "  ┷     ┷       ",
    ],
];

/// How long one frame shows while a task runs.
pub const STEP: std::time::Duration = std::time::Duration::from_millis(400);
