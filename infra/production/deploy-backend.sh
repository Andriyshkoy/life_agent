#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

if [[ "$#" -ne 1 ]] || ! life_agent_is_backend_image "$1"; then
    life_agent_die \
        "usage: deploy-backend.sh ghcr.io/andriyshkoy/life_agent/backend@sha256:<64 lowercase hex>"
fi
readonly candidate_image="$1"

export LIFE_AGENT_BACKEND_IMAGE="${candidate_image}"
life_agent_prepare_runtime

life_agent_compose pull postgres >/dev/null
life_agent_compose up --detach --no-deps postgres >/dev/null
if ! life_agent_wait_for_health postgres 90; then
    life_agent_die "PostgreSQL did not become healthy; API was not changed"
fi

before_revision="$(life_agent_schema_revision)"
readonly before_revision
current_image="$(life_agent_read_optional_state current-image)"
current_schema="$(life_agent_read_optional_state current-schema)"
original_previous_image="$(life_agent_read_optional_state previous-image)"
original_previous_schema="$(life_agent_read_optional_state previous-schema)"
running_image="$(life_agent_running_api_image 2>/dev/null || true)"
rollback_image=""
if [[ -n "${current_image}" && -z "${current_schema}" ]] ||
    [[ -z "${current_image}" && -n "${current_schema}" ]]; then
    life_agent_die "recorded current deployment state is incomplete"
fi
if [[ -n "${original_previous_image}" && -z "${original_previous_schema}" ]] ||
    [[ -z "${original_previous_image}" && -n "${original_previous_schema}" ]]; then
    life_agent_die "recorded previous deployment state is incomplete"
fi
if [[ -n "${current_image}" ]]; then
    if [[
        "${current_schema}" == "${before_revision}" &&
        "${running_image}" == "${current_image}" &&
        "$(
            life_agent_docker inspect \
                --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
                "$(life_agent_compose ps --quiet api)" 2>/dev/null || true
        )" == "healthy"
    ]]; then
        rollback_image="${current_image}"
    fi
fi
readonly current_image
readonly current_schema
readonly original_previous_image
readonly original_previous_schema
readonly running_image
readonly rollback_image

deploy_mutating=0
deploy_completed=0

deploy_restore_original_state() {
    if [[ -n "${current_image}" ]]; then
        life_agent_write_state current-image "${current_image}" || return 1
        life_agent_write_state current-schema "${current_schema}" || return 1
    else
        life_agent_remove_state current-image || return 1
        life_agent_remove_state current-schema || return 1
    fi
    if [[ -n "${original_previous_image}" ]]; then
        life_agent_write_state previous-image "${original_previous_image}" || return 1
        life_agent_write_state previous-schema "${original_previous_schema}" || return 1
    else
        life_agent_remove_state previous-image || return 1
        life_agent_remove_state previous-schema || return 1
    fi
}

deploy_failure_handler() {
    local exit_status="${1:-1}"
    trap - ERR HUP INT TERM
    set +e
    if [[ "${deploy_completed}" -eq 1 || "${deploy_mutating}" -eq 0 ]]; then
        exit "${exit_status}"
    fi

    local observed_revision=""
    local revision_status=1
    observed_revision="$(life_agent_schema_revision 2>/dev/null)"
    revision_status="$?"
    life_agent_stop_api
    local stop_status="$?"

    if [[ "${revision_status}" -eq 0 ]] && life_agent_can_auto_rollback \
        "${rollback_image}" "${before_revision}" "${observed_revision}"; then
        export LIFE_AGENT_BACKEND_IMAGE="${rollback_image}"
        life_agent_compose up --detach --no-deps api >/dev/null
        local restore_start_status="$?"
        if [[ "${restore_start_status}" -eq 0 ]] && life_agent_wait_for_health api 90; then
            if ! deploy_restore_original_state; then
                life_agent_stop_api
                echo "life-agent-deploy: failed to restore deployment state" >&2
                exit 90
            fi
            echo \
                "life-agent-deploy: failed deployment restored the unchanged-schema image" \
                >&2
            exit "${exit_status}"
        fi
        life_agent_stop_api
        echo "life-agent-deploy: rollback failed; API was stopped" >&2
        exit 90
    fi

    if ! deploy_restore_original_state; then
        echo "life-agent-deploy: failed to restore deployment state" >&2
        exit 90
    fi
    if [[ "${stop_status}" -ne 0 ]]; then
        echo "life-agent-deploy: failed to stop API after deployment failure" >&2
        exit 90
    fi
    echo \
        "life-agent-deploy: API was stopped; no exact unchanged-schema rollback was safe" \
        >&2
    exit "${exit_status}"
}

trap 'deploy_failure_handler $?' ERR
trap 'deploy_failure_handler 129' HUP
trap 'deploy_failure_handler 130' INT
trap 'deploy_failure_handler 143' TERM

life_agent_compose pull api migrate database-role-init >/dev/null
deploy_mutating=1
life_agent_stop_api
life_agent_compose run --rm --no-deps -T migrate

after_revision="$(life_agent_schema_revision)"
readonly after_revision
life_agent_compose run --rm --no-deps -T database-role-init
export LIFE_AGENT_BACKEND_IMAGE="${candidate_image}"
life_agent_compose up --detach --no-deps api >/dev/null
if ! life_agent_wait_for_health api 120; then
    life_agent_die "candidate did not pass internal readiness"
fi

life_agent_write_state current-image "${candidate_image}"
life_agent_write_state current-schema "${after_revision}"
if [[ -n "${rollback_image}" && "${rollback_image}" != "${candidate_image}" ]]; then
    life_agent_write_state previous-image "${rollback_image}"
    life_agent_write_state previous-schema "${before_revision}"
else
    life_agent_remove_state previous-image
    life_agent_remove_state previous-schema
fi

deploy_completed=1
trap - ERR HUP INT TERM
echo "Life Agent backend deployment is ready at an immutable image digest."
