package com.cauce.core.events.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class CauceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        private const val NAME = "cauce.db"

        fun build(context: Context): CauceDatabase =
            Room.databaseBuilder(context.applicationContext, CauceDatabase::class.java, NAME).build()
    }
}
