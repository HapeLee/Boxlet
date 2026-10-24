package dev.boxlet.core.protocol.bp2

/** Owned by the session actor. Counters are cumulative, per connection direction. */
class Bp2SendCredit(window: Long = Bp2.WINDOW_BYTES) {
    var creditTotal: Long = window
        private set
    var sent: Long = 0
        private set
    val available: Long get() = creditTotal - sent
    init { require(window in 2L * Bp2.CHUNK_BYTES..32L * Bp2.CHUNK_BYTES) }

    fun update(total: Long) {
        require(total >= creditTotal) { "Credit must be monotonic" }
        require(total - sent <= 32L * Bp2.CHUNK_BYTES) { "Receive window exceeds protocol limit" }
        creditTotal = total
    }

    /** The last 1 MiB is reserved for repair, so a full out-of-order window cannot deadlock it. */
    fun reserve(bytes: Int, retry: Boolean = false): Boolean {
        require(bytes in 1..Bp2.CHUNK_BYTES)
        val required = bytes.toLong() + if (retry) 0 else Bp2.CHUNK_BYTES
        if (available < required) return false
        check(sent <= Long.MAX_VALUE - bytes) { "Credit counter exhausted; reconnect" }
        sent += bytes
        return true
    }
}

/** Credit returned once per received frame, including each retransmission of an identical chunk. */
class Bp2ReceiveCredit(window: Long = Bp2.WINDOW_BYTES, private val maxPendingFrames: Int = 1024) {
    var creditTotal: Long = window
        private set
    var received: Long = 0
        private set
    private val pending = mutableMapOf<Long, Int>()
    private var lastSequence = 0L
    init { require(window in 2L * Bp2.CHUNK_BYTES..32L * Bp2.CHUNK_BYTES && maxPendingFrames > 0) }

    fun consume(sequence: Long, bytes: Int) {
        require(sequence > lastSequence && bytes in 1..Bp2.CHUNK_BYTES)
        require(pending.size < maxPendingFrames && received <= creditTotal - bytes) { "Receive credit exceeded" }
        pending[sequence] = bytes
        received += bytes
        lastSequence = sequence
    }

    fun release(sequence: Long): Long {
        val count = pending[sequence] ?: return creditTotal
        check(creditTotal <= Long.MAX_VALUE - count) { "Credit counter exhausted; reconnect" }
        pending.remove(sequence)
        creditTotal += count
        return creditTotal
    }
}
