package stt

import io.kotest.core.Tag

/** Tests that spawn real processes; run only by `gradle integrationTest` (ADR 0021). */
object Integration : Tag()
