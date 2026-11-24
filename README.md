# Adv Notes

A notebook-style handwriting app for Android, built for the S Pen. It has an AI solver for math, physics and chemistry, handwriting-to-text, imports from other note apps, and a side panel of tools (scientific calculator, PDF reader, AI chat) that anyone can extend.

<p>
<img src="docs/screenshots/03_editor_ink.png" width="240">
<img src="docs/screenshots/04_editor_lasso.png" width="240">
<img src="docs/screenshots/05_ai_sheet.png" width="240">
</p>

On a tablet: two pages side by side with a picture placed on the page, tool options that pop out from the toolbar, and the page list.

<p>
<img src="docs/screenshots/31_two_pages_with_picture.png" width="420">
<img src="docs/screenshots/32_pen_options_popout.png" width="420">
</p>
<p>
<img src="docs/screenshots/33_shape_options.png" width="420">
<img src="docs/screenshots/34_page_list.png" width="420">
</p>

## Features

**Writing**
- Pressure-sensitive pen (S Pen / any active stylus) with an adjustable pressure curve. Strokes are smoothed, variable-width outlines.
- Highlighter that sits *under* the ink, with an optional "always straight" mode.
- Hold to snap: draw a line or shape and keep the pen still for a moment. It turns into a straight line (snapping to 0°/45°/90°), a circle/ellipse, a rectangle, a triangle or a polygon. You can keep moving a snapped line's end point before lifting.
- Shape tool: line, arrow, rectangle, ellipse, triangle, right triangle, diamond, coordinate axes.
- Eraser: whole-stroke or partial. The S Pen side button (configurable) gives a temporary eraser or lasso, and a pen's eraser end works too.
- Lasso: select ink and text, then move, resize (corner handle), duplicate, recolour, delete or **Solve**.
- Text boxes; undo/redo; pinch to zoom, two fingers to pan, double-tap to fit.
- Palm rejection: in *Auto* mode fingers draw until a pen is seen, after which fingers only scroll and zoom.
- Movable toolbar: drag its ⠿ handle to the top, bottom, left or right edge (icons stay centred). The tools panel can sit left or right of the page: drag or tap its handle.

**Notebooks**
- Paper styles: Plain, Narrow grid (5 mm), Grid (7 mm), Dotted, **Oxford** (7 mm ruling, header band, red margin), College ruled, Wide ruled, Cornell, Isometric. Paper colours: white, cream, mint and dark. You can set the style per notebook or per page.
- Multiple pages, PDF export / share.

**Import from other apps** (library ⇪ button, or *Insert pages from file…* inside a notebook)
- **Samsung Notes** `.sdocx`: handwriting comes in as real, editable strokes with their colour, pen size and pressure, plus shapes and lines (Share → *Samsung Notes file* in Samsung Notes). The format is undocumented; the decoder follows the reverse-engineered descriptions in [bxff/samsung-notes-format](https://github.com/bxff/samsung-notes-format) (MIT) and the format facts documented by [twangodev/sdocx](https://github.com/twangodev/sdocx). Typed text, images and audio are skipped; export to PDF from Samsung Notes if you need those. Pen *types* (fountain pen, brush, pencil texture) become a normal pen.
- **PDF** (also how GoodNotes, Notability, OneNote and Samsung Notes export): every page becomes a page you can write on. Circle printed text with the lasso and *Solve* or *To text* works on it.
- **Images** (PNG/JPEG/WebP; pick several for several pages).
- **Xournal++** `.xopp`: strokes with pressure, highlighter, text and paper style.

Imported PDFs and images sync to Google Drive with the notebook.

<img src="docs/screenshots/paper_styles.png" width="420">

**AI solver (math, physics, chemistry).** Lasso some handwriting or part of an imported worksheet and tap **Solve**. Pick a subject (*Auto* detects it) and *Solve*, *Answer only*, *Check my work* or *Hint*. Physics answers list givens with SI units and carry units through; chemistry answers balance equations and show the mole working. The answer streams in and can be written onto the page next to your work.

**Handwriting to text.** Lasso → **To text**, choose the language (27). Recognition runs on-device from your pen strokes with Google ML Kit Digital Ink (works offline after a one-time model download per language), or through your AI provider (auto-detects the language and also reads printed text). Then replace the ink with typed text, keep both, or copy.

**Tools side panel** (▦ in the editor): small apps next to your notes. On tablets they sit beside the page (drag the divider to resize); on phones they open over the bottom half. Several can stay open as tabs.
- **Scientific calculator.** The C++ engine of [moondotgz/casio](https://github.com/moondotgz/casio) (fx-991CEX feature set), compiled for Android through a small Qt-compatibility layer; the engine's own test suite passes unchanged. Exact fractions, √ and π, ∫, d/dx, Σ/Π, 47 constants, 40 unit conversions, STO/RCL variables, complex and base-N modes, polynomial and linear-system solvers, and statistics with 7 regressions. Long-press any result to write it on the page.
- **PDF reader** (pdf.js): a library of your PDFs, table of contents from the document outline, bookmarks named after their section, full-text search, remembered position, *Cite this page* into your notes, and *Summarise / Explain / Ask about this page* with AI.
- **AI chat** for everything else: explanations, essays, translation, quizzes, flashcards. It can look at your lasso selection or the visible page, and conversations are kept.
- **Build your own:** web tools are a folder with `manifest.json` + HTML/JS, installed from a `.zip` in *Settings → Tools*; most web apps port without changes. Native tools are a Kotlin interface. See **[docs/TOOLS.md](docs/TOOLS.md)** and the example in [`docs/tool-template`](docs/tool-template).

<p>
<img src="docs/screenshots/13_calculator.png" width="300">
<img src="docs/screenshots/17_solver_subjects.png" width="300">
<img src="docs/screenshots/11_imported_samsung_notes.png" width="300">
</p>
<img src="docs/screenshots/pdf_reader.png" width="900">

| Provider | Notes |
|---|---|
| OpenAI (API key) | Chat Completions with image input. Default `gpt-5`. |
| ChatGPT subscription (**unofficial**) | signs in as the open-source Codex CLI (same OAuth client and headers) and uses the Codex backend.|
| Anthropic (API key) | Messages API, default `claude-opus-5-5`, with server-side refusal fallback enabled for models that support it. |
| OpenRouter | Any vision model. |
| Custom OpenAI-compatible | LM Studio, Ollama, llama.cpp server, vLLM, LiteLLM… (base URL + optional key) |
| Custom Anthropic-compatible | Any `/v1/messages` server. |
| On-device (llama.cpp) | Runs a vision model fully offline through llama.cpp + libmtmd (JNI, arm64). Search Hugging Face from Settings (trending vision models, or any GGUF repo), pick the quantisation and vision projector (mmproj), and download, with a warning when it won't fit in the device's RAM; an optional Hugging Face token unlocks gated models. Or import your own GGUF + mmproj files. |

Each provider has a ↻ button that loads its model list, plus a *Test connection* button. API keys and tokens are stored in EncryptedSharedPreferences (Android Keystore).

**Google Drive sync.** Notebooks sync two-way with an "Adv Notes" folder in your Drive, using the narrow `drive.file` scope. Sync runs automatically every hour and after you close a notebook. If a notebook was edited on two devices, you get a "(conflict copy)" instead of losing edits. Local deletions propagate.


## Languages

English and Brazilian Portuguese (machine-translated). Pick one under **Settings → Language**; it follows the phone's language by default. Adding a language is a single JSON file, see [docs/TRANSLATING.md](docs/TRANSLATING.md).

## Building

Requirements: JDK 17+, Android SDK 35, and for the on-device runner NDK `27.2.12479018` + CMake `3.22.1`.

```bash
git clone --recursive <repo>      # llama.cpp and the casio calculator engine are submodules
./gradlew assembleRelease         # app/build/outputs/apk/release/app-release.apk
```

- Without the submodule, or with `advnotes.llama=false` in `gradle.properties`, the app builds without the native runner (faster, smaller). The on-device option then shows as unavailable.
- The release build is signed with the debug key so it installs directly. Add your own signing config before publishing.
- CI (`.github/workflows/build.yml`) runs the unit tests and uploads the release APK as an artifact on every push.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

- `InkLogicTest`: shape recognition, partial eraser, lasso hit-testing, JSON round-trip.
- `ImportTest`: Samsung Notes and Xournal++ import on real sample files, format detection.
- `HandwritingTest`: line grouping for recognition, subject prompts.
- `CalculatorEngineTest`: the C++ calculator engine through the real JNI bridge (built for the host by the `buildHostCasio` task; needs `g++`).
- `RenderSnapshots` / `UiSnapshots`: Robolectric (native graphics) runs that drive the real UI with simulated S Pen events (pressure, side button, hold-to-snap, lasso), open the tools panel and use the calculator, and write screenshots to `app/build/snapshots/`.

The calculator engine's own suite also runs against the compatibility layer on a desktop: `g++ -std=c++20 -Iapp/src/main/cpp/casio_shim -Iapp/src/main/cpp/casio/src -Iapp/src/main/cpp/casio/src/core app/src/main/cpp/casio/src/core/*.cpp app/src/main/cpp/casio/tests/test_core.cpp && ./a.out` (124/124 pass).

## Project layout

```
app/src/main/java/com/advnotes/app/
  ink/      InkCanvasView (input, tools, viewport), StrokeRenderer, PaperRenderer, Shapes, Geometry
  editor/   EditorScreen + EditorViewModel (undo/redo, pages, AI sheet)
  ai/       provider clients (SSE streaming), ChatGPT OAuth, llama.cpp bridge, model downloads
  sync/     Google Drive two-way sync (notebooks + imported files) + WorkManager job
  importer/ PDF, image, Samsung Notes (.sdocx) and Xournal++ (.xopp) import
  tools/    side panel, tool framework (native + web tools, JS bridge), calculator, AI chat
  data/     JSON notebook storage, settings / encrypted secrets
  ui/       library, settings, theme
app/src/main/assets/tools/   bundled web tools (PDF reader with pdf.js) and the AdvNotes JS API
app/src/main/cpp/
  llama_jni.cpp           on-device LLM bridge (llama.cpp submodule)
  casio/                  calculator engine (moondotgz/casio submodule)
  casio_shim/             Qt-compatibility layer so the engine builds without Qt
  casio_bridge/           JSON API + JNI for the calculator
```

## Third-party code

llama.cpp (MIT), pdf.js (Apache-2.0, bundled in `assets/tools/pdfreader/pdfjs`), Google ML Kit Digital Ink Recognition, and the Samsung Notes format description from bxff/samsung-notes-format (MIT; two of its sample notes are used as test fixtures).
