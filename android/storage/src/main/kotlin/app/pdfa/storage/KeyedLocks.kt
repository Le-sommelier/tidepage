package app.pdfa.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One [Mutex] per key. An entry exists only while someone holds or waits for it, so the map does
 * not grow with the number of documents ever touched.
 */
internal class KeyedLocks {
    private class Slot(val mutex: Mutex, var users: Int)

    private val slots = HashMap<String, Slot>()

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val slot = synchronized(slots) {
            slots.getOrPut(key) { Slot(Mutex(), 0) }.also { it.users++ }
        }
        try {
            return slot.mutex.withLock { block() }
        } finally {
            synchronized(slots) {
                slot.users--
                if (slot.users == 0) slots.remove(key)
            }
        }
    }

    fun size(): Int = synchronized(slots) { slots.size }
}
