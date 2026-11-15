//! The shared colour tokens (`ui-theme/colors.json`, ADR 0068) as the terminal reports them: what a
//! pty test compares a drawn cell's colour with.

use serde_json::Value;
use vt100::Color;

/// The shared tokens, the same file both frontends read.
const COLOURS: &str = include_str!("../../../../ui-theme/colors.json");

/// A colour role's value in `mode` (`light` or `dark`): `&["colors", "border"]`, … A palette (the
/// speaker tints) is a list, and its first entry is the one returned.
pub fn token(path: &[&str], mode: &str) -> Color {
    let mut value: &Value = &serde_json::from_str(COLOURS).unwrap();
    for step in path {
        value = value
            .get(step)
            .unwrap_or_else(|| panic!("no {} in the tokens", path.join(".")));
    }
    let hex = match value.get(mode).unwrap() {
        Value::String(text) => text.clone(),
        Value::Array(list) => list[0].as_str().unwrap().to_string(),
        other => panic!(
            "{} is a colour or a list of them, not {other}",
            path.join(".")
        ),
    };
    let channel = |at: usize| u8::from_str_radix(&hex[1 + at * 2..3 + at * 2], 16).unwrap();
    Color::Rgb(channel(0), channel(1), channel(2))
}
