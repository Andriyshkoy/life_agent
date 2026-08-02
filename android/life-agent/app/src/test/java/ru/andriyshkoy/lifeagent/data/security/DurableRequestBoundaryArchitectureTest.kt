package ru.andriyshkoy.lifeagent.data.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableRequestBoundaryArchitectureTest {
    @Test
    fun durableCreationAndDispatchCallSitesStayBehindProtectedBoundary() {
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bsyncTransportDao\b"""),
            allowedPaths = APPROVED_TRANSPORT_DAO_OWNERS + LIFE_AGENT_DATABASE,
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bSyncTransportDao\b"""),
            allowedPaths = setOf(LIFE_AGENT_DATABASE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "insertRequest(",
            allowedPaths = setOf(
                PROTECTED_STORE,
                SYNC_PERSISTENCE_STORE,
                SYNC_REQUEST_STORE,
                TRANSPORT_DAO,
            ),
        )
        assertOnlyAllowedMainCallSites(
            token = "insertPushRequest(",
            allowedPaths = setOf(PROTECTED_STORE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "claimAttemptRow(",
            allowedPaths = setOf(PROTECTED_STORE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "claimAttempt(",
            allowedPaths = setOf(TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "findRunnableRequests(",
            allowedPaths = setOf(TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "findRunnableRequestRows(",
            allowedPaths = setOf(TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "findPotentiallyRunnableRequestRows(",
            allowedPaths = setOf(TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "claimRevokeAttempt(",
            allowedPaths = setOf(PROTECTED_STORE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "findWaitingRefreshRequests(",
            allowedPaths = setOf(AUTH_STORE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "findRequestsNeedingLocalTerminalization(",
            allowedPaths = setOf(SYNC_REQUEST_STORE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "findOpenBootstrapRequests(",
            allowedPaths = setOf(SYNC_REQUEST_STORE, TRANSPORT_DAO),
        )
        assertOnlyAllowedMainCallSites(
            token = "DurableSyncRequestProtector(",
            allowedPaths = setOf(PROTECTED_STORE, REQUEST_PROTECTION),
        )
        assertOnlyAllowedMainCallSites(
            token = "DurableSyncRequestVerifier(",
            allowedPaths = setOf(PROTECTED_STORE, REQUEST_PROTECTION),
        )
        assertOnlyAllowedMainCallSites(
            token = "loadVerified(",
            allowedPaths = setOf(PROTECTED_STORE, REQUEST_PROTECTION),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bBootstrapIntentPersistence\b"""),
            allowedPaths = setOf(
                PROTECTED_STORE,
                SYNC_PERSISTENCE_STORE,
                SYNC_REQUEST_STORE,
            ),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitEnrollmentSuccessState\b"""),
            allowedPaths = setOf(PROTECTED_STORE, AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitPushBootstrapRequired(?:WithProtectedIntent)?\b"""),
            allowedPaths = setOf(PROTECTED_STORE, SYNC_REQUEST_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\binstallOrRetainBootstrapIntent\b"""),
            allowedPaths = setOf(SYNC_PERSISTENCE_STORE, SYNC_REQUEST_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitBootstrapPage\b"""),
            allowedPaths = setOf(PROTECTED_STORE, SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex(
                """\bcommitBootstrapCursorExpired(?:WithProtectedReplacement)?\b""",
            ),
            allowedPaths = setOf(PROTECTED_STORE, SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitPushResponse\b"""),
            allowedPaths = setOf(PROTECTED_STORE, SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitPullPage\b"""),
            allowedPaths = setOf(PROTECTED_STORE, SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitCursorInvalid\b"""),
            allowedPaths = setOf(PROTECTED_STORE, SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bhandleTrustedSyncUnauthorized\b"""),
            allowedPaths = setOf(PROTECTED_STORE, AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitRevokeTerminal\b"""),
            allowedPaths = setOf(PROTECTED_STORE, AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bquarantineRevokeIntegrity\b"""),
            allowedPaths = setOf(PROTECTED_STORE, AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bpreflightFreshResponseRequestMetadata\b"""),
            allowedPaths = setOf(
                AUTH_STORE,
                SYNC_PERSISTENCE_STORE,
                SYNC_REQUEST_STORE,
            ),
        )
    }

    @Test
    fun authStoreCannotInsertCallerBuiltDurableRequests() {
        val source = File(mainSourceRoot(), AUTH_STORE).readText()

        assertFalse(source.contains("val bootstrapRequest: SyncHttpRequestEntity"))
        assertFalse(source.contains("suspend fun beginRevoke("))
        assertFalse(source.contains("transportDao.insertRequest("))
    }

    private fun assertOnlyAllowedMainCallSites(
        token: String,
        allowedPaths: Set<String>,
    ) = assertOnlyAllowedMainMatches(
        pattern = Regex(Regex.escape(token)),
        allowedPaths = allowedPaths,
    )

    private fun assertOnlyAllowedMainMatches(
        pattern: Regex,
        allowedPaths: Set<String>,
    ) {
        val root = mainSourceRoot()
        val disallowed = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { file -> pattern.containsMatchIn(file.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot(allowedPaths::contains)
            .toList()

        assertTrue(
            "Unexpected main matches for ${pattern.pattern}: $disallowed",
            disallowed.isEmpty(),
        )
    }

    private fun mainSourceRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .map { File(it, "app/src/main") }
            .firstOrNull(File::isDirectory)
            ?: error("Android main source root is unavailable")
    }

    private companion object {
        const val DB_PACKAGE = "ru/andriyshkoy/lifeagent/data/local/db"
        const val SECURITY_PACKAGE = "ru/andriyshkoy/lifeagent/data/security"
        const val AUTH_STORE = "java/$DB_PACKAGE/SyncAuthPersistenceStore.kt"
        const val LIFE_AGENT_DATABASE = "java/$DB_PACKAGE/LifeAgentDatabase.kt"
        const val PROTECTED_STORE = "java/$DB_PACKAGE/ProtectedSyncRequestStore.kt"
        const val SYNC_PERSISTENCE_STORE = "java/$DB_PACKAGE/SyncPersistenceStore.kt"
        const val SYNC_REQUEST_STORE = "java/$DB_PACKAGE/SyncRequestPersistenceStore.kt"
        const val TRANSPORT_DAO = "java/$DB_PACKAGE/dao/SyncTransportDao.kt"
        const val REQUEST_PROTECTION =
            "java/$SECURITY_PACKAGE/DurableSyncRequestProtection.kt"
        val APPROVED_TRANSPORT_DAO_OWNERS = setOf(
            PROTECTED_STORE,
            AUTH_STORE,
            SYNC_PERSISTENCE_STORE,
            SYNC_REQUEST_STORE,
        )
    }
}
