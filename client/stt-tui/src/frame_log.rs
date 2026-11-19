//! The text of each frame the TUI draws, written to the file `STT_TUI_FRAME_LOG` names, for the pty
//! tests only (ADR 0070): their harness accepts a screen only when it equals the last frame
//! reported here, so a terminal's repaint caught half done - a Windows ConPTY paints after the
//! frame's markers - is never taken for the frame. Debug builds only; the release build has none
//! of it.

use std::path::PathBuf;
use std::sync::LazyLock;

use ratatui::buffer::{Buffer, Cell};
use ratatui::crossterm::style::Colored;
use ratatui::style::{Color, Modifier};
use unicode_width::UnicodeWidthStr;

/// The file named by `STT_TUI_FRAME_LOG`, if the variable is set.
static TARGET: LazyLock<Option<PathBuf>> =
    LazyLock::new(|| std::env::var_os("STT_TUI_FRAME_LOG").map(PathBuf::from));

/// Reports `buffer` as the frame drawn last: for each row a line of its cells' text - a wide
/// character once, its right half adding nothing, as a terminal's screen reads it - then a line of
/// the same cells' styles, `foreground/background/attributes` each, separated by spaces (see
/// [`style_token`]). The file is replaced whole (written beside it, then renamed over it), so a
/// reader never sees half a frame. Nothing is written without the variable.
pub fn record(buffer: &Buffer) {
    let Some(target) = TARGET.as_ref() else {
        return;
    };
    let area = buffer.area;
    let mut lines = Vec::with_capacity(usize::from(area.height) * 2);
    for y in area.top()..area.bottom() {
        let mut text = String::new();
        let mut styles: Vec<String> = Vec::new();
        let mut covered = 0;
        for x in area.left()..area.right() {
            if covered > 0 {
                covered -= 1;
                continue;
            }
            let cell = &buffer[(x, y)];
            covered = cell.symbol().width().saturating_sub(1);
            text.push_str(cell.symbol());
            styles.push(style_token(cell));
        }
        lines.push(text);
        lines.push(styles.join(" "));
    }
    let partial = target.with_extension("part");
    // A frame that cannot be reported leaves the last one in place: the harness then fails its
    // wait, naming the frame log, rather than the TUI failing to draw.
    if std::fs::write(&partial, lines.join("\n")).is_ok() {
        let _ = std::fs::rename(&partial, target);
    }
}

/// A cell's style as the terminal is told to draw it: `foreground/background/attributes`, a
/// colour `default`, `idx:n` (a palette colour; the named ones are the palette's first sixteen) or
/// `rgb:r,g,b`, and the attributes `B`old, `I`talic, `U`nderline and `R`everse in that order.
/// Under `NO_COLOR` crossterm writes no colour at all, whatever the buffer holds, so both are the
/// terminal's `default` then - crossterm's own test decides it.
fn style_token(cell: &Cell) -> String {
    let modifier = cell.modifier;
    let attributes: String = [
        (Modifier::BOLD, 'B'),
        (Modifier::ITALIC, 'I'),
        (Modifier::UNDERLINED, 'U'),
        (Modifier::REVERSED, 'R'),
    ]
    .iter()
    .filter(|(flag, _)| modifier.contains(*flag))
    .map(|(_, letter)| letter)
    .collect();
    if Colored::ansi_color_disabled_memoized() {
        return format!("default/default/{attributes}");
    }
    format!("{}/{}/{attributes}", colour(cell.fg), colour(cell.bg))
}

/// `colour` as [`style_token`] writes it.
fn colour(colour: Color) -> String {
    let index = match colour {
        Color::Reset => return "default".to_string(),
        Color::Rgb(red, green, blue) => return format!("rgb:{red},{green},{blue}"),
        Color::Indexed(index) => index,
        Color::Black => 0,
        Color::Red => 1,
        Color::Green => 2,
        Color::Yellow => 3,
        Color::Blue => 4,
        Color::Magenta => 5,
        Color::Cyan => 6,
        Color::Gray => 7,
        Color::DarkGray => 8,
        Color::LightRed => 9,
        Color::LightGreen => 10,
        Color::LightYellow => 11,
        Color::LightBlue => 12,
        Color::LightMagenta => 13,
        Color::LightCyan => 14,
        Color::White => 15,
    };
    format!("idx:{index}")
}
