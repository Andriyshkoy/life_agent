package ru.andriyshkoy.lifeagent.data.sync.wire

internal object WireTestFixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing test resource $name"
        }.use { it.readBytes() }

    fun objectFrom(name: String, byteLimit: Int = 4_194_304): WireJsonObject =
        StrictJson.parse(bytes(name), StrictJsonLimits.response(byteLimit)) as WireJsonObject

    fun canonical(name: String, byteLimit: Int = 4_194_304): ByteArray =
        StrictJson.canonicalBytes(objectFrom(name, byteLimit))

    fun enrollmentRequest(): EnrollmentClaimRequest {
        val root = objectFrom("auth-enrollment-claim-request.json")
        return EnrollmentClaimRequest(
            requestId = root.requireString("request_id"),
            enrollmentCode = WipeableSecret.ascii(root.requireString("enrollment_code")),
            installationId = root.requireString("installation_id"),
            localOwnerId = root.requireString("local_owner_id"),
            replaceActiveDevice = root.requireBoolean("replace_active_device"),
        )
    }

    fun refreshRequest(): RefreshRequest {
        val root = objectFrom("auth-refresh-request.json")
        return RefreshRequest(
            requestId = root.requireString("request_id"),
            deviceId = root.requireString("device_id"),
            generation = root.requireInteger("generation", 1L..JSON_SAFE_INTEGER_MAX),
            refreshToken = WipeableSecret.ascii(root.requireString("refresh_token")),
        )
    }

    fun revokeRequest(): RevokeRequest {
        val root = objectFrom("auth-revoke-request.json")
        return RevokeRequest(
            requestId = root.requireString("request_id"),
            deviceId = root.requireString("device_id"),
            generation = root.requireInteger("generation", 1L..JSON_SAFE_INTEGER_MAX),
            refreshToken = WipeableSecret.ascii(root.requireString("refresh_token")),
        )
    }

    fun bootstrapRequest(name: String = "sync-bootstrap-request.json"): BootstrapRequest {
        val root = objectFrom(name)
        return BootstrapRequest(
            requestId = root.requireString("request_id"),
            bootstrapId = root.requireString("bootstrap_id"),
            deviceId = root.requireString("device_id"),
            pageSize = root.requireInteger("page_size", 1L..500L).toInt(),
            pageCursor = root.requireNullableString("page_cursor"),
        )
    }

    fun pullRequest(name: String = "sync-pull-request.json"): PullRequest {
        val root = objectFrom(name)
        return PullRequest(
            requestId = root.requireString("request_id"),
            deviceId = root.requireString("device_id"),
            cursor = root.requireString("cursor"),
            pageSize = root.requireInteger("page_size", 1L..500L).toInt(),
        )
    }

    fun withProperty(
        root: WireJsonObject,
        name: String,
        value: WireJsonValue,
    ): WireJsonObject = WireJsonObject(root.properties + (name to value))

    fun withPageHash(root: WireJsonObject): WireJsonObject {
        val digest = StrictJson.canonicalSha256(root.without("page_sha256"))
        return withProperty(root, "page_sha256", digest.asJson())
    }
}
