#!/usr/bin/env bash
set -Eeuo pipefail

readonly domain="life.andriyshkoy.ru"
readonly source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly target="/etc/nginx/sites-available/${domain}"
readonly enabled="/etc/nginx/sites-enabled/${domain}"
readonly backup_root="/var/backups/life-agent-nginx"
readonly runtime_dir="/run/life-agent"
readonly lock_file="${runtime_dir}/nginx-bootstrap.lock"
readonly certificate_dir="/etc/letsencrypt/live/${domain}"

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this installer through sudo." >&2
    exit 1
fi

if ! certbot show_account --non-interactive >/dev/null 2>&1; then
    echo \
        "No usable default Certbot account; register one before running bootstrap." \
        >&2
    exit 1
fi

for required_file in \
    "${source_dir}/${domain}.http.conf" \
    "${source_dir}/${domain}.https.conf"; do
    if [[ ! -f "${required_file}" ]]; then
        echo "Missing ${required_file}" >&2
        exit 1
    fi
done

for protected_path in "${target}" "${enabled}"; do
    if [[ -d "${protected_path}" && ! -L "${protected_path}" ]]; then
        echo "Refusing to replace directory ${protected_path}" >&2
        exit 1
    fi
    if [[ -e "${protected_path}" || -L "${protected_path}" ]]; then
        echo "Refusing to replace existing vhost path ${protected_path}" >&2
        exit 4
    fi
done

if [[ -e "${certificate_dir}" || -L "${certificate_dir}" ]]; then
    for certificate_file in fullchain.pem privkey.pem; do
        if [[ ! -s "${certificate_dir}/${certificate_file}" ]]; then
            echo \
                "Existing certificate lineage is incomplete: ${certificate_dir}" \
                >&2
            exit 4
        fi
    done

    if ! certificate_san_text="$(
        openssl x509 \
            -in "${certificate_dir}/fullchain.pem" \
            -noout \
            -ext subjectAltName
    )"; then
        echo "Cannot inspect existing certificate lineage for ${domain}" >&2
        exit 4
    fi
    readonly certificate_san_text

    certificate_san_entries="$(
        printf '%s\n' "${certificate_san_text}" |
            sed '1d' |
            tr -d '[:space:]'
    )"
    readonly certificate_san_entries

    if [[ "${certificate_san_entries}" != "DNS:${domain}" ]]; then
        echo \
            "Refusing certificate lineage whose SAN set is not exactly ${domain}" \
            >&2
        exit 4
    fi

    echo "Reusing the existing single-domain certificate lineage for ${domain}."
fi

if [[ -L "${runtime_dir}" || ( -e "${runtime_dir}" && ! -d "${runtime_dir}" ) ]]; then
    echo "Unsafe runtime path ${runtime_dir}" >&2
    exit 1
fi
if [[ ! -e "${runtime_dir}" ]]; then
    install -d -o root -g root -m 0700 "${runtime_dir}"
fi
if [[
    "$(stat -c '%u:%a' "${runtime_dir}")" != "0:700"
]]; then
    echo "Runtime directory must be root-owned mode 0700: ${runtime_dir}" >&2
    exit 1
fi
if [[ -L "${lock_file}" || ( -e "${lock_file}" && ! -f "${lock_file}" ) ]]; then
    echo "Unsafe lock path ${lock_file}" >&2
    exit 1
fi
if [[ ! -e "${lock_file}" ]]; then
    install -T -o root -g root -m 0600 /dev/null "${lock_file}"
fi
if [[ "$(stat -c '%u:%a' "${lock_file}")" != "0:600" ]]; then
    echo "Lock file must be root-owned mode 0600: ${lock_file}" >&2
    exit 1
fi

exec 9<>"${lock_file}"
if ! flock -n 9; then
    echo "Another Life Agent nginx bootstrap is already running." >&2
    exit 3
fi

if ! getent ahostsv4 "${domain}" >/dev/null; then
    echo "DNS for ${domain} does not resolve yet; nginx was not changed." >&2
    exit 2
fi

mkdir -p "${backup_root}"
backup_dir="$(mktemp -d "${backup_root}/bootstrap.XXXXXXXX")"
readonly backup_dir

if [[ -e "${target}" || -L "${target}" ]]; then
    cp -a --no-dereference "${target}" "${backup_dir}/target"
fi
if [[ -e "${enabled}" || -L "${enabled}" ]]; then
    cp -a --no-dereference "${enabled}" "${backup_dir}/enabled"
fi

rollback_and_exit() {
    local exit_status="${1:-1}"
    local rollback_failed=0
    trap - ERR HUP INT TERM
    set +e
    if ! rm -f "${enabled}" "${target}"; then
        rollback_failed=1
    fi
    if [[ -e "${backup_dir}/target" || -L "${backup_dir}/target" ]]; then
        if ! cp -a --no-dereference "${backup_dir}/target" "${target}"; then
            rollback_failed=1
        fi
    fi
    if [[ -e "${backup_dir}/enabled" || -L "${backup_dir}/enabled" ]]; then
        if ! cp -a --no-dereference "${backup_dir}/enabled" "${enabled}"; then
            rollback_failed=1
        fi
    fi
    if ! nginx -t; then
        rollback_failed=1
    elif ! systemctl reload nginx; then
        rollback_failed=1
    fi
    if [[ "${rollback_failed}" -ne 0 ]]; then
        echo \
            "ROLLBACK INCOMPLETE after status ${exit_status}; inspect nginx manually." \
            >&2
        exit 90
    fi
    echo "Rollback completed after status ${exit_status}." >&2
    exit "${exit_status}"
}

on_error() {
    local exit_status="$?"
    rollback_and_exit "${exit_status}"
}

trap on_error ERR
trap 'rollback_and_exit 129' HUP
trap 'rollback_and_exit 130' INT
trap 'rollback_and_exit 143' TERM

rm -f "${enabled}" "${target}"
install -T -o root -g root -m 0644 \
    "${source_dir}/${domain}.http.conf" \
    "${target}"
ln -sfnT "${target}" "${enabled}"
nginx -t
systemctl reload nginx

certbot certonly \
    --nginx \
    --non-interactive \
    --agree-tos \
    --keep-until-expiring \
    --cert-name "${domain}" \
    --domain "${domain}"

test -s "${certificate_dir}/fullchain.pem"
test -s "${certificate_dir}/privkey.pem"

install -T -o root -g root -m 0644 \
    "${source_dir}/${domain}.https.conf" \
    "${target}"
nginx -t
systemctl reload nginx

health_body="$(mktemp "${backup_dir}/health.XXXXXXXX")"
readonly health_body
health_status=""
health_exit_status=1

# systemctl reload returns after signalling nginx; a newly loaded worker may need
# a short moment before it serves the new SNI vhost. A single immediate request
# can therefore still see the previous default certificate and trigger a false
# rollback. Keep this probe bounded, validate TLS on every attempt, and require
# an empty HTTP 204 before declaring success.
for probe_attempt in {1..20}; do
    if health_status="$(
        curl \
            --silent \
            --noproxy '*' \
            --connect-timeout 2 \
            --max-time 5 \
            --output "${health_body}" \
            --write-out '%{http_code}' \
            --resolve "${domain}:443:127.0.0.1" \
            "https://${domain}/healthz" \
            2>/dev/null
    )"; then
        if [[ "${health_status}" == "204" && ! -s "${health_body}" ]]; then
            health_exit_status=0
            break
        fi
        health_exit_status=22
    else
        health_exit_status="$?"
    fi
    sleep 1
done
readonly health_status

if [[ "${health_exit_status}" -ne 0 ]]; then
    echo \
        "HTTPS health probe did not converge; last curl status ${health_exit_status}," \
        "HTTP status ${health_status}" \
        >&2
    rollback_and_exit "${health_exit_status}"
fi

trap - ERR HUP INT TERM
echo
echo "HTTPS bootstrap for ${domain} passed its on-host TLS health check."
echo "Verify public reachability from a separate network before relying on it."
