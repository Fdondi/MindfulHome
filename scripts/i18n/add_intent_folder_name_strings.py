#!/usr/bin/env python3
"""Add intent folder name + something-else string keys to catalogs and locale XMLs."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
I18N = Path(__file__).resolve().parent

# Insert after this existing key in values/strings.xml and full locale XMLs.
AFTER_KEY = "i_ll_do_something_else"

STRINGS = {
    "en": {
        "intent_folder_search": "Search",
        "intent_folder_reflect": "Reflect",
        "intent_folder_travel": "Travel",
        "intent_folder_learn": "Learn",
        "intent_folder_connect": "Connect",
        "intent_folder_organize": "Organize",
        "intent_folder_snap": "Snap",
        "intent_folder_util": "Util",
        "something_else_question": "something else?",
    },
    "de": {
        "intent_folder_search": "Suchen",
        "intent_folder_reflect": "Reflektieren",
        "intent_folder_travel": "Reisen",
        "intent_folder_learn": "Lernen",
        "intent_folder_connect": "Verbinden",
        "intent_folder_organize": "Organisieren",
        "intent_folder_snap": "Foto",
        "intent_folder_util": "Util",
        "something_else_question": "etwas anderes?",
    },
    "fr": {
        "intent_folder_search": "Chercher",
        "intent_folder_reflect": "Réfléchir",
        "intent_folder_travel": "Voyager",
        "intent_folder_learn": "Apprendre",
        "intent_folder_connect": "Connecter",
        "intent_folder_organize": "Organiser",
        "intent_folder_snap": "Photo",
        "intent_folder_util": "Util",
        "something_else_question": "autre chose ?",
    },
    "it": {
        "intent_folder_search": "Cercare",
        "intent_folder_reflect": "Riflettere",
        "intent_folder_travel": "Viaggiare",
        "intent_folder_learn": "Imparare",
        "intent_folder_connect": "Connettere",
        "intent_folder_organize": "Organizzare",
        "intent_folder_snap": "Scatta",
        "intent_folder_util": "Util",
        "something_else_question": "qualcos'altro?",
    },
    "es": {
        "intent_folder_search": "Buscar",
        "intent_folder_reflect": "Reflexionar",
        "intent_folder_travel": "Viajar",
        "intent_folder_learn": "Aprender",
        "intent_folder_connect": "Conectar",
        "intent_folder_organize": "Organizar",
        "intent_folder_snap": "Foto",
        "intent_folder_util": "Util",
        "something_else_question": "¿otra cosa?",
    },
    "zh-rCN": {
        "intent_folder_search": "搜索",
        "intent_folder_reflect": "反思",
        "intent_folder_travel": "出行",
        "intent_folder_learn": "学习",
        "intent_folder_connect": "联系",
        "intent_folder_organize": "整理",
        "intent_folder_snap": "拍照",
        "intent_folder_util": "工具",
        "something_else_question": "做点别的？",
    },
    "ja": {
        "intent_folder_search": "検索",
        "intent_folder_reflect": "内省",
        "intent_folder_travel": "移動",
        "intent_folder_learn": "学習",
        "intent_folder_connect": "つながる",
        "intent_folder_organize": "整理",
        "intent_folder_snap": "撮影",
        "intent_folder_util": "その他",
        "something_else_question": "他のこと？",
    },
}

XML_LOCALE_DIRS = {
    "en": "values",
    "de": "values-de",
    "fr": "values-fr",
    "it": "values-it",
    "es": "values-es",
    "zh-rCN": "values-zh-rCN",
    "ja": "values-ja",
}

JSON_LOCALES = ("de", "fr", "it", "es", "zh-rCN")


def esc(s: str) -> str:
    return s.replace("'", r"\'").replace("&", "&amp;").replace('"', "&quot;")


def xml_block(strings: dict[str, str]) -> str:
    return "\n".join(f'    <string name="{k}">{esc(v)}</string>' for k, v in strings.items())


def patch_xml(locale: str) -> None:
    folder = XML_LOCALE_DIRS[locale]
    path = RES / folder / "strings.xml"
    text = path.read_text(encoding="utf-8")
    strings = STRINGS[locale]
    if "intent_folder_search" in text:
        print(f"skip xml {path} (already present)")
        return
    after = re.search(rf'(    <string name="{AFTER_KEY}">.*?</string>\n)', text)
    if after:
        insert_at = after.end()
        text = text[:insert_at] + xml_block(strings) + "\n" + text[insert_at:]
    else:
        # Partial locale files: insert before closing </resources>
        text = text.replace("</resources>", xml_block(strings) + "\n</resources>")
    path.write_text(text, encoding="utf-8")
    print(f"patched {path}")


def patch_en_catalog() -> None:
    path = I18N / "en_catalog.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["strings"].update(STRINGS["en"])
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"patched {path}")


def patch_translation_json(locale: str) -> None:
    path = I18N / "translations" / f"{locale}.json"
    if not path.exists():
        print(f"skip missing {path}")
        return
    data = json.loads(path.read_text(encoding="utf-8"))
    data["strings"].update(STRINGS[locale])
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"patched {path}")


def main() -> None:
    for loc in XML_LOCALE_DIRS:
        patch_xml(loc)
    patch_en_catalog()
    for loc in JSON_LOCALES:
        patch_translation_json(loc)


if __name__ == "__main__":
    main()
