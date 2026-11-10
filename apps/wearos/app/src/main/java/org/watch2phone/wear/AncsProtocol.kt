package org.watch2phone.wear

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AncsEvent(val kind: Int, val flags: Int, val id: Int) {
    val preExisting: Boolean get() = flags and 4 != 0
    val silent: Boolean get() = flags and 1 != 0
}

internal data class AncsPreview(val title: String, val message: String)

internal object AncsProtocol {
    private const val TITLE_BYTES = 80
    private const val MESSAGE_BYTES = 240
    const val MAX_RESPONSE_BYTES = 5 + 6 + TITLE_BYTES + MESSAGE_BYTES

    fun event(bytes: ByteArray): AncsEvent? {
        if (bytes.size != 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return AncsEvent(buffer.get().toInt() and 0xff, buffer.get().toInt() and 0xff, buffer.getInt(4))
    }

    fun request(id: Int): ByteArray = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        .put(0).putInt(id)
        .put(1).putShort(TITLE_BYTES.toShort())
        .put(3).putShort(MESSAGE_BYTES.toShort()).array()

    // A null result means a complete, matching response has not arrived yet.
    fun preview(bytes: ByteArray, id: Int): AncsPreview? {
        if (bytes.size !in 5..MAX_RESPONSE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get().toInt() != 0 || buffer.int != id) return null
        val title = attribute(buffer, 1, TITLE_BYTES) ?: return null
        val message = attribute(buffer, 3, MESSAGE_BYTES) ?: return null
        if (buffer.hasRemaining()) return null
        return AncsPreview(title, message)
    }

    private fun attribute(buffer: ByteBuffer, id: Int, maximum: Int): String? {
        if (buffer.remaining() < 3 || buffer.get().toInt() != id) return null
        val length = buffer.short.toInt() and 0xffff
        if (length > maximum || buffer.remaining() < length) return null
        val value = ByteArray(length)
        buffer.get(value)
        return value.toString(Charsets.UTF_8)
    }
}
