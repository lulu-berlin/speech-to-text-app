//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself. It shows the queue as the service's event stream reports it, and
//! uploads a file by the path the user types.

use std::io;
use std::path::Path;
use std::process::ExitCode;
use std::sync::mpsc;
use std::time::Duration;

use ratatui::DefaultTerminal;
use ratatui::crossterm::event::{self as terminal_event, KeyCode, KeyEventKind, KeyModifiers};
use stt_client::{Event, Service, ServiceDirs, Snapshot, events, locate, upload};

mod state;
mod view;

/// What the terminal loop wakes up for: an event from the service, something the user did, or the
/// outcome of an upload this TUI started.
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

/// Draws the queue, again after every event, until the user quits. `u` opens the upload prompt and
/// Enter sends the path it holds; `q`, Escape (with no prompt open) and Ctrl-C quit.
fn show(
    terminal: &mut DefaultTerminal,
    messages: &mpsc::Receiver<Message>,
    runtime: tokio::runtime::Handle,
    service: Service,
    sender: mpsc::Sender<Message>,
) -> io::Result<()> {
    let mut snapshot: Option<Snapshot> = None;
    let mut prompt: Option<String> = None;
    let mut notice: Option<String> = None;
    draw(terminal, &snapshot, &prompt, &notice)?;
    while let Ok(message) = messages.recv() {
        match message {
            Message::Service(event) => state::apply(&mut snapshot, event),
            Message::Notice(text) => notice = Some(text),
            Message::Terminal(terminal_event::Event::Key(key))
                if key.kind == KeyEventKind::Press =>
            {
                // Raw mode turns Ctrl-C into a key, so it is read here like any other.
                if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
                    return Ok(());
                }
                // With the prompt open, Escape cancels it and every other key is text; `q` there is
                // a letter of a file name. Without it, `u` opens the prompt and `q` or Escape quit.
                match (prompt.is_some(), key.code) {
                    (true, KeyCode::Esc) => prompt = None,
                    (false, KeyCode::Esc | KeyCode::Char('q')) => return Ok(()),
                    (false, KeyCode::Char('u')) => prompt = Some(String::new()),
                    (true, KeyCode::Backspace) => {
                        if let Some(path) = prompt.as_mut() {
                            path.pop();
                        }
                    }
                    (true, KeyCode::Enter) => {
                        let path = prompt.take().unwrap_or_default().trim().to_string();
                        if path.is_empty() {
                            notice = Some("no file path given".to_string());
                        } else {
                            let service = service.clone();
                            let sender = sender.clone();
                            runtime.spawn(async move {
                                let text = match upload(&service, Path::new(&path)).await {
                                    Ok(task) => format!("queued {}", task.filename),
                                    Err(error) => format!("upload failed: {error}"),
                                };
                                let _ = sender.send(Message::Notice(text));
                            });
                        }
                    }
                    (true, KeyCode::Char(character)) => {
                        if let Some(path) = prompt.as_mut() {
                            path.push(character);
                        }
                    }
                    _ => {}
                }
            }
            Message::Terminal(_) => {}
        }
        draw(terminal, &snapshot, &prompt, &notice)?;
    }
    Ok(())
}

/// Draws the screen as it is now: the queue, the prompt if it is open, the last notice.
fn draw(
    terminal: &mut DefaultTerminal,
    snapshot: &Option<Snapshot>,
    prompt: &Option<String>,
    notice: &Option<String>,
) -> io::Result<()> {
    let view = view::View {
        snapshot: snapshot.as_ref(),
        prompt: prompt.as_deref(),
        notice: notice.as_deref(),
    };
    terminal.draw(|frame| view::draw(frame, &view))?;
    Ok(())
}
