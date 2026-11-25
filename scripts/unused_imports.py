"""Lists unused imports in tracked Kotlin files (path:line:import). Run from the repo root: python scripts/unused_imports.py"""
import re, subprocess, sys

files = subprocess.check_output(['git', 'ls-files', '*.kt'], text=True).split('\n')
SKIP = {'*', 'getValue', 'setValue', 'provideDelegate'}
hits = 0
for f in files:
    if not f:
        continue
    try:
        s = open(f, encoding='utf-8').read()
    except Exception:
        continue
    lines = s.split('\n')
    body = '\n'.join(l for l in lines if not l.lstrip().startswith('import '))
    # strip comments so imports referenced only in comments count as unused
    for i, l in enumerate(lines):
        m = re.match(r'import ([\w.`]+?)(?: as (\w+))?\s*$', l.rstrip('\r'))
        if not m:
            continue
        name = m.group(2) or m.group(1).split('.')[-1].strip('`')
        if name in SKIP:
            continue
        if not re.search(r'\b' + re.escape(name) + r'\b', body):
            print(f'{f}:{i + 1}:{l.strip()}')
            hits += 1
print(hits, file=sys.stderr)
