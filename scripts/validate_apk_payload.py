#!/usr/bin/env python3
"""Compare two APK ZIP payloads while ignoring their signing block."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path


def payload(path: Path) -> dict[str, tuple[int, str]]:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        duplicates = sorted({name for name in names if names.count(name) > 1})
        if duplicates:
            raise ValueError(f"{path}: duplicate ZIP entries: {duplicates}")

        return {
            info.filename: (
                info.file_size,
                hashlib.sha256(archive.read(info)).hexdigest(),
            )
            for info in archive.infolist()
            if not info.is_dir()
        }


def main() -> int:
    if len(sys.argv) != 3:
        print(
            "usage: validate_apk_payload.py <published.apk> <built.apk>",
            file=sys.stderr,
        )
        return 2

    published_path, built_path = map(Path, sys.argv[1:])
    for path in (published_path, built_path):
        if not path.is_file():
            print(f"FAIL: APK does not exist: {path}", file=sys.stderr)
            return 1

    try:
        published = payload(published_path)
        built = payload(built_path)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1

    if published == built:
        print(
            "PASS: published and freshly built APK ZIP payloads are identical "
            f"({len(published)} files); signing blocks may differ"
        )
        return 0

    published_names = set(published)
    built_names = set(built)
    only_published = sorted(published_names - built_names)
    only_built = sorted(built_names - published_names)
    changed = sorted(
        name
        for name in published_names & built_names
        if published[name] != built[name]
    )
    print(
        "FAIL: APK payload mismatch\n"
        f"  only published: {only_published}\n"
        f"  only built: {only_built}\n"
        f"  changed: {changed}",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
