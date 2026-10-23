package com.cauce.core.sync

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
    private var onPulled: (Set<String>) -> Unit = {}

    fun configure(
        engine: SyncEngine,
        uploader: BlobUploader,
        onPulled: (Set<String>) -> Unit = {},
    ) {
        syncEngine = engine
        blobUploader = uploader
        this.onPulled = onPulled
    }

    fun engine(): SyncEngine? = syncEngine

    fun uploader(): BlobUploader? = blobUploader

    fun notifyPulled(types: Set<String>) {
        if (types.isNotEmpty()) onPulled(types)
    }
}
