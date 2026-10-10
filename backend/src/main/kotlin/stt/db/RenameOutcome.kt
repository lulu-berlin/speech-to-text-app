package stt.db

/**
 * What a speaker rename decided (item 22): [Renamed] stored the name; [DuplicateName] stored nothing because
 * [DuplicateName.existing], another speaker of the same task, already has the name in some letter case, so the
 * route can answer `409 duplicate-speaker-name` naming it.
 */
sealed interface RenameOutcome {
    data object Renamed : RenameOutcome

    data class DuplicateName(
        val existing: Speaker,
    ) : RenameOutcome
}
