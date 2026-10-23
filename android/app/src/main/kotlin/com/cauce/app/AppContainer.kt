package com.cauce.app

import android.content.Context
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

    private val api = HttpSyncApi(baseUrl = BuildConfig.SYNC_BASE_URL)

    val syncEngine = SyncEngine(
        store = eventStore,
        api = api,
        cursor = SyncCursor(context),
    )

    val blobUploader = BlobUploader(api)
}
