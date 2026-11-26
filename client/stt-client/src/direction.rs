//! A text's direction by its first letter (roadmap item 76): the rule the browser applies
//! (`frontend/src/shared/textDirection.ts`), for the frontends that draw text themselves - the TUI
//! and the window. Every implementation passes the shared table `docs/text-direction-cases.json`.

use std::sync::LazyLock;

use regex::Regex;

/// A text's direction: left-to-right or right-to-left.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    Ltr,
    Rtl,
}

// A letter: Unicode's letter categories (`\p{L}`), as the browser's rule matches them. Not
// `char::is_alphabetic`, which also takes combining vowel marks such as the Arabic shadda.
static LETTER: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\p{L}").expect("a valid pattern"));

// The letters of the right-to-left scripts: the code-point ranges Unicode gives the R and AL bidi
// classes (Hebrew and Yiddish, Arabic with Persian, Urdu, Sindhi and Pashto, Syriac, Thaana, N'Ko
// and the rest) - the browser's ranges, checked against Chromium 156 over every Unicode letter.
fn rtl_letter(letter: char) -> bool {
    matches!(letter as u32,
        0x0590..=0x08FF | 0xFB1D..=0xFDFF | 0xFE70..=0xFEFF | 0x10800..=0x10FFF | 0x1E800..=0x1EFFF)
}

/// `text`'s direction is its first letter's: right-to-left for a letter of a right-to-left script,
/// left-to-right for any other; digits, punctuation and spaces before it do not decide. A text
/// without a letter has no direction of its own (`None`).
pub fn letter_direction(text: &str) -> Option<Direction> {
    let letter = LETTER.find(text)?.as_str().chars().next()?;
    Some(if rtl_letter(letter) {
        Direction::Rtl
    } else {
        Direction::Ltr
    })
}

/// The direction of `text`'s last letter, by the same letters as [`letter_direction`]: what a
/// letter typed after it continues, which decides where an editor's cursor goes. `None` without a
/// letter.
pub fn last_letter_direction(text: &str) -> Option<Direction> {
    let letter = LETTER.find_iter(text).last()?.as_str().chars().next()?;
    Some(if rtl_letter(letter) {
        Direction::Rtl
    } else {
        Direction::Ltr
    })
}

/// A standalone text's direction - a speaker name or a filename, on its own or inside a sentence:
/// its letter direction, and left-to-right when it has no letter (not the app language's).
pub fn standalone_direction(text: &str) -> Direction {
    letter_direction(text).unwrap_or(Direction::Ltr)
}

/// The direction of each of a transcript's turns, given their texts in order: a turn takes its own
/// letter direction; a turn without a letter takes the previous lettered turn's, or at the start of
/// the transcript the next one's; `None` when no turn has a letter (the app language's then).
pub fn turn_directions(texts: &[&str]) -> Vec<Option<Direction>> {
    let own: Vec<Option<Direction>> = texts.iter().map(|text| letter_direction(text)).collect();
    (0..own.len())
        .map(|index| {
            own[index]
                .or_else(|| own[..index].iter().rev().find_map(|direction| *direction))
                .or_else(|| own[index + 1..].iter().find_map(|direction| *direction))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use serde::Deserialize;

    use super::{Direction, letter_direction, standalone_direction, turn_directions};

    // Item 76: one table of the rule's cases, which every implementation of the rule (the browser,
    // the TUI, the window) is tested against, so they cannot drift apart. `none` is a text without
    // a letter, which has no direction of its own.
    const TABLE: &str = include_str!("../../../docs/text-direction-cases.json");

    #[derive(Deserialize)]
    struct Table {
        letters: Vec<TextCase>,
        turns: Vec<TurnsCase>,
        standalone: Vec<TextCase>,
    }

    #[derive(Deserialize)]
    struct TextCase {
        text: String,
        direction: String,
        why: String,
    }

    #[derive(Deserialize)]
    struct TurnsCase {
        texts: Vec<String>,
        directions: Vec<String>,
        why: String,
    }

    fn table() -> Table {
        serde_json::from_str(TABLE).expect("the shared table's three sections")
    }

    fn name(direction: Option<Direction>) -> &'static str {
        match direction {
            Some(Direction::Rtl) => "rtl",
            Some(Direction::Ltr) => "ltr",
            None => "none",
        }
    }

    #[test]
    fn decides_every_text_of_the_shared_table() {
        for case in table().letters {
            assert_eq!(
                name(letter_direction(&case.text)),
                case.direction,
                "{}",
                case.why
            );
        }
    }

    #[test]
    fn decides_every_transcript_of_the_shared_table() {
        for case in table().turns {
            let texts: Vec<&str> = case.texts.iter().map(String::as_str).collect();
            let directions: Vec<&str> = turn_directions(&texts).into_iter().map(name).collect();
            assert_eq!(directions, case.directions, "{}", case.why);
        }
    }

    #[test]
    fn decides_every_standalone_text_of_the_shared_table() {
        for case in table().standalone {
            let direction = Some(standalone_direction(&case.text));
            assert_eq!(name(direction), case.direction, "{}", case.why);
        }
    }
}
