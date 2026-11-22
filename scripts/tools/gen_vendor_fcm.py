#!/usr/bin/env python3
"""Generate a device framework compatibility matrix covering every non-AOSP HAL declared by
the stock vendor VINTF manifests, so check_vintf accepts the prebuilt vendor.
Usage: gen_vendor_fcm.py <vintf-dir> > framework_compatibility_matrix.xml"""
import glob, os, sys, xml.etree.ElementTree as ET
from collections import defaultdict
from xml.sax.saxutils import escape

if len(sys.argv) != 2:
    sys.exit(f"usage: {sys.argv[0]} <vintf-dir>")

xml_files = sorted(glob.glob(os.path.join(sys.argv[1], "**", "*.xml"), recursive=True))
if not xml_files:
    sys.exit(f"no XML files found in {sys.argv[1]}")

hals = defaultdict(lambda: {"versions": set(), "ifaces": defaultdict(set), "regex_ifaces": defaultdict(set)})
manifests = 0
for f in xml_files:
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    if root.tag != "manifest":
        continue
    manifests += 1
    for h in root.findall("hal"):
        name = (h.findtext("name") or "").strip()
        if not name or name.startswith("android."):
            continue
        fmt = (h.get("format") or "hidl").strip()
        e = hals[(name, fmt)]
        for fq in h.findall("fqname"):  # @1.0::IFoo/default or AIDL IFoo/default
            t = (fq.text or "").strip()
            if not t:
                continue
            if "::" in t:
                ver_part, rest = t.split("::", 1)
                ver = ver_part.lstrip("@")
                if ver:
                    e["versions"].add(ver)
            else:
                rest = t
            if "/" in rest:
                i, inst = rest.split("/", 1)
                if i:
                    e["ifaces"][i].add(inst)
        for v in h.findall("version"):
            vt = (v.text or "").strip()
            if vt:
                e["versions"].add(vt)
        for i in h.findall("interface"):
            iname = (i.findtext("name") or "").strip()
            if not iname:
                continue
            for inst in i.findall("instance"):
                it = (inst.text or "").strip()
                if it:
                    e["ifaces"][iname].add(it)
            for rinst in i.findall("regex-instance"):
                rt = (rinst.text or "").strip()
                if rt:
                    e["regex_ifaces"][iname].add(rt)

if manifests == 0 or not hals:
    sys.exit(f"no non-AOSP VINTF HALs found across {manifests} manifests in {sys.argv[1]}")


def hidl_key(v):
    try:
        return tuple(int(p) for p in v.split("."))
    except ValueError:
        return (0,)


def aidl_bounds(v):
    if "-" in v:
        lo_s, hi_s = v.split("-", 1)
        return int(lo_s), int(hi_s)
    val = int(v)
    return val, val


print('<compatibility-matrix version="2.0" type="framework">')
for name, fmt in sorted(hals):
    e = hals[(name, fmt)]
    print(f'    <hal format="{escape(fmt)}" optional="true">\n        <name>{escape(name)}</name>')
    if fmt == "hidl":
        for v in sorted(e["versions"], key=hidl_key):
            print(f'        <version>{escape(v)}</version>')
    elif e["versions"]:
        bounds = [aidl_bounds(v) for v in e["versions"]]
        lo = min(b[0] for b in bounds)
        hi = max(b[1] for b in bounds)
        print(f'        <version>{lo}</version>' if lo == hi else f'        <version>{lo}-{hi}</version>')
    all_ifaces = sorted(set(e["ifaces"]) | set(e["regex_ifaces"]))
    for i in all_ifaces:
        print(f'        <interface>\n            <name>{escape(i)}</name>')
        for inst in sorted(e["ifaces"][i]):
            print(f'            <instance>{escape(inst)}</instance>')
        for rinst in sorted(e["regex_ifaces"][i]):
            print(f'            <regex-instance>{escape(rinst)}</regex-instance>')
        print('        </interface>')
    print('    </hal>')
print('</compatibility-matrix>')
