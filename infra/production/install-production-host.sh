#!/bin/bash
set -Eeuo pipefail

readonly source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly target_parent="/opt/life-agent"
readonly target_dir="${target_parent}/production"
readonly sudoers_source="${source_dir}/life-agent-production.sudoers"
readonly sudoers_target="/etc/sudoers.d/life-agent-production"
readonly deploy_user="life-agent-deploy"
readonly lock_file="/run/lock/life-agent-production-bundle.lock"
readonly -a runtime_files=(
    compose.production.yaml
    deploy-backend.sh
    rollback-backend.sh
    lib.sh
    deploy-from-ci.sh
)

install_die() {
    echo "life-agent-production-installer: $*" >&2
    return 1
}

if [[ "${EUID}" -ne 0 ]]; then
    install_die "run this host installer through interactive sudo"
fi
if ! /usr/bin/getent passwd "${deploy_user}" >/dev/null; then
    install_die "the dedicated deploy account does not exist"
fi
if /usr/bin/id -nG "${deploy_user}" |
    /usr/bin/tr ' ' '\n' |
    /usr/bin/grep -Eq '^(docker|sudo)$'; then
    install_die "the deploy account must not belong to docker or sudo groups"
fi

for file_name in "${runtime_files[@]}"; do
    source_path="${source_dir}/${file_name}"
    if [[ ! -f "${source_path}" || -L "${source_path}" ]]; then
        install_die "the tracked production bundle is incomplete"
    fi
done
if [[ ! -f "${sudoers_source}" || -L "${sudoers_source}" ]]; then
    install_die "the tracked production sudo rule is missing"
fi

/bin/bash -n \
    "${source_dir}/deploy-backend.sh" \
    "${source_dir}/rollback-backend.sh" \
    "${source_dir}/lib.sh" \
    "${source_dir}/deploy-from-ci.sh"
/usr/sbin/visudo -cf "${sudoers_source}" >/dev/null

for protected_path in "${target_parent}" "${target_dir}"; do
    if [[ -L "${protected_path}" ]] ||
        [[ -e "${protected_path}" && ! -d "${protected_path}" ]]; then
        install_die "refusing an unsafe production installation path"
    fi
done
if [[ -L "${sudoers_target}" ]] ||
    [[ -e "${sudoers_target}" && ! -f "${sudoers_target}" ]]; then
    install_die "refusing an unsafe production sudoers path"
fi

/usr/bin/install -d -o root -g root -m 0755 "${target_parent}" "${target_dir}"
if [[ ! -e "${lock_file}" ]]; then
    /usr/bin/install -T -o root -g root -m 0600 /dev/null "${lock_file}"
fi
if [[ ! -f "${lock_file}" || -L "${lock_file}" ]] ||
    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${lock_file}")" != "0:0:600" ]]; then
    install_die "the production bundle lock is unsafe"
fi
exec 9<>"${lock_file}"
if ! /usr/bin/flock -n 9; then
    install_die "another production bundle operation is active"
fi

/usr/bin/install -T -o root -g root -m 0644 \
    "${source_dir}/compose.production.yaml" \
    "${target_dir}/compose.production.yaml"
for file_name in deploy-backend.sh rollback-backend.sh lib.sh deploy-from-ci.sh; do
    /usr/bin/install -T -o root -g root -m 0755 \
        "${source_dir}/${file_name}" \
        "${target_dir}/${file_name}"
done
/usr/bin/install -T -o root -g root -m 0440 \
    "${sudoers_source}" \
    "${sudoers_target}"
/usr/sbin/visudo -cf "${sudoers_target}" >/dev/null

if [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${target_parent}")" != "0:0:755" ]] ||
    [[ "$(/usr/bin/stat -c '%u:%g:%a' -- "${target_dir}")" != "0:0:755" ]]; then
    install_die "the installed production directories have unsafe metadata"
fi

echo "The fixed Life Agent production boundary is installed."
