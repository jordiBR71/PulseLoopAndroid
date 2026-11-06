package com.pulseloop.ring

/**
 * Remembers the all-day monitor settings last written to a YCBT ring, so a reconnect doesn't
 * write them again.
 *
 * A Trinity 5363 (COLMI_SMART_HEALTH, firmware 2.04) with HR and SpO₂ set to every 30 min logged
 * only three readings in a morning while its link dropped and came back every 10–25 minutes; each
 * reconnect re-sent `01 0c 01 1e` / `01 26 01 1e`. The working hypothesis is that each write
 * restarts the ring's interval timer, so with a link shorter than the interval it never fires.
 *
 * Settings are re-sent when they change, on the first connection of the process, and at most
 * every [refreshMs] otherwise, in case the ring lost them (e.g. it ran flat).
 */
class YCBTMonitorMemory(
    private val refreshMs: Long = 6 * 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastSettings: MeasurementSettings? = null
    private var lastSentAt: Long = 0L

    @Synchronized
    fun shouldSend(settings: MeasurementSettings): Boolean =
        settings != lastSettings || clock() - lastSentAt >= refreshMs

    @Synchronized
    fun recordSent(settings: MeasurementSettings) {
        lastSettings = settings
        lastSentAt = clock()
    }

    companion object {
        /** One per process: the sync engine is rebuilt on every connection. */
        val shared = YCBTMonitorMemory()
    }
}
