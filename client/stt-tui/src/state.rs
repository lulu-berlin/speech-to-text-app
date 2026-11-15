//! What the TUI knows of the service: the last snapshot, kept current by the events after it, and
//! which screen shows it - the queue, the history of completed tasks, one transcript, or the
//! settings.

use stt_client::{Event, Snapshot, Task};

use crate::settings::SettingsScreen;
use crate::transcript::Transcript;

/// The GUI's pages: the tab bar shows these, and `1`/`2`/`3` (or `Tab`) switch between them. A
/// transcript is the History page.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Page {
    Queue,
    History,
    Settings,
}

impl Page {
    /// Every page, in the tab bar's order.
    pub const ALL: [Page; 3] = [Page::Queue, Page::History, Page::Settings];

    /// The next page, round.
    pub fn next(self) -> Page {
        match self {
            Page::Queue => Page::History,
            Page::History => Page::Settings,
            Page::Settings => Page::Queue,
        }
    }

    /// The previous page, round.
    pub fn previous(self) -> Page {
        self.next().next()
    }
}

impl Screen {
    /// The page this screen belongs to.
    pub fn page(&self) -> Page {
        match self {
            Screen::Queue => Page::Queue,
            Screen::History | Screen::Transcript(_) => Page::History,
            Screen::Status(task) => {
                if ended(&task.status) {
                    Page::History
                } else {
                    Page::Queue
                }
            }
            Screen::Settings(_) | Screen::SettingsFailed(_) => Page::Settings,
        }
    }

    /// The screen `b` goes back to: History from a transcript or a failed or canceled task's page,
    /// and the queue from the pages and a pending task's page.
    pub fn back(&self) -> Screen {
        match self {
            Screen::Transcript(_) => Screen::History,
            Screen::Status(task) => {
                if ended(&task.status) {
                    Screen::History
                } else {
                    Screen::Queue
                }
            }
            Screen::Queue | Screen::History | Screen::Settings(_) | Screen::SettingsFailed(_) => {
                Screen::Queue
            }
        }
    }
}

/// The screen the terminal shows.
pub enum Screen {
    Queue,
    History,
    /// A transcript, with its speakers list's selection and rename prompt.
    Transcript(Transcript),
    /// A task without a transcript: why it failed or was canceled, or that it is still waiting.
    Status(Task),
    /// The settings, with the selected row.
    Settings(SettingsScreen),
    /// The settings page when the settings could not be read: why, and Enter to read again.
    SettingsFailed(String),
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
        Event::SettingsUpdated(_) => {}
        Event::TaskDeleted(id) => {
            state.tasks.retain(|task| task.id != id);
            state.queue.task_ids.retain(|known| *known != id);
        }
        // The queue holds no speakers or settings: the open screen takes these (`main.rs`).
        Event::Snapshot(_)
        | Event::SpeakersRenamed(_)
        | Event::SpeakersMerged(_)
        | Event::SpeakersReassigned(_)
        | Event::SegmentEdited(_)
        | Event::Other(_) => {}
    }
}

/// The rows the queue view shows: the pending tasks - QUEUED, RUNNING and PAUSED - in the queue's
/// order. FAILED and CANCELED ones are History's, where they are retried, as in the browser.
pub fn rows(snapshot: Option<&Snapshot>) -> Vec<&Task> {
    let Some(snapshot) = snapshot else {
        return Vec::new();
    };
    snapshot
        .queue
        .task_ids
        .iter()
        .filter_map(|id| snapshot.tasks.iter().find(|task| task.id == *id))
        .filter(|task| matches!(task.status.as_str(), "QUEUED" | "RUNNING" | "PAUSED"))
        .collect()
}

/// The queue row at `selected`, if there is one.
pub fn task_at(snapshot: Option<&Snapshot>, selected: usize) -> Option<&Task> {
    rows(snapshot).get(selected).copied()
}

/// History's status filter: all its finished rows, or one status's - the browser's options and
/// order, which `f` cycles.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Status {
    All,
    Completed,
    Failed,
    Canceled,
}

impl Status {
    /// The next filter `f` shows.
    pub fn next(self) -> Status {
        match self {
            Status::All => Status::Completed,
            Status::Completed => Status::Failed,
            Status::Failed => Status::Canceled,
            Status::Canceled => Status::All,
        }
    }

    /// The catalog key of the filter's name, as the browser writes it.
    pub fn key(self) -> &'static str {
        match self {
            Status::All => "history.status.all",
            Status::Completed => "history.status.completed",
            Status::Failed => "history.status.failed",
            Status::Canceled => "history.status.canceled",
        }
    }

    fn allows(self, status: &str) -> bool {
        match self {
            Status::All => true,
            Status::Completed => status == "COMPLETED",
            Status::Failed => status == "FAILED",
            Status::Canceled => status == "CANCELED",
        }
    }
}

/// The history's rows: the finished tasks whose name holds `search` - ignoring case and the spaces
/// around it - and whose status `filter` allows, in the list's order.
pub fn history<'a>(tasks: &'a [Task], search: &str, filter: Status) -> Vec<&'a Task> {
    let needle = search.trim().to_lowercase();
    let mut rows: Vec<&Task> = finished(tasks)
        .into_iter()
        .filter(|task| {
            filter.allows(&task.status) && task.filename.to_lowercase().contains(&needle)
        })
        .collect();
    // Newest first, as the browser sorts its cards; a task whose finish time is unknown sorts
    // last. The sort is stable, so the list's order breaks ties.
    rows.sort_by_key(|task| std::cmp::Reverse(task.finished_at.unwrap_or(i64::MIN)));
    rows
}

/// The history's rows: the tasks that finished - COMPLETED, FAILED or CANCELED - in the list's
/// order.
pub fn finished(tasks: &[Task]) -> Vec<&Task> {
    tasks
        .iter()
        .filter(|task| task.status == "COMPLETED" || ended(&task.status))
        .collect()
}

/// Whether a task ended in a failure or a cancellation: the statuses whose page says what happened
/// instead of showing a transcript.
pub fn ended(status: &str) -> bool {
    matches!(status, "FAILED" | "CANCELED")
}

/// A task's status in the app's words (item 88's catalog): the browser's `status.*` entries, so a
/// translated UI names the statuses as the browser does. A status the catalog does not know is
/// shown as the service sent it.
pub fn status_word(status: &str) -> String {
    let key = match status {
        "QUEUED" => "status.queued",
        "RUNNING" => "status.running",
        "PAUSED" => "status.paused",
        "COMPLETED" => "status.completed",
        "FAILED" => "status.failed",
        "CANCELED" => "status.canceled",
        _ => return status.to_string(),
    };
    stt_client::text(key, &[])
}
