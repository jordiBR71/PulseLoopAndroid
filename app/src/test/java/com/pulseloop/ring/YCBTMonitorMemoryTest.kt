package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YCBTMonitorMemoryTest {
    private val settings = MeasurementSettings.ALL_ON_DEFAULT.copy(hrIntervalMinutes = 30)

    private class FakeWriter : RingCommandWriter {
        val sent = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { sent.add(command) }
    }

    private fun monitorWrites(writer: FakeWriter): Int = writer.sent.count {
        it.size >= 2 && it[0] == YCBTGroup.SETTING.toByte() &&
            (it[1].toInt() and 0xFF) in setOf(YCBTSettingKey.HEART_MONITOR, YCBTSettingKey.BLOOD_OXYGEN_MONITOR)
    }

    @Test
    fun `sends on first use, after a change, and after the refresh window`() {
        var now = 0L
        val memory = YCBTMonitorMemory(refreshMs = 1_000L, clock = { now })
        assertTrue(memory.shouldSend(settings))
        memory.recordSent(settings)
        assertFalse(memory.shouldSend(settings))
        assertTrue(memory.shouldSend(settings.copy(hrIntervalMinutes = 60)))
        now = 1_000L
        assertTrue(memory.shouldSend(settings))
    }

    @Test
    fun `a reconnect does not re-send unchanged monitor settings`() {
        val memory = YCBTMonitorMemory(refreshMs = Long.MAX_VALUE)
        val first = FakeWriter()
        YCBTSyncEngine(first, YCBTHistoryTransfer(first), monitorMemory = memory)
            .apply { setMeasurementSettings(settings) }.runStartup()
        assertEquals(2, monitorWrites(first))

        val second = FakeWriter()
        YCBTSyncEngine(second, YCBTHistoryTransfer(second), monitorMemory = memory)
            .apply { setMeasurementSettings(settings) }.runStartup()
        assertEquals(0, monitorWrites(second))
    }
}
