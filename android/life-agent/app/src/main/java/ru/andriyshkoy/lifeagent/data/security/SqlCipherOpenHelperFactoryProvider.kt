package ru.andriyshkoy.lifeagent.data.security

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object SqlCipherOpenHelperFactoryProvider {
    fun create(
        key: SqlCipherKey,
        enableWriteAheadLogging: Boolean = true,
    ): SupportSQLiteOpenHelper.Factory = SupportOpenHelperFactory(
        key.bytesForOpenHelperFactory(),
        SqlCipherPrivacyHook,
        enableWriteAheadLogging,
    )
}
