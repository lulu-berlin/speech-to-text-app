//! The settings screen (roadmap item 67): the stored settings one per row, the selected row, and
//! the write that saves a change at once - as the browser's Settings page does (ADR 0062).

use std::sync::mpsc;

use stt_client::{
    DefaultTerminal, Frontend, Os, Service, Settings, restore_settings, save_settings, text,
};

use crate::Message;
use crate::choices::{language_codes, step_choice};
use crate::input::{Action, Mode};

/// One row of the settings screen.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Field {
    Device,
    Threads,
    Language,
    Speakers,
    DefaultFrontend,
    TerminalCommand,
}

/// The rows, in the order the screen shows them.
pub const FIELDS: [Field; 6] = [
    Field::Device,
    Field::Threads,
    Field::Language,
    Field::Speakers,
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
            Field::DefaultFrontend => "settings.defaultFrontend",
            Field::TerminalCommand => "settings.terminalCommand",
        };
        text(key, &[])
    }

    /// The row's one-line description, from the shared catalog.
    pub fn description(self) -> String {
        let key = match self {
            Field::Device => "tui.settings.deviceDescription",
            Field::Threads => "tui.settings.threadsDescription",
            Field::Language => "tui.settings.languageDescription",
            Field::Speakers => "tui.settings.speakersDescription",
            Field::DefaultFrontend => "tui.settings.defaultFrontendDescription",
            Field::TerminalCommand => "tui.settings.terminalCommandDescription",
        };
        text(key, &[])
    }

    /// The row's value in the screen's settings (a count row shows the chosen count, saved only
    /// with `fixed`), as the screen shows it.
    pub fn value(self, screen: &SettingsScreen) -> String {
        let settings = &screen.settings;
        match self {
            Field::Device => device_label(settings, &settings.device),
            Field::Threads => settings
                .threads
                .map_or_else(|| text("tui.settings.threadsAuto", &[]), |n| n.to_string()),
            Field::Language => settings.default_language.clone(),
            Field::Speakers => match settings.speakers_mode.as_str() {
                "fixed" => settings.speakers_count.map_or_else(
                    || text("tui.settings.speakersFixed", &[]),
                    |n| text("tui.settings.speakersFixed", &[("count", &n.to_string())]),
                ),
                mode => mode.to_string(),
            },
            Field::DefaultFrontend => settings
                .default_frontend
                .clone()
                .unwrap_or_else(|| "-".to_string()),
            Field::TerminalCommand => settings.terminal_command.clone().unwrap_or_else(|| {
                text(
                    "tui.settings.terminalDefault",
                    &[("default", &screen.default_terminal_label())],
                )
            }),
        }
    }
}

/// The service's refusal of a setting's last save, standing in its row.
struct Standing {
    field: Field,
    text: String,
}

/// The open settings screen: the settings and the frontends as the service last answered them, the
/// selected row, the terminal command or number being edited in its row, the open dropdown, and
/// the terminal that opens the TUI when no command is stored.
pub struct SettingsScreen {
    pub settings: Settings,
    pub frontends: Vec<Frontend>,
    pub row: usize,
    terminal: Option<String>,
    dropdown: Option<Dropdown>,
    /// The selected number row's draft while it is edited in place.
    number: Option<String>,
    /// A setting's message, standing in its row: the draft's validation message, or the service's
    /// when it refused the setting's last save.
    message: Option<Standing>,
    /// Whether a save from this page waits for the service's answer: the page says "Saving…".
    saving: bool,
    /// The first row the list shows (set at each draw): the mouse's hit-test lays the rows out
    /// from it, as the frame did.
    offset: std::cell::Cell<usize>,
    default_terminal: DefaultTerminal,
}

/// The options of a setting with a set of values, floating below its row: the stored one is
/// marked, the cursor starts on it, and Enter chooses the one under the cursor. The language list
/// narrows as text is typed, as History's search narrows its rows; the other lists show every
/// option.
pub struct Dropdown {
    pub options: Vec<String>,
    /// The stored value's option, if it is one of them.
    pub current: Option<usize>,
    /// The option under the cursor, among the shown ones.
    pub cursor: usize,
    /// What is typed to narrow the list.
    pub filter: String,
    /// Whether the list narrows with `filter` - the language list does, its list is long.
    pub filtering: bool,
    /// The indexes in `options` the filter lets through, rebuilt when the filter changes.
    shown: Vec<usize>,
}

impl Dropdown {
    fn new(options: Vec<String>, current: Option<usize>, filtering: bool) -> Dropdown {
        let mut dropdown = Dropdown {
            options,
            current,
            cursor: current.unwrap_or(0),
            filter: String::new(),
            filtering,
            shown: Vec::new(),
        };
        dropdown.refilter();
        dropdown
    }

    /// The options the filter lets through, as indexes into [`options`](Self::options): the labels
    /// holding what is typed, case-insensitively, as History's search matches file names.
    pub fn shown(&self) -> &[usize] {
        &self.shown
    }

    /// Rebuilds the shown options after the filter changed.
    fn refilter(&mut self) {
        let needle = self.filter.to_lowercase();
        self.shown = (0..self.options.len())
            .filter(|&index| {
                !self.filtering || self.options[index].to_lowercase().contains(&needle)
            })
            .collect();
    }

    /// The option under the cursor, as its index in [`options`](Self::options), while the list
    /// shows one.
    fn chosen(&self) -> Option<usize> {
        self.shown.get(self.cursor).copied()
    }

    /// The catalog key of its keys line: a filtering list names typing as what narrows it.
    pub fn hint_key(&self) -> &'static str {
        if self.filtering {
            "tui.settings.dropdownFilterHint"
        } else {
            "tui.settings.dropdownHint"
        }
    }
}

impl SettingsScreen {
    /// Selects `row`, the settings list's index (a mouse click lands on it) - the fields, then the
    /// restore row; an open dropdown, number or terminal command closes, since it is the previous
    /// row's.
    pub fn select(&mut self, row: usize) {
        self.row = row.min(FIELDS.len());
        self.dropdown = None;
        self.number = None;
        self.terminal = None;
    }

    /// The selected row's setting; `None` on the restore row under the fields.
    pub fn field(&self) -> Option<Field> {
        FIELDS.get(self.row).copied()
    }

    /// Whether the restore row under the fields is selected: its Enter asks to restore the defaults.
    pub fn on_restore(&self) -> bool {
        self.field().is_none()
    }

    pub fn new(settings: Settings, frontends: Vec<Frontend>) -> SettingsScreen {
        let terminal_var = std::env::var("TERMINAL").ok();
        SettingsScreen {
            settings,
            frontends,
            row: 0,
            terminal: None,
            dropdown: None,
            number: None,
            message: None,
            saving: false,
            offset: std::cell::Cell::new(0),
            default_terminal: stt_client::default_terminal(
                stt_client::host_os(),
                terminal_var.as_deref(),
                &stt_client::on_path,
            ),
        }
    }

    /// What opens the TUI when no terminal command is stored, as the row names it.
    pub fn default_terminal_label(&self) -> String {
        match &self.default_terminal {
            DefaultTerminal::Prefix(prefix) => prefix.clone(),
            DefaultTerminal::Os(Os::Windows) => text("tui.settings.terminalWindows", &[]),
            DefaultTerminal::Os(Os::MacOs) => text("tui.settings.terminalMacos", &[]),
            DefaultTerminal::Os(Os::Linux) | DefaultTerminal::None => {
                text("tui.settings.terminalNone", &[])
            }
        }
    }

    /// The key mode the screen asks for: the terminal command's text, an open dropdown's cursor,
    /// an edited number, or the page's commands.
    pub fn mode(&self) -> Mode {
        if self.terminal.is_some() {
            Mode::Text
        } else if self.dropdown.is_some() {
            Mode::Choose
        } else if self.number.is_some() {
            Mode::Number
        } else {
            Mode::Normal
        }
    }

    /// The terminal command's edit as it starts: the stored command, else the OS default's prefix
    /// (Linux), else empty.
    fn terminal_start(&self) -> String {
        match (&self.settings.terminal_command, &self.default_terminal) {
            (Some(command), _) => command.clone(),
            (None, DefaultTerminal::Prefix(prefix)) => prefix.clone(),
            (None, _) => String::new(),
        }
    }

    /// The settings as the service answered a save: a message a row held goes with it, and the
    /// page is no longer saving.
    pub fn saved(&mut self, settings: Settings) {
        self.settings = settings;
        self.message = None;
        self.stop_saving();
    }

    /// The service refused `field`'s save with `message`: the row keeps the stored value, and the
    /// message stands in it.
    pub fn refused(&mut self, field: Field, message: String) {
        self.message = Some(Standing {
            field,
            text: message,
        });
        self.stop_saving();
    }

    /// Whether a save from this page waits for the service's answer.
    pub fn saving(&self) -> bool {
        self.saving
    }

    /// Records the first row the list shows, as the frame drew it.
    pub fn set_offset(&self, offset: usize) {
        self.offset.set(offset);
    }

    /// The first row the list shows: the mouse's hit-test lays the rows out from it.
    pub fn offset(&self) -> usize {
        self.offset.get()
    }

    /// A save is on its way: the list's title says so until [`stop_saving`](Self::stop_saving).
    fn start_saving(&mut self) {
        self.saving = true;
    }

    /// The save is over, answered or refused: the page stops saying it is saving.
    fn stop_saving(&mut self) {
        self.saving = false;
    }

    /// Saves `changed`, the settings with `field` changed, and says so until the answer is in.
    fn store(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
        changed: Settings,
        field: Option<Field>,
    ) {
        self.start_saving();
        // A fixed speaker count is the upload's maximum too: the row is one choice, as the user
        // asked (2026-11-14: max speakers is pointless beside an automatic choice).
        let mut changed = changed;
        if changed.speakers_mode == "fixed"
            && let Some(count) = changed.speakers_count
        {
            changed.max_speakers = count;
        }
        save(runtime, service, sender, changed, None, field);
    }

    /// Restores the defaults and says so - "Saving…" - until the answer is in.
    pub fn restore(
        &mut self,
        runtime: &tokio::runtime::Handle,
        service: &Service,
        sender: &mpsc::Sender<Message>,
    ) {
        self.start_saving();
        restore(runtime, service, sender);
    }

    /// The restore was refused: the page stops saying it is saving, and the notice carries why.
    pub fn restore_failed(&mut self) {
        self.stop_saving();
    }

    /// The message standing in `field`'s row, if one does.
    pub fn message(&self, field: Field) -> Option<&str> {
        self.message
            .as_ref()
            .filter(|standing| standing.field == field)
            .map(|standing| standing.text.as_str())
    }

    /// The settings another client stored (`settings-updated`): the event carries no device list or
    /// GPU names, so the ones this screen read stay.
    pub fn updated(&mut self, mut settings: Settings) {
        settings.available_devices = std::mem::take(&mut self.settings.available_devices);
        settings.gpu_names = std::mem::take(&mut self.settings.gpu_names);
        self.saved(settings);
    }

    /// The command in the terminal prompt, while it is open.
    pub fn terminal(&self) -> Option<&str> {
        self.terminal.as_deref()
    }

    /// The selected number row's draft, while it is edited in place.
    pub fn number(&self) -> Option<&str> {
        self.number.as_deref()
    }

    /// The open dropdown, if any.
    pub fn dropdown(&self) -> Option<&Dropdown> {
        self.dropdown.as_ref()
    }

    /// The selected row's options as the dropdown lists them, in the order Left/Right step through
    /// them, and the stored value's place among them; `None` for a row without a set of values.
    fn options(&self) -> Option<(Vec<String>, Option<usize>)> {
        let settings = &self.settings;
        Some(match self.field()? {
            Field::Device => {
                let devices = &settings.available_devices;
                let labels = devices
                    .iter()
                    .map(|device| device_label(settings, device))
                    .collect();
                (
                    labels,
                    devices.iter().position(|device| *device == settings.device),
                )
            }
            Field::Language => {
                let languages = stt_client::languages().ok()?;
                let current = languages
                    .iter()
                    .position(|language| language.code == settings.default_language);
                let labels = languages
                    .into_iter()
                    .map(|language| format!("{}  {}", language.code, language.name))
                    .collect();
                (labels, current)
            }
            Field::Speakers => {
                // One control, as the upload dialog's: auto, off, and the counts 1..=MAX_SPEAKERS,
                // a fixed count being its own maximum.
                let mut options = vec!["auto".to_string(), "off".to_string()];
                options.extend((1..=MAX_SPEAKERS).map(|count| count.to_string()));
                let current = match settings.speakers_mode.as_str() {
                    "fixed" => settings.speakers_count.and_then(|count| {
                        options
                            .iter()
                            .position(|option| *option == count.to_string())
                    }),
                    "off" => Some(1),
                    _ => Some(0),
                };
                (options, current)
            }
            Field::DefaultFrontend => {
                let choices = frontend_choices(&self.frontends);
                let current = choices
                    .iter()
                    .position(|choice| *choice == settings.default_frontend);
                let labels = choices
                    .into_iter()
                    .map(|choice| choice.unwrap_or_else(|| "-".to_string()))
                    .collect();
                (labels, current)
            }
            _ => return None,
        })
    }

    /// Applies one key's action: an open dropdown, number or terminal prompt takes it; otherwise
    /// Up/Down select a row, and Enter opens the row's dropdown, number or prompt.
    pub fn handle(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        if self.dropdown.is_some() {
            self.handle_dropdown(action, runtime, sender, service);
        } else if self.number.is_some() {
            self.handle_number(action, runtime, sender, service);
        } else if self.terminal.is_some() {
            self.handle_terminal(action, runtime, sender, service);
        } else {
            match action {
                Action::SelectUp => self.row = self.row.saturating_sub(1),
                Action::SelectDown => {
                    if self.row < FIELDS.len() {
                        self.row += 1;
                    }
                }
                Action::OpenSelected => self.open_selected(),
                _ => {}
            }
        }
    }

    /// Enter on the selected row: the terminal command or a number in its row, or the dropdown of a
    /// setting with a set of values. The restore row's Enter is the page's to answer.
    fn open_selected(&mut self) {
        let Some(field) = self.field() else {
            return;
        };
        match field {
            Field::TerminalCommand => self.terminal = Some(self.terminal_start()),
            Field::Threads => {
                self.number = Some(self.stored_number());
            }
            _ => {
                if let Some((options, current)) = self.options() {
                    let filtering = self.field() == Some(Field::Language);
                    self.dropdown = Some(Dropdown::new(options, current, filtering));
                }
            }
        }
    }

    /// The open dropdown's keys: Up/Down move its cursor, Enter chooses, Escape closes it.
    fn handle_dropdown(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let Some(dropdown) = self.dropdown.as_mut() else {
            return;
        };
        match action {
            Action::SelectUp => dropdown.cursor = dropdown.cursor.saturating_sub(1),
            Action::SelectDown => {
                if dropdown.cursor + 1 < dropdown.shown().len() {
                    dropdown.cursor += 1;
                }
            }
            // Typing narrows the list and Backspace widens it (the language list; the others stay
            // whole), and the cursor returns to the first shown option.
            Action::PromptChar(character) if dropdown.filtering => {
                dropdown.filter.push(character);
                dropdown.refilter();
                dropdown.cursor = 0;
            }
            Action::PromptBackspace if dropdown.filtering => {
                dropdown.filter.pop();
                dropdown.refilter();
                dropdown.cursor = 0;
            }
            Action::CancelPrompt => self.dropdown = None,
            Action::SubmitPrompt => {
                // The speakers row's list is one choice: the option's own text decides auto, off
                // or a count (which is its own maximum).
                let speakers = if self.field() == Some(Field::Speakers) {
                    self.dropdown.as_ref().and_then(|dropdown| {
                        dropdown
                            .chosen()
                            .and_then(|index| dropdown.options.get(index))
                            .cloned()
                    })
                } else {
                    None
                };
                if let Some(option) = speakers {
                    self.dropdown = None;
                    self.choose_speakers(&option, runtime, sender, service);
                    return;
                }
                // Choosing is stepping from the stored option to the chosen one.
                let chosen = self.dropdown.as_ref().and_then(|dropdown| {
                    let current = dropdown.current?;
                    Some(dropdown.chosen()? as isize - current as isize)
                });
                self.dropdown = None;
                // On Speakers, choosing `fixed` again - the stored option - asks for its count.
                if let Some(step) =
                    chosen.filter(|step| *step != 0 || self.field() == Some(Field::Speakers))
                {
                    self.choose(step, runtime, sender, service);
                }
            }
            _ => {}
        }
    }

    /// The edited number's keys: digits and Backspace edit it, Up/Down step it, Enter checks and
    /// saves it, Escape leaves the stored value.
    fn handle_number(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let Some(draft) = self.number.as_mut() else {
            return;
        };
        match action {
            // Five digits hold any thread count a machine has, and never overflow.
            Action::PromptChar(digit) if draft.len() < 5 => draft.push(digit),
            Action::PromptBackspace => {
                draft.pop();
            }
            Action::Adjust(step) => *draft = step_number(draft, step),
            Action::CancelPrompt => {
                self.number = None;
                self.message = None;
            }
            Action::SubmitPrompt => self.submit_number(runtime, sender, service),
            _ => {}
        }
    }

    /// The edited terminal command's keys: characters and Backspace edit it, Enter saves a changed
    /// one (an empty one as none, the OS default), Escape leaves the stored one.
    fn handle_terminal(
        &mut self,
        action: Action,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let start = self.terminal_start();
        let Some(command) = self.terminal.as_mut() else {
            return;
        };
        match action {
            Action::PromptChar(character) => command.push(character),
            Action::PromptBackspace => {
                command.pop();
            }
            Action::CancelPrompt => self.terminal = None,
            Action::SubmitPrompt => {
                let command = command.trim().to_string();
                self.terminal = None;
                // The edit as it started - the stored command or the OS default - changes nothing.
                if command == start.trim() {
                    return;
                }
                let mut changed = self.settings.clone();
                changed.terminal_command = (!command.is_empty()).then_some(command);
                self.store(
                    runtime,
                    service,
                    sender,
                    changed,
                    Some(Field::TerminalCommand),
                );
            }
            _ => {}
        }
    }

    /// Moves the selected row's setting `step` places through its options and saves it. On
    /// Speakers, `fixed` asks for its count in the row instead of saving (see `submit_number`),
    /// and the other modes clear the count; choosing `fixed` again reopens the count.
    fn choose(
        &mut self,
        step: isize,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        if self.field() == Some(Field::Speakers) {
            let Some((options, current)) = self.options() else {
                return;
            };
            let Some(current) = current else {
                return;
            };
            let next = (current as isize + step).rem_euclid(options.len() as isize) as usize;
            self.choose_speakers(&options[next], runtime, sender, service);
            return;
        }
        if let Some(changed) = self.changed(step) {
            self.store(runtime, service, sender, changed, self.field());
        }
    }

    /// The speakers choice `option` from the row's list: auto, off, or a count (fixed, and its own
    /// maximum).
    fn choose_speakers(
        &mut self,
        option: &str,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let mut changed = self.settings.clone();
        match option.parse::<u32>() {
            Ok(count) => {
                changed.speakers_mode = "fixed".to_string();
                changed.speakers_count = Some(count);
                changed.max_speakers = count;
            }
            Err(_) => {
                changed.speakers_mode = option.to_string();
                changed.speakers_count = None;
            }
        }
        if changed != self.settings {
            self.store(runtime, service, sender, changed, Some(Field::Speakers));
        }
    }

    /// The selected number row's value as its draft starts: empty for an automatic thread count or
    /// a speaker count not chosen yet.
    fn stored_number(&self) -> String {
        let number = match self.field() {
            Some(Field::Threads) => self.settings.threads,
            _ => None,
        };
        number.map(|number| number.to_string()).unwrap_or_default()
    }

    /// Enter on an edited number: outside its range the browser's words say so and the draft stays
    /// open; inside it the settings are saved whole - except a speaker count outside `fixed`, which
    /// the count row only holds until the mode needs it.
    fn submit_number(
        &mut self,
        runtime: &tokio::runtime::Handle,
        sender: &mpsc::Sender<Message>,
        service: &Service,
    ) {
        let Some(draft) = self.number.as_deref() else {
            return;
        };
        let Some(field) = self.field() else {
            return;
        };
        // Only digits reach a number draft, so any parse is a count: none is the automatic one.
        let parsed = draft.parse::<u32>().ok();
        let mut changed = self.settings.clone();
        match field {
            Field::Threads => changed.threads = parsed,
            _ => return,
        }
        self.number = None;
        self.message = None;
        if changed != self.settings {
            self.store(runtime, service, sender, changed, Some(field));
        }
    }

    /// The settings with the selected row's setting moved `step` places through its options, if it
    /// can move.
    fn changed(&self, step: isize) -> Option<Settings> {
        let mut changed = self.settings.clone();
        match self.field()? {
            Field::Device => {
                changed.device = step_choice(&changed.available_devices, &changed.device, step)?;
            }
            Field::Language => {
                let codes = language_codes()?;
                changed.default_language = step_choice(&codes, &changed.default_language, step)?;
            }
            Field::Speakers => return None,
            Field::DefaultFrontend => {
                changed.default_frontend =
                    step_frontend(&self.frontends, &changed.default_frontend, step)?;
            }
            _ => return None,
        }
        (changed != self.settings).then_some(changed)
    }
}

/// The most speakers the contract takes, for the speakers row's counts.
const MAX_SPEAKERS: u32 = 20;

/// The thread count `draft` changed by `step`: below 1 it is empty, the automatic count.
fn step_number(draft: &str, step: isize) -> String {
    let current = draft.parse::<u32>().unwrap_or(0);
    let next = i64::from(current) + step as i64;
    if next <= 0 {
        String::new()
    } else {
        u32::try_from(next).unwrap_or(current).to_string()
    }
}

/// The default frontend's choices: none, then the installed ones in the registry's order.
fn frontend_choices(frontends: &[Frontend]) -> Vec<Option<String>> {
    let mut choices: Vec<Option<String>> = vec![None];
    choices.extend(frontends.iter().map(|frontend| Some(frontend.id.clone())));
    choices
}

/// The default frontend `step` places through none and the installed ones, in the registry's order.
fn step_frontend(
    frontends: &[Frontend],
    current: &Option<String>,
    step: isize,
) -> Option<Option<String>> {
    step_choice(&frontend_choices(frontends), current, step)
}

/// A device as the browser names it: "Auto", "CPU", and "GPU 0 — <name>" (the index as the
/// service gives it), or the value as it is when it is none of those.
fn device_label(settings: &Settings, device: &str) -> String {
    if device == "auto" {
        return "Auto".to_string();
    }
    if device == "cpu" {
        return "CPU".to_string();
    }
    let Some(index) = device.strip_prefix("gpu:") else {
        return device.to_string();
    };
    match settings.gpu_names.get(device) {
        Some(name) => format!("GPU {index} — {name}"),
        None => format!("GPU {index}"),
    }
}

/// Saves `settings` whole; the service's answer is what the screen shows, a refusal is sent with
/// the setting it refused - `field`, or none for the theme key - for the page to place. A caller
/// that drew the change before the answer (the theme key) passes the settings to put back on a
/// refusal as `restore`.
pub(crate) fn save(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
    settings: Settings,
    restore: Option<Settings>,
    field: Option<Field>,
) {
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        match save_settings(&service, &settings).await {
            Ok(saved) => {
                let _ = sender.send(Message::SettingsSaved(saved));
            }
            Err(error) => {
                if let Some(previous) = restore {
                    let _ = sender.send(Message::Stored(previous));
                }
                let _ = sender.send(Message::SettingRefused(field, error.to_string()));
            }
        }
    });
}

/// Restores the service's default settings; its answer is what the screen shows - with the stored
/// settings, so the CPU-fallback notice clears - and a refusal the settings page's notice.
pub(crate) fn restore(
    runtime: &tokio::runtime::Handle,
    service: &Service,
    sender: &mpsc::Sender<Message>,
) {
    let service = service.clone();
    let sender = sender.clone();
    runtime.spawn(async move {
        let message = match restore_settings(&service).await {
            Ok(settings) => Message::SettingsSaved(settings),
            Err(error) => Message::RestoreFailed(error.to_string()),
        };
        let _ = sender.send(message);
    });
}
