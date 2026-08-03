#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $- == *x* ]]; then
  printf '%s\n' "refusing to run maintenance tooling with shell tracing enabled" >&2
  exit 1
fi

umask 077

maintenance_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# This is tracked, nonsecret configuration owned by the maintenance image.
# shellcheck source=../maintenance.conf
source "$maintenance_root/maintenance.conf"

readonly maintenance_root
readonly LIFE_AGENT_MAINTENANCE_FORMAT_VERSION
readonly LIFE_AGENT_EXPECTED_POSTGRES_MAJOR
readonly LIFE_AGENT_EXPECTED_ALEMBIC_REVISION
readonly LIFE_AGENT_FIXTURE_INVARIANT_ID
readonly LIFE_AGENT_ARCHIVE_FILE
readonly LIFE_AGENT_CHECKSUM_FILE
readonly LIFE_AGENT_MANIFEST_FILE

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

require_regular_file() {
  local path="$1"
  local label="$2"
  [[ -f "$path" && ! -L "$path" ]] || fail "$label must be a regular non-symlink file"
}

require_private_file() {
  local path="$1"
  local label="$2"
  local mode

  require_regular_file "$path" "$label"
  mode="$(stat --format='%a' -- "$path")"
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] || fail "$label permissions are unreadable"
  (( (8#$mode & 077) == 0 )) || fail "$label must not be accessible by group or others"
}

configure_pgpass_file() {
  local path="$1"

  require_private_file "$path" "PostgreSQL passfile"
  (( $(stat --format='%s' -- "$path") >= 1 )) \
    || fail "PostgreSQL passfile is empty"
  (( $(stat --format='%s' -- "$path") <= 4096 )) \
    || fail "PostgreSQL passfile is unexpectedly large"

  unset PGPASSWORD
  export PGPASSFILE="$path"
}

validate_postgres_identifier() {
  local value="$1"
  local label="$2"
  [[ "$value" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] \
    || fail "$label must be a lowercase unquoted PostgreSQL identifier"
}

configure_postgres_connection() {
  local host="$1"
  local port="$2"
  local user="$3"
  local sslmode="$4"
  local ssl_root_cert_file="${5:-}"

  [[ -n "$host" && "$host" != -* && "$host" != *$'\n'* ]] \
    || fail "PostgreSQL host is invalid"
  [[ "$port" =~ ^[0-9]{1,5}$ ]] \
    && (( 10#$port >= 1 && 10#$port <= 65535 )) \
    || fail "PostgreSQL port is invalid"
  validate_postgres_identifier "$user" "PostgreSQL user"
  case "$sslmode" in
    disable|require|verify-ca|verify-full) ;;
    *) fail "PostgreSQL sslmode must be disable, require, verify-ca, or verify-full" ;;
  esac

  export PGHOST="$host"
  export PGPORT="$port"
  export PGUSER="$user"
  export PGSSLMODE="$sslmode"
  export PGCONNECT_TIMEOUT=10
  if [[ -n "$ssl_root_cert_file" ]]; then
    require_regular_file "$ssl_root_cert_file" "PostgreSQL TLS root certificate"
    export PGSSLROOTCERT="$ssl_root_cert_file"
  elif [[ "$sslmode" == "verify-ca" || "$sslmode" == "verify-full" ]]; then
    fail "verify-ca and verify-full require --ssl-root-cert-file"
  fi
}

canonical_key_generations() {
  local path="$1"

  require_regular_file "$path" "key-generation inventory"
  jq --exit-status --compact-output '
    def valid_generation_array:
      type == "array"
      and length >= 1
      and all(.[];
        type == "number"
        and floor == .
        and . >= 1
        and . <= 2147483647
      )
      and length == (unique | length);
    select(
      type == "object"
      and keys == [
        "access",
        "cursor",
        "enrollment",
        "refresh",
        "replay_encryption",
        "replay_fingerprint"
      ]
      and all(.[]; valid_generation_array)
    )
    | with_entries(.value |= sort)
  ' "$path" || fail "key-generation inventory has an invalid shape"
}

database_probe_for_snapshot() {
  local database="$1"
  local snapshot_id="$2"

  {
    printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;"
    printf '%s\n' "SET TRANSACTION SNAPSHOT :'life_agent_snapshot';"
    cat "$maintenance_root/sql/database_probe.sql"
    printf '%s\n' "COMMIT;"
  } | psql \
    --no-psqlrc \
    --quiet \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --set=life_agent_snapshot="$snapshot_id" \
    --dbname="$database"
}

database_probe_current() {
  local database="$1"

  psql \
    --no-psqlrc \
    --quiet \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --dbname="$database" \
    --file="$maintenance_root/sql/database_probe.sql"
}

fixture_invariant_for_snapshot() {
  local database="$1"
  local snapshot_id="$2"

  {
    printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;"
    printf '%s\n' "SET TRANSACTION SNAPSHOT :'life_agent_snapshot';"
    cat "$maintenance_root/sql/fixture_invariant.sql"
    printf '%s\n' "COMMIT;"
  } | psql \
    --no-psqlrc \
    --quiet \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --set=life_agent_snapshot="$snapshot_id" \
    --dbname="$database" \
    | sha256sum \
    | cut --delimiter=' ' --fields=1
}

fixture_invariant_current() {
  local database="$1"

  psql \
    --no-psqlrc \
    --quiet \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --dbname="$database" \
    --file="$maintenance_root/sql/fixture_invariant.sql" \
    | sha256sum \
    | cut --delimiter=' ' --fields=1
}

validate_database_probe() {
  local probe_json="$1"
  local configured_key_generations="$2"
  local expected_server_version_num="${3:-}"
  local server_version_num
  local required_key_generations

  jq --exit-status --null-input \
    --argjson probe "$probe_json" '
      $probe
      | type == "object"
      and keys == ["alembic_revision", "key_generations", "server_version_num"]
      and (.alembic_revision | type == "string")
      and (.server_version_num | type == "string" and test("^[0-9]{6}$"))
      and (.key_generations | type == "object")
    ' >/dev/null || fail "database probe returned an invalid result"

  [[ "$(jq --raw-output '.alembic_revision' <<<"$probe_json")" \
      == "$LIFE_AGENT_EXPECTED_ALEMBIC_REVISION" ]] \
    || fail "database Alembic revision is not the exact expected revision"

  server_version_num="$(jq --raw-output '.server_version_num' <<<"$probe_json")"
  [[ "${server_version_num:0:2}" == "$LIFE_AGENT_EXPECTED_POSTGRES_MAJOR" ]] \
    || fail "database server is not the exact supported PostgreSQL major"
  if [[ -n "$expected_server_version_num" ]]; then
    [[ "$server_version_num" == "$expected_server_version_num" ]] \
      || fail "restore PostgreSQL server version differs from the backup server"
  fi

  required_key_generations="$(jq --compact-output '.key_generations' <<<"$probe_json")"
  jq --exit-status --null-input \
    --argjson required "$required_key_generations" \
    --argjson configured "$configured_key_generations" '
      all(
        [
          "access",
          "cursor",
          "enrollment",
          "refresh",
          "replay_encryption",
          "replay_fingerprint"
        ][];
        . as $domain
        | (($required[$domain] - $configured[$domain]) | length) == 0
      )
    ' >/dev/null \
    || fail "configured key generations cannot satisfy database readiness"

  printf '%s\n' "$server_version_num"
}

clear_postgres_authentication() {
  unset PGPASSWORD PGPASSFILE
}
