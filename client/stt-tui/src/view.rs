//! What the TUI draws: the queue, the history of completed tasks, one transcript, and - under them
//! - the upload prompt and the last notice.

use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::Style;
use ratatui::text::Line;
use ratatui::widgets::{Block, Paragraph, Row, Table, TableState, Wrap};
use stt_client::{Snapshot, Task, Turns};

use crate::state::Screen;

/// Everything the screen shows.
pub struct View<'a> {
    /// The service's state, once its first snapshot arrived.
    pub snapshot: Option<&'a Snapshot>,
    /// Which screen is open.
    pub screen: &'a Screen,
    /// The queue row the commands act on.
    pub selected: usize,
    /// The task list the history shows.
    pub history: &'a [Task],
    /// The history row Enter opens.
    pub history_selected: usize,
    /// The path being typed into the upload prompt, while it is open.
    pub prompt: Option<&'a str>,
    /// The name being typed for the selected speaker, while the rename prompt is open.
    pub rename: Option<&'a str>,
    /// The download dialog, while it is open.
    pub download: Option<DownloadView<'a>>,
    /// What the last action reported.
    pub notice: Option<&'a str>,
}

/// The download dialog's fields: the task it saves, the path, the format, and whether the format
/// has the focus.
pub struct DownloadView<'a> {
    pub filename: &'a str,
    pub path: &'a str,
    pub format: &'a str,
    pub focus_format: bool,
}

/// Draws the open screen, with the prompt and the notice under it when there are any.
pub fn draw(frame: &mut Frame, view: &View<'_>) {
    let middle = match (
        view.download.is_some(),
        view.prompt.or(view.rename).is_some(),
    ) {
        // The dialog's border, its two fields and their spacing.
        (true, _) => 4,
        (false, true) => 1,
        (false, false) => 0,
    };
    let notice = if view.notice.is_some() { 1 } else { 0 };
    let areas = Layout::vertical([
        Constraint::Min(3),
        Constraint::Length(middle),
        Constraint::Length(notice),
    ])
    .split(frame.area());
    match view.screen {
        Screen::Queue => draw_queue(frame, areas[0], view),
        Screen::History => draw_history(frame, areas[0], view),
        Screen::Transcript(transcript) => draw_transcript(
            frame,
            areas[0],
            &transcript.task,
            &transcript.turns,
            transcript.speaker,
        ),
    }
    if let Some(path) = view.prompt {
        frame.render_widget(
            Paragraph::new(stt_client::text("upload.prompt", &[("path", path)])),
            areas[1],
        );
    }
    if let Some(name) = view.rename {
        frame.render_widget(
            Paragraph::new(stt_client::text("speakers.rename", &[("name", name)])),
            areas[1],
        );
    }
    if let Some(download) = &view.download {
        let title = stt_client::text("download.title", &[("filename", download.filename)]);
        let path = stt_client::text("download.path", &[("path", download.path)]);
        let format = stt_client::text("download.format", &[("format", download.format)]);
        let marker = |focused: bool| if focused { "> " } else { "  " };
        let lines = vec![
            Line::from(format!("{}{path}", marker(!download.focus_format))),
            Line::from(format!("{}{format}", marker(download.focus_format))),
        ];
        frame.render_widget(
            Paragraph::new(lines).block(Block::bordered().title(title)),
            areas[1],
        );
    }
    if let Some(notice) = view.notice {
        frame.render_widget(Paragraph::new(notice), areas[2]);
    }
}

/// The queue in its order - one row per task with its file name, status and transcription progress,
/// the selected row highlighted.
fn draw_queue(frame: &mut Frame, area: Rect, view: &View<'_>) {
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
    frame.render_stateful_widget(table, area, &mut state);
}

/// The completed tasks, the selected row highlighted.
fn draw_history(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let rows = crate::state::completed(view.history)
        .into_iter()
        .map(|task| Row::new([task.filename.clone(), task.status.clone()]));
    let table = Table::new(rows, [Constraint::Fill(1), Constraint::Length(12)])
        .block(Block::bordered().title(stt_client::text("history.title", &[])))
        .row_highlight_style(Style::new().reversed());
    let mut state = TableState::default().with_selected(Some(view.history_selected));
    frame.render_stateful_widget(table, area, &mut state);
}

/// A transcript: its speakers, the selected one highlighted, then one line per turn - its speaker's
/// name, its start and its text.
fn draw_transcript(frame: &mut Frame, area: Rect, task: &Task, turns: &Turns, speaker: usize) {
    let areas = Layout::vertical([
        // One row per speaker inside the list's border.
        Constraint::Length(turns.speakers.len() as u16 + 2),
        Constraint::Min(3),
    ])
    .split(area);
    let speakers = Table::new(
        turns
            .speakers
            .iter()
            .map(|speaker| Row::new([speaker.speaker.clone()])),
        [Constraint::Fill(1)],
    )
    .block(Block::bordered().title(stt_client::text("speakers.title", &[])))
    .row_highlight_style(Style::new().reversed());
    let mut state = TableState::default().with_selected(Some(speaker));
    frame.render_stateful_widget(speakers, areas[0], &mut state);
    let title = stt_client::text("transcript.title", &[("filename", &task.filename)]);
    let lines: Vec<Line> = turns
        .turns
        .iter()
        .map(|turn| {
            let speaker = turns
                .speakers
                .iter()
                .find(|speaker| speaker.speaker_id == turn.speaker_id)
                .map(|speaker| speaker.speaker.as_str())
                .unwrap_or_default();
            Line::from(format!(
                "{speaker}  {}  {}",
                clock(turn.start_ms),
                turn.text
            ))
        })
        .collect();
    frame.render_widget(
        Paragraph::new(lines)
            .block(Block::bordered().title(title))
            .wrap(Wrap { trim: true }),
        areas[1],
    );
}

/// A turn's start as `mm:ss`.
fn clock(ms: u64) -> String {
    format!("{:02}:{:02}", ms / 60_000, (ms / 1_000) % 60)
}
