package stt.diarization

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import stt.Integration
import stt.config.realSpeakerModelPath
import java.nio.file.Files
import java.util.Locale

class SpeakerThresholdSpec :
    BehaviorSpec({
        tags(Integration)

        given("the 18 LibriSpeech fixture clips of 6 readers (ADR 0032), whole and as 5 s excerpts at 0.5–5.5 s") {
            `when`("every pair within the whole clips and within the excerpts is compared by cosine distance") {
                then("same-speaker distances fall below the speaker threshold and different-speaker distances are at or above it") {
                    // ADR 0032: at the tuned threshold the whole-clip and 5 s-excerpt pairs are all classified correctly
                    // (1.5 s and 3 s excerpts overlap at every threshold and are not checked).
                    val ids =
                        Files
                            .list(librispeechClips())
                            .use { files -> files.map { it.fileName.toString() }.toList() }
                            .filter { it.endsWith(".flac") }
                            .map { it.removeSuffix(".flac") }
                            .sorted()
                    val pcms = decodeLibrispeech(tempdir("librispeech").toPath(), ids)
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val whole =
                            ids.map { id ->
                                id to
                                    embedder.embed(pcms.getValue(id), listOf(segment(0, pcms.getValue(id).size / 16L)))[0]
                            }
                        val excerpts =
                            ids
                                .filter { pcms.getValue(it).size / 16L >= 5_500 }
                                .map { id -> id to embedder.embed(pcms.getValue(id), listOf(segment(500, 5_500)))[0] }

                        for ((name, items) in listOf("whole clips" to whole, "5 s excerpts" to excerpts)) {
                            withClue("$name (threshold $SPEAKER_DISTANCE_THRESHOLD)") {
                                misclassifiedPairs(items).shouldBeEmpty()
                            }
                        }
                    }
                }
            }
        }
    })

/** Every pair of [items] on the wrong side of [SPEAKER_DISTANCE_THRESHOLD], described with its distance. */
private fun misclassifiedPairs(items: List<Pair<String, FloatArray>>): List<String> =
    items.indices.flatMap { a ->
        (a + 1 until items.size).mapNotNull { b ->
            val distance = 1.0 - dot(items[a].second, items[b].second)
            val sameSpeaker = items[a].first.substringBefore('-') == items[b].first.substringBefore('-')
            val wrong = if (sameSpeaker) distance >= SPEAKER_DISTANCE_THRESHOLD else distance < SPEAKER_DISTANCE_THRESHOLD
            if (wrong) "${items[a].first} vs ${items[b].first}: ${"%.4f".format(Locale.ROOT, distance)}" else null
        }
    }
