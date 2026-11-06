//! The queue's commands (roadmap item 67): the task commands the browser's rows send, the queue's
//! pause, and its order - the same API calls, with the per-install secret.

use std::io;

use serde_json::json;

use crate::Service;
use crate::events::{Queue, Task};
use crate::response_json;

/// A task command, with the statuses `docs/api/task-transitions.json` allows it from: the table the
/// browser reads to offer only allowed commands. A command outside it is not sent.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TaskCommand {
    Pause,
    Resume,
    Cancel,
    Retry,
}

impl TaskCommand {
    pub fn allowed(self, status: &str) -> bool {
        match self {
            TaskCommand::Pause => matches!(status, "QUEUED" | "RUNNING"),
            TaskCommand::Resume => status == "PAUSED",
            TaskCommand::Cancel => matches!(status, "QUEUED" | "RUNNING" | "PAUSED"),
            TaskCommand::Retry => matches!(status, "FAILED" | "CANCELED"),
        }
    }

    fn name(self) -> &'static str {
        match self {
            TaskCommand::Pause => "pause",
            TaskCommand::Resume => "resume",
            TaskCommand::Cancel => "cancel",
            TaskCommand::Retry => "retry",
        }
    }
}

/// Runs `command` on task `id`; the service answers with the task as it now is.
pub async fn task_command(service: &Service, id: i64, command: TaskCommand) -> io::Result<Task> {
    let response = reqwest::Client::new()
        .post(format!(
            "{}/api/tasks/{id}/{}",
            service.endpoint,
            command.name()
        ))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}

/// Pauses or resumes the whole queue; the service answers with the queue.
pub async fn set_queue_paused(service: &Service, paused: bool) -> io::Result<Queue> {
    let name = if paused { "pause" } else { "resume" };
    let response = reqwest::Client::new()
        .post(format!("{}/api/queue/{name}", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}

/// Puts the queue in `task_ids`' order (the pending tasks, as `PUT /api/queue/order` takes them).
pub async fn reorder_queue(service: &Service, task_ids: &[i64]) -> io::Result<Queue> {
    let response = reqwest::Client::new()
        .put(format!("{}/api/queue/order", service.endpoint))
        .bearer_auth(&service.secret)
        .json(&json!({ "taskIds": task_ids }))
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}
