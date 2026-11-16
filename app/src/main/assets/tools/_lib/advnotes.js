/*
 * Adv Notes web-tool API. Injected automatically into every web tool; including it yourself with
 * <script src="https://appassets.androidplatform.net/assets/tools/_lib/advnotes.js"></script>
 * is harmless. Full reference: docs/TOOLS.md
 */
(function () {
  if (window.AdvNotes || !window.AdvNotesNative) return;
  var N = window.AdvNotesNative;
  var pending = {};
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
    /** Name of the notebook the tool is open next to. */
    notebookTitle: N.notebookTitle(),

    /** Write text onto the current notebook page. */
    insertText: function (text) { N.insertText(String(text)); },
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
    document.documentElement.setAttribute('data-theme', window.AdvNotes.theme);
    window.dispatchEvent(new Event('advnotesready'));
  }
  if (document.documentElement) ready();
  else document.addEventListener('readystatechange', function once() {
    if (!document.documentElement) return;
    document.removeEventListener('readystatechange', once);
    ready();
  });
})();
