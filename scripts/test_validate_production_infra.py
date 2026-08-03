from __future__ import annotations

import importlib.util
import os
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ENTRYPOINT = ROOT / "backend" / "container-entrypoint.sh"
VALIDATOR_PATH = ROOT / "scripts" / "validate_production_infra.py"
SPEC = importlib.util.spec_from_file_location(
    "validate_production_infra", VALIDATOR_PATH
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load production infra validator")
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


def clean_environment() -> dict[str, str]:
    return {
        name: value
        for name, value in os.environ.items()
        if not name.startswith("LIFE_AGENT_")
    }


class ContainerEntrypointTests(unittest.TestCase):
    def test_direct_environment_remains_compatible(self) -> None:
        environment = clean_environment()
        environment["LIFE_AGENT_DATABASE_URL"] = "opaque-test-value"
        result = subprocess.run(
            [
                str(ENTRYPOINT),
                "sh",
                "-c",
                'test "$LIFE_AGENT_DATABASE_URL" = opaque-test-value',
            ],
            cwd=ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_direct_value_and_file_are_mutually_exclusive(self) -> None:
        environment = clean_environment()
        environment["LIFE_AGENT_DATABASE_URL"] = "must-not-appear"
        environment["LIFE_AGENT_DATABASE_URL_FILE"] = (
            "/run/secrets/life_agent_database_url"
        )
        result = subprocess.run(
            [str(ENTRYPOINT), "true"],
            cwd=ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 78)
        self.assertNotIn("must-not-appear", result.stderr)

    def test_arbitrary_secret_path_is_rejected(self) -> None:
        environment = clean_environment()
        environment["LIFE_AGENT_DATABASE_URL_FILE"] = "/tmp/not-a-runtime-secret"
        result = subprocess.run(
            [str(ENTRYPOINT), "true"],
            cwd=ROOT,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 78)


class ProductionBoundaryTests(unittest.TestCase):
    def test_compose_policy(self) -> None:
        VALIDATOR.validate_compose()

    def test_nginx_policy(self) -> None:
        VALIDATOR.validate_nginx()

    def test_shell_and_digest_policy(self) -> None:
        VALIDATOR.validate_shell_and_image_policy()


if __name__ == "__main__":
    unittest.main()
