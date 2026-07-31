package ru.andriyshkoy.lifeagent.data.sync.wire

import java.nio.charset.StandardCharsets
import java.util.Arrays

/** Mutable secret storage with identity equality and a permanently redacted representation. */
internal class WipeableSecret private constructor(
    private var storage: ByteArray?,
) : AutoCloseable {
    val size: Int
        get() = checkNotNull(storage) { "secret is closed" }.size

    fun copyBytes(): ByteArray = checkNotNull(storage) { "secret is closed" }.copyOf()

    inline fun <T> useBytes(block: (ByteArray) -> T): T {
        val copy = copyBytes()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun close() {
        storage?.let { bytes -> Arrays.fill(bytes, 0.toByte()) }
        storage = null
    }

    override fun toString(): String = "WipeableSecret(redacted=true)"

    companion object {
        fun ascii(value: String): WipeableSecret {
            require(value.all { it.code in 0x20..0x7e })
            return WipeableSecret(value.toByteArray(StandardCharsets.US_ASCII))
        }

        fun copyOf(value: ByteArray): WipeableSecret = WipeableSecret(value.copyOf())
    }
}

internal sealed interface M2WireRequest {
    val endpoint: M2Endpoint
    val correlationId: String
}

internal class EnrollmentClaimRequest(
    val requestId: String,
    val enrollmentCode: WipeableSecret,
    val installationId: String,
    val localOwnerId: String,
    val replaceActiveDevice: Boolean,
) : M2WireRequest, AutoCloseable {
    override val endpoint: M2Endpoint = M2Endpoint.AUTH_ENROLL
    override val correlationId: String = requestId

    override fun close() = enrollmentCode.close()

    override fun toString(): String = "EnrollmentClaimRequest(redacted=true)"
}

internal class RefreshRequest(
    val requestId: String,
    val deviceId: String,
    val generation: Long,
    val refreshToken: WipeableSecret,
) : M2WireRequest, AutoCloseable {
    override val endpoint: M2Endpoint = M2Endpoint.AUTH_REFRESH
    override val correlationId: String = requestId

    override fun close() = refreshToken.close()

    override fun toString(): String = "RefreshRequest(redacted=true)"
}

internal class RevokeRequest(
    val requestId: String,
    val deviceId: String,
    val generation: Long,
    val refreshToken: WipeableSecret,
) : M2WireRequest, AutoCloseable {
    override val endpoint: M2Endpoint = M2Endpoint.AUTH_REVOKE
    override val correlationId: String = requestId

    override fun close() = refreshToken.close()

    override fun toString(): String = "RevokeRequest(redacted=true)"
}

internal class M2NoteCaptureWire internal constructor(
    internal val document: WireJsonObject,
    val captureId: String,
    val operationId: String,
    val installationId: String,
    val localOwnerId: String,
    val deviceId: String?,
    val persistenceState: String,
) {
    override fun toString(): String = "M2NoteCaptureWire(redacted=true)"
}

internal class M2NoteEventWire internal constructor(
    internal val document: WireJsonObject,
    val eventId: String,
    val revisionId: String,
    val revisionNo: Int,
    val captureId: String,
    val operationId: String,
    val installationId: String,
    val localOwnerId: String,
    val deviceId: String?,
    val parentRevisionId: String?,
    val recordStatus: String,
    val persistenceState: String,
    val serverSequence: Long?,
    val receivedAt: String?,
) {
    override fun toString(): String = "M2NoteEventWire(redacted=true)"
}

internal class PushOperationWire internal constructor(
    internal val document: WireJsonObject,
    val ordinal: Int,
    val clientSequence: Long,
    val operationId: String,
    val captureId: String,
    val eventId: String,
    val revisionId: String,
    val expectedCurrentRevisionId: String?,
    val operationContentSha256: String,
    val capture: M2NoteCaptureWire,
    val event: M2NoteEventWire,
) {
    override fun toString(): String =
        "PushOperationWire(ordinal=$ordinal,clientSequence=$clientSequence,redacted=true)"
}

internal class PushBatchRequest(
    val batchId: String,
    val deviceId: String,
    operations: List<PushOperationWire>,
) : M2WireRequest {
    val operations: List<PushOperationWire> = operations.toList()
    override val endpoint: M2Endpoint = M2Endpoint.SYNC_PUSH
    override val correlationId: String = batchId

    override fun toString(): String =
        "PushBatchRequest(operationCount=${operations.size},redacted=true)"
}

internal data class BootstrapRequest(
    val requestId: String,
    val bootstrapId: String,
    val deviceId: String,
    val pageSize: Int,
    val pageCursor: String?,
) : M2WireRequest {
    override val endpoint: M2Endpoint = M2Endpoint.SYNC_BOOTSTRAP
    override val correlationId: String = requestId

    override fun toString(): String = "BootstrapRequest(redacted=true)"
}

internal data class PullRequest(
    val requestId: String,
    val deviceId: String,
    val cursor: String,
    val pageSize: Int,
) : M2WireRequest {
    override val endpoint: M2Endpoint = M2Endpoint.SYNC_PULL
    override val correlationId: String = requestId

    override fun toString(): String = "PullRequest(redacted=true)"
}

/**
 * Exact body bytes are an explicit lifetime-owned value, never a data class.
 * Callers persisting a replayable request copy the bytes, then close this value.
 */
internal class MaterializedWireRequest internal constructor(
    val endpoint: M2Endpoint,
    val correlationId: String,
    val idempotencyKey: String?,
    val bodySha256: String,
    body: ByteArray,
) : AutoCloseable {
    private var bodyStorage: ByteArray? = body

    val bodySize: Int
        get() = checkNotNull(bodyStorage) { "materialized request is closed" }.size

    fun copyBody(): ByteArray =
        checkNotNull(bodyStorage) { "materialized request is closed" }.copyOf()

    inline fun <T> useBody(block: (ByteArray) -> T): T {
        val copy = copyBody()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun close() {
        bodyStorage?.fill(0)
        bodyStorage = null
    }

    override fun toString(): String =
        "MaterializedWireRequest(endpoint=${endpoint.endpointId},bodySize=${bodyStorage?.size ?: 0},redacted=true)"
}

internal enum class FieldErrorCode(
    val wireName: String,
) {
    MISSING_REQUIRED_FIELD("missing_required_field"),
    INVALID_TYPE("invalid_type"),
    INVALID_FORMAT("invalid_format"),
    INVALID_VALUE("invalid_value"),
    UNSUPPORTED_VALUE("unsupported_value"),
    UNEXPECTED_FIELD("unexpected_field"),
    SCHEMA_INVALID("schema_invalid"),
    INVALID_FIELD_TYPE("invalid_field_type"),
    INVALID_FIELD_VALUE("invalid_field_value"),
    UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version"),
    UNSUPPORTED_OPERATION_KIND("unsupported_operation_kind"),
    UNSUPPORTED_EVENT_KIND("unsupported_event_kind"),
    UNSUPPORTED_SOURCE_CHANNEL("unsupported_source_channel"),
    ;

    companion object {
        fun fromWire(value: String): FieldErrorCode? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class WireFieldError(
    val path: String,
    val code: FieldErrorCode,
)

internal data class WireApiError(
    val requestId: String?,
    val errorCode: ApiErrorCode,
    val httpStatus: Int,
    val retryable: Boolean,
    val fieldErrors: List<WireFieldError>,
    val serverTime: String,
) {
    override fun toString(): String =
        "WireApiError(errorCode=${errorCode.wireName},httpStatus=$httpStatus,redacted=true)"
}

internal class EphemeralTokenPair internal constructor(
    accessToken: ByteArray,
    refreshToken: ByteArray,
    val accessExpiresAt: String,
    val refreshExpiresAt: String,
    val familyExpiresAt: String,
    val generation: Long,
) : AutoCloseable {
    // Constructor ownership is transferred; close() wipes these exact arrays.
    private var accessTokenStorage: ByteArray? = accessToken
    private var refreshTokenStorage: ByteArray? = refreshToken

    fun copyAccessToken(): ByteArray =
        checkNotNull(accessTokenStorage) { "credentials are closed" }.copyOf()

    fun copyRefreshToken(): ByteArray =
        checkNotNull(refreshTokenStorage) { "credentials are closed" }.copyOf()

    inline fun <T> useAccessToken(block: (ByteArray) -> T): T =
        useSecretCopy(copyAccessToken(), block)

    inline fun <T> useRefreshToken(block: (ByteArray) -> T): T =
        useSecretCopy(copyRefreshToken(), block)

    override fun close() {
        accessTokenStorage?.fill(0)
        refreshTokenStorage?.fill(0)
        accessTokenStorage = null
        refreshTokenStorage = null
    }

    override fun toString(): String = "EphemeralTokenPair(redacted=true)"

    companion object {
        inline fun <T> useSecretCopy(bytes: ByteArray, block: (ByteArray) -> T): T =
            try {
                block(bytes)
            } finally {
                bytes.fill(0)
            }
    }
}

internal class EnrollmentClaimSuccess(
    val requestId: String,
    val installationId: String,
    val localOwnerId: String,
    val deviceId: String,
    val personId: String,
    val credentials: EphemeralTokenPair,
    val bootstrapRequired: Boolean,
    val serverTime: String,
) : AutoCloseable, DecodedWireResponse {
    override fun close() = credentials.close()

    override fun toString(): String = "EnrollmentClaimSuccess(redacted=true)"
}

internal class RefreshSuccess(
    val requestId: String,
    val deviceId: String,
    val credentials: EphemeralTokenPair,
    val serverTime: String,
) : AutoCloseable, DecodedWireResponse {
    override fun close() = credentials.close()

    override fun toString(): String = "RefreshSuccess(redacted=true)"
}

internal data class RevokeSuccess(
    val requestId: String,
    val deviceId: String,
    val generation: Long,
    val revokedAt: String,
    val serverTime: String,
) : DecodedWireResponse {
    override fun toString(): String = "RevokeSuccess(redacted=true)"
}

internal enum class PushResultCode(
    val wireName: String,
) {
    APPLIED("applied"),
    CONFLICT("conflict"),
}

internal enum class PushOperationErrorCode(
    val wireName: String,
    val retryable: Boolean,
) {
    UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version", false),
    UNSUPPORTED_OPERATION_KIND("unsupported_operation_kind", false),
    UNSUPPORTED_EVENT_KIND("unsupported_event_kind", false),
    UNSUPPORTED_SOURCE_CHANNEL("unsupported_source_channel", false),
    SCHEMA_INVALID("schema_invalid", false),
    OPERATION_HASH_MISMATCH("operation_hash_mismatch", false),
    OPERATION_ID_COLLISION("operation_id_collision", false),
    CLIENT_SEQUENCE_COLLISION("client_sequence_collision", false),
    CAPTURE_ID_COLLISION("capture_id_collision", false),
    REVISION_ID_COLLISION("revision_id_collision", false),
    EVENT_ID_COLLISION("event_id_collision", false),
    MISSING_PARENT("missing_parent", true),
    INVALID_PARENT("invalid_parent", false),
    OWNERSHIP_VIOLATION("ownership_violation", false),
    ;

    companion object {
        fun fromWire(value: String): PushOperationErrorCode? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal sealed interface PushOperationResult {
    val ordinal: Int
    val operationId: String?
    val operationContentSha256: String?
}

internal data class PushOperationAck(
    override val ordinal: Int,
    override val operationId: String,
    override val operationContentSha256: String,
    val resultCode: PushResultCode,
    val replayed: Boolean,
    val captureId: String,
    val eventId: String,
    val revisionId: String,
    val currentRevisionId: String,
    val serverSequence: Long,
    val committedAt: String,
) : PushOperationResult {
    override fun toString(): String =
        "PushOperationAck(ordinal=$ordinal,resultCode=${resultCode.wireName},redacted=true)"
}

internal data class PushOperationError(
    override val ordinal: Int,
    override val operationId: String?,
    override val operationContentSha256: String?,
    val errorCode: PushOperationErrorCode,
    val retryable: Boolean,
    val fieldErrors: List<WireFieldError>,
) : PushOperationResult {
    override fun toString(): String =
        "PushOperationError(ordinal=$ordinal,errorCode=${errorCode.wireName},redacted=true)"
}

internal data class PushBatchSuccess(
    val batchId: String,
    val deviceId: String,
    val results: List<PushOperationResult>,
    val serverHighWatermark: String,
    val serverTime: String,
) : DecodedWireResponse {
    override fun toString(): String =
        "PushBatchSuccess(resultCount=${results.size},redacted=true)"
}

internal data class ServerChangeWire(
    val serverSequence: Long,
    val resultCode: PushResultCode,
    val operationId: String,
    val captureId: String,
    val eventId: String,
    val revisionId: String,
    val currentRevisionId: String,
    val operationContentSha256: String,
    val capture: M2NoteCaptureWire,
    val event: M2NoteEventWire,
) {
    override fun toString(): String =
        "ServerChangeWire(serverSequence=$serverSequence,resultCode=${resultCode.wireName},redacted=true)"
}

internal data class BootstrapPageSuccess(
    val requestId: String,
    val bootstrapId: String,
    val deviceId: String,
    val fromPageCursor: String?,
    val snapshotId: String,
    val pageId: String,
    val pageSha256: String,
    val changes: List<ServerChangeWire>,
    val nextPageCursor: String?,
    val incrementalCursor: String,
    val complete: Boolean,
    val serverTime: String,
) {
    override fun toString(): String =
        "BootstrapPageSuccess(changeCount=${changes.size},complete=$complete,redacted=true)"
}

internal data class PullPageSuccess(
    val requestId: String,
    val deviceId: String,
    val fromCursor: String,
    val pageId: String,
    val pageSha256: String,
    val changes: List<ServerChangeWire>,
    val nextCursor: String,
    val hasMore: Boolean,
    val serverTime: String,
) {
    override fun toString(): String =
        "PullPageSuccess(changeCount=${changes.size},hasMore=$hasMore,redacted=true)"
}

internal data class RevisionStreamFact(
    val eventId: String,
    val revisionNo: Int,
) {
    override fun toString(): String = "RevisionStreamFact(redacted=true)"
}

internal data class OperationReceiptFact(
    val operationContentSha256: String,
    val resultCode: PushResultCode,
    val captureId: String,
    val eventId: String,
    val revisionId: String,
    val currentRevisionId: String,
    val serverSequence: Long,
    val committedAt: String,
) {
    override fun toString(): String = "OperationReceiptFact(redacted=true)"
}

internal enum class BootstrapValidationPhase {
    INITIAL,
    IN_PROGRESS,
    COMPLETE,
    INCREMENTAL,
}

internal data class PageReplayReceipt(
    val requestBodySha256: String,
    val responseBodySha256: String,
) {
    override fun toString(): String = "PageReplayReceipt(redacted=true)"
}

/** Trusted reducer preconditions carried across already-validated pages. */
internal class ReplicaStreamValidationState(
    val lastServerSequence: Long? = null,
    seenPageIds: Set<String> = emptySet(),
    seenSuccessfulBootstrapRequestIds: Set<String> = emptySet(),
    seenSuccessfulPullRequestIds: Set<String> = emptySet(),
    successfulBootstrapPageReceipts: Map<String, PageReplayReceipt> = emptyMap(),
    successfulPullPageReceipts: Map<String, PageReplayReceipt> = emptyMap(),
    seenBootstrapPageCursors: Set<String> = emptySet(),
    seenPullCursors: Set<String> = emptySet(),
    revisionsById: Map<String, RevisionStreamFact> = emptyMap(),
    currentRevisionByEvent: Map<String, String> = emptyMap(),
    terminalReceiptsByOperationId: Map<String, OperationReceiptFact> = emptyMap(),
    captureIds: Set<String> = emptySet(),
    val receivingDeviceId: String? = null,
    val activeBootstrapId: String? = null,
    val bootstrapPhase: BootstrapValidationPhase = BootstrapValidationPhase.INITIAL,
    val expectedBootstrapPageCursor: String? = null,
    val expectedPullCursor: String? = null,
    val pullContinuationRequired: Boolean = false,
    val bootstrapSnapshotId: String? = null,
    val bootstrapIncrementalCursor: String? = null,
) {
    val seenPageIds: Set<String> = seenPageIds.toSet()
    val seenSuccessfulBootstrapRequestIds: Set<String> =
        seenSuccessfulBootstrapRequestIds.toSet()
    val seenSuccessfulPullRequestIds: Set<String> = seenSuccessfulPullRequestIds.toSet()
    val successfulBootstrapPageReceipts: Map<String, PageReplayReceipt> =
        successfulBootstrapPageReceipts.toMap()
    val successfulPullPageReceipts: Map<String, PageReplayReceipt> =
        successfulPullPageReceipts.toMap()
    val seenBootstrapPageCursors: Set<String> = seenBootstrapPageCursors.toSet()
    val seenPullCursors: Set<String> = seenPullCursors.toSet()
    val revisionsById: Map<String, RevisionStreamFact> = revisionsById.toMap()
    val currentRevisionByEvent: Map<String, String> = currentRevisionByEvent.toMap()
    val terminalReceiptsByOperationId: Map<String, OperationReceiptFact> =
        terminalReceiptsByOperationId.toMap()
    val captureIds: Set<String> = captureIds.toSet()

    fun copy(
        lastServerSequence: Long? = this.lastServerSequence,
        seenPageIds: Set<String> = this.seenPageIds,
        seenSuccessfulBootstrapRequestIds: Set<String> =
            this.seenSuccessfulBootstrapRequestIds,
        seenSuccessfulPullRequestIds: Set<String> = this.seenSuccessfulPullRequestIds,
        successfulBootstrapPageReceipts: Map<String, PageReplayReceipt> =
            this.successfulBootstrapPageReceipts,
        successfulPullPageReceipts: Map<String, PageReplayReceipt> =
            this.successfulPullPageReceipts,
        seenBootstrapPageCursors: Set<String> = this.seenBootstrapPageCursors,
        seenPullCursors: Set<String> = this.seenPullCursors,
        revisionsById: Map<String, RevisionStreamFact> = this.revisionsById,
        currentRevisionByEvent: Map<String, String> = this.currentRevisionByEvent,
        terminalReceiptsByOperationId: Map<String, OperationReceiptFact> =
            this.terminalReceiptsByOperationId,
        captureIds: Set<String> = this.captureIds,
        receivingDeviceId: String? = this.receivingDeviceId,
        activeBootstrapId: String? = this.activeBootstrapId,
        bootstrapPhase: BootstrapValidationPhase = this.bootstrapPhase,
        expectedBootstrapPageCursor: String? = this.expectedBootstrapPageCursor,
        expectedPullCursor: String? = this.expectedPullCursor,
        pullContinuationRequired: Boolean = this.pullContinuationRequired,
        bootstrapSnapshotId: String? = this.bootstrapSnapshotId,
        bootstrapIncrementalCursor: String? = this.bootstrapIncrementalCursor,
    ): ReplicaStreamValidationState = ReplicaStreamValidationState(
        lastServerSequence = lastServerSequence,
        seenPageIds = seenPageIds,
        seenSuccessfulBootstrapRequestIds = seenSuccessfulBootstrapRequestIds,
        seenSuccessfulPullRequestIds = seenSuccessfulPullRequestIds,
        successfulBootstrapPageReceipts = successfulBootstrapPageReceipts,
        successfulPullPageReceipts = successfulPullPageReceipts,
        seenBootstrapPageCursors = seenBootstrapPageCursors,
        seenPullCursors = seenPullCursors,
        revisionsById = revisionsById,
        currentRevisionByEvent = currentRevisionByEvent,
        terminalReceiptsByOperationId = terminalReceiptsByOperationId,
        captureIds = captureIds,
        receivingDeviceId = receivingDeviceId,
        activeBootstrapId = activeBootstrapId,
        bootstrapPhase = bootstrapPhase,
        expectedBootstrapPageCursor = expectedBootstrapPageCursor,
        expectedPullCursor = expectedPullCursor,
        pullContinuationRequired = pullContinuationRequired,
        bootstrapSnapshotId = bootstrapSnapshotId,
        bootstrapIncrementalCursor = bootstrapIncrementalCursor,
    )

    /** Starts a replacement replica stream while retaining lifetime exact-replay receipts. */
    fun resetReplicaStream(): ReplicaStreamValidationState = ReplicaStreamValidationState(
        seenSuccessfulBootstrapRequestIds = seenSuccessfulBootstrapRequestIds,
        seenSuccessfulPullRequestIds = seenSuccessfulPullRequestIds,
        successfulBootstrapPageReceipts = successfulBootstrapPageReceipts,
        successfulPullPageReceipts = successfulPullPageReceipts,
        terminalReceiptsByOperationId = terminalReceiptsByOperationId,
    )

    override fun toString(): String =
        "ReplicaStreamValidationState(phase=$bootstrapPhase," +
            "revisionCount=${revisionsById.size},pageCount=${seenPageIds.size}," +
            "cursorCount=${seenBootstrapPageCursors.size + seenPullCursors.size},redacted=true)"
}

internal data class ValidatedBootstrapPage(
    val page: BootstrapPageSuccess,
    val nextState: ReplicaStreamValidationState,
    val requestBodySha256: String,
    val responseBodySha256: String,
    val replayed: Boolean = false,
) : DecodedWireResponse {
    override fun toString(): String =
        "ValidatedBootstrapPage(changeCount=${page.changes.size},redacted=true)"
}

internal data class ValidatedPullPage(
    val page: PullPageSuccess,
    val nextState: ReplicaStreamValidationState,
    val requestBodySha256: String,
    val responseBodySha256: String,
    val replayed: Boolean = false,
) : DecodedWireResponse {
    override fun toString(): String =
        "ValidatedPullPage(changeCount=${page.changes.size},redacted=true)"
}

internal sealed interface ResponseExpectation {
    val request: M2WireRequest
}

internal class EnrollmentResponseExpectation(
    override val request: EnrollmentClaimRequest,
    val expectedStableDeviceId: String? = null,
    val expectedStablePersonId: String? = null,
    forbiddenExistingDeviceIds: Set<String> = emptySet(),
) : ResponseExpectation {
    val forbiddenExistingDeviceIds: Set<String> = forbiddenExistingDeviceIds.toSet()

    override fun toString(): String = "EnrollmentResponseExpectation(redacted=true)"
}

internal class RefreshResponseExpectation(
    override val request: RefreshRequest,
    val expectedFamilyExpiresAt: String,
    previouslyIssuedTokenSha256: Set<String>,
) : ResponseExpectation {
    val previouslyIssuedTokenSha256: Set<String> = previouslyIssuedTokenSha256.toSet()

    override fun toString(): String = "RefreshResponseExpectation(redacted=true)"
}

internal data class RevokeResponseExpectation(
    override val request: RevokeRequest,
) : ResponseExpectation {
    override fun toString(): String = "RevokeResponseExpectation(redacted=true)"
}

internal data class PushResponseExpectation(
    override val request: PushBatchRequest,
) : ResponseExpectation {
    override fun toString(): String = "PushResponseExpectation(redacted=true)"
}

internal data class BootstrapResponseExpectation(
    override val request: BootstrapRequest,
    val streamState: ReplicaStreamValidationState,
    val persistedRequestBodySha256: String? = null,
) : ResponseExpectation {
    override fun toString(): String = "BootstrapResponseExpectation(redacted=true)"
}

internal data class PullResponseExpectation(
    override val request: PullRequest,
    val streamState: ReplicaStreamValidationState,
    val persistedRequestBodySha256: String? = null,
) : ResponseExpectation {
    override fun toString(): String = "PullResponseExpectation(redacted=true)"
}

internal sealed interface DecodedWireResponse

internal data class DecodedApiError(
    val value: WireApiError,
) : DecodedWireResponse {
    override fun toString(): String =
        "DecodedApiError(errorCode=${value.errorCode.wireName},httpStatus=${value.httpStatus},redacted=true)"
}
