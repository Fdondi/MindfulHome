#!/usr/bin/env python3
"""Replace hardcoded English UI literals with stringResource / getString calls."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/com/mindfulhome"
MAP = Path(__file__).resolve().parent / "replacement_map.json"

IMPORT_COMPOSE = "import androidx.compose.ui.res.stringResource"
IMPORT_R = "import com.mindfulhome.R"


def ensure_imports(text: str, composable_file: bool) -> str:
    if "import com.mindfulhome.R" not in text:
        # after package line
        text = re.sub(
            r"(package com\.mindfulhome[^\n]*\n)",
            r"\1\n" + IMPORT_R + "\n",
            text,
            count=1,
        )
    if composable_file and "stringResource" in text and IMPORT_COMPOSE not in text:
        text = re.sub(
            r"(package com\.mindfulhome[^\n]*\n(?:import[^\n]*\n)*)",
            lambda m: m.group(0) + IMPORT_COMPOSE + "\n"
            if IMPORT_COMPOSE not in m.group(0)
            else m.group(0),
            text,
            count=1,
        )
    return text


def main() -> None:
    data = json.loads(MAP.read_text(encoding="utf-8"))
    # longest first
    reps = sorted(data["replacements"], key=lambda r: -len(r["en_literal"]))
    # group by file
    files: dict[str, list[dict]] = {}
    for r in reps:
        if r.get("has_format"):
            continue  # manual: need args
        for loc in r["locations"]:
            rel = loc.rsplit(":", 1)[0]
            files.setdefault(rel, []).append(r)

    changed_files = 0
    total_repl = 0
    for rel, items in files.items():
        path = SRC / rel
        if not path.exists():
            print("missing", rel)
            continue
        text = path.read_text(encoding="utf-8")
        original = text
        # unique by literal
        seen = set()
        for r in sorted(items, key=lambda x: -len(x["en_literal"])):
            lit = r["en_literal"]
            if lit in seen:
                continue
            seen.add(lit)
            key = r["key"]
            # Match "literal" as Kotlin string (exact)
            pattern = '"' + re.escape(lit) + '"'
            # Prefer stringResource in ui/; getString elsewhere when context available is hard —
            # for ui use stringResource; for service leave for manual if not composable.
            if rel.startswith("ui/"):
                repl = f"stringResource(R.string.{key})"
            else:
                # only replace if clearly getString-able later — skip non-ui for auto
                continue
            new_text, n = re.subn(pattern, repl, text)
            if n:
                text = new_text
                total_repl += n
        if text != original:
            text = ensure_imports(text, composable_file=True)
            path.write_text(text, encoding="utf-8")
            changed_files += 1
            print(f"updated {rel}")
    print(f"changed_files={changed_files} replacements={total_repl}")


if __name__ == "__main__":
    main()
