//! The TUI's key map: what a key asks for, in the upload prompt or at the queue - and which task
//! command a key means for a task's status.

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use stt_client::{Task, TaskCommand};

use crate::state::Page;
/// What a key asks the TUI to do.
#[derive(Clone, Copy)]
pub enum Action {
    /// Ctrl-C: quit at once, wherever the TUI is.
    Quit,
    /// Open the upload prompt, empty.
    OpenPrompt,
    /// Close the upload prompt without sending it.
    CancelPrompt,
    PromptBackspace,
    /// Upload the path the prompt holds.
    SubmitPrompt,
    /// Add a character to the upload prompt.
    PromptChar(char),
    SelectUp,
    SelectDown,
    /// The previous segment of an open transcript (Up, `k`, Left, `h`).
    SegmentPrevious,
    /// The next segment of an open transcript (Down, `j`, Right, `l`).
    SegmentNext,
    /// A viewport up a transcript (`PgUp`, Ctrl-U).
    ScrollPrevious,
    /// A viewport down a transcript (`PgDn`, Ctrl-D).
    ScrollNext,
    /// The transcript's first segment (`Home`, `g`).
    GotoStart,
    /// The transcript's last segment (`End`, `G`).
    GotoEnd,
    /// Move the selected task `step` places through the queue.
    MoveSelected(isize),
    /// Move the selected task to the queue's first place.
    MoveToTop,
    /// Move the selected task to the queue's last place.
    MoveToBottom,
    /// Pause or resume the whole queue.
    ToggleQueuePause,
    /// A task command key; [command_for] turns it into the command, if the task allows it.
    TaskKey(char),
    /// Enter: open what the selected row is (the history opens a transcript).
    OpenSelected,
    /// `b`: back to the previous screen.
    Back,
    /// Escape and `q`: back where there is a previous screen, quit at the queue.
    BackOrQuit,
    /// `d`: open the download dialog for the open transcript.
    OpenDownload,
    /// `Space` in a transcript: play from the playhead, or pause.
    PlayPause,
    /// `p` in a transcript: play the cursor's segment alone, or stop it while it plays.
    PlaySegment,
    /// `[` and `]`: the playhead back or on by five seconds.
    SeekBack,
    SeekForward,
    /// `f` in a transcript: the cursor back to the playing segment, walking with the playhead again.
    FollowPlayhead,
    DownloadChar(char),
    DownloadBackspace,
    /// Move the dialog's focus between its folder list and its format.
    DownloadTab,
    SubmitDownload,
    CancelDownload,
    /// `m` in a transcript: mark the selected speaker for a merge, unmark it, or merge the marked
    /// speaker into it.
    Merge,
    /// `R` in a transcript: reset all merges.
    ResetMerges,
    /// `e` in a transcript: edit the cursor's segment text.
    EditSegment,
    /// `v` in a transcript: put whisper's original text back on the cursor's segment.
    RevertSegment,
    /// `a` in a transcript: mark the selected speaker's segments, or unmark them.
    MarkSegments,
    /// `A` in a transcript: mark every segment of the cursor's turn, or unmark them.
    MarkTurn,
    /// `n` in a transcript: move the marked segments to a new speaker.
    AssignNewSpeaker,
    /// `s`: open the settings.
    OpenSettings,
    /// `1`/`2`/`3`: open the page.
    OpenPage(Page),
    /// `Tab`: the next page.
    PageNext,
    /// `Shift-Tab`: the previous page.
    PagePrev,
    /// `?`: the current page's key reference.
    Help,
    /// `t`: the next theme mode, `auto → light → dark → auto`, stored for every frontend.
    CycleTheme,
    /// `Tab`: the next field of the upload dialog.
    FieldNext,
    /// `Shift-Tab`: the previous field of the upload dialog.
    FieldPrev,
    /// `Delete` or `Backspace` on a queue row: delete the selected task, after a confirmation.
    DeleteTask,
    /// `x`: dismiss the device-fallback banner.
    DismissFallback,
    /// Enter in the confirmation: do it.
    ConfirmYes,
    /// Escape in the confirmation: don't.
    ConfirmNo,
    /// Left/Right: change the selected setting `step` places.
    Adjust(isize),
    /// `/` on History: search its rows by file name.
    Search,
    /// `f` on History: the next status filter.
    FilterNext,
}

/// What the keyboard is for: the page's commands, a text prompt (a speaker rename, the terminal
/// command), the download dialog, the upload dialog, a settings dropdown or a settings number. One
/// mode, never a set of flags.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Normal,
    Text,
    /// History's search: every character is part of the query; Escape clears it.
    Search,
    Download,
    Upload,
    /// A yes/no confirmation over the page (deleting a task).
    Confirm,
    /// A settings dropdown: its cursor moves, Enter chooses, Escape closes it alone.
    Choose,
    /// A settings number edited in its row: digits and Backspace edit it, Up/Down change it by one,
    /// Enter saves it, Escape leaves the stored value.
    Number,
}

/// Where a key lands, as far as the key map cares: a transcript keeps the arrows and `h`/`j`/`k`/`l`
/// for its segment cursor, every page uses them to move between the tabs.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Context {
    Transcript,
    Page,
}

/// What `key` does in `mode`: with a text prompt open every key is text except Escape, Enter and
/// Backspace; in a dialog the dialog's keys; at the queue the keys are commands. Raw mode turns
/// Ctrl-C into a key, so it quits from all of them.
pub fn action(key: &KeyEvent, mode: Mode, context: Context) -> Option<Action> {
    if key.kind != KeyEventKind::Press {
        return None;
    }
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
        return Some(Action::Quit);
    }
    match mode {
        Mode::Number => {
            return match key.code {
                KeyCode::Esc | KeyCode::Char('q') => Some(Action::CancelPrompt),
                KeyCode::Enter => Some(Action::SubmitPrompt),
                KeyCode::Backspace => Some(Action::PromptBackspace),
                KeyCode::Up => Some(Action::Adjust(1)),
                KeyCode::Down => Some(Action::Adjust(-1)),
                KeyCode::Char(digit @ '0'..='9') => Some(Action::PromptChar(digit)),
                _ => None,
            };
        }
        Mode::Choose => {
            // The transcript's "Move to" list keeps its vim moves and `q`.
            if context == Context::Transcript {
                return match key.code {
                    KeyCode::Esc | KeyCode::Char('q') => Some(Action::CancelPrompt),
                    KeyCode::Enter => Some(Action::SubmitPrompt),
                    KeyCode::Up | KeyCode::Char('k') => Some(Action::SelectUp),
                    KeyCode::Down | KeyCode::Char('j') => Some(Action::SelectDown),
                    _ => None,
                };
            }
            // A settings dropdown is a filtering search: the arrows move, letters narrow its list
            // and Backspace widens it, so every letter can be typed; Escape closes it.
            return match key.code {
                KeyCode::Esc => Some(Action::CancelPrompt),
                KeyCode::Enter => Some(Action::SubmitPrompt),
                KeyCode::Up => Some(Action::SelectUp),
                KeyCode::Down => Some(Action::SelectDown),
                KeyCode::Backspace => Some(Action::PromptBackspace),
                KeyCode::Char(character) => Some(Action::PromptChar(character)),
                _ => None,
            };
        }
        Mode::Confirm => {
            return match key.code {
                KeyCode::Esc | KeyCode::Char('q') => Some(Action::ConfirmNo),
                KeyCode::Enter => Some(Action::ConfirmYes),
                _ => None,
            };
        }
        Mode::Download => {
            return match key.code {
                KeyCode::Esc => Some(Action::CancelDownload),
                KeyCode::Enter => Some(Action::SubmitDownload),
                KeyCode::Backspace => Some(Action::DownloadBackspace),
                KeyCode::Tab => Some(Action::DownloadTab),
                // Letters type the target file, so only the arrows move the list and its folders,
                // as the upload dialog's do; on the format row Left and Right change it.
                KeyCode::Up => Some(Action::SelectUp),
                KeyCode::Down => Some(Action::SelectDown),
                KeyCode::Left => Some(Action::Adjust(-1)),
                KeyCode::Right => Some(Action::Adjust(1)),
                KeyCode::Char(character) => Some(Action::DownloadChar(character)),
                _ => None,
            };
        }
        Mode::Upload => {
            return match key.code {
                KeyCode::Esc => Some(Action::CancelPrompt),
                KeyCode::Enter => Some(Action::SubmitPrompt),
                KeyCode::Backspace => Some(Action::PromptBackspace),
                // Letters type a path inside the dialog, so only the arrows move the selection.
                KeyCode::Up => Some(Action::SelectUp),
                KeyCode::Down => Some(Action::SelectDown),
                KeyCode::Tab => Some(Action::FieldNext),
                KeyCode::BackTab => Some(Action::FieldPrev),
                KeyCode::Left => Some(Action::Adjust(-1)),
                KeyCode::Right => Some(Action::Adjust(1)),
                KeyCode::Char(character) => Some(Action::PromptChar(character)),
                _ => None,
            };
        }
        Mode::Text | Mode::Search => {
            return match key.code {
                KeyCode::Esc => Some(Action::CancelPrompt),
                KeyCode::Backspace => Some(Action::PromptBackspace),
                KeyCode::Enter => Some(Action::SubmitPrompt),
                // Ctrl-R in a text prompt reverts the segment being edited, as the browser's editor
                // offers its revert beside the text.
                KeyCode::Char('r')
                    if mode == Mode::Text && key.modifiers.contains(KeyModifiers::CONTROL) =>
                {
                    Some(Action::RevertSegment)
                }
                KeyCode::Char(character) => Some(Action::PromptChar(character)),
                _ => None,
            };
        }
        Mode::Normal => {}
    }
    let shift = key.modifiers.contains(KeyModifiers::SHIFT);
    // In a transcript every arrow and `h`/`j`/`k`/`l` moves the segment cursor, so they do not move
    // between the tabs there (Shift and a tab's letter, the page keys and `Tab` still do); the
    // paging and jumping keys work there too.
    if context == Context::Transcript {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        match key.code {
            KeyCode::Char('g') => return Some(Action::GotoStart),
            KeyCode::Char('G') => return Some(Action::GotoEnd),
            KeyCode::Home => return Some(Action::GotoStart),
            KeyCode::End => return Some(Action::GotoEnd),
            KeyCode::PageUp => return Some(Action::ScrollPrevious),
            KeyCode::PageDown => return Some(Action::ScrollNext),
            // The transcript's audio: play from the playhead, or pause.
            KeyCode::Char(' ') => return Some(Action::PlayPause),
            KeyCode::Char('p') => return Some(Action::PlaySegment),
            KeyCode::Char('[') => return Some(Action::SeekBack),
            KeyCode::Char(']') => return Some(Action::SeekForward),
            // The cursor back to the playing segment, following the playhead again.
            KeyCode::Char('f') => return Some(Action::FollowPlayhead),
            KeyCode::Char('u') if control => return Some(Action::ScrollPrevious),
            KeyCode::Char('d') if control => return Some(Action::ScrollNext),
            KeyCode::Up | KeyCode::Char('k') | KeyCode::Left | KeyCode::Char('h') if !shift => {
                return Some(Action::SegmentPrevious);
            }
            KeyCode::Down | KeyCode::Char('j') | KeyCode::Right | KeyCode::Char('l') if !shift => {
                return Some(Action::SegmentNext);
            }
            _ => {}
        }
    }
    match key.code {
        KeyCode::Esc => Some(Action::BackOrQuit),
        KeyCode::Char('q') => Some(Action::BackOrQuit),
        KeyCode::Char('b') => Some(Action::Back),
        // `h`/`l` move to the previous and the next tab; Shift and a tab's first letter opens it.
        KeyCode::Char('h') => Some(Action::PagePrev),
        KeyCode::Char('l') => Some(Action::PageNext),
        KeyCode::Char('Q') => Some(Action::OpenPage(Page::Queue)),
        KeyCode::Char('H') => Some(Action::OpenPage(Page::History)),
        KeyCode::Char('S') => Some(Action::OpenPage(Page::Settings)),
        KeyCode::Char('d') => Some(Action::OpenDownload),
        KeyCode::Char('x') => Some(Action::DismissFallback),
        KeyCode::Delete | KeyCode::Backspace => Some(Action::DeleteTask),
        KeyCode::Char('m') => Some(Action::Merge),
        KeyCode::Char('R') => Some(Action::ResetMerges),
        KeyCode::Char('a') => Some(Action::MarkSegments),
        KeyCode::Char('A') => Some(Action::MarkTurn),
        KeyCode::Char('e') => Some(Action::EditSegment),
        KeyCode::Char('v') => Some(Action::RevertSegment),
        KeyCode::Char('n') => Some(Action::AssignNewSpeaker),
        // `s`, and `o` - the queue's "change [o]ptions" for new uploads - open the settings.
        KeyCode::Char('s') | KeyCode::Char('o') => Some(Action::OpenSettings),
        KeyCode::Char('?') => Some(Action::Help),
        KeyCode::Char('/') => Some(Action::Search),
        KeyCode::Char('f') => Some(Action::FilterNext),
        KeyCode::Char('t') => Some(Action::CycleTheme),
        KeyCode::Char('1') => Some(Action::OpenPage(Page::Queue)),
        KeyCode::Char('2') => Some(Action::OpenPage(Page::History)),
        KeyCode::Char('3') => Some(Action::OpenPage(Page::Settings)),
        KeyCode::Tab if shift => Some(Action::PagePrev),
        KeyCode::BackTab => Some(Action::PagePrev),
        KeyCode::Tab => Some(Action::PageNext),
        KeyCode::Left => Some(Action::PagePrev),
        KeyCode::Right => Some(Action::PageNext),
        KeyCode::Enter => Some(Action::OpenSelected),
        KeyCode::Char('u') => Some(Action::OpenPrompt),
        KeyCode::Up if shift => Some(Action::MoveSelected(-1)),
        KeyCode::Down if shift => Some(Action::MoveSelected(1)),
        KeyCode::Home if shift => Some(Action::MoveToTop),
        KeyCode::End if shift => Some(Action::MoveToBottom),
        KeyCode::Char('K') => Some(Action::MoveSelected(-1)),
        KeyCode::Char('J') => Some(Action::MoveSelected(1)),
        KeyCode::Up | KeyCode::Char('k') => Some(Action::SelectUp),
        KeyCode::Down | KeyCode::Char('j') => Some(Action::SelectDown),
        KeyCode::Char(' ') => Some(Action::ToggleQueuePause),
        KeyCode::Char(character @ ('p' | 'c' | 'r')) => Some(Action::TaskKey(character)),
        _ => None,
    }
}

/// The command `key` sends for `task`, when the task's status allows it: the transition table the
/// browser reads (`docs/api/task-transitions.json`).
pub fn command_for(task: &Task, key: char) -> Option<TaskCommand> {
    let command = match key {
        'p' if task.status == "PAUSED" => TaskCommand::Resume,
        'p' => TaskCommand::Pause,
        'c' => TaskCommand::Cancel,
        'r' => TaskCommand::Retry,
        _ => return None,
    };
    command.allowed(&task.status).then_some(command)
}
