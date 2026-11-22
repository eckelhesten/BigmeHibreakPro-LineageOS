#!/usr/bin/env bash
# First install of LineageOS for the Bigme HiBreak Pro, from this folder's images.
# Needs: adb and fastboot (Android platform tools), the bootloader unlocked, stock firmware
# Bigme_HiBreak_EEA_V1.0_20260312 running on slot A with USB debugging on.
# WIPES THE PHONE. Stops at the first error and leaves the phone in fastboot, where every step
# can be repeated.
set -euo pipefail
cd "$(dirname "$0")"

die() { echo "ERROR: $*" >&2; exit 1; }
# fastboot waits for the phone by itself; prints "yes" in fastbootd, "no" in the bootloader
userspace() {
  local v; v=$(fastboot getvar is-userspace 2>&1 || true)
  if grep -qiE 'is-userspace:[[:space:]]*yes' <<<"$v"; then echo yes; else echo no; fi
}
step() { echo; echo "==> $*"; }

command -v adb >/dev/null || die "adb not found: install Android platform tools"
command -v fastboot >/dev/null || die "fastboot not found: install Android platform tools"

step "Checking the images"
if command -v sha256sum >/dev/null; then sha256sum -c SHA256SUMS; else shasum -a 256 -c SHA256SUMS; fi

step "Checking the phone"
[ "$(adb get-state 2>/dev/null)" = device ] || die "no phone over adb: connect it, turn on USB debugging and accept the prompt"
slot=$(adb shell getprop ro.boot.slot_suffix | tr -d '\r')
[ "$slot" = _a ] || die "the phone runs slot '${slot}', not _a: Bigme's fastbootd only works from slot A (see the README)"
echo "phone on slot A"

echo
echo "This ERASES everything on the phone and installs LineageOS."
read -r -p "Type YES to continue: " answer
[ "$answer" = YES ] || die "cancelled"

step "Rebooting to Bigme's fastbootd"
adb reboot fastboot
[ "$(userspace)" = yes ] || die "not in fastbootd (userspace fastboot)"

step "Flashing system, system_ext, product"
fastboot flash system system.img
fastboot flash system_ext system_ext.img
fastboot flash product product.img

step "Rebooting to the bootloader"
fastboot reboot bootloader
sleep 8
[ "$(userspace)" = no ] || die "still in fastbootd, expected the bootloader"

step "Flashing boot, dtbo, vbmeta"
fastboot flash boot_a boot.img
fastboot flash dtbo_a dtbo.img
fastboot flash vbmeta_a vbmeta.img
fastboot flash vbmeta_system_a vbmeta_system.img

step "Wiping data"
fastboot -w
fastboot format:ext4 metadata

step "Rebooting into LineageOS (the first boot takes a few minutes)"
fastboot reboot
