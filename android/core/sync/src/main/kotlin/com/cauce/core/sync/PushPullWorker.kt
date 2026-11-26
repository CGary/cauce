package com.cauce.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Periodic (and manual) push/pull. Network and backoff are WorkManager's job. Every attempt,
 * ok or not, is recorded in [SyncAttempts] so Home can show it (D22).
 */
class PushPullWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = SyncRuntime.engine() ?: return Result.failure()
        val attempts = SyncAttempts(applicationContext)
        val progress = SyncProgress()

        return try {
            val pulledTypes = engine.sync(progress)
            attempts.record(SyncAttemptLog.succeeded(Instant.now(), progress))
            SyncFeedback.succeeded()
            SyncRuntime.notifyPulled(applicationContext, pulledTypes)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncHttpException) {
            attempts.record(SyncAttemptLog.failed(Instant.now(), e, progress))
            // A 401 (wrong or missing token) is not retried: it surfaces on Home instead (D20).
            SyncFeedback.failed(e)
            if (e.code >= 500 || e.code == 429) Result.retry() else Result.failure()
        } catch (e: IOException) {
            attempts.record(SyncAttemptLog.failed(Instant.now(), e, progress))
            Result.retry()
        } catch (e: Exception) {
            attempts.record(SyncAttemptLog.failed(Instant.now(), e, progress))
            Result.failure()
        }
    }
}
