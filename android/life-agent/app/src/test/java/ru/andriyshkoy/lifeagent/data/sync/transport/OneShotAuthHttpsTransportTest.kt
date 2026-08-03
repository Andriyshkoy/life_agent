package ru.andriyshkoy.lifeagent.data.sync.transport

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint
import ru.andriyshkoy.lifeagent.data.sync.wire.M2_API_ERROR_MAX_BYTES
import ru.andriyshkoy.lifeagent.data.sync.wire.M2WireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.MaterializedWireRequest
import ru.andriyshkoy.lifeagent.data.sync.wire.WipeableSecret
import ru.andriyshkoy.lifeagent.data.sync.wire.WireRequestCodec
import ru.andriyshkoy.lifeagent.data.sync.wire.WireTestFixtures
import kotlin.reflect.KClass

class OneShotAuthHttpsTransportTest {
    @Test
    fun enrollAndRefreshUseExactRouteHeadersAndBodyWithoutTakingCallerOwnership() = runBlocking {
        listOf(M2Endpoint.AUTH_ENROLL, M2Endpoint.AUTH_REFRESH).forEach { endpoint ->
            authInputs(endpoint).use { inputs ->
                val expectedBody = inputs.materialized.copyBody()
                var observedRequest: Request? = null
                var observedBody: ByteArray? = null
                var transportBodyStorage: ByteArray? = null
                val factory = AuthRecordingCallFactory { request ->
                    observedRequest = request
                    val body = checkNotNull(request.body)
                    transportBodyStorage = authRequestBodyStorage(request)
                    assertTrue(body.isOneShot())
                    assertEquals("application/json", body.contentType().toString())
                    assertEquals(expectedBody.size.toLong(), body.contentLength())
                    observedBody = Buffer().let { sink ->
                        body.writeTo(sink)
                        sink.readByteArray()
                    }
                    assertThrows(IllegalStateException::class.java) {
                        body.writeTo(Buffer())
                    }
                    validAuthResponse(request)
                }

                val outcome = transport(factory).execute(inputs.materialized)

                assertTrue(outcome is OneShotAuthHttpsRawResponse)
                outcome.close()
                val request = checkNotNull(observedRequest)
                assertEquals(endpoint.method, request.method)
                assertEquals("$SYNTHETIC_ORIGIN${endpoint.path}", request.url.toString())
                assertEquals("identity", request.header("Accept-Encoding"))
                assertNull(request.header("Authorization"))
                assertNull(request.header("Idempotency-Key"))
                assertNull(request.header("Cookie"))
                assertEquals(setOf("Accept-Encoding"), request.headers.names())
                assertArrayEquals(expectedBody, checkNotNull(observedBody))
                assertAllZero(checkNotNull(transportBodyStorage))
                // Both caller-owned values remain available for the later
                // strict response expectation and are closed by the caller.
                assertArrayEquals(expectedBody, inputs.materialized.copyBody())
                assertTrue(inputs.secret.size > 0)
                assertEquals(1, factory.newCallCount.get())
                assertEquals(1, factory.executeCount.get())
                expectedBody.fill(0)
                observedBody?.fill(0)
            }
        }
    }

    @Test
    fun rejectsEveryNonEnrollOrRefreshEndpointBeforeAllocatingACall() = runBlocking {
        authInputs(M2Endpoint.AUTH_REVOKE).use { inputs ->
            val before = inputs.materialized.copyBody()
            val factory = AuthRecordingCallFactory { request -> validAuthResponse(request) }

            val failure = runCatching {
                transport(factory).execute(inputs.materialized)
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(0, factory.newCallCount.get())
            assertEquals(0, factory.executeCount.get())
            assertArrayEquals(before, inputs.materialized.copyBody())
            assertTrue(inputs.secret.size > 0)
            before.fill(0)
        }
    }

    @Test
    fun redirectAndPreviouslyRedirectedResponsesAreRejectedWithoutAnotherExchange() = runBlocking {
        authInputs(M2Endpoint.AUTH_ENROLL).use { directInputs ->
            val directBody = AuthTrackingResponseBody("redirect".encodeToByteArray())
            val directFactory = AuthRecordingCallFactory { request ->
                authResponse(
                    request = request,
                    code = 307,
                    headers = Headers.Builder().add("Location", "/elsewhere").build(),
                    body = directBody,
                )
            }

            val directOutcome = transport(directFactory).execute(directInputs.materialized)

            assertEquals(
                OneShotAuthHttpsProtocolFailureKind.UNEXPECTED_HTTP_STATUS,
                (directOutcome as OneShotAuthHttpsProtocolFailure).kind,
            )
            assertEquals(0, directBody.readCount.get())
            assertTrue(directBody.closed.get())
            assertEquals(1, directFactory.newCallCount.get())
            assertEquals(1, directFactory.executeCount.get())
        }

        authInputs(M2Endpoint.AUTH_REFRESH).use { followedInputs ->
            val finalBody = AuthTrackingResponseBody("followed".encodeToByteArray())
            val followedFactory = AuthRecordingCallFactory { request ->
                val prior = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_2)
                    .code(307)
                    .message("synthetic redirect")
                    .handshake(AUTH_TEST_HANDSHAKE)
                    .headers(Headers.Builder().add("Location", "/elsewhere").build())
                    .build()
                validAuthResponse(request, finalBody).newBuilder()
                    .priorResponse(prior)
                    .build()
            }

            val followedOutcome = transport(followedFactory).execute(followedInputs.materialized)

            assertEquals(
                OneShotAuthHttpsProtocolFailureKind.ROUTE_MISMATCH,
                (followedOutcome as OneShotAuthHttpsProtocolFailure).kind,
            )
            assertEquals(0, finalBody.readCount.get())
            assertTrue(finalBody.closed.get())
            assertEquals(1, followedFactory.newCallCount.get())
            assertEquals(1, followedFactory.executeCount.get())
        }
    }

    @Test
    fun timeoutIsContentFreeAndWipesOnlyTheTransportCopy() = runBlocking {
        authInputs(M2Endpoint.AUTH_REFRESH).use { inputs ->
            val callerBody = inputs.materialized.copyBody()
            var transportBodyStorage: ByteArray? = null
            val factory = AuthRecordingCallFactory { request ->
                transportBodyStorage = authRequestBodyStorage(request)
                throw SocketTimeoutException("synthetic secret-shaped timeout")
            }

            val outcome = transport(factory).execute(inputs.materialized)

            assertEquals(
                OneShotAuthHttpsNetworkFailureKind.TIMEOUT,
                (outcome as OneShotAuthHttpsNetworkFailure).kind,
            )
            assertNull(outcome.httpStatus)
            assertAllZero(checkNotNull(transportBodyStorage))
            assertArrayEquals(callerBody, inputs.materialized.copyBody())
            assertTrue(inputs.secret.size > 0)
            assertFalse(outcome.toString().contains("secret-shaped"))
            assertEquals(1, factory.newCallCount.get())
            assertEquals(1, factory.executeCount.get())
            callerBody.fill(0)
        }
    }

    @Test
    fun cancellationCancelsExchangeBeforeTransportCopyIsWiped() = runBlocking {
        authInputs(M2Endpoint.AUTH_ENROLL).use { inputs ->
            val callerBody = inputs.materialized.copyBody()
            val started = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val executeExited = AtomicBoolean(false)
            val storageWasWipedBeforeExecuteExited = AtomicBoolean(false)
            val capturedStorage = AtomicReference<ByteArray?>()
            val factory = AuthRecordingCallFactory { request ->
                val storage = authRequestBodyStorage(request)
                capturedStorage.set(storage)
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
                    transport(factory, dispatcher).execute(inputs.materialized)
                }

                assertTrue(started.await(5, TimeUnit.SECONDS))
                deferred.cancelAndJoin()

                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                assertTrue(executeExited.get())
                assertFalse(storageWasWipedBeforeExecuteExited.get())
                assertAllZero(checkNotNull(capturedStorage.get()))
                assertArrayEquals(callerBody, inputs.materialized.copyBody())
                assertTrue(inputs.secret.size > 0)
                assertEquals(1, factory.newCallCount.get())
                assertEquals(1, factory.executeCount.get())
                assertTrue(factory.cancelCount.get() >= 1)
            }
            callerBody.fill(0)
        }
    }

    @Test
    fun responseAndErrorBodiesEnforceExactLimitAndLimitPlusOne() = runBlocking {
        listOf(200, 422).forEach { status ->
            listOf(false, true).forEach { chunked ->
                val limit = if (status == 200) {
                    M2Endpoint.AUTH_ENROLL.successMaxBytes
                } else {
                    M2_API_ERROR_MAX_BYTES
                }
                val accepted = executeWithResponseBody(
                    status = status,
                    bytes = ByteArray(limit) { 7 },
                    chunked = chunked,
                )
                assertTrue(accepted is OneShotAuthHttpsRawResponse)
                assertEquals(limit, (accepted as OneShotAuthHttpsRawResponse).bodySize)
                accepted.close()

                val rejected = executeWithResponseBody(
                    status = status,
                    bytes = ByteArray(limit + 1) { 9 },
                    chunked = chunked,
                )
                assertEquals(
                    OneShotAuthHttpsProtocolFailureKind.RESPONSE_TOO_LARGE,
                    (rejected as OneShotAuthHttpsProtocolFailure).kind,
                )
            }
        }
    }

    @Test
    fun strictIdentityAndBoundedErrorHeadersAreRequiredBeforeBodyRead() = runBlocking {
        val invalidHeaders = listOf(
            validAuthHeaders().newBuilder().add("Content-Encoding", "gzip").build(),
            validAuthHeaders().newBuilder().add("Content-Type", "application/json").build(),
            validAuthHeaders().newBuilder().add("Cache-Control", "no-store").build(),
            validAuthHeaders().newBuilder().add("WWW-Authenticate", "Bearer").build(),
            validAuthHeaders().newBuilder().add("X-Oversized", "x".repeat(8_193)).build(),
        )
        invalidHeaders.forEach { headers ->
            authInputs(M2Endpoint.AUTH_REFRESH).use { inputs ->
                val trackedBody = AuthTrackingResponseBody("untrusted".encodeToByteArray())
                val factory = AuthRecordingCallFactory { request ->
                    authResponse(request, 401, headers, trackedBody)
                }

                val outcome = transport(factory).execute(inputs.materialized)

                assertEquals(
                    OneShotAuthHttpsProtocolFailureKind.RESPONSE_HEADERS_INVALID,
                    (outcome as OneShotAuthHttpsProtocolFailure).kind,
                )
                assertEquals(0, trackedBody.readCount.get())
                assertTrue(trackedBody.closed.get())
            }
        }
    }

    @Test
    fun untrustedTransportStatusesAreUnreadAndContentFree() = runBlocking {
        listOf(408, 500, 502, 504).forEach { status ->
            authInputs(M2Endpoint.AUTH_ENROLL).use { inputs ->
                val trackedBody = AuthTrackingResponseBody("untrusted-$status".encodeToByteArray())
                val factory = AuthRecordingCallFactory { request ->
                    authResponse(request, status, Headers.Builder().build(), trackedBody)
                }

                val outcome = transport(factory).execute(inputs.materialized)

                val failure = outcome as OneShotAuthHttpsNetworkFailure
                assertEquals(
                    OneShotAuthHttpsNetworkFailureKind.UNTRUSTED_HTTP_STATUS,
                    failure.kind,
                )
                assertEquals(status, failure.httpStatus)
                assertEquals(0, trackedBody.readCount.get())
                assertTrue(trackedBody.closed.get())
                assertEquals(1, factory.newCallCount.get())
                assertEquals(1, factory.executeCount.get())
            }
        }
    }

    @Test
    fun rawOutcomeConsumeAndCloseWipeOwnedBytesAndDiagnosticsStayRedacted() = runBlocking {
        val first = executeSuccess("sensitive-auth-response".encodeToByteArray())
        var consumed: ByteArray? = null
        val diagnostic = first.toString()
        first.consumeBody { body ->
            consumed = body
            assertArrayEquals("sensitive-auth-response".encodeToByteArray(), body)
        }
        assertAllZero(checkNotNull(consumed))
        assertThrows(IllegalStateException::class.java) { first.bodySize }
        assertFalse(diagnostic.contains("sensitive-auth-response"))

        val second = executeSuccess("close-me".encodeToByteArray())
        val storage = OneShotAuthHttpsRawResponse::class.java
            .getDeclaredField("bodyStorage")
            .apply { isAccessible = true }
            .get(second) as ByteArray
        second.close()
        assertAllZero(storage)
        assertThrows(IllegalStateException::class.java) { second.bodySize }
        Unit
    }

    private suspend fun executeSuccess(responseBytes: ByteArray): OneShotAuthHttpsRawResponse =
        authInputs(M2Endpoint.AUTH_ENROLL).use { inputs ->
            val factory = AuthRecordingCallFactory { request ->
                validAuthResponse(request, AuthTrackingResponseBody(responseBytes))
            }
            transport(factory).execute(inputs.materialized) as OneShotAuthHttpsRawResponse
        }

    private suspend fun executeWithResponseBody(
        status: Int,
        bytes: ByteArray,
        chunked: Boolean,
    ): OneShotAuthHttpsOutcome = authInputs(M2Endpoint.AUTH_ENROLL).use { inputs ->
        val declaredLength = if (chunked) -1L else bytes.size.toLong()
        val headers = validAuthHeaders().newBuilder().apply {
            if (chunked) add("Transfer-Encoding", "chunked")
        }.build()
        val factory = AuthRecordingCallFactory { request ->
            authResponse(
                request = request,
                code = status,
                headers = headers,
                body = AuthTrackingResponseBody(bytes, declaredLength),
            )
        }
        transport(factory).execute(inputs.materialized)
    }

    private fun transport(
        factory: Call.Factory,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): OneShotAuthHttpsTransport = OneShotAuthHttpsTransport(
        callFactory = factory,
        configuration = M2HttpsConfiguration.parse(
            rawOrigin = SYNTHETIC_ORIGIN,
            rawSpkiPins = "$FIRST_PIN,$SECOND_PIN",
        ),
        ioDispatcher = ioDispatcher,
    )

    private fun authInputs(endpoint: M2Endpoint): AuthInputs {
        val request: M2WireRequest = when (endpoint) {
            M2Endpoint.AUTH_ENROLL -> WireTestFixtures.enrollmentRequest()
            M2Endpoint.AUTH_REFRESH -> WireTestFixtures.refreshRequest()
            M2Endpoint.AUTH_REVOKE -> WireTestFixtures.revokeRequest()
            else -> error("unsupported test endpoint")
        }
        val secret = when (request) {
            is ru.andriyshkoy.lifeagent.data.sync.wire.EnrollmentClaimRequest ->
                request.enrollmentCode

            is ru.andriyshkoy.lifeagent.data.sync.wire.RefreshRequest -> request.refreshToken
            is ru.andriyshkoy.lifeagent.data.sync.wire.RevokeRequest -> request.refreshToken
            else -> error("unsupported test request")
        }
        return AuthInputs(
            owner = request as AutoCloseable,
            secret = secret,
            materialized = WireRequestCodec.materialize(request),
        )
    }

    private companion object {
        const val SYNTHETIC_ORIGIN = "https://m2.invalid"
        val FIRST_PIN = authSyntheticPin(1)
        val SECOND_PIN = authSyntheticPin(37)
    }
}

private class AuthInputs(
    private val owner: AutoCloseable,
    val secret: WipeableSecret,
    val materialized: MaterializedWireRequest,
) : AutoCloseable {
    override fun close() {
        materialized.close()
        owner.close()
    }
}

private class AuthRecordingCallFactory(
    private val responder: (Request) -> Response,
) : Call.Factory {
    val newCallCount = AtomicInteger(0)
    val executeCount = AtomicInteger(0)
    val cancelCount = AtomicInteger(0)

    override fun newCall(request: Request): Call {
        newCallCount.incrementAndGet()
        return AuthFakeCall(request, executeCount, cancelCount, responder)
    }
}

private class AuthFakeCall(
    private val originalRequest: Request,
    private val executeCount: AtomicInteger,
    private val cancelCount: AtomicInteger,
    private val responder: (Request) -> Response,
) : Call {
    private val executed = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val executingThread = AtomicReference<Thread?>()

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

    public override fun clone(): Call = AuthFakeCall(
        originalRequest,
        executeCount,
        cancelCount,
        responder,
    )
}

private class AuthTrackingResponseBody(
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

    override fun contentType(): MediaType = AUTH_JSON_MEDIA_TYPE

    override fun contentLength(): Long = declaredLength

    override fun source(): BufferedSource = trackedSource
}

private fun validAuthResponse(
    request: Request,
    body: ResponseBody = AuthTrackingResponseBody("{}".encodeToByteArray()),
): Response = authResponse(request, 200, validAuthHeaders(), body)

private fun authResponse(
    request: Request,
    code: Int,
    headers: Headers,
    body: ResponseBody,
): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_2)
    .code(code)
    .message("synthetic")
    .handshake(AUTH_TEST_HANDSHAKE)
    .headers(headers)
    .body(body)
    .build()

private fun validAuthHeaders(): Headers = Headers.Builder()
    .add("Content-Type", "application/json")
    .add("Cache-Control", "no-store")
    .build()

private fun authRequestBodyStorage(request: Request): ByteArray {
    val body = checkNotNull(request.body)
    val field = body.javaClass.getDeclaredField("bytes").apply { isAccessible = true }
    return field.get(body) as ByteArray
}

private fun authSyntheticPin(seed: Int): String = "sha256/" +
    Base64.getEncoder().encodeToString(
        ByteArray(32) { index -> (seed + index).toByte() },
    )

private fun assertAllZero(bytes: ByteArray) {
    assertTrue("sensitive buffer was not wiped", bytes.all { it == 0.toByte() })
}

private val AUTH_JSON_MEDIA_TYPE = "application/json".toMediaType()
private val AUTH_TEST_HANDSHAKE = Handshake.get(
    tlsVersion = TlsVersion.TLS_1_3,
    cipherSuite = CipherSuite.TLS_AES_128_GCM_SHA256,
    peerCertificates = emptyList(),
    localCertificates = emptyList(),
)
