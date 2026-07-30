#!/usr/bin/env bash

set -Eeuo pipefail

reset_sentinel_sha256="${LIFE_AGENT_TEST_RESET_SENTINEL_SHA256:-}"
if [[ ! "$reset_sentinel_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "invalid PostgreSQL test reset sentinel digest" >&2
  exit 1
fi

psql \
  --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=reset_sentinel_sha256="$reset_sentinel_sha256" <<'SQL'
CREATE SCHEMA life_agent_test_guard;
REVOKE ALL ON SCHEMA life_agent_test_guard FROM PUBLIC;

CREATE TABLE life_agent_test_guard.reset_authorization (
    token_sha256 character(64) PRIMARY KEY,
    expected_database name NOT NULL,
    expected_role name NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT reset_authorization_token_sha256_format
        CHECK (token_sha256 ~ '^[0-9a-f]{64}$')
);
REVOKE ALL ON TABLE life_agent_test_guard.reset_authorization FROM PUBLIC;

INSERT INTO life_agent_test_guard.reset_authorization (
    token_sha256,
    expected_database,
    expected_role
)
VALUES (
    :'reset_sentinel_sha256',
    current_database(),
    current_user
);
SQL
