package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.docsApiFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the backend's packaged copy of the upload allow-list (`src/main/resources/allowed-extensions.json`,
 * which the upload route validates against) equal to the contract file `docs/api/allowed-extensions.json`
 * the frontend reads (item 28; ADR 0029).
 */
class AllowedExtensionsContractSpec :
    BehaviorSpec({
        given("the packaged allowed-extensions.json") {
            `when`("compared with docs/api/allowed-extensions.json") {
                then("they are identical") {
                    Files
                        .readString(
                            Path.of(
                                this::class.java.classLoader
                                    .getResource("allowed-extensions.json")!!
                                    .toURI(),
                            ),
                        ).shouldBe(Files.readString(docsApiFile("allowed-extensions.json")))
                }
            }
        }
    })