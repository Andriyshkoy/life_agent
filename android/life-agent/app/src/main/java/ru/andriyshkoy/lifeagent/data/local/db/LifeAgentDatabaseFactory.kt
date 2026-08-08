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
        // Versions 1..6 were pre-product schemas with no retained user data.
        .fallbackToDestructiveMigrationFrom(
            true,
            1,
            2,
            3,
            4,
            5,
            6,
        )
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
