//! Moving through a fixed set of choices, the same way wherever the TUI does it: the language
//! list, the devices, the speakers choices and the frontends.

/// `current` moved `step` places through `choices`, wrapping round; `None` when `current` is not one
/// of them (the service offers a list the stored value fell out of).
pub fn step_choice<T, Q>(choices: &[T], current: &Q, step: isize) -> Option<T>
where
    T: Clone + PartialEq<Q>,
    Q: ?Sized,
{
    let index = choices.iter().position(|choice| choice == current)? as isize;
    let next = (index + step).rem_euclid(choices.len() as isize) as usize;
    Some(choices[next].clone())
}

/// The language codes the service accepts, as `docs/api/languages.json` lists them (the browser's
/// own list); `None` when that cannot be read.
pub fn language_codes() -> Option<Vec<String>> {
    stt_client::languages()
        .ok()
        .map(|list| list.into_iter().map(|language| language.code).collect())
}
