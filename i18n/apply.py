"""Wraps every Arabic string literal in tr("<arabic>", "<english>")."""
import glob
import json
import os
import sys

from ktstrings import AR, literals

BS = chr(92)
here = os.path.dirname(os.path.abspath(__file__))
src_root = sys.argv[1]
ar = json.load(open(os.path.join(here, 'ar.json'), encoding='utf-8'))
en = json.load(open(os.path.join(here, 'en.json'), encoding='utf-8'))
assert len(ar) == len(en), (len(ar), len(en))


def to_kotlin(text):
    """JSON-decoded English -> Kotlin literal body: escape quotes/newlines outside ${...}."""
    out, depth, i = [], 0, 0
    while i < len(text):
        c = text[i]
        if depth == 0 and text.startswith('${', i):
            depth = 1
            out.append('${')
            i += 2
            continue
        if depth > 0:
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            out.append(c)
        elif c == '"':
            out.append(BS + '"')
        elif c == '\n':
            out.append(BS + 'n')
        else:
            out.append(c)
        i += 1
    return ''.join(out)


table = {a: to_kotlin(e) for a, e in zip(ar, en)}
changed = 0
for f in glob.glob(os.path.join(src_root, '**', '*.kt'), recursive=True):
    if f.endswith('Lang.kt'):
        continue
    src = open(f, encoding='utf-8').read()
    edits = []
    for s, e, t in literals(src):
        if not AR.search(t):
            continue
        if src[max(0, s - 3):s] == 'tr(':
            continue
        if t not in table:
            print('MISSING', f, t)
            continue
        edits.append((s, e, 'tr("%s", "%s")' % (t, table[t])))
    for s, e, rep in sorted(edits, reverse=True):
        src = src[:s] + rep + src[e:]
    if edits:
        if 'package com.ahmed.shield.ui' in src and 'import com.ahmed.shield.tr' not in src:
            src = src.replace('\nimport ', '\nimport com.ahmed.shield.tr\nimport ', 1)
        open(f, 'w', encoding='utf-8').write(src)
        changed += len(edits)
        print(os.path.basename(f), len(edits))
print('total', changed)
