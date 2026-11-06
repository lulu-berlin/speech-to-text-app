//! What the TUI knows of the service: the last snapshot, kept current by the events after it.

use stt_client::{Event, Snapshot};

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
        Event::Snapshot(_) | Event::Other(_) => {}
    }
}
