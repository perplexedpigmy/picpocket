package com.picpocket.app.debug

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object TracingBuffer {
    private const val CAPACITY = 1000

    @Volatile
    var enabled: Boolean = false

    private val buffer = arrayOfNulls<LogEntry>(CAPACITY)
    private var head: Int = 0
    private var size: Int = 0
    private val lock = ReentrantReadWriteLock()

    fun push(entry: LogEntry) {
        if (!enabled) return
        lock.write {
            buffer[head] = entry
            head = (head + 1) % CAPACITY
            if (size < CAPACITY) size++
        }
    }

    fun getEntries(): List<LogEntry> {
        lock.read {
            if (size == 0) return emptyList()
            val start = if (size < CAPACITY) 0 else head
            val result = mutableListOf<LogEntry>()
            for (i in 0 until size) {
                val idx = (start + i) % CAPACITY
                buffer[idx]?.let { result.add(it) }
            }
            return result.toList()
        }
    }

    fun clear() {
        lock.write {
            buffer.fill(null)
            head = 0
            size = 0
        }
    }
}
