#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
maintenance_root="$repository_root/infra/postgres-maintenance"
test_root="$(mktemp --directory /tmp/life-agent-maintenance-integration.XXXXXX)"
network_name="life-agent-maintenance-test-$$"
postgres_name="life-agent-maintenance-postgres-$$"
maintenance_image="life-agent-postgres-maintenance:integration"
backend_image="life-agent-backend:maintenance-integration"
test_password="synthetic-maintenance-password"

cleanup() {
  local status=$?
  trap - EXIT
  docker rm --force "$postgres_name" >/dev/null 2>&1 || true
  docker network rm "$network_name" >/dev/null 2>&1 || true
  if [[ "$test_root" == /tmp/life-agent-maintenance-integration.* ]]; then
    rm -rf -- "$test_root"
  fi
  exit "$status"
}
trap cleanup EXIT

docker build --quiet --tag "$maintenance_image" "$maintenance_root" >/dev/null
docker build --quiet --tag "$backend_image" "$repository_root/backend" >/dev/null
docker network create "$network_name" >/dev/null
docker run \
  --detach \
  --name "$postgres_name" \
  --network "$network_name" \
  --env POSTGRES_DB=life_agent_source \
  --env POSTGRES_USER=life_agent_admin \
  --env POSTGRES_PASSWORD="$test_password" \
  --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=512m \
  postgres:17.9-bookworm@sha256:47f917f7409eacd22fc5dfb1dee634e1b55cf0c01d1a7eb701be2227a03e0641 \
  >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$postgres_name" \
      pg_isready --username=life_agent_admin --dbname=life_agent_source \
      >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$postgres_name" \
  pg_isready --username=life_agent_admin --dbname=life_agent_source \
  >/dev/null

docker run \
  --rm \
  --network "$network_name" \
  --env LIFE_AGENT_DATABASE_URL="postgresql+asyncpg://life_agent_admin:${test_password}@${postgres_name}:5432/life_agent_source" \
  --entrypoint alembic \
  "$backend_image" \
  upgrade head

docker exec \
  --env PGPASSWORD="$test_password" \
  --interactive \
  "$postgres_name" \
  psql \
    --no-psqlrc \
    --set=ON_ERROR_STOP=1 \
    --username=life_agent_admin \
    --dbname=life_agent_source \
    >/dev/null <<'SQL'
INSERT INTO person (person_id, subject_id)
VALUES (
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001'
);
INSERT INTO credential_family (
    credential_family_id,
    person_id,
    status,
    family_expires_at,
    tombstone_until
)
VALUES (
    '30000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'reserved',
    CURRENT_TIMESTAMP + INTERVAL '2 days',
    CURRENT_TIMESTAMP + INTERVAL '3 days'
);
INSERT INTO enrollment_grant (
    enrollment_grant_id,
    person_id,
    credential_family_id,
    code_hmac,
    code_key_generation,
    status,
    expires_at
)
VALUES (
    '40000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    decode(repeat('01', 32), 'hex'),
    1,
    'issued',
    CURRENT_TIMESTAMP + INTERVAL '1 day'
);
SQL

printf '%s:%s:%s:%s:%s\n' \
  "$postgres_name" \
  5432 \
  '*' \
  life_agent_admin \
  "$test_password" \
  >"$test_root/postgres.pgpass"
cp "$maintenance_root/tests/key-generations.json" "$test_root/key-generations.json"
mkdir "$test_root/backups"

docker run \
  --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$test_root:/work" \
  --entrypoint age-keygen \
  "$maintenance_image" \
  --output /work/age-identity.txt
docker run \
  --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$test_root:/work:ro" \
  --entrypoint age-keygen \
  "$maintenance_image" \
  --y /work/age-identity.txt \
  >"$test_root/age-recipient.txt"

bundle_directory="$(
  docker run \
    --rm \
    --network "$network_name" \
    --user "$(id -u):$(id -g)" \
    --volume "$test_root:/work" \
    "$maintenance_image" \
    backup \
      --host "$postgres_name" \
      --port 5432 \
      --user life_agent_admin \
      --database life_agent_source \
      --sslmode disable \
      --pgpass-file /work/postgres.pgpass \
      --age-recipients-file /work/age-recipient.txt \
      --key-generations-file /work/key-generations.json \
      --output-directory /work/backups
)"
bundle_name="$(basename -- "$bundle_directory")"
host_bundle_directory="$test_root/backups/$bundle_name"
[[ -f "$host_bundle_directory/database.dump.age" ]]
[[ -f "$host_bundle_directory/database.dump.age.sha256" ]]
[[ -f "$host_bundle_directory/manifest.json" ]]
[[ -z "$(find "$test_root" -type f -name '*.dump' -print -quit)" ]]
if grep --recursive --fixed-strings --quiet "$test_password" "$host_bundle_directory"; then
  printf '%s\n' "backup bundle contains the test password" >&2
  exit 1
fi
if grep --recursive --fixed-strings --quiet "AGE-SECRET-KEY" "$host_bundle_directory"; then
  printf '%s\n' "backup bundle contains the age identity" >&2
  exit 1
fi

docker run \
  --rm \
  --network "$network_name" \
  --user "$(id -u):$(id -g)" \
  --volume "$test_root:/work" \
  "$maintenance_image" \
  restore \
    --host "$postgres_name" \
    --port 5432 \
    --user life_agent_admin \
    --maintenance-database postgres \
    --restore-database life_agent_restore_test \
    --confirm-restore-database life_agent_restore_test \
    --sslmode disable \
    --pgpass-file /work/postgres.pgpass \
    --age-identity-file /work/age-identity.txt \
    --bundle-directory "/work/backups/$bundle_name"

restored_fixture="$(
  docker exec \
    --env PGPASSWORD="$test_password" \
    "$postgres_name" \
    psql \
      --no-psqlrc \
      --quiet \
      --tuples-only \
      --no-align \
      --set=ON_ERROR_STOP=1 \
      --username=life_agent_admin \
      --dbname=life_agent_restore_test \
      --command="SELECT count(*) FROM enrollment_grant WHERE code_key_generation = 1"
)"
[[ "$restored_fixture" == "1" ]]

if docker run \
  --rm \
  --network "$network_name" \
  --user "$(id -u):$(id -g)" \
  --volume "$test_root:/work" \
  "$maintenance_image" \
  restore \
    --host "$postgres_name" \
    --port 5432 \
    --user life_agent_admin \
    --maintenance-database postgres \
    --restore-database life_agent_restore_test \
    --confirm-restore-database life_agent_restore_test \
    --sslmode disable \
    --pgpass-file /work/postgres.pgpass \
    --age-identity-file /work/age-identity.txt \
    --bundle-directory "/work/backups/$bundle_name" \
    >"$test_root/existing-target.stdout" \
    2>"$test_root/existing-target.stderr"; then
  printf '%s\n' "restore unexpectedly accepted an existing database" >&2
  exit 1
fi
grep --fixed-strings --quiet \
  "restore target already exists" \
  "$test_root/existing-target.stderr"

mkdir "$test_root/tampered"
cp --archive "$host_bundle_directory" "$test_root/tampered/$bundle_name"
printf 'tamper' >>"$test_root/tampered/$bundle_name/database.dump.age"
if docker run \
  --rm \
  --network "$network_name" \
  --user "$(id -u):$(id -g)" \
  --volume "$test_root:/work" \
  "$maintenance_image" \
  restore \
    --host "$postgres_name" \
    --port 5432 \
    --user life_agent_admin \
    --maintenance-database postgres \
    --restore-database life_agent_restore_tampered \
    --confirm-restore-database life_agent_restore_tampered \
    --sslmode disable \
    --pgpass-file /work/postgres.pgpass \
    --age-identity-file /work/age-identity.txt \
    --bundle-directory "/work/tampered/$bundle_name" \
    >"$test_root/tampered.stdout" \
    2>"$test_root/tampered.stderr"; then
  printf '%s\n' "restore unexpectedly accepted a tampered archive" >&2
  exit 1
fi
grep --fixed-strings --quiet \
  "encrypted backup checksum does not match" \
  "$test_root/tampered.stderr"
tampered_target_exists="$(
  docker exec \
    --env PGPASSWORD="$test_password" \
    "$postgres_name" \
    psql \
      --no-psqlrc \
      --quiet \
      --tuples-only \
      --no-align \
      --set=ON_ERROR_STOP=1 \
      --username=life_agent_admin \
      --dbname=postgres \
      --command="SELECT count(*) FROM pg_database WHERE datname = 'life_agent_restore_tampered'"
)"
[[ "$tampered_target_exists" == "0" ]]

printf '%s\n' "PASS: encrypted backup and clean restore verified"
