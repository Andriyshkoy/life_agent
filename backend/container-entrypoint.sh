#!/bin/sh
set -eu

load_secret_file() {
    variable_name="$1"
    file_variable_name="${variable_name}_FILE"

    eval "direct_value=\${${variable_name}-}"
    eval "file_path=\${${file_variable_name}-}"

    if [ -n "${direct_value}" ] && [ -n "${file_path}" ]; then
        echo "${variable_name} and ${file_variable_name} are mutually exclusive" >&2
        exit 78
    fi
    if [ -z "${file_path}" ]; then
        return
    fi
    case "${file_path}" in
        /run/secrets/life_agent_*) ;;
        *)
            echo "${file_variable_name} must reference a Life Agent runtime secret" >&2
            exit 78
            ;;
    esac
    if [ ! -f "${file_path}" ] || [ -L "${file_path}" ]; then
        echo "${file_variable_name} does not reference a regular file" >&2
        exit 78
    fi

    secret_value="$(cat -- "${file_path}")"
    if [ -z "${secret_value}" ]; then
        echo "${file_variable_name} references an empty file" >&2
        exit 78
    fi
    case "${secret_value}" in
        *"
"*)
            echo "${file_variable_name} must contain exactly one line" >&2
            exit 78
            ;;
    esac

    export "${variable_name}=${secret_value}"
    unset "${file_variable_name}"
}

for variable_name in \
    LIFE_AGENT_DATABASE_URL \
    LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY \
    LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY \
    LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY \
    LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY \
    LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY \
    LIFE_AGENT_CURSOR_HMAC_KEY; do
    load_secret_file "${variable_name}"
done
unset variable_name

exec "$@"
