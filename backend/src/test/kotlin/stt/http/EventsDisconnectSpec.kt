package stt.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import stt.withCapturedLogs
import kotlin.time.Duration.Companion.seconds

/**
 * Item 29, fix 4: a disconnect is ordinary for SSE — a browser tab closes, a laptop sleeps. The closed
 * subscriber's response must end quietly (regression: the writer's closed-channel exception escaped the
 * route and was logged as "Unhandled exception while serving /api/events"), and every other subscriber —
 * another tab — keeps receiving each event.
 */
class EventsDisconnectSpec :
    BehaviorSpec({

        given("two SSE subscribers on an idle server, like two browser tabs") {
            `when`("one closes its stream and events keep coming") {
                then("its response ends without an error logged, and the other receives every event") {
                    val dataDir = tempdir("stt-events-disconnect").toPath()

                    withCapturedLogs(Logger.ROOT_LOGGER_NAME, Level.ERROR) { logs ->
                        SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                            val leaving = server.connectEvents()
                            val staying = server.connectEvents()
                            leaving.readEvent().first shouldBe "snapshot"
                            staying.readEvent().first shouldBe "snapshot"

                            leaving.close()

                            // Every toggle publishes queue-updated to both subscribers: the open one reads it,
                            // the closed one's route writes it into the dropped connection. Twenty writes, so a
                            // first write landing in the socket buffer cannot hide the closed channel.
                            repeat(10) {
                                server.command("POST", "/api/queue/pause") shouldBe 200
                                staying.readEvent().first shouldBe "queue-updated"
                                server.command("POST", "/api/queue/resume") shouldBe 200
                                staying.readEvent().first shouldBe "queue-updated"
                            }

                            // The closed subscriber's route runs on its own; give it time to fail its writes.
                            delay(2.seconds)
                            logs.toList().map { "${it.formattedMessage} ${it.throwableProxy?.className}" }.shouldBeEmpty()
                        }
                    }
                }
            }
        }
    })
