package com.cauce.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The actionable message under Home's sync status line (D22). Only failures that
 * retrying will not fix are shown; network errors, 429 and 5xx stay silent because WorkManager
 * retries them. Lives in memory: the app runs a sync on every start, so it reappears there.
 */
object SyncFeedback {
    private val state = MutableStateFlow<String?>(null)

    /** A readable Spanish message, or null when the last sync was fine (or only transient). */
    val problem: StateFlow<String?> = state.asStateFlow()

    fun succeeded() {
        state.value = null
    }

    fun failed(error: Throwable) {
        messageFor(error)?.let { state.value = it }
    }

    fun messageFor(error: Throwable): String? = when {
        error is SyncHttpException && error.code == 401 ->
            "Sin sincronizar: el servidor rechazó el token de la app (401). " +
                "Hay que recompilar el APK con el cauceApiToken vigente."
        error is SyncHttpException && error.code in 400..499 && error.code != 429 ->
            "Sin sincronizar: el servidor respondió HTTP ${error.code}."
        else -> null
    }
}
