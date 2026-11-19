//! The language list's codes, which the settings page's language row chooses from.

/// The language codes the service accepts, as `docs/api/languages.json` lists them (the browser's
/// own list); `None` when that cannot be read.
pub fn language_codes() -> Option<Vec<String>> {
    stt_client::languages()
        .ok()
        .map(|list| list.into_iter().map(|language| language.code).collect())
}
