package ru.andriyshkoy.lifeagent.data.export

import java.math.BigInteger

const val LIFE_AGENT_EXPORT_FORMAT = "life-agent"
const val LIFE_AGENT_EXPORT_FORMAT_VERSION = "1.0.0"
const val LIFE_AGENT_EXPORT_FILENAME = "life-agent-export.json"
const val WELLBEING_CATALOG_KIND = "wellbeing_dimension"
const val WELLBEING_CATALOG_SCHEMA_VERSION = "1.0.0"

data class CatalogItemExportSnapshot(
    val catalogItemId: String,
    val localOwnerId: String,
    val catalogKind: String,
    val createdAt: String,
)

data class CatalogVersionExportSnapshot(
    val catalogVersionId: String,
    val catalogItemId: String,
    val versionNo: Int,
    val schemaVersion: String,
    val payload: CanonicalCatalogPayloadJson,
    val contentSha256: String,
    val createdAt: String,
)

data class CatalogHeadExportSnapshot(
    val catalogItemId: String,
    val currentVersionId: String,
    val updatedAt: String,
)

data class CatalogExportSnapshot(
    val items: List<CatalogItemExportSnapshot>,
    val versions: List<CatalogVersionExportSnapshot>,
    val heads: List<CatalogHeadExportSnapshot>,
) {
    companion object {
        val Empty = CatalogExportSnapshot(
            items = emptyList(),
            versions = emptyList(),
            heads = emptyList(),
        )
    }
}

data class EventPointerExportSnapshot(
    val eventId: String,
    val currentRevisionId: String,
)

/** Canonical, closed aggregate payload for one wellbeing-dimension version. */
class CanonicalCatalogPayloadJson private constructor(
    internal val document: CanonicalJsonObject,
    private val canonicalBytes: ByteArray,
) {
    fun toByteArray(): ByteArray = canonicalBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CanonicalCatalogPayloadJson &&
            canonicalBytes.contentEquals(other.canonicalBytes)

    override fun hashCode(): Int = canonicalBytes.contentHashCode()

    override fun toString(): String =
        "CanonicalCatalogPayloadJson(${canonicalBytes.size} bytes)"

    companion object {
        fun fromJson(bytes: ByteArray): CanonicalCatalogPayloadJson {
            val document = CanonicalJson.parse(bytes) as? CanonicalJsonObject
                ?: throw LifeAgentExportFormatException(
                    "canonical catalog payload must be a JSON object",
                )
            return fromDocument(document)
        }

        internal fun fromDocument(
            document: CanonicalJsonObject,
        ): CanonicalCatalogPayloadJson = CanonicalCatalogPayloadJson(
            document = document,
            canonicalBytes = CanonicalJson.encode(document),
        )
    }
}

/** Canonical local life-event revision already materialized by persistence. */
class CanonicalLifeEventJson private constructor(
    internal val document: CanonicalJsonObject,
    private val canonicalBytes: ByteArray,
) {
    fun toByteArray(): ByteArray = canonicalBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CanonicalLifeEventJson && canonicalBytes.contentEquals(other.canonicalBytes)

    override fun hashCode(): Int = canonicalBytes.contentHashCode()

    override fun toString(): String =
        "CanonicalLifeEventJson(${canonicalBytes.size} bytes)"

    companion object {
        fun fromJson(bytes: ByteArray): CanonicalLifeEventJson {
            val document = CanonicalJson.parse(bytes) as? CanonicalJsonObject
                ?: throw LifeAgentExportFormatException(
                    "canonical event revision must be a JSON object",
                )
            return fromDocument(document)
        }

        internal fun fromDocument(document: CanonicalJsonObject): CanonicalLifeEventJson =
            CanonicalLifeEventJson(
                document = document,
                canonicalBytes = CanonicalJson.encode(document),
            )
    }
}

data class LifeAgentExportSnapshot(
    val catalogs: CatalogExportSnapshot,
    val events: List<EventPointerExportSnapshot>,
    val revisions: List<CanonicalLifeEventJson>,
)

open class LifeAgentExportException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class LifeAgentExportFormatException(
    message: String,
    cause: Throwable? = null,
) : LifeAgentExportException(message, cause)

class LifeAgentExportValidationException(
    val violations: List<String>,
) : LifeAgentExportException(
    violations.joinToString(
        prefix = "Invalid Life Agent export: ",
        separator = "; ",
    ),
)

/** Exact BigInteger-to-Int conversion that is available on every supported API. */
internal fun BigInteger.toLocalIntExactOrNull(): Int? =
    if (this < LOCAL_INT_MIN || this > LOCAL_INT_MAX) null else toInt()

private val LOCAL_INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
private val LOCAL_INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
