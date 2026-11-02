package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.Instant

/**
 * ADR 0064 (the service's half of the mutual supervision): the tray registers itself with the service, and the
 * service starts it again when it died - so the unit is both up or both down.
 */
class TraySupervisorSpec :
    BehaviorSpec({
        given("a tray that registered itself and is still alive") {
            `when`("the service looks at it") {
                then("nothing is started") {
                    val started = mutableListOf<List<String>>()
                    val supervisor = supervisor(started, alive = { true })
                    supervisor.registered(4242, listOf("/opt/stt/bin/stt-tray"))
                    supervisor.check()
                    started shouldBe emptyList()
                }
            }
        }

        given("a registered tray whose pid is gone") {
            `when`("the service looks at it") {
                then("it starts the tray's command once, and starts nothing more until the new tray registers") {
                    val started = mutableListOf<List<String>>()
                    val supervisor = supervisor(started, alive = { false })
                    supervisor.registered(4242, listOf("/opt/stt/bin/stt-tray"))
                    supervisor.check()
                    supervisor.check()
                    started shouldBe listOf(listOf("/opt/stt/bin/stt-tray"))
                }
            }
        }

        given("a registered tray whose pid is gone and whose command cannot be started") {
            `when`("the service looks at it") {
                then("the service stops, because the unit is both up or both down") {
                    val stops = mutableListOf<String>()
                    val supervisor =
                        TraySupervisor(
                            startTray = { throw java.io.IOException("no stt-tray beside stt-service") },
                            alive = { false },
                            now = { Instant.now() },
                            stopService = { reason -> stops += reason },
                        )
                    supervisor.registered(4242, listOf("/opt/stt/bin/stt-tray"))
                    supervisor.check()
                    stops.size shouldBe 1
                    stops.single() shouldContain "could not be restarted"
                }
            }
        }
        given("a tray that keeps dying right after each restart") {
            `when`("it dies a fourth time within 60 seconds") {
                then("the restarts count as failed and the service stops") {
                    val stops = mutableListOf<String>()
                    var now = Instant.parse("2026-10-31T12:00:00Z")
                    val supervisor =
                        TraySupervisor(
                            startTray = {},
                            alive = { false },
                            now = { now },
                            stopService = { reason -> stops += reason },
                        )
                    // Each death follows a fresh registration, as the tray that was started registers itself.
                    repeat(4) { death ->
                        supervisor.registered(4242L + death, listOf("/opt/stt/bin/stt-tray"))
                        supervisor.check()
                        now = now.plusSeconds(10)
                    }
                    stops.size shouldBe 1
                    stops.single() shouldContain "3 times"
                }
            }
        }
    })

/** A supervisor with fakes: [alive] says whether a pid runs, and the service's stop is recorded in [stops]. */
internal fun supervisor(
    started: MutableList<List<String>>,
    alive: (Long) -> Boolean,
    stops: MutableList<String> = mutableListOf(),
    now: () -> Instant = { Instant.now() },
): TraySupervisor =
    TraySupervisor(
        startTray = { command -> started += command },
        alive = alive,
        now = now,
        stopService = { reason -> stops += reason },
    )
