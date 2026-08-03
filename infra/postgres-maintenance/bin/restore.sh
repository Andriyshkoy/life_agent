#!/usr/bin/env bash

set -Eeuo pipefail

# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage() {
  printf '%s\n' \
    "usage: restore --host HOST --port PORT --user USER" \
    "  --maintenance-database DATABASE --restore-database NEW_DATABASE" \
    "  --confirm-restore-database NEW_DATABASE --sslmode MODE" \
    "  [--ssl-root-cert-file FILE] --pgpass-file SECRET_FILE" \
    "  --age-identity-file SECRET_FILE --bundle-directory DIRECTORY"
}

host=""
port=""
user=""
maintenance_database=""
restore_database=""
confirm_restore_database=""
sslmode=""
ssl_root_cert_file=""
pgpass_file=""
age_identity_file=""
bundle_directory=""

while (( $# > 0 )); do
  case "$1" in
    --host) host="${2:-}"; shift 2 ;;
    --port) port="${2:-}"; shift 2 ;;
    --user) user="${2:-}"; shift 2 ;;
    --maintenance-database) maintenance_database="${2:-}"; shift 2 ;;
    --restore-database) restore_database="${2:-}"; shift 2 ;;
    --confirm-restore-database) confirm_restore_database="${2:-}"; shift 2 ;;
    --sslmode) sslmode="${2:-}"; shift 2 ;;
    --ssl-root-cert-file) ssl_root_cert_file="${2:-}"; shift 2 ;;
    --pgpass-file) pgpass_file="${2:-}"; shift 2 ;;
    --age-identity-file) age_identity_file="${2:-}"; shift 2 ;;
    --bundle-directory) bundle_directory="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; fail "unknown or incomplete restore option" ;;
  esac
done

[[ -n "$host" && -n "$port" && -n "$user" ]] \
  || fail "PostgreSQL connection options are required"
[[ -n "$maintenance_database" && -n "$restore_database" ]] \
  || fail "maintenance and restore database names are required"
[[ "$confirm_restore_database" == "$restore_database" ]] \
  || fail "restore database confirmation does not match"
[[ -n "$sslmode" && -n "$pgpass_file" ]] \
  || fail "PostgreSQL transport and passfile options are required"
[[ -n "$age_identity_file" && -n "$bundle_directory" ]] \
  || fail "age identity and backup bundle inputs are required"

for command_name in age basename createdb cut jq pg_restore psql sha256sum stat wc; do
  require_command "$command_name"
done

validate_postgres_identifier "$maintenance_database" "maintenance database"
validate_postgres_identifier "$restore_database" "restore database"
case "$restore_database" in
  postgres|template0|template1) fail "restore target may not be a system database" ;;
esac
[[ "$restore_database" != "$maintenance_database" ]] \
  || fail "restore target must differ from the maintenance database"
[[ -d "$bundle_directory" && ! -L "$bundle_directory" ]] \
  || fail "backup bundle must be a non-symlink directory"
require_private_file "$age_identity_file" "age identity file"
configure_postgres_connection "$host" "$port" "$user" "$sslmode" "$ssl_root_cert_file"
configure_pgpass_file "$pgpass_file"
trap clear_postgres_authentication EXIT

manifest_path="$bundle_directory/$LIFE_AGENT_MANIFEST_FILE"
archive_path="$bundle_directory/$LIFE_AGENT_ARCHIVE_FILE"
checksum_path="$bundle_directory/$LIFE_AGENT_CHECKSUM_FILE"
require_regular_file "$manifest_path" "backup manifest"
require_regular_file "$archive_path" "encrypted backup archive"
require_regular_file "$checksum_path" "backup checksum"

manifest="$(jq --compact-output '.' "$manifest_path")" \
  || fail "backup manifest is not valid JSON"
jq --exit-status --null-input \
  --argjson manifest "$manifest" \
  --argjson format_version "$LIFE_AGENT_MAINTENANCE_FORMAT_VERSION" \
  --arg expected_revision "$LIFE_AGENT_EXPECTED_ALEMBIC_REVISION" \
  --arg archive_file "$LIFE_AGENT_ARCHIVE_FILE" \
  --arg invariant_id "$LIFE_AGENT_FIXTURE_INVARIANT_ID" '
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
    $manifest
    | type == "object"
    and keys == [
      "archive",
      "backup_id",
      "created_at_utc",
      "database",
      "fixture_invariant",
      "format_version",
      "key_generations"
    ]
    and .format_version == $format_version
    and (.backup_id | type == "string" and test("^life-agent-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}$"))
    and (.created_at_utc | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    and (.database | type == "object" and keys == ["alembic_revision", "server_version_num", "source_name"])
    and (.database.source_name | type == "string" and test("^[a-z_][a-z0-9_]{0,62}$"))
    and .database.alembic_revision == $expected_revision
    and (.database.server_version_num | type == "string" and test("^[0-9]{6}$"))
    and (.archive | type == "object" and keys == ["bytes", "file", "format", "sha256"])
    and .archive.file == $archive_file
    and .archive.format == "postgresql_custom_age"
    and (.archive.sha256 | type == "string" and test("^[0-9a-f]{64}$"))
    and (.archive.bytes | type == "number" and floor == . and . >= 1)
    and (
      .key_generations
      | type == "object"
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
    and (
      .fixture_invariant
      | type == "object"
      and keys == ["algorithm", "id", "sha256"]
      and .id == $invariant_id
      and .algorithm == "sha256"
      and (.sha256 | type == "string" and test("^[0-9a-f]{64}$"))
    )
  ' >/dev/null || fail "backup manifest violates the maintenance contract"

backup_id="$(jq --raw-output '.backup_id' <<<"$manifest")"
[[ "$(basename -- "$bundle_directory")" == "$backup_id" ]] \
  || fail "backup bundle directory does not match its manifest identifier"
source_database="$(jq --raw-output '.database.source_name' <<<"$manifest")"
[[ "$restore_database" != "$source_database" ]] \
  || fail "restore target must be separate from the backed-up source database"

expected_archive_sha256="$(jq --raw-output '.archive.sha256' <<<"$manifest")"
expected_archive_bytes="$(jq --raw-output '.archive.bytes' <<<"$manifest")"
actual_archive_sha256="$(sha256sum "$archive_path" | cut --delimiter=' ' --fields=1)"
actual_archive_bytes="$(stat --format='%s' -- "$archive_path")"
[[ "$actual_archive_sha256" == "$expected_archive_sha256" ]] \
  || fail "encrypted backup checksum does not match its manifest"
[[ "$actual_archive_bytes" == "$expected_archive_bytes" ]] \
  || fail "encrypted backup size does not match its manifest"
checksum_line="$(<"$checksum_path")"
[[ "$(wc --lines <"$checksum_path")" == "1" ]] \
  || fail "backup checksum file must contain exactly one record"
[[ "$checksum_line" == "$expected_archive_sha256  $LIFE_AGENT_ARCHIVE_FILE" ]] \
  || fail "backup checksum record does not match its manifest"

age --decrypt --identity "$age_identity_file" "$archive_path" \
  | pg_restore --list >/dev/null

cluster_probe="$(
  psql \
    --no-psqlrc \
    --quiet \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --dbname="$maintenance_database" \
    --command="SHOW server_version_num"
)"
[[ "$cluster_probe" == "$(jq --raw-output '.database.server_version_num' <<<"$manifest")" ]] \
  || fail "restore PostgreSQL server version differs from the backup server"

target_exists="$(
  printf '%s\n' "SELECT 1 FROM pg_database WHERE datname = :'restore_database';" \
    | psql \
        --no-psqlrc \
        --quiet \
        --tuples-only \
        --no-align \
        --set=ON_ERROR_STOP=1 \
        --set=restore_database="$restore_database" \
        --dbname="$maintenance_database"
)"
[[ -z "$target_exists" ]] \
  || fail "restore target already exists; this tool only restores into a clean database"

createdb \
  --maintenance-db="$maintenance_database" \
  --owner="$user" \
  "$restore_database"

age --decrypt --identity "$age_identity_file" "$archive_path" \
  | pg_restore \
      --exit-on-error \
      --single-transaction \
      --no-owner \
      --no-privileges \
      --dbname="$restore_database"

configured_key_generations="$(jq --compact-output '.key_generations' <<<"$manifest")"
restore_probe="$(database_probe_current "$restore_database")"
validate_database_probe \
  "$restore_probe" \
  "$configured_key_generations" \
  "$(jq --raw-output '.database.server_version_num' <<<"$manifest")" \
  >/dev/null

expected_fixture_invariant="$(jq --raw-output '.fixture_invariant.sha256' <<<"$manifest")"
actual_fixture_invariant="$(fixture_invariant_current "$restore_database")"
[[ "$actual_fixture_invariant" == "$expected_fixture_invariant" ]] \
  || fail "restored fixture invariant does not match the backup snapshot"

clear_postgres_authentication
trap - EXIT
printf 'restore verified: %s\n' "$restore_database"
