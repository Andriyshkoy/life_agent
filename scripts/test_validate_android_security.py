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
  <application
      android:allowBackup="true"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules_legacy"
      android:usesCleartextTraffic="false" />
</manifest>
"""
        path = self.write_temporary_xml(xml)
        with self.assertRaisesRegex(SecurityValidationError, "allowBackup"):
            validate_manifest(path)

    def write_temporary_xml(self, content: str) -> Path:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        path = Path(temporary_directory.name) / "fixture.xml"
        path.write_text(content, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
