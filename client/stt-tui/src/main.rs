//! `stt-tui`, the terminal frontend of the per-user service (roadmap item 67): a client of the
//! service's HTTP API, never a second backend - with no service running it says how to start one
//! and starts nothing itself. It shows the queue as the service's event stream reports it, uploads
//! a file by the path the user types, sends the queue's commands, and opens the history of
//! completed tasks and their transcripts.

use std::future::Future;
use std::io;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::mpsc;
use std::time::{Duration, Instant};

use ratatui::DefaultTerminal;
use ratatui::crossterm::cursor::SetCursorStyle;
use ratatui::crossterm::event as terminal_event;
use ratatui::crossterm::event::{KeyCode, MouseButton, MouseEventKind};
use ratatui::crossterm::terminal::{
    BeginSynchronizedUpdate, Clear, ClearType, EndSynchronizedUpdate,
};
use ratatui::crossterm::{execute, queue};
use stt_client::{
    Event, Frontend, Service, ServiceDirs, Settings, Snapshot, Speaker, Task, TaskCommand, Turns,
    UploadOptions, audio, delete_task, dismiss_device_fallback, events, locate, reorder_queue,
    set_queue_paused, task_command, text, turns,
};

use crate::download::Download;
use crate::input::Action;
use crate::player::Player;
use crate::settings::SettingsScreen;
use crate::state::{Page, Screen};
use crate::transcript::Transcript;
use crate::upload_dialog::UploadDialog;

mod bidi;
mod browser;
mod choices;
mod download;
mod footer;
#[cfg(debug_assertions)]
mod frame_log;
mod graphemes;
mod hints;
mod input;
mod keyboard;
mod player;
mod settings;
mod state;
mod stenographer;
mod theme;
mod transcript;
mod upload_dialog;
mod upload_options;
mod view;
mod wide_overlap;
mod writes;

/// What the terminal loop wakes up for: an event from the service, something the user did, or the
/// outcome of a request this TUI made.
enum Message {
    Service(Event),
    Terminal(terminal_event::Event),
    /// A mouse event, with the moment the input thread read it.
    Mouse(terminal_event::MouseEvent, Instant),
    Notice(String),
    /// The service refused an upload as a file it already has: task `task` is the existing one,
    /// offered above the queue as the browser's duplicate link is.
    Duplicate {
        task: i64,
        filename: String,
    },
    /// An upload met a lost connection: these files wait, the one it was on named by `name`, and
    /// are sent again once a snapshot says the connection is back.
    UploadHeld {
        files: Vec<PathBuf>,
        options: UploadOptions,
        name: String,
        /// The batch's place in the order the user sent batches.
        turn: u64,
    },
    /// A transcript arrived; the transcript screen shows it.
    Transcript(Task, Turns),
    /// Task `id`'s turns could not be read: an open transcript of it says why and offers a retry.
    TranscriptFailed(i64, String),
    /// Task `id`'s speakers as the service answered a rename or a merge; an open transcript of it
    /// shows them.
    Speakers(i64, Vec<Speaker>),
    /// Task `id`'s turns, read again after a merge; an open transcript of it shows them.
    Turns(i64, Turns),
    /// Task `id`'s audio arrived (the service's FLAC clip): the open transcript of it plays it
    /// from `from` - alone up to `until` when given, as a segment played on its own.
    Audio(i64, Vec<u8>, Duration, Option<Duration>),
    /// Task `task`'s segment `segment` edit was refused with `error`: the open transcript of it
    /// puts `draft` back in the prompt, and the notice names the reason.
    EditRefused {
        task: i64,
        segment: i64,
        draft: String,
        error: String,
    },
    /// The settings and the installed frontends arrived; the settings screen shows them.
    Settings(Settings, Vec<Frontend>),
    /// The settings as the service answered a save; an open settings screen shows them.
    SettingsSaved(Settings),
    /// The settings could not be read: the settings page opens with why.
    SettingsFailed(String),
    /// The defaults could not be restored: the settings page stops saving and shows why.
    RestoreFailed(String),
    /// The service refused a save with its message - a setting's (whose row then shows it), or
    /// the theme key's, which names none.
    SettingRefused(Option<settings::Field>, String),
    /// The settings arrived for the starting options of the upload dialog `opened` counts.
    UploadDefaults(u64, Settings),
    /// The stored settings, as the service last answered a read or a write with them: the
    /// fallback notice and the theme follow them, and `t` writes them back with the next mode
    /// (roadmap item 67, ADR 0068).
    Stored(Settings),
    /// One file of an upload batch is on its way: `sent` of `total` bytes have reached the
    /// service's socket. The id names the upload, so two files of the same name never share a
    /// line.
    UploadProgress {
        id: u64,
        filename: String,
        sent: u64,
        total: u64,
    },
    /// One file of an upload batch is done: its line goes.
    UploadDone(u64),
    /// The event stream dropped, went silent or could not be opened; a new one is on its way.
    Disconnected,
}

/// A finished task's notice on the queue page: its file and how it ended, newest first.
struct Notice {
    id: i64,
    filename: String,
    status: String,
}

/// An upload batch the connection cut off: its files from the one it was on, its options, and that
/// file's name for the line that says it waits.
struct Held {
    files: Vec<PathBuf>,
    options: UploadOptions,
    name: String,
    /// The batch's place in the order the user sent batches: the resend follows it, not the order
    /// the failures happened to arrive in.
    turn: u64,
}

/// A file of an upload batch on its way to the service.
struct Uploading {
    id: u64,
    filename: String,
    sent: u64,
    total: u64,
}

impl Uploading {
    /// The share of the file sent, in whole percent: 0 for an empty file, never above 100.
    fn percent(&self) -> u64 {
        (self.sent * 100)
            .checked_div(self.total)
            .unwrap_or(0)
            .min(100)
    }
}

/// What has the queue page's keyboard: its rows, the upload area above them, or one of the
/// finished tasks' notices above that.
#[derive(Clone, Copy, PartialEq, Eq)]
enum QueueFocus {
    Rows,
    Upload,
    Notice(usize),
}

/// Whether the user asked to leave.
enum Outcome {
    Continue,
    Quit,
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
    // The stored settings say whether whisper-cli fell back to the CPU, which the queue shows, and
    // which theme mode to draw in.
    {
        let settings = service.clone();
        let sender = sender.clone();
        runtime.spawn(async move {
            if let Ok(settings) = stt_client::settings(&settings).await {
                let _ = sender.send(Message::Stored(settings));
            }
        });
    }
    // What `auto` draws from, asked before the keyboard thread reads the terminal: the terminal's
    // answer arrives as input. `NO_COLOR` draws no colour, so nothing is asked.
    let clues = if std::env::var_os("NO_COLOR").is_some() {
        theme::Clues::default()
    } else {
        theme::Clues::read()
    };
    let keys = sender.clone();
    let mut terminal = ratatui::init();
    // The cursor a prompt shows is a blinking block, as the user asked (DECSCUSR 1); the terminal's
    // own shape comes back when the TUI leaves.
    let _ = execute!(io::stdout(), SetCursorStyle::BlinkingBlock);
    // Keys, mouse reports and pastes are read on a thread of their own (ADR 0069).
    let keyboard = match keyboard::Keyboard::open() {
        Ok(keyboard) => keyboard,
        Err(error) => {
            let _ = execute!(io::stdout(), SetCursorStyle::DefaultUserShape);
            ratatui::restore();
            eprintln!("stt-tui: the terminal's input cannot be read ({error})");
            return ExitCode::FAILURE;
        }
    };
    let reader = keyboard.reader();
    std::thread::spawn(move || {
        // A mouse event is stamped as it is read, so a double-click is two clicks close together
        // as they arrive, however long the TUI takes to draw between them.
        keyboard::read(reader, |event| {
            let message = match event {
                terminal_event::Event::Mouse(mouse) => Message::Mouse(mouse, Instant::now()),
                event => Message::Terminal(event),
            };
            keys.send(message).is_ok()
        });
    });
    let shown = show(
        &mut terminal,
        &received,
        runtime.handle().clone(),
        service,
        sender,
        clues,
    );
    keyboard.close();
    let _ = execute!(io::stdout(), SetCursorStyle::DefaultUserShape);
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

/// How long a stream may stay silent before it counts as dropped: the service sends at least a
/// heartbeat every 5 s (ADR 0044), so 15 s without any event means the connection is gone even if
/// the socket says otherwise - as the browser decides.
const SILENCE: Duration = Duration::from_secs(15);

/// Reads the service's event stream and hands every event to the terminal. A stream that drops,
/// stays silent for [`SILENCE`], or cannot be opened is announced and opened again after
/// [`RECONNECT`]; its first event, a fresh snapshot, brings the view up to date with whatever
/// happened meanwhile.
async fn follow(service: Service, sender: mpsc::Sender<Message>) {
    loop {
        if let Ok(mut stream) = events(&service).await {
            while let Ok(Ok(Some(event))) = tokio::time::timeout(SILENCE, stream.next()).await {
                if sender.send(Message::Service(event)).is_err() {
                    return;
                }
            }
        }
        if sender.send(Message::Disconnected).is_err() {
            return;
        }
        tokio::time::sleep(RECONNECT).await;
    }
}

/// A confirmation waiting for its answer: deleting a task, cancelling one that has started (work
/// would be lost), or restoring the default settings, as the browser asks.
enum Confirm {
    Delete(Task),
    Cancel(Task),
    RestoreDefaults,
}

/// Everything the terminal shows and remembers.
struct Ui {
    snapshot: Option<Snapshot>,
    screen: Screen,
    /// The queue's selected row.
    selected: usize,
    /// History's selected row.
    history_selected: usize,
    /// History's search query, as typed, and its status filter.
    history_search: String,
    history_status: state::Status,
    /// Whether History's search prompt has the keyboard.
    searching: bool,
    /// The upload dialog, while it is open.
    upload: Option<UploadDialog>,
    /// How many upload dialogs have opened: the open one's number, which its settings read
    /// carries back, so a closed dialog's late answer is told apart.
    uploads_opened: u64,
    /// The download dialog, while it is open.
    download: Option<Download>,
    /// The transcript's audio, started on its first `Space`; and which task's clip it holds.
    player: Option<Player>,
    clip: Option<i64>,
    /// What the last action reported.
    notice: Option<String>,
    /// The colours to draw with (roadmap item 67, ADR 0068): the stored theme mode's tokens.
    theme: theme::Palette,
    /// What `auto` resolves from: the OS's preference and the terminal's background.
    clues: theme::Clues,
    /// Whether the terminal draws 24-bit colour; otherwise the palette is the 256-colour one.
    true_colour: bool,
    /// The stored settings, as the service last reported them: `t` writes them with the next mode.
    settings: Option<Settings>,
    /// Whether `NO_COLOR` is set: then the selected row is marked, and nothing is coloured.
    no_color: bool,
    /// Whether the event stream is down: from its drop until a new stream's snapshot arrives.
    disconnected: bool,
    /// Whether the key reference is open.
    help: bool,
    /// How many rows the key reference's table is scrolled down; it opens at the top.
    help_scroll: usize,
    /// What has the queue page's keyboard.
    focus: QueueFocus,
    /// The latest finished tasks, newest first, at most five: the queue page's notices.
    notices: Vec<Notice>,
    /// The files of an upload batch still going, with the bytes sent of each.
    uploads: Vec<Uploading>,
    /// The upload batches the connection cut off, in the order they were cut off - sent again,
    /// in that order, once a snapshot says the connection is back.
    held: Vec<Held>,
    /// How many batches the user has sent: the next one's place in that order.
    batches_sent: u64,
    /// Whether the settings page waits for its read: set when the page is asked for, cleared when
    /// the user goes elsewhere first, so a late answer does not bring the page back.
    settings_wanted: bool,
    /// The queue confirmation, while one is open.
    confirm: Option<Confirm>,
    /// The GPU the service fell back from, while its notice stands.
    device_fallback: Option<String>,
    /// The last left click's moment, as it was read, and position, for a double click.
    last_click: Option<(Instant, u16, u16)>,
    /// The paths a paste named that still wait for their dialog, the first one's open.
    pasted: std::collections::VecDeque<std::path::PathBuf>,
    /// The stenographer's frame: it moves on every step while a task runs, and is 0 otherwise.
    stenographer: usize,
    /// Where the last frame drew the page, for the mouse's hit-test.
    page: ratatui::layout::Rect,
    /// The whole frame the last draw painted: a dialog's area, for the mouse's hit-test.
    frame_area: ratatui::layout::Rect,
    /// The frame the last draw painted, which the next one is drawn over (see [`wide_overlap`]).
    previous_frame: ratatui::buffer::Buffer,
    /// The first queue row the table showed, for the mouse's hit-test.
    queue_offset: std::cell::Cell<usize>,
    /// The first History row the list showed, for the mouse's hit-test.
    history_offset: std::cell::Cell<usize>,
    /// The footer hints a click performs, as the last frame drew them.
    footer_hits: std::cell::RefCell<Vec<view::FooterHint>>,
    /// Whether the environment names an emulator that reorders text itself: the key reference
    /// then adds the notice that its setting must be off.
    reorders: bool,
    /// Whether the TUI writes for kitty's default mode (`view::rtl_words`): each right-to-left
    /// word is written in typed order, for the terminal to turn.
    rtl_words: bool,
    /// Whether the environment names kitty: the key reference adds the notice on its `force_ltr`
    /// and `STT_TUI_RTL_WORDS`.
    kitty: bool,
}

impl Ui {
    fn new(clues: theme::Clues) -> Ui {
        let true_colour = theme::true_colour();
        Ui {
            snapshot: None,
            screen: Screen::Queue,
            selected: 0,
            history_selected: 0,
            history_search: String::new(),
            history_status: state::Status::All,
            searching: false,
            upload: None,
            uploads_opened: 0,
            download: None,
            player: None,
            clip: None,
            notice: None,
            theme: theme::for_mode("auto", &clues, true_colour),
            clues,
            true_colour,
            settings: None,
            no_color: std::env::var_os("NO_COLOR").is_some(),
            disconnected: false,
            help: false,
            help_scroll: 0,
            focus: QueueFocus::Rows,
            notices: Vec::new(),
            uploads: Vec::new(),
            held: Vec::new(),
            batches_sent: 0,
            settings_wanted: false,
            confirm: None,
            device_fallback: None,
            last_click: None,
            pasted: std::collections::VecDeque::new(),
            stenographer: 0,
            page: ratatui::layout::Rect::default(),
            frame_area: ratatui::layout::Rect::default(),
            previous_frame: ratatui::buffer::Buffer::default(),
            queue_offset: std::cell::Cell::new(0),
            history_offset: std::cell::Cell::new(0),
            footer_hits: std::cell::RefCell::new(Vec::new()),
            reorders: view::reorders_text(),
            rtl_words: view::rtl_words(),
            kitty: view::kitty_text(),
        }
    }

    /// The stored settings as the service reported them: the fallback notice, the theme in their
    /// mode, and the app's language (item 88) - the stored one when it ships a catalog, else the
    /// system's, as the browser resolves it.
    fn stored(&mut self, settings: Settings) {
        self.device_fallback = settings.device_fallback.clone();
        self.theme = theme::for_mode(&settings.theme_mode, &self.clues, self.true_colour);
        stt_client::set_locale(
            settings.app_language.as_deref(),
            &stt_client::system_language_tags(),
        );
        self.settings = Some(settings);
    }

    /// `t`, or a click on the header's mode: the next theme mode, drawn at once and saved with
    /// every other setting.
    fn cycle_theme(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        if let Some(stored) = self.settings.clone() {
            let mut changed = stored.clone();
            changed.theme_mode = theme::next(&stored.theme_mode).to_string();
            // Drawn at once, so a second `t` goes on from it before the service answers the first.
            self.stored(changed.clone());
            // The whole settings, so a write never resets a setting another frontend changed; a
            // refusal puts the stored mode back.
            settings::save(runtime, service, sender, changed, Some(stored), None);
        }
    }

    /// While the stream is not live a write is refused in place, as the browser's write gate
    /// refuses it: nothing is sent, and the notice names it and gives the reason. Whether
    /// `action` in the input mode `mode` is such a write - a key's, or a click's that does a key's
    /// work.
    fn refused_offline(&mut self, action: Action, mode: input::Mode) -> bool {
        if self.connection() == view::Connection::Live {
            return false;
        }
        let place = writes::Place {
            screen: &self.screen,
            upload: self.upload.as_ref(),
            upload_focused: self.focus == QueueFocus::Upload,
            selected_task: self.selected_task(),
            confirming: self.confirming(),
        };
        let Some(name) = writes::name(action, mode, &place) else {
            return false;
        };
        self.notice = Some(text("tui.writes.refused", &[("action", &text(name, &[]))]));
        true
    }

    /// A left click at `column`, `row`, read at `at`: the tabs switch pages, the header's mode
    /// cycles, the upload area opens the dialog, a row is selected; a second click on the same spot
    /// read within half a second opens what the first selected - a history row, an upload file or
    /// folder - or plays a transcript segment.
    fn click(
        &mut self,
        column: u16,
        row: u16,
        at: Instant,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let double = self
            .last_click
            .is_some_and(|(when, last_column, last_row)| {
                last_column == column
                    && last_row == row
                    && at.duration_since(when) < Duration::from_millis(500)
            });
        self.last_click = Some((at, column, row));
        let target = view::mouse_target(&self.view(), column, row);
        // A click outside the open dialog closes it, as Escape does - but not one on a footer
        // hint: the footer names the open dialog's own keys, and the click performs its key there.
        if !matches!(target, Some(view::Target::FooterHint(_)))
            && self.close_modal_outside(column, row)
        {
            return;
        }
        match target {
            Some(view::Target::Tab(page)) => self.open_page(page, runtime, service, sender),
            // `mouse_target` names the upload area only on the Queue page.
            Some(view::Target::Upload) => {
                if !self.refused_offline(Action::OpenPrompt, input::Mode::Normal) {
                    self.queue_action(Action::OpenPrompt, runtime, service, sender);
                }
            }
            Some(view::Target::UploadOption(field)) => {
                if let Some(upload) = &mut self.upload {
                    upload.open_chooser(field);
                }
            }
            Some(view::Target::UploadChoice(index)) => {
                if let Some(upload) = &mut self.upload {
                    upload.choose(index);
                }
            }
            Some(view::Target::UploadMark(index)) => {
                if let Some(upload) = &mut self.upload {
                    upload.click_mark(index);
                }
            }
            // A click on a row selects it; a second click opens it as Enter does - a folder is
            // entered, even while files are marked; on a file it is Enter, through the key path,
            // the write gate included.
            Some(view::Target::UploadRow(index)) => {
                let enter = self.upload.as_mut().is_some_and(|upload| {
                    if !(upload.select(index) && double) {
                        return false;
                    }
                    if upload.browser().selects_folder() {
                        upload.enter_selected();
                        return false;
                    }
                    true
                });
                if enter {
                    let enter = terminal_event::KeyEvent::new(
                        KeyCode::Enter,
                        terminal_event::KeyModifiers::NONE,
                    );
                    self.press(enter, runtime, service, sender);
                }
            }
            Some(view::Target::QueueRow(index)) => {
                self.selected = index;
                self.focus = QueueFocus::Rows;
            }
            Some(view::Target::HistoryRow(index)) => {
                self.history_selected = index;
                if double && matches!(self.screen, Screen::History) {
                    self.history_action(Action::OpenSelected, runtime, service, sender);
                }
            }
            Some(view::Target::Theme) => {
                if !self.refused_offline(Action::CycleTheme, self.input_mode()) {
                    self.cycle_theme(runtime, service, sender);
                }
            }
            Some(view::Target::SettingsRow(index)) => {
                let opened = if let Screen::Settings(screen) = &mut self.screen {
                    screen.select(index);
                    double.then(|| screen.on_restore())
                } else {
                    None
                };
                // A second click opens the row as Enter does: its dropdown, editor, or - the
                // restore row - the shared confirmation.
                if let Some(restore) = opened {
                    if restore {
                        self.confirm = Some(Confirm::RestoreDefaults);
                    } else if let Screen::Settings(screen) = &mut self.screen {
                        screen.handle(Action::OpenSelected, runtime, sender, service);
                    }
                }
            }
            // A click on a segment moves the cursor there - unless a prompt or a dialog holds the
            // keyboard: a rename in progress goes to the speaker it started on.
            Some(view::Target::Segment(index)) => {
                let selected = if self.input_mode() == input::Mode::Normal {
                    if let Screen::Transcript(transcript) = &mut self.screen {
                        transcript.select_segment(index);
                        true
                    } else {
                        false
                    }
                } else {
                    false
                };
                // A second click plays the segment it lands on, as `p` plays the cursor's.
                if selected && double {
                    self.play_segment(runtime, service, sender);
                }
            }
            // A footer hint naming one key performs it.
            Some(view::Target::FooterHint(key)) => self.press(key, runtime, service, sender),
            None => {}
        }
    }

    /// `key` as if typed, for a click that does a key's work: through the key path, the write
    /// gates and the open dialog included.
    fn press(
        &mut self,
        key: terminal_event::KeyEvent,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let _ = self.handle(
            Message::Terminal(terminal_event::Event::Key(key)),
            runtime,
            service,
            sender,
        );
    }

    /// Opens `dialog` and reads the settings it starts from, as the GUI's upload panel does.
    fn open_upload(
        &mut self,
        dialog: UploadDialog,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        self.upload = Some(dialog);
        self.uploads_opened += 1;
        let opened = self.uploads_opened;
        let service = service.clone();
        fetch(
            runtime,
            sender,
            "tui.settings.loadFailed",
            async move { stt_client::settings(&service).await },
            move |settings| Message::UploadDefaults(opened, settings),
        );
    }

    /// A click outside the open dialog closes it: Help, a confirmation, the "Move to" list, the
    /// upload dialog or the download dialog, the topmost first. True when it closed one.
    fn close_modal_outside(&mut self, column: u16, row: u16) -> bool {
        let frame = self.frame_area;
        let inside = |rect: ratatui::layout::Rect| {
            column >= rect.x && column < rect.right() && row >= rect.y && row < rect.bottom()
        };
        let middle = inside(view::centered(frame));
        if self.help {
            let outside = !inside(view::help_area(frame, &self.view()));
            if outside {
                self.help = false;
                return true;
            }
            return false;
        }
        if self.confirm.is_some() {
            if !middle {
                self.confirm = None;
                return true;
            }
            return false;
        }
        let picking =
            matches!(&self.screen, Screen::Transcript(transcript) if transcript.picker().is_some());
        if picking {
            if !middle {
                if let Screen::Transcript(transcript) = &mut self.screen {
                    transcript.cancel_picker();
                }
                return true;
            }
            return false;
        }
        if let Some(upload) = &self.upload {
            if !inside(view::upload_area(frame, upload, self.no_color)) {
                self.upload = None;
                return true;
            }
            return false;
        }
        if self.download.is_some() {
            if !middle {
                self.download = None;
                return true;
            }
            return false;
        }
        false
    }

    /// A terminal drag-and-drop: the paths an open queue accepts open the upload dialog, one after
    /// another; text that names no existing file is ignored.
    fn paste(
        &mut self,
        text: &str,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        if !matches!(self.screen, Screen::Queue) {
            return;
        }
        self.pasted.extend(crate::upload_dialog::pasted_paths(text));
        if self.upload.is_none()
            && let Some(path) = self.pasted.pop_front()
        {
            self.open_upload(UploadDialog::open_with(&path), runtime, service, sender);
        }
    }

    /// A wheel notch: the page acts on it as it acts on Up/Down.
    fn wheel(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        match self.screen {
            Screen::Queue => {
                self.queue_action(action, runtime, service, sender);
            }
            Screen::History => {
                self.history_action(action, runtime, service, sender);
            }
            _ => {
                self.other_action(action, runtime, service, sender);
            }
        }
    }

    /// Whether something is drawn over the page: a dialog, a confirmation, the key reference or a
    /// settings dropdown.
    fn overlay_open(&self) -> bool {
        self.upload.is_some()
            || self.download.is_some()
            || self.confirm.is_some()
            || self.transcript_dialog().is_some()
            || self.help
            || matches!(&self.screen, Screen::Settings(screen) if screen.dropdown().is_some())
            || matches!(&self.screen, Screen::Transcript(transcript) if transcript.picker().is_some())
    }

    /// The view the draw and the mouse's hit-test read.
    fn view(&self) -> view::View<'_> {
        view::View {
            snapshot: self.snapshot.as_ref(),
            screen: &self.screen,
            selected: self.selected,
            history: self.tasks(),
            history_selected: self.history_selected,
            history_search: &self.history_search,
            history_status: self.history_status,
            upload: self.upload.as_ref(),
            rename: match &self.screen {
                Screen::Transcript(transcript) => transcript.rename(),
                _ => None,
            },
            edit: match &self.screen {
                Screen::Transcript(transcript) => transcript.edit(),
                _ => None,
            },
            download: self.download.as_ref(),
            player: self.player.as_ref(),
            clip: self.clip,
            notice: self.notice.as_deref(),
            theme: &self.theme,
            no_color: self.no_color,
            disconnected: self.disconnected,
            help: self.help,
            help_scroll: self.help_scroll,
            upload_focused: self.focus == QueueFocus::Upload,
            uploads: &self.uploads,
            waiting: self.held.iter().map(|held| held.name.as_str()).collect(),
            notices: &self.notices,
            notice_focused: match self.focus {
                QueueFocus::Notice(index) => Some(index),
                _ => None,
            },
            confirm: self.dialog(),
            confirming: self.confirming(),
            mode: self.input_mode(),
            context: self.input_context(),
            device_fallback: self.device_fallback.as_deref(),
            typing: self.typing(),
            stenographer: self.stenographer,
            connection: self.connection(),
            settings: self.settings.as_ref(),
            now_ms: now_ms(),
            page: self.page,
            frame: self.frame_area,
            queue_offset: &self.queue_offset,
            history_offset: &self.history_offset,
            footer_hits: &self.footer_hits,
            reorders: self.reorders,
            rtl_words: self.rtl_words,
            kitty: self.kitty,
        }
    }

    /// The key mode the open screen reads keys in: a text prompt or a dialog's while one is open,
    /// the page's otherwise (the settings page has dropdown and number modes of its own).
    fn input_mode(&self) -> input::Mode {
        if self.confirming().is_some() {
            return input::Mode::Confirm;
        }
        if self.download.is_some() {
            return input::Mode::Download;
        }
        if self.upload.is_some() {
            return input::Mode::Upload;
        }
        if self.searching {
            return input::Mode::Search;
        }
        // The rename prompt takes text as the upload prompt does: every key but Escape, Enter and
        // Backspace is a character of the name.
        let renaming =
            matches!(&self.screen, Screen::Transcript(transcript) if transcript.rename().is_some());
        let editing =
            matches!(&self.screen, Screen::Transcript(transcript) if transcript.edit().is_some());
        if renaming || editing {
            return input::Mode::Text;
        }
        match &self.screen {
            Screen::Settings(screen) => screen.mode(),
            // The "Move to" list chooses as a dropdown does.
            Screen::Transcript(transcript) if transcript.picker().is_some() => input::Mode::Choose,
            Screen::Queue
            | Screen::History
            | Screen::Transcript(_)
            | Screen::Status(_)
            | Screen::SettingsFailed(_) => input::Mode::Normal,
        }
    }

    /// Where a key lands as far as the key map cares: a transcript keeps the arrows and `h`/`j`/`k`/
    /// `l` for its segments, every page uses them for the tabs.
    fn input_context(&self) -> input::Context {
        match &self.screen {
            Screen::Transcript(_) => input::Context::Transcript,
            Screen::Queue
            | Screen::History
            | Screen::Settings(_)
            | Screen::SettingsFailed(_)
            | Screen::Status(_) => input::Context::Page,
        }
    }

    /// Whether the queue's rows have the keyboard: not the upload area, not a notice.
    fn on_rows(&self) -> bool {
        self.focus == QueueFocus::Rows
    }

    /// The selected row of the open list: History's on History, the queue's otherwise.
    fn selected_task(&self) -> Option<&Task> {
        match self.screen {
            Screen::History => self.history_rows().get(self.history_selected).copied(),
            _ => task_at(self.snapshot.as_ref(), self.selected),
        }
    }

    /// History's rows as its search and status filter show them: the snapshot's finished tasks,
    /// which the events keep current while the page is open - as the browser's list is.
    fn history_rows(&self) -> Vec<&Task> {
        state::history(self.tasks(), &self.history_search, self.history_status)
    }

    /// Every task the stream knows, finished ones included: what its snapshot holds.
    fn tasks(&self) -> &[Task] {
        self.snapshot
            .as_ref()
            .map_or(&[][..], |snapshot| snapshot.tasks.as_slice())
    }

    /// Space in a transcript: the audio plays from the playhead, or pauses. The first press reads
    /// the clip from the service; the player keeps it, so a later press resumes where it stopped.
    /// Another task's clip (one History played) stops, and this task's is read.
    fn play_pause(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let id = {
            let Screen::Transcript(transcript) = &self.screen else {
                return;
            };
            transcript.task.id
        };
        let pauses = self.clip == Some(id) && self.player.as_ref().is_some_and(Player::playing);
        if !pauses && let Screen::Transcript(transcript) = &mut self.screen {
            transcript.start_tracking();
        }
        self.play_task(id, runtime, service, sender);
    }

    /// Space on task `id`: its own clip pauses while it plays and resumes after; otherwise any
    /// other task's clip stops and `id`'s is read and played from the start.
    fn play_task(
        &self,
        id: i64,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        if let Some(player) = &self.player {
            if self.clip == Some(id) {
                if player.playing() {
                    player.pause();
                } else {
                    player.resume();
                }
                return;
            }
            player.pause();
        }
        read_clip(runtime, service, sender, id, Duration::ZERO, None);
    }

    /// `[` and `]`: the playhead five seconds back or on, clamped to the clip, and the cursor
    /// walks with it again.
    fn seek(&mut self, back: bool) {
        let Some(player) = &self.player else {
            return;
        };
        let length = player.length();
        if length == Duration::ZERO {
            return;
        }
        let step = Duration::from_secs(5);
        let at = player.position();
        let to = if back {
            at.saturating_sub(step)
        } else {
            (at + step).min(length)
        };
        self.move_playhead(to);
    }

    /// Moves the playhead to `to`, the cursor tracking it: what a seek and Enter on a segment do.
    fn move_playhead(&mut self, to: Duration) {
        let Screen::Transcript(transcript) = &mut self.screen else {
            return;
        };
        if let Some(player) = &self.player {
            player.seek(to);
        }
        transcript.track(to);
        transcript.start_tracking();
    }

    /// Enter on a transcript's segment body: seeks to the segment's start while the clip plays,
    /// and plays the task from there otherwise. With segments marked, Enter opens the "Move to"
    /// list instead, and on the Speakers panel it renames or assigns, as before.
    fn play_from_cursor(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let Some((id, start)) = (match &self.screen {
            Screen::Transcript(transcript) => transcript
                .current()
                .map(|segment| (transcript.task.id, Duration::from_millis(segment.start_ms))),
            _ => None,
        }) else {
            return;
        };
        self.move_playhead(start);
        let loaded = self.clip == Some(id)
            && self
                .player
                .as_ref()
                .is_some_and(|player| player.length() > Duration::ZERO);
        if loaded {
            if let Some(player) = &self.player {
                player.resume();
            }
            return;
        }
        read_clip(runtime, service, sender, id, start, None);
    }

    /// `p` on a transcript's segment: plays it alone, from its start, and stops at its end - the
    /// browser's Play-segment control (item 52). Pressed while that segment plays alone, it stops
    /// it back at the segment's start, as the browser's Stop does.
    fn play_segment(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let Screen::Transcript(transcript) = &mut self.screen else {
            return;
        };
        let Some(segment) = transcript.current() else {
            return;
        };
        let id = transcript.task.id;
        let start = Duration::from_millis(segment.start_ms);
        let end = Duration::from_millis(segment.end_ms);
        let player = self.player.get_or_insert_with(Player::start);
        if player.playing()
            && player
                .window()
                .is_some_and(|(from, _)| from.abs_diff(start) < Duration::from_millis(1))
        {
            player.pause();
            player.seek(start);
            transcript.track(start);
            return;
        }
        transcript.track(start);
        transcript.start_tracking();
        if self.clip == Some(id) && player.length() > Duration::ZERO {
            player.play_window(start, end);
            return;
        }
        read_clip(runtime, service, sender, id, start, Some(end));
    }

    /// Space on a History row: plays that task's clip from the start without opening it, or
    /// pauses it while it plays and resumes it after; another row's Space plays that row's clip
    /// instead. A row without a stored clip has nothing to play; it says so itself.
    fn play_history(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let Some(id) = self
            .selected_task()
            .filter(|task| task.has_audio)
            .map(|task| task.id)
        else {
            return;
        };
        self.play_task(id, runtime, service, sender);
    }

    /// Opens the task a duplicate upload offers, as the browser's duplicate link does: its
    /// transcript once it is done, its queue row while it still waits or runs, History on one that
    /// failed or was canceled; a task the queue no longer holds opens nothing.
    fn open_duplicate(
        &mut self,
        id: i64,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let Some(task) = self.tasks().iter().find(|task| task.id == id).cloned() else {
            return;
        };
        match task.status.as_str() {
            "QUEUED" | "RUNNING" | "PAUSED" => {
                // The queue's rows, in the queue's order, not the snapshot's task list.
                if let Some(index) = state::rows(self.snapshot.as_ref())
                    .iter()
                    .position(|entry| entry.id == id)
                {
                    self.selected = index;
                    self.focus = QueueFocus::Rows;
                }
            }
            "FAILED" | "CANCELED" => {
                self.history_selected = self
                    .history_rows()
                    .iter()
                    .position(|task| task.id == id)
                    .unwrap_or(0);
                self.leave_screen();
                self.screen = Screen::History;
            }
            _ => self.open_task(task, runtime, service, sender),
        }
    }

    /// Offers task `id` as a notice above the queue - a finished task's line or a duplicate
    /// upload's offer - the newest first and at most five; the same task replaces its line rather
    /// than adding one, and a notice the list no longer holds hands the keyboard back below.
    fn offer_notice(&mut self, id: i64, filename: String, status: &str) {
        self.notices.retain(|notice| notice.id != id);
        self.notices.insert(
            0,
            Notice {
                id,
                filename,
                status: status.to_string(),
            },
        );
        self.notices.truncate(5);
        if let QueueFocus::Notice(index) = self.focus
            && index >= self.notices.len()
        {
            self.focus = match self.notices.len().checked_sub(1) {
                Some(last) => QueueFocus::Notice(last),
                None => QueueFocus::Upload,
            };
        }
    }

    /// While a transcript's cursor walks with the playhead, it moves to the playing segment.
    fn track_playhead(&mut self) {
        let Screen::Transcript(transcript) = &mut self.screen else {
            return;
        };
        if !transcript.tracking() {
            return;
        }
        if let Some(player) = &self.player {
            transcript.track(player.position());
        }
    }

    /// Opens `task` as its status allows: a completed task's transcript, which asks for its turns;
    /// an ended one's status page; and the waiting page while it is still in the queue. Neither
    /// status page asks the service for anything.
    fn open_task(
        &mut self,
        task: Task,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        self.leave_screen();
        if task.status == "COMPLETED" {
            self.screen = Screen::Transcript(Transcript::loading(task.clone()));
            open_transcript(runtime, service, sender, task);
        } else {
            self.screen = Screen::Status(task);
        }
    }

    /// Whether History's search or filter hides any row.
    fn filters_active(&self) -> bool {
        !self.history_search.is_empty() || self.history_status != state::Status::All
    }

    /// Clears History's search and status filter, leaving every row.
    fn clear_filters(&mut self) {
        self.history_search.clear();
        self.history_status = state::Status::All;
        self.searching = false;
        self.history_selected = 0;
    }

    /// The event stream's state: reconnecting after a drop until a new snapshot, live once a
    /// snapshot arrived, connecting before the first.
    fn connection(&self) -> view::Connection {
        if self.disconnected {
            view::Connection::Reconnecting
        } else if self.snapshot.is_some() {
            view::Connection::Live
        } else {
            view::Connection::Connecting
        }
    }

    /// The open transcript's confirmation, while a merge or reassignment waits for one.
    fn transcript_dialog(&self) -> Option<view::Dialog> {
        match &self.screen {
            Screen::Transcript(transcript) => transcript.dialog(),
            _ => None,
        }
    }

    /// The confirmation over the page, in the one dialog style: the queue's, the settings page's, or
    /// the transcript's.
    fn dialog(&self) -> Option<view::Dialog> {
        // A dialog from its catalog texts, the title naming the task's file where it has one.
        let dialog = |title: &str, filename: &str, body: &str, confirm: &str| view::Dialog {
            title: text(title, &[("filename", filename)]),
            body: text(body, &[]),
            confirm: text(confirm, &[]),
        };
        match self.confirm.as_ref() {
            Some(Confirm::Delete(task)) => Some(dialog(
                "queue.delete.title",
                &task.filename,
                "queue.delete.body",
                "queue.delete.confirm",
            )),
            Some(Confirm::Cancel(task)) => Some(dialog(
                "queue.cancel.title",
                &task.filename,
                "queue.cancel.body",
                "queue.cancel.confirm",
            )),
            Some(Confirm::RestoreDefaults) => Some(dialog(
                "settings.confirmTitle",
                "",
                "settings.confirmBody",
                "settings.confirmRestore",
            )),
            None => self.transcript_dialog(),
        }
    }

    /// The catalog name of the write the open confirmation would send, if one is open.
    fn confirming(&self) -> Option<&'static str> {
        match (&self.confirm, &self.screen) {
            (Some(Confirm::Delete(_)), _) => Some("tui.writes.delete"),
            (Some(Confirm::Cancel(_)), _) => Some("tui.writes.cancel"),
            (Some(Confirm::RestoreDefaults), _) => Some("tui.writes.restoreDefaults"),
            (None, Screen::Transcript(transcript)) => transcript.pending_write(),
            (None, _) => None,
        }
    }

    /// A key while a confirmation is open: Enter sends what it asks about - the queue's delete or
    /// cancel, or the transcript's merge or reassignment - Escape closes it with nothing sent, and
    /// any other key leaves it open.
    fn answer(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let yes = matches!(action, Action::ConfirmYes);
        let no = matches!(action, Action::ConfirmNo);
        match self.confirm.take() {
            Some(Confirm::Delete(task)) if yes => {
                let service = service.clone();
                dispatch(runtime, sender, "tui.task.deleteFailed", async move {
                    delete_task(&service, task.id)
                        .await
                        .map(|()| text("tui.task.deleted", &[("filename", &task.filename)]))
                });
                // A transcript's task is on its way out: its page goes back to History, where the
                // notice - or a failed write's - shows.
                if matches!(self.screen, Screen::Transcript(_)) {
                    self.leave_screen();
                    self.screen = Screen::History;
                }
            }
            Some(Confirm::Cancel(task)) if yes => {
                send_command(runtime, service, sender, task.id, TaskCommand::Cancel);
            }
            Some(Confirm::RestoreDefaults) if yes => {
                if let Screen::Settings(screen) = &mut self.screen {
                    screen.restore(runtime, service, sender);
                }
            }
            Some(confirm) if !no => self.confirm = Some(confirm),
            Some(_) => {}
            None => {
                if let Screen::Transcript(transcript) = &mut self.screen {
                    if yes {
                        transcript.confirm(runtime, sender, service);
                    } else if no {
                        transcript.dismiss();
                    }
                }
            }
        }
    }

    /// Whether a task is RUNNING: the header's tile types then, as the browser's does.
    fn typing(&self) -> bool {
        self.snapshot
            .as_ref()
            .is_some_and(|snapshot| snapshot.tasks.iter().any(|task| task.status == "RUNNING"))
    }

    /// Whether a tile on screen types: the header's while a task runs, or the empty queue's, which
    /// always does.
    fn animating(&self) -> bool {
        let empty_queue = matches!(self.screen, Screen::Queue)
            && self.snapshot.is_some()
            && state::rows(self.snapshot.as_ref()).is_empty();
        // A playing clip's bar follows the playhead, and a play on its way is about to show, so
        // the loop wakes for them too.
        let player_busy = self.player.as_ref().is_some_and(Player::busy);
        self.typing() || empty_queue || player_busy
    }

    /// One frame of the loop: the cursor follows the playhead if it walks with it, then the screen
    /// is drawn.
    fn frame(&mut self, terminal: &mut DefaultTerminal) -> io::Result<()> {
        self.track_playhead();
        self.draw(terminal)
    }

    fn draw(&mut self, terminal: &mut DefaultTerminal) -> io::Result<()> {
        let view = self.view();
        let mut page = self.page;
        // One frame, one terminal update: the terminal shows the whole of it or none of it, and a
        // reader of the output (the pty tests) knows where a frame ends.
        let mut out = io::stdout();
        execute!(out, BeginSynchronizedUpdate)?;
        let mut frame_area = self.frame_area;
        let previous = &self.previous_frame;
        let drawn = terminal.draw(|frame| {
            frame_area = frame.area();
            page = view::draw(frame, &view);
            // ratatui does not repaint a column the terminal blanked under a wide character drawn
            // over half of another one; marked here, it does.
            wide_overlap::repaint_orphans(previous, frame.buffer_mut());
        });
        let ended = execute!(out, EndSynchronizedUpdate);
        if let Ok(frame) = &drawn {
            // The pty tests' report of the frame just drawn (ADR 0070); debug builds only.
            #[cfg(debug_assertions)]
            frame_log::record(frame.buffer);
            self.previous_frame.clone_from(frame.buffer);
        }
        self.page = page;
        self.frame_area = frame_area;
        drawn.map(|_| ()).and(ended)
    }

    /// Applies one message; `Outcome::Quit` when the user asked to leave.
    fn handle(
        &mut self,
        message: Message,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        match message {
            Message::Service(event) => {
                // A snapshot opens every stream: the view is current again.
                if matches!(event, Event::Snapshot(_)) {
                    self.disconnected = false;
                    // The batches the connection cut off go again now that it is back, as the
                    // browser's waiting uploads do: one after another, so the queue takes them in
                    // the order they were sent.
                    let held = std::mem::take(&mut self.held);
                    if !held.is_empty() {
                        let service = service.clone();
                        let sender = sender.clone();
                        runtime.spawn(async move {
                            let mut held = held;
                            held.sort_by_key(|held| held.turn);
                            for Held {
                                files,
                                options,
                                name: _,
                                turn,
                            } in held
                            {
                                crate::upload_dialog::send_in_turn(
                                    files,
                                    options,
                                    service.clone(),
                                    sender.clone(),
                                    turn,
                                )
                                .await;
                            }
                        });
                    }
                }
                // The fallback notice and the theme follow the service's own report, whichever
                // screen shows.
                if let Event::SettingsUpdated(settings) = &event {
                    self.stored(settings.clone());
                }
                // Speakers or settings changed elsewhere (the browser, another TUI): the open screen
                // follows them - a transcript of the task, which after a merge reads its turns
                // again, or the settings.
                match (&mut self.screen, &event) {
                    (Screen::Transcript(transcript), Event::SpeakersRenamed(change)) => {
                        transcript.set_speakers(change.id, change.speakers.clone());
                    }
                    (Screen::Transcript(transcript), Event::SpeakersMerged(change))
                        if change.id == transcript.task.id =>
                    {
                        transcript.set_speakers(change.id, change.speakers.clone());
                        reread_turns(runtime, service, sender, change.id);
                    }
                    (Screen::Transcript(transcript), Event::SpeakersReassigned(change))
                        if change.id == transcript.task.id =>
                    {
                        transcript.set_speakers(change.id, change.speakers.clone());
                        reread_turns(runtime, service, sender, change.id);
                    }
                    (Screen::Transcript(transcript), Event::SegmentEdited(change))
                        if change.id == transcript.task.id =>
                    {
                        reread_turns(runtime, service, sender, change.id);
                    }
                    (Screen::Settings(screen), Event::SettingsUpdated(settings)) => {
                        screen.updated(settings.clone());
                    }
                    _ => {}
                }
                // A task that finished shows as a notice on the queue, the newest first; the same
                // task again (a retry, then another finish) replaces its line rather than adding.
                if let Event::TaskUpdated(task) = &event
                    && matches!(task.status.as_str(), "COMPLETED" | "FAILED" | "CANCELED")
                {
                    self.offer_notice(task.id, task.filename.clone(), &task.status);
                }
                state::apply(&mut self.snapshot, event);
                // A deleted task leaves History's list with it, and its selection stays on a row.
                self.history_selected = self
                    .history_selected
                    .min(self.history_rows().len().saturating_sub(1));
                let rows = state::rows(self.snapshot.as_ref()).len();
                if rows > 0 {
                    self.selected = self.selected.min(rows - 1);
                }
            }
            Message::UploadProgress {
                id,
                filename,
                sent,
                total,
            } => match self.uploads.iter_mut().find(|uploading| uploading.id == id) {
                Some(uploading) => {
                    uploading.sent = sent;
                    uploading.total = total;
                }
                None => self.uploads.push(Uploading {
                    id,
                    filename,
                    sent,
                    total,
                }),
            },
            Message::UploadDone(id) => {
                self.uploads.retain(|uploading| uploading.id != id);
            }
            Message::Notice(line) => self.notice = Some(line),
            // A duplicate upload's offer takes a notice line, newest first, as a finished task's
            // does; Enter on it opens the task it names.
            Message::Duplicate { task, filename } => {
                self.offer_notice(task, filename, "DUPLICATE");
            }
            // An upload the connection cut off waits on a line of its own until the next
            // snapshot sends it again; a batch cut off after it waits beside it.
            Message::UploadHeld {
                files,
                options,
                name,
                turn,
            } => {
                self.held.push(Held {
                    files,
                    options,
                    name,
                    turn,
                });
            }
            Message::Disconnected => self.disconnected = true,
            // The turns open the transcript only while it is the screen they were read for: the
            // user may have gone elsewhere since.
            Message::Transcript(task, turns) => {
                if matches!(&self.screen, Screen::Transcript(open) if open.task.id == task.id) {
                    self.screen = Screen::Transcript(Transcript::new(task, turns));
                }
            }
            Message::TranscriptFailed(id, error) => {
                if let Screen::Transcript(transcript) = &mut self.screen
                    && transcript.task.id == id
                {
                    transcript.load_failed(error);
                }
            }
            Message::Speakers(id, speakers) => {
                if let Screen::Transcript(transcript) = &mut self.screen {
                    transcript.set_speakers(id, speakers);
                }
            }
            Message::Turns(id, turns) => {
                if let Screen::Transcript(transcript) = &mut self.screen {
                    transcript.set_turns(id, turns);
                }
            }
            Message::Audio(id, bytes, from, until) => {
                // The clip plays where it was asked for: its task's open transcript or status
                // page, or History.
                let open = match &self.screen {
                    Screen::Transcript(transcript) => transcript.task.id == id,
                    Screen::Status(task) => task.id == id,
                    Screen::History => true,
                    _ => false,
                };
                if open {
                    let player = self.player.get_or_insert_with(Player::start);
                    player.play(bytes, from, until);
                    self.clip = Some(id);
                    if let Screen::Transcript(transcript) = &mut self.screen {
                        transcript.start_tracking();
                    }
                }
            }
            Message::EditRefused {
                task,
                segment,
                draft,
                error,
            } => {
                if let Screen::Transcript(transcript) = &mut self.screen
                    && transcript.task.id == task
                {
                    transcript.keep_draft(segment, draft);
                }
                self.notice = Some(text("tui.segment.editFailed", &[("error", &error)]));
            }
            // The settings are stored whenever they come; their page opens only while the user
            // still waits for it.
            Message::Settings(settings, frontends) => {
                self.stored(settings.clone());
                if std::mem::take(&mut self.settings_wanted) {
                    self.screen = Screen::Settings(SettingsScreen::new(settings, frontends));
                }
            }
            // The settings page opens even then, with why the read failed and Enter to read again
            // - while the user still waits for it.
            Message::SettingsFailed(error) => {
                if std::mem::take(&mut self.settings_wanted) {
                    self.screen = Screen::SettingsFailed(text(
                        "tui.settings.loadFailed",
                        &[("error", &error)],
                    ));
                }
            }
            Message::Stored(settings) => self.stored(settings),
            // Only the open dialog's own read: an earlier dialog's answer is not this one's.
            Message::UploadDefaults(opened, settings) => {
                if let Some(upload) = self.upload.as_mut()
                    && opened == self.uploads_opened
                {
                    upload.defaults(&settings);
                }
            }
            Message::SettingsSaved(settings) => {
                self.stored(settings.clone());
                if let Screen::Settings(screen) = &mut self.screen {
                    screen.saved(settings);
                }
            }
            // On the settings page a setting's refusal stands in its row; any other is the notice.
            Message::SettingRefused(field, message) => match (&mut self.screen, field) {
                (Screen::Settings(screen), Some(field)) => screen.refused(field, message),
                _ => {
                    self.notice = Some(text("tui.settings.saveFailed", &[("error", &message)]));
                }
            },
            Message::RestoreFailed(message) => {
                if let Screen::Settings(screen) = &mut self.screen {
                    screen.restore_failed();
                }
                self.notice = Some(text("tui.settings.restoreFailed", &[("error", &message)]));
            }
            Message::Terminal(terminal_event::Event::Key(key)) => {
                let mode = self.input_mode();
                let context = self.input_context();
                // While the key reference is open the arrows, j/k and the paging keys scroll its
                // table, which may be taller than the frame.
                if self.help && key.kind == ratatui::crossterm::event::KeyEventKind::Press {
                    let last = view::help_max_scroll(self.frame_area, &self.view());
                    let scroll = match key.code {
                        KeyCode::Up | KeyCode::Char('k') => {
                            Some(self.help_scroll.saturating_sub(1))
                        }
                        KeyCode::Down | KeyCode::Char('j') => Some(self.help_scroll + 1),
                        KeyCode::PageUp => Some(self.help_scroll.saturating_sub(10)),
                        KeyCode::PageDown => Some(self.help_scroll + 10),
                        KeyCode::Home => Some(0),
                        KeyCode::End => Some(last),
                        _ => None,
                    };
                    if let Some(scroll) = scroll {
                        self.help_scroll = scroll.min(last);
                        return Outcome::Continue;
                    }
                }
                let Some(action) = input::action(&key, mode, context) else {
                    return Outcome::Continue;
                };
                // Ctrl-C quits from everywhere - over the key reference and a confirmation too:
                // the user is never trapped.
                if matches!(action, Action::Quit) {
                    return Outcome::Quit;
                }
                // The key reference: `?` opens it, `?` or Escape closes it, and while it is open
                // nothing else acts.
                if self.help {
                    if matches!(action, Action::Help | Action::BackOrQuit) {
                        self.help = false;
                    }
                    return Outcome::Continue;
                }
                if matches!(action, Action::Help) {
                    self.help = true;
                    self.help_scroll = 0;
                    return Outcome::Continue;
                }
                // On History an active search or filter goes before the page: Escape clears both
                // and stays, as the no-match line says.
                if key.code == KeyCode::Esc
                    && matches!(self.screen, Screen::History)
                    && !self.searching
                    && self.filters_active()
                {
                    self.clear_filters();
                    return Outcome::Continue;
                }
                if self.refused_offline(action, mode) {
                    return Outcome::Continue;
                }
                // The confirmation answers over the page: Enter does what it asks, Escape does not.
                if self.confirming().is_some() {
                    self.answer(action, runtime, service, sender);
                    return Outcome::Continue;
                }
                // The pages switch, and the theme cycles, from anywhere, before any screen acts on
                // the key.
                match action {
                    Action::OpenPage(page) => {
                        self.open_page(page, runtime, service, sender);
                        return Outcome::Continue;
                    }
                    Action::PageNext => {
                        self.step_page(true, runtime, service, sender);
                        return Outcome::Continue;
                    }
                    Action::PagePrev => {
                        self.step_page(false, runtime, service, sender);
                        return Outcome::Continue;
                    }
                    Action::CycleTheme => {
                        self.cycle_theme(runtime, service, sender);
                        return Outcome::Continue;
                    }
                    _ => {}
                }
                // Enter on a transcript's segment body plays from that segment (or seeks to it);
                // marked segments and the Speakers panel keep Enter's assign and rename.
                if matches!(action, Action::OpenSelected) {
                    let body_enter = match &self.screen {
                        Screen::Transcript(transcript) => {
                            !transcript.speaker_focus()
                                && !transcript.marking()
                                && transcript.current().is_some()
                        }
                        _ => false,
                    };
                    if body_enter {
                        self.play_from_cursor(runtime, service, sender);
                        return Outcome::Continue;
                    }
                }
                return match self.screen {
                    Screen::Queue => self.queue_action(action, runtime, service, sender),
                    Screen::History => self.history_action(action, runtime, service, sender),
                    _ => self.other_action(action, runtime, service, sender),
                };
            }
            Message::Terminal(terminal_event::Event::Paste(text)) => {
                self.paste(&text, runtime, service, sender);
            }
            Message::Mouse(mouse, at) => match mouse.kind {
                MouseEventKind::Down(MouseButton::Left) => {
                    self.click(mouse.column, mouse.row, at, runtime, service, sender);
                }
                // A wheel notch moves the focused list, as Up/Down do: the page the user is on.
                MouseEventKind::ScrollUp => {
                    self.wheel(Action::SelectUp, runtime, service, sender);
                }
                MouseEventKind::ScrollDown => {
                    self.wheel(Action::SelectDown, runtime, service, sender);
                }
                // A two-finger horizontal scroll moves between the tabs as Left and Right do on a
                // page - on a transcript too, where it leaves the segment cursor alone.
                MouseEventKind::ScrollLeft => {
                    self.step_page(false, runtime, service, sender);
                }
                MouseEventKind::ScrollRight => {
                    self.step_page(true, runtime, service, sender);
                }
                _ => {}
            },
            Message::Terminal(_) => {}
        }
        Outcome::Continue
    }

    /// Opens the tab after the page's (`forward`) or the one before it.
    fn step_page(
        &mut self,
        forward: bool,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        let page = match forward {
            true => self.screen.page().next(),
            false => self.screen.page().previous(),
        };
        self.open_page(page, runtime, service, sender);
    }

    /// Opens `page`, unless it is the page shown; Settings reads what it shows first. A
    /// transcript's audio does not go on behind another page: leaving it pauses the clip, which the
    /// transcript, opened again, resumes from where it stood.
    fn open_page(
        &mut self,
        page: Page,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        if self.screen.page() == page {
            return;
        }
        self.leave_screen();
        self.stop_page_playback();
        match page {
            Page::Queue => self.screen = Screen::Queue,
            Page::History => self.screen = Screen::History,
            Page::Settings => self.show_settings(runtime, service, sender),
        }
    }

    /// What going to another screen ends: History's search (as Enter does - the query stays, the
    /// keys are the next screen's) and a settings read still on its way, whose page opens only if
    /// that is where the user went.
    fn leave_screen(&mut self) {
        self.searching = false;
        self.settings_wanted = false;
    }

    /// Reads the settings for their page, which opens when they arrive - unless the user has gone
    /// elsewhere by then.
    fn show_settings(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        self.settings_wanted = true;
        open_settings(runtime, service, sender);
    }

    /// Leaving a transcript or a status page - for another page, or back to History - stops what
    /// it plays; the player keeps the clip, so the page, opened again, plays on from where it stood.
    fn stop_page_playback(&self) {
        if let (Screen::Transcript(_) | Screen::Status(_), Some(player)) =
            (&self.screen, &self.player)
        {
            player.pause();
        }
    }

    /// The queue's keys: its commands, selection and reordering, and the upload prompt.
    fn queue_action(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        // While the upload dialog is open it takes every key but quit, so no page or queue command
        // acts behind it.
        if !matches!(action, Action::Quit)
            && let Some(upload) = self.upload.as_mut()
        {
            if matches!(action, Action::SubmitPrompt) {
                self.batches_sent += 1;
                upload.set_turn(self.batches_sent);
            }
            if upload.handle(action, runtime, sender, service) {
                self.upload = None;
                if let Some(path) = self.pasted.pop_front() {
                    self.open_upload(UploadDialog::open_with(&path), runtime, service, sender);
                }
            }
            return Outcome::Continue;
        }
        match action {
            Action::Quit | Action::BackOrQuit => return Outcome::Quit,
            Action::Back => {}
            Action::OpenSelected => {
                if let QueueFocus::Notice(index) = self.focus {
                    // A notice opens its target: the transcript of a completed task, or History,
                    // on the failed or canceled one's row.
                    if let Some((id, status)) = self
                        .notices
                        .get(index)
                        .map(|notice| (notice.id, notice.status.clone()))
                    {
                        if status == "DUPLICATE" {
                            self.open_duplicate(id, runtime, service, sender);
                        } else if status == "COMPLETED" {
                            if let Some(task) =
                                self.tasks().iter().find(|task| task.id == id).cloned()
                            {
                                self.open_task(task, runtime, service, sender);
                            }
                        } else {
                            self.history_selected = self
                                .history_rows()
                                .iter()
                                .position(|task| task.id == id)
                                .unwrap_or(0);
                            self.leave_screen();
                            self.screen = Screen::History;
                        }
                    }
                    self.focus = QueueFocus::Rows;
                } else if self.focus == QueueFocus::Upload {
                    self.open_upload(UploadDialog::open(), runtime, service, sender);
                } else if let Some(task) = self.selected_task().cloned() {
                    self.open_task(task, runtime, service, sender);
                }
            }
            Action::DismissFallback => {
                if let Some(device) = self.device_fallback.take() {
                    let _ = device;
                    let service = service.clone();
                    let sender = sender.clone();
                    runtime.spawn(async move {
                        let message = match dismiss_device_fallback(&service).await {
                            Ok(settings) => Message::Stored(settings),
                            Err(error) => Message::Notice(text(
                                "queue.fallback.dismissFailed",
                                &[("error", &error.to_string())],
                            )),
                        };
                        let _ = sender.send(message);
                    });
                }
            }
            Action::OpenDownload | Action::DeleteTask => {
                if let Some(task) = task_at(self.snapshot.as_ref(), self.selected) {
                    self.confirm = Some(Confirm::Delete(task.clone()));
                }
            }
            Action::ConfirmYes | Action::ConfirmNo => {}
            Action::DownloadChar(_)
            | Action::DownloadBackspace
            | Action::DownloadTab
            | Action::SubmitDownload
            | Action::CancelDownload
            | Action::Merge
            | Action::ResetMerges
            | Action::MarkSegments
            | Action::MarkTurn
            | Action::AssignNewSpeaker
            | Action::EditSegment
            | Action::RevertSegment
            | Action::SegmentPrevious
            | Action::SegmentNext
            | Action::ScrollPrevious
            | Action::ScrollNext
            | Action::GotoStart
            | Action::GotoEnd
            | Action::Adjust(_)
            | Action::OpenPage(_)
            | Action::PageNext
            | Action::PagePrev
            | Action::Help
            | Action::CycleTheme
            | Action::Search
            | Action::FilterNext
            | Action::FieldNext
            | Action::FieldPrev
            | Action::PlayPause
            | Action::PlaySegment
            | Action::SeekBack
            | Action::SeekForward => {}
            Action::OpenSettings => self.show_settings(runtime, service, sender),
            Action::OpenPrompt => {
                self.open_upload(UploadDialog::open(), runtime, service, sender);
            }
            Action::CancelPrompt
            | Action::PromptBackspace
            | Action::PromptChar(_)
            | Action::SubmitPrompt => {}
            // The queue's page runs, downward as it is drawn: the upload area, the rows under it,
            // and the finished tasks' notices under them.
            Action::SelectUp => {
                let rows = state::rows(self.snapshot.as_ref()).len();
                match self.focus {
                    QueueFocus::Notice(0) if rows > 0 => {
                        self.selected = rows - 1;
                        self.focus = QueueFocus::Rows;
                    }
                    QueueFocus::Notice(0) => self.focus = QueueFocus::Upload,
                    QueueFocus::Notice(index) => self.focus = QueueFocus::Notice(index - 1),
                    QueueFocus::Upload => {}
                    QueueFocus::Rows if self.selected == 0 => self.focus = QueueFocus::Upload,
                    QueueFocus::Rows => self.selected -= 1,
                }
            }
            Action::SelectDown => {
                let rows = state::rows(self.snapshot.as_ref()).len();
                match self.focus {
                    QueueFocus::Notice(index) if index + 1 < self.notices.len() => {
                        self.focus = QueueFocus::Notice(index + 1);
                    }
                    QueueFocus::Notice(_) => {}
                    QueueFocus::Upload if rows > 0 => self.focus = QueueFocus::Rows,
                    QueueFocus::Rows if self.selected + 1 < rows => self.selected += 1,
                    QueueFocus::Upload | QueueFocus::Rows if !self.notices.is_empty() => {
                        self.focus = QueueFocus::Notice(0);
                    }
                    QueueFocus::Upload | QueueFocus::Rows => {}
                }
            }
            Action::MoveSelected(step) if self.on_rows() => {
                move_selected(runtime, sender, service, self, step);
            }
            Action::MoveSelected(_) => {}
            Action::MoveToTop if self.on_rows() => {
                move_selected_to(runtime, sender, service, self, 0);
            }
            Action::MoveToBottom if self.on_rows() => {
                let last = self.snapshot.as_ref().map_or(0, |snapshot| {
                    snapshot.queue.task_ids.len().saturating_sub(1)
                });
                move_selected_to(runtime, sender, service, self, last);
            }
            Action::MoveToTop | Action::MoveToBottom => {}
            Action::ToggleQueuePause if self.focus == QueueFocus::Upload => {
                self.open_upload(UploadDialog::open(), runtime, service, sender);
            }
            Action::ToggleQueuePause => {
                let paused = self.snapshot.as_ref().is_some_and(|s| s.queue.paused);
                let service = service.clone();
                dispatch(runtime, sender, "tui.command.failed", async move {
                    set_queue_paused(&service, !paused).await.map(|queue| {
                        text(
                            if queue.paused {
                                "tui.queue.paused"
                            } else {
                                "tui.queue.running"
                            },
                            &[],
                        )
                    })
                });
            }
            Action::TaskKey(character) if self.on_rows() => {
                if let Some(task) = task_at(self.snapshot.as_ref(), self.selected)
                    && let Some(command) = input::command_for(task, character)
                {
                    // Cancelling a running task, or a paused one that has started, loses work: it
                    // asks first, as the browser does; a task never started is cancelled at once.
                    let started = task.status == "RUNNING"
                        || (task.status == "PAUSED" && task.started_at.is_some());
                    if command == TaskCommand::Cancel && started {
                        self.confirm = Some(Confirm::Cancel(task.clone()));
                    } else {
                        send_command(runtime, service, sender, task.id, command);
                    }
                }
            }
            Action::TaskKey(_) => {}
            // The follow key belongs to a transcript; the queue has no playhead to follow.
            Action::FollowPlayhead => {}
        }
        Outcome::Continue
    }

    /// History's keys: move, open the selected task, retry a failed or cancelled one, delete one
    /// after the dialog; the keys every page shares go on to `other_action`.
    fn history_action(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        match action {
            // `/` starts (or restarts) the search, which the controls line shows as it is typed.
            Action::Search => self.searching = true,
            Action::FilterNext => {
                self.history_status = self.history_status.next();
                self.history_selected = 0;
            }
            Action::PromptChar(character) if self.searching => {
                self.history_search.push(character);
                self.history_selected = 0;
            }
            Action::PromptBackspace if self.searching => {
                graphemes::pop(&mut self.history_search);
                self.history_selected = 0;
            }
            Action::SubmitPrompt if self.searching => self.searching = false,
            // Escape in the search clears it and the filter, as the no-match line says.
            Action::CancelPrompt if self.searching => self.clear_filters(),
            Action::SelectUp => {
                self.history_selected = self.history_selected.saturating_sub(1);
            }
            Action::SelectDown => {
                let rows = self.history_rows().len();
                if self.history_selected + 1 < rows {
                    self.history_selected += 1;
                }
            }
            Action::OpenSelected => {
                if let Some(task) = self.selected_task().cloned() {
                    self.open_task(task, runtime, service, sender);
                }
            }
            // `r` on a FAILED or CANCELED row retries it: the task goes back to the queue.
            Action::TaskKey(key) => {
                if let Some(task) = self.selected_task()
                    && let Some(command) = input::command_for(task, key)
                    && command == TaskCommand::Retry
                {
                    send_command(runtime, service, sender, task.id, command);
                }
            }
            // Delete or Backspace on any row asks in the queue's dialog first.
            Action::DeleteTask => {
                if let Some(task) = self.selected_task().cloned() {
                    self.confirm = Some(Confirm::Delete(task));
                }
            }
            // Space plays the selected row's audio; the queue's pause is the Queue page's.
            Action::ToggleQueuePause => self.play_history(runtime, service, sender),
            _ => return self.other_action(action, runtime, service, sender),
        }
        Outcome::Continue
    }

    /// Every other screen, and the keys the pages share: quit, go back, open the settings, the
    /// download dialog, and a transcript's or the settings' own keys.
    fn other_action(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) -> Outcome {
        match action {
            Action::Quit => return Outcome::Quit,
            Action::BackOrQuit | Action::Back => {
                self.stop_page_playback();
                self.leave_screen();
                self.screen = self.screen.back();
            }
            Action::PlayPause => self.play_pause(runtime, service, sender),
            Action::PlaySegment => self.play_segment(runtime, service, sender),
            Action::SeekBack => self.seek(true),
            Action::SeekForward => self.seek(false),
            // `f` returns the cursor to the playing segment - the playhead stays - and following
            // resumes; a paused clip has no playing segment to return to.
            Action::FollowPlayhead => {
                if let Screen::Transcript(transcript) = &mut self.screen
                    && let Some(player) = &self.player
                    && player.playing()
                {
                    transcript.follow_playhead(player.position());
                }
            }
            Action::OpenSettings => self.show_settings(runtime, service, sender),
            Action::OpenDownload => {
                if let Screen::Transcript(transcript) = &self.screen {
                    self.download = Some(Download::new(transcript.task.clone()));
                }
            }
            // Delete or Backspace on a transcript asks in the queue's dialog first, as a row does.
            Action::DeleteTask => {
                if let Screen::Transcript(transcript) = &self.screen {
                    self.confirm = Some(Confirm::Delete(transcript.task.clone()));
                }
            }
            // The download dialog takes its own keys, the list's and the format's alike.
            action
                if self.download.is_some()
                    && matches!(
                        action,
                        Action::DownloadChar(_)
                            | Action::DownloadBackspace
                            | Action::DownloadTab
                            | Action::SelectUp
                            | Action::SelectDown
                            | Action::Adjust(_)
                            | Action::CancelDownload
                            | Action::SubmitDownload
                    ) =>
            {
                if let Some(download) = self.download.as_mut()
                    && download.handle(action, runtime, sender, service)
                {
                    self.download = None;
                }
            }
            action => {
                // A failed read says why; Enter tries again, and while the turns are on their
                // way the page's keys do nothing.
                if let Screen::Transcript(transcript) = &self.screen
                    && transcript.load_error().is_some()
                    && matches!(action, Action::OpenSelected)
                {
                    let task = transcript.task.clone();
                    self.screen = Screen::Transcript(Transcript::loading(task.clone()));
                    open_transcript(runtime, service, sender, task);
                    return Outcome::Continue;
                }
                // A FAILED or CANCELED task's page retries with `r`, which sends it back to the
                // queue - where the page then goes.
                if let Screen::Status(task) = &self.screen
                    && matches!(action, Action::TaskKey('r'))
                    && let Some(command) = input::command_for(task, 'r')
                {
                    send_command(runtime, service, sender, task.id, command);
                    self.leave_screen();
                    self.screen = Screen::Queue;
                    return Outcome::Continue;
                }
                // A finished task's page plays its stored audio with Space, as the browser's status
                // page shows its player; without audio the page says why and Space does nothing.
                if let Screen::Status(task) = &self.screen
                    && matches!(action, Action::ToggleQueuePause)
                {
                    if crate::state::ended(&task.status) && task.has_audio {
                        let id = task.id;
                        self.play_task(id, runtime, service, sender);
                    }
                    return Outcome::Continue;
                }
                match &mut self.screen {
                    Screen::Transcript(transcript) => {
                        if !transcript.is_loading() {
                            transcript.handle(action, runtime, sender, service);
                        }
                    }
                    // The restore row's Enter asks first, in the shared confirmation.
                    Screen::Settings(screen)
                        if screen.on_restore() && matches!(action, Action::OpenSelected) =>
                    {
                        self.confirm = Some(Confirm::RestoreDefaults);
                    }
                    Screen::Settings(screen) => screen.handle(action, runtime, sender, service),
                    // Enter reads a settings page whose read failed again.
                    Screen::SettingsFailed(_) if matches!(action, Action::OpenSelected) => {
                        self.show_settings(runtime, service, sender);
                    }
                    Screen::SettingsFailed(_)
                    | Screen::Status(_)
                    | Screen::Queue
                    | Screen::History => {}
                }
            }
        }
        Outcome::Continue
    }
}

/// How often a screen with a dialog on it is repainted whole, in case another process sharing the
/// terminal wrote over it: a backend started from the same shell with `&` prints its task output
/// there, and the TUI's own diffing would leave those cells be.
const OVERLAY_REPAINT: std::time::Duration = std::time::Duration::from_millis(500);

/// Draws the open screen, again after every event, until the user quits. When a dialog, a
/// confirmation or the key reference closes, the whole screen is repainted: a terminal that drew one
/// of its glyphs wider than counted keeps cells the next draw would otherwise leave alone. While a
/// task runs the stenographer steps on a timer of its own, so a stream of events does not stall it;
/// with a dialog open the loop also wakes on `OVERLAY_REPAINT` to repaint every cell.
fn show(
    terminal: &mut DefaultTerminal,
    messages: &mpsc::Receiver<Message>,
    runtime: tokio::runtime::Handle,
    service: Service,
    sender: mpsc::Sender<Message>,
    clues: theme::Clues,
) -> io::Result<()> {
    let mut ui = Ui::new(clues);
    ui.draw(terminal)?;
    let mut next_step = Instant::now() + stenographer::STEP;
    let mut next_repaint = Instant::now() + OVERLAY_REPAINT;
    let mut was_animating = false;
    loop {
        let animating = ui.animating();
        let overlay = ui.overlay_open();
        // What stopped the animation - a clip at its end, a play that failed - changed the screen
        // after the last timed frame; one more frame shows it before the loop sleeps.
        if was_animating && !animating {
            ui.frame(terminal)?;
        }
        was_animating = animating;
        let message = if animating || overlay {
            let now = Instant::now();
            let mut deadline = now + std::time::Duration::from_secs(3600);
            if animating {
                deadline = deadline.min(next_step);
            }
            if overlay {
                deadline = deadline.min(next_repaint);
            }
            let wait = deadline.saturating_duration_since(Instant::now());
            match messages.recv_timeout(wait) {
                Ok(message) => Some(message),
                Err(mpsc::RecvTimeoutError::Timeout) => None,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            }
        } else {
            match messages.recv() {
                Ok(message) => Some(message),
                Err(_) => break,
            }
        };
        let Some(message) = message else {
            let now = Instant::now();
            if animating && now >= next_step {
                ui.stenographer += 1;
                next_step = now + stenographer::STEP;
            }
            if overlay && now >= next_repaint {
                // Another process on this terminal may have written over the dialog: resetting
                // the back buffer makes the frame below write every cell again.
                terminal.swap_buffers();
                next_repaint = now + OVERLAY_REPAINT;
            }
            ui.frame(terminal)?;
            continue;
        };
        if overlay {
            next_repaint = Instant::now() + OVERLAY_REPAINT;
        }
        let overlay_was_open = ui.overlay_open();
        if matches!(
            ui.handle(message, &runtime, &service, &sender),
            Outcome::Quit
        ) {
            return Ok(());
        }
        if overlay_was_open && !ui.overlay_open() {
            // `Terminal::clear` would first ask the terminal for its cursor, which a full-screen TUI
            // does not need: the clear is queued here and reaches the terminal with the next frame,
            // in one flush and so without a blank frame; resetting the buffers makes that draw
            // write every cell.
            queue!(terminal.backend_mut(), Clear(ClearType::All))?;
            terminal.swap_buffers();
        }
        if !ui.animating() {
            // No tile types: the stenographer rests on its first frame.
            ui.stenographer = 0;
        }
        ui.frame(terminal)?;
    }
    Ok(())
}

/// Fetches the settings and the installed frontends and shows them.
fn open_settings(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
) {
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match stt_client::settings(&service).await {
            Ok(settings) => match stt_client::frontends(&service).await {
                Ok(frontends) => Message::Settings(settings, frontends),
                Err(error) => Message::SettingsFailed(error.to_string()),
            },
            Err(error) => Message::SettingsFailed(error.to_string()),
        };
        let _ = sender.send(message);
    });
}

/// Reads task `id`'s turns again; an open transcript of it shows them.
fn reread_turns(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
) {
    let service = service.clone();
    fetch(
        runtime,
        sender,
        "tui.transcript.loadFailed",
        async move { turns(&service, id).await },
        move |turns| Message::Turns(id, turns),
    );
}

/// Fetches `task`'s turns for the open transcript screen: they replace its loading words, or its
/// retry line says why they could not be read.
fn open_transcript(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    task: Task,
) {
    let id = task.id;
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match turns(&service, task.id).await {
            Ok(turns) => Message::Transcript(task, turns),
            Err(error) => Message::TranscriptFailed(id, error.to_string()),
        };
        let _ = sender.send(message);
    });
}

/// Reads task `id`'s clip from the service; on arrival the open transcript plays it from `from`,
/// alone up to `until` when given.
fn read_clip(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    from: Duration,
    until: Option<Duration>,
) {
    let service = service.clone();
    fetch(
        runtime,
        sender,
        "tui.audio.failed",
        async move { audio(&service, id).await },
        move |bytes| Message::Audio(id, bytes, from, until),
    );
}

/// The time the screen reads, in epoch milliseconds: the system clock - or, under
/// `STT_TUI_CLOCK`, the time the file it names holds, so a test moves the clock itself.
fn now_ms() -> i64 {
    if let Some(path) = std::env::var_os("STT_TUI_CLOCK")
        && let Some(ms) = std::fs::read_to_string(path)
            .ok()
            .and_then(|text| text.trim().parse().ok())
    {
        return ms;
    }
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |since| {
            i64::try_from(since.as_millis()).unwrap_or(i64::MAX)
        })
}

/// Runs `request` off the terminal thread and hands its value to `arrived`; a failure is the
/// notice `failed` names, with the service's message. The terminal keeps drawing meanwhile.
fn fetch<T: Send + 'static>(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    failed: &'static str,
    request: impl std::future::Future<Output = io::Result<T>> + Send + 'static,
    arrived: impl FnOnce(T) -> Message + Send + 'static,
) {
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match request.await {
            Ok(value) => arrived(value),
            Err(error) => Message::Notice(text(failed, &[("error", &error.to_string())])),
        };
        let _ = sender.send(message);
    });
}

/// The task on the queue's row `selected`, if any.
fn task_at(snapshot: Option<&Snapshot>, selected: usize) -> Option<&Task> {
    state::rows(snapshot).get(selected).copied()
}

/// Moves the selected task `step` places through the queue, if it is in the queue and has a
/// neighbour there; the service's `queue-updated` then shows the new order.
fn move_selected(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    service: &Service,
    ui: &Ui,
    step: isize,
) {
    let Some(task) = task_at(ui.snapshot.as_ref(), ui.selected) else {
        return;
    };
    let Some(snapshot) = ui.snapshot.as_ref() else {
        return;
    };
    let Some(from) = snapshot.queue.task_ids.iter().position(|id| *id == task.id) else {
        return;
    };
    let Some(to) = from.checked_add_signed(step) else {
        return;
    };
    if to >= snapshot.queue.task_ids.len() {
        return;
    }
    let mut ids = snapshot.queue.task_ids.clone();
    ids.swap(from, to);
    reorder(runtime, sender, service, ids);
}

/// Moves the selected task to `to`, the queue's index: the top (0) or the bottom. Nothing is sent
/// when it is already there.
fn move_selected_to(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    service: &Service,
    ui: &Ui,
    to: usize,
) {
    let Some(task) = task_at(ui.snapshot.as_ref(), ui.selected) else {
        return;
    };
    let Some(snapshot) = ui.snapshot.as_ref() else {
        return;
    };
    let Some(from) = snapshot.queue.task_ids.iter().position(|id| *id == task.id) else {
        return;
    };
    let last = snapshot.queue.task_ids.len().saturating_sub(1);
    let to = to.min(last);
    if from == to {
        return;
    }
    let mut ids = snapshot.queue.task_ids.clone();
    ids.remove(from);
    ids.insert(to, task.id);
    reorder(runtime, sender, service, ids);
}

/// Sends the queue's new `ids`; the notice reports it, or the service's refusal.
fn reorder(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    service: &Service,
    ids: Vec<i64>,
) {
    let service = service.clone();
    dispatch(runtime, sender, "tui.command.failed", async move {
        reorder_queue(&service, &ids)
            .await
            .map(|_| text("tui.queue.reordered", &[]))
    });
}

/// Sends `command` for task `id`; the notice reports the task's new status, or the failure.
fn send_command(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    command: TaskCommand,
) {
    let service = service.clone();
    dispatch(runtime, sender, "tui.command.failed", async move {
        task_command(&service, id, command).await.map(|task| {
            text(
                "tui.task.commandDone",
                &[
                    ("filename", &task.filename),
                    ("status", &crate::state::status_word(&task.status)),
                ],
            )
        })
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
