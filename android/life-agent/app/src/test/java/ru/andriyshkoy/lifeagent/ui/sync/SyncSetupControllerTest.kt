package ru.andriyshkoy.lifeagent.ui.sync

import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.sync.status.EnrollmentAttemptStatus
import ru.andriyshkoy.lifeagent.data.sync.status.SyncBootstrapStatus
import ru.andriyshkoy.lifeagent.data.sync.status.SyncConnectionStatus
import ru.andriyshkoy.lifeagent.data.sync.status.SyncStatusReadModel
import ru.andriyshkoy.lifeagent.data.sync.status.SyncStatusSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSetupControllerTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `local snapshot opens code entry without durable state`() = runTest(dispatcher) {
        val fixture = fixture()
        advanceUntilIdle()

        assertTrue(fixture.controller.uiState.value is SyncSetupUiState.LocalOnly)

        fixture.controller.showCodeEntry()

        assertTrue(fixture.controller.uiState.value is SyncSetupUiState.CodeEntry)
    }

    @Test
    fun `owned code is wiped before the single non cancellable gateway call`() =
        runTest(dispatcher) {
            val enteredGateway = CompletableDeferred<Unit>()
            val releaseGateway = CompletableDeferred<Unit>()
            lateinit var input: CharArray
            var calls = 0
            var receivedCode: String? = null
            val fixture = fixture(
                enrollmentGateway = SyncEnrollmentGateway { secret ->
                    calls += 1
                    assertTrue(input.all { it == '\u0000' })
                    receivedCode = secret.useBytes {
                        String(it, StandardCharsets.US_ASCII)
                    }
                    enteredGateway.complete(Unit)
                    releaseGateway.await()
                    SyncEnrollmentGatewayResult.CONNECTED
                },
            )
            advanceUntilIdle()
            input = VALID_CODE.toCharArray()

            fixture.controller.submitEnrollment(input)
            runCurrent()
            enteredGateway.await()

            assertTrue(input.all { it == '\u0000' })
            assertEquals(VALID_CODE, receivedCode)
            assertEquals(1, calls)
            assertTrue(fixture.controller.uiState.value is SyncSetupUiState.Enrolling)

            fixture.controller.submitEnrollment(VALID_CODE.toCharArray())
            runCurrent()
            assertEquals(1, calls)
            assertTrue(fixture.controller.uiState.value is SyncSetupUiState.Enrolling)

            fixture.status.value = readySnapshot()
            releaseGateway.complete(Unit)
            advanceUntilIdle()

            assertTrue(fixture.controller.uiState.value is SyncSetupUiState.Ready)
        }

    @Test
    fun `invalid code is wiped and rejected before gateway`() = runTest(dispatcher) {
        var calls = 0
        val fixture = fixture(
            enrollmentGateway = SyncEnrollmentGateway {
                calls += 1
                SyncEnrollmentGatewayResult.CONNECTED
            },
        )
        advanceUntilIdle()
        val input = "AAAA-BBBB".toCharArray()

        fixture.controller.submitEnrollment(input)
        advanceUntilIdle()

        assertTrue(input.all { it == '\u0000' })
        assertEquals(0, calls)
        assertEquals(
            SyncSetupErrorReason.INVALID_CODE,
            (fixture.controller.uiState.value as SyncSetupUiState.Error).reason,
        )
    }

    @Test
    fun `rejected enrollment exposes only a closed user error`() = runTest(dispatcher) {
        val fixture = fixture(
            enrollmentGateway = SyncEnrollmentGateway {
                SyncEnrollmentGatewayResult.REJECTED
            },
        )
        advanceUntilIdle()

        fixture.controller.submitEnrollment(VALID_CODE.toCharArray())
        advanceUntilIdle()

        assertEquals(
            SyncSetupErrorReason.ENROLLMENT_REJECTED,
            (fixture.controller.uiState.value as SyncSetupUiState.Error).reason,
        )
    }

    @Test
    fun `manual action reports accepted request without claiming completion`() =
        runTest(dispatcher) {
            var enqueueCalls = 0
            val fixture = fixture(
                initial = readySnapshot(),
                manualGateway = ManualSyncEnqueueGateway {
                    enqueueCalls += 1
                    ManualSyncEnqueueResult.QUEUED
                },
            )
            advanceUntilIdle()
            val notice = async { fixture.controller.notices.first() }
            runCurrent()

            fixture.controller.enqueueNow()

            assertEquals(SyncSetupNotice.QUEUED, notice.await())
            assertEquals(1, enqueueCalls)
            assertTrue(fixture.controller.uiState.value is SyncSetupUiState.Ready)
        }

    private fun fixture(
        initial: SyncStatusSnapshot = localSnapshot(),
        enrollmentGateway: SyncEnrollmentGateway = SyncEnrollmentGateway {
            SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE
        },
        manualGateway: ManualSyncEnqueueGateway = ManualSyncEnqueueGateway {
            ManualSyncEnqueueResult.NOT_CONFIGURED
        },
    ): Fixture {
        val status = MutableStateFlow(initial)
        val controller = DefaultSyncSetupController(
            readModel = SyncStatusReadModel { status },
            enrollmentGateway = enrollmentGateway,
            manualSyncGateway = manualGateway,
        )
        return Fixture(controller, status)
    }

    private data class Fixture(
        val controller: DefaultSyncSetupController,
        val status: MutableStateFlow<SyncStatusSnapshot>,
    )

    private companion object {
        const val VALID_CODE = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345"

        fun localSnapshot() = SyncStatusSnapshot(
            connection = SyncConnectionStatus.LOCAL_ONLY,
            enrollmentAttempt = EnrollmentAttemptStatus.NONE,
            pendingCount = 2,
            bootstrap = SyncBootstrapStatus.UNAVAILABLE,
            lastServerConfirmationAt = null,
        )

        fun readySnapshot() = SyncStatusSnapshot(
            connection = SyncConnectionStatus.READY,
            enrollmentAttempt = EnrollmentAttemptStatus.COMPLETED,
            pendingCount = 0,
            bootstrap = SyncBootstrapStatus.READY,
            lastServerConfirmationAt = Instant.parse("2026-08-03T04:05:06Z"),
        )
    }
}
