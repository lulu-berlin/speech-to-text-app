package stt.process

/**
 * The host platform's process-control facts: the kill(2) signal numbers and whether a run can own
 * its own process group. SIGSTOP and SIGCONT differ between Linux and Darwin (macOS/BSD): Linux
 * 19/18, Darwin 17/19. SIGTERM and SIGKILL are 15/9 on both platforms, so they stay in
 * [ProcessTree]. Linux puts every run in its own group through `setsid`; Darwin has no setsid, so a
 * run is the direct child only. Windows has no signals and no setsid: a run is the direct child,
 * killed with [Process.destroyForcibly], and a pause reaches it only through the chunk gate.
 * An unknown platform falls back to Linux, today's behavior.
 */
internal data class ProcessPlatform(
    /** SIGSTOP's number, or null where the host has no signals (Windows). */
    val stop: Int?,
    /** SIGCONT's number, or null where the host has no signals (Windows). */
    val cont: Int?,
    /** Whether commands are spawned through `setsid` and signalled as a process group. */
    val processGroups: Boolean,
    /** Whether this is Windows: the `;` PATH, `.exe` names and the `NUL` null device. */
    val windows: Boolean = false,
) {
    /** Whether the host has POSIX signals; false on Windows, where pause never freezes a process. */
    val signals: Boolean
        get() = stop != null && cont != null

    /** The null input device: `/dev/null` on POSIX, `NUL` on Windows (a run's stdin and whisper-cli's probe input). */
    val nullDevice: String
        get() = if (windows) "NUL" else "/dev/null"

    companion object {
        val LINUX = ProcessPlatform(stop = 19, cont = 18, processGroups = true)

        /** macOS/BSD: SIGSTOP is 17 and SIGCONT is 19 (swapped against Linux); no `setsid`. */
        val DARWIN = ProcessPlatform(stop = 17, cont = 19, processGroups = false)

        /** Windows: no signals, no `setsid`; the run is the direct child and is terminated directly. */
        val WINDOWS = ProcessPlatform(stop = null, cont = null, processGroups = false, windows = true)

        /** The JVM's `os.name`: "Mac OS X" on macOS, "Linux" on Linux, "Windows 11" on Windows. */
        fun forOsName(osName: String): ProcessPlatform =
            when {
                osName.startsWith("Mac") -> DARWIN
                osName.startsWith("Windows") -> WINDOWS
                else -> LINUX
            }

        fun current(): ProcessPlatform = forOsName(System.getProperty("os.name"))
    }
}
