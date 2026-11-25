#!/usr/bin/env bash
# Copy the build outputs from the build server into $OUT_DIR/<build-name>/ and verify checksums.
# Usage: scripts/build/fetch-artifacts.sh [server-ip]     (SSH_OPTS / OUT_DIR / FETCH_MODE env)
#
# The pipeline leaves each flavour's zip, images and logs in /srv/dist/<flavour>/
# (scripts/build/buildserver-pipeline.sh); each becomes $OUT_DIR/lineage-<version>-<flavour>-hibreakpro/.
# Without /srv/dist (a partial build) the newest zip in the product out directory is fetched.
#
# FETCH_MODE=logs: only the logs and the server's SHA256SUMS of the zip and images (CI runs that
# don't publish, which used to download ~12 GB only to keep the logs). Default: everything.
#
# One SSH connection for all of it (ControlMaster), and one zstd-compressed tar stream per
# flavour instead of an scp per file; the checksums are computed on the server and verified here.
set -euo pipefail
cd "$(dirname "$0")/../.."
H="root@${1:-$(hcloud server ip hibreak-build)}"
MODE="${FETCH_MODE:-all}"
[[ "$MODE" == all || "$MODE" == logs ]] || { echo "FETCH_MODE must be all or logs, not $MODE" >&2; exit 2; }
read -r -a SSH_ARR <<<"${SSH_OPTS:--o BatchMode=yes}"
CM_DIR=$(mktemp -d)
trap 'ssh "${SSH_ARR[@]}" -o ControlPath="$CM_DIR/cm" -O exit "$H" >/dev/null 2>&1 || true; rm -rf "$CM_DIR"' EXIT
SSH_ARR+=(-o ControlMaster=auto -o "ControlPath=$CM_DIR/cm" -o ControlPersist=120)
P=/srv/lineage/out/target/product/hibreakpro

REQ_IMGS=(boot.img dtbo.img vbmeta.img vbmeta_system.img vbmeta_vendor.img system.img system_ext.img product.img)
OPT_IMGS=(super_empty.img)
LOGS=(build.log sepolicy-check.log release-check.log sync3.log)

r() { ssh -n "${SSH_ARR[@]}" "$H" "$@"; }

fetch_dir() { # remote dir, zip path in it, log dir -> $OUT_DIR/<zip name>/
  local dir=$1 zip=$2 logs=$3 name dest f
  name=$(basename "$zip" .zip)
  dest="${OUT_DIR:-out}/$name"; mkdir -p "$dest"
  local fetched=("$(basename "$zip")" "${REQ_IMGS[@]}")
  for f in "${OPT_IMGS[@]}"; do
    if r "[ -f $(printf '%q' "$dir/$f") ]"; then fetched+=("$f"); else echo "optional image absent: $f"; fi
  done
  if [ "$MODE" = all ]; then
    # zstd -1: the zip is compressed already, the images (erofs/ext4 with free space) partly.
    ssh -n "${SSH_ARR[@]}" "$H" "set -o pipefail; cd $(printf '%q' "$dir") && tar -cf - $(printf ' %q' "${fetched[@]}") | zstd -1 -T0 -q" \
      | zstd -dq | tar -C "$dest" -xf -
  fi
  local have=()
  for f in "${LOGS[@]}"; do
    if r "[ -f $(printf '%q' "$logs/$f") ]"; then have+=("$f"); fi
  done
  if [ "${#have[@]}" -gt 0 ]; then
    ssh -n "${SSH_ARR[@]}" "$H" "set -o pipefail; cd $(printf '%q' "$logs") && tar -cf - $(printf ' %q' "${have[@]}") | zstd -3 -T0 -q" \
      | zstd -dq | tar -C "$dest" -xf - || true
  fi
  r "cd $(printf '%q' "$dir") && sha256sum$(printf ' %q' "${fetched[@]}")" > "$dest/SHA256SUMS"
  if [ "$MODE" = all ]; then
    (
      cd "$dest"
      if command -v sha256sum >/dev/null 2>&1; then
        sha256sum -c SHA256SUMS
      else
        shasum -a 256 -c SHA256SUMS
      fi
    ) && echo "artifacts verified in $dest"
  else
    echo "logs and server checksums in $dest (FETCH_MODE=logs: no zip or images)"
  fi
  ls -la "$dest"
}

flavors=$(r "ls /srv/dist 2>/dev/null" || true)
if [ -n "$flavors" ]; then
  for f in $flavors; do
    zip=$(r "ls -t /srv/dist/$f/lineage-*-hibreakpro.zip 2>/dev/null | head -1" || true)
    [ -n "$zip" ] || { echo "no OTA zip in $H:/srv/dist/$f" >&2; exit 1; }
    fetch_dir "/srv/dist/$f" "$zip" "/srv/dist/$f"
  done
else
  zip=$(r "ls -t $P/lineage-*-hibreakpro.zip 2>/dev/null | head -1" || true)
  [ -n "$zip" ] || { echo "no OTA zip found on $H:$P" >&2; exit 1; }
  fetch_dir "$P" "$zip" /srv
fi
