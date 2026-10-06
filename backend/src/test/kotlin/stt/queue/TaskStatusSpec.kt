package stt.queue

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class TaskStatusSpec :
    BehaviorSpec({

        given("each allowed transition from the spec's table") {
            `when`("it is applied") {
                then("it is accepted and yields exactly the target status") {
                    for ((from, to) in allowedTransitions) {
                        from.transition(to).shouldBe(TransitionOutcome.Accepted(to))
                    }
                }
            }
        }

        given("every status pair from a six-by-six sweep, including all pairs out of a terminal status") {
            `when`("the pair is not in the spec's allowed table") {
                then("the transition is rejected with that exact pair") {
                    for (from in TaskStatus.entries) {
                        for (to in TaskStatus.entries) {
                            if ((from to to) !in allowedTransitions) {
                                from.transition(to).shouldBe(TransitionOutcome.Rejected(from, to))
                            }
                        }
                    }
                }
            }
        }
    })
