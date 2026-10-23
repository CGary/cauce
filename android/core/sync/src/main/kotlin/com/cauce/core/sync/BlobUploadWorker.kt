package com.cauce.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException

/** Presign with the server, then PUT the file to object storage. Wifi only, with retries. */
class BlobUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uploader = SyncRuntime.uploader() ?: return Result.failure()
        val key = inputData.getString(KEY_KEY) ?: return Result.failure()
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val contentType = inputData.getString(KEY_CONTENT_TYPE) ?: "application/octet-stream"

        return try {
            if (uploader.upload(key, contentType, File(path))) Result.success() else Result.failure()
        } catch (e: SyncHttpException) {
            if (e.code >= 500 || e.code == 429) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_KEY = "key"
        const val KEY_PATH = "path"
        const val KEY_CONTENT_TYPE = "content_type"

        fun request(key: String, path: String, contentType: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BlobUploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_KEY to key,
                        KEY_PATH to path,
                        KEY_CONTENT_TYPE to contentType,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build()
    }
}
