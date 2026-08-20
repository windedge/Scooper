#!/usr/bin/env python3
"""PO health checker for Scooper i18n catalogs.

Validates that every .po translation file consistently covers the msgids
declared in messages.pot, that no translation string is left empty, and that
there are no duplicate msgids (within pot or within a po file).

English (en.po) is the source-language stub and is exempt from content checks.

Usage:
    python scripts/check_po.py
Exit code 0 with "RESULT PASS" on success, else non-zero with "RESULT FAIL".
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANG_DIR = ROOT / "src" / "main" / "resources" / "lang"
POT_FILE = LANG_DIR / "messages.pot"
TRANSLATED_PO = ["de.po", "es.po", "fr.po", "ja.po", "ru.po", "sl.po", "zh_CN.po", "zh_TW.po"]
STUB_PO = ["en.po"]

# keyword, and optionally the plural form index for msgstr[i]
KW_RE = re.compile(r"^(msgctxt|msgid_plural|msgid|msgstr(?:\[(\d+)\])?)\s*(.*)$")


def parse_po(path):
    """Parse a PO file into a list of entry dicts.

    Each entry: {"ctxt", "msgid", "msgid_plural", "msgstr": [(idx, value), ...]}.
    The file header (empty msgid) is included as a normal entry with msgid "".
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    n = len(lines)
    entries = []
    cur = None
    i = 0

    def flush():
        nonlocal cur
        if cur is not None:
            entries.append(cur)
            cur = None

    while i < n:
        stripped = lines[i].strip()

        if not stripped:
            flush()
            i += 1
            continue

        if stripped.startswith("#~") or stripped.startswith("#"):
            i += 1
            continue

        m = KW_RE.match(stripped)
        if not m:
            i += 1
            continue

        kw = m.group(1)
        index = m.group(2)
        first = m.group(3).strip()

        # Collect this field's quoted value (may span several lines).
        value = ""
        if first.startswith('"') and first.endswith('"'):
            value += first[1:-1]
        i += 1
        while i < n:
            s = lines[i].strip()
            if s.startswith('"') and s.endswith('"'):
                value += s[1:-1]
                i += 1
            else:
                break

        if kw == "msgctxt":
            if cur is not None:
                flush()
            cur = {"ctxt": value, "msgid": "", "msgid_plural": None, "msgstr": []}
            continue

        if cur is None:
            cur = {"ctxt": "", "msgid": "", "msgid_plural": None, "msgstr": []}

        if kw == "msgid":
            cur["msgid"] = value
        elif kw == "msgid_plural":
            cur["msgid_plural"] = value
        elif kw.startswith("msgstr"):
            cur["msgstr"].append((int(index) if index is not None else 0, value))

    flush()
    return entries


def entry_key(e):
    return (e["ctxt"], e["msgid"])


def msgid_of(e):
    return e["msgid"]


def check_file(name, required, check_content):
    path = LANG_DIR / name
    if not path.exists():
        return [f"{name}: file missing"]
    entries = parse_po(path)
    errors = []

    seen = {}
    for e in entries:
        k = entry_key(e)
        seen[k] = seen.get(k, 0) + 1
    for k, c in seen.items():
        if c > 1:
            errors.append(f"{name}: duplicate msgid {k!r}")

    keys = {msgid_of(e) for e in entries if msgid_of(e)}
    for k in sorted(required - keys):
        errors.append(f"{name}: missing msgid {k!r} (in pot but not po)")
    for k in sorted(keys - required):
        errors.append(f"{name}: extra msgid {k!r} (in po but not pot)")

    if check_content:
        for e in entries:
            if not e["msgid"]:
                continue  # header entry
            for idx, val in sorted(e["msgstr"]):
                if not val:
                    errors.append(
                        f"{name}: empty msgstr for {msgid_of(e)!r} (form {idx})"
                    )
    return errors


def main():
    pot_entries = parse_po(POT_FILE)
    if not pot_entries:
        print(f"RESULT FAIL: could not parse {POT_FILE}")
        sys.exit(1)

    seen = {}
    for e in pot_entries:
        k = entry_key(e)
        seen[k] = seen.get(k, 0) + 1
    errors = [f"messages.pot: duplicate msgid {k!r}" for k, c in seen.items() if c > 1]

    required = {msgid_of(e) for e in pot_entries if msgid_of(e)}

    for name in TRANSLATED_PO:
        errors += check_file(name, required, check_content=True)
    for name in STUB_PO:
        errors += check_file(name, set(), check_content=False)

    if errors:
        print("RESULT FAIL")
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print(
        f"RESULT PASS: pot has {len(required)} msgids, "
        f"{len(TRANSLATED_PO)} translated po files consistent, no empty msgstr, no duplicates"
    )


if __name__ == "__main__":
    main()
