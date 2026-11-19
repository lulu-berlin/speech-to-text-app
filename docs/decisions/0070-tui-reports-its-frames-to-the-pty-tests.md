# 0070 — The TUI reports its drawn frames to its pty tests

Status: accepted
Date: 2026-11-18

## Context

The TUI's pty tests (roadmap item 67) read the real binary's output into a `vt100` screen and wait
for a screen a predicate holds of. A half-painted screen must never be accepted. On Linux and macOS
the TUI's synchronized-update markers bracket every frame, so the harness skips the screen while a
frame is open. A Windows ConPTY passes the markers on at once and repaints after them, with nothing
marking the end of its paint (run 43860329866's raw output). The harness therefore accepted a
screen once two reads drew the same one - in practice, once nothing changed for 2 ms - so a ConPTY
that pauses longer mid-repaint hands a test half a screen (the second review, S14). Nothing in the
terminal stream can tell the harness a ConPTY repaint is finished, and a longer quiet time was
ruled out in the first review (T4): it makes a slow machine fail a test.

## Decision

- **The TUI writes the text of each frame it draws to the file `STT_TUI_FRAME_LOG` names**, after
  the frame is drawn: one line per screen row, from ratatui's buffer. Without the variable it
  writes nothing.
- **The report carries each cell's style**: after each row's text, a line with every cell's
  foreground, background and bold, italic, underline and reverse attributes, as the TUI tells the
  terminal to draw them. A screen is accepted only when its cells' styles equal the reported
  frame's too, so a repaint that changes only colours - a theme switched, a selection moved - is
  not taken before it is painted (the second review, S8; 2026-11-18).
- **The pty harness sets the variable for every TUI it starts** - except the one test that proves
  the production path, which starts the TUI without it (`Tui::start_unreported`) - **and accepts a
  screen only when it equals the last frame the TUI logged**, with the stenographer's tile and the
  elapsed clock left out as before. A partial repaint differs from the logged frame, on every OS,
  and no quiet time decides anything.
- **The log is a side file, not an escape sequence in the terminal stream**: a ConPTY re-encodes
  the screen and need not pass a sequence it does not know, which is the platform this is for.
- **Debug builds only** (`#[cfg(debug_assertions)]`): `cargo test` and CI build the binary the pty
  tests run in the dev profile, and the packaged release build carries no frame log. A test-only
  `#[cfg(test)]` cannot do it, since integration tests build the binary as a normal binary. Under
  `cargo test --release` the harness finds no log and fails, saying so.

## Consequences

- The shipped binary is unchanged; a debug binary run with the variable set writes one file.
- The harness's acceptance rule is the same on every OS and does not depend on timing.
- The frame's text from ratatui's buffer and the `vt100` screen's text are compared in one
  normalised form: wide characters and their right halves are read the same way in both.
- **A cell ratatui's diff skips because the terminal blanked it under a wide character
  ([ratatui #2933](https://github.com/ratatui/ratatui/issues/2933)) would make the report disagree
  with the screen; `wide_overlap` marks it to be written.** That change ships in release builds too
  - it is the correct frame, not a test aid - and without it the zh-Hans footer's muted cell is the
  first difference the comparison finds.
