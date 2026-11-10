// Search over the bundled Australian Food Composition Database (FSANZ, Release 3).
// Data: Food Standards Australia New Zealand (2025). Australian Food Composition Database -
// Release 3. Licensed by FSANZ under a licence based on CC BY-SA 3.0 AU.
import RAW from "./afcd.json";
import { PREP, tokens } from "./text.js";

// Forms that are rarely what's on a plate; ranked lower unless the query asks for them.
const UNEATEN_FORMS = ["dry", "uncooked", "powder", "mix", "dehydrated"];

let index; // built lazily, once per isolate

function getIndex() {
  if (!index) {
    index = RAW.map(([key, name, kcal, protein, fat, carbs, ml]) => {
      const [head] = name.split(",");
      // "boiled from dry" describes a cooked food; don't let "dry" mark it as an uneaten form.
      const searchable = name.replace(/\bfrom dry\b/gi, "");
      return {
        key, name, per100: { kcal, protein, fat, carbs },
        per100ml: ml ? { kcal: ml[0], protein: ml[1], fat: ml[2], carbs: ml[3] } : null,
        head: new Set(tokens(head, { stopwords: false })),
        all: new Set(tokens(searchable, { stopwords: false })),
      };
    });
  }
  return index;
}

/** Best-scoring AFCD foods for a free-text name, best first. */
export function searchAfcd(query, limit = 12) {
  const q = [...new Set(tokens(query))];
  if (!q.length) return [];
  const qSet = new Set(q);
  const wantsUneaten = UNEATEN_FORMS.some((w) => qSet.has(w));
  const scored = [];
  for (const food of getIndex()) {
    let score = 0;
    let exact = 0; // matched non-preparation words
    for (const t of q) {
      const prep = PREP.has(t);
      if (food.head.has(t)) { score += prep ? 1 : 3; if (!prep) exact++; }
      else if (food.all.has(t)) { score += prep ? 1 : 1.5; if (!prep) exact++; }
      else if (prep) continue;
      else if (t.length >= 4) {
        for (const w of food.all) {
          if (w.length >= 4 && (w.startsWith(t) || t.startsWith(w))) { score += 0.75; break; }
        }
      }
    }
    if (!exact) continue;
    for (const h of food.head) if (!qSet.has(h)) score -= 0.5; // "Rice beverage" is not "rice"
    if (!wantsUneaten && UNEATEN_FORMS.some((w) => food.all.has(w))) score -= 2;
    score -= 0.05 * food.all.size; // prefer simpler, more generic entries on ties
    scored.push({ food, score });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit).map(({ food }) => ({
    source: "afcd", name: food.name, per100: food.per100, per100ml: food.per100ml,
  }));
}
