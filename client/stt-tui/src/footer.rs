//! The footer's key hints (roadmap item 67). Each hint is a catalog entry of its own (`hint.*`), and
//! a footer is the ordered list of its page's hints, each with the rule for when its key can act;
//! the footer shows the hints that can act now, the key reference lists every hint of the page.

use stt_client::{Task, TaskCommand};

use crate::state::Screen;
use crate::transcript::Transcript;
use crate::view::{Connection, View};
use crate::writes::{Place, hint_writes};

/// Whether a hint's key can act in the view.
type Rule = fn(&View<'_>) -> bool;

/// A hint list: each hint's catalog key with its rule, in the order the footer shows them.
pub(crate) type Hints = &'static [(&'static str, Rule)];

/// The key acts in every state its list is shown in.
fn always(_: &View<'_>) -> bool {
    true
}

/// `t` cycles the stored theme mode: it waits for the settings read.
fn theme_known(view: &View<'_>) -> bool {
    view.settings.is_some()
}

/// The queue row the task keys act on: none while the upload area has the focus.
fn queue_task<'a>(view: &View<'a>) -> Option<&'a Task> {
    if view.upload_focused || view.notice_focused.is_some() {
        None
    } else {
        crate::state::task_at(view.snapshot, view.selected)
    }
}

/// What `key` sends for the selected queue row, when its status allows any.
fn queue_command(view: &View<'_>, key: char) -> Option<TaskCommand> {
    queue_task(view).and_then(|task| crate::input::command_for(task, key))
}

fn pauses(view: &View<'_>) -> bool {
    queue_command(view, 'p') == Some(TaskCommand::Pause)
}

fn resumes(view: &View<'_>) -> bool {
    queue_command(view, 'p') == Some(TaskCommand::Resume)
}

fn cancels(view: &View<'_>) -> bool {
    queue_command(view, 'c').is_some()
}

/// A reorder swaps the selected task with its neighbour in the queue order: it needs one.
fn reorders(view: &View<'_>) -> bool {
    queue_task(view).is_some() && crate::state::rows(view.snapshot).len() > 1
}

/// Up and Down move between the upload area and the rows: they need a row.
fn has_rows(view: &View<'_>) -> bool {
    !crate::state::rows(view.snapshot).is_empty()
}

/// Space pauses a running queue - except on the upload area, where it opens the dialog.
fn queue_running(view: &View<'_>) -> bool {
    !view.upload_focused
        && view.notice_focused.is_none()
        && view.snapshot.is_some_and(|snapshot| !snapshot.queue.paused)
}

/// Space resumes a paused queue, except on the upload area.
fn queue_paused(view: &View<'_>) -> bool {
    !view.upload_focused
        && view.notice_focused.is_none()
        && view.snapshot.is_some_and(|snapshot| snapshot.queue.paused)
}

/// The hints the page's header offers beside the queue's state: the footer leaves them to it, the
/// key reference lists them.
const IN_HEADER: &[&str] = &["tui.hint.queuePause", "tui.hint.queueResume"];

/// Enter and Space open the upload dialog while the upload area has the focus.
fn on_upload_area(view: &View<'_>) -> bool {
    view.upload_focused
}

/// History's open and delete act on the selected row of the list as it is shown.
fn history_row(view: &View<'_>) -> bool {
    view.history_task().is_some()
}

/// Space plays the selected row's clip: it needs one stored.
fn history_audio(view: &View<'_>) -> bool {
    view.history_task().is_some_and(|task| task.has_audio)
}

/// A finished task's status page plays its clip: it needs one stored.
fn status_audio(view: &View<'_>) -> bool {
    matches!(view.screen, Screen::Status(task) if crate::state::ended(&task.status) && task.has_audio)
}

/// `r` retries a failed or canceled row.
fn history_retries(view: &View<'_>) -> bool {
    view.history_task()
        .and_then(|task| crate::input::command_for(task, 'r'))
        == Some(TaskCommand::Retry)
}

/// Up and Down move between History's rows: they need two.
fn several_history_rows(view: &View<'_>) -> bool {
    view.history_rows().len() > 1
}

/// The transcript the screen shows, if it shows one.
fn transcript<'a>(view: &View<'a>) -> Option<&'a Transcript> {
    match view.screen {
        Screen::Transcript(transcript) => Some(transcript),
        _ => None,
    }
}

/// `R` resets the task's merges only while the service reports some and the speakers can be edited.
fn has_merges(view: &View<'_>) -> bool {
    transcript(view)
        .is_some_and(|transcript| transcript.can_edit_speakers() && transcript.turns.has_merges)
}

/// How many segments the transcript holds.
fn segments(view: &View<'_>) -> usize {
    transcript(view).map_or(0, Transcript::segment_count)
}

/// The cursor's keys need a segment under it.
fn on_segment(view: &View<'_>) -> bool {
    transcript(view).is_some_and(|transcript| transcript.current().is_some())
}

/// `v` reverts only a segment an edit changed.
fn on_edited_segment(view: &View<'_>) -> bool {
    transcript(view)
        .and_then(Transcript::current)
        .is_some_and(|segment| segment.edited)
}

/// Paging and jumping move the cursor only when there is somewhere else to go.
fn several_segments(view: &View<'_>) -> bool {
    segments(view) > 1
}

/// A clip plays on the transcript the screen shows.
fn playing(view: &View<'_>) -> bool {
    view.player.is_some_and(crate::player::Player::playing)
}

/// While the cursor walks with the playhead, the footer says so.
fn following(view: &View<'_>) -> bool {
    transcript(view).is_some_and(|transcript| playing(view) && transcript.tracking())
}

/// `f` is offered while a clip plays and the cursor has left the playhead.
fn follow_offer(view: &View<'_>) -> bool {
    transcript(view).is_some_and(|transcript| playing(view) && !transcript.tracking())
}

/// A merge folds one speaker into another: it needs two, and speakers that can be edited.
fn merges(view: &View<'_>) -> bool {
    transcript(view).is_some_and(|transcript| {
        transcript.can_edit_speakers() && transcript.turns.speakers.len() > 1
    })
}

/// A rename needs a speaker that can be edited.
fn renames(view: &View<'_>) -> bool {
    transcript(view).is_some_and(Transcript::can_edit_speakers)
}

/// Assigning moves the marked segments; without any, Enter plays from the cursor's segment instead.
fn marked(view: &View<'_>) -> bool {
    transcript(view).is_some_and(Transcript::marking)
}

/// Marking needs a segment under the cursor, and segments that can be marked.
fn marks(view: &View<'_>) -> bool {
    on_segment(view) && transcript(view).is_some_and(Transcript::can_mark)
}

/// Enter plays from the cursor's segment while nothing is marked.
fn plays_from_segment(view: &View<'_>) -> bool {
    on_segment(view) && !marked(view)
}

pub(crate) const QUEUE: Hints = &[
    ("tui.hint.move", has_rows),
    ("tui.hint.reorder", reorders),
    ("tui.hint.reorderEnds", reorders),
    ("tui.hint.pause", pauses),
    ("tui.hint.resume", resumes),
    ("tui.hint.cancel", cancels),
    ("tui.hint.queuePause", queue_running),
    ("tui.hint.queueResume", queue_paused),
    ("tui.hint.upload", always),
    ("tui.hint.uploadArea", on_upload_area),
    ("tui.hint.pages", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.quit", always),
    ("tui.hint.help", always),
];

pub(crate) const HISTORY: Hints = &[
    ("tui.hint.move", several_history_rows),
    ("tui.hint.open", history_row),
    ("tui.hint.play", history_audio),
    ("tui.hint.search", always),
    ("tui.hint.filter", always),
    ("tui.hint.retry", history_retries),
    ("tui.hint.delete", history_row),
    ("tui.hint.pages", always),
    ("tui.hint.back", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.quit", always),
    ("tui.hint.help", always),
];

pub(crate) const TRANSCRIPT: Hints = &[
    ("tui.hint.transcript", always),
    ("tui.hint.segmentMove", on_segment),
    ("tui.hint.page", several_segments),
    ("tui.hint.start", several_segments),
    ("tui.hint.end", several_segments),
    ("tui.hint.play", always),
    ("tui.hint.following", following),
    ("tui.hint.follow", follow_offer),
    ("tui.hint.playSegment", on_segment),
    ("tui.hint.edit", on_segment),
    ("tui.hint.revert", on_edited_segment),
    ("tui.hint.rename", renames),
    ("tui.hint.merge", merges),
    ("tui.hint.resetMerges", has_merges),
    ("tui.hint.mark", marks),
    ("tui.hint.markTurn", marks),
    ("tui.hint.playFrom", plays_from_segment),
    ("tui.hint.moveTo", marked),
    ("tui.hint.newSpeaker", marked),
    ("tui.hint.download", always),
    ("tui.hint.delete", always),
    ("tui.hint.back", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.help", always),
];

/// The panel's `R` resets a name of the speaker's own - a default one has nothing to reset - while
/// the speakers can be edited.
fn renamed(view: &View<'_>) -> bool {
    transcript(view)
        .is_some_and(|transcript| transcript.can_edit_speakers() && transcript.focused_renamed())
}

/// On the panel Enter renames the speaker, unless segments are marked: then it moves them there.
fn unmarked(view: &View<'_>) -> bool {
    !marked(view)
}

/// A transcript's Speakers panel, while it has the keyboard.
const SPEAKERS: Hints = &[
    ("tui.hint.speakers", always),
    ("tui.hint.move", always),
    ("tui.hint.renameSpeaker", unmarked),
    ("tui.hint.assign", marked),
    ("tui.hint.resetName", renamed),
    ("tui.hint.merge", merges),
    ("tui.hint.play", always),
    ("tui.hint.back", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.help", always),
];

/// The "Move to" list, while it is open over a transcript.
const MOVE_TO: Hints = &[
    ("tui.hint.move", always),
    ("tui.hint.moveThere", always),
    ("tui.hint.dialogCancel", always),
];

const STATUS: Hints = &[
    ("tui.hint.play", status_audio),
    ("tui.hint.back", always),
    ("tui.hint.pages", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.quit", always),
    ("tui.hint.help", always),
];

/// The settings page's selected row: a field's Enter chooses or edits, the restore row's restores.
fn on_restore(view: &View<'_>) -> bool {
    matches!(view.screen, Screen::Settings(screen) if screen.on_restore())
}

/// The settings page with its fields - not the page a failed read left.
fn on_field(view: &View<'_>) -> bool {
    matches!(view.screen, Screen::Settings(_))
}

/// A field's Enter chooses or edits: not the restore row's.
fn on_choose(view: &View<'_>) -> bool {
    on_field(view) && !on_restore(view)
}

/// The settings page a failed read left: Enter reads the settings again.
fn on_failed(view: &View<'_>) -> bool {
    matches!(view.screen, Screen::SettingsFailed(_))
}

const SETTINGS: Hints = &[
    ("tui.hint.move", on_field),
    ("tui.hint.choose", on_choose),
    ("tui.hint.restoreDefaults", on_restore),
    ("tui.hint.readAgain", on_failed),
    ("tui.hint.pages", always),
    ("tui.hint.back", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.quit", always),
    ("tui.hint.help", always),
];

/// Up and Down move the dialog's file list: they need two rows.
fn dialog_moves(view: &View<'_>) -> bool {
    view.upload
        .is_some_and(|dialog| dialog.browser().rows().count() > 1)
}

/// Enter follows a typed path, or the selected row.
fn dialog_opens(view: &View<'_>) -> bool {
    view.upload.is_some_and(|dialog| {
        let browser = dialog.browser();
        browser.typed().is_some() || browser.rows().next().is_some()
    })
}

/// Left and Backspace go to the folder above - the root has none.
fn dialog_goes_up(view: &View<'_>) -> bool {
    view.upload
        .is_some_and(|dialog| dialog.browser().parent().is_some())
}

/// Space marks the selected file for a batch, while nothing is being typed.
fn dialog_marks(view: &View<'_>) -> bool {
    view.upload
        .is_some_and(crate::upload_dialog::UploadDialog::can_mark)
}

/// Right opens the selected folder while the list has the focus; on an option it changes it.
fn dialog_opens_folder(view: &View<'_>) -> bool {
    view.upload.is_some_and(|dialog| {
        dialog.focus() == crate::upload_options::Field::Browser && dialog.browser().selects_folder()
    })
}

/// Up and Down move the download dialog's list: they need two rows.
fn download_moves(view: &View<'_>) -> bool {
    view.download
        .is_some_and(|dialog| dialog.browser().rows().count() > 1)
}

/// Left and Backspace go up from the download dialog's folder - the root has none.
fn download_goes_up(view: &View<'_>) -> bool {
    view.download
        .is_some_and(|dialog| dialog.browser().parent().is_some())
}

const UPLOAD: Hints = &[
    ("tui.hint.browseMove", dialog_moves),
    ("tui.hint.uploadOpen", dialog_opens),
    ("tui.hint.browseUp", dialog_goes_up),
    ("tui.hint.browseFolder", dialog_opens_folder),
    ("tui.hint.markFile", dialog_marks),
    ("tui.hint.uploadOptions", always),
    ("tui.hint.dialogCancel", always),
];

/// Up and Down move between the notices: they need two of them.
fn notice_moves(view: &View<'_>) -> bool {
    view.notices.len() > 1
}

/// The queue page's finished-task notices, while the keyboard is on them.
const NOTICES: Hints = &[
    ("tui.hint.move", notice_moves),
    ("tui.hint.open", always),
    ("tui.hint.pages", always),
    ("tui.hint.back", always),
    ("tui.hint.theme", theme_known),
    ("tui.hint.help", always),
];

const DOWNLOAD: Hints = &[
    ("tui.hint.browseMove", download_moves),
    ("tui.hint.downloadOpen", always),
    ("tui.hint.browseUp", download_goes_up),
    ("tui.hint.browseFolder", always),
    ("tui.hint.downloadFormat", always),
    ("tui.hint.downloadSave", always),
    ("tui.hint.dialogCancel", always),
];

/// The page's own hint list: the one its key reference lists.
fn page(screen: &Screen) -> Hints {
    match screen {
        Screen::Queue => QUEUE,
        Screen::History => HISTORY,
        Screen::Transcript(_) => TRANSCRIPT,
        Screen::Status(_) => STATUS,
        Screen::Settings(_) | Screen::SettingsFailed(_) => SETTINGS,
    }
}

/// The two keys every text prompt has: Escape cancels it, Backspace erases a character.
const PROMPT_TAIL: Hints = &[
    ("tui.hint.dialogCancel", always),
    ("tui.hint.promptErase", always),
];

/// A segment's edit prompt, before the shared tail: save, and revert an edited segment.
const EDIT_PROMPT: Hints = &[
    ("tui.hint.promptSave", always),
    ("tui.hint.promptRevert", prompt_reverts),
];

/// A speaker's rename prompt, before the shared tail.
const RENAME_PROMPT: Hints = &[("tui.hint.promptSave", always)];

/// History's search while it is typed, before the shared tail.
const SEARCH_PROMPT: Hints = &[("tui.hint.searchDone", always)];

/// Ctrl-R reverts the edit prompt's segment only when an edit changed it.
fn prompt_reverts(view: &View<'_>) -> bool {
    transcript(view).is_some_and(Transcript::edit_reverts)
}

/// The hint list of what has the keyboard, the tail it shares, and whether it is the page's own: a
/// dialog's while one is open, a text prompt's head and [`PROMPT_TAIL`] while one takes the typing,
/// the Speakers panel's while it has the transcript's keyboard, else the page's with no tail.
fn list(view: &View<'_>) -> (Hints, Hints, bool) {
    const NO_TAIL: Hints = &[];
    if view.upload.is_some() {
        (UPLOAD, NO_TAIL, false)
    } else if view.download.is_some() {
        (DOWNLOAD, NO_TAIL, false)
    } else if view.edit.is_some() {
        (EDIT_PROMPT, PROMPT_TAIL, false)
    } else if view.rename.is_some() {
        (RENAME_PROMPT, PROMPT_TAIL, false)
    } else if view.mode == crate::input::Mode::Search {
        (SEARCH_PROMPT, PROMPT_TAIL, false)
    } else if view.notice_focused.is_some() {
        (NOTICES, NO_TAIL, false)
    } else if transcript(view).is_some_and(|transcript| transcript.picker().is_some()) {
        (MOVE_TO, NO_TAIL, false)
    } else if transcript(view).is_some_and(Transcript::speaker_focus) {
        (SPEAKERS, NO_TAIL, false)
    } else {
        (page(view.screen), NO_TAIL, true)
    }
}

/// Where a write would land in the view: what the write rule needs to tell whether a hint's key
/// writes.
fn write_place<'a>(view: &View<'a>) -> Place<'a> {
    Place {
        screen: view.screen,
        upload: view.upload,
        upload_focused: view.upload_focused,
        selected_task: match view.screen {
            Screen::History => view.history_task(),
            _ => crate::state::task_at(view.snapshot, view.selected),
        },
        confirming: view.confirming,
    }
}

/// The catalog texts of the hints of `hints` that can act now. While the stream is not live a write
/// cannot act either, so the hints whose key the write rule picks - each key taken as the press it
/// names - are left out.
fn acting<'a>(
    view: &View<'_>,
    hints: impl Iterator<Item = &'a (&'static str, Rule)>,
) -> Vec<String> {
    let offline = (view.connection != Connection::Live).then(|| write_place(view));
    hints
        .filter(|(_, rule)| rule(view))
        .map(|(key, _)| stt_client::text(key, &[]))
        .filter(|hint| match &offline {
            Some(place) => !hint_writes(hint, view.mode, view.context, place),
            None => true,
        })
        .collect()
}

/// The footer: the hints of what has the keyboard that can act now, less those the page's header
/// offers.
pub fn shown(view: &View<'_>) -> Vec<String> {
    let (head, tail, _) = list(view);
    acting(
        view,
        head.iter()
            .chain(tail)
            .filter(|(key, _)| !IN_HEADER.contains(key)),
    )
}

/// Whether the footer shows the page's own list where `?` opens its key reference: then it is one
/// row, and the hints that do not fit are left to `? more` - the reference lists every one. A
/// dialog's, a prompt's or a panel's list, or a page list while a dropdown, a number or a
/// confirmation reads the keys, wraps instead, as `?` does not open the reference there.
pub fn compacts(view: &View<'_>) -> bool {
    list(view).2 && view.mode == crate::input::Mode::Normal
}

/// The page's key reference: the hints of the page that can act now, in the footer's order - those
/// the footer shows, those it leaves to `? more`, and those the header offers.
pub fn reference(view: &View<'_>) -> Vec<String> {
    acting(view, page(view.screen).iter())
}
