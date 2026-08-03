package ru.andriyshkoy.lifeagent.data.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSetupPrivacyBoundaryTest {
    @Test
    fun enrollmentCodeSurfaceHasNoDurableOrObservableRetentionPath() {
        val screen = source(SCREEN)
        val controller = source(CONTROLLER)
        val combined = "$screen\n$controller"

        assertTrue(screen.contains("PasswordVisualTransformation()"))
        assertTrue(screen.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(screen.contains("code = \"\""))
        assertTrue(controller.contains("ownedCode.copyToOwnedSecretAndWipe()"))
        assertTrue(controller.contains("withContext(NonCancellable)"))
        assertTrue(controller.contains("WipeableSecret"))
        assertTrue(controller.contains(") : ViewModel(), SyncSetupController"))
        assertFalse(screen.contains("DefaultSyncSetupController("))

        listOf(
            "rememberSaveable",
            "SavedStateHandle",
            "SharedPreferences",
            "DataStore",
            "android.util.Log",
            "Log.",
            "println(",
            "sync_auth_attempt",
            "sync_http_request",
        ).forEach { forbidden ->
            assertFalse("Sync setup contains forbidden $forbidden", combined.contains(forbidden))
        }
        assertFalse(controller.contains("replaceActiveDevice"))
    }

    @Test
    fun bodylessProjectionDoesNotReturnIdentifiersOrContent() {
        val projection = source(PROJECTION)
        val projectedFields = projection
            .substringAfter("internal data class SyncStatusProjectionRow(")
            .substringBefore(")\n\n@Dao")

        listOf(
            "requestId",
            "deviceId",
            "personId",
            "credentialEpochId",
            "cursor",
            "body",
            "payload",
            "errorCode",
            "token",
        ).forEach { forbidden ->
            assertFalse(
                "Bodyless projection exposes $forbidden",
                projectedFields.contains(forbidden, ignoreCase = true),
            )
        }
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), relativePath).readText()

    private fun mainSourceRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDirectory) { it.parentFile }
            .map { File(it, "app/src/main") }
            .firstOrNull(File::isDirectory)
            ?: error("Android main source root is unavailable")
    }

    private companion object {
        const val ROOT = "java/ru/andriyshkoy/lifeagent"
        const val SCREEN = "$ROOT/ui/screens/SyncSetupScreen.kt"
        const val CONTROLLER = "$ROOT/ui/sync/SyncSetupController.kt"
        const val PROJECTION = "$ROOT/data/local/db/dao/SyncStatusProjectionDao.kt"
    }
}
