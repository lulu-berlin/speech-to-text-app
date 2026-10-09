package stt.engine

import stt.process.ProcessControl

/** Records the freeze and continue calls the engine makes on the running task's process. */
internal class FakeProcessControl : ProcessControl {
    /** Whether the process is frozen right now. */
    var frozen = false
        private set

    var pauses = 0
        private set

    var resumes = 0
        private set

    override fun pause() {
        frozen = true
        pauses++
    }

    override fun resume() {
        frozen = false
        resumes++
    }
}
