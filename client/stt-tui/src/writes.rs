//! Which keys write (roadmap item 67, third amendment): while the event stream is not live the
//! browser's write gate disables them, and the TUI refuses them with a notice that names them. The
//! footer's muted hints ask this rule itself - each hint's key taken as the press it names - so
//! there is one source of truth for which keys write.

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use stt_client::{Task, TaskCommand};

use crate::input::{self, Action, Mode};
use crate::state::Screen;
use crate::upload_dialog::UploadDialog;

/// Whether the footer's hint writes at `place`: its key is taken as the press it names, and muted
/// exactly when that press would be refused. A hint whose key is a list of navigation keys
/// (`↑↓/j/k`, `PgUp/PgDn`) names no single press and never mutes.
pub fn hint_writes(hint: &str, mode: Mode, context: input::Context, place: &Place<'_>) -> bool {
    let Some(key) = hint_key(hint) else {
        return false;
    };
    let Some(action) = input::action(&key, mode, context) else {
        return false;
    };
    name(action, mode, place).is_some()
}

/// The press the catalog hint `hint` names: the first key its brackets spell out - `[p]ause`, `re[v]ert`,
/// `[Del] delete`, `[Shift-↑↓ or J/K] reorder` - or `None` when the first bracketed key is a list.
pub(crate) fn hint_key(hint: &str) -> Option<KeyEvent> {
    let mut rest = hint;
    while let Some(open) = rest.find('[') {
        let close = rest[open..].find(']')? + open;
        let fragment = &rest[open + 1..close];
        rest = &rest[close + 1..];
        if let Some(key) = key_named(fragment) {
            return Some(key);
        }
    }
    None
}

/// The key `fragment` names - a character, or a key by its name, with an optional `Shift-` or
/// `Ctrl-` - or `None` when it names none (`PgUp/PgDn/^U/^D`, `←→/h/l or Q/H/S`).
fn key_named(fragment: &str) -> Option<KeyEvent> {
    if let Some(rest) = fragment.strip_prefix("Ctrl-") {
        // A terminal reports Ctrl and a letter as the lower-case letter with Ctrl held.
        let mut characters = rest.chars();
        return match (characters.next(), characters.next()) {
            (Some(character), None) => Some(KeyEvent::new(
                KeyCode::Char(character.to_ascii_lowercase()),
                KeyModifiers::CONTROL,
            )),
            _ => None,
        };
    }
    let (modifiers, fragment) = match fragment.strip_prefix("Shift-") {
        Some(rest) => (KeyModifiers::SHIFT, rest),
        None => (KeyModifiers::NONE, fragment),
    };
    let code = match fragment {
        "Esc" => KeyCode::Esc,
        "Enter" => KeyCode::Enter,
        "Tab" => KeyCode::Tab,
        "Backspace" => KeyCode::Backspace,
        "Del" => KeyCode::Delete,
        "Space" => KeyCode::Char(' '),
        "Up" => KeyCode::Up,
        "Down" => KeyCode::Down,
        "Left" => KeyCode::Left,
        "Right" => KeyCode::Right,
        // The arrows as the hints draw them: the arrow keys, never the characters typed.
        "↑" => KeyCode::Up,
        "↓" => KeyCode::Down,
        "←" => KeyCode::Left,
        "→" => KeyCode::Right,
        _ => {
            let mut characters = fragment.chars();
            match (characters.next(), characters.next()) {
                (Some(character), None) => KeyCode::Char(character),
                _ => return None,
            }
        }
    };
    Some(KeyEvent::new(code, modifiers))
}

/// Where a key lands, as far as writing goes.
pub struct Place<'a> {
    pub screen: &'a Screen,
    /// The upload dialog, while it is open.
    pub upload: Option<&'a UploadDialog>,
    /// Whether the queue page's upload area has the focus.
    pub upload_focused: bool,
    /// The row the task commands act on: the queue's selected one, or History's.
    pub selected_task: Option<&'a Task>,
    /// The write the open confirmation sends on Enter, by its catalog name.
    pub confirming: Option<&'static str>,
}

/// The catalog name of the write `action` would start in `mode` at `place`, if it writes. Reading,
/// moving and local work (the download dialog) are not writes.
pub fn name(action: Action, mode: Mode, place: &Place<'_>) -> Option<&'static str> {
    let transcript = match place.screen {
        Screen::Transcript(transcript) => Some(transcript),
        _ => None,
    };
    let name = match (mode, action) {
        (_, Action::CycleTheme) => "tui.writes.theme",
        (Mode::Confirm, Action::ConfirmYes) => return place.confirming,
        (Mode::Upload, Action::SubmitPrompt) => {
            return place
                .upload
                .is_some_and(UploadDialog::enter_uploads)
                .then_some("tui.writes.upload");
        }
        (Mode::Text, Action::SubmitPrompt) => match transcript {
            Some(transcript) if transcript.rename().is_some() => "tui.writes.rename",
            Some(_) => "tui.writes.edit",
            None => "tui.writes.setting",
        },
        // Ctrl-R in the edit prompt reverts its segment.
        (Mode::Text, Action::RevertSegment)
            if transcript.is_some_and(|open| open.edit().is_some()) =>
        {
            "tui.writes.revert"
        }
        // The "Move to" list's Enter moves the marked segments; a settings dropdown's stores.
        (Mode::Choose, Action::SubmitPrompt) if transcript.is_some() => "tui.writes.assign",
        (Mode::Choose | Mode::Number, Action::SubmitPrompt) => "tui.writes.setting",
        (Mode::Normal, action) => match (place.screen, action) {
            (_, Action::DismissFallback) => "tui.writes.dismiss",
            (Screen::Queue, Action::MoveSelected(_)) => "tui.writes.reorder",
            (Screen::Queue | Screen::History, Action::DeleteTask) => "tui.writes.delete",
            (Screen::Queue, Action::OpenPrompt) => "tui.writes.upload",
            (Screen::Queue, Action::OpenSelected | Action::ToggleQueuePause)
                if place.upload_focused =>
            {
                "tui.writes.upload"
            }
            (Screen::Queue, Action::ToggleQueuePause) => "tui.writes.queuePause",
            (Screen::Queue | Screen::History, Action::TaskKey(key)) => {
                match input::command_for(place.selected_task?, key)? {
                    TaskCommand::Pause => "tui.writes.pause",
                    TaskCommand::Resume => "tui.writes.resume",
                    TaskCommand::Cancel => "tui.writes.cancel",
                    TaskCommand::Retry => "tui.writes.retry",
                }
            }
            (Screen::Transcript(_), Action::TaskKey('r')) => "tui.writes.rename",
            (Screen::Transcript(_), Action::EditSegment) => "tui.writes.edit",
            (Screen::Transcript(_), Action::RevertSegment) => "tui.writes.revert",
            (Screen::Transcript(_), Action::Merge) => "tui.writes.merge",
            (Screen::Transcript(_), Action::ResetMerges) => "tui.writes.reset",
            (Screen::Transcript(_), Action::AssignNewSpeaker) => "tui.writes.assign",
            // Enter moves marked segments only on the Speakers panel; on the transcript it opens
            // the "Move to" list, whose own Enter moves them.
            (Screen::Transcript(transcript), Action::OpenSelected)
                if transcript.speaker_focus() =>
            {
                if transcript.marking() {
                    "tui.writes.assign"
                } else {
                    "tui.writes.rename"
                }
            }
            (Screen::Settings(screen), Action::OpenSelected) if screen.on_restore() => {
                "tui.writes.restoreDefaults"
            }
            (Screen::Settings(_), Action::OpenSelected) => "tui.writes.setting",
            _ => return None,
        },
        _ => return None,
    };
    Some(name)
}
