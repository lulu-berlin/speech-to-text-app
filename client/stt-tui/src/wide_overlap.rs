//! ratatui's diff repaints only the cells that changed between two frames. When a wide character
//! is drawn over the left half of a wide character the previous frame had there, the terminal
//! blanks the old character's right half too, but ratatui leaves that column alone if the two
//! frames hold the same cell there, so the terminal keeps the blanked cell (roadmap item 67, the
//! second review, discovered with S8; ratatui issue #2933). Until a ratatui release repaints it,
//! the TUI marks such columns to be written whatever the diff thinks; then this module goes.

use ratatui::buffer::{Buffer, CellDiffOption};
use unicode_width::UnicodeWidthStr;

/// Marks the cells of `next` that the terminal will have blanked once `next` is drawn over
/// `previous` - the columns of a previous wide character past a new wide one that covers its left
/// half - so the diff writes them. Frames of different sizes are left alone: ratatui redraws a
/// resized screen whole.
pub fn repaint_orphans(previous: &Buffer, next: &mut Buffer) {
    let area = next.area;
    if previous.area != area {
        return;
    }
    for y in area.top()..area.bottom() {
        let mut x = area.left();
        while x < area.right() {
            let width = next[(x, y)].symbol().width().max(1) as u16;
            // The previous frame's characters starting under this one's right half: the part of
            // one that reaches past this character is blanked by the terminal.
            for under in x + 1..(x + width).min(area.right()) {
                let reach = under + previous[(under, y)].symbol().width() as u16;
                for orphan in x + width..reach.min(area.right()) {
                    next[(orphan, y)].set_diff_option(CellDiffOption::AlwaysUpdate);
                }
            }
            x += width;
        }
    }
}

#[cfg(test)]
mod tests {
    use ratatui::buffer::Buffer;
    use ratatui::layout::Rect;
    use ratatui::style::{Color, Style};
    use ratatui::text::{Line, Span};
    use ratatui::widgets::{Paragraph, Widget};

    use super::repaint_orphans;

    #[test]
    fn a_wide_character_over_half_of_another_has_the_other_half_repainted() {
        let area = Rect::new(0, 0, 6, 1);
        let row = Style::new().bg(Color::Blue);
        // Before: `ab`, then `重` at columns 2-3. After: `a`, then `辑` at columns 1-2, over
        // the left half of `重`; column 3, `重`'s right half, is a blank of the row's style in both.
        let mut previous = Buffer::empty(area);
        Paragraph::new("ab重")
            .style(row)
            .render(area, &mut previous);
        let mut next = Buffer::empty(area);
        Paragraph::new(Line::from(vec![
            Span::raw("a"),
            Span::styled("辑", Style::new().bg(Color::Red)),
        ]))
        .style(row)
        .render(area, &mut next);

        repaint_orphans(&previous, &mut next);

        let written: Vec<u16> = previous.diff(&next).iter().map(|(x, _, _)| *x).collect();
        assert_eq!(written, [1, 3], "`辑`, then the column it orphaned");
    }
}
