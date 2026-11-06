//! The transcript screen (roadmap item 67): one task's turns, its speakers list with the selected
//! speaker, and the rename prompt with the request that renames it.

use std::sync::mpsc;

use stt_client::{Service, Speaker, Task, Turns, rename_speaker, text};

use crate::Message;
use crate::input::Action;

/// An open transcript: the task, its speakers and turns, the speakers list's selected row, and the
/// name being typed while the rename prompt is open.
pub struct Transcript {
    pub task: Task,
    pub turns: Turns,
    pub speaker: usize,
    rename: Option<String>,
}

impl Transcript {
    pub fn new(task: Task, turns: Turns) -> Transcript {
        Transcript {
            task,
            turns,
            speaker: 0,
            rename: None,
        }
    }

    /// The name in the rename prompt, while it is open.
    pub fn rename(&self) -> Option<&str> {
        self.rename.as_deref()
    }

    /// Task `id`'s speakers as the service answered; they relabel this transcript if it is task
    /// `id`'s.
    pub fn set_speakers(&mut self, id: i64, speakers: Vec<Speaker>) {
        if self.task.id == id {
            self.speaker = self.speaker.min(speakers.len().saturating_sub(1));
            self.turns.speakers = speakers;
        }
    }

    /// Applies one key's action: Up/Down select a speaker, `r` opens the rename prompt with the
    /// selected speaker's name, and while it is open the prompt's keys edit, send or close it.
    pub fn handle(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        match action {
            Action::SelectUp => self.speaker = self.speaker.saturating_sub(1),
            Action::SelectDown => {
                if self.speaker + 1 < self.turns.speakers.len() {
                    self.speaker += 1;
                }
            }
            // `r` in a transcript renames the selected speaker, starting from its name.
            Action::TaskKey('r') => {
                if let Some(selected) = self.turns.speakers.get(self.speaker) {
                    self.rename = Some(selected.speaker.clone());
                }
            }
            Action::CancelPrompt => self.rename = None,
            Action::PromptBackspace => {
                if let Some(name) = self.rename.as_mut() {
                    name.pop();
                }
            }
            Action::PromptChar(character) => {
                if let Some(name) = self.rename.as_mut() {
                    name.push(character);
                }
            }
            Action::SubmitPrompt => {
                if let Some(name) = self.rename.take()
                    && let Some(selected) = self.turns.speakers.get(self.speaker)
                {
                    rename(
                        runtime,
                        service,
                        sender,
                        self.task.id,
                        selected.speaker_id,
                        name,
                    );
                }
            }
            _ => {}
        }
    }
}

/// Renames speaker `speaker_id` of task `id` to `name`; the service's answer relabels the open
/// transcript, and a refusal is the notice.
fn rename(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    speaker_id: i64,
    name: String,
) {
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match rename_speaker(&service, id, speaker_id, &name).await {
            Ok(speakers) => Message::Speakers(id, speakers),
            Err(error) => Message::Notice(text(
                "speakers.rename-failed",
                &[("error", &error.to_string())],
            )),
        };
        let _ = sender.send(message);
    });
}
