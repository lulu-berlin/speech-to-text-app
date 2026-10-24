package stt.db

/**
 * The segments without their stored row ids, for assertions about the stored content. Item 52 added the id to
 * [Segment]; the tests that check ordering, text, timings and speakers deliberately ignore it (the id itself is
 * asserted by `TurnsSpec` and `SegmentEditRoutesSpec`).
 */
internal fun List<Segment>.withoutIds(): List<Segment> = map { it.copy(id = null) }
