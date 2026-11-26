//! A line's visual order (roadmap item 76): a terminal writes each cell where it is told, so the
//! TUI orders a line itself - the Unicode bidi algorithm, the paragraph's direction as its base -
//! before it writes the cells, left to right. kitty's default mode turns each right-to-left word's
//! letters itself, so for it ([`typed_words`]) those letters go back in typed order.

use stt_client::direction::{Direction, letter_direction};
use unicode_bidi::{BidiInfo, Level};

/// `glyphs` - one user-visible character each, `text` giving its characters - reordered from
/// logical to visual order: the line's resolved levels (rule L1), then every run at or above each
/// odd level reversed, highest first (rule L2); a paired bracket in a right-to-left run is swapped
/// for its mirror through `retext` (rule L4), as a terminal draws the character it is given.
pub fn visual<G>(
    glyphs: Vec<G>,
    text: impl Fn(&G) -> &str,
    retext: impl Fn(&mut G, String),
    base: Direction,
) -> Vec<G> {
    let mut line = String::new();
    let mut starts = Vec::with_capacity(glyphs.len());
    for glyph in &glyphs {
        starts.push(line.len());
        line.push_str(text(glyph));
    }
    if line.is_empty() {
        return glyphs;
    }
    let base = match base {
        Direction::Ltr => Level::ltr(),
        Direction::Rtl => Level::rtl(),
    };
    let info = BidiInfo::new(&line, Some(base));
    let paragraph = &info.paragraphs[0];
    let levels = info.reordered_levels(paragraph, 0..line.len());
    let glyph_levels: Vec<u8> = starts.iter().map(|&start| levels[start].number()).collect();
    let highest = glyph_levels.iter().copied().max().unwrap_or(0);
    let lowest_odd = glyph_levels
        .iter()
        .copied()
        .filter(|level| level % 2 == 1)
        .min()
        .unwrap_or(highest + 1);
    let mut order: Vec<usize> = (0..glyphs.len()).collect();
    for level in (lowest_odd..=highest).rev() {
        let mut index = 0;
        while index < order.len() {
            if glyph_levels[order[index]] >= level {
                let start = index;
                while index < order.len() && glyph_levels[order[index]] >= level {
                    index += 1;
                }
                order[start..index].reverse();
            } else {
                index += 1;
            }
        }
    }
    let mut slots: Vec<Option<G>> = glyphs.into_iter().map(Some).collect();
    order
        .into_iter()
        .map(|index| {
            let mut glyph = slots[index].take().expect("each glyph placed once");
            if glyph_levels[index] % 2 == 1
                && let Some(mirror) = mirrored(text(&glyph))
            {
                retext(&mut glyph, mirror.to_string());
            }
            glyph
        })
        .collect()
}

/// `glyphs`, in visual order, with each right-to-left word's letters put back in typed order -
/// for kitty's default mode (`STT_TUI_RTL_WORDS`), which draws each right-to-left word's letters
/// right to left itself. A word is a run of right-to-left letters, `text` giving each glyph's
/// characters with its combining marks; digits, spaces and punctuation stay where the visual order
/// put them, and so does every word.
pub fn typed_words<G>(mut glyphs: Vec<G>, text: impl Fn(&G) -> &str) -> Vec<G> {
    let rtl = |glyph: &G| letter_direction(text(glyph)) == Some(Direction::Rtl);
    let mut start = 0;
    while start < glyphs.len() {
        if rtl(&glyphs[start]) {
            let mut end = start;
            while end < glyphs.len() && rtl(&glyphs[end]) {
                end += 1;
            }
            glyphs[start..end].reverse();
            start = end;
        } else {
            start += 1;
        }
    }
    glyphs
}

// The paired brackets a right-to-left run draws mirrored (Unicode's Bidi_Mirroring_Glyph for the
// brackets a transcript or a name carries).
fn mirrored(glyph: &str) -> Option<char> {
    let mut characters = glyph.chars();
    let character = characters.next()?;
    if characters.next().is_some() {
        return None;
    }
    Some(match character {
        '(' => ')',
        ')' => '(',
        '[' => ']',
        ']' => '[',
        '{' => '}',
        '}' => '{',
        '<' => '>',
        '>' => '<',
        '«' => '»',
        '»' => '«',
        '‹' => '›',
        '›' => '‹',
        _ => return None,
    })
}
