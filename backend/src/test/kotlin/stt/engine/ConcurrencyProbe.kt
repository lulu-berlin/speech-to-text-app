package stt.engine

/** Records how many calls are in flight at once, so a test can assert the engine keeps the concurrency at 1. */
internal class ConcurrencyProbe {
    private var active = 0

    /** The highest number of calls that were ever in flight together. */
    var max: Int = 0
        private set

    suspend fun <T> around(block: suspend () -> T): T {
        active++
        if (active > max) max = active
        try {
            return block()
        } finally {
            active--
        }
    }
}
