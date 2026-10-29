package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope

/** One firing that is ringing: an alarm occurrence (`alarma_id` + `programada_para`). */
internal data class RingingKey(val alarmaId: String, val programadaPara: String)

/**
 * Pure bookkeeping of the alarm ringing service (SPEC 2.3): it rings while any firing is
 * unattended and stops with the last one. No Android, no clock; the service feeds it events.
 */
internal class RingingSet {
    private val active = LinkedHashSet<RingingKey>()

    val isRinging: Boolean get() = active.isNotEmpty()

    val keys: List<RingingKey> get() = active.toList()

    /** A firing starts ringing (re-firing the same occurrence, e.g. a snooze, is idempotent). */
    fun start(key: RingingKey) {
        active += key
    }

    /** A firing was attended, snoozed or timed out. Returns true when it was the last one. */
    fun close(key: RingingKey): Boolean = active.remove(key) && active.isEmpty()

    /** Active firings already closed by a terminal `alarma_atendida` in the log. */
    fun closedIn(events: List<Envelope>): List<RingingKey> =
        active.filter { hasAtendida(events, it.alarmaId, it.programadaPara) }
}
