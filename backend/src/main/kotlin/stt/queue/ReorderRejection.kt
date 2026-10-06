package stt.queue

/**
 * Why the queue refused a reorder. When several apply, the first of
 * duplicate > unknown > missing is reported (a repeated or unknown id usually
 * takes the slot of a required one, so a missing id is mostly a symptom).
 */
sealed interface ReorderRejection {
    /** [ids] are named more than once. */
    data class DuplicateIds(
        val ids: Set<Long>,
    ) : ReorderRejection

    /** [ids] are not in the queue (never queued, or already terminal). */
    data class UnknownIds(
        val ids: Set<Long>,
    ) : ReorderRejection

    /** [ids] are in the queue but not named. */
    data class MissingIds(
        val ids: Set<Long>,
    ) : ReorderRejection
}
