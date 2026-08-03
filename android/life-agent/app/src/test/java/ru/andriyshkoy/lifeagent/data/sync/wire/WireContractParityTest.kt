package ru.andriyshkoy.lifeagent.data.sync.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.andriyshkoy.lifeagent.data.security.REQUEST_BODY_HMAC_DOMAIN

class WireContractParityTest {
    @Test
    fun endpointConstantsMatchFrozenHttpManifest() {
        val manifest = parseManifest()
        val endpoints = manifest.requireArray("endpoints").elements.map { it as WireJsonObject }
        assertEquals(M2Endpoint.entries.size, endpoints.size)

        endpoints.forEach { frozen ->
            val endpoint = checkNotNull(M2Endpoint.fromId(frozen.requireString("id")))
            assertEquals(endpoint.method, frozen.requireString("method"))
            assertEquals(endpoint.path, frozen.requireString("path"))
            assertEquals(endpoint.requestMessageType, frozen.requireString("request_message_type"))
            assertEquals(endpoint.successMessageType, frozen.requireString("success_message_type"))
            val byteLimits = frozen.requireObject("byte_limits")
            assertEquals(
                endpoint.requestMaxBytes.toLong(),
                byteLimits.requireInteger("request_raw_max_bytes", 1L..Long.MAX_VALUE),
            )
            assertEquals(
                endpoint.successMaxBytes.toLong(),
                byteLimits.requireInteger("success_raw_max_bytes", 1L..Long.MAX_VALUE),
            )
            val correlation = frozen.requireObject("correlation")
            assertEquals(
                endpoint.requestCorrelation.wireName,
                correlation.requireString("request_body_field"),
            )
            assertEquals(
                endpoint.requestCorrelation.wireName,
                correlation.requireString("success_response_field"),
            )
            assertEquals("request_id", correlation.requireString("error_response_field"))
            assertEquals(
                endpoint.durableExactReplay,
                frozen.requireObject("request_identity").requireBoolean("durable_exact_replay"),
            )
            assertEquals(
                endpoint.sync401RecoveryEligible,
                frozen.requireBoolean("sync_401_recovery_eligible"),
            )
            assertEquals(
                endpoint.authenticationMode,
                when (frozen.requireObject("authentication").requireString("mode")) {
                    "none_plus_one_time_code" -> WireAuthenticationMode.NONE_PLUS_ONE_TIME_CODE
                    "refresh_token_body" -> WireAuthenticationMode.REFRESH_TOKEN_BODY
                    "bearer_access" -> WireAuthenticationMode.BEARER_ACCESS
                    else -> error("unknown frozen auth mode")
                },
            )
        }
    }

    @Test
    fun endpointStatusErrorRetryMatricesMatchFrozenManifest() {
        val endpoints = parseManifest().requireArray("endpoints").elements
            .map { it as WireJsonObject }
        endpoints.forEach { frozen ->
            val endpoint = checkNotNull(M2Endpoint.fromId(frozen.requireString("id")))
            val frozenPolicies = frozen.requireObject("error_policy")
                .requireArray("allowed_status_code_map")
                .elements
                .flatMap { raw ->
                    val item = raw as WireJsonObject
                    val status = item.requireInteger("http_status", 1L..999L).toInt()
                    val retryable = item.requireBoolean("retryable")
                    item.requireArray("error_codes").elements.map { codeRaw ->
                        val code = ApiErrorCode.fromWire((codeRaw as WireJsonString).value)
                        checkNotNull(code)
                        EndpointErrorPolicy(status, code, retryable)
                    }
                }
                .toSet()
            assertEquals(endpoint.errorPolicies, frozenPolicies)
        }
    }

    @Test
    fun sharedTransportAndErrorCapsMatchFrozenManifest() {
        val manifest = parseManifest()
        val transport = manifest.requireObject("transport")
        assertEquals("HTTPS", transport.requireString("protocol"))
        assertEquals(M2_REQUEST_MEDIA_TYPE, transport.requireString("request_media_type"))
        assertEquals(M2_REQUEST_MEDIA_TYPE, transport.requireString("success_media_type"))
        assertEquals(M2_SUCCESS_STATUS.toLong(), transport.requireInteger("success_status", 1L..999L))
        assertEquals(
            M2_API_ERROR_MAX_BYTES.toLong(),
            transport.requireObject("api_error").requireInteger("raw_max_bytes", 1L..Long.MAX_VALUE),
        )
    }

    @Test
    fun endpointSetIsClosedAndNetworkRuntimeIsStillAbsent() {
        assertEquals(
            setOf(
                "auth_enroll", "auth_refresh", "auth_revoke", "sync_push",
                "sync_bootstrap", "sync_pull",
            ),
            M2Endpoint.entries.map { it.endpointId }.toSet(),
        )
        assertTrue(M2Endpoint.entries.all { it.method == "POST" && it.path.startsWith("/api/v1/") })
        assertFalse(M2Endpoint.AUTH_ENROLL.usesBearerAccess)
        assertTrue(M2Endpoint.SYNC_PUSH.usesBearerAccess)
    }

    @Test
    fun durableAndroidHmacFramingMatchesFrozenManifestAndSchema() {
        val manifest = parseManifest()
        val execution = manifest.requireObject("client_policy")
            .requireObject("android_execution")
        val contract = execution.requireObject("raw_body_fingerprint_contract")
        assertEquals("HMAC-SHA-256", contract.requireString("algorithm"))
        assertEquals(REQUEST_BODY_HMAC_DOMAIN, contract.requireString("domain"))
        assertEquals(
            listOf(
                "domain",
                "endpoint_id",
                "protocol_version",
                "local_credential_epoch_id",
                "device_id",
                "key_epoch_uint64_be",
                "exact_raw_body_octets",
            ),
            contract.requireArray("input_order").elements.map {
                (it as WireJsonString).value
            },
        )
        assertEquals(
            "unsigned_uint64_big_endian_octet_length_before_every_input_component",
            contract.requireString("length_prefix_encoding"),
        )
        assertEquals(
            "exactly_8_octets_unsigned_uint64_big_endian",
            contract.requireString("key_epoch_encoding"),
        )
        assertEquals(
            M2Endpoint.entries.filter { it.durableExactReplay }.map { it.endpointId }.toSet(),
            execution.requireArray("durable_request_endpoint_ids").elements.map {
                (it as WireJsonString).value
            }.toSet(),
        )
        val schema = WireTestFixtures.bytes("http-api.schema.json").decodeToString()
        assertTrue(schema.contains("\"const\": \"$REQUEST_BODY_HMAC_DOMAIN\""))
        assertTrue(schema.contains("key_epoch_uint64_be"))
    }

    private fun parseManifest(): WireJsonObject =
        StrictJson.parse(
            WireTestFixtures.bytes("http-api-v1.json"),
            StrictJsonLimits.request(2 * 1024 * 1024),
        ) as WireJsonObject
}
