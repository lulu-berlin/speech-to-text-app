package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.docsApiFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the backend's packaged copy of the language list (`src/main/resources/languages.json`, which the
 * upload route validates against) equal to the contract file `docs/api/languages.json` the frontend reads
 * (item 12's contract; ADR 0029).
 */
class LanguagesContractSpec :
    BehaviorSpec({
        given("the packaged languages.json") {
            `when`("compared with docs/api/languages.json") {
                then("they are identical") {
                    Files
                        .readString(
                            Path.of(
                                this::class.java.classLoader
                                    .getResource("languages.json")!!
                                    .toURI(),
                            ),
                        ).shouldBe(Files.readString(docsApiFile("languages.json")))
                }
            }
        }
    })
