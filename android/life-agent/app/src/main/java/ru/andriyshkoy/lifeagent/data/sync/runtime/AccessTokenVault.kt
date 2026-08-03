package ru.andriyshkoy.lifeagent.data.sync.runtime

import java.util.LinkedHashMap
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.requireCanonicalUuid

/** Exact credential-family generation used to authorize one protected request. */
internal data class AccessTokenKey(
    val credentialEpochId: String,
    val accessGeneration: Long,
) {
    init {
        requireCanonicalUuid(credentialEpochId)
        require(accessGeneration > 0) { "Access generation must be positive" }
    }

    override fun toString(): String = "AccessTokenKey(redacted=true)"
}

/**
 * Process-local bearer-token storage. Nothing in this type has a persistence path.
 *
 * [replace] takes ownership of its secret immediately. [claim] returns a separate,
 * caller-owned copy that must be closed after the exact send attempt completes.
 */
internal class AccessTokenVault(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : AutoCloseable {
    private val lock = Any()
    private val entries = LinkedHashMap<AccessTokenKey, WipeableSecret>()
    private var closed = false

    init {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) {
            "Access token vault capacity must remain small"
        }
    }

    /**
     * Replaces the token for [key] and takes ownership of [ownedToken], including
     * when the vault rejects the write because it is already closed.
     */
    fun replace(key: AccessTokenKey, ownedToken: WipeableSecret) {
        synchronized(lock) {
            if (closed) {
                ownedToken.close()
                error("Access token vault is closed")
            }
            try {
                ownedToken.useBytes(::requireCanonicalAccessToken)
            } catch (error: Throwable) {
                ownedToken.close()
                throw error
            }

            entries.remove(key)?.close()
            entries[key] = ownedToken
            trimOldestEntries()
        }
    }

    /** Returns exact-generation send authority, or null when that authority is absent. */
    fun claim(key: AccessTokenKey): AccessTokenClaim? = synchronized(lock) {
        if (closed) {
            return@synchronized null
        }
        val storedToken = entries[key] ?: return@synchronized null
        AccessTokenClaim(
            key = key,
            bearerAccessToken = storedToken.useBytes(WipeableSecret::copyOf),
        )
    }

    fun revoke(key: AccessTokenKey): Boolean = synchronized(lock) {
        val removed = entries.remove(key) ?: return@synchronized false
        removed.close()
        true
    }

    fun revokeEpoch(credentialEpochId: String): Int {
        requireCanonicalUuid(credentialEpochId)
        return synchronized(lock) {
            var revoked = 0
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.credentialEpochId == credentialEpochId) {
                    iterator.remove()
                    entry.value.close()
                    revoked += 1
                }
            }
            revoked
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.values.forEach(WipeableSecret::close)
            entries.clear()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            entries.values.forEach(WipeableSecret::close)
            entries.clear()
            closed = true
        }
    }

    override fun toString(): String = "AccessTokenVault(redacted=true)"

    private fun trimOldestEntries() {
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            val oldest = iterator.next()
            iterator.remove()
            oldest.value.close()
        }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 2
        const val MAX_ALLOWED_ENTRIES = 4
    }
}

/** Caller-owned exact-generation authority. Closing it wipes its private token copy. */
internal class AccessTokenClaim internal constructor(
    val key: AccessTokenKey,
    val bearerAccessToken: WipeableSecret,
) : AutoCloseable {
    override fun close() = bearerAccessToken.close()

    override fun toString(): String = "AccessTokenClaim(redacted=true)"
}

private fun requireCanonicalAccessToken(bytes: ByteArray) {
    require(bytes.size == CANONICAL_ACCESS_TOKEN_OCTETS) {
        "Access token is not canonical"
    }
    require(
        bytes[0] == 'l'.code.toByte() &&
            bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() &&
            bytes[3] == '_'.code.toByte(),
    ) {
        "Access token is not canonical"
    }
    for (index in ACCESS_TOKEN_PREFIX_OCTETS until bytes.size) {
        require(isBase64UrlOctet(bytes[index])) { "Access token is not canonical" }
    }
    require(
        CANONICAL_BASE64URL_FINAL_CHARACTERS.contains(
            (bytes.last().toInt() and 0xff).toChar(),
        ),
    ) {
        "Access token is not canonical"
    }
}

private fun isBase64UrlOctet(value: Byte): Boolean =
    value in 'A'.code.toByte()..'Z'.code.toByte() ||
        value in 'a'.code.toByte()..'z'.code.toByte() ||
        value in '0'.code.toByte()..'9'.code.toByte() ||
        value == '-'.code.toByte() ||
        value == '_'.code.toByte()

private const val CANONICAL_ACCESS_TOKEN_OCTETS = 47
private const val ACCESS_TOKEN_PREFIX_OCTETS = 4
private const val CANONICAL_BASE64URL_FINAL_CHARACTERS = "AEIMQUYcgkosw048"
