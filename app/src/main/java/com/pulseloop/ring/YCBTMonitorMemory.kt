package com.pulseloop.ring

import android.content.Context

/**
 * Remembers the all-day monitor settings last written to a YCBT ring, so a reconnect doesn't
 * write them again.
 *
 * A Trinity 5363 (COLMI_SMART_HEALTH, firmware 2.04) with HR and SpO₂ set to every 30 min logged
 * only three readings in a morning while its link dropped and came back every 10–25 minutes; each
 * reconnect re-sent `01 0c 01 1e` / `01 26 01 1e`. The working hypothesis is that each write
 * restarts the ring's interval timer, so with a link shorter than the interval it rarely fires.
 *
 * Settings are re-sent when they change and at most every [refreshMs] otherwise, in case the ring
 * lost them (e.g. it ran flat). The record survives process death through [Store], because
 * aggressive OEM battery management kills the app between reconnects: with an in-memory record
 * every restart re-sent the settings anyway.
 */
class YCBTMonitorMemory(
    private val refreshMs: Long = 6 * 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Store {
        fun load(): Pair<String, Long>?
        fun save(key: String, sentAt: Long)
    }

    private var store: Store? = null
    private var loaded = false
    private var lastKey: String? = null
    private var lastSentAt: Long = 0L

    @Synchronized
    fun attach(store: Store) {
        this.store = store
        loaded = false
    }

    @Synchronized
    fun shouldSend(settings: MeasurementSettings): Boolean {
        ensureLoaded()
        return key(settings) != lastKey || clock() - lastSentAt >= refreshMs
    }

    @Synchronized
    fun recordSent(settings: MeasurementSettings) {
        lastKey = key(settings)
        lastSentAt = clock()
        store?.save(lastKey!!, lastSentAt)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        store?.load()?.let { (k, at) ->
            lastKey = k
            lastSentAt = at
        }
    }

    // MeasurementSettings is a data class of primitives, so toString() is stable across processes.
    private fun key(settings: MeasurementSettings): String = settings.toString()

    companion object {
        /** One per process: the sync engine is rebuilt on every connection. */
        val shared = YCBTMonitorMemory()

        private const val PREFS = "ycbt_monitor_memory"

        fun attachPreferences(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            shared.attach(object : Store {
                override fun load(): Pair<String, Long>? {
                    val k = prefs.getString("key", null) ?: return null
                    return k to prefs.getLong("sentAt", 0L)
                }

                override fun save(key: String, sentAt: Long) {
                    prefs.edit().putString("key", key).putLong("sentAt", sentAt).apply()
                }
            })
        }
    }
}
