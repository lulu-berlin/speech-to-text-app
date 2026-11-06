//! What the TUI draws.

use ratatui::Frame;
use ratatui::layout::Constraint;
use ratatui::widgets::{Block, Row, Table};
use stt_client::Snapshot;

/// The queue in its order: one row per task with its file name, status and transcription progress.
pub fn draw(frame: &mut Frame, snapshot: Option<&Snapshot>) {
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
