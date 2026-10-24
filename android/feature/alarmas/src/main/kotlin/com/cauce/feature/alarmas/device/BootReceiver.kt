package com.cauce.feature.alarmas.device

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * SPEC 2.7: alarms survive reboots. `AlarmManager` schedules do not, so boot (and the clock,
 * timezone or exact-alarm permission changing under a calendar alarm) triggers a full P1
 * recompute and reprogram. `goAsync()` keeps the process alive until the reprogram completes.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit
            else -> return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        AlarmasRuntime.scope.launch {
            try {
                AlarmasRuntime.rescheduleNow(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
