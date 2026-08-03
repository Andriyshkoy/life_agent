package ru.andriyshkoy.lifeagent

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Closeable
import ru.andriyshkoy.lifeagent.data.export.CanonicalNotesExportCodec
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.security.DatabaseKeyManager
import ru.andriyshkoy.lifeagent.data.security.RetryableSensitiveStorageCloser
import ru.andriyshkoy.lifeagent.data.security.SqlCipherKey
import ru.andriyshkoy.lifeagent.data.security.SqlCipherOpenHelperFactoryProvider
import ru.andriyshkoy.lifeagent.data.security.SqlCipherRuntime
import ru.andriyshkoy.lifeagent.data.security.closeProcessSecretsDatabaseThenKey
import ru.andriyshkoy.lifeagent.data.sync.work.SyncWorkExecutionPort
import ru.andriyshkoy.lifeagent.domain.export.ExportNotesUseCase
import ru.andriyshkoy.lifeagent.domain.export.NotesRepositoryExportSnapshotSource
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.NotesRepository
import ru.andriyshkoy.lifeagent.ui.sync.DefaultSyncSetupController

/**
 * Process graph for local-first storage and the protected M2 sync runtime.
 *
 * The SQLCipher key holder deliberately lives for exactly as long as Room.
 */
class AppContainer(
    context: Context,
) : Closeable {
    private val databaseKey: SqlCipherKey
    private val database: LifeAgentDatabase
    private val m2Runtime: M2ProductionAppRuntime
    private val storageCloser: RetryableSensitiveStorageCloser

    val notesRepository: NotesRepository
    val exportNotes: ExportNotesUseCase

    init {
        SqlCipherRuntime.initialize()

        val key = DatabaseKeyManager(
            context = context,
            databaseName = LifeAgentDatabase.NAME,
        ).openSqlCipherKey()
        var openedDatabase: LifeAgentDatabase? = null
        var openedM2Runtime: M2ProductionAppRuntime? = null

        try {
            val db = LifeAgentDatabaseFactory.create(
                context = context,
                openHelperFactory = SqlCipherOpenHelperFactoryProvider.create(key),
            )
            openedDatabase = db
            verifyEncryptedDatabase(db.openHelper.writableDatabase)

            val repository = RoomNotesRepository(
                database = db,
                collectorVersion = BuildConfig.VERSION_NAME,
            )
            val runtime = M2ProductionAppRuntime.create(
                context = context,
                database = db,
                localNotesRepository = repository,
            )
            openedM2Runtime = runtime
            databaseKey = key
            database = db
            m2Runtime = runtime
            notesRepository = runtime.notesRepository
            exportNotes = ExportNotesUseCase(
                source = NotesRepositoryExportSnapshotSource(notesRepository),
                codec = CanonicalNotesExportCodec(),
            )
            storageCloser = RetryableSensitiveStorageCloser(
                closeDatabase = database::close,
                closeKey = databaseKey::close,
                closeProcessSecrets = m2Runtime::close,
            )
        } catch (error: Throwable) {
            throw checkNotNull(
                closeProcessSecretsDatabaseThenKey(
                    primaryFailure = error,
                    closeProcessSecrets = { openedM2Runtime?.close() },
                    closeDatabase = { openedDatabase?.close() },
                    closeKey = key::close,
                ),
            )
        }
    }

    internal val syncWorkExecutionPort: SyncWorkExecutionPort
        get() = m2Runtime.syncWorkExecutionPort

    internal fun enqueueSyncAtStartup() = m2Runtime.enqueueAtStartup()

    internal fun createSyncSetupController(): DefaultSyncSetupController =
        m2Runtime.createSyncSetupController()

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
