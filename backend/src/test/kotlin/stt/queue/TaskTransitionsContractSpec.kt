package stt.queue

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.docsApiFile
import java.nio.file.Files

/**
 * Checks the domain's transition table against the shared contract `docs/api/task-transitions.json`
 * (roadmap item 11, item 12's contract): the frontend's state machine reads the same file, so the
 * two must not drift, and every command the API offers must name an allowed transition.
 */
class TaskTransitionsContractSpec :
    BehaviorSpec({
        given("the transitions of docs/api/task-transitions.json") {
            `when`("compared with TaskStatus's allowed targets") {
                then("every status allows exactly the contract's targets") {
                    val expected =
                        contract["transitions"]!!.jsonObject.mapValues { (_, targets) ->
                            targets.jsonArray.map { TaskStatus.valueOf(it.jsonPrimitive.content) }.toSet()
                        }
                    expected.keys shouldContainExactlyInAnyOrder TaskStatus.entries.map { it.name }
                    for (from in TaskStatus.entries) {
                        for (to in TaskStatus.entries) {
                            withClue("$from → $to") {
                                from.canTransitionTo(to) shouldBe (to in expected.getValue(from.name))
                            }
                        }
                    }
                }
            }
        }

        given("the commands of docs/api/task-transitions.json") {
            `when`("checked against the transition table") {
                then("every command's target is allowed from each status it accepts") {
                    for ((command, spec) in contract["commands"]!!.jsonObject) {
                        val commandSpec = spec.jsonObject
                        val to = TaskStatus.valueOf(commandSpec.getValue("to").jsonPrimitive.content)
                        for (fromName in commandSpec.getValue("from").jsonArray) {
                            val from = TaskStatus.valueOf(fromName.jsonPrimitive.content)
                            withClue("$command: $from → $to") {
                                from.canTransitionTo(to) shouldBe true
                            }
                        }
                    }
                }
            }
        }
    })

/** The parsed contract file `docs/api/task-transitions.json`. */
private val contract: JsonObject =
    Json
        .parseToJsonElement(Files.readString(docsApiFile("task-transitions.json")))
        .jsonObject
