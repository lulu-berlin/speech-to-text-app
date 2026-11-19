//! A task's speakers (roadmap item 67): renaming one and merging some, the calls the browser's
//! transcript makes, with the per-install secret. The answer is the task's effective speakers
//! (`docs/api/speakers.json`).

use std::io;

use serde::Deserialize;
use serde_json::json;

use crate::Service;
use crate::read::Speaker;
use crate::response_json;

/// `docs/api/speakers.json`, as far as a client reads it.
#[derive(Deserialize)]
struct Speakers {
    speakers: Vec<Speaker>,
}

/// Names speaker `speaker_id` of task `id` `name`, trimmed first as the contract asks of clients;
/// `None` sends null, and the service names it `Speaker N` again. The service answers with the
/// task's speakers.
pub async fn set_speaker_name(
    service: &Service,
    id: i64,
    speaker_id: i64,
    name: Option<&str>,
) -> io::Result<Vec<Speaker>> {
    let response = crate::client()
        .put(format!(
            "{}/api/tasks/{id}/speakers/{speaker_id}",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .json(&json!({ "speaker": name.map(str::trim) }))
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Speakers>(response)
        .await
        .map(|answer| answer.speakers)
}

/// Moves `segments` of task `id` to `speaker` - a new speaker when `None`; the service answers with
/// the task's effective speakers.
pub async fn reassign_segments(
    service: &Service,
    id: i64,
    segments: &[i64],
    speaker: Option<i64>,
) -> io::Result<Vec<Speaker>> {
    let response = crate::client()
        .post(format!(
            "{}/api/tasks/{id}/speakers/reassign",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .json(&json!({ "segmentIds": segments, "speakerId": speaker }))
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Speakers>(response)
        .await
        .map(|answer| answer.speakers)
}

/// Removes every merge of task `id` ("Reset all merges"); the service answers with its speakers.
pub async fn reset_merges(service: &Service, id: i64) -> io::Result<Vec<Speaker>> {
    let response = crate::client()
        .delete(format!(
            "{}/api/tasks/{id}/speakers/merges",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Speakers>(response)
        .await
        .map(|answer| answer.speakers)
}

/// Folds every speaker of `group` but `into` into speaker `into` of task `id`; `into` keeps its id
/// and name, and the service answers with the task's speakers.
pub async fn merge_speakers(
    service: &Service,
    id: i64,
    group: &[i64],
    into: i64,
) -> io::Result<Vec<Speaker>> {
    let response = crate::client()
        .post(format!(
            "{}/api/tasks/{id}/speakers/merge",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .json(&json!({ "group": group, "into": into }))
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json::<Speakers>(response)
        .await
        .map(|answer| answer.speakers)
}
