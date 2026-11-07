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
    Event, Frontend, Service, ServiceDirs, Settings, Snapshot, Speaker, Task, Turns, events,
    history, locate, reorder_queue, set_queue_paused, task_command, text, turns, upload,
};

use crate::download::Download;
use crate::input::Action;
use crate::settings::SettingsScreen;
use crate::state::Screen;
use crate::transcript::Transcript;

mod download;
mod input;
mod settings;
mod state;
mod transcript;
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
    /// Task `id`'s speakers as the service answered a rename or a merge; an open transcript of it
    /// shows them.
    Speakers(i64, Vec<Speaker>),
    /// Task `id`'s turns, read again after a merge; an open transcript of it shows them.
    Turns(i64, Turns),
    /// The settings and the installed frontends arrived; the settings screen shows them.
    Settings(Settings, Vec<Frontend>),
    /// The settings as the service answered a save; an open settings screen shows them.
    SettingsSaved(Settings),
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

/// How long a stream may stay silent before it counts as dropped: the service sends at least a
/// heartbeat every 5 s (ADR 0044), so 15 s without any event means the connection is gone even if
/// the socket says otherwise - as the browser decides.
const SILENCE: Duration = Duration::from_secs(15);

/// Reads the service's event stream and hands every event to the terminal. A stream that drops,
/// stays silent for [`SILENCE`], or cannot be opened is opened again after [`RECONNECT`]; its first
/// event, a fresh snapshot, brings the view up to date with whatever happened meanwhile.
async fn follow(service: Service, sender: mpsc::Sender<Message>) {
    loop {
        if let Ok(mut stream) = events(&service).await {
            while let Ok(Ok(Some(event))) = tokio::time::timeout(SILENCE, stream.next()).await {
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
    /// Whether `NO_COLOR` is set: then the selected row is marked, and nothing is coloured.
    no_color: bool,
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
            no_color: std::env::var_os("NO_COLOR").is_some(),
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
            rename: match &self.screen {
                Screen::Transcript(transcript) => transcript.rename(),
                _ => None,
            },
            terminal: match &self.screen {
                Screen::Settings(screen) => screen.terminal(),
                _ => None,
            },
            download: self.download.as_ref().map(Download::view),
            notice: self.notice.as_deref(),
            no_color: self.no_color,
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
                self.screen = Screen::Transcript(Transcript::new(task, turns));
            }
            Message::Speakers(id, speakers) => {
                if let Screen::Transcript(transcript) = &mut self.screen {
                    transcript.set_speakers(id, speakers);
                }
            }
            Message::Turns(id, turns) => {
                if let Screen::Transcript(transcript) = &mut self.screen {
                    transcript.set_turns(id, turns);
                }
            }
            Message::Settings(settings, frontends) => {
                self.screen = Screen::Settings(SettingsScreen::new(settings, frontends));
            }
            Message::SettingsSaved(settings) => {
                if let Screen::Settings(screen) = &mut self.screen {
                    screen.saved(settings);
                }
            }
            Message::Terminal(terminal_event::Event::Key(key)) => {
                // The rename prompt takes text as the upload prompt does: every key but Escape,
                // Enter and Backspace is a character of the name.
                let renaming = matches!(&self.screen, Screen::Transcript(transcript) if transcript.rename().is_some());
                let terminal =
                    matches!(&self.screen, Screen::Settings(screen) if screen.terminal().is_some());
                let text_open = self.prompt.is_some() || renaming || terminal;
                let Some(action) = input::action(&key, text_open, self.download.is_some()) else {
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
            | Action::CancelDownload
            | Action::Merge
            | Action::Adjust(_) => {}
            Action::OpenHistory => open_history(runtime, service, sender),
            Action::OpenSettings => open_settings(runtime, service, sender),
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

    /// Every other screen: quit, go back, open the history or the selected transcript, and in a
    /// transcript select a speaker and rename it.
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
                    Screen::Transcript(_) => Screen::History,
                    _ => Screen::Queue,
                };
            }
            Action::OpenHistory => open_history(runtime, service, sender),
            Action::OpenSettings => open_settings(runtime, service, sender),
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
                if let Screen::Transcript(transcript) = &self.screen {
                    self.download = Some(Download::new(transcript.task.clone()));
                }
            }
            Action::DownloadChar(_)
            | Action::DownloadBackspace
            | Action::DownloadTab
            | Action::DownloadFormatStep(_)
            | Action::CancelDownload
            | Action::SubmitDownload => {
                if let Some(download) = self.download.as_mut()
                    && download.handle(action, runtime, sender, service)
                {
                    self.download = None;
                }
            }
            action => match &mut self.screen {
                Screen::Transcript(transcript) => {
                    transcript.handle(action, runtime, sender, service);
                }
                Screen::Settings(screen) => screen.handle(action, runtime, sender, service),
                Screen::Queue | Screen::History => {}
            },
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
    fetch(
        runtime,
        sender,
        "history.failed",
        async move { history(&service).await },
        Message::History,
    );
}

/// Fetches the settings and the installed frontends and shows them.
fn open_settings(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
) {
    let service = service.clone();
    fetch(
        runtime,
        sender,
        "settings.failed",
        async move {
            let settings = stt_client::settings(&service).await?;
            let frontends = stt_client::frontends(&service).await?;
            Ok((settings, frontends))
        },
        |(settings, frontends)| Message::Settings(settings, frontends),
    );
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
    fetch(
        runtime,
        sender,
        "transcript.failed",
        async move { turns(&service, id).await },
        move |turns| Message::Transcript(task, turns),
    );
}

/// Runs `request` off the terminal thread and hands its value to `arrived`; a failure is the
/// notice `failed` names, with the service's message. The terminal keeps drawing meanwhile.
fn fetch<T: Send + 'static>(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    failed: &'static str,
    request: impl std::future::Future<Output = io::Result<T>> + Send + 'static,
    arrived: impl FnOnce(T) -> Message + Send + 'static,
) {
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match request.await {
            Ok(value) => arrived(value),
            Err(error) => Message::Notice(text(failed, &[("error", &error.to_string())])),
        };
        let _ = sender.send(message);
    });
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
