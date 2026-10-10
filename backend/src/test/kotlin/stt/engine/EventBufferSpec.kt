package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The engine's event flow and the SSE stream's numbered bridge are built from one [EVENT_BUFFER]
 * constant: a capacity change is one edit, not two literals that can silently diverge (item 28,
 * finding 5). The test pins the shared constant's value.
 */
class EventBufferSpec :
    BehaviorSpec({
        given("the engine's event flow and the SSE stream") {
            `when`("their shared buffer constant") {
                then("is the capacity both flows are configured with") {
                    EVENT_BUFFER shouldBe 64
                }
            }
        }
    })