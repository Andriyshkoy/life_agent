#!/usr/bin/env python3
"""Dependency-free guards for the Android development APK release path."""

from __future__ import annotations

import sys
from collections.abc import Iterable
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DISTRIBUTION_WORKFLOW = ROOT / ".github/workflows/android-dev-distribution.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
INSTRUMENTED_WORKFLOW = ROOT / ".github/workflows/android-instrumented.yml"
ANDROID_BUILD = ROOT / "android/life-agent/app/build.gradle.kts"
ANDROID_ROOT_BUILD = ROOT / "android/life-agent/build.gradle.kts"
ANDROID_MAIN = ROOT / "android/life-agent/app/src/main"

RETIRED_ACTIVE_PATHS = (
    ROOT / ".github/workflows/android-m1-instrumented.yml",
    ROOT / "scripts/run_android_m1_instrumented_ci.sh",
    ROOT / "scripts/run_android_m1_cold_start_smoke.sh",
)

SERVER_CONFIGURATION_TOKENS = (
    "LIFE_AGENT_API_ORIGIN",
    "LIFE_AGENT_API_SPKI_PINS",
)


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

    ordered_steps = (
        "      - name: Check out the verified develop revision",
        "      - name: Build pinned Android toolchain image",
        "      - name: Build unsigned internal APK",
        "      - name: Validate internal Android security declarations",
        "      - name: Prepare signing material",
        "      - name: Sign and verify APK",
        "      - name: Publish rolling prerelease",
    )
    for step in ordered_steps:
        require(step in text, f"missing distribution step: {step.strip()}")
    require(
        [text.index(step) for step in ordered_steps]
        == sorted(text.index(step) for step in ordered_steps),
        "distribution steps are out of order",
    )

    required_checkout_tokens = (
        "ref: ${{ env.SOURCE_SHA }}",
        "persist-credentials: false",
    )
    for token in required_checkout_tokens:
        require(token in text, f"missing verified checkout token: {token}")

    required_build_tokens = (
        "android/life-agent/Dockerfile.build",
        "life-agent-product-builder:distribution",
        '--env LIFE_AGENT_VERSION_CODE="$version_code"',
        '--env LIFE_AGENT_VERSION_NAME="0.1.0.$SOURCE_RUN_NUMBER+$short_sha"',
        "./gradlew --no-daemon --stacktrace :app:assembleInternal",
        "android/life-agent/app/build/outputs/apk/internal/app-internal-unsigned.apk",
        "python3 scripts/validate_android_security.py",
        "--merged-manifest",
        (
            "android/life-agent/app/build/intermediates/merged_manifests/"
            "internal/processInternalManifest/AndroidManifest.xml"
        ),
    )
    for token in required_build_tokens:
        require(token in text, f"missing pinned Android build token: {token}")

    required_signing_tokens = (
        "KEYSTORE_BASE64: ${{ secrets.LIFE_AGENT_DEV_KEYSTORE_BASE64 }}",
        (
            "LIFE_AGENT_KEYSTORE_PASSWORD: "
            "${{ secrets.LIFE_AGENT_DEV_KEYSTORE_PASSWORD }}"
        ),
        "LIFE_AGENT_KEY_ALIAS: life-agent-dev",
        "apksigner \\\n              sign",
        "--ks-pass env:LIFE_AGENT_KEYSTORE_PASSWORD",
        "--key-pass env:LIFE_AGENT_KEY_PASSWORD",
        "apksigner \\\n              verify",
        "zipalign \\\n              -c",
        'LIFE_AGENT_PACKAGE_NAME" != "ru.andriyshkoy.lifeagent.dev"',
        '[[ "$LIFE_AGENT_VERSION_NAME" != *-dev ]]',
    )
    for token in required_signing_tokens:
        require(token in text, f"missing APK signing or identity guard: {token}")

    required_publication_tokens = (
        'if [ "$current_develop_sha" != "$SOURCE_SHA" ]',
        "gh release upload dev-latest",
        "gh release create dev-latest",
        'if [ "$tag_sha" != "$SOURCE_SHA" ]',
        'if [ "$release_target" != "$SOURCE_SHA" ]',
        'if [ "$asset_digest" != "$expected_digest" ]',
        "--draft=false",
        'if [ "$is_draft" != "false" ]',
    )
    for token in required_publication_tokens:
        require(token in text, f"missing rolling release guard: {token}")

    require(
        "gh run download" not in text,
        "distribution must build the APK from the verified source revision",
    )
    require(
        "life-agent-internal-unsigned" not in text,
        "distribution must not consume the generic CI artifact",
    )
    for token in SERVER_CONFIGURATION_TOKENS:
        require(token not in text, f"server configuration leaked into distribution: {token}")


def validate_generic_ci(text: str) -> None:
    require(
        "environment: dev-distribution" not in text,
        "generic CI must not bind the distribution environment",
    )
    require(
        ":app:assembleInternal" not in text,
        "generic CI must not duplicate the trusted internal APK build",
    )
    require(
        "life-agent-internal-unsigned" not in text,
        "generic CI must not publish an unused unsigned internal APK",
    )
    for retired_job in (
        "  backend-quality:",
        "  backend-postgres:",
        "  backend-container:",
        "  android-probe:",
    ):
        require(retired_job not in text, f"generic CI contains retired job: {retired_job}")
    for token in SERVER_CONFIGURATION_TOKENS:
        require(token not in text, f"server configuration leaked into generic CI: {token}")


def validate_instrumented_workflow(text: str) -> None:
    required_tokens = (
        "name: android-instrumented\n",
        "  android-instrumented:\n",
        "    name: android-instrumented\n",
        "scripts/run_android_local_instrumented_ci.sh",
        "scripts/run_android_local_cold_start_smoke.sh",
        "app/build/outputs/local-cold-start",
        "android-instrumented-diagnostics-${{ github.run_id }}",
    )
    for token in required_tokens:
        require(token in text, f"missing local instrumented workflow token: {token}")
    for token in ("android-m1", "run_android_m1", "m1-cold-start"):
        require(
            token not in text,
            f"instrumented workflow contains retired milestone name: {token}",
        )


def validate_android_build(text: str) -> None:
    required_local_build_tokens = (
        'create("internal")',
        'applicationIdSuffix = ".dev"',
        'versionNameSuffix = "$internalVersionNameSuffix-dev"',
        "buildConfig = true",
    )
    for token in required_local_build_tokens:
        require(token in text, f"missing Android build contract: {token}")

    forbidden_tokens = (
        *SERVER_CONFIGURATION_TOKENS,
        'environmentVariable("LIFE_AGENT_KEYSTORE_PATH")',
        'environmentVariable("LIFE_AGENT_KEYSTORE_PASSWORD")',
        'environmentVariable("LIFE_AGENT_KEY_ALIAS")',
        'environmentVariable("LIFE_AGENT_KEY_PASSWORD")',
        'signingConfigs.create("distribution")',
        "signingConfig = distributionSigningConfig",
        "androidx.work:",
        "com.squareup.okhttp3:",
        "mockwebserver",
        "okhttp-tls",
        'create("m1Api35")',
    )
    for token in forbidden_tokens:
        require(token not in text, f"Android build contains retired configuration: {token}")


def validate_android_root_build(text: str) -> None:
    require(
        "org.jetbrains.kotlin.plugin.serialization" not in text,
        "Android root build contains the unused serialization compiler plugin",
    )


def validate_repository_shape() -> None:
    for path in RETIRED_ACTIVE_PATHS:
        require(
            not path.exists(),
            f"retired active build artifact remains: {path.relative_to(ROOT)}",
        )


def validate_no_server_configuration(paths: Iterable[Path]) -> None:
    for path in paths:
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for token in SERVER_CONFIGURATION_TOKENS:
            require(
                token not in text,
                f"tracked Android source contains retired server configuration: "
                f"{path.relative_to(ROOT)}",
            )


def android_source_paths() -> list[Path]:
    return [
        DISTRIBUTION_WORKFLOW,
        CI_WORKFLOW,
        INSTRUMENTED_WORKFLOW,
        ANDROID_BUILD,
        *(path for path in ANDROID_MAIN.rglob("*") if path.is_file()),
    ]


def validate_all() -> None:
    validate_distribution_workflow(DISTRIBUTION_WORKFLOW.read_text(encoding="utf-8"))
    validate_generic_ci(CI_WORKFLOW.read_text(encoding="utf-8"))
    instrumented_text = INSTRUMENTED_WORKFLOW.read_text(encoding="utf-8")
    validate_generic_ci(instrumented_text)
    validate_instrumented_workflow(instrumented_text)
    validate_android_build(ANDROID_BUILD.read_text(encoding="utf-8"))
    validate_android_root_build(ANDROID_ROOT_BUILD.read_text(encoding="utf-8"))
    validate_repository_shape()
    validate_no_server_configuration(android_source_paths())


def main() -> int:
    validate_all()
    print(
        "PASS: Android development APK is rebuilt from verified source, "
        "signed externally, and published without server configuration"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AndroidDistributionValidationError, OSError, UnicodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
