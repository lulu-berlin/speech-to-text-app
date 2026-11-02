#![allow(dead_code)]

//! A stand-in service for the client tests: a local socket that speaks HTTP the way the service does,
//! so each test asserts on bytes instead of a mock. Every request is read whole (its request line and
//! headers) and answered with a status and a body; the connection then closes.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::thread;

use stt_client::ServiceDirs;

/// One request: its `line` (`GET /api/health HTTP/1.1`), its `head` headers with lower-cased names,
/// and its `body` when it carried one.
pub struct Request {
    pub line: String,
    pub head: Vec<String>,
    pub body: String,
}

impl Request {
    /// The header line whose name is `name`, without the name and the colon.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.head
            .iter()
            .find(|line| {
                line.to_ascii_lowercase()
                    .starts_with(&format!("{}:", name.to_ascii_lowercase()))
            })
            .map(|line| line[(name.len() + 1)..].trim())
    }
}

/// Writes `text` as the bootstrap file `name` in `dir`.
pub fn write(dir: &Path, name: &str, text: &str) {
    std::fs::write(dir.join(name), text).unwrap();
}

/// Reads one request from `stream`: the request line, every header up to the empty line, and the body
/// its `Content-Length` announces.
pub fn read(stream: &mut TcpStream) -> Request {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();
    let line = line.trim_end().to_string();
    let mut head = Vec::new();
    loop {
        let mut header = String::new();
        if reader.read_line(&mut header).unwrap() == 0 {
            break;
        }
        if header == "\r\n" || header == "\n" {
            break;
        }
        head.push(header.trim_end().to_string());
    }
    let length = head
        .iter()
        .find_map(|line| {
            line.to_ascii_lowercase()
                .strip_prefix("content-length:")
                .map(|value| value.trim().parse::<usize>().unwrap_or(0))
        })
        .unwrap_or(0);
    let mut body = String::new();
    if length > 0 {
        let mut bytes = vec![0u8; length];
        reader.read_exact(&mut bytes).unwrap();
        body = String::from_utf8_lossy(&bytes).to_string();
    }
    Request { line, head, body }
}

/// Answers with `status` (for example `401 Unauthorized`) and `body`, closing the connection.
pub fn respond(stream: &mut TcpStream, status: &str, body: &str) {
    write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )
    .unwrap();
}

/// Answers every request on `listener` until the test ends, `status` and body chosen by `answer`.
pub fn answer_forever(
    listener: TcpListener,
    answer: impl Fn(&Request) -> (&'static str, String) + Send + 'static,
) {
    thread::spawn(move || {
        for stream in listener.incoming() {
            let mut stream = stream.unwrap();
            let request = read(&mut stream);
            let (status, body) = answer(&request);
            respond(&mut stream, status, &body);
        }
    });
}

/// The health answer a running service gives.
pub fn healthy() -> (&'static str, String) {
    ("200 OK", r#"{"status":"ok"}"#.to_string())
}

/// What a service's first start leaves in `dir`: the bootstrap files pointing at a fresh port, and
/// its health answer on that port.
pub fn start_and_answer(dir: &Path) {
    start_and_answer_with(dir, |_| healthy());
}

/// Like [start_and_answer], but [answer] chooses what the stand-in answers: a test that needs the
/// quit route, the launch token or a failure uses this instead of its own listener.
pub fn start_and_answer_with(
    dir: &Path,
    answer: impl Fn(&Request) -> (&'static str, String) + Send + 'static,
) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    write_bootstrap(dir, port);
    answer_forever(listener, answer);
}

/// `config.json` with the per-install secret and `runtime.json` advertising `port`.
pub fn write_bootstrap(dir: &Path, port: u16) {
    write(
        dir,
        "config.json",
        r#"{"port":28763,"secret":"the-install-secret"}"#,
    );
    write(
        dir,
        "runtime.json",
        &format!(r#"{{"pid":4242,"endpoint":"http://127.0.0.1:{port}"}}"#),
    );
}

/// Service directories that keep the config and the runtime files together in `dir`.
pub fn dirs_in(dir: &Path) -> ServiceDirs {
    ServiceDirs {
        config_dir: dir.to_path_buf(),
        runtime_dir: dir.to_path_buf(),
    }
}

/// A port nothing listens on: bound once to take it from the OS, then released. `runtime.json`
/// pointing at it is the leftover of a service that crashed.
pub fn closed_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}
