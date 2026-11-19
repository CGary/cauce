package com.cauce.feature.alarmas.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cauce.feature.alarmas.Alarm
import com.cauce.feature.alarmas.R

/**
 * The ringing alarm notification (SPEC 2.3): high priority, alarm category, full-screen intent
 * over the lock screen and the two actions — Hecho and Posponer 10 min. The normal one has no
 * sound of its own: [AlarmRingingService] plays the alarm (`USAGE_ALARM`) and this is its
 * foreground notification. When the service cannot start or cannot play, the same notification
 * goes out on an audible backup channel instead (alarm sound, vibration, insistent), so a firing
 * is never silent. Both share the notification id, so Hecho, Posponer and the timeout cancel
 * either one.
 */
internal object AlarmNotifications {
    /**
     * Channels are immutable once created, so silencing the channel needs a new id: the service
     * plays the sound, the channel must not (SPEC 2.3). Old channels are deleted, never reused.
     */
    private const val CHANNEL_ID = "alarmas_disparos_v3"
    private const val PREVIOUS_CHANNEL_ID = "alarmas_disparos_v2"
    private const val LEGACY_CHANNEL_ID = "alarmas_disparos"

    /** Audible backup (SPEC §8, agente): used only when the ringing service fails. */
    private const val FALLBACK_CHANNEL_ID = "alarmas_respaldo_v1"

    private val FALLBACK_VIBRATION = longArrayOf(0, 500, 500, 500, 500, 500, 500)

    fun ensureChannel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(PREVIOUS_CHANNEL_ID)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarmas",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Disparos de alarmas: remedios, reuniones y otras"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)

        val fallback = NotificationChannel(
            FALLBACK_CHANNEL_ID,
            "Alarmas (respaldo)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Suena solo si el servicio de alarma no puede reproducir el tono"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = FALLBACK_VIBRATION
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(fallback)
    }

    /** Posts the audible backup directly (the ringing service could not start). */
    fun post(context: Context, alarm: Alarm, programadaPara: String) {
        notifyId(context, notificationId(alarm.id), buildFallback(context, alarm, programadaPara))
    }

    /** Posts [notification] under [id]; failures are logged, never thrown (it is the last resort). */
    fun notifyId(context: Context, id: Int, notification: Notification) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                manager.notify(id, notification)
            } else {
                Log.e(AlarmasRuntime.TAG, "notifications disabled: alarm $id cannot be shown")
            }
        } catch (e: RuntimeException) {
            Log.e(AlarmasRuntime.TAG, "posting alarm notification $id failed", e)
        }
    }

    fun notificationId(alarmaId: String): Int = alarmaId.hashCode()

    /** The service's silent foreground notification (the normal path). */
    fun build(context: Context, alarm: Alarm, programadaPara: String): Notification =
        build(context, alarm, programadaPara, CHANNEL_ID, silent = true)

    /**
     * The audible backup: same content, actions and full-screen intent, on the backup channel,
     * with [Notification.FLAG_INSISTENT] so the sound repeats until Hecho, Posponer or the timeout
     * cancels it.
     */
    fun buildFallback(context: Context, alarm: Alarm, programadaPara: String): Notification =
        build(context, alarm, programadaPara, FALLBACK_CHANNEL_ID, silent = false).apply {
            flags = flags or Notification.FLAG_INSISTENT
        }

    private fun build(
        context: Context,
        alarm: Alarm,
        programadaPara: String,
        channelId: String,
        silent: Boolean,
    ): Notification {
        ensureChannel(context)
        val text = when {
            alarm.tipo == "remedio" ->
                listOfNotNull(alarm.remedio, alarm.destinatario).joinToString(" · ")
            else -> alarm.horaRaw
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.titulo)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(silent)
            .setContentIntent(openAppIntent(context))
            .setFullScreenIntent(alertActivityIntent(context, alarm.id, programadaPara), true)
            .addAction(0, "Hecho", actionIntent(context, AlarmReceiver.ACTION_HECHO, alarm.id, programadaPara))
            .addAction(
                0,
                "Posponer 10 min",
                actionIntent(context, AlarmReceiver.ACTION_POSPONER, alarm.id, programadaPara),
            )
            .build()
        return notification
    }

    fun cancel(context: Context, alarmaId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(alarmaId))
    }

    fun cancelId(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alertActivityIntent(context: Context, alarmaId: String, programadaPara: String): PendingIntent {
        val intent = Intent(context, AlarmAlertActivity::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
            .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, programadaPara)
        return PendingIntent.getActivity(
            context,
            "alert:$alarmaId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        context: Context,
        action: String,
        alarmaId: String,
        programadaPara: String,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
            .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, programadaPara)
        return PendingIntent.getBroadcast(
            context,
            "$action:$alarmaId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
