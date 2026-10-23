package com.cauce.app

import android.app.Application
import com.cauce.core.events.CauceServices
import com.cauce.core.events.EventStore
import com.cauce.core.events.projection.ProjectionRegistry
import com.cauce.core.sync.SyncRuntime
import com.cauce.core.sync.SyncScheduler

/**
 * The app's manual wiring. It is also the [CauceServices] every feature reaches through
 * `context.cauceServices()`; there is no DI framework.
 */
class CauceApp : Application(), CauceServices {
    lateinit var container: AppContainer
        private set

    override val eventStore: EventStore get() = container.eventStore

    override val projections: ProjectionRegistry get() = container.projections

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)

        SyncRuntime.configure(
            engine = container.syncEngine,
            uploader = container.blobUploader,
            onPulled = { ctx, types ->
                // Device-side effects are re-applied only for features touched by the pull
                // (AGENTS.md hard rule).
                FeatureRegistry.features.forEach { feature ->
                    if (feature.eventTypes.any { it in types }) feature.onEventsPulled(ctx, types)
                }
            },
        )

        SyncScheduler.schedule(this)
        FeatureRegistry.features.forEach { feature -> feature.onAppStart(this) }
        SyncScheduler.syncNow(this)
    }
}
