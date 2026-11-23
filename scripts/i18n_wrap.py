#!/usr/bin/env python3
"""One-off helper: wraps user-facing string literals in Kotlin UI code with tr(...).

Only literals in display positions are touched (Text(...), icon descriptions, toasts, labels,
titles, error messages, option lists), so comparison keys and prompts stay as they are.
Usage: scripts/i18n_wrap.py FILE...   (edits in place, prints a count per file)
"""
import re, sys

DISPLAY_CALLS = {  # call name -> positional arg indexes that are display text
    'Text': {0}, 'Icon': {1}, 'makeText': {1}, 'AiException': {0}, 'ToolButton': {1}, 'T': {1},
    'DragGrip': {0}, 'NameDialog': {0, 1}, 'SettingRow': {0, 1}, 'SectionTitle': {0}, 'Section': {0},
    'SwitchRow': {0, 1}, 'SettingsGroup': {0}, 'FieldLabel': {0, 1}, 'CatalogRow': {0, 1}, 'ChoiceRow': {1, 2},
    'RadioRow': {0}, 'SecretField': {0}, 'AnswerText': {0}, 'InfoRow': {0, 1}, 'ToggleRow': {0, 1}, 'Hint': {0}, 'TextButtonRow': {0},
}
DISPLAY_NAMED = {'help', 'text', 'contentDescription', 'label', 'title', 'placeholder', 'description', 'message',
                 'subtitle', 'hint', 'summary', 'confirmText', 'dismissText', 'supportingText'}
ASSIGN = re.compile(r'\b(error|message|msg|status|hint|title|subtitle|aerr|info)\s*=\s*$')

def tokenize(src):
    """Yields ('str', start, end, parts) for plain string literals and ('ch', i, c) for code chars.
    parts: list of ('lit', text) / ('expr', code) pieces of the template."""
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3
            while i < n and src[i] == '"': i += 1
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if c == '"':
            start = i; i += 1; parts = []; buf = ''
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    buf += src[i:i + 2]; i += 2
                elif src.startswith('${', i):
                    if buf: parts.append(('lit', buf)); buf = ''
                    depth, j = 1, i + 2
                    while j < n and depth:
                        if src[j] == '{': depth += 1
                        elif src[j] == '}': depth -= 1
                        j += 1
                    parts.append(('expr', src[i + 2:j - 1])); i = j
                elif src[i] == '$' and i + 1 < n and (src[i + 1].isalpha() or src[i + 1] == '_'):
                    if buf: parts.append(('lit', buf)); buf = ''
                    m = re.match(r'[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*', src[i + 1:])
                    # $a.b is only "a" in Kotlin templates
                    name = m.group(0).split('.')[0]
                    parts.append(('expr', name)); i += 1 + len(name)
                else:
                    buf += src[i]; i += 1
            if buf: parts.append(('lit', buf))
            yield ('str', start, i + 1, parts); i += 1; continue
        yield ('ch', i, c); i += 1

def looks_like_text(parts):
    text = ''.join(p[1] for p in parts if p[0] == 'lit')
    if not re.search(r'[A-Za-z]{2}', text): return False
    if any(p[0] == 'expr' and '"' in p[1] for p in parts): return False
    if re.fullmatch(r'[a-z0-9_.\-/:*]+', text): return False             # keys, paths, mime types
    if re.fullmatch(r'[A-Z0-9_]+', text): return False                   # ENUM_KEYS
    if re.search(r'https?://|\.kt\b|\.json\b|%[0-9.]*[sdf]', text): return False
    if re.fullmatch(r'[a-z]+[A-Z][A-Za-z]*', text): return False         # camelCase keys
    return True

def process(path):
    src = open(path).read()
    stack = []   # [name, argIndex, namedArg]
    edits = []
    toks = list(tokenize(src))
    for k, t in enumerate(toks):
        if t[0] == 'ch':
            _, i, c = t
            if c in '([':
                m = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*$', src[max(0, i - 60):i]) if c == '(' else None
                stack.append([m.group(1) if m else '', 0, None])
            elif c in ')]' and stack:
                stack.pop()
            elif c == ',' and stack:
                stack[-1][1] += 1; stack[-1][2] = None
            elif c == '=' and stack and src[i + 1:i + 2] != '=' and src[i - 1:i] not in '=!<>':
                m = re.search(r'([A-Za-z_][A-Za-z0-9_]*)\s*$', src[max(0, i - 40):i])
                if m: stack[-1][2] = m.group(1)
            elif c == '{':
                stack.append(['{', 0, None])
            elif c == '}' and stack:
                stack.pop()
            continue
        _, s, e, parts = t
        if not looks_like_text(parts): continue
        before = src[max(0, s - 80):s]; after = src[e:e + 40]
        line_start = src.rfind('\n', 0, s) + 1
        line = src[line_start:s]
        if re.search(r'(==|!=|in)\s*$', before) or re.match(r'\s*(->|==|!=|\.format|\.equals|,\s*ignoreCase)', after): continue
        if re.search(r'\bconst\s+val\b|@\w+\(|Log\.|ToolLog|LlamaLog|onNodeWith|hasContentDescription|getString\(|putString\(|header\(|\bput\(', line): continue
        if before.endswith('tr('): continue
        ok = False
        # nearest enclosing call (skip lambda braces: Text inside label = { Text("..") } is still Text)
        frame = stack[-1] if stack else None
        if frame and frame[0] != '{':
            name, idx, named = frame
            if named in DISPLAY_NAMED: ok = True
            elif named is None and idx in DISPLAY_CALLS.get(name, ()): ok = True
            elif name in ('listOf', 'mapOf', 'Pair') and re.search(r'"[A-Z_][A-Za-z_]*"\s+to\s+$', before): ok = True
            elif name in ('listOf', 'mapOf') and re.match(r'\s+to\s+"[a-z]+"', after) and ' ' in ''.join(p[1] for p in parts if p[0] == 'lit'): ok = True
            elif re.search(r'[A-Za-z0-9_)\]]\s+to\s+$', before) and re.match(r'[A-Z]', ''.join(p[1] for p in parts if p[0] == 'lit')): ok = True
        if not ok and ASSIGN.search(before): ok = True
        if not ok and re.search(r'override\s+val\s+(name|description)\s*=\s*$', before):
            # evaluate per read so a language change shows up
            ok = True
            m = re.search(r'=\s*$', before)
            edits.append((s - (len(before) - m.start()), s, ' get() = '))
        if not ok: continue
        # build tr(...) call
        key, args = '', []
        for p in parts:
            if p[0] == 'lit': key += p[1]
            else: key += '{%d}' % len(args); args.append(p[1].strip())
        lit = '"' + key + '"'
        edits.append((s, e, 'tr(' + lit + ''.join(', ' + a for a in args) + ')'))
    if not edits: return 0
    edits.sort()
    out, last = [], 0
    for s, e, rep in edits:
        out.append(src[last:s]); out.append(rep); last = e
    out.append(src[last:])
    res = ''.join(out)
    if 'import com.advnotes.app.i18n.tr' not in res:
        res = re.sub(r'(\npackage [^\n]+\n)', r'\1\nimport com.advnotes.app.i18n.tr\n', res, count=1)
    open(path, 'w').write(res)
    return sum(1 for x in edits if x[2].startswith('tr('))

if __name__ == '__main__':
    total = 0
    for f in sys.argv[1:]:
        n = process(f); total += n; print(f'{n:4} {f}')
    print(total, 'strings wrapped')
