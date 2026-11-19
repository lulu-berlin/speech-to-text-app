//! The theme (roadmap item 67, ADR 0068): the TUI's colours are the shared tokens in
//! `ui-theme/colors.json`, the file the browser's themes read too, so the two frontends cannot
//! drift apart. The mode is the service's stored `themeMode`; `auto` follows the OS's preference,
//! then the terminal's background, then dark.

use ratatui::style::Color;
use serde_json::Value;

/// The compiled-in shared tokens.
const TOKENS: &str = include_str!("../../../ui-theme/colors.json");

/// The colours the TUI draws with, one per role it uses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Palette {
    pub background: Color,
    pub surface: Color,
    /// A raised surface: chips and a floating list over the page.
    pub surface_raised: Color,
    /// The body text on the page.
    pub text: Color,
    pub border: Color,
    pub muted: Color,
    pub accent: Color,
    pub accent_tint: Color,
    /// The words on an accent fill: the playing segment's text.
    pub accent_text: Color,
    pub danger: Color,
    /// The status groups' text colours (ADR 0033): pending, done, problem and gone.
    pub pending: Color,
    pub done: Color,
    pub gone: Color,
    /// The status groups' chip backgrounds, in the same order: pending, active, done, problem and
    /// gone.
    pub status_backgrounds: [Color; 5],
    /// One tint per speaker index, wrapping when a task has more speakers than entries.
    pub speaker_tints: Vec<Color>,
}

impl Palette {
    /// The tint for `index`, wrapping as the browser's palette does.
    pub fn speaker(&self, index: usize) -> Option<Color> {
        if self.speaker_tints.is_empty() {
            None
        } else {
            Some(self.speaker_tints[index % self.speaker_tints.len()])
        }
    }

    /// The colour a task status is drawn in, from the status groups' roles (ADR 0033): RUNNING
    /// active, COMPLETED done, FAILED problem, CANCELED gone, the rest pending.
    pub fn status(&self, status: &str) -> Color {
        match status {
            "RUNNING" => self.accent,
            "COMPLETED" => self.done,
            "FAILED" => self.danger,
            "CANCELED" => self.gone,
            _ => self.pending,
        }
    }

    /// The background a status chip is drawn on: its group's, as [`Palette::status`] groups it.
    pub fn status_background(&self, status: &str) -> Color {
        let [pending, active, done, problem, gone] = self.status_backgrounds;
        match status {
            "RUNNING" => active,
            "COMPLETED" => done,
            "FAILED" => problem,
            "CANCELED" => gone,
            _ => pending,
        }
    }
}

/// What `auto` decides from, read once at startup: whether the OS prefers a dark interface, and
/// whether the terminal's background is dark - `None` where one says nothing.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Clues {
    pub os: Option<bool>,
    pub terminal: Option<bool>,
}

impl Clues {
    /// Asks the OS, and the terminal only when the OS says nothing. The terminal is asked before
    /// anything else reads it: its answer arrives on the TUI's input, which nothing else may consume
    /// first.
    pub fn read() -> Clues {
        let os = os_prefers_dark();
        let terminal = if os.is_none() {
            terminal_colorsaurus::theme_mode(terminal_colorsaurus::QueryOptions::default())
                .ok()
                .map(|mode| mode == terminal_colorsaurus::ThemeMode::Dark)
        } else {
            None
        };
        Clues { os, terminal }
    }
}

/// Whether the terminal draws 24-bit colour: the Windows console always does (conhost and Windows
/// Terminal, neither of which sets `COLORTERM`); elsewhere a terminal says so with `COLORTERM`.
pub fn true_colour() -> bool {
    cfg!(windows)
        || matches!(
            std::env::var("COLORTERM").as_deref(),
            Ok("truecolor" | "24bit")
        )
}

/// The palette for a stored `themeMode` (`auto`, `light` or `dark`; anything else is `auto`), in
/// 24-bit colour or the 256-colour palette.
pub fn for_mode(mode: &str, clues: &Clues, true_colour: bool) -> Palette {
    palette(resolved(mode, clues), true_colour)
}

/// The mode `t` stores after `mode`: `auto → light → dark → auto`, the browser's control's order.
pub fn next(mode: &str) -> &'static str {
    match mode {
        "light" => "dark",
        "dark" => "auto",
        _ => "light",
    }
}

/// The tokens' mode a stored `themeMode` draws in: `auto` takes the OS's preference, then the
/// terminal's background, then dark.
fn resolved(mode: &str, clues: &Clues) -> &'static str {
    let dark = match mode {
        "light" => false,
        "dark" => true,
        _ => clues.os.or(clues.terminal).unwrap_or(true),
    };
    if dark { "dark" } else { "light" }
}

/// The tokens' value for `path`, in `mode`: `colors.accent`, `colors.status.pending.text`,
/// `colors.speakerTints`, … - an array for the speaker palette.
fn value<'a>(tokens: &'a Value, path: &[&str], mode: &str) -> &'a Value {
    let mut value = tokens;
    for step in path {
        value = value
            .get(step)
            .unwrap_or_else(|| panic!("the shared tokens have {}", path.join(".")));
    }
    value
        .get(mode)
        .unwrap_or_else(|| panic!("the shared tokens have {} for {mode}", path.join(".")))
}

/// A `#rrggbb` token's channels.
fn rgb(value: &Value) -> [u8; 3] {
    let text = value.as_str().expect("a colour is a string");
    let channel = |at: usize| {
        u8::from_str_radix(&text[1 + at * 2..3 + at * 2], 16)
            .unwrap_or_else(|_| panic!("{text} is a #rrggbb colour"))
    };
    [channel(0), channel(1), channel(2)]
}

/// A colour as the terminal draws it: exact in 24-bit colour, otherwise its nearest entry of the
/// 256-colour palette.
fn drawn(rgb: [u8; 3], true_colour: bool) -> Color {
    if true_colour {
        Color::Rgb(rgb[0], rgb[1], rgb[2])
    } else {
        Color::Indexed(nearest_256(rgb))
    }
}

/// The 256-colour palette's entry nearest `rgb` by RGB distance, from its colour cube (16-231,
/// levels 0, 95, 135, 175, 215, 255) and its grey ramp (232-255, 8 to 238 in steps of 10). The 16
/// system colours are never chosen: the user's terminal theme redefines them.
fn nearest_256(rgb: [u8; 3]) -> u8 {
    const LEVELS: [u8; 6] = [0, 95, 135, 175, 215, 255];
    let distance = |a: [u8; 3], b: [u8; 3]| -> u32 {
        a.iter()
            .zip(b)
            .map(|(x, y)| u32::from(x.abs_diff(y)).pow(2))
            .sum()
    };
    let cube = rgb.map(|channel| {
        (0..6u8)
            .min_by_key(|&level| LEVELS[usize::from(level)].abs_diff(channel))
            .expect("the cube has levels")
    });
    let cube_colour = cube.map(|level| LEVELS[usize::from(level)]);
    let grey = (0..24u8)
        .min_by_key(|&step| distance(rgb, [8 + 10 * step; 3]))
        .expect("the ramp has greys");
    if distance(rgb, [8 + 10 * grey; 3]) < distance(rgb, cube_colour) {
        232 + grey
    } else {
        16 + 36 * cube[0] + 6 * cube[1] + cube[2]
    }
}

fn palette(mode: &str, true_colour: bool) -> Palette {
    let tokens: Value = serde_json::from_str(TOKENS).expect("the shared tokens are valid JSON");
    let colour = |path: &[&str]| drawn(rgb(value(&tokens, path, mode)), true_colour);
    Palette {
        background: colour(&["colors", "background"]),
        surface: colour(&["colors", "surface"]),
        surface_raised: colour(&["colors", "surfaceRaised"]),
        text: colour(&["colors", "text"]),
        border: colour(&["colors", "border"]),
        muted: colour(&["colors", "textMuted"]),
        accent: colour(&["colors", "accent"]),
        accent_tint: colour(&["colors", "accentTint"]),
        accent_text: colour(&["colors", "accentText"]),
        danger: colour(&["colors", "danger"]),
        pending: colour(&["colors", "status", "pending", "text"]),
        done: colour(&["colors", "status", "done", "text"]),
        gone: colour(&["colors", "status", "gone", "text"]),
        status_backgrounds: ["pending", "active", "done", "problem", "gone"]
            .map(|group| colour(&["colors", "status", group, "background"])),
        speaker_tints: value(&tokens, &["colors", "speakerTints"], mode)
            .as_array()
            .expect("the speaker tints are an array")
            .iter()
            .map(|tint| drawn(rgb(tint), true_colour))
            .collect(),
    }
}

/// Whether the OS prefers a dark interface, as each one reports it: `None` when it says nothing or
/// its tool cannot run.
fn os_prefers_dark() -> Option<bool> {
    let run = |program: &str, args: &[&str]| {
        std::process::Command::new(program)
            .args(args)
            .output()
            .ok()
            .map(|output| {
                (
                    output.status.success(),
                    String::from_utf8_lossy(&output.stdout).into_owned(),
                )
            })
    };
    if cfg!(target_os = "linux") {
        let (succeeded, scheme) = run(
            "gsettings",
            &["get", "org.gnome.desktop.interface", "color-scheme"],
        )?;
        succeeded.then(|| gsettings_prefers_dark(&scheme)).flatten()
    } else if cfg!(target_os = "macos") {
        let (succeeded, style) = run("defaults", &["read", "-g", "AppleInterfaceStyle"])?;
        Some(defaults_prefers_dark(succeeded, &style))
    } else if cfg!(target_os = "windows") {
        let (succeeded, answer) = run(
            "reg",
            &[
                "query",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
                "/v",
                "AppsUseLightTheme",
            ],
        )?;
        succeeded.then(|| reg_prefers_dark(&answer)).flatten()
    } else {
        None
    }
}

/// GNOME's `color-scheme` (`'prefer-dark'`, `'prefer-light'`, `'default'`): `default` states no
/// preference.
fn gsettings_prefers_dark(scheme: &str) -> Option<bool> {
    if scheme.contains("prefer-dark") {
        Some(true)
    } else if scheme.contains("prefer-light") {
        Some(false)
    } else {
        None
    }
}

/// macOS's `AppleInterfaceStyle`: `Dark` in the dark appearance; `defaults` fails when the key is
/// absent, which is the light one.
fn defaults_prefers_dark(succeeded: bool, style: &str) -> bool {
    succeeded && style.trim() == "Dark"
}

/// Windows' `AppsUseLightTheme` as `reg query` prints it: `0x0` is dark, `0x1` light.
fn reg_prefers_dark(answer: &str) -> Option<bool> {
    let value = answer
        .lines()
        .find(|line| line.contains("AppsUseLightTheme"))?
        .split_whitespace()
        .last()?;
    match value {
        "0x0" => Some(true),
        "0x1" => Some(false),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auto_takes_the_os_then_the_terminal_then_dark() {
        let clues = |os, terminal| Clues { os, terminal };
        assert_eq!(resolved("auto", &clues(Some(false), Some(true))), "light");
        assert_eq!(resolved("auto", &clues(Some(true), Some(false))), "dark");
        assert_eq!(resolved("auto", &clues(None, Some(false))), "light");
        assert_eq!(resolved("auto", &clues(None, None)), "dark");
        // An unknown stored value is auto.
        assert_eq!(resolved("sepia", &clues(None, Some(false))), "light");
    }

    #[test]
    fn a_chosen_mode_ignores_every_clue() {
        let light_everywhere = Clues {
            os: Some(false),
            terminal: Some(false),
        };
        let dark_everywhere = Clues {
            os: Some(true),
            terminal: Some(true),
        };
        assert_eq!(resolved("dark", &light_everywhere), "dark");
        assert_eq!(resolved("light", &dark_everywhere), "light");
    }

    #[test]
    fn t_cycles_auto_light_dark() {
        assert_eq!(next("auto"), "light");
        assert_eq!(next("light"), "dark");
        assert_eq!(next("dark"), "auto");
    }

    #[test]
    fn gnome_states_a_preference_only_for_prefer_dark_and_prefer_light() {
        assert_eq!(gsettings_prefers_dark("'prefer-dark'\n"), Some(true));
        assert_eq!(gsettings_prefers_dark("'prefer-light'\n"), Some(false));
        assert_eq!(gsettings_prefers_dark("'default'\n"), None);
    }

    #[test]
    fn macos_is_dark_only_when_the_style_key_says_dark() {
        assert!(defaults_prefers_dark(true, "Dark\n"));
        // The key is absent in the light appearance: `defaults` fails.
        assert!(!defaults_prefers_dark(false, ""));
    }

    #[test]
    fn windows_reads_apps_use_light_theme() {
        let answer = |value: &str| {
            format!(
                "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n    AppsUseLightTheme    REG_DWORD    {value}\r\n\r\n"
            )
        };
        assert_eq!(reg_prefers_dark(&answer("0x0")), Some(true));
        assert_eq!(reg_prefers_dark(&answer("0x1")), Some(false));
        assert_eq!(reg_prefers_dark("\r\n"), None);
    }
}
