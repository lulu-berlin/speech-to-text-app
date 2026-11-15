//! Editing and reverting one segment of a completed task's transcript (item 52's contract).

use std::io;

use serde_json::json;

use crate::read::Turns;
use crate::{Service, client, response_json};

/// Replaces segment `segment` of task `id`'s text with `text`, trimmed first as the contract asks of
/// clients; the service answers the task's updated turns
/// (`PUT /api/tasks/{id}/segments/{segmentId}`).
pub async fn edit_segment(
    service: &Service,
    id: i64,
    segment: i64,
    text: &str,
) -> io::Result<Turns> {
    let response = client()
        .put(format!(
            "{}/api/tasks/{id}/segments/{segment}",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .json(&json!({ "text": text.trim() }))
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Turns>(response).await
}

/// Puts whisper's original text back on segment `segment` of task `id`; the service answers the
/// task's updated turns (`POST …/revert`).
pub async fn revert_segment(service: &Service, id: i64, segment: i64) -> io::Result<Turns> {
    let response = client()
        .post(format!(
            "{}/api/tasks/{id}/segments/{segment}/revert",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Turns>(response).await
}
