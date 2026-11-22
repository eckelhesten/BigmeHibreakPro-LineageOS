#!/usr/bin/env python3
"""Unpack an Android boot image (header v0-v2): kernel, ramdisk, dtb, and BoardConfig-ready values.

Usage: unpack_bootimg_v2.py <boot.img> <outdir>
Writes: kernel, ramdisk.cpio(.gz), dtb, ramdisk/ (extracted newc cpio), bootimg.json
"""
import gzip
import json
import lzma
import os
import struct
import sys


def align(n, page):
    return (n + page - 1) // page * page


def cstr(buf):
    return buf.split(b"\0", 1)[0].decode("utf-8", "replace")


def parse(path):
    d = open(path, "rb").read()
    if len(d) < 1660 or d[:8] != b"ANDROID!":
        sys.exit("not an Android boot image")
    (ksz, kaddr, rsz, raddr, ssz, saddr, tags, page, ver, osv) = struct.unpack_from("<10I", d, 8)
    if ver > 2:
        sys.exit(f"unsupported boot header version {ver} (only v0-v2 supported)")
    if page == 0 or (page & (page - 1)) != 0 or page > len(d):
        sys.exit(f"invalid page size {page}")
    name = cstr(d[48:64])
    cmdline = cstr(d[64:576])
    extra = cstr(d[608:1632])
    info = dict(kernel_size=ksz, kernel_addr=kaddr, ramdisk_size=rsz, ramdisk_addr=raddr,
                second_size=ssz, second_addr=saddr, tags_addr=tags, page_size=page,
                header_version=ver, name=name, cmdline=(cmdline + " " + extra).strip() if extra else cmdline)
    os_ver = osv >> 11
    info["os_version"] = f"{(os_ver >> 14) & 0x7f}.{(os_ver >> 7) & 0x7f}.{os_ver & 0x7f}"
    lvl = osv & 0x7ff
    info["os_patch_level"] = f"{2000 + (lvl >> 4)}-{lvl & 0xf:02d}"
    dtb_size = dtb_addr = 0
    if ver >= 1:
        rdtbo_size, rdtbo_off, hdr_size = struct.unpack_from("<IQI", d, 1632)
        info.update(recovery_dtbo_size=rdtbo_size, header_size=hdr_size)
    if ver == 2:
        dtb_size, dtb_addr = struct.unpack_from("<IQ", d, 1648)
        info.update(dtb_size=dtb_size, dtb_addr=dtb_addr)
    off = page
    if off + ksz > len(d):
        sys.exit("truncated boot image (kernel)")
    kernel = d[off:off + ksz]; off += align(ksz, page)
    if off + rsz > len(d):
        sys.exit("truncated boot image (ramdisk)")
    ramdisk = d[off:off + rsz]; off += align(rsz, page)
    off += align(ssz, page)
    if ver >= 1:
        off += align(info.get("recovery_dtbo_size", 0), page)
    if ver == 2 and off + dtb_size > len(d):
        sys.exit("truncated boot image (dtb)")
    dtb = d[off:off + dtb_size] if ver == 2 else b""
    # Standard mkbootimg base: kernel_addr = base + kernel_offset (0x00008000 by default).
    base = kaddr - 0x8000
    info["board"] = {
        "BOARD_KERNEL_BASE": hex(base),
        "BOARD_KERNEL_OFFSET": hex(kaddr - base),
        "BOARD_RAMDISK_OFFSET": hex(raddr - base),
        "BOARD_KERNEL_TAGS_OFFSET": hex(tags - base),
        "BOARD_DTB_OFFSET": hex(dtb_addr - base) if ver == 2 else None,
        "BOARD_KERNEL_PAGESIZE": page,
        "BOARD_BOOT_HEADER_VERSION": ver,
    }
    return info, kernel, ramdisk, dtb


def _safe_dest(root_real, rel):
    if not rel or rel.startswith("/") or "\0" in rel:
        raise ValueError(f"unsafe cpio path: {rel!r}")
    parts = [p for p in rel.split("/") if p and p != "."]
    if any(p == ".." for p in parts):
        raise ValueError(f"path traversal in cpio entry: {rel!r}")
    if not parts:
        return None
    cur = root_real
    for p in parts[:-1]:
        nxt = os.path.join(cur, p)
        if os.path.islink(nxt):
            raise ValueError(f"refusing to traverse symlink {nxt!r} for {rel!r}")
        cur = nxt
    dest = os.path.join(cur, parts[-1])
    dest_real = os.path.realpath(os.path.dirname(dest))
    if dest_real != root_real and not dest_real.startswith(root_real + os.sep):
        raise ValueError(f"cpio entry escapes outdir: {rel!r}")
    return dest


def extract_cpio(data, outdir):
    """Minimal newc/crc cpio extractor (regular files, dirs, symlinks), supporting concatenated archives."""
    root_real = os.path.realpath(outdir)
    os.makedirs(root_real, exist_ok=True)
    pos, n = 0, 0
    while pos + 110 <= len(data):
        magic = data[pos:pos + 6]
        if magic not in (b"070701", b"070702"):
            break
        f = [int(data[pos + 6 + i * 8: pos + 14 + i * 8], 16) for i in range(13)]
        mode, filesize, namesize = f[1], f[6], f[11]
        if namesize == 0 or pos + 110 + namesize > len(data):
            raise ValueError("truncated cpio entry name")
        name = data[pos + 110: pos + 110 + namesize - 1].decode("utf-8", "replace")
        pos = align(pos + 110 + namesize, 4)
        if pos + filesize > len(data):
            raise ValueError(f"truncated cpio entry body for {name!r}")
        body = data[pos:pos + filesize]
        pos = align(pos + filesize, 4)
        if name == "TRAILER!!!":
            while pos < len(data) and data[pos] == 0:
                pos += 1
            continue
        dest = _safe_dest(root_real, name)
        if dest is None:
            continue
        kind = mode & 0o170000
        if kind == 0o040000:
            if os.path.islink(dest):
                raise ValueError(f"symlink collision for directory {name!r}")
            os.makedirs(dest, exist_ok=True)
        elif kind == 0o120000:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.lexists(dest):
                os.unlink(dest)
            os.symlink(body.decode("utf-8", "replace"), dest)
        elif kind == 0o100000:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            if os.path.islink(dest):
                raise ValueError(f"refusing to write regular file through symlink {name!r}")
            with open(dest, "wb") as wf:
                wf.write(body)
        n += 1
    return n


def main():
    if len(sys.argv) != 3:
        sys.exit(f"usage: {sys.argv[0]} <boot.img> <outdir>")
    img, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    info, kernel, ramdisk, dtb = parse(img)
    with open(os.path.join(out, "kernel"), "wb") as f:
        f.write(kernel)
    with open(os.path.join(out, "dtb"), "wb") as f:
        f.write(dtb)
    if not ramdisk:
        info["ramdisk_format"] = "empty"
        info["ramdisk_files"] = 0
    else:
        if ramdisk[:2] == b"\x1f\x8b":
            with open(os.path.join(out, "ramdisk.cpio.gz"), "wb") as f:
                f.write(ramdisk)
            cpio = gzip.decompress(ramdisk); info["ramdisk_format"] = "gzip"
        elif ramdisk[:4] == b"\x02\x21\x4c\x18":
            sys.exit("lz4-legacy ramdisk: not supported by this script")
        elif ramdisk[:6] in (b"070701", b"070702"):
            cpio = ramdisk; info["ramdisk_format"] = "raw"
        else:
            cpio = lzma.decompress(ramdisk); info["ramdisk_format"] = "lzma/xz"
        with open(os.path.join(out, "ramdisk.cpio"), "wb") as f:
            f.write(cpio)
        info["ramdisk_files"] = extract_cpio(cpio, os.path.join(out, "ramdisk"))
    info["kernel_format"] = "gzip" if kernel[:2] == b"\x1f\x8b" else "raw"
    with open(os.path.join(out, "bootimg.json"), "w") as f:
        json.dump(info, f, indent=1)
    print(json.dumps(info, indent=1))


if __name__ == "__main__":
    main()
