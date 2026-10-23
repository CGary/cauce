package com.cauce.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

/** The `server_seq` cursor: the last server position this device has applied. */
class SyncCursor(private val context: Context) {
    private val key = longPreferencesKey("server_seq")

    val flow: Flow<Long> = context.syncDataStore.data.map { it[key] ?: 0L }

    suspend fun read(): Long = context.syncDataStore.data.first()[key] ?: 0L

    suspend fun write(value: Long) {
        context.syncDataStore.edit { it[key] = value }
    }
}
