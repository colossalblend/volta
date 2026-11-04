#!/usr/bin/env python3
"""Drops the lists of a geoip.dat / geosite.dat that are too big for the iOS VPN extension.

    python3 ios/tools/geo_trim.py <in.dat> <out.dat> [max-bytes]

Both files are protobuf: a list of entries (field 1), each starting with its code. The app
copies the lists a config names into a small file before each connect (Shared/GeoData.swift)
and refuses any single list above GeoData.maxListBytes, so bigger ones (geoip:us, au, nl…,
several MB each) only make the app larger. Entries are copied byte for byte.
"""
import sys


def varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return r, i


def put_varint(v):
    out = bytearray()
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def code_of(b, start, end):
    i = start
    while i < end:
        key, i = varint(b, i)
        if key & 7 == 2:
            n, i = varint(b, i)
            if key >> 3 == 1:
                return b[i:i + n].decode("utf-8", "replace")
            i += n
        elif key & 7 == 0:
            _, i = varint(b, i)
        else:
            return ""
    return ""


def main():
    src, dst = sys.argv[1], sys.argv[2]
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 1_200_000
    b = open(src, "rb").read()
    out = bytearray()
    kept = dropped = 0
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        if key & 7 != 2:
            raise SystemExit(f"unexpected wire type {key & 7}")
        n, i = varint(b, i)
        body = b[i:i + n]
        i += n
        if key >> 3 != 1:
            continue
        code = code_of(b, i - n, i)
        if n > limit:
            dropped += 1
            print(f"dropped {code}: {n} bytes")
            continue
        out += b"\x0a" + put_varint(n) + body
        kept += 1
    open(dst, "wb").write(out)
    print(f"{dst}: {kept} lists kept, {dropped} dropped, {len(out)} bytes")


if __name__ == "__main__":
    main()
