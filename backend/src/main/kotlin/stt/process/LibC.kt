package stt.process

import com.sun.jna.Library
import com.sun.jna.Native

/** The libc functions the runner needs, bound via JNA (ADR 0020). */
internal interface LibC : Library {
    fun kill(
        pid: Int,
        signal: Int,
    ): Int

    companion object {
        val INSTANCE: LibC = Native.load("c", LibC::class.java)
    }
}
