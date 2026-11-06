#![allow(dead_code)]

//! The TUI as a user runs it: the real `stt-tui` binary in a pseudo-terminal (a Unix pty or a
//! Windows ConPTY), its output read into a `vt100` screen the tests look at, and keys typed into it.

use std::io::{Read, Write};
use std::num::NonZero;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use portable_pty::{Child, CommandBuilder, ExitStatus, MasterPty, PtySize, native_pty_system};

/// A running `stt-tui` and the screen it has drawn so far.
pub struct Tui {
    child: Box<dyn Child + Send + Sync>,
    master: Box<dyn MasterPty + Send>,
    keyboard: Box<dyn Write + Send>,
    screen: Arc<Mutex<vt100::Parser>>,
}

impl Tui {
    /// Starts the TUI at `program` in a `cols` x `rows` terminal with the service directories in
    /// `state` (both `config.json` and `runtime.json`).
    pub fn start(program: &Path, state: &Path, cols: u16, rows: u16) -> Tui {
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
        let child = pty.slave.spawn_command(command).unwrap();
        drop(pty.slave);
        let screen = Arc::new(Mutex::new(vt100::Parser::new(
            NonZero::new(rows).expect("a terminal has rows"),
            NonZero::new(cols).expect("a terminal has columns"),
            0,
        )));
        let mut output = pty.master.try_clone_reader().unwrap();
        let drawn = Arc::clone(&screen);
        thread::spawn(move || {
            let mut bytes = [0u8; 8192];
            while let Ok(read) = output.read(&mut bytes) {
                if read == 0 {
                    break;
                }
                drawn.lock().unwrap().process(&bytes[..read]);
            }
        });
        let keyboard = pty.master.take_writer().unwrap();
        Tui {
            child,
            master: pty.master,
            keyboard,
            screen,
        }
    }

    /// The screen's text once `shown` holds for it, or a panic with the screen after `timeout`.
    pub fn wait_for(&self, timeout: Duration, shown: impl Fn(&str) -> bool) -> String {
        self.wait_for_screen(timeout, |screen| shown(&screen.contents()));
        self.text()
    }

    /// Waits until `holds` is true of the screen, or panics with the screen after `timeout`.
    pub fn wait_for_screen(&self, timeout: Duration, holds: impl Fn(&vt100::Screen) -> bool) {
        let deadline = Instant::now() + timeout;
        loop {
            if holds(self.screen.lock().unwrap().screen()) {
                return;
            }
            if Instant::now() >= deadline {
                panic!(
                    "the TUI did not show what was expected within {timeout:?}; screen:\n{}",
                    self.text()
                );
            }
            thread::sleep(Duration::from_millis(50));
        }
    }

    /// The screen's text, one line per row.
    pub fn text(&self) -> String {
        self.screen.lock().unwrap().screen().contents()
    }

    /// Types `keys` - the bytes a terminal sends for them - into the TUI.
    pub fn press(&mut self, keys: &[u8]) {
        self.keyboard.write_all(keys).unwrap();
        self.keyboard.flush().unwrap();
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

/// A TUI with a completed task's transcript open, where the transcript tests start: a stand-in
/// service, the TUI attached to it with the queue from `snapshot.json`, then `h` answered with
/// `task-list.json` and Enter on `interview.mp3` (task 3) answered with `turns.json`. A test binds
/// every field: the event stream and the bootstrap directory must last as long as it does.
pub struct OpenTranscript {
    pub service: EventService,
    pub tui: Tui,
    pub stream: EventStream,
    pub state: tempfile::TempDir,
}

/// Opens `interview.mp3`'s transcript as a user would; see [`OpenTranscript`].
pub fn open_transcript() -> OpenTranscript {
    const SNAPSHOT: &str = include_str!("../../../../docs/api/snapshot.json");
    const TASK_LIST: &str = include_str!("../../../../docs/api/task-list.json");
    const TURNS: &str = include_str!("../../../../docs/api/turns.json");
    const WAIT: Duration = Duration::from_secs(20);
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    crate::support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"h");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("History")
    });
    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| {
        text.contains("Speakers") && text.contains("Happy to be here.")
    });
    OpenTranscript {
        service,
        tui,
        stream,
        state,
    }
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
    streams: std::sync::mpsc::Receiver<EventStream>,
    uploads: std::sync::mpsc::Receiver<Upload>,
    commands: std::sync::mpsc::Receiver<Command>,
    gets: std::sync::mpsc::Receiver<Fetch>,
}

/// One `GET` a client made beyond the health check and the event stream (the task list, a
/// transcript's turns), its request read whole and its response still to be sent.
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
}

/// One open event stream of an [`EventService`]; dropping it closes the connection, as a service
/// that went away would.
pub struct EventStream {
    stream: std::net::TcpStream,
    /// The `Authorization` header the stream was asked for with.
    pub authorization: Option<String>,
}

impl EventService {
    pub fn start() -> EventService {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let (handed, streams) = std::sync::mpsc::channel();
        let (uploaded, uploads) = std::sync::mpsc::channel();
        let (commanded, commands) = std::sync::mpsc::channel();
        let (fetched, gets) = std::sync::mpsc::channel();
        thread::spawn(move || {
            for stream in listener.incoming() {
                let mut stream = stream.unwrap();
                let request = crate::support::read(&mut stream);
                if request.line.starts_with("GET /api/events ") {
                    write!(
                        stream,
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n"
                    )
                    .unwrap();
                    stream.flush().unwrap();
                    let authorization = request.header("Authorization").map(str::to_owned);
                    if handed
                        .send(EventStream {
                            stream,
                            authorization,
                        })
                        .is_err()
                    {
                        return;
                    }
                } else if request.line.starts_with("POST /api/tasks ") {
                    if uploaded.send(Upload { request, stream }).is_err() {
                        return;
                    }
                } else if request.line.starts_with("POST /api/tasks/")
                    || request.line.starts_with("POST /api/queue/")
                    || request.line.starts_with("PUT /api/queue/order ")
                    || request.line.starts_with("PUT /api/tasks/")
                {
                    if commanded.send(Command { request, stream }).is_err() {
                        return;
                    }
                } else if request.line.starts_with("GET /api/tasks") {
                    if fetched.send(Fetch { request, stream }).is_err() {
                        return;
                    }
                } else {
                    let (status, body) = crate::support::healthy();
                    crate::support::respond(&mut stream, status, &body);
                }
            }
        });
        EventService {
            port,
            streams,
            uploads,
            commands,
            gets,
        }
    }

    /// The next read a client made, or a panic after `timeout`.
    pub fn next_get(&self, timeout: Duration) -> Fetch {
        self.gets
            .recv_timeout(timeout)
            .unwrap_or_else(|_| panic!("no GET arrived within {timeout:?}"))
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
