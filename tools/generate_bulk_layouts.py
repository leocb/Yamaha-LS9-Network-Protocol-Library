#!/usr/bin/env python3
"""Generate LS9 bulk-block layouts from LS9 Editor's descriptor tables.

The LS9 console transfers bulk blocks as the raw memory image of one scene,
one library entry, or one current-data area. The layout of those images is not
published; LS9 Editor reconstructs it from four private tables in its Mach-O
binary:

  _gElementDesc    0x1003ed380  element descriptors (one per element)
  _gParamDesc      0x1003f7c90  per-parameter kind/min/max/default
  _gLibraryDesc    0x1003f4a70  library records, variants and collection index
  _gCollectionDesc 0x1003df820  current-data collection descriptors
  version table    0x100797080  collection index -> version -> layout pointer

The layout walk is ``CCollectionBase::calcDataSize`` (0x1000ad2d0) and
``CCollectionBase::getIndParamOffset`` (0x1000b0aa0); the version and variant
bytes are read by ``CCollectionBase::getVersionFromBuffer`` (0x1000b1330) and
``getVariantFromBuffer`` (0x1000b1320); the trailing checksum is written by
``CCollectionBase::setCheckSumToBuffer`` (0x1000b2030) using
``mem_CalcCheckSumLong`` (0x1000d73a0).

The scene layout produced here reproduces the offsets used by the StageMix
scene decoder (0x146 link bytes, 0x16c link values, 0x204 link switches,
0x268 mute master, 0x269 mix bus types, 0x334 input switches, 0x33d input
phase, 0x3535 GEQ bands, 0x3918 effect parameters), which validates the walk.

Addresses are for LS9 Editor V2.2.1 (x86-64, SHA-256
74ba4a90eee9ff9857e0ff2e720e0c0f2a45d0c75460a43b085154c99dde0b9f).

Usage:
    python3 tools/generate_bulk_layouts.py "/path/to/LS9 Editor"

Outputs:
    src/main/resources/io/github/leocb/ls9/ls9-bulk-layouts.csv
"""
import csv
import os
import struct
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "io", "github", "leocb", "ls9")
IMAGE_BASE = 0x100000000

ELEMENT_DESC = 0x1003ed380
PARAM_DESC = 0x1003f7c90
LIBRARY_DESC = 0x1003f4a70
COLLECTION_DESC = 0x1003df820
VERSION_TABLE = 0x100797080

KIND_NAMES = {
    0x00: "bit",
    0x01: "u8",
    0x81: "s8",
    0x02: "u16",
    0x82: "s16",
    0xA2: "s16be",
    0x04: "u32",
    0x84: "s32",
    0xA4: "s32be",
}

TITLE_ELEMENT = 331
VERSION_ELEMENT = 332
FLAG_ELEMENT = 333
VARIANT_ELEMENT = 334
CHECKSUM_ELEMENT = 342


class Image:
    def __init__(self, path):
        with open(path, "rb") as fh:
            self.data = fh.read()

    def u8(self, addr):
        return self.data[addr - IMAGE_BASE]

    def u16(self, addr):
        return struct.unpack_from("<H", self.data, addr - IMAGE_BASE)[0]

    def u32(self, addr):
        return struct.unpack_from("<I", self.data, addr - IMAGE_BASE)[0]

    def i32(self, addr):
        return struct.unpack_from("<i", self.data, addr - IMAGE_BASE)[0]

    def u64(self, addr):
        return struct.unpack_from("<Q", self.data, addr - IMAGE_BASE)[0]


def parse_elements(img):
    count = img.u32(ELEMENT_DESC)
    pos = 1
    elements = []
    for _ in range(count):
        length = img.u32(ELEMENT_DESC + 4 * (pos + 2)) & 0xFFFF
        elements.append([img.u32(ELEMENT_DESC + 4 * (pos + j)) for j in range(3 + length)])
        pos += 3 + length
    return elements


def parse_params(img):
    def record(i):
        base = PARAM_DESC + i * 0x14
        return {
            "min": img.i32(base),
            "max": img.i32(base + 4),
            "default": img.u32(base + 8),
            "kind": img.u8(base + 0xC),
        }
    return record


def parse_libraries(img):
    count = img.u32(LIBRARY_DESC)
    pos = 1
    libraries = []
    for _ in range(count):
        variants = img.u16(LIBRARY_DESC + 4 * (pos + 1))
        records = []
        for v in range(variants):
            base = LIBRARY_DESC + 4 * (pos + 2 + v * 7)
            records.append({
                "minVersion": img.u16(base),
                "collection": img.u16(base + 4),
                "version": img.u16(base + 6),
                "preset": img.u16(base + 10),
            })
        libraries.append(records)
        pos += 2 + 7 * variants
    return libraries


def parse_collections(img):
    count = img.u32(COLLECTION_DESC)
    pos = 1
    collections = []
    for _ in range(count):
        record = [img.u32(COLLECTION_DESC + 4 * (pos + j)) for j in range(7)]
        collections.append({
            "collection": record[1] & 0xFFFF,
            "version": record[1] >> 16,
        })
        pos += 7
    return collections


def collection_descriptor(img, collection, version):
    table = img.u64(VERSION_TABLE + collection * 8)
    return img.u64(table + version * 0x10)


def encode_element(dense):
    """Dense element index -> published wire element number.

    LS9 Editor's element array is dense: every 128 entries start a new element
    page, and the wire address uses (page, offset) instead of (page << 7) + off.
    Confirmed by Yamaha's parameter list: dense 128 = kEffect = 0x0100,
    dense 163 = kAD824 = 0x0123, dense 256 = kSetupPGMChangeTable = 0x0200.
    """
    return ((dense >> 7) << 8) | (dense & 0x7F)


def walk(img, elements, params, descriptor, include_scene, area_kind, area_number):
    entries = img.u32(descriptor)
    offset = 0
    bit = 0
    fields = []
    for entry in range(entries):
        base = descriptor + 4 + entry * 12
        repetitions = img.u16(base)
        element = img.u16(base + 2)
        last = img.u16(base + 4)
        first = img.u16(base + 6)
        payload = elements[element][3:]
        if first > last:
            continue
        for index in range(first, last + 1):
            if bit:
                bit = 0
                offset += 1
            param_index = payload[index] & 0xFFFF
            param = params(param_index)
            kind = KIND_NAMES.get(param["kind"])
            if kind is None:
                raise ValueError("unknown kind %#x" % param["kind"])
            if kind == "bit":
                for channel in range(repetitions):
                    fields.append((element, index, channel, offset, bit, "bit", 0,
                                   param, param_index))
                    bit += 1
                    if bit == 8:
                        bit = 0
                        offset += 1
            else:
                size = {"u8": 1, "s8": 1, "u16": 2, "s16": 2, "s16be": 2,
                        "u32": 4, "s32": 4, "s32be": 4}[kind]
                align = 4 if size == 4 else (2 if size == 2 else 1)
                if offset % align:
                    offset += align - (offset % align)
                for channel in range(repetitions):
                    fields.append((element, index, channel, offset, -1, kind, size,
                                   param, param_index))
                    offset += size
        if bit:
            bit = 0
            offset += 1
    size = offset + (offset & 1)

    rows = []
    for element, index, channel, field_offset, field_bit, kind, field_size, param, param_index in fields:
        region = "param"
        published_element = encode_element(element)
        published_index = index
        if element == TITLE_ELEMENT:
            region = "title"
        elif element == VERSION_ELEMENT:
            region = "version"
        elif element == FLAG_ELEMENT:
            region = "flag"
        elif element == VARIANT_ELEMENT:
            region = "variant" if index == 0 else "reserved"
        elif element == CHECKSUM_ELEMENT:
            region = "checksum"
        elif 335 <= element <= 341 and not (area_kind == "library" and area_number == 0):
            region = "meta"
        rows.append({
            "areaKind": area_kind,
            "area": area_number,
            "variant": 0,
            "dataSize": size,
            "collection": 0,
            "version": 0,
            "region": region,
            "offset": field_offset,
            "bit": field_bit,
            "size": field_size,
            "kind": kind,
            "element": published_element,
            "index": published_index,
            "channel": channel,
            "min": param["min"],
            "max": param["max"],
            "default": param["default"],
            "param": param_index,
        })
    return size, rows


def generate(img):
    elements = parse_elements(img)
    params = parse_params(img)
    libraries = parse_libraries(img)
    collections = parse_collections(img)

    all_rows = []
    for library, variants in enumerate(libraries):
        for variant, record in enumerate(variants):
            desc = collection_descriptor(img, record["collection"], record["version"])
            size, rows = walk(img, elements, params, desc, True, "library", library)
            for row in rows:
                row["variant"] = variant
                row["dataSize"] = size
                row["collection"] = record["collection"]
                row["version"] = record["version"]
            all_rows.extend(rows)

    for number, record in enumerate(collections):
        desc = collection_descriptor(img, record["collection"], record["version"])
        size, rows = walk(img, elements, params, desc, True, "collection", number)
        for row in rows:
            row["dataSize"] = size
            row["collection"] = record["collection"]
            row["version"] = record["version"]
        all_rows.extend(rows)
    return all_rows


HEADER = ["areaKind", "area", "variant", "dataSize", "collection", "version",
          "region", "offset", "bit", "size", "kind", "element", "index",
          "channel", "min", "max", "default", "param"]


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    img = Image(sys.argv[1])
    rows = generate(img)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "ls9-bulk-layouts.csv")
    with open(path, "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=HEADER)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    print("wrote %d fields to %s" % (len(rows), path))
    for kind in ("library", "collection"):
        areas = sorted({r["area"] for r in rows if r["areaKind"] == kind})
        for area in areas:
            variants = sorted({r["variant"] for r in rows
                               if r["areaKind"] == kind and r["area"] == area})
            for variant in variants:
                size = next(r["dataSize"] for r in rows
                            if r["areaKind"] == kind and r["area"] == area
                            and r["variant"] == variant)
                print("  %s %2d variant %d: %6d bytes" % (kind, area, variant, size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
