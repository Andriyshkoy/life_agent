"""Align durable replay and pending sync-operation invariants."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260730_0003"
down_revision: str | Sequence[str] | None = "20260730_0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_OBSOLETE_SYNC_401_OUTCOME = (
    "terminal_sync_401_after_one_allowed_credential_recovery_"
    "and_current_generation_exact_original_request_retry_exhausted"
)
_STORED_OUTCOME_ALLOWED = (
    "stored_outcome IN ("
    "'authenticated_success', "
    "'authenticated_nonretryable_terminal_api_error', "
    "'terminal_auth_revoke_401_credential_unavailable', "
    "'terminal_operation_result_batch'"
    ")"
)
_LEGACY_STORED_OUTCOME_ALLOWED = (
    "stored_outcome IN ("
    "'authenticated_success', "
    "'authenticated_nonretryable_terminal_api_error', "
    "'terminal_auth_revoke_401_credential_unavailable', "
    f"'{_OBSOLETE_SYNC_401_OUTCOME}', "
    "'terminal_operation_result_batch'"
    ")"
)
_STORED_OUTCOME_COHERENT = (
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
    ")"
)
_LEGACY_STORED_OUTCOME_COHERENT = (
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
    ") OR ("
    "endpoint_id IN ('sync_push', 'sync_bootstrap', 'sync_pull') "
    f"AND stored_outcome = '{_OBSOLETE_SYNC_401_OUTCOME}' "
    "AND http_status = 401 AND error_code = 'credential_unavailable'"
    ")"
    ")"
    ")"
)
_PENDING_PARENT_IDENTITY_COHERENT = (
    "("
    "registry_state = 'pending_missing_parent' "
    "AND event_id IS NULL "
    "AND expected_current_revision_id IS NOT NULL"
    ") OR ("
    "registry_state IN ('terminal_error', 'committed') "
    "AND event_id IS NOT NULL"
    ")"
)
_ENDPOINT_RESPONSE_PLAINTEXT_SIZE = (
    "("
    "endpoint_id = 'auth_revoke' "
    "AND response_body_plaintext_bytes <= 16384"
    ") OR ("
    "endpoint_id = 'sync_push' "
    "AND response_body_plaintext_bytes <= 524288"
    ") OR ("
    "endpoint_id IN ('sync_bootstrap', 'sync_pull') "
    "AND response_body_plaintext_bytes <= 4194304"
    ")"
)
_START_TIME_PRECISION_COHERENT = (
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
    ")"
)
_INTERVAL_FIELDS_COHERENT = (
    "("
    "effective_end_utc IS NULL "
    "AND original_local_end IS NULL "
    "AND end_offset_seconds IS NULL"
    ") OR ("
    "effective_end_utc IS NOT NULL "
    "AND effective_start_utc IS NOT NULL "
    "AND original_local_end IS NOT NULL "
    "AND end_offset_seconds IS NOT NULL "
    "AND effective_end_utc >= effective_start_utc"
    ")"
)
_LEGACY_INTERVAL_FIELDS_COHERENT = (
    "("
    "effective_end_utc IS NULL "
    "AND original_local_end IS NULL "
    "AND end_offset_seconds IS NULL"
    ") OR ("
    "effective_end_utc IS NOT NULL "
    "AND original_local_end IS NOT NULL "
    "AND end_offset_seconds IS NOT NULL "
    "AND effective_end_utc >= effective_start_utc"
    ")"
)


def upgrade() -> None:
    op.execute(
        "LOCK TABLE event_revision, http_replay, sync_operation, "
        "sync_operation_registry IN SHARE ROW EXCLUSIVE MODE"
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM http_replay
                WHERE stored_outcome =
                    'terminal_sync_401_after_one_allowed_credential_recovery_'
                    'and_current_generation_exact_original_request_retry_exhausted'
            ) THEN
                RAISE EXCEPTION
                    'obsolete sync credential replay rows must be purged explicitly'
                    USING ERRCODE = '23514';
            END IF;
            IF EXISTS (
                SELECT 1
                FROM sync_operation_registry
                WHERE registry_state = 'pending_missing_parent'
                  AND expected_current_revision_id IS NULL
            ) THEN
                RAISE EXCEPTION
                    'pending missing-parent operation lacks expected revision'
                    USING ERRCODE = '23514';
            END IF;
        END;
        $$
        """
    )
    op.alter_column(
        "event_revision",
        "effective_start_utc",
        existing_type=sa.TIMESTAMP(timezone=True),
        existing_nullable=False,
        nullable=True,
    )
    op.alter_column(
        "event_revision",
        "original_local_start",
        existing_type=sa.TIMESTAMP(timezone=False),
        existing_nullable=False,
        nullable=True,
    )
    op.alter_column(
        "event_revision",
        "start_offset_seconds",
        existing_type=sa.Integer(),
        existing_nullable=False,
        nullable=True,
    )
    op.alter_column(
        "event_revision",
        "local_date",
        existing_type=sa.Date(),
        existing_nullable=False,
        nullable=True,
    )
    op.create_check_constraint(
        op.f("ck_event_revision_start_time_precision_coherent"),
        "event_revision",
        _START_TIME_PRECISION_COHERENT,
    )
    op.drop_constraint(
        op.f("ck_event_revision_interval_fields_coherent"),
        "event_revision",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_event_revision_interval_fields_coherent"),
        "event_revision",
        _INTERVAL_FIELDS_COHERENT,
    )
    op.drop_constraint(
        op.f("uq_sync_operation_first_batch_membership"),
        "sync_operation",
        type_="unique",
    )
    op.drop_constraint(
        op.f("uq_sync_operation_registry_first_batch_membership"),
        "sync_operation_registry",
        type_="unique",
    )
    op.create_index(
        "ix_sync_operation_registry_first_batch_membership",
        "sync_operation_registry",
        [
            "credential_family_id",
            "submitting_device_id",
            "first_batch_id",
            "first_batch_ordinal",
        ],
    )
    op.create_index(
        "ix_sync_operation_first_batch_membership",
        "sync_operation",
        [
            "credential_family_id",
            "submitting_device_id",
            "first_batch_id",
            "first_batch_ordinal",
        ],
    )

    op.alter_column(
        "sync_operation_registry",
        "event_id",
        existing_type=postgresql.UUID(as_uuid=True),
        existing_nullable=False,
        nullable=True,
    )
    op.execute(
        """
        UPDATE sync_operation_registry
        SET event_id = NULL
        WHERE registry_state = 'pending_missing_parent'
        """
    )
    op.create_check_constraint(
        op.f("ck_sync_operation_registry_pending_parent_identity_coherent"),
        "sync_operation_registry",
        _PENDING_PARENT_IDENTITY_COHERENT,
    )

    op.drop_constraint(
        op.f("ck_http_replay_stored_outcome_coherent"),
        "http_replay",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_http_replay_stored_outcome_allowed"),
        "http_replay",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_http_replay_stored_outcome_allowed"),
        "http_replay",
        _STORED_OUTCOME_ALLOWED,
    )
    op.create_check_constraint(
        op.f("ck_http_replay_stored_outcome_coherent"),
        "http_replay",
        _STORED_OUTCOME_COHERENT,
    )
    op.create_check_constraint(
        op.f("ck_http_replay_endpoint_response_plaintext_size"),
        "http_replay",
        _ENDPOINT_RESPONSE_PLAINTEXT_SIZE,
    )


def downgrade() -> None:
    op.execute(
        "LOCK TABLE event_revision, http_replay, sync_operation, "
        "sync_operation_registry IN SHARE ROW EXCLUSIVE MODE"
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM sync_operation_registry
                GROUP BY
                    credential_family_id,
                    submitting_device_id,
                    first_batch_id,
                    first_batch_ordinal
                HAVING count(*) > 1
            ) OR EXISTS (
                SELECT 1
                FROM sync_operation
                GROUP BY
                    credential_family_id,
                    submitting_device_id,
                    first_batch_id,
                    first_batch_ordinal
                HAVING count(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'batch namespace reuse prevents invariant downgrade'
                    USING ERRCODE = '23505';
            END IF;
            IF EXISTS (
                SELECT 1
                FROM event_revision
                WHERE effective_start_utc IS NULL
                   OR original_local_start IS NULL
                   OR start_offset_seconds IS NULL
                   OR local_date IS NULL
            ) THEN
                RAISE EXCEPTION
                    'nullable event start time prevents invariant downgrade'
                    USING ERRCODE = '23502';
            END IF;
        END;
        $$
        """
    )
    op.drop_constraint(
        op.f("ck_http_replay_endpoint_response_plaintext_size"),
        "http_replay",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_http_replay_stored_outcome_coherent"),
        "http_replay",
        type_="check",
    )
    op.drop_constraint(
        op.f("ck_http_replay_stored_outcome_allowed"),
        "http_replay",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_http_replay_stored_outcome_allowed"),
        "http_replay",
        _LEGACY_STORED_OUTCOME_ALLOWED,
    )
    op.create_check_constraint(
        op.f("ck_http_replay_stored_outcome_coherent"),
        "http_replay",
        _LEGACY_STORED_OUTCOME_COHERENT,
    )

    op.drop_constraint(
        op.f("ck_sync_operation_registry_pending_parent_identity_coherent"),
        "sync_operation_registry",
        type_="check",
    )
    op.execute(
        r"""
        DO $$
        DECLARE
            pending_row RECORD;
            restored_event_id TEXT;
        BEGIN
            FOR pending_row IN
                SELECT canonical_operation
                FROM sync_operation_registry
                WHERE event_id IS NULL
            LOOP
                BEGIN
                    restored_event_id :=
                        convert_from(pending_row.canonical_operation, 'UTF8')::jsonb
                        ->> 'event_id';
                EXCEPTION
                    WHEN OTHERS THEN
                        RAISE EXCEPTION
                            'pending operation canonical bytes cannot restore event id'
                            USING ERRCODE = '23514';
                END;
                IF restored_event_id IS NULL OR restored_event_id !~
                    '^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-'
                    '[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                THEN
                    RAISE EXCEPTION
                        'pending operation canonical event id is not canonical'
                        USING ERRCODE = '23514';
                END IF;
            END LOOP;
        END;
        $$
        """
    )
    op.execute(
        """
        UPDATE sync_operation_registry
        SET event_id = (
            convert_from(canonical_operation, 'UTF8')::jsonb
            ->> 'event_id'
        )::uuid
        WHERE event_id IS NULL
        """
    )
    op.alter_column(
        "sync_operation_registry",
        "event_id",
        existing_type=postgresql.UUID(as_uuid=True),
        existing_nullable=True,
        nullable=False,
    )
    op.drop_constraint(
        op.f("ck_event_revision_interval_fields_coherent"),
        "event_revision",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_event_revision_interval_fields_coherent"),
        "event_revision",
        _LEGACY_INTERVAL_FIELDS_COHERENT,
    )
    op.drop_constraint(
        op.f("ck_event_revision_start_time_precision_coherent"),
        "event_revision",
        type_="check",
    )
    op.alter_column(
        "event_revision",
        "effective_start_utc",
        existing_type=sa.TIMESTAMP(timezone=True),
        existing_nullable=True,
        nullable=False,
    )
    op.alter_column(
        "event_revision",
        "original_local_start",
        existing_type=sa.TIMESTAMP(timezone=False),
        existing_nullable=True,
        nullable=False,
    )
    op.alter_column(
        "event_revision",
        "start_offset_seconds",
        existing_type=sa.Integer(),
        existing_nullable=True,
        nullable=False,
    )
    op.alter_column(
        "event_revision",
        "local_date",
        existing_type=sa.Date(),
        existing_nullable=True,
        nullable=False,
    )
    op.drop_index(
        "ix_sync_operation_first_batch_membership",
        table_name="sync_operation",
    )
    op.drop_index(
        "ix_sync_operation_registry_first_batch_membership",
        table_name="sync_operation_registry",
    )
    op.create_unique_constraint(
        op.f("uq_sync_operation_registry_first_batch_membership"),
        "sync_operation_registry",
        [
            "credential_family_id",
            "submitting_device_id",
            "first_batch_id",
            "first_batch_ordinal",
        ],
    )
    op.create_unique_constraint(
        op.f("uq_sync_operation_first_batch_membership"),
        "sync_operation",
        [
            "credential_family_id",
            "submitting_device_id",
            "first_batch_id",
            "first_batch_ordinal",
        ],
    )
