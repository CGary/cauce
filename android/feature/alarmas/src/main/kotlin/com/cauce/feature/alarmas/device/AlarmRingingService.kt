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
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.RING_DURATION
import com.cauce.feature.alarmas.RingingKey
import com.cauce.feature.alarmas.RingingSet
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Plays the alarm like the system clock does (SPEC 2.3, §5): a foreground service that loops the
 * default alarm ringtone and a vibration pattern with `USAGE_ALARM`, so silent mode does not mute
 * it. Its foreground notification is the full-screen one (Hecho / Posponer). It rings while any
 * firing is unattended, stops with the last one, and cuts each firing after 2 minutes through
 * [AlarmActions.timeout].
 *
 * No failure on the start path may kill the process (a dead process rings nothing): if
 * `startForeground` fails, the audible backup notification is posted and the service stops; if
 * only the player fails, the service stays in the foreground and swaps its notification for the
 * audible backup, and keeps vibrating. Every caught failure is logged under [AlarmasRuntime.TAG].
 */
class AlarmRingingService : Service() {
    private val set = RingingSet()
    private val notifications = HashMap<RingingKey, Shown>()
    private val timers = HashMap<RingingKey, Job>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(AlarmasRuntime.TAG, "uncaught failure in the ringing service", e)
        },
    )
    private var foregroundKey: RingingKey? = null
    private var ringing = false
    private var player: MediaPlayer? = null

    /** The player could not start: the foreground notification is the audible backup instead. */
    private var audibleFallback = false
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
        val notification = readNotification(intent, EXTRA_NOTIFICATION)
        val fallback = readNotification(intent, EXTRA_FALLBACK_NOTIFICATION) ?: notification
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, key.alarmaId.hashCode())
        if (notification == null || fallback == null) {
            Log.e(AlarmasRuntime.TAG, "ringing ${key.alarmaId}: no notification in the start intent")
            if (!set.isRinging) stopSelf()
            return
        }
        notifications[key] = Shown(notificationId, notification, fallback)
        if (!showForeground(key)) {
            // Not in the foreground, so the service cannot play: the backup channel rings instead.
            notifications.remove(key)
            AlarmNotifications.notifyId(this, notificationId, fallback)
            if (!set.isRinging) stopSelf()
            return
        }
        set.start(key)
        startRinging()
        openAlertScreen(key)
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(RING_MILLIS)
            try {
                AlarmActions.timeout(applicationContext, key.alarmaId, key.programadaPara)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AlarmasRuntime.TAG, "timeout of ${key.alarmaId} failed", e)
            }
            close(key)
        }
        observeLog()
    }

    /**
     * SPEC §8: ColorOS on Android 16 does not launch the notification's full-screen intent, so
     * the service opens the alarm screen itself. Holding "Mostrar sobre otras apps" is what lets
     * a background app start an activity. A failure here never stops the sound.
     */
    private fun openAlertScreen(key: RingingKey) {
        try {
            if (!Settings.canDrawOverlays(this)) return
            val intent = AlarmNotifications
                .alertActivityLaunchIntent(this, key.alarmaId, key.programadaPara)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(AlarmasRuntime.TAG, "opening the alarm screen for ${key.alarmaId} failed", e)
        }
    }

    private fun readNotification(intent: Intent, name: String): Notification? = try {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Notification>(name)
    } catch (e: RuntimeException) {
        Log.e(AlarmasRuntime.TAG, "reading the notification extra '$name' failed", e)
        null
    }

    /** Returns false (and logs why) when the system refuses the foreground start. */
    private fun showForeground(key: RingingKey): Boolean {
        val shown = notifications.getValue(key)
        val notification = if (audibleFallback) shown.fallback else shown.notification
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, shown.id, notification, type)
            foregroundKey = key
            true
        } catch (e: Exception) {
            Log.e(AlarmasRuntime.TAG, "startForeground for ${key.alarmaId} failed", e)
            false
        }
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
            closed?.let { AlarmNotifications.cancelId(this, it.id) }
        }
    }

    /** Wake lock, looping alarm tone and vibration, once per ringing session. */
    private fun startRinging() {
        if (ringing) return
        ringing = true
        if (wakeLock == null) {
            try {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cauce:alarmas:ringing")
                    .apply { acquire(WAKE_LOCK_MILLIS) }
            } catch (e: RuntimeException) {
                Log.e(AlarmasRuntime.TAG, "acquiring the ringing wake lock failed", e)
            }
        }
        player = createPlayer()
        if (player == null) useAudibleFallback()
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0), ALARM_ATTRIBUTES)
        } catch (e: RuntimeException) {
            Log.e(AlarmasRuntime.TAG, "starting the vibration failed", e)
        }
    }

    private fun createPlayer(): MediaPlayer? {
        var created: MediaPlayer? = null
        return try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            MediaPlayer().also { created = it }.apply {
                setAudioAttributes(ALARM_ATTRIBUTES)
                setDataSource(this@AlarmRingingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(AlarmasRuntime.TAG, "alarm tone could not play; using the audible backup", e)
            runCatching { created?.release() }
            null
        }
    }

    /**
     * The player failed: the foreground notification becomes the audible backup (alarm sound on
     * its own channel, insistent). Re-posting the same id updates the foreground notification.
     */
    private fun useAudibleFallback() {
        if (audibleFallback) return
        audibleFallback = true
        val key = foregroundKey ?: return
        if (!showForeground(key)) {
            notifications[key]?.let { AlarmNotifications.notifyId(this, it.id, it.fallback) }
        }
    }

    private fun stopRinging() {
        ringing = false
        audibleFallback = false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { getSystemService(Vibrator::class.java)?.cancel() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** A ringing firing's foreground notification and its audible backup (same id). */
    private class Shown(val id: Int, val notification: Notification, val fallback: Notification)

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
        private const val EXTRA_FALLBACK_NOTIFICATION = "fallback_notification"
        private val RING_MILLIS = RING_DURATION.toMillis()
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
            fallback: Notification,
        ): Boolean = try {
            val intent = Intent(context, AlarmRingingService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmReceiver.EXTRA_ALARMA_ID, alarmaId)
                .putExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA, programadaPara)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_NOTIFICATION, notification)
                .putExtra(EXTRA_FALLBACK_NOTIFICATION, fallback)
            context.startForegroundService(intent)
            true
        } catch (e: RuntimeException) {
            Log.e(AlarmasRuntime.TAG, "startForegroundService for $alarmaId refused", e)
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
