#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

if [[ "$#" -ne 0 ]]; then
    life_agent_die "usage: rollback-backend.sh"
fi

candidate_placeholder="${LIFE_AGENT_BACKEND_IMAGE_PREFIX}$(printf '0%.0s' {1..64})"
export LIFE_AGENT_BACKEND_IMAGE="${candidate_placeholder}"
life_agent_prepare_runtime

current_image="$(life_agent_read_state current-image)"
current_schema="$(life_agent_read_state current-schema)"
previous_image="$(life_agent_read_state previous-image)"
previous_schema="$(life_agent_read_state previous-schema)"
readonly current_image
readonly current_schema
readonly previous_image
readonly previous_schema

if ! life_agent_is_backend_image "${current_image}" ||
    ! life_agent_is_backend_image "${previous_image}"; then
    life_agent_die "recorded rollback images are not approved immutable digests"
fi
if [[ "${current_image}" == "${previous_image}" ]]; then
    life_agent_die "recorded rollback target is not distinct"
fi

export LIFE_AGENT_BACKEND_IMAGE="${current_image}"
life_agent_compose up --detach --no-deps postgres >/dev/null
if ! life_agent_wait_for_health postgres 90; then
    life_agent_die "PostgreSQL did not become healthy; API was not changed"
fi
observed_revision="$(life_agent_schema_revision)"
readonly observed_revision
if [[
    "${observed_revision}" != "${current_schema}" ||
    "${observed_revision}" != "${previous_schema}"
]]; then
    life_agent_die "rollback refused because the recorded schema revision changed"
fi

rollback_mutating=0
rollback_completed=0

rollback_failure_handler() {
    local exit_status="${1:-1}"
    trap - ERR HUP INT TERM
    set +e
    if [[ "${rollback_completed}" -eq 1 || "${rollback_mutating}" -eq 0 ]]; then
        exit "${exit_status}"
    fi

    local failure_revision=""
    local revision_status=1
    failure_revision="$(life_agent_schema_revision 2>/dev/null)"
    revision_status="$?"
    life_agent_stop_api
    if [[
        "${revision_status}" -eq 0 &&
        "${failure_revision}" == "${observed_revision}"
    ]]; then
        export LIFE_AGENT_BACKEND_IMAGE="${current_image}"
        life_agent_compose up --detach --no-deps api >/dev/null
        local restore_start_status="$?"
        if [[ "${restore_start_status}" -eq 0 ]] && life_agent_wait_for_health api 90; then
            local state_restore_status=0
            life_agent_write_state current-image "${current_image}" || state_restore_status=1
            life_agent_write_state current-schema "${current_schema}" || state_restore_status=1
            life_agent_write_state previous-image "${previous_image}" || state_restore_status=1
            life_agent_write_state previous-schema "${previous_schema}" || state_restore_status=1
            if [[ "${state_restore_status}" -eq 0 ]]; then
                echo "life-agent-deploy: failed rollback restored the current image" >&2
                exit "${exit_status}"
            fi
        fi
    fi
    life_agent_stop_api
    echo "life-agent-deploy: rollback recovery failed; API was stopped" >&2
    exit 90
}

trap 'rollback_failure_handler $?' ERR
trap 'rollback_failure_handler 129' HUP
trap 'rollback_failure_handler 130' INT
trap 'rollback_failure_handler 143' TERM

export LIFE_AGENT_BACKEND_IMAGE="${previous_image}"
life_agent_compose pull api >/dev/null
rollback_mutating=1
life_agent_stop_api
life_agent_compose up --detach --no-deps api >/dev/null
if ! life_agent_wait_for_health api 90; then
    life_agent_die "rollback target did not pass internal readiness"
fi

life_agent_write_state current-image "${previous_image}"
life_agent_write_state current-schema "${previous_schema}"
life_agent_write_state previous-image "${current_image}"
life_agent_write_state previous-schema "${current_schema}"

rollback_completed=1
trap - ERR HUP INT TERM
echo "Life Agent backend rollback is ready at the recorded immutable image digest."
