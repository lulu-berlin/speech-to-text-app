package stt.process

/**
 * Freezes and continues the external process a task's chunk is running under (ADR 0004, ADR 0020):
 * `SIGSTOP` and `SIGCONT` for the whole process tree. The engine's seam for pausing a running task,
 * so engine tests observe the calls instead of real signals; [ProcessRunner] implements it.
 */
interface ProcessControl {
    /**
     * Freezes the current run, or one that is still spawning, unless the engine is between two
     * processes: with no run in flight this is a no-op, so the next process is not started frozen.
     */
    fun pause()

    /** Continues the current run and clears any pending freeze. */
    fun resume()
}
