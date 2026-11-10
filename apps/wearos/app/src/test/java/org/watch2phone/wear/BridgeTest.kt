package org.watch2phone.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.watch2phone.wear.PeripheralState.Event

class BridgeTest {
    @Test
    fun sharedWireVectors() {
        val rows = checkNotNull(javaClass.getResourceAsStream("/vectors.tsv"))
            .bufferedReader(Charsets.UTF_8).use { it.readLines() }
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val fields = row.split('\t')
            val bytes = fields[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val frame = Frame.decode(bytes)
            if (fields[0] == "invalid") {
                assertNull(row, frame)
                continue
            }
            assertNotNull(row, frame)
            requireNotNull(frame)
            assertEquals(if (fields[0] == "request") Opcode.REQUEST else Opcode.REPLY, frame.opcode)
            assertEquals(fields[2].toInt(), frame.transaction)
            assertEquals(fields[3], frame.text)
            assertArrayEquals(bytes, frame.encode())
            if (frame.opcode == Opcode.REQUEST) {
                val expected = bytes.copyOf().apply { this[1] = 2 }
                assertArrayEquals(expected, frame.reply()?.encode())
            } else {
                assertNull(frame.reply())
            }
        }
    }

    @Test
    fun stoppedSessionCannotBeRevivedByCallbacks() {
        val stopped = PeripheralState.STOPPED
        assertEquals(stopped, stopped.next(Event.PUBLISHED))
        assertEquals(stopped, stopped.next(Event.CONNECT))
        val starting = stopped.next(Event.START)
        assertEquals(PeripheralState.STARTING, starting)
        val advertising = starting.next(Event.PUBLISHED)
        assertEquals(PeripheralState.ADVERTISING, advertising)
        val connected = advertising.next(Event.CONNECT)
        assertEquals(PeripheralState.CONNECTED, connected)
        assertEquals(advertising, connected.next(Event.DISCONNECT).next(Event.PUBLISHED))
        assertEquals(stopped, connected.next(Event.STOP))
    }
}
