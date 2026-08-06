#!/usr/bin/env bash
# Mechanical checks for a release note. Prose rules only fire if someone remembers to apply them
# document-wide; these run. Exits non-zero on any finding.
#
#   .claude/skills/release-note/scripts/lint-release-note.sh site/src/content/release-notes/1.41.0.mdx

set -uo pipefail

f="${1:?usage: lint-release-note.sh <release-note.mdx>}"
[ -f "$f" ] || { echo "no such file: $f" >&2; exit 2; }

fail=0
# check <description> <grep-output>; prints each matched line indented, preserving line breaks.
check() {
  [ -n "$2" ] || return 0
  fail=1
  printf '\n✗ %s\n' "$1"
  printf '%s\n' "$2" | sed 's/^/    /'
}

# --- type links -------------------------------------------------------------
# A class link glued to a backticked method is not a link to that method.
hits=$(grep -n '\](type)`\.' "$f" || true)
check "class link + backtick method (write [Foo#bar(Baz)](type) as one unit)" "$hits"

# api-index keys carry no space after commas; a space yields a dead type://# link.
hits=$(grep -nE '\[[A-Za-z0-9_.]+#[^]]*, [^]]*\]\(type\)' "$f" || true)
check "space after comma in a method type link (silently dead)" "$hits"

# --- links ------------------------------------------------------------------
hits=$(grep -n '](https://armeria.dev/' "$f" || true)
check "absolute armeria.dev link (use a site-relative /docs/... path)" "$hits"

# --- leftovers from the generator ------------------------------------------
hits=$(grep -n '^- N/A' "$f" || true)
check "empty section left as '- N/A' (delete the section)" "$hits"

hits=$(grep -n 'Maybe ignore' "$f" || true)
check "'🗑 Maybe ignore' section must be triaged away" "$hits"

hits=$(grep -nE '^\s*\*\s' "$f" || true)
check "'*' bullet (use '-')" "$hits"

# --- dependencies -----------------------------------------------------------
deps=$(sed -n '/^## ⛓ Dependencies/,/^## /p' "$f")
hits=$(printf '%s' "$deps" | grep -n ' -> ' || true)
check "ASCII arrow in Dependencies (use →)" "$hits"

# Strip stable-release metadata only; -RC1/-M1/-SNAPSHOT identify distinct builds and must stay.
hits=$(printf '%s' "$deps" | grep -nE '[0-9](\.(Final|RELEASE|GA)|-GA|\.v[0-9]{6,})' || true)
check "release qualifier left in a version (.Final/.RELEASE/-GA/.vYYYYMMDD)" "$hits"

hits=$(printf '%s' "$deps" | grep -n '^- Build' || true)
check "build-only dependency section leaked in" "$hits"

# --- formatting -------------------------------------------------------------
hits=$(awk 'length>104 {printf "%d: (%d chars)\n", FNR, length}' "$f" || true)
check "line longer than 104 chars" "$hits"

if [ $fail -eq 0 ]; then
  echo "✓ $(basename "$f"): all mechanical checks passed"
else
  printf '\nSome checks failed. Note that a bad type link never fails the site build —\n'
  printf 'it renders as a blank type://# link, so this script is the only thing that catches it.\n'
fi
exit $fail
