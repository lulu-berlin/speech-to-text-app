package stt.queue

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class TaskStatusSpec :
    BehaviorSpec({

        given("each allowed transition from the spec's table") {
            `when`("it is checked against the table") {
                then("it is allowed") {
                    for ((from, to) in allowedTransitions) {
                        from.canTransitionTo(to).shouldBe(true)
                    }
                }
            }
        }

        given("every status pair from a six-by-six sweep, including all pairs out of a terminal status") {
            `when`("the pair is not in the spec's allowed table") {
                then("the transition is not allowed") {
                    for (from in TaskStatus.entries) {
                        for (to in TaskStatus.entries) {
                            if ((from to to) !in allowedTransitions) {
                                from.canTransitionTo(to).shouldBe(false)
                            }
                        }
                    }
                }
            }
        }
    })
