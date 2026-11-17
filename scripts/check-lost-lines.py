#!/usr/bin/env python3
"""Did a merge drop anything main had?

Usage: python scripts/check-lost-lines.py <merge-base> <old-main> <merged-ref>
  e.g. python scripts/check-lost-lines.py 7772a4ce main update/fvk-1117

Takes every line <old-main> added since <merge-base> and looks for it anywhere in
the source files of <merged-ref>. Lines still present (even in another file, after
a refactor moved them) are fine; anything printed is missing and must be either an
intentional rewrite or something the merge silently lost.
"""
import collections, subprocess, sys

base, old, merged = sys.argv[1:4]
EXT = ('.kt', '.kts', '.go', '.xml', '.ts', '.tsx')

def git(*a):
    return subprocess.run(['git', *a], capture_output=True, text=True,
                          encoding='utf-8', errors='replace').stdout

present = set()
for f in git('ls-tree', '-r', '--name-only', merged).split('\n'):
    if f.endswith(EXT):
        present.update(l.strip() for l in git('show', f'{merged}:{f}').splitlines())

missing = collections.defaultdict(list)
for f in git('diff', '--name-only', base, old).split('\n'):
    if not f:
        continue
    for l in git('diff', '-U0', base, old, '--', f).splitlines():
        if l.startswith('+') and not l.startswith('+++'):
            t = l[1:].strip()
            if len(t) < 6 or t.startswith(('//', '*', '/*', 'import ')):
                continue
            if t not in present:
                missing[f].append(t)

for f, lines in missing.items():
    print(f'== {f} ({len(lines)})')
    for t in lines[:25]:
        print('   ', t[:150])
print('OK: nothing lost' if not missing else f'{sum(map(len, missing.values()))} lines to review')
