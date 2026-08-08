package ru.andriyshkoy.lifeagent

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Closeable
import ru.andriyshkoy.lifeagent.data.export.CanonicalLifeAgentExportCodec
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.security.DatabaseKeyManager
import ru.andriyshkoy.lifeagent.data.security.RetryableSensitiveStorageCloser
import ru.andriyshkoy.lifeagent.data.security.SqlCipherKey
import ru.andriyshkoy.lifeagent.data.security.SqlCipherOpenHelperFactoryProvider
import ru.andriyshkoy.lifeagent.data.security.SqlCipherRuntime
import ru.andriyshkoy.lifeagent.data.security.closeDatabaseThenKey
import ru.andriyshkoy.lifeagent.domain.export.ExportLifeAgentUseCase
import ru.andriyshkoy.lifeagent.domain.export.RepositoriesLifeAgentExportSnapshotSource
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.wellbeing.data.RoomWellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.data.RoomWellbeingRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingRepository

/**
 * Process graph for encrypted local storage.
 *
 * The SQLCipher key holder deliberately lives for exactly as long as Room.
 */
class AppContainer(
    context: Context,
) : Closeable {
    private val databaseKey: SqlCipherKey
    private val database: LifeAgentDatabase
    private val storageCloser: RetryableSensitiveStorageCloser

    val notesRepository: NotesRepository
    val wellbeingRepository: WellbeingRepository
    val wellbeingCatalogRepository: WellbeingCatalogRepository
    val exportLifeAgent: ExportLifeAgentUseCase

    init {
        SqlCipherRuntime.initialize()

        val key = DatabaseKeyManager(
            context = context,
            databaseName = LifeAgentDatabase.NAME,
        ).openSqlCipherKey()
        var openedDatabase: LifeAgentDatabase? = null

        try {
            val db = LifeAgentDatabaseFactory.create(
                context = context,
                openHelperFactory = SqlCipherOpenHelperFactoryProvider.create(key),
            )
            openedDatabase = db
            verifyEncryptedDatabase(db.openHelper.writableDatabase)

            val notes = RoomNotesRepository(
                database = db,
                collectorVersion = BuildConfig.VERSION_NAME,
            )
            val wellbeing = RoomWellbeingRepository(
                database = db,
                collectorVersion = BuildConfig.VERSION_NAME,
            )
            val wellbeingCatalog = RoomWellbeingCatalogRepository(database = db)
            databaseKey = key
            database = db
            notesRepository = notes
            wellbeingRepository = wellbeing
            wellbeingCatalogRepository = wellbeingCatalog
            exportLifeAgent = ExportLifeAgentUseCase(
                source = RepositoriesLifeAgentExportSnapshotSource(
                    notesRepository = notesRepository,
                    wellbeingRepository = wellbeingRepository,
                    wellbeingCatalogRepository = wellbeingCatalogRepository,
                ),
                codec = CanonicalLifeAgentExportCodec(),
            )
            storageCloser = RetryableSensitiveStorageCloser(
                closeDatabase = database::close,
                closeKey = databaseKey::close,
            )
        } catch (error: Throwable) {
            throw checkNotNull(
                closeDatabaseThenKey(
                    primaryFailure = error,
                    closeDatabase = { openedDatabase?.close() },
                    closeKey = key::close,
                ),
            )
        }
    }

    override fun close() = storageCloser.close()

    private fun verifyEncryptedDatabase(sqlite: SupportSQLiteDatabase) {
        require(singlePragmaValue(sqlite, "PRAGMA cipher_status") == "1") {
            "Encrypted storage verification failed."
        }
        require(singlePragmaValue(sqlite, "PRAGMA cipher_version").isNotBlank()) {
            "Encrypted storage verification failed."
        }
    }

    private fun singlePragmaValue(
        database: SupportSQLiteDatabase,
        statement: String,
    ): String = database.query(statement).use { cursor ->
        require(cursor.moveToFirst() && cursor.columnCount > 0) {
            "Encrypted storage verification failed."
        }
        cursor.getString(0).orEmpty()
    }
}
