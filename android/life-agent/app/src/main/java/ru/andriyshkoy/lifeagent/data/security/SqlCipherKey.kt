package ru.andriyshkoy.lifeagent.data.security

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-memory representation of a SQLCipher raw key.
 *
 * Keep this holder alive for as long as the corresponding database can open
 * connections. Closing it overwrites the shared bytes retained by SQLCipher's
 * open-helper factory.
 */
class SqlCipherKey private constructor(
    private val rawKeyBytes: ByteArray,
) : Closeable {
    private val closed = AtomicBoolean(false)

    internal fun bytesForOpenHelperFactory(): ByteArray {
        check(!closed.get()) { "SQLCipher key is already closed." }
        return rawKeyBytes
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            rawKeyBytes.fill(0)
        }
    }

    companion object {
        private const val DEK_SIZE_BYTES = 32
        private val HEX = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)

        internal fun fromDek(dek: ByteArray): SqlCipherKey {
            require(dek.size == DEK_SIZE_BYTES) {
                "SQLCipher DEK must contain exactly 32 bytes."
            }

            /*
             * SQLCipher's x'<64 hex characters>' form selects a 256-bit raw
             * database key instead of treating the input as a passphrase.
             */
            val rawKey = ByteArray(2 + (dek.size * 2) + 1)
            rawKey[0] = 'x'.code.toByte()
            rawKey[1] = '\''.code.toByte()
            dek.forEachIndexed { index, value ->
                val unsigned = value.toInt() and 0xff
                rawKey[2 + (index * 2)] = HEX[unsigned ushr 4]
                rawKey[3 + (index * 2)] = HEX[unsigned and 0x0f]
            }
            rawKey[rawKey.lastIndex] = '\''.code.toByte()
            return SqlCipherKey(rawKey)
        }
    }
}
