package com.cauce.app

import android.content.Context
import android.os.Build
import com.cauce.core.events.EventStore
import com.cauce.core.events.EventStores
import com.cauce.core.events.projection.ProjectionRegistry
import com.cauce.core.sync.BlobUploader
import com.cauce.core.sync.HttpSyncApi
import com.cauce.core.sync.SyncCursor
import com.cauce.core.sync.SyncEngine

/**
 * Manual wiring. Everything is constructed here, once, in dependency order. Activities and
 * workers reach it through [CauceApp.container] and [com.cauce.core.sync.SyncRuntime].
 */
class AppContainer(context: Context) {
    val eventStore: EventStore = EventStores.room(
        context = context,
        validator = buildEventValidator(context),
    )

    val projections: ProjectionRegistry = ProjectionRegistry.aggregate(FeatureRegistry.features)

    /** Same id the features put in `device_id` (e.g. AlarmasRuntime.deviceId()); pull sends it (D22). */
    private val deviceId: String = Build.MODEL.ifBlank { "android" }

    private val api = HttpSyncApi(
        baseUrl = BuildConfig.SYNC_BASE_URL,
        apiToken = BuildConfig.API_TOKEN,
        deviceId = deviceId,
    )

    val syncEngine = SyncEngine(
        store = eventStore,
        api = api,
        cursor = SyncCursor(context),
    )

    val blobUploader = BlobUploader(api)
}
