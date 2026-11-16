#!/usr/bin/env python3
"""Add DT_RUNPATH=$ORIGIN to an Android PIE without changing its size."""

import struct
import sys
from pathlib import Path

DT_NULL = 0
DT_STRSZ = 10
DT_DEBUG = 21
DT_RUNPATH = 29
DT_FLAGS = 30
PT_LOAD = 1
SHF_ALLOC = 2
SHT_NOBITS = 8


def patch(path: Path) -> None:
    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError(f"{path}: expected little-endian ELF")
    bits = data[4]
    if bits == 2:
        word = "<QQ"
        phoff, shoff = struct.unpack_from("<QQ", data, 32)
        phentsize, phnum, shentsize, shnum, shstrndx = struct.unpack_from("<HHHHH", data, 54)
        def section(i):
            pos = shoff + i * shentsize
            name, typ = struct.unpack_from("<II", data, pos)
            flags, addr, off, size = struct.unpack_from("<QQQQ", data, pos + 8)
            return name, typ, flags, addr, off, size
        def program(i):
            pos = phoff + i * phentsize
            typ, flags = struct.unpack_from("<II", data, pos)
            off, vaddr, _, filesz, _ = struct.unpack_from("<QQQQQ", data, pos + 8)
            return typ, off, vaddr, filesz
    elif bits == 1:
        word = "<II"
        phoff, shoff = struct.unpack_from("<II", data, 28)
        phentsize, phnum, shentsize, shnum, shstrndx = struct.unpack_from("<HHHHH", data, 42)
        def section(i):
            pos = shoff + i * shentsize
            name, typ, flags, addr, off, size = struct.unpack_from("<IIIIII", data, pos)
            return name, typ, flags, addr, off, size
        def program(i):
            pos = phoff + i * phentsize
            typ, off, vaddr, _, filesz = struct.unpack_from("<IIIII", data, pos)
            return typ, off, vaddr, filesz
    else:
        raise ValueError(f"{path}: unsupported ELF class")

    sections = [section(i) for i in range(shnum)]
    shstr = sections[shstrndx]
    names_blob = data[shstr[4]:shstr[4] + shstr[5]]
    def name_of(s):
        end = names_blob.find(b"\0", s[0])
        return names_blob[s[0]:end].decode("ascii", "replace")
    named = {name_of(s): s for s in sections}
    dynstr = named[".dynstr"]
    dynamic = named[".dynamic"]

    loads = [program(i) for i in range(phnum) if program(i)[0] == PT_LOAD]
    allocated = sorted((s for s in sections if s[2] & SHF_ALLOC and s[1] != SHT_NOBITS and s[5]),
                       key=lambda s: s[4])
    marker = b"$ORIGIN\0"
    candidate = None
    for left, right in zip(allocated, allocated[1:]):
        start = left[4] + left[5]
        end = right[4]
        if start >= dynstr[4] + dynstr[5] and end - start >= len(marker):
            for pos in range(start, end - len(marker) + 1):
                if data[pos:pos + len(marker)] == b"\0" * len(marker):
                    candidate = pos
                    break
        if candidate is not None:
            break
    use_debug_storage = candidate is None and bits == 1
    if candidate is None and not use_debug_storage:
        raise ValueError(f"{path}: no safe allocated padding for RUNPATH")

    entry_size = struct.calcsize(word)
    strsz_pos = debug_pos = flags_pos = null_pos = None
    for pos in range(dynamic[4], dynamic[4] + dynamic[5], entry_size):
        tag, value = struct.unpack_from(word, data, pos)
        if tag == DT_STRSZ:
            strsz_pos = pos
        elif tag == DT_DEBUG:
            debug_pos = pos
        elif tag == DT_FLAGS:
            flags_pos = pos
        elif tag == DT_NULL:
            null_pos = pos
            break
    if strsz_pos is None or debug_pos is None or null_pos is None:
        raise ValueError(f"{path}: required dynamic tags not found")

    runpath_pos = debug_pos
    if use_debug_storage:
        if flags_pos is None:
            raise ValueError(f"{path}: no fallback dynamic storage for RUNPATH")
        candidate = debug_pos
        runpath_pos = flags_pos

    candidate_vaddr = None
    for _, off, vaddr, filesz in loads:
        if off <= candidate and candidate + len(marker) <= off + filesz:
            candidate_vaddr = vaddr + candidate - off
            break
    if candidate_vaddr is None:
        raise ValueError(f"{path}: RUNPATH storage is not mapped")

    strtab_vaddr = dynstr[3]
    string_offset = candidate_vaddr - strtab_vaddr

    data[candidate:candidate + len(marker)] = marker
    struct.pack_into(word, data, strsz_pos, DT_STRSZ, string_offset + len(marker))
    struct.pack_into(word, data, runpath_pos, DT_RUNPATH, string_offset)
    path.write_bytes(data)
    print(f"patched {path}: DT_RUNPATH=$ORIGIN")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: patch_android_runpath.py ELF...")
    for arg in sys.argv[1:]:
        patch(Path(arg))
