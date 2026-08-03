#!/usr/bin/env python3
"""Regression tests for the Android dev distribution trust boundary."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.validate_android_distribution_workflow import (
    ANDROID_BUILD,
    CI_WORKFLOW,
    DISTRIBUTION_WORKFLOW,
    INSTRUMENTED_WORKFLOW,
    AndroidDistributionValidationError,
    validate_android_build_defaults,
    validate_distribution_workflow,
    validate_no_tracked_deployment_coordinates,
    validate_untrusted_ci,
)


class AndroidDistributionWorkflowValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.distribution = DISTRIBUTION_WORKFLOW.read_text(encoding="utf-8")
        cls.ci = CI_WORKFLOW.read_text(encoding="utf-8")
        cls.instrumented = INSTRUMENTED_WORKFLOW.read_text(encoding="utf-8")
        cls.android_build = ANDROID_BUILD.read_text(encoding="utf-8")

    def test_repository_workflows_keep_the_trust_boundary(self) -> None:
        validate_distribution_workflow(self.distribution)
        validate_untrusted_ci(self.ci)
        validate_untrusted_ci(self.instrumented)
        validate_android_build_defaults(self.android_build)

    def test_distribution_variable_cannot_move_to_secrets(self) -> None:
        mutated = self.distribution.replace(
            "vars.LIFE_AGENT_API_ORIGIN",
            "secrets.LIFE_AGENT_API_ORIGIN",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "trusted GitHub Variables",
        ):
            validate_distribution_workflow(mutated)

    def test_distribution_variable_must_be_checked_before_checkout(self) -> None:
        mutated = self.distribution.replace(
            '! is_non_blank "$LIFE_AGENT_API_ORIGIN" ||\n',
            "",
            1,
        )
        with self.assertRaisesRegex(AndroidDistributionValidationError, "blank input"):
            validate_distribution_workflow(mutated)

    def test_distribution_variable_must_reach_the_container_by_name(self) -> None:
        mutated = self.distribution.replace(
            "            --env LIFE_AGENT_API_SPKI_PINS \\\n",
            "",
            1,
        )
        with self.assertRaisesRegex(
            AndroidDistributionValidationError, "forwarded by name"
        ):
            validate_distribution_workflow(mutated)

    def test_untrusted_ci_cannot_receive_distribution_variables(self) -> None:
        mutated = self.ci + "\n# LIFE_AGENT_API_ORIGIN\n"
        with self.assertRaisesRegex(
            AndroidDistributionValidationError,
            "untrusted CI",
        ):
            validate_untrusted_ci(mutated)

    def test_android_build_variables_must_default_to_empty(self) -> None:
        mutated = self.android_build.replace(
            '.environmentVariable("LIFE_AGENT_API_ORIGIN")\n    .orElse("")',
            '.environmentVariable("LIFE_AGENT_API_ORIGIN")',
            1,
        )
        with self.assertRaisesRegex(AndroidDistributionValidationError, "default"):
            validate_android_build_defaults(mutated)

    def test_concrete_coordinate_is_rejected_from_tracked_deployment_source(
        self,
    ) -> None:
        coordinate = "https://" + "prod.example.invalid"
        path = self.write_temporary_source(f'val endpoint = "{coordinate}"\n')
        with self.assertRaisesRegex(
            AndroidDistributionValidationError, "HTTPS coordinate"
        ):
            validate_no_tracked_deployment_coordinates([path])

    def test_concrete_pin_is_rejected_from_tracked_deployment_source(self) -> None:
        pin = "sha256/" + ("A" * 43) + "="
        path = self.write_temporary_source(f'val pin = "{pin}"\n')
        with self.assertRaisesRegex(AndroidDistributionValidationError, "SPKI pin"):
            validate_no_tracked_deployment_coordinates([path])

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
