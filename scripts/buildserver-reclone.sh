#!/bin/bash
# Runs ON the build server: wipe a project's git data and re-clone it shallow via repo,
# killing any non-shallow fetch repo may fall back to. Usage: buildserver-reclone.sh <path> <name>
set -euo pipefail
cd /srv/lineage
P="${1:?usage: buildserver-reclone.sh <path> <name>}"
N="${2:?usage: buildserver-reclone.sh <path> <name>}"
[[ "$P" != /* && "$P" != *..* && "$N" != /* && "$N" != *..* ]] || { echo "invalid path/name" >&2; exit 2; }
LOG=/srv/reclone.log
: > "$LOG"
WD=""
trap 'kill "${WD:-}" 2>/dev/null || true' EXIT INT TERM
rm -rf "$P" ".repo/projects/$P.git" ".repo/project-objects/$N.git"
( while sleep 10; do
    for p in $(pgrep -f "^git fetch"); do
      [ -r "/proc/$p/cmdline" ] || continue
      if ! grep -qa -- "--depth" "/proc/$p/cmdline" 2>/dev/null; then
        echo "killed non-shallow fetch" >> "$LOG"
        kill "$p" 2>/dev/null || true
      fi
    done
  done ) &
WD=$!
ok=0
for t in 1 2 3; do
  if repo sync -c -j1 --no-tags --no-clone-bundle "$P" >> "$LOG" 2>&1; then
    echo RECLONE_OK >> "$LOG"
    ok=1
    break
  fi
  [ "$t" -lt 3 ] && sleep 90
done
if [ "$ok" -ne 1 ]; then
  echo RECLONE_FAILED >> "$LOG"
  exit 1
fi
