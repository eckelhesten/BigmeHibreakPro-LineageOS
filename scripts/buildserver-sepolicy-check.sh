#!/bin/bash
# Runs ON the build server after a build: compile the split SELinux policy exactly like init does
# on first boot (our system/system_ext/product policy + mapping + the prebuilt vendor's policy).
set -uo pipefail
cd /srv/lineage || exit 1
P=out/target/product/hibreakpro
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
rc=0
for f in vendor_sepolicy.cil plat_pub_versioned.cil vndk_sepolicy_version; do
  debugfs -R "dump /etc/selinux/$f $T/$f" "$P/vendor.img" >/dev/null 2>&1 || true
done
[ -s "$T/vendor_sepolicy.cil" ] && [ -s "$T/plat_pub_versioned.cil" ] || { echo "SEPOLICY_COMPILE=FAILED (missing vendor cil dumps)"; exit 1; }
V=$([ -f "$T/vndk_sepolicy_version" ] && tr -d '\n' < "$T/vndk_sepolicy_version" || true); V=${V:-31.0}
echo "vendor policy version: $V; extracted: $(ls "$T" | tr '\n' ' ')"
CILS=("$P/system/etc/selinux/plat_sepolicy.cil" "$P/system/etc/selinux/mapping/$V.cil")
[ -f "$P/system/etc/selinux/mapping/$V.compat.cil" ] && CILS+=("$P/system/etc/selinux/mapping/$V.compat.cil")
for x in system_ext product; do
  for f in "$P/$x/etc/selinux/${x}_sepolicy.cil" "$P/$x/etc/selinux/mapping/$V.cil"; do
    [ -f "$f" ] && CILS+=("$f")
  done
done
CILS+=("$T/plat_pub_versioned.cil" "$T/vendor_sepolicy.cil")
echo "compiling ${#CILS[@]} cil files"
if out/host/linux-x86/bin/secilc -m -M true -G -N -c 30 "${CILS[@]}" -o "$T/policy" -f /dev/null > "$T/secilc.log" 2>&1; then
  echo "SEPOLICY_COMPILE=ok ($(stat -c %s "$T/policy") bytes)"
else
  echo "SEPOLICY_COMPILE=FAILED"; tail -15 "$T/secilc.log"; rc=1
fi
# The XRZ e-ink types must reach the vendor composer through the 31.0 attributes (sepolicy/private/xrz.te)
for a in vendor_xrz_prop xrz_debugfs xrz_system_file; do
  if grep -q "(typeattributeset ${a}_31_0 (.*\b${a}\b" "$P/system_ext/etc/selinux/system_ext_sepolicy.cil"; then
    echo "XRZ_MAP ${a}_31_0=ok"
  else
    echo "XRZ_MAP ${a}_31_0=MISSING"
    rc=1
  fi
done
grep -h "xrz" "$P/system_ext/etc/selinux/system_ext_property_contexts" "$P/system_ext/etc/selinux/system_ext_file_contexts" 2>/dev/null || true
exit "$rc"
