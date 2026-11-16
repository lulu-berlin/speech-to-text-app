//! What the TUI draws: the queue, the history of completed tasks, one transcript, the settings,
//! and - under them - the prompts and the last notice.

use std::path::Path;
use std::time::Duration;

use ratatui::Frame;
use ratatui::crossterm::event::KeyEvent;
use ratatui::layout::{Alignment, Constraint, Layout, Position, Rect};
use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Block, BorderType, Cell, Clear, Paragraph, Row, Table, TableState, Wrap};
use stt_client::{Snapshot, Task};

use crate::browser::Browser;
use crate::download::Download;
use crate::settings::{Dropdown, FIELDS, SettingsScreen};
use crate::state::{Page, Screen};
use crate::stenographer;
use crate::transcript::Transcript;
use crate::upload_dialog::UploadDialog;
use crate::upload_options::Field;

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
    /// History's search query and status filter, as its controls line shows them.
    pub history_search: &'a str,
    pub history_status: crate::state::Status,
    /// The upload dialog, while it is open.
    pub upload: Option<&'a UploadDialog>,
    /// The name being typed for the selected speaker, while the rename prompt is open.
    pub rename: Option<&'a str>,
    /// The segment's text in the edit prompt, while it is open.
    pub edit: Option<&'a str>,
    /// The download dialog, while it is open.
    pub download: Option<&'a Download>,
    /// The transcript's audio, once it has started.
    pub player: Option<&'a crate::player::Player>,
    /// The task whose clip the player holds: a History row shows it while it plays.
    pub clip: Option<i64>,
    /// What the last action reported.
    pub notice: Option<&'a str>,
    /// Whether the user set `NO_COLOR`: then nothing is coloured or reversed, and the selected row
    /// is marked with `>` instead.
    pub no_color: bool,
    /// The colours to draw with (roadmap item 67, ADR 0068): the shared tokens in the stored mode.
    pub theme: &'a crate::theme::Palette,
    /// Whether the event stream is down: the screen then says the service cannot be reached.
    pub disconnected: bool,
    /// Whether the key reference is open, over the page it belongs to.
    pub help: bool,
    /// How many rows the key reference's table is scrolled down.
    pub help_scroll: usize,
    /// Whether the queue page's upload area has the focus.
    pub upload_focused: bool,
    /// The files of an upload batch on their way, with the bytes sent of each.
    pub uploads: &'a [crate::Uploading],
    /// The file an upload waits on, once a lost connection cut its batch off.
    pub waiting: Option<&'a str>,
    /// The finished tasks' notices, newest first: the queue page's lines above the upload area.
    pub notices: &'a [crate::Notice],
    /// The notice the keyboard is on, while one is.
    pub notice_focused: Option<usize>,
    /// The confirmation asked over the page, while one is open.
    pub confirm: Option<Dialog>,
    /// The write the open confirmation sends, by its catalog name: what a dropped stream refuses.
    pub confirming: Option<&'static str>,
    /// The key mode and context the open screen reads keys in.
    pub mode: crate::input::Mode,
    pub context: crate::input::Context,
    /// The GPU the service fell back from, while its notice stands.
    pub device_fallback: Option<&'a str>,
    /// Whether a task is RUNNING: the header's tile types exactly then, as the browser's does.
    pub typing: bool,
    /// The stenographer's step while a tile types; a resting tile shows its first frame.
    pub stenographer: usize,
    /// The event stream's state, as the header names it.
    pub connection: Connection,
    /// The stored settings, once read: the theme mode and the new uploads' defaults.
    pub settings: Option<&'a stt_client::Settings>,
    /// The time the frame is drawn at, in epoch milliseconds: what a running row's elapsed time
    /// counts to.
    pub now_ms: i64,
    /// Where the last frame drew the page: the mouse's hit-test lays the page out in it as the
    /// frame did.
    pub page: Rect,
    /// The whole frame the last draw painted: a dialog's area for the mouse's hit-test.
    pub frame: Rect,
    /// The first queue row the table showed: the hit-test lays the queue's rows out from it.
    pub queue_offset: &'a std::cell::Cell<usize>,
    /// The first History row the list showed: the hit-test lays History's rows out from it.
    pub history_offset: &'a std::cell::Cell<usize>,
    /// The footer hints a click performs, as the last frame drew them.
    pub footer_hits: &'a std::cell::RefCell<Vec<FooterHint>>,
}

impl<'a> View<'a> {
    /// Whether the page shortcuts reach: no dialog, prompt or the key reference holds the keyboard.
    /// `input::action` maps Shift and a tab's letter to a page exactly then.
    pub fn keys_reach_pages(&self) -> bool {
        self.mode == crate::input::Mode::Normal && !self.help
    }

    /// History's rows as its search and filter show them, newest first: the list its keys act on.
    pub fn history_rows(&self) -> Vec<&'a Task> {
        crate::state::history(self.history, self.history_search, self.history_status)
    }

    /// The History row its keys act on, while one is selected.
    pub fn history_task(&self) -> Option<&'a Task> {
        self.history_rows().get(self.history_selected).copied()
    }
}

/// The event stream's state, in the browser's words: connecting until the first snapshot, live
/// while the stream is up, reconnecting after it dropped until a new snapshot.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Connection {
    Connecting,
    Live,
    Reconnecting,
}

/// Draws the open screen, with the prompt, the notice and - while the stream is down - the
/// connection line under it; returns where the page was drawn.
pub fn draw(frame: &mut Frame, view: &View<'_>) -> Rect {
    if !view.no_color {
        // The page's own colours, set once for every cell: text is the theme's, never the
        // terminal's default foreground.
        frame.render_widget(
            Block::default().style(Style::new().fg(view.theme.text).bg(view.theme.background)),
            frame.area(),
        );
    }
    // The download dialog is a panel of its own, over the page; only a prompt takes a line here.
    let middle = match view.rename.or(view.edit).is_some() {
        true => 1,
        false => 0,
    };
    let notice = if view.notice.is_some() { 1 } else { 0 };
    let connection = if view.disconnected { 1 } else { 0 };
    // The footer: the keys that can act now, in words, so nothing must be guessed. A page's own
    // list is one row, the hints that do not fit left to `? more` (its reference lists them all);
    // any other list takes as many rows as it needs, none cut off.
    let shown = crate::footer::shown(view);
    let shown = if crate::footer::compacts(view) {
        crate::hints::fit(
            &shown,
            &stt_client::text("tui.hint.more", &[]),
            accent(view),
            frame.area().width,
        )
    } else {
        shown
    };
    let (footer, ranges) = crate::hints::pack(
        &shown,
        accent(view),
        Some(view.theme.muted),
        frame.area().width,
    );
    let areas = Layout::vertical([
        // The header: the stenographer's tile beside the product name and the tab bar.
        Constraint::Length(HEADER_ROWS),
        Constraint::Min(3),
        Constraint::Length(middle),
        Constraint::Length(notice),
        Constraint::Length(connection),
        Constraint::Length(footer.len() as u16),
    ])
    .split(frame.area());
    // The hints a click performs - those naming one key - where this frame packed them.
    *view.footer_hits.borrow_mut() = footer_click_hits(&shown, &ranges, areas[5]);
    draw_header(frame, areas[0], view);
    // While the first snapshot is on its way the browser's loading region sits above the page: the
    // resting large tile over its label, with the page still drawn under it.
    let page = if view.snapshot.is_none() {
        let [region, page] =
            Layout::vertical([Constraint::Length(CONNECTING_ROWS), Constraint::Min(3)])
                .areas(areas[1]);
        draw_connecting(frame, region, view);
        page
    } else {
        areas[1]
    };
    draw_screen(frame, page, view);
    if let Some(name) = view.rename {
        frame.render_widget(
            Paragraph::new(stt_client::text("tui.speakers.rename", &[("name", name)])),
            areas[2],
        );
        cursor_at_end(frame, areas[2], "tui.speakers.rename", "name", name);
    }
    if let Some(text) = view.edit {
        frame.render_widget(
            Paragraph::new(stt_client::text(
                "tui.segment.editPrompt",
                &[("text", text)],
            )),
            areas[2],
        );
        cursor_at_end(frame, areas[2], "tui.segment.editPrompt", "text", text);
    }
    if let Some(notice) = view.notice {
        frame.render_widget(Paragraph::new(notice), areas[3]);
    }
    if view.disconnected {
        frame.render_widget(
            Paragraph::new(stt_client::text("error.transport-read", &[])),
            areas[4],
        );
    }
    frame.render_widget(Paragraph::new(footer), areas[5]);
    // The dialogs over the page, the topmost last: each greys out everything drawn before it, so
    // only the dialog with the focus stands out, as btop's do.
    if let Some(download) = view.download {
        dim(frame, view);
        draw_download(frame, view, download);
    }
    if let Some(upload) = view.upload {
        dim(frame, view);
        draw_upload(frame, view, upload);
    }
    if let Screen::Transcript(transcript) = view.screen
        && let Some(cursor) = transcript.picker()
    {
        dim(frame, view);
        draw_move_to(frame, view, transcript, cursor);
    }
    if let Some(dialog) = &view.confirm {
        let body = Text::from(vec![
            Line::from(dialog.body.clone()),
            Line::default(),
            hint(view, "tui.confirm.keys", &[("confirm", &dialog.confirm)]),
        ]);
        dim(frame, view);
        modal(frame, view, dialog.title.clone(), body, true);
    }
    if view.help {
        dim(frame, view);
        draw_help(frame, view);
    }
    page
}

/// Greys out everything drawn so far - the page under a dialog - in the muted colour; under
/// `NO_COLOR` nothing is coloured, so nothing changes.
fn dim(frame: &mut Frame, view: &View<'_>) {
    if !view.no_color {
        let area = frame.area();
        frame
            .buffer_mut()
            .set_style(area, Style::new().fg(view.theme.muted));
    }
}

/// A dialog's frame, as the upload dialog's: the border in the accent colour - the dialog with the
/// focus - and the title in the page's text colour; plain under `NO_COLOR`.
fn dialog_block(view: &View<'_>, title: String) -> Block<'static> {
    Block::bordered()
        .border_style(fg(view, view.theme.accent))
        .title(Span::styled(title, fg(view, view.theme.text)))
}

/// The "Move to" list over the transcript, as the browser's picker: where the marked segments can
/// go - New speaker, then every speaker - the cursor's row highlighted (`>` without colours). Its
/// keys are the footer's.
fn draw_move_to(frame: &mut Frame, view: &View<'_>, transcript: &Transcript, cursor: usize) {
    let title = stt_client::text(
        "tui.reassign.title",
        &[("count", &transcript.marked_segment_count().to_string())],
    );
    let options = std::iter::once(stt_client::text("transcript.reassign.newSpeaker", &[])).chain(
        transcript
            .turns
            .speakers
            .iter()
            .map(|speaker| speaker.speaker.clone()),
    );
    let body = Text::from(
        options
            .enumerate()
            .map(|(index, option)| {
                let line = format!("{}{option}", marker(index == cursor, view.no_color));
                if index == cursor {
                    Line::styled(line, highlight(view))
                } else {
                    Line::from(line)
                }
            })
            .collect::<Vec<_>>(),
    );
    modal(frame, view, title, body, false);
}

/// A destructive action's confirmation, in the browser's `ConfirmDialog` style: the question as the
/// title, one sentence of what follows, and the confirm named beside Escape's Cancel.
pub struct Dialog {
    pub title: String,
    pub body: String,
    pub confirm: String,
}

/// The page's full key reference, over the screen, as btop's help: a table of every key of the page,
/// `Key:` in the accent and `Description:` muted, whether it can act now or not (the footer shows
/// those that can, and leaves the rest to `? more`), and how to close it. A table taller than the
/// frame scrolls, its cut edges marked `↑ more` and `↓ more` on the frame.
fn draw_help(frame: &mut Frame, view: &View<'_>) {
    let table = help_table(frame.area(), view, view.help_scroll);
    let strong = fg(view, view.theme.text).patch(bold(view));
    // A key cell, centred in the key column as btop's are, with the gap to the descriptions after.
    let centred = |text: &str| {
        let room = table.key_width - text.chars().count();
        let before = room / 2;
        format!(
            "{}{text}{}",
            " ".repeat(before),
            " ".repeat(room - before + HELP_GAP)
        )
    };
    let (key, description) = (
        stt_client::text("tui.help.key", &[]),
        stt_client::text("tui.help.description", &[]),
    );
    let mut lines = vec![Line::from(vec![
        Span::styled(centred(&key), strong),
        Span::styled(description, strong),
    ])];
    lines.extend(
        table.entries[table.first..table.first + table.visible]
            .iter()
            .map(|(key, words)| {
                Line::from(vec![
                    Span::styled(centred(key), fg(view, view.theme.accent)),
                    Span::styled(words.clone(), fg(view, view.theme.muted)),
                ])
            }),
    );
    lines.push(Line::default());
    lines.push(hint(view, "tui.help.close", &[]));
    let mut block = dialog_block(view, stt_client::text(help_title(view.screen), &[]))
        .padding(ratatui::widgets::Padding::horizontal(1));
    let more = fg(view, view.theme.muted);
    if table.first > 0 {
        let above = stt_client::text("tui.help.moreAbove", &[]);
        block = block.title_top(Line::styled(above, more).right_aligned());
    }
    if table.first + table.visible < table.entries.len() {
        let below = stt_client::text("tui.help.moreBelow", &[]);
        block = block.title_bottom(Line::styled(below, more).right_aligned());
    }
    clear(frame, view, table.area);
    frame.render_widget(Paragraph::new(lines).block(block), table.area);
}

/// The rows the key reference keeps around its table rows: its two borders, the header, a blank row
/// and the line saying how to close it.
const HELP_CHROME: u16 = 5;
/// The spaces between the key reference's columns.
const HELP_GAP: usize = 4;

/// The key reference's table over a frame: its entries as keys and words, the key column's width,
/// and the rows shown - from `first`, `visible` of them - in the centred `area` that fits them.
struct HelpTable {
    area: Rect,
    entries: Vec<(String, String)>,
    key_width: usize,
    first: usize,
    visible: usize,
}

/// Where the key reference is drawn over `frame`: what a click outside it is measured against.
pub fn help_area(frame: Rect, view: &View<'_>) -> Rect {
    help_table(frame, view, 0).area
}

/// How far the key reference over `frame` scrolls: its rows past the ones that fit.
pub fn help_max_scroll(frame: Rect, view: &View<'_>) -> usize {
    let table = help_table(frame, view, 0);
    table.entries.len() - table.visible
}

/// The key reference's table over `frame` - the page's keys that act now - scrolled down by
/// `scroll` rows (at most to its last).
fn help_table(frame: Rect, view: &View<'_>, scroll: usize) -> HelpTable {
    let screen = view.screen;
    let entries: Vec<(String, String)> = crate::footer::reference(view)
        .iter()
        .map(|hint| crate::hints::key_and_words(hint))
        .collect();
    let width_of = |text: &str| text.chars().count();
    let key_width = entries
        .iter()
        .map(|(key, _)| width_of(key))
        .chain([width_of(&stt_client::text("tui.help.key", &[]))])
        .max()
        .unwrap_or(0);
    let words_width = entries
        .iter()
        .map(|(_, words)| width_of(words))
        .chain([width_of(&stt_client::text("tui.help.description", &[]))])
        .max()
        .unwrap_or(0);
    let visible = entries
        .len()
        .min(usize::from(frame.height.saturating_sub(2 + HELP_CHROME)))
        .max(1)
        .min(entries.len());
    let first = scroll.min(entries.len() - visible);
    let close = crate::hints::line("tui.help.close", &[], Some(Color::Reset)).width();
    let title = width_of(&stt_client::text(help_title(screen), &[]));
    let content = (key_width + HELP_GAP + words_width).max(close).max(title);
    // The borders and a column of padding inside each.
    let width = (content as u16 + 4).min(frame.width);
    let height = (visible as u16 + HELP_CHROME).min(frame.height);
    HelpTable {
        area: Rect {
            x: frame.x + (frame.width - width) / 2,
            y: frame.y + (frame.height - height) / 2,
            width,
            height,
        },
        entries,
        key_width,
        first,
        visible,
    }
}

/// Draws a centred modal over the screen - cleared beneath, in the dialogs' frame, the body wrapped
/// when `wrap` - the shape a confirmation, the download dialog and "Move to" share.
fn modal(
    frame: &mut Frame,
    view: &View<'_>,
    title: String,
    body: impl Into<Text<'static>>,
    wrap: bool,
) {
    let area = centered(frame.area());
    let paragraph = Paragraph::new(body).block(dialog_block(view, title));
    let paragraph = if wrap {
        paragraph.wrap(Wrap { trim: true })
    } else {
        paragraph
    };
    clear(frame, view, area);
    frame.render_widget(paragraph, area);
}

/// The upload dialog, as its signed-off design draws it (`docs/design/tui/upload-dark.svg`): the
/// folder it shows with how to move in it, a rule, the entries marked `[✓]` or `[ ]` with their
/// size and date, a rule, the two options on one row, a rule, the path being typed, and the
/// dialog's own line of keys; the selected entry is filled in the accent tint.
fn draw_upload(frame: &mut Frame, view: &View<'_>, upload: &UploadDialog) {
    let browser = upload.browser();
    let area = upload_area(frame.area(), upload);
    // The design's window: an accent frame with the title in the page's own text colour - white
    // on the background, not a filled title bar.
    let block = dialog_block(view, stt_client::text("tui.upload.title", &[]));
    let inner = block.inner(area);
    clear(frame, view, area);
    frame.render_widget(block, area);
    let rule = "─".repeat(inner.width.saturating_sub(2) as usize);
    let rule = Line::styled(rule, fg(view, view.theme.border));
    let mut lines: Vec<Line> = vec![
        muted_hint(
            view,
            "tui.browse.folder",
            &[("dir", &browser.dir().display().to_string())],
        ),
        muted_hint(view, "tui.browse.markHint", &[]),
        rule.clone(),
    ];
    // The fixed rows after the list: a rule, the options, a rule, the path, the dialog's keys -
    // and the error line when the typed path failed.
    let tail = 5usize + usize::from(browser.error().is_some());
    let list_height = (inner.height as usize)
        .saturating_sub(lines.len() + tail)
        .max(1);
    lines.extend(browser_rows(
        view,
        browser,
        inner.width,
        list_height,
        // The files carry the highlight only while they have the keyboard: with a chip or its
        // list in focus no file is selected on screen.
        (upload.focus() == Field::Browser).then(|| browser.selected()),
        |file| upload.is_marked(file),
        |file| {
            if stt_client::allowed_extension(file) {
                String::new()
            } else {
                format!(" {}", stt_client::text("tui.upload.notAudio", &[]))
            }
        },
    ));
    lines.push(rule.clone());
    // The two options on one row, as the design: the language and the speakers choice, each with
    // its value in a chip; the row the keyboard is on carries the accent.
    let chip = |field: Field| {
        let value = format!("{} ▾", upload.value(field));
        let style = if upload.focus() == field {
            Style::new()
                .fg(view.theme.accent_text)
                .bg(view.theme.accent)
        } else {
            Style::new()
                .fg(view.theme.text)
                .bg(view.theme.surface_raised)
        };
        vec![
            Span::styled(format!("{}   ", field.label()), fg(view, view.theme.muted)),
            Span::styled(value, style),
        ]
    };
    lines.push(Line::from(
        chip(Field::Language)
            .into_iter()
            .chain([Span::raw("   ")])
            .chain(chip(Field::Speakers))
            .collect::<Vec<_>>(),
    ));
    lines.push(rule);
    // The path being typed, its end in view when it is wider than the row: the cursor follows it.
    let path_prefix = format!("{}   ", stt_client::text("tui.upload.path", &[]));
    let typed = browser
        .typed()
        .map(|path| typed_field(path, path_prefix.chars().count() as u16, inner.width));
    let path = match &typed {
        Some((path, _)) => Line::from(vec![
            Span::styled(path_prefix, fg(view, view.theme.muted)),
            Span::raw(path.clone()),
        ]),
        None => muted_hint(view, "tui.upload.footerPath", &[]),
    };
    let path_row = lines.len() as u16;
    lines.push(path);
    if let Some(error) = browser.error() {
        lines.push(Line::styled(error.to_string(), fg(view, view.theme.danger)));
    }
    let mut footer: Vec<Span> = Vec::new();
    for (key, args) in [
        (
            "tui.upload.footerFiles",
            vec![("count", upload.marked_count().to_string())],
        ),
        ("tui.upload.footerTab", Vec::new()),
        ("tui.upload.footerPath", Vec::new()),
        ("tui.upload.footerCancel", Vec::new()),
    ] {
        if !footer.is_empty() {
            footer.push(Span::raw("    "));
        }
        let args: Vec<(&str, &str)> = args
            .iter()
            .map(|(name, value)| (*name, value.as_str()))
            .collect();
        footer.extend(muted_hint(view, key, &args).spans);
    }
    lines.push(Line::from(footer));
    frame.render_widget(Paragraph::new(lines), inner);
    // An option's list, floating under its chip: its choices, the current one ticked and the
    // keyboard's row in the accent, scrolled to keep it in view.
    if let Some(chooser) = upload.chooser() {
        let parts = upload_parts(frame.area(), upload);
        let area = parts.chooser;
        if area.width > 2 && area.height > 2 {
            let block = Block::bordered()
                .border_style(fg(view, view.theme.accent))
                .title(Span::styled(
                    match chooser.filter() {
                        Some(filter) if !filter.is_empty() => {
                            format!("{}  {filter}", chooser.field().label())
                        }
                        _ => chooser.field().label(),
                    },
                    fg(view, view.theme.text),
                ))
                .style(Style::new().bg(view.theme.surface_raised));
            let list = block.inner(area);
            clear(frame, view, area);
            frame.render_widget(block, area);
            let current = upload.value(chooser.field());
            let height = list.height as usize;
            let skip = chooser.cursor().saturating_sub(height.saturating_sub(1));
            let lines: Vec<Line> = chooser
                .rows()
                .enumerate()
                .skip(skip)
                .take(height)
                .map(|(index, choice)| {
                    let mark = if choice.value == current {
                        "✓ "
                    } else {
                        "  "
                    };
                    let style = if index == chooser.cursor() {
                        Style::new()
                            .fg(view.theme.accent_text)
                            .bg(view.theme.accent)
                    } else {
                        Style::new().fg(view.theme.text)
                    };
                    Line::from(Span::styled(format!("{mark}{}", choice.label), style))
                })
                .collect();
            frame.render_widget(Paragraph::new(lines), list);
        }
    }
    // The cursor at the typed path's end, on the line it is drawn on.
    if let Some((_, column)) = typed {
        frame.set_cursor_position((inner.x + column, inner.y + path_row));
    }
}

/// Where the upload dialog's parts sit in `area`, as the draw lays them out: the list's rows, the
/// options row, the chips' columns and widths, and a chooser's box.
struct UploadParts {
    inner: Rect,
    list_top: u16,
    list_height: u16,
    options_row: u16,
    language_col: u16,
    language_width: u16,
    speakers_col: u16,
    speakers_width: u16,
    chooser: Rect,
}

/// The upload dialog's layout in `area`: the numbers the draw and the mouse's hit-test share.
fn upload_parts(area: Rect, upload: &UploadDialog) -> UploadParts {
    let outer = upload_area(area, upload);
    let inner = Rect {
        x: outer.x + 1,
        y: outer.y + 1,
        width: outer.width.saturating_sub(2),
        height: outer.height.saturating_sub(2),
    };
    // The rows before the list (folder, mark hint, rule) and after it (rule, options, rule, path,
    // the dialog's keys).
    let list_height = (inner.height as usize).saturating_sub(3 + 5).max(1) as u16;
    let skip = upload
        .browser()
        .selected()
        .saturating_sub(list_height.saturating_sub(1) as usize);
    let rows = upload.browser().rows().count();
    let shown = rows.saturating_sub(skip).min(list_height as usize) as u16;
    let options_row = 3 + shown + 1;
    let language_label = stt_client::text("queue.language", &[]).chars().count() as u16;
    let language_width = upload.value(Field::Language).chars().count() as u16 + 2;
    let language_col = inner.x + language_label + 3;
    let speakers_label = stt_client::text("queue.speakers", &[]).chars().count() as u16;
    let speakers_col = language_col + language_width + 3 + speakers_label + 3;
    let speakers_width = upload.value(Field::Speakers).chars().count() as u16 + 2;
    let chooser = match upload.chooser() {
        Some(chooser) => {
            let x = match chooser.field() {
                Field::Speakers => speakers_col,
                Field::Language | Field::Browser => language_col,
            };
            let labels = chooser
                .rows()
                .map(|choice| choice.label.chars().count())
                .max()
                .unwrap_or(0) as u16;
            let width = (labels + 4).min(inner.right().saturating_sub(x));
            let height = (chooser.rows().count() as u16 + 2)
                .min(12)
                .min(inner.height);
            // Under the chips when the room is there; otherwise over the list above them, so a
            // long catalogue still shows several rows at once.
            let below = inner.bottom().saturating_sub(inner.y + options_row + 1);
            let y = if below >= height {
                inner.y + options_row + 1
            } else {
                (inner.y + options_row + 1)
                    .saturating_sub(height)
                    .max(inner.y)
            };
            let height = height.min(inner.bottom().saturating_sub(y));
            Rect {
                x,
                y,
                width,
                height,
            }
        }
        None => Rect::default(),
    };
    UploadParts {
        inner,
        list_top: 3,
        list_height: shown,
        options_row,
        language_col,
        language_width,
        speakers_col,
        speakers_width,
        chooser,
    }
}

/// A dialog's prose: its words in the muted role and its bracketed keys in the accent, underlined,
/// as the footer draws a hint.
fn muted_hint(view: &View<'_>, key: &str, args: &[(&str, &str)]) -> Line<'static> {
    crate::hints::line_muted(
        key,
        args,
        accent(view),
        (!view.no_color).then_some(view.theme.muted),
    )
}

/// Fills `area` with the page's own colours, clearing what it covered: a modal or a floating list
/// paints on this instead of the terminal's defaults.
fn clear(frame: &mut Frame, view: &View<'_>, area: Rect) {
    // The erase comes first: a styled block only recolours the glyphs already there, leaving the
    // page showing through the dialog's blank cells.
    frame.render_widget(Clear, area);
    frame.buffer_mut().set_style(
        area,
        Style::new().fg(view.theme.text).bg(view.theme.background),
    );
}

/// The upload dialog's area: only as wide and tall as its content needs - its longest entry (or
/// the options row, with a floor), and one row per entry above the fixed rows - bounded by the
/// frame's margins when the content is larger.
pub fn upload_area(area: Rect, upload: &UploadDialog) -> Rect {
    let most = area.height.saturating_sub(4);
    let rows = upload.browser().rows().count() as u16;
    let error = u16::from(upload.browser().error().is_some());
    // The folder, the mark hint and a rule; the list; a rule, the options, a rule, the path, the
    // dialog's keys (and an error); the two borders.
    let wanted = 3 + rows + 5 + error + 2;
    let inner = upload_entries_width(upload)
        .max(upload_options_width(upload))
        .max(upload_prose_width(upload))
        .max(40);
    let width = (inner + 2).min(area.width.saturating_sub(6)).max(20);
    Rect {
        // Centred in the frame, with the page's own top margin.
        x: area.x + area.width.saturating_sub(width) / 2,
        y: area.y + 2,
        width,
        height: wanted.min(most),
    }
}

/// The widest row the browser draws, in columns: a mark, the name, and a file's size and date.
fn upload_entries_width(upload: &UploadDialog) -> u16 {
    upload
        .browser()
        .rows()
        .map(|row| match row {
            None => 7,
            Some(entry) if entry.is_dir() => {
                4 + entry.file_name().map_or(0, |name| name.len()) as u16 + 1
            }
            Some(entry) => {
                let name = entry.file_name().map_or(0, |name| name.len()) as u16;
                if stt_client::allowed_extension(entry) {
                    // The mark, a name wide enough, the size column and a date like `Nov 14`.
                    4 + name + 7 + 3 + 6
                } else {
                    let label = stt_client::text("tui.upload.notAudio", &[]);
                    4 + name + 1 + label.chars().count() as u16
                }
            }
        })
        .max()
        .unwrap_or(0)
}

/// The widest fixed text the dialog draws - its title, folder line, mark hint, path row, the typed
/// path in it, and its keys - so nothing is clipped; the typed path widens the dialog as it grows.
fn upload_prose_width(upload: &UploadDialog) -> u16 {
    let text = stt_client::text;
    let browser = upload.browser();
    let folder = text(
        "tui.browse.folder",
        &[("dir", &browser.dir().display().to_string())],
    );
    let path = match browser.typed() {
        Some(typed) => format!("{}   {typed}", text("tui.upload.path", &[])),
        None => text("tui.upload.footerPath", &[]),
    };
    let footer = [
        text(
            "tui.upload.footerFiles",
            &[("count", &upload.marked_count().to_string())],
        ),
        text("tui.upload.footerTab", &[]),
        text("tui.upload.footerPath", &[]),
        text("tui.upload.footerCancel", &[]),
    ]
    .join("    ");
    // A hint's keys lose their brackets when they are styled, so the drawn line is shorter than
    // the template: count it as drawn.
    let plain = |line: &str| line.replace(['[', ']'], "").chars().count() as u16;
    [
        folder,
        text("tui.browse.markHint", &[]),
        path,
        footer,
        text("tui.upload.title", &[]),
    ]
    .iter()
    .map(|line| plain(line))
    .max()
    .unwrap_or(0)
}

/// The options row's width: both chips with their labels and values.
fn upload_options_width(upload: &UploadDialog) -> u16 {
    let chip = |field: crate::upload_options::Field| {
        field.label().chars().count() as u16 + 3 + upload.value(field).chars().count() as u16 + 2
    };
    chip(crate::upload_options::Field::Language) + 3 + chip(crate::upload_options::Field::Speakers)
}

/// The download dialog, in the upload dialog's shape: the folder its list shows, the typed target,
/// the format, and the folder's rows - `../` first, folders before files.
fn draw_download(frame: &mut Frame, view: &View<'_>, download: &Download) {
    let browser = download.browser();
    let area = centered(frame.area());
    let title = stt_client::text(
        "tui.download.title",
        &[("filename", download.task_filename())],
    );
    let mut lines: Vec<Line> = vec![
        Line::from(stt_client::text(
            "tui.browse.folder",
            &[("dir", &browser.dir().display().to_string())],
        )),
        hint(view, "tui.hint.downloadOpen", &[]),
        hint(view, "tui.hint.downloadUp", &[]),
    ];
    // The target being typed, its end in view when it is wider than the row.
    let typed = browser.typed().map(|typed| {
        typed_field(
            typed,
            prefix_width("tui.download.path", "path"),
            area.width.saturating_sub(2),
        )
    });
    if let Some((typed, _)) = &typed {
        lines.push(Line::from(stt_client::text(
            "tui.download.path",
            &[("path", typed)],
        )));
    }
    lines.push(Line::from(""));
    let marker = if download.format_focus() { "> " } else { "  " };
    lines.push(Line::from(format!(
        "{marker}{}",
        stt_client::text("tui.download.format", &[("format", download.format())])
    )));
    lines.push(Line::from(""));
    let height = area.height.saturating_sub(2) as usize;
    let list_height = height.saturating_sub(lines.len()).max(1);
    lines.extend(browser_rows(
        view,
        browser,
        area.width.saturating_sub(2),
        list_height,
        Some(browser.selected()),
        |_| false,
        |_| String::new(),
    ));
    modal(frame, view, title, lines, false);
    // The cursor at the typed target's end, in the same row the cursor of an upload's path takes.
    if let Some((_, column)) = typed {
        cursor(frame, area, 4, 1 + column);
    }
}

/// A folder browser's rows in `height` lines, scrolled to keep the selection in view: `../` first,
/// then folders with a `/` and the files, as the designs draw them; the selected row is filled in
/// the accent tint across the whole line, and the list scrolls to keep it visible. A file the
/// service would not take is greyed out: no mark, no size and no date, nor a row steps land on.
fn browser_rows(
    view: &View<'_>,
    browser: &Browser,
    width: u16,
    height: usize,
    selected: Option<usize>,
    tick: impl Fn(&Path) -> bool,
    label: impl Fn(&Path) -> String,
) -> Vec<Line<'static>> {
    let skip = browser.selected().saturating_sub(height.saturating_sub(1));
    let text = fg(view, view.theme.text);
    let muted = fg(view, view.theme.muted);
    let accent = fg(view, view.theme.accent);
    let width = usize::from(width);
    // The design's columns: the mark at 4, the name at 8, the size ending at 59, the date at 62.
    let size_end = width.saturating_sub(19).min(59);
    let date_at = size_end + 3;
    browser
        .rows()
        .enumerate()
        .skip(skip)
        .take(height)
        .map(|(index, row)| {
            let tint = (Some(index) == selected).then_some(view.theme.accent_tint);
            let with_bg = |style: Style| match tint {
                Some(bg) => style.bg(bg),
                None => style,
            };
            let mut line = match row {
                None => Line::from(vec![
                    Span::styled("  ↰ ", with_bg(muted)),
                    Span::styled("../", with_bg(accent)),
                ]),
                Some(entry) if entry.is_dir() => Line::from(vec![
                    Span::styled("  ▸ ", with_bg(muted)),
                    Span::styled(format!("{}/", file_name(entry)), with_bg(accent)),
                ]),
                Some(entry) if !stt_client::allowed_extension(entry) => Line::from(vec![
                    Span::styled("    ", with_bg(muted)),
                    Span::styled(
                        format!("{}{}", file_name(entry), label(entry)),
                        with_bg(muted),
                    ),
                ]),
                Some(entry) => {
                    let marked = tick(entry);
                    let mark = if marked { "[✓] " } else { "[ ] " };
                    let mark_style = with_bg(if marked { accent } else { muted });
                    let (size, date) = file_detail(entry);
                    let name_room = date_at
                        .saturating_sub(4)
                        .saturating_sub(mark.chars().count());
                    let name = format!("{}{}", file_name(entry), label(entry));
                    let padded = format!("{name:<name_room$}");
                    let size = format!("{size:>7}");
                    Line::from(vec![
                        Span::styled(mark, mark_style),
                        Span::styled(padded, with_bg(text)),
                        Span::styled(size, with_bg(muted)),
                        Span::styled("   ", with_bg(muted)),
                        Span::styled(date, with_bg(muted)),
                    ])
                }
            };
            // The tint fills the line to the dialog's right edge, past where the text ends.
            let used = line.width();
            if let Some(bg) = tint.filter(|_| used < width) {
                line.push_span(Span::styled(" ".repeat(width - used), Style::new().bg(bg)));
            }
            line
        })
        .collect()
}

/// An entry's name as text, empty when it has none.
fn file_name(entry: &Path) -> String {
    entry
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// A file's size and date for the dialog's rows: `118 MB` and `Nov 7`, the design's columns; empty
/// when the entry cannot be read.
fn file_detail(entry: &Path) -> (String, String) {
    let Ok(metadata) = std::fs::metadata(entry) else {
        return (String::new(), String::new());
    };
    let size = human_size(metadata.len());
    let date = metadata
        .modified()
        .ok()
        .map(|time| {
            let millis = chrono::DateTime::<chrono::Local>::from(time).timestamp_millis();
            stt_client::date_month_day(millis)
        })
        .unwrap_or_default();
    (size, date)
}

/// `len` bytes as the design writes a size: whole megabytes from a hundred, one decimal below,
/// and the smaller units the same way, in the app's language's digits.
fn human_size(len: u64) -> String {
    let units = ["B", "KB", "MB", "GB"];
    let mut value = len as f64;
    let mut unit = 0;
    while value >= 1000.0 && unit + 1 < units.len() {
        value /= 1000.0;
        unit += 1;
    }
    if unit == 0 {
        format!(
            "{} B",
            stt_client::integer(i64::try_from(len).unwrap_or(i64::MAX))
        )
    } else if value >= 100.0 {
        format!("{} {}", stt_client::number(value, 0), units[unit])
    } else {
        format!("{} {}", stt_client::number(value, 1), units[unit])
    }
}

/// The middle of `area` for a modal: 70% wide, two thirds tall.
pub fn centered(area: Rect) -> Rect {
    let width = (area.width * 7 / 10).clamp(20, area.width);
    let height = (area.height * 2 / 3).clamp(6, area.height);
    Rect {
        x: area.x + (area.width - width) / 2,
        y: area.y + (area.height - height) / 2,
        width,
        height,
    }
}

/// The catalog key of a page's key reference title: `1 Queue` has `help.queue`, a transcript
/// `help.transcript`.
fn help_title(screen: &Screen) -> &'static str {
    match screen {
        Screen::Queue => "tui.help.queue",
        Screen::History => "tui.help.history",
        Screen::Transcript(_) => "tui.help.transcript",
        Screen::Status(_) => "tui.help.status",
        Screen::Settings(_) | Screen::SettingsFailed(_) => "tui.help.settings",
    }
}

/// The header's rows: as many as the stenographer's tile is tall.
pub const HEADER_ROWS: u16 = 3;

/// The loading region's rows: the large tile's height with its label under it.
const CONNECTING_ROWS: u16 = stenographer::LARGE[0].len() as u16 + 1;

/// The page the tab bar names: the queue, the history, a transcript, a task's status or the
/// settings.
fn draw_screen(frame: &mut Frame, area: Rect, view: &View<'_>) {
    match view.screen {
        Screen::Queue => draw_queue(frame, area, view),
        Screen::History => draw_history(frame, area, view),
        Screen::Transcript(transcript) => draw_transcript(frame, area, view, transcript),
        Screen::Status(task) => draw_status(frame, area, view, task),
        Screen::Settings(screen) => draw_settings(frame, area, view, screen),
        Screen::SettingsFailed(error) => draw_settings_failed(frame, area, view, error),
    }
}

/// The settings page's introduction, in the browser's words.
fn settings_intro() -> String {
    stt_client::text("settings.intro", &[])
}

/// The settings page when the settings could not be read, as the browser's page shows it: the
/// introduction, then why the read failed and that Enter reads them again.
fn draw_settings_failed(frame: &mut Frame, area: Rect, view: &View<'_>, error: &str) {
    let block = panel(view, stt_client::text("settings.title", &[]));
    let inside = block.inner(area);
    frame.render_widget(block, area);
    let body = Text::from(vec![
        Line::from(settings_intro()),
        Line::default(),
        Line::styled(error.to_string(), fg(view, view.theme.danger)),
        hint(view, "tui.settings.retry", &[]),
    ]);
    frame.render_widget(Paragraph::new(body).wrap(Wrap { trim: true }), inside);
}

/// What the browser's `LoadingState` shows while the first snapshot is on its way: the resting
/// large tile over a short muted label, centred in its region.
fn draw_connecting(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let below = draw_large_tile(frame, area, view, false);
    frame.render_widget(
        Paragraph::new(stt_client::text("app.connecting", &[]))
            .alignment(Alignment::Center)
            .style(fg(view, view.theme.muted)),
        below,
    );
}

/// The header: the stenographer's tile at its left; right of it the product name with the
/// connection's state and the theme mode, the tab bar and a rule under them, as the signed-off
/// designs draw it.
fn draw_header(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let [tile, rest] = Layout::horizontal([
        Constraint::Length(stenographer::SMALL_WIDTH),
        Constraint::Fill(1),
    ])
    .areas(area);
    if !view.no_color {
        // The designs' bands: the first two rows on the surface, the tile's column on the tint.
        let bands = Rect {
            height: area.height.min(2),
            ..area
        };
        let buffer = frame.buffer_mut();
        buffer.set_style(bands, Style::new().bg(view.theme.surface));
        buffer.set_style(tile, Style::new().bg(view.theme.accent_tint));
    }
    let step = if view.typing { view.stenographer } else { 0 };
    let small = &stenographer::SMALL[step % stenographer::SMALL.len()];
    draw_tile(frame, tile, view, small);
    let [status, tabs, rule] = Layout::vertical([Constraint::Length(1); 3]).areas(rest);
    frame.render_widget(Paragraph::new(status_line(view, status.width).0), status);
    draw_tabs(frame, tabs, view);
    frame.render_widget(
        Paragraph::new(Span::styled(
            "─".repeat(rule.width as usize),
            fg(view, view.theme.border),
        )),
        rule,
    );
}

/// The space between the header's first-row parts.
const STATUS_GAP: &str = "   ";

/// The header's first row right of the tile: the product name in the accent and what the queue is
/// doing beside it, muted - `transcribing 1 task` while tasks run, `idle` otherwise - then the
/// connection's state and - once the settings are read - the theme mode, pushed to the row's right
/// end, in the browser's words and colours. The mode's columns, counted from the row's start, come
/// with it, so a click there cycles it.
fn status_line(view: &View<'_>, width: u16) -> (Line<'static>, Option<std::ops::Range<u16>>) {
    // The product name is never translated (item 88's standard).
    let name = "Speech-to-Text";
    let name_style = fg(view, view.theme.accent).patch(bold(view));
    let summary = view.snapshot.map(|snapshot| {
        let running = snapshot
            .tasks
            .iter()
            .filter(|task| task.status == "RUNNING")
            .count();
        let key = if running == 0 {
            "tui.header.idle"
        } else {
            "tui.header.transcribing"
        };
        format!(
            "{STATUS_GAP}{}",
            stt_client::text(key, &[("count", &running.to_string())])
        )
    });
    // The browser's colours: muted while connecting, the done status's while live, the problem
    // status's while reconnecting.
    let (key, colour) = match view.connection {
        Connection::Connecting => ("app.connection.connecting", view.theme.muted),
        Connection::Live => ("app.connection.live", view.theme.status("COMPLETED")),
        Connection::Reconnecting => ("app.connection.reconnecting", view.theme.status("FAILED")),
    };
    let state = format!("● {}", stt_client::text(key, &[]));
    let state_width = state.chars().count();
    let mut right = state_width;
    let mut mode_label = None;
    if let Some(stored) = view.settings.map(|settings| settings.theme_mode.as_str()) {
        let key = match stored {
            "light" => "app.theme.light",
            "dark" => "app.theme.dark",
            _ => "app.theme.system",
        };
        let label = format!("◐ {}", stt_client::text(key, &[]));
        right += STATUS_GAP.len() + label.chars().count();
        mode_label = Some(label);
    }
    // The state block ends at the row's right edge, less one column of margin.
    let margin = 1;
    let left = name.chars().count() + summary.as_ref().map_or(0, |text| text.chars().count());
    let pad = usize::from(width)
        .saturating_sub(left + right + margin)
        .max(STATUS_GAP.len());
    let mut spans = vec![Span::styled(name, name_style)];
    if let Some(summary) = summary {
        spans.push(Span::styled(summary, fg(view, view.theme.muted)));
    }
    spans.extend([
        Span::raw(" ".repeat(pad)),
        Span::styled(state, fg(view, colour)),
    ]);
    let mut mode = None;
    if let Some(label) = mode_label {
        let start = left + pad + state_width + STATUS_GAP.len();
        mode = Some(start as u16..(start + label.chars().count()) as u16);
        spans.push(Span::raw(STATUS_GAP));
        spans.push(Span::styled(label, fg(view, view.theme.muted)));
    }
    (Line::from(spans), mode)
}

/// A transcript's player, in the terminal's one row: its state, the playhead over the clip's
/// length, and the progress as a thin line filling left to right.
fn draw_player_bar(frame: &mut Frame, area: Rect, view: &View<'_>, duration_ms: Option<i64>) {
    let Some(player) = view.player else {
        return;
    };
    if let Some(error) = player.failed() {
        let line = hint(view, "tui.audio.failed", &[("error", &error)]);
        frame.render_widget(Paragraph::new(line), area);
        return;
    }
    let position = player.position();
    let total = duration_ms
        .map(|ms| Duration::from_millis(ms.max(0) as u64))
        .filter(|total| !total.is_zero())
        .unwrap_or_else(|| player.length());
    let state = if player.playing() { "⏸" } else { "▶" };
    let times = format!(
        "{state} {} / {} ",
        duration_text(position.as_millis() as i64),
        duration_text(total.as_millis() as i64)
    );
    let used = times.chars().count();
    let width = usize::from(area.width).saturating_sub(used);
    let fraction = if total.is_zero() {
        0.0
    } else {
        position.as_secs_f64() / total.as_secs_f64()
    };
    let mut spans = vec![Span::styled(times, fg(view, view.theme.muted))];
    spans.extend(seek_line(view, width, Fill::Done(fraction)));
    frame.render_widget(Paragraph::new(Line::from(spans)), area);
}

/// How far a progress line is filled.
enum Fill {
    /// This fraction of it, from the left.
    Done(f64),
    /// No percentage yet: a segment two fifths of the line wide, swept along it a column a step.
    Sweep(usize),
}

/// A progress line `width` columns wide, drawn like the player's seek line: `━` in the accent for
/// the filled part, `─` muted for the track.
fn seek_line(view: &View<'_>, width: usize, fill: Fill) -> Vec<Span<'static>> {
    let (start, filled) = match fill {
        Fill::Done(fraction) => (
            0,
            ((fraction.clamp(0.0, 1.0) * width as f64).round() as usize).min(width),
        ),
        Fill::Sweep(step) => {
            let segment = ((width as f64 * 0.4).round() as usize).min(width);
            (step % (width - segment + 1), segment)
        }
    };
    let track = fg(view, view.theme.muted);
    vec![
        Span::styled("─".repeat(start), track),
        Span::styled("━".repeat(filled), fg(view, view.theme.accent)),
        Span::styled("─".repeat(width - start - filled), track),
    ]
}

/// A queue row's bar, `BAR_WIDTH` columns: `━` in `colour` for each whole cell done and `╸` for a
/// half-done one, the track `─` in the border role - or, with no percentage yet, a segment two
/// fifths of the bar wide swept along it.
fn progress_bar(view: &View<'_>, fill: Fill, colour: Color) -> Vec<Span<'static>> {
    let width = usize::from(BAR_WIDTH);
    let track = fg(view, view.theme.border);
    let filled = fg(view, colour).patch(bold(view));
    match fill {
        Fill::Done(fraction) => {
            let halves = (fraction.clamp(0.0, 1.0) * width as f64 * 2.0).round() as usize;
            let whole = halves / 2;
            let half = halves % 2;
            vec![
                Span::styled(format!("{}{}", "━".repeat(whole), "╸".repeat(half)), filled),
                Span::styled("─".repeat(width - whole - half), track),
            ]
        }
        Fill::Sweep(step) => {
            let segment = (width as f64 * 0.4).round() as usize;
            let start = step % (width - segment + 1);
            vec![
                Span::styled("─".repeat(start), track),
                Span::styled("━".repeat(segment), filled),
                Span::styled("─".repeat(width - start - segment), track),
            ]
        }
    }
}

/// One frame of the stenographer's tile, in the accent role; under `NO_COLOR` plain.
fn draw_tile(frame: &mut Frame, area: Rect, view: &View<'_>, rows: &[&str]) {
    let style = fg(view, view.theme.accent);
    let lines: Vec<Line> = rows.iter().map(|row| Line::styled(*row, style)).collect();
    frame.render_widget(Paragraph::new(lines), area);
}

/// The GUI's pages as a tab bar, as the design draws it: `Queue`, `History`, `Settings`. The open
/// page sits in an accent chip, its text in the page's own background colour and no key hint; the
/// others are muted, and a tab a shortcut can open now - it is not the open one, and no dialog,
/// prompt or the key reference holds the keyboard - has its title's first letter in the page's
/// text colour and underlined, as the footer marks a live key. Shift and that letter opens it;
/// the digits and `Tab` do too. Under `NO_COLOR` the TUI writes no attribute at all, so no letter
/// is marked there either; the footer and the key reference still name it.
fn draw_tabs(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let acting = view.keys_reach_pages();
    let mut spans: Vec<Span> = Vec::new();
    for (index, tab) in tabs(view.screen.page()).into_iter().enumerate() {
        if index > 0 {
            spans.push(Span::raw("  "));
        }
        // Every tab is drawn in the chip's width, padded, so switching pages never moves a title.
        if tab.open {
            let chip = Style::new().fg(view.theme.background).bg(view.theme.accent);
            spans.push(Span::styled(format!(" {} ", tab.title), chip));
            continue;
        }
        let base = fg(view, view.theme.muted);
        let hint = if acting && !view.no_color {
            fg(view, view.theme.text).underlined()
        } else {
            base
        };
        let mut rest = tab.title.chars();
        let first = rest.next().map(String::from).unwrap_or_default();
        spans.push(Span::raw(" "));
        spans.push(Span::styled(first, hint));
        spans.push(Span::styled(rest.as_str().to_string(), base));
        spans.push(Span::raw(" "));
    }
    frame.render_widget(Paragraph::new(Line::from(spans)), area);
}

/// One tab of the bar: the open page's title in a chip, the others plain, kept in parts so the
/// draw can mark the title's first letter.
struct Tab {
    page: Page,
    title: String,
    /// The open page's tab: drawn in the accent chip, ` Title `.
    open: bool,
}

impl Tab {
    fn width(&self) -> u16 {
        (self.title.chars().count() + 2) as u16
    }
}

/// The tab bar's pieces, one per page, the open one a chip: the drawn row and the mouse's hit-test
/// share them.
fn tabs(active: Page) -> Vec<Tab> {
    Page::ALL
        .iter()
        .map(|page| {
            let key = match page {
                Page::Queue => "queue.title",
                Page::History => "history.title",
                Page::Settings => "settings.title",
            };
            Tab {
                page: *page,
                title: stt_client::text(key, &[]),
                open: *page == active,
            }
        })
        .collect()
}

/// What a mouse click landed on.
pub enum Target {
    Tab(Page),
    /// The theme mode in the header's first row: a click cycles it, as `t` does.
    Theme,
    /// The queue page's upload area.
    Upload,
    /// The upload dialog's option chip `field`.
    UploadOption(Field),
    /// The upload dialog's chooser row `index`.
    UploadChoice(usize),
    /// The upload dialog's file-browser row `index`, the `../` row counting as the first.
    UploadRow(usize),
    /// The upload dialog's mark on row `index`: a click ticks or unticks that file.
    UploadMark(usize),
    QueueRow(usize),
    HistoryRow(usize),
    SettingsRow(usize),
    /// A transcript's segment, counted over the turns as the cursor counts them.
    Segment(usize),
    /// A footer hint naming one key: a click performs that press.
    FooterHint(KeyEvent),
}

/// A footer hint a click performs: its row and the columns it covers, and the press it names.
pub struct FooterHint {
    pub row: u16,
    pub start: u16,
    pub end: u16,
    pub key: KeyEvent,
}

/// The target at `column`, `row` (zero-based), read from the same layout the draw uses.
pub fn mouse_target(view: &View<'_>, column: u16, row: u16) -> Option<Target> {
    let at = Position::new(column, row);
    if row < HEADER_ROWS {
        // The mode is on the header's first row and the tabs on its second, both right of the
        // stenographer's tile.
        let column = column.checked_sub(stenographer::SMALL_WIDTH)?;
        if row == 0 {
            let width = view.page.width.saturating_sub(stenographer::SMALL_WIDTH);
            return status_line(view, width)
                .1
                .filter(|mode| mode.contains(&column))
                .map(|_| Target::Theme);
        }
        if row != 1 {
            return None;
        }
        let mut x = 0;
        let pieces = tabs(view.screen.page());
        for (index, tab) in pieces.iter().enumerate() {
            let width = tab.width();
            if column >= x && column < x + width {
                return Some(Target::Tab(tab.page));
            }
            x += width;
            if index + 1 < pieces.len() {
                x += 2;
            }
        }
        return None;
    }
    // The upload dialog, drawn over the page: its chips, its list and a chooser's rows.
    if let Some(upload) = view.upload {
        let parts = upload_parts(view.frame, upload);
        if let Some(chooser) = upload.chooser() {
            let list = Rect {
                x: parts.chooser.x + 1,
                y: parts.chooser.y + 1,
                width: parts.chooser.width.saturating_sub(2),
                height: parts.chooser.height.saturating_sub(2),
            };
            if list.contains(at) {
                let line = (row - list.y) as usize;
                let skip = chooser
                    .cursor()
                    .saturating_sub(list.height.saturating_sub(1) as usize);
                let index = skip + line;
                if index < chooser.rows().count() {
                    return Some(Target::UploadChoice(index));
                }
            }
            return None;
        }
        if row == parts.inner.y + parts.options_row {
            let language = parts.language_col..parts.language_col + parts.language_width;
            if language.contains(&column) {
                return Some(Target::UploadOption(Field::Language));
            }
            let speakers = parts.speakers_col..parts.speakers_col + parts.speakers_width;
            if speakers.contains(&column) {
                return Some(Target::UploadOption(Field::Speakers));
            }
        }
        let first = parts.inner.y + parts.list_top;
        if row >= first && row < first + parts.list_height {
            let skip = upload
                .browser()
                .selected()
                .saturating_sub(parts.list_height.saturating_sub(1) as usize);
            let index = skip + (row - first) as usize;
            // The `[ ]`/`[✓]` mark starts the row: a click on those four columns ticks the file.
            if column < parts.inner.x + 4 {
                return Some(Target::UploadMark(index));
            }
            return Some(Target::UploadRow(index));
        }
        return None;
    }
    // A footer hint naming one key performs it, as pressing the key does.
    if let Some(key) = view
        .footer_hits
        .borrow()
        .iter()
        .find(|hit| hit.row == row && column >= hit.start && column < hit.end)
        .map(|hit| hit.key)
    {
        return Some(Target::FooterHint(key));
    }
    match view.screen {
        Screen::Queue => {
            // The page laid out as the last frame drew it: the whole upload box, then two lines a
            // row from the first row the frame showed.
            let areas = queue_layout(view, view.page);
            if areas.upload_box.contains(at) {
                return Some(Target::Upload);
            }
            if !areas.rows.contains(at) {
                return None;
            }
            let index = view.queue_offset.get() + usize::from((row - areas.rows.y) / 2);
            (index < crate::state::rows(view.snapshot).len()).then_some(Target::QueueRow(index))
        }
        Screen::History => {
            // The rows start under the controls row and the rule, from the first the list showed,
            // each taking as many lines as it draws (its own, an error, an audio note).
            let line = row.checked_sub(view.page.y + 2)? as usize;
            let rows =
                crate::state::history(view.history, view.history_search, view.history_status);
            let heights = rows.iter().map(|task| history_height(task));
            row_at(heights, view.history_offset.get(), line).map(Target::HistoryRow)
        }
        Screen::Settings(screen) => {
            // The rows sit inside the list's border, under the introduction and the probe's
            // failure the last frame drew; each is one line, from the first the list showed.
            let (intro, probe) = settings_header(screen);
            let [_, list, _] = settings_layout(view.page, &intro, probe.as_deref());
            let line = row.checked_sub(list.y + 1)? as usize;
            let rows = 0..=crate::settings::FIELDS.len();
            row_at(rows.map(|_| 1), screen.offset(), line).map(Target::SettingsRow)
        }
        // A segment, where the last frame drew it.
        Screen::Transcript(transcript) => transcript.segment_at(column, row).map(Target::Segment),
        Screen::Status(_) | Screen::SettingsFailed(_) => None,
    }
}

/// The row a click's `line` falls in, over the rows' `heights`, from `first` - the row the table
/// started showing - or `None` when the line falls past the last row.
fn row_at(heights: impl Iterator<Item = u16>, first: usize, line: usize) -> Option<usize> {
    let mut line = line;
    for (index, height) in heights.enumerate().skip(first) {
        let height = usize::from(height);
        if line < height {
            return Some(index);
        }
        line -= height;
    }
    None
}

/// The footer hints `shown` a click performs: those naming one key, with the row and columns the
/// footer packed them into.
fn footer_click_hits(
    shown: &[String],
    ranges: &[(usize, std::ops::Range<usize>)],
    area: Rect,
) -> Vec<FooterHint> {
    shown
        .iter()
        .zip(ranges)
        .filter_map(|(hint, (row, range))| {
            let key = crate::writes::hint_key(hint)?;
            Some(FooterHint {
                row: area.y + *row as u16,
                start: area.x + range.start as u16,
                end: area.x + range.end as u16,
                key,
            })
        })
        .collect()
}

/// The rows the queue page's upload box takes: its border, the chooser line and the defaults line.
const UPLOAD_BOX_ROWS: u16 = 4;
/// The most finished-task notices the queue page lists.
const NOTICES: usize = 5;

/// The queue page's areas, top to bottom, as the design lays it out.
struct QueueAreas {
    /// The CPU-fallback notice, while it stands.
    banner: Rect,
    /// An upload batch's files while they go; a batch the connection cut off waits above them.
    uploads: Rect,
    upload_box: Rect,
    header: Rect,
    rule: Rect,
    /// Two lines a task, as many as fit; the empty state's tile and words when there are none.
    rows: Rect,
    /// A blank row, the `Finished` heading and a line per notice, while there are any.
    notices: Rect,
}

fn queue_layout(view: &View<'_>, area: Rect) -> QueueAreas {
    let banner = u16::from(view.device_fallback.is_some());
    let uploads = view.uploads.len() as u16 + u16::from(view.waiting.is_some());
    let shown = view.notices.len().min(NOTICES) as u16;
    let notices = if shown == 0 { 0 } else { shown + 2 };
    // The upload box, a blank row, the header and the rule.
    let fixed = banner + uploads + UPLOAD_BOX_ROWS + 3;
    let room = area.height.saturating_sub(fixed + notices);
    let tasks = crate::state::rows(view.snapshot).len() as u16;
    let rows = if tasks == 0 {
        // The large tile, a blank row and the words.
        room.min(stenographer::LARGE[0].len() as u16 + 2)
    } else {
        room.min(tasks.saturating_mul(2))
    };
    let [
        banner,
        uploads,
        upload_box,
        _,
        header,
        rule,
        rows,
        notices,
        _,
    ] = Layout::vertical([
        Constraint::Length(banner),
        Constraint::Length(uploads),
        Constraint::Length(UPLOAD_BOX_ROWS),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(rows),
        Constraint::Length(notices),
        Constraint::Fill(1),
    ])
    .areas(area);
    QueueAreas {
        banner,
        uploads,
        upload_box,
        header,
        rule,
        rows,
        notices,
    }
}

/// The queue page as the design draws it: no outer panel - the upload box, the `Queue n tasks`
/// header with the queue's state, a rule, two lines a task, and the finished tasks under them.
fn draw_queue(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let areas = queue_layout(view, area);
    if let Some(device) = view.device_fallback {
        frame.render_widget(
            Paragraph::new(stt_client::text(
                "tui.settings.fallbackBanner",
                &[("device", device)],
            )),
            areas.banner,
        );
    }
    let waiting = view.waiting.map(|name| {
        Line::from(stt_client::text(
            "tui.upload.waiting",
            &[("filename", name)],
        ))
    });
    let progress = view.uploads.iter().map(|uploading| {
        let percent = uploading.percent();
        Line::from(stt_client::text(
            "tui.upload.progress",
            &[
                ("filename", &uploading.filename),
                (
                    "percent",
                    &stt_client::integer(i64::try_from(percent).unwrap_or(i64::MAX)),
                ),
            ],
        ))
    });
    frame.render_widget(
        Paragraph::new(waiting.into_iter().chain(progress).collect::<Vec<_>>()),
        areas.uploads,
    );
    draw_upload_box(frame, areas.upload_box, view);
    let tasks = crate::state::rows(view.snapshot);
    draw_queue_header(frame, areas.header, view, tasks.len());
    let rule = Line::from(vec![
        Span::raw("  "),
        Span::styled(
            "─".repeat(usize::from(areas.rule.width.saturating_sub(4))),
            fg(view, view.theme.border),
        ),
    ]);
    frame.render_widget(Paragraph::new(rule), areas.rule);
    if tasks.is_empty() {
        // Nothing runs while the queue is empty: its tile types as charm, as the browser's does.
        let inner = Rect {
            x: areas.rows.x + 2,
            width: areas.rows.width.saturating_sub(4),
            ..areas.rows
        };
        let below = draw_large_tile(frame, inner, view, true);
        frame.render_widget(
            Paragraph::new(hint(view, "tui.queue.empty", &[])).wrap(Wrap { trim: true }),
            below,
        );
    } else {
        let visible = usize::from((areas.rows.height / 2).max(1));
        let offset = queue_offset(view.queue_offset.get(), view.selected, visible, tasks.len());
        // The first row drawn, for the mouse's hit-test.
        view.queue_offset.set(offset);
        let columns = row_columns(areas.rows.width);
        let on_rows = !view.upload_focused && view.notice_focused.is_none();
        for (line, (index, task)) in tasks
            .iter()
            .enumerate()
            .skip(offset)
            .take(visible)
            .enumerate()
        {
            let y = areas.rows.y + 2 * line as u16;
            let row = Rect {
                y,
                height: areas.rows.bottom().saturating_sub(y).min(2),
                ..areas.rows
            };
            let selected = on_rows && index == view.selected;
            draw_queue_row(frame, row, view, task, selected, &columns);
        }
    }
    draw_notices(frame, areas.notices, view);
}

/// The first queue row shown: the one shown before, moved only as far as keeps `selected` among
/// the `visible` rows.
fn queue_offset(previous: usize, selected: usize, visible: usize, count: usize) -> usize {
    let offset = if selected < previous {
        selected
    } else if selected >= previous + visible {
        selected + 1 - visible
    } else {
        previous
    };
    offset.min(count.saturating_sub(visible))
}

/// The upload box, two columns in from each side: its accent border titled `Upload`, the chooser
/// line and the defaults new uploads take, tinted while it has the focus (marked with `>` under
/// `NO_COLOR`).
fn draw_upload_box(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let area = Rect {
        x: area.x + 2,
        width: area.width.saturating_sub(4),
        ..area
    };
    let border = fg(view, view.theme.accent);
    let strong = fg(view, view.theme.text).patch(bold(view));
    let block = Block::bordered()
        .border_type(BorderType::Rounded)
        .border_style(border)
        .title(Line::from(vec![
            Span::styled("─ ", border),
            Span::styled(stt_client::text("tui.upload.areaTitle", &[]), strong),
            Span::raw(" "),
        ]));
    let inner = block.inner(area);
    frame.render_widget(block, area);
    if view.upload_focused {
        frame.buffer_mut().set_style(inner, highlight(view));
    }
    let icon = if view.upload_focused && view.no_color {
        ">  "
    } else {
        "⇪  "
    };
    let mut choose = vec![
        Span::styled(icon, border),
        Span::styled(stt_client::text("tui.upload.choose", &[]), strong),
        Span::styled("  —  ", fg(view, view.theme.border)),
    ];
    choose.extend(muted_hint(view, "tui.upload.chooseKeys", &[]).spans);
    let (language, speakers, max) = match view.settings {
        Some(settings) => (
            settings.default_language.clone(),
            match (settings.speakers_mode.as_str(), settings.speakers_count) {
                ("fixed", Some(count)) => count.to_string(),
                (mode, _) => mode.to_string(),
            },
            settings.max_speakers.to_string(),
        ),
        // The contract's defaults, until the settings are read.
        None => ("auto".to_string(), "auto".to_string(), "8".to_string()),
    };
    let mut defaults = vec![
        Span::raw("   "),
        Span::styled(
            format!("{} ", stt_client::text("tui.upload.defaultsLabel", &[])),
            fg(view, view.theme.muted),
        ),
        Span::styled(
            stt_client::text(
                "tui.upload.defaultsValues",
                &[
                    ("language", &language),
                    ("speakers", &speakers),
                    ("max", &max),
                ],
            ),
            fg(view, view.theme.text),
        ),
        Span::raw("     "),
    ];
    defaults.extend(muted_hint(view, "tui.upload.change", &[]).spans);
    let content = Rect {
        x: inner.x + 1,
        width: inner.width.saturating_sub(2),
        ..inner
    };
    frame.render_widget(
        Paragraph::new(vec![Line::from(choose), Line::from(defaults)]),
        content,
    );
}

/// `  Queue n tasks`, and at the right the queue's state - `● Running` in the done colour or
/// `● Paused` muted - with the key that turns it, except while Space opens the upload dialog.
fn draw_queue_header(frame: &mut Frame, area: Rect, view: &View<'_>, tasks: usize) {
    let count = tasks.to_string();
    let left = Line::from(vec![
        Span::raw("  "),
        Span::styled(
            stt_client::text("queue.title", &[]),
            fg(view, view.theme.text).patch(bold(view)),
        ),
        Span::raw(" "),
        Span::styled(
            stt_client::text("tui.queue.tasks", &[("count", &count)]),
            fg(view, view.theme.muted),
        ),
    ]);
    frame.render_widget(Paragraph::new(left), area);
    let paused = view.snapshot.is_some_and(|snapshot| snapshot.queue.paused);
    let (state, colour, key) = if paused {
        (
            "tui.queue.statePaused",
            view.theme.muted,
            "tui.queue.spaceResume",
        )
    } else {
        (
            "tui.queue.stateRunning",
            view.theme.done,
            "tui.queue.spacePause",
        )
    };
    let mut right = vec![Span::styled(
        format!("{:<12}", format!("● {}", stt_client::text(state, &[]))),
        fg(view, colour),
    )];
    if !view.upload_focused {
        right.extend(muted_hint(view, key, &[]).spans);
    }
    let x = area.width.saturating_sub(28);
    let right_area = Rect {
        x: area.x + x,
        width: area.width - x,
        ..area
    };
    frame.render_widget(Paragraph::new(Line::from(right)), right_area);
}

/// A queue row's two lines in `area`: the selection bar, the name, the status - a running task's
/// phase and time, or its status chip - the bar and the percentage; then its language and size and,
/// while diarizing, the speakers stage with its two-stage bar. The selected row is tinted across
/// the page (marked with `>` under `NO_COLOR`).
fn draw_queue_row(
    frame: &mut Frame,
    area: Rect,
    view: &View<'_>,
    task: &Task,
    selected: bool,
    columns: &RowColumns,
) {
    if selected {
        frame.buffer_mut().set_style(area, highlight(view));
    }
    let running = task.status == "RUNNING";
    let mut first = vec![Span::styled(
        match (selected, view.no_color) {
            (true, true) => "> ",
            (true, false) => " ▍",
            (false, _) => "  ",
        },
        fg(view, view.theme.accent),
    )];
    let name_style = if selected {
        fg(view, view.theme.text).patch(bold(view))
    } else {
        fg(view, view.theme.text)
    };
    first.push(Span::styled(
        ellipsized(&task.filename, usize::from(columns.name_width)),
        name_style,
    ));
    let status = row_status(view, task, columns);
    let buffer = frame.buffer_mut();
    buffer.set_line(area.x, area.y, &Line::from(first), columns.status);
    buffer.set_span(
        area.x + columns.status,
        area.y,
        &status,
        columns
            .bar
            .unwrap_or(columns.percent)
            .saturating_sub(columns.status),
    );
    if let Some(x) = columns.bar {
        let fill = if running && task.phase.as_deref() == Some("CHUNKING") {
            Fill::Sweep(view.stenographer)
        } else {
            Fill::Done(task.transcription_progress)
        };
        let colour = if running {
            view.theme.accent
        } else {
            view.theme.muted
        };
        let bar = Line::from(progress_bar(view, fill, colour));
        buffer.set_line(area.x + x, area.y, &bar, BAR_WIDTH);
    }
    let percent = format!(
        "{:>width$}",
        format!(
            "{}%",
            stt_client::integer((task.transcription_progress * 100.0).round() as i64)
        ),
        width = usize::from(PERCENT_WIDTH)
    );
    let percent_style = if running {
        fg(view, view.theme.text).patch(bold(view))
    } else {
        fg(view, view.theme.muted)
    };
    buffer.set_span(
        area.x + columns.percent,
        area.y,
        &Span::styled(percent, percent_style),
        PERCENT_WIDTH,
    );
    if area.height < 2 {
        return;
    }
    let y = area.y + 1;
    let language = if task.language.is_empty() {
        "-"
    } else {
        task.language.as_str()
    };
    buffer.set_span(
        area.x + 2,
        y,
        &Span::styled(
            ellipsized(
                &format!("{language} · {}", size(task.size_bytes)),
                usize::from(columns.name_width),
            ),
            fg(view, view.theme.muted),
        ),
        columns.name_width,
    );
    // While diarizing, the browser's two-stage bar: embedding fills the left half, clustering the
    // right, and the stage names itself with its own percentage.
    if running
        && task.phase.as_deref() == Some("DIARIZING")
        && let Some(progress) = task.diarization_progress
    {
        draw_speakers_stage(
            buffer,
            Rect {
                y,
                height: 1,
                ..area
            },
            view,
            task.diarization_stage.as_deref(),
            progress,
            columns,
        );
    }
}

/// A queue row's status: a running task's phase and time in the accent where the columns leave room
/// for it, otherwise the status as a chip in its group's colours.
fn row_status(view: &View<'_>, task: &Task, columns: &RowColumns) -> Span<'static> {
    let line = running_line(task, view.now_ms);
    if task.status == "RUNNING" && columns.phase && !line.is_empty() {
        return Span::styled(line, fg(view, view.theme.accent));
    }
    status_chip(view, &task.status, usize::from(status_word_width()))
}

/// A task's status as a chip: the word between two spaces - padded to `width` - in its group's
/// text colour on its group's background (ADR 0033); plain under `NO_COLOR`.
fn status_chip(view: &View<'_>, status: &str, width: usize) -> Span<'static> {
    let word = crate::state::status_word(status);
    let chip = format!(" {word:<width$} ");
    if view.no_color {
        Span::raw(chip)
    } else {
        Span::styled(
            chip,
            Style::new()
                .fg(view.theme.status(status))
                .bg(view.theme.status_background(status)),
        )
    }
}

/// A diarizing row's speakers stage on its line `area`: `Speakers` in the done colour and the
/// stage with its own percentage at the status column, and the two-stage bar at the bar's.
fn draw_speakers_stage(
    buffer: &mut ratatui::buffer::Buffer,
    area: Rect,
    view: &View<'_>,
    stage: Option<&str>,
    progress: f64,
    columns: &RowColumns,
) {
    let (index, key) = match stage {
        Some("CLUSTERING") => (1.0, Some("transcript.diarization.clustering")),
        Some("EMBEDDING") => (0.0, Some("transcript.diarization.embedding")),
        _ => (0.0, None),
    };
    let mut label = vec![Span::styled(
        format!("{:<10}", stt_client::text("queue.speakers", &[])),
        fg(view, view.theme.done),
    )];
    if let Some(key) = key {
        label.push(Span::styled(
            stt_client::text(
                "transcript.diarization.value",
                &[
                    ("stage", &stt_client::text(key, &[])),
                    (
                        "percent",
                        &stt_client::integer((progress * 100.0).round() as i64),
                    ),
                ],
            ),
            fg(view, view.theme.muted),
        ));
    }
    let end = columns.bar.unwrap_or(area.width);
    buffer.set_line(
        area.x + columns.status,
        area.y,
        &Line::from(label),
        end.saturating_sub(columns.status),
    );
    if let Some(x) = columns.bar {
        let bar = progress_bar(view, Fill::Done((index + progress) / 2.0), view.theme.done);
        buffer.set_line(area.x + x, area.y, &Line::from(bar), BAR_WIDTH);
    }
}

/// The finished tasks' notices, newest first, under a blank row and their `Finished` heading: each
/// its mark in its status's colour, the file name, and the rest muted with its key in the accent.
/// The one the keyboard is on is tinted (marked with `>` under `NO_COLOR`).
fn draw_notices(frame: &mut Frame, area: Rect, view: &View<'_>) {
    if area.height < 2 {
        return;
    }
    let heading = Line::from(vec![
        Span::raw("  "),
        Span::styled(
            stt_client::text("tui.queue.finished", &[]),
            fg(view, view.theme.text).patch(bold(view)),
        ),
    ]);
    frame
        .buffer_mut()
        .set_line(area.x, area.y + 1, &heading, area.width);
    for (index, notice) in view.notices.iter().take(NOTICES).enumerate() {
        let y = area.y + 2 + index as u16;
        if y >= area.bottom() {
            break;
        }
        let focused = view.notice_focused == Some(index);
        if focused {
            frame.buffer_mut().set_style(
                Rect {
                    y,
                    height: 1,
                    ..area
                },
                highlight(view),
            );
        }
        let (mark, colour, key) = match notice.status.as_str() {
            "COMPLETED" => ("✓", view.theme.done, "tui.notice.transcribedRest"),
            "FAILED" => ("✗", view.theme.danger, "tui.notice.failedRest"),
            "DUPLICATE" => ("≡", view.theme.accent, "tui.notice.duplicateRest"),
            _ => ("⊘", view.theme.muted, "tui.notice.canceledRest"),
        };
        let mut spans = vec![
            Span::raw(if focused && view.no_color { "> " } else { "  " }),
            Span::styled(format!("{mark} "), fg(view, colour)),
            Span::styled(notice.filename.clone(), fg(view, view.theme.text)),
            Span::raw(" "),
        ];
        spans.extend(muted_hint(view, key, &[]).spans);
        frame
            .buffer_mut()
            .set_line(area.x, y, &Line::from(spans), area.width);
    }
}

/// The History search field's width, as the design draws it.
const SEARCH_WIDTH: u16 = 28;
/// Where a History row's name starts, after the selection bar and the status chip.
const HISTORY_NAME: u16 = 15;
/// Where a History row's stored error and its audio note start, under the name.
const HISTORY_NOTE: u16 = 17;
/// How far from the page's right edge a History row's date starts.
const HISTORY_DATE_FROM_RIGHT: u16 = 39;

/// The finished tasks as the design draws them: no panel; the controls row - the search and its
/// field, the status filter and its chip, how many tasks show - a rule, and one line a task (its
/// status chip, its name, its date and duration) with its error and audio note under it, the
/// selected row tinted across the page.
fn draw_history(frame: &mut Frame, area: Rect, view: &View<'_>) {
    let tasks = crate::state::finished(view.history);
    if tasks.is_empty() {
        let inner = Rect {
            x: area.x + 2,
            width: area.width.saturating_sub(4),
            ..area
        };
        let below = draw_large_tile(frame, inner, view, false);
        frame.render_widget(
            Paragraph::new(stt_client::text("tui.history.empty", &[])).wrap(Wrap { trim: true }),
            below,
        );
        return;
    }
    let rows = crate::state::history(view.history, view.history_search, view.history_status);
    let [controls, rule, list] = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Fill(1),
    ])
    .areas(area);
    draw_history_controls(frame, controls, view, rows.len(), tasks.len());
    let rule_line = Line::from(vec![
        Span::raw("  "),
        Span::styled(
            "─".repeat(usize::from(rule.width.saturating_sub(4))),
            fg(view, view.theme.border),
        ),
    ]);
    frame.render_widget(Paragraph::new(rule_line), rule);
    if rows.is_empty() {
        let words = Rect {
            x: list.x + 2,
            width: list.width.saturating_sub(4),
            ..list
        };
        frame.render_widget(
            Paragraph::new(stt_client::text("tui.history.noMatches", &[]))
                .wrap(Wrap { trim: true }),
            words,
        );
        return;
    }
    let heights: Vec<u16> = rows.iter().map(|task| history_height(task)).collect();
    let offset = history_offset(
        view.history_offset.get(),
        view.history_selected,
        &heights,
        list.height,
    );
    // The first row drawn, for the mouse's hit-test.
    view.history_offset.set(offset);
    let mut y = list.y;
    for (index, task) in rows.iter().enumerate().skip(offset) {
        if y >= list.bottom() {
            break;
        }
        let height = heights[index].min(list.bottom() - y);
        let row = Rect { y, height, ..list };
        draw_history_row(frame, row, view, task, index == view.history_selected);
        y += height;
    }
}

/// The first History row shown: the one shown before, moved only as far as keeps the `selected`
/// row's lines - `heights` a row - inside `height` lines.
fn history_offset(previous: usize, selected: usize, heights: &[u16], height: u16) -> usize {
    let mut offset = previous.min(selected);
    while offset < selected && heights[offset..=selected].iter().sum::<u16>() > height {
        offset += 1;
    }
    offset
}

/// History's controls row: `/ Search` and the search field on the raised surface (the cursor in it
/// while a search is typed), `f Status` and the filter's chip, and how many of the finished tasks
/// show - the keys in the accent, the words muted.
fn draw_history_controls(
    frame: &mut Frame,
    area: Rect,
    view: &View<'_>,
    shown: usize,
    total: usize,
) {
    let muted = fg(view, view.theme.muted);
    let key = fg(view, view.theme.accent);
    let raised = if view.no_color {
        Style::new()
    } else {
        Style::new()
            .fg(view.theme.text)
            .bg(view.theme.surface_raised)
    };
    let field: String = format!(" {}", view.history_search)
        .chars()
        .chain(std::iter::repeat(' '))
        .take(usize::from(SEARCH_WIDTH))
        .collect();
    let chip = format!(" {} ▾  ", stt_client::text(view.history_status.key(), &[]));
    let search = format!(" {}  ", stt_client::text("history.search", &[]));
    // Where the field starts: after the margin, the key and its word.
    let field_column = 3 + search.chars().count() as u16;
    let line = Line::from(vec![
        Span::raw("  "),
        Span::styled("/", key),
        Span::styled(search, muted),
        Span::styled(field, raised),
        Span::raw("     "),
        Span::styled("f", key),
        Span::styled(
            format!(" {}  ", stt_client::text("history.status", &[])),
            muted,
        ),
        Span::styled(chip, raised),
        Span::raw("        "),
        Span::styled(
            stt_client::text(
                "tui.history.count",
                &[("shown", &shown.to_string()), ("total", &total.to_string())],
            ),
            muted,
        ),
    ]);
    frame.render_widget(Paragraph::new(line), area);
    if view.mode == crate::input::Mode::Search {
        // The cursor after the query, inside the field: the search prompt's caret.
        let column = field_column + 1 + view.history_search.chars().count() as u16;
        cursor(frame, area, 0, column.min(field_column + SEARCH_WIDTH - 1));
    }
}

/// A History row in `area`: the selection bar, the status chip in its group's colours, the name -
/// with the playing clip's note after it - and the date and duration; under it the stored error and
/// a missing clip's note. The selected row is tinted across the page (marked with `>` under
/// `NO_COLOR`).
fn draw_history_row(frame: &mut Frame, area: Rect, view: &View<'_>, task: &Task, selected: bool) {
    if selected {
        frame.buffer_mut().set_style(area, highlight(view));
    }
    let date_column = area.width.saturating_sub(HISTORY_DATE_FROM_RIGHT);
    let name_width = date_column.saturating_sub(HISTORY_NAME + 2);
    // The status names padded to the longest one's width, so the names line up.
    let chip = status_chip(view, &task.status, usize::from(status_word_width()));
    let name_style = if selected {
        fg(view, view.theme.text).patch(bold(view))
    } else {
        fg(view, view.theme.text)
    };
    let mut name = vec![Span::styled(task.filename.clone(), name_style)];
    // The row whose clip plays (Space on History) says so, in the accent; one that cannot play - no
    // audio device - says why, as the transcript's bar does.
    if view.clip == Some(task.id)
        && let Some(player) = view.player
    {
        if let Some(error) = player.failed() {
            name.push(Span::styled(
                format!(
                    "   {}",
                    stt_client::text("tui.audio.failed", &[("error", &error)])
                ),
                fg(view, view.theme.danger),
            ));
        } else if player.playing() {
            name.push(Span::styled(
                format!("   {}", stt_client::text("tui.history.playing", &[])),
                fg(view, view.theme.accent),
            ));
        }
    }
    let buffer = frame.buffer_mut();
    buffer.set_span(
        area.x,
        area.y,
        &Span::styled(
            match (selected, view.no_color) {
                (true, true) => "> ",
                (true, false) => " ▍",
                (false, _) => "  ",
            },
            fg(view, view.theme.accent),
        ),
        2,
    );
    buffer.set_span(area.x + 2, area.y, &chip, HISTORY_NAME - 2);
    buffer.set_line(area.x + HISTORY_NAME, area.y, &Line::from(name), name_width);
    buffer.set_span(
        area.x + date_column,
        area.y,
        &Span::styled(history_meta(task), fg(view, view.theme.muted)),
        area.width.saturating_sub(date_column),
    );
    let note_width = area.width.saturating_sub(HISTORY_NOTE + 2);
    let mut y = area.y + 1;
    let mut note = |text: String, style: Style| {
        if y < area.bottom() {
            buffer.set_span(
                area.x + HISTORY_NOTE,
                y,
                &Span::styled(ellipsized(&text, usize::from(note_width)), style),
                note_width,
            );
            y += 1;
        }
    };
    if let Some(error) = &task.error {
        note(format!("⚠ {error}"), fg(view, view.theme.danger));
    }
    // No stored clip: the row has nothing to play, and says why in the browser's words.
    if !task.has_audio {
        note(
            stt_client::text("transcript.noAudio", &[]),
            fg(view, view.theme.muted),
        );
    }
}

/// A task's finish date and duration, the browser's meta line: `Oct 9, 2026 · 19:45`, each only
/// when the service reported it.
fn history_meta(task: &Task) -> String {
    let mut parts: Vec<String> = Vec::new();
    if let Some(finished) = task.finished_at
        && let Some(date) = finish_date(finished)
    {
        parts.push(date);
    }
    if let Some(duration) = task.duration_ms {
        parts.push(duration_text(duration));
    }
    parts.join(" · ")
}

/// How many lines a History row takes: its own, then one each for a stored error and a missing
/// clip's note.
fn history_height(task: &Task) -> u16 {
    1 + u16::from(task.error.is_some()) + u16::from(!task.has_audio)
}

/// A finish time as the app's language writes its medium date: `Oct 9, 2026`, `9. Okt. 2026`,
/// `2026/10/09`, on the API's UTC date.
fn finish_date(ms: i64) -> Option<String> {
    Some(stt_client::date_medium(ms))
}

/// A duration as `mm:ss` under an hour and `h:mm:ss` from one, as the browser writes it.
fn duration_text(ms: i64) -> String {
    stt_client::duration(ms)
}

/// The large tile at the top of [area], resting or typing, and the area under it for the words -
/// the empty states and the loading region put their label there.
fn draw_large_tile(frame: &mut Frame, area: Rect, view: &View<'_>, typing: bool) -> Rect {
    let step = if typing { view.stenographer } else { 0 };
    let large = &stenographer::LARGE[step % stenographer::LARGE.len()];
    let tall = large.len() as u16;
    if area.height > tall {
        let [tile, below] =
            Layout::vertical([Constraint::Length(tall), Constraint::Fill(1)]).areas(area);
        draw_tile(frame, tile, view, large);
        below
    } else {
        area
    }
}

/// The label column's width on the settings page: the longest name the app's language writes, so
/// no label is ever clipped; the values start a column after it.
fn label_width() -> u16 {
    crate::settings::FIELDS
        .iter()
        .map(|field| field.label().chars().count())
        .max()
        .unwrap_or(15) as u16
        + 1
}
/// The most options a dropdown shows at once; a longer list scrolls with its cursor.
const DROPDOWN_ROWS: usize = 8;

/// A Settings row's name - or the restore row's words: in the text colour and bold while the row
/// is selected, muted otherwise, as the design draws them.
fn settings_name_style(view: &View<'_>, selected: bool) -> Style {
    if selected {
        fg(view, view.theme.text).patch(bold(view))
    } else {
        fg(view, view.theme.muted)
    }
}

/// The settings, one row per setting with its value and the restore row under them, the selected
/// row highlighted, a number or the terminal command being edited in its row with a bar for the
/// cursor and its keys beside it, and an open dropdown floating over the rows below it.
fn draw_settings(frame: &mut Frame, area: Rect, view: &View<'_>, screen: &SettingsScreen) {
    let rows = FIELDS
        .iter()
        .enumerate()
        .map(|(index, field)| {
            let selected = index == screen.row;
            let name = settings_name_style(view, selected);
            Row::new([
                Cell::from(format!(
                    "{}{}",
                    marker(selected, view.no_color),
                    field.label()
                ))
                .style(name),
                Cell::from(value_cell(view, screen, *field)),
            ])
        })
        // The restore row, as the browser's button sits under its fields: its words in the value
        // column, where the eye already reads what each row does - muted, as the settings' names
        // are, until it is selected.
        .chain(std::iter::once(Row::new([
            Cell::from(marker(screen.on_restore(), view.no_color)),
            Cell::from(stt_client::text("settings.restore", &[]))
                .style(settings_name_style(view, screen.on_restore())),
        ])));
    let (intro_text, probe) = settings_header(screen);
    let [intro, list, described] = settings_layout(area, &intro_text, probe.as_deref());
    let mut intro_lines = vec![Line::styled(intro_text, fg(view, view.theme.muted))];
    if let Some(probe) = probe {
        intro_lines.push(Line::styled(probe, fg(view, view.theme.danger)));
    }
    frame.render_widget(Paragraph::new(intro_lines).wrap(Wrap { trim: true }), intro);
    // While a save waits for its answer the list's title says so, as the browser's status line
    // does - in the title, so the rows do not move under the cursor or the mouse meanwhile.
    let mut title = stt_client::text("settings.title", &[]);
    if screen.saving() {
        title = format!("{title} · {}", stt_client::text("settings.saving", &[]));
    }
    let table = Table::new(
        rows,
        [Constraint::Length(label_width()), Constraint::Fill(1)],
    )
    .block(panel(view, title))
    .row_highlight_style(highlight(view));
    let mut state = TableState::default().with_selected(Some(screen.row));
    frame.render_stateful_widget(table, list, &mut state);
    // Where the list starts showing, for the mouse's hit-test.
    screen.set_offset(state.offset());
    // A number or the terminal command being edited: the cursor after the draft, in the row's
    // value column (the block's border, the label's column and the table's spacing aside).
    if let Some(draft) = screen.number().or_else(|| screen.terminal()) {
        let column = 2 + label_width() + draft.chars().count() as u16;
        let row = list_line(list, screen.row, state.offset()).saturating_sub(list.y);
        cursor(frame, list, row, column);
    }
    frame.render_widget(
        Paragraph::new(description_line(screen)).wrap(Wrap { trim: true }),
        described,
    );
    if let Some(dropdown) = screen.dropdown() {
        draw_dropdown(
            frame,
            list,
            list_line(list, screen.row, state.offset()),
            view,
            screen,
            dropdown,
        );
    }
}

/// The settings page's areas, top to bottom: the browser's introduction `intro` and, while one
/// stands, the device probe's failure `probe`, as tall as they wrap to the page's width; the
/// settings list; and the selected row's description, two lines at the bottom so even a narrow
/// terminal shows it whole. The list takes what is left.
fn settings_layout(area: Rect, intro: &str, probe: Option<&str>) -> [Rect; 3] {
    let intro = wrapped_rows(intro, area.width);
    let probe = probe.map_or(0, |line| wrapped_rows(line, area.width));
    Layout::vertical([
        Constraint::Length(intro + probe),
        Constraint::Min(3),
        Constraint::Length(2),
    ])
    .areas(area)
}

/// The device probe's failure, as the browser's callout names it, while one stands.
fn settings_probe(screen: &SettingsScreen) -> Option<String> {
    screen
        .settings
        .device_probe_error
        .as_deref()
        .map(|error| stt_client::text("tui.settings.probeFailed", &[("error", error)]))
}

/// The settings page's header: the browser's introduction, and the device probe's failure while
/// one stands.
fn settings_header(screen: &SettingsScreen) -> (String, Option<String>) {
    (settings_intro(), settings_probe(screen))
}

/// A row's value column: the number or the terminal command being edited in the selected row (with
/// a bar for the cursor and the keys beside it), else the setting's value - and after either, in
/// the danger colour, the setting's message while one stands in its row.
fn value_cell(
    view: &View<'_>,
    screen: &SettingsScreen,
    field: crate::settings::Field,
) -> Line<'static> {
    let selected = screen.field() == Some(field);
    let mut line = match (selected, screen.number(), screen.terminal()) {
        (true, Some(draft), _) => draft_line(draft, hint(view, "tui.settings.numberHint", &[])),
        (true, _, Some(draft)) => draft_line(
            draft,
            hint(
                view,
                "tui.settings.terminalHint",
                &[("default", &screen.default_terminal_label())],
            ),
        ),
        _ => Line::from(field.value(screen)),
    };
    if let Some(message) = screen.message(field) {
        line.spans.push(Span::styled(
            format!("  {message}"),
            fg(view, view.theme.danger),
        ));
    }
    line
}

/// A draft being edited in its row, its keys beside it. The bar sits where the draft ends: the
/// terminal's cursor is drawn on that cell, and the tests read the bar as the place typing goes.
fn draft_line(draft: &str, mut keys: Line<'static>) -> Line<'static> {
    keys.spans.insert(0, Span::raw(format!("{draft}▏  ")));
    keys
}

/// The selected row's description: its label and the catalog's sentence for it - the restore row's
/// what a restore does.
fn description_line(screen: &SettingsScreen) -> String {
    match screen.field() {
        Some(field) => format!("{} — {}", field.label(), field.description()),
        None => format!(
            "{} — {}",
            stt_client::text("settings.restore", &[]),
            stt_client::text("settings.confirmBody", &[])
        ),
    }
}

/// The screen line of the settings row `row` inside `list`, after the table's own scrolling.
fn list_line(list: Rect, row: usize, offset: usize) -> u16 {
    list.y + 1 + row.saturating_sub(offset) as u16
}

/// A dropdown under the value of the settings row on `line`, inside `page`: titled with the
/// setting, the stored option ticked, the cursor's highlighted, and its keys on its last line. It
/// opens above the row when there is more room there.
fn draw_dropdown(
    frame: &mut Frame,
    page: Rect,
    line: u16,
    view: &View<'_>,
    screen: &SettingsScreen,
    dropdown: &Dropdown,
) {
    let Some(field) = screen.field() else {
        return;
    };
    let title = field.label();
    let mut hint = hint(view, dropdown.hint_key(), &[]);
    // What is typed to narrow the list, before its keys.
    if dropdown.filtering && !dropdown.filter.is_empty() {
        hint.spans.insert(
            0,
            Span::raw(format!(
                "{}  ",
                stt_client::text(
                    "tui.settings.dropdownFilter",
                    &[("filter", &dropdown.filter)]
                )
            )),
        );
    }
    let shown = dropdown.shown();
    let x = page.x + 1 + label_width() + 1;
    let widest = shown
        .iter()
        .map(|index| dropdown.options[*index].chars().count() + 2)
        .chain([title.chars().count() + 2, hint.width()])
        .max()
        .unwrap_or(0) as u16;
    let width = (widest + 2).min(page.right().saturating_sub(x));
    let below = page.bottom().saturating_sub(line + 1);
    let above = line.saturating_sub(page.y);
    let wanted = shown.len().min(DROPDOWN_ROWS) as u16 + 3;
    let (y, height) = if wanted <= below || below >= above {
        (line + 1, wanted.min(below))
    } else {
        let height = wanted.min(above);
        (line - height, height)
    };
    if height < 4 || width < 4 {
        return;
    }
    let area = Rect {
        x,
        y,
        width,
        height,
    };
    clear(frame, view, area);
    let block = panel(view, title);
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let [list, keys] = Layout::vertical([Constraint::Fill(1), Constraint::Length(1)]).areas(inner);
    let rows = shown.iter().enumerate().map(|(row, index)| {
        let tick = if Some(*index) == dropdown.current {
            "✓"
        } else {
            " "
        };
        Row::new([format!(
            "{}{tick} {}",
            marker(row == dropdown.cursor, view.no_color),
            dropdown.options[*index]
        )])
    });
    let options = Table::new(rows, [Constraint::Fill(1)]).row_highlight_style(highlight(view));
    let mut state = TableState::default().with_selected(Some(dropdown.cursor));
    frame.render_stateful_widget(options, list, &mut state);
    frame.render_widget(Paragraph::new(hint), keys);
}

/// A transcript: its speakers, the selected one highlighted and the one marked for a merge labelled
/// so, then one line per turn - its speaker's name, its start and its text.
fn draw_transcript(frame: &mut Frame, area: Rect, view: &View<'_>, transcript: &Transcript) {
    // While the turns are read - or after the read failed - the page shows the task's header and
    // what is happening, instead of an empty transcript.
    if transcript.is_loading() || transcript.load_error().is_some() {
        draw_transcript_waiting(frame, area, view, transcript);
        return;
    }
    // The player's bar takes the page's last row once playback has started.
    let area = match view.player {
        Some(_) => {
            let [turns, bar] =
                Layout::vertical([Constraint::Min(3), Constraint::Length(1)]).areas(area);
            draw_player_bar(frame, bar, view, transcript.task.duration_ms);
            turns
        }
        None => area,
    };
    let title = stt_client::text(
        "tui.transcript.title",
        &[("filename", &transcript.task.filename)],
    );
    let Transcript { turns, marked, .. } = transcript;
    let speaker = transcript.speaker_index();
    let speaker_focused = transcript.speaker_focus();
    let current = transcript.position();
    // The segment the playhead is in, marked as the browser marks its current one: an accent fill
    // with accent text.
    let playing = view
        .player
        .filter(|player| player.length() > Duration::ZERO)
        .and_then(|player| transcript.playing(player.position()));
    // What a standing mark means and what to do next, in place: a marked speaker for a merge, or
    // the segments marked for a reassignment.
    let guidance = match (*marked, transcript.marked_segment_count()) {
        (Some(id), _) => turns
            .speakers
            .iter()
            .find(|entry| entry.speaker_id == id)
            .map(|entry| {
                hint(
                    view,
                    "tui.transcript.mergeHint",
                    &[("name", &entry.speaker)],
                )
            }),
        (None, count) if count > 0 => Some(hint(
            view,
            "tui.transcript.reassignHint",
            &[("count", &count.to_string())],
        )),
        _ => None,
    };
    let guidance_rows = guidance.as_ref().map_or(0, |line| {
        let text: String = line
            .spans
            .iter()
            .map(|span| span.content.as_ref())
            .collect();
        wrapped_rows(&text, area.width)
    });
    let [header, speaker_area, guidance_area, body] = Layout::vertical([
        // The task's header - file name, finish date, duration - above what it heads.
        Constraint::Length(1),
        // One row per speaker inside the list's border; no panel at all with speakers off.
        Constraint::Length(match turns.speakers.is_empty() {
            true => 0,
            false => turns.speakers.len() as u16 + 2,
        }),
        Constraint::Length(guidance_rows),
        Constraint::Min(3),
    ])
    .areas(area);
    frame.render_widget(
        Paragraph::new(Line::styled(
            header_text(&transcript.task),
            fg(view, view.theme.muted),
        )),
        header,
    );
    let speakers = Table::new(
        turns.speakers.iter().enumerate().map(|(index, entry)| {
            let tint = view
                .theme
                .speaker(index)
                .map_or_else(Style::new, |colour| fg(view, colour));
            Row::new([Cell::from(if Some(entry.speaker_id) == *marked {
                stt_client::text("tui.speakers.merging", &[("name", &entry.speaker)])
            } else if Some(entry.speaker_id) == transcript.marked_speaker() {
                format!(
                    "{}{} {}",
                    marker(index == speaker && speaker_focused, view.no_color),
                    entry.speaker,
                    stt_client::text("tui.speakers.marked", &[])
                )
            } else {
                format!(
                    "{}{}",
                    marker(index == speaker && speaker_focused, view.no_color),
                    entry.speaker
                )
            })
            .style(tint)])
        }),
        [Constraint::Fill(1)],
    )
    .block(panel(
        view,
        stt_client::text("transcript.speakers.label", &[]),
    ))
    .row_highlight_style(highlight(view));
    let mut state = TableState::default().with_selected(speaker_focused.then_some(speaker));
    frame.render_stateful_widget(speakers, speaker_area, &mut state);
    if let Some(line) = guidance {
        frame.render_widget(
            Paragraph::new(line).wrap(Wrap { trim: true }),
            guidance_area,
        );
    }
    let inner = Rect {
        x: body.x + 1,
        y: body.y + 1,
        width: body.width.saturating_sub(2),
        height: body.height.saturating_sub(2),
    };
    let mut lines: Vec<Line> = Vec::new();
    // For each line, the segment each column shows: what a click lands on.
    let mut hits: Vec<Vec<Option<usize>>> = Vec::new();
    // Each turn's rows and segment count: the follow and page maths work in rows.
    let mut blocks: Vec<(u16, usize)> = Vec::new();
    // The segments counted over the turns, as the cursor counts them.
    let mut flat = 0usize;
    for (turn_index, turn) in turns.turns.iter().enumerate() {
        let wrapped = turn_rows(
            view,
            transcript,
            turn_index,
            flat,
            current,
            playing,
            inner.width,
        );
        flat += turn.segments.len();
        blocks.push((wrapped.len() as u16, turn.segments.len().max(1)));
        for row in &wrapped {
            hits.push(row.iter().map(|&(_, _, segment)| segment).collect());
            lines.push(Line::from(spans_of(row)));
        }
        if turn_index + 1 < turns.turns.len() {
            hits.push(Vec::new());
            lines.push(Line::default());
        }
    }
    transcript.set_hits(inner, hits);
    // The cursor's turn keeps the view following it: the rows the turns before it take, as drawn.
    if let Some((turn, _)) = current
        && let Some((rows, _)) = blocks.get(turn)
    {
        let start: u16 = blocks[..turn].iter().map(|(rows, _)| rows + 1).sum();
        transcript.follow(start, start + rows, inner.height);
    }
    // A page holds the segments of the turns that start in the view: blank rows and wrapped lines
    // count, so paging moves about a screenful of turns rather than of segment rows.
    let mut used = 0u16;
    let mut page = 0usize;
    for &(rows, segments) in &blocks {
        if page > 0 && used + 1 > inner.height {
            break;
        }
        used += rows + 1;
        page += segments;
    }
    transcript.set_page(page.max(1));
    frame.render_widget(
        Paragraph::new(lines)
            .block(panel(view, title))
            .scroll((transcript.scroll(), 0)),
        body,
    );
}

/// Turn `turn_index`'s rows, `width` columns wide: its speaker's name in the speaker's tint, then
/// its segments - the cursor's (`current`) highlighted and the playhead's (`playing`) in the
/// accent, each with its box while segments are marked - and its time under the name; each
/// character carries the segment it shows, counted from `first`, the turn's first segment as the
/// cursor counts them.
fn turn_rows(
    view: &View<'_>,
    transcript: &Transcript,
    turn_index: usize,
    first: usize,
    current: Option<(usize, usize)>,
    playing: Option<(usize, usize)>,
    width: u16,
) -> Vec<Vec<Glyph>> {
    let turns = &transcript.turns;
    let turn = &turns.turns[turn_index];
    let marking = transcript.marking();
    let name = turns
        .speakers
        .iter()
        .find(|speaker| Some(speaker.speaker_id) == turn.speaker_id)
        .map(|speaker| speaker.speaker.as_str())
        .unwrap_or_default();
    // The speaker's name in its own tint, as the panel above shows it.
    let tint = turns
        .speakers
        .iter()
        .position(|speaker| Some(speaker.speaker_id) == turn.speaker_id)
        .and_then(|index| view.theme.speaker(index))
        .map_or_else(Style::new, |colour| fg(view, colour));
    // The design's turn: the time in grey and the speaker in its own tint on one row; the text
    // starts under the name on the rows below, aligned past the time.
    let time = clock(turn.start_ms);
    let clock_style = fg(view, view.theme.muted);
    let after_time = time.chars().count() + 2;
    let indent = [(Style::new(), " ".repeat(after_time))];
    let mut runs: Vec<(Style, String, Option<usize>)> = Vec::new();
    for (segment_index, segment) in turn.segments.iter().enumerate() {
        let flat = first + segment_index;
        if segment_index > 0 {
            runs.push((Style::new(), " ".to_string(), None));
        }
        // While segments are marked every segment shows a box, as the browser's reassign mode
        // shows a checkbox: ticked in the accent on a marked one, empty and muted on the others -
        // so it is plain which ones a reassignment moves.
        if marking {
            runs.push(if transcript.segment_marked(segment.id) {
                (fg(view, view.theme.accent), "☑ ".to_string(), Some(flat))
            } else {
                (fg(view, view.theme.muted), "☐ ".to_string(), Some(flat))
            });
        }
        let current = current == Some((turn_index, segment_index));
        let playing = playing == Some((turn_index, segment_index));
        // A segment with no text still shows, as the browser's `[empty]` - muted unless it is the
        // cursor's.
        let empty = segment.text.is_empty();
        runs.push((
            if playing {
                Style::new()
                    .bg(view.theme.accent)
                    .fg(view.theme.accent_text)
            } else if current {
                highlight(view)
            } else if empty {
                fg(view, view.theme.muted)
            } else {
                Style::new()
            },
            if empty {
                stt_client::text("transcript.empty", &[])
            } else {
                segment.text.clone()
            },
            Some(flat),
        ));
    }
    let glyphs = |parts: &[(Style, String)]| -> Vec<Glyph> {
        parts
            .iter()
            .flat_map(|(style, text)| text.chars().map(move |character| (*style, character, None)))
            .collect()
    };
    let header = glyphs(&[
        (clock_style, time),
        (Style::new(), "  ".to_string()),
        (tint, name.to_string()),
    ]);
    let mut wrapped = vec![header];
    wrapped.extend(wrap_tagged(&runs, width, &indent, &indent));
    wrapped
}

/// The task's header as History writes it: the file name, and the finish date and the duration
/// once the task has them.
fn header_text(task: &Task) -> String {
    let meta = history_meta(task);
    if meta.is_empty() {
        task.filename.clone()
    } else {
        format!("{}   {meta}", task.filename)
    }
}

/// The transcript while its turns are being read - or after the read failed: the task's header
/// (its file name, finish date and duration, as History writes them), and under it the resting
/// stenographer over the words: that it is loading, or why it could not be and that Enter retries.
fn draw_transcript_waiting(
    frame: &mut Frame,
    area: Rect,
    view: &View<'_>,
    transcript: &Transcript,
) {
    let block = panel(
        view,
        stt_client::text(
            "tui.transcript.title",
            &[("filename", &transcript.task.filename)],
        ),
    );
    let inner = block.inner(area);
    frame.render_widget(block, area);
    let [head, rest] = Layout::vertical([Constraint::Length(1), Constraint::Fill(1)]).areas(inner);
    frame.render_widget(
        Paragraph::new(Line::styled(
            header_text(&transcript.task),
            fg(view, view.theme.muted),
        )),
        head,
    );
    let large = &stenographer::LARGE[0];
    let [tile, words] =
        Layout::vertical([Constraint::Length(large.len() as u16), Constraint::Fill(1)]).areas(rest);
    draw_tile(frame, tile, view, large);
    let text = match transcript.load_error() {
        Some(error) => Text::from(vec![
            hint(view, "tui.transcript.loadFailed", &[("error", error)])
                .style(fg(view, view.theme.status("FAILED"))),
            Line::default(),
            hint(view, "tui.transcript.retry", &[]),
        ]),
        None => Text::from(Line::from(stt_client::text("transcript.loading", &[]))),
    };
    frame.render_widget(Paragraph::new(text).wrap(Wrap { trim: true }), words);
}

/// A task's status page, for a task with no transcript: what it was doing when it failed or was
/// canceled, with the stored error and the retry that sends it back to the queue - or that it is
/// still waiting there.
fn draw_status(frame: &mut Frame, area: Rect, view: &View<'_>, task: &Task) {
    let block = panel(view, task.filename.clone());
    let inner = block.inner(area);
    frame.render_widget(block, area);
    // Its clip, once Space played it, takes the page's last row with the player's bar.
    let inner = if view.clip == Some(task.id) && view.player.is_some() {
        let [words, bar] =
            Layout::vertical([Constraint::Min(1), Constraint::Length(1)]).areas(inner);
        draw_player_bar(frame, bar, view, task.duration_ms);
        words
    } else {
        inner
    };
    let mut lines: Vec<Line> = Vec::new();
    if crate::state::ended(&task.status) {
        let failed = task.status == "FAILED";
        let phase = phase_word(task.phase.as_deref());
        let outcome = match task.transcribed_until_ms {
            Some(until) => stt_client::text(
                "transcript.outcomeAt",
                &[
                    ("outcome", &outcome_word(failed)),
                    ("phase", &phase),
                    ("time", &clock(until.max(0) as u64)),
                ],
            ),
            None => stt_client::text(
                "transcript.outcome",
                &[("outcome", &outcome_word(failed)), ("phase", &phase)],
            ),
        };
        let colour = if failed {
            view.theme.status("FAILED")
        } else {
            view.theme.muted
        };
        lines.push(Line::styled(outcome, fg(view, colour)));
        if let Some(error) = &task.error {
            lines.push(Line::default());
            lines.push(Line::from(stt_client::text(
                "transcript.error",
                &[("message", error)],
            )));
        }
        // Without a stored clip there is nothing to play: the page says why, as the browser's
        // status page does in place of its player.
        if !task.has_audio {
            lines.push(Line::default());
            lines.push(Line::styled(
                stt_client::text("transcript.noAudio", &[]),
                fg(view, view.theme.muted),
            ));
        }
        lines.push(Line::default());
        lines.push(hint(view, "tui.status.retry", &[]));
    } else {
        lines.push(Line::from(stt_client::text("transcript.pending", &[])));
    }
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: true }), inner);
}

/// The phase a task ended in, as the status page's sentence reads it.
fn phase_word(phase: Option<&str>) -> String {
    let key = match phase {
        Some("CHUNKING") => "transcript.phase.chunking",
        Some("TRANSCRIBING") => "transcript.phase.transcribing",
        Some("DIARIZING") => "transcript.phase.diarizing",
        _ => "transcript.phase.processing",
    };
    stt_client::text(key, &[])
}

/// How a task that ended before finishing ended, in the browser's words: `Failed` or `Canceled`.
fn outcome_word(failed: bool) -> String {
    stt_client::text(
        if failed {
            "transcript.failed"
        } else {
            "transcript.canceled"
        },
        &[],
    )
}

/// A wrapped character: its style, the character, and the segment it shows, if it shows one.
type Glyph = (Style, char, Option<usize>);

/// `runs` wrapped into rows at word boundaries, `width` columns wide, each character keeping its
/// run's style and segment. The first row starts with `start` and every row after it as far in as
/// `lead` is wide, so a turn's text reads as one block beside its time and name. A word longer than
/// a row is broken over rows.
fn wrap_tagged(
    runs: &[(Style, String, Option<usize>)],
    width: u16,
    start: &[(Style, String)],
    lead: &[(Style, String)],
) -> Vec<Vec<Glyph>> {
    let width = width.max(1) as usize;
    let glyphs = |parts: &[(Style, String)]| -> Vec<Glyph> {
        parts
            .iter()
            .flat_map(|(style, text)| text.chars().map(move |character| (*style, character, None)))
            .collect()
    };
    let mut start = glyphs(start);
    start.truncate(width.saturating_sub(1));
    let mut lead = glyphs(lead);
    lead.truncate(width.saturating_sub(1));
    let pad = vec![(Style::new(), ' ', None); lead.len()];
    // Words stay whole; runs of spaces between them stay as wide as the line drew them.
    let mut tokens: Vec<(bool, Vec<Glyph>)> = Vec::new();
    for (style, text, segment) in runs {
        for character in text.chars() {
            let space = character == ' ';
            match tokens.last_mut() {
                Some((last_space, characters)) if *last_space == space => {
                    characters.push((*style, character, *segment));
                }
                _ => tokens.push((space, vec![(*style, character, *segment)])),
            }
        }
    }

    let mut rows: Vec<Vec<Glyph>> = Vec::new();
    let mut row: Vec<Glyph> = start.clone();
    let mut column = start.len();
    let mut has_word = false;
    let mut wrap_before_word = false;
    fn new_row(row: &mut Vec<Glyph>, column: &mut usize, start: &[Glyph]) {
        row.clear();
        row.extend_from_slice(start);
        *column = start.len();
    }
    /// Keeps a finished row without the spaces it ended on.
    fn push_trimmed(rows: &mut Vec<Vec<Glyph>>, mut row: Vec<Glyph>) {
        while row
            .last()
            .is_some_and(|(_, character, _)| *character == ' ')
        {
            row.pop();
        }
        rows.push(row);
    }
    for (space, characters) in tokens {
        if space {
            // A space run that does not fit ends the row there; the next word starts the next.
            if column + characters.len() <= width {
                column += characters.len();
                row.extend(characters);
            } else {
                wrap_before_word = true;
            }
            continue;
        }
        let mut pending = characters.into_iter().peekable();
        let length = pending.clone().count();
        if !wrap_before_word && column + length <= width {
            column += length;
            row.extend(pending);
            has_word = true;
            continue;
        }
        wrap_before_word = false;
        if has_word {
            push_trimmed(&mut rows, std::mem::take(&mut row));
        }
        let opening = match rows.len() {
            0 => &start[..],
            1 => &lead[..],
            _ => &pad[..],
        };
        new_row(&mut row, &mut column, opening);
        // A word longer than the room left: broken over lines.
        loop {
            while column < width {
                let Some(glyph) = pending.next() else {
                    break;
                };
                row.push(glyph);
                column += 1;
            }
            has_word = true;
            if pending.peek().is_none() {
                break;
            }
            rows.push(std::mem::take(&mut row));
            let opening = match rows.len() {
                0 => &start[..],
                1 => &lead[..],
                _ => &pad[..],
            };
            new_row(&mut row, &mut column, opening);
        }
    }
    if has_word {
        push_trimmed(&mut rows, row);
    }
    rows
}

fn wrapped_rows(text: &str, width: u16) -> u16 {
    wrap_tagged(&[(Style::new(), text.to_string(), None)], width, &[], &[]).len() as u16
}

/// `glyphs` grouped into spans, one run of a style per span.
fn spans_of(glyphs: &[Glyph]) -> Vec<Span<'static>> {
    let mut spans: Vec<Span<'static>> = Vec::new();
    let mut run = String::new();
    let mut style: Option<Style> = None;
    for &(next, character, _) in glyphs {
        if style == Some(next) {
            run.push(character);
        } else {
            if let Some(previous) = style {
                spans.push(Span::styled(std::mem::take(&mut run), previous));
            }
            style = Some(next);
            run.push(character);
        }
    }
    if let Some(previous) = style {
        spans.push(Span::styled(run, previous));
    }
    spans
}

/// The fewest columns a queue row's file name keeps before the bar, then the phase, gives way.
const FILE_MIN: u16 = 16;
/// The queue row's bar: twenty columns, as the design draws it.
const BAR_WIDTH: u16 = 20;
/// The percentage's field, right-aligned four columns in from the page's right edge.
const PERCENT_WIDTH: u16 = 6;
const PERCENT_FROM_RIGHT: u16 = 10;
/// The status column's width beside the bar: room for "Transcribing… · 00:12" and a gap.
const STATUS_WIDTH: u16 = 30;
/// The widest status word the app's language writes, so a chip never runs into the name.
fn status_word_width() -> u16 {
    [
        "QUEUED",
        "RUNNING",
        "PAUSED",
        "COMPLETED",
        "FAILED",
        "CANCELED",
    ]
    .iter()
    .map(|status| crate::state::status_word(status).chars().count())
    .max()
    .unwrap_or(9) as u16
}

/// The status column's width when only a chip fits: " RUNNING " and a gap.
fn chip_width() -> u16 {
    status_word_width() + 3
}

/// Where a queue row's parts go on a page `width` columns wide: the design's columns at 100 -
/// the status 62 columns from the right edge, the bar 32 - with the bar dropping out first, and then
/// the phase giving way to the status chip, while the name would get fewer than `FILE_MIN`.
struct RowColumns {
    name_width: u16,
    status: u16,
    /// Whether a running row shows its phase and time, rather than its status chip.
    phase: bool,
    bar: Option<u16>,
    percent: u16,
}

fn row_columns(width: u16) -> RowColumns {
    let percent = width.saturating_sub(PERCENT_FROM_RIGHT);
    let bar = percent.saturating_sub(BAR_WIDTH + 2);
    let layouts = [
        (bar.saturating_sub(STATUS_WIDTH), true, Some(bar)),
        (percent.saturating_sub(STATUS_WIDTH + 2), true, None),
        (percent.saturating_sub(chip_width()), false, None),
    ];
    // The name starts two columns in and keeps two columns before the status.
    let (status, phase, bar) = layouts
        .into_iter()
        .find(|(status, ..)| status.saturating_sub(4) >= FILE_MIN)
        .unwrap_or(layouts[2]);
    RowColumns {
        name_width: status.saturating_sub(4),
        status,
        phase,
        bar,
        percent,
    }
}

/// `text` in at most `width` characters: shortened with `…` when it does not fit.
fn ellipsized(text: &str, width: usize) -> String {
    if text.chars().count() <= width {
        return text.to_string();
    }
    let kept: String = text.chars().take(width.saturating_sub(1)).collect();
    format!("{kept}…")
}

/// The processing stage's name from the shared catalog, or the wire value when it is one the catalog
/// does not name; an empty cell when the task is not being processed.
fn phase(phase: Option<&str>) -> String {
    match phase {
        None => String::new(),
        Some("CHUNKING") => stt_client::text("queue.phase.chunking", &[]),
        Some("TRANSCRIBING") => stt_client::text("queue.phase.transcribing", &[]),
        Some("DIARIZING") => stt_client::text("queue.phase.diarizing", &[]),
        Some(other) => other.to_string(),
    }
}

/// A running task's line, as the browser's: its stage with an ellipsis and, once the engine has
/// started it, the time since then - "Transcribing… · 00:12"; the stage alone before its first
/// phase.
fn running_line(task: &Task, now_ms: i64) -> String {
    let stage = phase(task.phase.as_deref());
    if stage.is_empty() {
        return stage;
    }
    match task.started_at {
        Some(started) => format!(
            "{stage}… · {}",
            clock(u64::try_from(now_ms - started).unwrap_or(0))
        ),
        None => format!("{stage}…"),
    }
}

/// A file's size in decimal units, one decimal place: `57.7 MB` for 57671680 bytes, in the app's
/// language's digits.
fn size(bytes: u64) -> String {
    let (value, unit) = if bytes >= 1_000_000_000 {
        (bytes as f64 / 1_000_000_000.0, "GB")
    } else if bytes >= 1_000_000 {
        (bytes as f64 / 1_000_000.0, "MB")
    } else if bytes >= 1_000 {
        (bytes as f64 / 1_000.0, "KB")
    } else {
        (bytes as f64, "B")
    };
    if unit == "B" {
        format!(
            "{} B",
            stt_client::integer(i64::try_from(bytes).unwrap_or(i64::MAX))
        )
    } else {
        format!("{} {unit}", stt_client::number(value, 1))
    }
}

/// A turn's start as `mm:ss`, in the app's language's digits.
fn clock(ms: u64) -> String {
    stt_client::duration(i64::try_from(ms).unwrap_or(i64::MAX))
}

/// A foreground in `colour`, or no style at all when `NO_COLOR` is set.
fn fg(view: &View<'_>, colour: Color) -> Style {
    if view.no_color {
        Style::new()
    } else {
        Style::new().fg(colour)
    }
}

/// Bold, or no style at all when `NO_COLOR` is set: the TUI writes no attribute then.
fn bold(view: &View<'_>) -> Style {
    if view.no_color {
        Style::new()
    } else {
        Style::new().bold()
    }
}

/// The selected row's style: the accent tint, or nothing when `NO_COLOR` is set (the row is
/// marked with `>` instead).
fn highlight(view: &View<'_>) -> Style {
    if view.no_color {
        Style::new()
    } else {
        Style::new().bg(view.theme.accent_tint)
    }
}

/// The width of a catalog template's text before its `{placeholder}`, in columns: where a draft
/// filled into it starts.
fn prefix_width(key: &str, placeholder: &str) -> u16 {
    stt_client::text(key, &[])
        .split(&format!("{{{placeholder}}}"))
        .next()
        .unwrap_or_default()
        .chars()
        .count() as u16
}

/// A draft typed into a row `row_width` cells wide after a label `prefix_width` cells wide: what is
/// drawn of it - its end behind a `…` when all of it would not leave the cursor a cell - and the
/// cursor's column after it, counted from the row's start.
fn typed_field(draft: &str, prefix_width: u16, row_width: u16) -> (String, u16) {
    let shown = draft_tail(
        draft,
        row_width.saturating_sub(prefix_width).saturating_sub(1),
    );
    let column = prefix_width + shown.chars().count() as u16;
    (shown, column)
}

/// The end of `draft` that fits in `width` cells: all of it when it fits, otherwise its last
/// characters behind a `…`, so the end being typed - where the cursor is - stays in view.
fn draft_tail(draft: &str, width: u16) -> String {
    let width = width as usize;
    let length = draft.chars().count();
    if length <= width {
        return draft.to_string();
    }
    if width == 0 {
        return String::new();
    }
    let tail: String = draft.chars().skip(length - (width - 1)).collect();
    format!("…{tail}")
}

/// Shows the cursor at `column` of `area`'s `row` - where a prompt's draft ends. A prompt draws the
/// cursor itself, so the terminal shows it only while one is open.
fn cursor(frame: &mut Frame, area: Rect, row: u16, column: u16) {
    frame.set_cursor_position((area.x + column, area.y + row));
}

/// Shows the cursor after a draft that fills `key`'s `{placeholder}`, on `area`'s first row.
fn cursor_at_end(frame: &mut Frame, area: Rect, key: &str, placeholder: &str, draft: &str) {
    let column = prefix_width(key, placeholder) + draft.chars().count() as u16;
    cursor(frame, area, 0, column);
}

/// The catalog text `key` with `args` filled in and its keys highlighted in the accent colour, or
/// bracketed under `NO_COLOR` (`hints`).
fn hint(view: &View<'_>, key: &str, args: &[(&str, &str)]) -> Line<'static> {
    crate::hints::line(key, args, accent(view))
}

/// The colour a hint's keys are highlighted in: the accent, or none under `NO_COLOR`.
fn accent(view: &View<'_>) -> Option<ratatui::style::Color> {
    (!view.no_color).then_some(view.theme.accent)
}

/// A bordered panel: its border in the border role, its title in accent; plain under `NO_COLOR`.
fn panel(view: &View<'_>, title: String) -> Block<'static> {
    if view.no_color {
        Block::bordered().title(title)
    } else {
        Block::bordered()
            .border_style(Style::new().fg(view.theme.border))
            .title(Span::styled(title, Style::new().fg(view.theme.accent)))
    }
}

/// The prefix a row carries when it is selected and colors are off - selection must still show.
fn marker(selected: bool, no_color: bool) -> &'static str {
    if selected && no_color { "> " } else { "" }
}
