package ru.andriyshkoy.lifeagent.data.local.db

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.notes.domain.CorruptLocalNoteException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalIdentityStoreApi35Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database: LifeAgentDatabase = LifeAgentDatabaseFactory.createInMemory(context)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `clock-backed creation is exact and existing identity is returned unchanged`() = runTest {
        val installationId = UUID.fromString("00000000-0000-4000-8000-000000000101")
        val ownerId = UUID.fromString("00000000-0000-4000-8000-000000000102")
        val generated = ArrayDeque(listOf(installationId, ownerId))
        val createdAt = Instant.parse("2026-08-03T02:03:04.567Z")
        val store = LocalIdentityStore(
            database = database,
            uuidGenerator = UuidGenerator(generated::removeFirst),
            clock = Clock.fixed(createdAt, ZoneOffset.UTC),
        )

        val created = database.withTransaction {
            store.ensureIdentityInCurrentTransaction()
        }
        val replayed = database.withTransaction {
            store.ensureIdentityInCurrentTransaction(Instant.EPOCH)
        }

        assertEquals(installationId.toString(), created.installationId)
        assertEquals(ownerId.toString(), created.localOwnerId)
        assertEquals(created, replayed)
        assertTrue(generated.isEmpty())
        assertEquals(
            listOf(createdAt.toString(), createdAt.toString(), createdAt.toString()),
            identityTimestamps(),
        )
    }

    @Test
    fun `caller rollback removes every newly-created identity row`() = runTest {
        val generated = ArrayDeque(
            listOf(
                UUID.fromString("00000000-0000-4000-8000-000000000201"),
                UUID.fromString("00000000-0000-4000-8000-000000000202"),
            ),
        )
        val store = LocalIdentityStore(
            database = database,
            uuidGenerator = UuidGenerator(generated::removeFirst),
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                database.withTransaction {
                    store.ensureIdentityInCurrentTransaction(Instant.EPOCH)
                    error("force caller rollback")
                }
            }
        }

        assertNull(database.identityDao().findIdentity())
        assertEquals(0, database.identityDao().ownerCount())
        assertEquals(0, tableCount("local_installation"))
        assertEquals(0, tableCount("local_identity_state"))
    }

    @Test
    fun `creation outside caller transaction fails before every write`() = runTest {
        val generated = ArrayDeque(
            listOf(
                UUID.fromString("00000000-0000-4000-8000-000000000251"),
                UUID.fromString("00000000-0000-4000-8000-000000000252"),
            ),
        )
        val store = LocalIdentityStore(
            database = database,
            uuidGenerator = UuidGenerator(generated::removeFirst),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.ensureIdentityInCurrentTransaction(Instant.EPOCH)
            }
        }

        assertTrue(failure.message.orEmpty().contains("caller-owned Room transaction"))
        assertEquals(2, generated.size)
        assertNull(database.identityDao().findIdentity())
        assertEquals(0, database.identityDao().ownerCount())
        assertEquals(0, tableCount("local_installation"))
        assertEquals(0, tableCount("local_identity_state"))
    }

    @Test
    fun `missing marker with historical owner fails closed without replacement`() = runTest {
        val generated = ArrayDeque(
            listOf(
                UUID.fromString("00000000-0000-4000-8000-000000000301"),
                UUID.fromString("00000000-0000-4000-8000-000000000302"),
                UUID.fromString("00000000-0000-4000-8000-000000000303"),
                UUID.fromString("00000000-0000-4000-8000-000000000304"),
            ),
        )
        val store = LocalIdentityStore(
            database = database,
            uuidGenerator = UuidGenerator(generated::removeFirst),
        )
        database.withTransaction {
            store.ensureIdentityInCurrentTransaction(Instant.EPOCH)
        }
        database.openHelper.writableDatabase.execSQL("DELETE FROM local_identity_state")

        assertThrows(CorruptLocalNoteException::class.java) {
            kotlinx.coroutines.runBlocking {
                database.withTransaction {
                    store.ensureIdentityInCurrentTransaction(Instant.EPOCH.plusSeconds(1))
                }
            }
        }

        assertEquals(1, database.identityDao().ownerCount())
        assertEquals(1, tableCount("local_installation"))
        assertEquals(0, tableCount("local_identity_state"))
        assertEquals(2, generated.size)
    }

    private fun identityTimestamps(): List<String> {
        val databaseHandle = database.openHelper.readableDatabase
        val installation = databaseHandle.query(
            "SELECT created_at_utc FROM local_installation",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        val owner = databaseHandle.query(
            "SELECT created_at_utc FROM local_owner",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        val selected = databaseHandle.query(
            "SELECT selected_at_utc FROM local_identity_state",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        return listOf(installation, owner, selected)
    }

    private fun tableCount(tableName: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $tableName")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
}
