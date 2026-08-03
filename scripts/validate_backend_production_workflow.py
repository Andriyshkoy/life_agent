#!/usr/bin/env python3
"""Dependency-free security checks for the production backend workflow."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = ROOT / ".github/workflows/backend-production.yml"
CI_WORKFLOW_PATH = ROOT / ".github/workflows/ci.yml"
PRODUCTION_BUNDLE = (
    Path("infra/production/compose.production.yaml"),
    Path("infra/production/deploy-backend.sh"),
    Path("infra/production/rollback-backend.sh"),
    Path("infra/production/lib.sh"),
    Path("infra/production/deploy-from-ci.sh"),
    Path("infra/production/life-agent-production.sudoers"),
    Path("infra/production/install-production-host.sh"),
)


class WorkflowValidationError(AssertionError):
    """Raised when the backend delivery workflow loses a required guard."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise WorkflowValidationError(message)


def extract_job(text: str, job_id: str) -> str:
    jobs_offset = text.find("\njobs:\n")
    require(jobs_offset >= 0, "missing jobs mapping")
    jobs_text = text[jobs_offset + 1 :]
    starts = list(re.finditer(r"(?m)^  ([a-zA-Z_][a-zA-Z0-9_-]*):\n", jobs_text))
    for index, match in enumerate(starts):
        if match.group(1) != job_id:
            continue
        end = starts[index + 1].start() if index + 1 < len(starts) else len(jobs_text)
        return jobs_text[match.start() : end]
    raise WorkflowValidationError(f"missing {job_id} job")


def extract_step(job_text: str, step_name: str) -> str:
    marker = f"      - name: {step_name}\n"
    start = job_text.find(marker)
    require(start >= 0, f"missing {step_name} step")
    next_step = job_text.find("\n      - name: ", start + len(marker))
    end = next_step if next_step >= 0 else len(job_text)
    return job_text[start:end]


def validate_workflow(text: str) -> None:
    required_fragments = {
        "push trigger": "  push:\n",
        "main branch trigger": "      - main\n",
        "push event gate": "github.event_name == 'push'",
        "main source gate": "github.ref == 'refs/heads/main'",
        "source revision": "SOURCE_SHA: ${{ github.sha }}",
        "CI workflow gate": "            ci.yml\n",
        "instrumented workflow gate": "            android-m1-instrumented.yml\n",
        "exact CI revision query": "--field head_sha=\"$SOURCE_SHA\"",
        "CI success gate": "[ \"$conclusion\" != \"success\" ]",
        "immutable Dockerfile build": "--file backend/Dockerfile",
        "runtime image verification": (
            "backend/scripts/verify-runtime-image.sh \"$immutable_ref\""
        ),
        "GHCR backend repository": (
            'image_repository="ghcr.io/${repository_path}/backend"'
        ),
        "digest-only output": 'echo "image_ref=$immutable_ref" >> "$GITHUB_OUTPUT"',
        "successful publication gate": "needs.publish.result == 'success'",
        "protected environment": "    environment: production\n",
        "tracked deploy script": "infra/production/deploy-backend.sh",
        "fixed root-owned deploy boundary": (
            "/opt/life-agent/production/deploy-from-ci.sh"
        ),
        "tracked bundle attestation": (
            'echo "bundle_digest=sha256:$bundle_hash" >>"$GITHUB_OUTPUT"'
        ),
        "ephemeral registry token": "GHCR_PULL_TOKEN: ${{ github.token }}",
        "registry token over standard input": (
            "            printf '%s' \"$GHCR_PULL_TOKEN\"\n"
        ),
        "noninteractive privilege boundary": "              sudo -n -- \\\n",
        "strict host verification": "printf '  StrictHostKeyChecking yes\\n'",
        "pinned known hosts": "printf '  UserKnownHostsFile %s\\n'",
        "isolated global known hosts": "printf '  GlobalKnownHostsFile /dev/null\\n'",
        "isolated identity agent": "printf '  IdentityAgent none\\n'",
        "public-key-only authentication": "printf '  PasswordAuthentication no\\n'",
        "noninteractive authentication": "printf '  BatchMode yes\\n'",
        "metadata standard-input frame": (
            '              "$PRODUCTION_BUNDLE_DIGEST"\n'
        ),
        "serialized production promotion": "  group: backend-production\n",
        "non-cancelling production promotion": "  cancel-in-progress: false\n",
        "checkout credential isolation": "          persist-credentials: false\n",
    }
    for label, fragment in required_fragments.items():
        require(fragment in text, f"missing {label}")

    require("workflow_run:" not in text, "default-branch workflow-run deployment is forbidden")
    require("workflow_dispatch:" not in text, "manual deployment trigger is forbidden")
    require("      - develop\n" not in text, "develop must never trigger production")
    require("refs/heads/develop" not in text, "develop must never be deployed")
    require(":latest" not in text, "mutable latest image references are forbidden")
    require("contents: write" not in text, "production workflow has excess contents access")
    require("actions: write" not in text, "production workflow has excess actions access")
    require("id-token: write" not in text, "unused identity-token access is forbidden")
    require("actions/upload-artifact" not in text, "deployment data must not be uploaded")
    require("scp -F" not in text, "CI-uploaded deployment scripts are forbidden")
    require(
        "/tmp/life-agent-release." not in text,
        "temporary remote deployment scripts are forbidden",
    )
    require("read:packages" not in text, "PAT package scopes are forbidden")
    require("write:packages" not in text, "PAT package scopes are forbidden")
    require("set -x" not in text, "shell tracing can disclose production credentials")

    extract_job(text, "verified_main")
    publish_job = extract_job(text, "publish")
    deploy_job = extract_job(text, "deploy")
    require(
        "    needs: verified_main\n" in publish_job,
        "publication must depend on exact-main verification",
    )
    require(
        re.search(
            r"(?m)^    needs:\n      - verified_main\n      - publish\n",
            deploy_job,
        )
        is not None,
        "deployment must depend on verification and publication",
    )
    require(
        text.count("packages: write") == 1 and "packages: write" in publish_job,
        "only image publication may write packages",
    )
    require(
        text.count("packages: read") == 1 and "packages: read" in deploy_job,
        "deployment alone must receive package read access",
    )
    require(
        text.count("environment: production") == 1
        and "environment: production" in deploy_job,
        "only the deployment job may enter the production environment",
    )

    validation_step = extract_step(deploy_job, "Validate the tracked production bundle")
    require(
        "Refusing to deploy a revision that is no longer current main." in validation_step,
        "deployment must revalidate current main after environment approval",
    )
    require(
        '"repos/$GITHUB_REPOSITORY/git/ref/heads/main"' in validation_step,
        "deployment must query the final main revision",
    )

    ssh_step = extract_step(
        deploy_job,
        "Deploy the immutable image through the protected host boundary",
    )

    secret_bindings = re.findall(r"\$\{\{\s*secrets\.([A-Z0-9_]+)\s*}}", text)
    require(
        sorted(secret_bindings)
        == sorted(
            [
                "PROD_SSH_HOST",
                "PROD_SSH_USER",
                "PROD_SSH_PRIVATE_KEY",
                "PROD_SSH_HOST_KEY",
            ]
        ),
        "production may consume only the four approved SSH secrets",
    )
    require(
        len(re.findall(r"\$\{\{\s*secrets\.", ssh_step)) == 4,
        "production SSH secrets must be scoped to the SSH step",
    )
    require(
        "Refusing stale main immediately before production deployment." in ssh_step,
        "SSH step must suppress stale main immediately before deployment",
    )
    require(
        "sudo -n --" in ssh_step,
        "sudo must never consume the registry token as a password",
    )
    require(
        "printf '%s' \"$GHCR_PULL_TOKEN\"" in ssh_step,
        "the registry token must travel only over standard input",
    )
    require(
        "/opt/life-agent/production/deploy-from-ci.sh" in ssh_step,
        "deployment must invoke the fixed root-owned entrypoint",
    )
    require(
        re.search(
            r"sudo -n -- \\\n\s+/opt/life-agent/production/deploy-from-ci\.sh\n",
            ssh_step,
        )
        is not None,
        "the constrained sudo entrypoint must receive no command-line arguments",
    )
    require(
        '"$PROD_SSH_USER@$PROD_SSH_HOST"' not in text,
        "SSH coordinates must stay out of process arguments",
    )
    require(
        text.count("^sha256:[0-9a-f]{64}$") >= 2,
        "immutable image references must validate a lowercase SHA-256 digest",
    )


def validate_bundle_state(root: Path = ROOT) -> str:
    present = [path for path in PRODUCTION_BUNDLE if (root / path).is_file()]
    require(
        len(present) in (0, len(PRODUCTION_BUNDLE)),
        "production deployment bundle must be either absent or complete",
    )
    return "complete" if present else "declared-interface"


def validate_ci_integration(text: str) -> None:
    require(
        "permissions:\n  contents: read\n" in text,
        "CI must keep repository contents read-only",
    )
    require(
        "contents: write" not in text,
        "CI must not receive repository contents write access",
    )
    require(
        "          python scripts/validate_production_infra.py\n" in text,
        "CI must run the production infrastructure validator",
    )
    require(
        "          python -m unittest scripts.test_validate_production_infra\n"
        in text,
        "CI must run the production infrastructure regression tests",
    )

    release_job = extract_job(text, "release-source")
    required_release_fragments = {
        "main pull-request release gate": (
            "if: github.event_name == 'pull_request' && github.base_ref == 'main'"
        ),
        "job-scoped GitHub token": "GH_TOKEN: ${{ github.token }}",
        "immutable pull-request head": (
            "RELEASE_HEAD_SHA: ${{ github.event.pull_request.head.sha }}"
        ),
        "immutable pull-request base": (
            "RELEASE_BASE_SHA: ${{ github.event.pull_request.base.sha }}"
        ),
        "head repository identity": (
            "RELEASE_HEAD_REPOSITORY_ID: ${{ github.event.pull_request.head.repo.id }}"
        ),
        "current repository identity": (
            "CURRENT_REPOSITORY_ID: ${{ github.repository_id }}"
        ),
        "fail-closed shell": "set -Eeuo pipefail",
        "read-only API method": "--method GET",
        "pinned REST API version": "X-GitHub-Api-Version: 2022-11-28",
        "exact ref endpoint": (
            '"repos/$CURRENT_REPOSITORY/git/ref/heads/$branch_name"'
        ),
        "exact ref object": "--jq '.object.sha'",
        "same-repository name guard": (
            '[ "$RELEASE_HEAD_REPOSITORY" != "$CURRENT_REPOSITORY" ]'
        ),
        "same-repository ID guard": (
            '[ "$RELEASE_HEAD_REPOSITORY_ID" != "$CURRENT_REPOSITORY_ID" ]'
        ),
        "bounded release namespace": (
            '[[ ! "$RELEASE_HEAD" =~ ^release/[a-z0-9][a-z0-9._/-]{0,119}$ ]]'
        ),
        "lowercase event SHA guard": (
            '[[ ! "$RELEASE_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]'
        ),
        "lowercase base SHA guard": (
            '[[ ! "$RELEASE_BASE_SHA" =~ ^[0-9a-f]{40}$ ]]'
        ),
        "live ref SHA guard": "for observed_sha in",
        "current main ref": "current_main_sha=\"$(read_branch_sha main)\"",
        "current develop ref": "current_develop_sha=\"$(read_branch_sha develop)\"",
        "exact release ref": (
            'current_release_sha="$(read_branch_sha "$RELEASE_HEAD")"'
        ),
        "fresh base requirement": (
            '[ "$RELEASE_BASE_SHA" != "$current_main_sha" ]'
        ),
        "exact head requirement": (
            '[ "$RELEASE_HEAD_SHA" != "$current_release_sha" ]'
        ),
        "main ancestry comparison": (
            '"repos/$CURRENT_REPOSITORY/compare/'
            '$current_main_sha...$RELEASE_HEAD_SHA"'
        ),
        "zero behind requirement": '[ "$compare_behind_by" != "0" ]',
        "allowed compare status": (
            '[[ ! "$compare_status" =~ ^(ahead|identical)$ ]]'
        ),
        "exact compare base": '[ "$compare_base_sha" != "$current_main_sha" ]',
        "exact merge base": (
            '[ "$compare_merge_base_sha" != "$current_main_sha" ]'
        ),
        "git commit tree lookup": (
            '"repos/$CURRENT_REPOSITORY/git/commits/$commit_sha"'
        ),
        "exact commit tree object": "--jq '.tree.sha'",
        "exact develop tree": (
            '[ "$release_tree_sha" != "$develop_tree_sha" ]'
        ),
        "final main ref recheck": (
            '[ "$(read_branch_sha main)" != "$current_main_sha" ]'
        ),
        "final develop ref recheck": (
            '[ "$(read_branch_sha develop)" != "$current_develop_sha" ]'
        ),
        "final release ref recheck": (
            '[ "$(read_branch_sha "$RELEASE_HEAD")" != "$RELEASE_HEAD_SHA" ]'
        ),
    }
    for label, fragment in required_release_fragments.items():
        require(fragment in release_job, f"missing {label}")

    require(
        release_job.count("--method GET") == 3
        and release_job.count("X-GitHub-Api-Version: 2022-11-28") == 3,
        "release-source API calls must stay GET-only and version-pinned",
    )

    require(
        "pull_request_target:" not in text,
        "privileged pull-request-target execution is forbidden",
    )
    require(
        "actions/checkout" not in release_job,
        "release-source must not execute pull-request content",
    )
    require(
        "    permissions:" not in release_job
        and re.search(r"(?m)^\s+[A-Za-z0-9_-]+: write\s*$", text) is None,
        "release-source must not gain write permissions",
    )
    require(
        "exit 0" not in release_job,
        "every release source must pass the complete proof path",
    )
    require(
        "continue-on-error: true" not in release_job
        and "|| true" not in release_job
        and "set +e" not in release_job,
        "release-source must fail closed on API or shell errors",
    )
    require(
        "set -x" not in release_job
        and "--verbose" not in release_job
        and "--cache" not in release_job
        and "actions/cache" not in release_job,
        "release-source must not expose or persist API data",
    )
    require(
        release_job.count("$(read_branch_sha main)") == 2
        and release_job.count("$(read_branch_sha develop)") == 2
        and release_job.count('$(read_branch_sha "$RELEASE_HEAD")') == 2,
        "release refs must be read before and after proof validation",
    )


def main() -> int:
    validate_workflow(WORKFLOW_PATH.read_text(encoding="utf-8"))
    validate_ci_integration(CI_WORKFLOW_PATH.read_text(encoding="utf-8"))
    bundle_state = validate_bundle_state()
    print(
        "PASS: production backend workflow guards are complete; "
        f"bundle={bundle_state}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
