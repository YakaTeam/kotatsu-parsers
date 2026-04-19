#!/usr/bin/env python3
"""
Build parsers.json for the GitHub Pages catalog.

Walks src/main/kotlin, parses @MangaSourceParser + @Broken annotations, and
pulls the first string literal after MangaParserSource.NAME as the domain.
"""
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_ROOT = REPO_ROOT / "src" / "main" / "kotlin"
OUT = Path(__file__).resolve().parent / "parsers.json"

# @MangaSourceParser("NAME", "Title", "lang"[, type])
# Handles multiline and trailing comma/whitespace.
ANN_RE = re.compile(
    r'@MangaSourceParser\s*\(\s*'
    r'"([^"]+)"\s*,\s*'            # name
    r'"([^"]+)"\s*,?\s*'           # title
    r'(?:"([^"]*)"\s*,?\s*)?'      # locale (optional)
    r'(?:ContentType\.([A-Z_]+)\s*,?\s*)?'  # type (optional)
    r'\)',
    re.DOTALL,
)
BROKEN_RE = re.compile(r'@Broken(?:\(\s*"([^"]*)"\s*\))?')
DOMAIN_RE = re.compile(
    r'MangaParserSource\.[A-Z0-9_]+\s*,\s*"([^"]+)"',
)
# Fallback: ConfigKey.Domain("example.com") somewhere in the class body
CFG_DOMAIN_RE = re.compile(r'ConfigKey\.Domain\s*\(\s*"([^"]+)"')


def extract(file: Path):
    text = file.read_text(encoding="utf-8", errors="replace")
    m = ANN_RE.search(text)
    if not m:
        return None
    name, title, locale, ctype = m.groups()
    locale = locale or ""
    ctype = ctype or "MANGA"

    broken = BROKEN_RE.search(text)
    is_broken = broken is not None
    broken_reason = (broken.group(1) if broken and broken.group(1) else "") if is_broken else ""

    dm = DOMAIN_RE.search(text) or CFG_DOMAIN_RE.search(text)
    domain = dm.group(1) if dm else ""

    return {
        "name": name,
        "title": title,
        "locale": locale,
        "type": ctype,
        "domain": domain,
        "broken": is_broken,
        "broken_reason": broken_reason,
        "file": str(file.relative_to(REPO_ROOT)).replace("\\", "/"),
    }


def main():
    parsers = []
    seen_names = set()
    dupes = []
    missing_domain = []
    for kt in SRC_ROOT.rglob("*.kt"):
        entry = extract(kt)
        if not entry:
            continue
        if entry["name"] in seen_names:
            dupes.append(entry["name"])
            continue
        seen_names.add(entry["name"])
        parsers.append(entry)
        if not entry["domain"]:
            missing_domain.append(f'{entry["name"]} ({entry["file"]})')

    parsers.sort(key=lambda p: p["title"].lower())

    payload = {
        "generated_by": "docs/build_catalog.py",
        "total": len(parsers),
        "broken": sum(1 for p in parsers if p["broken"]),
        "by_type": {},
        "by_locale": {},
        "parsers": parsers,
    }
    for p in parsers:
        payload["by_type"][p["type"]] = payload["by_type"].get(p["type"], 0) + 1
        loc = p["locale"] or "multi"
        payload["by_locale"][loc] = payload["by_locale"].get(loc, 0) + 1

    OUT.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"wrote {OUT.relative_to(REPO_ROOT)} :: {payload['total']} parsers, "
          f"{payload['broken']} broken", file=sys.stderr)
    if dupes:
        print(f"  {len(dupes)} duplicate names skipped", file=sys.stderr)
    if missing_domain:
        print(f"  {len(missing_domain)} parsers missing domain literal "
              f"(first 5): {missing_domain[:5]}", file=sys.stderr)


if __name__ == "__main__":
    main()
