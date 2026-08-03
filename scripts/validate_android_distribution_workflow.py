#!/usr/bin/env python3
"""Dependency-free guards for trusted Android dev distribution configuration."""

from __future__ import annotations

import re
import sys
from collections.abc import Iterable
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DISTRIBUTION_WORKFLOW = ROOT / ".github/workflows/android-dev-distribution.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
INSTRUMENTED_WORKFLOW = ROOT / ".github/workflows/android-m1-instrumented.yml"
ANDROID_BUILD = ROOT / "android/life-agent/app/build.gradle.kts"
ANDROID_MAIN = ROOT / "android/life-agent/app/src/main"

DEPLOYMENT_VARIABLES = (
    "LIFE_AGENT_API_ORIGIN",
    "LIFE_AGENT_API_SPKI_PINS",
)
CONCRETE_HTTPS_COORDINATE = re.compile(r"https://[A-Za-z0-9]")
CONCRETE_SPKI_PIN = re.compile(r"sha256/[A-Za-z0-9+/]{43}=")


class AndroidDistributionValidationError(AssertionError):
    """Raised when the Android distribution trust boundary drifts."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AndroidDistributionValidationError(message)


def validate_distribution_workflow(text: str) -> None:
    required_trust_guards = {
        "successful upstream CI": "github.event.workflow_run.conclusion == 'success'",
        "push-only upstream event": "github.event.workflow_run.event == 'push'",
        "develop-only upstream branch": (
            "github.event.workflow_run.head_branch == 'develop'"
        ),
        "same-repository upstream source": (
            "github.event.workflow_run.head_repository.full_name == github.repository"
        ),
        "protected distribution environment": "    environment: dev-distribution\n",
        "verified source revision": (
            "SOURCE_SHA: ${{ github.event.workflow_run.head_sha }}"
        ),
        "verified source run number": (
            "SOURCE_RUN_NUMBER: ${{ github.event.workflow_run.run_number }}"
        ),
    }
    for label, token in required_trust_guards.items():
        require(token in text, f"missing {label}")

    configuration_step = (
        "      - name: Require trusted Android deployment configuration"
    )
    checkout_step = "      - name: Check out the verified develop revision"
    build_step = "      - name: Build configured unsigned internal APK"
    require(configuration_step in text, "missing early deployment configuration gate")
    require(checkout_step in text, "missing verified-revision checkout")
    require(build_step in text, "missing configured internal APK build")
    require(
        text.index(configuration_step)
        < text.index(checkout_step)
        < text.index(build_step),
        "deployment configuration must fail before checkout and build",
    )

    gate = text[text.index(configuration_step) : text.index(checkout_step)]
    build = text[text.index(build_step) :]
    for variable in DEPLOYMENT_VARIABLES:
        binding = f"{variable}: ${{{{ vars.{variable} }}}}"
        require(binding in text, f"{variable} must come from trusted GitHub Variables")
        require(f'"${variable}"' in gate, f"{variable} is not checked for blank input")
        require(
            f"--env {variable} \\\n" in build,
            f"{variable} is not forwarded by name into the pinned build container",
        )
        require(
            f"secrets.{variable}" not in text,
            f"public deployment configuration must not use GitHub Secrets: {variable}",
        )
        require(
            not re.search(rf"(?:echo|printf)[^\n]*\${variable}", gate),
            f"configuration gate must not print {variable}",
        )

    required_build_tokens = (
        "android/life-agent/Dockerfile.build",
        "life-agent-product-builder:distribution",
        "./gradlew --no-daemon --stacktrace :app:assembleInternal",
        "android/life-agent/app/build/outputs/apk/internal/app-internal-unsigned.apk",
    )
    for token in required_build_tokens:
        require(token in text, f"missing pinned configured build token: {token}")
    require(
        "gh run download" not in text, "configured APK must not reuse the empty CI APK"
    )
    require(
        "life-agent-internal-unsigned" not in text,
        "configured APK must be rebuilt inside the trusted job",
    )


def validate_untrusted_ci(text: str) -> None:
    for variable in DEPLOYMENT_VARIABLES:
        require(
            variable not in text,
            f"untrusted CI must not receive deployment configuration: {variable}",
        )
    require(
        "environment: dev-distribution" not in text,
        "untrusted CI must not bind the distribution environment",
    )


def validate_android_build_defaults(text: str) -> None:
    for variable in DEPLOYMENT_VARIABLES:
        declaration = f'.environmentVariable("{variable}")\n    .orElse("")'
        require(
            declaration in text,
            f"{variable} must default to an empty BuildConfig value",
        )


def validate_no_tracked_deployment_coordinates(paths: Iterable[Path]) -> None:
    for path in paths:
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        relative = path.relative_to(ROOT)
        require(
            CONCRETE_HTTPS_COORDINATE.search(text) is None,
            f"tracked Android deployment source contains an HTTPS coordinate: {relative}",
        )
        require(
            CONCRETE_SPKI_PIN.search(text) is None,
            f"tracked Android deployment source contains an SPKI pin: {relative}",
        )


def deployment_source_paths() -> list[Path]:
    return [
        DISTRIBUTION_WORKFLOW,
        CI_WORKFLOW,
        INSTRUMENTED_WORKFLOW,
        ANDROID_BUILD,
        *(path for path in ANDROID_MAIN.rglob("*") if path.is_file()),
    ]


def validate_all() -> None:
    validate_distribution_workflow(DISTRIBUTION_WORKFLOW.read_text(encoding="utf-8"))
    validate_untrusted_ci(CI_WORKFLOW.read_text(encoding="utf-8"))
    validate_untrusted_ci(INSTRUMENTED_WORKFLOW.read_text(encoding="utf-8"))
    validate_android_build_defaults(ANDROID_BUILD.read_text(encoding="utf-8"))
    validate_no_tracked_deployment_coordinates(deployment_source_paths())


def main() -> int:
    validate_all()
    print(
        "PASS: trusted Android distribution configuration is fail-closed; "
        "untrusted CI remains deployment-free"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AndroidDistributionValidationError, OSError, UnicodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
