package com.cauce.core.sync

import android.content.Context

/**
 * The seam between workers (constructed by WorkManager, not by us) and the app's manual wiring.
 * `:app` calls [configure] once in `Application.onCreate`.
 */
object SyncRuntime {
    @Volatile
    private var syncEngine: SyncEngine? = null

    @Volatile
    private var blobUploader: BlobUploader? = null

    @Volatile
    private var onPulled: (Context, Set<String>) -> Unit = { _, _ -> }

    fun configure(
        engine: SyncEngine,
        uploader: BlobUploader,
        onPulled: (Context, Set<String>) -> Unit = { _, _ -> },
    ) {
        syncEngine = engine
        blobUploader = uploader
        this.onPulled = onPulled
    }

    fun engine(): SyncEngine? = syncEngine

    fun uploader(): BlobUploader? = blobUploader

    /** [context] is the worker's application context; it travels with the pulled types. */
    fun notifyPulled(context: Context, types: Set<String>) {
        if (types.isNotEmpty()) onPulled(context, types)
    }
}
