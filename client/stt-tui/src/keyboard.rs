//! What the user types, clicks and pastes, read through termina (ADR 0069) and handed to the rest
//! of the TUI as the crossterm events it handles.
//!
//! crossterm reads the legacy console records on Windows, which carry no paste and, under a
//! pseudoconsole, no mouse: a pasted path arrived as keystrokes there. termina switches the
//! Windows console to virtual-terminal input, so every OS hands the TUI the same bytes - keys,
//! SGR mouse reports, bracketed pastes - and parses them with one parser. Its Unix source polls
//! level-triggered, so a key pressed with a resize is still read (item 67, `resize_keys.rs`).

use std::io::{self, Write};

use ratatui::crossterm::event as crossterm;
use termina::event as termina_event;
use termina::{PlatformTerminal, Terminal};

/// The terminal modes the TUI asks for on its output, on every OS: mouse reports in SGR form for
/// presses, drags and motion, and pastes bracketed. Written as bytes rather than through
/// crossterm's commands, whose Windows mouse command sets the console's input mode back to the
/// legacy records.
const ENABLE: &[u8] = b"\x1b[?1000h\x1b[?1002h\x1b[?1003h\x1b[?1006h\x1b[?2004h";
/// The same modes switched off again, as the TUI leaves the terminal.
const DISABLE: &[u8] = b"\x1b[?2004l\x1b[?1006l\x1b[?1003l\x1b[?1002l\x1b[?1000l";

/// The terminal's input for as long as the TUI runs: it holds the console's modes - restored when
/// it drops - and gives the reader the input thread uses.
pub struct Keyboard {
    terminal: PlatformTerminal,
}

impl Keyboard {
    /// Opens the terminal's input and asks the terminal for mouse reports and bracketed pastes. Call
    /// it after the terminal is in raw mode: on Windows it adds the console modes raw mode leaves
    /// out (virtual-terminal input, mouse and window events).
    pub fn open() -> io::Result<Keyboard> {
        let mut terminal = PlatformTerminal::new()?;
        terminal.enter_raw_mode()?;
        let mut out = io::stdout();
        out.write_all(ENABLE)?;
        out.flush()?;
        Ok(Keyboard { terminal })
    }

    /// The reader the input thread blocks on.
    pub fn reader(&self) -> termina::EventReader {
        self.terminal.event_reader()
    }

    /// Switches the mouse reports and bracketed pastes off; the console's own modes come back as
    /// the keyboard drops.
    pub fn close(self) {
        let mut out = io::stdout();
        let _ = out.write_all(DISABLE);
        let _ = out.flush();
    }
}

/// Reads the terminal's input until it ends, handing each event the TUI handles to `deliver`;
/// stops when `deliver` says the TUI no longer listens.
pub fn read(reader: termina::EventReader, mut deliver: impl FnMut(crossterm::Event) -> bool) {
    loop {
        match reader.read(|_| true) {
            Ok(event) => {
                if let Some(event) = to_crossterm(event)
                    && !deliver(event)
                {
                    return;
                }
            }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => return,
        }
    }
}

/// `event` as the crossterm event the TUI handles; `None` for what it does not act on - the
/// terminal's answers to queries, media and lone modifier keys.
fn to_crossterm(event: termina::Event) -> Option<crossterm::Event> {
    match event {
        termina::Event::Key(key) => Some(crossterm::Event::Key(crossterm::KeyEvent {
            code: key_code(key.code)?,
            modifiers: modifiers(key.modifiers),
            kind: match key.kind {
                termina_event::KeyEventKind::Press => crossterm::KeyEventKind::Press,
                termina_event::KeyEventKind::Release => crossterm::KeyEventKind::Release,
                termina_event::KeyEventKind::Repeat => crossterm::KeyEventKind::Repeat,
            },
            state: key_state(key.state),
        })),
        termina::Event::Mouse(mouse) => Some(crossterm::Event::Mouse(crossterm::MouseEvent {
            kind: match mouse.kind {
                termina_event::MouseEventKind::Down(button) => {
                    crossterm::MouseEventKind::Down(button_of(button))
                }
                termina_event::MouseEventKind::Up(button) => {
                    crossterm::MouseEventKind::Up(button_of(button))
                }
                termina_event::MouseEventKind::Drag(button) => {
                    crossterm::MouseEventKind::Drag(button_of(button))
                }
                termina_event::MouseEventKind::Moved => crossterm::MouseEventKind::Moved,
                termina_event::MouseEventKind::ScrollDown => crossterm::MouseEventKind::ScrollDown,
                termina_event::MouseEventKind::ScrollUp => crossterm::MouseEventKind::ScrollUp,
                termina_event::MouseEventKind::ScrollLeft => crossterm::MouseEventKind::ScrollLeft,
                termina_event::MouseEventKind::ScrollRight => {
                    crossterm::MouseEventKind::ScrollRight
                }
            },
            column: mouse.column,
            row: mouse.row,
            modifiers: modifiers(mouse.modifiers),
        })),
        termina::Event::WindowResized(size) => Some(crossterm::Event::Resize(size.cols, size.rows)),
        termina::Event::FocusIn => Some(crossterm::Event::FocusGained),
        termina::Event::FocusOut => Some(crossterm::Event::FocusLost),
        termina::Event::Paste(text) => Some(crossterm::Event::Paste(text)),
        termina::Event::Csi(_) | termina::Event::Osc(_) | termina::Event::Dcs(_) => None,
    }
}

fn key_code(code: termina_event::KeyCode) -> Option<crossterm::KeyCode> {
    use crossterm::KeyCode as C;
    use termina_event::KeyCode as T;
    Some(match code {
        T::Char(character) => C::Char(character),
        T::Enter => C::Enter,
        T::Backspace => C::Backspace,
        T::Tab => C::Tab,
        T::Escape => C::Esc,
        T::Left => C::Left,
        T::Right => C::Right,
        T::Up => C::Up,
        T::Down => C::Down,
        T::Home => C::Home,
        T::End => C::End,
        T::BackTab => C::BackTab,
        T::PageUp => C::PageUp,
        T::PageDown => C::PageDown,
        T::Insert => C::Insert,
        T::Delete => C::Delete,
        T::KeypadBegin => C::KeypadBegin,
        T::CapsLock => C::CapsLock,
        T::ScrollLock => C::ScrollLock,
        T::NumLock => C::NumLock,
        T::PrintScreen => C::PrintScreen,
        T::Pause => C::Pause,
        T::Menu => C::Menu,
        T::Null => C::Null,
        T::Function(number) => C::F(number),
        T::Modifier(_) | T::Media(_) => return None,
    })
}

fn modifiers(modifiers: termina_event::Modifiers) -> crossterm::KeyModifiers {
    let mut mapped = crossterm::KeyModifiers::NONE;
    for (from, to) in [
        (
            termina_event::Modifiers::SHIFT,
            crossterm::KeyModifiers::SHIFT,
        ),
        (
            termina_event::Modifiers::CONTROL,
            crossterm::KeyModifiers::CONTROL,
        ),
        (termina_event::Modifiers::ALT, crossterm::KeyModifiers::ALT),
        (
            termina_event::Modifiers::SUPER,
            crossterm::KeyModifiers::SUPER,
        ),
        (
            termina_event::Modifiers::HYPER,
            crossterm::KeyModifiers::HYPER,
        ),
        (
            termina_event::Modifiers::META,
            crossterm::KeyModifiers::META,
        ),
    ] {
        if modifiers.contains(from) {
            mapped |= to;
        }
    }
    mapped
}

fn key_state(state: termina_event::KeyEventState) -> crossterm::KeyEventState {
    let mut mapped = crossterm::KeyEventState::NONE;
    for (from, to) in [
        (
            termina_event::KeyEventState::KEYPAD,
            crossterm::KeyEventState::KEYPAD,
        ),
        (
            termina_event::KeyEventState::CAPS_LOCK,
            crossterm::KeyEventState::CAPS_LOCK,
        ),
        (
            termina_event::KeyEventState::NUM_LOCK,
            crossterm::KeyEventState::NUM_LOCK,
        ),
    ] {
        if state.contains(from) {
            mapped |= to;
        }
    }
    mapped
}

fn button_of(button: termina_event::MouseButton) -> crossterm::MouseButton {
    match button {
        termina_event::MouseButton::Left => crossterm::MouseButton::Left,
        termina_event::MouseButton::Right => crossterm::MouseButton::Right,
        termina_event::MouseButton::Middle => crossterm::MouseButton::Middle,
    }
}
