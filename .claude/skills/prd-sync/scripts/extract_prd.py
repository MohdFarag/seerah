#!/usr/bin/env python3
"""Extract the unified PRD section from a saved Notion fetch of the PRD page.

Usage: extract_prd.py <notion-fetch-output> <snapshot-path>
           [--date YYYY-MM-DD] [--exclude HEADING ...] [--exclude-file FILE]
           [--compare OLD_SNAPSHOT]

The input is the text (or JSON with a "text" field) returned by the Notion
fetch tool. The snapshot keeps everything from the first top-level heading up
to the discussion log, which is history and never a requirement. Notion's
inline markup is turned into plain Markdown. --exclude drops a section (the
heading line and everything up to the next heading of the same or higher
level). --compare prints the headings that differ from an existing snapshot,
so a human decides whether a new section belongs in it.
"""

import argparse
import datetime
import json
import pathlib
import re
import sys

PAGE_URL = "https://app.notion.com/p/40e88743a2df81ffb7ede29b75cc79b0"
LOG_HEADING = re.compile(r"(?m)^# سجل المسودة السابقة والمناقشات\s*$")
FIRST_H1 = re.compile(r"(?m)^# \S")


def page_text(raw: str) -> str:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return raw
    if isinstance(data, dict) and isinstance(data.get("text"), str):
        return data["text"]
    return raw


def extract(text: str) -> str:
    start = FIRST_H1.search(text)
    end = LOG_HEADING.search(text)
    if not start or not end or end.start() <= start.start():
        sys.exit(
            "error: could not find the PRD title or the discussion-log heading; "
            "the page structure changed, so stop and tell the owner"
        )
    return text[start.start() : end.start()].rstrip() + "\n"


LIST_OR_ROW = re.compile(r"^\s*(?:[-*] |\d+\. |\|)")
HEADING = re.compile(r"^(#{1,6}) (.+?)\s*$")


def to_markdown(text: str) -> str:
    text = re.sub(r"</?b>", "**", text)
    text = re.sub(r"</?i>", "*", text)
    text = re.sub(r"</?code>", "`", text)
    text = re.sub(r"<br\s*/?>", "  \n", text)
    text = re.sub(
        r'<date start="([^"]+)"(?: end="([^"]+)")?\s*/>',
        lambda m: m.group(1) + (f"–{m.group(2)}" if m.group(2) else ""),
        text,
    )
    lines = text.split("\n")
    out: list[str] = []
    for i, line in enumerate(lines):
        blank = not line.strip()
        if blank and out and LIST_OR_ROW.match(out[-1]):
            nxt = next((n for n in lines[i + 1 :] if n.strip()), "")
            if LIST_OR_ROW.match(nxt):
                continue  # Notion separates list items and table rows with blank lines
        if blank and out and not out[-1].strip():
            continue
        hard_break = line.endswith("  ") and line.strip()
        out.append(line.rstrip() + ("  " if hard_break else ""))
    while out and out[-1].strip() in ("", "---"):
        out.pop()  # Notion closes the section with a divider before the log
    return "\n".join(out)


def drop_sections(text: str, excluded: list[str]) -> str:
    out, skip_level = [], 0
    for line in text.split("\n"):
        m = HEADING.match(line)
        if m:
            level = len(m.group(1))
            if skip_level and level <= skip_level:
                skip_level = 0
            if not skip_level and m.group(2) in excluded:
                skip_level = level
        if not skip_level:
            out.append(line)
    return "\n".join(out)


def headings(text: str) -> list[str]:
    return [m.group(2) for line in text.split("\n") if (m := HEADING.match(line))]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("snapshot")
    ap.add_argument("--date", default=datetime.date.today().isoformat())
    ap.add_argument("--exclude", nargs="*", default=[])
    ap.add_argument("--exclude-file", help="one heading per line; # starts a comment")
    ap.add_argument("--compare")
    args = ap.parse_args()
    if args.exclude_file:
        for line in pathlib.Path(args.exclude_file).read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                args.exclude.append(line.strip())
    body = to_markdown(extract(page_text(pathlib.Path(args.source).read_text(encoding="utf-8"))))
    missing = [h for h in args.exclude if h not in headings(body)]
    if missing:
        sys.exit(f"error: --exclude heading not found: {missing}")
    body = drop_sections(body, args.exclude).rstrip() + "\n"
    if args.compare:
        old = set(headings(pathlib.Path(args.compare).read_text(encoding="utf-8")))
        new = set(headings(body))
        for h in sorted(new - old):
            print(f"new heading (not in the current snapshot): {h}")
        for h in sorted(old - new):
            print(f"heading gone from Notion: {h}")
    header = (
        f"<!-- Snapshot of the unified PRD from Notion, taken {args.date}.\n"
        f"Source of truth: {PAGE_URL}\n"
        "Do not edit here; update Notion and re-snapshot. Work-log sections listed in\n"
        ".claude/skills/prd-sync/references/excluded-sections.txt are left out. -->\n\n"
    )
    pathlib.Path(args.snapshot).write_text(header + body, encoding="utf-8")
    print(f"wrote {args.snapshot}: {len(body.splitlines())} lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())
