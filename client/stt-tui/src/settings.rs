//! The settings screen (roadmap item 67): the stored settings one per row, the selected row, and
//! the write that saves a change at once - as the browser's Settings page does (ADR 0062).

use std::sync::mpsc;

use stt_client::{Service, Settings, languages, save_settings, text};

use crate::Message;
use crate::input::Action;

/// One row of the settings screen.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Field {
    Device,
    Threads,
    Language,
    Speakers,
    SpeakerCount,
    MaxSpeakers,
}

/// The rows, in the order the screen shows them.
pub const FIELDS: [Field; 6] = [
    Field::Device,
    Field::Threads,
    Field::Language,
    Field::Speakers,
    Field::SpeakerCount,
    Field::MaxSpeakers,
];

impl Field {
    /// The row's name, from the shared catalog.
    pub fn label(self) -> String {
        let key = match self {
            Field::Device => "settings.device",
            Field::Threads => "settings.threads",
            Field::Language => "settings.language",
            Field::Speakers => "settings.speakers",
            Field::SpeakerCount => "settings.speaker-count",
            Field::MaxSpeakers => "settings.max-speakers",
        };
        text(key, &[])
    }

    /// The row's value in `settings`, as the screen shows it.
    pub fn value(self, settings: &Settings) -> String {
        match self {
            Field::Device => match settings.gpu_names.get(&settings.device) {
                Some(name) => format!("{} ({name})", settings.device),
                None => settings.device.clone(),
            },
            Field::Threads => settings
                .threads
                .map_or_else(|| text("settings.threads-auto", &[]), |n| n.to_string()),
            Field::Language => settings.default_language.clone(),
            Field::Speakers => settings.speakers_mode.clone(),
            Field::SpeakerCount => settings
                .speakers_count
                .map_or_else(|| "-".to_string(), |n| n.to_string()),
            Field::MaxSpeakers => settings.max_speakers.to_string(),
        }
    }
}

/// The open settings screen: the settings as the service last answered them, and the selected row.
pub struct SettingsScreen {
    pub settings: Settings,
    pub row: usize,
}

impl SettingsScreen {
    pub fn new(settings: Settings) -> SettingsScreen {
        SettingsScreen { settings, row: 0 }
    }

    /// Applies one key's action: Up/Down select a row, Left/Right change its setting and save the
    /// settings at once.
    pub fn handle(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        match action {
            Action::SelectUp => self.row = self.row.saturating_sub(1),
            Action::SelectDown => {
                if self.row + 1 < FIELDS.len() {
                    self.row += 1;
                }
            }
            Action::Adjust(step) => {
                if let Some(changed) = self.changed(step) {
                    save(runtime, service, sender, changed);
                }
            }
            _ => {}
        }
    }

    /// The settings with the selected row's setting moved `step` places, if it can move.
    fn changed(&self, step: isize) -> Option<Settings> {
        let mut changed = self.settings.clone();
        match FIELDS[self.row] {
            Field::Device => {
                changed.device = step_choice(&changed.available_devices, &changed.device, step)?;
            }
            Field::Threads => changed.threads = step_threads(changed.threads, step),
            Field::Language => {
                let codes: Vec<String> = languages()
                    .ok()?
                    .into_iter()
                    .map(|language| language.code)
                    .collect();
                changed.default_language = step_choice(&codes, &changed.default_language, step)?;
            }
            Field::Speakers => {
                let modes = ["auto".to_string(), "off".to_string(), "fixed".to_string()];
                let mode = step_choice(&modes, &changed.speakers_mode, step)?;
                // A count belongs to `fixed` only; the browser writes null for the other modes.
                if mode != "fixed" {
                    changed.speakers_count = None;
                }
                changed.speakers_mode = mode;
            }
            Field::SpeakerCount => {
                if changed.speakers_mode != "fixed" {
                    return None;
                }
                changed.speakers_count = Some(step_between(
                    changed.speakers_count.unwrap_or(0),
                    step,
                    1,
                    20,
                ));
            }
            Field::MaxSpeakers => {
                changed.max_speakers = step_between(changed.max_speakers, step, 1, 20);
            }
        }
        Some(changed)
    }
}

/// `current` moved `step` places through `choices`, wrapping round; `None` when `current` is not
/// one of them (the service offers a list the stored value fell out of).
fn step_choice(choices: &[String], current: &str, step: isize) -> Option<String> {
    let index = choices.iter().position(|choice| choice == current)? as isize;
    let next = (index + step).rem_euclid(choices.len() as isize) as usize;
    Some(choices[next].clone())
}

/// `value` moved `step` places, kept within `low..=high`.
fn step_between(value: u32, step: isize, low: u32, high: u32) -> u32 {
    let next = i64::from(value) + step as i64;
    next.clamp(i64::from(low), i64::from(high)) as u32
}

/// The thread count `step` places on from `threads`, `None` (auto) sitting below 1.
fn step_threads(threads: Option<u32>, step: isize) -> Option<u32> {
    let current = threads.map_or(0, i64::from);
    let next = current + step as i64;
    if next <= 0 {
        None
    } else {
        u32::try_from(next).ok().or(threads)
    }
}

/// Saves `settings` whole; the service's answer is what the screen shows, a refusal the notice.
fn save(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    settings: Settings,
) {
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match save_settings(&service, &settings).await {
            Ok(saved) => Message::SettingsSaved(saved),
            Err(error) => Message::Notice(text(
                "settings.save-failed",
                &[("error", &error.to_string())],
            )),
        };
        let _ = sender.send(message);
    });
}
