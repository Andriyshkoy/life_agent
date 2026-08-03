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
            allowedPaths = setOf(
                PROTECTED_STORE,
                PROTECTED_RESPONSE_STORE,
                REQUEST_PROTECTION,
            ),
        )
        assertOnlyAllowedMainCallSites(
            token = "loadVerified(",
            allowedPaths = setOf(
                PROTECTED_STORE,
                PROTECTED_RESPONSE_STORE,
                REQUEST_PROTECTION,
            ),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bBootstrapIntentPersistence\b"""),
            allowedPaths = setOf(
                PROTECTED_STORE,
                PROTECTED_RESPONSE_STORE,
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
            allowedPaths = setOf(SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitPullPage\b"""),
            allowedPaths = setOf(SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitCursorInvalid\b"""),
            allowedPaths = setOf(SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bhandleTrustedSyncUnauthorized\b"""),
            allowedPaths = setOf(AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitRevokeTerminal\b"""),
            allowedPaths = setOf(AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bquarantineRevokeIntegrity\b"""),
            allowedPaths = setOf(AUTH_STORE),
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

    @Test
    fun httpsTransportsStayBehindTheSinglePinnedProductionFactory() {
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bokhttp3\."""),
            allowedPaths = HTTPS_TRANSPORT_FILES,
        )
        assertOnlyAllowedMainCallSites(
            token = "newCall(",
            allowedPaths = HTTPS_CALL_OWNERS,
        )
        assertOnlyAllowedMainCallSites(
            token = "consumeBody(",
            allowedPaths = HTTPS_CALL_OWNERS + setOf(
                REQUEST_PROTECTION,
                PROTECTED_RESPONSE_STORE,
            ),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bExactHttpsTransport\b"""),
            allowedPaths = EXACT_HTTPS_FILES,
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex(
                """\bExactHttps(?:Outcome|RawResponse|""" +
                    """NetworkFailure(?:Kind)?|ProtocolFailure(?:Kind)?)\b""",
            ),
            allowedPaths = setOf(EXACT_HTTPS_TRANSPORT, PROTECTED_RESPONSE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bProductionM2HttpsTransportFactory\b"""),
            allowedPaths = setOf(EXACT_HTTPS_CONFIGURATION),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bM2HttpsConfiguration\b"""),
            allowedPaths = HTTPS_TRANSPORT_FILES,
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bOneShotAuthHttpsTransport\b"""),
            allowedPaths = setOf(
                EXACT_HTTPS_CONFIGURATION,
                ONE_SHOT_AUTH_HTTPS_TRANSPORT,
            ),
        )
        assertOnlyAllowedMainCallSites(
            token = "OneShotAuthHttpsTransport(",
            allowedPaths = setOf(EXACT_HTTPS_CONFIGURATION),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""BuildConfig\.LIFE_AGENT_API_(?:ORIGIN|SPKI_PINS)"""),
            allowedPaths = setOf(EXACT_HTTPS_CONFIGURATION),
        )

        val factorySource = File(mainSourceRoot(), EXACT_HTTPS_CONFIGURATION).readText()
        assertSourceTokenCount(
            source = factorySource,
            token = "createPinnedTransport { configuration, client ->",
            expectedCount = 2,
        )
        listOf(
            "private inline fun <T> createPinnedTransport(",
            "private fun buildPinnedClient(",
            "OkHttpClient.Builder()",
            ".certificatePinner(configuration.certificatePinner())",
            ".connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))",
            ".followRedirects(false)",
            ".followSslRedirects(false)",
            ".retryOnConnectionFailure(false)",
            ".fastFallback(false)",
            ".authenticator(Authenticator.NONE)",
            ".proxyAuthenticator(Authenticator.NONE)",
            ".cookieJar(CookieJar.NO_COOKIES)",
            ".cache(null)",
            ".eventListener(EventListener.NONE)",
            ".connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
            ".writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
            ".readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
            ".callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
            "check(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())",
        ).forEach { token ->
            assertSourceTokenCount(factorySource, token, expectedCount = 1)
        }
        listOf(
            ".addInterceptor(",
            ".addNetworkInterceptor(",
            ".sslSocketFactory(",
            ".hostnameVerifier(",
        ).forEach { forbidden ->
            assertFalse(
                "Pinned production factory contains forbidden policy $forbidden",
                factorySource.contains(forbidden),
            )
        }

        val transportSource = HTTPS_TRANSPORT_FILES.joinToString("\n") { path ->
            File(mainSourceRoot(), path).readText()
        }
        listOf(
            "WireResponseCodec",
            "SyncPersistenceStore",
            "SyncAuthPersistenceStore",
            "SyncRequestPersistenceStore",
            "WorkManager",
            "CoroutineWorker",
        ).forEach { forbidden ->
            assertFalse("Transport references forbidden $forbidden", transportSource.contains(forbidden))
        }

        val appGraph = File(mainSourceRoot(), APP_CONTAINER).readText()
        assertFalse(appGraph.contains("ExactHttps"))

        val allMainSource = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }
        assertFalse(
            "Tracked production source contains a concrete HTTPS coordinate",
            Regex("""https://[A-Za-z0-9]""").containsMatchIn(allMainSource),
        )
        assertFalse(
            "Tracked production source contains a concrete SPKI pin",
            Regex("""sha256/[A-Za-z0-9+/]{43}=""").containsMatchIn(allMainSource),
        )
    }

    @Test
    fun durableResponseReductionStaysBehindOneClosedProductionDecoder() {
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bWireResponseCodec\b"""),
            allowedPaths = setOf(PROTECTED_RESPONSE_STORE, WIRE_RESPONSE_CODEC),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex(
                """\bcommit(?:PushResponse|BootstrapPage|PullPage|""" +
                    """BootstrapCursorExpired|CursorInvalid)InCurrentTransaction\b""",
            ),
            allowedPaths = setOf(PROTECTED_RESPONSE_STORE, SYNC_PERSISTENCE_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitBootstrapRequiredInCurrentTransaction\b"""),
            allowedPaths = setOf(PROTECTED_RESPONSE_STORE, SYNC_REQUEST_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bhandleTrustedSyncUnauthorizedInCurrentTransaction\b"""),
            allowedPaths = setOf(PROTECTED_RESPONSE_STORE, AUTH_STORE),
        )
        assertOnlyAllowedMainMatches(
            pattern = Regex("""\bcommitRevokeTerminalInCurrentTransaction\b"""),
            allowedPaths = setOf(PROTECTED_RESPONSE_STORE, AUTH_STORE),
        )

        val source = File(mainSourceRoot(), PROTECTED_RESPONSE_STORE).readText()
        assertTrue(source.contains("internal sealed interface ProtectedFreshResponseDecoder"))
        assertFalse(source.contains("suspend fun decode(input: ProtectedFreshResponseInput)"))
        assertTrue(
            Regex(
                """\b(?:object|class)\s+\w+\s*:\s*ProtectedFreshResponseDecoder\b""",
            ).findAll(source).count() == 1,
        )
    }

    private fun assertOnlyAllowedMainCallSites(
        token: String,
        allowedPaths: Set<String>,
    ) = assertOnlyAllowedMainMatches(
        pattern = Regex(Regex.escape(token)),
        allowedPaths = allowedPaths,
    )

    private fun assertSourceTokenCount(
        source: String,
        token: String,
        expectedCount: Int,
    ) {
        val actualCount = Regex(Regex.escape(token)).findAll(source).count()
        assertTrue(
            "Expected $expectedCount occurrences of $token, found $actualCount",
            actualCount == expectedCount,
        )
    }

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
        const val PROTECTED_RESPONSE_STORE =
            "java/$DB_PACKAGE/ProtectedSyncResponseStore.kt"
        const val SYNC_PERSISTENCE_STORE = "java/$DB_PACKAGE/SyncPersistenceStore.kt"
        const val SYNC_REQUEST_STORE = "java/$DB_PACKAGE/SyncRequestPersistenceStore.kt"
        const val TRANSPORT_DAO = "java/$DB_PACKAGE/dao/SyncTransportDao.kt"
        const val WIRE_RESPONSE_CODEC =
            "java/ru/andriyshkoy/lifeagent/data/sync/wire/WireResponseCodec.kt"
        const val REQUEST_PROTECTION =
            "java/$SECURITY_PACKAGE/DurableSyncRequestProtection.kt"
        const val APP_CONTAINER = "java/ru/andriyshkoy/lifeagent/AppContainer.kt"
        const val TRANSPORT_PACKAGE = "ru/andriyshkoy/lifeagent/data/sync/transport"
        const val EXACT_HTTPS_CONFIGURATION =
            "java/$TRANSPORT_PACKAGE/M2HttpsConfiguration.kt"
        const val EXACT_HTTPS_TRANSPORT =
            "java/$TRANSPORT_PACKAGE/ExactHttpsTransport.kt"
        const val ONE_SHOT_AUTH_HTTPS_TRANSPORT =
            "java/$TRANSPORT_PACKAGE/OneShotAuthHttpsTransport.kt"
        val EXACT_HTTPS_FILES = setOf(EXACT_HTTPS_CONFIGURATION, EXACT_HTTPS_TRANSPORT)
        val HTTPS_CALL_OWNERS = setOf(
            EXACT_HTTPS_TRANSPORT,
            ONE_SHOT_AUTH_HTTPS_TRANSPORT,
        )
        val HTTPS_TRANSPORT_FILES = EXACT_HTTPS_FILES + ONE_SHOT_AUTH_HTTPS_TRANSPORT
        val APPROVED_TRANSPORT_DAO_OWNERS = setOf(
            PROTECTED_STORE,
            PROTECTED_RESPONSE_STORE,
            AUTH_STORE,
            SYNC_PERSISTENCE_STORE,
            SYNC_REQUEST_STORE,
        )
    }
}
