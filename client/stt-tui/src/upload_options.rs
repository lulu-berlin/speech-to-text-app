//! The upload dialog's option fields (roadmap item 67, parity amendment): the GUI upload panel's
//! language and speakers choice, starting from the stored settings and kept within the contract's
//! ranges. The speakers choice carries its count: a fixed count is also the upload's maximum, and
//! only an automatic one uses the stored maximum, as the user decided (2026-11-14: there is no
//! point in a separate maximum when speakers are automatic).

use stt_client::{Settings, Speakers, UploadOptions, text};

/// The dialog's rows: the file list, or one of the upload options.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Field {
    Browser,
    Language,
    Speakers,
}

impl Field {
    /// The row's name, from the shared catalog - the same keys the browser's upload panel uses.
    pub fn label(self) -> String {
        let key = match self {
            Field::Browser => "queue.uploadOptions",
            Field::Language => "queue.language",
            Field::Speakers => "queue.speakers",
        };
        text(key, &[])
    }
}

/// The upload's options as the dialog holds them.
pub struct Options {
    language: String,
    speakers: Speakers,
    /// The stored maximum: what an automatic or off choice sends, a fixed count being its own.
    max_speakers: u32,
}

impl Options {
    /// The values before the settings arrive: what the contract defaults to.
    pub fn new() -> Options {
        Options {
            language: "auto".to_string(),
            speakers: Speakers::Auto,
            max_speakers: 8,
        }
    }

    /// The stored settings become the starting values, as the GUI's panel does.
    pub fn defaults(&mut self, settings: &Settings) {
        self.language = settings.default_language.clone();
        self.speakers = match settings.speakers_mode.as_str() {
            "off" => Speakers::Off,
            "fixed" => Speakers::Count(settings.speakers_count.unwrap_or(3).clamp(1, 20)),
            _ => Speakers::Auto,
        };
        self.max_speakers = settings.max_speakers.clamp(1, 20);
    }

    /// The row's value as the dialog shows it.
    pub fn value(&self, field: Field) -> String {
        match field {
            Field::Browser => String::new(),
            Field::Language => self.language.clone(),
            Field::Speakers => match self.speakers {
                Speakers::Auto => "auto".to_string(),
                Speakers::Off => "off".to_string(),
                Speakers::Count(count) => count.to_string(),
            },
        }
    }

    /// The contract's upload options, for the request: a fixed count is the maximum; otherwise the
    /// stored maximum caps an automatic (or off) choice.
    pub fn to_request(&self) -> UploadOptions {
        UploadOptions {
            language: self.language.clone(),
            speakers: self.speakers,
            max_speakers: match self.speakers {
                Speakers::Count(count) => count,
                Speakers::Auto | Speakers::Off => self.max_speakers,
            },
        }
    }
}

/// A choice in a field's list: what the list shows and what choosing it sets.
#[derive(Clone)]
pub struct Choice {
    pub label: String,
    pub value: String,
}

impl Options {
    /// The field's choices, as its list shows them - the language catalogue's codes and names, or
    /// auto, off and the counts 1..=20; the browser has no list.
    pub fn choices(&self, field: Field) -> Vec<Choice> {
        match field {
            Field::Browser => Vec::new(),
            Field::Language => stt_client::languages()
                .unwrap_or_default()
                .into_iter()
                .map(|language| Choice {
                    label: format!("{}  {}", language.code, language.name),
                    value: language.code,
                })
                .collect(),
            Field::Speakers => ["auto", "off"]
                .into_iter()
                .map(String::from)
                .chain((1..=MAX_SPEAKERS).map(|count| count.to_string()))
                .map(|value| Choice {
                    label: value.clone(),
                    value,
                })
                .collect(),
        }
    }

    /// Sets `field` to `value`: a language code, or auto, off or a speakers count.
    pub fn choose(&mut self, field: Field, value: &str) {
        match field {
            Field::Browser => {}
            Field::Language => self.language = value.to_string(),
            Field::Speakers => {
                self.speakers = match value {
                    "auto" => Speakers::Auto,
                    "off" => Speakers::Off,
                    _ => value
                        .parse::<u32>()
                        .map(|count| Speakers::Count(count.clamp(1, MAX_SPEAKERS)))
                        .unwrap_or(Speakers::Auto),
                };
            }
        }
    }
}

/// The most speakers the contract takes.
const MAX_SPEAKERS: u32 = 20;
