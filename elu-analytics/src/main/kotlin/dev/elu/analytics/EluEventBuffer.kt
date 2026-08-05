package dev.elu.analytics

/**
 * In-memory FIFO for facade ops issued while `pending` (no usable config yet).
 * Cap 100, drop-oldest. Never persisted, never sent — dropped wholesale if the
 * device turns out to be blocked/disabled.
 */
internal class EluEventBuffer(private val capacity: Int = 100) {
    private val ops = ArrayDeque<() -> Unit>()

    @Synchronized
    fun add(op: () -> Unit) {
        if (ops.size >= capacity) ops.removeFirst()
        ops.addLast(op)
    }

    @Synchronized
    fun drain(): List<() -> Unit> {
        val out = ops.toList()
        ops.clear()
        return out
    }

    @Synchronized
    fun clear() {
        ops.clear()
    }
}
