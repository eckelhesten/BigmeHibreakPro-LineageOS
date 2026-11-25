#!/bin/bash
# Runs ON the build server after a release-key build, before flashing: catch the signing mistakes
# that make a user build loop before adb is up (see docs/signing.md).
# - Every APEX: the public key it embeds (apex_pubkey) must be the key its payload is signed with,
#   otherwise apexd rejects it at boot. Reports APEXes not on our keys (the CTS shim is expected).
# - Every APK on system, system_ext and product must verify and must not use an AOSP test key.
# - SELinux: each certificate in plat_mac_permissions.xml must be one of ours.
# Exit status 1 if anything is wrong.
set -uo pipefail
shopt -s nullglob
cd /srv/lineage
KEYS=vendor/lineage-priv/keys
P=out/target/product/hibreakpro
AV=external/avb/avbtool.py
J=$PWD/prebuilts/jdk/jdk21/linux-x86/bin/java
A=$PWD/prebuilts/sdk/tools/linux/lib/apksigner.jar
[ -f "$A" ] || A=$PWD/prebuilts/sdk/tools/darwin/lib/apksigner.jar
bad=0
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

declare -A OURS
for k in "$KEYS"/*.avbpubkey; do
  OURS[$(sha1sum < "$k" | cut -d' ' -f1)]=1
done
[ "${#OURS[@]}" -gt 0 ] || { echo "NO RELEASE AVB PUBKEYS IN $KEYS"; exit 1; }

apex_count=0
for f in "$P"/system/apex/*.apex "$P"/system/apex/*.capex "$P"/system_ext/apex/*.apex "$P"/system_ext/apex/*.capex "$P"/product/apex/*.apex "$P"/product/apex/*.capex; do
  [ -f "$f" ] || continue
  apex_count=$((apex_count + 1))
  n=$(basename "$f"); d=$tmp/$n; mkdir -p "$d"
  case $f in
    *.capex)
      unzip -qo "$f" original_apex -d "$d" 2>/dev/null && unzip -qo "$d/original_apex" apex_payload.img apex_pubkey -d "$d" 2>/dev/null || true ;;
    *)
      unzip -qo "$f" apex_payload.img apex_pubkey -d "$d" 2>/dev/null || true ;;
  esac
  case $n in
    com.android.apex.cts.shim*)
      [ -f "$d/apex_payload.img" ] || continue ;;
  esac
  if [ ! -s "$d/apex_payload.img" ] || [ ! -s "$d/apex_pubkey" ]; then
    echo "APEX MISSING PAYLOAD/PUBKEY: $n"; bad=1; continue
  fi
  payload=$(python3 "$AV" info_image --image "$d/apex_payload.img" 2>/dev/null | sed -n 's/^Public key (sha1): *//p')
  embedded=$(sha1sum < "$d/apex_pubkey" | cut -d' ' -f1)
  if [ -z "$payload" ] || [ -z "$embedded" ] || [ "$payload" != "$embedded" ]; then
    echo "APEX KEY MISMATCH: $n"; bad=1
  elif [ -z "${OURS[$embedded]:-}" ]; then
    case $n in
      com.android.apex.cts.shim*) echo "apex not on our keys (expected shim): $n" ;;
      *) echo "APEX NOT ON OUR KEYS: $n"; bad=1 ;;
    esac
  fi
done
[ "$apex_count" -gt 0 ] || { echo "NO APEXES FOUND IN $P"; bad=1; }

declare -A TEST
for c in build/make/target/product/security/*.x509.pem; do
  TEST[$(openssl x509 -in "$c" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr A-F a-f)]=$(basename "$c" .x509.pem)
done
# apksigner (a JVM each, ~750 packages per flavour): in parallel, one result file per package,
# then the results in order.
verify_pkg() { # pkg result-file: line 1 the note (or empty), then the signers' SHA-256 digests
  local pkg=$1 res=$2 out note=""
  if out=$("$J" -jar "$A" verify --print-certs "$pkg" 2>/dev/null); then
    :
  elif [ "$(basename "$pkg")" = PrebuiltExchange3Google.apk ] \
      && out=$("$J" -jar "$A" verify --max-sdk-version 23 --print-certs "$pkg" 2>/dev/null); then
    # Upstream MindTheGapps ships this API 23 APK with its v2 block stripped; its v1 Google signature is intact.
    note="apk with stripped v2 signature (expected MindTheGapps upstream): $(basename "$pkg")"
  else
    out=""
  fi
  { printf '%s\n' "$note"; sed -n 's/^Signer .*certificate SHA-256 digest: //p' <<<"$out"; } > "$res"
}
export -f verify_pkg
export J A
mkdir -p "$tmp/pkgs"
find "$P/system" "$P/system_ext" "$P/product" \( -name '*.apk' -o -name '*.apex' -o -name '*.capex' \) -print0 \
  | sort -z > "$tmp/pkglist"
i=0
while IFS= read -r -d '' pkg; do
  printf '%s\0%s\0' "$pkg" "$tmp/pkgs/$i"; i=$((i + 1))
done < "$tmp/pkglist" | xargs -0 -n 2 -P "$(nproc 2>/dev/null || echo 4)" bash -c 'verify_pkg "$1" "$2"' _
n=0
while IFS= read -r -d '' pkg; do
  res=$tmp/pkgs/$n
  n=$((n + 1))
  note=""; signers=""
  if [ -f "$res" ]; then
    note=$(head -1 "$res"); signers=$(tail -n +2 "$res")
  fi
  [ -n "$note" ] && echo "$note"
  if [ -z "$signers" ]; then
    echo "PACKAGE DOES NOT VERIFY: $pkg"; bad=1
  else
    while IFS= read -r d; do
      [ -n "$d" ] || continue
      if [ -n "${TEST[$d]:-}" ]; then
        case $(basename "$pkg") in
          com.android.apex.cts.shim*) ;;
          *) echo "PACKAGE ON TEST KEY ${TEST[$d]}: $pkg"; bad=1 ;;
        esac
      fi
    done <<<"$signers"
  fi
done < "$tmp/pkglist"
[ "$n" -gt 0 ] || { echo "NO APKS/APEXES VERIFIED IN $P"; bad=1; }
echo "checked $n APKs/APEXes"

python3 - "$P" "$KEYS" <<'EOF' || bad=1
import binascii, glob, hashlib, os, subprocess, sys
import xml.etree.ElementTree as ET

def cert_sha256(path):
    der = subprocess.check_output(["openssl", "x509", "-in", path, "-outform", "DER"])
    return hashlib.sha256(der).hexdigest()

ours = {cert_sha256(c) for c in sorted(glob.glob(sys.argv[2] + "/*.x509.pem"))}
test = {cert_sha256(c): os.path.basename(c)
        for c in sorted(glob.glob("build/make/target/product/security/*.x509.pem"))}
if not ours:
    print("NO RELEASE X509 CERTS FOUND"); sys.exit(1)
ok = True
for rel, require_ours in (
    ("system/etc/selinux/plat_mac_permissions.xml", True),
    ("system_ext/etc/selinux/system_ext_mac_permissions.xml", False),
    ("product/etc/selinux/product_mac_permissions.xml", False),
):
    path = os.path.join(sys.argv[1], rel)
    if not os.path.exists(path):
        if require_ours:
            print("MISSING REQUIRED MAC_PERMISSIONS:", rel); ok = False
        continue
    root = ET.parse(path).getroot()
    signers = root.findall(".//signer")
    if require_ours and not signers:
        print("EMPTY MAC_PERMISSIONS:", rel); ok = False
    for signer in signers:
        sig = signer.get("signature", "").strip()
        if not sig:
            print("MISSING SIGNATURE ATTR:", rel); ok = False; continue
        seinfo_el = signer.find("seinfo")
        seinfo = seinfo_el.get("value", "") if seinfo_el is not None else "<none>"
        h = hashlib.sha256(binascii.unhexlify(sig)).hexdigest()
        if h in test:
            print("SELINUX CERT IS AOSP TEST KEY:", rel, seinfo, test[h]); ok = False
        elif require_ours and h not in ours:
            print("SELINUX CERT NOT OURS:", rel, seinfo); ok = False
sys.exit(0 if ok else 1)
EOF

if [ ! -f "$P/system/build.prop" ]; then
  echo "MISSING $P/system/build.prop"; bad=1
elif grep -q '^ro.build.type=user$' "$P/system/build.prop"; then
  grep -q '^ro.build.tags=release-keys$' "$P/system/build.prop" || { echo "USER BUILD NOT RELEASE-KEYS"; bad=1; }
  for pf in "$P/system/build.prop" "$P/system_ext/etc/build.prop" "$P/product/etc/build.prop" "$P/system/etc/prop.default" "$P/recovery/root/prop.default"; do
    if [ -f "$pf" ] && grep -q '^ro.debuggable=1$' "$pf"; then
      echo "USER BUILD HAS ro.debuggable=1 in $pf"; bad=1
    fi
  done
  for leak in \
    "$P/system_ext/bin/hbdebug.sh" \
    "$P/system_ext/etc/init/hbdebug.rc" \
    "$P/product/etc/security/adb_keys" \
    "$P/recovery/root/product/etc/security/adb_keys"; do
    { [ -e "$leak" ] || [ -L "$leak" ]; } && { echo "DEBUG FILE IN USER BUILD: $leak"; bad=1; }
  done
  if [ -e "$P/recovery/root/adb_keys" ] || { [ -L "$P/recovery/root/adb_keys" ] && [ "$(readlink "$P/recovery/root/adb_keys")" != "/product/etc/security/adb_keys" ]; }; then
    echo "DEBUG FILE IN USER BUILD: $P/recovery/root/adb_keys"; bad=1
  fi
  if [ -f "$P/boot.img" ]; then
    python3 - "$P/boot.img" <<'EOF' || bad=1
import gzip, lzma, struct, sys
d = open(sys.argv[1], "rb").read()
if d[:8] != b"ANDROID!":
    sys.exit(0)
ksz, _, rsz, _, _, _, _, page, ver, _ = struct.unpack_from("<10I", d, 8)
off = page + ((ksz + page - 1) // page) * page
ramdisk = d[off:off + rsz]
if ramdisk[:2] == b"\x1f\x8b":
    cpio = gzip.decompress(ramdisk)
elif ramdisk[:6] == b"\xfd7zXZ\x00" or ramdisk[:3] == b"\x5d\x00\x00":
    cpio = lzma.decompress(ramdisk)
else:
    cpio = ramdisk
pos = 0
bad = False
while pos + 110 <= len(cpio) and cpio[pos:pos + 6] in (b"070701", b"070702"):
    f = [int(cpio[pos + 6 + i * 8:pos + 14 + i * 8], 16) for i in range(13)]
    mode, fsz, nsz = f[1], f[6], f[11]
    name = cpio[pos + 110:pos + 110 + max(0, nsz - 1)].decode("utf-8", "replace")
    doff = ((pos + 110 + nsz + 3) // 4) * 4
    data = cpio[doff:doff + fsz]
    pos = ((doff + fsz + 3) // 4) * 4
    if name == "TRAILER!!!":
        while pos < len(cpio) and cpio[pos] == 0:
            pos += 1
        continue
    if name == "adb_keys" and (mode & 0o170000) == 0o120000 and data == b"/product/etc/security/adb_keys":
        continue
    if "adb_keys" in name or "hbdebug" in name:
        print("DEBUG ENTRY IN USER BOOT.IMG RAMDISK:", name)
        bad = True
sys.exit(1 if bad else 0)
EOF
  fi
else
  # Not a release (build.yml publishes user builds only): said, not skipped silently.
  echo "note: ro.build.type is $(sed -n 's/^ro.build.type=//p' "$P/system/build.prop"), not user: the user-build checks (release-keys tags, debuggable, debug files) were skipped"
fi
[ $bad = 0 ] && echo "release build OK" || echo "release build has problems"
exit $bad
