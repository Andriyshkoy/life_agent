package ru.andriyshkoy.lifeagent.data.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkBoundaryArchitectureTest {
    @Test
    fun workManagerAndOpaqueExecutionStayInsideTheScaffoldingBoundary() {
        assertOnlyAllowedMainMatches(
            Regex("""\bWorkManager\b"""),
            setOf(
                SYNC_WORK_CONTRACT,
                SYNC_WORK_EXECUTION,
                SYNC_WORK_SCHEDULER,
                SYNC_WORKER,
            ),
        )
        assertOnlyAllowedMainMatches(
            Regex("""\bCoroutineWorker\b"""),
            setOf(SYNC_WORKER),
        )
        assertOnlyAllowedMainMatches(
            Regex("""\bData\.EMPTY\b"""),
            setOf(SYNC_WORK_CONTRACT),
        )
        assertOnlyAllowedMainMatches(
            Regex("""\bworkDataOf\b|\bData\.Builder\b|\.setInputData\("""),
            setOf(SYNC_WORK_CONTRACT),
        )

        val scheduler = File(mainSourceRoot(), SYNC_WORK_SCHEDULER).readText()
        assertEqualsCount(scheduler, "enqueueUniqueWork(", 1)
        assertEqualsCount(scheduler, "ExistingWorkPolicy.KEEP", 1)
        assertEqualsCount(scheduler, "ExistingWorkPolicy.APPEND_OR_REPLACE", 1)
        assertEqualsCount(scheduler, "SyncWorkContract.UNIQUE_WORK_NAME", 1)

        val worker = File(mainSourceRoot(), SYNC_WORKER).readText()
        listOf(
            "AppContainer",
            "BuildConfig",
            "ExactHttpsTransport",
            "OkHttpClient",
            "SyncTransportDao",
            "SyncHttpRequestEntity",
            "WipeableSecret",
            "requestBody",
            "accessToken",
            "refreshToken",
        ).forEach { forbidden ->
            assertFalse("Worker references forbidden $forbidden", worker.contains(forbidden))
        }

        val appContainer = File(mainSourceRoot(), APP_CONTAINER).readText()
        val application = File(mainSourceRoot(), APPLICATION).readText()
        listOf(appContainer, application).forEach { source ->
            assertFalse(source.contains("SyncWorkScheduler"))
            assertFalse(source.contains("SyncWorkExecutionPortProvider"))
            assertFalse(source.contains("LifeAgentSyncWorker"))
        }
    }

    private fun assertOnlyAllowedMainMatches(
        pattern: Regex,
        allowedPaths: Set<String>,
    ) {
        val root = mainSourceRoot()
        val disallowed = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot(allowedPaths::contains)
            .toList()
        assertTrue(
            "Unexpected main matches for ${pattern.pattern}: $disallowed",
            disallowed.isEmpty(),
        )
    }

    private fun assertEqualsCount(source: String, token: String, expected: Int) {
        val actual = Regex(Regex.escape(token)).findAll(source).count()
        assertTrue("Expected $expected occurrences of $token, found $actual", actual == expected)
    }

    private fun mainSourceRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .map { File(it, "app/src/main") }
            .firstOrNull(File::isDirectory)
            ?: error("Android main source root is unavailable")
    }

    private companion object {
        const val APP_CONTAINER = "java/ru/andriyshkoy/lifeagent/AppContainer.kt"
        const val APPLICATION = "java/ru/andriyshkoy/lifeagent/LifeAgentApplication.kt"
        const val WORK_PACKAGE = "java/ru/andriyshkoy/lifeagent/data/sync/work"
        const val SYNC_WORK_CONTRACT = "$WORK_PACKAGE/SyncWorkContract.kt"
        const val SYNC_WORK_EXECUTION = "$WORK_PACKAGE/SyncWorkExecution.kt"
        const val SYNC_WORK_SCHEDULER = "$WORK_PACKAGE/SyncWorkScheduler.kt"
        const val SYNC_WORKER = "$WORK_PACKAGE/LifeAgentSyncWorker.kt"
    }
}
