#!/usr/bin/env bash
# Copy the build outputs from the build server into $OUT_DIR/<build-name>/ and verify checksums.
# Usage: scripts/fetch-artifacts.sh [server-ip]     (SSH_OPTS / OUT_DIR env are honoured)
#
# The pipeline leaves each flavour's zip, images and logs in /srv/dist/<flavour>/
# (scripts/buildserver-pipeline.sh); each becomes $OUT_DIR/lineage-<version>-<flavour>-hibreakpro/.
# Without /srv/dist (a build from before flavours, or rebuild.sh) the newest zip in the product
# out directory is fetched as before.
set -euo pipefail
cd "$(dirname "$0")/.."
H="root@${1:-$(hcloud server ip hibreak-build)}"
read -r -a SSH_ARR <<<"${SSH_OPTS:--o BatchMode=yes}"
P=/srv/lineage/out/target/product/hibreakpro

REQ_IMGS=(boot.img dtbo.img vbmeta.img vbmeta_system.img vbmeta_vendor.img system.img system_ext.img product.img)
OPT_IMGS=(super_empty.img)

fetch_dir() { # remote dir, zip path in it, log dir -> $OUT_DIR/<zip name>/
  local dir=$1 zip=$2 logs=$3 name dest f quoted
  name=$(basename "$zip" .zip)
  dest="${OUT_DIR:-out}/$name"; mkdir -p "$dest"
  local fetched=("$(basename "$zip")")
  scp -q "${SSH_ARR[@]}" "$H:$zip" "$dest/"
  for f in "${REQ_IMGS[@]}"; do
    scp -q "${SSH_ARR[@]}" "$H:$dir/$f" "$dest/"
    fetched+=("$f")
  done
  for f in "${OPT_IMGS[@]}"; do
    if ssh -n "${SSH_ARR[@]}" "$H" "[ -f $(printf '%q' "$dir/$f") ]"; then
      scp -q "${SSH_ARR[@]}" "$H:$dir/$f" "$dest/"
      fetched+=("$f")
    else
      echo "optional image absent: $f"
    fi
  done
  for f in build.log sepolicy-check.log release-check.log sync3.log; do
    scp -q "${SSH_ARR[@]}" "$H:$logs/$f" "$dest/$f" 2>/dev/null || true
  done
  quoted=$(printf ' %q' "${fetched[@]}")
  ssh -n "${SSH_ARR[@]}" "$H" "cd $(printf '%q' "$dir") && sha256sum$quoted" > "$dest/SHA256SUMS"
  (
    cd "$dest"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum -c SHA256SUMS
    else
      shasum -a 256 -c SHA256SUMS
    fi
  ) && echo "artifacts verified in $dest"
  ls -la "$dest"
}

flavors=$(ssh -n "${SSH_ARR[@]}" "$H" "ls /srv/dist 2>/dev/null" || true)
if [ -n "$flavors" ]; then
  for f in $flavors; do
    zip=$(ssh -n "${SSH_ARR[@]}" "$H" "ls -t /srv/dist/$f/lineage-*-hibreakpro.zip 2>/dev/null | head -1" || true)
    [ -n "$zip" ] || { echo "no OTA zip in $H:/srv/dist/$f" >&2; exit 1; }
    fetch_dir "/srv/dist/$f" "$zip" "/srv/dist/$f"
  done
else
  zip=$(ssh -n "${SSH_ARR[@]}" "$H" "ls -t $P/lineage-*-hibreakpro.zip 2>/dev/null | head -1" || true)
  [ -n "$zip" ] || { echo "no OTA zip found on $H:$P" >&2; exit 1; }
  fetch_dir "$P" "$zip" /srv
fi
