//! The upload dialog (roadmap item 67, parity amendment): a file browser starting in the current
//! directory (the home directory when that cannot be read), navigable with the arrows or j/k and
//! Enter, and a typed path for anything the browser does not show. Choosing a file hands it to the
//! upload; the option fields come in later cycles.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;

use stt_client::{Service, Settings, UploadOptions, allowed_extension, text, upload_with_progress};

use crate::Message;
use crate::browser::Browser;
use crate::input::Action;
use crate::upload_options::{Choice, Field, Options};

/// The upload dialog as it is shown: the folder browser - with the path being typed instead and
/// the last path it could not find - the upload options, and which row has the focus.
pub struct UploadDialog {
    browser: Browser,
    options: Options,
    focus: Field,
    /// The option chip the keyboard returns to when Tab brings it back from the files.
    option: Field,
    /// The files marked to upload together, in the order they were marked.
    marked: Vec<PathBuf>,
    /// The list an option's chip opened, while one is.
    chooser: Option<Chooser>,
}

/// The list an option's chip opened: its field, its choices, the filter typing narrows them with -
/// the language catalogue is long - and the row the keyboard is on.
pub struct Chooser {
    field: Field,
    choices: Vec<Choice>,
    /// The row under the cursor, among the shown ones.
    cursor: usize,
    /// What is typed to narrow the list.
    filter: String,
    /// The indexes in `choices` the filter lets through.
    shown: Vec<usize>,
}

impl Chooser {
    fn new(field: Field, choices: Vec<Choice>, current: &str) -> Chooser {
        let mut chooser = Chooser {
            field,
            choices,
            cursor: 0,
            filter: String::new(),
            shown: Vec::new(),
        };
        chooser.refilter();
        chooser.cursor = chooser
            .shown
            .iter()
            .position(|&index| chooser.choices[index].value == current)
            .unwrap_or(0);
        chooser
    }

    /// The field the list belongs to.
    pub fn field(&self) -> Field {
        self.field
    }

    /// The choices the filter lets through, in order.
    pub fn rows(&self) -> impl Iterator<Item = &Choice> {
        self.shown
            .iter()
            .filter_map(|&index| self.choices.get(index))
    }

    /// The row the keyboard is on, among the shown ones.
    pub fn cursor(&self) -> usize {
        self.cursor
    }

    /// What is typed to narrow the list, while the list narrows at all.
    pub fn filter(&self) -> Option<&str> {
        self.filtering().then_some(self.filter.as_str())
    }

    /// Whether typing narrows this list: the language catalogue does, the counts do not.
    fn filtering(&self) -> bool {
        self.field == Field::Language
    }

    /// Rebuilds the shown choices after the filter changed: the labels holding what is typed,
    /// case-insensitively, as History's search matches file names.
    fn refilter(&mut self) {
        let needle = self.filter.to_lowercase();
        self.shown = (0..self.choices.len())
            .filter(|&index| {
                !self.filtering() || self.choices[index].label.to_lowercase().contains(&needle)
            })
            .collect();
        self.cursor = 0;
    }

    /// Adds a character to the filter, as typing does.
    fn push(&mut self, character: char) {
        self.filter.push(character);
        self.refilter();
    }

    /// Takes the filter's last character back, as Backspace does.
    fn pop(&mut self) {
        self.filter.pop();
        self.refilter();
    }
}

/// What Enter did.
pub enum Submit {
    /// A file was chosen: upload it.
    Choose(PathBuf),
    /// These files were marked, in that order: upload each in turn.
    Batch(Vec<PathBuf>),
    /// A directory was entered, or a typed directory accepted: nothing to upload yet.
    Browsing,
}

/// The existing files a pasted text names: tokens split at unquoted spaces, with single or double
/// quotes and a backslash understood, and `file://` URIs decoded - a terminal's drag-and-drop.
pub fn pasted_paths(text: &str) -> Vec<std::path::PathBuf> {
    let mut tokens = Vec::new();
    let mut token = String::new();
    let mut quoted: Option<char> = None;
    let mut escaped = false;
    for character in text.chars() {
        if escaped {
            token.push(character);
            escaped = false;
            continue;
        }
        match character {
            '\\' => escaped = true,
            '"' | '\'' if quoted == Some(character) => quoted = None,
            '"' | '\'' if quoted.is_none() => quoted = Some(character),
            _ if character.is_whitespace() && quoted.is_none() => {
                if !token.is_empty() {
                    tokens.push(std::mem::take(&mut token));
                }
            }
            _ => token.push(character),
        }
    }
    if !token.is_empty() {
        tokens.push(token);
    }
    tokens
        .into_iter()
        .filter_map(|token| {
            let path = match token.strip_prefix("file://") {
                Some(rest) => {
                    let rest = rest.strip_prefix("localhost").unwrap_or(rest);
                    percent_decoded(rest)
                }
                None => std::path::PathBuf::from(token),
            };
            path.is_file().then_some(path)
        })
        .collect()
}

/// `text` with `%XX` escapes decoded, as `file://` URIs carry them.
fn percent_decoded(text: &str) -> std::path::PathBuf {
    let bytes = text.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        let escape = bytes.get(index + 1..index + 3).and_then(|hex| {
            std::str::from_utf8(hex)
                .ok()
                .and_then(|hex| u8::from_str_radix(hex, 16).ok())
        });
        match (bytes[index], escape) {
            (b'%', Some(byte)) => {
                decoded.push(byte);
                index += 3;
            }
            (byte, _) => {
                decoded.push(byte);
                index += 1;
            }
        }
    }
    std::path::PathBuf::from(String::from_utf8_lossy(&decoded).into_owned())
}

impl UploadDialog {
    /// Opens with `path` typed, in the directory it is in when that can be read.
    pub fn open_with(path: &std::path::Path) -> UploadDialog {
        let mut dialog = UploadDialog::open();
        dialog.marked.clear();
        if let Some(dir) = path.parent().filter(|dir| dir.is_dir()) {
            dialog.browser.enter(dir.to_path_buf());
        }
        dialog
            .browser
            .set_typed(path.to_string_lossy().into_owned());
        dialog
    }

    /// Opens in the current directory, or the home directory when the current one cannot be read.
    pub fn open() -> UploadDialog {
        UploadDialog {
            browser: Browser::open(),
            options: Options::new(),
            focus: Field::Browser,
            option: Field::Language,
            marked: Vec::new(),
            chooser: None,
        }
    }

    /// The folder browser: the folder, its rows, the selection and the typed path.
    pub fn browser(&self) -> &Browser {
        &self.browser
    }

    /// The row that has the focus.
    pub fn focus(&self) -> Field {
        self.focus
    }

    /// The list an option's chip opened, while one is.
    pub fn chooser(&self) -> Option<&Chooser> {
        self.chooser.as_ref()
    }

    /// Opens `field`'s list at its current value: what Enter on a chip does.
    pub fn open_chooser(&mut self, field: Field) {
        let choices = self.options.choices(field);
        if field == Field::Browser || choices.is_empty() {
            return;
        }
        let value = self.options.value(field);
        self.focus = field;
        self.option = field;
        self.chooser = Some(Chooser::new(field, choices, &value));
    }

    /// Closes the list without choosing, as Escape does.
    pub fn close_chooser(&mut self) {
        self.chooser = None;
    }

    /// Chooses the list's `index`th shown row and closes it, as Enter or a click does.
    pub fn choose(&mut self, index: usize) {
        let Some(chooser) = self.chooser.take() else {
            return;
        };
        let choice = chooser
            .shown
            .get(index)
            .and_then(|&at| chooser.choices.get(at));
        if let Some(choice) = choice {
            self.options.choose(chooser.field, &choice.value);
        }
    }

    /// Moves the list's cursor by `step`, kept within the list.
    fn step_chooser(&mut self, step: isize) {
        let Some(chooser) = self.chooser.as_mut() else {
            return;
        };
        let last = chooser.shown.len().saturating_sub(1) as isize;
        chooser.cursor = (chooser.cursor as isize + step).clamp(0, last) as usize;
    }

    /// Enters the selected folder, or goes up on `../`, as Enter on a folder does.
    pub fn enter_selected(&mut self) -> bool {
        self.browser.enter_selected()
    }

    /// A click on a row's mark: selects the row and ticks or unticks its file, as Space does.
    pub fn click_mark(&mut self, index: usize) {
        if self.select(index) {
            self.toggle_mark();
        }
    }

    /// Selects the browser's `index`th row, as a click on it does; false when the row cannot be
    /// landed on (a non-audio file).
    pub fn select(&mut self, index: usize) -> bool {
        let landed = self.browser.select(index);
        if landed {
            self.focus = Field::Browser;
        }
        landed
    }

    /// The stored settings become the options' starting values, as the GUI's panel does. A later
    /// edit by the user wins: this only fills what the user has not changed.
    pub fn defaults(&mut self, settings: &Settings) {
        self.options.defaults(settings);
    }

    /// The row's value as the dialog shows it.
    pub fn value(&self, field: Field) -> String {
        self.options.value(field)
    }

    /// Tab and Shift-Tab: between the files and the settings area, returning to the chip last
    /// focused.
    fn switch_area(&mut self) {
        self.focus = if self.focus == Field::Browser {
            self.option
        } else {
            Field::Browser
        };
    }

    /// Left/Right on the focused option; on the file list Left goes up a folder and Right opens the
    /// selected one - a file is left to Enter, which uploads it.
    fn adjust(&mut self, step: isize) {
        if self.focus == Field::Browser {
            if step < 0 {
                self.browser.go_up();
            } else {
                self.browser.enter_selected();
            }
            return;
        }
        // The settings area's left and right pick the chip; the value changes only through its
        // list.
        self.focus = match self.focus {
            Field::Language => Field::Speakers,
            _ => Field::Language,
        };
        self.option = self.focus;
    }

    /// The audio file the selected row names, when it names one: a non-audio file is not pickable.
    fn selected_file(&self) -> Option<PathBuf> {
        self.browser
            .selected_row()
            .flatten()
            .filter(|entry| !entry.is_dir() && allowed_extension(entry))
    }

    /// Whether Enter sends a file now rather than opening a folder: the write a dropped stream
    /// refuses.
    pub fn enter_uploads(&self) -> bool {
        if self.focus != Field::Browser || self.chooser.is_some() {
            return false;
        }
        if !self.marked.is_empty() {
            return true;
        }
        match self.browser.typed() {
            Some(typed) => !self.browser.resolve(typed.trim()).is_dir(),
            None => self.selected_file().is_some(),
        }
    }

    /// Applies one dialog key; true when the dialog closes (cancelled, or a chosen file sent).
    pub fn handle(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) -> bool {
        // An open list takes the keys: the arrows move it, Enter chooses, Escape closes it alone.
        if self.chooser.is_some() {
            match action {
                // The language list narrows as letters are typed, and Backspace widens it again.
                Action::PromptChar(character)
                    if self.chooser.as_ref().is_some_and(Chooser::filtering) =>
                {
                    if let Some(chooser) = self.chooser.as_mut() {
                        chooser.push(character);
                    }
                }
                Action::PromptBackspace
                    if self.chooser.as_ref().is_some_and(Chooser::filtering) =>
                {
                    if let Some(chooser) = self.chooser.as_mut() {
                        chooser.pop();
                    }
                }
                Action::SelectUp => self.step_chooser(-1),
                Action::SelectDown => self.step_chooser(1),
                // Left and right move between the two option lists without choosing anything.
                Action::Adjust(_) => {
                    let other = match self.chooser.as_ref().map(Chooser::field) {
                        Some(Field::Language) => Field::Speakers,
                        _ => Field::Language,
                    };
                    self.open_chooser(other);
                }
                Action::SubmitPrompt => {
                    let cursor = self.chooser.as_ref().map_or(0, |chooser| chooser.cursor());
                    self.choose(cursor);
                }
                Action::CancelPrompt => self.close_chooser(),
                _ => {}
            }
            return false;
        }
        // Enter on a chip opens its list, as its `▾` says - unless a path is typed, which Enter
        // sends whatever row has the focus.
        if matches!(action, Action::SubmitPrompt)
            && self.focus != Field::Browser
            && self.browser.typed().is_none()
        {
            self.open_chooser(self.focus);
            return false;
        }
        match action {
            Action::CancelPrompt => return true,
            // A typed path takes Tab as completion; otherwise Tab moves between the files and the
            // settings, and left/right pick a chip inside the settings.
            Action::FieldNext => {
                if self.browser.typed().is_some() {
                    self.browser.complete_typed();
                } else {
                    self.switch_area();
                }
            }
            Action::FieldPrev => {
                if self.browser.typed().is_none() {
                    self.switch_area();
                }
            }
            Action::Adjust(step) => self.adjust(step),
            // Up from a chip goes back to the files; down opens the chip's list.
            Action::SelectUp if self.focus != Field::Browser => self.focus = Field::Browser,
            Action::SelectDown if self.focus != Field::Browser => self.open_chooser(self.focus),
            Action::SubmitPrompt => match self.submit() {
                Submit::Batch(paths) => {
                    // One uploader sends them in turn, so the queue takes them in order; a file the
                    // service would refuse by extension is left out with its own notice.
                    let options = self.options.to_request();
                    runtime.spawn(send_in_turn(
                        paths,
                        options,
                        service.clone(),
                        sender.clone(),
                    ));
                    return true;
                }
                Submit::Choose(path) => {
                    // A file the service would refuse by extension is not sent at all: the dialog
                    // says so and stays open, so another entry can be chosen.
                    if !allowed_extension(&path) {
                        let _ = sender.send(Message::Notice(text(
                            "tui.upload.unsupported",
                            &[("path", &path.display().to_string())],
                        )));
                        return false;
                    }
                    let options = self.options.to_request();
                    runtime.spawn(send_in_turn(
                        vec![path],
                        options,
                        service.clone(),
                        sender.clone(),
                    ));
                    return true;
                }
                Submit::Browsing => {}
            },
            Action::PromptBackspace => self.browser.backspace(),
            // Space marks the selected file for a batch while no path is being typed; otherwise it
            // is a space in what is typed, as a path may hold one.
            Action::PromptChar(' ')
                if self.browser.typed().is_none() && self.focus == Field::Browser =>
            {
                self.toggle_mark();
            }
            Action::PromptChar(character) => self.browser.push(character),
            Action::SelectUp => self.browser.up(),
            Action::SelectDown => self.browser.down(),
            _ => {}
        }
        false
    }

    /// Whether Space marks the selected row: nothing typed, the file list focused, a file on it.
    pub fn can_mark(&self) -> bool {
        self.browser.typed().is_none()
            && self.focus == Field::Browser
            && self.selected_file().is_some()
    }

    /// Whether a file of the list is marked.
    pub fn is_marked(&self, file: &std::path::Path) -> bool {
        self.marked.iter().any(|marked| marked == file)
    }

    /// How many files are marked for the batch.
    pub fn marked_count(&self) -> usize {
        self.marked.len()
    }

    /// Marks the selected file, or unmarks it; a folder and `../` are not files to upload.
    fn toggle_mark(&mut self) {
        let Some(file) = self.selected_file() else {
            return;
        };
        match self.marked.iter().position(|marked| *marked == file) {
            Some(index) => {
                self.marked.remove(index);
            }
            None => self.marked.push(file),
        }
    }

    /// Enter: a typed path is resolved against the shown folder (`~` is the home folder) - a folder
    /// is browsed, an existing file chosen, and a path that does not exist reported in the dialog,
    /// which stays open; otherwise the selected row is followed: `../` goes up, a directory is
    /// entered, a file is chosen.
    pub fn submit(&mut self) -> Submit {
        self.browser.set_error(None);
        if let Some(typed) = self.browser.take_typed() {
            let path = self.browser.resolve(typed.trim());
            if path.is_dir() {
                self.browser.enter(path);
                return Submit::Browsing;
            }
            if !path.exists() {
                self.browser.set_error(Some(text(
                    "tui.upload.missing",
                    &[("path", &path.display().to_string())],
                )));
                return Submit::Browsing;
            }
            return Submit::Choose(path);
        }
        if !self.marked.is_empty() {
            return Submit::Batch(std::mem::take(&mut self.marked));
        }
        if self.browser.enter_selected() {
            return Submit::Browsing;
        }
        match self.selected_file() {
            Some(entry) => Submit::Choose(entry),
            None => Submit::Browsing,
        }
    }
}

/// Numbers the uploads this process sends, so two files of the same name never share a line.
static NEXT_UPLOAD: AtomicU64 = AtomicU64::new(1);

/// Sends `paths` one after another with `options`, so the queue takes them in order. Each file
/// reports itself while it goes - a line with the bytes sent of its length, which goes when its
/// answer is in - and then its notice; a file the service would refuse by extension is left out
/// with its own notice.
pub(crate) async fn send_in_turn(
    paths: Vec<PathBuf>,
    options: UploadOptions,
    service: Service,
    sender: mpsc::Sender<Message>,
) {
    let mut pending = paths.into_iter();
    while let Some(path) = pending.next() {
        if !allowed_extension(&path) {
            let _ = sender.send(Message::Notice(text(
                "tui.upload.unsupported",
                &[("path", &path.display().to_string())],
            )));
            continue;
        }
        let name = path
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_default();
        let id = NEXT_UPLOAD.fetch_add(1, Ordering::Relaxed);
        let progress = sender.clone();
        let going = name.clone();
        let result = upload_with_progress(&service, &path, &options, move |sent, total| {
            let _ = progress.send(Message::UploadProgress {
                id,
                filename: going.clone(),
                sent,
                total,
            });
        })
        .await;
        if let Err(error) = &result
            && error.lost()
        {
            // No answer came: this file and the ones behind it wait for the connection, and the
            // Ui sends them again once a snapshot says it is back.
            let files: Vec<PathBuf> = std::iter::once(path).chain(pending).collect();
            let _ = sender.send(Message::UploadHeld {
                files,
                options,
                name,
            });
            return;
        }
        // The file's line goes before what the service answered shows, so no frame says the file
        // is still on its way beside that answer.
        let _ = sender.send(Message::UploadDone(id));
        // A file the service already has is offered as the task it is; any other refusal is its
        // error.
        let _ = sender.send(match result {
            Ok(task) => Message::Notice(text("tui.upload.queued", &[("filename", &task.filename)])),
            Err(error) => match error.duplicate() {
                Some(existing) => Message::Duplicate {
                    task: existing,
                    filename: name,
                },
                None => {
                    Message::Notice(text("tui.upload.failed", &[("error", &error.to_string())]))
                }
            },
        });
    }
}
