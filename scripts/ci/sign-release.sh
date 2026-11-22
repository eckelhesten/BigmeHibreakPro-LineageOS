#!/usr/bin/env bash
# NOT WIRED IN YET (PLAN.md: release keys + user build). Runs ON the build server after
# `m target-files-package otatools`: re-sign the target files with our release keys and make
# a signed full OTA (+ incremental if PREV_TARGET_FILES is given).
# Keys are generated once, offline (development/tools/make_key), and must never be committed.
# Env: KEYS_DIR (required), DIST (output dir), DEVICE (hibreakpro), LINEAGE_BRANCH, PREV_TARGET_FILES
set -euo pipefail
shopt -s nullglob
: "${KEYS_DIR:?}" "${OUT:?run after breakfast}"
DEVICE="${DEVICE:-hibreakpro}"; LINEAGE_BRANCH="${LINEAGE_BRANCH:-lineage-23.2}"
DIST="${DIST:-/srv/dist}"; mkdir -p "$DIST"

[ -f "$KEYS_DIR/releasekey.pk8" ] || { echo "missing releasekey.pk8 in $KEYS_DIR" >&2; exit 1; }
[ -f "$KEYS_DIR/avb2048.pem" ] || { echo "missing avb2048.pem in $KEYS_DIR" >&2; exit 1; }

TF_UNSIGNED=$(ls -t "$OUT"/obj/PACKAGING/target_files_intermediates/*-target_files*.zip | head -1)
SIGNED_TF="$DIST/lineage-$DEVICE-target_files-signed.zip"

sign_args=(
  -o -d "$KEYS_DIR"
  --extra_apks AdServicesApk.apk="$KEYS_DIR/releasekey"
  --extra_apks HalfSheetUX.apk="$KEYS_DIR/releasekey"
  --extra_apks OsuLogin.apk="$KEYS_DIR/releasekey"
  --extra_apks SafetyCenterResources.apk="$KEYS_DIR/releasekey"
  --extra_apks ServiceConnectivityResources.apk="$KEYS_DIR/releasekey"
  --extra_apks ServiceUwbResources.apk="$KEYS_DIR/releasekey"
  --extra_apks ServiceWifiResources.apk="$KEYS_DIR/releasekey"
  --extra_apks WifiDialog.apk="$KEYS_DIR/releasekey"
  --avb_vbmeta_key "$KEYS_DIR/avb2048.pem"
  --avb_vbmeta_algorithm SHA256_RSA2048
  --avb_vbmeta_system_key "$KEYS_DIR/avb2048.pem"
  --avb_vbmeta_system_algorithm SHA256_RSA2048
  --avb_vbmeta_vendor_key "$KEYS_DIR/avb2048.pem"
  --avb_vbmeta_vendor_algorithm SHA256_RSA2048
)
n_apex=0
for pem in "$KEYS_DIR"/com.*.pem; do
  case $pem in *.x509.pem) continue ;; esac
  a=$(basename "$pem" .pem)
  sign_args+=(
    --extra_apex_payload_key "$a.apex=$pem"
    --extra_apks "$a.apex=$KEYS_DIR/$a"
  )
  n_apex=$((n_apex + 1))
done
[ "$n_apex" -gt 0 ] || { echo "no per-APEX keys in $KEYS_DIR" >&2; exit 1; }

sign_target_files_apks "${sign_args[@]}" "$TF_UNSIGNED" "$SIGNED_TF"

STAMP=$(date -u +%Y%m%d)
FULL="$DIST/lineage-${LINEAGE_BRANCH#lineage-}-$STAMP-UNOFFICIAL-$DEVICE.zip"
ota_from_target_files -k "$KEYS_DIR/releasekey" "$SIGNED_TF" "$FULL"
hash_files=("$(basename "$SIGNED_TF")" "$(basename "$FULL")")
if [[ -n "${PREV_TARGET_FILES:-}" && -f "$PREV_TARGET_FILES" ]]; then
  INC="${FULL%.zip}-incremental.zip"
  ota_from_target_files -k "$KEYS_DIR/releasekey" -i "$PREV_TARGET_FILES" "$SIGNED_TF" "$INC"
  hash_files+=("$(basename "$INC")")
fi

# Fastboot-flashable images for first install / recovery.
imgs=(boot.img dtbo.img vbmeta.img vbmeta_system.img vbmeta_vendor.img system.img system_ext.img product.img)
unzip_patterns=()
for im in "${imgs[@]}"; do unzip_patterns+=("IMAGES/$im"); done
unzip -o -j "$SIGNED_TF" "${unzip_patterns[@]}" -d "$DIST"
for im in "${imgs[@]}"; do
  [ -f "$DIST/$im" ] && hash_files+=("$im")
done

(
  cd "$DIST"
  sha256sum "${hash_files[@]}" > SHA256SUMS.tmp
  mv -f SHA256SUMS.tmp SHA256SUMS
)
