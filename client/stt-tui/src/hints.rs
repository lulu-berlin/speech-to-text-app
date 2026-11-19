//! Key hints (roadmap item 67, second amendment): a catalog text marks each key it names in
//! brackets - `[p]ause`, `re[v]ert`, `[Enter] open`. With colours the brackets go and the key is
//! underlined in the accent colour: a letter key in place inside its action's word, any other key
//! before its action. `NO_COLOR` writes no attribute at all, so there the brackets stay and mark
//! the key.

use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};

/// What separates the hints of a hint list (a footer): `[p]ause   [c]ancel`, spaced rather
/// than dotted, as the designs draw it.
pub const SEPARATOR: &str = "   ";

/// The catalog text `key` with `args` filled in, its keys highlighted in `accent` - or bracketed,
/// when `accent` is `None` (`NO_COLOR`). The markup is read from the template before the arguments
/// go in, so a file name with brackets in it is never taken for a key.
pub fn line(key: &str, args: &[(&str, &str)], accent: Option<Color>) -> Line<'static> {
    render(stt_client::template(key).unwrap_or(key), args, accent, None)
}

/// [`line`] with its words that are not keys in `muted`, as a footer draws a hint.
pub fn line_muted(
    key: &str,
    args: &[(&str, &str)],
    accent: Option<Color>,
    muted: Option<Color>,
) -> Line<'static> {
    render(
        stt_client::template(key).unwrap_or(key),
        args,
        accent,
        muted,
    )
}

/// The hints `hints` (catalog texts) packed into rows at most `width` columns wide, a hint never
/// split across rows - one wider than `width` takes a row of its own - and each hint's row with the
/// columns it covers.
pub fn pack(
    hints: &[String],
    accent: Option<Color>,
    muted: Option<Color>,
    width: u16,
) -> (Vec<Line<'static>>, Vec<(usize, std::ops::Range<usize>)>) {
    let mut rows: Vec<Line<'static>> = Vec::new();
    let mut ranges = Vec::new();
    let mut row = Line::default();
    for hint in hints.iter().map(|part| render(part, &[], accent, muted)) {
        let wanted = row.width() + SEPARATOR.chars().count() + hint.width();
        if row.width() > 0 && wanted > usize::from(width) {
            rows.push(std::mem::take(&mut row));
        }
        if row.width() > 0 {
            row.spans.push(Span::raw(SEPARATOR));
        }
        let start = row.width();
        row.spans.extend(hint.spans);
        ranges.push((rows.len(), start..row.width()));
    }
    rows.push(row);
    (rows, ranges)
}

/// The hints `hints` (catalog texts) that fit one row `width` columns wide, in order: all of them
/// when they fit, else those that fit before `more`, which then ends the row in place of the rest.
pub fn fit(hints: &[String], more: &str, accent: Option<Color>, width: u16) -> Vec<String> {
    let width = usize::from(width);
    let separator = SEPARATOR.chars().count();
    let columns = |hint: &str| render(hint, &[], accent, None).width();
    let all: usize = hints.iter().map(|hint| columns(hint)).sum::<usize>()
        + separator * hints.len().saturating_sub(1);
    if all <= width {
        return hints.to_vec();
    }
    let room = width.saturating_sub(separator + columns(more));
    let mut kept = Vec::new();
    let mut used = 0;
    for hint in hints {
        let gap = if kept.is_empty() { 0 } else { separator };
        if used + gap + columns(hint) > room {
            break;
        }
        used += gap + columns(hint);
        kept.push(hint.clone());
    }
    kept.push(more.to_string());
    kept
}

/// `template` with `args` filled in and its marked keys styled (see [`line`]).
fn render(
    template: &str,
    args: &[(&str, &str)],
    accent: Option<Color>,
    muted: Option<Color>,
) -> Line<'static> {
    let fill = |part: &str| {
        if args.is_empty() {
            stt_client::format(part, &[])
        } else {
            stt_client::format(part, args)
        }
    };
    let spans = parts(template)
        .into_iter()
        .map(|(is_key, part)| match (is_key, accent) {
            (false, _) => match muted {
                Some(muted) => Span::styled(fill(part), Style::new().fg(muted)),
                None => Span::raw(fill(part)),
            },
            (true, Some(accent)) => Span::styled(
                fill(part),
                Style::new().fg(accent).add_modifier(Modifier::UNDERLINED),
            ),
            (true, None) => Span::raw(format!("[{}]", fill(part))),
        })
        .collect::<Vec<_>>();
    Line::from(spans)
}

/// A hint template split for the key reference's table: its keys (the bracketed parts, joined by
/// ` / `) and its words. A letter key inside its word keeps the word whole (`[p]ause` is `p` and
/// `pause`, `re[v]ert` `v` and `revert`); any other key leaves the words after it (`[Enter] open`
/// is `Enter` and `open`). A hint without a key has an empty key.
pub fn key_and_words(template: &str) -> (String, String) {
    let parts = parts(template);
    let keys: Vec<&str> = parts
        .iter()
        .filter(|(is_key, _)| *is_key)
        .map(|(_, part)| *part)
        .collect();
    // A letter key inside its word touches a letter of it, before or after (`[/]search` is `/`
    // and `search`).
    let in_word = keys.len() == 1
        && keys[0].chars().all(char::is_alphanumeric)
        && parts.iter().enumerate().any(|(index, (is_key, _))| {
            *is_key
                && (index > 0 && parts[index - 1].1.ends_with(char::is_alphanumeric)
                    || parts
                        .get(index + 1)
                        .is_some_and(|(_, next)| next.starts_with(char::is_alphanumeric)))
        });
    let words: String = parts
        .iter()
        .filter(|(is_key, _)| in_word || !is_key)
        .map(|(_, part)| *part)
        .collect();
    (keys.join(" / "), words.trim().to_string())
}

/// `template` cut into its plain runs and its bracketed keys, in order; an unclosed `[` is text.
fn parts(template: &str) -> Vec<(bool, &str)> {
    let mut parts = Vec::new();
    let mut rest = template;
    while let Some(open) = rest.find('[') {
        let Some(close) = rest[open..].find(']').map(|close| open + close) else {
            break;
        };
        if open > 0 {
            parts.push((false, &rest[..open]));
        }
        parts.push((true, &rest[open + 1..close]));
        rest = &rest[close + 1..];
    }
    if !rest.is_empty() {
        parts.push((false, rest));
    }
    parts
}

#[cfg(test)]
mod tests {
    use super::{SEPARATOR, pack, parts};
    use crate::footer::{HISTORY, QUEUE, TRANSCRIPT};

    #[test]
    fn a_template_splits_into_its_text_and_its_keys() {
        assert_eq!(
            parts("re[v]ert · [Enter] open"),
            vec![
                (false, "re"),
                (true, "v"),
                (false, "ert · "),
                (true, "Enter"),
                (false, " open"),
            ]
        );
        assert_eq!(parts("no keys [here"), vec![(false, "no keys [here")]);
    }

    #[test]
    fn a_footer_packs_whole_hints_into_rows_no_wider_than_the_terminal() {
        let mut tight = 0;
        for width in [20u16, 40, 80, 200] {
            for list in [QUEUE, HISTORY, TRANSCRIPT] {
                let hints: Vec<String> = list
                    .iter()
                    .map(|(key, _)| stt_client::text(key, &[]))
                    .collect();
                for row in &pack(&hints, None, None, width).0 {
                    let text = row
                        .spans
                        .iter()
                        .map(|span| span.content.as_ref())
                        .collect::<String>();
                    // A single hint may be wider than the terminal; anything else is not.
                    let on_row = text.split(SEPARATOR).count();
                    assert!(
                        row.width() <= usize::from(width) || on_row == 1,
                        "at {width}: {text:?} splits a hint or overflows"
                    );
                    if on_row > 1 {
                        tight += 1;
                    }
                }
            }
        }
        assert!(tight > 0, "some width packs more than one hint a row");
    }
}
