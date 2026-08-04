# Internationalization (i18n)

## Languages

In-app language, chosen on first onboarding step and changeable in Settings. **System default** clears the per-app locale override and follows the device language (best supported match, else English).

| Locale | Folder |
|--------|--------|
| System default | (device) |
| English | `values/` |
| German | `values-de/` |
| French | `values-fr/` |
| Italian | `values-it/` |
| Spanish | `values-es/` |
| Simplified Chinese | `values-zh-rCN/` |
| Japanese | `values-ja/` |

Arabic was deferred (RTL layout QA).

Translations are **first-pass machine translation**.

- **de / fr / it**: full string set matching English
- **es / zh-rCN / ja**: core UI + onboarding + permissions translated; remaining keys fall back to English via Android resources until filled

Missing keys in a locale fall back to English.

## AI replies

System prompts append `Always write your replies in <Language> (locale <tag>).` from `PromptTemplates.withReplyLanguage`. Offline nudge fallbacks (e.g. “Your time is up…”) and notification copy (timer countdown, Quick Launch status, reply actions, extension prompts) use `strings.xml` via `LocaleHelper.wrap` — Services do not inherit AppCompat per-app locales on their own.

## Tooling

Used to create first-pass translation; to modify just edit the strings.xml directly.  

- `scripts/i18n/extracted_strings.json` — catalog from source scan
- `scripts/i18n/generate_resources.py` — English `strings.xml` generator
- `scripts/i18n/apply_string_resources.py` — wire `stringResource` in UI
- `scripts/i18n/translations/` — per-locale JSON used to emit XML
- `scripts/i18n/emit_locale_xml.py` — writes `values-<locale>/strings.xml` from those JSON files
