"""Install the durable sync-read state, cursor, page, and replay invariants."""

from collections.abc import Sequence

from alembic import op

revision: str = "20260731_0004"
down_revision: str | Sequence[str] | None = "20260730_0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_LOCK_TABLES_UPGRADE = (
    "person, credential_family, credential_generation, device, "
    "http_replay, device_replay_quota, sync_stream, sync_operation, "
    "sync_snapshot, sync_cursor"
)
_LOCK_TABLES_DOWNGRADE = (
    "person, credential_family, credential_generation, device, "
    "http_replay, device_replay_quota, sync_stream, sync_operation, "
    "sync_read_state, sync_snapshot, sync_cursor, sync_read_page"
)

_REFUSE_LEGACY_READ_STATE = """
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sync_cursor) THEN
        RAISE EXCEPTION
            'legacy sync cursor rows prevent read-invariant upgrade'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM sync_snapshot) THEN
        RAISE EXCEPTION
            'legacy sync snapshot rows prevent read-invariant upgrade'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM http_replay
        WHERE endpoint_id IN ('sync_bootstrap', 'sync_pull')
          AND outcome_class = 'success'
    ) THEN
        RAISE EXCEPTION
            'legacy successful sync-read replay lacks exact page evidence'
            USING ERRCODE = '23514';
    END IF;
END;
$$
"""

_SYNC_SNAPSHOT_SQL = """
CREATE TABLE sync_snapshot (
    snapshot_id UUID NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL,
    bootstrap_id UUID,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL,
    credential_family_id UUID NOT NULL,
    sync_stream_id UUID NOT NULL,
    protocol_stream VARCHAR(32) NOT NULL,
    start_sequence BIGINT NOT NULL,
    high_watermark_sequence BIGINT NOT NULL,
    source_cursor_id UUID,
    source_cursor_kind VARCHAR(24),
    source_cursor_protocol_stream VARCHAR(32),
    bootstrap_incremental_cursor_id UUID,
    bootstrap_incremental_cursor_kind VARCHAR(24),
    bootstrap_incremental_cursor_protocol_stream VARCHAR(32),
    purge_generation BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_sync_snapshot PRIMARY KEY (snapshot_id),
    CONSTRAINT fk_sync_snapshot_stream_person
        FOREIGN KEY (sync_stream_id, person_id)
        REFERENCES sync_stream (sync_stream_id, person_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sync_snapshot_credential_namespace
        FOREIGN KEY (credential_family_id, person_id, device_id)
        REFERENCES credential_family (credential_family_id, person_id, device_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_sync_snapshot_bootstrap_attempt
        UNIQUE (person_id, device_id, credential_family_id, bootstrap_id),
    CONSTRAINT uq_sync_snapshot_cursor_binding UNIQUE (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind,
        high_watermark_sequence
    ),
    CONSTRAINT uq_sync_snapshot_bootstrap_binding
        UNIQUE (snapshot_id, bootstrap_id),
    CONSTRAINT uq_sync_snapshot_read_state_binding UNIQUE (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind,
        status,
        bootstrap_id
    ),
    CONSTRAINT uq_sync_snapshot_page_binding UNIQUE (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind
    ),
    CONSTRAINT ck_sync_snapshot_protocol_stream_supported
        CHECK (protocol_stream = 'life_events'),
    CONSTRAINT ck_sync_snapshot_snapshot_kind_binding_coherent CHECK (
        (
            snapshot_kind = 'bootstrap'
            AND bootstrap_id IS NOT NULL
            AND start_sequence = 0
            AND source_cursor_id IS NULL
            AND source_cursor_kind IS NULL
            AND source_cursor_protocol_stream IS NULL
            AND bootstrap_incremental_cursor_id IS NOT NULL
            AND bootstrap_incremental_cursor_kind = 'incremental'
            AND bootstrap_incremental_cursor_protocol_stream = 'sync_incremental_v1'
        )
        OR (
            snapshot_kind = 'incremental'
            AND bootstrap_id IS NULL
            AND source_cursor_id IS NOT NULL
            AND source_cursor_kind = 'incremental'
            AND source_cursor_protocol_stream = 'sync_incremental_v1'
            AND bootstrap_incremental_cursor_id IS NULL
            AND bootstrap_incremental_cursor_kind IS NULL
            AND bootstrap_incremental_cursor_protocol_stream IS NULL
        )
    ),
    CONSTRAINT ck_sync_snapshot_sequence_window_coherent CHECK (
        start_sequence BETWEEN 0 AND 9007199254740991
        AND high_watermark_sequence BETWEEN 0 AND 9007199254740991
        AND start_sequence <= high_watermark_sequence
    ),
    CONSTRAINT ck_sync_snapshot_purge_generation_range
        CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_snapshot_status_allowed
        CHECK (status IN ('active', 'complete', 'expired', 'revoked')),
    CONSTRAINT ck_sync_snapshot_expiry_after_creation
        CHECK (expires_at > created_at),
    CONSTRAINT ck_sync_snapshot_status_metadata_coherent CHECK (
        (status = 'active' AND completed_at IS NULL AND revoked_at IS NULL)
        OR (status = 'complete' AND completed_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'expired' AND revoked_at IS NULL)
        OR (status = 'revoked' AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT ck_sync_snapshot_lifecycle_time_order CHECK (
        (completed_at IS NULL OR completed_at >= created_at)
        AND (revoked_at IS NULL OR revoked_at >= created_at)
        AND (
            completed_at IS NULL
            OR revoked_at IS NULL
            OR revoked_at >= completed_at
        )
    )
)
"""

_SYNC_CURSOR_SQL = """
CREATE TABLE sync_cursor (
    sync_cursor_id UUID NOT NULL,
    generation SMALLINT DEFAULT 1 NOT NULL,
    cursor_kind VARCHAR(24) NOT NULL,
    protocol_stream VARCHAR(32) NOT NULL,
    handle_hmac BYTEA NOT NULL,
    derivation_nonce BYTEA NOT NULL,
    signing_key_generation INTEGER NOT NULL,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL,
    credential_family_id UUID NOT NULL,
    sync_stream_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL,
    bootstrap_id UUID,
    exact_position BIGINT NOT NULL,
    snapshot_high_watermark_sequence BIGINT NOT NULL,
    purge_generation BIGINT NOT NULL,
    cursor_state VARCHAR(16) NOT NULL,
    lineage_depth INTEGER NOT NULL,
    parent_cursor_id UUID,
    parent_snapshot_id UUID,
    parent_snapshot_kind VARCHAR(16),
    parent_bootstrap_id UUID,
    parent_cursor_kind VARCHAR(24),
    parent_protocol_stream VARCHAR(32),
    parent_exact_position BIGINT,
    parent_lineage_depth INTEGER,
    issued_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_sync_cursor PRIMARY KEY (sync_cursor_id),
    CONSTRAINT fk_sync_cursor_snapshot_binding FOREIGN KEY (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind,
        snapshot_high_watermark_sequence
    ) REFERENCES sync_snapshot (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind,
        high_watermark_sequence
    ) ON DELETE CASCADE,
    CONSTRAINT fk_sync_cursor_bootstrap_binding
        FOREIGN KEY (snapshot_id, bootstrap_id)
        REFERENCES sync_snapshot (snapshot_id, bootstrap_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sync_cursor_parent_namespace FOREIGN KEY (
        parent_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        parent_snapshot_id,
        parent_snapshot_kind,
        parent_cursor_kind,
        parent_protocol_stream,
        parent_exact_position,
        parent_lineage_depth
    ) REFERENCES sync_cursor (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_id,
        snapshot_kind,
        cursor_kind,
        protocol_stream,
        exact_position,
        lineage_depth
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_sync_cursor_handle_lookup
        UNIQUE (signing_key_generation, handle_hmac),
    CONSTRAINT uq_sync_cursor_parent_no_fork UNIQUE (parent_cursor_id),
    CONSTRAINT uq_sync_cursor_source_binding UNIQUE (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind,
        cursor_kind,
        protocol_stream,
        exact_position
    ),
    CONSTRAINT uq_sync_cursor_snapshot_kind_binding UNIQUE (
        sync_cursor_id,
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream
    ),
    CONSTRAINT uq_sync_cursor_parent_namespace UNIQUE (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_id,
        snapshot_kind,
        cursor_kind,
        protocol_stream,
        exact_position,
        lineage_depth
    ),
    CONSTRAINT uq_sync_cursor_read_state_binding UNIQUE (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream,
        cursor_state,
        exact_position
    ),
    CONSTRAINT uq_sync_cursor_page_binding UNIQUE (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream,
        exact_position
    ),
    CONSTRAINT ck_sync_cursor_generation_supported CHECK (generation = 1),
    CONSTRAINT ck_sync_cursor_kind_protocol_binding_coherent CHECK (
        (
            cursor_kind = 'bootstrap_page'
            AND protocol_stream = 'sync_bootstrap_v1'
            AND snapshot_kind = 'bootstrap'
            AND bootstrap_id IS NOT NULL
        )
        OR (
            cursor_kind = 'incremental'
            AND protocol_stream = 'sync_incremental_v1'
            AND snapshot_kind IN ('bootstrap', 'incremental')
            AND bootstrap_id IS NULL
        )
    ),
    CONSTRAINT ck_sync_cursor_handle_hmac_length
        CHECK (octet_length(handle_hmac) = 32),
    CONSTRAINT ck_sync_cursor_derivation_nonce_length
        CHECK (octet_length(derivation_nonce) = 32),
    CONSTRAINT ck_sync_cursor_signing_key_generation_positive
        CHECK (signing_key_generation > 0),
    CONSTRAINT ck_sync_cursor_position_within_snapshot CHECK (
        exact_position BETWEEN 0 AND 9007199254740991
        AND exact_position <= snapshot_high_watermark_sequence
    ),
    CONSTRAINT ck_sync_cursor_purge_generation_range
        CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_cursor_lineage_depth_range
        CHECK (lineage_depth BETWEEN 0 AND 2147483647),
    CONSTRAINT ck_sync_cursor_lineage_coherent CHECK (
        (
            parent_cursor_id IS NULL
            AND parent_snapshot_id IS NULL
            AND parent_snapshot_kind IS NULL
            AND parent_bootstrap_id IS NULL
            AND parent_cursor_kind IS NULL
            AND parent_protocol_stream IS NULL
            AND parent_exact_position IS NULL
            AND parent_lineage_depth IS NULL
            AND lineage_depth = 0
        )
        OR (
            parent_cursor_id IS NOT NULL
            AND parent_cursor_id <> sync_cursor_id
            AND parent_snapshot_id IS NOT NULL
            AND parent_snapshot_kind IS NOT NULL
            AND parent_cursor_kind IS NOT NULL
            AND parent_protocol_stream IS NOT NULL
            AND parent_cursor_kind = cursor_kind
            AND parent_protocol_stream = protocol_stream
            AND parent_exact_position IS NOT NULL
            AND parent_lineage_depth IS NOT NULL
            AND lineage_depth = parent_lineage_depth + 1
            AND exact_position >= parent_exact_position
            AND (
                (
                    cursor_kind = 'bootstrap_page'
                    AND parent_snapshot_id = snapshot_id
                    AND parent_snapshot_kind = 'bootstrap'
                    AND parent_bootstrap_id IS NOT NULL
                    AND parent_bootstrap_id = bootstrap_id
                )
                OR (
                    cursor_kind = 'incremental'
                    AND snapshot_kind = 'incremental'
                    AND parent_snapshot_kind IN ('bootstrap', 'incremental')
                    AND parent_bootstrap_id IS NULL
                )
            )
        )
    ),
    CONSTRAINT ck_sync_cursor_incremental_root_uses_bootstrap_snapshot
        CHECK (
            parent_cursor_id IS NOT NULL
            OR cursor_kind = 'bootstrap_page'
            OR snapshot_kind = 'bootstrap'
        ),
    CONSTRAINT ck_sync_cursor_state_metadata_coherent CHECK (
        (cursor_state = 'staged' AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (cursor_state = 'current' AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (
            cursor_state = 'consumed'
            AND consumed_at IS NOT NULL
            AND consumed_at >= issued_at
            AND revoked_at IS NULL
        )
        OR (
            cursor_state = 'revoked'
            AND revoked_at IS NOT NULL
            AND revoked_at >= issued_at
            AND (consumed_at IS NULL OR consumed_at >= issued_at)
            AND (consumed_at IS NULL OR revoked_at >= consumed_at)
        )
    ),
    CONSTRAINT ck_sync_cursor_expiry_after_issue CHECK (expires_at > issued_at),
    CONSTRAINT ck_sync_cursor_last_use_after_issue
        CHECK (last_used_at IS NULL OR last_used_at >= issued_at)
)
"""

_SYNC_READ_STATE_SQL = """
CREATE TABLE sync_read_state (
    sync_read_state_id UUID NOT NULL,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL,
    credential_family_id UUID NOT NULL,
    sync_stream_id UUID NOT NULL,
    protocol_stream VARCHAR(32) NOT NULL,
    purge_generation BIGINT NOT NULL,
    bootstrap_snapshot_id UUID NOT NULL,
    bootstrap_snapshot_kind VARCHAR(16) NOT NULL,
    bootstrap_snapshot_status VARCHAR(16) NOT NULL,
    bootstrap_id UUID NOT NULL,
    current_incremental_cursor_id UUID NOT NULL,
    current_cursor_kind VARCHAR(24) NOT NULL,
    current_cursor_protocol_stream VARCHAR(32) NOT NULL,
    current_cursor_state VARCHAR(16) NOT NULL,
    current_exact_position BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_sync_read_state PRIMARY KEY (sync_read_state_id),
    CONSTRAINT fk_sync_read_state_stream_person
        FOREIGN KEY (sync_stream_id, person_id)
        REFERENCES sync_stream (sync_stream_id, person_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sync_read_state_credential_namespace
        FOREIGN KEY (credential_family_id, person_id, device_id)
        REFERENCES credential_family (credential_family_id, person_id, device_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_state_bootstrap_snapshot FOREIGN KEY (
        bootstrap_snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        bootstrap_snapshot_kind,
        bootstrap_snapshot_status,
        bootstrap_id
    ) REFERENCES sync_snapshot (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind,
        status,
        bootstrap_id
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_state_current_cursor FOREIGN KEY (
        current_incremental_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        current_cursor_kind,
        current_cursor_protocol_stream,
        current_cursor_state,
        current_exact_position
    ) REFERENCES sync_cursor (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream,
        cursor_state,
        exact_position
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_sync_read_state_namespace
        UNIQUE (person_id, device_id, credential_family_id, sync_stream_id),
    CONSTRAINT uq_sync_read_state_current_cursor
        UNIQUE (current_incremental_cursor_id),
    CONSTRAINT ck_sync_read_state_authority_binding_coherent CHECK (
        protocol_stream = 'life_events'
        AND bootstrap_snapshot_kind = 'bootstrap'
        AND bootstrap_snapshot_status = 'complete'
        AND current_cursor_kind = 'incremental'
        AND current_cursor_protocol_stream = 'sync_incremental_v1'
        AND current_cursor_state = 'current'
    ),
    CONSTRAINT ck_sync_read_state_position_generation_range CHECK (
        purge_generation BETWEEN 0 AND 9007199254740991
        AND current_exact_position BETWEEN 0 AND 9007199254740991
    ),
    CONSTRAINT ck_sync_read_state_update_time_order
        CHECK (updated_at >= created_at)
)
"""

_SYNC_READ_PAGE_SQL = """
CREATE TABLE sync_read_page (
    page_id UUID NOT NULL,
    endpoint_id VARCHAR(32) NOT NULL,
    protocol_version VARCHAR(16) NOT NULL,
    request_identity_kind VARCHAR(16) NOT NULL,
    request_id UUID NOT NULL,
    http_replay_id UUID NOT NULL,
    replay_outcome_class VARCHAR(16) NOT NULL,
    replay_stored_outcome VARCHAR(128) NOT NULL,
    replay_http_status SMALLINT NOT NULL,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL,
    credential_family_id UUID NOT NULL,
    sync_stream_id UUID NOT NULL,
    protocol_stream VARCHAR(32) NOT NULL,
    purge_generation BIGINT NOT NULL,
    snapshot_id UUID NOT NULL,
    snapshot_kind VARCHAR(16) NOT NULL,
    bootstrap_id UUID,
    page_ordinal INTEGER NOT NULL,
    requested_page_size SMALLINT NOT NULL,
    from_cursor_id UUID,
    from_cursor_kind VARCHAR(24),
    from_cursor_protocol_stream VARCHAR(32),
    from_exact_position BIGINT,
    next_cursor_id UUID,
    next_cursor_kind VARCHAR(24),
    next_cursor_protocol_stream VARCHAR(32),
    next_exact_position BIGINT,
    incremental_cursor_id UUID,
    incremental_cursor_kind VARCHAR(24),
    incremental_cursor_protocol_stream VARCHAR(32),
    incremental_exact_position BIGINT,
    change_count SMALLINT NOT NULL,
    first_server_sequence BIGINT,
    last_server_sequence BIGINT,
    has_more BOOLEAN NOT NULL,
    page_sha256 BYTEA NOT NULL,
    response_body_sha256 BYTEA NOT NULL,
    response_body_plaintext_bytes INTEGER NOT NULL,
    server_time TIMESTAMP WITH TIME ZONE NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_sync_read_page PRIMARY KEY (page_id),
    CONSTRAINT fk_sync_read_page_stream_person
        FOREIGN KEY (sync_stream_id, person_id)
        REFERENCES sync_stream (sync_stream_id, person_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sync_read_page_credential_namespace
        FOREIGN KEY (credential_family_id, person_id, device_id)
        REFERENCES credential_family (credential_family_id, person_id, device_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_page_snapshot FOREIGN KEY (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind
    ) REFERENCES sync_snapshot (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        snapshot_kind
    ) ON DELETE NO ACTION,
    CONSTRAINT fk_sync_read_page_bootstrap_snapshot
        FOREIGN KEY (snapshot_id, bootstrap_id)
        REFERENCES sync_snapshot (snapshot_id, bootstrap_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_page_from_cursor FOREIGN KEY (
        from_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        from_cursor_kind,
        from_cursor_protocol_stream,
        from_exact_position
    ) REFERENCES sync_cursor (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream,
        exact_position
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_page_next_cursor FOREIGN KEY (
        next_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        next_cursor_kind,
        next_cursor_protocol_stream,
        next_exact_position
    ) REFERENCES sync_cursor (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream,
        exact_position
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_page_incremental_cursor FOREIGN KEY (
        incremental_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        incremental_cursor_kind,
        incremental_cursor_protocol_stream,
        incremental_exact_position
    ) REFERENCES sync_cursor (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        purge_generation,
        cursor_kind,
        protocol_stream,
        exact_position
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_sync_read_page_replay_binding FOREIGN KEY (
        http_replay_id,
        endpoint_id,
        protocol_version,
        request_identity_kind,
        request_id,
        person_id,
        credential_family_id,
        device_id,
        replay_outcome_class,
        replay_stored_outcome,
        replay_http_status,
        response_body_sha256,
        response_body_plaintext_bytes,
        committed_at,
        purge_generation
    ) REFERENCES http_replay (
        http_replay_id,
        endpoint_id,
        protocol_version,
        request_identity_kind,
        request_identity,
        person_id,
        credential_family_id,
        device_id,
        outcome_class,
        stored_outcome,
        http_status,
        response_body_sha256,
        response_body_plaintext_bytes,
        committed_at,
        purge_generation
    ) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_sync_read_page_replay UNIQUE (http_replay_id),
    CONSTRAINT uq_sync_read_page_request_namespace
        UNIQUE (endpoint_id, credential_family_id, device_id, request_id),
    CONSTRAINT uq_sync_read_page_snapshot_ordinal
        UNIQUE (snapshot_id, page_ordinal),
    CONSTRAINT ck_sync_read_page_protocol_binding_coherent CHECK (
        protocol_version = '1.0.0'
        AND request_identity_kind = 'request_id'
        AND protocol_stream = 'life_events'
        AND replay_outcome_class = 'success'
        AND replay_stored_outcome = 'authenticated_success'
        AND replay_http_status = 200
    ),
    CONSTRAINT ck_sync_read_page_page_bounds CHECK (
        page_ordinal BETWEEN 0 AND 2147483647
        AND requested_page_size BETWEEN 1 AND 500
        AND change_count BETWEEN 0 AND requested_page_size
    ),
    CONSTRAINT ck_sync_read_page_sequence_evidence_coherent CHECK (
        (
            change_count = 0
            AND first_server_sequence IS NULL
            AND last_server_sequence IS NULL
        )
        OR (
            change_count > 0
                    AND first_server_sequence IS NOT NULL
                    AND last_server_sequence IS NOT NULL
                    AND first_server_sequence <= last_server_sequence
                    AND last_server_sequence - first_server_sequence + 1 >= change_count
        )
    ),
    CONSTRAINT ck_sync_read_page_endpoint_cursor_binding_coherent CHECK (
        (
            endpoint_id = 'sync_bootstrap'
            AND snapshot_kind = 'bootstrap'
            AND bootstrap_id IS NOT NULL
            AND incremental_cursor_id IS NOT NULL
            AND incremental_cursor_kind IS NOT NULL
            AND incremental_cursor_protocol_stream IS NOT NULL
            AND incremental_cursor_kind = 'incremental'
            AND incremental_cursor_protocol_stream = 'sync_incremental_v1'
            AND incremental_exact_position IS NOT NULL
            AND (
                (
                    page_ordinal = 0
                    AND from_cursor_id IS NULL
                    AND from_cursor_kind IS NULL
                    AND from_cursor_protocol_stream IS NULL
                    AND from_exact_position IS NULL
                )
                OR (
                    page_ordinal > 0
                    AND from_cursor_id IS NOT NULL
                    AND from_cursor_kind IS NOT NULL
                    AND from_cursor_protocol_stream IS NOT NULL
                    AND from_cursor_kind = 'bootstrap_page'
                    AND from_cursor_protocol_stream = 'sync_bootstrap_v1'
                    AND from_exact_position IS NOT NULL
                )
            )
            AND (
                (
                    has_more = true
                    AND change_count > 0
                    AND next_cursor_id IS NOT NULL
                    AND next_cursor_kind IS NOT NULL
                    AND next_cursor_protocol_stream IS NOT NULL
                    AND next_cursor_kind = 'bootstrap_page'
                    AND next_cursor_protocol_stream = 'sync_bootstrap_v1'
                    AND next_exact_position IS NOT NULL
                )
                OR (
                    has_more = false
                    AND next_cursor_id IS NULL
                    AND next_cursor_kind IS NULL
                    AND next_cursor_protocol_stream IS NULL
                    AND next_exact_position IS NULL
                )
            )
        )
        OR (
            endpoint_id = 'sync_pull'
            AND snapshot_kind = 'incremental'
            AND bootstrap_id IS NULL
            AND incremental_cursor_id IS NULL
            AND incremental_cursor_kind IS NULL
            AND incremental_cursor_protocol_stream IS NULL
            AND incremental_exact_position IS NULL
            AND from_cursor_id IS NOT NULL
            AND from_cursor_kind IS NOT NULL
            AND from_cursor_protocol_stream IS NOT NULL
            AND from_cursor_kind = 'incremental'
            AND from_cursor_protocol_stream = 'sync_incremental_v1'
            AND from_exact_position IS NOT NULL
            AND next_cursor_id IS NOT NULL
            AND next_cursor_kind IS NOT NULL
            AND next_cursor_protocol_stream IS NOT NULL
            AND next_cursor_kind = 'incremental'
            AND next_cursor_protocol_stream = 'sync_incremental_v1'
            AND next_exact_position IS NOT NULL
            AND (
                (
                    change_count = 0
                    AND has_more = false
                    AND next_cursor_id = from_cursor_id
                    AND next_exact_position = from_exact_position
                )
                OR (
                    change_count > 0
                    AND next_cursor_id <> from_cursor_id
                    AND next_exact_position > from_exact_position
                )
            )
        )
    ),
    CONSTRAINT ck_sync_read_page_sequence_cursor_progress_coherent CHECK (
        (
            endpoint_id = 'sync_bootstrap'
            AND (
                (
                    change_count = 0
                    AND has_more = false
                    AND COALESCE(from_exact_position, 0) = incremental_exact_position
                )
                OR (
                    change_count > 0
                    AND first_server_sequence > COALESCE(from_exact_position, 0)
                    AND (
                        (has_more = true AND last_server_sequence <= next_exact_position)
                        OR (
                            has_more = false
                            AND last_server_sequence <= incremental_exact_position
                        )
                    )
                )
            )
        )
        OR (
            endpoint_id = 'sync_pull'
            AND (
                (
                    change_count = 0
                    AND first_server_sequence IS NULL
                    AND last_server_sequence IS NULL
                    AND next_exact_position = from_exact_position
                )
                OR (
                    change_count > 0
                    AND first_server_sequence > from_exact_position
                    AND last_server_sequence <= next_exact_position
                )
            )
        )
    ),
    CONSTRAINT ck_sync_read_page_response_evidence_coherent CHECK (
        octet_length(page_sha256) = 32
        AND octet_length(response_body_sha256) = 32
        AND response_body_plaintext_bytes BETWEEN 1 AND 4194304
    ),
    CONSTRAINT ck_sync_read_page_purge_generation_range
        CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_read_page_commit_time_order
        CHECK (server_time <= committed_at)
)
"""

_SNAPSHOT_CAPTURE_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_guard_sync_snapshot_capture()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    captured_stream_head BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT stream.last_server_sequence
        INTO captured_stream_head
        FROM public.sync_stream AS stream
        WHERE stream.sync_stream_id = NEW.sync_stream_id
          AND stream.person_id = NEW.person_id
        FOR SHARE;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'sync snapshot has no matching stream head to capture'
                USING ERRCODE = '23503';
        END IF;

        IF NEW.high_watermark_sequence <> captured_stream_head THEN
            RAISE EXCEPTION
                'sync snapshot high-water mark must capture the locked stream head'
                USING ERRCODE = '23514';
        END IF;
    ELSIF ROW(
        NEW.snapshot_id,
        NEW.snapshot_kind,
        NEW.bootstrap_id,
        NEW.person_id,
        NEW.device_id,
        NEW.credential_family_id,
        NEW.sync_stream_id,
        NEW.protocol_stream,
        NEW.start_sequence,
        NEW.high_watermark_sequence,
        NEW.source_cursor_id,
        NEW.source_cursor_kind,
        NEW.source_cursor_protocol_stream,
        NEW.bootstrap_incremental_cursor_id,
        NEW.bootstrap_incremental_cursor_kind,
        NEW.bootstrap_incremental_cursor_protocol_stream,
        NEW.purge_generation,
        NEW.created_at,
        NEW.expires_at
    ) IS DISTINCT FROM ROW(
        OLD.snapshot_id,
        OLD.snapshot_kind,
        OLD.bootstrap_id,
        OLD.person_id,
        OLD.device_id,
        OLD.credential_family_id,
        OLD.sync_stream_id,
        OLD.protocol_stream,
        OLD.start_sequence,
        OLD.high_watermark_sequence,
        OLD.source_cursor_id,
        OLD.source_cursor_kind,
        OLD.source_cursor_protocol_stream,
        OLD.bootstrap_incremental_cursor_id,
        OLD.bootstrap_incremental_cursor_kind,
        OLD.bootstrap_incremental_cursor_protocol_stream,
        OLD.purge_generation,
        OLD.created_at,
        OLD.expires_at
    ) THEN
        RAISE EXCEPTION
            'sync snapshot capture and namespace fields are immutable'
            USING ERRCODE = '23514';
    ELSIF NOT (
        (OLD.status = 'active' AND NEW.status IN (
            'active', 'complete', 'expired', 'revoked'
        ))
        OR (OLD.status = 'complete' AND NEW.status IN (
            'complete', 'expired', 'revoked'
        ))
        OR (OLD.status = 'expired' AND NEW.status IN ('expired', 'revoked'))
        OR (OLD.status = 'revoked' AND NEW.status = 'revoked')
    ) THEN
        RAISE EXCEPTION
            'sync snapshot lifecycle cannot move backward'
            USING ERRCODE = '23514';
    ELSIF (
        OLD.completed_at IS NOT NULL
        AND NEW.completed_at IS DISTINCT FROM OLD.completed_at
    ) OR (
        OLD.completed_at IS NULL
        AND NEW.completed_at IS NOT NULL
        AND NEW.status <> 'complete'
    ) OR (
        OLD.revoked_at IS NOT NULL
        AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
    ) OR (
        OLD.revoked_at IS NULL
        AND NEW.revoked_at IS NOT NULL
        AND NEW.status <> 'revoked'
    ) THEN
        RAISE EXCEPTION
            'sync snapshot lifecycle timestamps are write-once'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$
"""

_CURSOR_LIFECYCLE_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_guard_sync_cursor_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF ROW(
        NEW.sync_cursor_id,
        NEW.generation,
        NEW.cursor_kind,
        NEW.protocol_stream,
        NEW.handle_hmac,
        NEW.derivation_nonce,
        NEW.signing_key_generation,
        NEW.person_id,
        NEW.device_id,
        NEW.credential_family_id,
        NEW.sync_stream_id,
        NEW.snapshot_id,
        NEW.snapshot_kind,
        NEW.bootstrap_id,
        NEW.exact_position,
        NEW.snapshot_high_watermark_sequence,
        NEW.purge_generation,
        NEW.lineage_depth,
        NEW.parent_cursor_id,
        NEW.parent_snapshot_id,
        NEW.parent_snapshot_kind,
        NEW.parent_bootstrap_id,
        NEW.parent_cursor_kind,
        NEW.parent_protocol_stream,
        NEW.parent_exact_position,
        NEW.parent_lineage_depth,
        NEW.issued_at,
        NEW.expires_at
    ) IS DISTINCT FROM ROW(
        OLD.sync_cursor_id,
        OLD.generation,
        OLD.cursor_kind,
        OLD.protocol_stream,
        OLD.handle_hmac,
        OLD.derivation_nonce,
        OLD.signing_key_generation,
        OLD.person_id,
        OLD.device_id,
        OLD.credential_family_id,
        OLD.sync_stream_id,
        OLD.snapshot_id,
        OLD.snapshot_kind,
        OLD.bootstrap_id,
        OLD.exact_position,
        OLD.snapshot_high_watermark_sequence,
        OLD.purge_generation,
        OLD.lineage_depth,
        OLD.parent_cursor_id,
        OLD.parent_snapshot_id,
        OLD.parent_snapshot_kind,
        OLD.parent_bootstrap_id,
        OLD.parent_cursor_kind,
        OLD.parent_protocol_stream,
        OLD.parent_exact_position,
        OLD.parent_lineage_depth,
        OLD.issued_at,
        OLD.expires_at
    ) THEN
        RAISE EXCEPTION
            'sync cursor issuance and opaque-handle binding are immutable'
            USING ERRCODE = '23514';
    ELSIF NOT (
        (OLD.cursor_state = 'staged' AND NEW.cursor_state IN (
            'staged', 'current', 'revoked'
        ))
        OR (OLD.cursor_state = 'current' AND NEW.cursor_state IN (
            'current', 'consumed', 'revoked'
        ))
        OR (OLD.cursor_state = 'consumed' AND NEW.cursor_state IN (
            'consumed', 'revoked'
        ))
        OR (OLD.cursor_state = 'revoked' AND NEW.cursor_state = 'revoked')
    ) THEN
        RAISE EXCEPTION
            'sync cursor lifecycle cannot move backward'
            USING ERRCODE = '23514';
    ELSIF (
        OLD.last_used_at IS NOT NULL
        AND (
            NEW.last_used_at IS NULL
            OR NEW.last_used_at < OLD.last_used_at
        )
    ) OR (
        OLD.consumed_at IS NOT NULL
        AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at
    ) OR (
        OLD.consumed_at IS NULL
        AND NEW.consumed_at IS NOT NULL
        AND NEW.cursor_state <> 'consumed'
    ) OR (
        OLD.revoked_at IS NOT NULL
        AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
    ) OR (
        OLD.revoked_at IS NULL
        AND NEW.revoked_at IS NOT NULL
        AND NEW.cursor_state <> 'revoked'
    ) THEN
        RAISE EXCEPTION
            'sync cursor lifecycle timestamps are monotonic and write-once'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$
"""

_READ_PAGE_IMMUTABILITY_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_guard_sync_read_page_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION
        'successful sync-read page evidence is append-only'
        USING ERRCODE = '23514';
END;
$function$
"""

_SNAPSHOT_CURSOR_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_check_sync_snapshot_cursor_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.sync_snapshot AS snapshot
        WHERE snapshot.status = 'active'
          AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS active_page
                WHERE active_page.snapshot_id = snapshot.snapshot_id
                  AND active_page.endpoint_id = CASE snapshot.snapshot_kind
                        WHEN 'bootstrap' THEN 'sync_bootstrap'
                        ELSE 'sync_pull'
                      END
                  AND active_page.has_more = true
          )
    ) THEN
        RAISE EXCEPTION
            'active sync snapshot lacks a successful continuation page'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_snapshot AS snapshot
        JOIN public.sync_stream AS stream
          ON stream.sync_stream_id = snapshot.sync_stream_id
         AND stream.person_id = snapshot.person_id
        WHERE (
                snapshot.status IN ('active', 'complete')
                AND snapshot.purge_generation <> stream.purge_generation
              )
           OR snapshot.high_watermark_sequence > stream.last_server_sequence
           OR (
                snapshot.snapshot_kind = 'incremental'
                AND snapshot.status IN ('active', 'complete')
                AND snapshot.start_sequence <
                    stream.minimum_available_sequence - 1
           )
           OR (
                snapshot.snapshot_kind = 'bootstrap'
                AND NOT EXISTS (
                    SELECT 1
                    FROM public.sync_cursor AS cursor_row
                    WHERE cursor_row.sync_cursor_id =
                            snapshot.bootstrap_incremental_cursor_id
                      AND cursor_row.person_id = snapshot.person_id
                      AND cursor_row.device_id = snapshot.device_id
                      AND cursor_row.credential_family_id =
                            snapshot.credential_family_id
                      AND cursor_row.sync_stream_id = snapshot.sync_stream_id
                      AND cursor_row.purge_generation = snapshot.purge_generation
                      AND cursor_row.snapshot_id = snapshot.snapshot_id
                      AND cursor_row.snapshot_kind = 'bootstrap'
                      AND cursor_row.bootstrap_id IS NULL
                      AND cursor_row.cursor_kind =
                            snapshot.bootstrap_incremental_cursor_kind
                      AND cursor_row.protocol_stream =
                            snapshot.bootstrap_incremental_cursor_protocol_stream
                      AND cursor_row.exact_position =
                            snapshot.high_watermark_sequence
                      AND cursor_row.snapshot_high_watermark_sequence =
                            snapshot.high_watermark_sequence
                      AND cursor_row.parent_cursor_id IS NULL
                      AND cursor_row.lineage_depth = 0
                      AND (
                            snapshot.status <> 'active'
                            OR cursor_row.cursor_state = 'staged'
                      )
                      AND (
                            snapshot.status <> 'complete'
                            OR cursor_row.cursor_state <> 'staged'
                      )
                )
           )
           OR (
                snapshot.snapshot_kind = 'incremental'
                AND NOT EXISTS (
                    SELECT 1
                    FROM public.sync_cursor AS cursor_row
                    JOIN public.sync_snapshot AS source_snapshot
                      ON source_snapshot.snapshot_id = cursor_row.snapshot_id
                    WHERE cursor_row.sync_cursor_id = snapshot.source_cursor_id
                      AND cursor_row.person_id = snapshot.person_id
                      AND cursor_row.device_id = snapshot.device_id
                      AND cursor_row.credential_family_id =
                            snapshot.credential_family_id
                      AND cursor_row.sync_stream_id = snapshot.sync_stream_id
                      AND cursor_row.purge_generation = snapshot.purge_generation
                      AND cursor_row.cursor_kind = snapshot.source_cursor_kind
                      AND cursor_row.protocol_stream =
                            snapshot.source_cursor_protocol_stream
                      AND cursor_row.exact_position = snapshot.start_sequence
                      AND (
                            (
                                snapshot.status IN ('active', 'complete')
                                AND source_snapshot.status = 'complete'
                                AND (
                                    cursor_row.cursor_state = 'current'
                                    OR (
                                        cursor_row.cursor_state = 'consumed'
                                        AND EXISTS (
                                            SELECT 1
                                            FROM public.sync_read_page AS first_page
                                            WHERE first_page.endpoint_id = 'sync_pull'
                                              AND first_page.snapshot_id =
                                                    snapshot.snapshot_id
                                              AND first_page.page_ordinal = 0
                                              AND first_page.from_cursor_id =
                                                    cursor_row.sync_cursor_id
                                              AND (
                                                (
                                                    first_page.next_cursor_id <>
                                                        cursor_row.sync_cursor_id
                                                    AND cursor_row.consumed_at >=
                                                        first_page.committed_at
                                                )
                                                OR (
                                                    first_page.next_cursor_id =
                                                        cursor_row.sync_cursor_id
                                                    AND EXISTS (
                                                        SELECT 1
                                                        FROM public.sync_read_page AS later_page
                                                        WHERE later_page.endpoint_id =
                                                                'sync_pull'
                                                          AND later_page.snapshot_id <>
                                                                snapshot.snapshot_id
                                                          AND later_page.from_cursor_id =
                                                                cursor_row.sync_cursor_id
                                                          AND later_page.next_cursor_id <>
                                                                cursor_row.sync_cursor_id
                                                          AND later_page.committed_at >=
                                                                first_page.committed_at
                                                          AND cursor_row.consumed_at >=
                                                                later_page.committed_at
                                                    )
                                                )
                                              )
                                        )
                                    )
                                )
                            )
                            OR (
                                snapshot.status IN ('expired', 'revoked')
                                AND cursor_row.cursor_state = 'revoked'
                                AND source_snapshot.status IN (
                                    'expired', 'revoked'
                                )
                            )
                      )
                )
           )
    ) THEN
        RAISE EXCEPTION
            'sync snapshot cursor binding is incomplete or incoherent'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$function$
"""

_READ_AUTHORITY_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_check_sync_read_authority()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.sync_cursor AS cursor_row
        WHERE cursor_row.cursor_kind = 'incremental'
          AND cursor_row.cursor_state = 'current'
          AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_state AS read_state
                WHERE read_state.current_incremental_cursor_id =
                        cursor_row.sync_cursor_id
          )
    ) THEN
        RAISE EXCEPTION
            'current incremental cursor lacks its authoritative read-state pointer'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        WITH RECURSIVE ancestry AS (
            SELECT
                read_state.sync_read_state_id,
                cursor_row.sync_cursor_id,
                cursor_row.parent_cursor_id,
                cursor_row.lineage_depth
            FROM public.sync_read_state AS read_state
            JOIN public.sync_cursor AS cursor_row
              ON cursor_row.sync_cursor_id =
                    read_state.current_incremental_cursor_id

            UNION

            SELECT
                ancestry.sync_read_state_id,
                parent.sync_cursor_id,
                parent.parent_cursor_id,
                parent.lineage_depth
            FROM ancestry
            JOIN public.sync_cursor AS parent
              ON parent.sync_cursor_id = ancestry.parent_cursor_id
            WHERE ancestry.lineage_depth > 0
        )
        SELECT 1
        FROM public.sync_read_state AS read_state
        JOIN public.sync_snapshot AS bootstrap_snapshot
          ON bootstrap_snapshot.snapshot_id =
                read_state.bootstrap_snapshot_id
        WHERE NOT EXISTS (
            SELECT 1
            FROM ancestry
            WHERE ancestry.sync_read_state_id =
                    read_state.sync_read_state_id
              AND ancestry.parent_cursor_id IS NULL
              AND ancestry.lineage_depth = 0
              AND ancestry.sync_cursor_id =
                    bootstrap_snapshot.bootstrap_incremental_cursor_id
        )
    ) THEN
        RAISE EXCEPTION
            'authoritative incremental cursor is outside its bootstrap lineage'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        WITH RECURSIVE ancestry AS (
            SELECT
                read_state.sync_read_state_id,
                cursor_row.sync_cursor_id,
                cursor_row.parent_cursor_id,
                cursor_row.lineage_depth
            FROM public.sync_read_state AS read_state
            JOIN public.sync_cursor AS cursor_row
              ON cursor_row.sync_cursor_id =
                    read_state.current_incremental_cursor_id

            UNION

            SELECT
                ancestry.sync_read_state_id,
                parent.sync_cursor_id,
                parent.parent_cursor_id,
                parent.lineage_depth
            FROM ancestry
            JOIN public.sync_cursor AS parent
              ON parent.sync_cursor_id = ancestry.parent_cursor_id
            WHERE ancestry.lineage_depth > 0
        )
        SELECT 1
        FROM ancestry
        JOIN public.sync_read_state AS read_state
          ON read_state.sync_read_state_id = ancestry.sync_read_state_id
        WHERE (
            ancestry.parent_cursor_id IS NULL
            AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS bootstrap_page
                WHERE bootstrap_page.endpoint_id = 'sync_bootstrap'
                  AND bootstrap_page.snapshot_id =
                        read_state.bootstrap_snapshot_id
                  AND bootstrap_page.bootstrap_id = read_state.bootstrap_id
                  AND bootstrap_page.has_more = false
                  AND bootstrap_page.incremental_cursor_id =
                        ancestry.sync_cursor_id
            )
        )
        OR (
            ancestry.parent_cursor_id IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS pull_page
                WHERE pull_page.endpoint_id = 'sync_pull'
                  AND pull_page.from_cursor_id = ancestry.parent_cursor_id
                  AND pull_page.next_cursor_id = ancestry.sync_cursor_id
            )
        )
    ) THEN
        RAISE EXCEPTION
            'authoritative cursor lineage lacks successful read-page evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$function$
"""

_READ_PAGE_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_check_sync_read_page_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.sync_read_page AS page
        JOIN public.sync_snapshot AS snapshot
          ON snapshot.snapshot_id = page.snapshot_id
        WHERE (
            page.endpoint_id = 'sync_bootstrap'
            AND (
                page.bootstrap_id IS DISTINCT FROM snapshot.bootstrap_id
                OR page.incremental_cursor_id IS DISTINCT FROM
                    snapshot.bootstrap_incremental_cursor_id
                OR page.incremental_exact_position IS DISTINCT FROM
                    snapshot.high_watermark_sequence
                OR (
                    page.from_cursor_id IS NOT NULL
                    AND NOT EXISTS (
                        SELECT 1
                        FROM public.sync_cursor AS from_cursor
                        WHERE from_cursor.sync_cursor_id = page.from_cursor_id
                          AND from_cursor.snapshot_id = snapshot.snapshot_id
                          AND from_cursor.snapshot_kind = 'bootstrap'
                          AND from_cursor.bootstrap_id = snapshot.bootstrap_id
                          AND from_cursor.lineage_depth = page.page_ordinal - 1
                          AND from_cursor.issued_at <= page.committed_at
                          AND from_cursor.expires_at > page.committed_at
                          AND (
                                (
                                    snapshot.status IN ('active', 'complete')
                                    AND from_cursor.cursor_state = 'consumed'
                                    AND from_cursor.consumed_at >=
                                        page.committed_at
                                )
                                OR (
                                    snapshot.status IN ('expired', 'revoked')
                                    AND from_cursor.cursor_state = 'revoked'
                                    AND from_cursor.consumed_at >=
                                        page.committed_at
                                    AND from_cursor.revoked_at >=
                                        page.committed_at
                                    AND (
                                        (
                                            snapshot.status = 'revoked'
                                            AND snapshot.revoked_at >=
                                                page.committed_at
                                        )
                                        OR (
                                            snapshot.status = 'expired'
                                            AND snapshot.expires_at >
                                                page.committed_at
                                        )
                                    )
                                )
                          )
                    )
                )
                OR (
                    page.page_ordinal > 0
                    AND (
                        page.change_count = 0
                        OR NOT EXISTS (
                            SELECT 1
                            FROM public.sync_read_page AS prior_page
                            WHERE prior_page.endpoint_id = 'sync_bootstrap'
                              AND prior_page.snapshot_id = page.snapshot_id
                              AND prior_page.bootstrap_id = page.bootstrap_id
                              AND prior_page.page_ordinal = page.page_ordinal - 1
                              AND prior_page.has_more = true
                              AND prior_page.next_cursor_id = page.from_cursor_id
                        )
                    )
                )
                OR (
                    page.next_cursor_id IS NOT NULL
                    AND NOT EXISTS (
                        SELECT 1
                        FROM public.sync_cursor AS next_cursor
                        WHERE next_cursor.sync_cursor_id = page.next_cursor_id
                          AND next_cursor.snapshot_id = snapshot.snapshot_id
                          AND next_cursor.snapshot_kind = 'bootstrap'
                          AND next_cursor.bootstrap_id = snapshot.bootstrap_id
                          AND next_cursor.lineage_depth = page.page_ordinal
                          AND next_cursor.parent_cursor_id IS NOT DISTINCT FROM
                                page.from_cursor_id
                    )
                )
                OR (
                    page.has_more
                    AND page.next_exact_position >=
                        snapshot.high_watermark_sequence
                )
                OR (
                    NOT page.has_more
                    AND NOT (
                        snapshot.completed_at IS NOT NULL
                        AND snapshot.completed_at >= snapshot.created_at
                        AND snapshot.completed_at <= page.committed_at
                        AND (
                            (
                                snapshot.status = 'complete'
                                AND EXISTS (
                                    WITH RECURSIVE authority_ancestry AS (
                                        SELECT
                                            cursor_row.sync_cursor_id,
                                            cursor_row.parent_cursor_id,
                                            cursor_row.lineage_depth
                                        FROM public.sync_read_state AS read_state
                                        JOIN public.sync_cursor AS cursor_row
                                          ON cursor_row.sync_cursor_id =
                                                read_state.current_incremental_cursor_id
                                        WHERE read_state.bootstrap_snapshot_id =
                                                snapshot.snapshot_id
                                          AND read_state.bootstrap_id =
                                                snapshot.bootstrap_id

                                        UNION

                                        SELECT
                                            parent.sync_cursor_id,
                                            parent.parent_cursor_id,
                                            parent.lineage_depth
                                        FROM authority_ancestry AS ancestry
                                        JOIN public.sync_cursor AS parent
                                          ON parent.sync_cursor_id =
                                                ancestry.parent_cursor_id
                                        WHERE ancestry.lineage_depth > 0
                                    )
                                    SELECT 1
                                    FROM authority_ancestry AS ancestry
                                    JOIN public.sync_cursor AS stable_cursor
                                      ON stable_cursor.sync_cursor_id =
                                            ancestry.sync_cursor_id
                                    WHERE ancestry.sync_cursor_id =
                                            snapshot.bootstrap_incremental_cursor_id
                                      AND ancestry.parent_cursor_id IS NULL
                                      AND ancestry.lineage_depth = 0
                                      AND stable_cursor.issued_at <=
                                            page.committed_at
                                      AND stable_cursor.expires_at >
                                            page.committed_at
                                      AND (
                                            stable_cursor.cursor_state = 'current'
                                            OR (
                                                stable_cursor.cursor_state =
                                                    'consumed'
                                                AND stable_cursor.consumed_at >=
                                                    page.committed_at
                                            )
                                      )
                                )
                            )
                            OR (
                                snapshot.status IN ('expired', 'revoked')
                                AND EXISTS (
                                    SELECT 1
                                    FROM public.sync_cursor AS stable_cursor
                                    WHERE stable_cursor.sync_cursor_id =
                                            snapshot.bootstrap_incremental_cursor_id
                                      AND stable_cursor.cursor_state = 'revoked'
                                      AND stable_cursor.issued_at <=
                                            page.committed_at
                                      AND stable_cursor.expires_at >
                                            page.committed_at
                                      AND stable_cursor.revoked_at >=
                                            page.committed_at
                                )
                                AND (
                                    (
                                        snapshot.status = 'revoked'
                                        AND snapshot.revoked_at >= page.committed_at
                                    )
                                    OR (
                                        snapshot.status = 'expired'
                                        AND snapshot.expires_at > page.committed_at
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        OR (
            page.endpoint_id = 'sync_pull'
            AND (
                (
                    page.page_ordinal > 0
                    AND (
                        page.change_count = 0
                        OR NOT EXISTS (
                            SELECT 1
                            FROM public.sync_read_page AS prior_page
                            WHERE prior_page.endpoint_id = 'sync_pull'
                              AND prior_page.snapshot_id = page.snapshot_id
                              AND prior_page.page_ordinal = page.page_ordinal - 1
                              AND prior_page.has_more = true
                              AND prior_page.next_cursor_id = page.from_cursor_id
                        )
                    )
                )
                OR
                NOT EXISTS (
                    WITH RECURSIVE ancestry AS (
                        SELECT
                            cursor_row.sync_cursor_id,
                            cursor_row.parent_cursor_id,
                            cursor_row.lineage_depth
                        FROM public.sync_cursor AS cursor_row
                        WHERE cursor_row.sync_cursor_id = page.from_cursor_id

                        UNION

                        SELECT
                            parent.sync_cursor_id,
                            parent.parent_cursor_id,
                            parent.lineage_depth
                        FROM ancestry
                        JOIN public.sync_cursor AS parent
                          ON parent.sync_cursor_id = ancestry.parent_cursor_id
                        WHERE ancestry.lineage_depth > 0
                    )
                    SELECT 1
                    FROM public.sync_cursor AS from_cursor
                    JOIN public.sync_cursor AS source_cursor
                      ON source_cursor.sync_cursor_id = snapshot.source_cursor_id
                    JOIN public.sync_snapshot AS from_snapshot
                      ON from_snapshot.snapshot_id = from_cursor.snapshot_id
                    WHERE from_cursor.sync_cursor_id = page.from_cursor_id
                      AND (
                            (
                                snapshot.status IN ('active', 'complete')
                                AND from_cursor.issued_at <= page.committed_at
                                AND from_cursor.expires_at > page.committed_at
                                AND (
                                    from_cursor.cursor_state = 'current'
                                    OR (
                                        from_cursor.cursor_state = 'consumed'
                                        AND (
                                            (
                                                page.next_cursor_id <>
                                                    from_cursor.sync_cursor_id
                                                AND from_cursor.consumed_at >=
                                                    page.committed_at
                                            )
                                            OR (
                                                page.next_cursor_id =
                                                    from_cursor.sync_cursor_id
                                                AND EXISTS (
                                                    SELECT 1
                                                    FROM public.sync_read_page AS
                                                        later_page
                                                    WHERE later_page.endpoint_id =
                                                            'sync_pull'
                                                      AND later_page.from_cursor_id =
                                                            from_cursor.sync_cursor_id
                                                      AND later_page.next_cursor_id <>
                                                            from_cursor.sync_cursor_id
                                                      AND later_page.committed_at >
                                                            page.committed_at
                                                      AND from_cursor.consumed_at >=
                                                            later_page.committed_at
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                            OR (
                                snapshot.status IN ('expired', 'revoked')
                                AND from_cursor.cursor_state = 'revoked'
                                AND from_cursor.issued_at <= page.committed_at
                                AND from_cursor.expires_at > page.committed_at
                                AND from_cursor.revoked_at >= page.committed_at
                                AND (
                                    page.next_cursor_id =
                                        from_cursor.sync_cursor_id
                                    OR from_cursor.consumed_at >=
                                        page.committed_at
                                )
                                AND from_snapshot.status IN (
                                    'expired', 'revoked'
                                )
                                AND (
                                    (
                                        snapshot.status = 'revoked'
                                        AND snapshot.revoked_at >=
                                            page.committed_at
                                    )
                                    OR (
                                        snapshot.status = 'expired'
                                        AND snapshot.expires_at >
                                            page.committed_at
                                    )
                                )
                                AND (
                                    (
                                        from_snapshot.status = 'revoked'
                                        AND from_snapshot.revoked_at >=
                                            page.committed_at
                                    )
                                    OR (
                                        from_snapshot.status = 'expired'
                                        AND from_snapshot.expires_at >
                                            page.committed_at
                                    )
                                )
                            )
                      )
                      AND from_cursor.lineage_depth =
                            source_cursor.lineage_depth + page.page_ordinal
                      AND (
                            page.page_ordinal = 0
                            OR from_cursor.snapshot_id = snapshot.snapshot_id
                      )
                      AND EXISTS (
                            SELECT 1
                            FROM ancestry
                            WHERE ancestry.sync_cursor_id =
                                    source_cursor.sync_cursor_id
                      )
                )
                OR (
                    page.next_cursor_id = page.from_cursor_id
                    AND snapshot.high_watermark_sequence <>
                        page.from_exact_position
                )
                OR (
                    page.next_cursor_id <> page.from_cursor_id
                    AND NOT EXISTS (
                        SELECT 1
                        FROM public.sync_cursor AS next_cursor
                        WHERE next_cursor.sync_cursor_id = page.next_cursor_id
                          AND next_cursor.snapshot_id = snapshot.snapshot_id
                          AND next_cursor.snapshot_kind = 'incremental'
                          AND next_cursor.parent_cursor_id = page.from_cursor_id
                    )
                )
                OR (
                    page.has_more
                    AND page.next_exact_position >=
                        snapshot.high_watermark_sequence
                )
                OR (
                    NOT page.has_more
                    AND page.next_exact_position <>
                        snapshot.high_watermark_sequence
                )
                OR (
                    NOT page.has_more
                    AND NOT (
                        snapshot.completed_at IS NOT NULL
                        AND snapshot.completed_at >= snapshot.created_at
                        AND snapshot.completed_at <= page.committed_at
                        AND snapshot.status IN (
                            'complete', 'expired', 'revoked'
                        )
                    )
                )
            )
        )
    ) THEN
        RAISE EXCEPTION
            'sync read page is outside its frozen snapshot or cursor lineage'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_snapshot AS snapshot
        WHERE snapshot.status = 'active'
          AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS active_page
                WHERE active_page.snapshot_id = snapshot.snapshot_id
                  AND active_page.endpoint_id = CASE snapshot.snapshot_kind
                        WHEN 'bootstrap' THEN 'sync_bootstrap'
                        ELSE 'sync_pull'
                      END
                  AND active_page.has_more = true
          )
    ) THEN
        RAISE EXCEPTION
            'active sync snapshot lacks a successful continuation page'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_snapshot AS snapshot
        WHERE snapshot.completed_at IS NOT NULL
          AND snapshot.status IN ('complete', 'expired', 'revoked')
          AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS terminal_page
                WHERE terminal_page.snapshot_id = snapshot.snapshot_id
                  AND terminal_page.endpoint_id = CASE snapshot.snapshot_kind
                        WHEN 'bootstrap' THEN 'sync_bootstrap'
                        ELSE 'sync_pull'
                      END
                  AND terminal_page.has_more = false
          )
    ) THEN
        RAISE EXCEPTION
            'completed sync snapshot lacks terminal successful page evidence'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_read_page AS page
        JOIN public.sync_snapshot AS snapshot
          ON snapshot.snapshot_id = page.snapshot_id
        CROSS JOIN LATERAL (
            SELECT
                count(*) AS change_count,
                min(operation.server_sequence) AS first_server_sequence,
                max(operation.server_sequence) AS last_server_sequence
            FROM public.sync_operation AS operation
            WHERE operation.person_id = page.person_id
              AND operation.sync_stream_id = page.sync_stream_id
              AND operation.purge_generation = page.purge_generation
              AND operation.server_sequence >
                    COALESCE(page.from_exact_position, 0)
              AND operation.server_sequence <= CASE
                    WHEN page.endpoint_id = 'sync_bootstrap'
                         AND NOT page.has_more
                        THEN page.incremental_exact_position
                    ELSE page.next_exact_position
                  END
        ) AS source_window
        WHERE snapshot.status IN ('active', 'complete')
          AND (
                source_window.change_count <> page.change_count
           OR source_window.first_server_sequence IS DISTINCT FROM
                page.first_server_sequence
           OR source_window.last_server_sequence IS DISTINCT FROM
                page.last_server_sequence
          )
    ) THEN
        RAISE EXCEPTION
            'sync read page omits or invents authoritative stream changes'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_read_page AS page
        JOIN public.sync_snapshot AS snapshot
          ON snapshot.snapshot_id = page.snapshot_id
        JOIN public.sync_cursor AS output_cursor
          ON output_cursor.sync_cursor_id = page.next_cursor_id
        WHERE page.endpoint_id = 'sync_bootstrap'
          AND page.has_more
          AND (
                output_cursor.issued_at > page.committed_at
                OR output_cursor.expires_at <= page.committed_at
                OR output_cursor.cursor_state = 'staged'
                OR (
                    output_cursor.cursor_state = 'current'
                    AND (
                        snapshot.status <> 'active'
                        OR EXISTS (
                            SELECT 1
                            FROM public.sync_read_page AS successor_page
                            WHERE successor_page.endpoint_id = 'sync_bootstrap'
                              AND successor_page.snapshot_id = page.snapshot_id
                              AND successor_page.from_cursor_id =
                                    output_cursor.sync_cursor_id
                        )
                    )
                )
                OR (
                    output_cursor.cursor_state = 'consumed'
                    AND (
                        output_cursor.consumed_at < page.committed_at
                        OR NOT EXISTS (
                            SELECT 1
                            FROM public.sync_read_page AS successor_page
                            WHERE successor_page.endpoint_id = 'sync_bootstrap'
                              AND successor_page.snapshot_id = page.snapshot_id
                              AND successor_page.from_cursor_id =
                                    output_cursor.sync_cursor_id
                        )
                    )
                )
                OR (
                    output_cursor.cursor_state = 'revoked'
                    AND (
                        output_cursor.revoked_at < page.committed_at
                        OR snapshot.status NOT IN ('expired', 'revoked')
                        OR (
                            snapshot.status = 'revoked'
                            AND snapshot.revoked_at < page.committed_at
                        )
                        OR (
                            snapshot.status = 'expired'
                            AND snapshot.expires_at <= page.committed_at
                        )
                    )
                )
          )
    ) THEN
        RAISE EXCEPTION
            'bootstrap page emitted a cursor outside its durable lifecycle'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_read_page AS page
        JOIN public.sync_cursor AS output_cursor
          ON output_cursor.sync_cursor_id = page.next_cursor_id
        JOIN public.sync_snapshot AS output_snapshot
          ON output_snapshot.snapshot_id = output_cursor.snapshot_id
        JOIN public.sync_snapshot AS page_snapshot
          ON page_snapshot.snapshot_id = page.snapshot_id
        WHERE page.endpoint_id = 'sync_pull'
          AND (
                output_cursor.issued_at > page.committed_at
                OR output_cursor.expires_at <= page.committed_at
                OR output_cursor.cursor_state = 'staged'
                OR (
                    output_cursor.cursor_state = 'current'
                    AND (
                        NOT EXISTS (
                            SELECT 1
                            FROM public.sync_read_state AS read_state
                            WHERE read_state.current_incremental_cursor_id =
                                    output_cursor.sync_cursor_id
                        )
                        OR NOT (
                            (page.has_more AND page_snapshot.status = 'active')
                            OR (
                                NOT page.has_more
                                AND page_snapshot.status = 'complete'
                            )
                        )
                    )
                )
                OR (
                    output_cursor.cursor_state = 'consumed'
                    AND (
                        output_cursor.consumed_at < page.committed_at
                        OR NOT EXISTS (
                            WITH RECURSIVE authority_ancestry AS (
                                SELECT
                                    cursor_row.sync_cursor_id,
                                    cursor_row.parent_cursor_id,
                                    cursor_row.lineage_depth
                                FROM public.sync_read_state AS read_state
                                JOIN public.sync_cursor AS cursor_row
                                  ON cursor_row.sync_cursor_id =
                                        read_state.current_incremental_cursor_id

                                UNION

                                SELECT
                                    parent.sync_cursor_id,
                                    parent.parent_cursor_id,
                                    parent.lineage_depth
                                FROM authority_ancestry AS ancestry
                                JOIN public.sync_cursor AS parent
                                  ON parent.sync_cursor_id = ancestry.parent_cursor_id
                                WHERE ancestry.lineage_depth > 0
                            )
                            SELECT
                                1
                            FROM authority_ancestry AS ancestry
                            WHERE ancestry.sync_cursor_id =
                                    output_cursor.sync_cursor_id
                        )
                    )
                )
                OR (
                    output_cursor.cursor_state = 'revoked'
                    AND (
                        output_cursor.revoked_at < page.committed_at
                        OR output_snapshot.status NOT IN ('expired', 'revoked')
                        OR page_snapshot.status NOT IN ('expired', 'revoked')
                        OR (
                            output_snapshot.status = 'revoked'
                            AND output_snapshot.revoked_at < page.committed_at
                        )
                        OR (
                            output_snapshot.status = 'expired'
                            AND output_snapshot.expires_at <= page.committed_at
                        )
                        OR (
                            page_snapshot.status = 'revoked'
                            AND page_snapshot.revoked_at < page.committed_at
                        )
                        OR (
                            page_snapshot.status = 'expired'
                            AND page_snapshot.expires_at <= page.committed_at
                        )
                    )
                )
          )
    ) THEN
        RAISE EXCEPTION
            'pull page emitted a cursor outside its durable authority lifecycle'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.sync_cursor AS cursor_row
        WHERE (
            cursor_row.cursor_kind = 'bootstrap_page'
            AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS issuing_page
                WHERE issuing_page.endpoint_id = 'sync_bootstrap'
                  AND issuing_page.snapshot_id = cursor_row.snapshot_id
                  AND issuing_page.page_ordinal = cursor_row.lineage_depth
                  AND issuing_page.has_more = true
                  AND issuing_page.next_cursor_id = cursor_row.sync_cursor_id
            )
        )
        OR (
            cursor_row.cursor_kind = 'incremental'
            AND cursor_row.parent_cursor_id IS NOT NULL
            AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS issuing_page
                WHERE issuing_page.endpoint_id = 'sync_pull'
                  AND issuing_page.from_cursor_id = cursor_row.parent_cursor_id
                  AND issuing_page.next_cursor_id = cursor_row.sync_cursor_id
            )
        )
    ) THEN
        RAISE EXCEPTION
            'retained sync cursor lacks successful issuing-page evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$function$
"""

_READ_REPLAY_FUNCTION_SQL = """
CREATE FUNCTION public.life_agent_check_sync_read_replay_totality()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.http_replay AS replay
        WHERE replay.endpoint_id IN ('sync_bootstrap', 'sync_pull')
          AND replay.outcome_class = 'success'
          AND replay.stored_outcome = 'authenticated_success'
          AND replay.http_status = 200
          AND NOT EXISTS (
                SELECT 1
                FROM public.sync_read_page AS page
                WHERE page.http_replay_id = replay.http_replay_id
          )
    ) THEN
        RAISE EXCEPTION
            'successful sync-read replay lacks exact page evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$function$
"""

_TRIGGER_SQL = (
    """
    CREATE TRIGGER tr_sync_snapshot_capture_guard
    BEFORE INSERT ON public.sync_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_guard_sync_snapshot_capture()
    """,
    """
    CREATE TRIGGER tr_sync_snapshot_lifecycle_guard
    AFTER UPDATE ON public.sync_snapshot
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_guard_sync_snapshot_capture()
    """,
    """
    CREATE TRIGGER tr_sync_cursor_lifecycle_guard
    AFTER UPDATE ON public.sync_cursor
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_guard_sync_cursor_lifecycle()
    """,
    """
    CREATE TRIGGER tr_sync_read_page_immutability_guard
    AFTER UPDATE ON public.sync_read_page
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_guard_sync_read_page_immutability()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_snapshot_cursor_binding_snapshot
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_snapshot_cursor_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_snapshot_cursor_binding_cursor
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_cursor
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_snapshot_cursor_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_snapshot_cursor_binding_stream
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_stream
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_snapshot_cursor_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_authority_state
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_read_state
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_authority()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_authority_cursor
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_cursor
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_authority()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_authority_snapshot
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_authority()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_authority_page
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_read_page
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_authority()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_page_binding_page
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_read_page
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_page_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_page_binding_snapshot
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_snapshot
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_page_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_page_binding_cursor
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_cursor
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_page_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_page_binding_state
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_read_state
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_page_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_page_binding_operation
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_operation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_page_binding()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_replay_totality_page
    AFTER INSERT OR UPDATE OR DELETE ON public.sync_read_page
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_replay_totality()
    """,
    """
    CREATE CONSTRAINT TRIGGER ct_sync_read_replay_totality_replay
    AFTER INSERT OR UPDATE OR DELETE ON public.http_replay
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION public.life_agent_check_sync_read_replay_totality()
    """,
)

_LEGACY_SYNC_SNAPSHOT_SQL = """
CREATE TABLE sync_snapshot (
    snapshot_id UUID NOT NULL,
    bootstrap_id UUID NOT NULL,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL,
    credential_family_id UUID NOT NULL,
    sync_stream_id UUID NOT NULL,
    protocol_stream VARCHAR(32) NOT NULL,
    high_watermark_sequence BIGINT NOT NULL,
    purge_generation BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_sync_snapshot PRIMARY KEY (snapshot_id),
    CONSTRAINT fk_sync_snapshot_stream_person
        FOREIGN KEY (sync_stream_id, person_id)
        REFERENCES sync_stream (sync_stream_id, person_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sync_snapshot_credential_namespace
        FOREIGN KEY (credential_family_id, person_id, device_id)
        REFERENCES credential_family (credential_family_id, person_id, device_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_sync_snapshot_bootstrap_attempt
        UNIQUE (person_id, device_id, credential_family_id, bootstrap_id),
    CONSTRAINT uq_sync_snapshot_cursor_binding UNIQUE (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        protocol_stream,
        purge_generation,
        high_watermark_sequence
    ),
    CONSTRAINT uq_sync_snapshot_bootstrap_binding
        UNIQUE (snapshot_id, bootstrap_id),
    CONSTRAINT ck_sync_snapshot_protocol_stream_supported
        CHECK (protocol_stream = 'life_events'),
    CONSTRAINT ck_sync_snapshot_high_watermark_range
        CHECK (high_watermark_sequence BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_snapshot_purge_generation_range
        CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_snapshot_status_allowed
        CHECK (status IN ('active', 'complete', 'expired', 'revoked')),
    CONSTRAINT ck_sync_snapshot_expiry_after_creation
        CHECK (expires_at > created_at),
    CONSTRAINT ck_sync_snapshot_status_metadata_coherent CHECK (
        (status = 'active' AND completed_at IS NULL AND revoked_at IS NULL)
        OR (status = 'complete' AND completed_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'expired' AND revoked_at IS NULL)
        OR (status = 'revoked' AND revoked_at IS NOT NULL)
    )
)
"""

_LEGACY_SYNC_CURSOR_SQL = """
CREATE TABLE sync_cursor (
    sync_cursor_id UUID NOT NULL,
    cursor_kind VARCHAR(24) NOT NULL,
    handle_hmac BYTEA NOT NULL,
    signing_key_generation INTEGER NOT NULL,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL,
    credential_family_id UUID NOT NULL,
    sync_stream_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    bootstrap_id UUID,
    protocol_stream VARCHAR(32) NOT NULL,
    exact_position BIGINT NOT NULL,
    snapshot_high_watermark_sequence BIGINT NOT NULL,
    purge_generation BIGINT NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    parent_cursor_id UUID,
    CONSTRAINT pk_sync_cursor PRIMARY KEY (sync_cursor_id),
    CONSTRAINT fk_sync_cursor_snapshot_binding FOREIGN KEY (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        protocol_stream,
        purge_generation,
        snapshot_high_watermark_sequence
    ) REFERENCES sync_snapshot (
        snapshot_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        protocol_stream,
        purge_generation,
        high_watermark_sequence
    ) ON DELETE CASCADE,
    CONSTRAINT fk_sync_cursor_bootstrap_binding
        FOREIGN KEY (snapshot_id, bootstrap_id)
        REFERENCES sync_snapshot (snapshot_id, bootstrap_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sync_cursor_parent_namespace FOREIGN KEY (
        parent_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        snapshot_id,
        protocol_stream,
        purge_generation
    ) REFERENCES sync_cursor (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        snapshot_id,
        protocol_stream,
        purge_generation
    ) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_sync_cursor_handle_lookup
        UNIQUE (signing_key_generation, handle_hmac),
    CONSTRAINT uq_sync_cursor_parent_namespace UNIQUE (
        sync_cursor_id,
        person_id,
        device_id,
        credential_family_id,
        sync_stream_id,
        snapshot_id,
        protocol_stream,
        purge_generation
    ),
    CONSTRAINT ck_sync_cursor_cursor_kind_allowed
        CHECK (cursor_kind IN ('bootstrap_page', 'incremental')),
    CONSTRAINT ck_sync_cursor_handle_hmac_length
        CHECK (octet_length(handle_hmac) = 32),
    CONSTRAINT ck_sync_cursor_signing_key_generation_positive
        CHECK (signing_key_generation > 0),
    CONSTRAINT ck_sync_cursor_bootstrap_binding_coherent CHECK (
        (cursor_kind = 'bootstrap_page' AND bootstrap_id IS NOT NULL)
        OR (cursor_kind = 'incremental' AND bootstrap_id IS NULL)
    ),
    CONSTRAINT ck_sync_cursor_protocol_stream_supported
        CHECK (protocol_stream = 'life_events'),
    CONSTRAINT ck_sync_cursor_exact_position_range
        CHECK (exact_position BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_cursor_position_within_snapshot
        CHECK (exact_position <= snapshot_high_watermark_sequence),
    CONSTRAINT ck_sync_cursor_purge_generation_range
        CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_sync_cursor_expiry_after_issue CHECK (expires_at > issued_at)
)
"""

_REFUSE_READ_STATE_DOWNGRADE = """
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sync_read_page) THEN
        RAISE EXCEPTION
            'sync read page evidence prevents invariant downgrade'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM sync_read_state) THEN
        RAISE EXCEPTION
            'authoritative sync read state prevents invariant downgrade'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM sync_cursor) THEN
        RAISE EXCEPTION
            'generation-1 sync cursor rows prevent invariant downgrade'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM sync_snapshot
        WHERE snapshot_kind = 'incremental'
    ) THEN
        RAISE EXCEPTION
            'incremental sync snapshot rows prevent invariant downgrade'
            USING ERRCODE = '23514';
    END IF;
END;
$$
"""


def upgrade() -> None:
    op.execute(f"LOCK TABLE {_LOCK_TABLES_UPGRADE} IN SHARE ROW EXCLUSIVE MODE")
    op.execute(_REFUSE_LEGACY_READ_STATE)

    op.drop_table("sync_cursor")
    op.drop_table("sync_snapshot")

    op.create_unique_constraint(
        "uq_http_replay_read_page_binding",
        "http_replay",
        [
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
        ],
    )

    for statement in (
        _SYNC_SNAPSHOT_SQL,
        _SYNC_CURSOR_SQL,
        _SYNC_READ_STATE_SQL,
        _SYNC_READ_PAGE_SQL,
    ):
        op.execute(statement)

    for statement in (
        "CREATE INDEX ix_sync_snapshot_expires_at ON sync_snapshot (expires_at)",
        "CREATE UNIQUE INDEX uq_sync_snapshot_active_bootstrap_namespace "
        "ON sync_snapshot "
        "(person_id, device_id, credential_family_id, sync_stream_id, "
        "purge_generation) "
        "WHERE snapshot_kind = 'bootstrap' AND status = 'active'",
        "CREATE UNIQUE INDEX uq_sync_snapshot_active_incremental_namespace "
        "ON sync_snapshot "
        "(person_id, device_id, credential_family_id, sync_stream_id, "
        "purge_generation) "
        "WHERE snapshot_kind = 'incremental' AND status = 'active'",
        "CREATE INDEX ix_sync_cursor_expiry ON sync_cursor (expires_at) "
        "WHERE cursor_state <> 'revoked'",
        "CREATE UNIQUE INDEX uq_sync_cursor_current_incremental_namespace "
        "ON sync_cursor "
        "(person_id, device_id, credential_family_id, sync_stream_id) "
        "WHERE cursor_kind = 'incremental' AND cursor_state = 'current'",
        "CREATE UNIQUE INDEX uq_sync_cursor_current_bootstrap_snapshot "
        "ON sync_cursor (snapshot_id) "
        "WHERE cursor_kind = 'bootstrap_page' AND cursor_state = 'current'",
        "CREATE UNIQUE INDEX uq_sync_cursor_incremental_root_snapshot "
        "ON sync_cursor (snapshot_id) "
        "WHERE cursor_kind = 'incremental' AND parent_cursor_id IS NULL",
        _SNAPSHOT_CURSOR_FUNCTION_SQL,
        _READ_AUTHORITY_FUNCTION_SQL,
        _READ_PAGE_FUNCTION_SQL,
        _READ_REPLAY_FUNCTION_SQL,
        _SNAPSHOT_CAPTURE_FUNCTION_SQL,
        _CURSOR_LIFECYCLE_FUNCTION_SQL,
        _READ_PAGE_IMMUTABILITY_FUNCTION_SQL,
        *_TRIGGER_SQL,
    ):
        op.execute(statement)


def downgrade() -> None:
    op.execute(f"LOCK TABLE {_LOCK_TABLES_DOWNGRADE} IN SHARE ROW EXCLUSIVE MODE")
    op.execute(_REFUSE_READ_STATE_DOWNGRADE)

    for trigger_name, table_name in (
        ("tr_sync_read_page_immutability_guard", "sync_read_page"),
        ("tr_sync_cursor_lifecycle_guard", "sync_cursor"),
        ("tr_sync_snapshot_lifecycle_guard", "sync_snapshot"),
        ("tr_sync_snapshot_capture_guard", "sync_snapshot"),
        ("ct_sync_read_replay_totality_replay", "http_replay"),
        ("ct_sync_read_replay_totality_page", "sync_read_page"),
        ("ct_sync_read_page_binding_cursor", "sync_cursor"),
        ("ct_sync_read_page_binding_state", "sync_read_state"),
        ("ct_sync_read_page_binding_operation", "sync_operation"),
        ("ct_sync_read_page_binding_snapshot", "sync_snapshot"),
        ("ct_sync_read_page_binding_page", "sync_read_page"),
        ("ct_sync_read_authority_page", "sync_read_page"),
        ("ct_sync_read_authority_snapshot", "sync_snapshot"),
        ("ct_sync_read_authority_cursor", "sync_cursor"),
        ("ct_sync_read_authority_state", "sync_read_state"),
        ("ct_sync_snapshot_cursor_binding_stream", "sync_stream"),
        ("ct_sync_snapshot_cursor_binding_cursor", "sync_cursor"),
        ("ct_sync_snapshot_cursor_binding_snapshot", "sync_snapshot"),
    ):
        op.execute(f"DROP TRIGGER {trigger_name} ON public.{table_name}")

    for function_name in (
        "life_agent_check_sync_read_replay_totality",
        "life_agent_check_sync_read_page_binding",
        "life_agent_check_sync_read_authority",
        "life_agent_check_sync_snapshot_cursor_binding",
        "life_agent_guard_sync_read_page_immutability",
        "life_agent_guard_sync_cursor_lifecycle",
        "life_agent_guard_sync_snapshot_capture",
    ):
        op.execute(f"DROP FUNCTION public.{function_name}()")

    op.drop_table("sync_read_page")
    op.drop_table("sync_read_state")
    op.drop_table("sync_cursor")
    op.drop_table("sync_snapshot")

    op.execute(_LEGACY_SYNC_SNAPSHOT_SQL)
    op.execute(_LEGACY_SYNC_CURSOR_SQL)
    op.execute("CREATE INDEX ix_sync_snapshot_expires_at ON sync_snapshot (expires_at)")
    op.execute(
        "CREATE INDEX ix_sync_cursor_expiry ON sync_cursor (expires_at) WHERE revoked_at IS NULL"
    )

    op.drop_constraint(
        "uq_http_replay_read_page_binding",
        "http_replay",
        type_="unique",
    )
