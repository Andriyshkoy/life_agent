package ru.andriyshkoy.lifeagent.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `local_catalog_item` (
                    `catalog_item_id` TEXT NOT NULL,
                    `local_owner_id` TEXT NOT NULL,
                    `catalog_kind` TEXT NOT NULL,
                    `created_at_utc` TEXT NOT NULL,
                    PRIMARY KEY(`catalog_item_id`),
                    FOREIGN KEY(`local_owner_id`) REFERENCES `local_owner`(`local_owner_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_local_catalog_item_local_owner_id_catalog_kind`
                ON `local_catalog_item` (`local_owner_id`, `catalog_kind`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `local_catalog_version` (
                    `catalog_version_id` TEXT NOT NULL,
                    `catalog_item_id` TEXT NOT NULL,
                    `version_no` INTEGER NOT NULL,
                    `schema_version` TEXT NOT NULL,
                    `payload_jcs` BLOB NOT NULL,
                    `content_sha256` TEXT NOT NULL,
                    `created_at_utc` TEXT NOT NULL,
                    PRIMARY KEY(`catalog_version_id`),
                    FOREIGN KEY(`catalog_item_id`) REFERENCES `local_catalog_item`(`catalog_item_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_local_catalog_version_catalog_item_id_catalog_version_id`
                ON `local_catalog_version` (`catalog_item_id`, `catalog_version_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_local_catalog_version_catalog_item_id_version_no`
                ON `local_catalog_version` (`catalog_item_id`, `version_no`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `local_catalog_head` (
                    `catalog_item_id` TEXT NOT NULL,
                    `current_version_id` TEXT NOT NULL,
                    `updated_at_utc` TEXT NOT NULL,
                    PRIMARY KEY(`catalog_item_id`),
                    FOREIGN KEY(`catalog_item_id`) REFERENCES `local_catalog_item`(`catalog_item_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`catalog_item_id`, `current_version_id`)
                        REFERENCES `local_catalog_version`(`catalog_item_id`, `catalog_version_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_local_catalog_head_catalog_item_id_current_version_id`
                ON `local_catalog_head` (`catalog_item_id`, `current_version_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_local_catalog_head_current_version_id`
                ON `local_catalog_head` (`current_version_id`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE sync_outbox
                ADD COLUMN command_fingerprint_sha256 TEXT NOT NULL DEFAULT ''
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            preflightVersionTwo(db)
            db.execSQL("PRAGMA defer_foreign_keys = ON")

            snapshotVersionTwoTables(db)
            dropVersionTwoDependencySet(db)
            createVersionThreeCoreTables(db)
            createVersionThreeRequiredParentIndices(db)
            restoreVersionTwoRows(db)
            createVersionThreeSyncTables(db)
            createVersionThreeIndices(db)
            installRuntimeGuards(db)
            seedCurrentIdentity(db)
            dropMigrationSnapshots(db)

            requireNoRows(
                db = db,
                sql = "PRAGMA foreign_key_check",
                message = "Room v3 migration produced a foreign-key violation",
            )
            db.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Room v3 migration failed SQLite integrity_check"
                }
            }
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                ALTER TABLE `sync_stream_state`
                ADD COLUMN `replica_lineage_id` TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_replica_cursor` (
                    `lineage_id` TEXT NOT NULL,
                    `cursor_value` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    PRIMARY KEY(`lineage_id`, `cursor_value`),
                    FOREIGN KEY(`lineage_id`)
                        REFERENCES `sync_bootstrap_session`(`bootstrap_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_sync_replica_cursor_lineage_id_role`
                ON `sync_replica_cursor` (`lineage_id`, `role`)
                """.trimIndent(),
            )

            // A v3 applied cursor has no trustworthy lineage ledger. Keep the
            // materialized replica visible, but forbid incremental use until a
            // new bootstrap establishes an authoritative lineage.
            db.execSQL(
                """
                UPDATE `sync_auth_state`
                SET `bootstrap_required` = 1
                WHERE EXISTS(
                    SELECT 1 FROM `sync_stream_state`
                    WHERE `sync_stream_state`.`applied_cursor` IS NOT NULL
                      AND `sync_stream_state`.`credential_epoch_id` =
                          `sync_auth_state`.`credential_epoch_id`
                      AND `sync_stream_state`.`device_id` =
                          `sync_auth_state`.`device_id`
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE `sync_stream_state`
                SET `phase` = 'bootstrap_required',
                    `bootstrap_required` = 1
                WHERE `applied_cursor` IS NOT NULL
                """.trimIndent(),
            )

            requireNoRows(
                db = db,
                sql = "PRAGMA foreign_key_check",
                message = "Room v4 migration produced a foreign-key violation",
            )
        }
    }

    /**
     * Releases only the single known false-positive initial-bootstrap halt
     * produced by the v4 retry-claim attempt-count race. The predicate is
     * deliberately stricter than the normal runtime state machine: anything
     * other than a pristine, current, first-bootstrap topology remains halted
     * for explicit integrity recovery. SQLite can prove only canonical storage
     * shape here; body and HMAC bytes remain unchanged so the normal protected
     * dispatch verifier authenticates and binds them after the forced refresh
     * releases the retry, failing closed again on any cryptographic or semantic
     * drift.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TEMP TABLE `_m5_known_bootstrap_recovery` (
                    `request_identity` TEXT NOT NULL PRIMARY KEY,
                    `credential_epoch_id` TEXT NOT NULL,
                    `device_id` TEXT NOT NULL,
                    `access_generation_used` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `_m5_known_bootstrap_recovery`(
                    request_identity, credential_epoch_id, device_id,
                    access_generation_used
                )
                SELECT request.request_identity,
                       request.credential_epoch_id,
                       request.device_id,
                       auth.generation
                FROM sync_http_request AS request
                JOIN sync_auth_state AS auth
                  ON auth.singleton_id = 1
                 AND auth.credential_epoch_id = request.credential_epoch_id
                 AND auth.device_id = request.device_id
                JOIN sync_stream_state AS stream
                  ON stream.singleton_id = 1
                 AND stream.credential_epoch_id = request.credential_epoch_id
                 AND stream.device_id = request.device_id
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id = identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                JOIN sync_bootstrap_session AS session
                  ON session.credential_epoch_id = request.credential_epoch_id
                 AND session.device_id = request.device_id
                WHERE typeof(auth.state) = 'text'
                  AND auth.state = 'active'
                  AND typeof(auth.bootstrap_required) = 'integer'
                  AND auth.bootstrap_required = 1
                  AND typeof(auth.generation) = 'integer'
                  AND auth.generation > 0
                  AND auth.token_type = 'Bearer'
                  AND auth.failure_code IS NULL
                  AND typeof(auth.refresh_token_ciphertext) = 'blob'
                  AND length(auth.refresh_token_ciphertext) > 0
                  AND typeof(auth.refresh_token_nonce) = 'blob'
                  AND length(auth.refresh_token_nonce) > 0
                  AND typeof(auth.refresh_token_key_alias) = 'text'
                  AND length(trim(auth.refresh_token_key_alias)) > 0
                  AND typeof(auth.refresh_token_key_generation) = 'integer'
                  AND auth.refresh_token_key_generation > 0
                  AND typeof(auth.refresh_token_aad_version) = 'integer'
                  AND auth.refresh_token_aad_version > 0
                  AND typeof(stream.phase) = 'text'
                  AND stream.phase = 'integrity_halted'
                  AND typeof(stream.bootstrap_required) = 'integer'
                  AND stream.bootstrap_required = 1
                  AND stream.integrity_error_code = 'request_body_metadata_invalid'
                  AND typeof(stream.updated_at_utc) = 'text'
                  AND stream.applied_cursor IS NULL
                  AND typeof(stream.last_applied_server_sequence) = 'integer'
                  AND stream.last_applied_server_sequence = 0
                  AND stream.high_watermark_hint IS NULL
                  AND stream.replica_lineage_id IS NULL
                  AND typeof(session.state) = 'text'
                  AND session.state = 'staging'
                  AND typeof(session.active_slot) = 'integer'
                  AND session.active_slot = 1
                  AND session.snapshot_id IS NULL
                  AND session.next_page_cursor IS NULL
                  AND session.candidate_incremental_cursor IS NULL
                  AND typeof(session.next_page_index) = 'integer'
                  AND session.next_page_index = 0
                  AND session.last_staged_server_sequence IS NULL
                  AND typeof(session.staged_page_count) = 'integer'
                  AND session.staged_page_count = 0
                  AND typeof(session.staged_body_bytes) = 'integer'
                  AND session.staged_body_bytes = 0
                  AND typeof(session.created_at_utc) = 'text'
                  AND length(trim(session.created_at_utc)) > 0
                  AND session.updated_at_utc = session.created_at_utc
                  AND typeof(request.endpoint_id) = 'text'
                  AND request.endpoint_id = 'sync_bootstrap'
                  AND typeof(request.request_identity) = 'text'
                  AND length(request.request_identity) > 0
                  AND typeof(request.protocol_version) = 'text'
                  AND request.protocol_version = '1.0.0'
                  AND request.idempotency_key IS NULL
                  AND typeof(request.body_storage_kind) = 'text'
                  AND request.body_storage_kind = 'raw'
                  AND typeof(request.raw_request_body) = 'blob'
                  AND length(request.raw_request_body) > 0
                  AND typeof(request.request_body_octet_count) = 'integer'
                  AND request.request_body_octet_count = length(request.raw_request_body)
                  AND request.sealed_body_ciphertext IS NULL
                  AND request.sealed_body_nonce IS NULL
                  AND request.sealed_body_key_alias IS NULL
                  AND request.sealed_body_key_generation IS NULL
                  AND request.sealed_body_aad_version IS NULL
                  AND typeof(request.raw_body_hmac) = 'blob'
                  AND length(request.raw_body_hmac) = 32
                  AND request.raw_body_hmac != zeroblob(32)
                  AND typeof(request.hmac_key_generation) = 'integer'
                  AND request.hmac_key_generation = 1
                  AND typeof(request.state) = 'text'
                  AND request.state = 'integrity_failure'
                  AND typeof(request.attempt_count) = 'integer'
                  AND request.attempt_count = 2
                  AND typeof(request.attempt_budget) = 'integer'
                  AND request.attempt_budget > request.attempt_count
                  AND request.attempt_budget <= 2147483647
                  AND typeof(request.deadline_at_epoch_ms) = 'integer'
                  AND request.deadline_at_epoch_ms > 0
                  AND request.next_attempt_at_epoch_ms IS NULL
                  AND typeof(request.last_attempt_at_epoch_ms) = 'integer'
                  AND request.last_attempt_at_epoch_ms > 0
                  AND request.last_attempt_at_epoch_ms < request.deadline_at_epoch_ms
                  AND request.lease_expires_at_epoch_ms IS NULL
                  AND request.active_attempt_id IS NULL
                  AND typeof(request.access_generation_used) = 'integer'
                  AND request.access_generation_used = 1
                  AND request.access_generation_used <= auth.generation
                  AND typeof(request.refresh_attempted) = 'integer'
                  AND request.refresh_attempted = 0
                  AND typeof(request.original_retry_count) = 'integer'
                  AND request.original_retry_count = 0
                  AND request.terminal_http_status IS NULL
                  AND request.exact_response_body IS NULL
                  AND request.response_sha256 IS NULL
                  AND typeof(request.terminal_at_utc) = 'text'
                  AND length(trim(request.terminal_at_utc)) > 0
                  AND request.terminal_error_code = 'request_body_metadata_invalid'
                  AND typeof(request.created_at_utc) = 'text'
                  AND length(trim(request.created_at_utc)) > 0
                  AND typeof(request.updated_at_utc) = 'text'
                  AND request.updated_at_utc = request.terminal_at_utc
                  AND stream.updated_at_utc = request.terminal_at_utc
                  AND (SELECT COUNT(*) FROM sync_auth_state) = 1
                  AND (SELECT COUNT(*) FROM sync_stream_state) = 1
                  AND (SELECT COUNT(*) FROM sync_bootstrap_session) = 1
                  AND (SELECT COUNT(*) FROM sync_http_request) = 1
                  AND NOT EXISTS(SELECT 1 FROM sync_server_change)
                  AND NOT EXISTS(SELECT 1 FROM sync_page_receipt)
                  AND NOT EXISTS(SELECT 1 FROM sync_staged_change)
                  AND NOT EXISTS(SELECT 1 FROM sync_replica_cursor)
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE sync_http_request
                SET state = 'waiting_refresh',
                    access_generation_used = (
                        SELECT recovery.access_generation_used
                        FROM `_m5_known_bootstrap_recovery` AS recovery
                        WHERE recovery.request_identity =
                                  sync_http_request.request_identity
                          AND recovery.credential_epoch_id =
                                  sync_http_request.credential_epoch_id
                          AND recovery.device_id = sync_http_request.device_id
                    ),
                    refresh_attempted = 1,
                    original_retry_count = 0,
                    next_attempt_at_epoch_ms = NULL,
                    lease_expires_at_epoch_ms = NULL,
                    active_attempt_id = NULL,
                    terminal_http_status = NULL,
                    exact_response_body = NULL,
                    response_sha256 = NULL,
                    terminal_at_utc = NULL,
                    terminal_error_code = 'credential_recovery_pending'
                WHERE EXISTS(
                    SELECT 1 FROM `_m5_known_bootstrap_recovery` AS recovery
                    WHERE recovery.request_identity =
                              sync_http_request.request_identity
                      AND recovery.credential_epoch_id =
                              sync_http_request.credential_epoch_id
                      AND recovery.device_id = sync_http_request.device_id
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE sync_stream_state
                SET phase = 'bootstrap_required',
                    integrity_error_code = NULL
                WHERE EXISTS(
                    SELECT 1 FROM `_m5_known_bootstrap_recovery` AS recovery
                    WHERE recovery.credential_epoch_id =
                              sync_stream_state.credential_epoch_id
                      AND recovery.device_id = sync_stream_state.device_id
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `_m5_known_bootstrap_recovery`")

            requireNoRows(
                db = db,
                sql = "PRAGMA foreign_key_check",
                message = "Room v5 recovery migration produced a foreign-key violation",
            )
        }
    }

    /**
     * Requeues only the pristine first-bootstrap halt caused by applying the
     * millisecond-only server timestamp rule to a higher-precision Android
     * completion timestamp. No response or request bytes are rewritten; the
     * protected retry verifies the original body and receives the server's
     * exact replay after normal credential refresh.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TEMP TABLE `_m6_local_time_bootstrap_recovery` (
                    `request_identity` TEXT NOT NULL PRIMARY KEY,
                    `credential_epoch_id` TEXT NOT NULL,
                    `device_id` TEXT NOT NULL,
                    `access_generation_used` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `_m6_local_time_bootstrap_recovery`(
                    request_identity, credential_epoch_id, device_id,
                    access_generation_used
                )
                SELECT request.request_identity,
                       request.credential_epoch_id,
                       request.device_id,
                       auth.generation
                FROM sync_http_request AS request
                JOIN sync_auth_state AS auth
                  ON auth.singleton_id = 1
                 AND auth.credential_epoch_id = request.credential_epoch_id
                 AND auth.device_id = request.device_id
                JOIN sync_stream_state AS stream
                  ON stream.singleton_id = 1
                 AND stream.credential_epoch_id = request.credential_epoch_id
                 AND stream.device_id = request.device_id
                JOIN local_identity_state AS identity
                  ON identity.singleton_id = 1
                 AND identity.installation_id = auth.installation_id
                 AND identity.local_owner_id = auth.local_owner_id
                JOIN local_installation AS installation
                  ON installation.installation_id = identity.installation_id
                 AND installation.server_device_id = auth.device_id
                JOIN local_owner AS owner
                  ON owner.installation_id = identity.installation_id
                 AND owner.local_owner_id = identity.local_owner_id
                 AND owner.server_person_id = auth.person_id
                JOIN sync_bootstrap_session AS session
                  ON session.credential_epoch_id = request.credential_epoch_id
                 AND session.device_id = request.device_id
                WHERE typeof(auth.state) = 'text'
                  AND auth.state = 'active'
                  AND typeof(auth.bootstrap_required) = 'integer'
                  AND auth.bootstrap_required = 1
                  AND typeof(auth.generation) = 'integer'
                  AND auth.generation > 0
                  AND auth.token_type = 'Bearer'
                  AND auth.failure_code IS NULL
                  AND typeof(auth.refresh_token_ciphertext) = 'blob'
                  AND length(auth.refresh_token_ciphertext) > 0
                  AND typeof(auth.refresh_token_nonce) = 'blob'
                  AND length(auth.refresh_token_nonce) > 0
                  AND typeof(auth.refresh_token_key_alias) = 'text'
                  AND length(trim(auth.refresh_token_key_alias)) > 0
                  AND typeof(auth.refresh_token_key_generation) = 'integer'
                  AND auth.refresh_token_key_generation > 0
                  AND typeof(auth.refresh_token_aad_version) = 'integer'
                  AND auth.refresh_token_aad_version > 0
                  AND typeof(stream.phase) = 'text'
                  AND stream.phase = 'integrity_halted'
                  AND typeof(stream.bootstrap_required) = 'integer'
                  AND stream.bootstrap_required = 1
                  AND stream.integrity_error_code =
                      'protected_response_reduction_failed'
                  AND stream.applied_cursor IS NULL
                  AND typeof(stream.last_applied_server_sequence) = 'integer'
                  AND stream.last_applied_server_sequence = 0
                  AND stream.high_watermark_hint IS NULL
                  AND stream.replica_lineage_id IS NULL
                  AND typeof(stream.updated_at_utc) = 'text'
                  AND (
                    (
                      length(stream.updated_at_utc) = 27
                      AND stream.updated_at_utc GLOB
                          '????-??-??T??:??:??.??????Z'
                    ) OR (
                      length(stream.updated_at_utc) = 30
                      AND stream.updated_at_utc GLOB
                          '????-??-??T??:??:??.?????????Z'
                    )
                  )
                  AND typeof(session.state) = 'text'
                  AND session.state = 'staging'
                  AND typeof(session.active_slot) = 'integer'
                  AND session.active_slot = 1
                  AND session.snapshot_id IS NULL
                  AND session.next_page_cursor IS NULL
                  AND session.candidate_incremental_cursor IS NULL
                  AND typeof(session.next_page_index) = 'integer'
                  AND session.next_page_index = 0
                  AND session.last_staged_server_sequence IS NULL
                  AND typeof(session.staged_page_count) = 'integer'
                  AND session.staged_page_count = 0
                  AND typeof(session.staged_body_bytes) = 'integer'
                  AND session.staged_body_bytes = 0
                  AND typeof(request.endpoint_id) = 'text'
                  AND request.endpoint_id = 'sync_bootstrap'
                  AND typeof(request.request_identity) = 'text'
                  AND length(request.request_identity) > 0
                  AND request.protocol_version = '1.0.0'
                  AND request.idempotency_key IS NULL
                  AND request.body_storage_kind = 'raw'
                  AND typeof(request.raw_request_body) = 'blob'
                  AND length(request.raw_request_body) > 0
                  AND typeof(request.request_body_octet_count) = 'integer'
                  AND request.request_body_octet_count =
                      length(request.raw_request_body)
                  AND request.sealed_body_ciphertext IS NULL
                  AND request.sealed_body_nonce IS NULL
                  AND request.sealed_body_key_alias IS NULL
                  AND request.sealed_body_key_generation IS NULL
                  AND request.sealed_body_aad_version IS NULL
                  AND typeof(request.raw_body_hmac) = 'blob'
                  AND length(request.raw_body_hmac) = 32
                  AND request.raw_body_hmac != zeroblob(32)
                  AND typeof(request.hmac_key_generation) = 'integer'
                  AND request.hmac_key_generation = 1
                  AND typeof(request.state) = 'text'
                  AND request.state = 'sending'
                  AND typeof(request.attempt_count) = 'integer'
                  AND request.attempt_count > 0
                  AND typeof(request.attempt_budget) = 'integer'
                  AND request.attempt_count < request.attempt_budget
                  AND request.attempt_budget <= 2147483647
                  AND typeof(request.deadline_at_epoch_ms) = 'integer'
                  AND request.deadline_at_epoch_ms > 0
                  AND request.next_attempt_at_epoch_ms IS NULL
                  AND typeof(request.last_attempt_at_epoch_ms) = 'integer'
                  AND request.last_attempt_at_epoch_ms > 0
                  AND request.last_attempt_at_epoch_ms <
                      request.deadline_at_epoch_ms
                  AND typeof(request.lease_expires_at_epoch_ms) = 'integer'
                  AND request.lease_expires_at_epoch_ms >
                      request.last_attempt_at_epoch_ms
                  AND typeof(request.active_attempt_id) = 'text'
                  AND length(trim(request.active_attempt_id)) > 0
                  AND typeof(request.access_generation_used) = 'integer'
                  AND request.access_generation_used > 0
                  AND request.access_generation_used <= auth.generation
                  AND typeof(request.refresh_attempted) = 'integer'
                  AND request.refresh_attempted = 0
                  AND typeof(request.original_retry_count) = 'integer'
                  AND request.original_retry_count = 0
                  AND request.terminal_http_status IS NULL
                  AND request.exact_response_body IS NULL
                  AND request.response_sha256 IS NULL
                  AND request.terminal_at_utc IS NULL
                  AND request.terminal_error_code IS NULL
                  AND (SELECT COUNT(*) FROM sync_auth_state) = 1
                  AND (SELECT COUNT(*) FROM sync_stream_state) = 1
                  AND (SELECT COUNT(*) FROM sync_bootstrap_session) = 1
                  AND (SELECT COUNT(*) FROM sync_http_request) = 1
                  AND NOT EXISTS(SELECT 1 FROM sync_server_change)
                  AND NOT EXISTS(SELECT 1 FROM sync_page_receipt)
                  AND NOT EXISTS(SELECT 1 FROM sync_staged_change)
                  AND NOT EXISTS(SELECT 1 FROM sync_replica_cursor)
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE sync_http_request
                SET state = 'waiting_refresh',
                    access_generation_used = (
                        SELECT recovery.access_generation_used
                        FROM `_m6_local_time_bootstrap_recovery` AS recovery
                        WHERE recovery.request_identity =
                                  sync_http_request.request_identity
                          AND recovery.credential_epoch_id =
                                  sync_http_request.credential_epoch_id
                          AND recovery.device_id = sync_http_request.device_id
                    ),
                    refresh_attempted = 1,
                    original_retry_count = 0,
                    next_attempt_at_epoch_ms = NULL,
                    lease_expires_at_epoch_ms = NULL,
                    active_attempt_id = NULL,
                    terminal_http_status = NULL,
                    exact_response_body = NULL,
                    response_sha256 = NULL,
                    terminal_at_utc = NULL,
                    terminal_error_code = 'credential_recovery_pending'
                WHERE EXISTS(
                    SELECT 1
                    FROM `_m6_local_time_bootstrap_recovery` AS recovery
                    WHERE recovery.request_identity =
                              sync_http_request.request_identity
                      AND recovery.credential_epoch_id =
                              sync_http_request.credential_epoch_id
                      AND recovery.device_id = sync_http_request.device_id
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE sync_stream_state
                SET phase = 'bootstrap_required',
                    integrity_error_code = NULL
                WHERE EXISTS(
                    SELECT 1
                    FROM `_m6_local_time_bootstrap_recovery` AS recovery
                    WHERE recovery.credential_epoch_id =
                              sync_stream_state.credential_epoch_id
                      AND recovery.device_id = sync_stream_state.device_id
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `_m6_local_time_bootstrap_recovery`")

            requireNoRows(
                db = db,
                sql = "PRAGMA foreign_key_check",
                message = "Room v6 recovery migration produced a foreign-key violation",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )

    /**
     * Room does not expose table CHECK declarations in @Entity. Equivalent
     * SQLite guards keep raw and Keystore-sealed request bodies mutually
     * exclusive on both fresh and migrated databases.
     */
    fun installRuntimeGuards(db: SupportSQLiteDatabase) {
        val requestBodyPredicate = """
            (
              NEW.`body_storage_kind` = 'raw'
              AND NEW.`endpoint_id` IN ('sync_push', 'sync_bootstrap', 'sync_pull')
              AND NEW.`raw_request_body` IS NOT NULL
              AND length(NEW.`raw_request_body`) > 0
              AND NEW.`request_body_octet_count` = length(NEW.`raw_request_body`)
              AND NEW.`sealed_body_ciphertext` IS NULL
              AND NEW.`sealed_body_nonce` IS NULL
              AND NEW.`sealed_body_key_alias` IS NULL
              AND NEW.`sealed_body_key_generation` IS NULL
              AND NEW.`sealed_body_aad_version` IS NULL
            )
            OR
            (
              NEW.`body_storage_kind` = 'keystore_aead'
              AND NEW.`endpoint_id` = 'auth_revoke'
              AND NEW.`raw_request_body` IS NULL
              AND NEW.`request_body_octet_count` > 0
              AND NEW.`sealed_body_ciphertext` IS NOT NULL
              AND length(NEW.`sealed_body_ciphertext`) > 0
              AND NEW.`sealed_body_nonce` IS NOT NULL
              AND length(NEW.`sealed_body_nonce`) > 0
              AND NEW.`sealed_body_key_alias` IS NOT NULL
              AND length(trim(NEW.`sealed_body_key_alias`)) > 0
              AND NEW.`sealed_body_key_generation` IS NOT NULL
              AND NEW.`sealed_body_key_generation` > 0
              AND NEW.`sealed_body_aad_version` IS NOT NULL
              AND NEW.`sealed_body_aad_version` > 0
            )
        """.trimIndent()
        val authEnvelopePredicate = """
            (
              NEW.`refresh_token_ciphertext` IS NULL
              AND NEW.`refresh_token_nonce` IS NULL
              AND NEW.`refresh_token_key_alias` IS NULL
              AND NEW.`refresh_token_key_generation` IS NULL
              AND NEW.`refresh_token_aad_version` IS NULL
              AND NEW.`state` IN (
                'quarantined',
                'expired',
                'revoked',
                'integrity_failure'
              )
            )
            OR
            (
              NEW.`state` IN (
                'active',
                'refresh_in_flight',
                'revoke_pending'
              )
              AND
              NEW.`refresh_token_ciphertext` IS NOT NULL
              AND length(NEW.`refresh_token_ciphertext`) > 0
              AND NEW.`refresh_token_nonce` IS NOT NULL
              AND length(NEW.`refresh_token_nonce`) > 0
              AND NEW.`refresh_token_key_alias` IS NOT NULL
              AND length(trim(NEW.`refresh_token_key_alias`)) > 0
              AND NEW.`refresh_token_key_generation` IS NOT NULL
              AND NEW.`refresh_token_key_generation` > 0
              AND NEW.`refresh_token_aad_version` IS NOT NULL
              AND NEW.`refresh_token_aad_version` > 0
            )
        """.trimIndent()
        listOf(
            "guard_sync_http_request_body_insert",
            "guard_sync_http_request_body_update",
            "guard_sync_auth_envelope_insert",
            "guard_sync_auth_envelope_update",
        ).forEach { triggerName ->
            db.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
        }
        listOf("INSERT", "UPDATE").forEach { operation ->
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS
                    `guard_sync_http_request_body_${operation.lowercase()}`
                BEFORE $operation ON `sync_http_request`
                WHEN COALESCE(($requestBodyPredicate), 0) = 0
                     OR NEW.`raw_body_hmac` IS NULL
                     OR typeof(NEW.`raw_body_hmac`) != 'blob'
                     OR length(NEW.`raw_body_hmac`) != 32
                     OR typeof(NEW.`hmac_key_generation`) != 'integer'
                     OR NEW.`hmac_key_generation` != 1
                     OR (
                       NEW.`state` IN ('ready', 'retry_wait', 'sending', 'waiting_refresh')
                       AND (
                         typeof(NEW.`access_generation_used`) != 'integer'
                         OR NEW.`access_generation_used` <= 0
                         OR typeof(NEW.`attempt_count`) != 'integer'
                         OR NEW.`attempt_count` < 0
                         OR NEW.`attempt_count` > 2147483647
                       )
                     )
                BEGIN
                    SELECT RAISE(ABORT, 'invalid durable request body storage');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS
                    `guard_sync_auth_envelope_${operation.lowercase()}`
                BEFORE $operation ON `sync_auth_state`
                WHEN COALESCE(($authEnvelopePredicate), 0) = 0
                     OR NEW.`access_expires_at_epoch_ms` <= 0
                     OR NEW.`refresh_expires_at_epoch_ms`
                        <= NEW.`access_expires_at_epoch_ms`
                     OR NEW.`family_expires_at_epoch_ms`
                        < NEW.`refresh_expires_at_epoch_ms`
                BEGIN
                    SELECT RAISE(ABORT, 'invalid credential envelope storage');
                END
                """.trimIndent(),
            )
        }
    }

    private fun preflightVersionTwo(db: SupportSQLiteDatabase) {
        requireNoRows(
            db = db,
            sql = "PRAGMA foreign_key_check",
            message = "Room v2 database has a foreign-key violation",
        )
        db.query("SELECT COUNT(*) FROM local_owner").use { cursor ->
            check(cursor.moveToFirst())
            check(cursor.getLong(0) <= 1L) {
                "Room v2 current identity is ambiguous"
            }
        }
        requireNoRows(
            db = db,
            sql = """
                SELECT 1
                FROM local_event_revision AS r
                LEFT JOIN local_capture AS c
                  ON c.capture_id = r.capture_id
                 AND c.operation_id = r.operation_id
                WHERE c.capture_id IS NULL
                LIMIT 1
            """.trimIndent(),
            message = "Room v2 revision/capture operation binding is invalid",
        )
        requireNoRows(
            db = db,
            sql = """
                SELECT 1
                FROM sync_outbox AS o
                LEFT JOIN local_capture AS c
                  ON c.capture_id = o.capture_id
                 AND c.operation_id = o.operation_id
                 AND c.local_owner_id = o.local_owner_id
                 AND c.installation_id = o.installation_id
                WHERE c.capture_id IS NULL
                LIMIT 1
            """.trimIndent(),
            message = "Room v2 outbox/capture provenance binding is invalid",
        )
        requireNoRows(
            db = db,
            sql = """
                SELECT 1
                FROM sync_outbox AS o
                LEFT JOIN local_event_revision AS r
                  ON r.event_id = o.event_id
                 AND r.revision_id = o.revision_id
                 AND r.capture_id = o.capture_id
                 AND r.operation_id = o.operation_id
                WHERE r.revision_id IS NULL
                LIMIT 1
            """.trimIndent(),
            message = "Room v2 outbox/revision binding is invalid",
        )
        requireNoRows(
            db = db,
            sql = """
                SELECT 1
                FROM sync_outbox AS o
                JOIN local_capture AS c
                  ON c.capture_id = o.capture_id
                 AND c.operation_id = o.operation_id
                JOIN local_event_revision AS r
                  ON r.event_id = o.event_id
                 AND r.revision_id = o.revision_id
                 AND r.capture_id = o.capture_id
                 AND r.operation_id = o.operation_id
                WHERE o.operation_kind = 'append_event_revision'
                  AND (
                    o.schema_version = '4.0.0'
                    OR c.schema_version = '4.0.0'
                    OR r.schema_version = '4.0.0'
                  )
                  AND NOT (
                    o.schema_version = '4.0.0'
                    AND c.schema_version = '4.0.0'
                    AND r.schema_version = '4.0.0'
                  )
                LIMIT 1
            """.trimIndent(),
            message = "Room v2 M2 outbox schema tuple is inconsistent",
        )
        requireNoRows(
            db = db,
            sql = """
                SELECT server_sequence
                FROM local_event_revision
                WHERE server_sequence IS NOT NULL
                GROUP BY server_sequence
                HAVING COUNT(*) > 1
                LIMIT 1
            """.trimIndent(),
            message = "Room v2 contains duplicate server sequences",
        )
        requireNoRows(
            db = db,
            sql = """
                SELECT 1
                FROM local_event_head AS h
                JOIN local_event_revision AS r
                  ON r.event_id = h.event_id
                 AND r.revision_id = h.server_current_revision_id
                WHERE h.server_current_revision_id IS NOT NULL
                  AND r.server_sequence IS NULL
                LIMIT 1
            """.trimIndent(),
            message = "Room v2 remote head has no observed server sequence",
        )
    }

    private fun snapshotVersionTwoTables(db: SupportSQLiteDatabase) {
        val snapshots = listOf(
            "_m2_capture_backup" to "local_capture",
            "_m2_revision_backup" to "local_event_revision",
            "_m2_parent_backup" to "local_revision_parent",
            "_m2_head_backup" to "local_event_head",
            "_m2_outbox_backup" to "sync_outbox",
        )
        snapshots.forEach { (snapshot, source) ->
            db.execSQL("DROP TABLE IF EXISTS `$snapshot`")
            db.execSQL("CREATE TEMP TABLE `$snapshot` AS SELECT * FROM `$source`")
        }
        db.execSQL("DROP TABLE IF EXISTS `_m2_outbox_sequence_backup`")
        db.execSQL(
            "CREATE TEMP TABLE `_m2_outbox_sequence_backup` (`seq` INTEGER NOT NULL)",
        )
        db.execSQL(
            """
            INSERT INTO `_m2_outbox_sequence_backup`(`seq`)
            SELECT seq FROM sqlite_sequence WHERE name = 'sync_outbox'
            """.trimIndent(),
        )
    }

    private fun dropVersionTwoDependencySet(db: SupportSQLiteDatabase) {
        listOf(
            "sync_outbox",
            "local_event_head",
            "local_revision_parent",
            "local_event_revision",
            "local_capture",
        ).forEach { table ->
            db.execSQL("DROP TABLE `$table`")
        }
        db.execSQL("DROP INDEX `index_local_owner_server_person_id`")
    }

    private fun createVersionThreeCoreTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_capture` (
                `capture_id` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `installation_id` TEXT NOT NULL,
                `local_owner_id` TEXT NOT NULL,
                `schema_version` TEXT NOT NULL,
                `persistence_state` TEXT NOT NULL,
                `source_channel` TEXT NOT NULL,
                `recorded_at_rfc3339` TEXT NOT NULL,
                `recorded_at_epoch_ms` INTEGER NOT NULL,
                `timezone_id` TEXT NOT NULL,
                `utc_offset_minutes` INTEGER NOT NULL,
                `origin_provider` TEXT,
                `origin_app` TEXT,
                `origin_device` TEXT,
                `origin_source_record_id` TEXT,
                `origin_source_record_version` TEXT,
                `origin_user_entered` INTEGER NOT NULL,
                `collector_name` TEXT NOT NULL,
                `collector_version` TEXT NOT NULL,
                `content_jcs` BLOB NOT NULL,
                `content_sha256` TEXT NOT NULL,
                `byte_size` INTEGER NOT NULL,
                PRIMARY KEY(`capture_id`),
                FOREIGN KEY(`local_owner_id`, `installation_id`)
                    REFERENCES `local_owner`(`local_owner_id`, `installation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_event_revision` (
                `revision_id` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `capture_id` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `revision_no` INTEGER NOT NULL,
                `schema_version` TEXT NOT NULL,
                `assertion_status` TEXT NOT NULL,
                `lifecycle` TEXT,
                `record_status` TEXT NOT NULL,
                `verification_status` TEXT NOT NULL,
                `source_channel` TEXT NOT NULL,
                `source_record_id` TEXT,
                `source_record_version` TEXT,
                `source_modified_at` TEXT,
                `recorded_at_rfc3339` TEXT NOT NULL,
                `origin_provider` TEXT,
                `origin_app` TEXT,
                `origin_device` TEXT,
                `origin_user_entered` INTEGER NOT NULL,
                `collector_name` TEXT NOT NULL,
                `collector_version` TEXT NOT NULL,
                `effective_start_utc` TEXT NOT NULL,
                `effective_start_epoch_ms` INTEGER NOT NULL,
                `effective_end_utc` TEXT,
                `effective_end_epoch_ms` INTEGER,
                `original_local_start` TEXT NOT NULL,
                `original_local_end` TEXT,
                `timezone_id` TEXT NOT NULL,
                `start_offset_seconds` INTEGER NOT NULL,
                `end_offset_seconds` INTEGER,
                `temporal_precision` TEXT NOT NULL,
                `local_date` TEXT NOT NULL,
                `source_expression` TEXT,
                `payload_jcs` BLOB NOT NULL,
                `evidence_jcs` BLOB NOT NULL,
                `quality_flags_jcs` BLOB NOT NULL,
                `created_at_rfc3339` TEXT NOT NULL,
                `content_sha256` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `correction_reason` TEXT,
                `server_received_at` TEXT,
                `server_sequence` INTEGER,
                PRIMARY KEY(`revision_id`),
                FOREIGN KEY(`event_id`) REFERENCES `local_life_event`(`event_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`capture_id`, `operation_id`)
                    REFERENCES `local_capture`(`capture_id`, `operation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_revision_parent` (
                `event_id` TEXT NOT NULL,
                `child_revision_id` TEXT NOT NULL,
                `parent_revision_id` TEXT NOT NULL,
                `relation` TEXT NOT NULL,
                PRIMARY KEY(`child_revision_id`, `parent_revision_id`),
                FOREIGN KEY(`event_id`, `child_revision_id`)
                    REFERENCES `local_event_revision`(`event_id`, `revision_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`event_id`, `parent_revision_id`)
                    REFERENCES `local_event_revision`(`event_id`, `revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_event_head` (
                `event_id` TEXT NOT NULL,
                `current_revision_id` TEXT NOT NULL,
                `server_current_revision_id` TEXT,
                `server_observed_sequence` INTEGER,
                `updated_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`event_id`),
                FOREIGN KEY(`event_id`) REFERENCES `local_life_event`(`event_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`event_id`, `current_revision_id`)
                    REFERENCES `local_event_revision`(`event_id`, `revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_outbox` (
                `local_sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `capture_id` TEXT NOT NULL,
                `installation_id` TEXT NOT NULL,
                `local_owner_id` TEXT NOT NULL,
                `operation_kind` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `revision_id` TEXT NOT NULL,
                `base_revision_id` TEXT,
                `schema_version` TEXT NOT NULL,
                `operation_jcs` BLOB NOT NULL,
                `operation_content_sha256` TEXT NOT NULL,
                `command_fingerprint_sha256` TEXT NOT NULL DEFAULT '',
                `wire_state` TEXT NOT NULL DEFAULT 'needs_materialization',
                `wire_protocol_version` TEXT,
                `wire_operation_material_jcs` BLOB,
                `wire_operation_content_sha256` TEXT,
                `wire_materialized_at_utc` TEXT,
                `active_batch_id` TEXT,
                `last_result_batch_id` TEXT,
                `last_result_code` TEXT,
                `last_result_retryable` INTEGER,
                `last_result_current_revision_id` TEXT,
                `last_result_details_jcs` BLOB,
                `created_at_utc` TEXT NOT NULL,
                `created_at_epoch_ms` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL,
                `next_attempt_at_epoch_ms` INTEGER,
                `last_attempt_at_epoch_ms` INTEGER,
                `server_sequence` INTEGER,
                `acked_at_utc` TEXT,
                `last_error_code` TEXT,
                FOREIGN KEY(
                    `capture_id`, `operation_id`, `local_owner_id`, `installation_id`
                ) REFERENCES `local_capture`(
                    `capture_id`, `operation_id`, `local_owner_id`, `installation_id`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`event_id`, `revision_id`, `capture_id`, `operation_id`)
                    REFERENCES `local_event_revision`(
                        `event_id`, `revision_id`, `capture_id`, `operation_id`
                    ) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`event_id`, `base_revision_id`)
                    REFERENCES `local_event_revision`(`event_id`, `revision_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_identity_state` (
                `singleton_id` INTEGER NOT NULL,
                `installation_id` TEXT NOT NULL,
                `local_owner_id` TEXT NOT NULL,
                `selected_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`singleton_id`),
                FOREIGN KEY(`local_owner_id`, `installation_id`)
                    REFERENCES `local_owner`(`local_owner_id`, `installation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
    }

    private fun restoreVersionTwoRows(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO `local_capture` SELECT * FROM `_m2_capture_backup`")
        db.execSQL("INSERT INTO `local_event_revision` SELECT * FROM `_m2_revision_backup`")
        db.execSQL("INSERT INTO `local_revision_parent` SELECT * FROM `_m2_parent_backup`")
        db.execSQL(
            """
            INSERT INTO `local_event_head`(
                event_id,
                current_revision_id,
                server_current_revision_id,
                server_observed_sequence,
                updated_at_utc
            )
            SELECT
                h.event_id,
                h.current_revision_id,
                h.server_current_revision_id,
                CASE
                    WHEN h.server_current_revision_id IS NULL THEN NULL
                    ELSE (
                        SELECT r.server_sequence
                        FROM local_event_revision AS r
                        WHERE r.event_id = h.event_id
                          AND r.revision_id = h.server_current_revision_id
                    )
                END,
                h.updated_at_utc
            FROM `_m2_head_backup` AS h
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `sync_outbox`(
                local_sequence,
                operation_id,
                capture_id,
                installation_id,
                local_owner_id,
                operation_kind,
                event_id,
                revision_id,
                base_revision_id,
                schema_version,
                operation_jcs,
                operation_content_sha256,
                command_fingerprint_sha256,
                wire_state,
                wire_protocol_version,
                wire_operation_material_jcs,
                wire_operation_content_sha256,
                wire_materialized_at_utc,
                active_batch_id,
                last_result_batch_id,
                last_result_code,
                last_result_retryable,
                last_result_current_revision_id,
                last_result_details_jcs,
                created_at_utc,
                created_at_epoch_ms,
                state,
                attempt_count,
                next_attempt_at_epoch_ms,
                last_attempt_at_epoch_ms,
                server_sequence,
                acked_at_utc,
                last_error_code
            )
            SELECT
                local_sequence,
                operation_id,
                capture_id,
                installation_id,
                local_owner_id,
                operation_kind,
                event_id,
                revision_id,
                base_revision_id,
                schema_version,
                operation_jcs,
                operation_content_sha256,
                command_fingerprint_sha256,
                CASE
                    WHEN schema_version = '4.0.0'
                     AND operation_kind = 'append_event_revision'
                     AND EXISTS (
                        SELECT 1
                        FROM local_capture AS c
                        JOIN local_event_revision AS r
                          ON r.capture_id = c.capture_id
                         AND r.operation_id = c.operation_id
                        JOIN local_life_event AS e ON e.event_id = r.event_id
                        WHERE c.capture_id = `_m2_outbox_backup`.capture_id
                          AND c.operation_id = `_m2_outbox_backup`.operation_id
                          AND r.event_id = `_m2_outbox_backup`.event_id
                          AND r.revision_id = `_m2_outbox_backup`.revision_id
                          AND c.schema_version = '4.0.0'
                          AND r.schema_version = '4.0.0'
                          AND e.kind = 'note'
                     )
                    THEN 'needs_materialization'
                    ELSE 'blocked_legacy_schema'
                END,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                NULL,
                created_at_utc,
                created_at_epoch_ms,
                state,
                attempt_count,
                next_attempt_at_epoch_ms,
                last_attempt_at_epoch_ms,
                server_sequence,
                acked_at_utc,
                last_error_code
            FROM `_m2_outbox_backup`
            ORDER BY local_sequence
            """.trimIndent(),
        )
        restoreOutboxSequence(db)
    }

    private fun restoreOutboxSequence(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE sqlite_sequence
            SET seq = MAX(
                seq,
                COALESCE(
                    (SELECT MAX(seq) FROM `_m2_outbox_sequence_backup`),
                    0
                )
            )
            WHERE name = 'sync_outbox'
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence(name, seq)
            SELECT
                'sync_outbox',
                (SELECT MAX(seq) FROM `_m2_outbox_sequence_backup`)
            WHERE NOT EXISTS (
                SELECT 1 FROM sqlite_sequence WHERE name = 'sync_outbox'
            )
              AND (SELECT MAX(seq) FROM `_m2_outbox_sequence_backup`) IS NOT NULL
            """.trimIndent(),
        )
    }

    /**
     * SQLite resolves composite parent keys through UNIQUE indices. These must
     * exist before any child rows are restored, not merely by migration end.
     */
    private fun createVersionThreeRequiredParentIndices(db: SupportSQLiteDatabase) {
        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_capture_capture_id_operation_id` ON `local_capture` (`capture_id`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_capture_capture_id_operation_id_local_owner_id_installation_id` ON `local_capture` (`capture_id`, `operation_id`, `local_owner_id`, `installation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_id` ON `local_event_revision` (`event_id`, `revision_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_id_capture_id_operation_id` ON `local_event_revision` (`event_id`, `revision_id`, `capture_id`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_id_capture_id_operation_id_server_sequence` ON `local_event_revision` (`event_id`, `revision_id`, `capture_id`, `operation_id`, `server_sequence`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_local_sequence_operation_id` ON `sync_outbox` (`local_sequence`, `operation_id`)",
        ).forEach(db::execSQL)
    }

    private fun createVersionThreeSyncTables(db: SupportSQLiteDatabase) {
        val createStatements = listOf(
            """
            CREATE TABLE IF NOT EXISTS `sync_auth_state` (
                `singleton_id` INTEGER NOT NULL,
                `credential_epoch_id` TEXT NOT NULL,
                `installation_id` TEXT NOT NULL,
                `local_owner_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `person_id` TEXT NOT NULL,
                `token_type` TEXT NOT NULL,
                `refresh_token_ciphertext` BLOB,
                `refresh_token_nonce` BLOB,
                `refresh_token_key_alias` TEXT,
                `refresh_token_key_generation` INTEGER,
                `refresh_token_aad_version` INTEGER,
                `access_expires_at_utc` TEXT NOT NULL,
                `access_expires_at_epoch_ms` INTEGER NOT NULL,
                `refresh_expires_at_utc` TEXT NOT NULL,
                `refresh_expires_at_epoch_ms` INTEGER NOT NULL,
                `family_expires_at_utc` TEXT NOT NULL,
                `family_expires_at_epoch_ms` INTEGER NOT NULL,
                `generation` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `bootstrap_required` INTEGER NOT NULL,
                `installed_at_utc` TEXT NOT NULL,
                `updated_at_utc` TEXT NOT NULL,
                `failure_code` TEXT,
                PRIMARY KEY(`singleton_id`),
                FOREIGN KEY(`local_owner_id`, `installation_id`)
                    REFERENCES `local_owner`(`local_owner_id`, `installation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_auth_attempt` (
                `request_id` TEXT NOT NULL,
                `endpoint_id` TEXT NOT NULL,
                `installation_id` TEXT NOT NULL,
                `local_owner_id` TEXT NOT NULL,
                `credential_epoch_id` TEXT,
                `expected_device_id` TEXT,
                `expected_generation` INTEGER,
                `state` TEXT NOT NULL,
                `created_at_utc` TEXT NOT NULL,
                `updated_at_utc` TEXT NOT NULL,
                `last_error_code` TEXT,
                PRIMARY KEY(`request_id`),
                FOREIGN KEY(`local_owner_id`, `installation_id`)
                    REFERENCES `local_owner`(`local_owner_id`, `installation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_auth_token_fingerprint` (
                `credential_epoch_id` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `token_kind` TEXT NOT NULL,
                `token_hmac` BLOB NOT NULL,
                `hmac_key_generation` INTEGER NOT NULL,
                `created_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`credential_epoch_id`, `generation`, `token_kind`)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_http_request` (
                `endpoint_id` TEXT NOT NULL,
                `request_identity` TEXT NOT NULL,
                `protocol_version` TEXT NOT NULL,
                `credential_epoch_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `idempotency_key` TEXT,
                `body_storage_kind` TEXT NOT NULL,
                `raw_request_body` BLOB,
                `sealed_body_ciphertext` BLOB,
                `sealed_body_nonce` BLOB,
                `sealed_body_key_alias` TEXT,
                `sealed_body_key_generation` INTEGER,
                `sealed_body_aad_version` INTEGER,
                `request_body_octet_count` INTEGER NOT NULL,
                `raw_body_hmac` BLOB NOT NULL,
                `hmac_key_generation` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `attempt_count` INTEGER NOT NULL DEFAULT 0,
                `attempt_budget` INTEGER NOT NULL,
                `deadline_at_epoch_ms` INTEGER NOT NULL,
                `next_attempt_at_epoch_ms` INTEGER,
                `last_attempt_at_epoch_ms` INTEGER,
                `lease_expires_at_epoch_ms` INTEGER,
                `active_attempt_id` TEXT,
                `access_generation_used` INTEGER,
                `refresh_attempted` INTEGER NOT NULL DEFAULT 0,
                `original_retry_count` INTEGER NOT NULL DEFAULT 0,
                `terminal_http_status` INTEGER,
                `exact_response_body` BLOB,
                `response_sha256` TEXT,
                `terminal_at_utc` TEXT,
                `terminal_error_code` TEXT,
                `created_at_utc` TEXT NOT NULL,
                `updated_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`endpoint_id`, `request_identity`)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_push_batch` (
                `batch_id` TEXT NOT NULL,
                `endpoint_id` TEXT NOT NULL,
                `request_identity` TEXT NOT NULL,
                `batch_content_sha256` TEXT NOT NULL,
                `operation_count` INTEGER NOT NULL,
                `created_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`batch_id`),
                FOREIGN KEY(`endpoint_id`, `request_identity`)
                    REFERENCES `sync_http_request`(`endpoint_id`, `request_identity`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_push_batch_item` (
                `batch_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `local_sequence` INTEGER NOT NULL,
                `operation_id` TEXT NOT NULL,
                `wire_operation_content_sha256` TEXT NOT NULL,
                PRIMARY KEY(`batch_id`, `ordinal`),
                FOREIGN KEY(`batch_id`) REFERENCES `sync_push_batch`(`batch_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`local_sequence`, `operation_id`)
                    REFERENCES `sync_outbox`(`local_sequence`, `operation_id`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_server_change` (
                `server_sequence` INTEGER NOT NULL,
                `operation_id` TEXT NOT NULL,
                `operation_content_sha256` TEXT NOT NULL,
                `result_code` TEXT NOT NULL,
                `capture_id` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `revision_id` TEXT NOT NULL,
                `current_revision_id` TEXT NOT NULL,
                `committed_at_utc` TEXT NOT NULL,
                `first_endpoint_id` TEXT NOT NULL,
                `first_request_identity` TEXT NOT NULL,
                `verified_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`server_sequence`),
                FOREIGN KEY(
                    `event_id`,
                    `revision_id`,
                    `capture_id`,
                    `operation_id`,
                    `server_sequence`
                ) REFERENCES `local_event_revision`(
                    `event_id`,
                    `revision_id`,
                    `capture_id`,
                    `operation_id`,
                    `server_sequence`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`first_endpoint_id`, `first_request_identity`)
                    REFERENCES `sync_http_request`(`endpoint_id`, `request_identity`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_stream_state` (
                `singleton_id` INTEGER NOT NULL,
                `credential_epoch_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `phase` TEXT NOT NULL,
                `bootstrap_required` INTEGER NOT NULL,
                `applied_cursor` TEXT,
                `last_applied_server_sequence` INTEGER NOT NULL DEFAULT 0,
                `high_watermark_hint` TEXT,
                `integrity_error_code` TEXT,
                `updated_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`singleton_id`)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_bootstrap_session` (
                `bootstrap_id` TEXT NOT NULL,
                `credential_epoch_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `active_slot` INTEGER,
                `snapshot_id` TEXT,
                `next_page_cursor` TEXT,
                `candidate_incremental_cursor` TEXT,
                `next_page_index` INTEGER NOT NULL,
                `last_staged_server_sequence` INTEGER,
                `staged_page_count` INTEGER NOT NULL DEFAULT 0,
                `staged_body_bytes` INTEGER NOT NULL DEFAULT 0,
                `created_at_utc` TEXT NOT NULL,
                `updated_at_utc` TEXT NOT NULL,
                PRIMARY KEY(`bootstrap_id`)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_page_receipt` (
                `page_id` TEXT NOT NULL,
                `endpoint_id` TEXT NOT NULL,
                `request_identity` TEXT NOT NULL,
                `bootstrap_id` TEXT,
                `page_index` INTEGER NOT NULL,
                `snapshot_id` TEXT,
                `from_cursor` TEXT,
                `next_cursor` TEXT,
                `incremental_cursor` TEXT,
                `page_sha256` TEXT NOT NULL,
                `change_count` INTEGER NOT NULL,
                `complete_or_has_more` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `first_server_sequence` INTEGER,
                `last_server_sequence` INTEGER,
                `received_at_utc` TEXT NOT NULL,
                `applied_at_utc` TEXT,
                PRIMARY KEY(`page_id`),
                FOREIGN KEY(`endpoint_id`, `request_identity`)
                    REFERENCES `sync_http_request`(`endpoint_id`, `request_identity`)
                    ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(`bootstrap_id`)
                    REFERENCES `sync_bootstrap_session`(`bootstrap_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `sync_staged_change` (
                `bootstrap_id` TEXT NOT NULL,
                `server_sequence` INTEGER NOT NULL,
                `page_id` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `operation_content_sha256` TEXT NOT NULL,
                `capture_id` TEXT NOT NULL,
                `event_id` TEXT NOT NULL,
                `revision_id` TEXT NOT NULL,
                `current_revision_id` TEXT NOT NULL,
                `result_code` TEXT NOT NULL,
                `committed_at_utc` TEXT NOT NULL,
                `change_jcs` BLOB NOT NULL,
                PRIMARY KEY(`bootstrap_id`, `server_sequence`),
                FOREIGN KEY(`bootstrap_id`)
                    REFERENCES `sync_bootstrap_session`(`bootstrap_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`page_id`, `bootstrap_id`)
                    REFERENCES `sync_page_receipt`(`page_id`, `bootstrap_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createStatements.forEach(db::execSQL)
    }

    private fun createVersionThreeIndices(db: SupportSQLiteDatabase) {
        val indexStatements = listOf(
            "CREATE INDEX IF NOT EXISTS `index_local_owner_server_person_id` ON `local_owner` (`server_person_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_capture_operation_id` ON `local_capture` (`operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_capture_capture_id_operation_id` ON `local_capture` (`capture_id`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_capture_capture_id_operation_id_local_owner_id_installation_id` ON `local_capture` (`capture_id`, `operation_id`, `local_owner_id`, `installation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_capture_local_owner_id_installation_id` ON `local_capture` (`local_owner_id`, `installation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_capture_recorded_at_epoch_ms` ON `local_capture` (`recorded_at_epoch_ms`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_id` ON `local_event_revision` (`event_id`, `revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_event_revision_capture_id_operation_id` ON `local_event_revision` (`capture_id`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_operation_id` ON `local_event_revision` (`operation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_no` ON `local_event_revision` (`event_id`, `revision_no`)",
            "CREATE INDEX IF NOT EXISTS `index_local_event_revision_local_date_effective_start_epoch_ms` ON `local_event_revision` (`local_date`, `effective_start_epoch_ms`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_id_capture_id_operation_id` ON `local_event_revision` (`event_id`, `revision_id`, `capture_id`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_server_sequence` ON `local_event_revision` (`server_sequence`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_revision_event_id_revision_id_capture_id_operation_id_server_sequence` ON `local_event_revision` (`event_id`, `revision_id`, `capture_id`, `operation_id`, `server_sequence`)",
            "CREATE INDEX IF NOT EXISTS `index_local_revision_parent_event_id_child_revision_id` ON `local_revision_parent` (`event_id`, `child_revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_revision_parent_event_id_parent_revision_id` ON `local_revision_parent` (`event_id`, `parent_revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_event_head_event_id_current_revision_id` ON `local_event_head` (`event_id`, `current_revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_event_head_event_id_server_current_revision_id` ON `local_event_head` (`event_id`, `server_current_revision_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_head_current_revision_id` ON `local_event_head` (`current_revision_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_event_head_server_current_revision_id` ON `local_event_head` (`server_current_revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_event_head_server_observed_sequence` ON `local_event_head` (`server_observed_sequence`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_operation_id` ON `sync_outbox` (`operation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_capture_id` ON `sync_outbox` (`capture_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_local_owner_id_installation_id` ON `sync_outbox` (`local_owner_id`, `installation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_event_id_revision_id` ON `sync_outbox` (`event_id`, `revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_event_id_base_revision_id` ON `sync_outbox` (`event_id`, `base_revision_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_revision_id` ON `sync_outbox` (`revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_state_next_attempt_at_epoch_ms` ON `sync_outbox` (`state`, `next_attempt_at_epoch_ms`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_local_sequence_operation_id` ON `sync_outbox` (`local_sequence`, `operation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_wire_state_local_sequence` ON `sync_outbox` (`wire_state`, `local_sequence`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_active_batch_id` ON `sync_outbox` (`active_batch_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_capture_id_operation_id_local_owner_id_installation_id` ON `sync_outbox` (`capture_id`, `operation_id`, `local_owner_id`, `installation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_event_id_revision_id_capture_id_operation_id` ON `sync_outbox` (`event_id`, `revision_id`, `capture_id`, `operation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_local_identity_state_local_owner_id_installation_id` ON `local_identity_state` (`local_owner_id`, `installation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_identity_state_installation_id_local_owner_id` ON `local_identity_state` (`installation_id`, `local_owner_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_auth_state_credential_epoch_id` ON `sync_auth_state` (`credential_epoch_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_auth_state_local_owner_id_installation_id` ON `sync_auth_state` (`local_owner_id`, `installation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_auth_state_device_id` ON `sync_auth_state` (`device_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_auth_state_state` ON `sync_auth_state` (`state`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_auth_attempt_local_owner_id_installation_id` ON `sync_auth_attempt` (`local_owner_id`, `installation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_auth_attempt_endpoint_id_state` ON `sync_auth_attempt` (`endpoint_id`, `state`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_auth_attempt_credential_epoch_id_state` ON `sync_auth_attempt` (`credential_epoch_id`, `state`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_auth_token_fingerprint_credential_epoch_id_token_hmac` ON `sync_auth_token_fingerprint` (`credential_epoch_id`, `token_hmac`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_http_request_idempotency_key` ON `sync_http_request` (`idempotency_key`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_http_request_state_next_attempt_at_epoch_ms` ON `sync_http_request` (`state`, `next_attempt_at_epoch_ms`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_http_request_credential_epoch_id_state` ON `sync_http_request` (`credential_epoch_id`, `state`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_http_request_active_attempt_id` ON `sync_http_request` (`active_attempt_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_push_batch_endpoint_id_request_identity` ON `sync_push_batch` (`endpoint_id`, `request_identity`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_push_batch_item_batch_id_local_sequence` ON `sync_push_batch_item` (`batch_id`, `local_sequence`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_push_batch_item_batch_id_operation_id` ON `sync_push_batch_item` (`batch_id`, `operation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_push_batch_item_local_sequence_operation_id` ON `sync_push_batch_item` (`local_sequence`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_server_change_operation_id` ON `sync_server_change` (`operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_server_change_capture_id` ON `sync_server_change` (`capture_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_server_change_revision_id` ON `sync_server_change` (`revision_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_server_change_event_id_revision_id_capture_id_operation_id_server_sequence` ON `sync_server_change` (`event_id`, `revision_id`, `capture_id`, `operation_id`, `server_sequence`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_server_change_first_endpoint_id_first_request_identity` ON `sync_server_change` (`first_endpoint_id`, `first_request_identity`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_stream_state_credential_epoch_id_device_id` ON `sync_stream_state` (`credential_epoch_id`, `device_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_stream_state_phase` ON `sync_stream_state` (`phase`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_bootstrap_session_state` ON `sync_bootstrap_session` (`state`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_bootstrap_session_credential_epoch_id_device_id_state` ON `sync_bootstrap_session` (`credential_epoch_id`, `device_id`, `state`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_bootstrap_session_snapshot_id` ON `sync_bootstrap_session` (`snapshot_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_bootstrap_session_active_slot` ON `sync_bootstrap_session` (`active_slot`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_page_receipt_endpoint_id_request_identity` ON `sync_page_receipt` (`endpoint_id`, `request_identity`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_page_receipt_bootstrap_id_page_index` ON `sync_page_receipt` (`bootstrap_id`, `page_index`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_page_receipt_page_id_bootstrap_id` ON `sync_page_receipt` (`page_id`, `bootstrap_id`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_page_receipt_state` ON `sync_page_receipt` (`state`)",
            "CREATE INDEX IF NOT EXISTS `index_sync_staged_change_page_id_bootstrap_id` ON `sync_staged_change` (`page_id`, `bootstrap_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_staged_change_bootstrap_id_operation_id` ON `sync_staged_change` (`bootstrap_id`, `operation_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_staged_change_bootstrap_id_capture_id` ON `sync_staged_change` (`bootstrap_id`, `capture_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_staged_change_bootstrap_id_revision_id` ON `sync_staged_change` (`bootstrap_id`, `revision_id`)",
        )
        indexStatements.forEach(db::execSQL)
    }

    private fun seedCurrentIdentity(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO `local_identity_state`(
                singleton_id,
                installation_id,
                local_owner_id,
                selected_at_utc
            )
            SELECT 1, installation_id, local_owner_id, created_at_utc
            FROM local_owner
            WHERE (SELECT COUNT(*) FROM local_owner) = 1
            """.trimIndent(),
        )
    }

    private fun dropMigrationSnapshots(db: SupportSQLiteDatabase) {
        listOf(
            "_m2_outbox_sequence_backup",
            "_m2_outbox_backup",
            "_m2_head_backup",
            "_m2_parent_backup",
            "_m2_revision_backup",
            "_m2_capture_backup",
        ).forEach { table ->
            db.execSQL("DROP TABLE `$table`")
        }
    }

    private fun requireNoRows(
        db: SupportSQLiteDatabase,
        sql: String,
        message: String,
    ) {
        db.query(sql).use { cursor ->
            check(!cursor.moveToFirst()) { message }
        }
    }
}
