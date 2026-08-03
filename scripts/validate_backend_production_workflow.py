#!/usr/bin/env python3
"""Dependency-free security checks for the production backend workflow."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = ROOT / ".github/workflows/backend-production.yml"
PRODUCTION_BUNDLE = (
    Path("infra/production/compose.production.yaml"),
    Path("infra/production/deploy-backend.sh"),
    Path("infra/production/rollback-backend.sh"),
    Path("infra/production/lib.sh"),
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
    starts = list(re.finditer(r"(?m)^  ([a-zA-Z_][a-zA-Z0-9_]*):\n", jobs_text))
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
        "strict host verification": "printf '  StrictHostKeyChecking yes\\n'",
        "pinned known hosts": "printf '  UserKnownHostsFile %s\\n'",
        "isolated global known hosts": "printf '  GlobalKnownHostsFile /dev/null\\n'",
        "isolated identity agent": "printf '  IdentityAgent none\\n'",
        "public-key-only authentication": "printf '  PasswordAuthentication no\\n'",
        "noninteractive authentication": "printf '  BatchMode yes\\n'",
        "deploy digest argument": '            "$IMMUTABLE_IMAGE_REF"\n',
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


def main() -> int:
    validate_workflow(WORKFLOW_PATH.read_text(encoding="utf-8"))
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
