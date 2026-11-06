//! What the TUI draws: the queue, the upload prompt while a path is typed, and the last upload's
//! outcome.

use ratatui::Frame;
use ratatui::layout::{Constraint, Layout};
use ratatui::style::Style;
use ratatui::widgets::{Block, Paragraph, Row, Table, TableState};
use stt_client::Snapshot;

/// Everything the screen shows.
pub struct View<'a> {
    /// The service's state, once its first snapshot arrived.
    pub snapshot: Option<&'a Snapshot>,
    /// The row the commands act on.
    pub selected: usize,
    /// The path being typed into the upload prompt, while it is open.
    pub prompt: Option<&'a str>,
    /// What the last upload or command reported.
    pub notice: Option<&'a str>,
}

/// Draws the queue in its order - one row per task with its file name, status and transcription
/// progress, the selected row highlighted - with the prompt and the notice under it when there are
/// any.
pub fn draw(frame: &mut Frame, view: &View<'_>) {
    let line = |text: Option<&str>| Constraint::Length(if text.is_some() { 1 } else { 0 });
    let areas = Layout::vertical([Constraint::Min(3), line(view.prompt), line(view.notice)])
        .split(frame.area());
    let rows = crate::state::rows(view.snapshot).into_iter().map(|task| {
        Row::new([
            task.filename.clone(),
            task.status.clone(),
            format!("{}%", (task.transcription_progress * 100.0).round()),
        ])
    });
    let paused = view.snapshot.is_some_and(|snapshot| snapshot.queue.paused);
    let title = stt_client::text(
        if paused {
            "queue.title-paused"
        } else {
            "queue.title"
        },
        &[],
    );
    let table = Table::new(
        rows,
        [
            Constraint::Fill(1),
            Constraint::Length(10),
            Constraint::Length(5),
        ],
    )
    .block(Block::bordered().title(title))
    .row_highlight_style(Style::new().reversed());
    let mut state = TableState::default().with_selected(Some(view.selected));
    frame.render_stateful_widget(table, areas[0], &mut state);
    if let Some(path) = view.prompt {
        frame.render_widget(
            Paragraph::new(stt_client::text("upload.prompt", &[("path", path)])),
            areas[1],
        );
    }
    if let Some(notice) = view.notice {
        frame.render_widget(Paragraph::new(notice), areas[2]);
    }
}
