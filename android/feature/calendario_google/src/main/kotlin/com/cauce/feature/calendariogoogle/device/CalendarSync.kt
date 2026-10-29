package com.cauce.feature.calendariogoogle.device

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cauce.core.events.cauceServices
import com.cauce.feature.calendariogoogle.previousState
import com.cauce.feature.calendariogoogle.syncActionEvent
import com.cauce.feature.calendariogoogle.syncDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Calendar -> event log: read, diff against this feature's own events, append what changed. */
internal object CalendarSync {
    private const val WORK_NAME = "calendario_google.sync"
    private const val DEBOUNCE_MS = 5_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null

    fun deviceId(): String = Build.MODEL.ifBlank { "android" }

    /** Returns how many events were appended; 0 also when the permission is missing. */
    suspend fun run(context: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = Instant.now()
            val current = CalendarReader.read(context, now) ?: return@withLock 0
            val store = context.cauceServices().eventStore
            val own = store.observeByTypes(setOf("reunion_sincronizada", "reunion_eliminada_en_origen")).first()
            val events = syncDiff(previousState(own), current, now)
                .map { syncActionEvent(deviceId(), now, it) }
            if (events.isNotEmpty()) store.append(events)
            events.size
        }
    }

    fun runAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { run(app) }
    }

    /** Observer on the provider (debounced) plus the 15-minute periodic worker (SPEC 2.6). */
    fun start(context: Context) {
        val app = context.applicationContext
        if (observer == null) {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.removeCallbacks(debounced)
                    handler.postDelayed(debounced, DEBOUNCE_MS)
                }
                private val debounced = Runnable { runAsync(app) }
            }
            try {
                app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, obs)
                observer = obs
            } catch (_: SecurityException) {
                // No permission yet: the list screen asks for it and triggers a sync once granted.
            }
        }
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CalendarSync.run(applicationContext)
        return Result.success()
    }
}
