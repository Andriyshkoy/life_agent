#!/bin/bash
set -Eeuo pipefail

umask 077

readonly life_agent_fixed_production_dir="/opt/life-agent/production"
readonly life_agent_backend_image_prefix="ghcr.io/andriyshkoy/life_agent/backend@sha256:"
readonly life_agent_deploy_user="life-agent-deploy"
readonly life_agent_bundle_lock_file="/run/lock/life-agent-production-bundle.lock"
readonly -a life_agent_bundle_files=(
    compose.production.yaml
    deploy-backend.sh
    rollback-backend.sh
    lib.sh
    deploy-from-ci.sh
    life-agent-production.sudoers
)

life_agent_ci_die() {
    echo "life-agent-production-boundary: $*" >&2
    return 1
}

life_agent_ci_is_backend_image() {
    local candidate="${1:-}"
    if [[ "${candidate}" != "${life_agent_backend_image_prefix}"* ]]; then
        return 1
    fi
    local digest="${candidate#"${life_agent_backend_image_prefix}"}"
    [[ "${digest}" =~ ^[0-9a-f]{64}$ ]]
}

life_agent_ci_bundle_digest() {
    local file_name
    local file_path
    local file_hash
    {
        for file_name in "${life_agent_bundle_files[@]}"; do
            if [[ "${file_name}" == "life-agent-production.sudoers" ]]; then
                file_path="/etc/sudoers.d/life-agent-production"
            else
                file_path="${life_agent_fixed_production_dir}/${file_name}"
            fi
            if [[ ! -f "${file_path}" || -L "${file_path}" ]]; then
                life_agent_ci_die "the installed production bundle is incomplete"
            fi
            case "${file_name}" in
                compose.production.yaml)
                    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${file_path}")" == "0:0:644" ]] ||
                        life_agent_ci_die "the installed Compose file has unsafe metadata"
                    ;;
                life-agent-production.sudoers)
                    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${file_path}")" == "0:0:440" ]] ||
                        life_agent_ci_die "the installed sudo rule has unsafe metadata"
                    ;;
                *)
                    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${file_path}")" == "0:0:755" ]] ||
                        life_agent_ci_die "an installed production script has unsafe metadata"
                    ;;
            esac
            file_hash="$(/usr/bin/sha256sum -- "${file_path}")"
            file_hash="${file_hash%% *}"
            [[ "${file_hash}" =~ ^[0-9a-f]{64}$ ]] ||
                life_agent_ci_die "an installed production file could not be hashed"
            printf '%s  %s\n' "${file_hash}" "${file_name}"
        done
    } | /usr/bin/sha256sum | {
        read -r file_hash _
        [[ "${file_hash}" =~ ^[0-9a-f]{64}$ ]] ||
            life_agent_ci_die "the installed production bundle could not be hashed"
        printf 'sha256:%s' "${file_hash}"
    }
}

if [[ "${EUID}" -ne 0 ]]; then
    life_agent_ci_die "run only through the constrained production sudo rule"
fi
if [[ "${SUDO_USER:-}" != "${life_agent_deploy_user}" ]]; then
    life_agent_ci_die "the production boundary requires the dedicated deploy account"
fi
if [[ "$#" -ne 0 ]]; then
    life_agent_ci_die "the production boundary does not accept command-line arguments"
fi
for protected_directory in /opt/life-agent "${life_agent_fixed_production_dir}"; do
    if [[ ! -d "${protected_directory}" ]] ||
        [[ -L "${protected_directory}" ]] ||
        [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${protected_directory}")" != "0:0:755" ]]; then
        life_agent_ci_die "the installed production directory has unsafe metadata"
    fi
done
if /usr/bin/id -nG "${life_agent_deploy_user}" |
    /usr/bin/tr ' ' '\n' |
    /usr/bin/grep -Eq '^(docker|sudo)$'; then
    life_agent_ci_die "the deploy account must not have a second privilege path"
fi
if [[ ! -f "${life_agent_bundle_lock_file}" ]] ||
    [[ -L "${life_agent_bundle_lock_file}" ]] ||
    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${life_agent_bundle_lock_file}")" != "0:0:600" ]]; then
    life_agent_ci_die "the production bundle lock is unsafe"
fi
exec 8<>"${life_agent_bundle_lock_file}"
if ! /usr/bin/flock -n 8; then
    life_agent_ci_die "another production bundle operation is active"
fi

candidate_image=""
registry_user=""
source_sha=""
expected_bundle_digest=""
IFS= read -r candidate_image || life_agent_ci_die "missing immutable image metadata"
IFS= read -r registry_user || life_agent_ci_die "missing registry user metadata"
IFS= read -r source_sha || life_agent_ci_die "missing source revision metadata"
IFS= read -r expected_bundle_digest || life_agent_ci_die "missing bundle metadata"
readonly candidate_image
readonly registry_user
readonly source_sha
readonly expected_bundle_digest

life_agent_ci_is_backend_image "${candidate_image}" ||
    life_agent_ci_die "refusing a mutable or foreign backend image"
if [[ ! "${registry_user}" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$ ]]; then
    life_agent_ci_die "the registry user is malformed"
fi
[[ "${source_sha}" =~ ^[0-9a-f]{40}$ ]] ||
    life_agent_ci_die "the source revision is malformed"
[[ "${expected_bundle_digest}" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    life_agent_ci_die "the expected bundle digest is malformed"

observed_bundle_digest="$(life_agent_ci_bundle_digest)"
readonly observed_bundle_digest
if [[ "${observed_bundle_digest}" != "${expected_bundle_digest}" ]]; then
    life_agent_ci_die "the installed root-owned bundle does not match the deployed source"
fi

if [[ ! -x /usr/bin/docker ]]; then
    life_agent_ci_die "the Docker CLI is unavailable"
fi

registry_config_dir="$(/usr/bin/mktemp -d /run/life-agent-ghcr.XXXXXXXX)"
readonly registry_config_dir

life_agent_ci_cleanup_registry() {
    local exit_status="$?"
    trap - EXIT HUP INT TERM
    if [[ "${registry_config_dir}" =~ ^/run/life-agent-ghcr\.[A-Za-z0-9]+$ ]] &&
        [[ -d "${registry_config_dir}" ]] &&
        [[ ! -L "${registry_config_dir}" ]]; then
        /usr/bin/rm -rf -- "${registry_config_dir}"
    fi
    exit "${exit_status}"
}

trap life_agent_ci_cleanup_registry EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ ! "${registry_config_dir}" =~ ^/run/life-agent-ghcr\.[A-Za-z0-9]+$ ]] ||
    [[ -L "${registry_config_dir}" ]] ||
    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${registry_config_dir}")" != "0:0:700" ]]; then
    life_agent_ci_die "could not create a safe ephemeral registry configuration"
fi

if ! /usr/bin/docker \
    --config "${registry_config_dir}" \
    login ghcr.io \
    --username "${registry_user}" \
    --password-stdin \
    >/dev/null; then
    life_agent_ci_die "ephemeral GHCR authentication failed"
fi

/usr/bin/docker \
    --config "${registry_config_dir}" \
    pull "${candidate_image}" \
    >/dev/null
published_source_sha="$(
    /usr/bin/docker \
        --config "${registry_config_dir}" \
        image inspect \
        --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
        "${candidate_image}"
)"
readonly published_source_sha
if [[ "${published_source_sha}" != "${source_sha}" ]]; then
    life_agent_ci_die "the immutable image does not match the verified source revision"
fi

/usr/bin/env -i \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LIFE_AGENT_DOCKER_CONFIG_DIR="${registry_config_dir}" \
    "${life_agent_fixed_production_dir}/deploy-backend.sh" \
    "${candidate_image}"
