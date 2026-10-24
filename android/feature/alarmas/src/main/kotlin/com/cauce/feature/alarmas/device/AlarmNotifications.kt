package com.cauce.feature.alarmas.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cauce.feature.alarmas.Alarm
import com.cauce.feature.alarmas.R

/**
 * The ringing alarm notification (SPEC 2.3): high priority, alarm category, full-screen intent
 * over the lock screen, `FLAG_INSISTENT` so sound+vibration loop until attended, and the two
 * actions — Hecho and Posponer 10 min.
 */
internal object AlarmNotifications {
    /**
     * Channels are immutable once created, so the alarm-sound + vibration fix needs a new id
     * (SPEC 2.3: the alarm rings *and* vibrates). The old channel is deleted, never reused.
     */
    private const val CHANNEL_ID = "alarmas_disparos_v2"
    private const val LEGACY_CHANNEL_ID = "alarmas_disparos"
    private val VIBRATION_PATTERN = longArrayOf(0, 500, 500)

    fun ensureChannel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarmas",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Disparos de alarmas: remedios, reuniones y otras"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?.let { setSound(it, audioAttributes) }
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, alarm: Alarm, programadaPara: String) {
        ensureChannel(context)
        val text = when {
            alarm.tipo == "remedio" ->
                listOfNotNull(alarm.remedio, alarm.destinatario).joinToString(" · ")
            else -> alarm.horaRaw
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.titulo)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openAppIntent(context))
            .setFullScreenIntent(alertActivityIntent(context, alarm.id, programadaPara), true)
            .addAction(0, "Hecho", actionIntent(context, AlarmReceiver.ACTION_HECHO, alarm.id, programadaPara))
            .addAction(
                0,
                "Posponer 10 min",
                actionIntent(context, AlarmReceiver.ACTION_POSPONER, alarm.id, programadaPara),
            )
            .build()
        // Ring and vibrate until the notification leaves the shade.
        notification.flags = notification.flags or NotificationCompat.FLAG_INSISTENT

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(alarm.id.hashCode(), notification)
        }
    }

    fun cancel(context: Context, alarmaId: String) {
        NotificationManagerCompat.from(context).cancel(alarmaId.hashCode())
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
