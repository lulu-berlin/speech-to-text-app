package stt.process

/**
 * Freezes and continues the external process a task's chunk is running under (ADR 0004, ADR 0020):
 * `SIGSTOP` and `SIGCONT` for the whole process tree on POSIX. Windows has no signal that freezes a
 * process, so there both are no-ops and the engine's chunk gate stops the run before its next
 * chunk (roadmap item 49). The engine's seam for pausing a running task, so engine tests observe
 * the calls instead of real signals; [ProcessRunner] implements it.
 */
interface ProcessControl {
    /**
     * Freezes the current run, or one that is still spawning, unless the engine is between two
     * processes: with no run in flight this is a no-op, so the next process is not started frozen.
     * A no-op on Windows, where the pause gate is the only stop.
     */
    fun pause()

    /** Continues the current run and clears any pending freeze. A no-op on Windows. */
    fun resume()
}
