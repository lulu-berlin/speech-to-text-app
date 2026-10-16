package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.config.realSpeakerModelPath
import stt.db.Speaker

class LibriSpeechDiarizationSpec :
    BehaviorSpec({
        tags(Integration)

        given("LibriSpeech clips of speakers 1089, 1188 and 1089 concatenated into one recording, each clip split into two segments") {
            `when`("the recording is embedded with the real model, labelled in auto mode and collapsed into turns") {
                then("the segments are labelled [0, 0, 1, 1, 0, 0] and the three turns change speaker at the clip boundaries") {
                    // Item 08's clips (docs/attributions.md); concatenated as item 11 rebuilds a task's PCM from its chunks.
                    val ids = listOf("1089-134686-0002", "1188-133604-0025", "1089-134691-0023")
                    val pcms = decodeLibrispeech(tempdir("librispeech").toPath(), ids).values.toList()
                    val pcm = FloatArray(pcms.sumOf { it.size })
                    var offsetSamples = 0
                    for (clip in pcms) {
                        clip.copyInto(pcm, offsetSamples)
                        offsetSamples += clip.size
                    }
                    // Clip boundaries in whole ms; each clip's two segments meet at its midpoint.
                    val boundariesMs = pcms.runningFold(0L) { offsetMs, clip -> offsetMs + clip.size / 16L }
                    val segments =
                        boundariesMs.zipWithNext().flatMap { (startMs, endMs) ->
                            val midMs = (startMs + endMs) / 2
                            listOf(segment(startMs, midMs), segment(midMs, endMs))
                        }

                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embeddings = Embeddings(model).embed(pcmSource(pcm), segments)
                        val speakerIds = speakerIds(segments, embeddings, k = null, maxSpeakers = 8)

                        speakerIds.shouldBe(listOf(0, 0, 1, 1, 0, 0))

                        val labelled = segments.zip(speakerIds) { segment, speakerId -> segment.copy(speakerId = speakerId) }
                        val speakers = listOf(Speaker(speakerId = 0, speaker = "Speaker 1"), Speaker(speakerId = 1, speaker = "Speaker 2"))
                        speakerTurns(labelled, speakers).map { Triple(it.speaker, it.startMs, it.endMs) }.shouldBe(
                            listOf(
                                Triple("Speaker 1", boundariesMs[0], boundariesMs[1]),
                                Triple("Speaker 2", boundariesMs[1], boundariesMs[2]),
                                Triple("Speaker 1", boundariesMs[2], boundariesMs[3]),
                            ),
                        )
                    }
                }
            }
        }
    })
