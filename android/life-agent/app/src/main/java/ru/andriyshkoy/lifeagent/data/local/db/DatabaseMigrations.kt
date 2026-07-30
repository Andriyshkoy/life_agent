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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
