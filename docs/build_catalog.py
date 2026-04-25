#!/usr/bin/env python3
"""
Build parsers.json for the GitHub Pages catalog.

Per Draken's review: name/title/locale/contentType/isBroken come straight from
the KSP-generated `MangaParserSource` enum (single source of truth — no need
to count or recompute these in Python). The script only walks the source tree
to fill in supplemental fields the enum doesn't carry: the `.kt` file path,
the parser's domain literal, and the human-readable `@Broken("reason")` text.

The KSP-generated file lives at:
  build/generated/ksp/main/kotlin/org/koitharu/kotatsu/parsers/model/MangaParserSource.kt

Run `./gradlew kspKotlin` first (the docs-catalog workflow does this in CI).
If the generated file is absent the script falls back to scanning source files
for `@MangaSourceParser` annotations directly so it stays useful for local
development without a full Gradle build.
"""
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_ROOT = REPO_ROOT / "src" / "main" / "kotlin"
GENERATED_ENUM = (
    REPO_ROOT / "build" / "generated" / "ksp" / "main" / "kotlin"
    / "org" / "koitharu" / "kotatsu" / "parsers" / "model" / "MangaParserSource.kt"
)
OUT = Path(__file__).resolve().parent / "parsers.json"

# Generated enum entry pattern. The KSP processor emits lines like:
#   ANIBEL( title = "Anibel",  locale = "be" /* Беларуская */, ContentType.MANGA, isBroken = true),
ENUM_ENTRY_RE = re.compile(
    r"^\s*([A-Z][A-Z0-9_]+)\s*\(\s*"
    r'title\s*=\s*"((?:[^"\\]|\\.)*)"\s*,\s*'
    r'locale\s*=\s*"([^"]*)"\s*'
    r"(?:/\*[^*]*\*/\s*)?,\s*"
    r"ContentType\.([A-Z_]+)\s*,\s*"
    r"isBroken\s*=\s*(true|false)\s*\)\s*,?\s*$",
    re.MULTILINE,
)

# Source-file patterns (used to enrich enum entries with file path, domain,
# and broken_reason — fields the generated enum doesn't carry — and as the
# fallback path when the generated enum isn't present).
ANN_RE = re.compile(
    r'@MangaSourceParser\s*\(\s*'
    r'"([^"]+)"\s*,\s*'            # name
    r'"((?:[^"\\]|\\.)*)"\s*,?\s*' # title
    r'(?:"([^"]*)"\s*,?\s*)?'      # locale (optional)
    r'(?:ContentType\.([A-Z_]+)\s*,?\s*)?'  # type (optional)
    r'\)',
    re.DOTALL,
)
BROKEN_RE = re.compile(r'@Broken(?:\s*\(\s*"((?:[^"\\]|\\.)*)"\s*\))?')
DOMAIN_RE = re.compile(r'MangaParserSource\.[A-Z0-9_]+\s*,\s*"([^"]+)"')
CFG_DOMAIN_RE = re.compile(r'ConfigKey\.Domain\s*\(\s*"([^"]+)"')


def parse_enum_file(path):
    """Read the KSP-generated MangaParserSource.kt and return a dict keyed by
    enum name with the canonical title/locale/type/broken fields. Returns
    None if the file isn't present (caller falls back to source scanning)."""
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    by_name = {}
    for m in ENUM_ENTRY_RE.finditer(text):
        name, title, locale, ctype, broken = m.groups()
        by_name[name] = {
            "name": name,
            "title": title,
            "locale": locale,
            "type": ctype,
            "broken": broken == "true",
        }
    return by_name or None


def parse_source_file(file):
    """Pull supplemental fields from a `@MangaSourceParser`-annotated .kt:
    the parser's name (for join-key), domain literal, and @Broken reason."""
    text = file.read_text(encoding="utf-8", errors="replace")
    m = ANN_RE.search(text)
    if not m:
        return None
    name = m.group(1)
    broken_match = BROKEN_RE.search(text)
    broken_reason = (broken_match.group(1) if broken_match and broken_match.group(1) else "") if broken_match else ""
    dm = DOMAIN_RE.search(text) or CFG_DOMAIN_RE.search(text)
    domain = dm.group(1) if dm else ""
    return {
        "name": name,
        "title": m.group(2),
        "locale": m.group(3) or "",
        "type": m.group(4) or "MANGA",
        "domain": domain,
        "broken": broken_match is not None,
        "broken_reason": broken_reason,
        "file": str(file.relative_to(REPO_ROOT)).replace("\\", "/"),
    }


def collect_source_metadata():
    """Walk src/main/kotlin once and index supplemental fields by parser name."""
    by_name = {}
    duplicates = []
    for kt in SRC_ROOT.rglob("*.kt"):
        entry = parse_source_file(kt)
        if not entry:
            continue
        if entry["name"] in by_name:
            duplicates.append(entry["name"])
            continue
        by_name[entry["name"]] = entry
    if duplicates:
        print(f"  {len(duplicates)} duplicate parser names found in sources, "
              f"first 5: {duplicates[:5]}", file=sys.stderr)
    return by_name


def merge_enum_with_sources(enum_entries, source_meta):
    """Use enum_entries as the authoritative parser-set; pull supplemental
    fields (file/domain/broken_reason) from source_meta when available."""
    parsers = []
    missing_source = []
    missing_domain = []
    for name, ent in enum_entries.items():
        src = source_meta.get(name) or {}
        merged = {
            "name": ent["name"],
            "title": ent["title"],
            "locale": ent["locale"],
            "type": ent["type"],
            "domain": src.get("domain", ""),
            "broken": ent["broken"],
            "broken_reason": src.get("broken_reason", ""),
            "file": src.get("file", ""),
        }
        if not src:
            missing_source.append(name)
        elif not merged["domain"]:
            missing_domain.append(f'{name} ({merged["file"]})')
        parsers.append(merged)
    if missing_source:
        print(f"  {len(missing_source)} enum entries with no matching source "
              f"file (first 5): {missing_source[:5]}", file=sys.stderr)
    if missing_domain:
        print(f"  {len(missing_domain)} parsers missing domain literal "
              f"(first 5): {missing_domain[:5]}", file=sys.stderr)
    return parsers


def main():
    enum_entries = parse_enum_file(GENERATED_ENUM)
    source_meta = collect_source_metadata()

    if enum_entries is None:
        # KSP hasn't run — fall back to source-only mode.
        print(
            f"  generated MangaParserSource.kt not found at "
            f"{GENERATED_ENUM.relative_to(REPO_ROOT)}; "
            f"falling back to source-file scan. Run ./gradlew kspKotlin to "
            f"build from the canonical enum.",
            file=sys.stderr,
        )
        parsers = list(source_meta.values())
        source_label = "source-tree scan (fallback)"
    else:
        parsers = merge_enum_with_sources(enum_entries, source_meta)
        source_label = "MangaParserSource enum"

    parsers.sort(key=lambda p: p["title"].lower())

    payload = {
        "generated_by": "docs/build_catalog.py",
        "source": source_label,
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
          f"{payload['broken']} broken (source: {source_label})", file=sys.stderr)


if __name__ == "__main__":
    main()
