#!/usr/bin/env bash

set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_project="life-agent-backend-test-$$"
compose_file="$backend_root/compose.test.yaml"

reset_sentinel="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
if [[ ! "$reset_sentinel" =~ ^[0-9a-f]{64}$ ]]; then
  echo "failed to generate the PostgreSQL test reset sentinel" >&2
  exit 1
fi
reset_sentinel_sha256="$(printf '%s' "$reset_sentinel" | sha256sum)"
reset_sentinel_sha256="${reset_sentinel_sha256%% *}"
if [[ ! "$reset_sentinel_sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "failed to hash the PostgreSQL test reset sentinel" >&2
  exit 1
fi

export LIFE_AGENT_TEST_RESET_SENTINEL="$reset_sentinel"
export LIFE_AGENT_TEST_RESET_SENTINEL_SHA256="$reset_sentinel_sha256"

cleanup() {
  docker compose \
    --project-name "$compose_project" \
    --file "$compose_file" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose \
  --project-name "$compose_project" \
  --file "$compose_file" \
  up --detach --quiet-pull --wait --wait-timeout 60

published_endpoint="$(
  docker compose \
    --project-name "$compose_project" \
    --file "$compose_file" \
    port postgres 5432
)"
postgres_port="${published_endpoint##*:}"
if [[ ! "$postgres_port" =~ ^[0-9]{1,5}$ ]]; then
  echo "failed to resolve the ephemeral PostgreSQL port" >&2
  exit 1
fi

export LIFE_AGENT_RUN_POSTGRES_INTEGRATION=1
export LIFE_AGENT_TEST_DATABASE_URL="postgresql+asyncpg://life_agent_test:life-agent-hermetic-test-password@127.0.0.1:${postgres_port}/life_agent_test"

cd "$backend_root"
uv run pytest -q \
  tests/test_postgres_integration.py \
  tests/test_sync_push_postgres.py
