#!/usr/bin/env python3
"""Validate Android backup and transport-security declarations.

The runtime instrumentation suite checks the packaged resources on Android.
This dependency-free validator provides an earlier, deterministic failure for
source and merged-manifest drift.
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
APP_ROOT = ROOT / "android" / "life-agent" / "app" / "src" / "main"
SOURCE_MANIFEST = APP_ROOT / "AndroidManifest.xml"
LEGACY_RULES = APP_ROOT / "res" / "xml" / "backup_rules_legacy.xml"
EXTRACTION_RULES = APP_ROOT / "res" / "xml" / "data_extraction_rules.xml"
NETWORK_SECURITY_CONFIG = APP_ROOT / "res" / "xml" / "network_security_config.xml"

ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
ANDROID_ATTRIBUTE = f"{{{ANDROID_NAMESPACE}}}"

REQUIRED_EXCLUDED_DOMAINS = frozenset(
    {
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    }
)

REQUIRED_APPLICATION_ATTRIBUTES = {
    "allowBackup": "false",
    "dataExtractionRules": "@xml/data_extraction_rules",
    "fullBackupContent": "@xml/backup_rules_legacy",
    "networkSecurityConfig": "@xml/network_security_config",
    "usesCleartextTraffic": "false",
}
SOURCE_PERMISSIONS = frozenset(
    {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    }
)
WORK_MANAGER_MERGED_PERMISSIONS = frozenset(
    {
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
    }
)
FORBIDDEN_PERMISSIONS = frozenset({"android.permission.CHANGE_NETWORK_STATE"})
DYNAMIC_RECEIVER_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"


class SecurityValidationError(AssertionError):
    """Raised when an Android security declaration is incomplete."""


def fail(message: str) -> None:
    raise SecurityValidationError(message)


def parse_xml(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except FileNotFoundError:
        fail(f"missing required Android security file: {path}")
    except ET.ParseError as error:
        fail(f"{path}: invalid XML: {error}")


def local_name(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def require_full_exclusion(parent: ET.Element, label: str) -> None:
    includes = [child for child in parent if local_name(child) == "include"]
    if includes:
        fail(f"{label}: permissive <include> rules are forbidden")

    excludes = [child for child in parent if local_name(child) == "exclude"]
    domain_paths = [
        (child.attrib.get("domain"), child.attrib.get("path")) for child in excludes
    ]
    duplicate_rules = sorted(
        rule for rule in set(domain_paths) if domain_paths.count(rule) > 1
    )
    if duplicate_rules:
        fail(f"{label}: duplicate exclusion rules: {duplicate_rules}")

    fully_excluded = {
        domain for domain, path in domain_paths if path == "." and domain is not None
    }
    missing = sorted(REQUIRED_EXCLUDED_DOMAINS - fully_excluded)
    if missing:
        fail(f"{label}: path='.' exclusion missing for domains: {', '.join(missing)}")

    unknown = sorted(
        domain
        for domain, _ in domain_paths
        if domain is not None and domain not in REQUIRED_EXCLUDED_DOMAINS
    )
    if unknown:
        fail(f"{label}: unknown backup domains: {', '.join(unknown)}")


def validate_manifest(path: Path, *, merged: bool = False) -> None:
    root = parse_xml(path)
    if local_name(root) != "manifest":
        fail(f"{path}: expected <manifest>, got <{local_name(root)}>")

    applications = [
        child for child in root if local_name(child) == "application"
    ]
    if len(applications) != 1:
        fail(f"{path}: expected exactly one <application>")

    application = applications[0]
    for attribute, expected in REQUIRED_APPLICATION_ATTRIBUTES.items():
        actual = application.attrib.get(f"{ANDROID_ATTRIBUTE}{attribute}")
        if actual != expected:
            fail(
                f"{path}: android:{attribute} must be {expected!r}, got {actual!r}"
            )

    permissions = [
        child.attrib.get(f"{ANDROID_ATTRIBUTE}name")
        for child in root
        if local_name(child) == "uses-permission"
    ]
    if any(permission is None for permission in permissions):
        fail(f"{path}: uses-permission without android:name")
    duplicate_permissions = sorted(
        permission
        for permission in set(permissions)
        if permission is not None and permissions.count(permission) > 1
    )
    if duplicate_permissions:
        fail(f"{path}: duplicate permissions: {duplicate_permissions}")
    permission_set = set(permissions)
    required_permissions = set(SOURCE_PERMISSIONS)
    allowed_permissions = set(SOURCE_PERMISSIONS)
    if merged:
        package_name = root.attrib.get("package")
        if not package_name:
            fail(f"{path}: merged manifest package is missing")
        required_permissions.update(WORK_MANAGER_MERGED_PERMISSIONS)
        allowed_permissions.update(WORK_MANAGER_MERGED_PERMISSIONS)
        allowed_permissions.add(
            f"{package_name}{DYNAMIC_RECEIVER_PERMISSION_SUFFIX}"
        )

    missing_permissions = sorted(required_permissions - permission_set)
    if missing_permissions:
        fail(f"{path}: required permissions missing: {', '.join(missing_permissions)}")
    forbidden_permissions = sorted(FORBIDDEN_PERMISSIONS & permission_set)
    if forbidden_permissions:
        fail(f"{path}: forbidden permissions present: {', '.join(forbidden_permissions)}")
    unexpected_permissions = sorted(permission_set - allowed_permissions)
    if unexpected_permissions:
        fail(f"{path}: unexpected permissions present: {', '.join(unexpected_permissions)}")


def validate_network_security_config(path: Path) -> None:
    root = parse_xml(path)
    if local_name(root) != "network-security-config":
        fail(f"{path}: expected <network-security-config>")

    children = list(root)
    if len(children) != 1 or local_name(children[0]) != "base-config":
        fail(f"{path}: expected exactly one <base-config>")
    base_config = children[0]
    if base_config.attrib.get("cleartextTrafficPermitted") != "false":
        fail(f"{path}: base cleartextTrafficPermitted must be 'false'")

    base_children = list(base_config)
    if len(base_children) != 1 or local_name(base_children[0]) != "trust-anchors":
        fail(f"{path}: expected exactly one <trust-anchors>")
    trust_anchors = base_children[0]
    certificates = list(trust_anchors)
    if (
        len(certificates) != 1
        or local_name(certificates[0]) != "certificates"
        or certificates[0].attrib != {"src": "system"}
    ):
        fail(f"{path}: trust anchors must contain only system certificates")


def validate_legacy_rules(path: Path) -> None:
    root = parse_xml(path)
    if local_name(root) != "full-backup-content":
        fail(f"{path}: expected <full-backup-content>")
    require_full_exclusion(root, str(path))


def validate_extraction_rules(path: Path) -> None:
    root = parse_xml(path)
    if local_name(root) != "data-extraction-rules":
        fail(f"{path}: expected <data-extraction-rules>")

    modes: dict[str, list[ET.Element]] = {}
    for child in root:
        modes.setdefault(local_name(child), []).append(child)

    for required_mode in ("cloud-backup", "device-transfer"):
        matches = modes.get(required_mode, [])
        if len(matches) != 1:
            fail(f"{path}: expected exactly one <{required_mode}>")
        require_full_exclusion(matches[0], f"{path}:<{required_mode}>")

    supported_modes = {
        "cloud-backup",
        "device-transfer",
        "cross-platform-transfer",
    }
    unknown_modes = sorted(set(modes) - supported_modes)
    if unknown_modes:
        fail(f"{path}: unknown extraction modes: {', '.join(unknown_modes)}")

    for cross_platform in modes.get("cross-platform-transfer", []):
        require_full_exclusion(
            cross_platform,
            f"{path}:<cross-platform-transfer>",
        )


def validate_all(merged_manifests: Iterable[Path] = ()) -> None:
    validate_manifest(SOURCE_MANIFEST)
    validate_legacy_rules(LEGACY_RULES)
    validate_extraction_rules(EXTRACTION_RULES)
    validate_network_security_config(NETWORK_SECURITY_CONFIG)
    for manifest in merged_manifests:
        validate_manifest(manifest, merged=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--merged-manifest",
        action="append",
        default=[],
        type=Path,
        help="additional AGP merged manifest to validate",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    validate_all(args.merged_manifest)
    checked = 4 + len(args.merged_manifest)
    print(
        "PASS: "
        f"{checked} Android security declarations; "
        f"{len(REQUIRED_EXCLUDED_DOMAINS)} backup domains excluded"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
