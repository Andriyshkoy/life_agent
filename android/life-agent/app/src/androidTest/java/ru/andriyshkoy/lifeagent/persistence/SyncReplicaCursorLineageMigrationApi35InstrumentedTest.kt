package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncBootstrapSessionEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.SyncReplicaCursorEntity

@RunWith(AndroidJUnit4::class)
class SyncReplicaCursorLineageMigrationApi35InstrumentedTest {
    private lateinit var context: Context
    private var database: LifeAgentDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun versionThreeCursorFailsClosedWhileNotesAndScopedLineagesSurvive() = runBlocking {
        createAndSeedVersionThreeDatabase()
        database = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
            databaseName = DATABASE_NAME,
        )
        val migrated = requireNotNull(database)
        val sqlite = migrated.openHelper.writableDatabase
        val replicaDao = migrated.syncReplicaDao()

        assertEquals(4, queryInt(sqlite, "PRAGMA user_version"))
        val stream = requireNotNull(replicaDao.findStreamState())
        assertEquals("bootstrap_required", stream.phase)
        assertTrue(stream.bootstrapRequired)
        assertEquals(LEGACY_CURSOR, stream.appliedCursor)
        assertNull(stream.replicaLineageId)
        assertEquals(
            1,
            queryInt(
                sqlite,
                "SELECT bootstrap_required FROM sync_auth_state WHERE singleton_id = 1",
            ),
        )
        assertArrayEquals(
            NOTE_CAPTURE_BYTES,
            queryBlob(
                sqlite,
                "SELECT content_jcs FROM local_capture WHERE capture_id = '$CAPTURE_ID'",
            ),
        )
        assertEquals(
            "note",
            queryString(
                sqlite,
                "SELECT kind FROM local_life_event WHERE event_id = '$EVENT_ID'",
            ),
        )
        assertEquals(0, queryInt(sqlite, "SELECT COUNT(*) FROM sync_replica_cursor"))

        replicaDao.insertBootstrapSession(
            bootstrapSession(
                bootstrapId = LINEAGE_B,
                state = "superseded",
                activeSlot = null,
            ),
        )
        replicaDao.insertReplicaCursor(
            SyncReplicaCursorEntity(
                lineageId = LINEAGE_A,
                cursorValue = SHARED_CURSOR,
                role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
            ),
        )
        replicaDao.insertReplicaCursor(
            SyncReplicaCursorEntity(
                lineageId = LINEAGE_B,
                cursorValue = SHARED_CURSOR,
                role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
            ),
        )

        assertEquals(1, replicaDao.countReplicaCursor(LINEAGE_A, SHARED_CURSOR))
        assertEquals(1, replicaDao.countReplicaCursor(LINEAGE_B, SHARED_CURSOR))
        assertEquals(
            SyncReplicaCursorEntity.ROLE_INCREMENTAL,
            replicaDao.findReplicaCursor(LINEAGE_A, SHARED_CURSOR)?.role,
        )
        assertTrue(
            runCatching {
                replicaDao.insertReplicaCursor(
                    SyncReplicaCursorEntity(
                        lineageId = LINEAGE_A,
                        cursorValue = SHARED_CURSOR,
                        role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                replicaDao.insertReplicaCursor(
                    SyncReplicaCursorEntity(
                        lineageId = UNKNOWN_LINEAGE,
                        cursorValue = "orphan-cursor",
                        role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
                    ),
                )
            }.isFailure,
        )

        assertEquals(
            1,
            replicaDao.promoteBootstrapCursor(
                credentialEpochId = EPOCH_ID,
                deviceId = DEVICE_ID,
                replicaLineageId = LINEAGE_A,
                incrementalCursor = SHARED_CURSOR,
                lastServerSequence = 7,
                updatedAtUtc = UPDATED_AT,
            ),
        )
        val promoted = requireNotNull(replicaDao.findStreamState())
        assertEquals(LINEAGE_A, promoted.replicaLineageId)
        assertEquals(SHARED_CURSOR, promoted.appliedCursor)
        assertFalse(promoted.bootstrapRequired)

        replicaDao.insertReplicaCursor(
            SyncReplicaCursorEntity(
                lineageId = LINEAGE_A,
                cursorValue = NEXT_CURSOR,
                role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
            ),
        )
        replicaDao.insertReplicaCursor(
            SyncReplicaCursorEntity(
                lineageId = LINEAGE_B,
                cursorValue = NEXT_CURSOR,
                role = SyncReplicaCursorEntity.ROLE_INCREMENTAL,
            ),
        )
        assertEquals(
            0,
            replicaDao.compareAndAdvanceCursor(
                credentialEpochId = EPOCH_ID,
                deviceId = DEVICE_ID,
                replicaLineageId = LINEAGE_B,
                expectedCursor = SHARED_CURSOR,
                expectedServerSequence = 7,
                nextCursor = NEXT_CURSOR,
                lastServerSequence = 8,
                nextPhase = "incremental",
                updatedAtUtc = UPDATED_AT,
            ),
        )
        assertEquals(
            1,
            replicaDao.compareAndAdvanceCursor(
                credentialEpochId = EPOCH_ID,
                deviceId = DEVICE_ID,
                replicaLineageId = LINEAGE_A,
                expectedCursor = SHARED_CURSOR,
                expectedServerSequence = 7,
                nextCursor = NEXT_CURSOR,
                lastServerSequence = 8,
                nextPhase = "incremental",
                updatedAtUtc = UPDATED_AT,
            ),
        )
        assertEquals(LINEAGE_A, replicaDao.findStreamState()?.replicaLineageId)
        sqlite.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

    private fun createAndSeedVersionThreeDatabase() {
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchemaFromAsset(db, VERSION_THREE_SCHEMA_ASSET)
                seedLocalNote(db)
                db.execSQL(
                    """
                    INSERT INTO sync_auth_state(
                        singleton_id, credential_epoch_id, installation_id,
                        local_owner_id, device_id, person_id, token_type,
                        refresh_token_ciphertext, refresh_token_nonce,
                        refresh_token_key_alias, refresh_token_key_generation,
                        refresh_token_aad_version, access_expires_at_utc,
                        access_expires_at_epoch_ms, refresh_expires_at_utc,
                        refresh_expires_at_epoch_ms, family_expires_at_utc,
                        family_expires_at_epoch_ms, generation, state,
                        bootstrap_required, installed_at_utc, updated_at_utc,
                        failure_code
                    ) VALUES(
                        1, ?, ?, ?, ?, ?, 'Bearer', X'01', X'02',
                        'migration-test-key', 1, 1,
                        '2026-08-03T01:00:00Z', 1785728400000,
                        '2026-08-03T02:00:00Z', 1785732000000,
                        '2026-08-03T03:00:00Z', 1785735600000,
                        1, 'active', 0, ?, ?, NULL
                    )
                    """.trimIndent(),
                    arrayOf(
                        EPOCH_ID,
                        INSTALLATION_ID,
                        OWNER_ID,
                        DEVICE_ID,
                        PERSON_ID,
                        CREATED_AT,
                        CREATED_AT,
                    ),
                )
                db.execSQL(
                    """
                    INSERT INTO sync_stream_state(
                        singleton_id, credential_epoch_id, device_id, phase,
                        bootstrap_required, applied_cursor,
                        last_applied_server_sequence, high_watermark_hint,
                        integrity_error_code, updated_at_utc
                    ) VALUES(1, ?, ?, 'incremental', 0, ?, 7, NULL, NULL, ?)
                    """.trimIndent(),
                    arrayOf(EPOCH_ID, DEVICE_ID, LEGACY_CURSOR, CREATED_AT),
                )
                db.execSQL(
                    """
                    INSERT INTO sync_bootstrap_session(
                        bootstrap_id, credential_epoch_id, device_id, state,
                        active_slot, snapshot_id, next_page_cursor,
                        candidate_incremental_cursor, next_page_index,
                        last_staged_server_sequence, staged_page_count,
                        staged_body_bytes, created_at_utc, updated_at_utc
                    ) VALUES(?, ?, ?, 'staging', 1, NULL, NULL, NULL, 0,
                             NULL, 0, 0, ?, ?)
                    """.trimIndent(),
                    arrayOf(LINEAGE_A, EPOCH_ID, DEVICE_ID, CREATED_AT, CREATED_AT),
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                throw AssertionError("Unexpected legacy helper upgrade $oldVersion->$newVersion")
            }
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(DATABASE_NAME)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun createSchemaFromAsset(
        db: SupportSQLiteDatabase,
        schemaAsset: String,
    ) {
        val document = context.assets
            .open(schemaAsset)
            .bufferedReader()
            .use { it.readText() }
        val entities = JSONObject(document)
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

    private fun seedLocalNote(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO local_installation(
                installation_id, created_at_utc, server_device_id
            ) VALUES(?, ?, ?)
            """.trimIndent(),
            arrayOf(INSTALLATION_ID, CREATED_AT, DEVICE_ID),
        )
        db.execSQL(
            """
            INSERT INTO local_owner(
                local_owner_id, installation_id, created_at_utc, server_person_id
            ) VALUES(?, ?, ?, NULL)
            """.trimIndent(),
            arrayOf(OWNER_ID, INSTALLATION_ID, CREATED_AT),
        )
        db.execSQL(
            """
            INSERT INTO local_capture(
                capture_id, operation_id, installation_id, local_owner_id,
                schema_version, persistence_state, source_channel,
                recorded_at_rfc3339, recorded_at_epoch_ms, timezone_id,
                utc_offset_minutes, origin_provider, origin_app, origin_device,
                origin_source_record_id, origin_source_record_version,
                origin_user_entered, collector_name, collector_version,
                content_jcs, content_sha256, byte_size
            ) VALUES(
                ?, ?, ?, ?, '4.0.0', 'committed', 'manual', ?, 1,
                'Asia/Novosibirsk', 420, NULL, NULL, NULL, NULL, NULL, 1,
                'migration_test', '1', ?, ?, ?
            )
            """.trimIndent(),
            arrayOf(
                CAPTURE_ID,
                OPERATION_ID,
                INSTALLATION_ID,
                OWNER_ID,
                CREATED_AT,
                NOTE_CAPTURE_BYTES,
                "a".repeat(64),
                NOTE_CAPTURE_BYTES.size,
            ),
        )
        db.execSQL(
            """
            INSERT INTO local_life_event(
                event_id, local_owner_id, kind, created_at_utc
            ) VALUES(?, ?, 'note', ?)
            """.trimIndent(),
            arrayOf(EVENT_ID, OWNER_ID, CREATED_AT),
        )
    }

    private fun bootstrapSession(
        bootstrapId: String,
        state: String,
        activeSlot: Int?,
    ) = SyncBootstrapSessionEntity(
        bootstrapId = bootstrapId,
        credentialEpochId = EPOCH_ID,
        deviceId = DEVICE_ID,
        state = state,
        activeSlot = activeSlot,
        snapshotId = null,
        nextPageCursor = null,
        candidateIncrementalCursor = null,
        nextPageIndex = 0,
        lastStagedServerSequence = null,
        createdAtUtc = CREATED_AT,
        updatedAtUtc = CREATED_AT,
    )

    private fun expandTableName(sql: String, tableName: String): String =
        sql.replace("\${TABLE_NAME}", tableName)

    private fun queryInt(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun queryBlob(db: SupportSQLiteDatabase, sql: String): ByteArray =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getBlob(0)
        }

    private companion object {
        const val DATABASE_NAME = "cursor-lineage-migration-api35.db"
        const val VERSION_THREE_SCHEMA_ASSET =
            "ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase/3.json"
        const val CREATED_AT = "2026-08-03T00:00:00Z"
        const val UPDATED_AT = "2026-08-03T00:01:00Z"
        const val INSTALLATION_ID = "00000000-0000-0000-0000-000000000101"
        const val OWNER_ID = "00000000-0000-0000-0000-000000000102"
        const val CAPTURE_ID = "00000000-0000-0000-0000-000000000103"
        const val OPERATION_ID = "00000000-0000-0000-0000-000000000104"
        const val EVENT_ID = "00000000-0000-0000-0000-000000000105"
        const val EPOCH_ID = "00000000-0000-0000-0000-000000000201"
        const val DEVICE_ID = "00000000-0000-0000-0000-000000000202"
        const val PERSON_ID = "00000000-0000-0000-0000-000000000203"
        const val LINEAGE_A = "00000000-0000-0000-0000-000000000301"
        const val LINEAGE_B = "00000000-0000-0000-0000-000000000302"
        const val UNKNOWN_LINEAGE = "00000000-0000-0000-0000-000000000399"
        const val LEGACY_CURSOR = "legacy-unscoped-cursor"
        const val SHARED_CURSOR = "server-may-reuse-this-across-lineages"
        const val NEXT_CURSOR = "next-scoped-cursor"
        val NOTE_CAPTURE_BYTES =
            "{\"note\":\"migration-preservation-sentinel\"}".toByteArray()
    }
}
