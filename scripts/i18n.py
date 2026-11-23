#!/usr/bin/env python3
"""Translation helper for Adv Notes.

  scripts/i18n.py            update app/src/main/assets/i18n/en.json (every translatable string in the app)
                             and add any new keys to each translation (with an empty value)
  scripts/i18n.py --check    report missing / unused keys per translation; fails if en.json is out of date

Keys are the English strings passed to tr("...") in the Kotlin sources, plus the labels of enums that
declare `(private val en: String`. See docs/TRANSLATING.md.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app/src/main/java')
OUT = os.path.join(ROOT, 'app/src/main/assets/i18n')

def kotlin_string(src, i):
    """Reads a plain "..." literal starting at src[i]; returns (value, end) or None for templates."""
    assert src[i] == '"'
    j, buf = i + 1, ''
    while src[j] != '"':
        if src[j] == '\\':
            buf += {'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$', "'": "'"}.get(src[j + 1], src[j + 1]); j += 2
        elif src[j] == '$' and (src[j + 1] == '{' or src[j + 1].isalpha()):
            return None
        else:
            buf += src[j]; j += 1
    return buf, j + 1

def collect():
    keys = {}
    for d, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith('.kt'): continue
            p = os.path.join(d, f); src = open(p).read(); rel = os.path.relpath(p, ROOT)
            for m in re.finditer(r'\btr\(\s*"', src):
                r = kotlin_string(src, m.end() - 1)
                if r: keys.setdefault(r[0], rel)
            for m in re.finditer(r'\btrn\([^,]+,\s*"', src):          # trn(n, "one", "other")
                r = kotlin_string(src, m.end() - 1)
                if not r: continue
                keys.setdefault(r[0], rel)
                m2 = re.match(r'\s*,\s*"', src[r[1]:])
                if m2:
                    r2 = kotlin_string(src, r[1] + m2.end() - 1)
                    if r2: keys.setdefault(r2[0], rel)
            for m in re.finditer(r'enum class \w+\(private val en: String[^)]*\)[^{]*\{', src):
                body = src[m.end():]
                end = body.find(';')
                for e in re.finditer(r'\b[A-Z][A-Z0-9_]*\(\s*"', body[:end]):
                    r = kotlin_string(body, e.end() - 1)
                    if r: keys.setdefault(r[0], rel)
    return dict(sorted(keys.items(), key=lambda kv: kv[0].lower()))

def dump(path, data):
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2); f.write('\n')

def main():
    keys = collect()
    os.makedirs(OUT, exist_ok=True)
    template = {'_meta': {'name': 'English (template — copy this file to start a translation)', 'machineTranslated': False}}
    template.update({k: '' for k in keys})
    en_path = os.path.join(OUT, 'en.json')
    if '--check' in sys.argv:
        ok = os.path.exists(en_path) and json.load(open(en_path, encoding='utf-8')) == template
        if not ok: print('en.json is out of date: run scripts/i18n.py')
        for f in sorted(os.listdir(OUT)):
            if not f.endswith('.json') or f == 'en.json': continue
            t = json.load(open(os.path.join(OUT, f), encoding='utf-8'))
            missing = [k for k in keys if not t.get(k)]; unused = [k for k in t if not k.startswith('_') and k not in keys]
            print(f'{f}: {len(keys) - len(missing)}/{len(keys)} translated, {len(missing)} missing, {len(unused)} unused')
            for k in missing[:20]: print('   missing:', json.dumps(k, ensure_ascii=False))
            for k in unused[:20]: print('   unused: ', json.dumps(k, ensure_ascii=False))
            bad = [k for k in keys if t.get(k) and sorted(re.findall(r'\{\d+\}', k)) != sorted(re.findall(r'\{\d+\}', t[k]))]
            for k in bad: print('   placeholders differ:', json.dumps(k, ensure_ascii=False)); ok = False
        sys.exit(0 if ok else 1)
    dump(en_path, template)
    for f in sorted(os.listdir(OUT)):
        if not f.endswith('.json') or f == 'en.json': continue
        p = os.path.join(OUT, f); t = json.load(open(p, encoding='utf-8'))
        merged = {'_meta': t.get('_meta', {})}
        merged.update({k: t.get(k, '') for k in keys})
        merged.update({k: v for k, v in t.items() if k not in merged})   # keep unused ones until a human removes them
        dump(p, merged)
    print(f'{len(keys)} strings → {os.path.relpath(en_path, ROOT)}')

main()
