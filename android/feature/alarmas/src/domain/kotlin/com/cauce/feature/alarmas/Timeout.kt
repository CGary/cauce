package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant

/** SPEC 2.3: a firing rings for 2 minutes with no answer before it becomes `ignorada`. */
internal val RING_DURATION: Duration = Duration.ofMinutes(2)

/**
 * Slack for the 2-minute cut: a timeout that arrives this close to the deadline counts as due,
 * so clock jitter between `AlarmManager` and the log never re-arms for a few milliseconds.
 */
internal val TIMEOUT_TOLERANCE: Duration = Duration.ofSeconds(2)

/**
 * SPEC 2.3/§8: time left before the 2-minute cut of this occurrence, or null when the cut is due.
 * The cut counts from the latest `alarma_disparada` of the occurrence (a snooze re-fire keeps
 * `programada_para` and has a newer one). It is due when `now ≥ ts + 2 min − tolerance`.
 * With no `alarma_disparada` for the occurrence it is due (SPEC §8, agente): there is nothing to
 * wait for, and the timeout stays the safe side that closes the occurrence.
 */
internal fun timeoutRemaining(
    events: List<Envelope>,
    alarmaId: String,
    programadaPara: String,
    now: Instant,
): Duration? {
    val target = Instant.parse(programadaPara)
    val latest = events
        .filter { event ->
            event.type == "alarma_disparada" &&
                event.payload.getValue("alarma_id").jsonPrimitive.content == alarmaId &&
                Instant.parse(event.payload.getValue("programada_para").jsonPrimitive.content) == target
        }
        .maxOfOrNull { Instant.parse(it.ts) }
        ?: return null
    val remaining = Duration.between(now, latest.plus(RING_DURATION))
    return if (remaining <= TIMEOUT_TOLERANCE) null else remaining
}
