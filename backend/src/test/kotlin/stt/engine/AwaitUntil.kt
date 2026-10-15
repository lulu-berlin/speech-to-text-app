package stt.engine

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Polls [done] in real time every [every] until it holds or [timeout] passes, for specs that run the real pipeline
 * (ffmpeg, whisper-cli, CAM++). It returns either way: the caller's assertions report what did not happen.
 */
internal suspend fun awaitUntil(
    timeout: Duration = 5.minutes,
    every: Duration = 50.milliseconds,
    done: suspend () -> Boolean,
) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!done() && deadline.hasNotPassedNow()) delay(every)
}
