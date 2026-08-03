#!/usr/bin/env python3
"""Unit tests for the dependency-free Android security validator."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.validate_android_security import (
    SecurityValidationError,
    validate_all,
    validate_extraction_rules,
    validate_manifest,
    validate_network_security_config,
)


ANDROID_NS = "http://schemas.android.com/apk/res/android"


class AndroidSecurityValidatorTest(unittest.TestCase):
    def test_repository_declarations_are_complete(self) -> None:
        validate_all()

    def test_device_transfer_must_exclude_every_domain(self) -> None:
        xml = """\
<data-extraction-rules>
  <cloud-backup>
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
  </cloud-backup>
  <device-transfer>
    <exclude domain="root" path="." />
  </device-transfer>
</data-extraction-rules>
"""
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(
            SecurityValidationError,
            "exclusion missing for domains",
        ):
            validate_extraction_rules(path)

    def test_permissive_include_is_rejected(self) -> None:
        exclusions = "\n".join(
            f'    <exclude domain="{domain}" path="." />'
            for domain in (
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
        )
        xml = f"""\
<data-extraction-rules>
  <cloud-backup>
    <include domain="file" path="safe.txt" />
{exclusions}
  </cloud-backup>
  <device-transfer>
{exclusions}
  </device-transfer>
</data-extraction-rules>
"""
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "include"):
            validate_extraction_rules(path)

    def test_allow_backup_must_remain_false(self) -> None:
        xml = f"""\
<manifest xmlns:android="{ANDROID_NS}">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <application
      android:allowBackup="true"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules_legacy"
      android:networkSecurityConfig="@xml/network_security_config"
      android:usesCleartextTraffic="false" />
</manifest>
"""
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "allowBackup"):
            validate_manifest(path)

    def test_internet_permission_is_required(self) -> None:
        xml = self.valid_manifest().replace(
            '  <uses-permission android:name="android.permission.INTERNET" />\n',
            "",
        )
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "required permissions missing"):
            validate_manifest(path)

    def test_network_observation_permission_is_required(self) -> None:
        xml = self.valid_manifest().replace(
            "  <uses-permission "
            'android:name="android.permission.ACCESS_NETWORK_STATE" />\n',
            "",
        )
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "required permissions missing"):
            validate_manifest(path)

    def test_network_management_permissions_are_forbidden(self) -> None:
        xml = self.valid_manifest().replace(
            "  <application",
            "  <uses-permission "
            'android:name="android.permission.CHANGE_NETWORK_STATE" />\n'
            "  <application",
        )
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "forbidden permissions present"):
            validate_manifest(path)

    def test_source_manifest_rejects_library_generated_permissions(self) -> None:
        xml = self.valid_manifest().replace(
            "  <application",
            "  <uses-permission android:name=\"android.permission.WAKE_LOCK\" />\n"
            "  <application",
        )
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "unexpected permissions present"):
            validate_manifest(path)

    def test_merged_manifest_allows_only_work_manager_generated_permissions(self) -> None:
        path = self.write_temporary_xml(self.valid_merged_manifest())

        validate_manifest(path, merged=True)

    def test_merged_manifest_rejects_unknown_permission(self) -> None:
        xml = self.valid_merged_manifest().replace(
            "  <application",
            "  <uses-permission android:name=\"android.permission.CHANGE_WIFI_STATE\" />\n"
            "  <application",
        )
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "unexpected permissions present"):
            validate_manifest(path, merged=True)

    def test_network_security_config_requires_only_system_trust(self) -> None:
        xml = """\
<network-security-config>
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors>
      <certificates src="user" />
    </trust-anchors>
  </base-config>
</network-security-config>
"""
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "only system certificates"):
            validate_network_security_config(path)

    def valid_manifest(self) -> str:
        return f"""\
<manifest xmlns:android="{ANDROID_NS}">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <application
      android:allowBackup="false"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules_legacy"
      android:networkSecurityConfig="@xml/network_security_config"
      android:usesCleartextTraffic="false" />
</manifest>
"""

    def valid_merged_manifest(self) -> str:
        package_name = "ru.andriyshkoy.lifeagent.test"
        return self.valid_manifest().replace(
            f'<manifest xmlns:android="{ANDROID_NS}">',
            f'<manifest xmlns:android="{ANDROID_NS}" package="{package_name}">',
        ).replace(
            "  <application",
            "  <uses-permission android:name=\"android.permission.WAKE_LOCK\" />\n"
            "  <uses-permission "
            "android:name=\"android.permission.RECEIVE_BOOT_COMPLETED\" />\n"
            "  <uses-permission "
            "android:name=\"android.permission.FOREGROUND_SERVICE\" />\n"
            "  <uses-permission android:name=\""
            f"{package_name}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\" />\n"
            "  <application",
        )

    def write_temporary_xml(self, content: str) -> Path:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        path = Path(temporary_directory.name) / "fixture.xml"
        path.write_text(content, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
