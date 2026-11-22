#
# LineageOS product for the Bigme HiBreak Pro
#

# Bring-up: keep userdebug builds ro.debuggable=1. LineageOS sets PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG
# in config/common.mk, which makes adbd refuse every service ("error: closed") while the framework
# or display isn't up. It can't be reset to false from here (add_json_bool: any non-empty value is
# true), so patches/vendor/lineage adds this opt-out. A plain variable, not a PRODUCT_ one: this file
# is evaluated before the makefiles it inherits. Release builds are `user` and unaffected.
LINEAGE_DEBUGGABLE_USERDEBUG := true

$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, device/bigme/hibreakpro/device.mk)
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_NAME := lineage_hibreakpro
PRODUCT_DEVICE := hibreakpro
PRODUCT_BRAND := Bigme
# Must match stock ro.product.*.model exactly: the Microtrust TEE license is bound to
# "<manufacturer> <model> <platform>". With "HiBreak Pro" the TEE reports "not verified",
# keymaster fails with SECURE_HW_COMMUNICATION_FAILED and /data can't be encrypted.
PRODUCT_MODEL := HiBreak
PRODUCT_MANUFACTURER := Bigme

PRODUCT_GMS_CLIENTID_BASE := android-bigme

# Keep the stock identity for apps/services that key on it
PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="HiBreak_EEA-user 14 UP1A.231005.007 1773045151 release-keys" \
    DeviceProduct=HiBreak_EEA \
    DeviceName=Smartphone

BUILD_FINGERPRINT := Bigme/HiBreak_EEA/Smartphone:14/UP1A.231005.007/1773045151:user/release-keys
