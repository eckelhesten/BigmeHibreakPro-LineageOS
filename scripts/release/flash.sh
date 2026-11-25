#!/usr/bin/env bash
# First install of LineageOS for the Bigme HiBreak Pro (B/W EEA), from this folder's images.
# Needs: adb and fastboot (Android platform tools), the bootloader unlocked, and stock firmware
# Bigme_HiBreak_EEA_V1.0_20260312 (slot A or slot B; also works when switching LineageOS flavours).
#
# WIPES THE PHONE. Stops at the first error and leaves the phone in fastboot; if anything fails,
# DO NOT REBOOT - fix the cable/port and re-run `bash flash.sh` to resume safely.
set -euo pipefail
cd "$(dirname "$0")"

EXPECTED_VENDOR_FP="Bigme/HiBreak_EEA/Smartphone:12/SP1A.210812.016/1773045151:user/release-keys"
FLASHING=0

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

on_exit() {
  local rc=$?
  if [ "$rc" -ne 0 ] && [ "$FLASHING" = 1 ]; then
    cat >&2 <<'WARN'

========================================================================
FLASHING STOPPED PARTWAY THROUGH - DO NOT REBOOT THE PHONE!
Rebooting with half-flashed partitions can trigger a dm-verity bootloop.
Leave the phone in fastboot mode, check/swap your USB cable (use a rear
port directly on the computer, not a hub), and run `bash flash.sh` again
to resume safely.
========================================================================
WARN
  fi
  exit "$rc"
}
trap on_exit EXIT

fb_var() {
  local name=$1 out
  out=$(fastboot getvar "$name" 2>&1 || true)
  printf '%s\n' "$out" | sed -n "s/^${name}:[[:space:]]*//p" | head -1 | tr -d '\r'
}

fb_connected() {
  fastboot devices 2>/dev/null | grep -q .
}

# Exactly one phone, over adb and fastboot together: with two, every command could go to either.
one_device() {
  local n
  n=$( { adb devices 2>/dev/null | sed 1d | grep -E '[[:space:]](device|recovery|sideload|unauthorized)$' || true
         fastboot devices 2>/dev/null || true; } | grep -c . || true)
  [ "${n:-0}" -le 1 ] || die "${n} phones connected: connect only the one to flash"
}

fb_userspace() {
  local v
  v=$(fb_var is-userspace)
  case "$v" in
    yes|YES|Yes) echo yes ;;
    *) echo no ;;
  esac
}

fb_slot() {
  local s
  s=$(fb_var current-slot)
  s=${s#_}
  case "$s" in
    a|b) echo "$s" ;;
    *) echo "" ;;
  esac
}

wait_fastboot_mode() {
  local want=$1 i
  for i in $(seq 1 60); do
    if fb_connected; then
      if [ "$(fb_userspace)" = "$want" ]; then
        # "no" is also what a failed getvar gives (fastbootd going down on its way to the
        # bootloader): the bootloader is there once it answers for its slot too.
        if [ "$want" = yes ] || [ -n "$(fb_slot)" ]; then
          return 0
        fi
      fi
    fi
    sleep 1
  done
  if [ "$want" = yes ]; then
    die "timed out waiting for fastbootd (userspace fastboot)"
  else
    die "timed out waiting for the bootloader (LK fastboot)"
  fi
}

command -v adb >/dev/null || die "adb not found: install Android platform tools"
command -v fastboot >/dev/null || die "fastboot not found: install Android platform tools"

step "Checking the images"
[ -s SHA256SUMS ] || die "SHA256SUMS is missing or empty"
if command -v sha256sum >/dev/null; then sha256sum -c SHA256SUMS; else shasum -a 256 -c SHA256SUMS; fi

step "Detecting the phone"
one_device
mode=""
if [ "$(adb get-state 2>/dev/null || true)" = device ]; then
  mode=adb
elif fb_connected; then
  if [ "$(fb_userspace)" = yes ]; then
    mode=fastbootd
  else
    mode=bootloader
  fi
else
  die "no phone found over adb or fastboot: connect it with a good cable (rear USB port recommended), enable USB debugging, and accept the prompt"
fi

slot=""
if [ "$mode" = adb ]; then
  hw=$(adb shell getprop ro.boot.hardware </dev/null | tr -d '\r')
  [ "$hw" = mt6877 ] || die "unexpected hardware '${hw}', expected 'mt6877' (Bigme HiBreak Pro)"

  vfp=$(adb shell getprop ro.vendor.build.fingerprint </dev/null | tr -d '\r')
  if [ "$vfp" != "$EXPECTED_VENDOR_FP" ]; then
    die "unexpected vendor firmware '${vfp}'. This ROM requires the black-and-white EEA HiBreak Pro running stock Bigme_HiBreak_EEA_V1.0_20260312 (not Color, CN, or other versions)."
  fi

  locked=$(adb shell getprop ro.boot.flash.locked </dev/null | tr -d '\r')
  vstate=$(adb shell getprop ro.boot.vbmeta.device_state </dev/null | tr -d '\r')
  if [ "$locked" != 0 ] && [ "$vstate" != unlocked ]; then
    die "bootloader is locked (ro.boot.flash.locked='${locked}', device_state='${vstate}'). Unlock it first: adb reboot bootloader && fastboot flashing unlock"
  fi

  raw_slot=$(adb shell getprop ro.boot.slot_suffix </dev/null | tr -d '\r')
  slot=${raw_slot#_}
  case "$slot" in
    a|b) ;;
    *) die "unexpected active slot '${raw_slot}'" ;;
  esac
  echo "phone ready over adb: Bigme HiBreak Pro (20260312 vendor), unlocked, slot ${slot}"
else
  slot=$(fb_slot)
  [ -n "$slot" ] || die "could not read current-slot from fastboot"
  if [ "$mode" = bootloader ]; then
    unlocked=$(fb_var unlocked)
    case "$unlocked" in
      yes|YES|Yes) ;;
      *) die "bootloader reports unlocked='${unlocked}' (must be unlocked before flashing)" ;;
    esac
  fi
  echo "phone detected in ${mode} mode on slot ${slot} (resuming install)"
fi

echo
echo "Tip: use a known-good USB cable connected directly to a rear port on your computer."
echo "This ERASES everything on the phone and installs LineageOS onto slot ${slot}."
read -r -p "Type YES to continue: " answer
[ "$answer" = YES ] || die "cancelled"

FLASHING=1

if [ "$mode" = adb ]; then
  step "Rebooting to the bootloader"
  adb reboot bootloader
  wait_fastboot_mode no
  mode=bootloader
fi

if [ "$mode" = bootloader ]; then
  slot=$(fb_slot)
  [ -n "$slot" ] || die "could not read current-slot in bootloader"
  unlocked=$(fb_var unlocked)
  case "$unlocked" in
    yes|YES|Yes) ;;
    *) die "bootloader reports unlocked='${unlocked}'" ;;
  esac

  step "Flashing LineageOS recovery (boot_${slot}) and entering fastbootd"
  fastboot flash "boot_${slot}" boot.img
  fastboot reboot fastboot
  wait_fastboot_mode yes
  mode=fastbootd
fi

if [ "$mode" = fastbootd ]; then
  slot=$(fb_slot)
  [ -n "$slot" ] || die "could not read current-slot in fastbootd"

  snap=$(fb_var snapshot-update-status)
  if [ -n "$snap" ] && [ "$snap" != none ]; then
    step "Cancelling pending Virtual A/B snapshot (${snap})"
    fastboot snapshot-update cancel
  fi

  step "Flashing system, system_ext, product (slot ${slot})"
  fastboot flash "system_${slot}" system.img
  fastboot flash "system_ext_${slot}" system_ext.img
  fastboot flash "product_${slot}" product.img

  step "Rebooting to the bootloader"
  fastboot reboot bootloader
  wait_fastboot_mode no
fi

slot=$(fb_slot)
[ -n "$slot" ] || die "could not read current-slot in bootloader"

step "Flashing boot, dtbo, vbmeta (slot ${slot})"
fastboot flash "boot_${slot}" boot.img
fastboot flash "dtbo_${slot}" dtbo.img
fastboot flash "vbmeta_${slot}" vbmeta.img
fastboot flash "vbmeta_system_${slot}" vbmeta_system.img
fastboot flash "vbmeta_vendor_${slot}" vbmeta_vendor.img

step "Wiping data and metadata"
fastboot -w
fastboot format:ext4 metadata

FLASHING=0
step "Rebooting into LineageOS (the first boot takes a few minutes)"
fastboot reboot
