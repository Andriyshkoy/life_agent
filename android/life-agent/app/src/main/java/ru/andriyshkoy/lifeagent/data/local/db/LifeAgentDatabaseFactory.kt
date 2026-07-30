package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper

object LifeAgentDatabaseFactory {
    fun create(
        context: Context,
        openHelperFactory: SupportSQLiteOpenHelper.Factory,
        databaseName: String = LifeAgentDatabase.NAME,
    ): LifeAgentDatabase = Room.databaseBuilder(
        context.applicationContext,
        LifeAgentDatabase::class.java,
        databaseName,
    )
        .openHelperFactory(openHelperFactory)
        .addMigrations(*DatabaseMigrations.ALL)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    fun createInMemory(context: Context): LifeAgentDatabase =
        Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            LifeAgentDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
}
