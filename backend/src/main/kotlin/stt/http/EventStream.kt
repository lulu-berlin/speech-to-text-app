package stt.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import stt.engine.EVENT_BUFFER
import stt.engine.Event
import stt.engine.ProcessingEngine

/**
 * The events an SSE connection subscribes to: the engine's stream with a per-emission sequence number, so a
 * subscriber that falls behind (the flow drops the oldest events for it) sees a gap in the numbers and the
 * connection can be closed instead of silently continuing.
 */
interface EventStream {
    val events: Flow<Pair<Long, Event>>
}

/**
 * The real [EventStream]: one bridge collector tags every engine emission `0, 1, 2, …` and republishes the
 * pairs. The bridge's own flow drops the oldest events for a lagging subscriber — exactly the semantics of
 * the engine's flow — but only *this* subscriber loses them, and the sequence numbers make the loss visible.
 */
class NumberedEventStream(
    engine: ProcessingEngine,
    scope: CoroutineScope,
) : EventStream {
    private val numbered =
        MutableSharedFlow<Pair<Long, Event>>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private var sequence = 0L

    override val events: Flow<Pair<Long, Event>> = numbered.asSharedFlow()

    init {
        // One collector, one counter: the numbers are the engine's emission order, not per-subscriber counts.
        scope.launch {
            engine.events.collect { event -> numbered.emit(sequence++ to event) }
        }
    }
}
