//! Reading what the service has already done (roadmap item 67): the task list behind the history
//! view, and a task's transcript turns, mirrored from `docs/api` (ADR 0029).

use std::io;

use serde::Deserialize;

use crate::Service;
use crate::events::Task;
use crate::response_json;

/// `GET /api/tasks`, as far as a client reads it.
#[derive(Debug, Deserialize)]
struct TaskList {
    tasks: Vec<Task>,
}

/// One speaker of a transcript (`turns.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Speaker {
    pub speaker_id: i64,
    pub speaker: String,
}

/// One turn: one speaker's uninterrupted stretch of the transcript (`turns.json`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Turn {
    pub speaker_id: i64,
    pub start_ms: u64,
    pub text: String,
}

/// `GET /api/tasks/{id}/turns`, as far as a client reads it: the speakers and the turns.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Turns {
    pub speakers: Vec<Speaker>,
    pub turns: Vec<Turn>,
}

/// Every task the service knows, completed ones included.
pub async fn history(service: &Service) -> io::Result<Vec<Task>> {
    let response = crate::client()
        .get(format!("{}/api/tasks", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<TaskList>(response)
        .await
        .map(|list| list.tasks)
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
