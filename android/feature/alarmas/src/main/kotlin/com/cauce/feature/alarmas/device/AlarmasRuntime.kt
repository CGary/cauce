package com.cauce.feature.alarmas.device

import android.content.Context
import android.os.Build
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.AlarmasActivas
import com.cauce.feature.alarmas.planFirings
import com.cauce.feature.alarmas.projectionParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val stateEventTypes = setOf("alarma_creada", "alarma_modificada", "alarma_desactivada")

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stable id for `device_id` in the envelopes this phone produces. */
    fun deviceId(): String = Build.MODEL.ifBlank { "android" }

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
        val services = context.cauceServices()
        val events = services.eventStore.observeByTypes(stateEventTypes).first()
        val now = Instant.now()
        val params = projectionParams(now, ZoneId.systemDefault())
        val json = services.projections.compute(AlarmasActivas.key, events, params)
        val activas = CauceJson.output.decodeFromJsonElement(AlarmasActivas.serializer, json)
        AlarmScheduler(context).replaceAll(planFirings(activas, now))
    }
}
