#!/usr/bin/env python3
"""Check that an APK's native libraries support 16 KB memory pages.

Google Play requires this for app updates from February 2027 (ADR 0003). For every
64-bit native library (arm64-v8a, x86_64) the check verifies that:

1. each ELF LOAD segment is aligned to at least 16 KB, and
2. if the library is stored uncompressed, its data starts on a 16 KB boundary in the APK.

Usage: check_page_alignment.py APK [APK ...]
Exit code 1 if any library fails.
"""

import struct
import sys
import zipfile

PAGE = 16 * 1024
ABIS_64 = ("lib/arm64-v8a/", "lib/x86_64/")
PT_LOAD = 1
LOCAL_HEADER_SIZE = 30


def load_alignments(elf: bytes) -> list[int]:
    """The `p_align` of every LOAD segment in a 64-bit little-endian ELF file."""
    if elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF file")
    (phoff,) = struct.unpack_from("<Q", elf, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        (p_type,) = struct.unpack_from("<I", elf, base)
        if p_type == PT_LOAD:
            (p_align,) = struct.unpack_from("<Q", elf, base + 0x30)
            aligns.append(p_align)
    return aligns


def data_offset(apk: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Where an entry's data starts in the APK file."""
    fp = apk.fp
    if fp is None:
        raise ValueError("APK is closed")
    fp.seek(info.header_offset)
    header = fp.read(LOCAL_HEADER_SIZE)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + LOCAL_HEADER_SIZE + name_len + extra_len


def check(path: str) -> list[str]:
    problems = []
    with zipfile.ZipFile(path) as apk:
        libs = [
            i
            for i in apk.infolist()
            if i.filename.startswith(ABIS_64) and i.filename.endswith(".so")
        ]
        for info in libs:
            small = [a for a in load_alignments(apk.read(info)) if a < PAGE]
            if small:
                problems.append(f"{info.filename}: LOAD segment aligned to {min(small)} bytes")
            if info.compress_type == zipfile.ZIP_STORED and data_offset(apk, info) % PAGE:
                problems.append(f"{info.filename}: stored uncompressed but not on a 16 KB boundary")
        print(f"{path}: {len(libs)} 64-bit native libraries checked")
    return problems


def main(paths: list[str]) -> int:
    if not paths:
        print(__doc__)
        return 2
    problems = [p for path in paths for p in check(path)]
    for p in problems:
        print(f"error: {p}")
    if not problems:
        print("ok: 16 KB page alignment")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
