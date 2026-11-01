package com.appsleuth.argus.sdk.recorder

import com.appsleuth.argus.sdk.model.ArgusEvent
import com.appsleuth.argus.sdk.model.EventType
import java.util.LinkedList

/**
 * Thread-safe, circular in-memory buffer that stores runtime flight events.
 */
object ArgusFlightRecorder {

    @Volatile
    var maxEvents: Int = 150

    private val lock = Any()
    private val buffer = LinkedList<ArgusEvent>()

    @Volatile
    var lastUserAction: String? = null
        private set

    fun record(event: ArgusEvent) {
        synchronized(lock) {
            if (event.type == EventType.USER_ACTION) {
                lastUserAction = event.label
            }

            buffer.addLast(event)
            if (buffer.size > maxEvents) {
                buffer.removeFirst()
            }
        }
    }

    fun getSnapshot(): List<ArgusEvent> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            lastUserAction = null
        }
    }

    fun size(): Int = synchronized(lock) { buffer.size }
}
