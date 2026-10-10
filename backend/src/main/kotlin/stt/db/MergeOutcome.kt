package stt.db

/**
 * What a speaker merge decided (item 22): [Merged] stored the mapping; [Invalid] stored nothing and says why, so the
 * route can answer `400 invalid-merge` with [Invalid.reason].
 */
sealed interface MergeOutcome {
    data object Merged : MergeOutcome

    data class Invalid(
        val reason: String,
    ) : MergeOutcome
}
