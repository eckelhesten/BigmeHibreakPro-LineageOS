#!/usr/bin/env bash
# Mac-side: push device/vendor trees to the build server and restart the build in tmux.
# Usage: scripts/rebuild.sh [server-ip] [make-targets...]
set -euo pipefail
cd "$(dirname "$0")/.."
IP="${1:-}"
if [ -n "$IP" ]; then shift; else IP=$(hcloud server ip hibreak-build); fi
H="root@$IP"
T="${*:-bacon}"
[[ "$T" =~ ^[A-Za-z0-9._\ -]+$ ]] || { echo "invalid make targets: $T" >&2; exit 2; }
if [ -n "${SSH_OPTS:-}" ]; then
  read -r -a SSH_ARR <<<"$SSH_OPTS"
elif [ -n "${CI_KEY_DIR:-}" ]; then
  SSH_ARR=(-i "$CI_KEY_DIR/id_ed25519" -o "UserKnownHostsFile=$CI_KEY_DIR/known_hosts" -o BatchMode=yes -o StrictHostKeyChecking=yes -o GlobalKnownHostsFile=/dev/null)
else
  SSH_ARR=(-o BatchMode=yes)
fi
s() { ssh "${SSH_ARR[@]}" "$H" "$@"; }
if [ -z "${BUILD_TYPE:-}" ]; then
  BUILD_TYPE=$(s "sed -n 's/^ro.build.type=//p' /srv/lineage/out/target/product/hibreakpro/system/build.prop 2>/dev/null | head -1" || true)
fi
[[ "${BUILD_TYPE:-}" =~ ^(user|userdebug|eng)$ ]] || {
  echo "cannot determine server BUILD_TYPE; pass BUILD_TYPE=user|userdebug|eng" >&2
  exit 1
}
# tar keeps the device/ and vendor/ prefixes intact (plain rsync of two dirs flattened them)
COPYFILE_DISABLE=1 tar --no-xattrs -cf - device/bigme vendor/bigme | zstd -3 -T0 -q | \
  s 'set -euo pipefail; rm -rf /srv/hibreak-src.new; mkdir -p /srv/hibreak-src.new && zstd -dq | tar -C /srv/hibreak-src.new -xf - && rm -rf /srv/hibreak-src/device /srv/hibreak-src/vendor && mkdir -p /srv/hibreak-src && mv /srv/hibreak-src.new/device /srv/hibreak-src/device && mv /srv/hibreak-src.new/vendor /srv/hibreak-src/vendor && rm -rf /srv/hibreak-src.new'
s 'grep -c BOARD_PREBUILT_DTBIMAGE_DIR /srv/hibreak-src/device/bigme/hibreakpro/BoardConfig.mk >/dev/null' && echo "trees uploaded"
scp -q "${SSH_ARR[@]}" scripts/buildserver-build.sh "$H:/srv/"
remote_cmd=$(printf 'BUILD_TYPE=%q /srv/buildserver-build.sh %q > /srv/build.log 2>&1' "$BUILD_TYPE" "$T")
s "tmux kill-session -t build 2>/dev/null || true; mv -f /srv/build.log /srv/build.prev.log 2>/dev/null || true; tmux new-session -d -s build $(printf '%q' "$remote_cmd"); echo build restarted ($BUILD_TYPE: $T)"
