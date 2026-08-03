package ru.andriyshkoy.lifeagent.data.sync.transport

import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import ru.andriyshkoy.lifeagent.BuildConfig
import ru.andriyshkoy.lifeagent.data.sync.wire.M2Endpoint

/**
 * Validated immutable production routing and certificate policy.
 *
 * The values originate in environment-backed BuildConfig fields. Keeping the
 * origin out of source prevents an internal deployment coordinate from
 * becoming an alternate application API, while strict parsing prevents the
 * environment from broadening the route at runtime.
 */
internal class M2HttpsConfiguration private constructor(
    private val origin: HttpUrl,
    private val spkiPins: List<String>,
) {
    val host: String
        get() = origin.host

    fun endpointUrl(endpoint: M2Endpoint): HttpUrl {
        require(endpoint.path.startsWith('/') && !endpoint.path.startsWith("//"))
        require('?' !in endpoint.path && '#' !in endpoint.path)

        val url = origin.newBuilder()
            .encodedPath(endpoint.path)
            .query(null)
            .fragment(null)
            .build()
        check(
            url.scheme == HTTPS_SCHEME &&
                url.host == origin.host &&
                url.port == HTTPS_DEFAULT_PORT &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.encodedPath == endpoint.path &&
                url.query == null &&
                url.fragment == null,
        ) {
            "M2 endpoint route is invalid"
        }
        return url
    }

    fun certificatePinner(): CertificatePinner = CertificatePinner.Builder()
        .also { builder ->
            spkiPins.forEach { pin -> builder.add(origin.host, pin) }
        }
        .build()

    override fun toString(): String = "M2HttpsConfiguration(redacted=true)"

    companion object {
        fun fromBuildConfig(): M2HttpsConfiguration = parse(
            rawOrigin = BuildConfig.LIFE_AGENT_API_ORIGIN,
            rawSpkiPins = BuildConfig.LIFE_AGENT_API_SPKI_PINS,
        )

        internal fun parse(
            rawOrigin: String,
            rawSpkiPins: String,
        ): M2HttpsConfiguration {
            require(rawOrigin.isNotEmpty() && rawOrigin == rawOrigin.trim()) {
                "M2 HTTPS origin is missing or malformed"
            }
            require(rawSpkiPins.isNotEmpty() && rawSpkiPins == rawSpkiPins.trim()) {
                "M2 HTTPS certificate pins are missing or malformed"
            }

            val origin = rawOrigin.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("M2 HTTPS origin is malformed")
            require(
                origin.scheme == HTTPS_SCHEME &&
                    origin.port == HTTPS_DEFAULT_PORT &&
                    origin.username.isEmpty() &&
                    origin.password.isEmpty() &&
                    origin.encodedPath == "/" &&
                    origin.query == null &&
                    origin.fragment == null,
            ) {
                "M2 HTTPS origin must be an exact root HTTPS origin"
            }

            val suppliedPins = rawSpkiPins.split(',').map(String::trim)
            require(suppliedPins.none(String::isEmpty)) {
                "M2 HTTPS certificate pins are malformed"
            }
            val uniquePins = suppliedPins
                .map(::canonicalSha256SpkiPin)
                .distinct()
            require(uniquePins.size >= MINIMUM_UNIQUE_PINS) {
                "M2 HTTPS requires at least two unique certificate pins"
            }

            return M2HttpsConfiguration(origin, uniquePins)
        }
    }
}

/** Constructs the only production-configured transport client. */
internal object ProductionM2HttpsTransportFactory {
    fun create(): ExactHttpsTransport = createPinnedTransport { configuration, client ->
        ExactHttpsTransport(
            callFactory = client,
            configuration = configuration,
        )
    }

    fun createAuth(): OneShotAuthHttpsTransport =
        createPinnedTransport { configuration, client ->
            OneShotAuthHttpsTransport(
                callFactory = client,
                configuration = configuration,
            )
        }

    private inline fun <T> createPinnedTransport(
        factory: (M2HttpsConfiguration, OkHttpClient) -> T,
    ): T {
        // Parse both environment-backed fields before allocating a client. In
        // particular, an empty pin set must fail before any Call can exist.
        val configuration = M2HttpsConfiguration.fromBuildConfig()
        val client = buildPinnedClient(configuration)
        return factory(configuration, client)
    }

    private fun buildPinnedClient(configuration: M2HttpsConfiguration): OkHttpClient {
        val client = OkHttpClient.Builder()
            .certificatePinner(configuration.certificatePinner())
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .eventListener(EventListener.NONE)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        check(client.interceptors.isEmpty() && client.networkInterceptors.isEmpty())
        return client
    }
}

private fun canonicalSha256SpkiPin(pin: String): String {
    require(SHA256_SPKI_PIN_PATTERN.matches(pin)) {
        "M2 HTTPS certificate pin is malformed"
    }
    val digest = try {
        Base64.getDecoder().decode(pin.removePrefix(SHA256_PIN_PREFIX))
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("M2 HTTPS certificate pin is malformed", error)
    }
    require(digest.size == SHA256_DIGEST_BYTES) {
        "M2 HTTPS certificate pin is malformed"
    }
    return try {
        val canonical = SHA256_PIN_PREFIX + Base64.getEncoder().encodeToString(digest)
        require(pin == canonical) {
            "M2 HTTPS certificate pin must use canonical Base64"
        }
        canonical
    } finally {
        digest.fill(0)
    }
}

private const val HTTPS_SCHEME = "https"
private const val HTTPS_DEFAULT_PORT = 443
private const val MINIMUM_UNIQUE_PINS = 2
private const val SHA256_PIN_PREFIX = "sha256/"
private const val SHA256_DIGEST_BYTES = 32
private val SHA256_SPKI_PIN_PATTERN = Regex("""sha256/[A-Za-z0-9+/]{43}=""")

private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val WRITE_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 60L
private const val CALL_TIMEOUT_SECONDS = 90L
