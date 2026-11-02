//! Quoting helpers for the terminal launchers (roadmap item 66).

pub(crate) fn powershell(word: &str) -> String {
    format!("'{}'", word.replace('\'', "''"))
}

pub(crate) fn posix(word: &str) -> String {
    format!("'{}'", word.replace('\'', r"'\''"))
}

pub(crate) fn applescript_string(text: &str) -> String {
    format!("\"{}\"", text.replace('\\', r"\\").replace('"', "\\\""))
}
