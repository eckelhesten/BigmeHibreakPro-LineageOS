# Release keys

Builds are signed with your own keys, not AOSP's public test keys. With test keys, anyone could
sign an app the phone trusts as part of the system.

## Making the keys

Make them once, offline, and keep a backup. **If you lose them, your next build can't be
installed as an update; the phone has to be erased.**

Follow LineageOS's [Signing builds](https://wiki.lineageos.org/signing_builds) guide
(`development/tools/make_key`). The directory needs:
- the app certificates: `releasekey`, `platform`, `shared`, `media`, `networkstack`, `nfc`,
  `sdk_sandbox`, `bluetooth`, `cyngn-app`, `testkey`, `testcert`, `verity` (`.pk8` and
  `.x509.pem` each);
- for each APEX, `com.android.<name>.pem` (payload key) plus `.pk8` and `.x509.pem` (container
  certificate);
- `avb2048.pem` (an RSA-2048 key for AVB).

Never commit the keys.

## Using them

- `scripts/ci/hcloud-build.sh` installs them on the build server when `RELEASE_KEYS_DIR` points
  at the directory.
- On your own machine, `scripts/ci/install-keys.sh` shows what it does: it copies the keys to
  `vendor/lineage-priv/keys` and writes a `keys.mk` that sets the default certificate, the APEX
  keys and the certificate overrides.
- `scripts/ci/check-release-build.sh` checks the finished build for anything still signed with a
  test key.

## Don't lock the bootloader

`fastboot flashing lock` doesn't work on this phone with a custom build: it ends in a boot loop
that only mtkclient can get you out of. Keep the bootloader unlocked.
