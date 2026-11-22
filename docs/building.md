# Building

## What you need

- **Bigme's proprietary files** in `vendor/bigme/hibreakpro`, taken from your own phone running
  stock firmware `Bigme_HiBreak_EEA_V1.0_20260312`. They are never published:
  - `prebuilt/`: `kernel`, `dtb`, `dtbo.img` and the stock `vendor.img`;
  - `proprietary/`: the blobs for `system` and `system_ext`;
  - `hibreakpro-vendor.mk`.
- **Your own release keys**: see [signing.md](signing.md). The build refuses to run without them.
- **A big Linux machine:** about 300 GB of disk and 32 GB of RAM plus 32 GB of swap (Soong peaks
  at about 47 GB). Or the cloud script below.

## What's in this repository

- `device/bigme/hibreakpro`: the device tree, HiBreakParts (the settings app for e-ink, front
  light, sleep screen and side key), overlays and SELinux policy.
- `patches/<path in the LineageOS tree>/*.patch`: changes to LineageOS projects, applied with
  `scripts/ci/apply-patches.sh <tree> patches` after `repo sync`.
- `manifests/`: local manifests, copied into `.repo/local_manifests/`.
- `scripts/`: the build pipeline (`scripts/build/`, `scripts/ci/`) and the release packaging
  (`scripts/release/`).

## Build on a cloud server

`scripts/ci/hcloud-build.sh` creates a Hetzner Cloud server (CPX62), syncs LineageOS 23.2,
applies the patches, builds, copies the results to `out/` and deletes the server again. It needs
the `hcloud` CLI with an active context.

```bash
BUILD_TYPE=user HIBREAK_FLAVORS="vanilla microg gapps" RELEASE_KEYS_DIR=~/my-release-keys \
  scripts/ci/hcloud-build.sh
```

A full build takes about 2.5 hours; each further flavour about 30 minutes. Other options
(`MAX_HOURS`, `CCACHE_STORE` to keep the compiler cache between servers, ...) are listed at the
top of the script.

## Build on your own machine

The steps are the ones `scripts/build/buildserver-pipeline.sh` runs:
1. `repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs`, copy
   `manifests/*.xml` into `.repo/local_manifests/`, `repo sync`.
2. Put `device/bigme` and `vendor/bigme` into the tree.
3. `scripts/ci/apply-patches.sh <tree> patches`.
4. Install your keys (`scripts/ci/install-keys.sh` shows what goes where).
5. `source build/envsetup.sh && breakfast hibreakpro user && m bacon`.

## Flavours

`HIBREAK_FLAVOR` picks the flavour at build time (`device.mk`):
- `microg` (default): microG Services and Companion, the official builds signed by microG
  (`scripts/ci/fetch-microg.sh`);
- `vanilla`: no Google services;
- `gapps`: MindTheGapps, pinned in `manifests/flavors/gapps.xml`.

Run `m installclean` before switching flavour in the same tree, or apps from the previous one end
up in the images (`scripts/build/buildserver-build.sh` does this for you).

## Checks and release packages

- `scripts/ci/check-release-build.sh` checks that every APK, APEX and SELinux certificate uses
  your keys.
- `scripts/ci/check-flavours.sh` checks each flavour's images and update zip.
- `scripts/release/package.sh` turns a flavour's images into the install zip with `flash.sh` and
  `INSTALL.txt`.
