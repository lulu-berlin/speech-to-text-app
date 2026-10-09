package stt.outputs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class TimestampsSpec :
    BehaviorSpec({
        given("timestamps in whole milliseconds") {
            `when`("they are formatted") {
                then(
                    "minutes and hours roll over, hours are zero-padded to two digits, and hours above 99 are not wrapped",
                ) {
                    // Oracle `fmt_srt`/`fmt_vtt`: ms = int(round(t * 1000)), divmod by 3_600_000, 60_000, 1000,
                    // f"{h:02d}:{m:02d}:{s:02d},{ms:03d}" (srt) / "…{ms:03d}" with a dot (vtt).
                    fmtSrt(0) shouldBe "00:00:00,000"
                    fmtSrt(59_999) shouldBe "00:00:59,999"
                    fmtSrt(60_000) shouldBe "00:01:00,000"
                    fmtSrt(3_599_999) shouldBe "00:59:59,999"
                    fmtSrt(3_600_000) shouldBe "01:00:00,000"
                    fmtSrt(3_600_001) shouldBe "01:00:00,001"
                    fmtSrt(360_000_000) shouldBe "100:00:00,000"
                    fmtVtt(3_600_000) shouldBe "01:00:00.000"
                    fmtVtt(360_000_000) shouldBe "100:00:00.000"
                }
            }
        }
    })
