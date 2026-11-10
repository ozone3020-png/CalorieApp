// Label mode: transcribe a nutrition information panel and turn it into one item.
import { num } from "./model.js";

export const LABEL_PROMPT = `You read the nutrition information panel on food and drink packaging.
Australian/NZ panels have two number columns: "Per serving" and "Per 100 g" (or "Per 100 mL").
Some panels (e.g. US "Nutrition Facts") only list per serving.

Transcribe the numbers EXACTLY as printed. Do not estimate, calculate or guess.
Read each row across carefully: the label may be curved or slanted, so match each value to its row
and column by position. Use null for anything you cannot read clearly.
Energy may be printed in kJ, Cal/kcal or both; report what is printed. "Cal" means kcal.
Report carbohydrate as "carbohydrate, total" (not sugars). Dietary fibre is optional on labels: null if absent.

Respond with ONLY JSON, no prose:
{"is_label":true,
 "product":"product name if visible, else null",
 "serving_size":number or null, "serving_unit":"g" or "ml" or null,
 "servings_per_package":number or null,
 "per_100":{"energy_kj":number|null,"energy_kcal":number|null,"protein_g":number|null,"fat_g":number|null,"carbs_g":number|null,"sugars_g":number|null,"fibre_g":number|null},
 "per_serving":{"energy_kj":number|null,"energy_kcal":number|null,"protein_g":number|null,"fat_g":number|null,"carbs_g":number|null,"sugars_g":number|null,"fibre_g":number|null}}
If the photo does not show a nutrition panel, return {"is_label":false}.`;

const KJ_PER_KCAL = 4.184;
const FIELDS = ["protein_g", "fat_g", "carbs_g"];
// Not every label lists these, so a missing one isn't a warning.
const OPTIONAL = ["fibre_g"];
const ALL_FIELDS = [...FIELDS, ...OPTIONAL];

/**
 * Turns the model's transcription into an item for one serving (or 100 g/mL if no serving size),
 * with a `warning` when the numbers don't hang together. Returns null if it isn't a usable label.
 */
export function labelToItem(raw) {
  if (!raw || raw.is_label === false) return null;
  const size = positive(raw.serving_size);
  const unit = String(raw.serving_unit ?? "").toLowerCase() === "ml" ? "ml" : "g";
  const p100 = column(raw.per_100);
  const pServ = column(raw.per_serving);

  // Per-100 values. Prefer deriving them from the per-serving column: its numbers are bigger, so the
  // label's rounding costs less (60 kJ per 500 mL is more precise than "12 kJ per 100 mL").
  let base = pServ.kcal != null && size ? scale(pServ, 100 / size) : null;
  if (!base && p100.kcal != null) base = { ...p100 };
  if (!base) return null;
  for (const f of ALL_FIELDS) {
    if (base[f] == null && p100[f] != null) base[f] = p100[f];
  }

  const warnings = [];
  // The two columns should agree (per serving ≈ per 100 × size / 100).
  if (size && p100.kcal != null && pServ.kcal != null) {
    const expected = p100.kcal * size / 100;
    if (Math.abs(pServ.kcal - expected) > Math.max(5, expected * 0.15)) warnings.push("columns disagree");
  }
  // Energy should roughly match 4·protein + 4·carbs + 9·fat (loose: fibre, alcohol and sweeteners shift it).
  const fromMacros = 4 * (base.protein_g ?? 0) + 4 * (base.carbs_g ?? 0) + 9 * (base.fat_g ?? 0);
  if (Math.abs(base.kcal - fromMacros) > Math.max(15, base.kcal * 0.25)) warnings.push("energy doesn't match macros");
  if (FIELDS.some((f) => base[f] == null)) warnings.push("some values unreadable");

  const amount = size ?? 100;
  const f = amount / 100;
  const per = unit === "ml" ? "100 mL" : "100 g";
  return {
    name: typeof raw.product === "string" && raw.product.trim() ? raw.product.trim() : "Packaged food",
    grams: num(amount, 1),
    unit,
    calories: num(base.kcal * f, 0),
    protein_g: num((base.protein_g ?? 0) * f),
    carbs_g: num((base.carbs_g ?? 0) * f),
    fat_g: num((base.fat_g ?? 0) * f),
    fibre_g: num((base.fibre_g ?? 0) * f),
    group: null,
    source: "label",
    match: `Nutrition label: ${Math.round(base.kcal * KJ_PER_KCAL)} kJ / ${fmt(base.kcal)} kcal per ${per}` +
      (size ? `, serving ${fmt(size)} ${unit === "ml" ? "mL" : "g"}` : ""),
    warning: warnings.length ? `Check against the label: ${warnings.join(", ")}.` : null,
  };
}

function column(c) {
  // Australian labels state kJ as the primary figure; it's also finer-grained than Cal, so prefer it.
  const kj = positive(c?.energy_kj);
  const kcal = kj != null ? kj / KJ_PER_KCAL : positive(c?.energy_kcal);
  const out = { kcal };
  for (const f of ALL_FIELDS) out[f] = nonNegative(c?.[f]);
  // A printed "0 kJ" column is valid (e.g. diet drinks).
  if (out.kcal == null && (c?.energy_kj === 0 || c?.energy_kcal === 0)) out.kcal = 0;
  return out;
}

function scale(c, factor) {
  const out = { kcal: c.kcal * factor };
  for (const f of ALL_FIELDS) out[f] = c[f] == null ? null : c[f] * factor;
  return out;
}

function positive(v) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function nonNegative(v) {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

function fmt(n) {
  return Math.round(n * 10) / 10;
}
