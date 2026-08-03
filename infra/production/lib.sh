#!/usr/bin/env bash

readonly LIFE_AGENT_PRODUCTION_DIR="$(
    cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"
readonly LIFE_AGENT_COMPOSE_FILE="${LIFE_AGENT_PRODUCTION_DIR}/compose.production.yaml"
readonly LIFE_AGENT_PRODUCTION_ENV_FILE="${LIFE_AGENT_PRODUCTION_ENV_FILE:-/etc/life-agent/production.env}"
readonly LIFE_AGENT_DEPLOY_STATE_DIR="${LIFE_AGENT_DEPLOY_STATE_DIR:-/var/lib/life-agent/deploy}"
readonly LIFE_AGENT_BACKEND_IMAGE_PREFIX="ghcr.io/andriyshkoy/life_agent/backend@sha256:"
readonly LIFE_AGENT_ACCOUNT_DIRECTORY="$(getent passwd "${EUID}" | cut -d: -f6)"
readonly LIFE_AGENT_DOCKER_CONFIG_DIR="${LIFE_AGENT_ACCOUNT_DIRECTORY}/.docker"

life_agent_die() {
    echo "life-agent-deploy: $*" >&2
    return 1
}

life_agent_require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        life_agent_die "run through sudo"
    fi
}

life_agent_is_backend_image() {
    local candidate="${1:-}"
    if [[ "${candidate}" != "${LIFE_AGENT_BACKEND_IMAGE_PREFIX}"* ]]; then
        return 1
    fi
    local digest="${candidate#"${LIFE_AGENT_BACKEND_IMAGE_PREFIX}"}"
    [[ "${digest}" =~ ^[0-9a-f]{64}$ ]]
}

life_agent_is_schema_revision() {
    local candidate="${1:-}"
    [[ "${candidate}" == "absent" ]] ||
        [[ "${candidate}" =~ ^present:[0-9A-Za-z_.-]+(,[0-9A-Za-z_.-]+)*$ ]]
}

life_agent_can_auto_rollback() {
    local rollback_image="${1:-}"
    local before_revision="${2:-}"
    local observed_revision="${3:-}"
    [[ -n "${rollback_image}" ]] &&
        life_agent_is_backend_image "${rollback_image}" &&
        life_agent_is_schema_revision "${before_revision}" &&
        [[ "${observed_revision}" == "${before_revision}" ]]
}

life_agent_require_regular_file() {
    local path="$1"
    if [[ ! -f "${path}" || -L "${path}" ]]; then
        life_agent_die "expected a regular non-symlink file: ${path}"
    fi
}

life_agent_read_env_value() {
    local requested_name="$1"
    local candidate_name
    local candidate_value
    while IFS='=' read -r candidate_name candidate_value; do
        if [[ "${candidate_name}" == "${requested_name}" ]]; then
            printf '%s' "${candidate_value}"
            return 0
        fi
    done <"${LIFE_AGENT_PRODUCTION_ENV_FILE}"
    return 1
}

life_agent_validate_environment_file() {
    life_agent_require_regular_file "${LIFE_AGENT_PRODUCTION_ENV_FILE}"
    local owner_and_mode
    owner_and_mode="$(stat -c '%u:%a' -- "${LIFE_AGENT_PRODUCTION_ENV_FILE}")"
    if [[ "${owner_and_mode}" != "0:600" ]]; then
        life_agent_die "production.env must be root-owned mode 0600"
    fi

    local line
    local name
    local value
    local -A seen_names=()
    while IFS= read -r line || [[ -n "${line}" ]]; do
        [[ -z "${line}" || "${line}" == \#* ]] && continue
        if [[ ! "${line}" =~ ^([A-Z][A-Z0-9_]*)=([A-Za-z0-9_./:-]+)$ ]]; then
            life_agent_die "production.env contains a non-canonical assignment"
        fi
        name="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        if [[ -n "${seen_names[${name}]+set}" ]]; then
            life_agent_die "production.env contains a duplicate variable"
        fi
        seen_names["${name}"]=1
        case "${name}" in
            LIFE_AGENT_API_LOOPBACK_PORT | \
            LIFE_AGENT_LOG_LEVEL | \
            LIFE_AGENT_DB_POOL_SIZE | \
            LIFE_AGENT_DB_MAX_OVERFLOW | \
            LIFE_AGENT_DB_POOL_RECYCLE_SECONDS | \
            LIFE_AGENT_READINESS_TIMEOUT_SECONDS | \
            LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_GENERATION | \
            LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_GENERATION | \
            LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_GENERATION | \
            LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_GENERATION | \
            LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_GENERATION | \
            LIFE_AGENT_CURSOR_HMAC_KEY_GENERATION | \
            LIFE_AGENT_POSTGRES_OWNER_PASSWORD_SECRET_FILE | \
            LIFE_AGENT_POSTGRES_RUNTIME_PASSWORD_SECRET_FILE | \
            LIFE_AGENT_MIGRATION_DATABASE_URL_SECRET_FILE | \
            LIFE_AGENT_RUNTIME_DATABASE_URL_SECRET_FILE | \
            LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_SECRET_FILE | \
            LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_SECRET_FILE | \
            LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_SECRET_FILE | \
            LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_SECRET_FILE | \
            LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_SECRET_FILE | \
            LIFE_AGENT_CURSOR_HMAC_KEY_SECRET_FILE) ;;
            *) life_agent_die "production.env contains an unsupported variable" ;;
        esac
        if [[ "${name}" == *_SECRET_FILE && "${value}" != /* ]]; then
            life_agent_die "secret file paths must be absolute"
        fi
        case "${name}" in
            LIFE_AGENT_API_LOOPBACK_PORT)
                [[ "${value}" == "18080" ]] ||
                    life_agent_die "API loopback port must match the nginx boundary"
                ;;
            LIFE_AGENT_LOG_LEVEL)
                [[ "${value}" =~ ^(INFO|WARNING|ERROR|CRITICAL)$ ]] ||
                    life_agent_die "production log level is invalid"
                ;;
            *_GENERATION)
                [[ "${value}" =~ ^[1-9][0-9]{0,9}$ ]] ||
                    life_agent_die "key generations must be positive canonical integers"
                ;;
            *_SECRET_FILE)
                if [[ "${value}" == *'/../'* || "${value}" == */.. ]]; then
                    life_agent_die "secret file paths must not traverse parent directories"
                fi
                ;;
        esac
    done <"${LIFE_AGENT_PRODUCTION_ENV_FILE}"

    local required_name
    for required_name in \
        LIFE_AGENT_POSTGRES_OWNER_PASSWORD_SECRET_FILE \
        LIFE_AGENT_POSTGRES_RUNTIME_PASSWORD_SECRET_FILE \
        LIFE_AGENT_MIGRATION_DATABASE_URL_SECRET_FILE \
        LIFE_AGENT_RUNTIME_DATABASE_URL_SECRET_FILE \
        LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_SECRET_FILE \
        LIFE_AGENT_CURSOR_HMAC_KEY_SECRET_FILE; do
        if [[ -z "${seen_names[${required_name}]+set}" ]]; then
            life_agent_die "production.env is missing a required secret-file mapping"
        fi
    done

    local -A seen_secret_paths=()
    local secret_path
    for required_name in \
        LIFE_AGENT_POSTGRES_OWNER_PASSWORD_SECRET_FILE \
        LIFE_AGENT_POSTGRES_RUNTIME_PASSWORD_SECRET_FILE \
        LIFE_AGENT_MIGRATION_DATABASE_URL_SECRET_FILE \
        LIFE_AGENT_RUNTIME_DATABASE_URL_SECRET_FILE \
        LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_SECRET_FILE \
        LIFE_AGENT_CURSOR_HMAC_KEY_SECRET_FILE; do
        secret_path="$(life_agent_read_env_value "${required_name}")"
        if [[ -n "${seen_secret_paths[${secret_path}]+set}" ]]; then
            life_agent_die "each secret mapping must use a distinct file"
        fi
        seen_secret_paths["${secret_path}"]=1
    done
}

life_agent_validate_secret_file() {
    local path="$1"
    local expected_group="$2"
    life_agent_require_regular_file "${path}"
    local ownership_and_mode
    ownership_and_mode="$(stat -c '%u:%g:%a' -- "${path}")"
    if [[ "${ownership_and_mode}" != "0:${expected_group}:440" ]]; then
        life_agent_die "runtime secret files must be root-owned, group-scoped, mode 0440"
    fi
    if ! python3 - "${path}" <<'PY'
import pathlib
import sys

value = pathlib.Path(sys.argv[1]).read_bytes()
if value.endswith(b"\n"):
    value = value[:-1]
if not value or b"\n" in value or b"\r" in value or b"\0" in value:
    raise SystemExit(1)
PY
    then
        life_agent_die "runtime secret files must contain exactly one non-empty line"
    fi
}

life_agent_validate_secret_files() {
    local name
    local path
    for name in \
        LIFE_AGENT_MIGRATION_DATABASE_URL_SECRET_FILE \
        LIFE_AGENT_RUNTIME_DATABASE_URL_SECRET_FILE \
        LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_SECRET_FILE \
        LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_SECRET_FILE \
        LIFE_AGENT_CURSOR_HMAC_KEY_SECRET_FILE; do
        path="$(life_agent_read_env_value "${name}")"
        life_agent_validate_secret_file "${path}" 10001
    done
    for name in \
        LIFE_AGENT_POSTGRES_OWNER_PASSWORD_SECRET_FILE \
        LIFE_AGENT_POSTGRES_RUNTIME_PASSWORD_SECRET_FILE; do
        path="$(life_agent_read_env_value "${name}")"
        life_agent_validate_secret_file "${path}" 999
    done
}

life_agent_compose() {
    env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        DOCKER_CONFIG="${LIFE_AGENT_DOCKER_CONFIG_DIR}" \
        LIFE_AGENT_BACKEND_IMAGE="${LIFE_AGENT_BACKEND_IMAGE:?}" \
        /usr/bin/docker-compose \
        --project-directory "${LIFE_AGENT_PRODUCTION_DIR}" \
        --env-file "${LIFE_AGENT_PRODUCTION_ENV_FILE}" \
        --file "${LIFE_AGENT_COMPOSE_FILE}" \
        --profile operations \
        "$@"
}

life_agent_docker() {
    env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        DOCKER_CONFIG="${LIFE_AGENT_DOCKER_CONFIG_DIR}" \
        /usr/bin/docker "$@"
}

life_agent_prepare_runtime() {
    life_agent_require_root
    if [[ -z "${LIFE_AGENT_ACCOUNT_DIRECTORY}" ]] ||
        [[ ! -x /usr/bin/docker || ! -x /usr/bin/docker-compose ]]; then
        life_agent_die "required local Docker executables or account metadata are unavailable"
    fi
    life_agent_require_regular_file "${LIFE_AGENT_COMPOSE_FILE}"
    life_agent_validate_environment_file
    life_agent_validate_secret_files

    if [[ -L "${LIFE_AGENT_DEPLOY_STATE_DIR}" || (
        -e "${LIFE_AGENT_DEPLOY_STATE_DIR}" &&
        ! -d "${LIFE_AGENT_DEPLOY_STATE_DIR}"
    ) ]]; then
        life_agent_die "unsafe deployment state path"
    fi
    if [[ ! -e "${LIFE_AGENT_DEPLOY_STATE_DIR}" ]]; then
        install -d -o root -g root -m 0700 "${LIFE_AGENT_DEPLOY_STATE_DIR}"
    fi
    if [[
        "$(stat -c '%u:%g:%a' -- "${LIFE_AGENT_DEPLOY_STATE_DIR}")" != "0:0:700"
    ]]; then
        life_agent_die "deployment state directory must be root-owned mode 0700"
    fi

    LIFE_AGENT_DEPLOY_LOCK_FILE="${LIFE_AGENT_DEPLOY_STATE_DIR}/deploy.lock"
    export LIFE_AGENT_DEPLOY_LOCK_FILE
    if [[ -L "${LIFE_AGENT_DEPLOY_LOCK_FILE}" || (
        -e "${LIFE_AGENT_DEPLOY_LOCK_FILE}" &&
        ! -f "${LIFE_AGENT_DEPLOY_LOCK_FILE}"
    ) ]]; then
        life_agent_die "unsafe deployment lock path"
    fi
    if [[ ! -e "${LIFE_AGENT_DEPLOY_LOCK_FILE}" ]]; then
        install -T -o root -g root -m 0600 /dev/null "${LIFE_AGENT_DEPLOY_LOCK_FILE}"
    fi
    exec 9<>"${LIFE_AGENT_DEPLOY_LOCK_FILE}"
    if ! flock -n 9; then
        life_agent_die "another deployment operation is active"
    fi

    life_agent_compose config --quiet
}

life_agent_wait_for_health() {
    local service_name="$1"
    local timeout_seconds="${2:-90}"
    local container_id
    container_id="$(life_agent_compose ps --quiet "${service_name}")"
    if [[ -z "${container_id}" ]]; then
        return 1
    fi

    local deadline_epoch=$(( $(date +%s) + timeout_seconds ))
    local status
    while (( $(date +%s) < deadline_epoch )); do
        status="$(
            life_agent_docker inspect \
                --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
                "${container_id}" 2>/dev/null || true
        )"
        case "${status}" in
            healthy) return 0 ;;
            unhealthy | missing) return 1 ;;
        esac
        sleep 1
    done
    return 1
}

life_agent_schema_revision() {
    local table_present
    table_present="$(
        life_agent_compose exec -T postgres \
            psql \
            --no-psqlrc \
            --tuples-only \
            --no-align \
            --set ON_ERROR_STOP=1 \
            --username life_agent_owner \
            --dbname life_agent \
            --command \
            "SELECT CASE WHEN to_regclass('public.alembic_version') IS NULL THEN '0' ELSE '1' END" |
            tr -d '[:space:]'
    )"
    if [[ "${table_present}" == "0" ]]; then
        printf '%s' absent
        return 0
    fi
    if [[ "${table_present}" != "1" ]]; then
        life_agent_die "could not determine schema revision"
    fi

    local revisions
    revisions="$(
        life_agent_compose exec -T postgres \
            psql \
            --no-psqlrc \
            --tuples-only \
            --no-align \
            --set ON_ERROR_STOP=1 \
            --username life_agent_owner \
            --dbname life_agent \
            --command \
            "SELECT COALESCE(string_agg(version_num, ',' ORDER BY version_num), '') FROM alembic_version" |
            tr -d '[:space:]'
    )"
    if [[ ! "${revisions}" =~ ^[0-9A-Za-z_.-]+(,[0-9A-Za-z_.-]+)*$ ]]; then
        life_agent_die "schema revision set is empty or malformed"
    fi
    printf 'present:%s' "${revisions}"
}

life_agent_state_path() {
    local name="$1"
    case "${name}" in
        current-image | current-schema | previous-image | previous-schema) ;;
        *) life_agent_die "invalid deployment state key" ;;
    esac
    printf '%s/%s' "${LIFE_AGENT_DEPLOY_STATE_DIR}" "${name}"
}

life_agent_read_state() {
    local name="$1"
    local path
    path="$(life_agent_state_path "${name}")"
    if [[ ! -e "${path}" && ! -L "${path}" ]]; then
        return 1
    fi
    life_agent_require_regular_file "${path}"
    if [[ "$(stat -c '%u:%g:%a' -- "${path}")" != "0:0:600" ]]; then
        life_agent_die "deployment state files must be root-owned mode 0600"
    fi
    local -a state_lines=()
    mapfile -t state_lines <"${path}"
    if [[ "${#state_lines[@]}" -ne 1 || -z "${state_lines[0]}" ]]; then
        life_agent_die "deployment state file is malformed"
    fi
    local value="${state_lines[0]}"
    case "${name}" in
        current-image | previous-image)
            if ! life_agent_is_backend_image "${value}"; then
                life_agent_die "deployment state contains an invalid image digest"
            fi
            ;;
        current-schema | previous-schema)
            if ! life_agent_is_schema_revision "${value}"; then
                life_agent_die "deployment state contains an invalid schema revision"
            fi
            ;;
    esac
    printf '%s' "${value}"
}

life_agent_read_optional_state() {
    local name="$1"
    local path
    path="$(life_agent_state_path "${name}")"
    if [[ ! -e "${path}" && ! -L "${path}" ]]; then
        return 0
    fi
    life_agent_read_state "${name}"
}

life_agent_write_state() {
    local name="$1"
    local value="$2"
    local path
    local temporary
    path="$(life_agent_state_path "${name}")"
    case "${name}" in
        current-image | previous-image)
            life_agent_is_backend_image "${value}" ||
                life_agent_die "refusing to write an invalid image digest"
            ;;
        current-schema | previous-schema)
            life_agent_is_schema_revision "${value}" ||
                life_agent_die "refusing to write an invalid schema revision"
            ;;
    esac
    temporary="$(mktemp "${LIFE_AGENT_DEPLOY_STATE_DIR}/.${name}.XXXXXXXX")"
    chmod 0600 "${temporary}"
    printf '%s\n' "${value}" >"${temporary}"
    chown root:root "${temporary}"
    mv -fT -- "${temporary}" "${path}"
}

life_agent_remove_state() {
    local name="$1"
    local path
    path="$(life_agent_state_path "${name}")"
    if [[ -e "${path}" || -L "${path}" ]]; then
        life_agent_require_regular_file "${path}"
        rm -f -- "${path}"
    fi
}

life_agent_running_api_image() {
    local container_id
    container_id="$(life_agent_compose ps --quiet api)"
    if [[ -z "${container_id}" ]]; then
        return 1
    fi
    life_agent_docker inspect --format '{{.Config.Image}}' "${container_id}"
}

life_agent_stop_api() {
    life_agent_compose stop --timeout 30 api >/dev/null
}
