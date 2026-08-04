#!/usr/bin/env python3
"""Add onboarding app-tiers + layout strings/arrays to catalogs and locale XML."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
I18N = Path(__file__).resolve().parent
RES = ROOT / "app/src/main/res"

TITLES = {
    "en": {
        "onboarding_app_tiers_title": "Three kinds of apps",
        "onboarding_layout_title": "How the home screen works",
    },
    "de": {
        "onboarding_app_tiers_title": "Drei Arten von Apps",
        "onboarding_layout_title": "So funktioniert der Startbildschirm",
    },
    "fr": {
        "onboarding_app_tiers_title": "Trois types d'apps",
        "onboarding_layout_title": "Comment fonctionne l'écran d'accueil",
    },
    "it": {
        "onboarding_app_tiers_title": "Tre tipi di app",
        "onboarding_layout_title": "Come funziona la schermata home",
    },
    "es": {
        "onboarding_app_tiers_title": "Tres tipos de apps",
        "onboarding_layout_title": "Cómo funciona la pantalla de inicio",
    },
    "zh-rCN": {
        "onboarding_app_tiers_title": "三类应用",
        "onboarding_layout_title": "主屏幕如何支持这一切",
    },
    "ja": {
        "onboarding_app_tiers_title": "アプリの三つの区分",
        "onboarding_layout_title": "ホーム画面の仕組み",
    },
}

APP_TIERS = {
    "en": [
        "Trusted — apps you know are useful and won't waste your time: put them on Quick Launch with no timer",
        "Quick untrusted — useful apps that can still waste time: put them on Quick Launch with a default timer",
        'Untrusted — rarely needed or easy to overuse: leave them off Quick Launch and open them through "something else?"',
    ],
    "de": [
        "Vertrauenswürdig — Apps, die nützlich sind und keine Zeit rauben: auf Quick Launch ohne Timer",
        "Schnell unvertrauenswürdig — nützliche Apps, die trotzdem Zeit rauben können: auf Quick Launch mit Standard-Timer",
        "Unvertrauenswürdig — selten nötig oder leicht zu übernutzen: nicht auf Quick Launch, öffnen über „etwas anderes?“",
    ],
    "fr": [
        "Fiables — apps utiles qui ne font pas perdre de temps : Quick Launch sans minuteur",
        "Rapides non fiables — utiles mais pouvant faire perdre du temps : Quick Launch avec minuteur par défaut",
        "Non fiables — rarement nécessaires ou faciles à abuser : hors Quick Launch, via « autre chose ? »",
    ],
    "it": [
        "Attendibili — app utili che non fanno perdere tempo: Quick Launch senza timer",
        "Rapide non attendibili — utili ma che possono far perdere tempo: Quick Launch con timer predefinito",
        "Non attendibili — raramente necessarie o facili da abusare: fuori da Quick Launch, tramite «qualcos'altro?»",
    ],
    "es": [
        "De confianza — apps útiles que no te hacen perder tiempo: Quick Launch sin temporizador",
        "Rápidas no confiables — útiles pero que pueden hacerte perder tiempo: Quick Launch con temporizador por defecto",
        "No confiables — poco necesarias o fáciles de abusar: fuera de Quick Launch, vía «¿otra cosa?»",
    ],
    "zh-rCN": [
        "可信 — 你知道有用且不会浪费时间的应用：放入快速启动，不设定时器",
        "快速不可信 — 有用但仍可能浪费时间：放入快速启动，并设默认定时器",
        "不可信 — 不常用或容易沉迷：不要放进快速启动，通过「做点别的？」打开",
    ],
    "ja": [
        "信頼できる — 役に立ち、時間を無駄にしないと分かるアプリ：クイック起動に入れ、タイマーなし",
        "すばやく不信頼 — 役立つが時間を浪費しうるアプリ：クイック起動に入れ、デフォルトのタイマー付き",
        "不信頼 — あまり使わない、または浪費しやすい：クイック起動に入れず「他のこと？」から開く",
    ],
}

LAYOUT = {
    "en": [
        "Those rectangles are folders for your Quick Launch apps",
        "A folder with one app launches that app on tap — long-press still opens the folder",
        "You can give apps a default timer, and change it whenever you like",
        "Add, rename, or remove folders as your goals change",
        "Add an app to one or several folders, or remove it when it no longer fits",
        "Change an app's timer whenever you need a tighter or looser limit",
        'The "something else?" tile opens everything else — your untrusted apps',
    ],
    "de": [
        "Diese Rechtecke sind Ordner für deine Quick-Launch-Apps",
        "Ein Ordner mit einer App startet die App per Tippen — Langer Druck öffnet weiterhin den Ordner",
        "Apps können einen Standard-Timer haben, den du jederzeit ändern kannst",
        "Ordner hinzufügen, umbenennen oder entfernen, wenn sich deine Ziele ändern",
        "Apps in einen oder mehrere Ordner legen — oder entfernen, wenn sie nicht mehr passen",
        "Den Timer einer App jederzeit strenger oder großzügiger einstellen",
        "Die Kachel „etwas anderes?“ öffnet alles andere — deine unvertrauenswürdigen Apps",
    ],
    "fr": [
        "Ces rectangles sont des dossiers pour vos apps Quick Launch",
        "Un dossier avec une seule app lance l'app au toucher — un appui long ouvre encore le dossier",
        "Vous pouvez donner un minuteur par défaut aux apps et le modifier quand vous voulez",
        "Ajoutez, renommez ou supprimez des dossiers selon vos objectifs",
        "Ajoutez une app à un ou plusieurs dossiers, ou retirez-la si elle ne convient plus",
        "Modifiez le minuteur d'une app pour une limite plus stricte ou plus souple",
        "La tuile « autre chose ? » ouvre tout le reste — vos apps non fiables",
    ],
    "it": [
        "Quei rettangoli sono cartelle per le app di Quick Launch",
        "Una cartella con una sola app la avvia al tocco — la pressione prolungata apre comunque la cartella",
        "Puoi dare alle app un timer predefinito e modificarlo quando vuoi",
        "Aggiungi, rinomina o rimuovi cartelle man mano che cambiano i tuoi obiettivi",
        "Aggiungi un'app a una o più cartelle, o rimuovila quando non ci sta più",
        "Cambia il timer di un'app quando ti serve un limite più stretto o più largo",
        "Il riquadro «qualcos'altro?» apre tutto il resto — le tue app non attendibili",
    ],
    "es": [
        "Esos rectángulos son carpetas para tus apps de Quick Launch",
        "Una carpeta con una sola app la lanza al tocarla — la pulsación larga sigue abriendo la carpeta",
        "Puedes dar a las apps un temporizador por defecto y cambiarlo cuando quieras",
        "Añade, renombra o elimina carpetas según cambien tus objetivos",
        "Añade una app a una o varias carpetas, o quítala cuando ya no encaje",
        "Cambia el temporizador de una app cuando necesites un límite más estricto o más flexible",
        "La ficha «¿otra cosa?» abre todo lo demás — tus apps no confiables",
    ],
    "zh-rCN": [
        "那些方块是快速启动应用的文件夹",
        "只有一个应用的文件夹：点按即启动该应用 — 长按仍可打开文件夹",
        "可以为应用设置默认定时器，并随时更改",
        "可随目标变化添加、重命名或删除文件夹",
        "可将应用加入一个或多个文件夹，不再适合时再移除",
        "随时收紧或放宽应用的定时器",
        "「做点别的？」磁贴打开其余一切 — 你的不可信应用",
    ],
    "ja": [
        "あの四角はクイック起動アプリ用のフォルダです",
        "アプリが1つのフォルダはタップで起動 — 長押しでフォルダを開けます",
        "アプリにデフォルトのタイマーを付けられ、いつでも変更できます",
        "目標に合わせてフォルダを追加・名前変更・削除できます",
        "アプリを1つまたは複数のフォルダに入れたり、合わなくなったら外せます",
        "必要に応じてアプリのタイマーを厳しく／ゆるく変更できます",
        "「他のこと？」タイルでそれ以外 — 不信頼なアプリ — にアクセスします",
    ],
}


def esc_xml(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', r"\"")
        .replace("'", r"\'")
    )


def array_xml(name: str, items: list[str]) -> str:
    lines = [f'    <string-array name="{name}">']
    for item in items:
        lines.append(f"        <item>{esc_xml(item)}</item>")
    lines.append("    </string-array>")
    return "\n".join(lines)


def patch_xml(locale: str) -> None:
    folder = "values" if locale == "en" else f"values-{locale}"
    path = RES / folder / "strings.xml"
    text = path.read_text(encoding="utf-8")
    titles = TITLES[locale]
    # Ensure title strings exist (en already has them).
    for key, value in titles.items():
        if f'<string name="{key}">' not in text:
            text = text.replace(
                "</resources>",
                f'    <string name="{key}">{esc_xml(value)}</string>\n</resources>',
            )
    # Replace or append arrays.
    for name, items in (
        ("onboarding_app_tiers_bullets", APP_TIERS[locale]),
        ("onboarding_layout_bullets", LAYOUT[locale]),
    ):
        block = array_xml(name, items)
        pattern = rf'    <string-array name="{name}">.*?</string-array>\n?'
        if re.search(pattern, text, flags=re.S):
            text = re.sub(pattern, block + "\n", text, count=1, flags=re.S)
        else:
            text = text.replace("</resources>", block + "\n</resources>")
    path.write_text(text, encoding="utf-8")
    print(f"patched {path}")


def patch_json(locale: str, path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    data["strings"].update(TITLES[locale])
    data["app_tiers_bullets"] = APP_TIERS[locale]
    data["layout_bullets"] = LAYOUT[locale]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"patched {path}")


def main() -> None:
    en_catalog = I18N / "en_catalog.json"
    data = json.loads(en_catalog.read_text(encoding="utf-8"))
    data["strings"].update(TITLES["en"])
    data["app_tiers_bullets"] = APP_TIERS["en"]
    data["layout_bullets"] = LAYOUT["en"]
    en_catalog.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"patched {en_catalog}")

    for path in sorted((I18N / "translations").glob("*.json")):
        patch_json(path.stem, path)

    # Locale XML: skip en (already hand-edited); patch all others including ja (XML-only).
    for locale in ("de", "fr", "it", "es", "zh-rCN", "ja"):
        patch_xml(locale)


if __name__ == "__main__":
    main()
