package ru.andriyshkoy.lifeagent.data.local.db

import ru.andriyshkoy.lifeagent.core.id.RandomUuidGenerator
import ru.andriyshkoy.lifeagent.core.id.UuidGenerator
import ru.andriyshkoy.lifeagent.data.local.db.dao.LocalIdentityRow
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalIdentityStateEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalInstallationEntity
import ru.andriyshkoy.lifeagent.data.local.db.entity.LocalOwnerEntity
import ru.andriyshkoy.lifeagent.notes.domain.CorruptLocalNoteException
import java.time.Clock
import java.time.Instant

/**
 * Owns creation and lookup of the one identity selected for new local writes.
 *
 * [ensureIdentityInCurrentTransaction] deliberately does not open a transaction. Its caller
 * must already own a Room transaction that also covers the operation needing the identity.
 * This keeps first-use identity creation atomic with enrollment or the first local mutation.
 */
internal class LocalIdentityStore(
    private val database: LifeAgentDatabase,
    private val uuidGenerator: UuidGenerator = RandomUuidGenerator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val identityDao = database.identityDao()

    /**
     * Returns the selected identity or creates it inside the caller's current Room transaction.
     *
     * The optional timestamp exists so a local mutation can retain its recorded-time semantics;
     * callers such as enrollment may use the injected UTC clock.
     */
    suspend fun ensureIdentityInCurrentTransaction(
        createdAt: Instant = clock.instant(),
    ): LocalIdentityRow {
        check(database.inTransaction()) {
            "Identity creation requires an active caller-owned Room transaction"
        }
        identityDao.findIdentity()?.let { return it }
        if (identityDao.ownerCount() != 0) {
            throw CorruptLocalNoteException(
                "Current identity marker is missing while historical owners exist",
            )
        }

        val installationId = uuidGenerator.next().toString()
        val ownerId = uuidGenerator.next().toString()
        val createdAtUtc = createdAt.toString()
        identityDao.insertInstallation(
            LocalInstallationEntity(
                installationId = installationId,
                createdAtUtc = createdAtUtc,
            ),
        )
        identityDao.insertOwner(
            LocalOwnerEntity(
                localOwnerId = ownerId,
                installationId = installationId,
                createdAtUtc = createdAtUtc,
            ),
        )
        identityDao.insertIdentityState(
            LocalIdentityStateEntity(
                installationId = installationId,
                localOwnerId = ownerId,
                selectedAtUtc = createdAtUtc,
            ),
        )
        return LocalIdentityRow(
            installationId = installationId,
            localOwnerId = ownerId,
            serverDeviceId = null,
            serverPersonId = null,
        )
    }

    suspend fun requireIdentity(): LocalIdentityRow =
        identityDao.findIdentity()
            ?: throw CorruptLocalNoteException("Local identity is missing")
}
