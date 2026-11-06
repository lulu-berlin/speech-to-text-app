//! The download dialog (roadmap item 67): the path being typed, the format it saves, and the
//! request that writes the transcript there.

use std::io;
use std::path::Path;
use std::sync::mpsc;

use stt_client::{Service, Task, text, transcript};

use crate::Message;
use crate::input::Action;
use crate::view::DownloadView;

/// The dialog: the transcript's task, the path, the format and which field has the focus.
pub struct Download {
    task: Task,
    path: String,
    format: Format,
    focus: Focus,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Format {
    Txt,
    Srt,
    Vtt,
}

impl Format {
    fn as_str(self) -> &'static str {
        match self {
            Format::Txt => "txt",
            Format::Srt => "srt",
            Format::Vtt => "vtt",
        }
    }

    /// The format `path`'s extension names, if it names one - so typing `memo.srt` selects srt.
    fn from_path(path: &str) -> Option<Format> {
        match Path::new(path)
            .extension()?
            .to_str()?
            .to_ascii_lowercase()
            .as_str()
        {
            "txt" => Some(Format::Txt),
            "srt" => Some(Format::Srt),
            "vtt" => Some(Format::Vtt),
            _ => None,
        }
    }

    fn cycle(self, step: isize) -> Format {
        let formats = [Format::Txt, Format::Srt, Format::Vtt];
        let index = formats
            .iter()
            .position(|format| *format == self)
            .unwrap_or(0) as isize;
        formats[(index + step).rem_euclid(formats.len() as isize) as usize]
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Focus {
    Path,
    Format,
}

impl Download {
    pub fn new(task: Task) -> Download {
        Download {
            task,
            path: String::new(),
            format: Format::Txt,
            focus: Focus::Path,
        }
    }

    /// What the view draws.
    pub fn view(&self) -> DownloadView<'_> {
        DownloadView {
            filename: &self.task.filename,
            path: &self.path,
            format: self.format.as_str(),
            focus_format: self.focus == Focus::Format,
        }
    }

    /// Applies one dialog key; true when the dialog closes (saved or cancelled).
    pub fn handle(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) -> bool {
        match action {
            Action::CancelDownload => return true,
            Action::SubmitDownload => {
                self.submit(runtime, sender, service);
                return true;
            }
            Action::DownloadChar(character) => {
                self.path.push(character);
                if let Some(format) = Format::from_path(&self.path) {
                    self.format = format;
                }
            }
            Action::DownloadBackspace => {
                self.path.pop();
                if let Some(format) = Format::from_path(&self.path) {
                    self.format = format;
                }
            }
            Action::DownloadTab => {
                self.focus = match self.focus {
                    Focus::Path => Focus::Format,
                    Focus::Format => Focus::Path,
                };
            }
            Action::DownloadFormatStep(step) if self.focus == Focus::Format => {
                self.format = self.format.cycle(step);
            }
            _ => {}
        }
        false
    }

    /// Saves the transcript to the typed path: the destination is opened first, so a path that
    /// cannot be written is reported before anything is fetched, and the body goes into that file.
    fn submit(
        &self,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let path = self.path.trim().to_string();
        if path.is_empty() {
            let _ = sender.send(Message::Notice(text("download.no-path", &[])));
            return;
        }
        let id = self.task.id;
        let format = self.format.as_str();
        let service = service.clone();
        let sender = sender.clone();
        runtime.spawn(async move {
            let notice = match save_transcript(&service, id, format, &path).await {
                Ok(()) => text("download.saved", &[("path", &path)]),
                Err(error) => text("download.failed", &[("error", &error.to_string())]),
            };
            let _ = sender.send(Message::Notice(notice));
        });
    }
}

/// Fetches task `id`'s transcript in `format` into `path`.
async fn save_transcript(service: &Service, id: i64, format: &str, path: &str) -> io::Result<()> {
    use tokio::io::AsyncWriteExt;
    let mut file = tokio::fs::File::create(path)
        .await
        .map_err(|error| io::Error::new(error.kind(), format!("{path}: {error}")))?;
    let body = transcript(service, id, format).await?;
    file.write_all(body.as_bytes())
        .await
        .map_err(io::Error::other)
}
