//! The TUI's key map: what a key asks for, in the upload prompt or at the queue - and which task
//! command a key means for a task's status.

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use stt_client::{Task, TaskCommand};

/// What a key asks the TUI to do.
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
}

/// What `key` does: with the prompt open every key is text except Escape, Enter and Backspace; at
/// the queue the keys are commands. Raw mode turns Ctrl-C into a key, so it quits from both.
pub fn action(key: &KeyEvent, prompt_open: bool) -> Option<Action> {
    if key.kind != KeyEventKind::Press {
        return None;
    }
    if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
        return Some(Action::Quit);
    }
    if prompt_open {
        return match key.code {
            KeyCode::Esc => Some(Action::CancelPrompt),
            KeyCode::Backspace => Some(Action::PromptBackspace),
            KeyCode::Enter => Some(Action::SubmitPrompt),
            KeyCode::Char(character) => Some(Action::PromptChar(character)),
            _ => None,
        };
    }
    let shift = key.modifiers.contains(KeyModifiers::SHIFT);
    match key.code {
        KeyCode::Esc | KeyCode::Char('q') => Some(Action::Quit),
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
