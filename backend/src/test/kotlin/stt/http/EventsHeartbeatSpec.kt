package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Item 29, fix 5 (ADR 0044): an idle `/api/events` stream carries a visible `heartbeat` event after 5 s of
 * silence, so a client can tell an idle server from a silently partitioned one. Runs on the real 5 s interval
 * (user decision), so the contract value itself is pinned.
 */
class EventsHeartbeatSpec :
    BehaviorSpec({

        given("an SSE subscriber on an idle server") {
            `when`("nothing else is sent after the snapshot") {
                then("a heartbeat shaped like heartbeat.json arrives 5 s after the last frame, and again while idle") {
                    val dataDir = tempdir("stt-events-heartbeat").toPath()

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        // Each heartbeat follows 5 s of silence: not much sooner (a busy stream needs none), and
                        // within a margin for scheduling.
                        repeat(2) {
                            val silence = TimeSource.Monotonic.markNow()
                            val (name, data) = events.readEvent(lineTimeout = 8.seconds)
                            silence.elapsedNow().inWholeMilliseconds shouldBeInRange 4_500L..7_000L
                            name shouldBe "heartbeat"
                            Json.parseToJsonElement(data).jsonObject shouldHaveShapeOf apiExample("heartbeat.json")
                        }
                    }
                }
            }
        }
    })
