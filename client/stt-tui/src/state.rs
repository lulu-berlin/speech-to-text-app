//! What the TUI knows of the service: the last snapshot, kept current by the events after it, and
//! which screen shows it - the queue, the history of completed tasks, one transcript, or the
//! settings.

use stt_client::{Event, Snapshot, Task};

use crate::settings::SettingsScreen;
use crate::transcript::Transcript;

/// The screen the terminal shows.
pub enum Screen {
    Queue,
    History,
    /// A transcript, with its speakers list's selection and rename prompt.
    Transcript(Transcript),
    /// The settings, with the selected row.
    Settings(SettingsScreen),
}

/// The service's state after `event`. Every stream starts with a snapshot, so a change before one
/// has nothing to apply to.
pub fn apply(state: &mut Option<Snapshot>, event: Event) {
    if let Event::Snapshot(latest) = event {
        *state = Some(latest);
        return;
    }
    let Some(state) = state else {
        return;
    };
    match event {
        Event::TaskUpdated(task) => {
            match state.tasks.iter_mut().find(|known| known.id == task.id) {
                Some(known) => *known = task,
                None => state.tasks.push(task),
            }
        }
        Event::TaskProgress(progress) => {
            if let Some(task) = state.tasks.iter_mut().find(|task| task.id == progress.id) {
                task.transcription_progress = progress.transcription_progress;
            }
        }
        Event::QueueUpdated(queue) => state.queue = queue,
        // The queue holds no speakers: an open transcript takes these (`main.rs`).
        Event::Snapshot(_)
        | Event::SpeakersRenamed(_)
        | Event::SpeakersMerged(_)
        | Event::Other(_) => {}
    }
}

/// The rows the queue view shows: the pending tasks in the queue's order, then the tasks that ended
/// FAILED or CANCELED, which can be retried and so stay reachable from the TUI.
pub fn rows(snapshot: Option<&Snapshot>) -> Vec<&Task> {
    let Some(snapshot) = snapshot else {
        return Vec::new();
    };
    let mut rows: Vec<&Task> = snapshot
        .queue
        .task_ids
        .iter()
        .filter_map(|id| snapshot.tasks.iter().find(|task| task.id == *id))
        .collect();
    rows.extend(snapshot.tasks.iter().filter(|task| {
        matches!(task.status.as_str(), "FAILED" | "CANCELED")
            && !snapshot.queue.task_ids.contains(&task.id)
    }));
    rows
}

/// The history's rows: the tasks that completed, in the list's order.
pub fn completed(tasks: &[Task]) -> Vec<&Task> {
    tasks
        .iter()
        .filter(|task| task.status == "COMPLETED")
        .collect()
}
