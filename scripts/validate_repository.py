#!/usr/bin/env python3
"""Fast, dependency-free checks for repository documentation and seed data."""

from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")


def fail(message: str) -> None:
    raise AssertionError(message)


def validate_markdown() -> tuple[int, int]:
    markdown_files = sorted(ROOT.glob("*.md")) + sorted((ROOT / "docs").glob("*.md"))
    markdown_files += sorted((ROOT / "schemas").glob("*.md"))
    markdown_files += sorted((ROOT / "templates").glob("*.md"))
    markdown_files += sorted((ROOT / "infra").rglob("*.md"))
    markdown_files += sorted((ROOT / "android").rglob("*.md"))

    link_count = 0
    for path in markdown_files:
        text = path.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), 1):
            if line.rstrip() != line:
                fail(f"{path.relative_to(ROOT)}:{line_number}: trailing whitespace")

        fence_count = sum(
            1 for line in text.splitlines() if line.lstrip().startswith("```")
        )
        if fence_count % 2:
            fail(f"{path.relative_to(ROOT)}: unbalanced fenced code blocks")

        for match in MARKDOWN_LINK.finditer(text):
            target = match.group(1).strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1]
            target = target.split(maxsplit=1)[0]
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            relative_target = unquote(target.split("#", 1)[0])
            if not relative_target:
                continue
            resolved = (path.parent / relative_target).resolve()
            try:
                resolved.relative_to(ROOT)
            except ValueError:
                fail(
                    f"{path.relative_to(ROOT)}: relative link escapes repository: "
                    f"{target}"
                )
            if not resolved.exists():
                fail(
                    f"{path.relative_to(ROOT)}: broken relative link: {target}"
                )
            link_count += 1
    return len(markdown_files), link_count


def validate_json() -> int:
    paths = sorted((ROOT / "schemas").glob("*.json"))
    paths += sorted((ROOT / "examples").glob("*.json"))
    for path in paths:
        with path.open(encoding="utf-8") as stream:
            json.load(stream)
    return len(paths)


def validate_csv() -> int:
    paths = sorted((ROOT / "templates").glob("*.csv"))
    for path in paths:
        with path.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.reader(stream)
            rows = list(reader)
        if not rows or not rows[0]:
            fail(f"{path.relative_to(ROOT)}: missing CSV header")
        width = len(rows[0])
        for row_number, row in enumerate(rows[1:], 2):
            if len(row) != width:
                fail(
                    f"{path.relative_to(ROOT)}:{row_number}: "
                    f"expected {width} columns, got {len(row)}"
                )
    return len(paths)


def main() -> int:
    markdown_count, link_count = validate_markdown()
    json_count = validate_json()
    csv_count = validate_csv()
    print(
        "PASS: "
        f"{markdown_count} Markdown files/{link_count} relative links, "
        f"{json_count} JSON files, {csv_count} CSV files"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
