//! Reading what the service has already done (roadmap item 67): a task's transcript and its
//! transcript turns, mirrored from `docs/api` (ADR 0029).

use std::io;

use serde::Deserialize;

use crate::Service;
use crate::response_json;

/// One speaker of a transcript (`turns.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Speaker {
    pub speaker_id: i64,
    pub speaker: String,
}

/// One segment of a turn (`turns.json`): a piece a speaker said, which can be reassigned.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Segment {
    pub id: i64,
    /// The segment's text: what a reassignment or an edit names on screen.
    pub text: String,
    /// When the segment starts, in milliseconds: the playhead's seek target.
    #[serde(default)]
    pub start_ms: u64,
    /// When the segment ends, in milliseconds: where playing it alone stops.
    #[serde(default)]
    pub end_ms: u64,
    /// Whether an edit replaced whisper's text (the revert request's precondition).
    #[serde(default)]
    pub edited: bool,
}

/// One turn: one speaker's uninterrupted stretch of the transcript (`turns.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Turn {
    /// The turn's speaker, or null for a task transcribed with speaker detection off.
    pub speaker_id: Option<i64>,
    pub start_ms: u64,
    pub text: String,
    /// The turn's segments, whose ids a reassignment names.
    #[serde(default)]
    pub segments: Vec<Segment>,
}

/// `GET /api/tasks/{id}/turns`, as far as a client reads it: the speakers and the turns.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Turns {
    pub speakers: Vec<Speaker>,
    pub turns: Vec<Turn>,
    /// Whether the task carries merges that a reset could undo (`hasMerges`).
    #[serde(default)]
    pub has_merges: bool,
}

/// Task `id`'s transcript in `format` (`txt`, `srt` or `vtt`), as the service renders it.
pub async fn transcript(service: &Service, id: i64, format: &str) -> io::Result<String> {
    let response = crate::client()
        .get(format!(
            "{}/api/tasks/{id}/transcript?format={format}",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(io::Error::other)?;
    response.text().await.map_err(io::Error::other)
}

/// Task `id`'s transcript turns.
pub async fn turns(service: &Service, id: i64) -> io::Result<Turns> {
    let response = crate::client()
        .get(format!("{}/api/tasks/{id}/turns", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}
/// Task `id`'s stored audio: the service's canonical FLAC clip, as it serves `/audio` (item 67's
/// player decodes it in the TUI; the browser hands the same URL to an `<audio>` element).
pub async fn audio(service: &Service, id: i64) -> io::Result<Vec<u8>> {
    let response = crate::client()
        .get(format!("{}/api/tasks/{id}/audio", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    crate::response_bytes(response).await
}
