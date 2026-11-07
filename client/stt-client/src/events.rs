//! The service's event stream, `GET /api/events` (roadmap item 67): server-sent events parsed from
//! the response body as it arrives, and the events a client reads from them. The types mirror
//! `docs/api` by hand (ADR 0029), as far as a client reads them.

use std::collections::VecDeque;
use std::io;

use serde::Deserialize;

use crate::read::Speaker;
use crate::{Service, parse_json};

/// A task as the service reports it (`task.*.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub id: i64,
    pub filename: String,
    pub status: String,
    pub transcription_progress: f64,
}

/// The work queue (`queue.json`): the order the service processes tasks in.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Queue {
    pub paused: bool,
    pub task_ids: Vec<i64>,
}

/// The first event of every connection (`snapshot.json`): every task and the queue.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Snapshot {
    pub tasks: Vec<Task>,
    pub queue: Queue,
}

/// The progress of a running task (`task-progress.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskProgress {
    pub id: i64,
    pub transcription_progress: f64,
}

/// A task's effective speakers after a change (`speakers-renamed.json`, `speakers-merged.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct SpeakersChanged {
    pub id: i64,
    pub speakers: Vec<Speaker>,
}

/// One event of the stream.
#[derive(Debug)]
pub enum Event {
    Snapshot(Snapshot),
    /// A task changed (`task-updated`): the whole task as it is now.
    TaskUpdated(Task),
    TaskProgress(TaskProgress),
    /// The queue changed (`queue-updated`): its order or its pause.
    QueueUpdated(Queue),
    /// A task's speakers were renamed (`speakers-renamed`); its turns are unchanged.
    SpeakersRenamed(SpeakersChanged),
    /// A task's speakers were merged or the merges reset (`speakers-merged`); its turns can join or
    /// split, so a client showing them reads them again.
    SpeakersMerged(SpeakersChanged),
    /// An event this client does not read yet, by its name.
    Other(String),
}

/// An open event stream.
pub struct Events {
    response: reqwest::Response,
    parser: Parser,
    pending: VecDeque<Frame>,
}

/// Opens the running `service`'s event stream with the per-install secret.
pub async fn events(service: &Service) -> io::Result<Events> {
    let response = crate::client()
        .get(format!("{}/api/events", service.endpoint))
        .bearer_auth(&service.secret)
        .header("Accept", "text/event-stream")
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(io::Error::other)?;
    Ok(Events {
        response,
        parser: Parser::default(),
        pending: VecDeque::new(),
    })
}

impl Events {
    /// The next event, or `None` when the service closed the stream.
    pub async fn next(&mut self) -> io::Result<Option<Event>> {
        loop {
            if let Some(frame) = self.pending.pop_front() {
                return decode(frame).map(Some);
            }
            match self.response.chunk().await.map_err(io::Error::other)? {
                Some(bytes) => self.pending.extend(self.parser.push(&bytes)),
                None => return Ok(None),
            }
        }
    }
}

fn decode(frame: Frame) -> io::Result<Event> {
    match frame.name.as_str() {
        "snapshot" => parse_json(&frame.data).map(Event::Snapshot),
        "task-updated" => parse_json(&frame.data).map(Event::TaskUpdated),
        "task-progress" => parse_json(&frame.data).map(Event::TaskProgress),
        "queue-updated" => parse_json(&frame.data).map(Event::QueueUpdated),
        "speakers-renamed" => parse_json(&frame.data).map(Event::SpeakersRenamed),
        "speakers-merged" => parse_json(&frame.data).map(Event::SpeakersMerged),
        _ => Ok(Event::Other(frame.name)),
    }
}

/// One dispatched server-sent event: its name and its data lines joined.
struct Frame {
    name: String,
    data: String,
}

/// The server-sent-events wire format: `field: value` lines, an empty line ending each event, `:`
/// lines as comments; `data` lines accumulate and an unnamed event is a `message`.
#[derive(Default)]
struct Parser {
    unread: Vec<u8>,
    name: String,
    data: Vec<String>,
}

impl Parser {
    /// Takes the next bytes of the body and returns the events they complete.
    fn push(&mut self, bytes: &[u8]) -> Vec<Frame> {
        self.unread.extend_from_slice(bytes);
        let mut frames = Vec::new();
        while let Some(end) = self.unread.iter().position(|&byte| byte == b'\n') {
            let line: Vec<u8> = self.unread.drain(..=end).collect();
            let line = String::from_utf8_lossy(&line);
            let line = line.strip_suffix('\n').unwrap_or(&line);
            let line = line.strip_suffix('\r').unwrap_or(line);
            if line.is_empty() {
                let name = std::mem::take(&mut self.name);
                if !self.data.is_empty() {
                    frames.push(Frame {
                        name: if name.is_empty() {
                            "message".to_string()
                        } else {
                            name
                        },
                        data: self.data.join("\n"),
                    });
                }
                self.data.clear();
                continue;
            }
            if line.starts_with(':') {
                continue;
            }
            let (field, value) = match line.split_once(':') {
                Some((field, value)) => (field, value.strip_prefix(' ').unwrap_or(value)),
                None => (line, ""),
            };
            match field {
                "event" => self.name = value.to_string(),
                "data" => self.data.push(value.to_string()),
                _ => {}
            }
        }
        frames
    }
}
