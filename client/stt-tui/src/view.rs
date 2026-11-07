//! What the TUI draws: the queue, the history of completed tasks, one transcript, the settings,
//! and - under them - the prompts and the last notice.

use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::Style;
use ratatui::text::{Line, Text};
use ratatui::widgets::{Block, Clear, Paragraph, Row, Table, TableState, Wrap};
use stt_client::{Snapshot, Task};

use crate::settings::{FIELDS, SettingsScreen};
use crate::state::{Page, Screen};
use crate::transcript::Transcript;
use crate::upload_dialog::UploadDialog;

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
    /// The upload dialog, while it is open.
    pub upload: Option<&'a UploadDialog>,
    /// The name being typed for the selected speaker, while the rename prompt is open.
    pub rename: Option<&'a str>,
    /// The terminal command being typed, while the settings' prompt is open.
    pub terminal: Option<&'a str>,
    /// The download dialog, while it is open.
    pub download: Option<DownloadView<'a>>,
    /// What the last action reported.
    pub notice: Option<&'a str>,
    /// Whether the user set `NO_COLOR`: then nothing is coloured or reversed, and the selected row
    /// is marked with `>` instead.
    pub no_color: bool,
    /// Whether the event stream is down: the screen then says the service cannot be reached.
    pub disconnected: bool,
    /// Whether the key reference is open, over the page it belongs to.
    pub help: bool,
    /// Whether the queue page's upload area has the focus.
    pub upload_focused: bool,
}

/// The download dialog's fields: the task it saves, the path, the format, and whether the format
/// has the focus.
pub struct DownloadView<'a> {
    pub filename: &'a str,
    pub path: &'a str,
    pub format: &'a str,
    pub focus_format: bool,
}

/// Draws the open screen, with the prompt, the notice and - while the stream is down - the
/// connection line under it.
pub fn draw(frame: &mut Frame, view: &View<'_>) {
    let middle = match (
        view.download.is_some(),
        view.rename.or(view.terminal).is_some(),
    ) {
        // The dialog's border, its two fields and their spacing.
        (true, _) => 4,
        (false, true) => 1,
        (false, false) => 0,
    };
    let notice = if view.notice.is_some() { 1 } else { 0 };
    let connection = if view.disconnected { 1 } else { 0 };
    let areas = Layout::vertical([
        // The tab bar: the pages, the active one bracketed.
        Constraint::Length(1),
        Constraint::Min(3),
        Constraint::Length(middle),
        Constraint::Length(notice),
        Constraint::Length(connection),
        // The footer: the page's keys in words, so nothing must be guessed.
        Constraint::Length(1),
    ])
    .split(frame.area());
    draw_tabs(frame, areas[0], view);
    match view.screen {
        Screen::Queue => draw_queue(frame, areas[1], view),
        Screen::History => draw_history(frame, areas[1], view),
        Screen::Transcript(transcript) => draw_transcript(frame, areas[1], view, transcript),
        Screen::Settings(screen) => draw_settings(frame, areas[1], view, screen),
    }
    if let Some(name) = view.rename {
        frame.render_widget(
            Paragraph::new(stt_client::text("speakers.rename", &[("name", name)])),
            areas[2],
        );
    }
    if let Some(command) = view.terminal {
        frame.render_widget(
            Paragraph::new(stt_client::text(
                "settings.terminal-prompt",
                &[("command", command)],
            )),
            areas[2],
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
            areas[2],
        );
    }
    if let Some(notice) = view.notice {
        frame.render_widget(Paragraph::new(notice), areas[3]);
    }
    if view.disconnected {
        frame.render_widget(
            Paragraph::new(stt_client::text("connection.unreachable", &[])),
            areas[4],
        );
    }
    draw_footer(frame, areas[5], view);
    if let Some(upload) = view.upload {
        draw_upload(frame, upload);
    }
    if view.help {
        draw_help(frame, view);
    }
}

/// The page's full key reference, over the screen: a block whose body is the same text the footer
/// shows, wrapped, so the two can never disagree, and how to close it.
fn draw_help(frame: &mut Frame, view: &View<'_>) {
    let (title_key, footer_key) = match view.screen {
        Screen::Queue => ("help.queue", "footer.queue"),
        Screen::History => ("help.history", "footer.history"),
        Screen::Transcript(_) => ("help.transcript", "footer.transcript"),
        Screen::Settings(_) => ("help.settings", "footer.settings"),
    };
    let title = stt_client::text(title_key, &[]);
    let body = format!(
        "{}\n\n{}",
        stt_client::text(footer_key, &[]),
        stt_client::text("help.close", &[])
    );
    modal(frame, title, body, true);
}

/// Draws a centred modal over the screen - cleared beneath, a bordered title, the body, wrapped
/// when `wrap` - the shape the help reference and the upload dialog share.
fn modal(frame: &mut Frame, title: String, body: impl Into<Text<'static>>, wrap: bool) {
    let area = centered(frame.area());
    let paragraph = Paragraph::new(body).block(Block::bordered().title(title));
    let paragraph = if wrap {
        paragraph.wrap(Wrap { trim: true })
    } else {
        paragraph
    };
    frame.render_widget(Clear, area);
    frame.render_widget(paragraph, area);
}

/// The upload dialog: its directory (or the path being typed), then the entries, directories first,
/// the selected one marked; the list scrolls to keep the selection visible.
fn draw_upload(frame: &mut Frame, upload: &UploadDialog) {
    let area = centered(frame.area());
    let title = stt_client::text(
        "upload.title",
        &[("dir", &upload.dir().display().to_string())],
    );
    let typed = upload
        .typed()
        .map(|path| stt_client::text("upload.prompt", &[("path", path)]));
    let height = area.height.saturating_sub(2) as usize;
    let skip = upload.selected().saturating_sub(height.saturating_sub(1));
    let mut lines: Vec<Line> = Vec::new();
    if let Some(typed) = typed {
        lines.push(Line::from(typed));
    }
    for (index, entry) in upload.entries().iter().enumerate().skip(skip).take(height) {
        let marker = if index == upload.selected() {
            "> "
        } else {
            "  "
        };
        let name = entry
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_default();
        let suffix = if entry.is_dir() { "/" } else { "" };
        lines.push(Line::from(format!("{marker}{name}{suffix}")));
    }
    modal(frame, title, lines, false);
}

/// The middle of `area` for the help block: 70% wide and as tall as its text needs, at most all of
/// it.
fn centered(area: Rect) -> Rect {
    let width = (area.width * 7 / 10).clamp(20, area.width);
    let height = (area.height * 2 / 3).clamp(6, area.height);
    Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + (area.height - height) / 2,
        width,
        height,
    }
}

/// The page's keys in words: the queue names reorder and upload, a transcript rename and merge,
/// the settings how to change a value, history how to open a row.
fn draw_footer(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let key = if view.upload.is_some() {
        "upload.footer"
    } else {
        match view.screen {
            Screen::Queue => "footer.queue",
            Screen::History => "footer.history",
            Screen::Transcript(_) => "footer.transcript",
            Screen::Settings(_) => "footer.settings",
        }
    };
    frame.render_widget(Paragraph::new(stt_client::text(key, &[])), area);
}

/// The GUI's pages as a tab bar: `1 Queue`, `2 History`, `3 Settings`, the active one bracketed.
fn draw_tabs(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let active = view.screen.page();
    let tabs: Vec<String> = Page::ALL
        .iter()
        .map(|page| {
            let (number, key) = match page {
                Page::Queue => ("1", "queue.title"),
                Page::History => ("2", "history.title"),
                Page::Settings => ("3", "settings.title"),
            };
            let title = stt_client::text(key, &[]);
            if *page == active {
                format!("[{number} {title}]")
            } else {
                format!(" {number} {title} ")
            }
        })
        .collect();
    frame.render_widget(Paragraph::new(tabs.join("  ")), area);
}

/// The queue in its order - one row per task with its file name, status and transcription progress,
/// the selected row highlighted (marked with `>` when `NO_COLOR` is set).
fn draw_queue(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let areas = Layout::vertical([
        // The upload area above the queue: reachable with Up/k, Enter or Space opens the prompt.
        Constraint::Length(3),
        Constraint::Min(3),
    ])
    .split(area);
    let upload_marker = if view.upload_focused { "> " } else { "  " };
    frame.render_widget(
        Paragraph::new(format!(
            "{upload_marker}{}",
            stt_client::text("upload.area", &[])
        ))
        .wrap(Wrap { trim: true })
        .block(Block::bordered()),
        areas[0],
    );
    let paused = view.snapshot.is_some_and(|snapshot| snapshot.queue.paused);
    let title = stt_client::text(
        if paused {
            "queue.title-paused"
        } else {
            "queue.title"
        },
        &[],
    );
    let tasks = crate::state::rows(view.snapshot);
    if tasks.is_empty() {
        frame.render_widget(
            Paragraph::new(stt_client::text("queue.empty", &[]))
                .wrap(Wrap { trim: true })
                .block(Block::bordered().title(title)),
            areas[1],
        );
        return;
    }
    let rows = tasks.into_iter().enumerate().map(|(index, task)| {
        Row::new([
            format!(
                "{}{}",
                marker(index == view.selected, view.no_color),
                task.filename
            ),
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
    .block(Block::bordered().title(title))
    .row_highlight_style(if view.upload_focused {
        Style::new()
    } else {
        highlight(view.no_color)
    });
    let mut state = TableState::default().with_selected(Some(view.selected));
    frame.render_stateful_widget(table, areas[1], &mut state);
}

/// The completed tasks, the selected row highlighted.
fn draw_history(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let title = stt_client::text("history.title", &[]);
    let tasks = crate::state::completed(view.history);
    if tasks.is_empty() {
        frame.render_widget(
            Paragraph::new(stt_client::text("history.empty", &[]))
                .wrap(Wrap { trim: true })
                .block(Block::bordered().title(title)),
            area,
        );
        return;
    }
    let rows = tasks.into_iter().enumerate().map(|(index, task)| {
        Row::new([
            format!(
                "{}{}",
                marker(index == view.history_selected, view.no_color),
                task.filename
            ),
            task.status.clone(),
        ])
    });
    let table = Table::new(rows, [Constraint::Fill(1), Constraint::Length(12)])
        .block(Block::bordered().title(title))
        .row_highlight_style(highlight(view.no_color));
    let mut state = TableState::default().with_selected(Some(view.history_selected));
    frame.render_stateful_widget(table, area, &mut state);
}

/// The settings, one row per setting with its value, the selected row highlighted.
fn draw_settings(frame: &mut Frame, area: Rect, view: &View<'_>, screen: &SettingsScreen) {
    let rows = FIELDS.iter().enumerate().map(|(index, field)| {
        Row::new([
            format!(
                "{}{}",
                marker(index == screen.row, view.no_color),
                field.label()
            ),
            field.value(screen),
        ])
    });
    let table = Table::new(rows, [Constraint::Length(16), Constraint::Fill(1)])
        .block(Block::bordered().title(stt_client::text("settings.title", &[])))
        .row_highlight_style(highlight(view.no_color));
    let mut state = TableState::default().with_selected(Some(screen.row));
    frame.render_stateful_widget(table, area, &mut state);
}

/// A transcript: its speakers, the selected one highlighted and the one marked for a merge labelled
/// so, then one line per turn - its speaker's name, its start and its text.
fn draw_transcript(frame: &mut Frame, area: Rect, view: &View<'_>, transcript: &Transcript) {
    let Transcript {
        task,
        turns,
        speaker,
        marked,
        ..
    } = transcript;
    let areas = Layout::vertical([
        // One row per speaker inside the list's border.
        Constraint::Length(turns.speakers.len() as u16 + 2),
        Constraint::Min(3),
    ])
    .split(area);
    let speakers = Table::new(
        turns.speakers.iter().enumerate().map(|(index, entry)| {
            Row::new([if Some(entry.speaker_id) == *marked {
                stt_client::text("speakers.merging", &[("name", &entry.speaker)])
            } else {
                format!(
                    "{}{}",
                    marker(index == *speaker, view.no_color),
                    entry.speaker
                )
            }])
        }),
        [Constraint::Fill(1)],
    )
    .block(Block::bordered().title(stt_client::text("speakers.title", &[])))
    .row_highlight_style(highlight(view.no_color));
    let mut state = TableState::default().with_selected(Some(*speaker));
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

/// The selected row's style: reversed video, or nothing when `NO_COLOR` is set.
fn highlight(no_color: bool) -> Style {
    if no_color {
        Style::new()
    } else {
        Style::new().reversed()
    }
}

/// The prefix a row carries when it is selected and colors are off - selection must still show.
fn marker(selected: bool, no_color: bool) -> &'static str {
    if selected && no_color { "> " } else { "" }
}
