#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
    echo "Usage: verify-runtime-image.sh <local-or-immutable-image-reference>" >&2
}

if [[ "$#" -ne 1 ]]; then
    usage
    exit 2
fi

readonly image_ref="$1"
if [[
    -z "${image_ref}" ||
    "${image_ref}" == -* ||
    ! "${image_ref}" =~ ^[a-zA-Z0-9][a-zA-Z0-9._/@:+-]*$
]]; then
    echo "Refusing an invalid backend image reference." >&2
    exit 2
fi

docker image inspect "${image_ref}" >/dev/null

readonly container_name="life-agent-backend-verify-${BASHPID}-${RANDOM}"
verification_root="$(
    mktemp -d "${RUNNER_TEMP:-/tmp}/life-agent-backend-verify.XXXXXXXX"
)"
readonly verification_root
readonly ready_body="${verification_root}/ready.json"
readonly admin_stdout="${verification_root}/admin.stdout"
readonly admin_stderr="${verification_root}/admin.stderr"

cleanup() {
    local exit_status="$?"
    trap - EXIT
    if [[ "${exit_status}" -ne 0 ]]; then
        docker logs "${container_name}" || true
    fi
    docker rm --force "${container_name}" >/dev/null 2>&1 || true
    rm -rf -- "${verification_root}"
    exit "${exit_status}"
}
trap cleanup EXIT

derive_verification_key() {
    printf 'life-agent-image-verification-only-%s' "$1" |
        openssl dgst -sha256 -binary |
        openssl base64 -A |
        tr '+/' '-_' |
        tr -d '='
}

readonly access_token_hmac_key="$(derive_verification_key access-token)"
readonly refresh_token_hmac_key="$(derive_verification_key refresh-token)"
readonly enrollment_code_hmac_key="$(derive_verification_key enrollment-code)"
readonly replay_fingerprint_hmac_key="$(derive_verification_key replay-fingerprint)"
readonly replay_response_encryption_key="$(derive_verification_key replay-encryption)"
readonly cursor_hmac_key="$(derive_verification_key cursor)"

docker run \
    --detach \
    --name "${container_name}" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --publish 127.0.0.1::8080 \
    --env LIFE_AGENT_ENVIRONMENT=test \
    --env LIFE_AGENT_DATABASE_URL=postgresql+asyncpg://life_agent_verify:verify-only@127.0.0.1:9/life_agent_verify \
    --env LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY="${access_token_hmac_key}" \
    --env LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_GENERATION=1 \
    --env LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY="${refresh_token_hmac_key}" \
    --env LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_GENERATION=1 \
    --env LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY="${enrollment_code_hmac_key}" \
    --env LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_GENERATION=1 \
    --env LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY="${replay_fingerprint_hmac_key}" \
    --env LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_GENERATION=1 \
    --env LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY="${replay_response_encryption_key}" \
    --env LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_GENERATION=1 \
    --env LIFE_AGENT_CURSOR_HMAC_KEY="${cursor_hmac_key}" \
    --env LIFE_AGENT_CURSOR_HMAC_KEY_GENERATION=1 \
    "${image_ref}" \
    >/dev/null

runtime_user="$(docker inspect --format '{{.Config.User}}' "${container_name}")"
readonly runtime_user
if [[ "${runtime_user}" != "10001:10001" ]]; then
    echo "Backend runtime image is not configured for its unprivileged user." >&2
    exit 1
fi

published_endpoint="$(docker port "${container_name}" 8080/tcp)"
readonly published_endpoint
readonly backend_port="${published_endpoint##*:}"
if [[ ! "${backend_port}" =~ ^[0-9]{1,5}$ ]]; then
    echo "Failed to resolve the backend verification port." >&2
    exit 1
fi

health_body=""
for _ in {1..30}; do
    if health_body="$(
        curl \
            --fail \
            --silent \
            --show-error \
            --max-time 2 \
            "http://127.0.0.1:${backend_port}/healthz"
    )"; then
        break
    fi
    container_running="$(
        docker inspect --format '{{.State.Running}}' "${container_name}"
    )"
    if [[ "${container_running}" != "true" ]]; then
        echo "Backend container exited during image verification." >&2
        exit 1
    fi
    sleep 1
done
readonly health_body

if [[ "${health_body}" != '{"status":"ok"}' ]]; then
    echo "Unexpected backend liveness response." >&2
    exit 1
fi

docker exec "${container_name}" life-agent-admin --help >/dev/null
if docker exec "${container_name}" life-agent-admin issue-enrollment-code \
    --allow-non-tty-output \
    >"${admin_stdout}" 2>"${admin_stderr}"; then
    echo "Admin CLI unexpectedly succeeded without its database." >&2
    exit 1
fi
if [[ -s "${admin_stdout}" ]]; then
    echo "Admin CLI emitted a code for an unavailable database." >&2
    exit 1
fi
admin_error="$(tr -d '\r\n' <"${admin_stderr}")"
readonly admin_error
readonly expected_admin_error='life-agent-admin: database is not ready at the expected migration'
if [[ "${admin_error}" != "${expected_admin_error}" ]]; then
    echo "Admin CLI did not fail with its bounded readiness error." >&2
    exit 1
fi

ready_status="$(
    curl \
        --silent \
        --show-error \
        --max-time 3 \
        --output "${ready_body}" \
        --write-out '%{http_code}' \
        "http://127.0.0.1:${backend_port}/readyz"
)"
readonly ready_status
if [[ "${ready_status}" != "503" ]]; then
    echo "Backend reported ready without its database or migrations." >&2
    exit 1
fi
if [[ "$(tr -d '\r\n' <"${ready_body}")" != '{"status":"not_ready"}' ]]; then
    echo "Unexpected backend pre-migration readiness response." >&2
    exit 1
fi

echo "Backend runtime image verification passed."
