package stt.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The session cookie the browser gets from the launch handshake (roadmap item 66). */
internal const val SESSION_COOKIE = "stt_session"

/** The path `stt-app open` and the tray open in the browser, with the one-time token as `t`. */
internal const val OPEN_PATH = "/open"

/**
 * The browser's way into app mode (roadmap item 66): a launch token is single-use and short-lived, and redeeming it
 * starts a session whose id the browser holds in the `HttpOnly` cookie. Both live in this process only: a restart ends
 * every browser session, and the browser then enters again through a fresh launch.
 */
internal class BrowserAccess(
    private val tokenTtl: Duration = 30.seconds,
) {
    private val mutex = Mutex()
    private val tokens = mutableMapOf<String, Long>()
    private val sessions = mutableSetOf<String>()
    private val random = SecureRandom()

    /** A fresh single-use token, valid for [tokenTtl]. */
    suspend fun newToken(): String {
        val token = randomId()
        mutex.withLock { tokens[token] = System.nanoTime() + tokenTtl.inWholeNanoseconds }
        return token
    }

    /** Spends [token] and returns the session id it starts, or null when it is unknown, spent or expired. */
    suspend fun redeem(token: String): String? {
        val now = System.nanoTime()
        val valid = mutex.withLock { tokens.remove(token)?.let { now <= it } ?: false }
        if (valid != true) return null
        val session = randomId()
        mutex.withLock { sessions.add(session) }
        return session
    }

    /** Whether [session] is a live session of this process. */
    suspend fun isSession(session: String): Boolean = mutex.withLock { session in sessions }

    private fun randomId(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
