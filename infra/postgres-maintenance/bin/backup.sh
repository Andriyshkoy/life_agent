#!/usr/bin/env bash

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage() {
  printf '%s\n' \
    "usage: backup --host HOST --port PORT --user USER --database DATABASE" \
    "  --sslmode MODE [--ssl-root-cert-file FILE] --pgpass-file SECRET_FILE" \
    "  --age-recipients-file FILE --key-generations-file FILE" \
    "  --output-directory DIRECTORY"
}

host=""
port=""
user=""
database=""
sslmode=""
ssl_root_cert_file=""
pgpass_file=""
age_recipients_file=""
key_generations_file=""
output_directory=""

while (( $# > 0 )); do
  case "$1" in
    --host) host="${2:-}"; shift 2 ;;
    --port) port="${2:-}"; shift 2 ;;
    --user) user="${2:-}"; shift 2 ;;
    --database) database="${2:-}"; shift 2 ;;
    --sslmode) sslmode="${2:-}"; shift 2 ;;
    --ssl-root-cert-file) ssl_root_cert_file="${2:-}"; shift 2 ;;
    --pgpass-file) pgpass_file="${2:-}"; shift 2 ;;
    --age-recipients-file) age_recipients_file="${2:-}"; shift 2 ;;
    --key-generations-file) key_generations_file="${2:-}"; shift 2 ;;
    --output-directory) output_directory="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail "unknown or incomplete backup option" ;;
  esac
done

[[ -n "$host" && -n "$port" && -n "$user" && -n "$database" ]] \
  || fail "PostgreSQL connection options are required"
[[ -n "$sslmode" && -n "$pgpass_file" ]] \
  || fail "PostgreSQL transport and passfile options are required"
[[ -n "$age_recipients_file" && -n "$key_generations_file" ]] \
  || fail "encryption and key-generation inputs are required"
[[ -n "$output_directory" ]] || fail "backup output directory is required"

for command_name in age cat cut date jq mkdir mktemp mv od pg_dump psql sha256sum stat sync tr; do
  require_command "$command_name"
done

validate_postgres_identifier "$database" "source database"
require_regular_file "$age_recipients_file" "age recipients file"
configure_postgres_connection "$host" "$port" "$user" "$sslmode" "$ssl_root_cert_file"
configure_pgpass_file "$pgpass_file"

configured_key_generations="$(canonical_key_generations "$key_generations_file")"
mkdir -p -- "$output_directory"
[[ -d "$output_directory" && ! -L "$output_directory" ]] \
  || fail "backup output directory must be a non-symlink directory"

created_at_utc="$(date --utc +'%Y-%m-%dT%H:%M:%SZ')"
compact_timestamp="$(date --utc +'%Y%m%dT%H%M%SZ')"
random_suffix="$(od --address-radix=n --read-bytes=6 --format=x1 /dev/urandom | tr -d ' \n')"
[[ "$random_suffix" =~ ^[0-9a-f]{12}$ ]] || fail "failed to create backup identifier"
backup_id="life-agent-${compact_timestamp}-${random_suffix}"
final_directory="$output_directory/$backup_id"
[[ ! -e "$final_directory" ]] || fail "backup identifier already exists"
staging_directory="$(mktemp --directory "$output_directory/.${backup_id}.staging.XXXXXX")"

snapshot_pid=""
snapshot_read_fd=""
snapshot_write_fd=""
snapshot_id=""

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if [[ -n "$snapshot_write_fd" ]]; then
    printf '%s\n' "ROLLBACK;" "\\quit" >&"$snapshot_write_fd"
    exec {snapshot_write_fd}>&-
  fi
  if [[ -n "$snapshot_read_fd" ]]; then
    exec {snapshot_read_fd}<&-
  fi
  if [[ -n "$snapshot_pid" ]]; then
    wait "$snapshot_pid" >/dev/null 2>&1
  fi
  if [[ -n "${staging_directory:-}" && -d "$staging_directory" ]]; then
    rm -rf -- "$staging_directory"
  fi
  clear_postgres_authentication
  exit "$status"
}
trap cleanup EXIT

coproc LIFE_AGENT_SNAPSHOT_HOLDER {
  psql \
    --no-psqlrc \
    --quiet \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --dbname="$database"
}
snapshot_pid="$LIFE_AGENT_SNAPSHOT_HOLDER_PID"
snapshot_read_fd="${LIFE_AGENT_SNAPSHOT_HOLDER[0]}"
snapshot_write_fd="${LIFE_AGENT_SNAPSHOT_HOLDER[1]}"
printf '%s\n' \
  "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" \
  "SELECT pg_export_snapshot();" \
  >&"$snapshot_write_fd"
IFS= read -r snapshot_id <&"$snapshot_read_fd" \
  || fail "failed to acquire a PostgreSQL export snapshot"
[[ "$snapshot_id" =~ ^[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+$ ]] \
  || fail "PostgreSQL returned an invalid export snapshot identifier"

database_probe="$(database_probe_for_snapshot "$database" "$snapshot_id")"
server_version_num="$(
  validate_database_probe "$database_probe" "$configured_key_generations"
)"
fixture_invariant_sha256="$(
  fixture_invariant_for_snapshot "$database" "$snapshot_id"
)"
[[ "$fixture_invariant_sha256" =~ ^[0-9a-f]{64}$ ]] \
  || fail "failed to calculate the fixture invariant"

archive_path="$staging_directory/$LIFE_AGENT_ARCHIVE_FILE"
pg_dump \
  --format=custom \
  --no-owner \
  --no-privileges \
  --snapshot="$snapshot_id" \
  --dbname="$database" \
  | age \
      --encrypt \
      --recipients-file "$age_recipients_file" \
      >"$archive_path"

printf '%s\n' "ROLLBACK;" "\\quit" >&"$snapshot_write_fd"
exec {snapshot_write_fd}>&-
snapshot_write_fd=""
exec {snapshot_read_fd}<&-
snapshot_read_fd=""
wait "$snapshot_pid"
snapshot_pid=""

archive_sha256="$(sha256sum "$archive_path" | cut --delimiter=' ' --fields=1)"
archive_bytes="$(stat --format='%s' -- "$archive_path")"
[[ "$archive_sha256" =~ ^[0-9a-f]{64}$ && "$archive_bytes" =~ ^[1-9][0-9]*$ ]] \
  || fail "failed to characterize encrypted backup archive"

printf '%s  %s\n' "$archive_sha256" "$LIFE_AGENT_ARCHIVE_FILE" \
  >"$staging_directory/$LIFE_AGENT_CHECKSUM_FILE"

jq --null-input \
  --argjson format_version "$LIFE_AGENT_MAINTENANCE_FORMAT_VERSION" \
  --arg backup_id "$backup_id" \
  --arg created_at_utc "$created_at_utc" \
  --arg source_database "$database" \
  --arg server_version_num "$server_version_num" \
  --arg alembic_revision "$LIFE_AGENT_EXPECTED_ALEMBIC_REVISION" \
  --arg archive_file "$LIFE_AGENT_ARCHIVE_FILE" \
  --arg archive_sha256 "$archive_sha256" \
  --argjson archive_bytes "$archive_bytes" \
  --argjson key_generations "$configured_key_generations" \
  --arg invariant_id "$LIFE_AGENT_FIXTURE_INVARIANT_ID" \
  --arg invariant_sha256 "$fixture_invariant_sha256" '
    {
      format_version: $format_version,
      backup_id: $backup_id,
      created_at_utc: $created_at_utc,
      database: {
        source_name: $source_database,
        server_version_num: $server_version_num,
        alembic_revision: $alembic_revision
      },
      archive: {
        file: $archive_file,
        format: "postgresql_custom_age",
        sha256: $archive_sha256,
        bytes: $archive_bytes
      },
      key_generations: $key_generations,
      fixture_invariant: {
        id: $invariant_id,
        algorithm: "sha256",
        sha256: $invariant_sha256
      }
    }
  ' >"$staging_directory/$LIFE_AGENT_MANIFEST_FILE"

sync --file-system \
  "$archive_path" \
  "$staging_directory/$LIFE_AGENT_CHECKSUM_FILE" \
  "$staging_directory/$LIFE_AGENT_MANIFEST_FILE"
mv -- "$staging_directory" "$final_directory"
staging_directory=""
sync --file-system "$output_directory"

clear_postgres_authentication
trap - EXIT
printf '%s\n' "$final_directory"
