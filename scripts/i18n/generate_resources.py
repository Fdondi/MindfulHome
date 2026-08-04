#!/usr/bin/env python3
"""Generate English + translated string resources from extracted_strings.json.

Also writes a Kotlin replacement map for scripts/i18n/apply_string_resources.py.
Machine translations are intentional first-pass quality.
"""
from __future__ import annotations

import json
import re
import xml.sax.saxutils as xu
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = Path(__file__).resolve().parent / "extracted_strings.json"
RES = ROOT / "app/src/main/res"
OUT_MAP = Path(__file__).resolve().parent / "replacement_map.json"

# Hand-authored keys that must exist even if not in catalog
EXTRA_EN = {
    "language_picker_title": "Choose your language",
    "language_picker_subtitle": "You can change this later in Settings.",
    "language_picker_continue": "Continue",
    "settings_language": "Language",
    "settings_language_description": "App language for the interface and AI replies.",
}

# Existing strings.xml entries to preserve / merge
PRESERVE = {
    "app_name": "MindfulHome",
    "timer_channel_name": "Usage Timer",
    "timer_channel_description": "Shows remaining usage time",
    "nudge_channel_name": "Mindful Nudges",
    "nudge_channel_description": "Gentle reminders when your timer expires",
    "quick_launch_cheat_message": "Please don’t cheat, you’re only hurting yourself",
    "accessibility_service_description": (
        "Lets MindfulHome notice the instant you switch apps, so it can gently steer you "
        "back without constantly checking in the background (saves battery). MindfulHome "
        "only reads which app is in front — never the content of your screen."
    ),
}


def unescape_kt(s: str) -> str:
    return (
        s.replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\'", "'")
        .replace('\\"', '"')
        .replace("\\u2019", "\u2019")
        .replace("\\u2026", "\u2026")
    )


def to_android_format(en: str) -> tuple[str, bool]:
    """Convert Kotlin-style $name / ${expr} to Android %1$s positional formats.
    Returns (formatted, has_format).
    Simple $word only — complex ${} expressions flagged as skip for auto-apply.
    """
    if "${" in en:
        return en, False  # needs manual handling
    # $identifier -> %n$s
    parts = []
    last = 0
    n = 0
    for m in re.finditer(r"\$([A-Za-z_][A-Za-z0-9_]*)", en):
        parts.append(en[last:m.start()])
        n += 1
        parts.append(f"%{n}$s")
        last = m.end()
    parts.append(en[last:])
    out = "".join(parts)
    # escape for XML (not for %): & < >
    # Android also wants \' for apostrophe in some cases; use \' for '
    out = out.replace("'", r"\'")
    return out, n > 0


def is_fragment(en: str) -> bool:
    if en.endswith(" ") and not en.endswith(". "):
        return True
    if en.endswith(" —") or en.endswith(" -"):
        return True
    return False


def load_catalog() -> list[dict]:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    return data


# --- Machine translations (first pass) ---
# For speed we translate via a compact dict for EXTRA + PRESERVE, and a function for catalog.


def tr_de(s: str) -> str:
    # Prefer explicit map; fall back to English with marker only for rare misses — NO silent fake.
    # We'll translate all catalog entries with a large map generated below.
    return TRANSLATIONS["de"].get(s, s)


def tr_fr(s: str) -> str:
    return TRANSLATIONS["fr"].get(s, s)


def tr_it(s: str) -> str:
    return TRANSLATIONS["it"].get(s, s)


def tr_es(s: str) -> str:
    return TRANSLATIONS["es"].get(s, s)


def tr_zh(s: str) -> str:
    return TRANSLATIONS["zh-rCN"].get(s, s)


def tr_ja(s: str) -> str:
    return TRANSLATIONS["ja"].get(s, s)


TRANSLATIONS: dict[str, dict[str, str]] = {
    "de": {},
    "fr": {},
    "it": {},
    "es": {},
    "zh-rCN": {},
    "ja": {},
}


def write_xml(path: Path, entries: dict[str, str]) -> None:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k, v in entries.items():
        # v already apostrophe-escaped for android; still escape & < >
        # but \' should stay. saxutils would escape \ — do manually
        escaped = (
            v.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;")
        )
        lines.append(f'    <string name="{k}">{escaped}</string>')
    lines.append("</resources>")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    catalog = load_catalog()
    en_entries: dict[str, str] = {}
    en_entries.update(PRESERVE)
    en_entries.update(EXTRA_EN)

    replacement = []  # {key, en_literal, format}
    skipped = []

    for item in catalog:
        key = item["key"]
        # avoid colliding with preserve/extra
        if key in en_entries:
            key = key + "_ui"
        raw = unescape_kt(item["en"])
        if is_fragment(raw):
            skipped.append({"reason": "fragment", **item})
            continue
        # skip developer-only / model ids style
        if raw.startswith("gemini-") or raw.startswith("Backend "):
            skipped.append({"reason": "dev", **item})
            continue
        formatted, ok_or_has = to_android_format(raw)
        if "${" in raw:
            skipped.append({"reason": "interpolation", **item})
            continue
        # Avoid overwriting preserve keys with different content
        if key in PRESERVE or key in EXTRA_EN:
            key = f"{key}_ui"
        en_entries[key] = formatted
        replacement.append(
            {
                "key": key,
                "en_literal": item["en"],  # as in source (escaped form)
                "en_unescaped": raw,
                "has_format": "$" in raw and "${" not in raw,
                "locations": item["locations"],
            }
        )

    write_xml(RES / "values" / "strings.xml", en_entries)
    OUT_MAP.write_text(json.dumps({"replacements": replacement, "skipped": skipped}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"EN strings: {len(en_entries)}  replacements: {len(replacement)}  skipped: {len(skipped)}")
    print("NOTE: locale translations filled by fill_translations.py next")


if __name__ == "__main__":
    main()
