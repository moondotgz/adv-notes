# Building tools for Adv Notes

Tools are small apps that open in a side panel next to a notebook: the calculator, the PDF reader and the AI chat are all tools. There are two kinds:

| | Web tool | Native tool |
|---|---|---|
| Written in | HTML / CSS / JavaScript | Kotlin + Jetpack Compose |
| Ships as | a `.zip` the user installs (Settings → Tools), or a folder in `app/src/main/assets/tools/` | code in the app |
| Good for | porting existing web apps, quick tools | deep integration, native libraries (e.g. the C++ calculator engine) |

## Web tools

A web tool is a folder:

```
my-tool/
  manifest.json
  index.html        (+ any JS, CSS, images, wasm…)
```

`manifest.json`:

```json
{
  "id": "unit-converter",          // lowercase letters, digits, - and _ ; unique
  "name": "Unit converter",
  "description": "Shown in the tool picker",
  "entry": "index.html",           // optional, default index.html
  "icon": "functions",             // calculate, book, chat, science, functions, translate, timer, map, extension
  "version": "1.0",
  "author": "you"
}
```

Zip the folder (the manifest may be at the zip root or inside one top-level folder) and install it from **Settings → Tools → Install tool from .zip**. Installing a tool with the same id replaces it, and an installed tool overrides a bundled one with the same id, which is handy while you iterate. A complete example is in [`docs/tool-template`](tool-template).

### Where it runs

The page is served from `https://appassets.androidplatform.net/…` inside an Android WebView, with JavaScript, DOM storage, `fetch`, WebAssembly and ES modules available. Relative URLs work as usual. Links to other sites open in the browser.

### The `AdvNotes` API

`window.AdvNotes` is injected before your scripts run. If you load scripts very early, you can also wait for the `advnotesready` event.

| Call | What it does |
|---|---|
| `AdvNotes.insertText(text)` | Writes text onto the current page (below the lasso selection, or in the visible area). |
| `AdvNotes.selectionImage()` | PNG data URL of the current lasso selection, or `null`. |
| `AdvNotes.pageImage()` | PNG data URL of the visible part of the page. |
| `await AdvNotes.ask(prompt, opts)` | Asks the AI provider the user configured. `opts`: `system`, `history: [{role: "user"\|"assistant", text}]`, `image: "selection"\|"page"\|dataURL`, `onText(partial)` for streaming. Resolves with the answer text. |
| `AdvNotes.storage.get(k)` / `.set(k, v)` / `.getJSON(k, default)` / `.setJSON(k, v)` | Persistent per-tool storage. |
| `await AdvNotes.pickFile(["application/pdf"])` | System file picker; the file is copied into the tool's storage. Resolves `{name, url}` or `null`. Load the `url` with `fetch` or pass it to a library. |
| `await AdvNotes.files()` / `await AdvNotes.deleteFile(name)` | List or remove stored files: `[{name, url, size}]`. |
| `AdvNotes.toast(msg)` | Short Android toast. |
| `AdvNotes.theme` | `"light"` or `"dark"`; also set as `data-theme` on `<html>`. |
| `AdvNotes.notebookTitle` | Title of the open notebook. |

### Porting an existing web app

1. Copy the built app (the folder with `index.html`) and add a `manifest.json`.
2. Make sure asset URLs are relative (`./app.js`, not `/app.js`), or rewrite absolute ones to start with your tool's folder.
3. Optionally wire it in: add a "write on page" button that calls `AdvNotes.insertText`, and use `AdvNotes.storage` instead of `localStorage` if you want data tied to the app.
4. Zip it and install it.

## Native tools

Implement `com.advnotes.app.tools.NotebookTool` and add it to `BuiltinTools.all()`:

```kotlin
object StopwatchTool : NotebookTool {
    override val id = "stopwatch"
    override val name = "Stopwatch"
    override val description = "Time experiments and write the result down"
    override val icon = Icons.Default.Timer

    @Composable
    override fun Content(host: ToolHost) {
        // Compose UI. host.insertText(…), host.selectionImage(), host.ai(), host.storage, host.pickFile(…)
    }
}
```

`ToolHost` offers the same abilities as the JavaScript API. Tools stay composed while the user switches tabs in the panel, so `remember` state survives tab switches but not closing the tool. Use `host.storage` for anything that should persist.

For native code (C/C++), add sources under `app/src/main/cpp` and a JNI bridge. The calculator (`tools/calculator`, which wraps the C++ engine of moondotgz/casio through a small Qt-compatibility layer in `cpp/casio_shim`) is a worked example.
