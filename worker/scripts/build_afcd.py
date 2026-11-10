"""
Converts the AFCD Release 3 "Nutrient profiles" workbook into src/afcd.json for the Worker.

    python3 -I scripts/build_afcd.py "data/AFCD Release 3 - Nutrient profiles.xlsx" src/afcd.json

Each entry: [food key, name, kcal, protein g, fat g, available carbohydrate g] per 100 g,
plus, for the 216 liquids FSANZ also profiles per 100 mL, a 7th element [kcal, protein, fat, carbs] per 100 mL.
Energy uses FSANZ's "Energy with dietary fibre" (the figure on Australian labels), converted kJ -> kcal.

Data: Food Standards Australia New Zealand (2025). Australian Food Composition Database - Release 3.
Licensed by FSANZ under a licence based on CC BY-SA 3.0 AU.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import xlsx  # noqa: E402

SHEET = "All solids & liquids per 100 g"
LIQUIDS_SHEET = "Liquids only per 100 mL"
COLUMNS = {
    "key": "Public Food Key",
    "name": "Food Name",
    "kj": "Energy with dietary fibre, equated",
    "protein": "Protein",
    "fat": "Fat, total",
    "carbs": "Available carbohydrate, with sugar alcohols",
}


def num(v):
    try:
        return round(float(v), 2)
    except (TypeError, ValueError):
        return None


def read_sheet(rows):
    header_row = next(i for i, r in enumerate(rows) if r and r[0] == "Public Food Key")
    header = [(h or "").replace("\n", " ").strip() for h in rows[header_row]]
    idx = {}
    for field, title in COLUMNS.items():
        matches = [i for i, h in enumerate(header) if h.startswith(title)]
        if not matches:
            sys.exit(f"Column not found: {title!r}")
        idx[field] = matches[0]

    out = []
    for r in rows[header_row + 1:]:
        get = lambda f: r[idx[f]] if idx[f] < len(r) else None  # noqa: E731
        key, name, kj = get("key"), get("name"), num(get("kj"))
        out.append((key, name, None if kj is None else [
            round(kj / 4.184, 1),
            num(get("protein")) or 0,
            num(get("fat")) or 0,
            num(get("carbs")) or 0,
        ]))
    return out


def main(src, dst):
    sheets = xlsx.sheets(src)
    per_ml = {key: values for key, _, values in read_sheet(sheets[LIQUIDS_SHEET]) if key and values}
    foods, skipped = [], 0
    for key, name, values in read_sheet(sheets[SHEET]):
        if not key or not name or values is None:
            skipped += 1
            continue
        entry = [key, " ".join(name.split()), *values]
        if key in per_ml:
            entry.append(per_ml[key])
        foods.append(entry)

    with open(dst, "w") as f:
        json.dump(foods, f, separators=(",", ":"), ensure_ascii=False)
    print(f"{len(foods)} foods ({len(per_ml)} with per-100 mL values) -> {dst} "
          f"({os.path.getsize(dst) // 1024} KB), skipped {skipped}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
