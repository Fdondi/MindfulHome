#!/usr/bin/env python3
"""Patch missing string keys into locale XML files (machine translation)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"

# Missing keys relative to current EN — MT first pass.
MISSING = {
    "de": {
        "backend_sign_in_description": "Melde dich mit deinem Google-Konto an, um das Remote-Gemini-Modell zu nutzen. Ohne Anmeldung fällt die App auf On-Device-Antworten zurück.",
        "copy_adb_command": "adb-Befehl kopieren",
        "enable": "Aktivieren",
        "focus_gate_length_description": "Hin und her im Fokuszeit-Gate-Chat: Minimum, bevor Fortfahren erscheint, Maximum, bevor der Zugriff automatisch gewährt wird.",
        "focus_time_description": "Während aktiver Intervalle bleibt der Launcher nach dem Timer verborgen. Nutze die KI, um Apps außerhalb von Quick Launch zu öffnen.",
        "grant": "Erlauben",
        "intent": "Absicht",
        "max_rounds_label": "max %1$d",
        "min_rounds_label": "min %1$d",
        "no_deadline": "Keine Frist",
        "ok": "OK",
        "onboarding_ai_model_body": "Private, kostenlose Offline-Nutzung ist mit einem lokalen Modell möglich, z. B. Gemma3-1B (557 MB). Lade es von HuggingFace herunter und lege es in den models-Ordner der App.\nAchtung: Kleine Modelle sind oft weniger klug als erwartet.\n\nFür eine leistungsfähigere Lösung mit weniger Speicher- und Rechenaufwand empfehlen wir die Anmeldung für unseren KI-Dienst mit Gemini.\n\nWenn keines konfiguriert ist, wird ein skriptbasiertes Fallback verwendet.",
        "onboarding_continue_if_stuck": "Weiter (falls stecken geblieben)",
        "onboarding_default_launcher_instructions": "MindfulHome muss deine Standard-Home-App sein. Tippe unten und wähle MindfulHome.",
        "onboarding_default_launcher_is_default": "MindfulHome ist dein Standard-Launcher.",
        "onboarding_grant_overlay_permission": "Overlay-Berechtigung erteilen",
        "onboarding_grant_usage_access": "Nutzungszugriff erteilen",
        "onboarding_notifications_granted": "Benachrichtigungen aktiv. Du siehst den Timer-Countdown und sanfte Stupser.",
        "onboarding_notifications_rationale": "MindfulHome nutzt Benachrichtigungen für den Timer-Countdown und sanfte Stupser, wenn die Zeit um ist. Kein Spam, versprochen.",
        "onboarding_overlay_granted": "Overlay erlaubt. Stupser erscheinen über jeder App.",
        "onboarding_overlay_rationale": "MindfulHome kann eine sanfte Overlay-Erinnerung zeigen, wenn der Session-Timer abläuft — auch in einer anderen App. Ohne das erscheinen Erinnerungen nur als Benachrichtigungen (die Android mit der Zeit dämpfen kann).",
        "onboarding_skip_for_now": "Vorerst überspringen",
        "onboarding_usage_granted": "Nutzungszugriff erteilt. Karma-Tracking funktioniert.",
        "onboarding_usage_rationale": "MindfulHome braucht Nutzungszugriff, um zu wissen, welche App im Vordergrund ist, wenn der Timer abläuft. So funktioniert Karma-Tracking.",
        "open_accessibility_settings": "Barrierefreiheit öffnen",
        "open_settings": "Einstellungen öffnen",
        "perm_accessibility_off_description": "Aus. MindfulHome prüft die Vordergrund-App per Timer — mehr Akku. Schalte dies ein, und MindfulHome erfährt sofort App-Wechsel — schnellere Reaktion, weniger Akku. Es liest nur, welche App vorne ist, nie den Bildschirminhalt.\n\nZum Aktivieren: unten tippen, MindfulHome unter Installierte Apps finden und einschalten.",
        "perm_accessibility_off_title": "App-Wechsel-Erkennung — Aus (optional)",
        "perm_accessibility_on_description": "An. MindfulHome wird sofort bei App-Wechsel benachrichtigt, reagiert direkt und pollt nicht im Hintergrund — besser für den Akku. Es liest nur, welche App vorne ist, nie den Bildschirminhalt.",
        "perm_accessibility_on_title": "App-Wechsel-Erkennung — An ✓",
        "perm_notification_granted": "Erteilt. MindfulHome kann Timer- und Stupser-Benachrichtigungen zeigen.",
        "perm_notification_required": "Erforderlich für Timer-Countdown und Stupser-Benachrichtigungen.",
        "perm_overlay_granted": "Erteilt. Stupser erscheinen über jeder App.",
        "perm_overlay_required": "Nicht erteilt. Stupser erscheinen nur als Benachrichtigungen, die Android mit der Zeit dämpfen kann. Tippe zum Erteilen.",
        "perm_skipped": "Fehlt. Du hast Erinnerungen übersprungen. Jederzeit hier erteilen.",
        "perm_usage_granted": "Erteilt. MindfulHome kann die Vordergrund-App erkennen.",
        "perm_usage_required": "Erforderlich für Karma-Tracking. Tippe zum Erteilen.",
        "priority_selected": "P%1$d*",
        "priority_unselected": "P%1$d",
        "quick_return_window_description": "Wenn du in diesem Fenster zurückkommst und noch ein Timer läuft, wird der Timer-Bildschirm übersprungen.",
        "show_hard_deadline": "Harte Deadline zeigen",
        "sign_in_with_google": "Mit Google anmelden",
        "signed_in_with_google": "Mit Google angemeldet",
        "signing_in": "Anmeldung…",
        "strikes_before_hiding_description": "Wie viele Negativ-Karma-Punkte eine App sammelt, bevor sie vom Homescreen ausgeblendet wird.",
        "timing_intervals_description": "Polling (Quick Launch, Usage-Cache, Nudge-Loop), Timer-Benachrichtigungs-Refresh und alle Nudge-Intervalle. Größere Schritte sparen Akku.",
    },
    "fr": {
        "backend_sign_in_description": "Connectez-vous avec votre compte Google pour utiliser le modèle Gemini distant. Sans connexion, l'appli repasse aux réponses sur l'appareil.",
        "copy_adb_command": "Copier la commande adb",
        "enable": "Activer",
        "focus_gate_length_description": "Allers-retours dans le chat de la porte focus : minimum avant que Continuer apparaisse, maximum avant l'accès automatique.",
        "focus_time_description": "Pendant les intervalles actifs, le lanceur reste masqué après le minuteur. Utilisez l'IA pour ouvrir des applis hors Quick Launch.",
        "grant": "Autoriser",
        "intent": "Intention",
        "max_rounds_label": "max %1$d",
        "min_rounds_label": "min %1$d",
        "no_deadline": "Pas d'échéance",
        "ok": "OK",
        "onboarding_ai_model_body": "Un usage privé, gratuit et hors ligne est possible avec un modèle local, par ex. Gemma3-1B (557 Mo). Téléchargez-le depuis HuggingFace et placez-le dans le dossier models de l'appli.\nAttention : les petits modèles sont souvent moins malins que prévu.\n\nPour une solution plus puissante et moins gourmande, nous recommandons de vous connecter à notre service IA Gemini.\n\nSinon, un repli scripté sera utilisé.",
        "onboarding_continue_if_stuck": "Continuer (si bloqué)",
        "onboarding_default_launcher_instructions": "MindfulHome doit être votre appli d'accueil par défaut. Appuyez ci-dessous et choisissez MindfulHome.",
        "onboarding_default_launcher_is_default": "MindfulHome est votre lanceur par défaut.",
        "onboarding_grant_overlay_permission": "Autoriser l'affichage par-dessus",
        "onboarding_grant_usage_access": "Autoriser l'accès à l'usage",
        "onboarding_notifications_granted": "Notifications activées. Vous verrez le compte à rebours et de doux rappels.",
        "onboarding_notifications_rationale": "MindfulHome utilise les notifications pour le compte à rebours et de doux rappels quand le temps est écoulé. Pas de spam, promis.",
        "onboarding_overlay_granted": "Overlay autorisé. Les rappels apparaissent au-dessus de toute appli.",
        "onboarding_overlay_rationale": "MindfulHome peut afficher un rappel overlay quand le minuteur expire, même dans une autre appli. Sans cela, les rappels ne sont que des notifications (qu'Android peut silencer).",
        "onboarding_skip_for_now": "Passer pour l'instant",
        "onboarding_usage_granted": "Accès à l'usage accordé. Le karma fonctionnera.",
        "onboarding_usage_rationale": "MindfulHome a besoin de l'accès à l'usage pour savoir quelle appli est au premier plan quand le minuteur expire. C'est ainsi que le karma fonctionne.",
        "open_accessibility_settings": "Ouvrir Accessibilité",
        "open_settings": "Ouvrir les paramètres",
        "perm_accessibility_off_description": "Désactivé. MindfulHome vérifie l'appli au premier plan sur un timer — plus de batterie. Activez ceci pour être notifié à chaque changement d'appli — plus rapide, moins de batterie. Lecture seule de l'appli au premier plan, jamais le contenu.\n\nPour activer : appuyez ci-dessous, trouvez MindfulHome et activez.",
        "perm_accessibility_off_title": "Détection de changement d'appli — Désactivée (optionnel)",
        "perm_accessibility_on_description": "Activé. MindfulHome est notifié à chaque changement d'appli, réagit tout de suite et ne sonde plus en arrière-plan — mieux pour la batterie. Lecture seule de l'appli au premier plan.",
        "perm_accessibility_on_title": "Détection de changement d'appli — Activée ✓",
        "perm_notification_granted": "Accordé. MindfulHome peut afficher les notifications de minuteur et de rappel.",
        "perm_notification_required": "Requis pour le compte à rebours et les rappels.",
        "perm_overlay_granted": "Accordé. Les rappels apparaissent au-dessus de toute appli.",
        "perm_overlay_required": "Non accordé. Les rappels ne seront que des notifications qu'Android peut silencer. Appuyez pour autoriser.",
        "perm_skipped": "Manquant. Vous avez ignoré les rappels. Accordez ici à tout moment.",
        "perm_usage_granted": "Accordé. MindfulHome peut suivre l'appli au premier plan.",
        "perm_usage_required": "Requis pour le suivi du karma. Appuyez pour autoriser.",
        "priority_selected": "P%1$d*",
        "priority_unselected": "P%1$d",
        "quick_return_window_description": "Si vous revenez dans cette fenêtre et qu'un minuteur tourne encore, l'écran minuteur est ignoré.",
        "show_hard_deadline": "Afficher la deadline stricte",
        "sign_in_with_google": "Se connecter avec Google",
        "signed_in_with_google": "Connecté avec Google",
        "signing_in": "Connexion…",
        "strikes_before_hiding_description": "Combien de points de mauvais karma une appli accumule avant d'être masquée de l'écran d'accueil.",
        "timing_intervals_description": "Polling (Quick Launch, cache d'usage, boucle de rappels), rafraîchissement de notification du minuteur, et tous les intervalles de rappel. De plus grands pas économisent la batterie.",
    },
    "it": {
        "backend_sign_in_description": "Accedi con il tuo account Google per usare il modello Gemini remoto. Senza accesso, l'app tornerà alle risposte on-device.",
        "copy_adb_command": "Copia comando adb",
        "enable": "Attiva",
        "focus_gate_length_description": "Scambi nella chat del gate focus: minimo prima che appaia Procedi, massimo prima dell'accesso automatico.",
        "focus_time_description": "Durante gli intervalli attivi, il launcher resta nascosto dopo il timer. Usa l'IA per aprire app fuori da Quick Launch.",
        "grant": "Consenti",
        "intent": "Intento",
        "max_rounds_label": "max %1$d",
        "min_rounds_label": "min %1$d",
        "no_deadline": "Nessuna scadenza",
        "ok": "OK",
        "onboarding_ai_model_body": "L'uso privato, gratuito e offline è possibile con un modello locale, ad es. Gemma3-1B (557 MB). Scaricalo da HuggingFace e mettilo nella cartella models dell'app.\nAttenzione: i modelli piccoli spesso non sono così bravi.\n\nPer una soluzione più potente e meno pesante consigliamo di accedere al nostro servizio IA Gemini.\n\nSe nessuno è configurato, verrà usato un fallback scriptato.",
        "onboarding_continue_if_stuck": "Continua (se bloccato)",
        "onboarding_default_launcher_instructions": "MindfulHome deve essere la home predefinita. Tocca sotto e seleziona MindfulHome.",
        "onboarding_default_launcher_is_default": "MindfulHome è il tuo launcher predefinito.",
        "onboarding_grant_overlay_permission": "Consenti overlay",
        "onboarding_grant_usage_access": "Consenti accesso all'utilizzo",
        "onboarding_notifications_granted": "Notifiche attive. Vedrai il countdown e gentili promemoria.",
        "onboarding_notifications_rationale": "MindfulHome usa le notifiche per il countdown e gentili promemoria a tempo scaduto. Niente spam, promesso.",
        "onboarding_overlay_granted": "Overlay consentito. I promemoria compaiono sopra qualsiasi app.",
        "onboarding_overlay_rationale": "MindfulHome può mostrare un overlay gentile quando scade il timer, anche in un'altra app. Senza, restano solo notifiche (che Android può silenziare).",
        "onboarding_skip_for_now": "Salta per ora",
        "onboarding_usage_granted": "Accesso all'utilizzo concesso. Il karma funzionerà.",
        "onboarding_usage_rationale": "MindfulHome serve l'accesso all'utilizzo per sapere quale app è in primo piano allo scadere del timer. Così funziona il karma.",
        "open_accessibility_settings": "Apri Accessibilità",
        "open_settings": "Apri impostazioni",
        "perm_accessibility_off_description": "Off. MindfulHome controlla l'app in primo piano a timer — più batteria. Attivalo per essere avvisato al cambio app — più rapido, meno batteria. Legge solo quale app è davanti, mai il contenuto.\n\nPer attivare: tocca sotto, trova MindfulHome e accendi.",
        "perm_accessibility_off_title": "Rilevamento cambio app — Off (opzionale)",
        "perm_accessibility_on_description": "On. MindfulHome è avvisato subito al cambio app, reagisce subito e non fa polling in background — meglio per la batteria. Legge solo l'app in primo piano.",
        "perm_accessibility_on_title": "Rilevamento cambio app — On ✓",
        "perm_notification_granted": "Concesso. MindfulHome può mostrare notifiche di timer e nudge.",
        "perm_notification_required": "Richiesto per countdown e notifiche nudge.",
        "perm_overlay_granted": "Concesso. I nudge compaiono sopra qualsiasi app.",
        "perm_overlay_required": "Non concesso. I nudge saranno solo notifiche che Android può silenziare. Tocca per concedere.",
        "perm_skipped": "Mancante. Hai saltato i promemoria. Concedi quando vuoi da qui.",
        "perm_usage_granted": "Concesso. MindfulHome può tracciare l'app in primo piano.",
        "perm_usage_required": "Richiesto per il karma. Tocca per concedere.",
        "priority_selected": "P%1$d*",
        "priority_unselected": "P%1$d",
        "quick_return_window_description": "Se torni entro questa finestra e un timer è ancora attivo, salta la schermata timer.",
        "show_hard_deadline": "Mostra deadline rigida",
        "sign_in_with_google": "Accedi con Google",
        "signed_in_with_google": "Accesso effettuato con Google",
        "signing_in": "Accesso…",
        "strikes_before_hiding_description": "Quanti punti di karma negativo un'app accumula prima di essere nascosta dalla home.",
        "timing_intervals_description": "Polling (Quick Launch, cache utilizzo, loop nudge), refresh notifica timer e tutti gli intervalli nudge. Passi più lunghi risparmiano batteria.",
    },
    "es": {
        "backend_sign_in_description": "Inicia sesión con tu cuenta de Google para usar el modelo Gemini remoto. Sin iniciar sesión, la app vuelve a respuestas en el dispositivo.",
        "copy_adb_command": "Copiar comando adb",
        "enable": "Activar",
        "focus_gate_length_description": "Intercambios en el chat de la puerta de foco: mínimo antes de que aparezca Continuar, máximo antes del acceso automático.",
        "focus_time_description": "Durante intervalos activos, el launcher permanece oculto tras el temporizador. Usa la IA para abrir apps fuera de Quick Launch.",
        "grant": "Conceder",
        "intent": "Intención",
        "max_rounds_label": "máx %1$d",
        "min_rounds_label": "mín %1$d",
        "no_deadline": "Sin plazo",
        "ok": "OK",
        "onboarding_ai_model_body": "El uso privado, gratis y sin conexión es posible con un modelo local, p. ej. Gemma3-1B (557 MB). Descárgalo de HuggingFace y colócalo en la carpeta models de la app.\nOjo: los modelos pequeños suelen ser menos listos de lo esperado.\n\nPara una solución más potente y menos pesada, te recomendamos iniciar sesión en nuestro servicio de IA con Gemini.\n\nSi no hay ninguno configurado, se usará un respaldo con guion.",
        "onboarding_continue_if_stuck": "Continuar (si te quedas atascado)",
        "onboarding_default_launcher_instructions": "MindfulHome debe ser tu app de inicio predeterminada. Toca abajo y elige MindfulHome.",
        "onboarding_default_launcher_is_default": "MindfulHome es tu launcher predeterminado.",
        "onboarding_grant_overlay_permission": "Conceder permiso de superposición",
        "onboarding_grant_usage_access": "Conceder acceso de uso",
        "onboarding_notifications_granted": "Notificaciones activadas. Verás la cuenta atrás y avisos suaves.",
        "onboarding_notifications_rationale": "MindfulHome usa notificaciones para la cuenta atrás y avisos suaves al acabar el tiempo. Sin spam, lo prometemos.",
        "onboarding_overlay_granted": "Superposición concedida. Los avisos aparecen sobre cualquier app.",
        "onboarding_overlay_rationale": "MindfulHome puede mostrar un aviso superpuesto cuando expire el temporizador, incluso dentro de otra app. Sin esto, solo habrá notificaciones (que Android puede silenciar).",
        "onboarding_skip_for_now": "Omitir por ahora",
        "onboarding_usage_granted": "Acceso de uso concedido. El karma funcionará.",
        "onboarding_usage_rationale": "MindfulHome necesita Acceso de uso para saber qué app está en primer plano cuando expira el temporizador. Así funciona el karma.",
        "open_accessibility_settings": "Abrir Accesibilidad",
        "open_settings": "Abrir ajustes",
        "perm_accessibility_off_description": "Desactivado. MindfulHome comprueba la app en primer plano con un temporizador — más batería. Actívalo para enterarte al cambiar de app — más rápido, menos batería. Solo lee qué app está delante, nunca el contenido.\n\nPara activar: toca abajo, busca MindfulHome y actívalo.",
        "perm_accessibility_off_title": "Detección de cambio de app — Desactivada (opcional)",
        "perm_accessibility_on_description": "Activado. MindfulHome se entera al instante del cambio de app, reacciona de inmediato y ya no hace polling en segundo plano — mejor para la batería. Solo lee la app en primer plano.",
        "perm_accessibility_on_title": "Detección de cambio de app — Activada ✓",
        "perm_notification_granted": "Concedido. MindfulHome puede mostrar notificaciones de temporizador y avisos.",
        "perm_notification_required": "Necesario para la cuenta atrás y avisos.",
        "perm_overlay_granted": "Concedido. Los avisos aparecen sobre cualquier app.",
        "perm_overlay_required": "No concedido. Los avisos solo serán notificaciones que Android puede silenciar. Toca para conceder.",
        "perm_skipped": "Falta. Omitiste los recordatorios. Concédelo aquí cuando quieras.",
        "perm_usage_granted": "Concedido. MindfulHome puede rastrear la app en primer plano.",
        "perm_usage_required": "Necesario para el karma. Toca para conceder.",
        "priority_selected": "P%1$d*",
        "priority_unselected": "P%1$d",
        "quick_return_window_description": "Si vuelves dentro de esta ventana y aún hay un temporizador, se omite la pantalla del temporizador.",
        "show_hard_deadline": "Mostrar plazo estricto",
        "sign_in_with_google": "Iniciar sesión con Google",
        "signed_in_with_google": "Sesión iniciada con Google",
        "signing_in": "Iniciando sesión…",
        "strikes_before_hiding_description": "Cuántos puntos de mal karma acumula una app antes de ocultarse de la pantalla de inicio.",
        "timing_intervals_description": "Polling (Quick Launch, caché de uso, bucle de avisos), refresco de notificación del temporizador y todos los intervalos de aviso. Pasos mayores ahorran batería.",
        # core chrome also for new locale file
        "app_name": "MindfulHome",
        "language_picker_title": "Elige tu idioma",
        "language_picker_subtitle": "Puedes cambiarlo después en Ajustes.",
        "language_picker_continue": "Continuar",
        "settings_language": "Idioma",
        "settings_language_description": "Idioma de la app para la interfaz y las respuestas de la IA.",
        "settings": "Ajustes",
        "cancel": "Cancelar",
        "save": "Guardar",
        "back": "Atrás",
        "done": "Listo",
        "add": "Añadir",
        "remove": "Eliminar",
        "permissions": "Permisos",
        "behavior": "Comportamiento",
        "about": "Acerca de",
        "welcome_to_mindfulhome": "Bienvenido a MindfulHome",
        "get_started": "Empezar",
        "how_it_works": "Cómo funciona",
        "makes_sense": "Tiene sentido",
        "karma": "Karma",
        "search_apps": "Buscar apps...",
        "search_apps_2": "Buscar apps",
    },
    "zh-rCN": {
        "backend_sign_in_description": "使用 Google 账号登录以使用远程 Gemini 模型。未登录时，应用会回退到设备端回复。",
        "copy_adb_command": "复制 adb 命令",
        "enable": "启用",
        "focus_gate_length_description": "专注时段门禁对话的来回次数：出现“继续”前的最少轮数，以及自动放行前的最多轮数。",
        "focus_time_description": "在活跃时段内，计时结束后启动器保持隐藏。用 AI 打开非 Quick Launch 应用。",
        "grant": "授权",
        "intent": "意图",
        "max_rounds_label": "最多 %1$d",
        "min_rounds_label": "最少 %1$d",
        "no_deadline": "无截止时间",
        "ok": "确定",
        "onboarding_ai_model_body": "可以使用本地模型进行私密、免费的离线使用，例如 Gemma3-1B（557 MB）。从 HuggingFace 下载并放入应用的 models 文件夹。\n请注意，小模型往往不如预期聪明。\n\n若需要更强、更省空间与算力的方案，建议登录使用我们的 Gemini AI 服务。\n\n若都未配置，将使用脚本化回退。",
        "onboarding_continue_if_stuck": "继续（若卡住）",
        "onboarding_default_launcher_instructions": "MindfulHome 需要设为默认主屏幕应用才能工作。点下方按钮并选择 MindfulHome。",
        "onboarding_default_launcher_is_default": "MindfulHome 已是你的默认启动器。",
        "onboarding_grant_overlay_permission": "授予悬浮窗权限",
        "onboarding_grant_usage_access": "授予使用情况访问权限",
        "onboarding_notifications_granted": "通知已开启。你会看到计时倒计时和温和提醒。",
        "onboarding_notifications_rationale": "MindfulHome 用通知显示计时倒计时，并在时间到时发送温和提醒。绝不刷屏。",
        "onboarding_overlay_granted": "已授予悬浮窗权限。提醒会显示在任何应用之上。",
        "onboarding_overlay_rationale": "会话计时结束时，MindfulHome 可在其他应用上显示温和提醒。若无此权限，提醒只能作为通知（Android 可能逐渐静音）。",
        "onboarding_skip_for_now": "暂时跳过",
        "onboarding_usage_granted": "已授予使用情况访问。业力追踪将可用。",
        "onboarding_usage_rationale": "MindfulHome 需要使用情况访问，以便在计时结束时知道前台应用。这是业力追踪的基础。",
        "open_accessibility_settings": "打开无障碍设置",
        "open_settings": "打开设置",
        "perm_accessibility_off_description": "关闭。MindfulHome 目前定时检查前台应用——更耗电。开启后可在切换应用时立即得知——反应更快、更省电。只读取前台应用，从不读取屏幕内容。\n\n启用：点下方，在已安装应用中找到 MindfulHome 并打开。",
        "perm_accessibility_off_title": "应用切换检测 — 关闭（可选）",
        "perm_accessibility_on_description": "开启。MindfulHome 在你切换应用时立即收到通知，立刻反应且不再后台轮询——更省电。只读取前台应用，从不读取屏幕内容。",
        "perm_accessibility_on_title": "应用切换检测 — 开启 ✓",
        "perm_notification_granted": "已授权。MindfulHome 可显示计时与提醒通知。",
        "perm_notification_required": "计时倒计时与提醒通知所需。",
        "perm_overlay_granted": "已授权。提醒会显示在任何应用之上。",
        "perm_overlay_required": "未授权。提醒只会作为通知，Android 可能逐渐静音。点此授权。",
        "perm_skipped": "缺失。你选择了跳过权限提醒。可随时在此授权。",
        "perm_usage_granted": "已授权。MindfulHome 可跟踪前台应用。",
        "perm_usage_required": "业力追踪所需。点此授权。",
        "priority_selected": "P%1$d*",
        "priority_unselected": "P%1$d",
        "quick_return_window_description": "若在此时间窗口内返回且计时仍在运行，则跳过计时界面。",
        "show_hard_deadline": "显示硬截止时间",
        "sign_in_with_google": "使用 Google 登录",
        "signed_in_with_google": "已使用 Google 登录",
        "signing_in": "正在登录…",
        "strikes_before_hiding_description": "应用在被从主屏幕隐藏前可累积多少负业力点数。",
        "timing_intervals_description": "轮询（Quick Launch、使用缓存、提醒循环）、计时通知刷新，以及所有提醒间隔。更大步长更省电。",
        "app_name": "MindfulHome",
        "language_picker_title": "选择语言",
        "language_picker_subtitle": "之后可在设置中更改。",
        "language_picker_continue": "继续",
        "settings_language": "语言",
        "settings_language_description": "界面与 AI 回复使用的应用语言。",
        "settings": "设置",
        "cancel": "取消",
        "save": "保存",
        "back": "返回",
        "done": "完成",
        "add": "添加",
        "remove": "移除",
        "permissions": "权限",
        "behavior": "行为",
        "about": "关于",
        "welcome_to_mindfulhome": "欢迎使用 MindfulHome",
        "get_started": "开始",
        "how_it_works": "工作原理",
        "makes_sense": "明白了",
        "karma": "业力",
        "search_apps": "搜索应用…",
        "search_apps_2": "搜索应用",
    },
    "ja": {
        "backend_sign_in_description": "リモートの Gemini モデルを使うには Google アカウントでサインインしてください。サインインしない場合は端末上の応答にフォールバックします。",
        "copy_adb_command": "adb コマンドをコピー",
        "enable": "有効にする",
        "focus_gate_length_description": "フォーカスゲートチャットのやり取り回数：「続行」が出るまでの最小、自動許可までの最大。",
        "focus_time_description": "アクティブな時間帯では、タイマー後もランチャーは非表示のままです。Quick Launch 以外のアプリは AI で開きます。",
        "grant": "許可",
        "intent": "意図",
        "max_rounds_label": "最大 %1$d",
        "min_rounds_label": "最小 %1$d",
        "no_deadline": "期限なし",
        "ok": "OK",
        "onboarding_ai_model_body": "Gemma3-1B（557 MB）などのローカルモデルで、非公開・無料・オフライン利用が可能です。HuggingFace からダウンロードし、アプリの models フォルダに配置してください。\nただし小さなモデルは期待ほど賢くないことがあります。\n\nより強力で容量・計算負荷が少ない方法として、Gemini の AI サービスへのサインインをおすすめします。\n\nどちらも未設定の場合はスクリプトのフォールバックが使われます。",
        "onboarding_continue_if_stuck": "続行（固まった場合）",
        "onboarding_default_launcher_instructions": "動作には MindfulHome をデフォルトのホームアプリにする必要があります。下のボタンを押し、MindfulHome を選んでください。",
        "onboarding_default_launcher_is_default": "MindfulHome がデフォルトのランチャーです。",
        "onboarding_grant_overlay_permission": "オーバーレイを許可",
        "onboarding_grant_usage_access": "使用状況へのアクセスを許可",
        "onboarding_notifications_granted": "通知オン。タイマーのカウントダウンとやさしいリマインダーが表示されます。",
        "onboarding_notifications_rationale": "MindfulHome は通知でタイマーのカウントダウンを示し、時間切れにやさしく知らせます。スパムはありません。",
        "onboarding_overlay_granted": "オーバーレイ許可済み。どのアプリの上にもリマインダーが出ます。",
        "onboarding_overlay_rationale": "セッションタイマー終了時、他のアプリの上にもやさしいオーバーレイを表示できます。これがないと通知のみになり、Android が徐々に黙らせることがあります。",
        "onboarding_skip_for_now": "今はスキップ",
        "onboarding_usage_granted": "使用状況アクセスを許可済み。カルマ追跡が動きます。",
        "onboarding_usage_rationale": "タイマー終了時に前面のアプリを知るため、使用状況アクセスが必要です。これがカルマ追跡の仕組みです。",
        "open_accessibility_settings": "ユーザー補助を開く",
        "open_settings": "設定を開く",
        "perm_accessibility_off_description": "オフ。MindfulHome はタイマーで前面アプリを確認しており、電池を多く使います。オンにするとアプリ切替をすぐ検知でき、反応が速く電池も節約できます。前面アプリ名のみ読み取り、画面内容は読みません。\n\n有効化：下をタップし、インストール済みアプリで MindfulHome を見つけてオン。",
        "perm_accessibility_off_title": "アプリ切替検出 — オフ（任意）",
        "perm_accessibility_on_description": "オン。アプリ切替をすぐ通知され、即座に反応しバックグラウンドでポーリングしません — 電池に有利。前面アプリのみ読み取ります。",
        "perm_accessibility_on_title": "アプリ切替検出 — オン ✓",
        "perm_notification_granted": "許可済み。タイマーとナッジ通知を表示できます。",
        "perm_notification_required": "タイマーのカウントダウンとナッジ通知に必要です。",
        "perm_overlay_granted": "許可済み。どのアプリの上にもナッジが出ます。",
        "perm_overlay_required": "未許可。ナッジは通知のみになり、Android が黙らせることがあります。タップして許可。",
        "perm_skipped": "未設定。権限リマインダーをスキップしました。ここからいつでも許可できます。",
        "perm_usage_granted": "許可済み。前面アプリを追跡できます。",
        "perm_usage_required": "カルマ追跡に必要です。タップして許可。",
        "priority_selected": "P%1$d*",
        "priority_unselected": "P%1$d",
        "quick_return_window_description": "この時間内に戻り、タイマーがまだ動いていればタイマー画面をスキップします。",
        "show_hard_deadline": "厳しい締切を表示",
        "sign_in_with_google": "Google でサインイン",
        "signed_in_with_google": "Google でサインイン済み",
        "signing_in": "サインイン中…",
        "strikes_before_hiding_description": "ホーム画面から隠されるまでにアプリが溜められる悪いカルマ点数。",
        "timing_intervals_description": "ポーリング（Quick Launch、使用キャッシュ、ナッジループ）、タイマー通知の更新、すべてのナッジ間隔。大きい刻みほど電池を節約。",
        "app_name": "MindfulHome",
        "language_picker_title": "言語を選択",
        "language_picker_subtitle": "後から設定で変更できます。",
        "language_picker_continue": "続ける",
        "settings_language": "言語",
        "settings_language_description": "インターフェースと AI 返信に使うアプリの言語。",
        "settings": "設定",
        "cancel": "キャンセル",
        "save": "保存",
        "back": "戻る",
        "done": "完了",
        "add": "追加",
        "remove": "削除",
        "permissions": "権限",
        "behavior": "動作",
        "about": "情報",
        "welcome_to_mindfulhome": "MindfulHome へようこそ",
        "get_started": "はじめる",
        "how_it_works": "仕組み",
        "makes_sense": "了解",
        "karma": "カルマ",
        "search_apps": "アプリを検索…",
        "search_apps_2": "アプリを検索",
    },
}

BULLETS = {
    "es": [
        "電話のロックを解除するたびにタイマーを設定",  # WRONG - fix
    ],
}

# Proper bullets per locale
BULLETS = {
    "de": [
        "Lege bei jedem Entsperren einen Timer fest",
        "Apps erhalten Karma, je nachdem ob du den Timer einhältst",
        "Apps mit niedrigem Karma werden ausgeblendet (aber nie blockiert)",
        "Sprich mit der KI, um ausgeblendete Apps zu öffnen — sie fragt nach, gibt aber immer nach",
        "Keine App wird von MindfulHome geschlossen oder zwangsbeendet",
    ],
    "fr": [
        "Réglez un minuteur à chaque déverrouillage",
        "Les applis gagnent du karma selon le respect du minuteur",
        "Les applis à faible karma sont masquées (jamais bloquées)",
        "Parlez à l'IA pour accéder aux applis masquées — elle demande, mais cède toujours",
        "Aucune appli n'est fermée de force par MindfulHome",
    ],
    "it": [
        "Imposta un timer ogni volta che sblocchi il telefono",
        "Le app guadagnano karma in base al rispetto del timer",
        "Le app a basso karma vengono nascoste (mai bloccate)",
        "Parla con l'IA per accedere alle app nascoste — chiede, ma cede sempre",
        "Nessuna app viene chiusa o forzata da MindfulHome",
    ],
    "es": [
        "Pon un temporizador cada vez que desbloquees el teléfono",
        "Las apps ganan karma según si respetas el temporizador",
        "Las apps de bajo karma se ocultan (nunca se bloquean)",
        "Habla con la IA para abrir apps ocultas — preguntará, pero siempre cederá",
        "MindfulHome nunca cierra ni fuerza el cierre de ninguna app",
    ],
    "zh-rCN": [
        "每次解锁手机时设置计时器",
        "应用根据你是否遵守计时获得业力",
        "低业力应用会被隐藏（但从不拦截）",
        "与 AI 对话以访问隐藏应用——它会询问，但总会让步",
        "MindfulHome 从不关闭或强制停止任何应用",
    ],
    "ja": [
        "ロック解除のたびにタイマーを設定",
        "タイマーを守れたかでアプリにカルマが付く",
        "カルマが低いアプリは非表示（ブロックはしない）",
        "非表示アプリは AI に話して開く — 尋ねるが、必ず折れる",
        "MindfulHome がアプリを強制終了することはない",
    ],
}


def esc(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", r"\'")
    )


def patch_existing(locale: str, additions: dict[str, str]) -> None:
    path = RES / f"values-{locale}" / "strings.xml"
    text = path.read_text(encoding="utf-8")
    existing = set(re.findall(r'<string name="([^"]+)">', text))
    to_add = {k: v for k, v in additions.items() if k not in existing}
    if not to_add:
        print(f"{locale}: nothing to add")
        return
    insert = "\n".join(f'    <string name="{k}">{esc(v)}</string>' for k, v in sorted(to_add.items()))
    # before string-array or closing resources
    if "<string-array" in text:
        text = text.replace("    <string-array", insert + "\n    <string-array", 1)
    else:
        text = text.replace("</resources>", insert + "\n</resources>", 1)
    # ensure bullets present
    if 'name="onboarding_philosophy_bullets"' not in text and locale in BULLETS:
        items = "\n".join(f"        <item>{esc(b)}</item>" for b in BULLETS[locale])
        block = (
            '    <string-array name="onboarding_philosophy_bullets">\n'
            + items
            + "\n    </string-array>\n"
        )
        text = text.replace("</resources>", block + "</resources>", 1)
    path.write_text(text, encoding="utf-8")
    print(f"{locale}: added {len(to_add)} keys")


def write_partial(locale: str, strings: dict[str, str]) -> None:
    path = RES / f"values-{locale}" / "strings.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k, v in sorted(strings.items()):
        lines.append(f'    <string name="{k}">{esc(v)}</string>')
    if locale in BULLETS:
        lines.append('    <string-array name="onboarding_philosophy_bullets">')
        for b in BULLETS[locale]:
            lines.append(f"        <item>{esc(b)}</item>")
        lines.append("    </string-array>")
    lines.append("</resources>")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{locale}: wrote partial file ({len(strings)} strings)")


def main() -> None:
    for loc in ("de", "fr", "it"):
        patch_existing(loc, MISSING[loc])
    for loc in ("es", "zh-rCN", "ja"):
        write_partial(loc, MISSING[loc])


if __name__ == "__main__":
    main()
