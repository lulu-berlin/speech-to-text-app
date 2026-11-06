//! The settings screen (roadmap item 67): the stored settings one per row, the selected row, and
//! the write that saves a change at once - as the browser's Settings page does (ADR 0062).

use std::sync::mpsc;

use stt_client::{Frontend, Service, Settings, languages, save_settings, text};

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
    DefaultFrontend,
    TerminalCommand,
}

/// The rows, in the order the screen shows them.
pub const FIELDS: [Field; 8] = [
    Field::Device,
    Field::Threads,
    Field::Language,
    Field::Speakers,
    Field::SpeakerCount,
    Field::MaxSpeakers,
    Field::DefaultFrontend,
    Field::TerminalCommand,
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
            Field::DefaultFrontend => "settings.default-frontend",
            Field::TerminalCommand => "settings.terminal-command",
        };
        text(key, &[])
    }

    /// The row's value in the screen's settings (a count row shows the chosen count, saved only
    /// with `fixed`), as the screen shows it.
    pub fn value(self, screen: &SettingsScreen) -> String {
        let settings = &screen.settings;
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
            Field::SpeakerCount => screen
                .count
                .map_or_else(|| "-".to_string(), |n| n.to_string()),
            Field::MaxSpeakers => settings.max_speakers.to_string(),
            Field::DefaultFrontend => settings
                .default_frontend
                .clone()
                .unwrap_or_else(|| "-".to_string()),
            Field::TerminalCommand => settings
                .terminal_command
                .clone()
                .unwrap_or_else(|| "-".to_string()),
        }
    }
}

/// The open settings screen: the settings and the frontends as the service last answered them, the
/// selected row, and the terminal command being typed while its prompt is open.
pub struct SettingsScreen {
    pub settings: Settings,
    pub frontends: Vec<Frontend>,
    pub row: usize,
    terminal: Option<String>,
    /// The speaker count the count row holds; written only while the mode is `fixed`.
    count: Option<u32>,
}

impl SettingsScreen {
    pub fn new(settings: Settings, frontends: Vec<Frontend>) -> SettingsScreen {
        SettingsScreen {
            count: settings.speakers_count,
            settings,
            frontends,
            row: 0,
            terminal: None,
        }
    }

    /// The settings as the service answered a save; the count row follows them.
    pub fn saved(&mut self, settings: Settings) {
        self.count = settings.speakers_count;
        self.settings = settings;
    }

    /// The command in the terminal prompt, while it is open.
    pub fn terminal(&self) -> Option<&str> {
        self.terminal.as_deref()
    }

    /// Applies one key's action: Up/Down select a row, Left/Right change its setting and save the
    /// settings at once, and Enter on the terminal command opens its prompt - whose keys edit, save
    /// or close it.
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
                if FIELDS[self.row] == Field::SpeakerCount {
                    self.count = Some(step_between(self.count.unwrap_or(0), step, 1, 20));
                }
                // `fixed` without a count is `invalid-speakers`: the browser keeps the stored mode,
                // and the screen says why.
                if FIELDS[self.row] == Field::Speakers
                    && step_mode(&self.settings.speakers_mode, step).as_deref() == Some("fixed")
                    && self.count.is_none()
                {
                    let _ = sender.send(Message::Notice(text("settings.count-first", &[])));
                    return;
                }
                if let Some(changed) = self.changed(step) {
                    save(runtime, service, sender, changed);
                }
            }
            Action::OpenSelected if self.row + 1 == FIELDS.len() => {
                self.terminal = Some(self.settings.terminal_command.clone().unwrap_or_default());
            }
            Action::CancelPrompt => self.terminal = None,
            Action::PromptBackspace => {
                if let Some(command) = self.terminal.as_mut() {
                    command.pop();
                }
            }
            Action::PromptChar(character) => {
                if let Some(command) = self.terminal.as_mut() {
                    command.push(character);
                }
            }
            Action::SubmitPrompt => {
                if let Some(command) = self.terminal.take() {
                    let command = command.trim();
                    let mut changed = self.settings.clone();
                    changed.terminal_command = (!command.is_empty()).then(|| command.to_string());
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
                let mode = step_mode(&changed.speakers_mode, step)?;
                // A count belongs to `fixed` only; the browser writes null for the other modes.
                changed.speakers_count = if mode == "fixed" {
                    Some(self.count?)
                } else {
                    None
                };
                changed.speakers_mode = mode;
            }
            Field::SpeakerCount => {
                changed.speakers_count = if changed.speakers_mode == "fixed" {
                    self.count
                } else {
                    None
                };
            }
            Field::MaxSpeakers => {
                changed.max_speakers = step_between(changed.max_speakers, step, 1, 20);
            }
            Field::DefaultFrontend => {
                changed.default_frontend =
                    step_frontend(&self.frontends, &changed.default_frontend, step)?;
            }
            Field::TerminalCommand => return None,
        }
        (changed != self.settings).then_some(changed)
    }
}

/// The speaker mode `step` places on from `mode`: auto, off, fixed, round.
fn step_mode(mode: &str, step: isize) -> Option<String> {
    let modes = ["auto".to_string(), "off".to_string(), "fixed".to_string()];
    step_choice(&modes, mode, step)
}

/// The default frontend `step` places through none and the installed ones, in the registry's order.
fn step_frontend(
    frontends: &[Frontend],
    current: &Option<String>,
    step: isize,
) -> Option<Option<String>> {
    let mut choices: Vec<Option<String>> = vec![None];
    choices.extend(frontends.iter().map(|frontend| Some(frontend.id.clone())));
    let index = choices.iter().position(|choice| *choice == *current)? as isize;
    let next = (index + step).rem_euclid(choices.len() as isize) as usize;
    Some(choices[next].clone())
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
