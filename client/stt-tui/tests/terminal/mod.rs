#![allow(dead_code)]

//! The TUI as a user runs it: the real `stt-tui` binary in a pseudo-terminal (a Unix pty or a
//! Windows ConPTY), its output read into a `vt100` screen the tests look at.

use std::io::Read;
use std::num::NonZero;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use portable_pty::{Child, CommandBuilder, PtySize, native_pty_system};

/// A running `stt-tui` and the screen it has drawn so far.
pub struct Tui {
    child: Box<dyn Child + Send + Sync>,
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
            // The master side stays open with the reader; keep it alive for as long as it reads.
            let _master = pty.master;
            let mut bytes = [0u8; 8192];
            while let Ok(read) = output.read(&mut bytes) {
                if read == 0 {
                    break;
                }
                drawn.lock().unwrap().process(&bytes[..read]);
            }
        });
        Tui { child, screen }
    }

    /// The screen's text once `shown` holds for it, or a panic with the screen after `timeout`.
    pub fn wait_for(&self, timeout: Duration, shown: impl Fn(&str) -> bool) -> String {
        let deadline = Instant::now() + timeout;
        loop {
            let text = self.text();
            if shown(&text) {
                return text;
            }
            if Instant::now() >= deadline {
                panic!(
                    "the TUI did not show what was expected within {timeout:?}; screen:\n{text}"
                );
            }
            thread::sleep(Duration::from_millis(50));
        }
    }

    /// The screen's text, one line per row.
    pub fn text(&self) -> String {
        self.screen.lock().unwrap().screen().contents()
    }
}

impl Drop for Tui {
    fn drop(&mut self) {
        let _ = self.child.kill();
    }
}
