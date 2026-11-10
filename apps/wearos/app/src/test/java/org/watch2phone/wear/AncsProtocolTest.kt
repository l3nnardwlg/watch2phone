package org.watch2phone.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AncsProtocolTest {
    @Test fun readsEventAndRequestsBoundedAttributesWithLittleEndianId() {
        val bytes = byteArrayOf(0, 5, 4, 1, 0x78, 0x56, 0x34, 0x12)
        val event = AncsProtocol.event(bytes)
        assertEquals(AncsEvent(0, 5, 0x12345678), event)
        assertEquals(true, event?.silent)
        assertEquals(true, event?.preExisting)
        assertNull(AncsProtocol.event(bytes.copyOf(7)))
        assertArrayEquals(
            byteArrayOf(0, 0x78, 0x56, 0x34, 0x12, 1, 80, 0, 3, 0xf0.toByte(), 0),
            AncsProtocol.request(0x12345678),
        )
    }

    @Test fun onlyPublishesACompleteMatchingPreview() {
        val header = byteArrayOf(0, 0x78, 0x56, 0x34, 0x12, 1, 4, 0)
        val bytes = header + "Tür".encodeToByteArray() + byteArrayOf(3, 0, 0)
        assertEquals(AncsPreview("Tür", ""), AncsProtocol.preview(bytes, 0x12345678))
        for (length in bytes.indices) {
            assertNull(AncsProtocol.preview(bytes.copyOf(length), 0x12345678))
        }
        assertNull(AncsProtocol.preview(bytes, 0x12345679))
        bytes[6] = 81
        assertNull(AncsProtocol.preview(bytes, 0x12345678))
    }
}
