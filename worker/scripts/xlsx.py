"""Minimal .xlsx reader using only the standard library (an .xlsx is zipped XML)."""
import re
import zipfile
import xml.etree.ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}
REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"


def _col_index(ref):
    letters = re.match(r"[A-Z]+", ref).group(0)
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n - 1


def sheets(path):
    """Returns {sheet_name: [[cell values...], ...]} for every sheet in the workbook."""
    with zipfile.ZipFile(path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", NS):
                shared.append("".join(t.text or "" for t in si.iter(f"{{{NS['m']}}}t")))

        rels = {r.get("Id"): r.get("Target") for r in
                ET.fromstring(z.read("xl/_rels/workbook.xml.rels")).iter(f"{REL_NS}Relationship")}
        out = {}
        for s in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", NS):
            target = rels[s.get(f"{{{NS['r']}}}id")].lstrip("/")
            target = target if target.startswith("xl/") else "xl/" + target
            rows = []
            for row in ET.fromstring(z.read(target)).iter(f"{{{NS['m']}}}row"):
                values = []
                for c in row.findall("m:c", NS):
                    i = _col_index(c.get("r"))
                    while len(values) < i:
                        values.append(None)
                    t = c.get("t")
                    v = c.find("m:v", NS)
                    if t == "s" and v is not None:
                        values.append(shared[int(v.text)])
                    elif t == "inlineStr":
                        values.append("".join(x.text or "" for x in c.iter(f"{{{NS['m']}}}t")))
                    elif v is not None:
                        values.append(v.text)
                    else:
                        values.append(None)
                rows.append(values)
            out[s.get("name")] = rows
        return out
