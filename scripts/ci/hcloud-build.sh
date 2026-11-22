#!/usr/bin/env bash
# Ephemeral Hetzner Cloud build: create a server, sync + build there, fetch the artifacts,
# delete the server. The server only exists (and only bills) while this script runs.
#
# Works from the Mac (active `hcloud` context) and from GitHub Actions (HCLOUD_TOKEN env).
# Needs device/bigme/hibreakpro and vendor/bigme/hibreakpro checked out next to this script.
#
# Env (all optional):
#   LINEAGE_BRANCH  lineage-23.2          BUILD_TYPE   userdebug
#   SERVER_TYPE     cpx62 (16 vCPU/32 GB) LOCATION     hel1 (cheapest for cpx62)
#   MAX_HOURS       5     hard stop; the server is deleted when it is reached
#   SSH_KEY_NAME    existing hcloud SSH key + matching local key; unset = throwaway key pair
#   KEEP_SERVER=1   keep the server afterwards (debugging; YOU must delete it)
#   KEY_SAVE_DIR    with KEEP_SERVER=1: copy the throwaway key and known_hosts there, so
#                   later incremental builds (CI_KEY_DIR) can keep using the server
#   BUILD_TARGET    make target(s) for the pipeline (default bacon; e.g. "systemimage systemextimage")
#   HIBREAK_FLAVORS flavours to build, one after the other on the same server (default microg;
#                   "vanilla microg gapps" for a release; device.mk). Targets other than bacon
#                   build the first one only.
#   OUT_DIR         out    artifacts land in $OUT_DIR/<build name>/
#   RELEASE_KEYS_DIR  our release keys (docs/signing.md, e.g. ~/.hibreak-release-keys): installed
#                   on the server as soon as the source is synced (scripts/ci/install-keys.sh); the
#                   pipeline waits for them before building. Unset: install them by hand during the
#                   sync, or the build stops with BUILD_RESULT=no-release-keys.
#   CI_RUN_ID       extra server label run=<id>, so a CI job cleans up only its own server
#   CCACHE_STORE    user@host:/dir  keep the compiler cache there between servers
#                   (scripts/ci/ccache-sync.sh; e.g. me@nas:/srv/ccache).
#                   Restored during the sync, saved after the build: ~1-1.5 h builds instead of ~2.5 h.
set -euo pipefail
cd "$(dirname "$0")/../.."

LINEAGE_BRANCH="${LINEAGE_BRANCH:-lineage-23.2}"
BUILD_TYPE="${BUILD_TYPE:-userdebug}"
BUILD_TARGET="${BUILD_TARGET:-bacon}"
HIBREAK_FLAVORS="${HIBREAK_FLAVORS:-microg}"
SERVER_TYPE="${SERVER_TYPE:-cpx62}"
LOCATION="${LOCATION:-hel1}"
MAX_HOURS="${MAX_HOURS:-5}"
OUT_DIR="${OUT_DIR:-out}"
NAME="hibreak-ci-$(date -u +%Y%m%d-%H%M%S)"
TMP="$(mktemp -d)"
START=$(date +%s)
OWN_KEY=0
KEY=""
IDENT=""
IP=""
CCACHE_PID=""

[[ "$LINEAGE_BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || { echo "invalid LINEAGE_BRANCH: $LINEAGE_BRANCH" >&2; exit 2; }
[[ "$BUILD_TYPE" =~ ^(user|userdebug|eng)$ ]] || { echo "invalid BUILD_TYPE: $BUILD_TYPE" >&2; exit 2; }
[[ "$BUILD_TARGET" =~ ^[A-Za-z0-9._\ -]+$ ]] || { echo "invalid BUILD_TARGET: $BUILD_TARGET" >&2; exit 2; }
for f in $HIBREAK_FLAVORS; do
  [[ "$f" =~ ^(vanilla|microg|gapps)$ ]] || { echo "invalid flavour in HIBREAK_FLAVORS: $f" >&2; exit 2; }
done
[[ -n "${HIBREAK_FLAVORS// /}" ]] || { echo "HIBREAK_FLAVORS is empty" >&2; exit 2; }

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

save_key_dir() {
  if [[ -n "${KEY_SAVE_DIR:-}" && -n "$IDENT" && -f "$IDENT" && -f "$TMP/known_hosts" ]]; then
    mkdir -p "$KEY_SAVE_DIR" \
      && install -m 600 "$IDENT" "$KEY_SAVE_DIR/" \
      && cp "$TMP/known_hosts" "$KEY_SAVE_DIR/" \
      && { [[ -f "$IDENT.pub" ]] && cp "$IDENT.pub" "$KEY_SAVE_DIR/" || rm -f "$KEY_SAVE_DIR/$(basename "$IDENT").pub"; } \
      && echo "${IP:-}" > "$KEY_SAVE_DIR/host" \
      && log "key saved to $KEY_SAVE_DIR (host ${IP:-unknown})"
  fi
}

cleanup() {
  local rc=$?
  set +e
  if [[ -n "${CCACHE_PID:-}" ]]; then
    kill "$CCACHE_PID" 2>/dev/null || true
    wait "$CCACHE_PID" 2>/dev/null || true
  fi
  local hours; hours=$(awk "BEGIN{printf \"%.2f\", ($(date +%s)-$START)/3600}")
  if [[ "${KEEP_SERVER:-0}" == 1 ]]; then
    if [[ -n "$NAME" ]]; then
      log "KEEP_SERVER=1: server $NAME is still running and billing. Delete it: hcloud server delete $NAME"
    fi
    save_key_dir || true
  elif [[ -n "$NAME" ]]; then
    local del_ok=0
    for _ in 1 2 3; do
      if hcloud server delete "$NAME" >/dev/null 2>&1; then
        del_ok=1
        log "server $NAME deleted"
        break
      fi
      sleep 2
    done
    if [[ $del_ok -eq 0 ]]; then
      hcloud server describe "$NAME" >/dev/null 2>&1 && log "WARNING: failed to delete server $NAME" || log "no server to delete"
    fi
  fi
  [[ "$OWN_KEY" == 1 && -n "$KEY" ]] && { hcloud ssh-key delete "$KEY" >/dev/null 2>&1 || true; }
  rm -rf "$TMP"
  log "server time ${hours} h (${SERVER_TYPE} ${LOCATION}), exit $rc"
  exit $rc
}
trap cleanup EXIT
trap 'exit 130' INT TERM

[[ -f device/bigme/hibreakpro/BoardConfig.mk ]] || { echo "device tree missing" >&2; exit 2; }
[[ -f vendor/bigme/hibreakpro/prebuilt/vendor.img ]] || { echo "vendor tree missing (private repo / LFS)" >&2; exit 2; }
if [[ -n "${RELEASE_KEYS_DIR:-}" ]]; then
  [[ -f "$RELEASE_KEYS_DIR/releasekey.pk8" ]] || { echo "RELEASE_KEYS_DIR=$RELEASE_KEYS_DIR has no releasekey.pk8" >&2; exit 2; }
  [[ -f "$RELEASE_KEYS_DIR/avb2048.pem" ]] || { echo "RELEASE_KEYS_DIR=$RELEASE_KEYS_DIR has no avb2048.pem" >&2; exit 2; }
fi

if [[ -n "${SSH_KEY_NAME:-}" ]]; then
  KEY="$SSH_KEY_NAME"; IDENT="${SSH_IDENTITY:-$HOME/.ssh/id_ed25519}"; OWN_KEY=0
else
  KEY="$NAME"; IDENT="$TMP/id_ed25519"; OWN_KEY=1
  ssh-keygen -q -t ed25519 -N '' -C "$NAME" -f "$IDENT"
  key_labels=(--label hibreak=ci --label "created=$(date -u +%s)")
  [[ -n "${CI_RUN_ID:-}" ]] && key_labels+=(--label "run=$CI_RUN_ID")
  hcloud ssh-key create --name "$KEY" --public-key-from-file "$IDENT.pub" "${key_labels[@]}" >/dev/null
fi
SSH_OPTS_ARR=(-i "$IDENT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o "UserKnownHostsFile=$TMP/known_hosts" -o ServerAliveInterval=30 -o ConnectTimeout=15)
export SSH_OPTS="-i $IDENT -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$TMP/known_hosts -o ServerAliveInterval=30 -o ConnectTimeout=15"

log "creating $NAME ($SERVER_TYPE in $LOCATION)"
LABEL="hibreak=ci"
[[ "${KEEP_SERVER:-0}" == "1" ]] && LABEL="hibreak=dev"
labels=(--label "$LABEL" --label "created=$(date -u +%s)")
[[ -n "${CI_RUN_ID:-}" ]] && labels+=(--label "run=$CI_RUN_ID")
hcloud server create --name "$NAME" --type "$SERVER_TYPE" --image ubuntu-24.04 --location "$LOCATION" \
  --ssh-key "$KEY" --user-data-from-file scripts/buildserver-cloud-init.yaml "${labels[@]}" >/dev/null
IP=$(hcloud server ip "$NAME")
H="root@$IP"
s() { ssh "${SSH_OPTS_ARR[@]}" "$H" "$@"; }

log "waiting for $IP to finish cloud-init"
for _ in $(seq 1 60); do s true 2>/dev/null && break; sleep 10; done
s 'cloud-init status --wait >/dev/null; test -f /var/lib/cloud/instance/hibreak-ready' \
  || { echo "cloud-init failed" >&2; s 'tail -30 /var/log/cloud-init-output.log' || true; exit 1; }
[[ "${KEEP_SERVER:-0}" == 1 ]] && { save_key_dir || true; }

log "uploading device/vendor trees and scripts"
dirs=(device/bigme vendor/bigme scripts manifests); [[ -d patches ]] && dirs+=(patches)
COPYFILE_DISABLE=1 tar --no-xattrs -cf - "${dirs[@]}" | zstd -3 -T0 -q | \
  s 'set -euo pipefail; mkdir -p /srv/hibreak-src && zstd -dq | tar -C /srv/hibreak-src -xf - && cp /srv/hibreak-src/scripts/buildserver-*.sh /srv/ && chmod +x /srv/*.sh'

if [[ -n "${CCACHE_STORE:-}" ]]; then
  # The pipeline waits at the build step while /srv/ccache.restoring exists.
  s "touch /srv/ccache.restoring"
  log "restoring ccache from $CCACHE_STORE (in parallel with the sync)"
  ( if scripts/ci/ccache-sync.sh restore "$IP" "$IDENT" "$TMP/known_hosts"; then
      : > "$TMP/ccache-restore.ok"
    fi
    s "rm -f /srv/ccache.restoring" ) > "$TMP/ccache-restore.log" 2>&1 &
  CCACHE_PID=$!
fi

KEYS_PENDING=""
if [[ -n "${RELEASE_KEYS_DIR:-}" ]]; then
  # The pipeline waits before the build while /srv/keys.pending exists.
  s "touch /srv/keys.pending"; KEYS_PENDING=1
fi

log "starting sync + build ($LINEAGE_BRANCH, $BUILD_TYPE, flavours: $HIBREAK_FLAVORS)"
{
  printf 'LINEAGE_BRANCH=%q\n' "$LINEAGE_BRANCH"
  printf 'BUILD_TYPE=%q\n' "$BUILD_TYPE"
  printf 'BUILD_TARGET=%q\n' "$BUILD_TARGET"
  printf 'SYNC_ONLY=%q\n' "${SYNC_ONLY:-0}"
  printf 'HIBREAK_FLAVORS=%q\n' "$HIBREAK_FLAVORS"
} | s 'umask 077 && cat > /srv/pipeline.env'
s "tmux new-session -d -s ci '/srv/buildserver-pipeline.sh'"

deadline=$(( START + MAX_HOURS * 3600 ))
last=""
build_started=0
while :; do
  sleep 60
  if (( $(date +%s) > deadline )); then echo "MAX_HOURS=$MAX_HOURS reached, giving up" >&2; exit 1; fi
  st=$(s 'cat /srv/PIPELINE_STATE 2>/dev/null; tmux has-session -t ci 2>/dev/null || echo NO_TMUX' 2>/dev/null) || continue
  # Pipeline killed from outside (e.g. OOM takes the whole tmux scope) before it could write "done ..."
  if [[ "$st" == *NO_TMUX* ]]; then st="${st%%$'\n'*}"; [[ "$st" == done* ]] || st="done killed-in-${st:-unknown}"; fi
  [[ "$st" != "$last" ]] && { log "state: $st"; last="$st"; }
  case "$st" in
    build*|check*|"done ok"|"done build-failed"*|"done sepolicy"*|"done release-check-failed"*|"done no-zip"*|"done dist-failed"*|"done killed-in-build"*|"done killed-in-check"*)
      build_started=1 ;;
  esac
  [[ "$st" == done* ]] && break
  # The keys need the synced tree (install-keys.sh runs avbtool from it).
  if [[ -n "$KEYS_PENDING" && ( "$st" == patches || "$st" == wait-ccache || "$st" == wait-keys ) ]]; then
    log "installing release keys from $RELEASE_KEYS_DIR"
    KEYS_DIR="$RELEASE_KEYS_DIR" CI_HOST="$IP" CI_IDENTITY="$IDENT" CI_KNOWN_HOSTS="$TMP/known_hosts" \
      scripts/ci/install-keys.sh || { echo "installing the release keys failed" >&2; exit 1; }
    s "rm -f /srv/keys.pending"; KEYS_PENDING=""
  fi
done

s 'tail -5 /srv/build.log 2>/dev/null; cat /srv/sepolicy-check-*.log 2>/dev/null; cat /srv/release-check-*.log 2>/dev/null' || true
if [[ -n "$CCACHE_PID" ]]; then wait "$CCACHE_PID" || true; CCACHE_PID=""; cat "$TMP/ccache-restore.log"; fi
# Save the cache only if the build stage actually ran and restore didn't fail/corrupt it (or build succeeded).
if [[ -n "${CCACHE_STORE:-}" && $build_started -eq 1 ]] && { [[ -f "$TMP/ccache-restore.ok" || "$st" == "done ok" ]]; }; then
  log "saving ccache to $CCACHE_STORE"
  scripts/ci/ccache-sync.sh save "$IP" "$IDENT" "$TMP/known_hosts" || log "ccache save failed (build result unaffected)"
elif [[ -n "${CCACHE_STORE:-}" ]]; then
  log "skipping ccache save (state=$st, build_started=$build_started)"
fi
if ! grep -q '^done ok$' <<<"$st"; then
  echo "pipeline failed: $st" >&2
  case "$st" in
    *init-failed*) s 'tail -60 /srv/init.log' || true ;;
    *sync-failed*) s 'tail -60 /srv/sync3.log' || true ;;
    *patches-failed*) s 'tail -60 /srv/patches.log' || true ;;
    *sepolicy*) s "cat /srv/sepolicy-check-${st##*-}.log" || true ;;
    *release-check*) s "cat /srv/release-check-${st##*-}.log" || true ;;
    *) s 'tail -60 /srv/build.log' || true ;;
  esac
  exit 1
fi

if [[ "$BUILD_TARGET" == bacon && "${SYNC_ONLY:-0}" != 1 ]]; then
  log "fetching artifacts"
  OUT_DIR="$OUT_DIR" scripts/fetch-artifacts.sh "$IP"
fi
log "build finished"
