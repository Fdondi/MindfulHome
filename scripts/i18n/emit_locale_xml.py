#!/usr/bin/env python3
"""Convenience for emitting values-<locale>/strings.xml 
if they have been generated as scripts/i18n/translations/<locale>.json.
This is purely in case tools find it easier to emit json than ensure android-ready xml.
It should in no way imply that one should write json and then run this script,
in most cases just edit the xml directly."""
from __future__ import annotations

import json
import re
import xml.sax.saxutils as xu
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EN = ROOT / "app/src/main/res/values/strings.xml"
TRANS = Path(__file__).resolve().parent / "translations"
RES = ROOT / "app/src/main/res"

BULLET_ARRAYS = (
    "onboarding_philosophy_bullets",
    "onboarding_app_tiers_bullets",
    "onboarding_layout_bullets",
    "onboarding_todo_bullets",
)

JSON_BULLET_KEYS = {
    "onboarding_philosophy_bullets": "bullets",
    "onboarding_app_tiers_bullets": "app_tiers_bullets",
    "onboarding_layout_bullets": "layout_bullets",
    "onboarding_todo_bullets": "todo_bullets",
}


def parse_en_order() -> tuple[list[str], dict[str, list[str]]]:
    text = EN.read_text(encoding="utf-8")
    keys = re.findall(r'<string name="([^"]+)">', text)
    arrays: dict[str, list[str]] = {}
    for name in BULLET_ARRAYS:
        arr = re.search(
            rf'<string-array name="{name}">(.*?)</string-array>',
            text,
            flags=re.S,
        )
        if not arr:
            continue
        arrays[name] = [
            b.replace("\\'", "'").replace('\\"', '"')
            for b in re.findall(r"<item>(.*?)</item>", arr.group(1), flags=re.S)
        ]
    return keys, arrays


def esc(s: str) -> str:
    s = s.replace("'", r"\'")
    s = xu.escape(s, {"\"": "&quot;"})
    return s


def emit(locale: str) -> None:
    path = TRANS / f"{locale}.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    keys, en_arrays = parse_en_order()
    strings = data["strings"]
    missing = [k for k in keys if k not in strings]
    if missing:
        raise SystemExit(f"{locale}: missing {len(missing)} keys e.g. {missing[:5]}")
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k in keys:
        lines.append(f'    <string name="{k}">{esc(strings[k])}</string>')
    for name in BULLET_ARRAYS:
        if name not in en_arrays:
            continue
        json_key = JSON_BULLET_KEYS[name]
        bullets = data.get(json_key)
        if bullets is None:
            raise SystemExit(f"{locale}: missing {json_key}")
        expected = len(en_arrays[name])
        if len(bullets) != expected:
            raise SystemExit(
                f"{locale}: expected {expected} {json_key}, got {len(bullets)}"
            )
        lines.append(f'    <string-array name="{name}">')
        for b in bullets:
            lines.append(f"        <item>{esc(b)}</item>")
        lines.append("    </string-array>")
    lines.append("</resources>")
    out = RES / f"values-{locale}" / "strings.xml"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(keys)} strings)")


def main() -> None:
    for p in sorted(TRANS.glob("*.json")):
        emit(p.stem)


if __name__ == "__main__":
    main()
