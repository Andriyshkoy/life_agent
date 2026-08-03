package ru.andriyshkoy.lifeagent.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "sync_auth_token_fingerprint",
    primaryKeys = ["credential_epoch_id", "generation", "token_kind"],
    indices = [
        Index(
            value = ["credential_epoch_id", "token_hmac"],
            unique = true,
        ),
    ],
)
data class SyncAuthTokenFingerprintEntity(
    @ColumnInfo(name = "credential_epoch_id")
    val credentialEpochId: String,
    @ColumnInfo(name = "generation")
    val generation: Long,
    @ColumnInfo(name = "token_kind")
    val tokenKind: String,
    @ColumnInfo(name = "token_hmac", typeAffinity = ColumnInfo.BLOB)
    val tokenHmac: ByteArray,
    @ColumnInfo(name = "hmac_key_generation")
    val hmacKeyGeneration: Int,
    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,
)
