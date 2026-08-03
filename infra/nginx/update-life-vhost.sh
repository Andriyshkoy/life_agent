#!/usr/bin/env bash
set -Eeuo pipefail

readonly domain="life.andriyshkoy.ru"
readonly source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly source="${source_dir}/${domain}.https.conf"
readonly target="/etc/nginx/sites-available/${domain}"
readonly backup_root="/var/backups/life-agent-nginx"
readonly expected_redirect="https://github.com/Andriyshkoy/life_agent/releases/download/dev-latest/life-agent-dev.apk"

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this updater through sudo." >&2
    exit 1
fi

for required_path in "${source}" "${target}"; do
    if [[ ! -f "${required_path}" || -L "${required_path}" ]]; then
        echo "Expected a regular file: ${required_path}" >&2
        exit 1
    fi
done

mkdir -p "${backup_root}"
backup_dir="$(mktemp -d "${backup_root}/update.XXXXXXXX")"
readonly backup_dir
cp -a --no-dereference "${target}" "${backup_dir}/previous.conf"

rollback() {
    local exit_status="${1:-1}"
    local rollback_failed=0
    trap - ERR HUP INT TERM
    set +e
    install -T -o root -g root -m 0644 \
        "${backup_dir}/previous.conf" \
        "${target}" ||
        rollback_failed=1
    nginx -t || rollback_failed=1
    systemctl reload nginx || rollback_failed=1
    if [[ "${rollback_failed}" -ne 0 ]]; then
        echo \
            "Nginx update failed with status ${exit_status}; rollback is incomplete." \
            >&2
        exit 90
    fi
    echo "Nginx update rolled back after status ${exit_status}." >&2
    exit "${exit_status}"
}

on_error() {
    local exit_status="$?"
    rollback "${exit_status}"
}

trap on_error ERR
trap 'rollback 129' HUP
trap 'rollback 130' INT
trap 'rollback 143' TERM

install -T -o root -g root -m 0644 "${source}" "${target}"
nginx -t
systemctl reload nginx

health_body="$(mktemp "${backup_dir}/health.XXXXXXXX")"
readonly health_body
health_status="$(
    curl \
        --silent \
        --show-error \
        --noproxy '*' \
        --output "${health_body}" \
        --write-out '%{http_code}' \
        --resolve "${domain}:443:127.0.0.1" \
        "https://${domain}/healthz"
)"
readonly health_status
if [[ "${health_status}" != "204" || -s "${health_body}" ]]; then
    echo "Unexpected content-bearing public health response." >&2
    false
fi

redirect_location="$(
    curl \
        --head \
        --silent \
        --show-error \
        --noproxy '*' \
        --resolve "${domain}:443:127.0.0.1" \
        "https://${domain}/dev.apk" |
        awk 'BEGIN { IGNORECASE = 1 } /^location:/ { print $2 }' |
        tr -d '\r'
)"
readonly redirect_location

if [[ "${redirect_location}" != "${expected_redirect}" ]]; then
    echo "Unexpected dev APK redirect: ${redirect_location}" >&2
    false
fi

trap - ERR HUP INT TERM
echo "Life Agent dev APK redirect is active."
