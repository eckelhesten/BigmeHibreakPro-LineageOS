#
# Bigme HiBreak Pro (B/W): MediaTek MT6877, kernel 4.19 (prebuilt), stock vendor (FCM 5 / VNDK 31)
# Bring-up stage 1: prebuilt kernel/DTB/DTBO + prebuilt stock vendor image. See PLAN.md Phase 3.
#
DEVICE_PATH := device/bigme/hibreakpro
VENDOR_PATH := vendor/bigme/hibreakpro

# Architecture (MT6877: 2x Cortex-A78 + 6x Cortex-A55)
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-2a-dotprod
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := cortex-a76

TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-2a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := generic
TARGET_2ND_CPU_VARIANT_RUNTIME := cortex-a55

# Platform
TARGET_BOARD_PLATFORM := mt6877
TARGET_BOOTLOADER_BOARD_NAME := k6877v1_64_k419
TARGET_NO_BOOTLOADER := true
TARGET_NO_RADIOIMAGE := true

# A/B + Virtual A/B
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS := \
    boot \
    dtbo \
    product \
    system \
    system_ext \
    vbmeta \
    vbmeta_system \
    vbmeta_vendor \
    vendor

# Kernel (prebuilt from stock boot_a; no source released by Bigme yet)
TARGET_FORCE_PREBUILT_KERNEL := true
TARGET_PREBUILT_KERNEL := $(VENDOR_PATH)/prebuilt/kernel
BOARD_PREBUILT_DTBIMAGE_DIR := $(VENDOR_PATH)/prebuilt/dtb
BOARD_PREBUILT_DTBOIMAGE := $(VENDOR_PATH)/prebuilt/dtbo.img
BOARD_INCLUDE_DTB_IN_BOOTIMG := true

BOARD_BOOT_HEADER_VERSION := 2
BOARD_KERNEL_BASE := 0x40078000
BOARD_KERNEL_PAGESIZE := 2048
BOARD_KERNEL_OFFSET := 0x00008000
BOARD_RAMDISK_OFFSET := 0x11088000
BOARD_KERNEL_TAGS_OFFSET := 0x07c08000
BOARD_DTB_OFFSET := 0x07c08000
BOARD_KERNEL_IMAGE_NAME := Image.gz

# No androidboot.selinux=permissive: the stock kernel has no CONFIG_SECURITY_SELINUX_DEVELOP, so
# init's security_setenforce(0) fails with EINVAL and is fatal (in both Android and recovery).
# SELinux is always enforcing here; per-domain `permissive` in sepolicy is the only debug escape.
BOARD_KERNEL_CMDLINE := bootopt=64S3,32N2,64N2
BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --kernel_offset $(BOARD_KERNEL_OFFSET)
BOARD_MKBOOTIMG_ARGS += --ramdisk_offset $(BOARD_RAMDISK_OFFSET)
BOARD_MKBOOTIMG_ARGS += --tags_offset $(BOARD_KERNEL_TAGS_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb_offset $(BOARD_DTB_OFFSET)

# Kernel has CONFIG_RD_GZIP only (no LZ4), no EROFS
BOARD_RAMDISK_USE_LZ4 := false

# Partitions (sizes from the stock GPT / lpdump)
BOARD_FLASH_BLOCK_SIZE := 131072
BOARD_BOOTIMAGE_PARTITION_SIZE := 41943040
BOARD_DTBOIMG_PARTITION_SIZE := 8388608

BOARD_SUPER_PARTITION_SIZE := 12884901888
BOARD_SUPER_PARTITION_GROUPS := main
# Virtual A/B: one group; leave 4 MiB for metadata
BOARD_MAIN_SIZE := 12880707584
BOARD_MAIN_PARTITION_LIST := system system_ext product vendor

BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs

# /metadata (metadata encryption keys, OTA snapshot state): first-stage init moves its mount
# into /system/metadata, so the system image needs that mount point (boot fatal without it).
BOARD_USES_METADATA_PARTITION := true

TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_VENDOR := vendor
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true

# Stock vendor image (prebuilt, byte-identical to device vendor_a)
BOARD_PREBUILT_VENDORIMAGE := $(VENDOR_PATH)/prebuilt/vendor.img

# Recovery: boot image v2 carries the recovery ramdisk
BOARD_USES_RECOVERY_AS_BOOT := true
TARGET_NO_RECOVERY := true
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/rootdir/etc/fstab.mt6877
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888
TARGET_RECOVERY_DEFAULT_ROTATION := ROTATION_LEFT

# AVB: verified boot with our own key (docs/signing.md), verification on. The stock LK only
# handles RSA-2048 keys. The whole chain is ours: vbmeta (boot, dtbo), vbmeta_system (system,
# system_ext, product) and vbmeta_vendor (the unchanged stock vendor image, whose own hashtree
# footer is reused). A/B OTAs write all of them; by hand, the vbmeta images and dtbo are flashed
# from LK fastboot (fastbootd can't).
AVB_KEY := vendor/lineage-priv/keys/avb2048.pem
BOARD_AVB_ENABLE := true
BOARD_AVB_KEY_PATH := $(AVB_KEY)
BOARD_AVB_ALGORITHM := SHA256_RSA2048
BOARD_AVB_ROLLBACK_INDEX := $(PLATFORM_SECURITY_PATCH_TIMESTAMP)
BOARD_AVB_VBMETA_SYSTEM := system system_ext product
BOARD_AVB_VBMETA_SYSTEM_KEY_PATH := $(AVB_KEY)
BOARD_AVB_VBMETA_SYSTEM_ALGORITHM := SHA256_RSA2048
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX := $(PLATFORM_SECURITY_PATCH_TIMESTAMP)
BOARD_AVB_VBMETA_SYSTEM_ROLLBACK_INDEX_LOCATION := 1
BOARD_AVB_VBMETA_VENDOR := vendor
BOARD_AVB_VBMETA_VENDOR_KEY_PATH := $(AVB_KEY)
BOARD_AVB_VBMETA_VENDOR_ALGORITHM := SHA256_RSA2048
BOARD_AVB_VBMETA_VENDOR_ROLLBACK_INDEX := $(PLATFORM_SECURITY_PATCH_TIMESTAMP)
BOARD_AVB_VBMETA_VENDOR_ROLLBACK_INDEX_LOCATION := 2

# Treble / VINTF: vendor is FCM level 5 (Android 11), VNDK 31
BOARD_SHIPPING_API_LEVEL := 30
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += $(DEVICE_PATH)/configs/vintf/framework_compatibility_matrix.xml

# SELinux: XRZ e-ink types the stock vendor policy expects (sepolicy/private/xrz.te)
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/private
# Debug builds only: bring-up log collector and SF e-ink engine policy (never on user builds)
ifneq (,$(filter eng userdebug,$(TARGET_BUILD_VARIANT)))
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/debug
endif

# Properties
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/configs/props/system.prop

# Inherit proprietary-side board config
-include $(VENDOR_PATH)/BoardConfigVendor.mk
