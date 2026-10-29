package com.cauce.feature.alarmas.device

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ServiceCompat
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.RingingKey
import com.cauce.feature.alarmas.RingingSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Plays the alarm like the system clock does (SPEC 2.3, §5): a foreground service that loops the
 * default alarm ringtone and a vibration pattern with `USAGE_ALARM`, so silent mode does not mute
 * it. Its foreground notification is the full-screen one (Hecho / Posponer). It rings while any
 * firing is unattended, stops with the last one, and cuts each firing after 2 minutes through
 * [AlarmActions.timeout].
 */
class AlarmRingingService : Service() {
    private val set = RingingSet()
    private val notifications = HashMap<RingingKey, Pair<Int, Notification>>()
    private val timers = HashMap<RingingKey, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundKey: RingingKey? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmaId = intent?.getStringExtra(AlarmReceiver.EXTRA_ALARMA_ID)
        val programadaPara = intent?.getStringExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA)
        if (alarmaId == null || programadaPara == null) {
            if (!set.isRinging) stopSelf()
            return START_NOT_STICKY
        }
        val key = RingingKey(alarmaId, programadaPara)
        when (intent.action) {
            ACTION_CLOSE -> close(key)
            else -> start(key, intent)
        }
        return START_NOT_STICKY
    }

    private fun start(key: RingingKey, intent: Intent) {
        @Suppress("DEPRECATION")
        val notification = intent.getParcelableExtra<Notification>(EXTRA_NOTIFICATION)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, key.alarmaId.hashCode())
        if (notification == null) {
            if (!set.isRinging) stopSelf()
            return
        }
        notifications[key] = notificationId to notification
        showForeground(key)
        set.start(key)
        startRinging()
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(RING_MILLIS)
            AlarmActions.timeout(applicationContext, key.alarmaId, key.programadaPara)
            close(key)
        }
        observeLog()
    }

    private fun showForeground(key: RingingKey) {
        val (id, notification) = notifications.getValue(key)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, id, notification, type)
        foregroundKey = key
    }

    /** Reuses the log as source of truth, like [AlarmAlertActivity]: a terminal atendida stops it. */
    private fun observeLog() {
        if (observing) return
        observing = true
        scope.launch {
            cauceServices().eventStore.observeByTypes(setOf("alarma_atendida"))
                .collect { events -> set.closedIn(events).forEach { close(it) } }
        }
    }

    private fun close(key: RingingKey) {
        timers.remove(key)?.cancel()
        val wasForeground = foregroundKey == key
        val closed = notifications.remove(key)
        val last = set.close(key)
        if (last || !set.isRinging) {
            stopRinging()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (wasForeground) {
            set.keys.firstOrNull { it in notifications }?.let { showForeground(it) }
            closed?.let { AlarmNotifications.cancelId(this, it.first) }
        }
    }

    private fun startRinging() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cauce:alarmas:ringing")
                .apply { acquire(WAKE_LOCK_MILLIS) }
        }
        if (player == null) {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(ALARM_ATTRIBUTES)
                    setDataSource(this@AlarmRingingService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
            val vibrator = getSystemService(Vibrator::class.java)
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0), ALARM_ATTRIBUTES)
        }
    }

    private fun stopRinging() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        getSystemService(Vibrator::class.java)?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.cauce.feature.alarmas.action.RING"
        private const val ACTION_CLOSE = "com.cauce.feature.alarmas.action.RING_CLOSE"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val RING_MILLIS = 2 * 60 * 1000L
        private const val WAKE_LOCK_MILLIS = 3 * 60 * 1000L
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 500)
        private val ALARM_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Starts ringing for a firing. Returns false when the system refuses the start. */
        fun start(
            context: Context,
            alarmaId: String,
            programadaPara: String,
            notificationId: Int,
            notification: Notification,
        ): Boolean = try {
            val intent = Intent(context, AlarmRingingService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
                .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, programadaPara)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_NOTIFICATION, notification)
            context.startForegroundService(intent)
            true
        } catch (e: RuntimeException) {
            false
        }

        /** Hecho / Posponer / timeout: stop ringing for this firing (no-op when not running). */
        fun close(context: Context, alarmaId: String, programadaPara: String) {
            try {
                val intent = Intent(context, AlarmRingingService::class.java)
                    .setAction(ACTION_CLOSE)
                    .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
                    .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, programadaPara)
                context.startService(intent)
            } catch (e: RuntimeException) {
                // Not running and the app is in the background: nothing is ringing.
            }
        }
    }
}
