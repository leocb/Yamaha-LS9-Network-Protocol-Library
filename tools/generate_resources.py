#!/usr/bin/env python3
"""Generate Java resources from Yamaha's official ParamChangeList_LS9.xls.

Usage:
    python3 tools/generate_resources.py path/to/ParamChangeList_LS9.xls

The spreadsheet is distributed by Yamaha in:
    LS9 MIDI Protocol (ls9_midiptcl101.zip)
    https://usa.yamaha.com/files/download/other_assets/7/311517/ls9_midiptcl101.zip

Outputs:
    src/main/resources/io/github/leocb/ls9/fader-table.csv
    src/main/resources/io/github/leocb/ls9/ls9-parameters.csv
"""
import csv
import os
import re
import sys

import xlrd

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "io", "github", "leocb", "ls9")


def parse_db(text):
    text = str(text).strip()
    if "Inf" in text:
        return "-Infinity"
    m = re.search(r"(-?\d+(?:\.\d+)?)\s*dB", text)
    if not m:
        raise ValueError("cannot parse dB value: %r" % text)
    return "%.2f" % float(m.group(1))


def fader_table(sheet):
    # PRM TABLE #03 is eight (Value, Data) pairs per row.
    start = None
    for r in range(sheet.nrows):
        if str(sheet.cell_value(r, 0)).startswith("PRM TABLE #03"):
            start = r
            break
    if start is None:
        raise RuntimeError("PRM TABLE #03 not found")
    values = {}
    for r in range(start + 2, sheet.nrows):
        row = [sheet.cell_value(r, c) for c in range(sheet.ncols)]
        if not any(str(x).strip() for x in row):
            break
        for c in range(1, sheet.ncols - 1, 2):
            v = row[c]
            if v == "" or v is None:
                continue
            values[int(float(v))] = parse_db(row[c + 1])
        if len(values) >= 1024:
            break
    missing = [i for i in range(1024) if i not in values]
    if missing:
        raise RuntimeError("fader table is incomplete, missing %r" % missing[:10])
    path = os.path.join(OUT, "fader-table.csv")
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["value", "db"])
        for i in range(1024):
            w.writerow([i, values[i]])
    print("wrote %s (%d rows)" % (path, 1024))


def parse_value_tables(sheet):
    """Extract PRM TABLE value/display pairs into a flat CSV."""
    rows = []
    table_id = None
    table_name = ""
    for r in range(sheet.nrows):
        first = str(sheet.cell_value(r, 0)).strip()
        if first.startswith("PRM TABLE #"):
            m = re.search(r"#(\d+)", first)
            table_id = int(m.group(1)) if m else None
            table_name = str(sheet.cell_value(r, 2)).strip().strip("[]").strip()
            continue
        if table_id is None:
            continue
        # The spreadsheet stores pairs in columns 1/2, 3/4, ... 17/18.
        for c in range(1, sheet.ncols - 1, 2):
            value = sheet.cell_value(r, c)
            display = sheet.cell_value(r, c + 1)
            if value == "" or value is None:
                continue
            if not isinstance(value, float) and not isinstance(value, int):
                continue
            text = str(display).strip()
            if not text:
                continue
            rows.append((table_id, table_name, int(value), text))
    path = os.path.join(OUT, "value-tables.csv")
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["table_id", "table_name", "value", "display"])
        w.writerows(rows)
    print("wrote %s (%d rows)" % (path, len(rows)))


def meter_table(sheet):
    rows = []
    for r in range(sheet.nrows):
        for c in range(1, sheet.ncols - 1, 4):
            value = sheet.cell_value(r, c)
            level = sheet.cell_value(r, c + 2)
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                continue
            text = str(level).strip()
            if text == "":
                continue
            rows.append((int(value), text))
    # Keep the first occurrence of each value.
    seen = {}
    for value, text in rows:
        seen.setdefault(value, text)
    path = os.path.join(OUT, "meter-table.csv")
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["value", "db"])
        for value in sorted(seen):
            w.writerow([value, seen[value]])
    print("wrote %s (%d rows)" % (path, len(seen)))


def parameter_table(sheet):
    category = element = max_ch = None
    rows = []
    for r in range(2, sheet.nrows):
        c0 = str(sheet.cell_value(r, 0)).strip()
        c1 = str(sheet.cell_value(r, 1)).strip()
        if c0:
            category = c0
        if c1:
            element = c1
            max_ch = sheet.cell_value(r, 3)
        index_name = str(sheet.cell_value(r, 4)).strip()
        template = [sheet.cell_value(r, c) for c in range(10, 29)]
        if not template or template[0] != "F0":
            continue
        # F0 43 1n 3E 12 CC EEee IIii cccc dd...
        try:
            cat = int(str(template[5]), 16)
            elem = int(str(template[6]), 16) << 8 | int(str(template[7]), 16)
            index = int(str(template[8]), 16) << 8 | int(str(template[9]), 16)
        except ValueError:
            continue
        comment = str(sheet.cell_value(r, 9)).replace("\n", " ").strip()
        m = re.search(r"PRM TABLE #(\d+)", comment)
        rows.append(
            {
                "category": category,
                "element": element,
                "element_hex": "0x%04X" % elem,
                "index_name": index_name,
                "index_hex": "0x%04X" % index,
                "min": sheet.cell_value(r, 6),
                "max": sheet.cell_value(r, 7),
                "default": sheet.cell_value(r, 8),
                "max_ch": max_ch,
                "comment": comment,
                "data_category": cat,
                "value_table": int(m.group(1)) if m else 0,
                "template": " ".join(str(x) for x in template[:19] if str(x) != ""),
            }
        )
    path = os.path.join(OUT, "ls9-parameters.csv")
    fields = [
        "category",
        "element",
        "element_hex",
        "index_name",
        "index_hex",
        "min",
        "max",
        "default",
        "max_ch",
        "comment",
        "data_category",
        "value_table",
        "template",
    ]
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    print("wrote %s (%d rows)" % (path, len(rows)))


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 1
    book = xlrd.open_workbook(sys.argv[1])
    os.makedirs(OUT, exist_ok=True)
    fader_table(book.sheet_by_name("PRM TABLE"))
    parse_value_tables(book.sheet_by_name("PRM TABLE"))
    meter_table(book.sheet_by_name("meter_steps"))
    parameter_table(book.sheet_by_name("Parameter Change List"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
