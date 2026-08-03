package ru.andriyshkoy.lifeagent.data.sync.transport

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.TlsVersion
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.local.db.ProtectedRequestClaim
import ru.andriyshkoy.lifeagent.data.security.VerifiedDurableRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import kotlin.reflect.KClass

class ExactHttpsTransportTest {
    @Test
    fun pushUsesExactRouteMethodHeadersAndSingleWriteBody() = runBlocking {
        val requestBytes = "{\"exact\":true}".encodeToByteArray()
        val originalStorage = requestBytes.copyOf()
        var observedRequest: Request? = null
        var observedBody: ByteArray? = null
        val factory = RecordingCallFactory { request ->
            observedRequest = request
            val body = checkNotNull(request.body)
            assertTrue(body.isOneShot())
            assertEquals("application/json", body.contentType().toString())
            assertEquals(requestBytes.size.toLong(), body.contentLength())
            observedBody = Buffer().let { sink ->
                body.writeTo(sink)
                sink.readByteArray()
            }
            assertThrows(IllegalStateException::class.java) {
                body.writeTo(Buffer())
            }
            validResponse(request)
        }
        val claim = claim(M2Endpoint.SYNC_PUSH, requestBytes)
        val token = WipeableSecret.ascii(VALID_ACCESS_TOKEN)

        val outcome = try {
            transport(factory).execute(claim, token)
        } finally {
            token.close()
        }

        assertTrue(outcome is ExactHttpsRawResponse)
        outcome.close()
        val request = checkNotNull(observedRequest)
        assertEquals("POST", request.method)
        assertEquals("$SYNTHETIC_ORIGIN${M2Endpoint.SYNC_PUSH.path}", request.url.toString())
        assertEquals("identity", request.header("Accept-Encoding"))
        assertEquals("Bearer $VALID_ACCESS_TOKEN", request.header("Authorization"))
        assertEquals(REQUEST_ID, request.header("Idempotency-Key"))
        assertEquals(
            setOf("Accept-Encoding", "Authorization", "Idempotency-Key"),
            request.headers.names(),
        )
        assertArrayEquals(originalStorage, checkNotNull(observedBody))
        assertAllZero(requestBytes)
        assertEquals(1, factory.newCallCount.get())
        assertEquals(1, factory.executeCount.get())
    }

    @Test
    fun rawResponseConsumeAndCloseWipeOwnedBytes() = runBlocking {
        val first = executeSuccess(responseBytes = "first".encodeToByteArray())
        var consumed: ByteArray? = null
        first.consumeBody { body ->
            consumed = body
            assertArrayEquals("first".encodeToByteArray(), body)
        }
        assertAllZero(checkNotNull(consumed))
        assertThrows(IllegalStateException::class.java) { first.bodySize }

        val second = executeSuccess(responseBytes = "second".encodeToByteArray())
        val bodyStorage = ExactHttpsRawResponse::class.java
            .getDeclaredField("bodyStorage")
            .apply { isAccessible = true }
            .get(second) as ByteArray
        second.close()
        assertAllZero(bodyStorage)
        assertThrows(IllegalStateException::class.java) { second.bodySize }
        Unit
    }

    @Test
    fun rawResponseIsWipedWhenConsumerThrows() = runBlocking {
        val response = executeSuccess(responseBytes = "consumer-failure".encodeToByteArray())
        var exposed: ByteArray? = null

        assertThrows(SyntheticConsumerException::class.java) {
            response.consumeBody { body ->
                exposed = body
                throw SyntheticConsumerException()
            }
        }

        assertAllZero(checkNotNull(exposed))
        assertThrows(IllegalStateException::class.java) { response.bodySize }
        Unit
    }

    @Test
    fun requestStorageIsWipedAfterSuccessIoFailureAndTimeout() = runBlocking {
        val cases = listOf<(Request) -> Response>(
            { request -> validResponse(request) },
            { throw IOException("synthetic") },
            { throw SocketTimeoutException("synthetic") },
        )

        cases.forEachIndexed { index, responder ->
            val storage = "{\"case\":$index}".encodeToByteArray()
            val factory = RecordingCallFactory(responder)
            val outcome = transport(factory).execute(claim(M2Endpoint.AUTH_REVOKE, storage), null)
            outcome.close()

            assertAllZero(storage)
            assertEquals(1, factory.newCallCount.get())
            assertEquals(1, factory.executeCount.get())
            if (index == 1) {
                assertEquals(ExactHttpsNetworkFailureKind.IO, (outcome as ExactHttpsNetworkFailure).kind)
            } else if (index == 2) {
                assertEquals(
                    ExactHttpsNetworkFailureKind.TIMEOUT,
                    (outcome as ExactHttpsNetworkFailure).kind,
                )
            }
        }
    }

    @Test
    fun cancellationInterruptsBlockingCallAndWipesRequestStorage() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val executeExited = AtomicBoolean(false)
        val storageWasWipedBeforeExecuteExited = AtomicBoolean(false)
        val storage = "{\"cancel\":true}".encodeToByteArray()
        val factory = RecordingCallFactory {
            started.countDown()
            try {
                while (true) Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            } catch (_: InterruptedException) {
                storageWasWipedBeforeExecuteExited.set(storage.all { it == 0.toByte() })
                executeExited.set(true)
                interrupted.countDown()
                throw InterruptedIOException("synthetic")
            }
            error("unreachable")
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val deferred = async(dispatcher) {
                transport(factory, dispatcher).execute(
                    claim(M2Endpoint.AUTH_REVOKE, storage),
                    null,
                )
            }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            deferred.cancelAndJoin()

            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            assertTrue(executeExited.get())
            assertFalse(storageWasWipedBeforeExecuteExited.get())
            assertAllZero(storage)
            assertEquals(1, factory.newCallCount.get())
            assertEquals(1, factory.executeCount.get())
            assertTrue(factory.cancelCount.get() >= 1)
        }
    }

    @Test
    fun redirectsAndUntrustedTransportStatusesAreUnreadAndSingleAttempt() = runBlocking {
        listOf(307, 408, 500, 502, 504).forEach { status ->
            val trackedBody = TrackingResponseBody("untrusted-$status".encodeToByteArray())
            val factory = RecordingCallFactory { request ->
                response(request, status, Headers.Builder().build(), trackedBody)
            }

            val outcome = transport(factory).execute(
                claim(M2Endpoint.AUTH_REVOKE, "{\"status\":$status}".encodeToByteArray()),
                null,
            )

            if (status == 307) {
                assertEquals(
                    ExactHttpsProtocolFailureKind.UNEXPECTED_HTTP_STATUS,
                    (outcome as ExactHttpsProtocolFailure).kind,
                )
            } else {
                val failure = outcome as ExactHttpsNetworkFailure
                assertEquals(ExactHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS, failure.kind)
                assertEquals(status, failure.httpStatus)
            }
            assertEquals(0, trackedBody.readCount.get())
            assertTrue(trackedBody.closed.get())
            assertEquals(1, factory.newCallCount.get())
            assertEquals(1, factory.executeCount.get())
        }
    }

    @Test
    fun gzipResponseIsRejectedBeforeBodyRead() = runBlocking {
        val trackedBody = TrackingResponseBody("compressed".encodeToByteArray())
        val headers = validHeaders().newBuilder()
            .add("Content-Encoding", "gzip")
            .build()
        val factory = RecordingCallFactory { request -> response(request, 200, headers, trackedBody) }

        val outcome = transport(factory).execute(
            claim(M2Endpoint.AUTH_REVOKE, "{}".encodeToByteArray()),
            null,
        )

        assertEquals(
            ExactHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
            (outcome as ExactHttpsProtocolFailure).kind,
        )
        assertEquals(0, trackedBody.readCount.get())
        assertTrue(trackedBody.closed.get())
    }

    @Test
    fun valid503RemainsAContractResponseForStrictWireReduction() = runBlocking {
        val trackedBody = TrackingResponseBody("{\"error\":true}".encodeToByteArray())
        val headers = validHeaders().newBuilder()
            .add("Retry-After", "7")
            .build()
        val factory = RecordingCallFactory { request -> response(request, 503, headers, trackedBody) }
        val outcome = transport(factory).execute(
            claim(M2Endpoint.AUTH_REVOKE, "{}".encodeToByteArray()),
            null,
        )

        val raw = outcome as ExactHttpsRawResponse
        assertEquals(503, raw.httpStatus)
        assertEquals(7, raw.retryAfterSeconds)
        assertTrue(trackedBody.readCount.get() > 0)
        raw.close()
    }

    @Test
    fun exactLimitAndLimitPlusOneAreEnforcedForFixedAndChunkedBodies() = runBlocking {
        val limit = M2Endpoint.AUTH_REVOKE.successMaxBytes
        listOf(false, true).forEach { chunked ->
            val atLimit = ByteArray(limit) { 7 }
            val accepted = executeWithBody(atLimit, chunked)
            assertTrue(accepted is ExactHttpsRawResponse)
            assertEquals(limit, (accepted as ExactHttpsRawResponse).bodySize)
            accepted.close()

            val overLimit = ByteArray(limit + 1) { 9 }
            val rejected = executeWithBody(overLimit, chunked)
            assertEquals(
                ExactHttpsProtocolFailureKind.RESPONSE_TOO_LARGE,
                (rejected as ExactHttpsProtocolFailure).kind,
            )
        }
    }

    @Test
    fun duplicateContractHeadersAreRejectedBeforeBodyRead() = runBlocking {
        val duplicateHeaders = listOf(
            "Content-Type" to "application/json",
            "Cache-Control" to "no-store",
            "Content-Encoding" to "identity",
            "Content-Length" to "2",
        )
        duplicateHeaders.forEach { (name, value) ->
            val trackedBody = TrackingResponseBody("{}".encodeToByteArray())
            val headers = validHeaders().newBuilder().apply {
                repeat(2 - validHeaders().values(name).size) {
                    add(name, value)
                }
            }.build()
            val factory = RecordingCallFactory { request -> response(request, 200, headers, trackedBody) }

            val outcome = transport(factory).execute(
                claim(M2Endpoint.AUTH_REVOKE, "{}".encodeToByteArray()),
                null,
            )

            assertEquals(
                "$name must be singular",
                ExactHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
                (outcome as ExactHttpsProtocolFailure).kind,
            )
            assertEquals(0, trackedBody.readCount.get())
        }
    }

    private suspend fun executeSuccess(responseBytes: ByteArray): ExactHttpsRawResponse {
        val factory = RecordingCallFactory { request ->
            validResponse(request, TrackingResponseBody(responseBytes))
        }
        return transport(factory).execute(
            claim(M2Endpoint.AUTH_REVOKE, "{}".encodeToByteArray()),
            null,
        ) as ExactHttpsRawResponse
    }

    private suspend fun executeWithBody(bytes: ByteArray, chunked: Boolean): ExactHttpsOutcome {
        val declaredLength = if (chunked) -1L else bytes.size.toLong()
        val headers = validHeaders().newBuilder().apply {
            if (chunked) add("Transfer-Encoding", "chunked")
        }.build()
        val factory = RecordingCallFactory { request ->
            response(
                request = request,
                code = 200,
                headers = headers,
                body = TrackingResponseBody(bytes, declaredLength),
            )
        }
        return transport(factory).execute(
            claim(M2Endpoint.AUTH_REVOKE, "{}".encodeToByteArray()),
            null,
        )
    }

    private fun transport(
        factory: Call.Factory,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExactHttpsTransport = ExactHttpsTransport(
        callFactory = factory,
        configuration = M2HttpsConfiguration.parse(
            rawOrigin = SYNTHETIC_ORIGIN,
            rawSpkiPins = "$FIRST_PIN,$SECOND_PIN",
        ),
        ioDispatcher = ioDispatcher,
    )

    private fun claim(endpoint: M2Endpoint, body: ByteArray): ProtectedRequestClaim.Claimed =
        ProtectedRequestClaim.Claimed(
            request = VerifiedDurableRequest(
                endpoint = endpoint,
                requestIdentity = REQUEST_ID,
                idempotencyKey = REQUEST_ID.takeIf { endpoint.idempotencyKeyRequired },
                body = body,
            ),
            attemptId = ATTEMPT_ID,
            credentialEpochId = CREDENTIAL_EPOCH_ID,
            accessGenerationUsed = 1L,
        )

    private companion object {
        const val SYNTHETIC_ORIGIN = "https://m2.invalid"
        const val REQUEST_ID = "00000000-0000-4000-8000-000000000001"
        const val ATTEMPT_ID = "00000000-0000-4000-8000-000000000002"
        const val CREDENTIAL_EPOCH_ID = "00000000-0000-4000-8000-000000000003"
        val FIRST_PIN = syntheticPin(1)
        val SECOND_PIN = syntheticPin(37)
        val VALID_ACCESS_TOKEN = "laa_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { index -> (index + 1).toByte() })
    }
}

private class RecordingCallFactory(
    private val responder: (Request) -> Response,
) : Call.Factory {
    val newCallCount = AtomicInteger(0)
    val executeCount = AtomicInteger(0)
    val cancelCount = AtomicInteger(0)

    override fun newCall(request: Request): Call {
        newCallCount.incrementAndGet()
        return FakeCall(request, executeCount, cancelCount, responder)
    }
}

private class FakeCall(
    private val originalRequest: Request,
    private val executeCount: AtomicInteger,
    private val cancelCount: AtomicInteger,
    private val responder: (Request) -> Response,
) : Call {
    private val executed = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val executingThread = AtomicReference<Thread?>(null)

    override fun request(): Request = originalRequest

    override fun execute(): Response {
        check(executed.compareAndSet(false, true))
        executeCount.incrementAndGet()
        executingThread.set(Thread.currentThread())
        return try {
            responder(originalRequest)
        } finally {
            executingThread.set(null)
        }
    }

    override fun enqueue(responseCallback: Callback) = error("enqueue is forbidden")

    override fun cancel() {
        canceled.set(true)
        cancelCount.incrementAndGet()
        executingThread.get()?.interrupt()
    }

    override fun isExecuted(): Boolean = executed.get()

    override fun isCanceled(): Boolean = canceled.get()

    override fun timeout(): Timeout = Timeout()

    override fun <T : Any> tag(type: KClass<T>): T? = null

    override fun <T> tag(type: Class<out T>): T? = null

    override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
        computeIfAbsent()

    override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
        computeIfAbsent()

    public override fun clone(): Call = FakeCall(
        originalRequest,
        executeCount,
        cancelCount,
        responder,
    )
}

private class SyntheticConsumerException : RuntimeException()

private class TrackingResponseBody(
    bytes: ByteArray,
    private val declaredLength: Long = bytes.size.toLong(),
) : ResponseBody() {
    val readCount = AtomicInteger(0)
    val closed = AtomicBoolean(false)
    private val trackedSource: BufferedSource = object : ForwardingSource(Buffer().write(bytes)) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            readCount.incrementAndGet()
            return super.read(sink, byteCount)
        }

        override fun close() {
            closed.set(true)
            super.close()
        }
    }.buffer()

    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = declaredLength

    override fun source(): BufferedSource = trackedSource
}

private fun validResponse(
    request: Request,
    body: ResponseBody = TrackingResponseBody("{}".encodeToByteArray()),
): Response = response(request, 200, validHeaders(), body)

private fun response(
    request: Request,
    code: Int,
    headers: Headers,
    body: ResponseBody,
): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_2)
    .code(code)
    .message("synthetic")
    .handshake(TEST_HANDSHAKE)
    .headers(headers)
    .body(body)
    .build()

private fun validHeaders(): Headers = Headers.Builder()
    .add("Content-Type", "application/json")
    .add("Cache-Control", "no-store")
    .build()

private fun syntheticPin(seed: Int): String = "sha256/" + Base64.getEncoder().encodeToString(
    ByteArray(32) { index -> (seed + index).toByte() },
)

private fun assertAllZero(bytes: ByteArray) {
    assertTrue("sensitive buffer was not wiped", bytes.all { it == 0.toByte() })
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val TEST_HANDSHAKE = Handshake.get(
    tlsVersion = TlsVersion.TLS_1_3,
    cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256,
    peerCertificates = emptyList(),
    localCertificates = emptyList(),
)
