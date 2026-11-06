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

/// Renames speaker `speaker_id` of task `id` to `name`, trimmed first as the contract asks of
/// clients; the service answers with the task's speakers.
pub async fn rename_speaker(
    service: &Service,
    id: i64,
    speaker_id: i64,
    name: &str,
) -> io::Result<Vec<Speaker>> {
    let response = crate::client()
        .put(format!(
            "{}/api/tasks/{id}/speakers/{speaker_id}",
            service.endpoint
        ))
        .bearer_auth(&service.secret)
        .json(&json!({ "speaker": name.trim() }))
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
