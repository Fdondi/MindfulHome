#!/usr/bin/env python3
"""Insert restored tutorial pages + notification/AI copy into locale XML."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"

STRINGS: dict[str, dict[str, str]] = {
    "de": {
        "tutorial_title": "Anleitung",
        "tutorial_subtitle": "Sieh dir Teile der Einrichtung erneut an, falls du vergessen hast, wie etwas funktioniert.",
        "tutorial_previous": "Zurück",
        "tutorial_next": "Weiter",
        "tutorial_page_of": "%1$d / %2$d",
        "how_it_works": "Wie es funktioniert",
        "coachmark_notifications_title": "Benachrichtigungen",
        "coachmark_notifications_body": "In einer anderen App hält eine Benachrichtigung den Countdown. Wenn die Zeit um ist: Vögel und ein kurzes Gespräch — Overlay, wenn erlaubt, sonst in dieser Benachrichtigung. Antworte dort.",
        "coachmark_settings_permissions_body": "Nutzung, Overlay und App-Wechsel.",
        "coachmark_settings_notifications_title": "Benachrichtigungen",
        "coachmark_settings_notifications_body": "Nötig für den Timer-Countdown und für das Gespräch am Ende, wenn Overlay aus ist.",
        "coachmark_settings_ai_body": "Cloud-Gemini, lokal über die App LM Playground, oder Skript ohne Modell.",
        "more_capable_requires_google_sign_in_and_interne": "Cloud-Gemini. Anmeldung und Internet. Am leistungsfähigsten; am schonendsten fürs Telefon.",
        "private_works_offline_requires_downloading_a_mod": "Nutzt die separate App LM Playground auf diesem Gerät. Privat, offline. Diese App installieren und dort ein Modell wählen.",
        "onboarding_ai_model_body": "Wähle, wie MindfulHome mit dir spricht.\\n\\nCloud (empfohlen) — anmelden, um Gemini auf Googles Servern zu nutzen. Leistungsfähiger, schonender fürs Telefon, braucht Internet.\\n\\nLokal — die separate App LM Playground installieren und dort ein Modell nutzen. Privat und offline; weniger leistungsfähig als Gemini.\\n\\nSkript — keine Anmeldung, keine Extra-App. Feste Antworten. Auch wenn Cloud oder Lokal nicht laufen.",
        "onboarding_ai_google_body": "Cloud. Empfohlen. Am leistungsfähigsten und schonendsten fürs Telefon. Braucht Internet.",
        "onboarding_ai_local_title": "Auf diesem Gerät (LM Playground)",
        "onboarding_ai_local_body": "Die separate App LM Playground installieren und dort ein lokales Modell wählen. Privat und offline. Weniger leistungsfähig als Gemini; braucht Speicher und RAM.",
        "onboarding_ai_none_body": "Kein Google-Konto und keine Extra-App. Feste Skript-Antworten. Auch wenn Cloud oder LM Playground nicht laufen.",
        "ai_mode_none_description": "Feste Skript-Antworten. Kein Google, kein LM Playground. Auch wenn die anderen Optionen scheitern.",
        "onboarding_app_tiers_title": "Drei Arten von Apps",
        "onboarding_layout_title": "So funktioniert der Startbildschirm",
        "onboarding_todo_title": "Wofür die Todo-Liste da ist",
        "tutorial_ai_model_body": "MindfulHome kann auf drei Arten sprechen. Wähle eine unter Einstellungen → KI-Modell. Wenn die gewählte Option nicht läuft, fallen Gespräche auf Skript-Antworten zurück.\\n\\nCloud — Google (Gemini): mit Google anmelden. Braucht Internet. Am leistungsfähigsten und schonendsten fürs Telefon: das Modell läuft auf Googles Servern, nicht auf dem Gerät.\\n\\nLokal — LM Playground (eine separate App): MindfulHome liefert kein lokales Modell mit. LM Playground installieren, dann dort ein Modell laden oder wählen. MindfulHome schickt den Chat auf dem Gerät an LM Playground. Privat und offline, sobald ein Modell bereit ist. Kleinere lokale Modelle sind schwächer als Gemini und brauchen Speicher und RAM. Fehlt LM Playground, ist es blockiert oder beschäftigt, gibt es Skript-Antworten.\\n\\nSkript — keine: kein Google-Konto, keine Extra-App, kein Modell-Download. Feste Antworten. Wähle das, wenn du keine KI willst. Es ist auch der automatische Fallback, wenn Cloud oder Lokal nicht verfügbar ist.",
    },
    "fr": {
        "tutorial_title": "Tutoriel",
        "tutorial_subtitle": "Revenez sur n\\'importe quelle partie du guide si vous avez oublié comment quelque chose fonctionne.",
        "tutorial_previous": "Précédent",
        "tutorial_next": "Suivant",
        "tutorial_page_of": "%1$d / %2$d",
        "how_it_works": "Comment ça marche",
        "coachmark_notifications_title": "Notifications",
        "coachmark_notifications_body": "Dans une autre app, une notification garde le compte à rebours. À la fin : oiseaux et un court chat — overlay si autorisé, sinon dans cette notification. Répondez là.",
        "coachmark_settings_permissions_body": "Utilisation, overlay et changement d\\'app.",
        "coachmark_settings_notifications_title": "Notifications",
        "coachmark_settings_notifications_body": "Nécessaire pour le compte à rebours et pour le chat de fin de minuteur si l\\'overlay est désactivé.",
        "coachmark_settings_ai_body": "Gemini dans le cloud, local via l\\'app LM Playground, ou scripté sans modèle.",
        "more_capable_requires_google_sign_in_and_interne": "Gemini cloud. Connexion et internet. Le plus capable ; le plus léger pour le téléphone.",
        "private_works_offline_requires_downloading_a_mod": "Utilise l\\'app séparée LM Playground sur cet appareil. Privé, hors ligne. Installez cette app et choisissez-y un modèle.",
        "onboarding_ai_model_body": "Choisissez comment MindfulHome vous parle.\\n\\nCloud (recommandé) — connectez-vous pour Gemini sur les serveurs Google. Plus capable, plus léger pour le téléphone, internet requis.\\n\\nLocal — installez l\\'app séparée LM Playground, puis un modèle dedans. Privé et hors ligne ; moins capable que Gemini.\\n\\nScripté — pas de compte ni d\\'app extra. Réponses fixes. Aussi si le cloud ou le local ne tourne pas.",
        "onboarding_ai_google_body": "Cloud. Recommandé. Le plus capable, et le plus léger pour le téléphone. Internet requis.",
        "onboarding_ai_local_title": "Sur cet appareil (LM Playground)",
        "onboarding_ai_local_body": "Installez l\\'app séparée LM Playground, puis choisissez-y un modèle local. Privé et hors ligne. Moins capable que Gemini ; utilise stockage et RAM.",
        "onboarding_ai_none_body": "Pas de compte Google ni d\\'app extra. Réponses scriptées fixes. Aussi si le cloud ou LM Playground ne tourne pas.",
        "ai_mode_none_description": "Réponses scriptées fixes. Pas Google, pas LM Playground. Aussi si les autres options échouent.",
        "onboarding_app_tiers_title": "Trois types d\\'apps",
        "onboarding_layout_title": "Comment fonctionne l\\'écran d\\'accueil",
        "onboarding_todo_title": "À quoi sert la liste Todo",
        "tutorial_ai_model_body": "MindfulHome peut parler de trois façons. Choisissez dans Réglages → Modèle d\\'IA. Si l\\'option choisie ne tourne pas, les conversations retombent sur des réponses scriptées.\\n\\nCloud — Google (Gemini) : connexion Google. Internet requis. Le plus capable et le plus léger pour le téléphone : le modèle tourne chez Google, pas sur l\\'appareil.\\n\\nLocal — LM Playground (une app séparée) : MindfulHome n\\'embarque pas de modèle local. Installez LM Playground, puis téléchargez ou choisissez un modèle dans cette app. MindfulHome envoie le chat à LM Playground sur l\\'appareil. Privé et hors ligne une fois un modèle prêt. Les petits modèles locaux sont moins capables que Gemini et utilisent stockage et RAM. Si LM Playground manque, est bloqué ou occupé, vous avez des réponses scriptées.\\n\\nScripté — aucun : pas de compte Google, pas d\\'app extra, pas de téléchargement. Réponses fixes. Choisissez ceci pour zéro IA. C\\'est aussi le repli automatique si cloud ou local est indisponible.",
    },
    "it": {
        "tutorial_title": "Tutorial",
        "tutorial_subtitle": "Rivedi qualsiasi parte della guida se hai dimenticato come funziona qualcosa.",
        "tutorial_previous": "Precedente",
        "tutorial_next": "Successivo",
        "tutorial_page_of": "%1$d / %2$d",
        "how_it_works": "Come funziona",
        "coachmark_notifications_title": "Notifiche",
        "coachmark_notifications_body": "In un\\'altra app, una notifica tiene il conto alla rovescia. A tempo scaduto: uccelli e una breve chat — overlay se consentito, altrimenti in quella notifica. Rispondi lì.",
        "coachmark_settings_permissions_body": "Utilizzo, overlay e cambio app.",
        "coachmark_settings_notifications_title": "Notifiche",
        "coachmark_settings_notifications_body": "Serve per il conto alla rovescia e per la chat a fine timer se l\\'overlay è spento.",
        "coachmark_settings_ai_body": "Gemini nel cloud, locale tramite l\\'app LM Playground, o scriptato senza modello.",
        "more_capable_requires_google_sign_in_and_interne": "Gemini cloud. Accesso e internet. Il più capace; il più leggero sul telefono.",
        "private_works_offline_requires_downloading_a_mod": "Usa l\\'app separata LM Playground su questo dispositivo. Privato, offline. Installa quell\\'app e scegli lì un modello.",
        "onboarding_ai_model_body": "Scegli come MindfulHome ti parla.\\n\\nCloud (consigliato) — accedi per usare Gemini sui server Google. Più capace, più leggero sul telefono, serve internet.\\n\\nLocale — installa l\\'app separata LM Playground e usa un modello lì. Privato e offline; meno capace di Gemini.\\n\\nScriptato — niente accesso né app extra. Risposte fisse. Anche se cloud o locale non partono.",
        "onboarding_ai_google_body": "Cloud. Consigliato. Il più capace e il più leggero sul telefono. Serve internet.",
        "onboarding_ai_local_title": "Su questo dispositivo (LM Playground)",
        "onboarding_ai_local_body": "Installa l\\'app separata LM Playground, poi scegli lì un modello locale. Privato e offline. Meno capace di Gemini; usa memoria e RAM.",
        "onboarding_ai_none_body": "Niente account Google né app extra. Risposte scriptate fisse. Anche se cloud o LM Playground non partono.",
        "ai_mode_none_description": "Risposte scriptate fisse. Niente Google, niente LM Playground. Anche se le altre opzioni falliscono.",
        "onboarding_app_tiers_title": "Tre tipi di app",
        "onboarding_layout_title": "Come funziona la schermata home",
        "onboarding_todo_title": "A cosa serve l\\'elenco Todo",
        "tutorial_ai_model_body": "MindfulHome può parlare in tre modi. Scegli in Impostazioni → Modello IA. Se l\\'opzione scelta non parte, le conversazioni tornano alle risposte scriptate.\\n\\nCloud — Google (Gemini): accedi con Google. Serve internet. È l\\'opzione più capace e più leggera sul telefono: il modello gira sui server Google, non sul dispositivo.\\n\\nLocale — LM Playground (un\\'app separata): MindfulHome non include un modello locale. Installa LM Playground, poi scarica o scegli un modello in quell\\'app. MindfulHome invia la chat a LM Playground sul dispositivo. Privato e offline quando un modello è pronto. I modelli locali piccoli sono meno capaci di Gemini e usano memoria e RAM. Se LM Playground manca, è bloccato o occupato, ricevi risposte scriptate.\\n\\nScriptato — nessuno: niente account Google, niente app extra, niente download. Risposte fisse. Sceglilo se vuoi zero IA. È anche il ripiego automatico quando cloud o locale non è disponibile.",
    },
    "es": {
        "tutorial_title": "Tutorial",
        "tutorial_subtitle": "Vuelve a cualquier parte de la guía si olvidaste cómo funciona algo.",
        "tutorial_previous": "Anterior",
        "tutorial_next": "Siguiente",
        "tutorial_page_of": "%1$d / %2$d",
        "how_it_works": "Cómo funciona",
        "coachmark_notifications_title": "Notificaciones",
        "coachmark_notifications_body": "En otra app, una notificación mantiene la cuenta atrás. Al acabar: pájaros y un chat corto — overlay si lo permitiste, si no en esa notificación. Responde ahí.",
        "coachmark_settings_permissions_body": "Uso, overlay y cambio de app.",
        "coachmark_settings_notifications_title": "Notificaciones",
        "coachmark_settings_notifications_body": "Hace falta para la cuenta atrás y para el chat al acabar el temporizador si el overlay está apagado.",
        "coachmark_settings_ai_body": "Gemini en la nube, local con la app LM Playground, o guionizado sin modelo.",
        "more_capable_requires_google_sign_in_and_interne": "Gemini en la nube. Inicio de sesión e internet. El más capaz; el más ligero en el teléfono.",
        "private_works_offline_requires_downloading_a_mod": "Usa la app aparte LM Playground en este dispositivo. Privado, sin conexión. Instala esa app y elige un modelo ahí.",
        "onboarding_ai_model_body": "Elige cómo te habla MindfulHome.\\n\\nNube (recomendado) — inicia sesión para Gemini en los servidores de Google. Más capaz, más ligero en el teléfono, necesita internet.\\n\\nLocal — instala la app aparte LM Playground y usa un modelo ahí. Privado y sin conexión; menos capaz que Gemini.\\n\\nGuionizado — sin cuenta ni app extra. Respuestas fijas. También si la nube o lo local no funciona.",
        "onboarding_ai_google_body": "Nube. Recomendado. El más capaz y el más ligero en el teléfono. Necesita internet.",
        "onboarding_ai_local_title": "En este dispositivo (LM Playground)",
        "onboarding_ai_local_body": "Instala la app aparte LM Playground y elige ahí un modelo local. Privado y sin conexión. Menos capaz que Gemini; usa almacenamiento y RAM.",
        "onboarding_ai_none_body": "Sin cuenta de Google ni app extra. Respuestas guionizadas fijas. También si la nube o LM Playground no funciona.",
        "ai_mode_none_description": "Respuestas guionizadas fijas. Sin Google ni LM Playground. También si fallan las otras opciones.",
        "onboarding_app_tiers_title": "Tres tipos de apps",
        "onboarding_layout_title": "Cómo funciona la pantalla de inicio",
        "onboarding_todo_title": "Para qué sirve la lista Todo",
        "tutorial_ai_model_body": "MindfulHome puede hablar de tres formas. Elige en Ajustes → Modelo de IA. Si la opción elegida no funciona, las conversaciones vuelven a respuestas guionizadas.\\n\\nNube — Google (Gemini): inicia sesión con Google. Necesita internet. Es la opción más capaz y más ligera en el teléfono: el modelo corre en los servidores de Google, no en el dispositivo.\\n\\nLocal — LM Playground (una app aparte): MindfulHome no incluye un modelo local. Instala LM Playground y descarga o elige un modelo en esa app. MindfulHome envía el chat a LM Playground en el dispositivo. Privado y sin conexión cuando hay un modelo listo. Los modelos locales pequeños son menos capaces que Gemini y usan almacenamiento y RAM. Si LM Playground falta, está bloqueado o ocupado, recibes respuestas guionizadas.\\n\\nGuionizado — ninguno: sin cuenta de Google, sin app extra, sin descarga. Respuestas fijas. Elige esto si quieres cero IA. También es el respaldo automático si la nube o lo local no está disponible.",
    },
    "zh-rCN": {
        "tutorial_title": "教程",
        "tutorial_subtitle": "如果忘记了某部分的用法，可在此重新查看设置指南。",
        "tutorial_previous": "上一页",
        "tutorial_next": "下一页",
        "tutorial_page_of": "%1$d / %2$d",
        "how_it_works": "它是如何运作的",
        "coachmark_notifications_title": "通知",
        "coachmark_notifications_body": "你在别的应用里时，通知会保持倒计时。时间到：小鸟和短对话——若允许则用浮层，否则就在该通知里。在那里回复。",
        "coachmark_settings_permissions_body": "使用情况、浮层和切换应用检测。",
        "coachmark_settings_notifications_title": "通知",
        "coachmark_settings_notifications_body": "定时器倒计时需要它；关闭浮层时，结束时的对话也靠它。",
        "coachmark_settings_ai_body": "云端 Gemini、通过 LM Playground 应用的本地，或无模型的脚本。",
        "more_capable_requires_google_sign_in_and_interne": "云端 Gemini。需登录和网络。能力最强，对手机负担最轻。",
        "private_works_offline_requires_downloading_a_mod": "使用本机上的独立应用 LM Playground。私密、离线。安装该应用并在其中选择模型。",
        "onboarding_ai_model_body": "选择 MindfulHome 如何与你对话。\\n\\n云端（推荐）— 登录后使用 Google 服务器上的 Gemini。更强、对手机更轻，需要网络。\\n\\n本地 — 安装独立应用 LM Playground，再在其中使用模型。私密离线；能力弱于 Gemini。\\n\\n脚本 — 无需登录或额外应用。固定回复。云端或本地无法运行时也会用它。",
        "onboarding_ai_google_body": "云端。推荐。能力最强，对手机负担最轻。需要网络。",
        "onboarding_ai_local_title": "本机（LM Playground）",
        "onboarding_ai_local_body": "安装独立应用 LM Playground，再在其中选择本地模型。私密离线。弱于 Gemini；占用存储和内存。",
        "onboarding_ai_none_body": "无需 Google 账号或额外应用。固定脚本回复。云端或 LM Playground 无法运行时也会用它。",
        "ai_mode_none_description": "固定脚本回复。不用 Google，不用 LM Playground。其他选项失败时也会用它。",
        "onboarding_app_tiers_title": "三类应用",
        "onboarding_layout_title": "主屏幕如何支持这一切",
        "onboarding_todo_title": "待办列表是做什么的",
        "tutorial_ai_model_body": "MindfulHome 可以用三种方式对话。在设置 → AI 模型中选择。若所选方式无法运行，对话会回退到脚本回复。\\n\\n云端 — Google（Gemini）：用 Google 登录。需要网络。能力最强、对手机最轻：模型在 Google 服务器上跑，不在本机。\\n\\n本地 — LM Playground（独立应用）：MindfulHome 不自带本地模型。安装 LM Playground，再在该应用中下载或选择模型。MindfulHome 把对话发到本机上的 LM Playground。模型就绪后可私密离线。较小的本地模型弱于 Gemini，并占用存储和内存。若未安装、被拦截或正忙，则使用脚本回复。\\n\\n脚本 — 无：无 Google 账号、无额外应用、无模型下载。固定回复。若不想用 AI 就选这项。云端或本地不可用时也会自动回退到它。",
    },
    "ja": {
        "tutorial_title": "チュートリアル",
        "tutorial_subtitle": "使い方を忘れたら、セットアップガイドの各項目をここから見直せます。",
        "tutorial_previous": "前へ",
        "tutorial_next": "次へ",
        "tutorial_page_of": "%1$d / %2$d",
        "how_it_works": "仕組み",
        "coachmark_notifications_title": "通知",
        "coachmark_notifications_body": "別アプリにいる間、通知がカウントダウンを保ちます。時間切れ：鳥と短い会話 — 許可していればオーバーレイ、なければその通知。そこで返信します。",
        "coachmark_settings_permissions_body": "使用状況、オーバーレイ、アプリ切替の検出。",
        "coachmark_settings_notifications_title": "通知",
        "coachmark_settings_notifications_body": "タイマーのカウントダウンと、オーバーレイがオフのときの終了時チャットに必要です。",
        "coachmark_settings_ai_body": "クラウドの Gemini、LM Playground アプリ経由のローカル、またはモデルなしのスクリプト。",
        "more_capable_requires_google_sign_in_and_interne": "クラウド Gemini。ログインとネットが必要。最も高性能で、端末への負担は最も軽い。",
        "private_works_offline_requires_downloading_a_mod": "別アプリの LM Playground をこの端末で使います。プライベート、オフライン。そのアプリを入れ、そこでモデルを選びます。",
        "onboarding_ai_model_body": "MindfulHome の話し方を選びます。\\n\\nクラウド（推奨）— ログインして Google のサーバー上の Gemini を使う。高性能で端末は軽く、ネットが必要。\\n\\nローカル — 別アプリ LM Playground を入れ、その中のモデルを使う。プライベートでオフライン。Gemini より弱い。\\n\\nスクリプト — ログインも別アプリも不要。決まった返答。クラウドやローカルが動かないときも使う。",
        "onboarding_ai_google_body": "クラウド。推奨。最も高性能で、端末への負担は最も軽い。ネットが必要。",
        "onboarding_ai_local_title": "この端末（LM Playground）",
        "onboarding_ai_local_body": "別アプリ LM Playground を入れ、そこでローカルモデルを選びます。プライベートでオフライン。Gemini より弱く、容量と RAM を使います。",
        "onboarding_ai_none_body": "Google アカウントも別アプリも不要。固定のスクリプト返答。クラウドや LM Playground が動かないときも使う。",
        "ai_mode_none_description": "固定のスクリプト返答。Google も LM Playground も使わない。他の選択肢が失敗したときも使う。",
        "onboarding_app_tiers_title": "アプリの三つの区分",
        "onboarding_layout_title": "ホーム画面の仕組み",
        "onboarding_todo_title": "Todoリストの役割",
        "tutorial_ai_model_body": "MindfulHome は三通りの話し方ができます。設定 → AIモデルで選びます。選んだものが動かないときは、会話はスクリプト返答に戻ります。\\n\\nクラウド — Google（Gemini）：Google でログイン。ネットが必要。最も高性能で端末は最も軽い：モデルは Google のサーバーで動き、端末では動きません。\\n\\nローカル — LM Playground（別アプリ）：MindfulHome はローカルモデルを同梱しません。LM Playground を入れ、そのアプリでモデルを入手または選択します。MindfulHome は端末上の LM Playground にチャットを送ります。モデルが用意できればプライベートでオフライン。小さいローカルモデルは Gemini より弱く、容量と RAM を使います。LM Playground が無い・ブロック・ビジーならスクリプト返答になります。\\n\\nスクリプト — なし：Google アカウントも別アプリもモデルダウンロードも不要。決まった返答。AI を使いたくないときに選びます。クラウドやローカルが使えないときの自動フォールバックでもあります。",
    },
}

ARRAYS: dict[str, dict[str, list[str]]] = {}


def load_head_arrays() -> None:
    import subprocess

    names = [
        "onboarding_philosophy_bullets",
        "onboarding_app_tiers_bullets",
        "onboarding_layout_bullets",
        "onboarding_todo_bullets",
    ]
    for loc in STRINGS:
        path = f"app/src/main/res/values-{loc}/strings.xml"
        text = subprocess.check_output(["git", "show", f"HEAD:{path}"], text=True)
        ARRAYS[loc] = {}
        for name in names:
            m = re.search(rf'<string-array name="{name}">(.*?)</string-array>', text, re.S)
            if not m:
                raise SystemExit(f"HEAD missing {name} in {loc}")
            ARRAYS[loc][name] = re.findall(r"<item>(.*?)</item>", m.group(1), re.S)


def upsert_string(xml: str, name: str, value: str) -> str:
    pattern = rf'^[ \t]*<string name="{name}">.*?</string>'
    line = f'    <string name="{name}">{value}</string>'
    if re.search(pattern, xml, re.M | re.S):
        return re.sub(pattern, lambda _m: line, xml, count=1, flags=re.M | re.S)
    xml = xml.replace(
        '    <string name="tutorial">',
        f'{line}\n    <string name="tutorial">',
        1,
    ) if name in {
        "tutorial_title",
        "tutorial_subtitle",
        "tutorial_previous",
        "tutorial_next",
        "tutorial_page_of",
        "how_it_works",
    } else xml
    if re.search(pattern, xml, re.M | re.S):
        return xml
    return xml.replace("</resources>", f"{line}\n</resources>", 1)


def upsert_array(xml: str, name: str, items: list[str]) -> str:
    body = "\n".join(f"        <item>{item}</item>" for item in items)
    block = f'    <string-array name="{name}">\n{body}\n    </string-array>'
    pattern = rf'<string-array name="{name}">.*?</string-array>'
    if re.search(pattern, xml, re.S):
        return re.sub(pattern, lambda _m: block, xml, count=1, flags=re.S)
    return xml.replace("</resources>", f"{block}\n</resources>", 1)


def insert_after(xml: str, after_name: str, new_name: str, value: str) -> str:
    pattern = rf'^[ \t]*<string name="{new_name}">.*?</string>'
    line = f'    <string name="{new_name}">{value}</string>'
    if re.search(pattern, xml, re.M | re.S):
        return re.sub(pattern, lambda _m: line, xml, count=1, flags=re.M | re.S)
    after = rf'(^[ \t]*<string name="{after_name}">.*?</string>)'
    return re.sub(after, lambda m: m.group(1) + "\n" + line, xml, count=1, flags=re.M | re.S)


def patch_locale(loc: str) -> None:
    path = RES / f"values-{loc}" / "strings.xml"
    xml = path.read_text(encoding="utf-8")
    data = STRINGS[loc]
    xml = insert_after(
        xml, "coachmark_timer_body",
        "coachmark_notifications_title", data["coachmark_notifications_title"],
    )
    xml = insert_after(
        xml, "coachmark_notifications_title",
        "coachmark_notifications_body", data["coachmark_notifications_body"],
    )
    xml = insert_after(
        xml, "coachmark_settings_permissions_body",
        "coachmark_settings_notifications_title",
        data["coachmark_settings_notifications_title"],
    )
    xml = insert_after(
        xml, "coachmark_settings_notifications_title",
        "coachmark_settings_notifications_body",
        data["coachmark_settings_notifications_body"],
    )
    skip = {
        "coachmark_notifications_title",
        "coachmark_notifications_body",
        "coachmark_settings_notifications_title",
        "coachmark_settings_notifications_body",
    }
    for name, value in data.items():
        if name in skip:
            continue
        xml = upsert_string(xml, name, value)
    for name, items in ARRAYS[loc].items():
        xml = upsert_array(xml, name, items)
    xml = re.sub(r"^        (<string name=)", r"    \1", xml, flags=re.M)
    xml = re.sub(r"^[ \t]+(<string-array name=)", r"    \1", xml, flags=re.M)
    path.write_text(xml, encoding="utf-8")


def main() -> None:
    load_head_arrays()
    for loc in STRINGS:
        patch_locale(loc)
        print("patched", loc)


if __name__ == "__main__":
    main()
