SELECT fixture_name || '=' || row_count::text
FROM (
    SELECT 'alembic_version' AS fixture_name, count(*) AS row_count FROM alembic_version
    UNION ALL SELECT 'capture', count(*) FROM capture
    UNION ALL SELECT 'credential_family', count(*) FROM credential_family
    UNION ALL SELECT 'credential_generation', count(*) FROM credential_generation
    UNION ALL SELECT 'device', count(*) FROM device
    UNION ALL SELECT 'device_replay_quota', count(*) FROM device_replay_quota
    UNION ALL SELECT 'enrollment_grant', count(*) FROM enrollment_grant
    UNION ALL SELECT 'event_revision', count(*) FROM event_revision
    UNION ALL SELECT 'http_replay', count(*) FROM http_replay
    UNION ALL SELECT 'life_event', count(*) FROM life_event
    UNION ALL SELECT 'person', count(*) FROM person
    UNION ALL SELECT 'sync_cursor', count(*) FROM sync_cursor
    UNION ALL SELECT 'sync_operation', count(*) FROM sync_operation
    UNION ALL SELECT 'sync_operation_registry', count(*) FROM sync_operation_registry
    UNION ALL SELECT 'sync_read_page', count(*) FROM sync_read_page
    UNION ALL SELECT 'sync_read_state', count(*) FROM sync_read_state
    UNION ALL SELECT 'sync_snapshot', count(*) FROM sync_snapshot
    UNION ALL SELECT 'sync_stream', count(*) FROM sync_stream
) AS fixture_cardinality
ORDER BY fixture_name;
