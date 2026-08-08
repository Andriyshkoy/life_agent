#!/usr/bin/env python3
"""Regression tests for the Android development APK release boundary."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.validate_android_distribution_workflow import (
    ANDROID_BUILD,
    ANDROID_ROOT_BUILD,
    CI_WORKFLOW,
    DISTRIBUTION_WORKFLOW,
    INSTRUMENTED_WORKFLOW,
    AndroidDistributionValidationError,
    validate_android_build,
    validate_android_root_build,
    validate_distribution_workflow,
    validate_generic_ci,
    validate_instrumented_workflow,
    validate_no_server_configuration,
)


class AndroidDistributionWorkflowValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.distribution = DISTRIBUTION_WORKFLOW.read_text(encoding="utf-8")
        cls.ci = CI_WORKFLOW.read_text(encoding="utf-8")
        cls.instrumented = INSTRUMENTED_WORKFLOW.read_text(encoding="utf-8")
        cls.android_build = ANDROID_BUILD.read_text(encoding="utf-8")
        cls.android_root_build = ANDROID_ROOT_BUILD.read_text(encoding="utf-8")

    def test_repository_workflows_keep_the_trust_boundary(self) -> None:
        validate_distribution_workflow(self.distribution)
        validate_generic_ci(self.ci)
        validate_generic_ci(self.instrumented)
        validate_instrumented_workflow(self.instrumented)
        validate_android_build(self.android_build)
        validate_android_root_build(self.android_root_build)

    def test_distribution_requires_same_repository_source(self) -> None:
        mutated = self.distribution.replace(
            "github.event.workflow_run.head_repository.full_name == github.repository",
            "true",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "same-repository",
        ):
            validate_distribution_workflow(mutated)

    def test_distribution_checkout_must_use_verified_revision(self) -> None:
        mutated = self.distribution.replace(
            "ref: ${{ env.SOURCE_SHA }}",
            "ref: develop",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "verified checkout",
        ):
            validate_distribution_workflow(mutated)

    def test_distribution_must_build_internal_apk_itself(self) -> None:
        mutated = self.distribution.replace(
            "./gradlew --no-daemon --stacktrace :app:assembleInternal",
            "./gradlew --no-daemon --stacktrace :app:assembleDebug",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "Android build token",
        ):
            validate_distribution_workflow(mutated)

    def test_signing_material_must_come_from_environment_secret(self) -> None:
        mutated = self.distribution.replace(
            "secrets.LIFE_AGENT_DEV_KEYSTORE_BASE64",
            "vars.LIFE_AGENT_DEV_KEYSTORE_BASE64",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "signing or identity guard",
        ):
            validate_distribution_workflow(mutated)

    def test_external_signature_verification_cannot_be_removed(self) -> None:
        mutated = self.distribution.replace(
            "              verify",
            "              version",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "signing or identity guard",
        ):
            validate_distribution_workflow(mutated)

    def test_release_asset_digest_verification_cannot_be_removed(self) -> None:
        mutated = self.distribution.replace(
            'if [ "$asset_digest" != "$expected_digest" ]',
            'if [ -z "$asset_digest" ]',
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "rolling release guard",
        ):
            validate_distribution_workflow(mutated)

    def test_generic_ci_cannot_duplicate_internal_build(self) -> None:
        mutated = self.ci + "\n# :app:assembleInternal\n"
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "duplicate",
        ):
            validate_generic_ci(mutated)

    def test_generic_ci_cannot_restore_backend_job(self) -> None:
        mutated = self.ci + "\n  backend-quality:\n"
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "retired job",
        ):
            validate_generic_ci(mutated)

    def test_instrumented_check_name_cannot_drift(self) -> None:
        mutated = self.instrumented.replace(
            "    name: android-instrumented",
            "    name: android-milestone-instrumented",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "instrumented workflow token",
        ):
            validate_instrumented_workflow(mutated)

    def test_android_build_cannot_embed_server_configuration(self) -> None:
        mutated = self.android_build + "\n// LIFE_AGENT_API_ORIGIN\n"
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "retired configuration",
        ):
            validate_android_build(mutated)

    def test_android_build_cannot_embed_distribution_signing(self) -> None:
        mutated = self.android_build + '\nsigningConfigs.create("distribution")\n'
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "retired configuration",
        ):
            validate_android_build(mutated)

    def test_android_build_cannot_restore_network_dependency(self) -> None:
        mutated = self.android_build + '\nimplementation("com.squareup.okhttp3:okhttp:5.3.2")\n'
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "retired configuration",
        ):
            validate_android_build(mutated)

    def test_root_build_cannot_restore_serialization_compiler_plugin(self) -> None:
        mutated = (
            self.android_root_build
            + '\nid("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"\n'
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "serialization compiler plugin",
        ):
            validate_android_root_build(mutated)

    def test_server_configuration_is_rejected_from_android_source(self) -> None:
        path = self.write_temporary_source("// LIFE_AGENT_API_SPKI_PINS\n")
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "retired server configuration",
        ):
            validate_no_server_configuration([path])

    def write_temporary_source(self, content: str) -> Path:
        temporary_directory = tempfile.TemporaryDirectory(
            dir=DISTRIBUTION_WORKFLOW.parent
        )
        self.addCleanup(temporary_directory.cleanup)
        path = Path(temporary_directory.name) / "fixture.txt"
        path.write_text(content, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
