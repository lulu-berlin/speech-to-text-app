//! The download dialog (roadmap item 67): the folder the target file is named in - browsed as the
//! upload dialog browses - the format it saves as, and the request that writes the transcript
//! there.

use std::io;
use std::path::Path;
use std::sync::mpsc;

use stt_client::{Service, Task, text, transcript};

use crate::Message;
use crate::browser::Browser;
use crate::input::Action;

/// The dialog: the transcript's task, the folder browser - its typed path names the target file -
/// the format it saves as, and whether the format row has the focus.
pub struct Download {
    task: Task,
    browser: Browser,
    format: Format,
    format_focus: bool,
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

impl Download {
    /// Opens in the folder the TUI runs in, as the upload dialog does, or in the home folder when
    /// that one cannot be read.
    pub fn new(task: Task) -> Download {
        Download {
            task,
            browser: Browser::open(),
            format: Format::Txt,
            format_focus: false,
        }
    }

    /// The folder browser: the folder, its rows, the selection and the typed target.
    pub fn browser(&self) -> &Browser {
        &self.browser
    }

    /// The task's file name, the dialog's title.
    pub fn task_filename(&self) -> &str {
        &self.task.filename
    }

    /// The format the transcript is saved as.
    pub fn format(&self) -> &'static str {
        self.format.as_str()
    }

    /// Whether the format row has the focus; the folder list has it otherwise.
    pub fn format_focus(&self) -> bool {
        self.format_focus
    }

    /// The typed target's extension picks the format, when it names one.
    fn follow_extension(&mut self) {
        if let Some(format) = self.browser.typed().and_then(Format::from_path) {
            self.format = format;
        }
    }

    /// Another folder is shown: the name typed for the last one goes.
    fn browse(&mut self, moved: bool) {
        if moved {
            self.browser.take_typed();
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
            Action::SubmitDownload => return self.submit(runtime, sender, service),
            Action::DownloadChar(character) => {
                self.browser.push(character);
                self.follow_extension();
            }
            Action::DownloadBackspace => {
                self.browser.backspace();
                self.follow_extension();
            }
            Action::DownloadTab => self.format_focus = !self.format_focus,
            Action::SelectUp => self.browser.up(),
            Action::SelectDown => self.browser.down(),
            // Left goes up a folder and Right opens the selected one, as the upload dialog's do;
            // on the format row they change it.
            Action::Adjust(step) if self.format_focus => self.format = self.format.cycle(step),
            Action::Adjust(step) if step < 0 => {
                self.browser.go_up();
                self.browse(true);
            }
            Action::Adjust(_) => {
                let moved = self.browser.enter_selected();
                self.browse(moved);
            }
            _ => {}
        }
        false
    }

    /// Enter: a typed target is resolved against the shown folder - a folder is descended into, and
    /// anything else saved to; otherwise the selected row is followed: `../` goes up, a folder is
    /// entered, and a file's name is typed so Enter again saves to it.
    fn submit(
        &mut self,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) -> bool {
        if let Some(typed) = self.browser.typed().map(|typed| typed.trim().to_string()) {
            if typed.is_empty() {
                self.browser.take_typed();
                return false;
            }
            let path = self.browser.resolve(&typed);
            if path.is_dir() {
                self.browser.take_typed();
                self.browser.enter(path);
                return false;
            }
            self.save(&path, &typed, runtime, sender, service);
            return true;
        }
        match self.browser.selected_row() {
            Some(Some(entry)) if !entry.is_dir() => {
                if let Some(name) = entry.file_name() {
                    self.browser.set_typed(name.to_string_lossy().into_owned());
                    self.follow_extension();
                }
            }
            Some(_) => {
                self.browser.enter_selected();
            }
            None => {
                let _ = sender.send(Message::Notice(text("tui.download.noPath", &[])));
            }
        }
        false
    }

    /// Saves the transcript to `path`, named on screen as `named`: the file is created first, so a
    /// path that cannot be written is reported before anything is fetched.
    fn save(
        &self,
        path: &Path,
        named: &str,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let id = self.task.id;
        let format = self.format.as_str();
        let path = path.to_path_buf();
        let named = named.to_string();
        let service = service.clone();
        let sender = sender.clone();
        runtime.spawn(async move {
            let notice = match save_transcript(&service, id, format, &path).await {
                Ok(()) => text("tui.download.saved", &[("path", &named)]),
                Err(error) => text("tui.download.failed", &[("error", &error.to_string())]),
            };
            let _ = sender.send(Message::Notice(notice));
        });
    }
}

/// Fetches task `id`'s transcript in `format` into `path`. The body is written to a partial file
/// beside `path` - whose creation reports a path that cannot be written before anything is
/// fetched - and takes `path`'s place only once whole, with the permissions of a file already
/// there: a download that fails leaves a file already at `path` as it was.
async fn save_transcript(service: &Service, id: i64, format: &str, path: &Path) -> io::Result<()> {
    use tokio::io::AsyncWriteExt;
    let named = path.display().to_string();
    let at_path = |error: io::Error| io::Error::new(error.kind(), format!("{named}: {error}"));
    let partial = partial_beside(path);
    let mut file = tokio::fs::File::create(&partial).await.map_err(at_path)?;
    let written = async {
        let body = transcript(service, id, format).await?;
        file.write_all(body.as_bytes())
            .await
            .map_err(io::Error::other)?;
        file.flush().await.map_err(io::Error::other)?;
        drop(file);
        // A file the download replaces keeps its mode (the second review, S3). Unix only: Windows'
        // one permission is read-only, which would make the partial undeletable while a rename
        // onto a read-only file fails there anyway.
        #[cfg(unix)]
        if let Ok(existing) = tokio::fs::metadata(path).await {
            tokio::fs::set_permissions(&partial, existing.permissions())
                .await
                .map_err(at_path)?;
        }
        tokio::fs::rename(&partial, path).await.map_err(at_path)
    }
    .await;
    if written.is_err() {
        // The partial file is ours and holds nothing the user asked for; the download's own error
        // is the one reported.
        let _ = tokio::fs::remove_file(&partial).await;
    }
    written
}

/// Where a download into `path` is written until it is whole: a hidden file beside it, in the same
/// folder, so the rename that puts it in place never crosses a file system.
fn partial_beside(path: &Path) -> std::path::PathBuf {
    let name = path.file_name().unwrap_or_default().to_string_lossy();
    path.with_file_name(format!(".{name}.part"))
}
