#!/bin/bash
# Runs ON the build server. Gentle shallow `repo sync` with retries, tuned for AOSP's rate limits:
#  - -j4: higher parallelism gets HTTP 429 / RESOURCE_EXHAUSTED from android.googlesource.com
#  - a watchdog kills any non-shallow (full-history) fetch; after a 429 repo falls back to one,
#    which for prebuilts/rust alone is ~50 GB
#  - projects that still fail are shallow-fetched one by one (buildserver-fix-failed.sh)
cd /srv/lineage || exit 1
LOG=/srv/sync3.log
: > "$LOG"
WD=""
trap 'kill "${WD:-}" 2>/dev/null || true' EXIT INT TERM
( while sleep 15; do
    # git by name or by path (/usr/bin/git): the anchored pattern missed the second.
    for p in $(pgrep -f "(^|/)git fetch --no-auto-gc"); do
      [ -r "/proc/$p/cmdline" ] || continue
      if ! grep -qa -- "--depth" "/proc/$p/cmdline" 2>/dev/null; then
        [ -r "/proc/$p/cmdline" ] || continue
        # One line per kill (cmdline has no newline of its own).
        printf '%s\n' "$(tr "\0" " " < "/proc/$p/cmdline" 2>/dev/null | cut -c1-200)" >> /srv/sync3-killed.log
        kill "$p" 2>/dev/null || true
      fi
    done
  done ) &
WD=$!
# Stale locks from killed fetches block later attempts
clear_locks() { find .repo/projects .repo/project-objects -name 'shallow.lock' -delete 2>/dev/null || true; }

for attempt in 1 2 3 4 5 6; do
  echo "ATTEMPT $attempt $(date +%T)" >> "$LOG"
  clear_locks
  repo sync -c -j4 --force-sync --no-tags --no-clone-bundle --optimized-fetch > /srv/sync-attempt.log 2>&1
  rc=$?; cat /srv/sync-attempt.log >> "$LOG"
  [ "$rc" = 0 ] && { echo SYNC_EXIT=0 >> "$LOG"; break; }
  # "Failing repos (network):" / "Failing repos (checkout):" blocks list one path per line
  failed=$(awk '/^Failing repos/{f=1;next} f&&(/^Try re-running/||/^error/||/^$/){f=0} f{print $1}' /srv/sync-attempt.log | sort -u)
  if [ -n "$failed" ] && [ "$attempt" -ge 2 ]; then
    echo "FIXING $(echo "$failed" | wc -w): $failed" >> "$LOG"
    clear_locks
    /srv/buildserver-fix-failed.sh $failed >> "$LOG" 2>&1
    # Everything fetched now? A local-only sync checks out the whole tree without the network.
    repo sync -l -c -j8 >> "$LOG" 2>&1 && { echo SYNC_EXIT=0 >> "$LOG"; break; }
  fi
  [ "$attempt" -lt 6 ] && sleep 180
done
grep -q SYNC_EXIT=0 "$LOG" || echo SYNC_EXIT=1 >> "$LOG"
