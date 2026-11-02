package stt.service

import java.io.IOException
import java.time.Duration
import java.time.Instant

/** How many restarts the service allows within [RESTART_WINDOW]; the next death counts as a failed restart. */
private const val RESTART_LIMIT = 3
private val RESTART_WINDOW: Duration = Duration.ofSeconds(60)

/**
 * The service's half of the unit's supervision (ADR 0064): the tray registers itself - its pid and the command that
 * starts it - and every look starts that command again when the tray died. A tray that keeps dying right after each
 * restart counts as a failed restart, and the service stops too: the unit is both up or both down.
 */
class TraySupervisor(
    private val startTray: (command: List<String>) -> Unit,
    private val alive: (pid: Long) -> Boolean,
    private val now: () -> Instant,
    private val stopService: (reason: String) -> Unit,
) {
    private var tray: Registered? = null
    private val restarts = mutableListOf<Instant>()

    /** The tray's registration (ADR 0064): it runs as [pid] and [command] starts it again. */
    fun registered(
        pid: Long,
        command: List<String>,
    ) {
        tray = Registered(pid, command)
    }

    /**
     * One look at the registered tray. A live tray is left alone; a dead one is started again, and the next look
     * judges the tray that registers itself in its place - or stops the service when the restarts ran out or the
     * command could not be started.
     */
    fun check() {
        val current = tray ?: return
        if (alive(current.pid)) return
        tray = null
        restarts.removeAll { Duration.between(it, now()) >= RESTART_WINDOW }
        if (restarts.size >= RESTART_LIMIT) {
            stopService(
                "the tray died again after it was restarted ${RESTART_LIMIT} times within" +
                    " ${RESTART_WINDOW.seconds} seconds",
            )
            return
        }
        try {
            startTray(current.command)
            restarts += now()
        } catch (e: IOException) {
            stopService("the tray could not be restarted: " + e.message)
        }
    }

    private data class Registered(
        val pid: Long,
        val command: List<String>,
    )
}
