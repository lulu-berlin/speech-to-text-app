package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 49 (discovered from PR #47's Windows job, run #249): the machine the backend runs on decides
 * whether libc exists, not the platform a runner was built for. A runner built with a foreign platform must
 * not load it — Windows has no libc, and the Windows job builds Darwin runners — and where the host does have
 * signals the load happens once, at construction, never inside the first pause request (CI #223/#239).
 */
class ProcessRunnerLibcSpec :
    BehaviorSpec({
        given("a runner built with a platform that is not the host's") {
            `when`("it is constructed") {
                then("libc is loaded exactly when the host has signals") {
                    var loads = 0
                    ProcessRunner(
                        gracePeriod = 1.seconds,
                        platform = foreignPlatform(),
                        loadLibc = { loads++ },
                        startProcess = { ExitedProcess() },
                    )

                    loads shouldBe if (ProcessPlatform.current().signals) 1 else 0
                }
            }
        }

        given("a runner on a host with signals") {
            `when`("nothing has signalled anything yet") {
                then("libc is already loaded, once, and pausing loads nothing more")
                    .config(enabled = ProcessPlatform.current().signals) {
                        var loads = 0
                        val runner =
                            ProcessRunner(
                                gracePeriod = 1.seconds,
                                loadLibc = { loads++ },
                                startProcess = { ExitedProcess() },
                            )

                        runner.pause()
                        runner.resume()

                        loads shouldBe 1
                    }
            }
        }
    })

/** The opposite kind of platform to the host: one without signals where the host has them, and vice versa. */
private fun foreignPlatform(): ProcessPlatform = if (ProcessPlatform.current().signals) ProcessPlatform.WINDOWS else ProcessPlatform.DARWIN
