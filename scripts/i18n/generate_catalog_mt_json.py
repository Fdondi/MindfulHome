#!/usr/bin/env python3
"""Machine-translate en_catalog.json into scripts/i18n/translations/<locale>.json."""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = Path(__file__).resolve().parent / "en_catalog.json"
SYNC = Path(__file__).resolve().parent / "sync_en_catalog.py"
OUT_DIR = Path(__file__).resolve().parent / "translations"

LOCALES: dict[str, str] = {
    "de": "de",
    "fr": "fr",
    "it": "it",
    "es": "es",
    "zh-rCN": "zh-CN",
    "ja": "ja",
}

KEEP_ENGLISH_KEYS = frozenset({"app_name"})
PLACEHOLDER_RE = re.compile(r"%(\d+\$)?[sdxf]")


def protect_placeholders(text: str) -> tuple[str, list[str]]:
    placeholders: list[str] = []

    def repl(m: re.Match[str]) -> str:
        placeholders.append(m.group(0))
        return f"XXPH{len(placeholders) - 1}XX"

    return PLACEHOLDER_RE.sub(repl, text), placeholders


def restore_placeholders(text: str, placeholders: list[str]) -> str:
    for i, ph in enumerate(placeholders):
        for variant in (f"XXPH{i}XX", f"XX PH {i} XX", f"xxph{i}xx"):
            text = text.replace(variant, ph)
        text = re.sub(rf"XX\s*PH\s*{i}\s*XX", ph, text, flags=re.IGNORECASE)
    return text


def translate_one(translator, text: str) -> str:
    if not text.strip():
        return text
    protected, ph = protect_placeholders(text)
    tr = translator.translate(protected)
    return restore_placeholders(tr, ph)


def load_existing(path: Path) -> tuple[dict[str, str], list[str]]:
    if not path.is_file():
        return {}, []
    data = json.loads(path.read_text(encoding="utf-8"))
    return data.get("strings", {}), data.get("bullets", [])


def main() -> int:
    try:
        from deep_translator import GoogleTranslator
    except ImportError:
        print("Install: pip install deep-translator", file=sys.stderr)
        return 1

    subprocess.run([sys.executable, str(SYNC)], check=True)
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    en_strings: dict[str, str] = catalog["strings"]
    en_bullets: list[str] = catalog["bullets"]
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    only_locales = [a for a in sys.argv[1:] if not a.startswith("-")]
    targets = {k: v for k, v in LOCALES.items() if not only_locales or k in only_locales}

    for folder, lang in targets.items():
        out_path = OUT_DIR / f"{folder}.json"
        prev_strings, prev_bullets = load_existing(out_path)
        translator = GoogleTranslator(source="en", target=lang)
        out_strings: dict[str, str] = dict(prev_strings)
        translated = 0

        for key, text in en_strings.items():
            if key in out_strings and key in prev_strings:
                continue
            if key in KEEP_ENGLISH_KEYS:
                out_strings[key] = text
                continue
            try:
                out_strings[key] = translate_one(translator, text)
            except Exception as e:
                print(f"{folder} {key}: {e}", file=sys.stderr)
                out_strings[key] = text
            translated += 1
            if translated % 15 == 0:
                print(f"{folder}: translated {translated} new strings…", file=sys.stderr)
            time.sleep(0.05)

        out_bullets: list[str] = list(prev_bullets)
        if len(out_bullets) != len(en_bullets):
            out_bullets = []
            for i, text in enumerate(en_bullets):
                try:
                    out_bullets.append(translate_one(translator, text))
                except Exception as e:
                    print(f"{folder} bullet {i}: {e}", file=sys.stderr)
                    out_bullets.append(text)
                time.sleep(0.05)

        payload = {"strings": {k: out_strings[k] for k in en_strings if k in out_strings}, "bullets": out_bullets}
        for k in en_strings:
            if k not in payload["strings"]:
                payload["strings"][k] = en_strings[k]
        payload["strings"] = {k: payload["strings"][k] for k in en_strings}

        out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {out_path} ({len(payload['strings'])} strings, {len(payload['bullets'])} bullets, +{translated} MT)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
