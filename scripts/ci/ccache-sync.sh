#!/usr/bin/env bash
# Keep the build server's compiler cache (/srv/ccache) between throwaway servers on a remote
# store host. A full build with a warm cache takes ~1-1.5 h instead of ~2.5 h.
# The source tree and out/ are not kept: a fresh sync is 20 min, and moving ~300 GB through a home
# line takes as long as building.
#
# Usage: scripts/ci/ccache-sync.sh save|restore <server-ip> <ssh-key> <known_hosts>
# Env:   CCACHE_STORE  user@host:/dir   (e.g. me@nas:/srv/ccache)
#
# Data goes directly between the store host and the server (4 parallel ssh streams, ~170 Mbit/s
# down / ~280 Mbit/s up on 2026-11-13). The server's throwaway key is copied to the store host for
# the transfer and removed afterwards. ccache already compresses its files (zstd), so plain tar.
# With rsync on the store host and a cache saved before, a save sends only the new entries.
set -euo pipefail
MODE="${1:?save|restore}"; IP="${2:?server ip}"; KEY="${3:?ssh key}"; KH="${4:?known_hosts}"
[[ "$MODE" == save || "$MODE" == restore ]] || { echo "usage: ccache-sync.sh save|restore <ip> <key> <known_hosts>" >&2; exit 2; }
STORE="${CCACHE_STORE:?set CCACHE_STORE=user@host:/dir}"
[[ "$STORE" == *:* ]] || { echo "invalid CCACHE_STORE=$STORE (expected user@host:/dir)" >&2; exit 2; }
SH="${STORE%%:*}"; SD="${STORE#*:}"; SD="${SD%/}"
[[ -n "$SH" && -n "$SD" ]] || { echo "invalid CCACHE_STORE=$STORE" >&2; exit 2; }
RD=".hibreak-ci/run-$$"

cleanup() { ssh -o BatchMode=yes "$SH" "rm -rf ~/$RD" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

ssh -o BatchMode=yes "$SH" "mkdir -p ~/.hibreak-ci && chmod 700 ~/.hibreak-ci && mkdir -p ~/$RD && chmod 700 ~/$RD"
scp -q -o BatchMode=yes "$KEY" "$SH:$RD/key"
scp -q -o BatchMode=yes "$KH" "$SH:$RD/known_hosts"

remote_cmd=$(printf 'bash %s -s -- %q %q %q %q' "${CCACHE_SYNC_DEBUG:+-x}" "$MODE" "$IP" "$RD" "$SD")
ssh -o BatchMode=yes "$SH" "$remote_cmd" <<'REMOTE'
set -euo pipefail
MODE=$1; IP=$2; RD=$HOME/$3; SD=$4
O=(-i "$RD/key" -o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$RD/known_hosts" -o GlobalKnownHostsFile=/dev/null -o ServerAliveInterval=30)
chmod 600 "$RD/key"
GROUPS_=("0 1 2 3" "4 5 6 7" "8 9 a b" "c d e f")
start=$(date +%s)
if [ "$MODE" = save ] && [ -d "$SD" ] && command -v rsync >/dev/null 2>&1 \
    && ssh -n "${O[@]}" root@"$IP" "command -v rsync >/dev/null"; then
  # A cache saved before: only the new entries go (ccache files never change once written), into
  # a hard-linked copy of the old cache that replaces it when complete. The full transfer below
  # moved all 10-50 GB again for every save.
  rm -rf "$SD.new"
  cp -al "$SD" "$SD.new"
  rc=0
  rsync -rlt --delete --exclude=/ccache.conf -e "ssh ${O[*]}" root@"$IP":/srv/ccache/ "$SD.new/" || rc=$?
  # 24: files vanished while a build was running (ccache's own cleanup): not an error.
  if [ "$rc" != 0 ] && [ "$rc" != 24 ]; then
    echo "ccache save FAILED (rsync $rc), keeping the previous cache"; rm -rf "$SD.new"; exit 1
  fi
  ssh -n "${O[@]}" root@"$IP" "if [ -f /srv/ccache/ccache.conf ]; then cat /srv/ccache/ccache.conf; fi" > "$SD.new/ccache.conf.tmp" \
    && mv "$SD.new/ccache.conf.tmp" "$SD.new/ccache.conf"
  [ -s "$SD.new/ccache.conf" ] || rm -f "$SD.new/ccache.conf"
  rm -rf "$SD.old"
  mv "$SD" "$SD.old" && mv "$SD.new" "$SD"
  rm -rf "$SD.old"
  echo "ccache saved (changes only): $(du -sh "$SD" | cut -f1) in $(( $(date +%s) - start )) s"
elif [ "$MODE" = save ]; then
  rm -rf "$SD.new"; mkdir -p "$SD.new"
  pids=()
  for g in "${GROUPS_[@]}"; do
    # GNU tar exits 1 for "file changed as we read it" (only while a build is running): not an error.
    ( ssh -n "${O[@]}" root@"$IP" "cd /srv/ccache && dirs=''; for d in $g; do [ -d \"\$d\" ] && dirs=\"\$dirs \$d\"; done; [ -n \"\$dirs\" ] && { tar --warning=no-file-changed -cf - \$dirs; rc=\$?; [ \$rc -le 1 ] && exit 0 || exit \$rc; } || tar -cf - -T /dev/null" \
        | tar -C "$SD.new" -xf - ) & pids+=($!)
  done
  ok=1; for p in "${pids[@]}"; do wait "$p" || ok=0; done
  ssh -n "${O[@]}" root@"$IP" "if [ -f /srv/ccache/ccache.conf ]; then cat /srv/ccache/ccache.conf; fi" > "$SD.new/ccache.conf" || ok=0
  [ -s "$SD.new/ccache.conf" ] || rm -f "$SD.new/ccache.conf"
  nshards=$(find "$SD.new" -maxdepth 1 -mindepth 1 -type d | wc -l)
  [ "$ok" = 1 ] && [ "$nshards" -gt 0 ] || { echo "ccache save FAILED, keeping the previous cache"; rm -rf "$SD.new"; exit 1; }
  rm -rf "$SD.old"
  if [ -d "$SD" ]; then mv "$SD" "$SD.old"; fi
  mv "$SD.new" "$SD"
  rm -rf "$SD.old"
  echo "ccache saved: $(du -sh "$SD" | cut -f1) in $(( $(date +%s) - start )) s"
else
  if [ ! -d "$SD" ] || [ "$(find "$SD" -maxdepth 1 -mindepth 1 -type d | wc -l)" -eq 0 ]; then
    echo "no saved ccache in $SD, starting cold"
    exit 0
  fi
  ssh -n "${O[@]}" root@"$IP" "mkdir -p /srv/ccache"
  pids=()
  for g in "${GROUPS_[@]}"; do
    ( cd "$SD" && dirs="" && for d in $g; do if [ -d "$d" ]; then dirs="$dirs $d"; fi; done && if [ -n "$dirs" ]; then tar -cf - $dirs | ssh "${O[@]}" root@"$IP" "tar -C /srv/ccache -xf -"; fi ) & pids+=($!)
  done
  ok=1; for p in "${pids[@]}"; do wait "$p" || ok=0; done
  [ ! -f "$SD/ccache.conf" ] || scp -q "${O[@]}" "$SD/ccache.conf" root@"$IP":/srv/ccache/ccache.conf || true
  [ "$ok" = 1 ] || { echo "ccache restore incomplete (the build still works, just slower)"; exit 1; }
  echo "ccache restored: $(du -sh "$SD" | cut -f1) in $(( $(date +%s) - start )) s"
fi
REMOTE
