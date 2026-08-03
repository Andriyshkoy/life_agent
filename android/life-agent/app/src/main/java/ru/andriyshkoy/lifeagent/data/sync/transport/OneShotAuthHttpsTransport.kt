package ru.andriyshkoy.lifeagent.data.sync.transport

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
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
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_API_ERROR_MAX_BYTES
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_REQUEST_MEDIA_TYPE
import ru.andriyshkoy.lifeagent.data.sync.wire.MaterializedWireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.constantTimeHexEquals
import ru.andriyshkoy.lifeagent.data.sync.wire.sha256Hex

/**
 * Single-exchange HTTPS adapter for ephemeral enrollment and refresh calls.
 *
 * [execute] does not take ownership of [MaterializedWireRequest]. In
 * particular, the caller must retain and eventually close both the original
 * auth request (including its secret) and its materialized request. They are
 * needed later as the trusted expectation for strict response decoding. This
 * adapter owns only a short-lived exact copy of the materialized body.
 *
 * The supplied [callFactory] must be the production pinned client configured
 * for [configuration]: system trust plus the configured pins, no redirects,
 * retries, cookies, cache, interceptors, logging, or transparent compression.
 */
internal class OneShotAuthHttpsTransport internal constructor(
    private val callFactory: Call.Factory,
    private val configuration: M2HttpsConfiguration,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @OptIn(InternalCoroutinesApi::class)
    suspend fun execute(
        materializedRequest: MaterializedWireRequest,
    ): OneShotAuthHttpsOutcome {
        val endpoint = materializedRequest.endpoint
        require(endpoint == M2Endpoint.AUTH_ENROLL || endpoint == M2Endpoint.AUTH_REFRESH) {
            "One-shot auth HTTPS transport accepts enrollment and refresh only"
        }
        require(!endpoint.durableExactReplay && !endpoint.usesBearerAccess) {
            "One-shot auth endpoint policy is invalid"
        }
        require(!endpoint.idempotencyKeyRequired && materializedRequest.idempotencyKey == null) {
            "One-shot auth requests cannot use idempotency headers"
        }
        require(materializedRequest.bodySize in 1..endpoint.requestMaxBytes) {
            "One-shot auth request body size is invalid"
        }

        val exactBodyCopy = materializedRequest.copyBody()
        val pendingOutcome = AtomicReference<OneShotAuthHttpsOutcome?>(null)
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
            require(
                constantTimeHexEquals(
                    materializedRequest.bodySha256,
                    sha256Hex(exactBodyCopy),
                ),
            ) {
                "One-shot auth materialization digest is invalid"
            }
            val expectedUrl = configuration.endpointUrl(endpoint)
            val outcome = try {
                runInterruptible(ioDispatcher) {
                    executeBlocking(
                        endpoint = endpoint,
                        expectedUrl = expectedUrl,
                        exactBody = exactBodyCopy,
                        activeCall = activeCall,
                        cancellationRequested = cancellationRequested,
                    ).also(pendingOutcome::set)
                }
            } catch (error: IOException) {
                // Cancellation takes precedence over transport classification.
                currentCoroutineContext().ensureActive()
                OneShotAuthHttpsNetworkFailure(
                    kind = when (error) {
                        is SocketTimeoutException,
                        is InterruptedIOException,
                        -> OneShotAuthHttpsNetworkFailureKind.TIMEOUT

                        else -> OneShotAuthHttpsNetworkFailureKind.IO
                    },
                    httpStatus = null,
                )
            }

            currentCoroutineContext().ensureActive()
            pendingOutcome.compareAndSet(outcome, null)
            return outcome
        } finally {
            cancellationHandle.dispose()
            // A cancellation must reach the blocking exchange before its body
            // storage can be wiped. runInterruptible does not return until the
            // interrupted block exits; this extra cancel closes the race before
            // Call.execute() begins or while an implementation is unwinding.
            activeCall.get()?.cancel()
            pendingOutcome.getAndSet(null)?.close()
            exactBodyCopy.fill(0)
        }
    }

    private fun executeBlocking(
        endpoint: M2Endpoint,
        expectedUrl: okhttp3.HttpUrl,
        exactBody: ByteArray,
        activeCall: AtomicReference<Call?>,
        cancellationRequested: AtomicBoolean,
    ): OneShotAuthHttpsOutcome {
        val request = Request.Builder()
            .url(expectedUrl)
            .method(endpoint.method, OneShotAuthRequestBody(exactBody))
            .header(ACCEPT_ENCODING, IDENTITY_ENCODING)
            .build()
        check(request.header(AUTHORIZATION) == null)
        check(request.header(IDEMPOTENCY_KEY) == null)
        check(request.header(COOKIE) == null)

        val call = callFactory.newCall(request)
        check(activeCall.compareAndSet(null, call))
        if (cancellationRequested.get()) call.cancel()
        return try {
            call.execute().use { response ->
                classifyAuthResponse(
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

/** Closed auth transport result with permanently redacted diagnostics. */
internal sealed interface OneShotAuthHttpsOutcome : AutoCloseable {
    override fun close() = Unit
}

/** Identity-encoded response bytes awaiting strict auth response decoding. */
internal class OneShotAuthHttpsRawResponse internal constructor(
    val httpStatus: Int,
    val retryAfterSeconds: Int?,
    body: ByteArray,
) : OneShotAuthHttpsOutcome {
    private var bodyStorage: ByteArray? = body

    val bodySize: Int
        get() = checkNotNull(bodyStorage) { "One-shot auth HTTPS response is closed" }.size

    inline fun <T> consumeBody(block: (ByteArray) -> T): T {
        val owned = checkNotNull(bodyStorage) { "One-shot auth HTTPS response is closed" }
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
        "OneShotAuthHttpsRawResponse(status=$httpStatus," +
            "bodySize=${bodyStorage?.size ?: 0},redacted=true)"
}

internal class OneShotAuthHttpsNetworkFailure internal constructor(
    val kind: OneShotAuthHttpsNetworkFailureKind,
    val httpStatus: Int?,
) : OneShotAuthHttpsOutcome {
    init {
        require(
            (kind == OneShotAuthHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS) ==
                (httpStatus != null),
        )
        httpStatus?.let { require(it in UNTRUSTED_TRANSPORT_STATUSES) }
    }

    override fun toString(): String =
        "OneShotAuthHttpsNetworkFailure(kind=$kind,redacted=true)"
}

internal enum class OneShotAuthHttpsNetworkFailureKind {
    IO,
    TIMEOUT,
    UNTRUSTED_HTTP_STATUS,
}

internal class OneShotAuthHttpsProtocolFailure internal constructor(
    val kind: OneShotAuthHttpsProtocolFailureKind,
) : OneShotAuthHttpsOutcome {
    override fun toString(): String =
        "OneShotAuthHttpsProtocolFailure(kind=$kind,redacted=true)"
}

internal enum class OneShotAuthHttpsProtocolFailureKind {
    ROUTE_MISMATCH,
    TLS_REQUIRED,
    RESPONSE_HEADERS_INVALID,
    UNEXPECTED_HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    RESPONSE_LENGTH_INVALID,
}

private fun classifyAuthResponse(
    endpoint: M2Endpoint,
    expectedUrl: okhttp3.HttpUrl,
    response: Response,
): OneShotAuthHttpsOutcome {
    if (
        response.request.url != expectedUrl ||
        response.request.method != endpoint.method ||
        response.priorResponse != null ||
        response.cacheResponse != null ||
        response.request.header(AUTHORIZATION) != null ||
        response.request.header(IDEMPOTENCY_KEY) != null ||
        response.request.header(COOKIE) != null
    ) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.ROUTE_MISMATCH,
        )
    }
    if (response.handshake == null) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.TLS_REQUIRED,
        )
    }
    if (!response.headers.withinAuthContractLimits()) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
        )
    }

    if (response.code in UNTRUSTED_TRANSPORT_STATUSES) {
        return OneShotAuthHttpsNetworkFailure(
            kind = OneShotAuthHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS,
            httpStatus = response.code,
        )
    }

    val statusAllowed = response.code == 200 ||
        endpoint.errorPolicies.any { policy -> policy.status == response.code }
    if (!statusAllowed) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.UNEXPECTED_HTTP_STATUS,
        )
    }
    val validatedHeaders = response.headers.validateAuthContractHeaders(response.code)
        ?: return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
        )

    val byteLimit = if (response.code == 200) {
        endpoint.successMaxBytes
    } else {
        M2_API_ERROR_MAX_BYTES
    }
    val exactBody = try {
        readBoundedAuthBody(response.body, byteLimit)
    } catch (_: AuthResponseTooLargeException) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.RESPONSE_TOO_LARGE,
        )
    } catch (_: AuthResponseLengthInvalidException) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.RESPONSE_LENGTH_INVALID,
        )
    } catch (_: ProtocolException) {
        return OneShotAuthHttpsProtocolFailure(
            OneShotAuthHttpsProtocolFailureKind.RESPONSE_LENGTH_INVALID,
        )
    }

    var pendingBody: ByteArray? = exactBody
    return try {
        OneShotAuthHttpsRawResponse(
            httpStatus = response.code,
            retryAfterSeconds = validatedHeaders.retryAfterSeconds,
            body = exactBody,
        ).also { pendingBody = null }
    } finally {
        pendingBody?.fill(0)
    }
}

private class OneShotAuthRequestBody(
    private val bytes: ByteArray,
) : RequestBody() {
    private val writeStarted = AtomicBoolean(false)

    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        check(writeStarted.compareAndSet(false, true)) {
            "One-shot auth request body cannot be written more than once"
        }
        sink.write(bytes)
    }

    override fun toString(): String =
        "OneShotAuthRequestBody(size=${bytes.size},redacted=true)"
}

private fun readBoundedAuthBody(
    body: ResponseBody,
    byteLimit: Int,
): ByteArray {
    require(byteLimit in 1 until Int.MAX_VALUE)
    val declaredLength = body.contentLength()
    if (declaredLength < -1L) throw AuthResponseLengthInvalidException()
    if (declaredLength > byteLimit.toLong()) throw AuthResponseTooLargeException()

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
            if (read == 0) throw AuthResponseLengthInvalidException()
            total += read
            if (total > byteLimit) throw AuthResponseTooLargeException()
        }
        if (declaredLength >= 0L && total.toLong() != declaredLength) {
            throw AuthResponseLengthInvalidException()
        }
        return scratch.copyOf(total)
    } finally {
        scratch.fill(0)
    }
}

private fun Headers.withinAuthContractLimits(): Boolean {
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

private fun Headers.validateAuthContractHeaders(
    httpStatus: Int,
): ValidatedAuthContractHeaders? {
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
    // Enrollment and refresh authenticate in the body, never as Bearer.
    if (values(WWW_AUTHENTICATE).isNotEmpty()) return null

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
    return ValidatedAuthContractHeaders(retryAfter)
}

private data class ValidatedAuthContractHeaders(
    val retryAfterSeconds: Int?,
)

private class AuthResponseTooLargeException : Exception()
private class AuthResponseLengthInvalidException : Exception()

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
private const val COOKIE = "Cookie"
private const val CONTENT_TYPE = "Content-Type"
private const val CONTENT_ENCODING = "Content-Encoding"
private const val CACHE_CONTROL = "Cache-Control"
private const val WWW_AUTHENTICATE = "WWW-Authenticate"
private const val RETRY_AFTER = "Retry-After"
private const val CONTENT_LENGTH = "Content-Length"
private const val TRANSFER_ENCODING = "Transfer-Encoding"
private const val IDENTITY_ENCODING = "identity"
private const val NO_STORE = "no-store"
private const val HORIZONTAL_TAB = 0x09
private const val MAX_RESPONSE_HEADER_COUNT = 32
private const val MAX_RESPONSE_HEADER_BYTES = 16_384L
private const val MAX_RESPONSE_HEADER_NAME_BYTES = 64
private const val MAX_RESPONSE_HEADER_VALUE_BYTES = 8_192
private const val MAX_RETRY_AFTER_SECONDS = 300
