//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself. It shows the queue as the service's event stream reports it.

use std::io;
use std::process::ExitCode;
use std::sync::mpsc;
use std::time::Duration;

use ratatui::crossterm::event::{
    self as terminal_event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers,
};
use ratatui::layout::Constraint;
use ratatui::widgets::{Block, Row, Table};
use ratatui::{DefaultTerminal, Frame};
use stt_client::{Event, Service, ServiceDirs, Snapshot, events, locate};

/// What the terminal loop wakes up for: an event from the service, or something the user did.
enum Message {
    Service(Event),
    Terminal(terminal_event::Event),
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
    runtime.spawn(follow(service, sender.clone()));
    let mut terminal = ratatui::init();
    std::thread::spawn(move || {
        while let Ok(event) = terminal_event::read() {
            if sender.send(Message::Terminal(event)).is_err() {
                return;
            }
        }
    });
    let shown = show(&mut terminal, &received);
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

/// Draws the queue, again after every event, until the user quits.
fn show(terminal: &mut DefaultTerminal, messages: &mpsc::Receiver<Message>) -> io::Result<()> {
    let mut snapshot: Option<Snapshot> = None;
    terminal.draw(|frame| draw(frame, snapshot.as_ref()))?;
    while let Ok(message) = messages.recv() {
        match message {
            Message::Service(event) => apply(&mut snapshot, event),
            Message::Terminal(terminal_event::Event::Key(key)) if quits(&key) => return Ok(()),
            Message::Terminal(_) => {}
        }
        terminal.draw(|frame| draw(frame, snapshot.as_ref()))?;
    }
    Ok(())
}

/// The service's state after `event`. Every stream starts with a snapshot, so a change before one
/// has nothing to apply to.
fn apply(state: &mut Option<Snapshot>, event: Event) {
    if let Event::Snapshot(latest) = event {
        *state = Some(latest);
        return;
    }
    let Some(state) = state else {
        return;
    };
    match event {
        Event::TaskUpdated(task) => {
            match state.tasks.iter_mut().find(|known| known.id == task.id) {
                Some(known) => *known = task,
                None => state.tasks.push(task),
            }
        }
        Event::TaskProgress(progress) => {
            if let Some(task) = state.tasks.iter_mut().find(|task| task.id == progress.id) {
                task.transcription_progress = progress.transcription_progress;
            }
        }
        Event::QueueUpdated(queue) => state.queue = queue,
        Event::Snapshot(_) | Event::Other(_) => {}
    }
}

/// `q`, Escape and Ctrl-C quit: the user is never trapped (raw mode turns Ctrl-C into a key).
fn quits(key: &KeyEvent) -> bool {
    key.kind == KeyEventKind::Press
        && match key.code {
            KeyCode::Char('q') | KeyCode::Esc => true,
            KeyCode::Char('c') => key.modifiers.contains(KeyModifiers::CONTROL),
            _ => false,
        }
}

/// The queue in its order: one row per task with its file name, status and transcription progress.
fn draw(frame: &mut Frame, snapshot: Option<&Snapshot>) {
    let rows = snapshot
        .into_iter()
        .flat_map(|snapshot| {
            snapshot
                .queue
                .task_ids
                .iter()
                .filter_map(|id| snapshot.tasks.iter().find(|task| task.id == *id))
        })
        .map(|task| {
            Row::new([
                task.filename.clone(),
                task.status.clone(),
                format!("{}%", (task.transcription_progress * 100.0).round()),
            ])
        });
    let table = Table::new(
        rows,
        [
            Constraint::Fill(1),
            Constraint::Length(10),
            Constraint::Length(5),
        ],
    )
    .block(Block::bordered().title("Queue"));
    frame.render_widget(table, frame.area());
}
