# 0069 — The TUI reads its input through termina

Status: accepted
Date: 2026-11-16

## Context

Item 67 decided (2026-10-22) that the TUI is ratatui on its crossterm backend, and crossterm read
the keyboard, the mouse and pastes as well as drawing. The pull request's CI runs (2026-11-16) found
two defects in that input path:

- On Unix, crossterm 0.29's default event source (mio, edge-triggered) returns a resize and drops
  the keyboard's readiness from the same poll, so a key pressed with a resize waits for the next
  key (`crossterm-0.29.0/src/event/source/unix/mio.rs`; no fixed release exists).
- On Windows, crossterm reads the console's legacy input records. They carry no paste: a pasted or
  dragged path arrived as keystrokes, so its letters acted as commands (its `/` opened History's
  search in the `paste.rs` run). Under a pseudoconsole they carried no mouse either: nine click and
  wheel tests saw nothing (run 43850892737). crossterm's mouse command on Windows also sets the
  console's input mode back to those records. crossterm's escape-sequence parser, which handles
  paste and SGR mouse, is Unix-only and private.

The Windows console can hand a program the same bytes a Unix terminal sends - keys, SGR mouse
reports, bracketed pastes - once `ENABLE_VIRTUAL_TERMINAL_INPUT` is set on its input.

## Decision

- **termina reads the input on every OS** (helix-editor's terminal crate, MIT OR MPL-2.0;
  `client/stt-tui/src/keyboard.rs`). Its `PlatformTerminal` sets virtual-terminal input on
  Windows, and its `EventReader` parses keys, mouse reports, bracketed pastes and resizes with one
  parser everywhere. Its Unix source polls level-triggered (`poll(2)`), so a key pressed with a
  resize is read.
- **ratatui keeps crossterm for drawing and raw mode.** Only the input thread changed: termina's
  events are mapped onto the crossterm events the TUI already handles, so the key bindings and
  handlers are unchanged. crossterm reads no input any more.
- **The TUI asks for mouse reports and bracketed pastes with the escape sequences themselves**
  (`ESC [ ? 1000/1002/1003/1006 h`, `ESC [ ? 2004 h`) on every OS, and switches them off as it leaves;
  termina restores the console's modes when the TUI exits.
- One input path on every OS: the Linux pty suite exercises the same parser and mapping Windows
  uses, and `resize_keys.rs` guards the lost key.

## Consequences

- `stt-tui` depends on `termina` 0.3 instead of crossterm's `use-dev-tty` event source, which the
  first fix of the lost key had chosen.
- Item 67's decision "ratatui + crossterm" (2026-10-22) now reads: ratatui on crossterm for
  drawing, termina for input.
