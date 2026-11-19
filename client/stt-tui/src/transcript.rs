//! The transcript screen (roadmap item 67): one task's turns, the segment the cursor is on, its
//! speakers list, the edit and rename prompts, and the requests that change speakers and segment
//! texts.

use std::cell::{Cell, RefCell};
use std::sync::mpsc;

use ratatui::layout::{Position, Rect};

use stt_client::{
    Segment, Service, Speaker, Task, Turns, edit_segment, merge_speakers, reassign_segments,
    reset_merges, revert_segment, set_speaker_name, text, turns,
};

use crate::Message;
use crate::input::Action;
use crate::view::Dialog;

/// A transcript change waiting for its confirmation: a merge always asks, a reassignment asks in a
/// task with merges, since it makes them permanent (the browser's dialogs).
pub enum Pending {
    Merge {
        marked: i64,
        into: i64,
    },
    Reassign {
        segments: Vec<i64>,
        speaker: Option<i64>,
    },
}

/// How an open transcript's turns stand: the request is out, the read failed, or the turns are
/// here.
pub enum Load {
    Waiting,
    /// The read failed, with the service's message.
    Failed(String),
    Loaded,
}

/// An open transcript: the task, its speakers and turns, the segment the cursor is on, the name or
/// segment text being typed while a prompt is open, the speaker marked for a merge, and the segments
/// marked for a reassignment.
pub struct Transcript {
    pub task: Task,
    pub turns: Turns,
    /// The segment the cursor is on, counted over the turns in reading order.
    cursor: usize,
    rename: Option<String>,
    /// The segment's id and text while its edit prompt is open.
    edit: Option<(i64, String)>,
    /// The id of the speaker a merge folds into the next one `m` is pressed on.
    pub marked: Option<i64>,
    /// The segments a reassignment moves, while they are marked.
    marked_segments: Vec<i64>,
    /// Whether the keyboard is on the Speakers panel rather than the transcript.
    speaker_focus: bool,
    /// The speaker row the panel selected, while it has the keyboard.
    speaker_cursor: usize,
    /// The segments a page step moves by: those whose turns start in the view (set at each draw).
    page: Cell<usize>,
    /// The row the view starts at (set at each draw, following the cursor).
    scroll: Cell<u16>,
    /// Where the last frame drew the turns: their area inside its border, and for each of their
    /// lines the segment each column shows (set at each draw, for the mouse).
    body: Cell<Rect>,
    hits: RefCell<Vec<Vec<Option<usize>>>>,
    /// The change the open confirmation asks about.
    pending: Option<Pending>,
    /// How the turns stand: being read, failed to be, or here.
    load: Load,
    /// Whether the cursor walks with the playhead: set when playback starts or seeks, cleared by a
    /// key that moves the cursor.
    tracking: bool,
    /// The "Move to" list's cursor while it is open: 0 is New speaker, then the speakers in order.
    picker: Option<usize>,
    /// The segment Ctrl-R reverted from the edit prompt: when the answer's turns arrive, whisper's
    /// text goes into the still-open prompt.
    reverted_in_prompt: Option<i64>,
}

impl Transcript {
    /// A transcript whose turns are still being read: the header shows at once, with the words
    /// that say it is loading.
    pub fn loading(task: Task) -> Transcript {
        let mut transcript = Transcript::new(
            task,
            Turns {
                speakers: Vec::new(),
                turns: Vec::new(),
                has_merges: false,
            },
        );
        transcript.load = Load::Waiting;
        transcript
    }

    /// Whether the turns are still being read.
    pub fn is_loading(&self) -> bool {
        matches!(self.load, Load::Waiting)
    }

    /// Why the turns could not be read, while that stands.
    pub fn load_error(&self) -> Option<&str> {
        match &self.load {
            Load::Failed(error) => Some(error),
            Load::Waiting | Load::Loaded => None,
        }
    }

    /// The read failed: the header stays and the words say why, with Enter to try again.
    pub fn load_failed(&mut self, error: String) {
        self.load = Load::Failed(error);
    }

    pub fn new(task: Task, turns: Turns) -> Transcript {
        Transcript {
            task,
            turns,
            cursor: 0,
            rename: None,
            edit: None,
            marked: None,
            marked_segments: Vec::new(),
            speaker_focus: false,
            speaker_cursor: 0,
            page: Cell::new(1),
            scroll: Cell::new(0),
            body: Cell::new(Rect::default()),
            hits: RefCell::new(Vec::new()),
            pending: None,
            load: Load::Loaded,
            tracking: false,
            picker: None,
            reverted_in_prompt: None,
        }
    }

    /// The confirmation open over the transcript, while a change waits for one.
    pub fn dialog(&self) -> Option<Dialog> {
        let name = |id: i64| {
            self.turns
                .speakers
                .iter()
                .find(|speaker| speaker.speaker_id == id)
                .map(|speaker| speaker.speaker.clone())
                .unwrap_or_default()
        };
        Some(match self.pending.as_ref()? {
            Pending::Merge { marked, into } => {
                let (folded, survivor) = (name(*marked), name(*into));
                Dialog {
                    title: text("transcript.speakers.mergeTitle", &[]),
                    body: text(
                        "transcript.speakers.mergeBody",
                        &[("names", &folded), ("survivor", &survivor)],
                    ),
                    confirm: text("transcript.speakers.mergeAction", &[]),
                }
            }
            Pending::Reassign { .. } => Dialog {
                title: text("transcript.reassign.title", &[]),
                body: text("transcript.reassign.body", &[]),
                confirm: text("transcript.move", &[]),
            },
        })
    }

    /// The catalog name of the write the open confirmation would send: what a dropped stream
    /// refuses.
    pub fn pending_write(&self) -> Option<&'static str> {
        Some(match self.pending.as_ref()? {
            Pending::Merge { .. } => "tui.writes.merge",
            Pending::Reassign { .. } => "tui.writes.assign",
        })
    }

    /// Enter in the confirmation: sends the change it asked about.
    pub fn confirm(
        &mut self,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        match self.pending.take() {
            Some(Pending::Merge { marked, into }) => {
                merge(runtime, service, sender, self.task.id, marked, into);
            }
            Some(Pending::Reassign { segments, speaker }) => {
                reassign(runtime, service, sender, self.task.id, segments, speaker);
            }
            None => {}
        }
    }

    /// Escape in the confirmation: nothing changes - the speaker stays marked for its merge, the
    /// segments stay selected.
    pub fn dismiss(&mut self) {
        match self.pending.take() {
            Some(Pending::Merge { marked, .. }) => self.marked = Some(marked),
            Some(Pending::Reassign { segments, .. }) => self.marked_segments = segments,
            None => {}
        }
    }

    /// Moves the selected segments to `speaker` (a new one when `None`); in a task with merges it
    /// asks first, since the move makes them permanent.
    fn assign(
        &mut self,
        speaker: Option<i64>,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let segments = std::mem::take(&mut self.marked_segments);
        if self.turns.has_merges {
            self.pending = Some(Pending::Reassign { segments, speaker });
        } else {
            reassign(runtime, service, sender, self.task.id, segments, speaker);
        }
    }

    /// The speaker whose segments are marked for a reassignment, if any.
    pub fn marked_speaker(&self) -> Option<i64> {
        let id = *self.marked_segments.first()?;
        self.turns
            .turns
            .iter()
            .find(|turn| turn.segments.iter().any(|segment| segment.id == id))
            .and_then(|turn| turn.speaker_id)
    }

    /// How many segments are marked for a reassignment.
    pub fn marked_segment_count(&self) -> usize {
        self.marked_segments.len()
    }

    /// Whether any segment is marked for a reassignment.
    pub fn marking(&self) -> bool {
        !self.marked_segments.is_empty()
    }

    /// Whether a speaker is marked for a merge.
    pub fn merging(&self) -> bool {
        self.marked.is_some()
    }

    /// Whether the speakers can be edited - merged, renamed, their names or merges reset: not while
    /// segments are marked, since merging and reassigning exclude each other, as in the browser.
    pub fn can_edit_speakers(&self) -> bool {
        !self.marking() && !self.turns.speakers.is_empty()
    }

    /// Whether segments can be marked for a reassignment: not while a speaker is marked for a merge,
    /// and not when the task has no speakers to reassign them to.
    pub fn can_mark(&self) -> bool {
        !self.merging() && !self.turns.speakers.is_empty()
    }

    /// Whether segment `id` is marked for a reassignment.
    pub fn segment_marked(&self, id: i64) -> bool {
        self.marked_segments.contains(&id)
    }

    /// The "Move to" list's cursor while it is open: 0 is New speaker, then the speakers in order.
    pub fn picker(&self) -> Option<usize> {
        self.picker
    }

    /// Closes the "Move to" list without moving anything, as Escape does.
    pub fn cancel_picker(&mut self) {
        self.picker = None;
    }

    /// Marks `ids` for a reassignment, or unmarks them once every one is marked.
    fn toggle_marked(&mut self, ids: &[i64]) {
        if ids.iter().all(|id| self.marked_segments.contains(id)) {
            self.marked_segments.retain(|id| !ids.contains(id));
        } else {
            for id in ids {
                if !self.marked_segments.contains(id) {
                    self.marked_segments.push(*id);
                }
            }
        }
    }

    /// The name in the rename prompt, while it is open.
    pub fn rename(&self) -> Option<&str> {
        self.rename.as_deref()
    }

    /// The segment's text in the edit prompt, while it is open.
    pub fn edit(&self) -> Option<&str> {
        self.edit.as_ref().map(|(_, text)| text.as_str())
    }

    /// Whether the edit prompt's segment was edited, so Ctrl-R there has whisper's text to restore.
    pub fn edit_reverts(&self) -> bool {
        self.edit
            .as_ref()
            .and_then(|(id, _)| self.segment(*id))
            .is_some_and(|segment| segment.edited)
    }

    /// Puts a refused edit's `draft` back in the prompt for segment `segment`.
    pub fn keep_draft(&mut self, segment: i64, draft: String) {
        self.edit = Some((segment, draft));
    }

    /// Whether the cursor walks with the playhead.
    pub fn tracking(&self) -> bool {
        self.tracking
    }

    /// Sets the cursor walking with the playhead, as starting or seeking playback does.
    pub fn start_tracking(&mut self) {
        self.tracking = true;
    }

    /// Puts the cursor on the segment the playhead is in at `at` and starts it walking with it
    /// again - what `f` asks after a key moved the cursor off the playhead.
    pub fn follow_playhead(&mut self, at: std::time::Duration) {
        self.track(at);
        self.start_tracking();
    }

    /// The segment the playhead is in at `at`, as (turn, segment): the last one that started by
    /// then, which keeps the current segment through a gap between segments, as the browser does.
    pub fn playing(&self, at: std::time::Duration) -> Option<(usize, usize)> {
        let ms = u64::try_from(at.as_millis()).unwrap_or(u64::MAX);
        let mut found = None;
        for (turn, entry) in self.turns.turns.iter().enumerate() {
            for (segment, span) in entry.segments.iter().enumerate() {
                if span.start_ms <= ms {
                    found = Some((turn, segment));
                }
            }
        }
        found
    }

    /// Moves the cursor to the segment the playhead is in at `at`.
    pub fn track(&mut self, at: std::time::Duration) {
        let Some((turn, segment)) = self.playing(at) else {
            return;
        };
        self.cursor = self.turns.turns[..turn]
            .iter()
            .map(|entry| entry.segments.len())
            .sum::<usize>()
            + segment;
    }

    /// How many segments the turns hold: the cursor counts over them.
    pub fn segment_count(&self) -> usize {
        self.turns
            .turns
            .iter()
            .map(|turn| turn.segments.len())
            .sum()
    }

    /// The segment the cursor is on, as (turn, segment).
    pub fn position(&self) -> Option<(usize, usize)> {
        let mut index = self.cursor;
        for (turn, entry) in self.turns.turns.iter().enumerate() {
            if index < entry.segments.len() {
                return Some((turn, index));
            }
            index -= entry.segments.len();
        }
        None
    }

    /// The text buffer of the open prompt: the segment's to edit, or the speaker's name to rename.
    fn prompt_mut(&mut self) -> Option<&mut String> {
        match &mut self.edit {
            Some((_, text)) => Some(text),
            None => self.rename.as_mut(),
        }
    }

    /// Segment `id`, wherever it is in the turns.
    fn segment(&self, id: i64) -> Option<&Segment> {
        self.turns
            .turns
            .iter()
            .flat_map(|turn| &turn.segments)
            .find(|segment| segment.id == id)
    }

    /// Records the segments a page step moves by: those whose turns start in the view.
    pub fn set_page(&self, segments: usize) {
        self.page.set(segments.max(1));
    }

    /// The row the view starts at, kept so the cursor's turn stays visible.
    pub fn scroll(&self) -> u16 {
        self.scroll.get()
    }

    /// Scrolls the view so rows `start..end` - the cursor's turn - are visible, moving as little as
    /// possible.
    pub fn follow(&self, start: u16, end: u16, height: u16) {
        let height = height.max(1);
        let mut scroll = self.scroll.get();
        if start < scroll {
            scroll = start;
        } else if end > scroll + height {
            scroll = end - height;
        }
        self.scroll.set(scroll);
    }

    /// Records where the frame drew the turns: `body`, their area inside its border, and for each
    /// line of the turns the segment each column shows, counted as the cursor counts them.
    pub fn set_hits(&self, body: Rect, hits: Vec<Vec<Option<usize>>>) {
        self.body.set(body);
        self.hits.replace(hits);
    }

    /// The segment drawn at the screen's `column`, `row` by the last frame, if one is.
    pub fn segment_at(&self, column: u16, row: u16) -> Option<usize> {
        let body = self.body.get();
        if !body.contains(Position::new(column, row)) {
            return None;
        }
        let line = usize::from(row - body.y + self.scroll.get());
        let column = usize::from(column - body.x);
        self.hits.borrow().get(line)?.get(column).copied().flatten()
    }

    /// Moves the cursor to segment `index`, as the arrows would: the cursor stops walking with the
    /// playhead, and the keyboard leaves the Speakers panel.
    pub fn select_segment(&mut self, index: usize) {
        self.tracking = false;
        self.speaker_focus = false;
        self.cursor = index;
    }

    /// The segment the cursor is on.
    pub fn current(&self) -> Option<&Segment> {
        self.position()
            .and_then(|(turn, segment)| self.turns.turns[turn].segments.get(segment))
    }

    /// Whether the keyboard is on the Speakers panel.
    pub fn speaker_focus(&self) -> bool {
        self.speaker_focus
    }

    /// The index in the speakers list of the speaker the cursor's segment belongs to.
    pub fn speaker_index(&self) -> usize {
        if self.speaker_focus {
            return self
                .speaker_cursor
                .min(self.turns.speakers.len().saturating_sub(1));
        }
        let Some((turn, _)) = self.position() else {
            return 0;
        };
        let speaker_id = self.turns.turns[turn].speaker_id;
        self.turns
            .speakers
            .iter()
            .position(|speaker| Some(speaker.speaker_id) == speaker_id)
            .unwrap_or(0)
    }

    /// Whether the speaker the panel has the keyboard on carries a name of its own - what `R` there
    /// resets; a default name has nothing to reset.
    pub fn focused_renamed(&self) -> bool {
        self.turns
            .speakers
            .get(self.speaker_index())
            .is_some_and(|speaker| speaker.speaker != default_name(speaker.speaker_id))
    }

    /// Task `id`'s speakers as the service answered; they relabel this transcript if it is task
    /// `id`'s.
    pub fn set_speakers(&mut self, id: i64, speakers: Vec<Speaker>) {
        if self.task.id == id {
            if !speakers
                .iter()
                .any(|speaker| Some(speaker.speaker_id) == self.marked)
            {
                self.marked = None;
            }
            self.turns.speakers = speakers;
            self.speaker_cursor = self
                .speaker_cursor
                .min(self.turns.speakers.len().saturating_sub(1));
            if self.turns.speakers.is_empty() {
                self.speaker_focus = false;
            }
        }
    }

    /// Task `id`'s turns, read again; they replace this transcript's if it is task `id`'s.
    pub fn set_turns(&mut self, id: i64, turns: Turns) {
        if self.task.id == id {
            self.turns = turns;
            self.cursor = self.cursor.min(self.segment_count().saturating_sub(1));
            self.set_speakers(id, self.turns.speakers.clone());
            self.load = Load::Loaded;
            // A revert asked for from the edit prompt: the prompt, still open on that segment, now
            // holds whisper's text.
            if let Some(reverted) = self.reverted_in_prompt.take()
                && self
                    .edit
                    .as_ref()
                    .is_some_and(|(segment, _)| *segment == reverted)
                && let Some(text) = self.segment(reverted).map(|segment| segment.text.clone())
            {
                self.edit = Some((reverted, text));
            }
        }
    }

    /// Applies one key's action: Up/Down/Left/Right and `h`/`j`/`k`/`l` move the segment cursor;
    /// `r` renames its speaker, `m` marks or merges it, `a` marks its segment for a reassignment,
    /// `e` edits the segment, `v` reverts it, and while a prompt is open its keys edit, send or
    /// close it.
    pub fn handle(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        // The "Move to" list has the keys while it is open: Up and Down choose, Enter moves the
        // marked segments there, Escape closes it.
        if let Some(cursor) = self.picker {
            match action {
                Action::SelectUp => self.picker = Some(cursor.saturating_sub(1)),
                Action::SelectDown => {
                    self.picker = Some((cursor + 1).min(self.turns.speakers.len()));
                }
                Action::SubmitPrompt => {
                    self.picker = None;
                    let target = cursor
                        .checked_sub(1)
                        .and_then(|index| self.turns.speakers.get(index))
                        .map(|speaker| speaker.speaker_id);
                    self.assign(target, runtime, sender, service);
                }
                Action::CancelPrompt => self.picker = None,
                _ => {}
            }
            return;
        }
        match action {
            Action::SelectUp | Action::SegmentPrevious => {
                self.tracking = false;
                if self.speaker_focus {
                    self.speaker_cursor = self.speaker_cursor.saturating_sub(1);
                } else if self.cursor == 0 && !self.turns.speakers.is_empty() {
                    // Up from the first segment reaches the panel, at its bottom row.
                    self.speaker_focus = true;
                    self.speaker_cursor = self.turns.speakers.len() - 1;
                } else {
                    self.cursor = self.cursor.saturating_sub(1);
                }
            }
            Action::SelectDown | Action::SegmentNext => {
                self.tracking = false;
                if self.speaker_focus {
                    if self.speaker_cursor + 1 < self.turns.speakers.len() {
                        self.speaker_cursor += 1;
                    } else {
                        // Down past the last speaker returns to the transcript's first segment.
                        self.speaker_focus = false;
                        self.cursor = 0;
                    }
                } else if self.cursor + 1 < self.segment_count() {
                    self.cursor += 1;
                }
            }
            Action::ScrollPrevious => {
                self.tracking = false;
                self.speaker_focus = false;
                self.cursor = self.cursor.saturating_sub(self.page.get());
            }
            Action::ScrollNext => {
                self.tracking = false;
                self.speaker_focus = false;
                self.cursor =
                    (self.cursor + self.page.get()).min(self.segment_count().saturating_sub(1));
            }
            Action::GotoStart => {
                self.speaker_focus = false;
                self.cursor = 0;
            }
            Action::GotoEnd => {
                self.speaker_focus = false;
                self.cursor = self.segment_count().saturating_sub(1);
            }
            // `r` in a transcript renames the cursor's speaker, starting from its name.
            Action::TaskKey('r') if !self.can_edit_speakers() => {}
            Action::TaskKey('r') => {
                if let Some(selected) = self.turns.speakers.get(self.speaker_index()) {
                    self.rename = Some(selected.speaker.clone());
                }
            }
            // `e` edits the cursor's segment, starting from its text.
            Action::EditSegment => {
                if let Some(segment) = self.current() {
                    self.edit = Some((segment.id, segment.text.clone()));
                }
            }
            // `v` puts whisper's original text back on the cursor's segment, if it was edited;
            // Ctrl-R in the edit prompt does it for the prompt's segment, and the prompt stays open
            // to take that text. A rename prompt has no segment to revert.
            Action::RevertSegment => {
                if self.rename.is_some() {
                    return;
                }
                let in_prompt = self.edit.as_ref().map(|(segment, _)| *segment);
                let segment = match in_prompt {
                    Some(id) => self.segment(id),
                    None => self.current(),
                };
                if let Some(segment) = segment
                    && segment.edited
                {
                    let id = segment.id;
                    let client = service.clone();
                    let task = self.task.id;
                    self.reverted_in_prompt = in_prompt;
                    change_turns(
                        runtime,
                        sender,
                        task,
                        "tui.segment.revertFailed",
                        async move { revert_segment(&client, task, id).await },
                    );
                }
            }
            Action::MarkSegments if !self.can_mark() => {}
            Action::MarkSegments => {
                if let Some(segment) = self.current() {
                    let id = segment.id;
                    if let Some(index) =
                        self.marked_segments.iter().position(|marked| *marked == id)
                    {
                        self.marked_segments.remove(index);
                    } else {
                        self.marked_segments.push(id);
                    }
                }
            }
            // `A` marks every segment of the cursor's turn - the browser's per-turn selection -
            // and unmarks them once they all are.
            Action::MarkTurn if !self.can_mark() => {}
            Action::MarkTurn => {
                let Some((turn, _)) = self.position() else {
                    return;
                };
                let ids: Vec<i64> = self.turns.turns[turn]
                    .segments
                    .iter()
                    .map(|segment| segment.id)
                    .collect();
                self.toggle_marked(&ids);
            }
            Action::AssignNewSpeaker => {
                if self.marking() {
                    self.assign(None, runtime, sender, service);
                }
            }
            Action::OpenSelected => {
                let selected = self
                    .turns
                    .speakers
                    .get(self.speaker_index())
                    .map(|speaker| (speaker.speaker_id, speaker.speaker.clone()));
                // Marked segments: on the panel Enter moves them to its speaker; on the transcript
                // it opens the "Move to" list, as the browser's picker does. Without marks, Enter on
                // the panel renames its speaker.
                match selected {
                    Some((id, _)) if self.marking() && self.speaker_focus => {
                        self.assign(Some(id), runtime, sender, service);
                    }
                    _ if self.marking() => self.picker = Some(0),
                    Some((_, name)) if self.speaker_focus => self.rename = Some(name),
                    _ => {}
                }
            }
            // `R` on the Speakers panel resets the focused speaker's name; on the transcript it
            // resets the task's merges.
            Action::ResetMerges if !self.can_edit_speakers() => {}
            Action::ResetMerges => {
                if self.speaker_focus {
                    if let Some(selected) = self.turns.speakers.get(self.speaker_index()) {
                        let speaker = selected.speaker_id;
                        let default = default_name(speaker);
                        if selected.speaker == default {
                            // Already what a reset would make it: it says so rather than sending a
                            // write that changes nothing.
                            let _ = sender.send(Message::Notice(text(
                                "tui.speakers.nameDefault",
                                &[("name", &default)],
                            )));
                        } else {
                            reset_name(runtime, service, sender, self.task.id, speaker);
                        }
                    }
                } else if self.turns.has_merges {
                    reset(runtime, service, sender, self.task.id);
                } else {
                    // The key also resets a speaker's name on the panel, so silence here read as a
                    // broken reset: it says what it found instead.
                    let _ = sender.send(Message::Notice(text("tui.transcript.noMerges", &[])));
                }
            }
            Action::Merge if !self.can_edit_speakers() => {}
            Action::Merge => {
                let Some(selected) = self.turns.speakers.get(self.speaker_index()) else {
                    return;
                };
                match self.marked {
                    None => self.marked = Some(selected.speaker_id),
                    Some(marked) if marked == selected.speaker_id => self.marked = None,
                    Some(marked) => {
                        // The merge asks first, naming who is folded into whom.
                        self.marked = None;
                        self.pending = Some(Pending::Merge {
                            marked,
                            into: selected.speaker_id,
                        });
                    }
                }
            }
            Action::CancelPrompt => {
                self.rename = None;
                self.edit = None;
                self.reverted_in_prompt = None;
            }
            Action::PromptBackspace => {
                if let Some(text) = self.prompt_mut() {
                    crate::graphemes::pop(text);
                }
            }
            Action::PromptChar(character) => {
                if let Some(text) = self.prompt_mut() {
                    text.push(character);
                }
            }
            Action::SubmitPrompt => {
                if let Some((segment, text)) = self.edit.take() {
                    // A draft that trims to the text already there sends nothing; the prompt just
                    // closes, as an unchanged form does in the browser.
                    if self
                        .segment(segment)
                        .is_some_and(|stored| stored.text == text.trim())
                    {
                        return;
                    }
                    edit(runtime, service, sender, self.task.id, segment, text);
                } else if let Some(name) = self.rename.take()
                    && let Some(selected) = self.turns.speakers.get(self.speaker_index())
                {
                    rename(
                        runtime,
                        service,
                        sender,
                        self.task.id,
                        selected.speaker_id,
                        name,
                    );
                }
            }
            _ => {}
        }
    }
}

/// After a speakers change for task `id`: the answer's speakers relabel the transcript, and the
/// turns are read again (a merge joins them, a reset splits them). A change the service refuses is
/// the notice `failure` names.
fn apply_change<F>(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    failure: &'static str,
    change: F,
) where
    F: std::future::Future<Output = std::io::Result<Vec<Speaker>>> + Send + 'static,
{
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        match change.await {
            Ok(speakers) => {
                let _ = sender.send(Message::Speakers(id, speakers));
            }
            Err(error) => {
                let _ = sender.send(Message::Notice(text(
                    failure,
                    &[("error", &error.to_string())],
                )));
                return;
            }
        }
        let message = match turns(&service, id).await {
            Ok(turns) => Message::Turns(id, turns),
            Err(error) => Message::Notice(text(
                "tui.transcript.loadFailed",
                &[("error", &error.to_string())],
            )),
        };
        let _ = sender.send(message);
    });
}

/// Sends segment `segment`'s new `text` for task `id`; the answer replaces the turns, and a
/// refusal puts `text` back in the prompt, the service's reason in the notice - as the browser
/// keeps an edit form the service turned down.
fn edit(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    segment: i64,
    text: String,
) {
    let client = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match edit_segment(&client, id, segment, &text).await {
            Ok(turns) => Message::Turns(id, turns),
            Err(error) => Message::EditRefused {
                task: id,
                segment,
                draft: text,
                error: error.to_string(),
            },
        };
        let _ = sender.send(message);
    });
}

/// Sends a segment change's answer, which already carries the task's updated turns.
fn change_turns<F>(
    runtime: &tokio::runtime::Handle,
    sender: &mpsc::Sender<Message>,
    id: i64,
    failure: &'static str,
    change: F,
) where
    F: std::future::Future<Output = std::io::Result<Turns>> + Send + 'static,
{
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match change.await {
            Ok(turns) => Message::Turns(id, turns),
            Err(error) => Message::Notice(text(failure, &[("error", &error.to_string())])),
        };
        let _ = sender.send(message);
    });
}

/// Moves task `id`'s `segments` to `speaker` (a new one when `None`).
fn reassign(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    segments: Vec<i64>,
    speaker: Option<i64>,
) {
    let client = service.clone();
    apply_change(
        runtime,
        service,
        sender,
        id,
        "tui.speakers.reassignFailed",
        async move { reassign_segments(&client, id, &segments, speaker).await },
    );
}

/// Resets task `id`'s merges.
fn reset(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
) {
    let client = service.clone();
    apply_change(
        runtime,
        service,
        sender,
        id,
        "tui.speakers.resetFailed",
        async move { reset_merges(&client, id).await },
    );
}

/// Resets speaker `speaker` of task `id` to its default name.
fn reset_name(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    speaker: i64,
) {
    let client = service.clone();
    apply_change(
        runtime,
        service,
        sender,
        id,
        "tui.speakers.resetFailed",
        async move { set_speaker_name(&client, id, speaker, None).await },
    );
}

/// The name the service gives speaker `speaker_id` until it is renamed: `Speaker 1` for id 0.
fn default_name(speaker_id: i64) -> String {
    format!("Speaker {}", speaker_id + 1)
}

/// Merges speaker `marked` of task `id` into speaker `into`.
fn merge(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    marked: i64,
    into: i64,
) {
    let client = service.clone();
    apply_change(
        runtime,
        service,
        sender,
        id,
        "tui.speakers.mergeFailed",
        async move { merge_speakers(&client, id, &[marked, into], into).await },
    );
}

/// Renames speaker `speaker` of task `id` to `name`.
fn rename(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    id: i64,
    speaker: i64,
    name: String,
) {
    let client = service.clone();
    apply_change(
        runtime,
        service,
        sender,
        id,
        "tui.speakers.renameFailed",
        async move { set_speaker_name(&client, id, speaker, Some(&name)).await },
    );
}
