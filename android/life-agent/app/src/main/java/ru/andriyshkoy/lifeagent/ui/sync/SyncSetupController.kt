package ru.andriyshkoy.lifeagent.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.andriyshkoy.lifeagent.data.sync.status.EnrollmentAttemptStatus
import ru.andriyshkoy.lifeagent.data.sync.status.SyncBootstrapStatus
import ru.andriyshkoy.lifeagent.data.sync.status.SyncConnectionStatus
import ru.andriyshkoy.lifeagent.data.sync.status.SyncStatusReadModel
import ru.andriyshkoy.lifeagent.data.sync.status.SyncStatusSnapshot
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.requireEnrollmentCode

public enum class SyncBootstrapUiStatus {
    UNAVAILABLE,
    REQUIRED,
    IN_PROGRESS,
    READY,
    NEEDS_ATTENTION,
}

public data class SyncSetupSummary(
    val pendingCount: Int,
    val bootstrap: SyncBootstrapUiStatus,
    val lastServerConfirmationAt: Instant?,
) {
    init {
        require(pendingCount >= 0)
    }

    public companion object {
        public val Empty: SyncSetupSummary = SyncSetupSummary(
            pendingCount = 0,
            bootstrap = SyncBootstrapUiStatus.UNAVAILABLE,
            lastServerConfirmationAt = null,
        )
    }
}

public enum class SyncSetupErrorReason {
    INVALID_CODE,
    ENROLLMENT_REJECTED,
    NEW_CODE_REQUIRED,
    LOCAL_UNAVAILABLE,
    STATUS_UNAVAILABLE,
    BUSY,
}

public sealed interface SyncSetupUiState {
    public data object Loading : SyncSetupUiState

    public data class LocalOnly(
        val summary: SyncSetupSummary,
    ) : SyncSetupUiState

    public data class CodeEntry(
        val summary: SyncSetupSummary,
    ) : SyncSetupUiState

    public data class Enrolling(
        val summary: SyncSetupSummary,
    ) : SyncSetupUiState

    public data class Error(
        val summary: SyncSetupSummary,
        val reason: SyncSetupErrorReason,
    ) : SyncSetupUiState

    public data class Ready(
        val summary: SyncSetupSummary,
    ) : SyncSetupUiState
}

public enum class SyncSetupNotice {
    QUEUED,
    NOT_CONFIGURED,
    MISCONFIGURED,
    FAILED,
}

/**
 * UI-facing synchronization boundary. [submitEnrollment] takes ownership of
 * the mutable array and guarantees that it is wiped before any gateway call.
 */
public interface SyncSetupController {
    public val uiState: StateFlow<SyncSetupUiState>
    public val notices: Flow<SyncSetupNotice>

    public fun showCodeEntry()

    public fun cancelCodeEntry()

    public fun submitEnrollment(ownedCode: CharArray)

    public fun enqueueNow()
}

internal enum class SyncEnrollmentGatewayResult {
    CONNECTED,
    REJECTED,
    NEW_CODE_REQUIRED,
    LOCAL_UNAVAILABLE,
    BUSY,
}

internal fun interface SyncEnrollmentGateway {
    /** Production adapters always enroll with replacement disabled. */
    suspend fun enroll(ownedEnrollmentCode: WipeableSecret): SyncEnrollmentGatewayResult
}

internal enum class ManualSyncEnqueueResult {
    QUEUED,
    NOT_CONFIGURED,
    MISCONFIGURED,
    FAILED,
}

internal fun interface ManualSyncEnqueueGateway {
    fun enqueueNow(): ManualSyncEnqueueResult
}

internal class DefaultSyncSetupController(
    readModel: SyncStatusReadModel,
    private val enrollmentGateway: SyncEnrollmentGateway,
    private val manualSyncGateway: ManualSyncEnqueueGateway,
) : ViewModel(), SyncSetupController {
    private val mutableUiState = MutableStateFlow<SyncSetupUiState>(SyncSetupUiState.Loading)
    private val mutableNotices = MutableSharedFlow<SyncSetupNotice>(extraBufferCapacity = 4)
    private var latestSnapshot: SyncStatusSnapshot? = null
    private var mode: SurfaceMode = SurfaceMode.AUTO

    override val uiState: StateFlow<SyncSetupUiState> = mutableUiState.asStateFlow()
    override val notices: Flow<SyncSetupNotice> = mutableNotices.asSharedFlow()

    init {
        viewModelScope.launch {
            readModel.observe()
                .catch { error ->
                    if (error is CancellationException) throw error
                    mode = SurfaceMode.Error(SyncSetupErrorReason.STATUS_UNAVAILABLE)
                    mutableUiState.value = SyncSetupUiState.Error(
                        summary = latestSnapshot?.toSummary() ?: SyncSetupSummary.Empty,
                        reason = SyncSetupErrorReason.STATUS_UNAVAILABLE,
                    )
                }
                .collect(::acceptSnapshot)
        }
    }

    override fun showCodeEntry() {
        val summary = latestSnapshot?.toSummary() ?: currentSummary()
        mode = SurfaceMode.CODE_ENTRY
        mutableUiState.value = SyncSetupUiState.CodeEntry(summary)
    }

    override fun cancelCodeEntry() {
        mode = SurfaceMode.AUTO
        latestSnapshot?.let(::acceptSnapshot)
            ?: run { mutableUiState.value = SyncSetupUiState.Loading }
    }

    override fun submitEnrollment(ownedCode: CharArray) {
        val secret = ownedCode.copyToOwnedSecretAndWipe()
        if (secret == null) {
            showError(SyncSetupErrorReason.INVALID_CODE)
            return
        }
        try {
            requireEnrollmentCode(secret)
        } catch (_: Exception) {
            secret.close()
            showError(SyncSetupErrorReason.INVALID_CODE)
            return
        }
        if (mode == SurfaceMode.ENROLLING || mode == SurfaceMode.AWAITING_READY) {
            secret.close()
            return
        }

        mode = SurfaceMode.ENROLLING
        mutableUiState.value = SyncSetupUiState.Enrolling(currentSummary())
        viewModelScope.launch {
            val result = try {
                withContext(NonCancellable) {
                    enrollmentGateway.enroll(ownedEnrollmentCode = secret)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE
            } finally {
                secret.close()
            }
            when (result) {
                SyncEnrollmentGatewayResult.CONNECTED -> {
                    mode = SurfaceMode.AWAITING_READY
                    mutableUiState.value = SyncSetupUiState.Enrolling(currentSummary())
                    latestSnapshot
                        ?.takeIf { it.connection == SyncConnectionStatus.READY }
                        ?.let(::acceptSnapshot)
                }

                SyncEnrollmentGatewayResult.REJECTED ->
                    showError(SyncSetupErrorReason.ENROLLMENT_REJECTED)

                SyncEnrollmentGatewayResult.NEW_CODE_REQUIRED ->
                    showError(SyncSetupErrorReason.NEW_CODE_REQUIRED)

                SyncEnrollmentGatewayResult.LOCAL_UNAVAILABLE ->
                    showError(SyncSetupErrorReason.LOCAL_UNAVAILABLE)

                SyncEnrollmentGatewayResult.BUSY -> showError(SyncSetupErrorReason.BUSY)
            }
        }
    }

    override fun enqueueNow() {
        val notice = try {
            when (manualSyncGateway.enqueueNow()) {
                ManualSyncEnqueueResult.QUEUED -> SyncSetupNotice.QUEUED
                ManualSyncEnqueueResult.NOT_CONFIGURED -> SyncSetupNotice.NOT_CONFIGURED
                ManualSyncEnqueueResult.MISCONFIGURED -> SyncSetupNotice.MISCONFIGURED
                ManualSyncEnqueueResult.FAILED -> SyncSetupNotice.FAILED
            }
        } catch (_: Exception) {
            SyncSetupNotice.FAILED
        }
        mutableNotices.tryEmit(notice)
    }

    private fun acceptSnapshot(snapshot: SyncStatusSnapshot) {
        latestSnapshot = snapshot
        val summary = snapshot.toSummary()
        if (snapshot.connection == SyncConnectionStatus.READY) {
            mode = SurfaceMode.AUTO
            mutableUiState.value = SyncSetupUiState.Ready(summary)
            return
        }
        mutableUiState.value = when (val currentMode = mode) {
            SurfaceMode.CODE_ENTRY -> SyncSetupUiState.CodeEntry(summary)
            SurfaceMode.ENROLLING,
            SurfaceMode.AWAITING_READY,
            -> SyncSetupUiState.Enrolling(summary)

            is SurfaceMode.Error -> SyncSetupUiState.Error(summary, currentMode.reason)
            SurfaceMode.AUTO -> when {
                snapshot.connection == SyncConnectionStatus.REENROLLMENT_REQUIRED ||
                    snapshot.enrollmentAttempt == EnrollmentAttemptStatus.OUTCOME_UNKNOWN ||
                    snapshot.enrollmentAttempt == EnrollmentAttemptStatus.DISPATCHING ->
                    SyncSetupUiState.Error(summary, SyncSetupErrorReason.NEW_CODE_REQUIRED)

                else -> SyncSetupUiState.LocalOnly(summary)
            }
        }
    }

    private fun showError(reason: SyncSetupErrorReason) {
        mode = SurfaceMode.Error(reason)
        mutableUiState.value = SyncSetupUiState.Error(currentSummary(), reason)
    }

    private fun currentSummary(): SyncSetupSummary =
        latestSnapshot?.toSummary() ?: when (val state = mutableUiState.value) {
            SyncSetupUiState.Loading -> SyncSetupSummary.Empty
            is SyncSetupUiState.LocalOnly -> state.summary
            is SyncSetupUiState.CodeEntry -> state.summary
            is SyncSetupUiState.Enrolling -> state.summary
            is SyncSetupUiState.Error -> state.summary
            is SyncSetupUiState.Ready -> state.summary
        }

    override fun toString(): String = "DefaultSyncSetupController(redacted=true)"

    private sealed interface SurfaceMode {
        data object AUTO : SurfaceMode
        data object CODE_ENTRY : SurfaceMode
        data object ENROLLING : SurfaceMode
        data object AWAITING_READY : SurfaceMode
        data class Error(val reason: SyncSetupErrorReason) : SurfaceMode
    }
}

/** Local fallback used until the production runtime is composed by the app graph. */
internal class LocalOnlySyncSetupController : SyncSetupController {
    private val mutableUiState = MutableStateFlow<SyncSetupUiState>(
        SyncSetupUiState.LocalOnly(SyncSetupSummary.Empty),
    )
    private val mutableNotices = MutableSharedFlow<SyncSetupNotice>(extraBufferCapacity = 1)

    override val uiState: StateFlow<SyncSetupUiState> = mutableUiState.asStateFlow()
    override val notices: Flow<SyncSetupNotice> = mutableNotices.asSharedFlow()

    override fun showCodeEntry() {
        mutableUiState.value = SyncSetupUiState.CodeEntry(SyncSetupSummary.Empty)
    }

    override fun cancelCodeEntry() {
        mutableUiState.value = SyncSetupUiState.LocalOnly(SyncSetupSummary.Empty)
    }

    override fun submitEnrollment(ownedCode: CharArray) {
        ownedCode.fill('\u0000')
        mutableUiState.value = SyncSetupUiState.Error(
            summary = SyncSetupSummary.Empty,
            reason = SyncSetupErrorReason.LOCAL_UNAVAILABLE,
        )
    }

    override fun enqueueNow() {
        mutableNotices.tryEmit(SyncSetupNotice.NOT_CONFIGURED)
    }

    override fun toString(): String = "LocalOnlySyncSetupController(redacted=true)"
}

private fun SyncStatusSnapshot.toSummary(): SyncSetupSummary = SyncSetupSummary(
    pendingCount = pendingCount,
    bootstrap = when (bootstrap) {
        SyncBootstrapStatus.UNAVAILABLE -> SyncBootstrapUiStatus.UNAVAILABLE
        SyncBootstrapStatus.REQUIRED -> SyncBootstrapUiStatus.REQUIRED
        SyncBootstrapStatus.IN_PROGRESS -> SyncBootstrapUiStatus.IN_PROGRESS
        SyncBootstrapStatus.READY -> SyncBootstrapUiStatus.READY
        SyncBootstrapStatus.INTEGRITY_HALTED -> SyncBootstrapUiStatus.NEEDS_ATTENTION
    },
    lastServerConfirmationAt = lastServerConfirmationAt,
)

private fun CharArray.copyToOwnedSecretAndWipe(): WipeableSecret? {
    val bytes = ByteArray(size)
    return try {
        forEachIndexed { index, character ->
            if (character.code !in 0x20..0x7e) return null
            bytes[index] = character.code.toByte()
        }
        WipeableSecret.copyOf(bytes)
    } finally {
        fill('\u0000')
        bytes.fill(0)
    }
}
