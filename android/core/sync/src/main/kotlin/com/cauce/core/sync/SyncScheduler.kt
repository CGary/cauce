package com.cauce.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_WORK = "cauce.sync.periodic"
    private const val NOW_WORK = "cauce.sync.now"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Every 15 minutes while online, plus a manual trigger through [syncNow]. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<PushPullWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushPullWorker>()
            .setConstraints(networkConstraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
