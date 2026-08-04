#!/usr/bin/env python3
"""Generate machine-translated strings.xml from values/strings.xml."""
from __future__ import annotations

import json
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "app/src/main/res/values/strings.xml"
RESULTS = REPO / "results/i18n"

LOCALES: dict[str, str] = {
    "de": "de",
    "fr": "fr",
    "it": "it",
    "es": "es",
    "zh-rCN": "zh-CN",
    "ja": "ja",
}

KEEP_ENGLISH_KEYS = frozenset({"app_name", "mindfulhome"})

PLACEHOLDER_RE = re.compile(r"%(\d+\$)?[sdxf]")


def protect_placeholders(text: str) -> tuple[str, list[str]]:
    placeholders: list[str] = []

    def repl(m: re.Match[str]) -> str:
        placeholders.append(m.group(0))
        return f"XXPH{len(placeholders) - 1}XX"

    return PLACEHOLDER_RE.sub(repl, text), placeholders


def restore_placeholders(text: str, placeholders: list[str]) -> str:
    for i, ph in enumerate(placeholders):
        for variant in (
            f"XXPH{i}XX",
            f"XX PH {i} XX",
            f"xxph{i}xx",
        ):
            text = text.replace(variant, ph)
        text = re.sub(rf"XX\s*PH\s*{i}\s*XX", ph, text, flags=re.IGNORECASE)
    return text


def android_escape(value: str) -> str:
    value = value.replace("&", "&amp;")
    value = value.replace("<", "&lt;")
    value = value.replace(">", "&gt;")
    value = value.replace("'", "\\'")
    return value


def load_strings(path: Path) -> list[tuple[str, str]]:
    tree = ET.parse(path)
    root = tree.getroot()
    out: list[tuple[str, str]] = []
    for el in root.findall("string"):
        out.append((el.attrib["name"], el.text or ""))
    return out


def write_strings(path: Path, entries: list[tuple[str, str]]) -> None:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, value in entries:
        lines.append(f'    <string name="{name}">{android_escape(value)}</string>')
    lines.append("</resources>")
    lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def translate_one(translator, text: str) -> str:
    if not text.strip():
        return text
    protected, ph = protect_placeholders(text)
    tr = translator.translate(protected)
    return restore_placeholders(tr, ph)


def main() -> int:
    try:
        from deep_translator import GoogleTranslator
    except ImportError:
        print("Install: pip install deep-translator", file=sys.stderr)
        return 1

    pairs = load_strings(SOURCE)
    summary: dict[str, object] = {"source_count": len(pairs), "locales": {}}

    for folder, lang in LOCALES.items():
        dest = REPO / f"app/src/main/res/values-{folder}/strings.xml"
        translator = GoogleTranslator(source="en", target=lang)
        translated_entries: list[tuple[str, str]] = []
        failed: list[str] = []

        for idx, (name, text) in enumerate(pairs):
            if name in KEEP_ENGLISH_KEYS:
                translated_entries.append((name, text))
                continue
            try:
                tr = translate_one(translator, text)
                if tr == text and text.strip():
                    # possible failure returning source
                    pass
                translated_entries.append((name, tr))
            except Exception as e:
                print(f"{folder} {name}: {e}", file=sys.stderr)
                translated_entries.append((name, text))
                failed.append(name)
            if (idx + 1) % 10 == 0:
                print(f"{folder}: {idx + 1}/{len(pairs)}", file=sys.stderr)
            time.sleep(0.08)

        write_strings(dest, translated_entries)
        summary["locales"][folder] = {
            "count": len(translated_entries),
            "failed_keys": failed,
        }
        print(f"Wrote {dest} ({len(translated_entries)} strings, {len(failed)} failures)")

    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "mt_generation_report.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
