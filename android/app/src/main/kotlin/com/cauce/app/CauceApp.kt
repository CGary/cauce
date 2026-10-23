package com.cauce.app

import android.app.Application
import com.cauce.core.sync.SyncRuntime
import com.cauce.core.sync.SyncScheduler

class CauceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)

        SyncRuntime.configure(
            engine = container.syncEngine,
            uploader = container.blobUploader,
            onPulled = {
                // Device-side effects are re-applied after a pull (AGENTS.md hard rule).
                FeatureRegistry.features.forEach { feature -> feature.onAppStart(this) }
            },
        )

        SyncScheduler.schedule(this)
        FeatureRegistry.features.forEach { feature -> feature.onAppStart(this) }
        SyncScheduler.syncNow(this)
    }
}
