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
    set_queue_paused, task_command, text, turns, upload,
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

/// Draws the open screen, again after every event, until the user quits, applying the keys
/// [`input`] maps: `h` opens the history, Enter opens the selected transcript, `b`/Escape go back,
/// `u` uploads by path, the queue's keys command it, and `q` or Ctrl-C quit.
fn show(
    terminal: &mut DefaultTerminal,
    messages: &mpsc::Receiver<Message>,
    runtime: tokio::runtime::Handle,
    service: Service,
    sender: mpsc::Sender<Message>,
) -> io::Result<()> {
    let mut snapshot: Option<Snapshot> = None;
    let mut screen = Screen::Queue;
    let mut selected = 0;
    let mut listed: Vec<Task> = Vec::new();
    let mut history_selected = 0;
    let mut prompt: Option<String> = None;
    let mut notice: Option<String> = None;
    draw(
        terminal,
        &snapshot,
        &screen,
        selected,
        &listed,
        history_selected,
        &prompt,
        &notice,
    )?;
    while let Ok(message) = messages.recv() {
        match message {
            Message::Service(event) => {
                state::apply(&mut snapshot, event);
                let rows = state::rows(snapshot.as_ref()).len();
                if rows > 0 {
                    selected = selected.min(rows - 1);
                }
            }
            Message::Notice(line) => notice = Some(line),
            Message::History(tasks) => {
                listed = tasks;
                history_selected = 0;
                screen = Screen::History;
            }
            Message::Transcript(task, turns) => {
                screen = Screen::Transcript { task, turns };
            }
            Message::Terminal(terminal_event::Event::Key(key)) => {
                let Some(action) = input::action(&key, prompt.is_some()) else {
                    continue;
                };
                if matches!(screen, Screen::Queue) {
                    match action {
                        Action::Quit => return Ok(()),
                        Action::BackOrQuit => {
                            if matches!(screen, Screen::Queue) {
                                return Ok(());
                            }
                        }
                        Action::Back => {}
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
                                notice = Some(text("upload.no-path", &[]));
                            } else {
                                let service = service.clone();
                                dispatch(&runtime, &sender, "upload.failed", async move {
                                    upload(&service, Path::new(&path)).await.map(|task| {
                                        text("upload.queued", &[("filename", &task.filename)])
                                    })
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
                            dispatch(&runtime, &sender, "command.failed", async move {
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
                            if let Some(task) = task_at(&snapshot, selected)
                                && let Some(command) = input::command_for(task, character)
                            {
                                let id = task.id;
                                let service = service.clone();
                                dispatch(&runtime, &sender, "command.failed", async move {
                                    task_command(&service, id, command).await.map(|task| {
                                        text(
                                            "task.command-done",
                                            &[
                                                ("filename", &task.filename),
                                                ("status", &task.status),
                                            ],
                                        )
                                    })
                                });
                            }
                        }
                        Action::OpenHistory => {
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
                        Action::OpenSelected => {}
                    }
                } else {
                    // Every other screen: `q` quits, Escape or `b` goes back, and the history opens a
                    // transcript; the queue's own keys do nothing here.
                    match action {
                        Action::Quit => return Ok(()),
                        Action::BackOrQuit | Action::Back => {
                            screen = match screen {
                                Screen::Transcript { .. } => Screen::History,
                                _ => Screen::Queue,
                            };
                        }
                        Action::OpenHistory => {
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
                        Action::SelectUp if matches!(screen, Screen::History) => {
                            history_selected = history_selected.saturating_sub(1);
                        }
                        Action::SelectDown if matches!(screen, Screen::History) => {
                            let rows = state::completed(&listed).len();
                            if history_selected + 1 < rows {
                                history_selected += 1;
                            }
                        }
                        Action::OpenSelected if matches!(screen, Screen::History) => {
                            let task = state::completed(&listed)
                                .get(history_selected)
                                .map(|task| (*task).clone());
                            if let Some(task) = task {
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
                        }
                        _ => {}
                    }
                }
            }
            Message::Terminal(_) => {}
        }
        draw(
            terminal,
            &snapshot,
            &screen,
            selected,
            &listed,
            history_selected,
            &prompt,
            &notice,
        )?;
    }
    Ok(())
}

/// The task on the queue's row `selected`, if any.
fn task_at(snapshot: &Option<Snapshot>, selected: usize) -> Option<&Task> {
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

/// Draws the screen as it is now.
#[allow(clippy::too_many_arguments)]
fn draw(
    terminal: &mut DefaultTerminal,
    snapshot: &Option<Snapshot>,
    screen: &Screen,
    selected: usize,
    history: &[Task],
    history_selected: usize,
    prompt: &Option<String>,
    notice: &Option<String>,
) -> io::Result<()> {
    let view = view::View {
        snapshot: snapshot.as_ref(),
        screen,
        selected,
        history,
        history_selected,
        prompt: prompt.as_deref(),
        notice: notice.as_deref(),
    };
    terminal.draw(|frame| view::draw(frame, &view))?;
    Ok(())
}
