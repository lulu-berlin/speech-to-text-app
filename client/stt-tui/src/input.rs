//! The TUI's key map: what a key asks for, in the upload prompt or at the queue - and which task
//! command a key means for a task's status.

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use stt_client::{Task, TaskCommand};

use crate::state::Page;
/// What a key asks the TUI to do.
#[derive(Clone, Copy)]
pub enum Action {
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
    /// Move the selected task `step` places through the queue.
    MoveSelected(isize),
    /// Pause or resume the whole queue.
    ToggleQueuePause,
    /// A task command key; [command_for] turns it into the command, if the task allows it.
    TaskKey(char),
    /// Open the history of completed tasks.
    OpenHistory,
    /// Enter: open what the selected row is (the history opens a transcript).
    OpenSelected,
    /// `b`: back to the previous screen.
    Back,
    /// Escape: back where there is a previous screen, quit at the queue.
    BackOrQuit,
    /// `d`: open the download dialog for the open transcript.
    OpenDownload,
    DownloadChar(char),
    DownloadBackspace,
    /// Move the dialog's focus between its path and its format.
    DownloadTab,
    /// Cycle the download format.
    DownloadFormatStep(isize),
    SubmitDownload,
    CancelDownload,
    /// `m` in a transcript: mark the selected speaker for a merge, unmark it, or merge the marked
    /// speaker into it.
    Merge,
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
    /// Left/Right: change the selected setting `step` places.
    Adjust(isize),
}

/// What the keyboard is for: the page's commands, a text prompt (a speaker rename, the terminal
/// command), the download dialog, or the upload dialog. One mode, never a set of flags.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Normal,
    Text,
    Download,
    Upload,
}

/// What `key` does in `mode`: with a text prompt open every key is text except Escape, Enter and
/// Backspace; in a dialog the dialog's keys; at the queue the keys are commands. Raw mode turns
/// Ctrl-C into a key, so it quits from all of them.
pub fn action(key: &KeyEvent, mode: Mode) -> Option<Action> {
    if key.kind != KeyEventKind::Press {
        return None;
    }
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
        return Some(Action::Quit);
    }
    match mode {
        Mode::Download => {
            return match key.code {
                KeyCode::Esc => Some(Action::CancelDownload),
                KeyCode::Enter => Some(Action::SubmitDownload),
                KeyCode::Backspace => Some(Action::DownloadBackspace),
                KeyCode::Tab => Some(Action::DownloadTab),
                KeyCode::Left => Some(Action::DownloadFormatStep(-1)),
                KeyCode::Right => Some(Action::DownloadFormatStep(1)),
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
                KeyCode::Char(character) => Some(Action::PromptChar(character)),
                _ => None,
            };
        }
        Mode::Text => {
            return match key.code {
                KeyCode::Esc => Some(Action::CancelPrompt),
                KeyCode::Backspace => Some(Action::PromptBackspace),
                KeyCode::Enter => Some(Action::SubmitPrompt),
                KeyCode::Char(character) => Some(Action::PromptChar(character)),
                _ => None,
            };
        }
        Mode::Normal => {}
    }
    let shift = key.modifiers.contains(KeyModifiers::SHIFT);
    match key.code {
        KeyCode::Esc => Some(Action::BackOrQuit),
        KeyCode::Char('q') => Some(Action::Quit),
        KeyCode::Char('b') => Some(Action::Back),
        KeyCode::Char('h') => Some(Action::OpenHistory),
        KeyCode::Char('d') => Some(Action::OpenDownload),
        KeyCode::Char('m') => Some(Action::Merge),
        KeyCode::Char('s') => Some(Action::OpenSettings),
        KeyCode::Char('?') => Some(Action::Help),
        KeyCode::Char('1') => Some(Action::OpenPage(Page::Queue)),
        KeyCode::Char('2') => Some(Action::OpenPage(Page::History)),
        KeyCode::Char('3') => Some(Action::OpenPage(Page::Settings)),
        KeyCode::Tab if shift => Some(Action::PagePrev),
        KeyCode::BackTab => Some(Action::PagePrev),
        KeyCode::Tab => Some(Action::PageNext),
        KeyCode::Left => Some(Action::Adjust(-1)),
        KeyCode::Right => Some(Action::Adjust(1)),
        KeyCode::Enter => Some(Action::OpenSelected),
        KeyCode::Char('u') => Some(Action::OpenPrompt),
        KeyCode::Up if shift => Some(Action::MoveSelected(-1)),
        KeyCode::Down if shift => Some(Action::MoveSelected(1)),
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
