#!/usr/bin/env bash
# Implemented in PLAN.md Phase 2 once the xrz_updater protocol is known:
#  1. query "$1" with the device's model/version parameters
#  2. if the version differs from "$2": download, verify, extract (payload.bin -> images)
#  3. run device/bigme/hibreakpro/extract-files.py against the images, diff, open a PR
echo "check-firmware.sh: not implemented yet (needs the OTA protocol from xrz_updater RE)" >&2
exit 1
