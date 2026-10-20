package stt.process

/**
 * The host platform's kill(2) signal numbers. SIGSTOP and SIGCONT differ between Linux and Darwin
 * (macOS/BSD): Linux 19/18, Darwin 17/19. SIGTERM and SIGKILL are 15/9 on both platforms, so they
 * stay in [ProcessTree]. An unknown platform falls back to Linux, today's behavior; Windows is
 * roadmap item 49.
 */
internal data class ProcessSignals(
    val stop: Int,
    val cont: Int,
) {
    companion object {
        val LINUX = ProcessSignals(stop = 19, cont = 18)

        /** macOS/BSD: SIGSTOP is 17 and SIGCONT is 19 (swapped against Linux). */
        val DARWIN = ProcessSignals(stop = 17, cont = 19)

        /** The JVM's `os.name`: "Mac OS X" on macOS, "Linux" on Linux. */
        fun forOsName(osName: String): ProcessSignals = if (osName.startsWith("Mac")) DARWIN else LINUX

        fun current(): ProcessSignals = forOsName(System.getProperty("os.name"))
    }
}
