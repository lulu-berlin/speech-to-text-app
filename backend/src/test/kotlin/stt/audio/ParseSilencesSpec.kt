package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ParseSilencesSpec :
    BehaviorSpec({
        given("silencedetect stderr lines mixed with other ffmpeg output, ending in a silence without silence_end") {
            `when`("they are parsed for a 5 s file") {
                then("each silence becomes an interval in milliseconds; the trailing one ends at the file duration") {
                    // Line formats as printed by ffmpeg 9.0.1 with -hide_banner -nostats.
                    val stderr =
                        listOf(
                            "Input #0, lavfi, from 'aevalsrc=0':",
                            "Stream mapping:",
                            "Press [q] to stop, [?] for help",
                            "Output #0, null, to 'pipe:':",
                            "[Parsed_silencedetect_0 @ 0x768494002700] silence_start: 1",
                            "[Parsed_silencedetect_0 @ 0x768494002700] silence_end: 2.500062 | silence_duration: 1.500063",
                            "[Parsed_silencedetect_0 @ 0x768494002700] silence_start: 4.25",
                            "[out#0/null @ 0x587f2f859480] video:0KiB audio:156KiB subtitle:0KiB other streams:0KiB global headers:0KiB muxing overhead: unknown",
                            "size=N/A time=00:00:05.00 bitrate=N/A speed= 687x elapsed=0:00:00.00    ",
                        )

                    val silences = parseSilences(stderr, durationMs = 5_000)

                    silences.shouldBe(
                        listOf(
                            Silence(startMs = 1_000, endMs = 2_500), // 2.500062 s rounds to 2 500 ms
                            Silence(startMs = 4_250, endMs = 5_000), // no silence_end: ends at the file duration
                        ),
                    )
                }
            }
        }
    })
