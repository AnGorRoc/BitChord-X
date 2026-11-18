# Merging a collaborator's long-lived branch

Why this exists: a branch cut from an old `main` that also moves code between
files (splitting `SettingsSheet`, extracting from `MainActivity`) merges
"cleanly" while quietly dropping `main`'s later edits to the old locations.

## Merging

1. `git fetch --all`, then `git checkout -b update/<name>-<date> main`.
2. `git merge --no-ff <remote>/<branch>`. Leave `main` untouched until the end.
3. On a conflict in a file one side refactored: take the refactored side
   (`git checkout --theirs`), then re-apply `main`'s own diff for that file
   (`git diff <merge-base> main -- <file>`) onto the new structure by hand.
4. `python scripts/check-lost-lines.py <merge-base> main update/<...>`. Every
   printed line must be an intentional rewrite.
5. `./gradlew assembleDevDebug testDevDebugUnitTest` (what `check.yml` runs).
6. Test on a device what `main`'s commits touched, then `git merge --ff-only`.

`git config rerere.enabled true` remembers conflict resolutions, so redoing a
merge does not repeat step 3.

## Working so it hurts less

- Short branches, or merge `main` in every day or two. The pain scales with how
  long a branch lives.
- Refactors that move code go in their own PR and merge first; nobody touches
  the moved files in the meantime.
- Tell each other before touching `MainActivity.kt` or `NowPlayingScreen.kt`.
- Open PRs, so `check.yml` runs before anything reaches `main`.
