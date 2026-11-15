//! The shared UI text every frontend shows (ADR 0067): compiled from
//! `frontend/src/locales/<locale>.json` by the build script, so the tray, the CLI and the TUI use
//! the browser's words. The app's language (item 88) picks the catalog, and the same language's
//! format locale writes the dates and numbers, as the browser's `Intl` does.

use std::str::FromStr;
use std::sync::RwLock;

use icu::calendar::Date as CalendarDate;
use icu::datetime::DateTimeFormatter;
use icu::datetime::fieldsets::{MD, YMD};
use icu::decimal::DecimalFormatter;
use icu::decimal::input::Decimal;
use icu::locale::Locale as IcuLocale;
use icu::plurals::{PluralCategory, PluralRules};

include!(concat!(env!("OUT_DIR"), "/ui_text.rs"));

/// The locale every frontend falls back to.
pub const DEFAULT_LOCALE: &str = "en";

/// The app's language: the UI locale whose catalog the text comes from, and the full BCP 47 tag
/// its dates and numbers are formatted with.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Locale {
    pub ui: String,
    pub format: String,
}

static LOCALE: RwLock<Option<Locale>> = RwLock::new(None);

/// Sets the app's language from the stored `appLanguage` and the system's languages (item 88):
/// the stored one when it ships a catalog, otherwise the first system language that does,
/// otherwise English - the browser's rule. The format locale is the system's tag when it names the
/// same UI locale, else the locale's canonical region.
pub fn set_locale(app_language: Option<&str>, system_tags: &[String]) -> Locale {
    let ui = effective_ui_locale(app_language, system_tags);
    let locale = Locale {
        format: format_locale_for(&ui, system_tags),
        ui,
    };
    *LOCALE
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(locale.clone());
    locale
}

/// The app's language, resolved from the environment when nothing set it.
pub fn locale() -> Locale {
    let stored = LOCALE
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    stored.unwrap_or_else(|| set_locale(None, &system_language_tags()))
}

/// The text for `key` in the app's language, with each `{name}` placeholder replaced by the value
/// in `args` and each plural chosen by its count. A key in no catalog is returned as itself, so
/// what is missing shows up rather than disappearing.
pub fn text(key: &str, args: &[(&str, &str)]) -> String {
    text_in(&locale(), key, args)
}

/// [`text`] in `locale`, falling back to English and then to `key`.
pub fn text_in(locale: &Locale, key: &str, args: &[(&str, &str)]) -> String {
    let template = lookup(&locale.ui, key).unwrap_or(key);
    format_message(template, args, locale)
}

/// `key`'s template in `ui`'s catalog, falling back to English: the raw message, before its
/// placeholders and plurals are filled. None when no catalog has the key.
fn lookup(ui: &str, key: &str) -> Option<&'static str> {
    catalog(ui)
        .iter()
        .find(|(candidate, _)| *candidate == key)
        .or_else(|| catalog(DEFAULT_LOCALE).iter().find(|(k, _)| *k == key))
        .map(|(_, template)| *template)
}

/// `key`'s template in the app's language, falling back to English: the raw message, before its
/// placeholders and plurals are filled. None when no catalog has the key.
pub fn template(key: &str) -> Option<&'static str> {
    lookup(&locale().ui, key)
}

/// `template` - a catalog message, such as a key hint's - with its placeholders and plural filled
/// in the app's language.
pub fn format(template: &str, args: &[(&str, &str)]) -> String {
    format_message(template, args, &locale())
}

/// `value` in the app's language's digits and separators, with `decimals` fraction digits.
pub fn number(value: f64, decimals: usize) -> String {
    number_in(&locale(), value, decimals)
}

/// [`number`] in `locale`.
pub fn number_in(locale: &Locale, value: f64, decimals: usize) -> String {
    digits_in(locale, &format!("{value:.decimals$}"))
}

/// `value` in the app's language's digits, grouped as the locale groups.
pub fn integer(value: i64) -> String {
    integer_in(&locale(), value)
}

/// [`integer`] in `locale`.
pub fn integer_in(locale: &Locale, value: i64) -> String {
    digits_in(locale, &value.to_string())
}

/// A date as the locale writes its medium date: `Oct 9, 2026`, `9. Okt. 2026`, `2026/10/09`.
pub fn date_medium(ms: i64) -> String {
    date_medium_in(&locale(), ms)
}

/// [`date_medium`] in `locale`.
pub fn date_medium_in(locale: &Locale, ms: i64) -> String {
    date_in(locale, ms, DateFormat::Full)
}

/// A date as the locale writes its month and day: `Oct 9`, `9. Okt.`.
pub fn date_month_day(ms: i64) -> String {
    date_month_day_in(&locale(), ms)
}

/// [`date_month_day`] in `locale`.
pub fn date_month_day_in(locale: &Locale, ms: i64) -> String {
    date_in(locale, ms, DateFormat::MonthDay)
}

/// A duration as the browser writes it: `mm:ss` under an hour, `h:mm:ss` from one, in the app's
/// language's digits.
pub fn duration(ms: i64) -> String {
    duration_in(&locale(), ms)
}

/// [`duration`] in `locale`.
pub fn duration_in(locale: &Locale, ms: i64) -> String {
    let total = ms.max(0) / 1000;
    let (hours, minutes, seconds) = (total / 3600, (total % 3600) / 60, total % 60);
    let zero = digits_in(locale, "0");
    let padded = |value: i64| {
        let text = digits_in(locale, &value.to_string());
        if text.chars().count() < 2 {
            format!("{zero}{text}")
        } else {
            text
        }
    };
    if hours > 0 {
        format!(
            "{}:{}:{}",
            digits_in(locale, &hours.to_string()),
            padded(minutes),
            padded(seconds)
        )
    } else {
        format!("{}:{}", padded(minutes), padded(seconds))
    }
}

/// Which of the locale's date patterns a date is written in.
enum DateFormat {
    /// The locale's medium date: `Oct 9, 2026`, `9. Okt. 2026`, `2026/10/09`.
    Full,
    /// The locale's month and day: `Oct 9`, `9. Okt.`.
    MonthDay,
}

fn date_in(locale: &Locale, ms: i64, format: DateFormat) -> String {
    let Some(date) = chrono::DateTime::from_timestamp_millis(ms) else {
        return String::new();
    };
    let Ok(id) = locale.format.parse::<IcuLocale>() else {
        return String::new();
    };
    let Ok(day) = CalendarDate::try_new_iso(
        chrono::Datelike::year(&date),
        u8::try_from(chrono::Datelike::month(&date)).unwrap_or(1),
        u8::try_from(chrono::Datelike::day(&date)).unwrap_or(1),
    ) else {
        return String::new();
    };
    let input = day;
    match format {
        DateFormat::MonthDay => DateTimeFormatter::try_new(id.id.into(), MD::medium())
            .map(|formatter| formatter.format(&input).to_string())
            .unwrap_or_default(),
        DateFormat::Full => DateTimeFormatter::try_new(id.id.into(), YMD::medium())
            .map(|formatter| formatter.format(&input).to_string())
            .unwrap_or_default(),
    }
}

/// `value` - a decimal string such as `57.7` - in `locale`'s digits and separators.
fn digits_in(locale: &Locale, value: &str) -> String {
    let Ok(id) = locale.format.parse::<IcuLocale>() else {
        return value.to_string();
    };
    let Ok(decimal) = Decimal::from_str(value) else {
        return value.to_string();
    };
    DecimalFormatter::try_new(id.id.into(), Default::default())
        .map(|formatter| formatter.format_to_string(&decimal))
        .unwrap_or_else(|_| value.to_string())
}

/// The system's languages, preferred first: `LANGUAGE`'s list, then the POSIX locale variables,
/// then the OS's own answer (Windows, macOS).
pub fn system_language_tags() -> Vec<String> {
    let mut tags = Vec::new();
    if let Ok(list) = std::env::var("LANGUAGE") {
        tags.extend(
            list.split(':')
                .filter(|tag| !tag.is_empty())
                .map(str::to_owned),
        );
    }
    for name in ["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(value) = std::env::var(name)
            && let Some(tag) = posix_tag(&value)
        {
            tags.push(tag);
        }
    }
    if tags.is_empty()
        && let Some(tag) = sys_locale::get_locale()
    {
        tags.push(tag);
    }
    tags
}

/// A POSIX locale value as a BCP 47 tag: `pt_BR.UTF-8` is `pt-BR`; `C` and `POSIX` name no
/// language.
fn posix_tag(value: &str) -> Option<String> {
    let value = value.split('.').next().unwrap_or("");
    let value = value.split('@').next().unwrap_or("");
    if value.is_empty() || value == "C" || value == "POSIX" {
        return None;
    }
    Some(value.replace('_', "-"))
}

/// The shipped UI locale a system language list asks for, or the default when none is shipped.
pub fn resolve_ui_locale(tags: &[String]) -> String {
    for raw in tags {
        if let Some((_, code)) = match_tag(raw)
            && shipped(&code)
        {
            return code;
        }
    }
    DEFAULT_UI_LOCALE.to_string()
}

/// The UI locale the app runs in: the stored App language when it ships a catalog, otherwise the
/// one the system's languages resolve to.
pub fn effective_ui_locale(app_language: Option<&str>, tags: &[String]) -> String {
    match app_language {
        Some(code) if shipped(code) => code.to_string(),
        _ => resolve_ui_locale(tags),
    }
}

/// The full BCP 47 tag formatting uses for a UI locale: the system's tag when it names the same UI
/// locale, else the locale's canonical region.
pub fn format_locale_for(ui_locale: &str, tags: &[String]) -> String {
    let Some((code, format)) = UI_LOCALES
        .iter()
        .find(|(candidate, _)| candidate.eq_ignore_ascii_case(ui_locale))
    else {
        return ui_locale.to_string();
    };
    for raw in tags {
        if let Some((tag, matched)) = match_tag(raw)
            && matched == *code
            && has_region(&tag)
        {
            return tag;
        }
    }
    (*format).to_string()
}

/// Whether a UI locale ships a catalog.
fn shipped(code: &str) -> bool {
    UI_LOCALES.iter().any(|(candidate, _)| *candidate == code)
}

/// The shipped UI locale a system tag names, with the tag normalized (trimmed, `_` as `-`), or
/// none when it names no shipped locale.
fn match_tag(raw: &str) -> Option<(String, String)> {
    let tag = raw.trim().replace('_', "-");
    if tag.is_empty() {
        return None;
    }
    let lower = tag.to_lowercase();
    if let Some((code, _)) = UI_LOCALES
        .iter()
        .find(|(candidate, _)| candidate.to_lowercase() == lower)
    {
        return Some((tag, (*code).to_string()));
    }
    let mut parts = lower.split('-');
    let base = parts.next().unwrap_or("");
    let region = parts.next();
    base_match(base, region).map(|code| (tag, code))
}

/// A language subtag whose system tag names no shipped UI locale directly: the split languages
/// choose a variant, `jv` is the BCP 47 tag for whisper's `jw`, and a language shipped as exactly
/// one locale takes it.
fn base_match(base: &str, region: Option<&str>) -> Option<String> {
    let code = match base {
        "zh" => {
            if matches!(region, Some("tw" | "hk" | "mo" | "hant")) {
                "zh-Hant"
            } else {
                "zh-Hans"
            }
        }
        "es" => {
            if region.is_none() || region == Some("es") {
                "es-ES"
            } else {
                "es-419"
            }
        }
        "pt" => {
            if region == Some("br") {
                "pt-BR"
            } else {
                "pt-PT"
            }
        }
        "jv" => "jw",
        _ => {
            let mut shipped = UI_LOCALES
                .iter()
                .map(|(candidate, _)| *candidate)
                .filter(|candidate| candidate.to_lowercase().split('-').next() == Some(base));
            let first = shipped.next()?;
            return if shipped.next().is_none() {
                Some(first.to_string())
            } else {
                None
            };
        }
    };
    Some(code.to_string())
}

/// Whether a BCP 47 tag names a region: a two-letter or three-digit subtag after the language.
fn has_region(tag: &str) -> bool {
    tag.split('-').skip(1).any(|part| {
        (part.len() == 2 && part.chars().all(|c| c.is_ascii_alphabetic()))
            || (part.len() == 3 && part.chars().all(|c| c.is_ascii_digit()))
    })
}

/// `template` with its arguments filled in: `{name}`, `{name, plural, …}` (with `#` for the
/// number), `{name, select, …}`, `{name, number}`, ICU's apostrophe quoting, and the browser's
/// tags (`<link>…</link>`), whose text stays.
fn format_message(template: &str, args: &[(&str, &str)], locale: &Locale) -> String {
    let mut out = String::new();
    render(template, args, locale, None, &mut out);
    out
}

fn render(
    template: &str,
    args: &[(&str, &str)],
    locale: &Locale,
    plural: Option<&str>,
    out: &mut String,
) {
    let chars: Vec<char> = template.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '\'' => {
                // ICU quotes only what follows a syntax character: `''` is one apostrophe,
                // `'{'` a literal brace, and any other apostrophe is itself (`Can't`).
                match chars.get(i + 1) {
                    Some('\'') => {
                        out.push('\'');
                        i += 2;
                    }
                    Some('{' | '}' | '#') => {
                        i += 1;
                        while i < chars.len() && chars[i] != '\'' {
                            out.push(chars[i]);
                            i += 1;
                        }
                        i += 1;
                    }
                    _ => {
                        out.push('\'');
                        i += 1;
                    }
                }
            }
            '#' if plural.is_some() => {
                out.push_str(&digits_in(locale, plural.unwrap_or_default()));
                i += 1;
            }
            '{' => {
                let (body, next) = take_braced(&chars, i);
                render_argument(&body, args, locale, out);
                i = next;
            }
            '<' => {
                i += 1;
                while i < chars.len() && chars[i] != '>' {
                    i += 1;
                }
                i += 1;
            }
            character => {
                out.push(character);
                i += 1;
            }
        }
    }
}

/// The inside of the `{…}` that starts at `start`, and the index after its `}`.
fn take_braced(chars: &[char], start: usize) -> (String, usize) {
    let mut depth = 0usize;
    let mut body = String::new();
    let mut i = start;
    while i < chars.len() {
        match chars[i] {
            '{' => {
                depth += 1;
                if depth > 1 {
                    body.push('{');
                }
            }
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return (body, i + 1);
                }
                body.push('}');
            }
            character => body.push(character),
        }
        i += 1;
    }
    (body, i)
}

fn render_argument(body: &str, args: &[(&str, &str)], locale: &Locale, out: &mut String) {
    let parts = split_top_level(body);
    let name = parts.first().map(|part| part.trim()).unwrap_or("");
    let value = args
        .iter()
        .find(|(key, _)| *key == name)
        .map(|(_, value)| *value);
    let missing = || {
        let mut text = String::from("{");
        text.push_str(name);
        text.push('}');
        text
    };
    match parts.get(1).map(|part| part.trim()) {
        None => out.push_str(&value.map_or_else(missing, str::to_owned)),
        Some("plural") => {
            let options = options_of(parts.get(2).map(|part| part.trim()).unwrap_or(""));
            let count = value.unwrap_or("0");
            let category = plural_category(locale, count);
            let branch = options
                .iter()
                .find(|(key, _)| *key == category)
                .or_else(|| options.iter().find(|(key, _)| *key == "other"))
                .map(|(_, branch)| branch.as_str())
                .unwrap_or_default();
            render(branch, args, locale, Some(count), out);
        }
        Some("select") => {
            let options = options_of(parts.get(2).map(|part| part.trim()).unwrap_or(""));
            let branch = value
                .and_then(|value| {
                    options
                        .iter()
                        .find(|(key, _)| *key == value)
                        .map(|(_, branch)| branch.as_str())
                })
                .or_else(|| {
                    options
                        .iter()
                        .find(|(key, _)| *key == "other")
                        .map(|(_, branch)| branch.as_str())
                })
                .unwrap_or_default();
            render(branch, args, locale, None, out);
        }
        Some("number") => {
            out.push_str(&value.map_or_else(missing, |value| digits_in(locale, value)))
        }
        Some(_) => out.push_str(&value.map_or_else(missing, str::to_owned)),
    }
}

/// The top-level comma-separated parts of an argument: its name, its type and its style.
fn split_top_level(body: &str) -> Vec<String> {
    let mut parts = Vec::new();
    let mut current = String::new();
    let mut depth = 0usize;
    for character in body.chars() {
        match character {
            '{' => {
                depth += 1;
                current.push(character);
            }
            '}' => {
                depth = depth.saturating_sub(1);
                current.push(character);
            }
            ',' if depth == 0 => {
                parts.push(std::mem::take(&mut current));
            }
            character => current.push(character),
        }
    }
    parts.push(current);
    parts
}

/// A plural's or select's options: `one {…} other {…}` as key and branch.
fn options_of(style: &str) -> Vec<(String, String)> {
    let chars: Vec<char> = style.chars().collect();
    let mut options = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        let start = i;
        while i < chars.len() && !chars[i].is_whitespace() && chars[i] != '{' {
            i += 1;
        }
        let key: String = chars[start..i].iter().collect();
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        if i >= chars.len() || chars[i] != '{' {
            break;
        }
        let (branch, next) = take_braced(&chars, i);
        options.push((key, branch));
        i = next;
    }
    options
}

/// The plural category `count` falls in for `locale`'s format locale.
fn plural_category(locale: &Locale, count: &str) -> String {
    let Ok(id) = locale.format.parse::<IcuLocale>() else {
        return "other".to_string();
    };
    let Ok(rules) = PluralRules::try_new(id.id.into(), Default::default()) else {
        return "other".to_string();
    };
    let Ok(decimal) = Decimal::from_str(count.trim()) else {
        return "other".to_string();
    };
    match rules.category_for(&decimal) {
        PluralCategory::Zero => "zero",
        PluralCategory::One => "one",
        PluralCategory::Two => "two",
        PluralCategory::Few => "few",
        PluralCategory::Many => "many",
        PluralCategory::Other => "other",
    }
    .to_string()
}
