package com.cauce.feature.alarmas.device

import android.content.Context
import android.os.Build
import android.util.Log
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.Alarm
import com.cauce.feature.alarmas.AlarmasActivas
import com.cauce.feature.alarmas.foldAlarms
import com.cauce.feature.alarmas.gcalAlarm
import com.cauce.feature.alarmas.isGcalId
import com.cauce.feature.alarmas.planFirings
import com.cauce.feature.alarmas.projectionParams
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.ZoneId

/**
 * Device-side plumbing of the feature. Holds the application-scoped scope (the module hooks
 * `onAppStart` / `onEventsPulled` are not suspend) and the one place that recomputes P1 and
 * reprograms `AlarmManager` — the effect SPEC 2.7 demands after boot, local edits and sync pulls.
 */
internal object AlarmasRuntime {
    /** Event types that change P1, so the ones that force a reschedule (SPEC 2.7). */
    val stateEventTypes: Set<String> = AlarmasActivas.eventTypes

    /** Logcat tag of every caught failure on the firing path (`adb logcat -s CauceAlarmas`). */
    const val TAG = "CauceAlarmas"

    /**
     * An uncaught failure in a receiver or reschedule is logged instead of killing the process:
     * a dead process cannot ring, time out or program the next firing.
     */
    private val logUncaught = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught failure in alarmas runtime", e)
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logUncaught)

    /**
     * Callers run concurrently on [Dispatchers.IO] (app start, sync pull, editors, every receiver),
     * so two reprograms could interleave: one's cancel-all after another's re-arm, or a stale
     * read re-arming a deactivated alarm. The lock covers read, compute and re-arm.
     */
    private val rescheduleLock = Mutex()

    /** Stable id for `device_id` in the envelopes this phone produces. */
    fun deviceId(): String = Build.MODEL.ifBlank { "android" }

    /** The ringable alarm of a firing: a `gcal:` entry comes from its P1 row, any other from the state fold. */
    suspend fun loadAlarm(context: Context, alarmaId: String, programadaPara: String): Alarm? {
        val events = context.cauceServices().eventStore.observeByTypes(stateEventTypes).first()
        return if (isGcalId(alarmaId)) {
            gcalAlarm(events, alarmaId, Instant.parse(programadaPara), ZoneId.systemDefault())
        } else {
            foldAlarms(events).firstOrNull { it.id == alarmaId }
        }
    }

    fun rescheduleAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch { rescheduleNow(appContext) }
    }

    /**
     * Suspend variant for receivers: they await it so the reprogram finishes before
     * `pendingResult.finish()`, inside the ~10 s `goAsync()` budget (AGENTS.md: re-apply device
     * effects after a pull; SPEC 2.7).
     */
    suspend fun rescheduleNow(context: Context) = withContext(Dispatchers.IO) {
        rescheduleLock.withLock { reschedule(context) }
    }

    private suspend fun reschedule(context: Context) {
        val services = context.cauceServices()
        val events = services.eventStore.observeByTypes(stateEventTypes).first()
        val now = Instant.now()
        val params = projectionParams(now, ZoneId.systemDefault())
        val json = services.projections.compute(AlarmasActivas.key, events, params)
        val activas = CauceJson.output.decodeFromJsonElement(AlarmasActivas.serializer, json)
        AlarmScheduler(context).replaceAll(planFirings(activas, now))
    }
}
