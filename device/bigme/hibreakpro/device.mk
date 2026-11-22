#
# Bigme HiBreak Pro: product configuration (bring-up stage 1)
#
DEVICE_PATH := device/bigme/hibreakpro

# 64-bit with 32-bit app support (stock abilist: arm64-v8a, armeabi-v7a, armeabi)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)

# Dynamic partitions (super): also makes build_image size system/system_ext/product automatically
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Virtual A/B without compression: updates use the kernel's dm-snapshot. Compressed VAB needs
# snapuserd handed over to its SELinux domain while the kernel is still permissive, but Bigme's
# 4.19 kernel enforces from the first policy load, so the first boot after an update failed in
# first-stage init (2026-11-16, docs/signing.md "Updates"). The stock vendor still declares
# ro.virtual_ab.compression.enabled; the update package decides, and ours says no compression.
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota/launch.mk)

# Launched with Android 11 vendor
PRODUCT_SHIPPING_API_LEVEL := 30

# The stock vendor was built against VNDK 31: keep the matching VNDK APEX on system
PRODUCT_EXTRA_VNDK_VERSIONS := 31

# Display: native portrait e-ink panel (824x1648, physical rotation 270 handled in overlays/system.prop)
TARGET_SCREEN_WIDTH := 824
TARGET_SCREEN_HEIGHT := 1648

# A/B
# E-ink overlay packages and customizations
PRODUCT_PACKAGES += \
    FrameworksResHiBreakPro \
    SettingsProviderHiBreakPro \
    SystemUIHiBreakPro \
    SettingsHiBreakPro \
    ApertureHiBreakPro \
    LineageSdkHiBreakPro \
    LatinIMEHiBreakPro

# Device parts: front light (brightness -> LM3630A warm/cold) and e-ink quick settings tiles
PRODUCT_PACKAGES += HiBreakParts

# System font: Noto Sans 450 (the font configuration is patched in external/roboto-fonts,
# frameworks/base and packages/overlays/Lineage)
PRODUCT_PACKAGES += \
    NotoSans-VF.ttf \
    NotoSans-Italic-VF.ttf \
    NotoSans-VF.ttf.product \
    NotoSans-Italic-VF.ttf.product

# Google services flavour (docs/building.md "Flavours"), from the environment at build time:
#   vanilla  no Google services at all
#   microg   microG (official release, signed by microG): the owner's daily driver, the default
#   gapps    MindTheGapps (Google Play services and Play Store)
# The flavour goes into the build name (lineage-23.2-<date>-UNOFFICIAL-<flavour>-hibreakpro).
# There is no update server: releases are downloaded from GitHub and installed with Updater >
# "Install local update" (docs/building.md "Releases").
ifeq ($(strip $(HIBREAK_FLAVOR)),)
HIBREAK_FLAVOR := microg
endif
ifeq ($(filter vanilla microg gapps,$(HIBREAK_FLAVOR)),)
$(error HIBREAK_FLAVOR must be vanilla, microg or gapps, not "$(HIBREAK_FLAVOR)")
endif
TARGET_UNOFFICIAL_BUILD_ID := $(HIBREAK_FLAVOR)
PRODUCT_PRODUCT_PROPERTIES += ro.hibreak.flavor=$(HIBREAK_FLAVOR)

# Mobile data on by default, as on the stock firmware. LineageOS turns it off
# (vendor/lineage config/telephony.mk, made overridable by patches/vendor/lineage 0002), and
# SystemUI then draws an exclamation mark on the signal icon until the user finds the switch.
PRODUCT_PRODUCT_PROPERTIES += ro.com.android.mobiledata=true

# ro.hibreak.microg lets LineageOS's restricted signature spoofing (only microG Services and
# Companion, only signed with microG's own certificate) work on user builds too; frameworks/base
# patch 0013. On in vanilla as well, so microG can be installed later; pointless with real GApps.
ifneq ($(HIBREAK_FLAVOR),gapps)
PRODUCT_SYSTEM_PROPERTIES += ro.hibreak.microg=true
endif

ifeq ($(HIBREAK_FLAVOR),microg)
# GmsCore and Companion as privileged apps on /product. The modules come from vendor/microg (its
# own Soong namespace, so MindTheGapps' GmsCore can sit in the same tree), which
# scripts/ci/fetch-microg.sh creates on the build server.
PRODUCT_SOONG_NAMESPACES += vendor/microg
PRODUCT_PACKAGES += GmsCore MicrogCompanion
# microG's defaults: registration, push and device attestation on (configs/microg/microg.xml).
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/microg/microg.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/microg.xml
endif

ifeq ($(HIBREAK_FLAVOR),gapps)
# MindTheGapps (Android 16, branch baklava), synced into vendor/gapps by the build server only
# for this flavour (manifests/flavors/gapps.xml).
ifeq ($(wildcard vendor/gapps/arm64/arm64-vendor.mk),)
$(error HIBREAK_FLAVOR=gapps needs MindTheGapps in vendor/gapps (manifests/flavors/gapps.xml))
endif
$(call inherit-product, vendor/gapps/arm64/arm64-vendor.mk)
endif

PRODUCT_PACKAGES += \
    update_engine \
    update_engine_sideload \
    update_verifier \
    checkpoint_gc \
    otapreopt_script

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_system=true \
    POSTINSTALL_PATH_system=system/bin/otapreopt_script \
    FILESYSTEM_TYPE_system=ext4 \
    POSTINSTALL_OPTIONAL_system=true

# VoLTE: MediaTek's IMS service from the stock firmware (docs/volte.md). The APK comes from
# the private vendor tree, built from the stock system_ext by tools/ims/extract-ims.py; without
# it there are no voice calls on LTE-only networks, so its absence is an error, not a silent skip.
ifeq ($(wildcard vendor/bigme/hibreakpro/proprietary/system_ext/priv-app/ImsService/Android.bp),)
$(error MediaTek IMS service missing from vendor/bigme/hibreakpro: run device/bigme/hibreakpro/tools/ims/extract-ims.py (docs/volte.md))
endif
PRODUCT_PACKAGES += \
    MtkImsService \
    TelephonyHiBreakPro \
    CarrierConfigHiBreakPro
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/configs/permissions/privapp-permissions-com.mediatek.ims.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-com.mediatek.ims.xml \
    $(DEVICE_PATH)/configs/sysconfig/com.mediatek.ims.config.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/sysconfig/com.mediatek.ims.config.xml \
    $(DEVICE_PATH)/rootdir/etc/init.hibreakpro.ims.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.hibreakpro.ims.rc

# Fastbootd (userspace fastboot for flashing logical partitions)
PRODUCT_PACKAGES += \
    fastbootd \
    android.hardware.fastboot@1.1-impl-mock

# fstab: first-stage mount. With recovery-as-boot (boot v2) boot.img carries the RECOVERY ramdisk,
# and first-stage init reads /first_stage_ramdisk/fstab.<hw> when androidboot.force_normal_boot=1.
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/fstab.mt6877:$(TARGET_COPY_OUT_RECOVERY)/root/first_stage_ramdisk/fstab.mt6877 \
    $(DEVICE_PATH)/rootdir/etc/init.recovery.mt6877.rc:$(TARGET_COPY_OUT_RECOVERY)/root/init.recovery.mt6877.rc

# E-ink: system-side pieces the stock vendor composer expects (docs/eink-interface.md)
PRODUCT_COPY_FILES += \
    $(DEVICE_PATH)/rootdir/etc/init.hibreakpro.eink.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.hibreakpro.eink.rc

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# Proprietary files
$(call inherit-product, vendor/bigme/hibreakpro/hibreakpro-vendor.mk)

# Debug builds only: opt-in adb public keys via HIBREAK_ADB_KEYS (kept out of git), so
# unattended adb can work in Android and recovery without unlocking or tapping.
ifneq (,$(filter eng userdebug,$(TARGET_BUILD_VARIANT)))
HIBREAK_ADB_KEYS ?= vendor/lineage-priv/debug/adb_keys
ifneq ($(wildcard $(HIBREAK_ADB_KEYS)),)
PRODUCT_ADB_KEYS := $(HIBREAK_ADB_KEYS)
# Recovery: the build turns the ramdisk's /adb_keys into a symlink to /product/etc/security/adb_keys
# (a copy to root/adb_keys loses to it), and recovery doesn't mount /product. So put the file at the
# symlink's target inside the recovery ramdisk.
PRODUCT_COPY_FILES += $(HIBREAK_ADB_KEYS):$(TARGET_COPY_OUT_RECOVERY)/root/product/etc/security/adb_keys
endif
# Every boot leaves logs in /metadata/hbdebug, readable from recovery (debug/hbdebug.sh).
PRODUCT_PACKAGES += hbdebug.sh
endif
