//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself. It shows the queue as the service's event stream reports it.

use std::io;
use std::process::ExitCode;
use std::sync::mpsc;

use ratatui::layout::Constraint;
use ratatui::widgets::{Block, Row, Table};
use ratatui::{DefaultTerminal, Frame};
use stt_client::{Event, Service, ServiceDirs, Snapshot, events, locate};

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
    // The network runs on the runtime's threads; the terminal is drawn on this one, from the events
    // the stream hands over.
    let (sender, received) = mpsc::channel();
    runtime.spawn(follow(service, sender));
    let mut terminal = ratatui::init();
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

/// Reads the service's event stream and hands every event to the terminal.
async fn follow(service: Service, sender: mpsc::Sender<Event>) {
    let Ok(mut stream) = events(&service).await else {
        return;
    };
    while let Ok(Some(event)) = stream.next().await {
        if sender.send(event).is_err() {
            return;
        }
    }
}

/// Draws the queue, again after every event, until the stream ends.
fn show(terminal: &mut DefaultTerminal, events: &mpsc::Receiver<Event>) -> io::Result<()> {
    let mut snapshot: Option<Snapshot> = None;
    terminal.draw(|frame| draw(frame, snapshot.as_ref()))?;
    while let Ok(event) = events.recv() {
        if let Event::Snapshot(latest) = event {
            snapshot = Some(latest);
        }
        terminal.draw(|frame| draw(frame, snapshot.as_ref()))?;
    }
    Ok(())
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
