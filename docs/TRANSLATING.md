# Translating Adv Notes

The app's text lives in JSON files in [`app/src/main/assets/i18n/`](../app/src/main/assets/i18n/), one per language.
The English text is the key, so a translation is just a list of “English → your language” pairs.

## Start a new language

1. Copy `en.json` to `<language tag>.json`, using a [BCP 47 tag](https://en.wikipedia.org/wiki/IETF_language_tag)
   such as `es.json`, `de.json` or `pt-BR.json`. The app picks it automatically when the phone's language matches,
   and it also appears under **Settings → Language**.
2. Fill in `_meta`:
   ```json
   "_meta": { "name": "Español", "machineTranslated": false }
   ```
   `name` is shown in the language list, written in the language itself. Set `machineTranslated` to `true` if the
   text came from a translation tool or AI and hasn't been checked by a fluent speaker; the app then labels it
   “machine-translated”.
3. Translate the values. Leave a value empty (`""`) to fall back to English.
   - Keep placeholders like `{0}` and `{1}`; they are filled with names, numbers and so on. Their order may change.
   - Keep leading/trailing spaces and `\n` line breaks: some strings are joined to others.
   - Product names (S Pen, Google Drive, Hugging Face, GGUF, Xournal++) usually stay as they are.
4. Check your file: `python3 scripts/i18n.py --check` lists missing keys and placeholder mistakes.
5. Open a pull request.

## Improving an existing translation

Edit the values in its JSON file. Once a fluent speaker has reviewed a machine translation, set
`"machineTranslated": false`.

## For developers

- Wrap user-facing text in `tr()` (from `com.advnotes.app.i18n`):
  `Text(tr("Delete page"))`, `tr("Delete {0}?", name)`. Don't translate keys, IDs or prompts sent to the AI.
- Enums with a display label take it as `private val en: String` and expose `val label get() = tr(en)`.
- After adding or changing strings, run `python3 scripts/i18n.py`. It regenerates `en.json` and adds the new keys
  (empty) to every translation. CI runs `--check` and fails if `en.json` is stale.
- Tools in the tool store have their own files: `tools/<id>/i18n/<tag>.json` in the adv-notes-tools repository
  (same format; see its README). The app passes the matching file to each tool.
