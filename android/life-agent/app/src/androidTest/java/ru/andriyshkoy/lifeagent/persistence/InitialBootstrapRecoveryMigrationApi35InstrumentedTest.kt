package ru.andriyshkoy.lifeagent.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase
import ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabaseFactory
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedActionablePushConstructionPort
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestConstructionSettings
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningFacade
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedSyncRequestPlanningOutcome
import ru.andriyshkoy.lifeagent.data.sync.runtime.DurableSyncNoRequestReason

@RunWith(AndroidJUnit4::class)
class InitialBootstrapRecoveryMigrationApi35InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets

    @Test
    fun exactRetryClaimFalsePositiveForcesRefreshThenReleasesCurrentRetry() = runBlocking {
        val name = "initial-bootstrap-known-recovery.db"
        createVersionFourDatabase(name)

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
            assertEquals(
                1L,
                queryLong(
                    db,
                    "SELECT bootstrap_required FROM sync_stream_state WHERE singleton_id = 1",
                ),
            )
            assertNull(
                queryNullableString(
                    db,
                    "SELECT integrity_error_code FROM sync_stream_state WHERE singleton_id = 1",
                ),
            )
            assertEquals(
                "waiting_refresh",
                queryString(
                    db,
                    "SELECT state FROM sync_http_request WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertNull(
                queryNullableLong(
                    db,
                    "SELECT next_attempt_at_epoch_ms FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                2L,
                queryLong(
                    db,
                    "SELECT attempt_count FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                3L,
                queryLong(
                    db,
                    "SELECT access_generation_used FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                1L,
                queryLong(
                    db,
                    "SELECT refresh_attempted FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                0L,
                queryLong(
                    db,
                    "SELECT original_retry_count FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertNull(
                queryNullableString(
                    db,
                    "SELECT terminal_at_utc FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                "credential_recovery_pending",
                queryNullableString(
                    db,
                    "SELECT terminal_error_code FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                0L,
                queryLong(
                    db,
                    "SELECT COUNT(*) FROM sync_http_request " +
                        "WHERE active_attempt_id IS NOT NULL " +
                        "OR lease_expires_at_epoch_ms IS NOT NULL " +
                        "OR terminal_http_status IS NOT NULL " +
                        "OR exact_response_body IS NOT NULL " +
                        "OR response_sha256 IS NOT NULL",
                ),
            )
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
            val waiting = migrated.syncTransportDao()
                .findWaitingRefreshAuthoritySnapshots()
                .single()
            assertTrue(waiting.hasCanonicalWaitingShape)
            assertEquals(3L, waiting.accessGenerationUsed)

            val plan = ProtectedSyncRequestPlanningFacade(
                context = context,
                database = migrated,
                settings = ProtectedSyncRequestConstructionSettings(
                    pageSize = 100,
                    attemptBudget = 8,
                    requestLifetimeMillis = 60_000,
                ),
                actionablePushes = ProtectedActionablePushConstructionPort {
                    error("Known waiting-refresh recovery must not build a push")
                },
            ).planAndConstruct(PLANNED_AT)
            assertEquals(
                DurableSyncNoRequestReason.REFRESH_REQUIRED,
                (plan as ProtectedSyncRequestPlanningOutcome.NoRequest).plan.reason,
            )

            db.execSQL(
                "UPDATE sync_auth_state SET generation = 4, updated_at_utc = ? " +
                    "WHERE singleton_id = 1 AND generation = 3",
                arrayOf(REFRESHED_AT),
            )
            assertEquals(
                1,
                migrated.syncTransportDao().releaseExactWaitingRefreshRequests(
                    credentialEpochId = EPOCH_ID,
                    deviceId = DEVICE_ID,
                    failedAccessGeneration = 3,
                    successorGeneration = 4,
                    nextAttemptAtEpochMs = RELEASED_AT_MS,
                    updatedAtUtc = REFRESHED_AT,
                ),
            )
            val candidate = migrated.syncTransportDao()
                .findRunnableRequestCandidates(RELEASED_AT_MS, 10)
                .single()
            assertEquals("sync_bootstrap", candidate.endpointId)
            assertEquals("retry_wait", candidate.state)
            assertEquals(4L, candidate.accessGenerationUsed)
            // The raw DAO claim proves the forced-refresh release restored the
            // exact current-generation authority. Production reaches this CAS
            // only after ProtectedSyncRequestStore authenticates the unchanged
            // body/HMAC and verifies its bootstrap-session membership.
            assertEquals(
                1,
                migrated.syncTransportDao().claimAttempt(
                    endpointId = candidate.endpointId,
                    requestIdentity = candidate.requestIdentity,
                    credentialEpochId = candidate.credentialEpochId,
                    accessGenerationUsed = candidate.accessGenerationUsed,
                    attemptId = ATTEMPT_ID,
                    attemptedAtEpochMs = RELEASED_AT_MS,
                    leaseExpiresAtEpochMs = RELEASED_AT_MS + 60_000,
                    updatedAtUtc = CLAIMED_AT,
                ),
            )
            assertEquals(
                "sending",
                queryString(
                    db,
                    "SELECT state FROM sync_http_request WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
            assertEquals(
                3L,
                queryLong(
                    db,
                    "SELECT attempt_count FROM sync_http_request " +
                        "WHERE endpoint_id = 'sync_bootstrap'",
                ),
            )
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun nearMissesRemainIntegrityHalted() {
        NearMiss.entries.forEach { nearMiss ->
            val name = "initial-bootstrap-near-${nearMiss.name.lowercase()}.db"
            createVersionFourDatabase(name, nearMiss)

            val migrated = LifeAgentDatabaseFactory.create(
                context = context,
                openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
                databaseName = name,
            )
            try {
                val db = migrated.openHelper.writableDatabase
                assertEquals(
                    LifeAgentDatabase.VERSION.toLong(),
                    queryLong(db, "PRAGMA user_version"),
                )
                assertEquals(
                    "integrity_halted",
                    queryString(
                        db,
                        "SELECT phase FROM sync_stream_state WHERE singleton_id = 1",
                    ),
                )
                assertEquals(
                    "integrity_failure",
                    queryString(
                        db,
                        "SELECT state FROM sync_http_request " +
                            "WHERE endpoint_id = 'sync_bootstrap' " +
                            "ORDER BY request_identity LIMIT 1",
                    ),
                )
            } finally {
                migrated.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun createVersionFourDatabase(
        name: String,
        nearMiss: NearMiss? = null,
    ) {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchemaFromAsset(db, VERSION_FOUR_SCHEMA_ASSET)
                seedKnownFalsePositive(db)
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

    private fun createSchemaFromAsset(
        db: SupportSQLiteDatabase,
        schemaAsset: String,
    ) {
        val document = testAssets
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

    private fun seedKnownFalsePositive(db: SupportSQLiteDatabase) {
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
            ) VALUES(?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(OWNER_ID, INSTALLATION_ID, CREATED_AT, PERSON_ID),
        )
        db.execSQL(
            """
            INSERT INTO local_identity_state(
                singleton_id, installation_id, local_owner_id, selected_at_utc
            ) VALUES(1, ?, ?, ?)
            """.trimIndent(),
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
                TERMINAL_AT,
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
                'request_body_metadata_invalid', ?, NULL
            )
            """.trimIndent(),
            arrayOf(EPOCH_ID, DEVICE_ID, TERMINAL_AT),
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
                'integrity_failure', 2, 8, ?, NULL, ?, NULL, NULL,
                1, 0, 0, NULL, NULL, NULL, ?,
                'request_body_metadata_invalid', ?, ?
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
                TERMINAL_AT,
                CREATED_AT,
                TERMINAL_AT,
            ),
        )
    }

    private fun expandTableName(sql: String, tableName: String): String =
        sql.replace("\${TABLE_NAME}", tableName)

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
        ATTEMPT_ONE {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET attempt_count = 1")
            }
        },
        ATTEMPT_THREE {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET attempt_count = 3")
            }
        },
        NON_PRISTINE_SESSION {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_bootstrap_session SET staged_page_count = 1")
            }
        },
        APPLIED_CURSOR {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_stream_state SET applied_cursor = 'near-miss-cursor'")
            }
        },
        OTHER_INTEGRITY_FAILURE {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE sync_stream_state " +
                        "SET integrity_error_code = 'request_body_hmac_mismatch'",
                )
                db.execSQL(
                    "UPDATE sync_http_request " +
                        "SET terminal_error_code = 'request_body_hmac_mismatch'",
                )
            }
        },
        REFRESH_ALREADY_ATTEMPTED {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET refresh_attempted = 1")
            }
        },
        ORIGINAL_RETRY_ALREADY_CONSUMED {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET original_retry_count = 1")
            }
        },
        FUTURE_ACCESS_GENERATION {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET access_generation_used = 4")
            }
        },
        REQUEST_GENERATION_TWO {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET access_generation_used = 2")
            }
        },
        EXHAUSTED_BUDGET {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET attempt_budget = 2")
            }
        },
        NONCANONICAL_HMAC {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET raw_body_hmac = X'00'")
            }
        },
        NORMALIZED_ZERO_HMAC {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE sync_http_request SET raw_body_hmac = zeroblob(32)")
            }
        },
        EXISTING_PAGE_DATA {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO sync_page_receipt(
                        page_id, endpoint_id, request_identity, bootstrap_id,
                        page_index, snapshot_id, from_cursor, next_cursor,
                        incremental_cursor, page_sha256, change_count,
                        complete_or_has_more, state, first_server_sequence,
                        last_server_sequence, received_at_utc, applied_at_utc
                    ) VALUES(
                        '$PAGE_ID', 'sync_bootstrap', '$REQUEST_ID', '$BOOTSTRAP_ID',
                        0, NULL, NULL, NULL, NULL, '${"a".repeat(64)}', 0,
                        0, 'verified', NULL, NULL, '$TERMINAL_AT', NULL
                    )
                    """.trimIndent(),
                )
            }
        },
        EXISTING_CURSOR_DATA {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO sync_replica_cursor(lineage_id, cursor_value, role)
                    VALUES('$BOOTSTRAP_ID', 'near-miss-cursor', 'incremental')
                    """.trimIndent(),
                )
            }
        },
        SECOND_SESSION {
            override fun mutate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO sync_bootstrap_session(
                        bootstrap_id, credential_epoch_id, device_id, state,
                        active_slot, snapshot_id, next_page_cursor,
                        candidate_incremental_cursor, next_page_index,
                        last_staged_server_sequence, staged_page_count,
                        staged_body_bytes, created_at_utc, updated_at_utc
                    ) VALUES(
                        '$SECOND_BOOTSTRAP_ID', '$EPOCH_ID', '$DEVICE_ID',
                        'superseded', NULL, NULL, NULL, NULL, 0, NULL, 0, 0,
                        '$CREATED_AT', '$CREATED_AT'
                    )
                    """.trimIndent(),
                )
            }
        },
        ;

        abstract fun mutate(db: SupportSQLiteDatabase)
    }

    private companion object {
        const val VERSION_FOUR_SCHEMA_ASSET =
            "ru.andriyshkoy.lifeagent.data.local.db.LifeAgentDatabase/4.json"
        const val CREATED_AT = "2026-08-03T13:55:21Z"
        const val TERMINAL_AT = "2026-08-03T13:55:40Z"
        const val INSTALLATION_ID = "00000000-0000-0000-0000-000000000101"
        const val OWNER_ID = "00000000-0000-0000-0000-000000000102"
        const val EPOCH_ID = "00000000-0000-0000-0000-000000000201"
        const val DEVICE_ID = "00000000-0000-0000-0000-000000000202"
        const val PERSON_ID = "00000000-0000-0000-0000-000000000203"
        const val BOOTSTRAP_ID = "00000000-0000-0000-0000-000000000301"
        const val SECOND_BOOTSTRAP_ID = "00000000-0000-0000-0000-000000000302"
        const val REQUEST_ID = "00000000-0000-0000-0000-000000000401"
        const val PAGE_ID = "00000000-0000-0000-0000-000000000501"
        const val ATTEMPT_ID = "00000000-0000-0000-0000-000000000601"
        const val CLAIMED_AT = "2026-08-03T13:55:42Z"
        const val PLANNED_AT = "2026-08-03T13:55:41Z"
        const val REFRESHED_AT = "2026-08-03T13:55:42Z"
        const val LAST_ATTEMPT_MS = 1_785_765_340_000L
        const val RELEASED_AT_MS = 1_785_765_342_000L
        const val DEADLINE_MS = 1_893_456_000_000L
        val REQUEST_BODY = (
            """{"protocol_version":"1.0.0","message_type":"bootstrap_request",""" +
                """"request_id":"$REQUEST_ID","bootstrap_id":"$BOOTSTRAP_ID",""" +
                """"device_id":"$DEVICE_ID","page_size":100,"page_cursor":null}"""
            ).toByteArray()
        val HMAC = ByteArray(32) { 7 }
    }
}
