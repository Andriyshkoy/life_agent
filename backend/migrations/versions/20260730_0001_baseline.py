"""Create the first durable M2 backend schema.

The migration intentionally carries frozen PostgreSQL DDL instead of importing
the live application metadata. Historical migrations must remain reproducible
when the model module evolves.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260730_0001"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


CREATE_STATEMENTS = (
    """
    CREATE TABLE person (
        person_id UUID NOT NULL,
        subject_id UUID NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT pk_person PRIMARY KEY (person_id),
        CONSTRAINT uq_person_subject_id UNIQUE (subject_id),
        CONSTRAINT ck_person_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991)
    )
    """,
    """
    CREATE TABLE device (
        device_id UUID NOT NULL,
        person_id UUID NOT NULL,
        installation_id UUID NOT NULL,
        local_owner_id UUID NOT NULL,
        status VARCHAR(16) NOT NULL,
        enrolled_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        last_seen_at TIMESTAMP WITH TIME ZONE,
        revoked_at TIMESTAMP WITH TIME ZONE,
        revoke_reason VARCHAR(64),
        replaced_by_device_id UUID,
        CONSTRAINT pk_device PRIMARY KEY (device_id),
        CONSTRAINT uq_device_installation_id UNIQUE (installation_id),
        CONSTRAINT uq_device_person_device UNIQUE (person_id, device_id),
        CONSTRAINT uq_device_provenance_binding
            UNIQUE (device_id, person_id, installation_id, local_owner_id),
        CONSTRAINT uq_device_local_identity UNIQUE (installation_id, local_owner_id),
        CONSTRAINT ck_device_status_allowed
            CHECK (status IN ('active', 'revoked', 'replaced')),
        CONSTRAINT ck_device_status_metadata_coherent CHECK (
            (
                status = 'active'
                AND revoked_at IS NULL
                AND revoke_reason IS NULL
                AND replaced_by_device_id IS NULL
            )
            OR (
                status = 'revoked'
                AND revoked_at IS NOT NULL
                AND revoke_reason IS NOT NULL
                AND replaced_by_device_id IS NULL
            )
            OR (
                status = 'replaced'
                AND revoked_at IS NOT NULL
                AND revoke_reason IS NOT NULL
                AND replaced_by_device_id IS NOT NULL
            )
        ),
        CONSTRAINT ck_device_replacement_not_self
            CHECK (replaced_by_device_id IS NULL OR replaced_by_device_id <> device_id),
        CONSTRAINT fk_device_person_id_person
            FOREIGN KEY (person_id) REFERENCES person (person_id) ON DELETE CASCADE,
        CONSTRAINT fk_device_person_replacement
            FOREIGN KEY (person_id, replaced_by_device_id)
            REFERENCES device (person_id, device_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
    )
    """,
    """
    CREATE TABLE life_event (
        event_id UUID NOT NULL,
        person_id UUID NOT NULL,
        event_kind VARCHAR(32) NOT NULL,
        root_revision_id UUID NOT NULL,
        current_revision_id UUID NOT NULL,
        privacy_class VARCHAR(32) DEFAULT 'health_sensitive' NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT pk_life_event PRIMARY KEY (event_id),
        CONSTRAINT uq_life_event_person_event UNIQUE (person_id, event_id),
        CONSTRAINT uq_life_event_event_kind UNIQUE (event_id, event_kind),
        CONSTRAINT uq_life_event_person_event_kind
            UNIQUE (person_id, event_id, event_kind),
        CONSTRAINT ck_life_event_event_kind_allowed CHECK (
            event_kind IN (
                'meal',
                'sleep',
                'wellbeing',
                'medication_intake',
                'supplement_intake',
                'measurement',
                'note'
            )
        ),
        CONSTRAINT ck_life_event_privacy_class_allowed
            CHECK (privacy_class IN ('health_sensitive', 'personal_sensitive')),
        CONSTRAINT ck_life_event_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
        CONSTRAINT fk_life_event_person_id_person
            FOREIGN KEY (person_id) REFERENCES person (person_id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE sync_stream (
        sync_stream_id UUID NOT NULL,
        person_id UUID NOT NULL,
        protocol_stream VARCHAR(32) DEFAULT 'life_events' NOT NULL,
        last_server_sequence BIGINT DEFAULT 0 NOT NULL,
        minimum_available_sequence BIGINT DEFAULT 0 NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT pk_sync_stream PRIMARY KEY (sync_stream_id),
        CONSTRAINT uq_sync_stream_person_protocol UNIQUE (person_id, protocol_stream),
        CONSTRAINT uq_sync_stream_stream_person UNIQUE (sync_stream_id, person_id),
        CONSTRAINT ck_sync_stream_protocol_stream_supported
            CHECK (protocol_stream = 'life_events'),
        CONSTRAINT ck_sync_stream_last_sequence_range
            CHECK (last_server_sequence BETWEEN 0 AND 9007199254740991),
        CONSTRAINT ck_sync_stream_minimum_sequence_range
            CHECK (minimum_available_sequence BETWEEN 0 AND 9007199254740991),
        CONSTRAINT ck_sync_stream_history_window_order
            CHECK (minimum_available_sequence <= last_server_sequence + 1),
        CONSTRAINT ck_sync_stream_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
        CONSTRAINT fk_sync_stream_person_id_person
            FOREIGN KEY (person_id) REFERENCES person (person_id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE capture (
        capture_id UUID NOT NULL,
        person_id UUID NOT NULL,
        device_id UUID NOT NULL,
        installation_id UUID NOT NULL,
        local_owner_id UUID NOT NULL,
        operation_id UUID NOT NULL,
        schema_version VARCHAR(16) NOT NULL,
        source_channel VARCHAR(32) NOT NULL,
        recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
        ingested_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        canonical_document BYTEA NOT NULL,
        canonical_document_sha256 BYTEA NOT NULL,
        canonical_byte_size INTEGER NOT NULL,
        privacy_class VARCHAR(32) DEFAULT 'health_sensitive' NOT NULL,
        retention_until TIMESTAMP WITH TIME ZONE,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        CONSTRAINT pk_capture PRIMARY KEY (capture_id),
        CONSTRAINT fk_capture_provenance_device
            FOREIGN KEY (device_id, person_id, installation_id, local_owner_id)
            REFERENCES device (device_id, person_id, installation_id, local_owner_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_capture_person_capture UNIQUE (person_id, capture_id),
        CONSTRAINT uq_capture_operation UNIQUE (operation_id),
        CONSTRAINT uq_capture_capture_operation UNIQUE (capture_id, operation_id),
        CONSTRAINT ck_capture_schema_version_supported
            CHECK (schema_version = '4.0.0'),
        CONSTRAINT ck_capture_source_channel_allowed CHECK (
            source_channel IN (
                'android_manual',
                'android_recording',
                'android_share_intent',
                'health_connect',
                'file_import',
                'connector',
                'system'
            )
        ),
        CONSTRAINT ck_capture_document_sha256_length
            CHECK (octet_length(canonical_document_sha256) = 32),
        CONSTRAINT ck_capture_canonical_bytes_coherent CHECK (
            canonical_byte_size BETWEEN 1 AND 4194304
            AND canonical_byte_size = octet_length(canonical_document)
        ),
        CONSTRAINT ck_capture_privacy_class_allowed
            CHECK (privacy_class IN ('health_sensitive', 'personal_sensitive')),
        CONSTRAINT ck_capture_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
        CONSTRAINT fk_capture_person_id_person
            FOREIGN KEY (person_id) REFERENCES person (person_id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE credential_family (
        credential_family_id UUID NOT NULL,
        person_id UUID NOT NULL,
        device_id UUID,
        status VARCHAR(16) NOT NULL,
        active_generation BIGINT,
        active_generation_current BOOLEAN DEFAULT true NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        activated_at TIMESTAMP WITH TIME ZONE,
        family_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
        revoked_at TIMESTAMP WITH TIME ZONE,
        revoke_reason VARCHAR(64),
        reuse_detected_at TIMESTAMP WITH TIME ZONE,
        tombstone_until TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_credential_family PRIMARY KEY (credential_family_id),
        CONSTRAINT fk_credential_family_person_device
            FOREIGN KEY (person_id, device_id)
            REFERENCES device (person_id, device_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_credential_family_person_family
            UNIQUE (person_id, credential_family_id),
        CONSTRAINT uq_credential_family_replay_namespace
            UNIQUE (credential_family_id, person_id, device_id),
        CONSTRAINT uq_credential_family_replay_retention
            UNIQUE (
                credential_family_id,
                person_id,
                device_id,
                tombstone_until
            ),
        CONSTRAINT uq_credential_family_generation_deadline
            UNIQUE (
                credential_family_id,
                family_expires_at,
                tombstone_until
            ),
        CONSTRAINT ck_credential_family_status_allowed
            CHECK (status IN ('reserved', 'active', 'revoked', 'expired')),
        CONSTRAINT ck_credential_family_active_generation_range CHECK (
            active_generation IS NULL
            OR active_generation BETWEEN 1 AND 9007199254740991
        ),
        CONSTRAINT ck_credential_family_active_generation_marker
            CHECK (active_generation_current = true),
        CONSTRAINT ck_credential_family_activation_binding_coherent CHECK (
            (
                status = 'reserved'
                AND device_id IS NULL
                AND active_generation IS NULL
                AND activated_at IS NULL
            )
            OR (
                status IN ('active', 'revoked', 'expired')
                AND device_id IS NOT NULL
                AND active_generation IS NOT NULL
                AND activated_at IS NOT NULL
            )
        ),
        CONSTRAINT ck_credential_family_revocation_coherent CHECK (
            (
                status = 'revoked'
                AND revoked_at IS NOT NULL
                AND revoke_reason IS NOT NULL
            )
            OR (
                status <> 'revoked'
                AND revoked_at IS NULL
                AND revoke_reason IS NULL
            )
        ),
        CONSTRAINT ck_credential_family_reuse_requires_revocation
            CHECK (reuse_detected_at IS NULL OR status = 'revoked'),
        CONSTRAINT ck_credential_family_family_expiry_after_creation
            CHECK (family_expires_at > created_at),
        CONSTRAINT ck_credential_family_tombstone_covers_family
            CHECK (tombstone_until >= family_expires_at),
        CONSTRAINT fk_credential_family_person_id_person
            FOREIGN KEY (person_id) REFERENCES person (person_id) ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE credential_generation (
        credential_family_id UUID NOT NULL,
        generation BIGINT NOT NULL,
        is_current BOOLEAN DEFAULT true NOT NULL,
        access_token_hmac BYTEA NOT NULL,
        access_key_generation INTEGER NOT NULL,
        refresh_token_hmac BYTEA NOT NULL,
        refresh_key_generation INTEGER NOT NULL,
        family_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
        family_tombstone_until TIMESTAMP WITH TIME ZONE NOT NULL,
        issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
        access_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
        refresh_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
        refresh_spent_at TIMESTAMP WITH TIME ZONE,
        successor_generation BIGINT,
        reuse_detected_at TIMESTAMP WITH TIME ZONE,
        retained_until TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_credential_generation
            PRIMARY KEY (credential_family_id, generation),
        CONSTRAINT fk_credential_generation_family
            FOREIGN KEY (
                credential_family_id,
                family_expires_at,
                family_tombstone_until
            )
            REFERENCES credential_family (
                credential_family_id,
                family_expires_at,
                tombstone_until
            )
            ON DELETE CASCADE ON UPDATE CASCADE,
        CONSTRAINT fk_credential_generation_successor
            FOREIGN KEY (credential_family_id, successor_generation)
            REFERENCES credential_generation (credential_family_id, generation)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_credential_generation_access_token_lookup
            UNIQUE (access_key_generation, access_token_hmac),
        CONSTRAINT uq_credential_generation_refresh_token_lookup
            UNIQUE (refresh_key_generation, refresh_token_hmac),
        CONSTRAINT uq_credential_generation_current_lookup
            UNIQUE (credential_family_id, generation, is_current),
        CONSTRAINT ck_credential_generation_generation_range
            CHECK (generation BETWEEN 1 AND 9007199254740991),
        CONSTRAINT ck_credential_generation_access_hmac_length
            CHECK (octet_length(access_token_hmac) = 32),
        CONSTRAINT ck_credential_generation_refresh_hmac_length
            CHECK (octet_length(refresh_token_hmac) = 32),
        CONSTRAINT ck_credential_generation_key_generations_positive
            CHECK (access_key_generation > 0 AND refresh_key_generation > 0),
        CONSTRAINT ck_credential_generation_expiry_order CHECK (
            issued_at < access_expires_at
            AND access_expires_at < refresh_expires_at
            AND refresh_expires_at <= family_expires_at
        ),
        CONSTRAINT ck_credential_generation_spend_successor_coherent CHECK (
            (refresh_spent_at IS NULL AND successor_generation IS NULL)
            OR (
                refresh_spent_at IS NOT NULL
                AND successor_generation = generation + 1
            )
        ),
        CONSTRAINT ck_credential_generation_current_spend_coherent CHECK (
            (
                is_current = true
                AND refresh_spent_at IS NULL
                AND successor_generation IS NULL
            )
            OR (
                is_current = false
                AND refresh_spent_at IS NOT NULL
                AND successor_generation = generation + 1
            )
        ),
        CONSTRAINT ck_credential_generation_reuse_after_spend CHECK (
            reuse_detected_at IS NULL
            OR (
                refresh_spent_at IS NOT NULL
                AND reuse_detected_at >= refresh_spent_at
            )
        ),
        CONSTRAINT ck_credential_generation_retention_covers_refresh
            CHECK (
                retained_until >= refresh_expires_at
                AND retained_until >= family_tombstone_until
            )
    )
    """,
    """
    CREATE TABLE enrollment_grant (
        enrollment_grant_id UUID NOT NULL,
        person_id UUID NOT NULL,
        credential_family_id UUID NOT NULL,
        code_hmac BYTEA NOT NULL,
        code_key_generation INTEGER NOT NULL,
        replacement_allowed BOOLEAN DEFAULT false NOT NULL,
        status VARCHAR(16) NOT NULL,
        attempt_count INTEGER DEFAULT 0 NOT NULL,
        max_attempts INTEGER DEFAULT 5 NOT NULL,
        issued_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
        consumed_at TIMESTAMP WITH TIME ZONE,
        revoked_at TIMESTAMP WITH TIME ZONE,
        terminal_outcome VARCHAR(40),
        resolved_device_id UUID,
        CONSTRAINT pk_enrollment_grant PRIMARY KEY (enrollment_grant_id),
        CONSTRAINT fk_enrollment_grant_person_family
            FOREIGN KEY (person_id, credential_family_id)
            REFERENCES credential_family (person_id, credential_family_id)
            ON DELETE CASCADE,
        CONSTRAINT fk_enrollment_grant_person_device
            FOREIGN KEY (person_id, resolved_device_id)
            REFERENCES device (person_id, device_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_enrollment_grant_credential_family
            UNIQUE (credential_family_id),
        CONSTRAINT uq_enrollment_grant_code_lookup
            UNIQUE (code_key_generation, code_hmac),
        CONSTRAINT ck_enrollment_grant_code_hmac_length
            CHECK (octet_length(code_hmac) = 32),
        CONSTRAINT ck_enrollment_grant_code_key_generation_positive
            CHECK (code_key_generation > 0),
        CONSTRAINT ck_enrollment_grant_status_allowed
            CHECK (status IN ('issued', 'consumed', 'revoked')),
        CONSTRAINT ck_enrollment_grant_attempt_bounds CHECK (
            attempt_count BETWEEN 0 AND max_attempts
            AND max_attempts BETWEEN 1 AND 100
        ),
        CONSTRAINT ck_enrollment_grant_expiry_after_issue
            CHECK (expires_at > issued_at),
        CONSTRAINT ck_enrollment_grant_terminal_state_coherent CHECK (
            (
                status = 'issued'
                AND consumed_at IS NULL
                AND revoked_at IS NULL
                AND terminal_outcome IS NULL
                AND resolved_device_id IS NULL
            )
            OR (
                status = 'consumed'
                AND consumed_at IS NOT NULL
                AND revoked_at IS NULL
                AND terminal_outcome IN (
                    'enrolled',
                    'active_device_exists',
                    'replacement_not_authorized'
                )
                AND (
                    (
                        terminal_outcome = 'enrolled'
                        AND resolved_device_id IS NOT NULL
                    )
                    OR (
                        terminal_outcome <> 'enrolled'
                        AND resolved_device_id IS NULL
                    )
                )
            )
            OR (
                status = 'revoked'
                AND consumed_at IS NULL
                AND revoked_at IS NOT NULL
                AND terminal_outcome IS NULL
                AND resolved_device_id IS NULL
            )
        )
    )
    """,
    """
    CREATE TABLE sync_operation_registry (
        operation_id UUID NOT NULL,
        person_id UUID NOT NULL,
        sync_stream_id UUID NOT NULL,
        credential_family_id UUID NOT NULL,
        submitting_device_id UUID NOT NULL,
        installation_id UUID NOT NULL,
        local_owner_id UUID NOT NULL,
        client_sequence BIGINT NOT NULL,
        first_batch_id UUID NOT NULL,
        first_batch_ordinal SMALLINT NOT NULL,
        capture_id UUID NOT NULL,
        event_id UUID NOT NULL,
        revision_id UUID NOT NULL,
        expected_current_revision_id UUID,
        operation_content_sha256 BYTEA NOT NULL,
        canonical_operation BYTEA NOT NULL,
        canonical_byte_size INTEGER NOT NULL,
        registry_state VARCHAR(32) NOT NULL,
        terminal_error_code VARCHAR(40),
        terminal_result_document BYTEA,
        terminal_result_sha256 BYTEA,
        terminal_result_byte_size INTEGER,
        first_received_at TIMESTAMP WITH TIME ZONE NOT NULL,
        last_evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
        terminal_at TIMESTAMP WITH TIME ZONE,
        privacy_class VARCHAR(32) DEFAULT 'health_sensitive' NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        CONSTRAINT pk_sync_operation_registry PRIMARY KEY (operation_id),
        CONSTRAINT fk_sync_operation_registry_stream_person
            FOREIGN KEY (sync_stream_id, person_id)
            REFERENCES sync_stream (sync_stream_id, person_id)
            ON DELETE CASCADE,
        CONSTRAINT fk_sync_operation_registry_credential_namespace
            FOREIGN KEY (
                credential_family_id,
                person_id,
                submitting_device_id
            )
            REFERENCES credential_family (
                credential_family_id,
                person_id,
                device_id
            )
            ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_sync_operation_registry_provenance_device
            FOREIGN KEY (
                submitting_device_id,
                person_id,
                installation_id,
                local_owner_id
            )
            REFERENCES device (
                device_id,
                person_id,
                installation_id,
                local_owner_id
            )
            ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_sync_operation_registry_installation_client_sequence
            UNIQUE (installation_id, client_sequence),
        CONSTRAINT uq_sync_operation_registry_capture UNIQUE (capture_id),
        CONSTRAINT uq_sync_operation_registry_revision UNIQUE (revision_id),
        CONSTRAINT uq_sync_operation_registry_first_batch_membership
            UNIQUE (
                credential_family_id,
                submitting_device_id,
                first_batch_id,
                first_batch_ordinal
            ),
        CONSTRAINT uq_sync_operation_registry_commit_binding
            UNIQUE (
                operation_id,
                person_id,
                sync_stream_id,
                credential_family_id,
                submitting_device_id,
                installation_id,
                local_owner_id,
                client_sequence,
                first_batch_id,
                first_batch_ordinal,
                capture_id,
                event_id,
                revision_id,
                operation_content_sha256,
                registry_state
            ),
        CONSTRAINT ck_sync_operation_registry_client_sequence_range
            CHECK (client_sequence BETWEEN 1 AND 9007199254740991),
        CONSTRAINT ck_sync_operation_registry_batch_ordinal_range
            CHECK (first_batch_ordinal BETWEEN 0 AND 99),
        CONSTRAINT ck_sync_operation_registry_operation_sha256_length
            CHECK (octet_length(operation_content_sha256) = 32),
        CONSTRAINT ck_sync_operation_registry_canonical_bytes_coherent CHECK (
            canonical_byte_size BETWEEN 1 AND 4194304
            AND canonical_byte_size = octet_length(canonical_operation)
        ),
        CONSTRAINT ck_sync_operation_registry_registry_state_allowed
            CHECK (
                registry_state IN (
                    'pending_missing_parent',
                    'terminal_error',
                    'committed'
                )
            ),
        CONSTRAINT ck_sync_operation_registry_registry_state_coherent CHECK (
            (
                registry_state = 'pending_missing_parent'
                AND terminal_error_code IS NULL
                AND terminal_result_document IS NULL
                AND terminal_result_sha256 IS NULL
                AND terminal_result_byte_size IS NULL
                AND terminal_at IS NULL
            )
            OR (
                registry_state = 'committed'
                AND terminal_error_code IS NULL
                AND terminal_result_document IS NULL
                AND terminal_result_sha256 IS NULL
                AND terminal_result_byte_size IS NULL
                AND terminal_at IS NOT NULL
            )
            OR (
                registry_state = 'terminal_error'
                AND terminal_error_code IN (
                    'unsupported_schema_version',
                    'unsupported_operation_kind',
                    'unsupported_event_kind',
                    'unsupported_source_channel',
                    'schema_invalid',
                    'operation_hash_mismatch',
                    'operation_id_collision',
                    'client_sequence_collision',
                    'capture_id_collision',
                    'revision_id_collision',
                    'event_id_collision',
                    'invalid_parent',
                    'ownership_violation'
                )
                AND terminal_result_document IS NOT NULL
                AND terminal_result_sha256 IS NOT NULL
                AND terminal_result_byte_size IS NOT NULL
                AND terminal_at IS NOT NULL
            )
        ),
        CONSTRAINT ck_sync_operation_registry_terminal_result_bytes_coherent
            CHECK (
                (
                    terminal_result_document IS NULL
                    AND terminal_result_sha256 IS NULL
                    AND terminal_result_byte_size IS NULL
                )
                OR (
                    octet_length(terminal_result_sha256) = 32
                    AND terminal_result_byte_size BETWEEN 1 AND 4194304
                    AND terminal_result_byte_size
                        = octet_length(terminal_result_document)
                )
            ),
        CONSTRAINT ck_sync_operation_registry_evaluation_time_order CHECK (
            last_evaluated_at >= first_received_at
            AND (
                terminal_at IS NULL
                OR terminal_at >= first_received_at
            )
        ),
        CONSTRAINT ck_sync_operation_registry_privacy_class_allowed
            CHECK (privacy_class IN ('health_sensitive', 'personal_sensitive')),
        CONSTRAINT ck_sync_operation_registry_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991)
    )
    """,
    """
    CREATE TABLE event_revision (
        revision_id UUID NOT NULL,
        event_id UUID NOT NULL,
        person_id UUID NOT NULL,
        capture_id UUID NOT NULL,
        submitting_device_id UUID NOT NULL,
        installation_id UUID NOT NULL,
        local_owner_id UUID NOT NULL,
        revision_no BIGINT NOT NULL,
        parent_revision_id UUID,
        parent_revision_no BIGINT,
        expected_current_revision_id UUID,
        schema_version VARCHAR(16) NOT NULL,
        event_kind VARCHAR(32) NOT NULL,
        assertion_status VARCHAR(16) NOT NULL,
        record_status VARCHAR(16) NOT NULL,
        verification_status VARCHAR(24) NOT NULL,
        actor VARCHAR(16) NOT NULL,
        correction_reason VARCHAR(500),
        source_channel VARCHAR(32) NOT NULL,
        source_record_id TEXT,
        source_record_version TEXT,
        recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
        effective_start_utc TIMESTAMP WITH TIME ZONE NOT NULL,
        effective_end_utc TIMESTAMP WITH TIME ZONE,
        original_local_start TIMESTAMP WITHOUT TIME ZONE NOT NULL,
        original_local_end TIMESTAMP WITHOUT TIME ZONE,
        timezone_id VARCHAR(64) NOT NULL,
        start_offset_seconds INTEGER NOT NULL,
        end_offset_seconds INTEGER,
        temporal_precision VARCHAR(16) NOT NULL,
        local_date DATE NOT NULL,
        revision_content_sha256 BYTEA NOT NULL,
        canonical_document BYTEA NOT NULL,
        canonical_document_sha256 BYTEA NOT NULL,
        canonical_byte_size INTEGER NOT NULL,
        privacy_class VARCHAR(32) DEFAULT 'health_sensitive' NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        server_received_at TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_event_revision PRIMARY KEY (revision_id),
        CONSTRAINT fk_event_revision_event_owner_kind
            FOREIGN KEY (person_id, event_id, event_kind)
            REFERENCES life_event (person_id, event_id, event_kind)
            ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_event_revision_person_capture
            FOREIGN KEY (person_id, capture_id)
            REFERENCES capture (person_id, capture_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_event_revision_provenance_device
            FOREIGN KEY (
                submitting_device_id,
                person_id,
                installation_id,
                local_owner_id
            )
            REFERENCES device (
                device_id,
                person_id,
                installation_id,
                local_owner_id
            )
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_event_revision_parent
            FOREIGN KEY (event_id, parent_revision_id, parent_revision_no)
            REFERENCES event_revision (event_id, revision_id, revision_no)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_event_revision_event_revision UNIQUE (event_id, revision_id),
        CONSTRAINT uq_event_revision_person_event_revision
            UNIQUE (person_id, event_id, revision_id),
        CONSTRAINT uq_event_revision_parent_lookup
            UNIQUE (event_id, revision_id, revision_no),
        CONSTRAINT ck_event_revision_revision_no_range
            CHECK (revision_no BETWEEN 1 AND 9007199254740991),
        CONSTRAINT ck_event_revision_linear_parent_coherent CHECK (
            (
                revision_no = 1
                AND parent_revision_id IS NULL
                AND parent_revision_no IS NULL
                AND expected_current_revision_id IS NULL
            )
            OR (
                revision_no > 1
                AND parent_revision_id IS NOT NULL
                AND parent_revision_no IS NOT NULL
                AND revision_no = parent_revision_no + 1
                AND expected_current_revision_id = parent_revision_id
            )
        ),
        CONSTRAINT ck_event_revision_schema_version_supported
            CHECK (schema_version = '4.0.0'),
        CONSTRAINT ck_event_revision_assertion_status_allowed
            CHECK (assertion_status IN ('observed', 'uncertain')),
        CONSTRAINT ck_event_revision_record_status_allowed
            CHECK (record_status IN ('active', 'retracted')),
        CONSTRAINT ck_event_revision_verification_status_allowed CHECK (
            verification_status IN (
                'source_recorded',
                'user_confirmed',
                'machine_inferred',
                'needs_review'
            )
        ),
        CONSTRAINT ck_event_revision_actor_allowed
            CHECK (actor IN ('user', 'system', 'connector')),
        CONSTRAINT ck_event_revision_source_channel_allowed CHECK (
            source_channel IN (
                'android_manual',
                'android_recording',
                'android_share_intent',
                'health_connect',
                'file_import',
                'connector',
                'system'
            )
        ),
        CONSTRAINT ck_event_revision_source_record_version_requires_id CHECK (
            (source_record_id IS NULL AND source_record_version IS NULL)
            OR source_record_id IS NOT NULL
        ),
        CONSTRAINT ck_event_revision_offset_range CHECK (
            start_offset_seconds BETWEEN -50400 AND 50400
            AND (
                end_offset_seconds IS NULL
                OR end_offset_seconds BETWEEN -50400 AND 50400
            )
        ),
        CONSTRAINT ck_event_revision_interval_fields_coherent CHECK (
            (
                effective_end_utc IS NULL
                AND original_local_end IS NULL
                AND end_offset_seconds IS NULL
            )
            OR (
                effective_end_utc IS NOT NULL
                AND original_local_end IS NOT NULL
                AND end_offset_seconds IS NOT NULL
                AND effective_end_utc >= effective_start_utc
            )
        ),
        CONSTRAINT ck_event_revision_temporal_precision_allowed CHECK (
            temporal_precision IN (
                'exact',
                'minute',
                'hour',
                'part_of_day',
                'date',
                'approximate',
                'unknown'
            )
        ),
        CONSTRAINT ck_event_revision_sha256_lengths CHECK (
            octet_length(revision_content_sha256) = 32
            AND octet_length(canonical_document_sha256) = 32
        ),
        CONSTRAINT ck_event_revision_canonical_bytes_coherent CHECK (
            canonical_byte_size BETWEEN 1 AND 4194304
            AND canonical_byte_size = octet_length(canonical_document)
        ),
        CONSTRAINT ck_event_revision_privacy_class_allowed
            CHECK (privacy_class IN ('health_sensitive', 'personal_sensitive')),
        CONSTRAINT ck_event_revision_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991)
    )
    """,
    """
    CREATE TABLE http_replay (
        http_replay_id UUID NOT NULL,
        endpoint_id VARCHAR(32) NOT NULL,
        protocol_version VARCHAR(16) NOT NULL,
        request_identity_kind VARCHAR(16) NOT NULL,
        request_identity UUID NOT NULL,
        person_id UUID NOT NULL,
        credential_family_id UUID NOT NULL,
        device_id UUID NOT NULL,
        family_tombstone_until TIMESTAMP WITH TIME ZONE NOT NULL,
        request_fingerprint_hmac BYTEA NOT NULL,
        fingerprint_key_generation INTEGER NOT NULL,
        outcome_class VARCHAR(16) NOT NULL,
        stored_outcome VARCHAR(128) NOT NULL,
        http_status SMALLINT NOT NULL,
        error_code VARCHAR(64),
        retryable BOOLEAN,
        response_body_ciphertext BYTEA NOT NULL,
        response_body_nonce BYTEA NOT NULL,
        response_body_sha256 BYTEA NOT NULL,
        response_body_plaintext_bytes INTEGER NOT NULL,
        response_encryption_algorithm VARCHAR(16) DEFAULT 'aes_256_gcm' NOT NULL,
        response_encryption_key_generation INTEGER NOT NULL,
        committed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        retention_until TIMESTAMP WITH TIME ZONE NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        CONSTRAINT pk_http_replay PRIMARY KEY (http_replay_id),
        CONSTRAINT fk_http_replay_credential_namespace
            FOREIGN KEY (
                credential_family_id,
                person_id,
                device_id,
                family_tombstone_until
            )
            REFERENCES credential_family (
                credential_family_id,
                person_id,
                device_id,
                tombstone_until
            )
            ON DELETE CASCADE ON UPDATE CASCADE DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_http_replay_durable_request_namespace UNIQUE (
            endpoint_id,
            protocol_version,
            credential_family_id,
            device_id,
            request_identity
        ),
        CONSTRAINT uq_http_replay_encryption_nonce
            UNIQUE (
                response_encryption_key_generation,
                response_body_nonce
            ),
        CONSTRAINT ck_http_replay_endpoint_id_allowed CHECK (
            endpoint_id IN ('auth_revoke', 'sync_push', 'sync_bootstrap', 'sync_pull')
        ),
        CONSTRAINT ck_http_replay_request_identity_kind_coherent CHECK (
            (
                endpoint_id = 'sync_push'
                AND request_identity_kind = 'batch_id'
            )
            OR (
                endpoint_id <> 'sync_push'
                AND request_identity_kind = 'request_id'
            )
        ),
        CONSTRAINT ck_http_replay_request_fingerprint_length
            CHECK (octet_length(request_fingerprint_hmac) = 32),
        CONSTRAINT ck_http_replay_key_generations_positive CHECK (
            fingerprint_key_generation > 0
            AND response_encryption_key_generation > 0
        ),
        CONSTRAINT ck_http_replay_outcome_class_allowed
            CHECK (outcome_class IN ('success', 'api_error')),
        CONSTRAINT ck_http_replay_outcome_metadata_coherent CHECK (
            (
                outcome_class = 'success'
                AND http_status = 200
                AND error_code IS NULL
                AND retryable IS NULL
            )
            OR (
                outcome_class = 'api_error'
                AND http_status BETWEEN 400 AND 499
                AND error_code IS NOT NULL
                AND retryable = false
            )
        ),
        CONSTRAINT ck_http_replay_stored_outcome_allowed CHECK (
            stored_outcome IN (
                'authenticated_success',
                'authenticated_nonretryable_terminal_api_error',
                'terminal_auth_revoke_401_credential_unavailable',
                'terminal_sync_401_after_one_allowed_credential_recovery_and_current_generation_exact_original_request_retry_exhausted',
                'terminal_operation_result_batch'
            )
        ),
        CONSTRAINT ck_http_replay_stored_outcome_coherent CHECK (
            (
                outcome_class = 'success'
                AND (
                    (
                        endpoint_id = 'sync_push'
                        AND stored_outcome = 'terminal_operation_result_batch'
                    )
                    OR (
                        endpoint_id IN (
                            'auth_revoke',
                            'sync_bootstrap',
                            'sync_pull'
                        )
                        AND stored_outcome = 'authenticated_success'
                    )
                )
            )
            OR (
                outcome_class = 'api_error'
                AND (
                    (
                        stored_outcome =
                            'authenticated_nonretryable_terminal_api_error'
                        AND error_code NOT IN (
                            'credential_unavailable',
                            'rate_limited',
                            'temporarily_unavailable'
                        )
                    )
                    OR (
                        endpoint_id = 'auth_revoke'
                        AND stored_outcome =
                            'terminal_auth_revoke_401_credential_unavailable'
                        AND http_status = 401
                        AND error_code = 'credential_unavailable'
                    )
                    OR (
                        endpoint_id IN (
                            'sync_push',
                            'sync_bootstrap',
                            'sync_pull'
                        )
                        AND stored_outcome =
                            'terminal_sync_401_after_one_allowed_credential_recovery_and_current_generation_exact_original_request_retry_exhausted'
                        AND http_status = 401
                        AND error_code = 'credential_unavailable'
                    )
                )
            )
        ),
        CONSTRAINT ck_http_replay_terminal_error_status_mapping CHECK (
            (error_code IS NULL)
            OR (
                error_code IN (
                    'malformed_json',
                    'unsupported_protocol_version',
                    'idempotency_key_mismatch',
                    'cursor_invalid'
                )
                AND http_status = 400
            )
            OR (
                error_code = 'credential_unavailable'
                AND http_status = 401
            )
            OR (
                error_code = 'device_mismatch'
                AND http_status = 403
            )
            OR (
                error_code IN (
                    'active_device_exists',
                    'batch_id_collision',
                    'request_id_collision',
                    'bootstrap_required'
                )
                AND http_status = 409
            )
            OR (
                error_code = 'cursor_expired'
                AND http_status = 410
            )
            OR (
                error_code = 'request_too_large'
                AND http_status = 413
            )
            OR (
                error_code = 'unsupported_media_type'
                AND http_status = 415
            )
            OR (
                error_code IN (
                    'request_schema_invalid',
                    'batch_hash_mismatch'
                )
                AND http_status = 422
            )
        ),
        CONSTRAINT ck_http_replay_terminal_error_endpoint_allowed CHECK (
            (error_code IS NULL)
            OR (
                endpoint_id = 'auth_revoke'
                AND error_code IN (
                    'malformed_json',
                    'unsupported_protocol_version',
                    'credential_unavailable',
                    'request_id_collision',
                    'request_too_large',
                    'unsupported_media_type',
                    'request_schema_invalid'
                )
            )
            OR (
                endpoint_id = 'sync_push'
                AND error_code IN (
                    'malformed_json',
                    'unsupported_protocol_version',
                    'idempotency_key_mismatch',
                    'credential_unavailable',
                    'device_mismatch',
                    'batch_id_collision',
                    'bootstrap_required',
                    'request_too_large',
                    'unsupported_media_type',
                    'request_schema_invalid',
                    'batch_hash_mismatch'
                )
            )
            OR (
                endpoint_id = 'sync_bootstrap'
                AND error_code IN (
                    'malformed_json',
                    'unsupported_protocol_version',
                    'cursor_invalid',
                    'credential_unavailable',
                    'device_mismatch',
                    'request_id_collision',
                    'bootstrap_required',
                    'cursor_expired',
                    'request_too_large',
                    'unsupported_media_type',
                    'request_schema_invalid'
                )
            )
            OR (
                endpoint_id = 'sync_pull'
                AND error_code IN (
                    'malformed_json',
                    'unsupported_protocol_version',
                    'cursor_invalid',
                    'credential_unavailable',
                    'device_mismatch',
                    'request_id_collision',
                    'bootstrap_required',
                    'request_too_large',
                    'unsupported_media_type',
                    'request_schema_invalid'
                )
            )
        ),
        CONSTRAINT ck_http_replay_response_nonce_length
            CHECK (octet_length(response_body_nonce) = 12),
        CONSTRAINT ck_http_replay_response_sha256_length
            CHECK (octet_length(response_body_sha256) = 32),
        CONSTRAINT ck_http_replay_response_encryption_algorithm_supported
            CHECK (response_encryption_algorithm = 'aes_256_gcm'),
        CONSTRAINT ck_http_replay_encrypted_response_size CHECK (
            response_body_plaintext_bytes BETWEEN 1 AND 4194304
            AND octet_length(response_body_ciphertext)
                = response_body_plaintext_bytes + 16
        ),
        CONSTRAINT ck_http_replay_retention_after_commit
            CHECK (
                retention_until >= committed_at + INTERVAL '30 days'
                AND (
                    endpoint_id <> 'auth_revoke'
                    OR retention_until >= family_tombstone_until
                )
            ),
        CONSTRAINT ck_http_replay_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991)
    )
    """,
    """
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
            UNIQUE (
                person_id,
                device_id,
                credential_family_id,
                bootstrap_id
            ),
        CONSTRAINT uq_sync_snapshot_cursor_binding
            UNIQUE (
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
            (
                status = 'active'
                AND completed_at IS NULL
                AND revoked_at IS NULL
            )
            OR (
                status = 'complete'
                AND completed_at IS NOT NULL
                AND revoked_at IS NULL
            )
            OR (status = 'expired' AND revoked_at IS NULL)
            OR (status = 'revoked' AND revoked_at IS NOT NULL)
        )
    )
    """,
    """
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
        CONSTRAINT fk_sync_cursor_snapshot_binding
            FOREIGN KEY (
                snapshot_id,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                protocol_stream,
                purge_generation,
                snapshot_high_watermark_sequence
            )
            REFERENCES sync_snapshot (
                snapshot_id,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                protocol_stream,
                purge_generation,
                high_watermark_sequence
            )
            ON DELETE CASCADE,
        CONSTRAINT fk_sync_cursor_bootstrap_binding
            FOREIGN KEY (snapshot_id, bootstrap_id)
            REFERENCES sync_snapshot (snapshot_id, bootstrap_id)
            ON DELETE CASCADE,
        CONSTRAINT fk_sync_cursor_parent_namespace
            FOREIGN KEY (
                parent_cursor_id,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                snapshot_id,
                protocol_stream,
                purge_generation
            )
            REFERENCES sync_cursor (
                sync_cursor_id,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                snapshot_id,
                protocol_stream,
                purge_generation
            )
            ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_sync_cursor_handle_lookup
            UNIQUE (signing_key_generation, handle_hmac),
        CONSTRAINT uq_sync_cursor_parent_namespace
            UNIQUE (
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
            (
                cursor_kind = 'bootstrap_page'
                AND bootstrap_id IS NOT NULL
            )
            OR (
                cursor_kind = 'incremental'
                AND bootstrap_id IS NULL
            )
        ),
        CONSTRAINT ck_sync_cursor_protocol_stream_supported
            CHECK (protocol_stream = 'life_events'),
        CONSTRAINT ck_sync_cursor_exact_position_range
            CHECK (exact_position BETWEEN 0 AND 9007199254740991),
        CONSTRAINT ck_sync_cursor_position_within_snapshot
            CHECK (exact_position <= snapshot_high_watermark_sequence),
        CONSTRAINT ck_sync_cursor_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991),
        CONSTRAINT ck_sync_cursor_expiry_after_issue
            CHECK (expires_at > issued_at)
    )
    """,
    """
    CREATE TABLE sync_operation (
        operation_id UUID NOT NULL,
        person_id UUID NOT NULL,
        sync_stream_id UUID NOT NULL,
        credential_family_id UUID NOT NULL,
        submitting_device_id UUID NOT NULL,
        installation_id UUID NOT NULL,
        local_owner_id UUID NOT NULL,
        client_sequence BIGINT NOT NULL,
        first_batch_id UUID NOT NULL,
        first_batch_ordinal SMALLINT NOT NULL,
        capture_id UUID NOT NULL,
        event_id UUID NOT NULL,
        revision_id UUID NOT NULL,
        expected_current_revision_id UUID,
        operation_kind VARCHAR(32) NOT NULL,
        operation_content_sha256 BYTEA NOT NULL,
        registry_state VARCHAR(32) DEFAULT 'committed' NOT NULL,
        canonical_operation BYTEA NOT NULL,
        canonical_byte_size INTEGER NOT NULL,
        result_code VARCHAR(16) NOT NULL,
        current_revision_id UUID NOT NULL,
        server_sequence BIGINT NOT NULL,
        first_received_at TIMESTAMP WITH TIME ZONE NOT NULL,
        committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
        privacy_class VARCHAR(32) DEFAULT 'health_sensitive' NOT NULL,
        purge_generation BIGINT DEFAULT 0 NOT NULL,
        CONSTRAINT pk_sync_operation PRIMARY KEY (operation_id),
        CONSTRAINT fk_sync_operation_registry
            FOREIGN KEY (
                operation_id,
                person_id,
                sync_stream_id,
                credential_family_id,
                submitting_device_id,
                installation_id,
                local_owner_id,
                client_sequence,
                first_batch_id,
                first_batch_ordinal,
                capture_id,
                event_id,
                revision_id,
                operation_content_sha256,
                registry_state
            )
            REFERENCES sync_operation_registry (
                operation_id,
                person_id,
                sync_stream_id,
                credential_family_id,
                submitting_device_id,
                installation_id,
                local_owner_id,
                client_sequence,
                first_batch_id,
                first_batch_ordinal,
                capture_id,
                event_id,
                revision_id,
                operation_content_sha256,
                registry_state
            )
            ON DELETE CASCADE,
        CONSTRAINT fk_sync_operation_stream_person
            FOREIGN KEY (sync_stream_id, person_id)
            REFERENCES sync_stream (sync_stream_id, person_id)
            ON DELETE CASCADE,
        CONSTRAINT fk_sync_operation_credential_namespace
            FOREIGN KEY (
                credential_family_id,
                person_id,
                submitting_device_id
            )
            REFERENCES credential_family (
                credential_family_id,
                person_id,
                device_id
            )
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_sync_operation_provenance_device
            FOREIGN KEY (
                submitting_device_id,
                person_id,
                installation_id,
                local_owner_id
            )
            REFERENCES device (
                device_id,
                person_id,
                installation_id,
                local_owner_id
            )
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_sync_operation_capture_operation
            FOREIGN KEY (capture_id, operation_id)
            REFERENCES capture (capture_id, operation_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_sync_operation_event_revision
            FOREIGN KEY (person_id, event_id, revision_id)
            REFERENCES event_revision (person_id, event_id, revision_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_sync_operation_expected_revision
            FOREIGN KEY (event_id, expected_current_revision_id)
            REFERENCES event_revision (event_id, revision_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT fk_sync_operation_current_revision
            FOREIGN KEY (event_id, current_revision_id)
            REFERENCES event_revision (event_id, revision_id)
            ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
        CONSTRAINT uq_sync_operation_revision UNIQUE (revision_id),
        CONSTRAINT uq_sync_operation_installation_client_sequence
            UNIQUE (installation_id, client_sequence),
        CONSTRAINT uq_sync_operation_stream_server_sequence
            UNIQUE (sync_stream_id, server_sequence),
        CONSTRAINT uq_sync_operation_first_batch_membership
            UNIQUE (
                credential_family_id,
                submitting_device_id,
                first_batch_id,
                first_batch_ordinal
            ),
        CONSTRAINT ck_sync_operation_client_sequence_range
            CHECK (client_sequence BETWEEN 1 AND 9007199254740991),
        CONSTRAINT ck_sync_operation_batch_ordinal_range
            CHECK (first_batch_ordinal BETWEEN 0 AND 99),
        CONSTRAINT ck_sync_operation_operation_kind_supported
            CHECK (operation_kind = 'append_event_revision'),
        CONSTRAINT ck_sync_operation_registry_state_committed
            CHECK (registry_state = 'committed'),
        CONSTRAINT ck_sync_operation_operation_sha256_length
            CHECK (octet_length(operation_content_sha256) = 32),
        CONSTRAINT ck_sync_operation_canonical_bytes_coherent CHECK (
            canonical_byte_size BETWEEN 1 AND 4194304
            AND canonical_byte_size = octet_length(canonical_operation)
        ),
        CONSTRAINT ck_sync_operation_result_current_revision_coherent CHECK (
            (
                result_code = 'applied'
                AND current_revision_id = revision_id
            )
            OR (
                result_code = 'conflict'
                AND current_revision_id <> revision_id
            )
        ),
        CONSTRAINT ck_sync_operation_server_sequence_range
            CHECK (server_sequence BETWEEN 1 AND 9007199254740991),
        CONSTRAINT ck_sync_operation_commit_after_receive
            CHECK (committed_at >= first_received_at),
        CONSTRAINT ck_sync_operation_privacy_class_allowed
            CHECK (privacy_class IN ('health_sensitive', 'personal_sensitive')),
        CONSTRAINT ck_sync_operation_purge_generation_range
            CHECK (purge_generation BETWEEN 0 AND 9007199254740991)
    )
    """,
    """
    ALTER TABLE credential_family
    ADD CONSTRAINT fk_credential_family_active_generation
    FOREIGN KEY (
        credential_family_id,
        active_generation,
        active_generation_current
    )
    REFERENCES credential_generation (
        credential_family_id,
        generation,
        is_current
    )
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
    """,
    """
    ALTER TABLE capture
    ADD CONSTRAINT fk_capture_operation_registry
    FOREIGN KEY (operation_id)
    REFERENCES sync_operation_registry (operation_id)
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
    """,
    """
    ALTER TABLE life_event
    ADD CONSTRAINT fk_life_event_current_revision
    FOREIGN KEY (event_id, current_revision_id)
    REFERENCES event_revision (event_id, revision_id)
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
    """,
    """
    ALTER TABLE life_event
    ADD CONSTRAINT fk_life_event_root_revision
    FOREIGN KEY (event_id, root_revision_id)
    REFERENCES event_revision (event_id, revision_id)
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
    """,
    "CREATE INDEX ix_device_person_id ON device (person_id)",
    """
    CREATE UNIQUE INDEX uq_device_one_active_per_person
    ON device (person_id) WHERE status = 'active'
    """,
    "CREATE INDEX ix_credential_family_device_id ON credential_family (device_id)",
    "CREATE INDEX ix_credential_family_person_id ON credential_family (person_id)",
    """
    CREATE INDEX ix_credential_family_tombstone_until
    ON credential_family (tombstone_until)
    """,
    """
    CREATE UNIQUE INDEX uq_credential_family_one_active_per_device
    ON credential_family (device_id) WHERE status = 'active'
    """,
    "CREATE INDEX ix_enrollment_grant_expires_at ON enrollment_grant (expires_at)",
    "CREATE INDEX ix_enrollment_grant_person_id ON enrollment_grant (person_id)",
    """
    CREATE INDEX ix_credential_generation_retained_until
    ON credential_generation (retained_until)
    """,
    """
    CREATE UNIQUE INDEX uq_credential_generation_one_current_per_family
    ON credential_generation (credential_family_id) WHERE is_current = true
    """,
    """
    CREATE INDEX ix_sync_operation_registry_person_state
    ON sync_operation_registry (person_id, registry_state)
    """,
    """
    CREATE INDEX ix_sync_operation_registry_event_id
    ON sync_operation_registry (event_id)
    """,
    """
    CREATE INDEX ix_capture_person_recorded_at
    ON capture (person_id, recorded_at)
    """,
    "CREATE INDEX ix_life_event_person_kind ON life_event (person_id, event_kind)",
    """
    CREATE INDEX ix_event_revision_event_revision_no
    ON event_revision (event_id, revision_no)
    """,
    """
    CREATE INDEX ix_event_revision_person_effective_start
    ON event_revision (person_id, effective_start_utc)
    """,
    """
    CREATE INDEX ix_event_revision_source_record
    ON event_revision (person_id, source_channel, source_record_id)
    """,
    "CREATE INDEX ix_sync_operation_event_id ON sync_operation (event_id)",
    """
    CREATE INDEX ix_sync_operation_person_committed_at
    ON sync_operation (person_id, committed_at)
    """,
    """
    CREATE INDEX ix_http_replay_person_device
    ON http_replay (person_id, device_id)
    """,
    "CREATE INDEX ix_http_replay_retention_until ON http_replay (retention_until)",
    "CREATE INDEX ix_sync_snapshot_expires_at ON sync_snapshot (expires_at)",
    """
    CREATE INDEX ix_sync_cursor_expiry
    ON sync_cursor (expires_at) WHERE revoked_at IS NULL
    """,
)


DROP_TABLES = (
    "sync_operation",
    "sync_cursor",
    "sync_snapshot",
    "http_replay",
    "event_revision",
    "capture",
    "sync_operation_registry",
    "enrollment_grant",
    "credential_generation",
    "credential_family",
    "sync_stream",
    "life_event",
    "device",
    "person",
)


def upgrade() -> None:
    for statement in CREATE_STATEMENTS:
        op.execute(sa.text(statement))


def downgrade() -> None:
    op.drop_constraint(
        "fk_life_event_root_revision",
        "life_event",
        type_="foreignkey",
    )
    op.drop_constraint(
        "fk_life_event_current_revision",
        "life_event",
        type_="foreignkey",
    )
    op.drop_constraint(
        "fk_credential_family_active_generation",
        "credential_family",
        type_="foreignkey",
    )
    for table_name in DROP_TABLES:
        op.drop_table(table_name)
