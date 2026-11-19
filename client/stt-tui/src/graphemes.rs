//! A user-visible character: a grapheme cluster, which may be several code points - a letter and
//! its combining marks, a Devanagari syllable, an emoji sequence (roadmap item 67, the review R20).

use unicode_segmentation::UnicodeSegmentation;

/// Removes `text`'s last user-visible character; nothing happens when it is empty. One Backspace
/// takes a whole character, so a mark is never left without its base.
pub fn pop(text: &mut String) {
    if let Some((start, _)) = text.grapheme_indices(true).next_back() {
        text.truncate(start);
    }
}

/// `text`'s user-visible characters.
pub fn of(text: &str) -> impl DoubleEndedIterator<Item = &str> {
    text.graphemes(true)
}
