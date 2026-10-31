package stt.db

/**
 * What a segment reassignment decided (item 73's amendment): [Reassigned] moved the segments;
 * [Invalid] stored nothing and says why, so the route can answer `400 invalid-reassignment` with
 * [Invalid.reason].
 */
sealed interface ReassignOutcome {
    data object Reassigned : ReassignOutcome

    /** The target is no speaker of the task as readers see it: unknown, or merged into another since the route's look. */
    data object UnknownSpeaker : ReassignOutcome

    data class Invalid(
        val reason: String,
    ) : ReassignOutcome
}
