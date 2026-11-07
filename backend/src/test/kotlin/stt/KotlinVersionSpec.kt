package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Ktor 3.6.1 is built against Kotlin 2.3.21 and kotlinx-serialization 1.11.0 (its POMs), and the
 * catalog keeps the pair (ADR 0014's comment). A transitive dependency that raised the standard
 * library would make the app compile against a Kotlin newer than its Ktor; this pins what runs.
 */
class KotlinVersionSpec :
    BehaviorSpec({
        given("the Kotlin standard library the app runs on") {
            `when`("asking it for its version") {
                then("it equals Ktor's pair, 2.3.21") {
                    KotlinVersion.CURRENT.toString().shouldBe("2.3.21")
                }
            }
        }
    })
