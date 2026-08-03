WITH required_key_epochs(domain, generation) AS (
    SELECT 'access', access_key_generation
    FROM credential_generation
    WHERE access_expires_at >= CURRENT_TIMESTAMP
    UNION
    SELECT 'refresh', refresh_key_generation
    FROM credential_generation
    WHERE retained_until >= CURRENT_TIMESTAMP
    UNION
    SELECT 'enrollment', code_key_generation
    FROM enrollment_grant
    WHERE status = 'issued'
      AND expires_at >= CURRENT_TIMESTAMP
    UNION
    SELECT 'replay_fingerprint', fingerprint_key_generation
    FROM http_replay
    UNION
    SELECT 'replay_encryption', response_encryption_key_generation
    FROM http_replay
    UNION
    SELECT 'cursor', signing_key_generation
    FROM sync_cursor
),
key_epochs AS (
    SELECT
        domain,
        jsonb_agg(generation ORDER BY generation) AS generations
    FROM required_key_epochs
    GROUP BY domain
),
revision AS (
    SELECT
        CASE
            WHEN count(*) = 1 THEN max(version_num)
            ELSE NULL
        END AS version_num
    FROM alembic_version
)
SELECT jsonb_build_object(
    'alembic_revision', (SELECT version_num FROM revision),
    'key_generations', jsonb_build_object(
        'access', coalesce(
            (SELECT generations FROM key_epochs WHERE domain = 'access'),
            '[]'::jsonb
        ),
        'cursor', coalesce(
            (SELECT generations FROM key_epochs WHERE domain = 'cursor'),
            '[]'::jsonb
        ),
        'enrollment', coalesce(
            (SELECT generations FROM key_epochs WHERE domain = 'enrollment'),
            '[]'::jsonb
        ),
        'refresh', coalesce(
            (SELECT generations FROM key_epochs WHERE domain = 'refresh'),
            '[]'::jsonb
        ),
        'replay_encryption', coalesce(
            (SELECT generations FROM key_epochs WHERE domain = 'replay_encryption'),
            '[]'::jsonb
        ),
        'replay_fingerprint', coalesce(
            (SELECT generations FROM key_epochs WHERE domain = 'replay_fingerprint'),
            '[]'::jsonb
        )
    ),
    'server_version_num', current_setting('server_version_num')
)::text;
