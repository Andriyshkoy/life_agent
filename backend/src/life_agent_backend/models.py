from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from life_agent_backend.database import metadata

SAFE_INTEGER_MAX = 9_007_199_254_740_991
MAX_REPLAY_BODY_BYTES = 4_194_304
AUTH_REVOKE_MAX_REPLAY_BODY_BYTES = 16_384
SYNC_PUSH_MAX_REPLAY_BODY_BYTES = 524_288
SYNC_DATA_PROTOCOL_STREAM = "life_events"
SYNC_BOOTSTRAP_CURSOR_PROTOCOL_STREAM = "sync_bootstrap_v1"
SYNC_INCREMENTAL_CURSOR_PROTOCOL_STREAM = "sync_incremental_v1"

UUID = postgresql.UUID(as_uuid=True)
BYTEA = postgresql.BYTEA()
UTC_TIMESTAMP = sa.TIMESTAMP(timezone=True)
LOCAL_TIMESTAMP = sa.TIMESTAMP(timezone=False)


person = sa.Table(
    "person",
    metadata,
    sa.Column("person_id", UUID, primary_key=True),
    sa.Column("subject_id", UUID, nullable=False),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "created_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.UniqueConstraint("subject_id", name="uq_person_subject_id"),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)


device = sa.Table(
    "device",
    metadata,
    sa.Column("device_id", UUID, primary_key=True),
    sa.Column(
        "person_id",
        UUID,
        sa.ForeignKey("person.person_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("installation_id", UUID, nullable=False),
    sa.Column("local_owner_id", UUID, nullable=False),
    sa.Column("status", sa.String(16), nullable=False),
    sa.Column(
        "enrolled_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("last_seen_at", UTC_TIMESTAMP),
    sa.Column("revoked_at", UTC_TIMESTAMP),
    sa.Column("revoke_reason", sa.String(64)),
    sa.Column("replaced_by_device_id", UUID),
    sa.ForeignKeyConstraint(
        ["person_id", "replaced_by_device_id"],
        ["device.person_id", "device.device_id"],
        name="fk_device_person_replacement",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint("installation_id", name="uq_device_installation_id"),
    sa.UniqueConstraint("local_owner_id", name="uq_device_local_owner_id"),
    sa.UniqueConstraint("person_id", "device_id", name="uq_device_person_device"),
    sa.UniqueConstraint(
        "device_id",
        "person_id",
        "installation_id",
        "local_owner_id",
        name="uq_device_provenance_binding",
    ),
    sa.UniqueConstraint(
        "installation_id",
        "local_owner_id",
        name="uq_device_local_identity",
    ),
    sa.CheckConstraint(
        "status IN ('active', 'revoked', 'replaced')",
        name="status_allowed",
    ),
    sa.CheckConstraint(
        "("
        "status = 'active' AND revoked_at IS NULL AND revoke_reason IS NULL "
        "AND replaced_by_device_id IS NULL"
        ") OR ("
        "status = 'revoked' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL "
        "AND replaced_by_device_id IS NULL"
        ") OR ("
        "status = 'replaced' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL "
        "AND replaced_by_device_id IS NOT NULL"
        ")",
        name="status_metadata_coherent",
    ),
    sa.CheckConstraint(
        "replaced_by_device_id IS NULL OR replaced_by_device_id <> device_id",
        name="replacement_not_self",
    ),
)
sa.Index("ix_device_person_id", device.c.person_id)
sa.Index(
    "uq_device_one_active_per_person",
    device.c.person_id,
    unique=True,
    postgresql_where=sa.text("status = 'active'"),
)


device_replay_quota = sa.Table(
    "device_replay_quota",
    metadata,
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column(
        "record_count",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "response_body_plaintext_bytes",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "updated_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.ForeignKeyConstraint(
        ["person_id", "device_id"],
        ["device.person_id", "device.device_id"],
        name="fk_device_replay_quota_person_device",
        ondelete="CASCADE",
    ),
    sa.PrimaryKeyConstraint(
        "person_id",
        "device_id",
        name="pk_device_replay_quota",
    ),
    sa.CheckConstraint(
        "record_count BETWEEN 0 AND 100000",
        name="record_count_range",
    ),
    sa.CheckConstraint(
        "response_body_plaintext_bytes BETWEEN 0 AND 536870912",
        name="plaintext_bytes_range",
    ),
    sa.CheckConstraint(
        "("
        "record_count = 0 AND response_body_plaintext_bytes = 0 "
        ") OR ("
        "record_count > 0 "
        "AND response_body_plaintext_bytes >= record_count "
        ")",
        name="state_coherent",
    ),
)


credential_family = sa.Table(
    "credential_family",
    metadata,
    sa.Column("credential_family_id", UUID, primary_key=True),
    sa.Column(
        "person_id",
        UUID,
        sa.ForeignKey("person.person_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("device_id", UUID),
    sa.Column("status", sa.String(16), nullable=False),
    sa.Column("active_generation", sa.BigInteger()),
    sa.Column(
        "active_generation_current",
        sa.Boolean(),
        nullable=False,
        server_default=sa.true(),
    ),
    sa.Column(
        "created_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("activated_at", UTC_TIMESTAMP),
    sa.Column("family_expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("revoked_at", UTC_TIMESTAMP),
    sa.Column("revoke_reason", sa.String(64)),
    sa.Column("reuse_detected_at", UTC_TIMESTAMP),
    sa.Column("tombstone_until", UTC_TIMESTAMP, nullable=False),
    sa.ForeignKeyConstraint(
        ["person_id", "device_id"],
        ["device.person_id", "device.device_id"],
        name="fk_credential_family_person_device",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "credential_family_id",
            "active_generation",
            "active_generation_current",
        ],
        [
            "credential_generation.credential_family_id",
            "credential_generation.generation",
            "credential_generation.is_current",
        ],
        name="fk_credential_family_active_generation",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
        use_alter=True,
    ),
    sa.UniqueConstraint(
        "person_id",
        "credential_family_id",
        name="uq_credential_family_person_family",
    ),
    sa.UniqueConstraint(
        "credential_family_id",
        "person_id",
        "device_id",
        name="uq_credential_family_replay_namespace",
    ),
    sa.UniqueConstraint(
        "credential_family_id",
        "person_id",
        "device_id",
        "tombstone_until",
        name="uq_credential_family_replay_retention",
    ),
    sa.UniqueConstraint(
        "credential_family_id",
        "family_expires_at",
        "tombstone_until",
        name="uq_credential_family_generation_deadline",
    ),
    sa.CheckConstraint(
        "status IN ('reserved', 'active', 'revoked', 'expired')",
        name="status_allowed",
    ),
    sa.CheckConstraint(
        f"active_generation IS NULL OR active_generation BETWEEN 1 AND {SAFE_INTEGER_MAX}",
        name="active_generation_range",
    ),
    sa.CheckConstraint(
        "active_generation_current = true",
        name="active_generation_marker",
    ),
    sa.CheckConstraint(
        "("
        "status = 'reserved' AND device_id IS NULL AND active_generation IS NULL "
        "AND activated_at IS NULL"
        ") OR ("
        "status IN ('active', 'revoked', 'expired') AND device_id IS NOT NULL "
        "AND active_generation IS NOT NULL AND activated_at IS NOT NULL"
        ")",
        name="activation_binding_coherent",
    ),
    sa.CheckConstraint(
        "(status = 'revoked' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL) "
        "OR (status <> 'revoked' AND revoked_at IS NULL AND revoke_reason IS NULL)",
        name="revocation_coherent",
    ),
    sa.CheckConstraint(
        "reuse_detected_at IS NULL OR status = 'revoked'",
        name="reuse_requires_revocation",
    ),
    sa.CheckConstraint(
        "family_expires_at > created_at",
        name="family_expiry_after_creation",
    ),
    sa.CheckConstraint(
        "tombstone_until >= family_expires_at",
        name="tombstone_covers_family",
    ),
)
sa.Index("ix_credential_family_person_id", credential_family.c.person_id)
sa.Index("ix_credential_family_device_id", credential_family.c.device_id)
sa.Index("ix_credential_family_tombstone_until", credential_family.c.tombstone_until)
sa.Index(
    "uq_credential_family_one_active_per_device",
    credential_family.c.device_id,
    unique=True,
    postgresql_where=sa.text("status = 'active'"),
)


enrollment_grant = sa.Table(
    "enrollment_grant",
    metadata,
    sa.Column("enrollment_grant_id", UUID, primary_key=True),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("code_hmac", BYTEA, nullable=False),
    sa.Column("code_key_generation", sa.Integer(), nullable=False),
    sa.Column(
        "replacement_allowed",
        sa.Boolean(),
        nullable=False,
        server_default=sa.false(),
    ),
    sa.Column("status", sa.String(16), nullable=False),
    sa.Column(
        "attempt_count",
        sa.Integer(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "max_attempts",
        sa.Integer(),
        nullable=False,
        server_default=sa.text("5"),
    ),
    sa.Column(
        "issued_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("consumed_at", UTC_TIMESTAMP),
    sa.Column("revoked_at", UTC_TIMESTAMP),
    sa.Column("terminal_outcome", sa.String(40)),
    sa.Column("resolved_device_id", UUID),
    sa.ForeignKeyConstraint(
        ["person_id", "credential_family_id"],
        ["credential_family.person_id", "credential_family.credential_family_id"],
        name="fk_enrollment_grant_person_family",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["person_id", "resolved_device_id"],
        ["device.person_id", "device.device_id"],
        name="fk_enrollment_grant_person_device",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "credential_family_id",
        name="uq_enrollment_grant_credential_family",
    ),
    sa.UniqueConstraint(
        "code_key_generation",
        "code_hmac",
        name="uq_enrollment_grant_code_lookup",
    ),
    sa.CheckConstraint(
        "octet_length(code_hmac) = 32",
        name="code_hmac_length",
    ),
    sa.CheckConstraint(
        "code_key_generation > 0",
        name="code_key_generation_positive",
    ),
    sa.CheckConstraint(
        "status IN ('issued', 'consumed', 'revoked')",
        name="status_allowed",
    ),
    sa.CheckConstraint(
        "attempt_count BETWEEN 0 AND max_attempts AND max_attempts BETWEEN 1 AND 100",
        name="attempt_bounds",
    ),
    sa.CheckConstraint("expires_at > issued_at", name="expiry_after_issue"),
    sa.CheckConstraint(
        "("
        "status = 'issued' AND consumed_at IS NULL AND revoked_at IS NULL "
        "AND terminal_outcome IS NULL AND resolved_device_id IS NULL"
        ") OR ("
        "status = 'consumed' AND consumed_at IS NOT NULL AND revoked_at IS NULL "
        "AND terminal_outcome IN ("
        "'enrolled', 'active_device_exists', 'replacement_not_authorized'"
        ") AND ("
        "(terminal_outcome = 'enrolled' AND resolved_device_id IS NOT NULL) OR "
        "(terminal_outcome <> 'enrolled' AND resolved_device_id IS NULL)"
        ")"
        ") OR ("
        "status = 'revoked' AND consumed_at IS NULL AND revoked_at IS NOT NULL "
        "AND terminal_outcome IS NULL AND resolved_device_id IS NULL"
        ")",
        name="terminal_state_coherent",
    ),
)
sa.Index("ix_enrollment_grant_person_id", enrollment_grant.c.person_id)
sa.Index("ix_enrollment_grant_expires_at", enrollment_grant.c.expires_at)
sa.Index(
    "uq_enrollment_grant_one_issued_per_person",
    enrollment_grant.c.person_id,
    unique=True,
    postgresql_where=sa.text("status = 'issued'"),
)


credential_generation = sa.Table(
    "credential_generation",
    metadata,
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("generation", sa.BigInteger(), nullable=False),
    sa.Column(
        "is_current",
        sa.Boolean(),
        nullable=False,
        server_default=sa.true(),
    ),
    sa.Column("access_token_hmac", BYTEA, nullable=False),
    sa.Column("access_key_generation", sa.Integer(), nullable=False),
    sa.Column("refresh_token_hmac", BYTEA, nullable=False),
    sa.Column("refresh_key_generation", sa.Integer(), nullable=False),
    sa.Column("family_expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("family_tombstone_until", UTC_TIMESTAMP, nullable=False),
    sa.Column("issued_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("access_expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("refresh_expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("refresh_spent_at", UTC_TIMESTAMP),
    sa.Column("successor_generation", sa.BigInteger()),
    sa.Column("reuse_detected_at", UTC_TIMESTAMP),
    sa.Column("retained_until", UTC_TIMESTAMP, nullable=False),
    sa.ForeignKeyConstraint(
        [
            "credential_family_id",
            "family_expires_at",
            "family_tombstone_until",
        ],
        [
            "credential_family.credential_family_id",
            "credential_family.family_expires_at",
            "credential_family.tombstone_until",
        ],
        name="fk_credential_generation_family",
        ondelete="CASCADE",
        onupdate="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["credential_family_id", "successor_generation"],
        [
            "credential_generation.credential_family_id",
            "credential_generation.generation",
        ],
        name="fk_credential_generation_successor",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.PrimaryKeyConstraint(
        "credential_family_id",
        "generation",
        name="pk_credential_generation",
    ),
    sa.UniqueConstraint(
        "access_key_generation",
        "access_token_hmac",
        name="uq_credential_generation_access_token_lookup",
    ),
    sa.UniqueConstraint(
        "refresh_key_generation",
        "refresh_token_hmac",
        name="uq_credential_generation_refresh_token_lookup",
    ),
    sa.UniqueConstraint(
        "credential_family_id",
        "generation",
        "is_current",
        name="uq_credential_generation_current_lookup",
    ),
    sa.CheckConstraint(
        f"generation BETWEEN 1 AND {SAFE_INTEGER_MAX}",
        name="generation_range",
    ),
    sa.CheckConstraint(
        "octet_length(access_token_hmac) = 32",
        name="access_hmac_length",
    ),
    sa.CheckConstraint(
        "octet_length(refresh_token_hmac) = 32",
        name="refresh_hmac_length",
    ),
    sa.CheckConstraint(
        "access_key_generation > 0 AND refresh_key_generation > 0",
        name="key_generations_positive",
    ),
    sa.CheckConstraint(
        "issued_at < access_expires_at AND access_expires_at < refresh_expires_at "
        "AND refresh_expires_at <= family_expires_at",
        name="expiry_order",
    ),
    sa.CheckConstraint(
        "(refresh_spent_at IS NULL AND successor_generation IS NULL) OR "
        "(refresh_spent_at IS NOT NULL AND successor_generation = generation + 1)",
        name="spend_successor_coherent",
    ),
    sa.CheckConstraint(
        "(is_current = true AND refresh_spent_at IS NULL "
        "AND successor_generation IS NULL) OR "
        "(is_current = false AND refresh_spent_at IS NOT NULL "
        "AND successor_generation = generation + 1)",
        name="current_spend_coherent",
    ),
    sa.CheckConstraint(
        "reuse_detected_at IS NULL OR "
        "(refresh_spent_at IS NOT NULL AND reuse_detected_at >= refresh_spent_at)",
        name="reuse_after_spend",
    ),
    sa.CheckConstraint(
        "retained_until >= refresh_expires_at AND retained_until >= family_tombstone_until",
        name="retention_covers_refresh",
    ),
)
sa.Index(
    "ix_credential_generation_retained_until",
    credential_generation.c.retained_until,
)
sa.Index(
    "uq_credential_generation_one_current_per_family",
    credential_generation.c.credential_family_id,
    unique=True,
    postgresql_where=sa.text("is_current = true"),
)


sync_stream = sa.Table(
    "sync_stream",
    metadata,
    sa.Column("sync_stream_id", UUID, primary_key=True),
    sa.Column(
        "person_id",
        UUID,
        sa.ForeignKey("person.person_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column(
        "protocol_stream",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'life_events'"),
    ),
    sa.Column(
        "last_server_sequence",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "minimum_available_sequence",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "created_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column(
        "updated_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.UniqueConstraint(
        "person_id",
        "protocol_stream",
        name="uq_sync_stream_person_protocol",
    ),
    sa.UniqueConstraint(
        "sync_stream_id",
        "person_id",
        name="uq_sync_stream_stream_person",
    ),
    sa.CheckConstraint(
        "protocol_stream = 'life_events'",
        name="protocol_stream_supported",
    ),
    sa.CheckConstraint(
        f"last_server_sequence BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="last_sequence_range",
    ),
    sa.CheckConstraint(
        f"minimum_available_sequence BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="minimum_sequence_range",
    ),
    sa.CheckConstraint(
        "minimum_available_sequence <= last_server_sequence + 1",
        name="history_window_order",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)


sync_operation_registry = sa.Table(
    "sync_operation_registry",
    metadata,
    sa.Column("operation_id", UUID, primary_key=True),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("sync_stream_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("submitting_device_id", UUID, nullable=False),
    sa.Column("installation_id", UUID, nullable=False),
    sa.Column("local_owner_id", UUID, nullable=False),
    sa.Column("client_sequence", sa.BigInteger(), nullable=False),
    sa.Column("first_batch_id", UUID, nullable=False),
    sa.Column("first_batch_ordinal", sa.SmallInteger(), nullable=False),
    sa.Column("capture_id", UUID, nullable=False),
    sa.Column("event_id", UUID, nullable=True),
    sa.Column("revision_id", UUID, nullable=False),
    sa.Column("expected_current_revision_id", UUID),
    sa.Column("operation_content_sha256", BYTEA, nullable=False),
    sa.Column("canonical_operation", BYTEA, nullable=False),
    sa.Column("canonical_byte_size", sa.Integer(), nullable=False),
    sa.Column("registry_state", sa.String(32), nullable=False),
    sa.Column("terminal_error_code", sa.String(40)),
    sa.Column("terminal_result_document", BYTEA),
    sa.Column("terminal_result_sha256", BYTEA),
    sa.Column("terminal_result_byte_size", sa.Integer()),
    sa.Column("first_received_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("last_evaluated_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("terminal_at", UTC_TIMESTAMP),
    sa.Column(
        "privacy_class",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'health_sensitive'"),
    ),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.ForeignKeyConstraint(
        ["sync_stream_id", "person_id"],
        ["sync_stream.sync_stream_id", "sync_stream.person_id"],
        name="fk_sync_operation_registry_stream_person",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["credential_family_id", "person_id", "submitting_device_id"],
        [
            "credential_family.credential_family_id",
            "credential_family.person_id",
            "credential_family.device_id",
        ],
        name="fk_sync_operation_registry_credential_namespace",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "submitting_device_id",
            "person_id",
            "installation_id",
            "local_owner_id",
        ],
        [
            "device.device_id",
            "device.person_id",
            "device.installation_id",
            "device.local_owner_id",
        ],
        name="fk_sync_operation_registry_provenance_device",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "installation_id",
        "client_sequence",
        name="uq_sync_operation_registry_installation_client_sequence",
    ),
    sa.UniqueConstraint(
        "capture_id",
        name="uq_sync_operation_registry_capture",
    ),
    sa.UniqueConstraint(
        "revision_id",
        name="uq_sync_operation_registry_revision",
    ),
    sa.UniqueConstraint(
        "operation_id",
        "person_id",
        "sync_stream_id",
        "credential_family_id",
        "submitting_device_id",
        "installation_id",
        "local_owner_id",
        "client_sequence",
        "first_batch_id",
        "first_batch_ordinal",
        "capture_id",
        "event_id",
        "revision_id",
        "operation_content_sha256",
        "registry_state",
        name="uq_sync_operation_registry_commit_binding",
    ),
    sa.CheckConstraint(
        f"client_sequence BETWEEN 1 AND {SAFE_INTEGER_MAX}",
        name="client_sequence_range",
    ),
    sa.CheckConstraint(
        "first_batch_ordinal BETWEEN 0 AND 99",
        name="batch_ordinal_range",
    ),
    sa.CheckConstraint(
        "octet_length(operation_content_sha256) = 32",
        name="operation_sha256_length",
    ),
    sa.CheckConstraint(
        f"canonical_byte_size BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES} "
        "AND canonical_byte_size = octet_length(canonical_operation)",
        name="canonical_bytes_coherent",
    ),
    sa.CheckConstraint(
        "registry_state IN ('pending_missing_parent', 'terminal_error', 'committed')",
        name="registry_state_allowed",
    ),
    sa.CheckConstraint(
        "("
        "registry_state = 'pending_missing_parent' "
        "AND event_id IS NULL "
        "AND expected_current_revision_id IS NOT NULL"
        ") OR ("
        "registry_state IN ('terminal_error', 'committed') "
        "AND event_id IS NOT NULL"
        ")",
        name="pending_parent_identity_coherent",
    ),
    sa.CheckConstraint(
        "("
        "registry_state = 'pending_missing_parent' "
        "AND terminal_error_code IS NULL AND terminal_result_document IS NULL "
        "AND terminal_result_sha256 IS NULL AND terminal_result_byte_size IS NULL "
        "AND terminal_at IS NULL"
        ") OR ("
        "registry_state = 'committed' "
        "AND terminal_error_code IS NULL AND terminal_result_document IS NULL "
        "AND terminal_result_sha256 IS NULL AND terminal_result_byte_size IS NULL "
        "AND terminal_at IS NOT NULL"
        ") OR ("
        "registry_state = 'terminal_error' "
        "AND terminal_error_code IN ("
        "'unsupported_schema_version', 'unsupported_operation_kind', "
        "'unsupported_event_kind', 'unsupported_source_channel', 'schema_invalid', "
        "'operation_hash_mismatch', 'operation_id_collision', "
        "'client_sequence_collision', 'capture_id_collision', "
        "'revision_id_collision', 'event_id_collision', 'invalid_parent', "
        "'ownership_violation'"
        ") AND terminal_result_document IS NOT NULL "
        "AND terminal_result_sha256 IS NOT NULL "
        "AND terminal_result_byte_size IS NOT NULL AND terminal_at IS NOT NULL"
        ")",
        name="registry_state_coherent",
    ),
    sa.CheckConstraint(
        "("
        "terminal_result_document IS NULL AND terminal_result_sha256 IS NULL "
        "AND terminal_result_byte_size IS NULL"
        ") OR ("
        "octet_length(terminal_result_sha256) = 32 "
        f"AND terminal_result_byte_size BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES} "
        "AND terminal_result_byte_size = octet_length(terminal_result_document)"
        ")",
        name="terminal_result_bytes_coherent",
    ),
    sa.CheckConstraint(
        "last_evaluated_at >= first_received_at "
        "AND (terminal_at IS NULL OR terminal_at >= first_received_at)",
        name="evaluation_time_order",
    ),
    sa.CheckConstraint(
        "privacy_class IN ('health_sensitive', 'personal_sensitive')",
        name="privacy_class_allowed",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)
sa.Index(
    "ix_sync_operation_registry_person_state",
    sync_operation_registry.c.person_id,
    sync_operation_registry.c.registry_state,
)
sa.Index(
    "ix_sync_operation_registry_event_id",
    sync_operation_registry.c.event_id,
)
sa.Index(
    "ix_sync_operation_registry_first_batch_membership",
    sync_operation_registry.c.credential_family_id,
    sync_operation_registry.c.submitting_device_id,
    sync_operation_registry.c.first_batch_id,
    sync_operation_registry.c.first_batch_ordinal,
)


capture = sa.Table(
    "capture",
    metadata,
    sa.Column("capture_id", UUID, primary_key=True),
    sa.Column(
        "person_id",
        UUID,
        sa.ForeignKey("person.person_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column("installation_id", UUID, nullable=False),
    sa.Column("local_owner_id", UUID, nullable=False),
    sa.Column("operation_id", UUID, nullable=False),
    sa.Column("schema_version", sa.String(16), nullable=False),
    sa.Column("source_channel", sa.String(32), nullable=False),
    sa.Column("recorded_at", UTC_TIMESTAMP, nullable=False),
    sa.Column(
        "ingested_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("canonical_document", BYTEA, nullable=False),
    sa.Column("canonical_document_sha256", BYTEA, nullable=False),
    sa.Column("canonical_byte_size", sa.Integer(), nullable=False),
    sa.Column(
        "privacy_class",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'health_sensitive'"),
    ),
    sa.Column("retention_until", UTC_TIMESTAMP),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.ForeignKeyConstraint(
        ["device_id", "person_id", "installation_id", "local_owner_id"],
        [
            "device.device_id",
            "device.person_id",
            "device.installation_id",
            "device.local_owner_id",
        ],
        name="fk_capture_provenance_device",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["operation_id"],
        ["sync_operation_registry.operation_id"],
        name="fk_capture_operation_registry",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
        use_alter=True,
    ),
    sa.UniqueConstraint(
        "person_id",
        "capture_id",
        name="uq_capture_person_capture",
    ),
    sa.UniqueConstraint("operation_id", name="uq_capture_operation"),
    sa.UniqueConstraint(
        "capture_id",
        "operation_id",
        name="uq_capture_capture_operation",
    ),
    sa.CheckConstraint(
        "schema_version = '4.0.0'",
        name="schema_version_supported",
    ),
    sa.CheckConstraint(
        "source_channel IN ("
        "'android_manual', 'android_recording', 'android_share_intent', "
        "'health_connect', 'file_import', 'connector', 'system'"
        ")",
        name="source_channel_allowed",
    ),
    sa.CheckConstraint(
        "octet_length(canonical_document_sha256) = 32",
        name="document_sha256_length",
    ),
    sa.CheckConstraint(
        f"canonical_byte_size BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES} "
        "AND canonical_byte_size = octet_length(canonical_document)",
        name="canonical_bytes_coherent",
    ),
    sa.CheckConstraint(
        "privacy_class IN ('health_sensitive', 'personal_sensitive')",
        name="privacy_class_allowed",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)
sa.Index("ix_capture_person_recorded_at", capture.c.person_id, capture.c.recorded_at)


life_event = sa.Table(
    "life_event",
    metadata,
    sa.Column("event_id", UUID, primary_key=True),
    sa.Column(
        "person_id",
        UUID,
        sa.ForeignKey("person.person_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("event_kind", sa.String(32), nullable=False),
    sa.Column("root_revision_id", UUID, nullable=False),
    sa.Column("current_revision_id", UUID, nullable=False),
    sa.Column(
        "privacy_class",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'health_sensitive'"),
    ),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column(
        "created_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column(
        "updated_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.ForeignKeyConstraint(
        ["event_id", "root_revision_id"],
        ["event_revision.event_id", "event_revision.revision_id"],
        name="fk_life_event_root_revision",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
        use_alter=True,
    ),
    sa.ForeignKeyConstraint(
        ["event_id", "current_revision_id"],
        ["event_revision.event_id", "event_revision.revision_id"],
        name="fk_life_event_current_revision",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
        use_alter=True,
    ),
    sa.UniqueConstraint(
        "person_id",
        "event_id",
        name="uq_life_event_person_event",
    ),
    sa.UniqueConstraint(
        "event_id",
        "event_kind",
        name="uq_life_event_event_kind",
    ),
    sa.UniqueConstraint(
        "person_id",
        "event_id",
        "event_kind",
        name="uq_life_event_person_event_kind",
    ),
    sa.CheckConstraint(
        "event_kind IN ("
        "'meal', 'sleep', 'wellbeing', 'medication_intake', "
        "'supplement_intake', 'measurement', 'note'"
        ")",
        name="event_kind_allowed",
    ),
    sa.CheckConstraint(
        "privacy_class IN ('health_sensitive', 'personal_sensitive')",
        name="privacy_class_allowed",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)
sa.Index("ix_life_event_person_kind", life_event.c.person_id, life_event.c.event_kind)


event_revision = sa.Table(
    "event_revision",
    metadata,
    sa.Column("revision_id", UUID, primary_key=True),
    sa.Column("event_id", UUID, nullable=False),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("capture_id", UUID, nullable=False),
    sa.Column("submitting_device_id", UUID, nullable=False),
    sa.Column("installation_id", UUID, nullable=False),
    sa.Column("local_owner_id", UUID, nullable=False),
    sa.Column("revision_no", sa.BigInteger(), nullable=False),
    sa.Column("parent_revision_id", UUID),
    sa.Column("parent_revision_no", sa.BigInteger()),
    sa.Column("expected_current_revision_id", UUID),
    sa.Column("schema_version", sa.String(16), nullable=False),
    sa.Column("event_kind", sa.String(32), nullable=False),
    sa.Column("assertion_status", sa.String(16), nullable=False),
    sa.Column("record_status", sa.String(16), nullable=False),
    sa.Column("verification_status", sa.String(24), nullable=False),
    sa.Column("actor", sa.String(16), nullable=False),
    sa.Column("correction_reason", sa.String(500)),
    sa.Column("source_channel", sa.String(32), nullable=False),
    sa.Column("source_record_id", sa.Text()),
    sa.Column("source_record_version", sa.Text()),
    sa.Column("recorded_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("effective_start_utc", UTC_TIMESTAMP, nullable=True),
    sa.Column("effective_end_utc", UTC_TIMESTAMP),
    sa.Column("original_local_start", LOCAL_TIMESTAMP, nullable=True),
    sa.Column("original_local_end", LOCAL_TIMESTAMP),
    sa.Column("timezone_id", sa.String(64), nullable=False),
    sa.Column("start_offset_seconds", sa.Integer(), nullable=True),
    sa.Column("end_offset_seconds", sa.Integer()),
    sa.Column("temporal_precision", sa.String(16), nullable=False),
    sa.Column("local_date", sa.Date(), nullable=True),
    sa.Column("revision_content_sha256", BYTEA, nullable=False),
    sa.Column("canonical_document", BYTEA, nullable=False),
    sa.Column("canonical_document_sha256", BYTEA, nullable=False),
    sa.Column("canonical_byte_size", sa.Integer(), nullable=False),
    sa.Column(
        "privacy_class",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'health_sensitive'"),
    ),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.Column("server_received_at", UTC_TIMESTAMP, nullable=False),
    sa.ForeignKeyConstraint(
        ["person_id", "event_id", "event_kind"],
        ["life_event.person_id", "life_event.event_id", "life_event.event_kind"],
        name="fk_event_revision_event_owner_kind",
        ondelete="CASCADE",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["person_id", "capture_id"],
        ["capture.person_id", "capture.capture_id"],
        name="fk_event_revision_person_capture",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "submitting_device_id",
            "person_id",
            "installation_id",
            "local_owner_id",
        ],
        [
            "device.device_id",
            "device.person_id",
            "device.installation_id",
            "device.local_owner_id",
        ],
        name="fk_event_revision_provenance_device",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["event_id", "parent_revision_id", "parent_revision_no"],
        [
            "event_revision.event_id",
            "event_revision.revision_id",
            "event_revision.revision_no",
        ],
        name="fk_event_revision_parent",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "event_id",
        "revision_id",
        name="uq_event_revision_event_revision",
    ),
    sa.UniqueConstraint(
        "person_id",
        "event_id",
        "revision_id",
        name="uq_event_revision_person_event_revision",
    ),
    sa.UniqueConstraint(
        "event_id",
        "revision_id",
        "revision_no",
        name="uq_event_revision_parent_lookup",
    ),
    sa.CheckConstraint(
        f"revision_no BETWEEN 1 AND {SAFE_INTEGER_MAX}",
        name="revision_no_range",
    ),
    sa.CheckConstraint(
        "("
        "revision_no = 1 AND parent_revision_id IS NULL "
        "AND parent_revision_no IS NULL AND expected_current_revision_id IS NULL"
        ") OR ("
        "revision_no > 1 AND parent_revision_id IS NOT NULL "
        "AND parent_revision_no IS NOT NULL "
        "AND revision_no = parent_revision_no + 1 "
        "AND expected_current_revision_id = parent_revision_id"
        ")",
        name="linear_parent_coherent",
    ),
    sa.CheckConstraint(
        "schema_version = '4.0.0'",
        name="schema_version_supported",
    ),
    sa.CheckConstraint(
        "assertion_status IN ('observed', 'uncertain')",
        name="assertion_status_allowed",
    ),
    sa.CheckConstraint(
        "record_status IN ('active', 'retracted')",
        name="record_status_allowed",
    ),
    sa.CheckConstraint(
        "verification_status IN ("
        "'source_recorded', 'user_confirmed', 'machine_inferred', 'needs_review'"
        ")",
        name="verification_status_allowed",
    ),
    sa.CheckConstraint(
        "actor IN ('user', 'system', 'connector')",
        name="actor_allowed",
    ),
    sa.CheckConstraint(
        "source_channel IN ("
        "'android_manual', 'android_recording', 'android_share_intent', "
        "'health_connect', 'file_import', 'connector', 'system'"
        ")",
        name="source_channel_allowed",
    ),
    sa.CheckConstraint(
        "(source_record_id IS NULL AND source_record_version IS NULL) "
        "OR source_record_id IS NOT NULL",
        name="source_record_version_requires_id",
    ),
    sa.CheckConstraint(
        "start_offset_seconds BETWEEN -50400 AND 50400 "
        "AND (end_offset_seconds IS NULL OR end_offset_seconds BETWEEN -50400 AND 50400)",
        name="offset_range",
    ),
    sa.CheckConstraint(
        "(effective_end_utc IS NULL AND original_local_end IS NULL "
        "AND end_offset_seconds IS NULL) OR "
        "(effective_end_utc IS NOT NULL AND effective_start_utc IS NOT NULL "
        "AND original_local_end IS NOT NULL "
        "AND end_offset_seconds IS NOT NULL AND effective_end_utc >= effective_start_utc)",
        name="interval_fields_coherent",
    ),
    sa.CheckConstraint(
        "temporal_precision IN ("
        "'exact', 'minute', 'hour', 'part_of_day', 'date', "
        "'approximate', 'unknown'"
        ")",
        name="temporal_precision_allowed",
    ),
    sa.CheckConstraint(
        "("
        "temporal_precision IN ('exact', 'minute', 'hour') "
        "AND effective_start_utc IS NOT NULL "
        "AND original_local_start IS NOT NULL "
        "AND start_offset_seconds IS NOT NULL "
        "AND local_date IS NOT NULL"
        ") OR ("
        "temporal_precision IN ('date', 'part_of_day') "
        "AND effective_start_utc IS NULL "
        "AND original_local_start IS NOT NULL "
        "AND start_offset_seconds IS NULL "
        "AND local_date IS NOT NULL"
        ") OR ("
        "temporal_precision = 'unknown' "
        "AND effective_start_utc IS NULL "
        "AND original_local_start IS NULL "
        "AND start_offset_seconds IS NULL "
        "AND local_date IS NULL"
        ") OR ("
        "temporal_precision = 'approximate' AND ("
        "("
        "effective_start_utc IS NOT NULL "
        "AND original_local_start IS NOT NULL "
        "AND start_offset_seconds IS NOT NULL "
        "AND local_date IS NOT NULL"
        ") OR ("
        "effective_start_utc IS NULL "
        "AND start_offset_seconds IS NULL"
        ")"
        ")"
        ")",
        name="start_time_precision_coherent",
    ),
    sa.CheckConstraint(
        "octet_length(revision_content_sha256) = 32 "
        "AND octet_length(canonical_document_sha256) = 32",
        name="sha256_lengths",
    ),
    sa.CheckConstraint(
        f"canonical_byte_size BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES} "
        "AND canonical_byte_size = octet_length(canonical_document)",
        name="canonical_bytes_coherent",
    ),
    sa.CheckConstraint(
        "privacy_class IN ('health_sensitive', 'personal_sensitive')",
        name="privacy_class_allowed",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)
sa.Index(
    "ix_event_revision_person_effective_start",
    event_revision.c.person_id,
    event_revision.c.effective_start_utc,
)
sa.Index(
    "ix_event_revision_event_revision_no",
    event_revision.c.event_id,
    event_revision.c.revision_no,
)
sa.Index(
    "ix_event_revision_source_record",
    event_revision.c.person_id,
    event_revision.c.source_channel,
    event_revision.c.source_record_id,
)


sync_operation = sa.Table(
    "sync_operation",
    metadata,
    sa.Column("operation_id", UUID, primary_key=True),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("sync_stream_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("submitting_device_id", UUID, nullable=False),
    sa.Column("installation_id", UUID, nullable=False),
    sa.Column("local_owner_id", UUID, nullable=False),
    sa.Column("client_sequence", sa.BigInteger(), nullable=False),
    sa.Column("first_batch_id", UUID, nullable=False),
    sa.Column("first_batch_ordinal", sa.SmallInteger(), nullable=False),
    sa.Column("capture_id", UUID, nullable=False),
    sa.Column("event_id", UUID, nullable=False),
    sa.Column("revision_id", UUID, nullable=False),
    sa.Column("expected_current_revision_id", UUID),
    sa.Column("operation_kind", sa.String(32), nullable=False),
    sa.Column("operation_content_sha256", BYTEA, nullable=False),
    sa.Column(
        "registry_state",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'committed'"),
    ),
    sa.Column("canonical_operation", BYTEA, nullable=False),
    sa.Column("canonical_byte_size", sa.Integer(), nullable=False),
    sa.Column("result_code", sa.String(16), nullable=False),
    sa.Column("current_revision_id", UUID, nullable=False),
    sa.Column("server_sequence", sa.BigInteger(), nullable=False),
    sa.Column("first_received_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("committed_at", UTC_TIMESTAMP, nullable=False),
    sa.Column(
        "privacy_class",
        sa.String(32),
        nullable=False,
        server_default=sa.text("'health_sensitive'"),
    ),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.ForeignKeyConstraint(
        [
            "operation_id",
            "person_id",
            "sync_stream_id",
            "credential_family_id",
            "submitting_device_id",
            "installation_id",
            "local_owner_id",
            "client_sequence",
            "first_batch_id",
            "first_batch_ordinal",
            "capture_id",
            "event_id",
            "revision_id",
            "operation_content_sha256",
            "registry_state",
        ],
        [
            "sync_operation_registry.operation_id",
            "sync_operation_registry.person_id",
            "sync_operation_registry.sync_stream_id",
            "sync_operation_registry.credential_family_id",
            "sync_operation_registry.submitting_device_id",
            "sync_operation_registry.installation_id",
            "sync_operation_registry.local_owner_id",
            "sync_operation_registry.client_sequence",
            "sync_operation_registry.first_batch_id",
            "sync_operation_registry.first_batch_ordinal",
            "sync_operation_registry.capture_id",
            "sync_operation_registry.event_id",
            "sync_operation_registry.revision_id",
            "sync_operation_registry.operation_content_sha256",
            "sync_operation_registry.registry_state",
        ],
        name="fk_sync_operation_registry",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["sync_stream_id", "person_id"],
        ["sync_stream.sync_stream_id", "sync_stream.person_id"],
        name="fk_sync_operation_stream_person",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["credential_family_id", "person_id", "submitting_device_id"],
        [
            "credential_family.credential_family_id",
            "credential_family.person_id",
            "credential_family.device_id",
        ],
        name="fk_sync_operation_credential_namespace",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "submitting_device_id",
            "person_id",
            "installation_id",
            "local_owner_id",
        ],
        [
            "device.device_id",
            "device.person_id",
            "device.installation_id",
            "device.local_owner_id",
        ],
        name="fk_sync_operation_provenance_device",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["capture_id", "operation_id"],
        ["capture.capture_id", "capture.operation_id"],
        name="fk_sync_operation_capture_operation",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["person_id", "event_id", "revision_id"],
        [
            "event_revision.person_id",
            "event_revision.event_id",
            "event_revision.revision_id",
        ],
        name="fk_sync_operation_event_revision",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["event_id", "expected_current_revision_id"],
        ["event_revision.event_id", "event_revision.revision_id"],
        name="fk_sync_operation_expected_revision",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["event_id", "current_revision_id"],
        ["event_revision.event_id", "event_revision.revision_id"],
        name="fk_sync_operation_current_revision",
        ondelete="RESTRICT",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint("revision_id", name="uq_sync_operation_revision"),
    sa.UniqueConstraint(
        "installation_id",
        "client_sequence",
        name="uq_sync_operation_installation_client_sequence",
    ),
    sa.UniqueConstraint(
        "sync_stream_id",
        "server_sequence",
        name="uq_sync_operation_stream_server_sequence",
    ),
    sa.CheckConstraint(
        f"client_sequence BETWEEN 1 AND {SAFE_INTEGER_MAX}",
        name="client_sequence_range",
    ),
    sa.CheckConstraint(
        "first_batch_ordinal BETWEEN 0 AND 99",
        name="batch_ordinal_range",
    ),
    sa.CheckConstraint(
        "operation_kind = 'append_event_revision'",
        name="operation_kind_supported",
    ),
    sa.CheckConstraint(
        "registry_state = 'committed'",
        name="registry_state_committed",
    ),
    sa.CheckConstraint(
        "octet_length(operation_content_sha256) = 32",
        name="operation_sha256_length",
    ),
    sa.CheckConstraint(
        f"canonical_byte_size BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES} "
        "AND canonical_byte_size = octet_length(canonical_operation)",
        name="canonical_bytes_coherent",
    ),
    sa.CheckConstraint(
        "(result_code = 'applied' AND current_revision_id = revision_id) OR "
        "(result_code = 'conflict' AND current_revision_id <> revision_id)",
        name="result_current_revision_coherent",
    ),
    sa.CheckConstraint(
        f"server_sequence BETWEEN 1 AND {SAFE_INTEGER_MAX}",
        name="server_sequence_range",
    ),
    sa.CheckConstraint(
        "committed_at >= first_received_at",
        name="commit_after_receive",
    ),
    sa.CheckConstraint(
        "privacy_class IN ('health_sensitive', 'personal_sensitive')",
        name="privacy_class_allowed",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)
sa.Index(
    "ix_sync_operation_person_committed_at",
    sync_operation.c.person_id,
    sync_operation.c.committed_at,
)
sa.Index(
    "ix_sync_operation_event_id",
    sync_operation.c.event_id,
)
sa.Index(
    "ix_sync_operation_first_batch_membership",
    sync_operation.c.credential_family_id,
    sync_operation.c.submitting_device_id,
    sync_operation.c.first_batch_id,
    sync_operation.c.first_batch_ordinal,
)


http_replay = sa.Table(
    "http_replay",
    metadata,
    sa.Column("http_replay_id", UUID, primary_key=True),
    sa.Column("endpoint_id", sa.String(32), nullable=False),
    sa.Column("protocol_version", sa.String(16), nullable=False),
    sa.Column("request_identity_kind", sa.String(16), nullable=False),
    sa.Column("request_identity", UUID, nullable=False),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column("family_tombstone_until", UTC_TIMESTAMP, nullable=False),
    sa.Column("request_fingerprint_hmac", BYTEA, nullable=False),
    sa.Column("fingerprint_key_generation", sa.Integer(), nullable=False),
    sa.Column("outcome_class", sa.String(16), nullable=False),
    sa.Column("stored_outcome", sa.String(128), nullable=False),
    sa.Column("http_status", sa.SmallInteger(), nullable=False),
    sa.Column("error_code", sa.String(64)),
    sa.Column("retryable", sa.Boolean()),
    sa.Column("response_body_ciphertext", BYTEA, nullable=False),
    sa.Column("response_body_nonce", BYTEA, nullable=False),
    sa.Column("response_body_sha256", BYTEA, nullable=False),
    sa.Column("response_body_plaintext_bytes", sa.Integer(), nullable=False),
    sa.Column(
        "response_encryption_algorithm",
        sa.String(16),
        nullable=False,
        server_default=sa.text("'aes_256_gcm'"),
    ),
    sa.Column("response_encryption_key_generation", sa.Integer(), nullable=False),
    sa.Column(
        "committed_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("retention_until", UTC_TIMESTAMP, nullable=False),
    sa.Column(
        "purge_generation",
        sa.BigInteger(),
        nullable=False,
        server_default=sa.text("0"),
    ),
    sa.ForeignKeyConstraint(
        [
            "credential_family_id",
            "person_id",
            "device_id",
            "family_tombstone_until",
        ],
        [
            "credential_family.credential_family_id",
            "credential_family.person_id",
            "credential_family.device_id",
            "credential_family.tombstone_until",
        ],
        name="fk_http_replay_credential_namespace",
        ondelete="CASCADE",
        onupdate="CASCADE",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        ["person_id", "device_id"],
        [
            "device_replay_quota.person_id",
            "device_replay_quota.device_id",
        ],
        name="fk_http_replay_person_device_quota",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "endpoint_id",
        "protocol_version",
        "credential_family_id",
        "device_id",
        "request_identity",
        name="uq_http_replay_durable_request_namespace",
    ),
    sa.UniqueConstraint(
        "response_encryption_key_generation",
        "response_body_nonce",
        name="uq_http_replay_encryption_nonce",
    ),
    sa.UniqueConstraint(
        "http_replay_id",
        "endpoint_id",
        "protocol_version",
        "request_identity_kind",
        "request_identity",
        "person_id",
        "credential_family_id",
        "device_id",
        "outcome_class",
        "stored_outcome",
        "http_status",
        "response_body_sha256",
        "response_body_plaintext_bytes",
        "committed_at",
        "purge_generation",
        name="uq_http_replay_read_page_binding",
    ),
    sa.CheckConstraint(
        "endpoint_id IN ('auth_revoke', 'sync_push', 'sync_bootstrap', 'sync_pull')",
        name="endpoint_id_allowed",
    ),
    sa.CheckConstraint(
        "(endpoint_id = 'sync_push' AND request_identity_kind = 'batch_id') OR "
        "(endpoint_id <> 'sync_push' AND request_identity_kind = 'request_id')",
        name="request_identity_kind_coherent",
    ),
    sa.CheckConstraint(
        "octet_length(request_fingerprint_hmac) = 32",
        name="request_fingerprint_length",
    ),
    sa.CheckConstraint(
        "fingerprint_key_generation > 0 AND response_encryption_key_generation > 0",
        name="key_generations_positive",
    ),
    sa.CheckConstraint(
        "outcome_class IN ('success', 'api_error')",
        name="outcome_class_allowed",
    ),
    sa.CheckConstraint(
        "(outcome_class = 'success' AND http_status = 200 "
        "AND error_code IS NULL AND retryable IS NULL) OR "
        "(outcome_class = 'api_error' AND http_status BETWEEN 400 AND 499 "
        "AND error_code IS NOT NULL AND retryable = false)",
        name="outcome_metadata_coherent",
    ),
    sa.CheckConstraint(
        "stored_outcome IN ("
        "'authenticated_success', "
        "'authenticated_nonretryable_terminal_api_error', "
        "'terminal_auth_revoke_401_credential_unavailable', "
        "'terminal_operation_result_batch'"
        ")",
        name="stored_outcome_allowed",
    ),
    sa.CheckConstraint(
        "("
        "outcome_class = 'success' AND ("
        "(endpoint_id = 'sync_push' "
        "AND stored_outcome = 'terminal_operation_result_batch') OR "
        "(endpoint_id IN ('auth_revoke', 'sync_bootstrap', 'sync_pull') "
        "AND stored_outcome = 'authenticated_success')"
        ")"
        ") OR ("
        "outcome_class = 'api_error' AND ("
        "("
        "stored_outcome = 'authenticated_nonretryable_terminal_api_error' "
        "AND error_code NOT IN ("
        "'credential_unavailable', 'rate_limited', 'temporarily_unavailable'"
        ")"
        ") OR ("
        "endpoint_id = 'auth_revoke' "
        "AND stored_outcome = 'terminal_auth_revoke_401_credential_unavailable' "
        "AND http_status = 401 AND error_code = 'credential_unavailable'"
        ")"
        ")"
        ")",
        name="stored_outcome_coherent",
    ),
    sa.CheckConstraint(
        "(error_code IS NULL) OR "
        "(error_code IN ("
        "'malformed_json', 'unsupported_protocol_version', "
        "'idempotency_key_mismatch', 'cursor_invalid'"
        ") AND http_status = 400) OR "
        "(error_code = 'credential_unavailable' AND http_status = 401) OR "
        "(error_code = 'device_mismatch' AND http_status = 403) OR "
        "(error_code IN ("
        "'active_device_exists', 'batch_id_collision', "
        "'request_id_collision', 'bootstrap_required'"
        ") AND http_status = 409) OR "
        "(error_code = 'cursor_expired' AND http_status = 410) OR "
        "(error_code = 'request_too_large' AND http_status = 413) OR "
        "(error_code = 'unsupported_media_type' AND http_status = 415) OR "
        "(error_code IN ('request_schema_invalid', 'batch_hash_mismatch') "
        "AND http_status = 422)",
        name="terminal_error_status_mapping",
    ),
    sa.CheckConstraint(
        "(error_code IS NULL) OR "
        "("
        "endpoint_id = 'auth_revoke' AND error_code IN ("
        "'malformed_json', 'unsupported_protocol_version', "
        "'credential_unavailable', 'request_id_collision', "
        "'request_too_large', 'unsupported_media_type', "
        "'request_schema_invalid'"
        ")"
        ") OR ("
        "endpoint_id = 'sync_push' AND error_code IN ("
        "'malformed_json', 'unsupported_protocol_version', "
        "'idempotency_key_mismatch', 'credential_unavailable', "
        "'device_mismatch', 'batch_id_collision', 'bootstrap_required', "
        "'request_too_large', 'unsupported_media_type', "
        "'request_schema_invalid', 'batch_hash_mismatch'"
        ")"
        ") OR ("
        "endpoint_id = 'sync_bootstrap' AND error_code IN ("
        "'malformed_json', 'unsupported_protocol_version', 'cursor_invalid', "
        "'credential_unavailable', 'device_mismatch', 'request_id_collision', "
        "'bootstrap_required', 'cursor_expired', 'request_too_large', "
        "'unsupported_media_type', 'request_schema_invalid'"
        ")"
        ") OR ("
        "endpoint_id = 'sync_pull' AND error_code IN ("
        "'malformed_json', 'unsupported_protocol_version', 'cursor_invalid', "
        "'credential_unavailable', 'device_mismatch', 'request_id_collision', "
        "'bootstrap_required', 'request_too_large', "
        "'unsupported_media_type', 'request_schema_invalid'"
        ")"
        ")",
        name="terminal_error_endpoint_allowed",
    ),
    sa.CheckConstraint(
        "octet_length(response_body_nonce) = 12",
        name="response_nonce_length",
    ),
    sa.CheckConstraint(
        "octet_length(response_body_sha256) = 32",
        name="response_sha256_length",
    ),
    sa.CheckConstraint(
        "response_encryption_algorithm = 'aes_256_gcm'",
        name="response_encryption_algorithm_supported",
    ),
    sa.CheckConstraint(
        f"response_body_plaintext_bytes BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES} "
        "AND octet_length(response_body_ciphertext) = response_body_plaintext_bytes + 16",
        name="encrypted_response_size",
    ),
    sa.CheckConstraint(
        "("
        "endpoint_id = 'auth_revoke' "
        f"AND response_body_plaintext_bytes <= {AUTH_REVOKE_MAX_REPLAY_BODY_BYTES}"
        ") OR ("
        "endpoint_id = 'sync_push' "
        f"AND response_body_plaintext_bytes <= {SYNC_PUSH_MAX_REPLAY_BODY_BYTES}"
        ") OR ("
        "endpoint_id IN ('sync_bootstrap', 'sync_pull') "
        f"AND response_body_plaintext_bytes <= {MAX_REPLAY_BODY_BYTES}"
        ")",
        name="endpoint_response_plaintext_size",
    ),
    sa.CheckConstraint(
        "retention_until >= committed_at + INTERVAL '30 days' "
        "AND (endpoint_id <> 'auth_revoke' OR retention_until >= family_tombstone_until)",
        name="retention_after_commit",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
)
sa.Index(
    "ix_http_replay_retention_until",
    http_replay.c.retention_until,
)
sa.Index(
    "ix_http_replay_person_device",
    http_replay.c.person_id,
    http_replay.c.device_id,
)
sa.Index(
    "ix_http_replay_person_device_retention",
    http_replay.c.person_id,
    http_replay.c.device_id,
    http_replay.c.retention_until,
)
sa.Index(
    "ix_http_replay_family_retention",
    http_replay.c.credential_family_id,
    http_replay.c.retention_until,
)


sync_snapshot = sa.Table(
    "sync_snapshot",
    metadata,
    sa.Column("snapshot_id", UUID, primary_key=True),
    sa.Column("snapshot_kind", sa.String(16), nullable=False),
    sa.Column("bootstrap_id", UUID),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("sync_stream_id", UUID, nullable=False),
    sa.Column("protocol_stream", sa.String(32), nullable=False),
    sa.Column("start_sequence", sa.BigInteger(), nullable=False),
    sa.Column("high_watermark_sequence", sa.BigInteger(), nullable=False),
    sa.Column("source_cursor_id", UUID),
    sa.Column("source_cursor_kind", sa.String(24)),
    sa.Column("source_cursor_protocol_stream", sa.String(32)),
    sa.Column("bootstrap_incremental_cursor_id", UUID),
    sa.Column("bootstrap_incremental_cursor_kind", sa.String(24)),
    sa.Column("bootstrap_incremental_cursor_protocol_stream", sa.String(32)),
    sa.Column("purge_generation", sa.BigInteger(), nullable=False),
    sa.Column("status", sa.String(16), nullable=False),
    sa.Column(
        "created_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("completed_at", UTC_TIMESTAMP),
    sa.Column("revoked_at", UTC_TIMESTAMP),
    sa.ForeignKeyConstraint(
        ["sync_stream_id", "person_id"],
        ["sync_stream.sync_stream_id", "sync_stream.person_id"],
        name="fk_sync_snapshot_stream_person",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["credential_family_id", "person_id", "device_id"],
        [
            "credential_family.credential_family_id",
            "credential_family.person_id",
            "credential_family.device_id",
        ],
        name="fk_sync_snapshot_credential_namespace",
        ondelete="CASCADE",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "person_id",
        "device_id",
        "credential_family_id",
        "bootstrap_id",
        name="uq_sync_snapshot_bootstrap_attempt",
    ),
    sa.UniqueConstraint(
        "snapshot_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "snapshot_kind",
        "high_watermark_sequence",
        name="uq_sync_snapshot_cursor_binding",
    ),
    sa.UniqueConstraint(
        "snapshot_id",
        "bootstrap_id",
        name="uq_sync_snapshot_bootstrap_binding",
    ),
    sa.UniqueConstraint(
        "snapshot_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "snapshot_kind",
        "status",
        "bootstrap_id",
        name="uq_sync_snapshot_read_state_binding",
    ),
    sa.UniqueConstraint(
        "snapshot_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "snapshot_kind",
        name="uq_sync_snapshot_page_binding",
    ),
    sa.CheckConstraint(
        f"protocol_stream = '{SYNC_DATA_PROTOCOL_STREAM}'",
        name="protocol_stream_supported",
    ),
    sa.CheckConstraint(
        "(snapshot_kind = 'bootstrap' "
        "AND bootstrap_id IS NOT NULL "
        "AND start_sequence = 0 "
        "AND source_cursor_id IS NULL "
        "AND source_cursor_kind IS NULL "
        "AND source_cursor_protocol_stream IS NULL "
        "AND bootstrap_incremental_cursor_id IS NOT NULL "
        "AND bootstrap_incremental_cursor_kind = 'incremental' "
        "AND bootstrap_incremental_cursor_protocol_stream = 'sync_incremental_v1'"
        ") OR (snapshot_kind = 'incremental' "
        "AND bootstrap_id IS NULL "
        "AND source_cursor_id IS NOT NULL "
        "AND source_cursor_kind = 'incremental' "
        "AND source_cursor_protocol_stream = 'sync_incremental_v1' "
        "AND bootstrap_incremental_cursor_id IS NULL "
        "AND bootstrap_incremental_cursor_kind IS NULL "
        "AND bootstrap_incremental_cursor_protocol_stream IS NULL)",
        name="snapshot_kind_binding_coherent",
    ),
    sa.CheckConstraint(
        f"start_sequence BETWEEN 0 AND {SAFE_INTEGER_MAX} "
        f"AND high_watermark_sequence BETWEEN 0 AND {SAFE_INTEGER_MAX} "
        "AND start_sequence <= high_watermark_sequence",
        name="sequence_window_coherent",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
    sa.CheckConstraint(
        "status IN ('active', 'complete', 'expired', 'revoked')",
        name="status_allowed",
    ),
    sa.CheckConstraint("expires_at > created_at", name="expiry_after_creation"),
    sa.CheckConstraint(
        "(status = 'active' AND completed_at IS NULL AND revoked_at IS NULL) OR "
        "(status = 'complete' AND completed_at IS NOT NULL AND revoked_at IS NULL) OR "
        "(status = 'expired' AND revoked_at IS NULL) OR "
        "(status = 'revoked' AND revoked_at IS NOT NULL)",
        name="status_metadata_coherent",
    ),
    sa.CheckConstraint(
        "(completed_at IS NULL OR completed_at >= created_at) AND "
        "(revoked_at IS NULL OR revoked_at >= created_at) AND "
        "(completed_at IS NULL OR revoked_at IS NULL OR revoked_at >= completed_at)",
        name="lifecycle_time_order",
    ),
)
sa.Index("ix_sync_snapshot_expires_at", sync_snapshot.c.expires_at)
sa.Index(
    "uq_sync_snapshot_active_bootstrap_namespace",
    sync_snapshot.c.person_id,
    sync_snapshot.c.device_id,
    sync_snapshot.c.credential_family_id,
    sync_snapshot.c.sync_stream_id,
    sync_snapshot.c.purge_generation,
    unique=True,
    postgresql_where=sa.text("snapshot_kind = 'bootstrap' AND status = 'active'"),
)
sa.Index(
    "uq_sync_snapshot_active_incremental_namespace",
    sync_snapshot.c.person_id,
    sync_snapshot.c.device_id,
    sync_snapshot.c.credential_family_id,
    sync_snapshot.c.sync_stream_id,
    sync_snapshot.c.purge_generation,
    unique=True,
    postgresql_where=sa.text("snapshot_kind = 'incremental' AND status = 'active'"),
)


sync_cursor = sa.Table(
    "sync_cursor",
    metadata,
    sa.Column("sync_cursor_id", UUID, primary_key=True),
    sa.Column("generation", sa.SmallInteger(), nullable=False, server_default=sa.text("1")),
    sa.Column("cursor_kind", sa.String(24), nullable=False),
    sa.Column("protocol_stream", sa.String(32), nullable=False),
    sa.Column("handle_hmac", BYTEA, nullable=False),
    sa.Column("derivation_nonce", BYTEA, nullable=False),
    sa.Column("signing_key_generation", sa.Integer(), nullable=False),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("sync_stream_id", UUID, nullable=False),
    sa.Column("snapshot_id", UUID, nullable=False),
    sa.Column("snapshot_kind", sa.String(16), nullable=False),
    sa.Column("bootstrap_id", UUID),
    sa.Column("exact_position", sa.BigInteger(), nullable=False),
    sa.Column("snapshot_high_watermark_sequence", sa.BigInteger(), nullable=False),
    sa.Column("purge_generation", sa.BigInteger(), nullable=False),
    sa.Column("cursor_state", sa.String(16), nullable=False),
    sa.Column("lineage_depth", sa.Integer(), nullable=False),
    sa.Column("parent_cursor_id", UUID),
    sa.Column("parent_snapshot_id", UUID),
    sa.Column("parent_snapshot_kind", sa.String(16)),
    sa.Column("parent_bootstrap_id", UUID),
    sa.Column("parent_cursor_kind", sa.String(24)),
    sa.Column("parent_protocol_stream", sa.String(32)),
    sa.Column("parent_exact_position", sa.BigInteger()),
    sa.Column("parent_lineage_depth", sa.Integer()),
    sa.Column(
        "issued_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column("expires_at", UTC_TIMESTAMP, nullable=False),
    sa.Column("last_used_at", UTC_TIMESTAMP),
    sa.Column("consumed_at", UTC_TIMESTAMP),
    sa.Column("revoked_at", UTC_TIMESTAMP),
    sa.ForeignKeyConstraint(
        [
            "snapshot_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "snapshot_kind",
            "snapshot_high_watermark_sequence",
        ],
        [
            "sync_snapshot.snapshot_id",
            "sync_snapshot.person_id",
            "sync_snapshot.device_id",
            "sync_snapshot.credential_family_id",
            "sync_snapshot.sync_stream_id",
            "sync_snapshot.purge_generation",
            "sync_snapshot.snapshot_kind",
            "sync_snapshot.high_watermark_sequence",
        ],
        name="fk_sync_cursor_snapshot_binding",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["snapshot_id", "bootstrap_id"],
        ["sync_snapshot.snapshot_id", "sync_snapshot.bootstrap_id"],
        name="fk_sync_cursor_bootstrap_binding",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        [
            "parent_cursor_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "parent_snapshot_id",
            "parent_snapshot_kind",
            "parent_cursor_kind",
            "parent_protocol_stream",
            "parent_exact_position",
            "parent_lineage_depth",
        ],
        [
            "sync_cursor.sync_cursor_id",
            "sync_cursor.person_id",
            "sync_cursor.device_id",
            "sync_cursor.credential_family_id",
            "sync_cursor.sync_stream_id",
            "sync_cursor.purge_generation",
            "sync_cursor.snapshot_id",
            "sync_cursor.snapshot_kind",
            "sync_cursor.cursor_kind",
            "sync_cursor.protocol_stream",
            "sync_cursor.exact_position",
            "sync_cursor.lineage_depth",
        ],
        name="fk_sync_cursor_parent_namespace",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "signing_key_generation",
        "handle_hmac",
        name="uq_sync_cursor_handle_lookup",
    ),
    sa.UniqueConstraint("parent_cursor_id", name="uq_sync_cursor_parent_no_fork"),
    sa.UniqueConstraint(
        "sync_cursor_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "snapshot_kind",
        "cursor_kind",
        "protocol_stream",
        "exact_position",
        name="uq_sync_cursor_source_binding",
    ),
    sa.UniqueConstraint(
        "sync_cursor_id",
        "snapshot_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "cursor_kind",
        "protocol_stream",
        name="uq_sync_cursor_snapshot_kind_binding",
    ),
    sa.UniqueConstraint(
        "sync_cursor_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "snapshot_id",
        "snapshot_kind",
        "cursor_kind",
        "protocol_stream",
        "exact_position",
        "lineage_depth",
        name="uq_sync_cursor_parent_namespace",
    ),
    sa.UniqueConstraint(
        "sync_cursor_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "cursor_kind",
        "protocol_stream",
        "cursor_state",
        "exact_position",
        name="uq_sync_cursor_read_state_binding",
    ),
    sa.UniqueConstraint(
        "sync_cursor_id",
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        "purge_generation",
        "cursor_kind",
        "protocol_stream",
        "exact_position",
        name="uq_sync_cursor_page_binding",
    ),
    sa.CheckConstraint("generation = 1", name="generation_supported"),
    sa.CheckConstraint(
        "(cursor_kind = 'bootstrap_page' "
        "AND protocol_stream = 'sync_bootstrap_v1' "
        "AND snapshot_kind = 'bootstrap' "
        "AND bootstrap_id IS NOT NULL) OR "
        "(cursor_kind = 'incremental' "
        "AND protocol_stream = 'sync_incremental_v1' "
        "AND snapshot_kind IN ('bootstrap', 'incremental') "
        "AND bootstrap_id IS NULL)",
        name="kind_protocol_binding_coherent",
    ),
    sa.CheckConstraint("octet_length(handle_hmac) = 32", name="handle_hmac_length"),
    sa.CheckConstraint(
        "octet_length(derivation_nonce) = 32",
        name="derivation_nonce_length",
    ),
    sa.CheckConstraint(
        "signing_key_generation > 0",
        name="signing_key_generation_positive",
    ),
    sa.CheckConstraint(
        f"exact_position BETWEEN 0 AND {SAFE_INTEGER_MAX} "
        "AND exact_position <= snapshot_high_watermark_sequence",
        name="position_within_snapshot",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
    sa.CheckConstraint(
        "lineage_depth BETWEEN 0 AND 2147483647",
        name="lineage_depth_range",
    ),
    sa.CheckConstraint(
        "(parent_cursor_id IS NULL "
        "AND parent_snapshot_id IS NULL "
        "AND parent_snapshot_kind IS NULL "
        "AND parent_bootstrap_id IS NULL "
        "AND parent_cursor_kind IS NULL "
        "AND parent_protocol_stream IS NULL "
        "AND parent_exact_position IS NULL "
        "AND parent_lineage_depth IS NULL "
        "AND lineage_depth = 0) OR "
        "(parent_cursor_id IS NOT NULL "
        "AND parent_cursor_id <> sync_cursor_id "
        "AND parent_snapshot_id IS NOT NULL "
        "AND parent_snapshot_kind IS NOT NULL "
        "AND parent_cursor_kind IS NOT NULL "
        "AND parent_protocol_stream IS NOT NULL "
        "AND parent_cursor_kind = cursor_kind "
        "AND parent_protocol_stream = protocol_stream "
        "AND parent_exact_position IS NOT NULL "
        "AND parent_lineage_depth IS NOT NULL "
        "AND lineage_depth = parent_lineage_depth + 1 "
        "AND exact_position >= parent_exact_position "
        "AND ((cursor_kind = 'bootstrap_page' "
        "AND parent_snapshot_id = snapshot_id "
        "AND parent_snapshot_kind = 'bootstrap' "
        "AND parent_bootstrap_id IS NOT NULL "
        "AND parent_bootstrap_id = bootstrap_id) OR "
        "(cursor_kind = 'incremental' "
        "AND snapshot_kind = 'incremental' "
        "AND parent_snapshot_kind IN ('bootstrap', 'incremental') "
        "AND parent_bootstrap_id IS NULL)))",
        name="lineage_coherent",
    ),
    sa.CheckConstraint(
        "parent_cursor_id IS NOT NULL OR cursor_kind = 'bootstrap_page' "
        "OR snapshot_kind = 'bootstrap'",
        name="incremental_root_uses_bootstrap_snapshot",
    ),
    sa.CheckConstraint(
        "(cursor_state = 'staged' AND consumed_at IS NULL AND revoked_at IS NULL) OR "
        "(cursor_state = 'current' AND consumed_at IS NULL AND revoked_at IS NULL) OR "
        "(cursor_state = 'consumed' AND consumed_at IS NOT NULL "
        "AND consumed_at >= issued_at AND revoked_at IS NULL) OR "
        "(cursor_state = 'revoked' AND revoked_at IS NOT NULL "
        "AND revoked_at >= issued_at "
        "AND (consumed_at IS NULL OR consumed_at >= issued_at) "
        "AND (consumed_at IS NULL OR revoked_at >= consumed_at))",
        name="state_metadata_coherent",
    ),
    sa.CheckConstraint("expires_at > issued_at", name="expiry_after_issue"),
    sa.CheckConstraint(
        "last_used_at IS NULL OR last_used_at >= issued_at",
        name="last_use_after_issue",
    ),
)
sa.Index(
    "ix_sync_cursor_expiry",
    sync_cursor.c.expires_at,
    postgresql_where=sa.text("cursor_state <> 'revoked'"),
)
sa.Index(
    "uq_sync_cursor_current_incremental_namespace",
    sync_cursor.c.person_id,
    sync_cursor.c.device_id,
    sync_cursor.c.credential_family_id,
    sync_cursor.c.sync_stream_id,
    unique=True,
    postgresql_where=sa.text("cursor_kind = 'incremental' AND cursor_state = 'current'"),
)
sa.Index(
    "uq_sync_cursor_current_bootstrap_snapshot",
    sync_cursor.c.snapshot_id,
    unique=True,
    postgresql_where=sa.text("cursor_kind = 'bootstrap_page' AND cursor_state = 'current'"),
)
sa.Index(
    "uq_sync_cursor_incremental_root_snapshot",
    sync_cursor.c.snapshot_id,
    unique=True,
    postgresql_where=sa.text("cursor_kind = 'incremental' AND parent_cursor_id IS NULL"),
)


sync_read_state = sa.Table(
    "sync_read_state",
    metadata,
    sa.Column("sync_read_state_id", UUID, primary_key=True),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("sync_stream_id", UUID, nullable=False),
    sa.Column("protocol_stream", sa.String(32), nullable=False),
    sa.Column("purge_generation", sa.BigInteger(), nullable=False),
    sa.Column("bootstrap_snapshot_id", UUID, nullable=False),
    sa.Column("bootstrap_snapshot_kind", sa.String(16), nullable=False),
    sa.Column("bootstrap_snapshot_status", sa.String(16), nullable=False),
    sa.Column("bootstrap_id", UUID, nullable=False),
    sa.Column("current_incremental_cursor_id", UUID, nullable=False),
    sa.Column("current_cursor_kind", sa.String(24), nullable=False),
    sa.Column("current_cursor_protocol_stream", sa.String(32), nullable=False),
    sa.Column("current_cursor_state", sa.String(16), nullable=False),
    sa.Column("current_exact_position", sa.BigInteger(), nullable=False),
    sa.Column(
        "created_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.Column(
        "updated_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.ForeignKeyConstraint(
        ["sync_stream_id", "person_id"],
        ["sync_stream.sync_stream_id", "sync_stream.person_id"],
        name="fk_sync_read_state_stream_person",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["credential_family_id", "person_id", "device_id"],
        [
            "credential_family.credential_family_id",
            "credential_family.person_id",
            "credential_family.device_id",
        ],
        name="fk_sync_read_state_credential_namespace",
        ondelete="CASCADE",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "bootstrap_snapshot_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "bootstrap_snapshot_kind",
            "bootstrap_snapshot_status",
            "bootstrap_id",
        ],
        [
            "sync_snapshot.snapshot_id",
            "sync_snapshot.person_id",
            "sync_snapshot.device_id",
            "sync_snapshot.credential_family_id",
            "sync_snapshot.sync_stream_id",
            "sync_snapshot.purge_generation",
            "sync_snapshot.snapshot_kind",
            "sync_snapshot.status",
            "sync_snapshot.bootstrap_id",
        ],
        name="fk_sync_read_state_bootstrap_snapshot",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "current_incremental_cursor_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "current_cursor_kind",
            "current_cursor_protocol_stream",
            "current_cursor_state",
            "current_exact_position",
        ],
        [
            "sync_cursor.sync_cursor_id",
            "sync_cursor.person_id",
            "sync_cursor.device_id",
            "sync_cursor.credential_family_id",
            "sync_cursor.sync_stream_id",
            "sync_cursor.purge_generation",
            "sync_cursor.cursor_kind",
            "sync_cursor.protocol_stream",
            "sync_cursor.cursor_state",
            "sync_cursor.exact_position",
        ],
        name="fk_sync_read_state_current_cursor",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint(
        "person_id",
        "device_id",
        "credential_family_id",
        "sync_stream_id",
        name="uq_sync_read_state_namespace",
    ),
    sa.UniqueConstraint(
        "current_incremental_cursor_id",
        name="uq_sync_read_state_current_cursor",
    ),
    sa.CheckConstraint(
        "protocol_stream = 'life_events' "
        "AND bootstrap_snapshot_kind = 'bootstrap' "
        "AND bootstrap_snapshot_status = 'complete' "
        "AND current_cursor_kind = 'incremental' "
        "AND current_cursor_protocol_stream = 'sync_incremental_v1' "
        "AND current_cursor_state = 'current'",
        name="authority_binding_coherent",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX} "
        f"AND current_exact_position BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="position_generation_range",
    ),
    sa.CheckConstraint("updated_at >= created_at", name="update_time_order"),
)


sync_read_page = sa.Table(
    "sync_read_page",
    metadata,
    sa.Column("page_id", UUID, primary_key=True),
    sa.Column("endpoint_id", sa.String(32), nullable=False),
    sa.Column("protocol_version", sa.String(16), nullable=False),
    sa.Column("request_identity_kind", sa.String(16), nullable=False),
    sa.Column("request_id", UUID, nullable=False),
    sa.Column("http_replay_id", UUID, nullable=False),
    sa.Column("replay_outcome_class", sa.String(16), nullable=False),
    sa.Column("replay_stored_outcome", sa.String(128), nullable=False),
    sa.Column("replay_http_status", sa.SmallInteger(), nullable=False),
    sa.Column("person_id", UUID, nullable=False),
    sa.Column("device_id", UUID, nullable=False),
    sa.Column("credential_family_id", UUID, nullable=False),
    sa.Column("sync_stream_id", UUID, nullable=False),
    sa.Column("protocol_stream", sa.String(32), nullable=False),
    sa.Column("purge_generation", sa.BigInteger(), nullable=False),
    sa.Column("snapshot_id", UUID, nullable=False),
    sa.Column("snapshot_kind", sa.String(16), nullable=False),
    sa.Column("bootstrap_id", UUID),
    sa.Column("page_ordinal", sa.Integer(), nullable=False),
    sa.Column("requested_page_size", sa.SmallInteger(), nullable=False),
    sa.Column("from_cursor_id", UUID),
    sa.Column("from_cursor_kind", sa.String(24)),
    sa.Column("from_cursor_protocol_stream", sa.String(32)),
    sa.Column("from_exact_position", sa.BigInteger()),
    sa.Column("next_cursor_id", UUID),
    sa.Column("next_cursor_kind", sa.String(24)),
    sa.Column("next_cursor_protocol_stream", sa.String(32)),
    sa.Column("next_exact_position", sa.BigInteger()),
    sa.Column("incremental_cursor_id", UUID),
    sa.Column("incremental_cursor_kind", sa.String(24)),
    sa.Column("incremental_cursor_protocol_stream", sa.String(32)),
    sa.Column("incremental_exact_position", sa.BigInteger()),
    sa.Column("change_count", sa.SmallInteger(), nullable=False),
    sa.Column("first_server_sequence", sa.BigInteger()),
    sa.Column("last_server_sequence", sa.BigInteger()),
    sa.Column("has_more", sa.Boolean(), nullable=False),
    sa.Column("page_sha256", BYTEA, nullable=False),
    sa.Column("response_body_sha256", BYTEA, nullable=False),
    sa.Column("response_body_plaintext_bytes", sa.Integer(), nullable=False),
    sa.Column("server_time", UTC_TIMESTAMP, nullable=False),
    sa.Column(
        "committed_at",
        UTC_TIMESTAMP,
        nullable=False,
        server_default=sa.text("CURRENT_TIMESTAMP"),
    ),
    sa.ForeignKeyConstraint(
        ["sync_stream_id", "person_id"],
        ["sync_stream.sync_stream_id", "sync_stream.person_id"],
        name="fk_sync_read_page_stream_person",
        ondelete="CASCADE",
    ),
    sa.ForeignKeyConstraint(
        ["credential_family_id", "person_id", "device_id"],
        [
            "credential_family.credential_family_id",
            "credential_family.person_id",
            "credential_family.device_id",
        ],
        name="fk_sync_read_page_credential_namespace",
        ondelete="CASCADE",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "snapshot_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "snapshot_kind",
        ],
        [
            "sync_snapshot.snapshot_id",
            "sync_snapshot.person_id",
            "sync_snapshot.device_id",
            "sync_snapshot.credential_family_id",
            "sync_snapshot.sync_stream_id",
            "sync_snapshot.purge_generation",
            "sync_snapshot.snapshot_kind",
        ],
        name="fk_sync_read_page_snapshot",
        ondelete="NO ACTION",
    ),
    sa.ForeignKeyConstraint(
        ["snapshot_id", "bootstrap_id"],
        ["sync_snapshot.snapshot_id", "sync_snapshot.bootstrap_id"],
        name="fk_sync_read_page_bootstrap_snapshot",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "from_cursor_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "from_cursor_kind",
            "from_cursor_protocol_stream",
            "from_exact_position",
        ],
        [
            "sync_cursor.sync_cursor_id",
            "sync_cursor.person_id",
            "sync_cursor.device_id",
            "sync_cursor.credential_family_id",
            "sync_cursor.sync_stream_id",
            "sync_cursor.purge_generation",
            "sync_cursor.cursor_kind",
            "sync_cursor.protocol_stream",
            "sync_cursor.exact_position",
        ],
        name="fk_sync_read_page_from_cursor",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "next_cursor_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "next_cursor_kind",
            "next_cursor_protocol_stream",
            "next_exact_position",
        ],
        [
            "sync_cursor.sync_cursor_id",
            "sync_cursor.person_id",
            "sync_cursor.device_id",
            "sync_cursor.credential_family_id",
            "sync_cursor.sync_stream_id",
            "sync_cursor.purge_generation",
            "sync_cursor.cursor_kind",
            "sync_cursor.protocol_stream",
            "sync_cursor.exact_position",
        ],
        name="fk_sync_read_page_next_cursor",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "incremental_cursor_id",
            "person_id",
            "device_id",
            "credential_family_id",
            "sync_stream_id",
            "purge_generation",
            "incremental_cursor_kind",
            "incremental_cursor_protocol_stream",
            "incremental_exact_position",
        ],
        [
            "sync_cursor.sync_cursor_id",
            "sync_cursor.person_id",
            "sync_cursor.device_id",
            "sync_cursor.credential_family_id",
            "sync_cursor.sync_stream_id",
            "sync_cursor.purge_generation",
            "sync_cursor.cursor_kind",
            "sync_cursor.protocol_stream",
            "sync_cursor.exact_position",
        ],
        name="fk_sync_read_page_incremental_cursor",
        ondelete="NO ACTION",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.ForeignKeyConstraint(
        [
            "http_replay_id",
            "endpoint_id",
            "protocol_version",
            "request_identity_kind",
            "request_id",
            "person_id",
            "credential_family_id",
            "device_id",
            "replay_outcome_class",
            "replay_stored_outcome",
            "replay_http_status",
            "response_body_sha256",
            "response_body_plaintext_bytes",
            "committed_at",
            "purge_generation",
        ],
        [
            "http_replay.http_replay_id",
            "http_replay.endpoint_id",
            "http_replay.protocol_version",
            "http_replay.request_identity_kind",
            "http_replay.request_identity",
            "http_replay.person_id",
            "http_replay.credential_family_id",
            "http_replay.device_id",
            "http_replay.outcome_class",
            "http_replay.stored_outcome",
            "http_replay.http_status",
            "http_replay.response_body_sha256",
            "http_replay.response_body_plaintext_bytes",
            "http_replay.committed_at",
            "http_replay.purge_generation",
        ],
        name="fk_sync_read_page_replay_binding",
        ondelete="CASCADE",
        deferrable=True,
        initially="DEFERRED",
    ),
    sa.UniqueConstraint("http_replay_id", name="uq_sync_read_page_replay"),
    sa.UniqueConstraint(
        "endpoint_id",
        "credential_family_id",
        "device_id",
        "request_id",
        name="uq_sync_read_page_request_namespace",
    ),
    sa.UniqueConstraint(
        "snapshot_id",
        "page_ordinal",
        name="uq_sync_read_page_snapshot_ordinal",
    ),
    sa.CheckConstraint(
        "protocol_version = '1.0.0' "
        "AND request_identity_kind = 'request_id' "
        "AND protocol_stream = 'life_events' "
        "AND replay_outcome_class = 'success' "
        "AND replay_stored_outcome = 'authenticated_success' "
        "AND replay_http_status = 200",
        name="protocol_binding_coherent",
    ),
    sa.CheckConstraint(
        "page_ordinal BETWEEN 0 AND 2147483647 "
        "AND requested_page_size BETWEEN 1 AND 500 "
        "AND change_count BETWEEN 0 AND requested_page_size",
        name="page_bounds",
    ),
    sa.CheckConstraint(
        "(change_count = 0 "
        "AND first_server_sequence IS NULL "
        "AND last_server_sequence IS NULL) OR "
        "(change_count > 0 "
        "AND first_server_sequence IS NOT NULL "
        "AND last_server_sequence IS NOT NULL "
        "AND first_server_sequence <= last_server_sequence "
        "AND last_server_sequence - first_server_sequence + 1 >= change_count)",
        name="sequence_evidence_coherent",
    ),
    sa.CheckConstraint(
        "(endpoint_id = 'sync_bootstrap' "
        "AND snapshot_kind = 'bootstrap' "
        "AND bootstrap_id IS NOT NULL "
        "AND incremental_cursor_id IS NOT NULL "
        "AND incremental_cursor_kind IS NOT NULL "
        "AND incremental_cursor_protocol_stream IS NOT NULL "
        "AND incremental_cursor_kind = 'incremental' "
        "AND incremental_cursor_protocol_stream = 'sync_incremental_v1' "
        "AND incremental_exact_position IS NOT NULL "
        "AND ((page_ordinal = 0 "
        "AND from_cursor_id IS NULL "
        "AND from_cursor_kind IS NULL "
        "AND from_cursor_protocol_stream IS NULL "
        "AND from_exact_position IS NULL) OR "
        "(page_ordinal > 0 "
        "AND from_cursor_id IS NOT NULL "
        "AND from_cursor_kind IS NOT NULL "
        "AND from_cursor_protocol_stream IS NOT NULL "
        "AND from_cursor_kind = 'bootstrap_page' "
        "AND from_cursor_protocol_stream = 'sync_bootstrap_v1' "
        "AND from_exact_position IS NOT NULL)) "
        "AND ((has_more = true "
        "AND change_count > 0 "
        "AND next_cursor_id IS NOT NULL "
        "AND next_cursor_kind IS NOT NULL "
        "AND next_cursor_protocol_stream IS NOT NULL "
        "AND next_cursor_kind = 'bootstrap_page' "
        "AND next_cursor_protocol_stream = 'sync_bootstrap_v1' "
        "AND next_exact_position IS NOT NULL) OR "
        "(has_more = false "
        "AND next_cursor_id IS NULL "
        "AND next_cursor_kind IS NULL "
        "AND next_cursor_protocol_stream IS NULL "
        "AND next_exact_position IS NULL))) OR "
        "(endpoint_id = 'sync_pull' "
        "AND snapshot_kind = 'incremental' "
        "AND bootstrap_id IS NULL "
        "AND incremental_cursor_id IS NULL "
        "AND incremental_cursor_kind IS NULL "
        "AND incremental_cursor_protocol_stream IS NULL "
        "AND incremental_exact_position IS NULL "
        "AND from_cursor_id IS NOT NULL "
        "AND from_cursor_kind IS NOT NULL "
        "AND from_cursor_protocol_stream IS NOT NULL "
        "AND from_cursor_kind = 'incremental' "
        "AND from_cursor_protocol_stream = 'sync_incremental_v1' "
        "AND from_exact_position IS NOT NULL "
        "AND next_cursor_id IS NOT NULL "
        "AND next_cursor_kind IS NOT NULL "
        "AND next_cursor_protocol_stream IS NOT NULL "
        "AND next_cursor_kind = 'incremental' "
        "AND next_cursor_protocol_stream = 'sync_incremental_v1' "
        "AND next_exact_position IS NOT NULL "
        "AND ((change_count = 0 "
        "AND has_more = false "
        "AND next_cursor_id = from_cursor_id "
        "AND next_exact_position = from_exact_position) OR "
        "(change_count > 0 "
        "AND next_cursor_id <> from_cursor_id "
        "AND next_exact_position > from_exact_position)))",
        name="endpoint_cursor_binding_coherent",
    ),
    sa.CheckConstraint(
        "(endpoint_id = 'sync_bootstrap' AND ("
        "(change_count = 0 "
        "AND has_more = false "
        "AND COALESCE(from_exact_position, 0) = incremental_exact_position) OR "
        "(change_count > 0 "
        "AND first_server_sequence > COALESCE(from_exact_position, 0) "
        "AND ((has_more = true AND last_server_sequence <= next_exact_position) OR "
        "(has_more = false AND last_server_sequence <= incremental_exact_position))))) OR "
        "(endpoint_id = 'sync_pull' AND ("
        "(change_count = 0 "
        "AND first_server_sequence IS NULL "
        "AND last_server_sequence IS NULL "
        "AND next_exact_position = from_exact_position) OR "
        "(change_count > 0 "
        "AND first_server_sequence > from_exact_position "
        "AND last_server_sequence <= next_exact_position)))",
        name="sequence_cursor_progress_coherent",
    ),
    sa.CheckConstraint(
        "octet_length(page_sha256) = 32 "
        "AND octet_length(response_body_sha256) = 32 "
        f"AND response_body_plaintext_bytes BETWEEN 1 AND {MAX_REPLAY_BODY_BYTES}",
        name="response_evidence_coherent",
    ),
    sa.CheckConstraint(
        f"purge_generation BETWEEN 0 AND {SAFE_INTEGER_MAX}",
        name="purge_generation_range",
    ),
    sa.CheckConstraint("server_time <= committed_at", name="commit_time_order"),
)
