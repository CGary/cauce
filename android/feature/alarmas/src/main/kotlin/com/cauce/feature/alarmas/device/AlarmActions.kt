package com.cauce.feature.alarmas.device

import android.content.Context
import android.util.Log
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.RING_DURATION
import com.cauce.feature.alarmas.SnoozeOutcome
import com.cauce.feature.alarmas.TIMEOUT_TOLERANCE
import com.cauce.feature.alarmas.alarmaAtendida
import com.cauce.feature.alarmas.fireEvents
import com.cauce.feature.alarmas.gcalAlarm
import com.cauce.feature.alarmas.hechoEvents
import com.cauce.feature.alarmas.isGcalId
import com.cauce.feature.alarmas.shouldRingGcal
import com.cauce.feature.alarmas.foldAlarms
import com.cauce.feature.alarmas.hasAtendida
import com.cauce.feature.alarmas.nextSnoozeOutcome
import com.cauce.feature.alarmas.shouldRing
import com.cauce.feature.alarmas.timeoutRemaining
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The four things a firing can turn into, shared by the receivers and the full-screen activity.
 * Everything reads the log fresh (last-`ts`-wins lives in [foldAlarms], the same code the
 * projections use) and appends events; no local state is mutated here.
 */
internal object AlarmActions {

    /**
     * The alarm rang: record `alarma_disparada`, deactivate when the calendar just ran out
     * (SPEC 2.6, `fin_tratamiento`), arm the 2-minute timeout (SPEC 2.3), program the next firing
     * (waiting at most 2 s for it) and only then ring. Ringing is the step that can fail or get the process killed, so the
     * timeout and the next firing are already in `AlarmManager` when it starts. A snooze that
     * comes back after that deactivation still rings (SPEC §8, agente); only a user deactivation
     * cancels it.
     */
    suspend fun fire(context: Context, alarmaId: String, programadaPara: String, snoozed: Boolean) {
        val services = context.cauceServices()
        val events = services.eventStore.observeByTypes(AlarmasRuntime.stateEventTypes).first()
        val zone = ZoneId.systemDefault()
        val programadaParaInstant = Instant.parse(programadaPara)
        val gcal = isGcalId(alarmaId)
        val rings = if (gcal) {
            shouldRingGcal(events, alarmaId, programadaParaInstant, zone, snoozed)
        } else {
            shouldRing(events, alarmaId, snoozed)
        }
        if (!rings) {
            AlarmNotifications.cancel(context, alarmaId)
            return
        }
        val alarm = if (gcal) {
            gcalAlarm(events, alarmaId, programadaParaInstant, zone)
        } else {
            foldAlarms(events).first { it.id == alarmaId }
        }

        val device = AlarmasRuntime.deviceId()
        val now = Instant.now()
        services.eventStore.append(
            fireEvents(device, now, alarmaId, programadaParaInstant, snoozed, alarm, zone),
        )

        logFailure("arming the timeout of $alarmaId") {
            AlarmScheduler(context).scheduleTimeout(alarmaId, programadaPara, now.plus(RING_DURATION))
        }
        // Bounded, so a slow reprogram (cold start, another reschedule holding the lock) cannot
        // delay the sound past the background-start allowance of the alarm; it then runs after.
        val rescheduled = withTimeoutOrNull(RESCHEDULE_BEFORE_RING_MILLIS) {
            logFailure("rescheduling after $alarmaId fired") { AlarmasRuntime.rescheduleNow(context) }
            true
        } ?: false

        val ringing = try {
            val notification = AlarmNotifications.build(context, alarm, programadaPara)
            val fallback = AlarmNotifications.buildFallback(context, alarm, programadaPara)
            AlarmRingingService.start(
                context, alarmaId, programadaPara, AlarmNotifications.notificationId(alarmaId),
                notification, fallback,
            )
        } catch (e: RuntimeException) {
            Log.e(AlarmasRuntime.TAG, "starting the ringing service for $alarmaId failed", e)
            false
        }
        if (!ringing) {
            logFailure("posting the backup notification of $alarmaId") {
                AlarmNotifications.post(context, alarm, programadaPara)
            }
        }
        if (!rescheduled) {
            logFailure("rescheduling after $alarmaId rang") { AlarmasRuntime.rescheduleNow(context) }
        }
    }

    private const val RESCHEDULE_BEFORE_RING_MILLIS = 2_000L

    /** Runs a step whose failure must not stop the rest of the firing; the cause goes to logcat. */
    private inline fun logFailure(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(AlarmasRuntime.TAG, "$what failed", e)
        }
    }

    /**
     * "Hecho": `alarma_atendida(hecho)`, plus `dosis_dada` when the alarm is a remedio (SPEC 2.4).
     * A one-shot alarm deactivates when attended (SPEC 2.6, `una_vez_atendida`).
     */
    suspend fun hecho(context: Context, alarmaId: String, programadaPara: String) {
        val services = context.cauceServices()
        val events = services.eventStore.observeByTypes(AlarmasRuntime.stateEventTypes).first()
        val alarm = if (isGcalId(alarmaId)) null else foldAlarms(events).firstOrNull { it.id == alarmaId }

        val device = AlarmasRuntime.deviceId()
        val now = Instant.now()
        val programadaParaInstant = Instant.parse(programadaPara)
        services.eventStore.append(hechoEvents(device, now, alarmaId, programadaParaInstant, alarm))

        AlarmScheduler(context).cancelTimeout(alarmaId)
        AlarmRingingService.close(context, alarmaId, programadaPara)
        AlarmNotifications.cancel(context, alarmaId)
        AlarmasRuntime.rescheduleNow(context)
    }

    /** "Posponer 10 min": up to 3 times; the fourth marks the occurrence `ignorada` (SPEC 2.5). */
    suspend fun posponer(context: Context, alarmaId: String, programadaPara: String) {
        val services = context.cauceServices()
        val events = services.eventStore.observeByTypes(setOf("alarma_atendida")).first()

        val device = AlarmasRuntime.deviceId()
        val now = Instant.now()
        val programadaParaInstant = Instant.parse(programadaPara)
        when (val outcome = nextSnoozeOutcome(events, alarmaId, programadaPara)) {
            is SnoozeOutcome.Pospuesta -> {
                services.eventStore.append(
                    listOf(
                        alarmaAtendida(
                            device, now, alarmaId, programadaParaInstant, "pospuesta", outcome.n,
                        ),
                    ),
                )
                AlarmScheduler(context)
                    .scheduleSnooze(alarmaId, programadaPara, now.plus(10, ChronoUnit.MINUTES))
            }
            SnoozeOutcome.Ignorada -> {
                services.eventStore.append(
                    listOf(alarmaAtendida(device, now, alarmaId, programadaParaInstant, "ignorada")),
                )
            }
        }
        AlarmScheduler(context).cancelTimeout(alarmaId)
        AlarmRingingService.close(context, alarmaId, programadaPara)
        AlarmNotifications.cancel(context, alarmaId)
    }

    /**
     * Two minutes ringing with no answer: the occurrence stays `ignorada` (SPEC 2.3). The cut
     * counts from the latest `alarma_disparada`; a backup timeout delivered early (ColorOS has
     * delivered the 2-minute one ~5 s after firing) is re-armed for the time left and does
     * nothing else (SPEC §8).
     */
    suspend fun timeout(context: Context, alarmaId: String, programadaPara: String) {
        val services = context.cauceServices()
        val events = services.eventStore
            .observeByTypes(setOf("alarma_atendida", "alarma_disparada")).first()
        val now = Instant.now()
        val remaining = timeoutRemaining(events, alarmaId, programadaPara, now)
        if (remaining != null && !hasAtendida(events, alarmaId, programadaPara)) {
            Log.i(AlarmasRuntime.TAG, "timeout of $alarmaId early by ${remaining.seconds} s: re-armed")
            AlarmScheduler(context)
                .scheduleTimeout(alarmaId, programadaPara, now.plus(remaining).plus(TIMEOUT_TOLERANCE))
            return
        }
        if (!hasAtendida(events, alarmaId, programadaPara)) {
            services.eventStore.append(
                listOf(
                    alarmaAtendida(
                        AlarmasRuntime.deviceId(), Instant.now(), alarmaId,
                        Instant.parse(programadaPara), "ignorada",
                    ),
                ),
            )
        }
        AlarmRingingService.close(context, alarmaId, programadaPara)
        AlarmNotifications.cancel(context, alarmaId)
    }
}
