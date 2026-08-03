package ru.andriyshkoy.lifeagent.data.security

import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

/**
 * Disables SQLCipher core logging before the connection is keyed or handles
 * application data. SQLCipher invokes this hook for every pooled connection.
 */
internal object SqlCipherPrivacyHook : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) {
        check(
            connection.executeForString(
                SET_LOG_LEVEL_NONE,
                null,
                null,
            ) == NONE,
        ) {
            "SQLCipher core log level could not be disabled."
        }
        check(
            connection.executeForString(
                SET_LOG_SOURCE_NONE,
                null,
                null,
            ) == NONE,
        ) {
            "SQLCipher core log sources could not be disabled."
        }
    }

    override fun postKey(connection: SQLiteConnection) = Unit

    private const val NONE = "NONE"
    private const val SET_LOG_LEVEL_NONE = "PRAGMA cipher_log_level = NONE"
    private const val SET_LOG_SOURCE_NONE = "PRAGMA cipher_log_source = NONE"
}
