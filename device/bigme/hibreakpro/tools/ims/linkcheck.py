#!/usr/bin/env python3
"""Precise link check: do the method/field references of some dex containers resolve against a
set of runtime jars (e.g. the phone's BOOTCLASSPATH)?

Usage: linkcheck.py --app a.apk [--app b.jar ...] --rt dir-or-jar [--rt ...]
Resolution walks superclasses and interfaces, like ART. Prints unresolved references grouped by
class, then a count. Classes that are missing entirely are listed once.
"""
import argparse
import glob
import os
import struct
import zipfile

NO_INDEX = 0xFFFFFFFF


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        if x < 0x80:
            return r, o
        s += 7


class Dex:
    def __init__(self, b):
        self.b = b
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off, self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off, self.class_defs_size,
         self.class_defs_off) = struct.unpack_from("<12I", b, 0x38)
        self._s = {}
        self.types = [self.s(struct.unpack_from("<I", b, self.type_ids_off + 4 * i)[0])
                      for i in range(self.type_ids_size)]

    def s(self, i):
        if i in self._s:
            return self._s[i]
        off = struct.unpack_from("<I", self.b, self.string_ids_off + 4 * i)[0]
        _, o = uleb(self.b, off)
        v = self.b[o:self.b.index(0, o)].decode("utf-8", "replace")
        self._s[i] = v
        return v

    def proto(self, i):
        _, ret, poff = struct.unpack_from("<III", self.b, self.proto_ids_off + 12 * i)
        params = []
        if poff:
            n = struct.unpack_from("<I", self.b, poff)[0]
            params = [self.types[struct.unpack_from("<H", self.b, poff + 4 + 2 * k)[0]]
                      for k in range(n)]
        return "(" + "".join(params) + ")" + self.types[ret]

    def method(self, i):
        c, p, n = struct.unpack_from("<HHI", self.b, self.method_ids_off + 8 * i)
        return self.types[c], self.s(n), self.proto(p)

    def field(self, i):
        c, t, n = struct.unpack_from("<HHI", self.b, self.field_ids_off + 8 * i)
        return self.types[c], self.s(n), self.types[t]

    def classes(self):
        for i in range(self.class_defs_size):
            (cidx, _, sup, ifoff, _, _, cdoff, _) = struct.unpack_from(
                "<8I", self.b, self.class_defs_off + 32 * i)
            name = self.types[cidx]
            superc = None if sup == NO_INDEX else self.types[sup]
            ifaces = []
            if ifoff:
                n = struct.unpack_from("<I", self.b, ifoff)[0]
                ifaces = [self.types[struct.unpack_from("<H", self.b, ifoff + 4 + 2 * k)[0]]
                          for k in range(n)]
            members = set()
            if cdoff:
                o = cdoff
                sf, o = uleb(self.b, o)
                inf, o = uleb(self.b, o)
                dm, o = uleb(self.b, o)
                vm, o = uleb(self.b, o)
                for count, kind in ((sf, "F"), (inf, "F"), (dm, "M"), (vm, "M")):
                    idx = 0
                    for _ in range(count):
                        d, o = uleb(self.b, o)
                        idx += d
                        _, o = uleb(self.b, o)  # access flags
                        if kind == "M":
                            _, o = uleb(self.b, o)  # code off
                            _, n, p = self.method(idx)
                            members.add(("M", n, p))
                        else:
                            _, n, t = self.field(idx)
                            members.add(("F", n, t))
            yield name, superc, ifaces, members

    def refs(self):
        for i in range(self.method_ids_size):
            c, n, p = self.method(i)
            yield c, ("M", n, p)
        for i in range(self.field_ids_size):
            c, n, t = self.field(i)
            yield c, ("F", n, t)


def dexes(path):
    if path.endswith(".dex"):
        yield Dex(open(path, "rb").read())
        return
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith("classes") and n.endswith(".dex"):
                yield Dex(z.read(n))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", action="append", required=True)
    ap.add_argument("--rt", action="append", required=True)
    a = ap.parse_args()
    rt = []
    for r in a.rt:
        rt += sorted(glob.glob(os.path.join(r, "*.jar"))) if os.path.isdir(r) else [r]
    world = {}
    refs = set()
    for path in rt + a.app:
        for d in dexes(path):
            for name, sup, ifs, mem in d.classes():
                world.setdefault(name, (sup, ifs, mem))
            if path in a.app:
                refs |= set(d.refs())

    def resolve(cls, m, seen=None):
        seen = seen or set()
        if cls in seen:
            return False
        seen.add(cls)
        if cls.startswith("["):
            return m[0] == "M" and m[1] in ("clone",) or resolve("Ljava/lang/Object;", m, seen)
        if cls not in world:
            return None
        sup, ifs, mem = world[cls]
        if m in mem:
            return True
        # signature-polymorphic MethodHandle/VarHandle calls
        if cls in ("Ljava/lang/invoke/MethodHandle;", "Ljava/lang/invoke/VarHandle;"):
            return True
        for nxt in ([sup] if sup else []) + list(ifs):
            if resolve(nxt, m, seen):
                return True
        return False

    missing_cls, bad = set(), {}
    for cls, m in refs:
        r = resolve(cls, m)
        if r is None:
            missing_cls.add(cls)
        elif r is False:
            bad.setdefault(cls, []).append(m)
    for c in sorted(missing_cls):
        print("NO CLASS", c)
    for c in sorted(bad):
        for m in sorted(bad[c]):
            print("NO MEMBER", c, *m)
    print(f"# {len(missing_cls)} missing classes, {sum(map(len, bad.values()))} missing members")


if __name__ == "__main__":
    main()
