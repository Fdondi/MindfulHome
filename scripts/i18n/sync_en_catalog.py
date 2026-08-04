#!/usr/bin/env python3
"""Refresh scripts/i18n/en_catalog.json from values/strings.xml."""
from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STRINGS_XML = REPO / "app/src/main/res/values/strings.xml"
CATALOG = Path(__file__).resolve().parent / "en_catalog.json"
BULLETS_NAME = "onboarding_philosophy_bullets"


def load_strings_xml(path: Path) -> tuple[dict[str, str], list[str]]:
    tree = ET.parse(path)
    root = tree.getroot()
    strings: dict[str, str] = {}
    for el in root.findall("string"):
        strings[el.attrib["name"]] = el.text or ""
    bullets: list[str] = []
    for arr in root.findall("string-array"):
        if arr.attrib.get("name") == BULLETS_NAME:
            bullets = [item.text or "" for item in arr.findall("item")]
    return strings, bullets


def main() -> int:
    strings, bullets = load_strings_xml(STRINGS_XML)
    payload = {"strings": strings, "bullets": bullets}
    CATALOG.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {CATALOG} ({len(strings)} strings, {len(bullets)} bullets)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
