package com.cauce.feature.alarmas.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Entry point for the firing, the 2-minute timeout and the two notification actions. Receivers
 * cannot suspend, so the I/O (event-store reads and appends, including the reschedule) runs with
 * `goAsync()` on the application-scoped scope, per AGENTS.md. Every path awaits the reschedule
 * before `pendingResult.finish()`, inside the ~10 s budget.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmaId = intent.getStringExtra(EXTRA_ALARMA_ID) ?: return
        val programadaPara = intent.getStringExtra(EXTRA_PROGRAMADA_PARA) ?: return
        val snoozed = intent.getBooleanExtra(EXTRA_SNOOZED, false)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        AlarmasRuntime.scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> AlarmActions.fire(appContext, alarmaId, programadaPara, snoozed)
                    ACTION_TIMEOUT -> AlarmActions.timeout(appContext, alarmaId, programadaPara)
                    ACTION_HECHO -> AlarmActions.hecho(appContext, alarmaId, programadaPara)
                    ACTION_POSPONER -> AlarmActions.posponer(appContext, alarmaId, programadaPara)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.cauce.feature.alarmas.action.FIRE"
        const val ACTION_TIMEOUT = "com.cauce.feature.alarmas.action.TIMEOUT"
        const val ACTION_HECHO = "com.cauce.feature.alarmas.action.HECHO"
        const val ACTION_POSPONER = "com.cauce.feature.alarmas.action.POSPONER"

        const val EXTRA_ALARMA_ID = "alarma_id"
        const val EXTRA_PROGRAMADA_PARA = "programada_para"
        const val EXTRA_SNOOZED = "snoozed"
    }
}
