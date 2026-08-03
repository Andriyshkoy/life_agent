#!/usr/bin/env python3
"""Dependency-free production Compose, nginx, and deployment policy checks."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "infra" / "production"
COMPOSE = PRODUCTION / "compose.production.yaml"
EXAMPLE_ENV = PRODUCTION / "production.env.example"
NGINX_HTTP = ROOT / "infra" / "nginx" / "life.andriyshkoy.ru.http.conf"
NGINX_HTTPS = ROOT / "infra" / "nginx" / "life.andriyshkoy.ru.https.conf"
DUMMY_IMAGE = "ghcr.io/andriyshkoy/life_agent/backend@sha256:" + "a" * 64
POSTGRES_IMAGE = (
    "postgres:17.9-bookworm@sha256:"
    "47f917f7409eacd22fc5dfb1dee634e1b55cf0c01d1a7eb701be2227a03e0641"
)
API_ROUTES = {
    "/api/v1/auth/enroll",
    "/api/v1/auth/refresh",
    "/api/v1/auth/revoke",
    "/api/v1/sync/bootstrap",
    "/api/v1/sync/pull",
    "/api/v1/sync/push",
}
RAW_SECRET_ENVIRONMENT_NAMES = {
    "LIFE_AGENT_DATABASE_URL",
    "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY",
    "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY",
    "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY",
    "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY",
    "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY",
    "LIFE_AGENT_CURSOR_HMAC_KEY",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def compose_model() -> dict[str, Any]:
    environment = os.environ.copy()
    environment["LIFE_AGENT_BACKEND_IMAGE"] = DUMMY_IMAGE
    result = subprocess.run(
        [
            "docker",
            "compose",
            "--env-file",
            str(EXAMPLE_ENV),
            "--file",
            str(COMPOSE),
            "--profile",
            "operations",
            "config",
            "--format",
            "json",
        ],
        cwd=ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def assert_hardened(
    service_name: str,
    service: dict[str, Any],
    user: str,
    *,
    networks: tuple[str, ...] = ("private",),
) -> None:
    if service.get("user") != user:
        fail(f"{service_name} must run as {user}")
    if service.get("read_only") is not True:
        fail(f"{service_name} root filesystem must be read-only")
    if service.get("cap_drop") != ["ALL"]:
        fail(f"{service_name} must drop all capabilities")
    if "no-new-privileges:true" not in service.get("security_opt", []):
        fail(f"{service_name} must forbid privilege escalation")
    if not service.get("tmpfs"):
        fail(f"{service_name} must declare bounded writable tmpfs paths")
    for key in ("pids_limit", "mem_limit", "cpus"):
        if not service.get(key):
            fail(f"{service_name} is missing {key}")
    logging = service.get("logging", {})
    if logging.get("driver") != "json-file" or logging.get("options") != {
        "max-file": "3",
        "max-size": "10m",
    }:
        fail(f"{service_name} must use bounded local logs")
    if set(service.get("networks", {})) != set(networks):
        fail(f"{service_name} has an unexpected network attachment")


def validate_compose() -> None:
    model = compose_model()
    services = model.get("services", {})
    if set(services) != {"api", "database-role-init", "migrate", "postgres"}:
        fail("production Compose has an unexpected service set")
    if model.get("networks", {}).get("private", {}).get("internal") is not True:
        fail("production network must be internal")
    if model.get("networks", {}).get("edge", {}).get("internal") is True:
        fail("loopback publication requires a separate edge bridge")

    api = services["api"]
    database_role_init = services["database-role-init"]
    migrate = services["migrate"]
    postgres = services["postgres"]
    assert_hardened("api", api, "10001:10001", networks=("private", "edge"))
    assert_hardened("migrate", migrate, "10001:10001")
    assert_hardened("database-role-init", database_role_init, "999:999")
    assert_hardened("postgres", postgres, "999:999")

    if api.get("image") != DUMMY_IMAGE or migrate.get("image") != DUMMY_IMAGE:
        fail("API and migration job must share the supplied immutable image")
    if (
        postgres.get("image") != POSTGRES_IMAGE
        or database_role_init.get("image") != POSTGRES_IMAGE
    ):
        fail("PostgreSQL 17 image must be pinned by digest")
    if postgres.get("ports"):
        fail("PostgreSQL must not publish a host port")
    postgres_volumes = postgres.get("volumes", [])
    if not any(
        volume.get("type") == "volume"
        and volume.get("target") == "/var/lib/postgresql/data"
        for volume in postgres_volumes
    ):
        fail("PostgreSQL data must use a persistent named volume")

    ports = api.get("ports", [])
    if ports != [
        {
            "mode": "ingress",
            "host_ip": "127.0.0.1",
            "target": 8080,
            "published": "18080",
            "protocol": "tcp",
        }
    ]:
        fail("API must publish only the expected loopback endpoint")
    api_health = " ".join(api.get("healthcheck", {}).get("test", []))
    if "/readyz" not in api_health or "/healthz" in api_health:
        fail("API container health must use internal readiness")
    if api.get("restart") != "unless-stopped":
        fail("API must restart unless explicitly stopped")

    if migrate.get("command") != ["alembic", "upgrade", "head"]:
        fail("migration must be an explicit Alembic upgrade-to-head job")
    if migrate.get("restart") not in (None, "no"):
        fail("migration job must never restart")
    if migrate.get("profiles") != ["operations"]:
        fail("migration job must remain opt-in")
    migration_environment = migrate.get("environment", {})
    api_environment = api.get("environment", {})
    if migration_environment.get("LIFE_AGENT_DATABASE_URL_FILE") != (
        "/run/secrets/life_agent_migration_database_url"
    ):
        fail("migration job must use the owner-scoped database URL")
    if api_environment.get("LIFE_AGENT_DATABASE_URL_FILE") != (
        "/run/secrets/life_agent_runtime_database_url"
    ):
        fail("API must use the restricted runtime database URL")
    role_init_command = " ".join(database_role_init.get("command", []))
    for required_fragment in (
        "life_agent_runtime",
        "NOSUPERUSER",
        "NOCREATEDB",
        "NOCREATEROLE",
        "NOINHERIT",
        "REVOKE CREATE ON SCHEMA public",
        "GRANT SELECT, INSERT, UPDATE, DELETE",
        "ALTER DEFAULT PRIVILEGES",
    ):
        if required_fragment not in role_init_command:
            fail("database role initializer does not enforce least privilege")
    if database_role_init.get("restart") not in (None, "no"):
        fail("database role initializer must never restart")
    if postgres.get("restart") != "unless-stopped":
        fail("PostgreSQL must restart unless explicitly stopped")

    for service_name in ("api", "migrate"):
        environment = services[service_name].get("environment", {})
        if RAW_SECRET_ENVIRONMENT_NAMES.intersection(environment):
            fail(f"{service_name} embeds a raw secret environment variable")
        expected_secret_names = (
            RAW_SECRET_ENVIRONMENT_NAMES
            if service_name == "api"
            else {"LIFE_AGENT_DATABASE_URL"}
        )
        for raw_name in expected_secret_names:
            file_name = f"{raw_name}_FILE"
            if not str(environment.get(file_name, "")).startswith("/run/secrets/"):
                fail(f"{service_name} is missing a runtime secret-file mapping")


def nginx_location_blocks(text: str) -> dict[str, str]:
    lines = text.splitlines()
    blocks: dict[str, str] = {}
    index = 0
    while index < len(lines):
        match = re.fullmatch(r"    location = (\S+) \{", lines[index])
        if match is None:
            index += 1
            continue
        path = match.group(1)
        start = index
        index += 1
        while index < len(lines) and lines[index] != "    }":
            index += 1
        if index == len(lines):
            fail(f"unterminated nginx location for {path}")
        if path in blocks:
            fail(f"duplicate nginx exact location for {path}")
        blocks[path] = "\n".join(lines[start : index + 1])
        index += 1
    return blocks


def validate_nginx() -> None:
    http_text = NGINX_HTTP.read_text(encoding="utf-8")
    https_text = NGINX_HTTPS.read_text(encoding="utf-8")
    for name, text in (("HTTP", http_text), ("HTTPS", https_text)):
        health = nginx_location_blocks(text).get("/healthz", "")
        if "return 204;" not in health or "application/json" in health:
            fail(f"{name} public health must be content-free HTTP 204")
        if "location / {\n        return 404;\n    }" not in text:
            fail(f"{name} must retain the default 404 boundary")

    blocks = nginx_location_blocks(https_text)
    observed_routes = {path for path in blocks if path.startswith("/api/")}
    if observed_routes != API_ROUTES:
        fail("nginx API route allowlist is not exact")
    if re.search(r"location\s+(?:\^~\s+)?/api/", https_text):
        fail("nginx must not expose an API prefix location")
    for route in API_ROUTES:
        block = blocks[route]
        if "if ($request_method != POST)" not in block:
            fail(f"{route} does not reject non-POST methods")
        if "proxy_pass http://127.0.0.1:18080;" not in block:
            fail(f"{route} does not use the loopback API")


def validate_shell_and_image_policy() -> None:
    shell_paths = [
        ROOT / "backend" / "container-entrypoint.sh",
        ROOT / "infra" / "nginx" / "install-life-vhost.sh",
        ROOT / "infra" / "nginx" / "update-life-vhost.sh",
        PRODUCTION / "lib.sh",
        PRODUCTION / "deploy-backend.sh",
        PRODUCTION / "rollback-backend.sh",
    ]
    subprocess.run(
        ["bash", "-n", *(str(path) for path in shell_paths[1:])],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(
        ["sh", "-n", str(shell_paths[0])],
        cwd=ROOT,
        check=True,
    )

    image_test = f"""
set -eu
source {str(PRODUCTION / "lib.sh")!r}
life_agent_is_backend_image {DUMMY_IMAGE!r}
! life_agent_is_backend_image 'ghcr.io/andriyshkoy/life_agent/backend:latest'
! life_agent_is_backend_image 'ghcr.io/other/project/backend@sha256:{"a" * 64}'
! life_agent_is_backend_image 'ghcrXio/andriyshkoy/life_agent/backend@sha256:{"a" * 64}'
life_agent_can_auto_rollback {DUMMY_IMAGE!r} 'present:revision_a' 'present:revision_a'
! life_agent_can_auto_rollback {DUMMY_IMAGE!r} 'present:revision_a' 'present:revision_b'
! life_agent_can_auto_rollback '' 'present:revision_a' 'present:revision_a'
"""
    subprocess.run(["bash", "-c", image_test], cwd=ROOT, check=True)

    sanitized_compose_test = f"""
set -eu
export LIFE_AGENT_PRODUCTION_ENV_FILE={str(EXAMPLE_ENV)!r}
export LIFE_AGENT_BACKEND_IMAGE={DUMMY_IMAGE!r}
export LIFE_AGENT_RUNTIME_DATABASE_URL_SECRET_FILE=/tmp/unvalidated-override
source {str(PRODUCTION / "lib.sh")!r}
life_agent_compose config --format json
"""
    rendered = subprocess.run(
        ["bash", "-c", sanitized_compose_test],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if "/tmp/unvalidated-override" in rendered:
        fail("caller environment overrides the validated Compose environment")

    dockerfile = (ROOT / "backend" / "Dockerfile").read_text(encoding="utf-8")
    health_line = next(
        line for line in dockerfile.splitlines() if "urllib.request.urlopen" in line
    )
    if "/readyz" not in health_line or "/healthz" in health_line:
        fail("backend image healthcheck must use readiness")

    deployment_library = (PRODUCTION / "lib.sh").read_text(encoding="utf-8")
    schema_probe_users = re.findall(
        r"--username ([A-Za-z_][A-Za-z0-9_]*)", deployment_library
    )
    if schema_probe_users != ["life_agent_owner", "life_agent_owner"]:
        fail("schema probes must use the configured PostgreSQL owner role")


def validate_example_environment() -> None:
    values: dict[str, str] = {}
    for line in EXAMPLE_ENV.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if line.count("=") != 1:
            fail("production.env.example contains a malformed assignment")
        name, value = line.split("=", 1)
        if name in values:
            fail("production.env.example contains duplicate variables")
        values[name] = value
    if any(name in RAW_SECRET_ENVIRONMENT_NAMES for name in values):
        fail("production.env.example must never contain raw secrets")
    if any(re.fullmatch(r"[A-Za-z0-9_-]{43}", value) for value in values.values()):
        fail("production.env.example appears to contain a cryptographic key")


def main() -> int:
    validate_compose()
    validate_nginx()
    validate_shell_and_image_policy()
    validate_example_environment()
    print("PASS: production Compose, nginx boundary, secret wiring, and deploy policy")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
