#!/usr/bin/env bash
# Runs ON the build server: drop our trees into the synced LineageOS source and build hibreakpro.
# Usage: buildserver-build.sh [make-target]   (default: bacon = flashable OTA zip + images)
# Env: BUILD_TYPE (userdebug), HIBREAK_FLAVOR (vanilla | microg | gapps, default microg; see
# device.mk).
set -uo pipefail
SRC=/srv/lineage
read -r -a TARGETS <<<"${*:-bacon}"
[ "${#TARGETS[@]}" -gt 0 ] || TARGETS=(bacon)
cd "$SRC" || { echo "BUILD_RESULT=no-src-dir"; exit 1; }

# By content, without the senders' file times: a changed file gets the time of this copy, so the
# build sees it as newer than its outputs. With -a, a file edited on the Mac while an earlier
# build ran kept a time older than that build's outputs and was never rebuilt.
mkdir -p device/bigme vendor/bigme
rsync -rlpD --checksum --delete /srv/hibreak-src/device/bigme/ device/bigme/ \
  || { echo "BUILD_RESULT=rsync-device-failed"; exit 1; }
rsync -rlpD --checksum --delete /srv/hibreak-src/vendor/bigme/ vendor/bigme/ \
  || { echo "BUILD_RESULT=rsync-vendor-failed"; exit 1; }

HIBREAK_FLAVOR="${HIBREAK_FLAVOR:-microg}"
case "$HIBREAK_FLAVOR" in
  vanilla|microg|gapps) ;;
  *) echo "BUILD_RESULT=bad-flavor ($HIBREAK_FLAVOR)"; exit 1 ;;
esac
export HIBREAK_FLAVOR
echo "== flavour: $HIBREAK_FLAVOR"

# microG lives outside our trees (vendor/microg, ~110 MB of release APKs): fetch it once. A copy
# from before it had its own Soong namespace would clash with MindTheGapps' GmsCore: refetch.
if [ "$HIBREAK_FLAVOR" = microg ] && ! grep -qs '^soong_namespace' vendor/microg/Android.bp; then
  bash /srv/hibreak-src/scripts/ci/fetch-microg.sh || { echo "BUILD_RESULT=microg-fetch-failed"; exit 1; }
fi
# MindTheGapps comes in with the sync (manifests/flavors/gapps.xml, Git LFS). An LFS pointer
# instead of the APK means the objects were never pulled.
if [ "$HIBREAK_FLAVOR" = gapps ]; then
  gms=vendor/gapps/arm64/proprietary/product/priv-app/GmsCore/GmsCore.apk
  [ -f vendor/gapps/arm64/arm64-vendor.mk ] || { echo "BUILD_RESULT=no-mindthegapps"; exit 1; }
  if [ "$(stat -c %s "$gms" 2>/dev/null || echo 0)" -lt 1000000 ]; then
    git -C vendor/gapps lfs pull || true
    [ "$(stat -c %s "$gms" 2>/dev/null || echo 0)" -ge 1000000 ] \
      || { echo "BUILD_RESULT=mindthegapps-lfs-missing"; exit 1; }
  fi
fi
# Release keys (vendor/lineage-priv/keys) are installed from the Mac with
# scripts/ci/install-keys.sh; without them the build would silently use the test keys.
[ -f vendor/lineage-priv/keys/keys.mk ] || { echo "BUILD_RESULT=no-release-keys"; exit 1; }

echo "== VNDK prebuilts available:"; ls prebuilts/vndk 2>/dev/null | tr '\n' ' '; echo

if [ "${HIBREAK_DISABLE_CCACHE:-0}" = 1 ]; then
  export USE_CCACHE=0
else
  export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=/srv/ccache
  ccache -M 80G >/dev/null
fi

set +u
source build/envsetup.sh
breakfast hibreakpro "${BUILD_TYPE:-userdebug}" || { echo "BUILD_RESULT=breakfast-failed"; exit 1; }
set -u
# Switching flavours: the build doesn't take apps out of the staging directories when they leave
# PRODUCT_PACKAGES, so the old flavour's apps would end up in the new images. installclean empties
# them (compiled objects stay). No stamp: built before flavours existed, which was microG.
stamp="$OUT/.hibreak-flavor"
last=$(cat "$stamp" 2>/dev/null || echo microg)
if [ -d "$OUT" ] && [ "$last" != "$HIBREAK_FLAVOR" ]; then
  echo "== flavour $last -> $HIBREAK_FLAVOR: installclean"
  m installclean || { echo "BUILD_RESULT=installclean-failed"; exit 1; }
fi
mkdir -p "$OUT" && echo "$HIBREAK_FLAVOR" > "$stamp"
start=$(date +%s)
m -j"$(nproc)" "${TARGETS[@]}"
rc=$?
echo "BUILD_SECONDS=$(( $(date +%s) - start ))"
echo "BUILD_RESULT=$([ $rc -eq 0 ] && echo ok || echo failed) rc=$rc"
ls -la "$OUT"/*.zip "$OUT"/*.img 2>/dev/null
exit $rc
