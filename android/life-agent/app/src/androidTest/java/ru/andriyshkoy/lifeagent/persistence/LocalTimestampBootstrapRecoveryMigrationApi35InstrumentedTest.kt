package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory

@RunWith(AndroidJUnit4::class)
class LocalTimestampBootstrapRecoveryMigrationApi35InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets

    @Test
    fun highPrecisionFirstBootstrapHaltIsRequeuedForVerifiedReplay() {
        val name = "local-time-bootstrap-known-recovery.db"
        createVersionFiveDatabase(name)

        val migrated = LifeAgentDatabaseFactory.create(
            context = context,
            openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
            databaseName = name,
        )
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals(LifeAgentDatabase.VERSION.toLong(), queryLong(db, "PRAGMA user_version"))
            assertEquals(
                "bootstrap_required",
                queryString(db, "SELECT phase FROM sync_stream_state WHERE singleton_id = 1"),
            )
            assertNull(
                queryNullableString(
                    db,
                    "SELECT integrity_error_code FROM sync_stream_state WHERE singleton_id = 1",
                ),
            )
            assertEquals(
                "waiting_refresh",
                queryString(db, "SELECT state FROM sync_http_request"),
            )
            assertEquals(1L, queryLong(db, "SELECT attempt_count FROM sync_http_request"))
            assertEquals(3L, queryLong(db, "SELECT access_generation_used FROM sync_http_request"))
            assertEquals(1L, queryLong(db, "SELECT refresh_attempted FROM sync_http_request"))
            assertNull(queryNullableLong(db, "SELECT next_attempt_at_epoch_ms FROM sync_http_request"))
            assertNull(queryNullableLong(db, "SELECT lease_expires_at_epoch_ms FROM sync_http_request"))
            assertNull(queryNullableString(db, "SELECT active_attempt_id FROM sync_http_request"))
            assertNull(queryNullableString(db, "SELECT terminal_at_utc FROM sync_http_request"))
            assertEquals(
                "credential_recovery_pending",
                queryNullableString(db, "SELECT terminal_error_code FROM sync_http_request"),
            )
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun nearMissesRemainHalted() {
        NearMiss.entries.forEach { nearMiss ->
            val name = "local-time-bootstrap-near-${nearMiss.name.lowercase()}.db"
            createVersionFiveDatabase(name, nearMiss)
            val migrated = LifeAgentDatabaseFactory.create(
                context = context,
                openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
                databaseName = name,
            )
            try {
                val db = migrated.openHelper.writableDatabase
                assertEquals(
                    "integrity_halted",
                    queryString(db, "SELECT phase FROM sync_stream_state WHERE singleton_id = 1"),
                )
                assertEquals(
                    "sending",
                    queryString(db, "SELECT state FROM sync_http_request"),
                )
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun createVersionFiveDatabase(name: String, nearMiss: NearMiss? = null) {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchemaFromAsset(db)
                seedHighPrecisionHalt(db)
                nearMiss?.mutate(db)
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
                .name(name)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun createSchemaFromAsset(db: SupportSQLiteDatabase) {
        val document = testAssets
            .open(VERSION_FIVE_SCHEMA_ASSET)
            .bufferedReader()
            .use { it.readText() }
        val entities = JSONObject(document)
            .getJSONObject("database")
            .getJSONArray("entities")
        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.getJSONArray("indices")
            for (indexIndex in 0 until indices.length()) {
                db.execSQL(
                    indices.getJSONObject(indexIndex)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", tableName),
                )
            }
        }
    }

    private fun seedHighPrecisionHalt(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO local_installation(installation_id, created_at_utc, server_device_id) " +
                "VALUES(?, ?, ?)",
            arrayOf(INSTALLATION_ID, CREATED_AT, DEVICE_ID),
        )
        db.execSQL(
            "INSERT INTO local_owner(local_owner_id, installation_id, created_at_utc, " +
                "server_person_id) VALUES(?, ?, ?, ?)",
            arrayOf(OWNER_ID, INSTALLATION_ID, CREATED_AT, PERSON_ID),
        )
        db.execSQL(
            "INSERT INTO local_identity_state(singleton_id, installation_id, local_owner_id, " +
                "selected_at_utc) VALUES(1, ?, ?, ?)",
            arrayOf(INSTALLATION_ID, OWNER_ID, CREATED_AT),
        )
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
                1, ?, ?, ?, ?, ?, 'Bearer', ?, ?, 'migration-test-key', 1, 1,
                '2030-01-01T00:00:00Z', 1893456000000,
                '2030-01-02T00:00:00Z', 1893542400000,
                '2030-01-03T00:00:00Z', 1893628800000,
                3, 'active', 1, ?, ?, NULL
            )
            """.trimIndent(),
            arrayOf(
                EPOCH_ID,
                INSTALLATION_ID,
                OWNER_ID,
                DEVICE_ID,
                PERSON_ID,
                byteArrayOf(1),
                byteArrayOf(2),
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
                integrity_error_code, updated_at_utc, replica_lineage_id
            ) VALUES(
                1, ?, ?, 'integrity_halted', 1, NULL, 0, NULL,
                'protected_response_reduction_failed', ?, NULL
            )
            """.trimIndent(),
            arrayOf(EPOCH_ID, DEVICE_ID, HIGH_PRECISION_FAILURE_AT),
        )
        db.execSQL(
            """
            INSERT INTO sync_bootstrap_session(
                bootstrap_id, credential_epoch_id, device_id, state,
                active_slot, snapshot_id, next_page_cursor,
                candidate_incremental_cursor, next_page_index,
                last_staged_server_sequence, staged_page_count,
                staged_body_bytes, created_at_utc, updated_at_utc
            ) VALUES(
                ?, ?, ?, 'staging', 1, NULL, NULL, NULL, 0, NULL, 0, 0, ?, ?
            )
            """.trimIndent(),
            arrayOf(BOOTSTRAP_ID, EPOCH_ID, DEVICE_ID, CREATED_AT, CREATED_AT),
        )
        db.execSQL(
            """
            INSERT INTO sync_http_request(
                endpoint_id, request_identity, protocol_version,
                credential_epoch_id, device_id, idempotency_key,
                body_storage_kind, raw_request_body,
                sealed_body_ciphertext, sealed_body_nonce,
                sealed_body_key_alias, sealed_body_key_generation,
                sealed_body_aad_version, request_body_octet_count,
                raw_body_hmac, hmac_key_generation, state, attempt_count,
                attempt_budget, deadline_at_epoch_ms,
                next_attempt_at_epoch_ms, last_attempt_at_epoch_ms,
                lease_expires_at_epoch_ms, active_attempt_id,
                access_generation_used, refresh_attempted,
                original_retry_count, terminal_http_status,
                exact_response_body, response_sha256, terminal_at_utc,
                terminal_error_code, created_at_utc, updated_at_utc
            ) VALUES(
                'sync_bootstrap', ?, '1.0.0', ?, ?, NULL, 'raw', ?,
                NULL, NULL, NULL, NULL, NULL, ?, ?, 1,
                'sending', 1, 8, ?, NULL, ?, ?, ?,
                3, 0, 0, NULL, NULL, NULL, NULL, NULL, ?, ?
            )
            """.trimIndent(),
            arrayOf(
                REQUEST_ID,
                EPOCH_ID,
                DEVICE_ID,
                REQUEST_BODY,
                REQUEST_BODY.size,
                HMAC,
                DEADLINE_MS,
                LAST_ATTEMPT_MS,
                LEASE_EXPIRES_MS,
                ATTEMPT_ID,
                CREATED_AT,
                CLAIMED_AT,
            ),
        )
    }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun queryNullableString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun queryNullableLong(db: SupportSQLiteDatabase, sql: String): Long? =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getLong(0)
        }

    private enum class NearMiss {
        MILLISECOND_LOCAL_TIME {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE sync_stream_state SET updated_at_utc = '2026-08-04T04:17:55.123Z'",
                )
            }
        },
        NON_PRISTINE_SESSION {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_bootstrap_session SET staged_page_count = 1")
            }
        },
        OTHER_INTEGRITY_FAILURE {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE sync_stream_state " +
                        "SET integrity_error_code = 'request_body_hmac_mismatch'",
                )
            }
        },
        ;

        abstract fun mutate(db: SupportSQLiteDatabase)
    }

    private companion object {
        const val VERSION_FIVE_SCHEMA_ASSET =
            "ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase/5.json"
        const val CREATED_AT = "2026-08-04T04:17:37.100Z"
        const val CLAIMED_AT = "2026-08-04T04:17:55.120Z"
        const val HIGH_PRECISION_FAILURE_AT = "2026-08-04T04:17:55.123456Z"
        const val INSTALLATION_ID = "00000000-0000-0000-0000-000000000101"
        const val OWNER_ID = "00000000-0000-0000-0000-000000000102"
        const val EPOCH_ID = "00000000-0000-0000-0000-000000000201"
        const val DEVICE_ID = "00000000-0000-0000-0000-000000000202"
        const val PERSON_ID = "00000000-0000-0000-0000-000000000203"
        const val BOOTSTRAP_ID = "00000000-0000-0000-0000-000000000301"
        const val REQUEST_ID = "00000000-0000-0000-0000-000000000401"
        const val ATTEMPT_ID = "00000000-0000-0000-0000-000000000501"
        const val LAST_ATTEMPT_MS = 1_785_817_875_120L
        const val LEASE_EXPIRES_MS = 1_785_817_935_120L
        const val DEADLINE_MS = 1_786_422_275_120L
        val REQUEST_BODY = (
            """{"protocol_version":"1.0.0","message_type":"bootstrap_request",""" +
                """"request_id":"$REQUEST_ID","bootstrap_id":"$BOOTSTRAP_ID",""" +
                """"device_id":"$DEVICE_ID","page_size":500,"page_cursor":null}"""
            ).toByteArray()
        val HMAC = ByteArray(32) { 7 }
    }
}
