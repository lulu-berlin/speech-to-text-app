package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DownloadFileNameSpec :
    BehaviorSpec({
        given("original upload file names") {
            `when`("the download file name for a format is built") {
                then("it is the original file's stem plus the format's extension") {
                    // Output prefix Path(audio).stem: only the last suffix is stripped, and a leading dot
                    // (".hidden") is not a suffix.
                    downloadFileName("interview.mp3", "srt") shouldBe "interview.srt"
                    downloadFileName("talk.2024.final.m4a", "txt") shouldBe "talk.2024.final.txt"
                    downloadFileName("recording", "vtt") shouldBe "recording.vtt"
                    downloadFileName(".hidden", "txt") shouldBe ".hidden.txt"
                }
            }
        }
    })
