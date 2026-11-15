//! The folder browser both file dialogs share (roadmap item 67): the folder shown, its entries -
//! folders before files, each by name, hidden ones skipped - the selected row, the path being
//! typed instead, and what the dialog last said about a typed path.

use std::fs;
use std::path::{Path, PathBuf};

use stt_client::allowed_extension;

pub struct Browser {
    dir: PathBuf,
    entries: Vec<PathBuf>,
    selected: usize,
    typed: Option<String>,
    error: Option<String>,
}

impl Browser {
    /// Opens in the current folder, or in the home folder when that one cannot be read.
    pub fn open() -> Browser {
        let dir = std::env::current_dir()
            .ok()
            .filter(|dir| fs::read_dir(dir).is_ok())
            .or_else(|| std::env::var_os("HOME").map(PathBuf::from))
            .unwrap_or_else(|| PathBuf::from("/"));
        let mut browser = Browser {
            dir,
            entries: Vec::new(),
            selected: 0,
            typed: None,
            error: None,
        };
        browser.reload();
        browser
    }

    /// The folder the list shows.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// The path being typed, while one is.
    pub fn typed(&self) -> Option<&str> {
        self.typed.as_deref()
    }

    /// Sets the typed path, as a pasted file or a chosen row's name does.
    pub fn set_typed(&mut self, typed: String) {
        self.typed = Some(typed);
    }

    /// Takes the typed path, leaving the browser browsing.
    pub fn take_typed(&mut self) -> Option<String> {
        self.typed.take()
    }

    /// What the dialog says about the last typed path, while it stands.
    pub fn error(&self) -> Option<&str> {
        self.error.as_deref()
    }

    /// Says `error` about the typed path until the next key; `None` clears it.
    pub fn set_error(&mut self, error: Option<String>) {
        self.error = error;
    }

    /// The selected row's index, the `../` row counting as the first.
    pub fn selected(&self) -> usize {
        self.selected
    }

    /// The list as it is shown: `None` for the `../` row (first, when the folder has a parent),
    /// then the entries.
    pub fn rows(&self) -> impl Iterator<Item = Option<&PathBuf>> {
        self.parent()
            .map(|_| None)
            .into_iter()
            .chain(self.entries.iter().map(Some))
    }

    /// The selected row: `None` for `../`, else its entry; outside the list, nothing.
    pub fn selected_row(&self) -> Option<Option<PathBuf>> {
        self.rows().nth(self.selected).map(|row| row.cloned())
    }

    /// The folder above the shown one, when there is one: the root has none.
    pub fn parent(&self) -> Option<&Path> {
        self.dir.parent()
    }

    /// Whether the selected row is a folder Right or Enter opens - the `../` row included.
    pub fn selects_folder(&self) -> bool {
        match self.rows().nth(self.selected) {
            Some(None) => true,
            Some(Some(entry)) => entry.is_dir(),
            None => false,
        }
    }

    /// Enters the selected row when it is a folder - `../` goes up - and says whether it did.
    pub fn enter_selected(&mut self) -> bool {
        match self.selected_row() {
            Some(None) => {
                self.go_up();
                true
            }
            Some(Some(entry)) if entry.is_dir() => {
                self.enter(entry);
                true
            }
            _ => false,
        }
    }

    /// Browses the folder above the shown one; at the root nothing changes.
    pub fn go_up(&mut self) {
        if let Some(parent) = self.dir.parent() {
            let parent = parent.to_path_buf();
            self.enter(parent);
        }
    }

    /// Browses `dir`: its entries replace the list and the selection returns to the first.
    pub fn enter(&mut self, dir: PathBuf) {
        self.dir = dir;
        self.reload();
    }

    /// Selects row `index` when the keyboard can land on it; false for a non-audio file.
    pub fn select(&mut self, index: usize) -> bool {
        if self.selectable(index) {
            self.selected = index;
            true
        } else {
            false
        }
    }

    /// Moves the selection up, stopping at the first row it can land on.
    pub fn up(&mut self) {
        if let Some(index) = (0..self.selected)
            .rev()
            .find(|index| self.selectable(*index))
        {
            self.selected = index;
        }
    }

    /// Moves the selection down, stopping at the last row it can land on.
    pub fn down(&mut self) {
        let next = (self.selected + 1..self.rows().count()).find(|index| self.selectable(*index));
        if let Some(index) = next {
            self.selected = index;
        }
    }

    /// Whether the keyboard can land on row `index`: `../` and folders always, a file when the
    /// service takes its extension - a non-audio file is shown greyed and stepped over.
    fn selectable(&self, index: usize) -> bool {
        match self.rows().nth(index) {
            None | Some(None) => true,
            Some(Some(entry)) => entry.is_dir() || allowed_extension(entry),
        }
    }

    /// The first row the keyboard can land on: the list's first selectable, or 0 for an empty one.
    fn first_selectable(&self) -> usize {
        (0..self.rows().count())
            .find(|index| self.selectable(*index))
            .unwrap_or(0)
    }

    /// `typed` completed against the folder it names, for Tab: the entries starting with its last
    /// component extend it to their longest common prefix - a single match whole, a folder with a
    /// `/` after it. `None` when nothing matches or nothing can be added.
    pub fn complete(&self, typed: &str) -> Option<String> {
        let (head, stem) = match typed.rfind('/') {
            Some(at) => typed.split_at(at + 1),
            None => ("", typed),
        };
        let dir = if head.is_empty() {
            self.dir.clone()
        } else {
            self.resolve(head)
        };
        let mut names: Vec<String> = fs::read_dir(&dir)
            .ok()?
            .filter_map(Result::ok)
            .filter_map(|entry| entry.file_name().to_str().map(str::to_string))
            .filter(|name| !name.starts_with('.') && name.starts_with(stem))
            .collect();
        names.sort();
        let mut prefix = names.first()?.clone();
        for name in &names {
            prefix = prefix
                .chars()
                .zip(name.chars())
                .take_while(|(a, b)| a == b)
                .map(|(a, _)| a)
                .collect();
        }
        if prefix.chars().count() <= stem.chars().count() {
            return None;
        }
        let mut completed = format!("{head}{prefix}");
        if names.len() == 1 && dir.join(&prefix).is_dir() {
            completed.push('/');
        }
        Some(completed)
    }

    /// The typed path completed in place, as Tab does; nothing when it cannot be extended.
    pub fn complete_typed(&mut self) {
        let Some(typed) = self.typed.clone() else {
            return;
        };
        if let Some(completed) = self.complete(&typed) {
            self.typed = Some(completed);
        }
    }

    /// Adds a character to the typed path, starting one when none is being typed.
    pub fn push(&mut self, character: char) {
        self.error = None;
        self.typed.get_or_insert_with(String::new).push(character);
    }

    /// Removes the typed path's last character; an emptied path goes back to browsing. With no
    /// path typed, Backspace goes up a folder.
    pub fn backspace(&mut self) {
        self.error = None;
        match self.typed.as_mut() {
            Some(typed) => {
                typed.pop();
                if typed.is_empty() {
                    self.typed = None;
                }
            }
            None => self.go_up(),
        }
    }

    /// `typed` as a path: `~` and `~/…` under the home folder, an absolute path as it is, anything
    /// else under the folder the browser shows.
    pub fn resolve(&self, typed: &str) -> PathBuf {
        let home = std::env::var_os("HOME").map(PathBuf::from);
        match (typed.strip_prefix('~'), home) {
            (Some(""), Some(home)) => home,
            (Some(rest), Some(home)) if rest.starts_with('/') => home.join(&rest[1..]),
            _ => self.dir.join(typed),
        }
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
        self.selected = self.first_selectable();
    }
}
