package com.cauce.core.events

import android.content.Context
import com.cauce.core.events.projection.ProjectionRegistry

/**
 * The app-level services a feature module needs: the local event log and the projections.
 *
 * `:app` implements this on the `Application` (the instances it already creates in `AppContainer`),
 * so a feature reaches them from any [Context] with [cauceServices]. No DI framework, no service
 * locator of its own.
 */
interface CauceServices {
    /**
     * The local, append-only log. Read with [EventStore.observeByTypes] or [EventStore.observeAll];
     * write with [EventStore.append] (e.g. `alarma_atendida` from a notification action).
     */
    val eventStore: EventStore

    /** Every feature's projections, dispatched by `<feature>.<name>` key. */
    val projections: ProjectionRegistry
}

/** The app's [CauceServices], from any context (activity, worker, receiver). */
fun Context.cauceServices(): CauceServices = applicationContext as CauceServices
