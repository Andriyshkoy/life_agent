package ru.andriyshkoy.lifeagent.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.core.id.MutationIds
import ru.andriyshkoy.lifeagent.core.time.PointTimeResolver
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.security.DatabaseKeyManager
import ru.andriyshkoy.lifeagent.data.security.SqlCipherKey
import ru.andriyshkoy.lifeagent.data.security.SqlCipherOpenHelperFactoryProvider
import ru.andriyshkoy.lifeagent.data.security.SqlCipherRuntime
import ru.andriyshkoy.lifeagent.notes.data.RoomNotesRepository
import ru.andriyshkoy.lifeagent.notes.domain.CorrectNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.CreateNoteCommand
import ru.andriyshkoy.lifeagent.notes.domain.IdempotencyConflictException
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationDisposition
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationOutcome
import ru.andriyshkoy.lifeagent.notes.domain.NoteMutationReceipt
import ru.andriyshkoy.lifeagent.notes.domain.NoteRecordStatus
import ru.andriyshkoy.lifeagent.notes.domain.RetractNoteCommand
import ru.andriyshkoy.lifeagent.wellbeing.data.RoomWellbeingCatalogRepository
import ru.andriyshkoy.lifeagent.wellbeing.data.RoomWellbeingRepository
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.CreateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.UpdateWellbeingDimensionCommand
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationDisposition
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationOutcome
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingMutationReceipt
import ru.andriyshkoy.lifeagent.wellbeing.domain.WellbeingOptionDraft

@RunWith(AndroidJUnit4::class)
class EncryptedNotesPersistenceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testId: String
    private lateinit var keyAlias: String
    private lateinit var databaseName: String
    private lateinit var envelopeRelativePath: String
    private var sqlCipherKey: SqlCipherKey? = null
    private var database: LifeAgentDatabase? = null
    private lateinit var repository: RoomNotesRepository
    private lateinit var wellbeingRepository: RoomWellbeingRepository
    private lateinit var wellbeingCatalogRepository: RoomWellbeingCatalogRepository

    @Before
    fun setUp() {
        testId = UUID.randomUUID().toString()
        keyAlias = "life_agent_persistence_test_$testId"
        databaseName = "life-agent-persistence-$testId.db"
        envelopeRelativePath = "persistence-tests/$testId/room-dek-v1"
        SqlCipherRuntime.initialize()
        openStore()
    }

    @After
    fun tearDown() {
        closeStore()
        context.deleteDatabase(databaseName)
        val envelope = File(context.noBackupFilesDir, envelopeRelativePath)
        listOf("", ".bak", ".new").forEach { suffix ->
            File(envelope.path + suffix).delete()
        }
        envelope.parentFile?.delete()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(keyAlias)) {
                deleteEntry(keyAlias)
            }
        }
    }

    @Test
    fun noteLifecycleIsAppendOnlyIdempotentAndDurableAcrossReopen() = runBlocking {
        val create = CreateNoteCommand(
            ids = mutationIds(1, 2, EVENT_ID, 4),
            text = "Локальная заметка — ёж",
            effectiveTime = effectiveTime(0),
            recordedAt = recordedAt(1),
        )
        val created = persisted(repository.create(create))
        assertEquals(NoteMutationDisposition.COMMITTED, created.disposition)
        assertEquals(
            NoteMutationDisposition.REPLAYED,
            persisted(repository.create(create)).disposition,
        )
        assertCounts(captures = 1, revisions = 1, parents = 0)

        val changedReplayFailure = runCatching {
            repository.create(create.copy(text = "Изменённая команда"))
        }.exceptionOrNull()
        assertTrue(changedReplayFailure is IdempotencyConflictException)
        assertCounts(captures = 1, revisions = 1, parents = 0)

        val correct = CorrectNoteCommand(
            ids = mutationIds(5, 6, EVENT_ID, 7),
            expectedCurrentRevisionId = create.ids.revisionId,
            text = "Исправленная локальная заметка",
            effectiveTime = effectiveTime(2),
            recordedAt = recordedAt(3),
            reason = "проверка истории",
        )
        val corrected = persisted(repository.correct(correct))
        assertEquals(2, corrected.note.revisionNo)
        assertEquals(
            NoteMutationDisposition.REPLAYED,
            persisted(repository.correct(correct)).disposition,
        )
        assertCounts(captures = 2, revisions = 2, parents = 1)
        assertParent(
            childRevisionId = correct.ids.revisionId,
            parentRevisionId = create.ids.revisionId,
        )

        val retract = RetractNoteCommand(
            ids = mutationIds(8, 9, EVENT_ID, 10),
            expectedCurrentRevisionId = correct.ids.revisionId,
            recordedAt = recordedAt(4),
        )
        val retracted = persisted(repository.retract(retract))
        assertEquals(NoteRecordStatus.RETRACTED, retracted.note.status)
        assertEquals(
            NoteMutationDisposition.REPLAYED,
            persisted(repository.retract(retract)).disposition,
        )
        assertCounts(captures = 3, revisions = 3, parents = 2)
        assertParent(
            childRevisionId = retract.ids.revisionId,
            parentRevisionId = correct.ids.revisionId,
        )

        closeStore()
        openStore()

        val reopened = requireNotNull(repository.getByEventId(EVENT_ID))
        assertEquals(retract.ids.revisionId, reopened.revisionId)
        assertEquals(3, reopened.revisionNo)
        assertEquals(NoteRecordStatus.RETRACTED, reopened.status)
        assertEquals("Исправленная локальная заметка", reopened.text)
        assertEquals(
            NoteMutationDisposition.REPLAYED,
            persisted(repository.retract(retract)).disposition,
        )

        val export = repository.exportSnapshot()
        assertEquals(retract.ids.revisionId, export.events.single().currentRevisionId)
        assertEquals(listOf(1, 2, 3), export.revisions.map { it.revisionNo })
        assertEquals(
            listOf(
                NoteRecordStatus.ACTIVE,
                NoteRecordStatus.ACTIVE,
                NoteRecordStatus.RETRACTED,
            ),
            export.revisions.map { it.status },
        )

        val redundantRetraction = repository.retract(
            RetractNoteCommand(
                ids = mutationIds(11, 12, EVENT_ID, 13),
                expectedCurrentRevisionId = retract.ids.revisionId,
                recordedAt = recordedAt(5),
            ),
        )
        assertTrue(redundantRetraction is NoteMutationOutcome.AlreadyRetracted)
        assertCounts(captures = 3, revisions = 3, parents = 2)
    }

    @Test
    fun concurrentCommandReplayCommitsOneLocalRevision() = runBlocking {
        val command = CreateNoteCommand(
            ids = mutationIds(21, 22, CONCURRENT_EVENT_ID, 24),
            text = "Параллельный локальный повтор",
            effectiveTime = effectiveTime(10),
            recordedAt = recordedAt(11),
        )

        val receipts = coroutineScope {
            List(CONCURRENT_RETRY_COUNT) {
                async(Dispatchers.Default) {
                    persisted(repository.create(command))
                }
            }.awaitAll()
        }

        assertEquals(
            1,
            receipts.count { it.disposition == NoteMutationDisposition.COMMITTED },
        )
        assertEquals(
            CONCURRENT_RETRY_COUNT - 1,
            receipts.count { it.disposition == NoteMutationDisposition.REPLAYED },
        )
        assertCounts(captures = 1, revisions = 1, parents = 0)
    }

    @Test
    fun productionRoomNoteMarkersStayEncryptedAcrossReopen() = runBlocking {
        val command = CreateNoteCommand(
            ids = MutationIds(
                operationId = PRODUCTION_OPERATION_ID,
                captureId = PRODUCTION_CAPTURE_ID,
                eventId = PRODUCTION_EVENT_ID,
                revisionId = PRODUCTION_REVISION_ID,
            ),
            text = PRODUCTION_NOTE_SENTINEL,
            effectiveTime = effectiveTime(15),
            recordedAt = recordedAt(16),
        )

        persisted(repository.create(command))
        assertEquals(
            PRODUCTION_NOTE_SENTINEL,
            requireNotNull(repository.getByEventId(PRODUCTION_EVENT_ID)).text,
        )
        assertEncryptedArtifacts()

        closeStore()
        assertEncryptedArtifacts()
        openStore()

        assertEquals(
            PRODUCTION_NOTE_SENTINEL,
            requireNotNull(repository.getByEventId(PRODUCTION_EVENT_ID)).text,
        )
        assertEncryptedArtifacts()
    }

    @Test
    fun wellbeingReplayCatalogSnapshotsAndNoteCoexistenceSurviveEncryptedReopen() =
        runBlocking {
            val noteCommand = CreateNoteCommand(
                ids = MutationIds(
                    operationId = COEXISTENCE_NOTE_OPERATION_ID,
                    captureId = COEXISTENCE_NOTE_CAPTURE_ID,
                    eventId = COEXISTENCE_NOTE_EVENT_ID,
                    revisionId = COEXISTENCE_NOTE_REVISION_ID,
                ),
                text = COEXISTENCE_NOTE_SENTINEL,
                effectiveTime = effectiveTime(20),
                recordedAt = recordedAt(21),
            )
            persisted(repository.create(noteCommand))

            wellbeingCatalogRepository.ensureSeeded(BASE_INSTANT.plusSeconds(22 * 60))
            assertEquals(
                DEFAULT_WELLBEING_DIMENSION_COUNT,
                wellbeingCatalogRepository.observeDimensions().first().size,
            )
            val originalDimension = wellbeingCatalogRepository.create(
                CreateWellbeingDimensionCommand(
                    dimensionId = WELLBEING_DIMENSION_ID,
                    catalogVersionId = WELLBEING_CATALOG_VERSION_ONE_ID,
                    label = WELLBEING_DIMENSION_SENTINEL,
                    sortOrder = 50,
                    options = listOf(
                        WellbeingOptionDraft(
                            optionId = WELLBEING_OPTION_ID,
                            label = WELLBEING_OPTION_SENTINEL,
                            sortOrder = 10,
                        ),
                    ),
                    createdAt = BASE_INSTANT.plusSeconds(23 * 60),
                ),
            )
            val valueSnapshot = originalDimension.snapshot(WELLBEING_OPTION_ID)
            val command = CreateWellbeingCommand(
                ids = MutationIds(
                    operationId = WELLBEING_OPERATION_ID,
                    captureId = WELLBEING_CAPTURE_ID,
                    eventId = WELLBEING_EVENT_ID,
                    revisionId = WELLBEING_REVISION_ID,
                ),
                values = listOf(valueSnapshot),
                comment = WELLBEING_COMMENT_SENTINEL,
                effectiveTime = effectiveTime(24),
                recordedAt = recordedAt(25),
            )

            val committed = persistedWellbeing(wellbeingRepository.create(command))
            val replayed = persistedWellbeing(wellbeingRepository.create(command))
            assertEquals(WellbeingMutationDisposition.COMMITTED, committed.disposition)
            assertEquals(WellbeingMutationDisposition.REPLAYED, replayed.disposition)
            assertEquals(committed.wellbeing, replayed.wellbeing)
            assertCounts(
                captures = 2,
                events = 2,
                revisions = 2,
                parents = 0,
            )

            val updatedDimension = wellbeingCatalogRepository.update(
                UpdateWellbeingDimensionCommand(
                    dimensionId = WELLBEING_DIMENSION_ID,
                    catalogVersionId = WELLBEING_CATALOG_VERSION_TWO_ID,
                    expectedCurrentVersionId = WELLBEING_CATALOG_VERSION_ONE_ID,
                    label = WELLBEING_UPDATED_DIMENSION_SENTINEL,
                    sortOrder = 50,
                    active = true,
                    options = listOf(
                        WellbeingOptionDraft(
                            optionId = WELLBEING_OPTION_ID,
                            label = WELLBEING_UPDATED_OPTION_SENTINEL,
                            sortOrder = 10,
                        ),
                    ),
                    createdAt = BASE_INSTANT.plusSeconds(26 * 60),
                ),
            )
            assertEquals(2, updatedDimension.version)
            assertEquals(2, updatedDimension.options.single().version)

            val sensitiveMarkers = arrayOf(
                COEXISTENCE_NOTE_SENTINEL,
                WELLBEING_COMMENT_SENTINEL,
                WELLBEING_DIMENSION_SENTINEL,
                WELLBEING_OPTION_SENTINEL,
                WELLBEING_UPDATED_DIMENSION_SENTINEL,
                WELLBEING_UPDATED_OPTION_SENTINEL,
                WELLBEING_OPERATION_ID.toString(),
                WELLBEING_REVISION_ID.toString(),
            )
            assertEncryptedArtifacts(*sensitiveMarkers)

            closeStore()
            assertEncryptedArtifacts(*sensitiveMarkers)
            openStore()

            assertEquals(
                COEXISTENCE_NOTE_SENTINEL,
                requireNotNull(repository.getByEventId(COEXISTENCE_NOTE_EVENT_ID)).text,
            )
            val reopenedWellbeing = requireNotNull(
                wellbeingRepository.getByEventId(WELLBEING_EVENT_ID),
            )
            assertEquals(WELLBEING_COMMENT_SENTINEL, reopenedWellbeing.payload.comment)
            assertEquals(listOf(valueSnapshot), reopenedWellbeing.payload.values)
            assertEquals(
                WellbeingMutationDisposition.REPLAYED,
                persistedWellbeing(wellbeingRepository.create(command)).disposition,
            )
            assertEquals(
                WELLBEING_EVENT_ID,
                requireNotNull(wellbeingRepository.observeLastCommitted().first()).eventId,
            )

            val reopenedCatalog = requireNotNull(
                wellbeingCatalogRepository.getDimension(WELLBEING_DIMENSION_ID),
            )
            assertEquals(WELLBEING_UPDATED_DIMENSION_SENTINEL, reopenedCatalog.label)
            assertEquals(WELLBEING_UPDATED_OPTION_SENTINEL, reopenedCatalog.options.single().label)
            assertEquals(2, reopenedCatalog.version)
            assertEquals(2, reopenedCatalog.options.single().version)
            assertEquals(
                listOf(1, 2),
                wellbeingCatalogRepository.exportSnapshot()
                    .versions
                    .filter { it.catalogItemId == WELLBEING_DIMENSION_ID }
                    .map { it.version },
            )
            assertCounts(
                captures = 2,
                events = 2,
                revisions = 2,
                parents = 0,
            )
            assertEncryptedArtifacts(*sensitiveMarkers)
        }

    @Test
    fun currentSchemaContainsOnlyTheElevenLocalTables() {
        assertEquals(EXPECTED_ROOM_SCHEMA_VERSION, LifeAgentDatabase.VERSION)
        val db = requireDatabase().openHelper.writableDatabase
        val localTables = db.query(
            """
            SELECT name
            FROM sqlite_schema
            WHERE type = 'table' AND name LIKE 'local_%'
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
        assertEquals(EXPECTED_LOCAL_TABLES.sorted(), localTables)
        assertEquals(
            LifeAgentDatabase.VERSION,
            db.query("PRAGMA user_version").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            },
        )
        assertEquals(
            ROOM_SCHEMA_IDENTITY_HASH,
            db.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            },
        )

        val allApplicationTables = db.query(
            """
            SELECT name
            FROM sqlite_schema
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
              AND name NOT IN ('android_metadata', 'room_master_table')
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
        assertEquals(EXPECTED_LOCAL_TABLES.sorted(), allApplicationTables)

        val revisionColumns = tableColumns("local_event_revision")
        assertTrue(revisionColumns.contains("command_fingerprint_sha256"))
    }

    @Test
    fun legacyVersionSixIsDestructivelyResetToTheLocalVersionSevenSchema() {
        closeRoomKeepingKey()
        context.deleteDatabase(databaseName)

        val legacyHelper = SqlCipherOpenHelperFactoryProvider
            .create(requireNotNull(sqlCipherKey))
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(databaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(6) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE local_legacy_probe(
                                        id INTEGER PRIMARY KEY,
                                        server_person_id TEXT,
                                        value TEXT NOT NULL
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TABLE sync_transport_probe(
                                        id INTEGER PRIMARY KEY,
                                        payload TEXT NOT NULL
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE INDEX sync_transport_probe_payload_idx
                                    ON sync_transport_probe(payload)
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    CREATE TRIGGER sync_transport_probe_guard
                                    BEFORE UPDATE ON sync_transport_probe
                                    BEGIN
                                        SELECT RAISE(ABORT, 'legacy transport is immutable');
                                    END
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    INSERT INTO local_legacy_probe(id, server_person_id, value)
                                    VALUES(1, 'legacy-person', 'legacy-row')
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    """
                                    INSERT INTO sync_transport_probe(id, payload)
                                    VALUES(1, 'legacy-payload')
                                    """.trimIndent(),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    )
                    .build(),
            )
        legacyHelper.writableDatabase
        legacyHelper.close()

        openRoomWithCurrentKey()

        val db = requireDatabase().openHelper.writableDatabase
        assertEquals(LifeAgentDatabase.VERSION, pragmaUserVersion(db))
        assertEquals(0, applicationObjectCount(db, "local_legacy_probe"))
        assertEquals(0, applicationObjectCount(db, "sync_*"))
        val counts = runBlocking { requireDatabase().lifeEventMutationDao().tableCounts() }
        assertEquals(0, counts.captures)
        assertEquals(0, counts.events)
        assertEquals(0, counts.revisions)
        assertEquals(0, counts.parents)
        assertEquals(0, counts.heads)

        currentApplicationTables(db).forEach { table ->
            tableColumns(table).forEach { column ->
                assertFalse(
                    "Legacy identity column survived destructive reset: $table.$column",
                    column.startsWith("server_"),
                )
            }
        }
    }

    private fun openStore() {
        val key = keyManager().openSqlCipherKey()
        sqlCipherKey = key
        try {
            openRoomWithCurrentKey()
        } catch (failure: Throwable) {
            closeStore()
            throw failure
        }
    }

    private fun openRoomWithCurrentKey() {
        val key = requireNotNull(sqlCipherKey) { "SQLCipher key is closed" }
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = SqlCipherOpenHelperFactoryProvider.create(key),
            databaseName = databaseName,
        )
        repository = RoomNotesRepository(
            database = requireDatabase(),
            collectorVersion = "local-instrumented-test",
        )
        wellbeingRepository = RoomWellbeingRepository(
            database = requireDatabase(),
            collectorVersion = "local-instrumented-test",
        )
        wellbeingCatalogRepository = RoomWellbeingCatalogRepository(
            database = requireDatabase(),
        )
        requireDatabase().openHelper.writableDatabase
    }

    private fun closeRoomKeepingKey() {
        database?.close()
        database = null
    }

    private fun closeStore() {
        closeRoomKeepingKey()
        sqlCipherKey?.close()
        sqlCipherKey = null
    }

    private fun keyManager() = DatabaseKeyManager(
        context = context,
        keyAlias = keyAlias,
        databaseName = databaseName,
        envelopeRelativePath = envelopeRelativePath,
    )

    private fun requireDatabase(): LifeAgentDatabase =
        requireNotNull(database) { "Test database is closed" }

    private suspend fun assertCounts(
        captures: Int,
        events: Int = 1,
        revisions: Int,
        parents: Int,
        heads: Int = events,
    ) {
        val counts = requireDatabase().lifeEventMutationDao().tableCounts()
        assertEquals(captures, counts.captures)
        assertEquals(events, counts.events)
        assertEquals(revisions, counts.revisions)
        assertEquals(parents, counts.parents)
        assertEquals(heads, counts.heads)
    }

    private suspend fun assertParent(
        childRevisionId: UUID,
        parentRevisionId: UUID,
    ) {
        val parent = requireDatabase()
            .noteQueryDao()
            .findParents(childRevisionId.toString())
            .single()
        assertEquals(EVENT_ID.toString(), parent.eventId)
        assertEquals(parentRevisionId.toString(), parent.parentRevisionId)
        assertEquals("supersedes", parent.relation)
    }

    private fun tableColumns(tableName: String): Set<String> =
        requireDatabase().openHelper.readableDatabase
            .query("PRAGMA table_info($tableName)")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }

    private fun pragmaUserVersion(db: SupportSQLiteDatabase): Int =
        db.query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun applicationObjectCount(
        db: SupportSQLiteDatabase,
        namePattern: String,
    ): Int = db.query(
        """
        SELECT COUNT(*)
        FROM sqlite_schema
        WHERE type IN ('table', 'index', 'trigger')
          AND name GLOB ?
        """.trimIndent(),
        arrayOf(namePattern),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun currentApplicationTables(db: SupportSQLiteDatabase): List<String> =
        db.query(
            """
            SELECT name
            FROM sqlite_schema
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
              AND name NOT IN ('android_metadata', 'room_master_table')
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun assertEncryptedArtifacts(vararg additionalMarkers: String) {
        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.isFile)
        val markers = listOf(
            PRODUCTION_NOTE_SENTINEL,
            PRODUCTION_OPERATION_ID.toString(),
            PRODUCTION_REVISION_ID.toString(),
        ) + additionalMarkers
        val encodings = markers.flatMap { marker ->
            listOf(
                marker.toByteArray(StandardCharsets.UTF_8),
                marker.toByteArray(StandardCharsets.UTF_16LE),
                marker.toByteArray(StandardCharsets.UTF_16BE),
            )
        }
        DATABASE_ARTIFACT_SUFFIXES
            .map { suffix -> File(databaseFile.path + suffix) }
            .filter(File::isFile)
            .forEach { artifact ->
                val contents = artifact.readBytes()
                encodings.forEach { plaintext ->
                    assertFalse(
                        "Plaintext marker found in ${artifact.name}",
                        contents.containsSubsequence(plaintext),
                    )
                }
            }
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private fun persisted(outcome: NoteMutationOutcome): NoteMutationReceipt =
        (outcome as? NoteMutationOutcome.Persisted)?.receipt
            ?: throw AssertionError("Expected a persisted note mutation, got $outcome")

    private fun persistedWellbeing(
        outcome: WellbeingMutationOutcome,
    ): WellbeingMutationReceipt =
        (outcome as? WellbeingMutationOutcome.Persisted)?.receipt
            ?: throw AssertionError("Expected a persisted wellbeing mutation, got $outcome")

    private fun mutationIds(
        operation: Long,
        capture: Long,
        event: UUID,
        revision: Long,
    ) = MutationIds(
        operationId = UUID(0, operation),
        captureId = UUID(0, capture),
        eventId = event,
        revisionId = UUID(0, revision),
    )

    private fun effectiveTime(minuteOffset: Long) =
        PointTimeResolver.resolveInstant(
            instant = BASE_INSTANT.plusSeconds(minuteOffset * 60),
            timezoneId = TEST_ZONE,
        )

    private fun recordedAt(minuteOffset: Long): OffsetDateTime =
        BASE_INSTANT
            .plusSeconds(minuteOffset * 60)
            .atZone(TEST_ZONE)
            .toOffsetDateTime()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CONCURRENT_RETRY_COUNT = 24
        const val PRODUCTION_NOTE_SENTINEL =
            "LIFE_AGENT_LOCAL_ROOM_SENTINEL_9F24A8C1_ёж"
        const val COEXISTENCE_NOTE_SENTINEL =
            "LIFE_AGENT_NOTE_COEXISTENCE_SENTINEL_71C4_ёж"
        const val WELLBEING_COMMENT_SENTINEL =
            "LIFE_AGENT_WELLBEING_COMMENT_SENTINEL_71C4_ёж"
        const val WELLBEING_DIMENSION_SENTINEL = "Тестовое измерение 71C4"
        const val WELLBEING_OPTION_SENTINEL = "Тестовый вариант 71C4"
        const val WELLBEING_UPDATED_DIMENSION_SENTINEL =
            "Изменённое измерение 71C4"
        const val WELLBEING_UPDATED_OPTION_SENTINEL =
            "Изменённый вариант 71C4"
        const val DEFAULT_WELLBEING_DIMENSION_COUNT = 4
        const val EXPECTED_ROOM_SCHEMA_VERSION = 7
        const val ROOM_SCHEMA_IDENTITY_HASH = "a87a7ffa630566fd3067751d141b80f1"
        val DATABASE_ARTIFACT_SUFFIXES = listOf("", "-wal", "-shm")
        val BASE_INSTANT: Instant = Instant.parse("2026-01-15T03:00:00Z")
        val TEST_ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")
        val EVENT_ID: UUID = UUID(0, 3)
        val CONCURRENT_EVENT_ID: UUID = UUID(0, 23)
        val PRODUCTION_OPERATION_ID: UUID =
            UUID.fromString("9f24a8c1-91d5-4aeb-bc27-75f9e3390bd1")
        val PRODUCTION_CAPTURE_ID: UUID =
            UUID.fromString("0ea5bc77-b2d8-46ef-b247-5bf0dfa8553f")
        val PRODUCTION_EVENT_ID: UUID =
            UUID.fromString("2779908e-dc69-4237-8ecf-5c16d57284c9")
        val PRODUCTION_REVISION_ID: UUID =
            UUID.fromString("f1c935ee-b624-476f-b7a8-3404096b9e68")
        val COEXISTENCE_NOTE_OPERATION_ID: UUID =
            UUID.fromString("404e9553-9f42-45cc-a319-f07f4136f101")
        val COEXISTENCE_NOTE_CAPTURE_ID: UUID =
            UUID.fromString("404e9553-9f42-45cc-a319-f07f4136f102")
        val COEXISTENCE_NOTE_EVENT_ID: UUID =
            UUID.fromString("404e9553-9f42-45cc-a319-f07f4136f103")
        val COEXISTENCE_NOTE_REVISION_ID: UUID =
            UUID.fromString("404e9553-9f42-45cc-a319-f07f4136f104")
        val WELLBEING_DIMENSION_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0101")
        val WELLBEING_OPTION_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0102")
        val WELLBEING_CATALOG_VERSION_ONE_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0103")
        val WELLBEING_CATALOG_VERSION_TWO_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0104")
        val WELLBEING_OPERATION_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0201")
        val WELLBEING_CAPTURE_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0202")
        val WELLBEING_EVENT_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0203")
        val WELLBEING_REVISION_ID: UUID =
            UUID.fromString("71c4c7ca-8a06-4659-8704-5f89dc1e0204")
        val EXPECTED_LOCAL_TABLES = listOf(
            "local_capture",
            "local_catalog_head",
            "local_catalog_item",
            "local_catalog_version",
            "local_event_head",
            "local_event_revision",
            "local_identity_state",
            "local_installation",
            "local_life_event",
            "local_owner",
            "local_revision_parent",
        )
    }
}
