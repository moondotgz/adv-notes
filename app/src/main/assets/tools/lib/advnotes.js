/*
 * Adv Notes web-tool API. Injected automatically into every web tool; including it yourself with
 * <script src="https://appassets.androidplatform.net/assets/tools/lib/advnotes.js"></script>
 * is harmless. Full reference: docs/TOOLS.md
 */
(function () {
  if (window.AdvNotes || !window.AdvNotesNative) return;
  var N = window.AdvNotesNative;
  var pending = {};
  // Translations of this tool for the app language: { "English text": "translation", "_meta": {...} }
  var STR = {};
  try { STR = N.strings ? JSON.parse(N.strings()) || {} : {}; } catch (e) { STR = {}; }
  function t(key) {
    var s = (typeof STR[key] === 'string' && STR[key]) ? STR[key] : String(key);
    for (var i = 1; i < arguments.length; i++) s = s.split('{' + (i - 1) + '}').join(String(arguments[i]));
    return s;
  }
  // Translates text nodes and placeholder/title/aria-label attributes whose whole text is a key.
  // Mixed text (numbers, names…) is translated by the tool itself with AdvNotes.t().
  var ATTRS = ['placeholder', 'title', 'aria-label'];
  function norm(x) { return String(x).replace(/\s+/g, ' ').trim(); }
  function translateNode(root) {
    if (!root || root.nodeType === 8) return;
    if (root.nodeType === 3) {
      var raw = root.nodeValue, k = norm(raw);
      if (k && typeof STR[k] === 'string' && STR[k] && k !== STR[k]) {
        var lead = raw.match(/^\s*/)[0], trail = raw.match(/\s*$/)[0];
        root.nodeValue = lead + STR[k] + trail;
      }
      return;
    }
    if (root.nodeType !== 1 || /^(SCRIPT|STYLE|TEXTAREA)$/.test(root.tagName) || root.getAttribute('translate') === 'no') return;
    for (var a = 0; a < ATTRS.length; a++) {
      var v = root.getAttribute(ATTRS[a]), vk = v && norm(v);
      if (vk && typeof STR[vk] === 'string' && STR[vk] && STR[vk] !== vk) root.setAttribute(ATTRS[a], STR[vk]);
    }
    for (var c = root.firstChild; c; c = c.nextSibling) translateNode(c);
  }
  function hasStrings() { for (var k in STR) if (k !== '_meta') return true; return false; }
  var seq = 0;

  function call(method, args, onProgress) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject, onProgress: onProgress };
      N.request(id, method, JSON.stringify(args || {}));
    });
  }

  window.AdvNotes = {
    /** "light" or "dark", following the app theme. */
    theme: N.theme(),
    /** App language tag, e.g. "en" or "pt-BR", and its name ("Português (Brasil)"). */
    lang: N.language ? N.language() : 'en',
    languageName: N.languageName ? N.languageName() : 'English',
    /**
     * Translates English UI text with this tool's i18n/<lang>.json; falls back to English.
     * t("Delete {0}?", name). Static HTML text is translated automatically.
     */
    t: t,
    /** The app's colours, e.g. colors.primary = "#3557D6" (also set as CSS variables --an-primary, --an-surface, …). */
    colors: (function () { try { return N.colors ? JSON.parse(N.colors()) : {}; } catch (e) { return {}; } })(),
    /** Name of the notebook the tool is open next to. */
    notebookTitle: N.notebookTitle(),

    /** Write text onto the current notebook page. */
    insertText: function (text) { N.insertText(String(text)); },
    /** Place an image (PNG/JPEG data URL, e.g. canvas.toDataURL()) on the page as a movable picture. */
    insertImage: N.insertImage ? function (dataUrl) { return N.insertImage(String(dataUrl)); } : undefined,
    /** Show a short Android toast. */
    toast: function (msg) { N.toast(String(msg)); },
    /** PNG data URL of the current lasso selection, or null. */
    selectionImage: function () { return N.selectionImage() || null; },
    /** PNG data URL of the visible part of the current page. */
    pageImage: function () { return N.pageImage() || null; },

    /** Persistent per-tool storage (strings), plus JSON helpers. */
    storage: {
      get: function (k) { var v = N.storageGet(String(k)); return v === null || v === undefined ? null : v; },
      set: function (k, v) { N.storageSet(String(k), v === null || v === undefined ? null : String(v)); },
      getJSON: function (k, dflt) { try { var v = this.get(k); return v === null ? dflt : JSON.parse(v); } catch (e) { return dflt; } },
      setJSON: function (k, v) { this.set(k, JSON.stringify(v)); }
    },

    /**
     * Ask the AI provider configured in Settings.
     * opts: { system, history: [{role:"user"|"assistant", text}], image: "selection"|"page"|dataURL, onText(partial) }
     * Resolves with the full answer text.
     */
    ask: function (prompt, opts) {
      opts = opts || {};
      return call('ai', { prompt: prompt, system: opts.system, history: opts.history, image: opts.image }, opts.onText);
    },
    /** Let the user pick a file; it is copied into the tool's storage. Resolves {name, url} or null. */
    pickFile: function (accept) { return call('pickFile', { accept: accept || ['*/*'] }); },
    /** Files in the tool's storage: [{name, url, size}]. */
    files: function () { return call('listFiles', {}); },
    deleteFile: function (name) { return call('deleteFile', { name: name }); },

    _resolve: function (id, ok, payload) {
      var p = pending[id];
      if (!p) return;
      delete pending[id];
      if (ok) p.resolve(payload); else p.reject(new Error(payload));
    },
    _progress: function (id, text) {
      var p = pending[id];
      if (p && p.onProgress) p.onProgress(text);
    }
  };
  // May run at document start, before <html> exists.
  function ready() {
    var root = document.documentElement;
    root.setAttribute('data-theme', window.AdvNotes.theme);
    var c = window.AdvNotes.colors;
    for (var k in c) root.style.setProperty('--an-' + k, c[k]);
    if (hasStrings()) {
      document.documentElement.setAttribute('lang', window.AdvNotes.lang);
      var go = function () {
        translateNode(document.body);
        new MutationObserver(function (muts) {
          for (var i = 0; i < muts.length; i++) {
            var m = muts[i];
            if (m.type === 'characterData') translateNode(m.target);
            else if (m.type === 'attributes') translateNode(m.target);
            else for (var j = 0; j < m.addedNodes.length; j++) translateNode(m.addedNodes[j]);
          }
        }).observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
      };
      if (document.body) go(); else document.addEventListener('DOMContentLoaded', go);
    }
    window.dispatchEvent(new Event('advnotesready'));
  }
  if (document.documentElement) ready();
  else document.addEventListener('readystatechange', function once() {
    if (!document.documentElement) return;
    document.removeEventListener('readystatechange', once);
    ready();
  });
})();
