#![allow(dead_code)]

//! The TUI as a user runs it: the real `stt-tui` binary in a pseudo-terminal (a Unix pty or a
//! Windows ConPTY), its output read into a `vt100` screen the tests look at, and keys typed into it.

use std::io::{Read, Write};
use std::num::NonZero;
use std::path::Path;
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use portable_pty::{Child, CommandBuilder, ExitStatus, MasterPty, PtySize, native_pty_system};

/// A running `stt-tui` and the screen it has drawn so far.
pub struct Tui {
    child: Box<dyn Child + Send + Sync>,
    master: Box<dyn MasterPty + Send>,
    /// What the keys are typed into - shared with the output reader, which answers the TUI's
    /// terminal queries through it as a real terminal would.
    keyboard: Arc<Mutex<Box<dyn Write + Send>>>,
    screen: Arc<Mutex<vt100::Parser>>,
    /// Everything the TUI has written to the terminal, escapes included - a color choice is only
    /// observable there, since the screen parser consumes them.
    raw: Arc<Mutex<Vec<u8>>>,
    /// How far the output reader's painting has got, waited on: a test waits for a redraw, never
    /// for a timer.
    drawn: Arc<(Mutex<Draw>, Condvar)>,
}

/// The painting's progress: the frames it has completed, and whether it is inside a frame - a
/// synchronized update the TUI opened and has not ended yet, whose screen is half a frame.
struct Draw {
    frames: u64,
    inside: bool,
}

impl Tui {
    /// Starts the TUI at `program` in a `cols` x `rows` terminal with the service directories in
    /// `state` (both `config.json` and `runtime.json`).
    pub fn start(program: &Path, state: &Path, cols: u16, rows: u16) -> Tui {
        Tui::start_with_env(program, state, cols, rows, &[])
    }

    /// Starts the TUI as [`Tui::start`] does, with `env` set on top of the inherited environment.
    pub fn start_with_env(
        program: &Path,
        state: &Path,
        cols: u16,
        rows: u16,
        env: &[(&str, &str)],
    ) -> Tui {
        Tui::start_full(program, state, cols, rows, env, None, None)
    }

    /// Starts the TUI as [`Tui::start_with_env`] does, in a terminal that reports `colours` when
    /// asked (OSC 10 and OSC 11). Without them it answers only the device-attributes query, as a
    /// terminal that cannot report its colours does.
    pub fn start_with_colours(
        program: &Path,
        state: &Path,
        cols: u16,
        rows: u16,
        env: &[(&str, &str)],
        colours: Option<Reported>,
    ) -> Tui {
        Tui::start_full(program, state, cols, rows, env, None, colours)
    }

    /// Starts the TUI as [`Tui::start`] does, in `cwd` - the directory the upload dialog opens in.
    pub fn start_in(program: &Path, state: &Path, cols: u16, rows: u16, cwd: &Path) -> Tui {
        Tui::start_full(program, state, cols, rows, &[], Some(cwd), None)
    }

    /// Starts the TUI in `cwd` with `env` set on top of the inherited environment - a dialog test
    /// that needs both its starting folder and its own `HOME`.
    pub fn start_in_with_env(
        program: &Path,
        state: &Path,
        cols: u16,
        rows: u16,
        cwd: &Path,
        env: &[(&str, &str)],
    ) -> Tui {
        Tui::start_full(program, state, cols, rows, env, Some(cwd), None)
    }

    fn start_full(
        program: &Path,
        state: &Path,
        cols: u16,
        rows: u16,
        env: &[(&str, &str)],
        cwd: Option<&Path>,
        colours: Option<Reported>,
    ) -> Tui {
        let pty = native_pty_system()
            .openpty(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .unwrap();
        let mut command = CommandBuilder::new(program);
        command.env("STT_CONFIG_DIR", state);
        command.env("STT_RUNTIME_DIR", state);
        // The test decides the environment's proxy and colour choices: a CI or developer shell
        // that exports them must not decide for it. The terminal reports true colour unless a test
        // says otherwise.
        command.env_remove("NO_COLOR");
        command.env("COLORTERM", "truecolor");
        // The app's language (item 88) comes from the system's: a developer's or CI's own locale
        // must not decide what the test reads, so it starts in English and a test that wants
        // another language sets `LC_ALL` itself.
        for key in ["LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"] {
            command.env_remove(key);
        }
        command.env("LC_ALL", "C.UTF-8");
        for key in [
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
            "http_proxy",
            "https_proxy",
            "all_proxy",
            "no_proxy",
        ] {
            command.env_remove(key);
        }
        for (key, value) in env {
            command.env(key, value);
        }
        if let Some(cwd) = cwd {
            command.cwd(cwd);
        }
        let child = pty.slave.spawn_command(command).unwrap();
        drop(pty.slave);
        let screen = Arc::new(Mutex::new(vt100::Parser::new(
            NonZero::new(rows).expect("a terminal has rows"),
            NonZero::new(cols).expect("a terminal has columns"),
            0,
        )));
        let raw = Arc::new(Mutex::new(Vec::new()));
        let mut output = pty.master.try_clone_reader().unwrap();
        let keyboard: Arc<Mutex<Box<dyn Write + Send>>> =
            Arc::new(Mutex::new(pty.master.take_writer().unwrap()));
        let drawn = Arc::clone(&screen);
        let written = Arc::clone(&raw);
        let answers = Arc::clone(&keyboard);
        let redrawn = Arc::new((
            Mutex::new(Draw {
                frames: 0,
                inside: false,
            }),
            Condvar::new(),
        ));
        let notify = Arc::clone(&redrawn);
        thread::spawn(move || {
            let mut bytes = [0u8; 8192];
            // Where the scan for terminal queries goes on from: past the last answered one, and
            // never further back than a query's length, so one split across reads is still found.
            let mut scanned = 0usize;
            // The bytes before this chunk that a frame's markers may begin in, so one split
            // across reads is still read.
            let mut tail: Vec<u8> = Vec::new();
            // Whether a frame is open: the markers are read in order, so this is true between a
            // frame's start and its end.
            let mut inside = false;
            while let Ok(read) = output.read(&mut bytes) {
                if read == 0 {
                    break;
                }
                let mut all = written.lock().unwrap();
                all.extend_from_slice(&bytes[..read]);
                drawn.lock().unwrap().process(&bytes[..read]);
                scanned = answer_queries(&all, scanned, colours, &answers);
                let mut frame_bytes = tail.clone();
                frame_bytes.extend_from_slice(&bytes[..read]);
                let mut ends = 0u64;
                for window in frame_bytes.windows(FRAME_MARKER_LEN) {
                    if window == FRAME_START {
                        inside = true;
                    } else if window == FRAME_END {
                        inside = false;
                        ends += 1;
                    }
                }
                let keep = frame_bytes.len().saturating_sub(FRAME_MARKER_LEN - 1);
                tail = frame_bytes[keep..].to_vec();
                drop(all);
                {
                    let mut draw = notify.0.lock().unwrap();
                    draw.frames += ends;
                    draw.inside = inside;
                }
                if ends > 0 {
                    notify.1.notify_all();
                }
            }
        });
        Tui {
            child,
            master: pty.master,
            keyboard,
            screen,
            raw,
            drawn: redrawn,
        }
    }

    /// Resizes the terminal to `cols` x `rows` and the screen parser with it, as a window manager
    /// would hand a resize to the TUI.
    pub fn resize(&mut self, cols: u16, rows: u16) {
        self.master
            .resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })
            .unwrap();
        self.screen.lock().unwrap().screen_mut().set_size(
            NonZero::new(rows).expect("a terminal has rows"),
            NonZero::new(cols).expect("a terminal has columns"),
        );
    }

    /// Everything the TUI has written to the terminal so far, escapes included.
    pub fn raw(&self) -> String {
        String::from_utf8_lossy(&self.raw.lock().unwrap()).into_owned()
    }

    /// The screen's text at the whole frame `shown` holds for, or a panic with the screen after
    /// `timeout`.
    pub fn wait_for(&self, timeout: Duration, shown: impl Fn(&str) -> bool) -> String {
        let mut matched = None;
        self.wait_for_screen(timeout, |screen| {
            let text = screen.contents();
            let holds = shown(&text);
            if holds {
                matched = Some(text);
            }
            holds
        });
        matched.expect("a wait that returned matched a screen")
    }

    /// Waits until `holds` is true of a whole frame, or panics with the screen after `timeout`.
    /// It wakes on the reader's redraw signal rather than polling a timer; `timeout` only bounds a
    /// TUI that fails to draw what the test expects. A frame is read only once the TUI has ended
    /// its synchronized update: mid-frame the screen is half painted, and a predicate that held
    /// there would hand its caller a partial screen.
    pub fn wait_for_screen(
        &self,
        timeout: Duration,
        mut holds: impl FnMut(&vt100::Screen) -> bool,
    ) {
        let deadline = Instant::now() + timeout;
        let (draw, redrawn) = &*self.drawn;
        loop {
            // The count before the check: a redraw that lands between the check and the wait is
            // seen as a change and never slept past.
            let seen = draw.lock().unwrap().frames;
            let holds_now = {
                let screen = self.screen.lock().unwrap();
                let painting = draw.lock().unwrap().inside;
                !painting && holds(screen.screen())
            };
            if holds_now {
                return;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                panic!(
                    "the TUI did not show what was expected within {timeout:?}; screen:\n{}",
                    self.text()
                );
            }
            let waiting = draw.lock().unwrap();
            if waiting.frames == seen {
                let _ = redrawn.wait_timeout(waiting, remaining).unwrap();
            }
        }
    }

    /// The marked cells' text as drawn now (the current segment, the selection), as
    /// [`Tui::wait_for_highlight`] reads it.
    pub fn marked_text(&self) -> String {
        marked(self.screen.lock().unwrap().screen())
    }

    /// Waits until the screen's reversed cells - the current segment, the selection - read
    /// `expected`, or panics with the screen after `timeout`.
    pub fn wait_for_highlight(&self, timeout: Duration, expected: &str) {
        self.wait_for_screen(timeout, |screen| marked(screen).contains(expected));
    }

    /// Waits until the first cell holding `needle` satisfies `matches`, or panics with the screen
    /// after `timeout` - the colour a role is drawn in, since text alone says nothing about it.
    pub fn wait_for_cell(
        &self,
        timeout: Duration,
        needle: &str,
        matches: impl Fn(&vt100::Cell) -> bool,
    ) {
        self.wait_for_screen(timeout, |screen| {
            let (rows, columns) = screen.size();
            for row in 0..rows.get() {
                let line: String = (0..columns.get())
                    .filter_map(|column| screen.cell(row, column))
                    .map(|cell| cell.contents())
                    .collect();
                if let Some(byte) = line.find(needle) {
                    let column = line[..byte].chars().count() as u16;
                    return screen.cell(row, column).map(&matches).unwrap_or(false);
                }
            }
            false
        });
    }

    /// The screen's cells and their colours at the whole frame `shown` holds of its text.
    pub fn wait_for_scene(&self, timeout: Duration, shown: impl Fn(&str) -> bool) -> Scene {
        let mut matched = None;
        self.wait_for_screen(timeout, |screen| {
            let holds = shown(&screen.contents());
            if holds {
                matched = Some(Scene::of(screen));
            }
            holds
        });
        matched.expect("a wait that returned matched a screen")
    }

    /// The screen's text, one line per row.
    pub fn text(&self) -> String {
        self.screen.lock().unwrap().screen().contents()
    }

    /// Types `keys` - the bytes a terminal sends for them - into the TUI.
    pub fn press(&mut self, keys: &[u8]) {
        let mut keyboard = self.keyboard.lock().unwrap();
        keyboard.write_all(keys).unwrap();
        keyboard.flush().unwrap();
    }

    /// A left click at `column`, `row` (zero-based), as a terminal in SGR mouse mode reports it.
    pub fn click(&mut self, column: u16, row: u16) {
        self.press(format!("\x1b[<0;{};{}M", column + 1, row + 1).as_bytes());
    }

    /// The TUI's exit status once it has exited, or `None` if it still runs after `timeout`.
    pub fn exit_within(&mut self, timeout: Duration) -> Option<ExitStatus> {
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(status) = self.child.try_wait().unwrap() {
                return Some(status);
            }
            if Instant::now() >= deadline {
                return None;
            }
            thread::sleep(Duration::from_millis(50));
        }
    }

    /// Whether the terminal is in its cooked mode: lines edited by the terminal and typed keys
    /// echoed, as a shell leaves it - the opposite of the raw mode a TUI draws in.
    #[cfg(unix)]
    pub fn cooked(&self) -> bool {
        let termios = self.master.get_termios().expect("a Unix pty has termios");
        let local = termios.local_flags.bits();
        local & libc::ICANON != 0 && local & libc::ECHO != 0
    }
}

/// The tab bar's screen row: the header's second, beside the stenographer's tile.
const TAB_ROW: u16 = 1;

/// Whether the tab bar's `title` has its first letter marked as a live shortcut - underlined in
/// `accent`, as the footer marks a key - and the letter after it neither.
pub fn tab_shortcut_marked(screen: &vt100::Screen, title: &str, accent: vt100::Color) -> bool {
    let bar = screen.contents_between(TAB_ROW, 0, TAB_ROW, screen.size().1.get());
    let Some(at) = bar.find(title) else {
        return false;
    };
    let col = bar[..at].chars().count() as u16;
    let first = screen.cell(TAB_ROW, col).unwrap();
    let second = screen.cell(TAB_ROW, col + 1).unwrap();
    first.underline() && first.fgcolor() == accent && !second.underline()
}

/// Whether none of the three tabs marks a live shortcut: the bar with no mark at all.
pub fn no_tab_marked(screen: &vt100::Screen, accent: vt100::Color) -> bool {
    ["Queue", "History", "Settings"]
        .iter()
        .all(|title| !tab_shortcut_marked(screen, title, accent))
}

/// The first cell on `row` holding `needle`: its foreground and background.
pub fn style(scene: &Scene, row: usize, needle: &str) -> (vt100::Color, vt100::Color) {
    let text = scene.row_text(row);
    let byte = text
        .find(needle)
        .unwrap_or_else(|| panic!("{needle:?} is on row {row}:\n{text}"));
    let cell = scene.cell(row, text[..byte].chars().count()).unwrap();
    (cell.fg, cell.bg)
}

/// The footer's hints on `screen`: the three-space-separated parts of the rows under the screen's last
/// bottom border or blank row - a page without a panel (the queue) leaves blank rows above the
/// footer; none before a page is drawn.
pub fn footer_hints(screen: &str) -> Vec<String> {
    let lines: Vec<&str> = screen.lines().collect();
    let Some(border) = lines
        .iter()
        .rposition(|line| line.starts_with('└') || line.trim().is_empty())
    else {
        return Vec::new();
    };
    lines[border + 1..]
        .iter()
        .flat_map(|line| line.split("   "))
        .map(|part| part.trim().to_string())
        .collect()
}

/// Whether the transcript shows a turn as the design draws it: `clock  name` on one row, then
/// `body` starting under the name (seven columns in) on the next.
pub fn turn(text: &str, clock: &str, name: &str, body: &str) -> bool {
    let header = format!("{clock}  {name}");
    let Some(index) = text.lines().position(|line| line.contains(&header)) else {
        return false;
    };
    let under = format!("       {body}");
    text.lines().nth(index + 1).is_some_and(|line| {
        line.strip_prefix('│')
            .is_some_and(|row| row.starts_with(&under))
    })
}

/// Whether the footer on `screen` offers every hint of `offered` and none of `withheld`.
pub fn offers(screen: &str, offered: &[&str], withheld: &[&str]) -> bool {
    let hints = footer_hints(screen);
    offered
        .iter()
        .all(|hint| hints.iter().any(|shown| shown == hint))
        && withheld
            .iter()
            .all(|hint| hints.iter().all(|shown| shown != hint))
}

/// The cells of a table row's segment between two `│` borders: its three-space-separated parts,
/// trimmed - a key reference row is its key and its description.
pub fn table_cells(inner: &str) -> Vec<&str> {
    inner
        .split("   ")
        .map(str::trim)
        .filter(|cell| !cell.is_empty())
        .collect()
}

/// Whether `screen` shows a table row of `key` and `words` between a pair of `│` borders: the key
/// reference's, wherever the page's own panels are around it.
pub fn table_row(screen: &str, key: &str, words: &str) -> bool {
    screen.lines().any(|line| {
        line.split('│')
            .any(|inner| table_cells(inner) == [key, words])
    })
}

/// The screen column and row (zero-based) where `needle` is first drawn - where a click on it goes.
pub fn position_of(screen: &str, needle: &str) -> (u16, u16) {
    screen
        .lines()
        .enumerate()
        .find_map(|(row, line)| {
            line.find(needle)
                .map(|byte| (line[..byte].chars().count() as u16, row as u16))
        })
        .unwrap_or_else(|| panic!("{needle:?} on the screen:\n{screen}"))
}

/// The multipart field `name`'s value in a request body, as the client wrote it; the tests that
/// check an upload's options share it.
pub fn multipart_field<'a>(body: &'a str, name: &str) -> Option<&'a str> {
    let header = body.find(&format!("name=\"{name}\""))?;
    let start = header + body[header..].find("\r\n\r\n")? + 4;
    let end = start + body[start..].find("\r\n")?;
    Some(&body[start..end])
}

/// A TUI with a completed task's transcript open, where the transcript tests start: a stand-in
/// service, the TUI attached to it with the queue from `snapshot.json`, then Enter on
/// `interview.mp3` (task 3) answered with `turns.json`. A test binds every field: the event stream
/// and the bootstrap directory must last as long as it does.
pub struct OpenTranscript {
    pub service: EventService,
    pub tui: Tui,
    pub stream: EventStream,
    pub state: tempfile::TempDir,
}

/// A terminal wide enough for any page's whole hint list on the footer's one row (the transcript's
/// is the longest, 321 columns): the tests that read which hints a footer offers start at it.
pub const WIDE: u16 = 400;

/// Opens `interview.mp3`'s transcript as a user would, at 120x24; see [`OpenTranscript`].
pub fn open_transcript() -> OpenTranscript {
    open_transcript_sized(include_str!("../../../../docs/api/turns.json"), 120)
}

/// Opens `interview.mp3`'s transcript as [`open_transcript`] does, its turns read as `turns`, in a
/// [`WIDE`] terminal: the footer then shows every hint that can act.
pub fn open_transcript_with(turns: &str) -> OpenTranscript {
    open_transcript_sized(turns, WIDE)
}

/// Opens `interview.mp3`'s transcript, its turns read as `turns`, in a terminal `cols` wide.
fn open_transcript_sized(turns: &str, cols: u16) -> OpenTranscript {
    const SNAPSHOT: &str = include_str!("../../../../docs/api/snapshot.json");
    const WAIT: Duration = Duration::from_secs(20);
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    crate::support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        cols,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("History")
    });
    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", turns);
    tui.wait_for(WAIT, |text| text.contains("Speakers"));
    OpenTranscript {
        service,
        tui,
        stream,
        state,
    }
}

/// Opens `interview.mp3`'s transcript as [`open_transcript`] does, in `cwd` - the folder the
/// download dialog opens in.
pub fn open_transcript_in(cwd: &Path) -> OpenTranscript {
    const SNAPSHOT: &str = include_str!("../../../../docs/api/snapshot.json");
    const TURNS: &str = include_str!("../../../../docs/api/turns.json");
    const WAIT: Duration = Duration::from_secs(20);
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    crate::support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        cwd,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("History")
    });
    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Speakers"));
    OpenTranscript {
        service,
        tui,
        stream,
        state,
    }
}

/// Opens `interview.mp3`'s transcript in colour at 120x30, its audio to be played on the silent
/// `sink` (`frozen`: only a seek moves the playhead; `null`: real time); nothing plays yet. See
/// [`OpenTranscript`] for the fields.
pub fn open_transcript_on(sink: &str) -> OpenTranscript {
    open_transcript_on_sized(sink, 120)
}

/// [`open_transcript_on`] in a terminal `cols` wide.
fn open_transcript_on_sized(sink: &str, cols: u16) -> OpenTranscript {
    const SNAPSHOT: &str = include_str!("../../../../docs/api/snapshot.json");
    const TURNS: &str = include_str!("../../../../docs/api/turns.json");
    const WAIT: Duration = Duration::from_secs(20);
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    crate::support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start_with_colours(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        cols,
        30,
        &[("STT_AUDIO_SINK", sink)],
        None,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));
    OpenTranscript {
        service,
        tui,
        stream,
        state,
    }
}

/// Opens the transcript as [`open_transcript_on`] does, in a [`WIDE`] terminal, then plays it: Space
/// read `/audio`, `clip` answered it, and the player bar stands at the start. The playback tests
/// start here.
pub fn open_transcript_playing(sink: &str, clip: &[u8]) -> OpenTranscript {
    const WAIT: Duration = Duration::from_secs(20);
    let mut open = open_transcript_on_sized(sink, WIDE);
    open.tui.press(b" ");
    open.service
        .next_get_from(&open.tui, WAIT)
        .answer_bytes("200 OK", clip);
    open.tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 19:45") && text.contains('⏸')
    });
    open
}

impl Drop for Tui {
    fn drop(&mut self) {
        let _ = self.child.kill();
    }
}

/// A stand-in service whose event streams the test writes itself: it answers the health check, and
/// hands every `GET /api/events` connection, its response head already sent, to the test.
pub struct EventService {
    pub port: u16,
    /// Whether the next `GET /api/settings` is handed to the test instead of answered with the
    /// contract's defaults, so a TUI's startup read cannot sit in front of the test's own reads.
    settings_captured: Arc<std::sync::atomic::AtomicBool>,
    /// How many settings reads the stand-in answered itself: the startup read is the first.
    settings_answered: Arc<std::sync::atomic::AtomicUsize>,
    streams: std::sync::mpsc::Receiver<EventStream>,
    uploads: std::sync::mpsc::Receiver<Upload>,
    commands: std::sync::mpsc::Receiver<Command>,
    gets: std::sync::mpsc::Receiver<Fetch>,
}

/// One `GET` a client made beyond the health check and the event stream (the task list, a
/// transcript's turns, the settings), its request read whole and its response still to be sent.
pub struct Fetch {
    request: crate::support::Request,
    stream: std::net::TcpStream,
}

impl Fetch {
    pub fn request(&self) -> &crate::support::Request {
        &self.request
    }

    pub fn answer(mut self, status: &str, body: &str) {
        crate::support::respond(&mut self.stream, status, body);
    }

    /// Answers with a binary `body` (a clip's `/audio`).
    pub fn answer_bytes(mut self, status: &str, body: &[u8]) {
        crate::support::respond_bytes(&mut self.stream, status, body);
    }
}

/// One task or queue command a client made (`POST /api/tasks/{id}/…`, `PUT /api/tasks/{id}/…`,
/// `POST /api/queue/…`, `PUT /api/queue/order`), its request read whole and its response still to
/// be sent.
pub struct Command {
    request: crate::support::Request,
    stream: std::net::TcpStream,
}

impl Command {
    pub fn request(&self) -> &crate::support::Request {
        &self.request
    }

    pub fn answer(mut self, status: &str, body: &str) {
        crate::support::respond(&mut self.stream, status, body);
    }
}

/// One `POST /api/tasks` a client made, its request read whole and its response still to be sent.
pub struct Upload {
    request: crate::support::Request,
    stream: std::net::TcpStream,
}

impl Upload {
    /// The request as the client sent it: line, headers and body.
    pub fn request(&self) -> &crate::support::Request {
        &self.request
    }

    /// Answers `status` (for example `201 Created`) with `body`.
    pub fn answer(mut self, status: &str, body: &str) {
        crate::support::respond(&mut self.stream, status, body);
    }

    /// Closes the connection without an answer, as a service that went away mid-request would:
    /// the request never lands.
    pub fn cut_off(self) {
        drop(self.stream);
    }
}

/// One open event stream of an [`EventService`]; dropping it closes the connection, as a service
/// that went away would.
pub struct EventStream {
    stream: std::net::TcpStream,
    /// The `Authorization` header the stream was asked for with.
    pub authorization: Option<String>,
}

impl EventService {
    /// The stand-in, which answers `GET /api/settings` with the contract's defaults unless the test
    /// asks for the next one: a TUI's startup read then cannot sit in front of the test's own reads.
    /// Each connection is read on its own thread, so one slow request never holds up the others.
    pub fn start() -> EventService {
        let settings_captured = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let settings_answered = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let captured = Arc::clone(&settings_captured);
        let answered = Arc::clone(&settings_answered);
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (handed, streams) = std::sync::mpsc::channel();
        let (uploaded, uploads) = std::sync::mpsc::channel();
        let (commanded, commands) = std::sync::mpsc::channel();
        let (fetched, gets) = std::sync::mpsc::channel();
        thread::spawn(move || {
            for stream in listener.incoming() {
                let mut stream = stream.unwrap();
                let captured = Arc::clone(&captured);
                let answered = Arc::clone(&answered);
                let handed = handed.clone();
                let uploaded = uploaded.clone();
                let commanded = commanded.clone();
                let fetched = fetched.clone();
                thread::spawn(move || {
                    let request = crate::support::read(&mut stream);
                    if request.line.starts_with("GET /api/events ") {
                        write!(
                            stream,
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n"
                        )
                        .unwrap();
                        stream.flush().unwrap();
                        let authorization = request.header("Authorization").map(str::to_owned);
                        let _ = handed.send(EventStream {
                            stream,
                            authorization,
                        });
                    } else if request.line.starts_with("POST /api/tasks ") {
                        let _ = uploaded.send(Upload { request, stream });
                    } else if request.line.starts_with("POST /api/tasks/")
                        || request.line.starts_with("POST /api/queue/")
                        || request.line.starts_with("PUT /api/queue/order ")
                        || request.line.starts_with("PUT /api/tasks/")
                        || request.line.starts_with("PUT /api/settings ")
                        || request.line.starts_with("DELETE /api/settings ")
                        || request.line.starts_with("DELETE /api/tasks/")
                        || request
                            .line
                            .starts_with("DELETE /api/settings/device-fallback")
                    {
                        let _ = commanded.send(Command { request, stream });
                    } else if request.line.starts_with("GET /api/settings ")
                        && !captured.swap(false, std::sync::atomic::Ordering::SeqCst)
                    {
                        crate::support::respond(
                            &mut stream,
                            "200 OK",
                            include_str!("../../../../docs/api/settings.json"),
                        );
                        answered.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                    } else if request.line.starts_with("GET /api/tasks")
                        || request.line.starts_with("GET /api/settings ")
                        || request.line.starts_with("GET /api/frontends ")
                    {
                        let _ = fetched.send(Fetch { request, stream });
                    } else {
                        let (status, body) = crate::support::healthy();
                        crate::support::respond(&mut stream, status, &body);
                    }
                });
            }
        });
        EventService {
            port,
            settings_captured,
            settings_answered,
            streams,
            uploads,
            commands,
            gets,
        }
    }

    /// Hands the next `GET /api/settings` after the TUI's startup read to the test: it first waits
    /// until the stand-in has answered that startup read, so the capture cannot take it instead.
    pub fn capture_next_settings(&self) {
        let deadline = Instant::now() + Duration::from_secs(20);
        while self
            .settings_answered
            .load(std::sync::atomic::Ordering::SeqCst)
            == 0
        {
            assert!(
                Instant::now() < deadline,
                "the TUI's startup settings read never arrived"
            );
            thread::sleep(Duration::from_millis(20));
        }
        self.settings_captured
            .store(true, std::sync::atomic::Ordering::SeqCst);
    }

    /// Hands the TUI's startup `GET /api/settings` to the test: called before the TUI starts.
    pub fn capture_startup_settings(&self) {
        self.settings_captured
            .store(true, std::sync::atomic::Ordering::SeqCst);
    }

    /// The next read a client made, or a panic after `timeout`.
    pub fn next_get(&self, timeout: Duration) -> Fetch {
        self.gets
            .recv_timeout(timeout)
            .unwrap_or_else(|_| panic!("no GET arrived within {timeout:?}"))
    }

    /// The next read `tui` made, or a panic after `timeout` that shows what `tui` had on screen -
    /// the evidence of whether the key that should have caused the read ever acted.
    pub fn next_get_from(&self, tui: &Tui, timeout: Duration) -> Fetch {
        self.gets.recv_timeout(timeout).unwrap_or_else(|_| {
            panic!("no GET arrived within {timeout:?}; screen:\n{}", tui.text())
        })
    }

    /// Whether a read arrived that the test has not read.
    pub fn get_arrived(&self) -> bool {
        self.gets.try_recv().is_ok()
    }

    /// The next command a client made, or a panic after `timeout`.
    pub fn next_command(&self, timeout: Duration) -> Command {
        self.commands
            .recv_timeout(timeout)
            .unwrap_or_else(|_| panic!("no command arrived within {timeout:?}"))
    }

    /// Whether a command arrived that the test has not read.
    pub fn command_arrived(&self) -> bool {
        self.commands.try_recv().is_ok()
    }

    /// The next upload a client made, or a panic after `timeout`.
    pub fn next_upload(&self, timeout: Duration) -> Upload {
        self.uploads
            .recv_timeout(timeout)
            .unwrap_or_else(|_| panic!("no upload arrived within {timeout:?}"))
    }

    /// Whether an upload arrived that the test has not read.
    pub fn upload_arrived(&self) -> bool {
        !self.uploads.try_recv().is_err()
    }

    /// The next event stream a client opens, or a panic after `timeout`.
    pub fn next_stream(&self, timeout: Duration) -> EventStream {
        self.streams
            .recv_timeout(timeout)
            .unwrap_or_else(|_| panic!("no event stream was opened within {timeout:?}"))
    }
}

impl EventStream {
    /// Sends the event `name` with `json` as its data, one `data:` line per JSON line.
    pub fn send(&mut self, name: &str, json: &str) {
        let data: String = json.lines().map(|line| format!("data: {line}\n")).collect();
        write!(self.stream, "event: {name}\n{data}\n").unwrap();
        self.stream.flush().unwrap();
    }
}

impl Drop for EventStream {
    fn drop(&mut self) {
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
    }
}

/// One cell of the drawn screen, with the colours the TUI (or the terminal's default) put on it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cell {
    pub text: String,
    pub fg: vt100::Color,
    pub bg: vt100::Color,
}

/// The whole drawn screen as cells: the colour tests read what the TUI painted where.
#[derive(Debug, Clone, Default)]
pub struct Scene {
    pub cells: Vec<Vec<Cell>>,
}

impl Scene {
    fn of(screen: &vt100::Screen) -> Scene {
        let (rows, columns) = screen.size();
        Scene {
            cells: (0..rows.get())
                .map(|row| {
                    (0..columns.get())
                        .filter_map(|column| screen.cell(row, column))
                        .map(|cell| Cell {
                            text: cell.contents().to_string(),
                            fg: cell.fgcolor(),
                            bg: cell.bgcolor(),
                        })
                        .collect()
                })
                .collect(),
        }
    }

    /// The first cell whose row holds `needle`: its row and the column the needle starts at.
    pub fn position(&self, needle: &str) -> Option<(usize, usize)> {
        self.cells.iter().enumerate().find_map(|(row, cells)| {
            let line: String = cells.iter().map(|cell| cell.text.as_str()).collect();
            let byte = line.find(needle)?;
            Some((row, line[..byte].chars().count()))
        })
    }

    pub fn cell(&self, row: usize, column: usize) -> Option<&Cell> {
        self.cells.get(row).and_then(|cells| cells.get(column))
    }

    /// The foreground of the first cell holding `needle`.
    pub fn fg(&self, needle: &str) -> vt100::Color {
        let (row, column) = self
            .position(needle)
            .unwrap_or_else(|| panic!("{needle:?} is on screen:\n{}", self.text()));
        self.cell(row, column).expect("a cell with text").fg
    }

    /// The background of the first cell holding `needle`.
    pub fn bg(&self, needle: &str) -> vt100::Color {
        let (row, column) = self
            .position(needle)
            .unwrap_or_else(|| panic!("{needle:?} is on screen:\n{}", self.text()));
        self.cell(row, column).expect("a cell with text").bg
    }

    /// The screen's text, one line per row.
    pub fn text(&self) -> String {
        self.cells
            .iter()
            .map(|cells| {
                cells
                    .iter()
                    .map(|cell| cell.text.as_str())
                    .collect::<String>()
            })
            .collect::<Vec<String>>()
            .join("\n")
    }

    /// The text of screen row `row`.
    pub fn row_text(&self, row: usize) -> String {
        self.cells[row]
            .iter()
            .map(|cell| cell.text.as_str())
            .collect()
    }

    /// The characters of screen row `row` in `columns`.
    pub fn slice(&self, row: usize, columns: std::ops::Range<usize>) -> String {
        self.row_text(row)
            .chars()
            .skip(columns.start)
            .take(columns.len())
            .collect()
    }

    /// The screen row whose text holds `needle`.
    pub fn row_of(&self, needle: &str) -> usize {
        (0..self.cells.len())
            .find(|row| self.row_text(*row).contains(needle))
            .unwrap_or_else(|| panic!("{needle:?} is on screen"))
    }
}

/// The colour the page is painted in: the background most of the screen's cells use.
pub fn page_background(screen: &vt100::Screen) -> vt100::Color {
    let (rows, columns) = screen.size();
    let mut counts: Vec<(vt100::Color, usize)> = Vec::new();
    for row in 0..rows.get() {
        for column in 0..columns.get() {
            let Some(cell) = screen.cell(row, column) else {
                continue;
            };
            let bg = cell.bgcolor();
            match counts.iter_mut().find(|(colour, _)| *colour == bg) {
                Some((_, count)) => *count += 1,
                None => counts.push((bg, 1)),
            }
        }
    }
    counts
        .into_iter()
        .max_by_key(|(_, count)| *count)
        .map(|(colour, _)| colour)
        .unwrap_or(vt100::Color::Default)
}

/// Whether `cell` marks the selection: reversed, or painted off the page's background.
pub fn cell_marked(cell: &vt100::Cell, page: vt100::Color) -> bool {
    cell.inverse() || (cell.bgcolor() != vt100::Color::Default && cell.bgcolor() != page)
}

/// The History row naming `name`, if one is on screen: the line that carries its name and a
/// finished status.
pub fn history_row<'a>(text: &'a str, name: &str) -> Option<&'a str> {
    text.lines()
        .find(|line| line.contains(name) && (line.contains("COMPLETED") || line.contains("FAILED")))
}

/// The marked cells' text - the current segment, the selection: reversed without colours, on the
/// accent tint with them, both read by [`cell_marked`].
fn marked(screen: &vt100::Screen) -> String {
    let page = page_background(screen);
    let (rows, cols) = screen.size();
    let mut text = String::new();
    for row in 0..rows.get() {
        for col in 0..cols.get() {
            let cell = screen.cell(row, col).unwrap();
            if cell_marked(cell, page) {
                text.push_str(cell.contents());
            }
        }
    }
    text
}

/// The escape sequences the TUI brackets every frame with (crossterm's synchronized update): the
/// point at which the screen holds a whole frame, never half of one.
const FRAME_START: &[u8] = b"\x1b[?2026h";
const FRAME_END: &[u8] = b"\x1b[?2026l";
/// Both markers' length, and so how many bytes a chunk's end may carry into the next read.
const FRAME_MARKER_LEN: usize = FRAME_END.len();

/// The colours a terminal reports when asked, as X11 colours such as `rgb:ffff/ffff/ffff`.
#[derive(Debug, Clone, Copy)]
pub struct Reported {
    pub foreground: &'static str,
    pub background: &'static str,
}

/// The terminal's answers to the queries written in `output[from..]`: the device attributes (DA1,
/// `ESC [ c`) every terminal answers, the cursor position (DSR, `ESC [ 6 n`) - which a Windows
/// ConPTY asks before it lets the program start, and waits for (`PSEUDOCONSOLE_INHERIT_CURSOR`,
/// set by `portable-pty`) - and its text and background colours (OSC 10, OSC 11) when it has them
/// to report. Returns where the next scan starts.
fn answer_queries(
    output: &[u8],
    from: usize,
    colours: Option<Reported>,
    keyboard: &Mutex<Box<dyn Write + Send>>,
) -> usize {
    const DA1: &[u8] = b"\x1b[c";
    const DSR: &[u8] = b"\x1b[6n";
    const OSC10: &[u8] = b"\x1b]10;?";
    const OSC11: &[u8] = b"\x1b]11;?";
    let mut at = from;
    loop {
        let rest = &output[at..];
        let found = [DA1, DSR, OSC10, OSC11]
            .into_iter()
            .filter_map(|query| {
                rest.windows(query.len())
                    .position(|window| window == query)
                    .map(|position| (position, query))
            })
            .min_by_key(|(position, _)| *position);
        let Some((position, query)) = found else {
            // Keep the tail a query may continue in.
            return at.max(output.len().saturating_sub(OSC11.len()));
        };
        let answer = if query == DA1 {
            Some("\x1b[?62;22c".to_string())
        } else if query == DSR {
            // The cursor where a fresh terminal has it: the top-left cell.
            Some("\x1b[1;1R".to_string())
        } else if query == OSC10 {
            colours.map(|colours| format!("\x1b]10;{}\x1b\\", colours.foreground))
        } else {
            colours.map(|colours| format!("\x1b]11;{}\x1b\\", colours.background))
        };
        if let Some(answer) = answer {
            let mut keyboard = keyboard.lock().unwrap();
            keyboard.write_all(answer.as_bytes()).unwrap();
            keyboard.flush().unwrap();
        }
        at += position + query.len();
    }
}
