//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself. It shows the queue as the service's event stream reports it, uploads
//! a file by the path the user types, and sends the queue's commands.

use std::future::Future;
use std::io;
use std::path::Path;
use std::process::ExitCode;
use std::sync::mpsc;
use std::time::Duration;

use ratatui::DefaultTerminal;
use ratatui::crossterm::event as terminal_event;
use stt_client::{
    Event, Service, ServiceDirs, Snapshot, events, locate, reorder_queue, set_queue_paused,
    task_command, upload,
};

use crate::input::Action;

mod input;
mod state;
mod view;

/// What the terminal loop wakes up for: an event from the service, something the user did, or the
/// outcome of a request this TUI made.
enum Message {
    Service(Event),
    Terminal(terminal_event::Event),
    Notice(String),
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

/// Draws the queue, again after every event, until the user quits, applying the keys [`input`]
/// maps: `u` opens the upload prompt, Up/Down select, `p`/`c`/`r` command the selected task,
/// `J`/`K` move it, Space pauses the queue, and `q`, Escape (with no prompt) and Ctrl-C quit.
fn show(
    terminal: &mut DefaultTerminal,
    messages: &mpsc::Receiver<Message>,
    runtime: tokio::runtime::Handle,
    service: Service,
    sender: mpsc::Sender<Message>,
) -> io::Result<()> {
    let mut snapshot: Option<Snapshot> = None;
    let mut selected = 0;
    let mut prompt: Option<String> = None;
    let mut notice: Option<String> = None;
    draw(terminal, &snapshot, selected, &prompt, &notice)?;
    while let Ok(message) = messages.recv() {
        match message {
            Message::Service(event) => {
                state::apply(&mut snapshot, event);
                let rows = state::rows(snapshot.as_ref()).len();
                if rows > 0 {
                    selected = selected.min(rows - 1);
                }
            }
            Message::Notice(text) => notice = Some(text),
            Message::Terminal(terminal_event::Event::Key(key)) => {
                let Some(action) = input::action(&key, prompt.is_some()) else {
                    continue;
                };
                match action {
                    Action::Quit => return Ok(()),
                    Action::OpenPrompt => prompt = Some(String::new()),
                    Action::CancelPrompt => prompt = None,
                    Action::PromptBackspace => {
                        if let Some(path) = prompt.as_mut() {
                            path.pop();
                        }
                    }
                    Action::PromptChar(character) => {
                        if let Some(path) = prompt.as_mut() {
                            path.push(character);
                        }
                    }
                    Action::SubmitPrompt => {
                        let path = prompt.take().unwrap_or_default().trim().to_string();
                        if path.is_empty() {
                            notice = Some("no file path given".to_string());
                        } else {
                            let service = service.clone();
                            dispatch(&runtime, &sender, "upload failed", async move {
                                upload(&service, Path::new(&path))
                                    .await
                                    .map(|task| format!("queued {}", task.filename))
                            });
                        }
                    }
                    Action::SelectUp => selected = selected.saturating_sub(1),
                    Action::SelectDown => {
                        let rows = state::rows(snapshot.as_ref()).len();
                        if selected + 1 < rows {
                            selected += 1;
                        }
                    }
                    Action::MoveSelected(step) => {
                        move_selected(&runtime, &sender, &service, &snapshot, selected, step)
                    }
                    Action::ToggleQueuePause => {
                        let paused = snapshot.as_ref().is_some_and(|s| s.queue.paused);
                        let service = service.clone();
                        dispatch(&runtime, &sender, "command failed", async move {
                            set_queue_paused(&service, !paused).await.map(|queue| {
                                if queue.paused {
                                    "queue paused".to_string()
                                } else {
                                    "queue running".to_string()
                                }
                            })
                        });
                    }
                    Action::TaskKey(character) => {
                        if let Some(task) = task_at(&snapshot, selected)
                            && let Some(command) = input::command_for(task, character)
                        {
                            let id = task.id;
                            let service = service.clone();
                            dispatch(&runtime, &sender, "command failed", async move {
                                task_command(&service, id, command)
                                    .await
                                    .map(|task| format!("{} {}", task.filename, task.status))
                            });
                        }
                    }
                }
            }
            Message::Terminal(_) => {}
        }
        draw(terminal, &snapshot, selected, &prompt, &notice)?;
    }
    Ok(())
}

/// The task on the row `selected`, if any.
fn task_at(snapshot: &Option<Snapshot>, selected: usize) -> Option<&stt_client::Task> {
    state::rows(snapshot.as_ref()).get(selected).copied()
}

/// Moves the selected task `step` places through the queue, if it is in the queue and has a
/// neighbour there; the service's `queue-updated` then shows the new order.
fn move_selected(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    service: &Service,
    snapshot: &Option<Snapshot>,
    selected: usize,
    step: isize,
) {
    let Some(task) = task_at(snapshot, selected) else {
        return;
    };
    let Some(queue) = snapshot.as_ref().map(|snapshot| &snapshot.queue) else {
        return;
    };
    let Some(from) = queue.task_ids.iter().position(|id| *id == task.id) else {
        return;
    };
    let Some(to) = from.checked_add_signed(step) else {
        return;
    };
    if to >= queue.task_ids.len() {
        return;
    }
    let mut ids = queue.task_ids.clone();
    ids.swap(from, to);
    let service = service.clone();
    dispatch(runtime, sender, "command failed", async move {
        reorder_queue(&service, &ids)
            .await
            .map(|_| "queue reordered".to_string())
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
        let text = match future.await {
            Ok(text) => text,
            Err(error) => format!("{failure}: {error}"),
        };
        let _ = sender.send(Message::Notice(text));
    });
}

/// Draws the screen as it is now: the queue with its selection, the prompt if it is open, the last
/// notice.
fn draw(
    terminal: &mut DefaultTerminal,
    snapshot: &Option<Snapshot>,
    selected: usize,
    prompt: &Option<String>,
    notice: &Option<String>,
) -> io::Result<()> {
    let view = view::View {
        snapshot: snapshot.as_ref(),
        selected,
        prompt: prompt.as_deref(),
        notice: notice.as_deref(),
    };
    terminal.draw(|frame| view::draw(frame, &view))?;
    Ok(())
}
