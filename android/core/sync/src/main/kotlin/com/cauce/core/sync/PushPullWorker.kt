package com.cauce.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

/** Periodic (and manual) push/pull. Network and backoff are WorkManager's job. */
class PushPullWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = SyncRuntime.engine() ?: return Result.failure()

        return try {
            val pulledTypes = engine.sync()
            SyncFeedback.succeeded()
            SyncRuntime.notifyPulled(applicationContext, pulledTypes)
            Result.success()
        } catch (e: SyncHttpException) {
            // A 401 (wrong or missing token) is not retried: it surfaces on Home instead (D20).
            SyncFeedback.failed(e)
            if (e.code >= 500 || e.code == 429) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}
