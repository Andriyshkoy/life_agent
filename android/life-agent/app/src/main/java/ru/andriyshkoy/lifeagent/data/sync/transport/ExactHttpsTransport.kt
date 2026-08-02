package ru.andriyshkoy.lifeagent.data.sync.transport

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedRequestClaim
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_API_ERROR_MAX_BYTES
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_REQUEST_MEDIA_TYPE
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.requireAccessToken

/**
 * Exact, single-attempt HTTPS adapter for already claimed durable requests.
 *
 * It owns no Room state and performs no wire decoding or response reduction.
 * The opaque claim is retained in every outcome so a later protected response
 * boundary can correlate the result to the exact attempt that left Room.
 */
internal class ExactHttpsTransport internal constructor(
    private val callFactory: Call.Factory,
    private val configuration: M2HttpsConfiguration,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Takes ownership of [claim]'s verified request body. The optional bearer
     * remains caller-owned; only a short-lived copied view is read here.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun execute(
        claim: ProtectedRequestClaim.Claimed,
        bearerAccessToken: WipeableSecret?,
    ): ExactHttpsOutcome {
        val verified = claim.request
        val endpoint = verified.endpoint
        val pendingOutcome = AtomicReference<ExactHttpsOutcome?>(null)
        val activeCall = AtomicReference<Call?>(null)
        val cancellationRequested = AtomicBoolean(false)
        val parentJob = checkNotNull(currentCoroutineContext()[Job])
        val cancellationHandle = parentJob.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause != null) {
                cancellationRequested.set(true)
                activeCall.get()?.cancel()
            }
        }
        try {
            require(endpoint.durableExactReplay) {
                "Exact HTTPS transport accepts durable requests only"
            }
            val authorization = authorizationHeader(endpoint, bearerAccessToken)
            val expectedUrl = configuration.endpointUrl(endpoint)

            val outcome = try {
                runInterruptible(ioDispatcher) {
                    verified.consumeBody { exactBody ->
                        executeBlocking(
                            claim = claim,
                            endpoint = endpoint,
                            expectedUrl = expectedUrl,
                            authorization = authorization,
                            exactBody = exactBody,
                            activeCall = activeCall,
                            cancellationRequested = cancellationRequested,
                        )
                    }.also(pendingOutcome::set)
                }
            } catch (error: IOException) {
                // Coroutine cancellation wins over an InterruptedIOException
                // emitted by Call.execute(). A normal timeout/IO error remains
                // a closed, content-free transport outcome.
                currentCoroutineContext().ensureActive()
                ExactHttpsNetworkFailure(
                    claim = claim,
                    kind = when (error) {
                        is SocketTimeoutException,
                        is InterruptedIOException,
                        -> ExactHttpsNetworkFailureKind.TIMEOUT

                        else -> ExactHttpsNetworkFailureKind.IO
                    },
                    httpStatus = null,
                )
            }

            currentCoroutineContext().ensureActive()
            pendingOutcome.compareAndSet(outcome, null)
            return outcome
        } finally {
            cancellationHandle.dispose()
            // If cancellation wins after the blocking call produced a raw
            // response but before ownership reaches the caller, wipe it here.
            pendingOutcome.getAndSet(null)?.close()
            verified.close()
        }
    }

    private fun executeBlocking(
        claim: ProtectedRequestClaim.Claimed,
        endpoint: M2Endpoint,
        expectedUrl: okhttp3.HttpUrl,
        authorization: String?,
        exactBody: ByteArray,
        activeCall: AtomicReference<Call?>,
        cancellationRequested: AtomicBoolean,
    ): ExactHttpsOutcome {
        require(exactBody.isNotEmpty() && exactBody.size <= endpoint.requestMaxBytes)
        val requestBuilder = Request.Builder()
            .url(expectedUrl)
            .method(endpoint.method, ExactOneShotRequestBody(exactBody))
            .header(ACCEPT_ENCODING, IDENTITY_ENCODING)
        authorization?.let { requestBuilder.header(AUTHORIZATION, it) }
        if (endpoint.idempotencyKeyRequired) {
            val key = requireNotNull(claim.request.idempotencyKey) {
                "Durable push request is missing its idempotency key"
            }
            require(key == claim.request.requestIdentity) {
                "Durable push idempotency binding is invalid"
            }
            requestBuilder.header(IDEMPOTENCY_KEY, key)
        } else {
            require(claim.request.idempotencyKey == null) {
                "Non-push durable request has an idempotency key"
            }
        }

        val call = callFactory.newCall(requestBuilder.build())
        check(activeCall.compareAndSet(null, call))
        if (cancellationRequested.get()) call.cancel()
        return try {
            call.execute().use { response ->
                classifyResponse(
                    claim = claim,
                    endpoint = endpoint,
                    expectedUrl = expectedUrl,
                    response = response,
                )
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }
}

/** Closed transport result; no implementation includes exception text or payload in toString(). */
internal sealed interface ExactHttpsOutcome : AutoCloseable {
    val claim: ProtectedRequestClaim.Claimed

    override fun close() = Unit
}

/** Exact identity-encoded response bytes awaiting protected wire reduction. */
internal class ExactHttpsRawResponse internal constructor(
    override val claim: ProtectedRequestClaim.Claimed,
    val httpStatus: Int,
    val retryAfterSeconds: Int?,
    body: ByteArray,
) : ExactHttpsOutcome {
    private var bodyStorage: ByteArray? = body

    val bodySize: Int
        get() = checkNotNull(bodyStorage) { "Exact HTTPS response is closed" }.size

    inline fun <T> consumeBody(block: (ByteArray) -> T): T {
        val owned = checkNotNull(bodyStorage) { "Exact HTTPS response is closed" }
        bodyStorage = null
        return try {
            block(owned)
        } finally {
            owned.fill(0)
        }
    }

    override fun close() {
        bodyStorage?.fill(0)
        bodyStorage = null
    }

    override fun toString(): String =
        "ExactHttpsRawResponse(status=$httpStatus,bodySize=${bodyStorage?.size ?: 0},redacted=true)"
}

internal class ExactHttpsNetworkFailure internal constructor(
    override val claim: ProtectedRequestClaim.Claimed,
    val kind: ExactHttpsNetworkFailureKind,
    val httpStatus: Int?,
) : ExactHttpsOutcome {
    init {
        require(
            (kind == ExactHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS) ==
                (httpStatus != null),
        )
        httpStatus?.let { require(it in UNTRUSTED_TRANSPORT_STATUSES) }
    }

    override fun toString(): String =
        "ExactHttpsNetworkFailure(kind=$kind,redacted=true)"
}

internal enum class ExactHttpsNetworkFailureKind {
    IO,
    TIMEOUT,
    UNTRUSTED_HTTP_STATUS,
}

internal class ExactHttpsProtocolFailure internal constructor(
    override val claim: ProtectedRequestClaim.Claimed,
    val kind: ExactHttpsProtocolFailureKind,
) : ExactHttpsOutcome {
    override fun toString(): String =
        "ExactHttpsProtocolFailure(kind=$kind,redacted=true)"
}

internal enum class ExactHttpsProtocolFailureKind {
    ROUTE_MISMATCH,
    TLS_REQUIRED,
    RESPONSE_HEADERS_INVALID,
    UNEXPECTED_HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    RESPONSE_LENGTH_INVALID,
}

private fun ExactHttpsTransport.authorizationHeader(
    endpoint: M2Endpoint,
    bearerAccessToken: WipeableSecret?,
): String? {
    if (!endpoint.usesBearerAccess) {
        require(bearerAccessToken == null) {
            "Authorization is forbidden for this M2 endpoint"
        }
        return null
    }
    val token = requireNotNull(bearerAccessToken) {
        "Bearer access token is required for this M2 endpoint"
    }
    return token.useBytes { bytes ->
        require(bytes.all { byte -> byte.toInt() and 0xff in PRINTABLE_ASCII }) {
            "Bearer access token is malformed"
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        requireAccessToken(text)
        "Bearer $text"
    }
}

private fun classifyResponse(
    claim: ProtectedRequestClaim.Claimed,
    endpoint: M2Endpoint,
    expectedUrl: okhttp3.HttpUrl,
    response: Response,
): ExactHttpsOutcome {
    if (
        response.request.url != expectedUrl ||
        response.request.method != endpoint.method ||
        response.priorResponse != null ||
        response.cacheResponse != null
    ) {
        return ExactHttpsProtocolFailure(claim, ExactHttpsProtocolFailureKind.ROUTE_MISMATCH)
    }
    if (response.handshake == null) {
        return ExactHttpsProtocolFailure(claim, ExactHttpsProtocolFailureKind.TLS_REQUIRED)
    }
    if (!response.headers.withinContractLimits()) {
        return ExactHttpsProtocolFailure(
            claim,
            ExactHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
        )
    }

    if (response.code in UNTRUSTED_TRANSPORT_STATUSES) {
        // These statuses never carry trusted contract bodies. Closing Response
        // without reading it prevents an intermediary page from entering the
        // wire decoder while preserving the durable attempt for bounded retry.
        return ExactHttpsNetworkFailure(
            claim = claim,
            kind = ExactHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS,
            httpStatus = response.code,
        )
    }

    val statusAllowed = response.code == 200 ||
        endpoint.errorPolicies.any { policy -> policy.status == response.code }
    if (!statusAllowed) {
        return ExactHttpsProtocolFailure(
            claim,
            ExactHttpsProtocolFailureKind.UNEXPECTED_HTTP_STATUS,
        )
    }
    val validatedHeaders = response.headers.validateContractResponseHeaders(
        endpoint = endpoint,
        httpStatus = response.code,
    ) ?: return ExactHttpsProtocolFailure(
        claim,
        ExactHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
    )

    val byteLimit = if (response.code == 200) {
        endpoint.successMaxBytes
    } else {
        M2_API_ERROR_MAX_BYTES
    }
    val exactBody = try {
        readBoundedIdentityBody(response.body, byteLimit)
    } catch (_: ResponseTooLargeException) {
        return ExactHttpsProtocolFailure(
            claim,
            ExactHttpsProtocolFailureKind.RESPONSE_TOO_LARGE,
        )
    } catch (_: ResponseLengthInvalidException) {
        return ExactHttpsProtocolFailure(
            claim,
            ExactHttpsProtocolFailureKind.RESPONSE_LENGTH_INVALID,
        )
    } catch (_: ProtocolException) {
        return ExactHttpsProtocolFailure(
            claim,
            ExactHttpsProtocolFailureKind.RESPONSE_LENGTH_INVALID,
        )
    }

    var pendingBody: ByteArray? = exactBody
    return try {
        ExactHttpsRawResponse(
            claim = claim,
            httpStatus = response.code,
            retryAfterSeconds = validatedHeaders.retryAfterSeconds,
            body = exactBody,
        ).also { pendingBody = null }
    } finally {
        pendingBody?.fill(0)
    }
}

private class ExactOneShotRequestBody(
    private val bytes: ByteArray,
) : RequestBody() {
    private val writeStarted = AtomicBoolean(false)

    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        check(writeStarted.compareAndSet(false, true)) {
            "Exact request body cannot be written more than once"
        }
        sink.write(bytes)
    }

    override fun toString(): String =
        "ExactOneShotRequestBody(size=${bytes.size},redacted=true)"
}

private fun readBoundedIdentityBody(
    body: ResponseBody,
    byteLimit: Int,
): ByteArray {
    require(byteLimit in 1 until Int.MAX_VALUE)
    val declaredLength = body.contentLength()
    if (declaredLength < -1L) throw ResponseLengthInvalidException()
    if (declaredLength > byteLimit.toLong()) throw ResponseTooLargeException()

    val scratch = ByteArray(byteLimit + 1)
    try {
        var total = 0
        val source = body.source()
        while (true) {
            val read = source.read(
                sink = scratch,
                offset = total,
                byteCount = scratch.size - total,
            )
            if (read == -1) break
            if (read == 0) throw ResponseLengthInvalidException()
            total += read
            if (total > byteLimit) throw ResponseTooLargeException()
        }
        if (declaredLength >= 0L && total.toLong() != declaredLength) {
            throw ResponseLengthInvalidException()
        }
        return scratch.copyOf(total)
    } finally {
        scratch.fill(0)
    }
}

private fun Headers.withinContractLimits(): Boolean {
    if (size > MAX_RESPONSE_HEADER_COUNT) return false
    var totalBytes = 0L
    for (index in 0 until size) {
        val name = name(index)
        val value = value(index)
        if (
            name.isEmpty() ||
            name.length > MAX_RESPONSE_HEADER_NAME_BYTES ||
            value.length > MAX_RESPONSE_HEADER_VALUE_BYTES ||
            name.any { it.code !in PRINTABLE_ASCII } ||
            value.any { it.code != HORIZONTAL_TAB && it.code !in PRINTABLE_ASCII }
        ) {
            return false
        }
        totalBytes += name.length.toLong() + value.length.toLong()
        if (totalBytes > MAX_RESPONSE_HEADER_BYTES) return false
    }
    return true
}

private fun Headers.validateContractResponseHeaders(
    endpoint: M2Endpoint,
    httpStatus: Int,
): ValidatedContractHeaders? {
    val contentTypes = values(CONTENT_TYPE)
    if (
        contentTypes.size != 1 ||
        !JSON_CONTENT_TYPE_PATTERN.matches(contentTypes.single())
    ) {
        return null
    }
    val encodings = values(CONTENT_ENCODING)
    if (
        encodings.size > 1 ||
        encodings.singleOrNull()?.equals(IDENTITY_ENCODING, ignoreCase = true) == false
    ) {
        return null
    }
    val cacheControl = values(CACHE_CONTROL)
    if (cacheControl.size != 1 || !cacheControl.single().equals(NO_STORE, ignoreCase = true)) {
        return null
    }

    val authenticate = values(WWW_AUTHENTICATE)
    val bearerChallengeRequired = endpoint.usesBearerAccess && httpStatus == 401
    if (
        (bearerChallengeRequired && authenticate != listOf(BEARER_SCHEME)) ||
        (!bearerChallengeRequired && authenticate.isNotEmpty())
    ) {
        return null
    }

    val contentLengths = values(CONTENT_LENGTH)
    if (
        contentLengths.size > 1 ||
        contentLengths.singleOrNull()?.let { !CANONICAL_LENGTH_PATTERN.matches(it) } == true ||
        (contentLengths.isNotEmpty() && values(TRANSFER_ENCODING).isNotEmpty())
    ) {
        return null
    }

    val retryAfterValues = values(RETRY_AFTER)
    if (retryAfterValues.size > 1) return null
    if (httpStatus !in RETRY_AFTER_STATUSES && retryAfterValues.isNotEmpty()) return null
    val retryAfter = retryAfterValues.singleOrNull()?.let { value ->
        if (!RETRY_AFTER_PATTERN.matches(value)) return null
        value.toIntOrNull()?.takeIf { it <= MAX_RETRY_AFTER_SECONDS } ?: return null
    }
    return ValidatedContractHeaders(retryAfter)
}

private data class ValidatedContractHeaders(
    val retryAfterSeconds: Int?,
)

private class ResponseTooLargeException : Exception()
private class ResponseLengthInvalidException : Exception()

private val JSON_MEDIA_TYPE = M2_REQUEST_MEDIA_TYPE.toMediaType()
private val PRINTABLE_ASCII = 0x20..0x7e
private val JSON_CONTENT_TYPE_PATTERN = Regex(
    pattern = """application/json(?:;[\t ]*charset=utf-8)?""",
    option = RegexOption.IGNORE_CASE,
)
private val CANONICAL_LENGTH_PATTERN = Regex("""0|[1-9][0-9]*""")
private val RETRY_AFTER_PATTERN = Regex("""0|[1-9][0-9]{0,2}""")
private val RETRY_AFTER_STATUSES = setOf(429, 503)
private val UNTRUSTED_TRANSPORT_STATUSES = setOf(408, 500, 502, 504)

private const val ACCEPT_ENCODING = "Accept-Encoding"
private const val AUTHORIZATION = "Authorization"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val CONTENT_TYPE = "Content-Type"
private const val CONTENT_ENCODING = "Content-Encoding"
private const val CACHE_CONTROL = "Cache-Control"
private const val WWW_AUTHENTICATE = "WWW-Authenticate"
private const val RETRY_AFTER = "Retry-After"
private const val CONTENT_LENGTH = "Content-Length"
private const val TRANSFER_ENCODING = "Transfer-Encoding"
private const val IDENTITY_ENCODING = "identity"
private const val NO_STORE = "no-store"
private const val BEARER_SCHEME = "Bearer"
private const val HORIZONTAL_TAB = 0x09
private const val MAX_RESPONSE_HEADER_COUNT = 32
private const val MAX_RESPONSE_HEADER_BYTES = 16_384L
private const val MAX_RESPONSE_HEADER_NAME_BYTES = 64
private const val MAX_RESPONSE_HEADER_VALUE_BYTES = 8_192
private const val MAX_RETRY_AFTER_SECONDS = 300
