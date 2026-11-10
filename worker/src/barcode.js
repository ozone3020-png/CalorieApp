// Barcode lookup in Open Food Facts (free, crowd-sourced, ODbL). Values are per 100 g (≈100 mL for drinks).
import { num } from "./model.js";

const KJ_PER_KCAL = 4.184;
const FIELDS = "code,product_name,brands,quantity,serving_quantity,nutriments,categories_tags";
// OFF asks API users to identify their app in the User-Agent.
const USER_AGENT = "CalorieApp/0.1 (personal, non-commercial; https://github.com/)";

/** Returns an item for the product, or null if Open Food Facts doesn't have it (or has no nutrition). */
export async function lookupBarcode(code) {
  const url = `https://world.openfoodfacts.org/api/v2/product/${code}.json?fields=${FIELDS}`;
  const res = await fetch(url, { headers: { "user-agent": USER_AGENT }, signal: AbortSignal.timeout(8000) });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`Open Food Facts HTTP ${res.status}`);
  const data = await res.json();
  if (data.status !== 1 || !data.product) return null;
  return productToItem(data.product, code);
}

/** Pure: turns an OFF product into an item for one serving / the pack, with sanity checks. Exported for tests. */
export function productToItem(p, code) {
  const n = p.nutriments ?? {};
  const protein = nonNegative(n.proteins_100g);
  const carbs = nonNegative(n.carbohydrates_100g);
  const fat = nonNegative(n.fat_100g);
  const fibre = nonNegative(n.fiber_100g);
  const fromMacros = protein != null || carbs != null || fat != null
    ? 4 * (protein ?? 0) + 4 * (carbs ?? 0) + 9 * (fat ?? 0)
    : null;

  // Energy: OFF may have kcal, kJ, both (sometimes contradicting each other), or neither.
  const kj = nonNegative(n["energy-kj_100g"]) ?? (n.energy_unit === "kJ" ? nonNegative(n.energy_100g) : null);
  const stated = [nonNegative(n["energy-kcal_100g"]), kj != null ? kj / KJ_PER_KCAL : null].filter((v) => v != null);
  const warnings = [];
  let kcal = null;
  if (stated.length === 2 && !close(stated[0], stated[1], 0.15, 5)) {
    // Contradiction: trust whichever agrees with the macros.
    kcal = fromMacros != null ? closest(stated, fromMacros) : stated[1];
    warnings.push("its kJ and kcal disagree");
  } else if (stated.length) {
    kcal = stated[0];
  }
  if (kcal != null && fromMacros != null && fromMacros > 20 && !close(kcal, fromMacros, 0.5, 20)) {
    // Way off (e.g. a per-serving number entered as per-100 g): the macros are more likely right.
    kcal = fromMacros;
    warnings.push("its energy didn't match the macros, so energy was worked out from them");
  }
  if (kcal == null) {
    if (fromMacros == null) return null; // no nutrition data at all
    kcal = fromMacros;
    warnings.push("no energy listed, worked out from the macros");
  }

  const unit = /\d\s*(ml|cl|l)\b/i.test(p.quantity ?? "") ? "ml" : "g";
  const pack = packSize(p.quantity);
  const serving = nonNegative(p.serving_quantity);
  // Drinks in a single-serve can/bottle (≤ 750 mL): the whole pack, since that's what gets drunk (OFF's
  // "serving" is often a European 200–250 mL portion). Otherwise one serving if known, else 100.
  const amount = unit === "ml" && pack && pack <= 750 ? pack : serving && serving > 0 ? serving : 100;
  const f = amount / 100;
  const per = unit === "ml" ? "100 mL" : "100 g";
  const brand = p.brands?.split(",")[0]?.trim();
  const product = p.product_name?.trim();
  const name = (brand && product && !product.toLowerCase().includes(brand.toLowerCase())
    ? `${brand} ${product}`
    : product || brand) || `Product ${code}`;

  return {
    name,
    grams: num(amount, 1),
    unit,
    calories: num(kcal * f, 0),
    protein_g: num((protein ?? 0) * f),
    carbs_g: num((carbs ?? 0) * f),
    fat_g: num((fat ?? 0) * f),
    fibre_g: num((fibre ?? 0) * f),
    group: fruitVegGroup(p.categories_tags),
    source: "off",
    match: `Open Food Facts: ${Math.round(kcal * KJ_PER_KCAL)} kJ / ${Math.round(kcal)} kcal per ${per}` +
      (p.quantity ? `, pack ${p.quantity}` : ""),
    warning: warnings.length
      ? `Check against the package: ${warnings.join("; ")}.`
      : protein == null || carbs == null || fat == null ? "Check against the package: some values are missing." : null,
  };
}

/** Plain fruit / vegetable products (frozen peas, tinned peaches) count towards serves; juices and snacks don't. */
export function fruitVegGroup(tags) {
  const t = new Set(Array.isArray(tags) ? tags : []);
  const excluded = [...t].some((x) => /juice|nectar|chips|crisps|snack|jam|sauce|soup|drink|smoothie|confection/.test(x));
  if (excluded) return null;
  if (t.has("en:dried-fruits")) return "dried_fruit";
  if (["en:fruits", "en:fresh-fruits", "en:canned-fruits", "en:frozen-fruits"].some((x) => t.has(x))) return "fruit";
  if (["en:vegetables", "en:fresh-vegetables", "en:frozen-vegetables", "en:canned-vegetables", "en:legumes",
    "en:pulses", "en:canned-legumes"].some((x) => t.has(x))) return "veg";
  return null;
}

function packSize(quantity) {
  const m = /([\d.,]+)\s*(kg|g|ml|cl|l)\b/i.exec(quantity ?? "");
  if (!m) return null;
  const v = Number(m[1].replace(",", "."));
  const mult = { kg: 1000, g: 1, ml: 1, cl: 10, l: 1000 }[m[2].toLowerCase()];
  return Number.isFinite(v) && v > 0 ? v * mult : null;
}

function nonNegative(v) {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

function close(a, b, rel, abs) {
  return Math.abs(a - b) <= Math.max(abs, Math.max(a, b) * rel);
}

function closest(values, target) {
  return values.reduce((best, v) => (Math.abs(v - target) < Math.abs(best - target) ? v : best));
}
