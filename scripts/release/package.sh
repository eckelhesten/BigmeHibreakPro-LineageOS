#!/usr/bin/env bash
# One install package per flavour for a GitHub release:
#   lineage-23.2-hibreakpro-<version>-<flavour>.zip
#     lineage-23.2-hibreakpro-<version>-<flavour>/{boot,dtbo,vbmeta,vbmeta_system,system,
#       system_ext,product}.img, SHA256SUMS, flash.sh, INSTALL.txt
# Usage: scripts/release/package.sh <version> <flavour> <dir with <flavour>-<image>.img> <out dir>
set -euo pipefail
V=$1; F=$2; SRC=$3; OUT=$4
here=$(cd "$(dirname "$0")" && pwd)
sha() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
name=lineage-23.2-hibreakpro-$V-$F
stage=$OUT/stage/$name
rm -rf "$stage"; mkdir -p "$stage"
for i in boot dtbo vbmeta vbmeta_system system system_ext product; do
  [ -f "$SRC/$F-$i.img" ] || { echo "missing $SRC/$F-$i.img"; exit 1; }
  ln -f "$SRC/$F-$i.img" "$stage/$i.img" 2>/dev/null || cp "$SRC/$F-$i.img" "$stage/$i.img"
done
cp "$here/flash.sh" "$here/INSTALL.txt" "$stage/"
chmod +x "$stage/flash.sh"
(cd "$stage" && sha boot.img dtbo.img vbmeta.img vbmeta_system.img system.img system_ext.img product.img > SHA256SUMS)
rm -f "$OUT/$name.zip"
(cd "$OUT/stage" && zip -q -6 -r "$OUT/$name.zip" "$name")
# wc -c, not stat: GNU `stat -f` is filesystem status (exit 0, no size), BSD has no `stat -c`.
size=$(wc -c < "$OUT/$name.zip" | tr -d ' ')
[ "$size" -lt 2147483648 ] || { echo "$name.zip is $size bytes: over GitHub's 2 GiB limit"; exit 1; }
(cd "$OUT" && sha "$name.zip")
