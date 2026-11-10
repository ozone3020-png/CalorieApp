// USDA FoodData Central search (generic foods only: Foundation + SR Legacy), values per 100 g.
const ENERGY_IDS = [1008, 2047, 2048]; // Energy (kcal), then Atwater general/specific
const PROTEIN = 1003, FAT = 1004, CARBS = 1005;

export async function searchUsda(env, query, limit = 5) {
  if (!env.USDA_API_KEY) return [];
  const url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search");
  url.searchParams.set("api_key", env.USDA_API_KEY);
  url.searchParams.set("query", query);
  url.searchParams.set("dataType", "Foundation,SR Legacy");
  url.searchParams.set("pageSize", String(limit));
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!res.ok) return [];
    const { foods = [] } = await res.json();
    return foods.map(toCandidate).filter(Boolean);
  } catch {
    return []; // USDA is a fallback; never fail the request because of it
  }
}

function toCandidate(food) {
  const byId = new Map();
  for (const n of food.foodNutrients ?? []) {
    if (n.unitName?.toUpperCase() === "KJ") continue;
    if (!byId.has(n.nutrientId)) byId.set(n.nutrientId, Number(n.value));
  }
  const kcal = ENERGY_IDS.map((id) => byId.get(id)).find((v) => Number.isFinite(v));
  if (!Number.isFinite(kcal)) return null;
  return {
    source: "usda",
    name: food.description,
    per100: {
      kcal,
      protein: byId.get(PROTEIN) ?? 0,
      fat: byId.get(FAT) ?? 0,
      carbs: byId.get(CARBS) ?? 0,
    },
  };
}
