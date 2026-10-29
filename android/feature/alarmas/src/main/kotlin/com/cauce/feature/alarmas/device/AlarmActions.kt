package com.cauce.feature.alarmas.device

import android.content.Context
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.SnoozeOutcome
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
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The four things a firing can turn into, shared by the receivers and the full-screen activity.
 * Everything reads the log fresh (last-`ts`-wins lives in [foldAlarms], the same code the
 * projections use) and appends events; no local state is mutated here.
 */
internal object AlarmActions {

    /**
     * The alarm rang: record `alarma_disparada`, deactivate when the calendar just ran out
     * (SPEC 2.6, `fin_tratamiento`), post the ringing notification, arm the 2-minute timeout
     * (SPEC 2.3) and program the next firing. A snooze that comes back after that deactivation
     * still rings (SPEC §8, agente); only a user deactivation cancels it.
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

        val notification = AlarmNotifications.build(context, alarm, programadaPara)
        val ringing = AlarmRingingService.start(
            context, alarmaId, programadaPara, AlarmNotifications.notificationId(alarmaId), notification,
        )
        if (!ringing) AlarmNotifications.post(context, alarm, programadaPara)
        AlarmScheduler(context).scheduleTimeout(alarmaId, programadaPara, now.plus(2, ChronoUnit.MINUTES))
        AlarmasRuntime.rescheduleNow(context)
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

    /** Two minutes ringing with no answer: the occurrence stays `ignorada` (SPEC 2.3). */
    suspend fun timeout(context: Context, alarmaId: String, programadaPara: String) {
        val services = context.cauceServices()
        val events = services.eventStore.observeByTypes(setOf("alarma_atendida")).first()
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
