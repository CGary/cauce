package com.cauce.feature.alarmas.device

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.cauce.feature.alarmas.FiringPlan
import com.cauce.feature.alarmas.formatUtc
import java.time.Instant

/**
 * The one writer of `AlarmManager`. Every firing, snooze and 2-minute timeout is a PendingIntent
 * keyed by alarm id, so "reprogram everything" (SPEC 2.7) can cancel what it scheduled before:
 * the ids of the scheduled firings are kept in SharedPreferences (device-local bookkeeping, not
 * event state).
 */
internal class AlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Cancels the previously scheduled firings and programs one exact alarm per plan. */
    fun replaceAll(plans: List<FiringPlan>) {
        cancelScheduledFirings()
        plans.forEach { plan -> scheduleAt(plan.triggerAt, fireIntent(plan.alarmaId, plan.triggerAt), clock = true) }
        prefs.edit()
            .putStringSet(KEY_SCHEDULED_IDS, plans.mapTo(mutableSetOf()) { it.alarmaId })
            .apply()
    }

    /** SPEC 2.5: "Posponer 10 min" re-fires the same occurrence (same `programada_para`). */
    fun scheduleSnooze(alarmaId: String, programadaPara: String, at: Instant) {
        scheduleAt(at, fireIntent(alarmaId, Instant.parse(programadaPara), snoozed = true), clock = true)
    }

    /** SPEC 2.3: the alarm rings until attended or 2 minutes pass (→ `ignorada`). */
    fun scheduleTimeout(alarmaId: String, programadaPara: String, at: Instant) {
        val intent = Intent(appContext, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TIMEOUT)
            .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
            .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, programadaPara)
        val pending = PendingIntent.getBroadcast(
            appContext,
            timeoutRequestCode(alarmaId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleAt(at, pending)
    }

    fun cancelTimeout(alarmaId: String) {
        val intent = Intent(appContext, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TIMEOUT)
        val pending = PendingIntent.getBroadcast(
            appContext,
            timeoutRequestCode(alarmaId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.let { alarmManager.cancel(it); it.cancel() }
    }

    private fun fireIntent(alarmaId: String, triggerAt: Instant, snoozed: Boolean = false): PendingIntent {
        val intent = Intent(appContext, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
            .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, formatUtc(triggerAt))
            .putExtra(AlarmReceiver.EXTRA_SNOOZED, snoozed)
        val requestCode = if (snoozed) snoozeRequestCode(alarmaId) else fireRequestCode(alarmaId)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelScheduledFirings() {
        prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().forEach { alarmaId ->
            val intent = Intent(appContext, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
            val pending = PendingIntent.getBroadcast(
                appContext,
                fireRequestCode(alarmaId),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pending?.let { alarmManager.cancel(it); it.cancel() }
        }
    }

    /**
     * SPEC §5: firings and snoozes use `setAlarmClock`, like the system clock app: ColorOS does not
     * align it (a 1 h window with `setExactAndAllowWhileIdle`), it leaves Doze and shows the alarm
     * icon. The 2-minute timeout is only a backup (the ringing service cuts the sound) and uses
     * `setExactAndAllowWhileIdle` so it does not show as "next alarm clock". Without exact-alarm
     * permission (API 31-32) the fallback is an inexact alarm, which degrades instead of crashing.
     */
    private fun scheduleAt(at: Instant, pending: PendingIntent, clock: Boolean = false) {
        val millis = at.toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        when {
            !canExact -> alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            clock -> alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showIntent()), pending)
            else -> alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    /** What tapping the status-bar alarm icon opens: the app, whose home is the alarm list. */
    private fun showIntent(): PendingIntent {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            appContext,
            SHOW_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val PREFS = "alarmas_device"
        const val KEY_SCHEDULED_IDS = "scheduled_ids"
        const val SHOW_REQUEST_CODE = 0x5a1a

        fun fireRequestCode(alarmaId: String): Int = "fire:$alarmaId".hashCode()
        fun snoozeRequestCode(alarmaId: String): Int = "snooze:$alarmaId".hashCode()
        fun timeoutRequestCode(alarmaId: String): Int = "timeout:$alarmaId".hashCode()
    }
}
