//! The upload dialog (roadmap item 67, parity amendment): a file browser starting in the current
//! directory (the home directory when that cannot be read), navigable with the arrows or j/k and
//! Enter, and a typed path for anything the browser does not show. Choosing a file hands it to the
//! upload; the option fields come in later cycles.

use std::fs;
use std::path::{Path, PathBuf};

/// The upload dialog as it is shown: the directory it browses, its entries, the selected one, and
/// the path being typed instead (`None` while only browsing).
pub struct UploadDialog {
    dir: PathBuf,
    entries: Vec<PathBuf>,
    selected: usize,
    typed: Option<String>,
}

/// What Enter did.
pub enum Submit {
    /// A file was chosen: upload it.
    Choose(PathBuf),
    /// A directory was entered, or a typed directory accepted: nothing to upload yet.
    Browsing,
}

impl UploadDialog {
    /// Opens in the current directory, or the home directory when the current one cannot be read.
    pub fn open() -> UploadDialog {
        let dir = std::env::current_dir()
            .ok()
            .filter(|dir| fs::read_dir(dir).is_ok())
            .or_else(|| std::env::var_os("HOME").map(PathBuf::from))
            .unwrap_or_else(|| PathBuf::from("/"));
        let mut dialog = UploadDialog {
            dir,
            entries: Vec::new(),
            selected: 0,
            typed: None,
        };
        dialog.reload();
        dialog
    }

    /// The directory the list shows.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// The path being typed, while one is.
    pub fn typed(&self) -> Option<&str> {
        self.typed.as_deref()
    }

    /// The list's entries: directories first, then files, each by name, hidden ones skipped.
    pub fn entries(&self) -> &[PathBuf] {
        &self.entries
    }

    /// The selected entry's index.
    pub fn selected(&self) -> usize {
        self.selected
    }

    /// Moves the selection up, stopping at the first entry.
    pub fn up(&mut self) {
        self.selected = self.selected.saturating_sub(1);
    }

    /// Moves the selection down.
    pub fn down(&mut self) {
        if self.selected + 1 < self.entries.len() {
            self.selected += 1;
        }
    }

    /// Adds a character to the typed path, starting one when none is being typed.
    pub fn push(&mut self, character: char) {
        self.typed.get_or_insert_with(String::new).push(character);
    }

    /// Removes the typed path's last character; an emptied path goes back to browsing.
    pub fn backspace(&mut self) {
        if let Some(typed) = self.typed.as_mut() {
            typed.pop();
            if typed.is_empty() {
                self.typed = None;
            }
        }
    }

    /// Enter: a typed path is resolved (a directory to browse, anything else to upload, so the
    /// upload reports what it is); otherwise the selected entry is entered or chosen.
    pub fn submit(&mut self) -> Submit {
        if let Some(typed) = self.typed.take() {
            let path = PathBuf::from(typed.trim());
            if path.is_dir() {
                self.enter(path);
                return Submit::Browsing;
            }
            return Submit::Choose(path);
        }
        match self.entries.get(self.selected) {
            Some(entry) if entry.is_dir() => {
                let entry = entry.clone();
                self.enter(entry);
                Submit::Browsing
            }
            Some(entry) => Submit::Choose(entry.clone()),
            None => Submit::Browsing,
        }
    }

    /// Browses `dir`: its entries replace the list and the selection returns to the first.
    fn enter(&mut self, dir: PathBuf) {
        self.dir = dir;
        self.reload();
    }

    fn reload(&mut self) {
        let mut entries: Vec<PathBuf> = fs::read_dir(&self.dir)
            .map(|entries| {
                entries
                    .filter_map(Result::ok)
                    .map(|entry| entry.path())
                    .filter(|path| {
                        path.file_name()
                            .and_then(|name| name.to_str())
                            .is_some_and(|name| !name.starts_with('.'))
                    })
                    .collect()
            })
            .unwrap_or_default();
        entries.sort_by(|a, b| {
            b.is_dir()
                .cmp(&a.is_dir())
                .then_with(|| a.file_name().cmp(&b.file_name()))
        });
        self.entries = entries;
        self.selected = 0;
    }
}
