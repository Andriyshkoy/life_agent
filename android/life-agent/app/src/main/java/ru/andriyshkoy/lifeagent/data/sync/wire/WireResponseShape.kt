package ru.andriyshkoy.lifeagent.data.sync.wire

internal class WireResponseShape(
    endpoint: M2Endpoint,
    apiError: Boolean,
) : StrictJsonShape {
    private val objectFields: Map<List<String>, Set<String>> = buildMap {
        if (apiError) {
            put(emptyList(), API_ERROR_FIELDS)
            put(path("field_errors", "*"), API_FIELD_ERROR_FIELDS)
            return@buildMap
        }
        put(emptyList(), SUCCESS_ROOT_FIELDS.getValue(endpoint))
        when (endpoint) {
            M2Endpoint.AUTH_ENROLL,
            M2Endpoint.AUTH_REFRESH,
            -> put(path("credentials"), TOKEN_PAIR_FIELDS)
            M2Endpoint.AUTH_REVOKE -> Unit
            M2Endpoint.SYNC_PUSH -> {
                put(path("results", "*"), PUSH_RESULT_UNION_FIELDS)
                put(path("results", "*", "field_errors", "*"), API_FIELD_ERROR_FIELDS)
            }
            M2Endpoint.SYNC_BOOTSTRAP,
            M2Endpoint.SYNC_PULL,
            -> addChangeObjectShapes(this)
        }
    }

    private val arrayPaths: Set<List<String>> = buildSet {
        if (apiError) {
            add(path("field_errors"))
            return@buildSet
        }
        when (endpoint) {
            M2Endpoint.SYNC_PUSH -> {
                add(path("results"))
                add(path("results", "*", "field_errors"))
            }
            M2Endpoint.SYNC_BOOTSTRAP,
            M2Endpoint.SYNC_PULL,
            -> {
                add(path("changes"))
                add(path("changes", "*", "event", "evidence"))
                add(path("changes", "*", "event", "quality_flags"))
                add(path("changes", "*", "event", "revision", "parents"))
            }
            else -> Unit
        }
    }

    private val scalarKinds: Map<List<String>, Set<WireJsonKind>> = buildMap {
        if (apiError) {
            strings("protocol_version", "message_type", "error_code", "server_time")
            put(jsonPath("request_id"), STRING_OR_NULL)
            put(jsonPath("http_status"), INTEGER)
            put(jsonPath("retryable"), BOOLEAN)
            strings("field_errors.*.path", "field_errors.*.code")
            return@buildMap
        }
        strings("protocol_version", "message_type")
        when (endpoint) {
            M2Endpoint.AUTH_ENROLL -> {
                strings(
                    "request_id", "installation_id", "local_owner_id", "device_id", "person_id",
                    "server_time",
                )
                put(jsonPath("bootstrap_required"), BOOLEAN)
                addTokenScalars(this)
            }
            M2Endpoint.AUTH_REFRESH -> {
                strings("request_id", "device_id", "server_time")
                addTokenScalars(this)
            }
            M2Endpoint.AUTH_REVOKE -> {
                strings("request_id", "device_id", "status", "revoked_at", "server_time")
                put(jsonPath("generation"), INTEGER)
            }
            M2Endpoint.SYNC_PUSH -> {
                strings("batch_id", "device_id", "server_high_watermark", "server_time")
                put(jsonPath("results", "*", "ordinal"), INTEGER)
                put(jsonPath("results", "*", "operation_id"), STRING_OR_NULL)
                put(jsonPath("results", "*", "operation_content_sha256"), STRING_OR_NULL)
                strings(
                    "results.*.status", "results.*.result_code", "results.*.capture_id",
                    "results.*.event_id", "results.*.revision_id",
                    "results.*.current_revision_id", "results.*.committed_at",
                    "results.*.error_code", "results.*.field_errors.*.path",
                    "results.*.field_errors.*.code",
                )
                put(jsonPath("results", "*", "replayed"), BOOLEAN)
                put(jsonPath("results", "*", "retryable"), BOOLEAN)
                put(jsonPath("results", "*", "server_sequence"), INTEGER)
            }
            M2Endpoint.SYNC_BOOTSTRAP -> {
                strings(
                    "request_id", "bootstrap_id", "device_id", "snapshot_id", "page_id",
                    "page_sha256", "incremental_cursor", "server_time",
                )
                put(jsonPath("from_page_cursor"), STRING_OR_NULL)
                put(jsonPath("next_page_cursor"), STRING_OR_NULL)
                put(jsonPath("complete"), BOOLEAN)
                addChangeScalars(this)
            }
            M2Endpoint.SYNC_PULL -> {
                strings(
                    "request_id", "device_id", "from_cursor", "page_id", "page_sha256",
                    "next_cursor", "server_time",
                )
                put(jsonPath("has_more"), BOOLEAN)
                addChangeScalars(this)
            }
        }
    }

    override fun allowedKinds(path: List<String>): Set<WireJsonKind>? = when {
        path in objectFields -> OBJECT
        path in arrayPaths -> ARRAY
        else -> scalarKinds[path]
    }

    override fun allowedObjectKeys(path: List<String>): Set<String>? = objectFields[path]

    override fun maxArrayItems(path: List<String>): Int? = when {
        path == jsonPath("field_errors") -> 8
        path == jsonPath("results") -> M2_MAX_PUSH_OPERATIONS
        path == jsonPath("results", "*", "field_errors") -> 8
        path == jsonPath("changes") -> M2_MAX_PAGE_SIZE
        path == jsonPath("changes", "*", "event", "revision", "parents") -> 1
        else -> null
    }

    override fun compactArray(path: List<String>): Boolean =
        path == jsonPath("changes", "*", "event", "evidence") ||
            path == jsonPath("changes", "*", "event", "quality_flags")

    override fun uniqueArrayItems(path: List<String>): Boolean =
        path == jsonPath("changes", "*", "event", "quality_flags")

    private companion object {
        val API_ERROR_FIELDS = setOf(
            "protocol_version", "message_type", "request_id", "error_code", "http_status",
            "retryable", "field_errors", "server_time",
        )
        val TOKEN_PAIR_FIELDS = setOf(
            "token_type", "access_token", "access_expires_at", "refresh_token",
            "refresh_expires_at", "family_expires_at", "generation",
        )
        val SUCCESS_ROOT_FIELDS = mapOf(
            M2Endpoint.AUTH_ENROLL to setOf(
                "protocol_version", "message_type", "request_id", "installation_id",
                "local_owner_id", "device_id", "person_id", "credentials",
                "bootstrap_required", "server_time",
            ),
            M2Endpoint.AUTH_REFRESH to setOf(
                "protocol_version", "message_type", "request_id", "device_id", "credentials",
                "server_time",
            ),
            M2Endpoint.AUTH_REVOKE to setOf(
                "protocol_version", "message_type", "request_id", "device_id", "generation",
                "status", "revoked_at", "server_time",
            ),
            M2Endpoint.SYNC_PUSH to setOf(
                "protocol_version", "message_type", "batch_id", "device_id", "results",
                "server_high_watermark", "server_time",
            ),
            M2Endpoint.SYNC_BOOTSTRAP to setOf(
                "protocol_version", "message_type", "request_id", "bootstrap_id", "device_id",
                "from_page_cursor", "snapshot_id", "page_id", "page_sha256", "changes",
                "next_page_cursor", "incremental_cursor", "complete", "server_time",
            ),
            M2Endpoint.SYNC_PULL to setOf(
                "protocol_version", "message_type", "request_id", "device_id", "from_cursor",
                "page_id", "page_sha256", "changes", "next_cursor", "has_more", "server_time",
            ),
        )
        val PUSH_RESULT_UNION_FIELDS = setOf(
            "ordinal", "operation_id", "status", "operation_content_sha256", "result_code",
            "replayed", "capture_id", "event_id", "revision_id", "current_revision_id",
            "server_sequence", "committed_at", "error_code", "retryable", "field_errors",
        )
        val SERVER_CHANGE_FIELDS = setOf(
            "server_sequence", "change_kind", "result_code", "operation_id", "capture_id",
            "event_id", "revision_id", "current_revision_id", "operation_content_sha256",
            "capture", "event",
        )

        val OBJECT = setOf(WireJsonKind.OBJECT)
        val ARRAY = setOf(WireJsonKind.ARRAY)
        val STRING = setOf(WireJsonKind.STRING)
        val INTEGER = setOf(WireJsonKind.INTEGER)
        val BOOLEAN = setOf(WireJsonKind.BOOLEAN)
        val NULL = setOf(WireJsonKind.NULL)
        val STRING_OR_NULL = setOf(WireJsonKind.STRING, WireJsonKind.NULL)
        val INTEGER_OR_NULL = setOf(WireJsonKind.INTEGER, WireJsonKind.NULL)

        fun addChangeObjectShapes(target: MutableMap<List<String>, Set<String>>) {
            target[path("changes", "*")] = SERVER_CHANGE_FIELDS
            target[path("changes", "*", "capture")] = CAPTURE_FIELDS
            target[path("changes", "*", "capture", "identity")] = IDENTITY_FIELDS
            target[path("changes", "*", "capture", "source")] = CAPTURE_SOURCE_FIELDS
            target[path("changes", "*", "capture", "source", "origin")] =
                CAPTURE_ORIGIN_FIELDS
            target[path("changes", "*", "capture", "source", "collector")] =
                COLLECTOR_FIELDS
            target[path("changes", "*", "capture", "content")] = CONTENT_FIELDS
            target[path("changes", "*", "capture", "content", "payload")] =
                NOTE_PAYLOAD_FIELDS
            target[path("changes", "*", "capture", "integrity")] = INTEGRITY_FIELDS

            target[path("changes", "*", "event")] = EVENT_FIELDS
            target[path("changes", "*", "event", "identity")] = IDENTITY_FIELDS
            target[path("changes", "*", "event", "source")] = EVENT_SOURCE_FIELDS
            target[path("changes", "*", "event", "source", "origin")] = EVENT_ORIGIN_FIELDS
            target[path("changes", "*", "event", "source", "collector")] = COLLECTOR_FIELDS
            target[path("changes", "*", "event", "time")] = EVENT_TIME_FIELDS
            target[path("changes", "*", "event", "payload")] = NOTE_PAYLOAD_FIELDS
            target[path("changes", "*", "event", "evidence", "*")] = EVIDENCE_FIELDS
            target[path("changes", "*", "event", "revision")] = REVISION_FIELDS
            target[path("changes", "*", "event", "revision", "parents", "*")] = PARENT_FIELDS
            target[path("changes", "*", "event", "server")] = SERVER_FIELDS
        }

        fun addTokenScalars(target: MutableMap<List<String>, Set<WireJsonKind>>) {
            listOf(
                "token_type", "access_token", "access_expires_at", "refresh_token",
                "refresh_expires_at", "family_expires_at",
            ).forEach { name -> target[jsonPath("credentials", name)] = STRING }
            target[jsonPath("credentials", "generation")] = INTEGER
        }

        fun addChangeScalars(target: MutableMap<List<String>, Set<WireJsonKind>>) {
            listOf(
                "change_kind", "result_code", "operation_id", "capture_id", "event_id",
                "revision_id", "current_revision_id", "operation_content_sha256",
            ).forEach { name -> target[jsonPath("changes", "*", name)] = STRING }
            target[jsonPath("changes", "*", "server_sequence")] = INTEGER

            listOf("schema_version", "persistence_state", "capture_id", "operation_id")
                .forEach { name ->
                    target[jsonPath("changes", "*", "capture", name)] = STRING
                }
            addIdentityScalars(target, "capture")
            listOf("channel", "recorded_at", "timezone_id").forEach { name ->
                target[jsonPath("changes", "*", "capture", "source", name)] = STRING
            }
            target[jsonPath("changes", "*", "capture", "source", "utc_offset_minutes")] =
                INTEGER
            listOf("provider", "app", "device", "source_record_id", "source_record_version")
                .forEach { name ->
                    target[
                        jsonPath("changes", "*", "capture", "source", "origin", name)
                    ] = STRING_OR_NULL
                }
            target[
                jsonPath("changes", "*", "capture", "source", "origin", "user_entered")
            ] = BOOLEAN
            addCollectorScalars(target, "capture")
            listOf("kind", "record_type").forEach { name ->
                target[jsonPath("changes", "*", "capture", "content", name)] = STRING
            }
            target[jsonPath("changes", "*", "capture", "content", "payload", "text")] =
                STRING
            target[jsonPath("changes", "*", "capture", "integrity", "sha256")] = STRING
            target[jsonPath("changes", "*", "capture", "integrity", "byte_size")] = INTEGER

            listOf(
                "schema_version", "persistence_state", "event_id", "revision_id", "kind",
                "assertion_status", "record_status", "verification_status",
            ).forEach { name -> target[jsonPath("changes", "*", "event", name)] = STRING }
            target[jsonPath("changes", "*", "event", "revision_no")] = INTEGER
            target[jsonPath("changes", "*", "event", "lifecycle")] = NULL
            addIdentityScalars(target, "event")
            listOf("capture_id", "operation_id", "channel", "recorded_at").forEach { name ->
                target[jsonPath("changes", "*", "event", "source", name)] = STRING
            }
            listOf("source_record_id", "source_record_version", "source_modified_at")
                .forEach { name ->
                    target[jsonPath("changes", "*", "event", "source", name)] = STRING_OR_NULL
                }
            listOf("provider", "app", "device").forEach { name ->
                target[
                    jsonPath("changes", "*", "event", "source", "origin", name)
                ] = STRING_OR_NULL
            }
            target[
                jsonPath("changes", "*", "event", "source", "origin", "user_entered")
            ] = BOOLEAN
            addCollectorScalars(target, "event")
            listOf(
                "effective_start_utc", "effective_end_utc", "original_local_start",
                "original_local_end", "local_date", "source_expression",
            ).forEach { name ->
                target[jsonPath("changes", "*", "event", "time", name)] = STRING_OR_NULL
            }
            listOf("timezone_id", "temporal_precision").forEach { name ->
                target[jsonPath("changes", "*", "event", "time", name)] = STRING
            }
            listOf("start_offset_seconds", "end_offset_seconds").forEach { name ->
                target[jsonPath("changes", "*", "event", "time", name)] = INTEGER_OR_NULL
            }
            target[jsonPath("changes", "*", "event", "payload", "text")] = STRING
            listOf("capture_ref", "field_path").forEach { name ->
                target[jsonPath("changes", "*", "event", "evidence", "*", name)] = STRING
            }
            listOf("artifact_id", "locator", "excerpt").forEach { name ->
                target[jsonPath("changes", "*", "event", "evidence", "*", name)] =
                    STRING_OR_NULL
            }
            target[
                jsonPath("changes", "*", "event", "evidence", "*", "human_confirmed")
            ] = BOOLEAN
            target[jsonPath("changes", "*", "event", "quality_flags", "*")] = STRING
            listOf("created_at", "content_sha256", "actor").forEach { name ->
                target[jsonPath("changes", "*", "event", "revision", name)] = STRING
            }
            target[jsonPath("changes", "*", "event", "revision", "correction_reason")] =
                STRING_OR_NULL
            listOf("revision_id", "relation").forEach { name ->
                target[
                    jsonPath("changes", "*", "event", "revision", "parents", "*", name)
                ] = STRING
            }
            target[jsonPath("changes", "*", "event", "server", "received_at")] = STRING
            target[jsonPath("changes", "*", "event", "server", "server_sequence")] = INTEGER
        }

        fun addIdentityScalars(
            target: MutableMap<List<String>, Set<WireJsonKind>>,
            owner: String,
        ) {
            listOf("installation_id", "local_owner_id", "device_id").forEach { name ->
                target[jsonPath("changes", "*", owner, "identity", name)] = STRING
            }
        }

        fun addCollectorScalars(
            target: MutableMap<List<String>, Set<WireJsonKind>>,
            owner: String,
        ) {
            listOf("name", "version").forEach { name ->
                target[jsonPath("changes", "*", owner, "source", "collector", name)] = STRING
            }
        }

        fun MutableMap<List<String>, Set<WireJsonKind>>.strings(vararg paths: String) {
            paths.forEach { encoded -> this[encoded.split('.')] = STRING }
        }

        fun path(vararg segments: String): List<String> = segments.toList()

        fun jsonPath(vararg segments: String): List<String> = segments.toList()
    }
}
