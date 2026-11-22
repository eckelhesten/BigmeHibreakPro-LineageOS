#!/bin/bash
# Runs ON the build server: repo init (first run) -> sync -> patches -> build -> sepolicy check.
# Progress goes to /srv/PIPELINE_STATE (polled by scripts/ci/hcloud-build.sh):
#   init | sync | patches | wait-ccache | wait-keys | build <flavour> | check <flavour> | "done ok"
#   | "done <failure>"
# HIBREAK_FLAVORS (pipeline.env, default "microg") lists the flavours to build one after the other
# on the same tree (device.mk). A full build (bacon) of each is checked and its zip and images are
# copied to /srv/dist/<flavour>/ before the next one starts (scripts/build/fetch-artifacts.sh).
# SKIP_SYNC=1 (a kept server whose tree is synced and patched): no init, sync or waits; the
# projects in REPATCH (e.g. "frameworks/base frameworks/opt/telephony") are reset to the manifest
# and their patches re-applied from /srv/hibreak-src, then the flavours are built.
[ -f /srv/pipeline.env ] && source /srv/pipeline.env
LINEAGE_BRANCH="${LINEAGE_BRANCH:-lineage-23.2}"
export BUILD_TYPE="${BUILD_TYPE:-userdebug}"
BUILD_TARGET="${BUILD_TARGET:-bacon}"
HIBREAK_FLAVORS="${HIBREAK_FLAVORS:-microg}"
state() { printf '%s\n' "$*" > /srv/PIPELINE_STATE.tmp && mv -f /srv/PIPELINE_STATE.tmp /srv/PIPELINE_STATE; }
fail() { state "done $1"; exit 1; }

mkdir -p /srv/lineage && cd /srv/lineage || fail init-failed
if [ "${SKIP_SYNC:-0}" = 1 ]; then
  state patches
  for p in ${REPATCH:-}; do
    d=/srv/hibreak-src/patches/$p
    ls "$d"/*.patch >/dev/null 2>&1 || fail "no-patches-$p"
    { (cd "$p" && (git am --abort >/dev/null 2>&1 || true) && git reset -q --hard m/"$LINEAGE_BRANCH" &&
        git -c user.name=ci -c user.email=ci@localhost am -q --3way "$d"/*.patch &&
        git log --oneline m/"$LINEAGE_BRANCH"..HEAD); } >> /srv/patches.log 2>&1 || fail "repatch-failed-$p"
  done
else
if [ ! -f .repo/manifest.xml ]; then
  state init
  repo init -u https://github.com/LineageOS/android.git -b "$LINEAGE_BRANCH" --git-lfs --depth=1 \
    --no-clone-bundle > /srv/init.log 2>&1 || fail init-failed
fi
mkdir -p .repo/local_manifests
# Extra projects only; device/vendor trees are copied in by buildserver-build.sh
for m in /srv/hibreak-src/manifests/*.xml; do
  [ -f "$m" ] || continue
  grep -q OWNER "$m" || cp "$m" .repo/local_manifests/
done
# Flavour-only projects (MindTheGapps: ~1 GB of Git LFS objects), synced only when needed.
for f in $HIBREAK_FLAVORS; do
  [ -f "/srv/hibreak-src/manifests/flavors/$f.xml" ] && cp "/srv/hibreak-src/manifests/flavors/$f.xml" \
    ".repo/local_manifests/flavor-$f.xml"
done

state sync
/srv/buildserver-sync.sh
grep -q SYNC_EXIT=0 /srv/sync3.log || fail sync-failed

state patches
if [ -d /srv/hibreak-src/patches ]; then
  /srv/hibreak-src/scripts/ci/apply-patches.sh /srv/lineage /srv/hibreak-src/patches > /srv/patches.log 2>&1 \
    || fail patches-failed
fi

# hcloud-build.sh uploads the saved compiler cache during the sync; wait for it (max 90 min).
if [ -e /srv/ccache.restoring ]; then
  state wait-ccache
  for _ in $(seq 540); do [ -e /srv/ccache.restoring ] || break; sleep 10; done
  if [ -e /srv/ccache.restoring ]; then
    echo "ccache restore timed out after 90 min; building without ccache" >&2
    export HIBREAK_DISABLE_CCACHE=1
  fi
fi

# hcloud-build.sh installs the release keys once the sync is done (RELEASE_KEYS_DIR); wait for them.
if [ -e /srv/keys.pending ]; then
  state wait-keys
  for _ in $(seq 180); do [ -e /srv/keys.pending ] || break; sleep 10; done
  [ -e /srv/keys.pending ] && fail keys-not-installed
fi

fi # SKIP_SYNC

if [ "${SYNC_ONLY:-0}" = 1 ]; then
  state "done ok"
  exit 0
fi

P=/srv/lineage/out/target/product/hibreakpro
rm -rf /srv/dist
for f in $HIBREAK_FLAVORS; do
  state "build $f"
  HIBREAK_FLAVOR="$f" /srv/buildserver-build.sh "$BUILD_TARGET" > "/srv/build-$f.log" 2>&1
  rc=$?
  ln -sfn "build-$f.log" /srv/build.log
  [ "$rc" = 0 ] || fail "build-failed-$f"

  # The checks need the images of a full build (bacon); partial targets build one flavour only.
  [ "$BUILD_TARGET" = bacon ] || { state "done ok"; exit 0; }
  state "check $f"
  /srv/buildserver-sepolicy-check.sh > "/srv/sepolicy-check-$f.log" 2>&1 || fail "sepolicy-failed-$f"
  grep -q SEPOLICY_COMPILE=ok "/srv/sepolicy-check-$f.log" || fail "sepolicy-failed-$f"
  ! grep -q '=MISSING$' "/srv/sepolicy-check-$f.log" || fail "sepolicy-xrz-map-missing-$f"
  if [ -f /srv/hibreak-src/scripts/ci/check-release-build.sh ]; then
    bash /srv/hibreak-src/scripts/ci/check-release-build.sh > "/srv/release-check-$f.log" 2>&1 \
      || fail "release-check-failed-$f"
  fi

  # Keep this flavour's outputs: the next flavour's installclean deletes the images. Copies, not
  # hard links: the next build may rewrite an image in place.
  zip=$(ls -t "$P"/lineage-*-UNOFFICIAL-"$f"-hibreakpro.zip 2>/dev/null | head -1)
  [ -n "$zip" ] || fail "no-zip-$f"
  d=/srv/dist/$f; mkdir -p "$d"
  cp "$zip" "$d/" || fail "dist-failed-$f"
  for img in boot dtbo vbmeta vbmeta_system vbmeta_vendor system system_ext product super_empty; do
    [ -f "$P/$img.img" ] && { cp "$P/$img.img" "$d/" || fail "dist-failed-$f"; }
  done
  for l in build sepolicy-check release-check; do
    [ -f "/srv/$l-$f.log" ] && cp "/srv/$l-$f.log" "$d/$l.log"
  done
  cp /srv/sync3.log "$d/" 2>/dev/null || true
done
if [ -f /srv/hibreak-src/scripts/ci/check-flavours.sh ]; then
  state "check flavours"
  bash /srv/hibreak-src/scripts/ci/check-flavours.sh /srv/dist > /srv/flavours-check.log 2>&1 \
    || fail flavours-check-failed
fi
state "done ok"
