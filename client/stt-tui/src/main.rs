//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself. It shows the queue as the service's event stream reports it, uploads
//! a file by the path the user types, sends the queue's commands, and opens the history of
//! completed tasks and their transcripts.

use std::future::Future;
use std::io;
use std::path::Path;
use std::process::ExitCode;
use std::sync::mpsc;
use std::time::Duration;

use ratatui::DefaultTerminal;
use ratatui::crossterm::event as terminal_event;
use stt_client::{
    Event, Service, ServiceDirs, Snapshot, Task, Turns, events, history, locate, reorder_queue,
    set_queue_paused, task_command, text, transcript, turns, upload,
};

use crate::input::Action;
use crate::state::Screen;

mod input;
mod state;
mod view;

/// What the terminal loop wakes up for: an event from the service, something the user did, or the
/// outcome of a request this TUI made.
enum Message {
    Service(Event),
    Terminal(terminal_event::Event),
    Notice(String),
    /// The task list arrived; the history screen shows it.
    History(Vec<Task>),
    /// A transcript arrived; the transcript screen shows it.
    Transcript(Task, Turns),
}

/// Whether the user asked to leave.
enum Outcome {
    Continue,
    Quit,
}

fn main() -> ExitCode {
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            eprintln!("stt-tui: {error}");
            return ExitCode::FAILURE;
        }
    };
    let dirs = match ServiceDirs::from_env() {
        Ok(dirs) => dirs,
        Err(error) => {
            eprintln!("stt-tui: {error}");
            return ExitCode::FAILURE;
        }
    };
    let service = match runtime.block_on(locate(&dirs)) {
        Ok(service) => service,
        Err(error) => {
            eprintln!(
                "stt-tui: the service is not running - start it with `stt-app open` ({error})"
            );
            return ExitCode::FAILURE;
        }
    };
    // The network runs on the runtime's threads and the keyboard is read on a thread of its own;
    // the terminal is drawn on this one, from what both hand over.
    let (sender, received) = mpsc::channel();
    runtime.spawn(follow(service.clone(), sender.clone()));
    let keys = sender.clone();
    let mut terminal = ratatui::init();
    std::thread::spawn(move || {
        while let Ok(event) = terminal_event::read() {
            if keys.send(Message::Terminal(event)).is_err() {
                return;
            }
        }
    });
    let shown = show(
        &mut terminal,
        &received,
        runtime.handle().clone(),
        service,
        sender,
    );
    ratatui::restore();
    match shown {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("stt-tui: {error}");
            ExitCode::FAILURE
        }
    }
}

/// How long the TUI waits before it opens a new event stream after one dropped: the browser's
/// reconnect delay.
const RECONNECT: Duration = Duration::from_secs(1);

/// Reads the service's event stream and hands every event to the terminal. A stream that drops (or
/// cannot be opened) is opened again after [`RECONNECT`]; its first event, a fresh snapshot, brings
/// the view up to date with whatever happened meanwhile.
async fn follow(service: Service, sender: mpsc::Sender<Message>) {
    loop {
        if let Ok(mut stream) = events(&service).await {
            while let Ok(Some(event)) = stream.next().await {
                if sender.send(Message::Service(event)).is_err() {
                    return;
                }
            }
        }
        tokio::time::sleep(RECONNECT).await;
    }
}

/// Everything the terminal shows and remembers.
struct Ui {
    snapshot: Option<Snapshot>,
    screen: Screen,
    /// The queue's selected row.
    selected: usize,
    /// The task list the history shows.
    listed: Vec<Task>,
    /// The history's selected row.
    history_selected: usize,
    /// The upload prompt's path, while it is open.
    prompt: Option<String>,
    /// The download dialog, while it is open.
    download: Option<Download>,
    /// What the last action reported.
    notice: Option<String>,
}

/// The download dialog: the transcript's task, the path being typed, the format, and which field has
/// the focus.
struct Download {
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

impl Ui {
    fn new() -> Ui {
        Ui {
            snapshot: None,
            screen: Screen::Queue,
            selected: 0,
            listed: Vec::new(),
            history_selected: 0,
            prompt: None,
            download: None,
            notice: None,
        }
    }

    fn draw(&self, terminal: &mut DefaultTerminal) -> io::Result<()> {
        let view = view::View {
            snapshot: self.snapshot.as_ref(),
            screen: &self.screen,
            selected: self.selected,
            history: &self.listed,
            history_selected: self.history_selected,
            prompt: self.prompt.as_deref(),
            download: self.download.as_ref().map(|download| view::DownloadView {
                filename: &download.task.filename,
                path: &download.path,
                format: download.format.as_str(),
                focus_format: download.focus == Focus::Format,
            }),
            notice: self.notice.as_deref(),
        };
        terminal.draw(|frame| view::draw(frame, &view))?;
        Ok(())
    }

    /// Applies one message; `Outcome::Quit` when the user asked to leave.
    fn handle(
        &mut self,
        message: Message,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        match message {
            Message::Service(event) => {
                state::apply(&mut self.snapshot, event);
                let rows = state::rows(self.snapshot.as_ref()).len();
                if rows > 0 {
                    self.selected = self.selected.min(rows - 1);
                }
            }
            Message::Notice(line) => self.notice = Some(line),
            Message::History(tasks) => {
                self.listed = tasks;
                self.history_selected = 0;
                self.screen = Screen::History;
            }
            Message::Transcript(task, turns) => {
                self.screen = Screen::Transcript { task, turns };
            }
            Message::Terminal(terminal_event::Event::Key(key)) => {
                let Some(action) =
                    input::action(&key, self.prompt.is_some(), self.download.is_some())
                else {
                    return Outcome::Continue;
                };
                return match self.screen {
                    Screen::Queue => self.queue_action(action, runtime, service, sender),
                    _ => self.other_action(action, runtime, service, sender),
                };
            }
            Message::Terminal(_) => {}
        }
        Outcome::Continue
    }

    /// The queue's keys: its commands, selection and reordering, and the upload prompt.
    fn queue_action(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        match action {
            Action::Quit | Action::BackOrQuit => return Outcome::Quit,
            Action::Back | Action::OpenSelected => {}
            Action::OpenDownload
            | Action::DownloadChar(_)
            | Action::DownloadBackspace
            | Action::DownloadTab
            | Action::DownloadFormatStep(_)
            | Action::SubmitDownload
            | Action::CancelDownload => {}
            Action::OpenHistory => open_history(runtime, service, sender),
            Action::OpenPrompt => self.prompt = Some(String::new()),
            Action::CancelPrompt => self.prompt = None,
            Action::PromptBackspace => {
                if let Some(path) = self.prompt.as_mut() {
                    path.pop();
                }
            }
            Action::PromptChar(character) => {
                if let Some(path) = self.prompt.as_mut() {
                    path.push(character);
                }
            }
            Action::SubmitPrompt => {
                let path = self.prompt.take().unwrap_or_default().trim().to_string();
                if path.is_empty() {
                    self.notice = Some(text("upload.no-path", &[]));
                } else {
                    let service = service.clone();
                    dispatch(runtime, sender, "upload.failed", async move {
                        upload(&service, Path::new(&path))
                            .await
                            .map(|task| text("upload.queued", &[("filename", &task.filename)]))
                    });
                }
            }
            Action::SelectUp => self.selected = self.selected.saturating_sub(1),
            Action::SelectDown => {
                let rows = state::rows(self.snapshot.as_ref()).len();
                if self.selected + 1 < rows {
                    self.selected += 1;
                }
            }
            Action::MoveSelected(step) => {
                move_selected(runtime, sender, service, self, step);
            }
            Action::ToggleQueuePause => {
                let paused = self.snapshot.as_ref().is_some_and(|s| s.queue.paused);
                let service = service.clone();
                dispatch(runtime, sender, "command.failed", async move {
                    set_queue_paused(&service, !paused).await.map(|queue| {
                        text(
                            if queue.paused {
                                "queue.paused"
                            } else {
                                "queue.running"
                            },
                            &[],
                        )
                    })
                });
            }
            Action::TaskKey(character) => {
                if let Some(task) = task_at(self.snapshot.as_ref(), self.selected)
                    && let Some(command) = input::command_for(task, character)
                {
                    let id = task.id;
                    let service = service.clone();
                    dispatch(runtime, sender, "command.failed", async move {
                        task_command(&service, id, command).await.map(|task| {
                            text(
                                "task.command-done",
                                &[("filename", &task.filename), ("status", &task.status)],
                            )
                        })
                    });
                }
            }
        }
        Outcome::Continue
    }

    /// Every other screen: quit, go back, open the history or the selected transcript.
    fn other_action(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        match action {
            Action::Quit => return Outcome::Quit,
            Action::BackOrQuit | Action::Back => {
                self.screen = match self.screen {
                    Screen::Transcript { .. } => Screen::History,
                    _ => Screen::Queue,
                };
            }
            Action::OpenHistory => open_history(runtime, service, sender),
            Action::SelectUp if matches!(self.screen, Screen::History) => {
                self.history_selected = self.history_selected.saturating_sub(1);
            }
            Action::SelectDown if matches!(self.screen, Screen::History) => {
                let rows = state::completed(&self.listed).len();
                if self.history_selected + 1 < rows {
                    self.history_selected += 1;
                }
            }
            Action::OpenSelected if matches!(self.screen, Screen::History) => {
                if let Some(task) = state::completed(&self.listed)
                    .get(self.history_selected)
                    .map(|task| (*task).clone())
                {
                    open_transcript(runtime, service, sender, task);
                }
            }
            Action::OpenDownload => {
                if let Screen::Transcript { task, .. } = &self.screen {
                    self.download = Some(Download {
                        task: task.clone(),
                        path: String::new(),
                        format: Format::Txt,
                        focus: Focus::Path,
                    });
                }
            }
            Action::DownloadChar(character) => {
                if let Some(download) = self.download.as_mut() {
                    download.path.push(character);
                    if let Some(format) = Format::from_path(&download.path) {
                        download.format = format;
                    }
                }
            }
            Action::DownloadBackspace => {
                if let Some(download) = self.download.as_mut() {
                    download.path.pop();
                    if let Some(format) = Format::from_path(&download.path) {
                        download.format = format;
                    }
                }
            }
            Action::DownloadTab => {
                if let Some(download) = self.download.as_mut() {
                    download.focus = match download.focus {
                        Focus::Path => Focus::Format,
                        Focus::Format => Focus::Path,
                    };
                }
            }
            Action::DownloadFormatStep(step) => {
                if let Some(download) = self.download.as_mut()
                    && download.focus == Focus::Format
                {
                    download.format = download.format.cycle(step);
                }
            }
            Action::CancelDownload => self.download = None,
            Action::SubmitDownload => submit_download(runtime, sender, service, self),
            _ => {}
        }
        Outcome::Continue
    }
}

/// Draws the open screen, again after every event, until the user quits.
fn show(
    terminal: &mut DefaultTerminal,
    messages: &mpsc::Receiver<Message>,
    runtime: tokio::runtime::Handle,
    service: Service,
    sender: mpsc::Sender<Message>,
) -> io::Result<()> {
    let mut ui = Ui::new();
    ui.draw(terminal)?;
    while let Ok(message) = messages.recv() {
        if matches!(
            ui.handle(message, &runtime, &service, &sender),
            Outcome::Quit
        ) {
            return Ok(());
        }
        ui.draw(terminal)?;
    }
    Ok(())
}

/// Fetches the task list and shows the history.
fn open_history(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
) {
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        match history(&service).await {
            Ok(tasks) => {
                let _ = sender.send(Message::History(tasks));
            }
            Err(error) => {
                let _ = sender.send(Message::Notice(text(
                    "history.failed",
                    &[("error", &error.to_string())],
                )));
            }
        }
    });
}

/// Fetches `task`'s turns and shows the transcript.
fn open_transcript(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    task: Task,
) {
    let id = task.id;
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        match turns(&service, id).await {
            Ok(turns) => {
                let _ = sender.send(Message::Transcript(task, turns));
            }
            Err(error) => {
                let _ = sender.send(Message::Notice(text(
                    "transcript.failed",
                    &[("error", &error.to_string())],
                )));
            }
        }
    });
}

/// Saves the dialog's transcript to its path: the destination is opened first, so a path that cannot
/// be written is reported before anything is fetched, and the body goes into that file.
fn submit_download(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    service: &Service,
    ui: &mut Ui,
) {
    let Some(download) = ui.download.take() else {
        return;
    };
    let path = download.path.trim().to_string();
    if path.is_empty() {
        ui.notice = Some(text("download.no-path", &[]));
        return;
    }
    let id = download.task.id;
    let format = download.format.as_str();
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

/// The task on the queue's row `selected`, if any.
fn task_at(snapshot: Option<&Snapshot>, selected: usize) -> Option<&Task> {
    state::rows(snapshot).get(selected).copied()
}

/// Moves the selected task `step` places through the queue, if it is in the queue and has a
/// neighbour there; the service's `queue-updated` then shows the new order.
fn move_selected(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    service: &Service,
    ui: &Ui,
    step: isize,
) {
    let Some(task) = task_at(ui.snapshot.as_ref(), ui.selected) else {
        return;
    };
    let Some(snapshot) = ui.snapshot.as_ref() else {
        return;
    };
    let Some(from) = snapshot.queue.task_ids.iter().position(|id| *id == task.id) else {
        return;
    };
    let Some(to) = from.checked_add_signed(step) else {
        return;
    };
    if to >= snapshot.queue.task_ids.len() {
        return;
    }
    let mut ids = snapshot.queue.task_ids.clone();
    ids.swap(from, to);
    let service = service.clone();
    dispatch(runtime, sender, "command.failed", async move {
        reorder_queue(&service, &ids)
            .await
            .map(|_| text("queue.reordered", &[]))
    });
}

/// Runs `future` on the runtime and reports its outcome as the notice: the task or queue it
/// returned, or `failure` with the error.
fn dispatch<F>(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    failure: &'static str,
    future: F,
) where
    F: Future<Output = io::Result<String>> + Send + 'static,
{
    let sender = sender.clone();
    runtime.spawn(async move {
        let notice = match future.await {
            Ok(notice) => notice,
            Err(error) => text(failure, &[("error", &error.to_string())]),
        };
        let _ = sender.send(Message::Notice(notice));
    });
}
