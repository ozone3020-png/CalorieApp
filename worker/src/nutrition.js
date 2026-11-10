// Turns food names + weights into nutrition: AFCD first, USDA second, AI estimate last.
import { searchAfcd } from "./afcd.js";
import { searchUsda } from "./usda.js";
import { num, parseJson, runModel } from "./model.js";

const AFCD_CANDIDATES = 12;
const USDA_CANDIDATES = 5;

const MATCH_PROMPT = `You match foods from a meal to nutrition database entries.
Foods are as eaten: assume cooked/prepared unless the name says raw.

For each item pick the ONE candidate id that best represents the food as eaten.
- Prefer "A" candidates (Australian database). Use a "U" candidate (USDA) only if no "A" candidate fits.
- Never pick uncooked, dry, raw, powdered or dehydrated entries for foods normally eaten cooked
  (e.g. rice, pasta, oats, lentils, meat), unless the item name says so.
- A close generic match is fine (e.g. "Rice, white, boiled" for "rice").
- When the variety isn't stated, pick the plainest, most common one
  (e.g. white bread for "toast", white rice for "rice", full-fat milk for "milk").
- Prefer generic entries over brand-name or restaurant-chain entries.
- Respect sugar-free variants: an item called zero, diet, sugar free, no sugar, low carb or "max" must
  match a sugar-free / diet / intense-sweetened / low-carb entry, never a regular sugared one.
  If there is no such candidate, use null.
- If no candidate is a reasonable match, use null.

Reply with ONLY JSON: {"matches":[{"i":0,"id":"A3"},{"i":1,"id":null}]}`;

const ESTIMATE_PROMPT = `You are a nutrition estimator. Estimate nutrition for the given weight of the food as typically eaten.
Reply with ONLY JSON: {"calories":number,"protein_g":number,"carbs_g":number,"fat_g":number}
If the text is not a recognisable food or drink, reply with ONLY: {"unknown":true}`;

// Names that promise (almost) no sugar. "light/lite" is left out: light milk is reduced-fat, not sugar-free.
const SUGAR_FREE = /\b(zero|diet|sugar[- ]?free|no sugar|low[- ]?carb|max|intense(ly)? sweetened|unsweetened)\b/i;

/**
 * Hard guard on top of the model's choice: a sugar-free item must not get a sugary entry's numbers
 * (e.g. "Monster Energy Zero Ultra" matched to a regular 235 kcal Monster).
 */
function contradictsSugarFree(item, match) {
  if (!SUGAR_FREE.test(item.name) || SUGAR_FREE.test(match.name)) return false;
  const per = unitOf(item) === "ml" && match.per100ml ? match.per100ml : match.per100;
  return per.carbs > 2.5;
}

/** Thrown when a typed name isn't a recognisable food. */
export class UnknownFoodError extends Error {}

/**
 * @param items [{ name, grams, unit?, calories?, protein_g?, carbs_g?, fat_g? }] - unit is "g" or "ml"
 *        (grams then holds millilitres); the optional
 *        nutrition fields are the vision model's own estimate, used if nothing matches.
 * @returns items with nutrition plus `source` ("afcd" | "usda" | "ai") and `match` (database food name).
 */
export async function resolveNutrition(env, items) {
  const candidates = await Promise.all(
    items.map(async (item) => {
      const afcd = searchAfcd(item.name, AFCD_CANDIDATES).map((c, j) => ({ ...c, id: `A${j + 1}` }));
      const usda = (await searchUsda(env, item.name, USDA_CANDIDATES)).map((c, j) => ({ ...c, id: `U${j + 1}` }));
      return [...afcd, ...usda];
    }),
  );

  const picks = await pickMatches(env, items, candidates);

  return Promise.all(
    items.map(async (item, i) => {
      let match = candidates[i].find((c) => c.id === picks.get(i));
      if (match && contradictsSugarFree(item, match)) {
        console.log(`rejected "${match.name}" for sugar-free "${item.name}"`);
        match = undefined;
      }
      if (match) return fromDatabase(item, match);
      if (hasEstimate(item)) return { ...normalize(item), source: "ai", match: null };
      return { ...(await estimate(env, item)), source: "ai", match: null };
    }),
  );
}

async function pickMatches(env, items, candidates) {
  const picks = new Map();
  const question = items
    .map((item, i) => ({
      i,
      item: item.name,
      candidates: candidates[i].map((c) => ({ id: c.id, name: c.name })),
    }))
    .filter((q) => q.candidates.length);
  if (!question.length) return picks;

  try {
    const response = await runModel(env, [
      { role: "system", content: MATCH_PROMPT },
      { role: "user", content: JSON.stringify({ items: question }) },
    ], 512);
    const parsed = parseJson(response);
    for (const m of parsed?.matches ?? []) {
      if (Number.isInteger(m?.i) && typeof m.id === "string") picks.set(m.i, m.id);
    }
  } catch (err) {
    console.log("match step failed, falling back to AI estimates:", String(err));
  }
  return picks;
}

async function estimate(env, item) {
  const response = await runModel(env, [
    { role: "system", content: ESTIMATE_PROMPT },
    { role: "user", content: `${item.grams} ${unitOf(item) === "ml" ? "mL" : "g"} of ${item.name}` },
  ], 200);
  const parsed = parseJson(response);
  if (parsed?.unknown) throw new UnknownFoodError(`"${item.name}" isn't a food I recognise`);
  if (!parsed || !(Number(parsed.calories) >= 0)) throw new Error(`could not estimate nutrition for "${item.name}"`);
  return normalize({ ...item, ...parsed });
}

function fromDatabase(item, match) {
  const unit = unitOf(item);
  // Drinks: AFCD's per-100 mL profile when it has one, otherwise per 100 g taking 1 mL ≈ 1 g.
  const per = unit === "ml" && match.per100ml ? match.per100ml : match.per100;
  const f = num(item.grams, 0) / 100;
  return {
    name: item.name,
    grams: num(item.grams, 0),
    unit,
    calories: num(per.kcal * f, 0),
    protein_g: num(per.protein * f),
    carbs_g: num(per.carbs * f),
    fat_g: num(per.fat * f),
    source: match.source,
    match: match.name,
  };
}

function unitOf(item) {
  return String(item.unit ?? "").toLowerCase() === "ml" ? "ml" : "g";
}

function hasEstimate(item) {
  return Number.isFinite(Number(item.calories)) && Number(item.calories) > 0;
}

function normalize(item) {
  return {
    name: item.name,
    grams: num(item.grams, 0),
    unit: unitOf(item),
    calories: num(item.calories, 0),
    protein_g: num(item.protein_g),
    carbs_g: num(item.carbs_g),
    fat_g: num(item.fat_g),
  };
}
