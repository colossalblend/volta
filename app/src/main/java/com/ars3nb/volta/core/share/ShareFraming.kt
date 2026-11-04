package com.ars3nb.volta.core.share

/**
 * Cuts a message into GATT-sized frames and puts it back together. One ATT write or
 * notification carries at most MTU − 3 bytes, and an attribute value at most 512, so
 * anything longer than one packet (a public key at the default MTU of 23, any encrypted
 * link) goes as frames. Writes with response and notifications arrive in order, so the
 * receiver only has to check that each frame is the next one.
 *
 * Frame: `index (u16, big-endian) ‖ total (u16, big-endian) ‖ chunk`.
 */
object ShareFraming {

    const val HEADER = 4

    /** ATT's own header per packet. */
    private const val ATT_OVERHEAD = 3

    /** The longest attribute value ATT allows. */
    private const val ATT_MAX_VALUE = 512

    /** How many message bytes fit in one frame at this MTU. */
    fun chunkSize(mtu: Int): Int = (minOf(mtu - ATT_OVERHEAD, ATT_MAX_VALUE) - HEADER).coerceAtLeast(1)

    /** [message] as frames for [mtu]; at least one frame, even for an empty message. */
    fun split(message: ByteArray, mtu: Int): List<ByteArray> {
        val size = chunkSize(mtu)
        val total = maxOf(1, (message.size + size - 1) / size)
        require(total <= 0xFFFF) { "message too long for framing" }
        return (0 until total).map { i ->
            val from = i * size
            val to = minOf(message.size, from + size)
            header(i, total) + message.copyOfRange(from, to)
        }
    }

    private fun header(index: Int, total: Int) = byteArrayOf(
        (index shr 8).toByte(), index.toByte(), (total shr 8).toByte(), total.toByte()
    )

    sealed interface Step {
        /** More frames to come. */
        data class Partial(val received: Int, val total: Int) : Step

        /** The whole message. */
        class Complete(val message: ByteArray) : Step

        /** Out of order or malformed; start over. */
        data class Broken(val reason: String) : Step

        /** Over [Assembler.maxBytes]: not something to send this way. */
        data object TooLarge : Step
    }

    /**
     * Collects frames for one message. A frame with index 0 always starts a new message
     * (the peer started over); a repeat of the last frame is ignored.
     */
    class Assembler(private val maxBytes: Int) {
        private val buffer = java.io.ByteArrayOutputStream()
        private var next = 0
        private var total = 0

        fun add(frame: ByteArray): Step {
            if (frame.size < HEADER) return broken("short frame")
            val index = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
            val count = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
            if (count == 0 || index >= count) return broken("bad header")
            if (index == 0) {
                buffer.reset()
                next = 0
                total = count
            } else if (count != total) {
                return broken("total changed")
            } else if (index == next - 1) {
                return Step.Partial(next, total)
            } else if (index != next) {
                return broken("frame $index, expected $next")
            }
            if (buffer.size() + frame.size - HEADER > maxBytes) {
                reset()
                return Step.TooLarge
            }
            buffer.write(frame, HEADER, frame.size - HEADER)
            next = index + 1
            if (next < total) return Step.Partial(next, total)
            val message = buffer.toByteArray()
            reset()
            return Step.Complete(message)
        }

        fun reset() {
            buffer.reset()
            next = 0
            total = 0
        }

        private fun broken(reason: String): Step {
            reset()
            return Step.Broken(reason)
        }
    }
}
