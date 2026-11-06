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

impl Drop for Tui {
    fn drop(&mut self) {
        let _ = self.child.kill();
    }
}

/// A stand-in service on a fresh port: it answers the health check, and `GET /api/events` with
/// `first` as the stream's first event (`name`, pretty-printed JSON allowed: it goes out as one
/// `data:` line per line, which SSE joins back) on a stream it then keeps open, as the service
/// does. Returns the port and the `Authorization` header the event stream was asked for with.
pub fn service_with_events(
    first: (&'static str, &'static str),
) -> (u16, Arc<Mutex<Option<String>>>) {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let authorization = Arc::new(Mutex::new(None));
    let seen = Arc::clone(&authorization);
    thread::spawn(move || {
        for stream in listener.incoming() {
            let mut stream = stream.unwrap();
            let request = crate::support::read(&mut stream);
            if request.line.starts_with("GET /api/events ") {
                *seen.lock().unwrap() = request.header("Authorization").map(str::to_owned);
                let (name, json) = first;
                let data: String = json.lines().map(|line| format!("data: {line}\n")).collect();
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\nevent: {name}\n{data}\n"
                )
                .unwrap();
                stream.flush().unwrap();
                thread::spawn(move || {
                    let _open = stream;
                    thread::park();
                });
            } else {
                let (status, body) = crate::support::healthy();
                crate::support::respond(&mut stream, status, &body);
            }
        }
    });
    (port, authorization)
}
