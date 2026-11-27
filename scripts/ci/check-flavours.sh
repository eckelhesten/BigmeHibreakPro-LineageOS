#!/bin/bash
# Runs ON the build server (root) after a multi-flavour build: check each flavour in /srv/dist/<flavour>/
# offline, from its images, before anything goes onto the phone or GitHub.
#   - the flavour is what it says (props, zip name), with no update server baked in
#   - the right Google-services apps are there, signed by the right party, and nothing else
#     leaked in from the flavour built before it (the builds share one tree, docs/building.md)
#   - privileged apps have their privapp permission files (a missing one stops boot on a user build)
#   - the OTA zip carries a payload and A/B metadata
# Usage: bash check-flavours.sh [dist-dir]       Exit status 1 if anything is wrong.
set -uo pipefail
shopt -s nullglob
DIST=${1:-/srv/dist}
SRC=/srv/lineage
J=$SRC/prebuilts/jdk/jdk21/linux-x86/bin/java
A=$SRC/prebuilts/sdk/tools/linux/lib/apksigner.jar
SIMG2IMG=$(command -v simg2img || echo "$SRC/out/host/linux-x86/bin/simg2img")
bad=0
err() { echo "  FAIL: $*"; bad=1; }
ok() { echo "  ok: $*"; }
work=$(mktemp -d /srv/check-flavours.XXXXXX)
cleanup() {
  local busy=0
  for m in "$work"/*/*; do
    if mountpoint -q "$m" && ! umount "$m"; then busy=1; fi
  done
  # Never rm -rf through a mount (read-only here, but a still-mounted image is no place for it).
  if [ "$busy" = 0 ]; then rm -rf "$work"; else echo "  note: $work still has mounts, left in place"; fi
}
trap cleanup EXIT

cert() { # apk -> SHA-256 of its first signer certificate
  "$J" -jar "$A" verify --print-certs "$1" 2>/dev/null | awk '/^Signer .*certificate SHA-256 digest/{print $NF; exit}'
}

first_match() { [ $# -gt 0 ] && printf '%s\n' "$1"; }

mount_img() { # image, mount point
  local img=$1 mnt=$2 raw
  mkdir -p "$mnt"
  if [ "$(head -c4 "$img" | od -An -tx1 | tr -d ' ')" = 3aff26ed ]; then # sparse
    raw="$mnt.raw"; "$SIMG2IMG" "$img" "$raw" || return 1; img=$raw
  fi
  mount -o ro,loop,noload "$img" "$mnt"
}

# microG's own certificate: from the release APK that fetch-microg.sh put into vendor/microg
MICROG_CERT=""
for f in "$SRC"/vendor/microg/*GmsCore*.apk "$SRC"/vendor/microg/*/GmsCore*.apk; do
  MICROG_CERT=$(cert "$f"); break
done

declare -A APPS
flavors=()
for d in "$DIST"/*/; do
  d=${d%/}
  f=$(basename "$d"); flavors+=("$f")
  echo "== $f"
  m=$work/$f
  for p in system system_ext product; do
    [ -f "$d/$p.img" ] || { err "$p.img missing"; continue; }
    mount_img "$d/$p.img" "$m/$p" || err "cannot mount $p.img"
  done
  S=$m/system/system; [ -d "$S" ] || S=$m/system
  SE=$m/system_ext; PR=$m/product

  # Zip name and props
  zip=$(first_match "$d"/lineage-*-UNOFFICIAL-"$f"-hibreakpro.zip)
  [ -n "$zip" ] && ok "zip $(basename "$zip") ($(du -h "$zip" | cut -f1))" || err "no zip named for $f"
  prop() { grep -h "^$1=" "$S/build.prop" "$SE/etc/build.prop" "$PR/etc/build.prop" 2>/dev/null | tail -1 | cut -d= -f2-; }
  [ "$(prop ro.hibreak.flavor)" = "$f" ] && ok "ro.hibreak.flavor=$f" || err "ro.hibreak.flavor='$(prop ro.hibreak.flavor)'"
  case "$(prop ro.lineage.version)" in *-"$f"-*|*-"$f") ok "ro.lineage.version=$(prop ro.lineage.version)" ;;
    *) err "ro.lineage.version='$(prop ro.lineage.version)' lacks the flavour" ;; esac
  sm=$(prop ro.hibreak.microg)
  if [ "$f" = gapps ]; then [ -z "$sm" ] && ok "no microG spoofing prop" || err "ro.hibreak.microg=$sm in gapps"
  else [ "$sm" = true ] && ok "ro.hibreak.microg=true" || err "ro.hibreak.microg='$sm'"; fi
  [ -z "$(prop lineage.updater.uri)" ] && ok "no update server URL" || err "lineage.updater.uri=$(prop lineage.updater.uri) (releases have no update server)"
  echo "  build: $(prop ro.build.type) $(prop ro.build.tags), fingerprint $(prop ro.build.fingerprint)"

  # Apps on the three partitions (name = directory), for the cross-flavour diff below
  list=""
  for base in "$S" "$SE" "$PR"; do
    for a in "$base"/app/*/ "$base"/priv-app/*/; do list+="${a#"$m"/}"$'\n'; done
  done
  APPS[$f]=$(printf '%s' "$list" | sed 's#/$##' | sort)
  echo "  apps: $(printf '%s\n' "${APPS[$f]}" | grep -c .)"

  gms=$(first_match "$PR"/priv-app/GmsCore/*.apk "$PR"/priv-app/PrebuiltGmsCore*/*.apk "$S"/priv-app/GmsCore/*.apk)
  store=$(first_match "$PR"/priv-app/Phonesky/*.apk)
  companion=$(first_match "$PR"/priv-app/MicrogCompanion/*.apk "$PR"/app/MicrogCompanion/*.apk)
  case $f in
    vanilla)
      [ -z "$gms$store$companion" ] && ok "no Google services" || err "Google services in vanilla: $gms $store $companion" ;;
    microg)
      [ -n "$gms" ] || err "no GmsCore"
      [ -n "$companion" ] || err "no microG Companion"
      [ -z "$store" ] || err "Play Store in microg: $store"
      if [ -n "$gms" ]; then
        c=$(cert "$gms")
        if [ -n "$MICROG_CERT" ] && [ "$c" = "$MICROG_CERT" ]; then ok "GmsCore signed by microG ($c)"
        else err "GmsCore cert $c, microG's is '${MICROG_CERT:-unknown}'"; fi
      fi
      [ -f "$S/etc/microg.xml" ] && ok "microg.xml defaults" || err "no /system/etc/microg.xml" ;;
    gapps)
      [ -n "$gms" ] || err "no GmsCore"
      [ -n "$store" ] || err "no Play Store (Phonesky)"
      [ -z "$companion" ] || err "microG Companion in gapps: $companion"
      if [ -n "$gms" ]; then
        c=$(cert "$gms")
        [ -n "$c" ] && [ "$c" != "$MICROG_CERT" ] && ok "GmsCore signed by Google ($c)" || err "GmsCore cert '$c'"
      fi ;;
  esac

  # Every privileged app needs its permissions declared in a privapp-permissions XML
  # (ro.control_privapp_permissions=enforce on user builds: a missing one stops the boot)
  missing=0
  for base in "$S" "$SE" "$PR"; do
    for apk in "$base"/priv-app/*/*.apk; do
      pkg=$("$SRC"/prebuilts/sdk/tools/linux/bin/aapt2 dump packagename "$apk" 2>/dev/null)
      [ -n "$pkg" ] || continue
      if ! grep -rqsF "package=\"$pkg\"" "$S/etc/permissions" "$SE/etc/permissions" "$PR/etc/permissions"; then
        # Apps that ask for no privileged permission need no file
        if "$SRC"/prebuilts/sdk/tools/linux/bin/aapt2 dump permissions "$apk" 2>/dev/null | grep -q "uses-permission"; then
          echo "  note: no privapp-permissions entry for $pkg (${apk#"$m"/})"; missing=$((missing + 1))
        fi
      fi
    done
  done
  [ "$missing" = 0 ] && ok "every privileged app has a privapp-permissions entry" \
    || echo "  ($missing privileged apps without an entry: harmless only if they use no privileged permission)"

  # OTA zip: payload and A/B metadata
  if [ -n "$zip" ]; then
    meta=$(unzip -p "$zip" META-INF/com/android/metadata 2>/dev/null)
    unzip -l "$zip" payload.bin >/dev/null 2>&1 && ok "payload.bin present" || err "no payload.bin"
    grep -q '^ota-type=AB' <<<"$meta" && ok "A/B package" || err "not an A/B package"
    echo "  post-build: $(sed -n 's/^post-build=//p' <<<"$meta")"
  fi
  du -h "$d"/{system,system_ext,product}.img 2>/dev/null | sed 's/^/  size: /'
done

# What differs between flavours: anything beyond the Google-services apps leaked from another build
echo "== app differences (relative to vanilla)"
if [ -n "${APPS[vanilla]:-}" ]; then
  for f in "${flavors[@]}"; do
    [ "$f" = vanilla ] && continue
    extra=$(comm -13 <(printf '%s\n' "${APPS[vanilla]}") <(printf '%s\n' "${APPS[$f]}"))
    gone=$(comm -23 <(printf '%s\n' "${APPS[vanilla]}") <(printf '%s\n' "${APPS[$f]}"))
    echo "-- $f adds:"; printf '%s\n' "$extra" | sed '/^$/d; s/^/   + /'
    echo "-- $f removes:"; printf '%s\n' "$gone" | sed '/^$/d; s/^/   - /'
  done
  if [ -n "${APPS[microg]:-}" ]; then
    unexpected=$(comm -13 <(printf '%s\n' "${APPS[vanilla]}") <(printf '%s\n' "${APPS[microg]}") \
      | grep -vE '/(GmsCore|MicrogCompanion)$')
    [ -z "$unexpected" ] && echo "  ok: microg = vanilla + GmsCore + Companion" || err "microg has extra apps: $unexpected"
    [ -z "$(comm -23 <(printf '%s\n' "${APPS[vanilla]}") <(printf '%s\n' "${APPS[microg]}"))" ] \
      || err "microg lacks apps that vanilla has"
  fi
fi

echo "CHECK_FLAVOURS=$([ $bad = 0 ] && echo ok || echo failed)"
exit $bad
