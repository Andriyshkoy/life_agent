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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
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

@RunWith(AndroidJUnit4::class)
class EncryptedNotesPersistenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets
    private lateinit var testId: String
    private lateinit var keyAlias: String
    private lateinit var databaseName: String
    private lateinit var envelopeRelativePath: String
    private lateinit var sqlCipherKey: SqlCipherKey
    private var database: LifeAgentDatabase? = null
    private lateinit var repository: RoomNotesRepository

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
        envelopeFile().let { envelope ->
            envelope.delete()
            File("${envelope.path}.bak").delete()
            envelope.parentFile?.delete()
        }
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            if (containsAlias(keyAlias)) {
                deleteEntry(keyAlias)
            }
        }
    }

    @Test
    fun lifecycleIsAppendOnlyIdempotentAndDurableAcrossReopen() = runBlocking {
        val create = CreateNoteCommand(
            ids = mutationIds(1, 2, EVENT_ID, 4),
            text = "Synthetic M1 note — ёж",
            effectiveTime = effectiveTime(0),
            recordedAt = recordedAt(1),
        )
        val created = persisted(repository.create(create))
        assertEquals(NoteMutationDisposition.COMMITTED, created.disposition)

        val createReplay = persisted(repository.create(create))
        assertEquals(NoteMutationDisposition.REPLAYED, createReplay.disposition)
        assertEquals(created.localSequence, createReplay.localSequence)
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)

        try {
            repository.create(create.copy(text = "Same operation, changed content"))
            throw AssertionError("A changed replay must fail closed")
        } catch (_: IdempotencyConflictException) {
            // Expected: one operation ID can only represent one immutable command.
        }
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)

        val correct = CorrectNoteCommand(
            ids = mutationIds(5, 6, EVENT_ID, 7),
            expectedCurrentRevisionId = create.ids.revisionId,
            text = "Synthetic corrected note",
            effectiveTime = effectiveTime(2),
            recordedAt = recordedAt(3),
            reason = "synthetic_fixture_correction",
        )
        val corrected = persisted(repository.correct(correct))
        assertEquals(NoteMutationDisposition.COMMITTED, corrected.disposition)
        assertEquals(2, corrected.note.revisionNo)
        assertEquals("Synthetic corrected note", corrected.note.text)

        val correctReplay = persisted(repository.correct(correct))
        assertEquals(NoteMutationDisposition.REPLAYED, correctReplay.disposition)
        assertEquals(corrected.localSequence, correctReplay.localSequence)
        assertCounts(captures = 2, revisions = 2, parents = 1, outbox = 2)
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
        assertEquals(NoteMutationDisposition.COMMITTED, retracted.disposition)
        assertEquals(NoteRecordStatus.RETRACTED, retracted.note.status)
        assertEquals("Synthetic corrected note", retracted.note.text)

        val retractReplay = persisted(repository.retract(retract))
        assertEquals(NoteMutationDisposition.REPLAYED, retractReplay.disposition)
        assertEquals(retracted.localSequence, retractReplay.localSequence)
        assertCounts(captures = 3, revisions = 3, parents = 2, outbox = 3)
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
        assertEquals("Synthetic corrected note", reopened.text)

        val pending = requireDatabase().outboxDao().pending(limit = 10)
        assertEquals(listOf(1L, 2L, 3L), pending.map { it.localSequence })
        assertEquals(
            listOf(
                create.ids.operationId.toString(),
                correct.ids.operationId.toString(),
                retract.ids.operationId.toString(),
            ),
            pending.map { it.operationId },
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

        val replayAfterReopen = persisted(repository.retract(retract))
        assertEquals(NoteMutationDisposition.REPLAYED, replayAfterReopen.disposition)
        assertEquals(retracted.localSequence, replayAfterReopen.localSequence)

        val redundantRetraction = repository.retract(
            RetractNoteCommand(
                ids = mutationIds(11, 12, EVENT_ID, 13),
                expectedCurrentRevisionId = retract.ids.revisionId,
                recordedAt = recordedAt(5),
            ),
        )
        assertTrue(redundantRetraction is NoteMutationOutcome.AlreadyRetracted)
        assertCounts(captures = 3, revisions = 3, parents = 2, outbox = 3)
    }

    @Test
    fun concurrentRetriesCommitExactlyOneOutboxOperation() = runBlocking {
        val command = CreateNoteCommand(
            ids = mutationIds(21, 22, CONCURRENT_EVENT_ID, 24),
            text = "Synthetic concurrent retry",
            effectiveTime = effectiveTime(10),
            recordedAt = recordedAt(11),
        )

        val receipts = coroutineScope {
            (0 until CONCURRENT_RETRY_COUNT)
                .map {
                    async(Dispatchers.Default) {
                        persisted(repository.create(command))
                    }
                }
                .awaitAll()
        }

        assertEquals(
            1,
            receipts.count {
                it.disposition == NoteMutationDisposition.COMMITTED
            },
        )
        assertEquals(
            CONCURRENT_RETRY_COUNT - 1,
            receipts.count {
                it.disposition == NoteMutationDisposition.REPLAYED
            },
        )
        assertEquals(1, receipts.map { it.localSequence }.distinct().size)
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)
    }

    @Test
    fun productionRoomNoteAndOutboxMarkersStayEncryptedAcrossReopen() = runBlocking {
        val ids = MutationIds(
            operationId = PRODUCTION_OUTBOX_SENTINEL_ID,
            captureId = PRODUCTION_CAPTURE_ID,
            eventId = PRODUCTION_SCAN_EVENT_ID,
            revisionId = PRODUCTION_REVISION_ID,
        )
        val command = CreateNoteCommand(
            ids = ids,
            text = PRODUCTION_NOTE_SENTINEL,
            effectiveTime = effectiveTime(15),
            recordedAt = recordedAt(16),
        )

        val committed = persisted(repository.create(command))
        assertEquals(NoteMutationDisposition.COMMITTED, committed.disposition)
        assertEquals(
            PRODUCTION_NOTE_SENTINEL,
            requireNotNull(repository.getByEventId(PRODUCTION_SCAN_EVENT_ID)).text,
        )
        assertEquals(
            PRODUCTION_OUTBOX_SENTINEL_ID.toString(),
            requireDatabase().outboxDao().pending(limit = 1).single().operationId,
        )
        assertAllProductionArtifactsExist()
        assertProductionMarkersAreEncrypted()

        closeStore()
        assertProductionMarkersAreEncrypted()
        openStore()

        assertEquals(
            PRODUCTION_NOTE_SENTINEL,
            requireNotNull(repository.getByEventId(PRODUCTION_SCAN_EVENT_ID)).text,
        )
        assertEquals(
            PRODUCTION_OUTBOX_SENTINEL_ID.toString(),
            requireDatabase().outboxDao().pending(limit = 1).single().operationId,
        )
        assertAllProductionArtifactsExist()
        assertProductionMarkersAreEncrypted()
    }

    @Test
    fun lateOutboxConstraintFailureRollsBackTheWholeMutation() = runBlocking {
        val command = CreateNoteCommand(
            ids = mutationIds(31, 32, ATOMIC_EVENT_ID, 34),
            text = "Synthetic forced rollback",
            effectiveTime = effectiveTime(20),
            recordedAt = recordedAt(21),
        )
        val sqlite = requireDatabase().openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER reject_synthetic_outbox
            BEFORE INSERT ON sync_outbox
            WHEN NEW.operation_id = '${command.ids.operationId}'
            BEGIN
                SELECT RAISE(ABORT, 'forced late outbox failure');
            END
            """.trimIndent(),
        )

        val failure = try {
            repository.create(command)
            null
        } catch (error: Exception) {
            error
        }
        assertTrue("The late outbox write must fail", failure != null)

        val counts = requireDatabase().noteMutationDao().tableCounts()
        assertEquals(0, counts.captures)
        assertEquals(0, counts.events)
        assertEquals(0, counts.revisions)
        assertEquals(0, counts.parents)
        assertEquals(0, counts.heads)
        assertEquals(0, counts.outboxOperations)
        assertNull(requireDatabase().identityDao().findIdentity())
        assertEquals(0, queryInt(sqlite, "SELECT COUNT(*) FROM local_installation"))
        assertEquals(0, queryInt(sqlite, "SELECT COUNT(*) FROM local_owner"))
        assertNull(repository.getByEventId(ATOMIC_EVENT_ID))

        sqlite.execSQL("DROP TRIGGER reject_synthetic_outbox")
        val retry = persisted(repository.create(command))
        assertEquals(NoteMutationDisposition.COMMITTED, retry.disposition)
        assertCounts(captures = 1, revisions = 1, parents = 0, outbox = 1)
    }

    @Test
    fun encryptedVersionOneDatabaseMigratesWithHistoryAndOutboxIntact() = runBlocking {
        closeStore()
        context.deleteDatabase(databaseName)
        sqlCipherKey = keyManager().openSqlCipherKey()

        val legacyHelper = createVersionOneOpenHelper()
        legacyHelper.writableDatabase
        legacyHelper.close()

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.isFile)
        assertFalse(
            databaseFile.readBytes()
                .take(SQLITE_PLAINTEXT_HEADER.size)
                .toByteArray()
                .contentEquals(SQLITE_PLAINTEXT_HEADER),
        )

        sqlCipherKey.close()
        sqlCipherKey = keyManager().openSqlCipherKey()
        openRoomWithCurrentKey()

        val migrated = requireDatabase().openHelper.writableDatabase
        assertEquals(
            LifeAgentDatabase.VERSION,
            queryInt(migrated, "PRAGMA user_version"),
        )
        assertEquals(2, queryInt(migrated, "SELECT COUNT(*) FROM local_capture"))
        assertEquals(1, queryInt(migrated, "SELECT COUNT(*) FROM local_life_event"))
        assertEquals(2, queryInt(migrated, "SELECT COUNT(*) FROM local_event_revision"))
        assertEquals(1, queryInt(migrated, "SELECT COUNT(*) FROM local_revision_parent"))
        assertEquals(1, queryInt(migrated, "SELECT COUNT(*) FROM local_event_head"))
        assertEquals(2, queryInt(migrated, "SELECT COUNT(*) FROM sync_outbox"))

        val current = requireNotNull(
            requireDatabase()
                .noteMutationDao()
                .findCurrentRevision(MIGRATION_EVENT_ID.toString()),
        )
        assertEquals(MIGRATION_REVISION_TWO.toString(), current.headRevisionId)
        assertEquals(2, current.revision.revisionNo)

        val parent = requireDatabase()
            .noteQueryDao()
            .findParents(MIGRATION_REVISION_TWO.toString())
            .single()
        assertEquals(MIGRATION_REVISION_ONE.toString(), parent.parentRevisionId)
        assertEquals("supersedes", parent.relation)

        val pending = requireDatabase().outboxDao().pending(limit = 10)
        assertEquals(listOf(1L, 2L), pending.map { it.localSequence })
        assertTrue(
            "Legacy rows must remain explicitly un-fingerprinted after migration",
            pending.all { it.commandFingerprintSha256.isEmpty() },
        )

        migrated.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Migrated fixture must satisfy every foreign key", cursor.moveToFirst())
        }

        val createReplay = persisted(
            repository.create(
                CreateNoteCommand(
                    ids = MutationIds(
                        operationId = MIGRATION_OPERATION_ONE,
                        captureId = MIGRATION_CAPTURE_ONE,
                        eventId = MIGRATION_EVENT_ID,
                        revisionId = MIGRATION_REVISION_ONE,
                    ),
                    text = "Synthetic legacy note",
                    effectiveTime = effectiveTime(0),
                    recordedAt = recordedAt(0),
                ),
            ),
        )
        assertEquals(NoteMutationDisposition.REPLAYED, createReplay.disposition)

        val correctionReplay = persisted(
            repository.correct(
                CorrectNoteCommand(
                    ids = MutationIds(
                        operationId = MIGRATION_OPERATION_TWO,
                        captureId = MIGRATION_CAPTURE_TWO,
                        eventId = MIGRATION_EVENT_ID,
                        revisionId = MIGRATION_REVISION_TWO,
                    ),
                    expectedCurrentRevisionId = MIGRATION_REVISION_ONE,
                    text = "Synthetic legacy corrected note",
                    effectiveTime = effectiveTime(1),
                    recordedAt = recordedAt(1),
                ),
            ),
        )
        assertEquals(NoteMutationDisposition.REPLAYED, correctionReplay.disposition)

        val upgradedPending = requireDatabase().outboxDao().pending(limit = 10)
        assertTrue(
            "An exact replay must lazily install real command fingerprints",
            upgradedPending.all { it.commandFingerprintSha256.isNotBlank() },
        )
        try {
            repository.create(
                CreateNoteCommand(
                    ids = MutationIds(
                        operationId = MIGRATION_OPERATION_ONE,
                        captureId = MIGRATION_CAPTURE_ONE,
                        eventId = MIGRATION_EVENT_ID,
                        revisionId = MIGRATION_REVISION_ONE,
                    ),
                    text = "Changed legacy replay",
                    effectiveTime = effectiveTime(0),
                    recordedAt = recordedAt(0),
                ),
            )
            throw AssertionError("A changed legacy replay must fail closed")
        } catch (_: IdempotencyConflictException) {
            // Expected after the real command fingerprint has been reconstructed.
        }
    }

    private fun openStore() {
        sqlCipherKey = keyManager().openSqlCipherKey()
        openRoomWithCurrentKey()
    }

    private fun openRoomWithCurrentKey() {
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = SqlCipherOpenHelperFactoryProvider.create(sqlCipherKey),
            databaseName = databaseName,
        )
        repository = RoomNotesRepository(
            database = requireDatabase(),
            collectorVersion = "m1-instrumented-test",
        )
    }

    private fun createVersionOneOpenHelper(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createVersionOneSchema(db)
                seedVersionOneFixture(db)
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                throw AssertionError("Unexpected legacy helper upgrade $oldVersion->$newVersion")
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(databaseName)
            .callback(callback)
            .build()
        return SqlCipherOpenHelperFactoryProvider
            .create(sqlCipherKey)
            .create(configuration)
    }

    private fun createVersionOneSchema(db: SupportSQLiteDatabase) {
        val schemaDocument = testAssets
            .open(VERSION_ONE_SCHEMA_ASSET)
            .bufferedReader()
            .use { it.readText() }
        val entities = JSONObject(schemaDocument)
            .getJSONObject("database")
            .getJSONArray("entities")

        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            db.execSQL(expandTableName(entity.getString("createSql"), tableName))

            val indices = entity.getJSONArray("indices")
            for (indexIndex in 0 until indices.length()) {
                db.execSQL(
                    expandTableName(
                        indices.getJSONObject(indexIndex).getString("createSql"),
                        tableName,
                    ),
                )
            }
        }
    }

    private fun seedVersionOneFixture(db: SupportSQLiteDatabase) {
        val createdAt = "2026-01-15T03:00:00Z"
        db.execSQL(
            """
            INSERT INTO local_installation(
                installation_id, created_at_utc, server_device_id
            ) VALUES(?, ?, NULL)
            """.trimIndent(),
            arrayOf(MIGRATION_INSTALLATION_ID.toString(), createdAt),
        )
        db.execSQL(
            """
            INSERT INTO local_owner(
                local_owner_id, installation_id, created_at_utc, server_person_id
            ) VALUES(?, ?, ?, NULL)
            """.trimIndent(),
            arrayOf(
                MIGRATION_OWNER_ID.toString(),
                MIGRATION_INSTALLATION_ID.toString(),
                createdAt,
            ),
        )

        insertLegacyCapture(
            db = db,
            captureId = MIGRATION_CAPTURE_ONE,
            operationId = MIGRATION_OPERATION_ONE,
            minuteOffset = 0,
            text = "Synthetic legacy note",
        )
        insertLegacyCapture(
            db = db,
            captureId = MIGRATION_CAPTURE_TWO,
            operationId = MIGRATION_OPERATION_TWO,
            minuteOffset = 1,
            text = "Synthetic legacy corrected note",
        )
        db.execSQL(
            """
            INSERT INTO local_life_event(
                event_id, local_owner_id, kind, created_at_utc
            ) VALUES(?, ?, 'note', ?)
            """.trimIndent(),
            arrayOf(
                MIGRATION_EVENT_ID.toString(),
                MIGRATION_OWNER_ID.toString(),
                createdAt,
            ),
        )
        insertLegacyRevision(
            db = db,
            revisionId = MIGRATION_REVISION_ONE,
            captureId = MIGRATION_CAPTURE_ONE,
            operationId = MIGRATION_OPERATION_ONE,
            revisionNo = 1,
            minuteOffset = 0,
            text = "Synthetic legacy note",
        )
        insertLegacyRevision(
            db = db,
            revisionId = MIGRATION_REVISION_TWO,
            captureId = MIGRATION_CAPTURE_TWO,
            operationId = MIGRATION_OPERATION_TWO,
            revisionNo = 2,
            minuteOffset = 1,
            text = "Synthetic legacy corrected note",
        )
        db.execSQL(
            """
            INSERT INTO local_revision_parent(
                event_id, child_revision_id, parent_revision_id, relation
            ) VALUES(?, ?, ?, 'supersedes')
            """.trimIndent(),
            arrayOf(
                MIGRATION_EVENT_ID.toString(),
                MIGRATION_REVISION_TWO.toString(),
                MIGRATION_REVISION_ONE.toString(),
            ),
        )
        insertLegacyOutbox(
            db = db,
            localSequence = 1,
            operationId = MIGRATION_OPERATION_ONE,
            captureId = MIGRATION_CAPTURE_ONE,
            revisionId = MIGRATION_REVISION_ONE,
            baseRevisionId = null,
            minuteOffset = 0,
            operationHash = LEGACY_OPERATION_HASH_ONE,
        )
        insertLegacyOutbox(
            db = db,
            localSequence = 2,
            operationId = MIGRATION_OPERATION_TWO,
            captureId = MIGRATION_CAPTURE_TWO,
            revisionId = MIGRATION_REVISION_TWO,
            baseRevisionId = MIGRATION_REVISION_ONE,
            minuteOffset = 1,
            operationHash = LEGACY_OPERATION_HASH_TWO,
        )
        db.execSQL(
            """
            INSERT INTO local_event_head(
                event_id, current_revision_id, server_current_revision_id, updated_at_utc
            ) VALUES(?, ?, NULL, ?)
            """.trimIndent(),
            arrayOf(
                MIGRATION_EVENT_ID.toString(),
                MIGRATION_REVISION_TWO.toString(),
                "2026-01-15T03:01:00Z",
            ),
        )
    }

    private fun insertLegacyCapture(
        db: SupportSQLiteDatabase,
        captureId: UUID,
        operationId: UUID,
        minuteOffset: Long,
        text: String,
    ) {
        val recorded = recordedAt(minuteOffset)
        val content = """{"text":"$text"}""".toByteArray(StandardCharsets.UTF_8)
        db.execSQL(
            """
            INSERT INTO local_capture(
                capture_id, operation_id, installation_id, local_owner_id,
                schema_version, persistence_state, source_channel,
                recorded_at_rfc3339, recorded_at_epoch_ms, timezone_id,
                utc_offset_minutes, origin_user_entered, collector_name,
                collector_version, content_jcs, content_sha256, byte_size
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                captureId.toString(),
                operationId.toString(),
                MIGRATION_INSTALLATION_ID.toString(),
                MIGRATION_OWNER_ID.toString(),
                "life_capture.v1",
                "local_pending",
                "android_manual",
                recorded.toString(),
                recorded.toInstant().toEpochMilli(),
                TEST_ZONE.id,
                recorded.offset.totalSeconds / 60,
                1,
                "life-agent-android",
                "legacy-migration-fixture",
                content,
                LEGACY_CONTENT_HASH,
                content.size,
            ),
        )
    }

    private fun insertLegacyRevision(
        db: SupportSQLiteDatabase,
        revisionId: UUID,
        captureId: UUID,
        operationId: UUID,
        revisionNo: Int,
        minuteOffset: Long,
        text: String,
    ) {
        val recorded = recordedAt(minuteOffset)
        val effective = effectiveTime(minuteOffset)
        db.execSQL(
            """
            INSERT INTO local_event_revision(
                revision_id, event_id, capture_id, operation_id, revision_no,
                schema_version, assertion_status, record_status,
                verification_status, source_channel, recorded_at_rfc3339,
                origin_user_entered, collector_name, collector_version,
                effective_start_utc, effective_start_epoch_ms,
                original_local_start, timezone_id, start_offset_seconds,
                temporal_precision, local_date, payload_jcs, evidence_jcs,
                quality_flags_jcs, created_at_rfc3339, content_sha256, actor
            ) VALUES(
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            arrayOf(
                revisionId.toString(),
                MIGRATION_EVENT_ID.toString(),
                captureId.toString(),
                operationId.toString(),
                revisionNo,
                "life_event.note.v1",
                "observed",
                "active",
                "user_confirmed",
                "android_manual",
                recorded.toString(),
                1,
                "life-agent-android",
                "legacy-migration-fixture",
                effective.effectiveAt.toString(),
                effective.effectiveAt.toEpochMilli(),
                effective.originalLocal.toString(),
                effective.timezoneId.id,
                effective.offset.totalSeconds,
                effective.precision.storageValue,
                effective.localDate.toString(),
                """{"text":"$text"}""".toByteArray(StandardCharsets.UTF_8),
                "{}".toByteArray(StandardCharsets.UTF_8),
                "[]".toByteArray(StandardCharsets.UTF_8),
                recorded.toString(),
                LEGACY_CONTENT_HASH,
                "user",
            ),
        )
    }

    private fun insertLegacyOutbox(
        db: SupportSQLiteDatabase,
        localSequence: Long,
        operationId: UUID,
        captureId: UUID,
        revisionId: UUID,
        baseRevisionId: UUID?,
        minuteOffset: Long,
        operationHash: String,
    ) {
        val createdAt = recordedAt(minuteOffset)
        db.execSQL(
            """
            INSERT INTO sync_outbox(
                local_sequence, operation_id, capture_id, installation_id,
                local_owner_id, operation_kind, event_id, revision_id,
                base_revision_id, schema_version, operation_jcs,
                operation_content_sha256, created_at_utc, created_at_epoch_ms,
                state, attempt_count
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                localSequence,
                operationId.toString(),
                captureId.toString(),
                MIGRATION_INSTALLATION_ID.toString(),
                MIGRATION_OWNER_ID.toString(),
                "append_event_revision",
                MIGRATION_EVENT_ID.toString(),
                revisionId.toString(),
                baseRevisionId?.toString(),
                "life_event.note.v1",
                """{"operation":"synthetic-$localSequence"}"""
                    .toByteArray(StandardCharsets.UTF_8),
                operationHash,
                createdAt.toInstant().toString(),
                createdAt.toInstant().toEpochMilli(),
                "pending",
                0,
            ),
        )
    }

    private fun expandTableName(sql: String, tableName: String): String =
        sql.replace("\${TABLE_NAME}", tableName)

    private fun queryInt(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Query returned no rows: $sql" }
            cursor.getInt(0)
        }

    private fun assertAllProductionArtifactsExist() {
        productionDatabaseArtifacts(includeMissing = true).forEach { artifact ->
            assertTrue("Expected encrypted artifact ${artifact.name}", artifact.isFile)
        }
    }

    private fun assertProductionMarkersAreEncrypted() {
        val markerEncodings = listOf(
            PRODUCTION_NOTE_SENTINEL,
            PRODUCTION_OUTBOX_SENTINEL_ID.toString(),
        ).flatMap { marker ->
            listOf(
                marker.toByteArray(StandardCharsets.UTF_8),
                marker.toByteArray(StandardCharsets.UTF_16LE),
                marker.toByteArray(StandardCharsets.UTF_16BE),
            )
        }
        productionDatabaseArtifacts(includeMissing = false).forEach { artifact ->
            val contents = artifact.readBytes()
            markerEncodings.forEach { plaintext ->
                assertFalse(
                    "Production plaintext marker found in ${artifact.name}",
                    contents.containsSubsequence(plaintext),
                )
            }
        }
    }

    private fun productionDatabaseArtifacts(includeMissing: Boolean): List<File> {
        val databaseFile = context.getDatabasePath(databaseName)
        val artifacts = DATABASE_ARTIFACT_SUFFIXES.map { suffix ->
            File(databaseFile.path + suffix)
        }
        return if (includeMissing) artifacts else artifacts.filter(File::isFile)
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private fun closeStore() {
        database?.close()
        database = null
        if (::sqlCipherKey.isInitialized) {
            sqlCipherKey.close()
        }
    }

    private fun keyManager() = DatabaseKeyManager(
        context = context,
        keyAlias = keyAlias,
        databaseName = databaseName,
        envelopeRelativePath = envelopeRelativePath,
    )

    private fun envelopeFile() = File(context.noBackupFilesDir, envelopeRelativePath)

    private fun requireDatabase(): LifeAgentDatabase =
        requireNotNull(database) { "Test database is closed" }

    private suspend fun assertCounts(
        captures: Int,
        revisions: Int,
        parents: Int,
        outbox: Int,
    ) {
        val counts = requireDatabase().noteMutationDao().tableCounts()
        assertEquals(captures, counts.captures)
        assertEquals(1, counts.events)
        assertEquals(revisions, counts.revisions)
        assertEquals(parents, counts.parents)
        assertEquals(1, counts.heads)
        assertEquals(outbox, counts.outboxOperations)
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

    private fun persisted(outcome: NoteMutationOutcome): NoteMutationReceipt =
        (outcome as? NoteMutationOutcome.Persisted)?.receipt
            ?: throw AssertionError("Expected a persisted note mutation, got $outcome")

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
        const val VERSION_ONE_SCHEMA_ASSET =
            "ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase/1.json"
        const val PRODUCTION_NOTE_SENTINEL =
            "LIFE_AGENT_PRODUCTION_ROOM_OUTBOX_SENTINEL_9F24A8C1_ёж"
        val SQLITE_PLAINTEXT_HEADER: ByteArray =
            "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
        val DATABASE_ARTIFACT_SUFFIXES: List<String> = listOf("", "-wal", "-shm")
        val LEGACY_CONTENT_HASH: String = "a".repeat(64)
        val LEGACY_OPERATION_HASH_ONE: String = "b".repeat(64)
        val LEGACY_OPERATION_HASH_TWO: String = "c".repeat(64)
        val BASE_INSTANT: Instant = Instant.parse("2026-01-15T03:00:00Z")
        val TEST_ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")
        val EVENT_ID: UUID = UUID(0, 3)
        val CONCURRENT_EVENT_ID: UUID = UUID(0, 23)
        val ATOMIC_EVENT_ID: UUID = UUID(0, 33)
        val PRODUCTION_OUTBOX_SENTINEL_ID: UUID =
            UUID.fromString("9f24a8c1-91d5-4aeb-bc27-75f9e3390bd1")
        val PRODUCTION_CAPTURE_ID: UUID =
            UUID.fromString("0ea5bc77-b2d8-46ef-b247-5bf0dfa8553f")
        val PRODUCTION_SCAN_EVENT_ID: UUID =
            UUID.fromString("2779908e-dc69-4237-8ecf-5c16d57284c9")
        val PRODUCTION_REVISION_ID: UUID =
            UUID.fromString("f1c935ee-b624-476f-b7a8-3404096b9e68")
        val MIGRATION_INSTALLATION_ID: UUID = UUID(0, 101)
        val MIGRATION_OWNER_ID: UUID = UUID(0, 102)
        val MIGRATION_OPERATION_ONE: UUID = UUID(0, 103)
        val MIGRATION_CAPTURE_ONE: UUID = UUID(0, 104)
        val MIGRATION_EVENT_ID: UUID = UUID(0, 105)
        val MIGRATION_REVISION_ONE: UUID = UUID(0, 106)
        val MIGRATION_OPERATION_TWO: UUID = UUID(0, 107)
        val MIGRATION_CAPTURE_TWO: UUID = UUID(0, 108)
        val MIGRATION_REVISION_TWO: UUID = UUID(0, 109)
    }
}
