package ru.andriyshkoy.lifeagent.data.sync.wire

internal const val M2_PROTOCOL_VERSION = "1.0.0"
internal const val M2_REQUEST_MEDIA_TYPE = "application/json"
internal const val M2_SUCCESS_STATUS = 200
internal const val M2_API_ERROR_MAX_BYTES = 16_384
internal const val M2_MAX_PAGE_SIZE = 500
internal const val M2_MAX_PUSH_OPERATIONS = 100

internal enum class WireAuthenticationMode {
    NONE_PLUS_ONE_TIME_CODE,
    REFRESH_TOKEN_BODY,
    BEARER_ACCESS,
}

internal enum class WireCorrelationField(
    val wireName: String,
) {
    REQUEST_ID("request_id"),
    BATCH_ID("batch_id"),
}

internal enum class ApiErrorCode(
    val wireName: String,
) {
    MALFORMED_JSON("malformed_json"),
    UNSUPPORTED_PROTOCOL_VERSION("unsupported_protocol_version"),
    REQUEST_SCHEMA_INVALID("request_schema_invalid"),
    REQUEST_TOO_LARGE("request_too_large"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
    RATE_LIMITED("rate_limited"),
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable"),
    ENROLLMENT_UNAVAILABLE("enrollment_unavailable"),
    ACTIVE_DEVICE_EXISTS("active_device_exists"),
    CREDENTIAL_UNAVAILABLE("credential_unavailable"),
    DEVICE_MISMATCH("device_mismatch"),
    IDEMPOTENCY_KEY_MISMATCH("idempotency_key_mismatch"),
    REQUEST_ID_COLLISION("request_id_collision"),
    BATCH_HASH_MISMATCH("batch_hash_mismatch"),
    BATCH_ID_COLLISION("batch_id_collision"),
    CURSOR_INVALID("cursor_invalid"),
    CURSOR_EXPIRED("cursor_expired"),
    BOOTSTRAP_REQUIRED("bootstrap_required"),
    ;

    companion object {
        fun fromWire(value: String): ApiErrorCode? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class EndpointErrorPolicy(
    val status: Int,
    val code: ApiErrorCode,
    val retryable: Boolean,
)

internal enum class M2Endpoint(
    val endpointId: String,
    val method: String,
    val path: String,
    val requestMessageType: String,
    val successMessageType: String,
    val requestMaxBytes: Int,
    val successMaxBytes: Int,
    val requestCorrelation: WireCorrelationField,
    val authenticationMode: WireAuthenticationMode,
    val durableExactReplay: Boolean,
    val sync401RecoveryEligible: Boolean,
    val idempotencyKeyRequired: Boolean,
    val errorPolicies: Set<EndpointErrorPolicy>,
) {
    AUTH_ENROLL(
        endpointId = "auth_enroll",
        method = "POST",
        path = "/api/v1/auth/enroll",
        requestMessageType = "enrollment_claim_request",
        successMessageType = "enrollment_claim_response",
        requestMaxBytes = 4_096,
        successMaxBytes = 16_384,
        requestCorrelation = WireCorrelationField.REQUEST_ID,
        authenticationMode = WireAuthenticationMode.NONE_PLUS_ONE_TIME_CODE,
        durableExactReplay = false,
        sync401RecoveryEligible = false,
        idempotencyKeyRequired = false,
        errorPolicies = errorPolicies(
            400 to listOf(ApiErrorCode.MALFORMED_JSON, ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION),
            401 to listOf(ApiErrorCode.ENROLLMENT_UNAVAILABLE),
            409 to listOf(ApiErrorCode.ACTIVE_DEVICE_EXISTS),
            413 to listOf(ApiErrorCode.REQUEST_TOO_LARGE),
            415 to listOf(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE),
            422 to listOf(ApiErrorCode.REQUEST_SCHEMA_INVALID),
            429 to listOf(ApiErrorCode.RATE_LIMITED),
            503 to listOf(ApiErrorCode.TEMPORARILY_UNAVAILABLE),
            retryableCodes = emptySet(),
        ),
    ),
    AUTH_REFRESH(
        endpointId = "auth_refresh",
        method = "POST",
        path = "/api/v1/auth/refresh",
        requestMessageType = "refresh_request",
        successMessageType = "refresh_response",
        requestMaxBytes = 4_096,
        successMaxBytes = 16_384,
        requestCorrelation = WireCorrelationField.REQUEST_ID,
        authenticationMode = WireAuthenticationMode.REFRESH_TOKEN_BODY,
        durableExactReplay = false,
        sync401RecoveryEligible = false,
        idempotencyKeyRequired = false,
        errorPolicies = errorPolicies(
            400 to listOf(ApiErrorCode.MALFORMED_JSON, ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION),
            401 to listOf(ApiErrorCode.CREDENTIAL_UNAVAILABLE),
            413 to listOf(ApiErrorCode.REQUEST_TOO_LARGE),
            415 to listOf(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE),
            422 to listOf(ApiErrorCode.REQUEST_SCHEMA_INVALID),
            429 to listOf(ApiErrorCode.RATE_LIMITED),
            503 to listOf(ApiErrorCode.TEMPORARILY_UNAVAILABLE),
            retryableCodes = emptySet(),
        ),
    ),
    AUTH_REVOKE(
        endpointId = "auth_revoke",
        method = "POST",
        path = "/api/v1/auth/revoke",
        requestMessageType = "revoke_request",
        successMessageType = "revoke_response",
        requestMaxBytes = 4_096,
        successMaxBytes = 16_384,
        requestCorrelation = WireCorrelationField.REQUEST_ID,
        authenticationMode = WireAuthenticationMode.REFRESH_TOKEN_BODY,
        durableExactReplay = true,
        sync401RecoveryEligible = false,
        idempotencyKeyRequired = false,
        errorPolicies = errorPolicies(
            400 to listOf(ApiErrorCode.MALFORMED_JSON, ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION),
            401 to listOf(ApiErrorCode.CREDENTIAL_UNAVAILABLE),
            409 to listOf(ApiErrorCode.REQUEST_ID_COLLISION),
            413 to listOf(ApiErrorCode.REQUEST_TOO_LARGE),
            415 to listOf(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE),
            422 to listOf(ApiErrorCode.REQUEST_SCHEMA_INVALID),
            429 to listOf(ApiErrorCode.RATE_LIMITED),
            503 to listOf(ApiErrorCode.TEMPORARILY_UNAVAILABLE),
            retryableCodes = setOf(
                ApiErrorCode.RATE_LIMITED,
                ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            ),
        ),
    ),
    SYNC_PUSH(
        endpointId = "sync_push",
        method = "POST",
        path = "/api/v1/sync/push",
        requestMessageType = "push_batch_request",
        successMessageType = "push_batch_response",
        requestMaxBytes = 2_097_152,
        successMaxBytes = 524_288,
        requestCorrelation = WireCorrelationField.BATCH_ID,
        authenticationMode = WireAuthenticationMode.BEARER_ACCESS,
        durableExactReplay = true,
        sync401RecoveryEligible = true,
        idempotencyKeyRequired = true,
        errorPolicies = errorPolicies(
            400 to listOf(
                ApiErrorCode.MALFORMED_JSON,
                ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
            ),
            401 to listOf(ApiErrorCode.CREDENTIAL_UNAVAILABLE),
            403 to listOf(ApiErrorCode.DEVICE_MISMATCH),
            409 to listOf(ApiErrorCode.BATCH_ID_COLLISION, ApiErrorCode.BOOTSTRAP_REQUIRED),
            413 to listOf(ApiErrorCode.REQUEST_TOO_LARGE),
            415 to listOf(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE),
            422 to listOf(ApiErrorCode.REQUEST_SCHEMA_INVALID, ApiErrorCode.BATCH_HASH_MISMATCH),
            429 to listOf(ApiErrorCode.RATE_LIMITED),
            503 to listOf(ApiErrorCode.TEMPORARILY_UNAVAILABLE),
            retryableCodes = setOf(
                ApiErrorCode.RATE_LIMITED,
                ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            ),
        ),
    ),
    SYNC_BOOTSTRAP(
        endpointId = "sync_bootstrap",
        method = "POST",
        path = "/api/v1/sync/bootstrap",
        requestMessageType = "bootstrap_request",
        successMessageType = "bootstrap_response",
        requestMaxBytes = 4_096,
        successMaxBytes = 4_194_304,
        requestCorrelation = WireCorrelationField.REQUEST_ID,
        authenticationMode = WireAuthenticationMode.BEARER_ACCESS,
        durableExactReplay = true,
        sync401RecoveryEligible = true,
        idempotencyKeyRequired = false,
        errorPolicies = errorPolicies(
            400 to listOf(
                ApiErrorCode.MALFORMED_JSON,
                ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                ApiErrorCode.CURSOR_INVALID,
            ),
            401 to listOf(ApiErrorCode.CREDENTIAL_UNAVAILABLE),
            403 to listOf(ApiErrorCode.DEVICE_MISMATCH),
            409 to listOf(ApiErrorCode.REQUEST_ID_COLLISION, ApiErrorCode.BOOTSTRAP_REQUIRED),
            410 to listOf(ApiErrorCode.CURSOR_EXPIRED),
            413 to listOf(ApiErrorCode.REQUEST_TOO_LARGE),
            415 to listOf(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE),
            422 to listOf(ApiErrorCode.REQUEST_SCHEMA_INVALID),
            429 to listOf(ApiErrorCode.RATE_LIMITED),
            503 to listOf(ApiErrorCode.TEMPORARILY_UNAVAILABLE),
            retryableCodes = setOf(
                ApiErrorCode.RATE_LIMITED,
                ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            ),
        ),
    ),
    SYNC_PULL(
        endpointId = "sync_pull",
        method = "POST",
        path = "/api/v1/sync/pull",
        requestMessageType = "pull_request",
        successMessageType = "pull_response",
        requestMaxBytes = 4_096,
        successMaxBytes = 4_194_304,
        requestCorrelation = WireCorrelationField.REQUEST_ID,
        authenticationMode = WireAuthenticationMode.BEARER_ACCESS,
        durableExactReplay = true,
        sync401RecoveryEligible = true,
        idempotencyKeyRequired = false,
        errorPolicies = errorPolicies(
            400 to listOf(
                ApiErrorCode.MALFORMED_JSON,
                ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                ApiErrorCode.CURSOR_INVALID,
            ),
            401 to listOf(ApiErrorCode.CREDENTIAL_UNAVAILABLE),
            403 to listOf(ApiErrorCode.DEVICE_MISMATCH),
            409 to listOf(ApiErrorCode.REQUEST_ID_COLLISION, ApiErrorCode.BOOTSTRAP_REQUIRED),
            413 to listOf(ApiErrorCode.REQUEST_TOO_LARGE),
            415 to listOf(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE),
            422 to listOf(ApiErrorCode.REQUEST_SCHEMA_INVALID),
            429 to listOf(ApiErrorCode.RATE_LIMITED),
            503 to listOf(ApiErrorCode.TEMPORARILY_UNAVAILABLE),
            retryableCodes = setOf(
                ApiErrorCode.RATE_LIMITED,
                ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            ),
        ),
    ),
    ;

    init {
        require(errorPolicies.size == errorPolicies.map { it.status to it.code }.toSet().size)
    }

    val usesBearerAccess: Boolean
        get() = authenticationMode == WireAuthenticationMode.BEARER_ACCESS

    fun policyFor(status: Int, code: ApiErrorCode): EndpointErrorPolicy? =
        errorPolicies.firstOrNull { it.status == status && it.code == code }

    companion object {
        fun fromId(value: String): M2Endpoint? = entries.firstOrNull { it.endpointId == value }
    }
}

private fun errorPolicies(
    vararg statusCodes: Pair<Int, List<ApiErrorCode>>,
    retryableCodes: Set<ApiErrorCode>,
): Set<EndpointErrorPolicy> = buildSet {
    statusCodes.forEach { (status, codes) ->
        codes.forEach { code ->
            add(
                EndpointErrorPolicy(
                    status = status,
                    code = code,
                    retryable = code in retryableCodes,
                ),
            )
        }
    }
}
