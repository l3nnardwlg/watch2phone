package org.watch2phone.wear

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID

internal object BridgeProtocol {
    val serviceUuid: UUID = UUID.fromString("ade40601-99c2-4635-b4d5-376c15c67a7e")
    val requestUuid: UUID = UUID.fromString("ade40602-99c2-4635-b4d5-376c15c67a7e")
    val replyUuid: UUID = UUID.fromString("ade40603-99c2-4635-b4d5-376c15c67a7e")
    val batteryUuid: UUID = UUID.fromString("ade40604-99c2-4635-b4d5-376c15c67a7e")
    val relayUuid: UUID = UUID.fromString("ade40605-99c2-4635-b4d5-376c15c67a7e")
}

internal enum class Opcode(val wire: Int) { REQUEST(1), REPLY(2) }

internal class Frame private constructor(
    val opcode: Opcode,
    val transaction: Int,
    private val payload: ByteArray,
    val text: String,
) {
    fun reply(): Frame? = when (opcode) {
        Opcode.REQUEST -> Frame(Opcode.REPLY, transaction, payload, text)
        Opcode.REPLY -> null
    }

    fun encode(): ByteArray = byteArrayOf(
        1, opcode.wire.toByte(), (transaction shr 8).toByte(), transaction.toByte(),
    ) + payload

    companion object {
        fun decode(value: ByteArray): Frame? {
            if (value.size !in 5..20 || value[0].toInt() != 1) return null
            val opcode = Opcode.entries.firstOrNull { it.wire == value[1].toInt() } ?: return null
            val transaction = ((value[2].toInt() and 0xff) shl 8) or (value[3].toInt() and 0xff)
            val payload = value.copyOfRange(4, value.size)
            val text = try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            return Frame(opcode, transaction, payload, text)
        }
    }
}
