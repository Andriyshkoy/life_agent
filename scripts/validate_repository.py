#!/usr/bin/env python3
"""Fast, dependency-free checks for repository documentation and seed data."""

from __future__ import annotations

import csv
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
PUBLIC_MARKDOWN = {Path("README.md")}
PRIVATE_DOCUMENTATION_PREFIXES = (".codex/",)
PRIVATE_AGENT_FILES = {"AGENTS.md"}
NON_PUBLIC_MARKDOWN_PARTS = {
    ".cache",
    ".codex",
    ".git",
    ".gradle",
    ".idea",
    ".private",
    ".tmp",
    ".venv",
    ".vscode",
    "build",
    "venv",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def validate_documentation_boundary() -> None:
    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
    ignore_rules = {line.strip() for line in gitignore}
    for required_rule in (".codex/", "AGENTS.md"):
        if required_rule not in ignore_rules:
            fail(f".gitignore must contain the local-only rule: {required_rule}")

    public_markdown = {
        path.relative_to(ROOT)
        for path in ROOT.rglob("*.md")
        if not NON_PUBLIC_MARKDOWN_PARTS.intersection(path.parts)
        and path.name != "AGENTS.md"
    }
    unexpected = sorted(public_markdown - PUBLIC_MARKDOWN)
    missing = sorted(PUBLIC_MARKDOWN - public_markdown)
    if unexpected:
        rendered = ", ".join(str(path) for path in unexpected)
        fail(
            "only README.md may be public Markdown; move internal documentation "
            f"to .codex/: {rendered}"
        )
    if missing:
        rendered = ", ".join(str(path) for path in missing)
        fail(f"missing public project documentation: {rendered}")

    if shutil.which("git"):
        result = subprocess.run(
            ["git", "ls-files", "-z"],
            cwd=ROOT,
            check=True,
            capture_output=True,
        )
        tracked = {
            Path(item.decode("utf-8"))
            for item in result.stdout.split(b"\0")
            if item
        }
        private_tracked = sorted(
            path
            for path in tracked
            if str(path).startswith(PRIVATE_DOCUMENTATION_PREFIXES)
            or str(path) in PRIVATE_AGENT_FILES
        )
        if private_tracked:
            rendered = ", ".join(str(path) for path in private_tracked)
            fail(f"local agent documentation must not be tracked: {rendered}")


def validate_markdown() -> tuple[int, int]:
    markdown_files = [ROOT / path for path in sorted(PUBLIC_MARKDOWN)]

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
    validate_documentation_boundary()
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
