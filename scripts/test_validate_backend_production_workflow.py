#!/usr/bin/env python3
"""Regression tests for production backend workflow validation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.validate_backend_production_workflow import (
    WORKFLOW_PATH,
    WorkflowValidationError,
    validate_bundle_state,
    validate_workflow,
)


class BackendProductionWorkflowValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

    def test_repository_workflow_is_complete(self) -> None:
        validate_workflow(self.workflow)

    def test_develop_trigger_is_rejected(self) -> None:
        mutated = self.workflow.replace(
            "      - main\n",
            "      - develop\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "main branch trigger"):
            validate_workflow(mutated)

    def test_mutable_latest_reference_is_rejected(self) -> None:
        mutated = self.workflow + "\n# ghcr.io/example/backend:latest\n"
        with self.assertRaisesRegex(WorkflowValidationError, "mutable latest"):
            validate_workflow(mutated)

    def test_production_environment_cannot_be_removed(self) -> None:
        mutated = self.workflow.replace("    environment: production\n", "", 1)
        with self.assertRaisesRegex(WorkflowValidationError, "protected environment"):
            validate_workflow(mutated)

    def test_accept_new_host_key_policy_is_rejected(self) -> None:
        mutated = self.workflow.replace(
            "StrictHostKeyChecking yes",
            "StrictHostKeyChecking accept-new",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "strict host verification"):
            validate_workflow(mutated)

    def test_system_known_hosts_cannot_be_reenabled(self) -> None:
        mutated = self.workflow.replace(
            "GlobalKnownHostsFile /dev/null",
            "GlobalKnownHostsFile /etc/ssh/ssh_known_hosts",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "global known hosts"):
            validate_workflow(mutated)

    def test_extra_production_secret_is_rejected(self) -> None:
        mutated = self.workflow.replace(
            "      PROD_SSH_HOST_KEY: ${{ secrets.PROD_SSH_HOST_KEY }}\n",
            "      PROD_SSH_HOST_KEY: ${{ secrets.PROD_SSH_HOST_KEY }}\n"
            "      EXTRA_SECRET: ${{ secrets.EXTRA_SECRET }}\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "approved SSH secrets"):
            validate_workflow(mutated)

    def test_deployment_cannot_drop_publication_dependency(self) -> None:
        mutated = self.workflow.replace("      - publish\n", "", 1)
        with self.assertRaisesRegex(WorkflowValidationError, "depend on verification"):
            validate_workflow(mutated)

    def test_final_current_main_check_cannot_be_removed(self) -> None:
        mutated = self.workflow.replace(
            "Refusing to deploy a revision that is no longer current main.",
            "Source changed.",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "revalidate current main"):
            validate_workflow(mutated)

    def test_secrets_cannot_move_to_job_scope(self) -> None:
        binding = "          PROD_SSH_HOST: ${{ secrets.PROD_SSH_HOST }}\n"
        mutated = self.workflow.replace(binding, "", 1).replace(
            "    steps:\n",
            "    env:\n"
            "      PROD_SSH_HOST: ${{ secrets.PROD_SSH_HOST }}\n"
            "    steps:\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "scoped to the SSH step"):
            validate_workflow(mutated)

    def test_partial_production_bundle_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "infra/production/deploy-backend.sh"
            path.parent.mkdir(parents=True)
            path.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            with self.assertRaisesRegex(WorkflowValidationError, "absent or complete"):
                validate_bundle_state(root)


if __name__ == "__main__":
    unittest.main()
