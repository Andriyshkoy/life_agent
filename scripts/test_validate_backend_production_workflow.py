#!/usr/bin/env python3
"""Regression tests for production backend workflow validation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.validate_backend_production_workflow import (
    CI_WORKFLOW_PATH,
    WORKFLOW_PATH,
    WorkflowValidationError,
    validate_bundle_state,
    validate_ci_integration,
    validate_workflow,
)


class BackendProductionWorkflowValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.ci_workflow = CI_WORKFLOW_PATH.read_text(encoding="utf-8")

    def test_repository_workflow_is_complete(self) -> None:
        validate_workflow(self.workflow)
        validate_ci_integration(self.ci_workflow)

    def test_production_infra_gate_cannot_be_removed_from_ci(self) -> None:
        mutated = self.ci_workflow.replace(
            "          python scripts/validate_production_infra.py\n",
            "",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "infrastructure validator"):
            validate_ci_integration(mutated)

    def test_release_source_requires_every_bridge_proof(self) -> None:
        mutations = (
            (
                "RELEASE_HEAD_SHA: ${{ github.event.pull_request.head.sha }}",
                "immutable pull-request head",
            ),
            (
                "RELEASE_BASE_SHA: ${{ github.event.pull_request.base.sha }}",
                "immutable pull-request base",
            ),
            (
                "RELEASE_HEAD_REPOSITORY_ID: "
                "${{ github.event.pull_request.head.repo.id }}",
                "head repository identity",
            ),
            (
                '[ "$RELEASE_HEAD_REPOSITORY" != "$CURRENT_REPOSITORY" ]',
                "same-repository name guard",
            ),
            (
                '[ "$RELEASE_HEAD_REPOSITORY_ID" != "$CURRENT_REPOSITORY_ID" ]',
                "same-repository ID guard",
            ),
            (
                '[[ ! "$RELEASE_HEAD" =~ '
                '^release/[a-z0-9][a-z0-9._/-]{0,119}$ ]]',
                "bounded release namespace",
            ),
            (
                '[[ ! "$RELEASE_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]',
                "lowercase event SHA guard",
            ),
            (
                '[[ ! "$RELEASE_BASE_SHA" =~ ^[0-9a-f]{40}$ ]]',
                "lowercase base SHA guard",
            ),
            (
                '[ "$RELEASE_BASE_SHA" != "$current_main_sha" ]',
                "fresh base requirement",
            ),
            (
                '[ "$RELEASE_HEAD_SHA" != "$current_release_sha" ]',
                "exact head requirement",
            ),
            (
                '"repos/$CURRENT_REPOSITORY/compare/'
                '$current_main_sha...$RELEASE_HEAD_SHA"',
                "main ancestry comparison",
            ),
            ('[ "$compare_behind_by" != "0" ]', "zero behind requirement"),
            (
                '[[ ! "$compare_status" =~ ^(ahead|identical)$ ]]',
                "allowed compare status",
            ),
            (
                '[ "$compare_merge_base_sha" != "$current_main_sha" ]',
                "exact merge base",
            ),
            (
                '[ "$release_tree_sha" != "$develop_tree_sha" ]',
                "exact develop tree",
            ),
            (
                '[ "$(read_branch_sha main)" != "$current_main_sha" ]',
                "final main ref recheck",
            ),
            (
                '[ "$(read_branch_sha develop)" != "$current_develop_sha" ]',
                "final develop ref recheck",
            ),
            (
                '[ "$(read_branch_sha "$RELEASE_HEAD")" '
                '!= "$RELEASE_HEAD_SHA" ]',
                "final release ref recheck",
            ),
        )
        for fragment, message in mutations:
            with self.subTest(message=message):
                self.assertIn(fragment, self.ci_workflow)
                mutated = self.ci_workflow.replace(fragment, "guard-removed", 1)
                with self.assertRaisesRegex(WorkflowValidationError, message):
                    validate_ci_integration(mutated)

    def test_release_source_rejects_error_suppression(self) -> None:
        mutated = self.ci_workflow.replace(
            "            gh api \\\n",
            "            gh api || true \\\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "fail closed"):
            validate_ci_integration(mutated)

    def test_release_source_rejects_proof_shortcut(self) -> None:
        mutated = self.ci_workflow.replace(
            '          current_main_sha="$(read_branch_sha main)"\n',
            "          exit 0\n"
            '          current_main_sha="$(read_branch_sha main)"\n',
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "complete proof path"):
            validate_ci_integration(mutated)

    def test_release_source_rejects_contents_write_access(self) -> None:
        mutated = self.ci_workflow.replace(
            "permissions:\n  contents: read\n",
            "permissions:\n  contents: write\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "read-only"):
            validate_ci_integration(mutated)

    def test_release_source_rejects_job_write_permission(self) -> None:
        mutated = self.ci_workflow.replace(
            "    steps:\n      - name: Require a verified production release source\n",
            "    permissions:\n"
            "      issues: write\n"
            "    steps:\n"
            "      - name: Require a verified production release source\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "write permissions"):
            validate_ci_integration(mutated)

    def test_release_source_api_shape_is_pinned(self) -> None:
        mutations = (
            ("--method GET", "--method POST"),
            (
                "X-GitHub-Api-Version: 2022-11-28",
                "X-GitHub-Api-Version: unpinned",
            ),
            ("--jq '.object.sha'", "--jq '.object.url'"),
            ("--jq '.tree.sha'", "--jq '.tree.url'"),
            (
                '"repos/$CURRENT_REPOSITORY/git/ref/heads/$branch_name"',
                '"repos/$CURRENT_REPOSITORY/git/refs/$branch_name"',
            ),
        )
        for original, replacement in mutations:
            with self.subTest(fragment=original):
                mutated = self.ci_workflow.replace(original, replacement, 1)
                self.assertNotEqual(mutated, self.ci_workflow)
                with self.assertRaises(WorkflowValidationError):
                    validate_ci_integration(mutated)

    def test_release_source_rejects_api_cache(self) -> None:
        mutated = self.ci_workflow.replace(
            "            gh api \\\n",
            "            gh api --cache 1h \\\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "expose or persist"):
            validate_ci_integration(mutated)

    def test_release_source_rejects_checkout(self) -> None:
        mutated = self.ci_workflow.replace(
            "\n  contracts:\n",
            "      - name: Forbidden checkout\n"
            "        uses: actions/checkout@deadbeef\n\n"
            "  contracts:\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "must not execute"):
            validate_ci_integration(mutated)

    def test_release_source_rejects_pull_request_target(self) -> None:
        mutated = self.ci_workflow.replace(
            "  pull_request:\n",
            "  pull_request_target:\n",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "pull-request-target"):
            validate_ci_integration(mutated)

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

    def test_deployment_cannot_drop_package_read_access(self) -> None:
        mutated = self.workflow.replace("      packages: read\n", "", 1)
        with self.assertRaisesRegex(WorkflowValidationError, "package read access"):
            validate_workflow(mutated)

    def test_registry_token_cannot_become_a_sudo_password(self) -> None:
        mutated = self.workflow.replace("sudo -n --", "sudo --", 1)
        with self.assertRaisesRegex(WorkflowValidationError, "privilege boundary"):
            validate_workflow(mutated)

    def test_ci_uploaded_root_script_is_rejected(self) -> None:
        mutated = self.workflow + "\n# scp -F config deploy.sh production:/tmp/\n"
        with self.assertRaisesRegex(WorkflowValidationError, "CI-uploaded"):
            validate_workflow(mutated)

    def test_registry_token_must_use_standard_input(self) -> None:
        mutated = self.workflow.replace(
            "printf '%s' \"$GHCR_PULL_TOKEN\"",
            "echo token-redacted",
            1,
        )
        with self.assertRaisesRegex(WorkflowValidationError, "standard input"):
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
