package stt.process

/**
 * The host platform's process-control facts: the kill(2) signal numbers and whether a run can own
 * its own process group. SIGSTOP and SIGCONT differ between Linux and Darwin (macOS/BSD): Linux
 * 19/18, Darwin 17/19. SIGTERM and SIGKILL are 15/9 on both platforms, so they stay in
 * [ProcessTree]. Linux puts every run in its own group through `setsid`; Darwin has no setsid, so a
 * run is the direct child only. An unknown platform falls back to Linux, today's behavior; Windows
 * is roadmap item 49.
 */
internal data class ProcessPlatform(
    val stop: Int,
    val cont: Int,
    /** Whether commands are spawned through `setsid` and signalled as a process group. */
    val processGroups: Boolean,
) {
    companion object {
        val LINUX = ProcessPlatform(stop = 19, cont = 18, processGroups = true)

        /** macOS/BSD: SIGSTOP is 17 and SIGCONT is 19 (swapped against Linux); no `setsid`. */
        val DARWIN = ProcessPlatform(stop = 17, cont = 19, processGroups = false)

        /** The JVM's `os.name`: "Mac OS X" on macOS, "Linux" on Linux. */
        fun forOsName(osName: String): ProcessPlatform = if (osName.startsWith("Mac")) DARWIN else LINUX

        fun current(): ProcessPlatform = forOsName(System.getProperty("os.name"))
    }
}
