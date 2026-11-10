// POST /analyze { image: "<base64 jpeg>", mime_type?: "image/jpeg" }
//   -> { items: [{ name, grams, calories, protein_g, carbs_g, fat_g, source, match }] }
//   with mode: "label" -> reads a nutrition information panel instead: one item, source "label", maybe a `warning`
// POST /barcode { code: "9300675024235" } -> { items: [one product] }, source "off" (Open Food Facts), maybe a `warning`;
//   404 "product not found" when Open Food Facts doesn't have it
// POST /lookup  { name: "rice", grams: 180, unit?: "g" | "ml" }
//   -> { item: { name, grams, calories, protein_g, carbs_g, fat_g, source, match } }
//
// Requests that use AI accept provider: "workers-ai" (default) | "gemini" — the one to try first; the other is
// the fallback. Responses then include provider (the one that answered) and fallback: true if it wasn't the first.
//
// source: "afcd" (Australian Food Composition Database), "usda" (USDA FoodData Central) or "ai" (model estimate).
import { makeAi, num, parseJson, PROVIDERS } from "./model.js";
import { resolveNutrition, UnknownFoodError } from "./nutrition.js";
import { LABEL_PROMPT, labelToItem } from "./label.js";
import { lookupBarcode } from "./barcode.js";

const VISION_PROMPT = `You identify food in meal photos. List each distinct food item.
For each item:
- name: a short generic name using Australian food terms, including how it is prepared
  when visible (e.g. "white rice, boiled", "beef mince, cooked", "capsicum, raw", "hot chips").
  In Australia "tomato sauce" means the ketchup-style condiment: call a sauce on pasta
  "tomato pasta sauce" (or "bolognese sauce" if it contains mince).
- unit: "ml" for drinks and other liquids you would pour (water, milk, juice, soft drink, coffee, beer, wine),
  otherwise "g".
- grams: estimated amount in that unit from visual cues (plate size, glass/can/bottle size, typical servings).
- calories, protein_g, carbs_g, fat_g: your estimate for that portion.

Respond with ONLY a JSON object, no prose, no markdown fences, in exactly this shape:
{"items":[{"name":"string","unit":"g","grams":number,"calories":number,"protein_g":number,"carbs_g":number,"fat_g":number}]}
If no food is visible, return {"items":[]}.`;

const MAX_IMAGE_BASE64_CHARS = 6_000_000; // ~4.5 MB JPEG
const MAX_ITEMS = 12;

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    // Local `wrangler dev` only (ALLOW_MODEL_OVERRIDE is never deployed): list the Gemini models the key can use.
    if (pathname === "/dev/gemini-models" && env.ALLOW_MODEL_OVERRIDE) {
      const res = await fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", {
        headers: { "x-goog-api-key": env.GEMINI_API_KEY ?? "" },
      });
      const data = await res.json();
      return json((data.models ?? []).filter((m) => m.supportedGenerationMethods?.includes("generateContent"))
        .map((m) => m.name));
    }
    const route = { "/analyze": analyze, "/lookup": lookup, "/barcode": barcode }[pathname];
    if (!route) return json({ error: "not found" }, 404);
    if (request.method !== "POST") return json({ error: "use POST" }, 405);

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "body must be JSON" }, 400);
    }
    try {
      return await route(body ?? {}, env);
    } catch (err) {
      return json({ error: "model call failed", detail: String(err) }, 502);
    }
  },
};

async function analyze(body, env) {
  const image = typeof body.image === "string" ? body.image.replace(/^data:[^,]+,/, "") : "";
  const mime = typeof body.mime_type === "string" ? body.mime_type : "image/jpeg";
  if (!image) return json({ error: "missing 'image' (base64)" }, 400);
  if (image.length > MAX_IMAGE_BASE64_CHARS) return json({ error: "image too large" }, 413);
  const ai = makeAi(env, providerOf(body), devOptions(env, body));
  if (body.mode === "label") return analyzeLabel(body, ai, env, image, mime);

  const response = await ai.run([
    { role: "system", content: VISION_PROMPT },
    {
      role: "user",
      content: [
        { type: "text", text: "Analyze this meal." },
        { type: "image_url", image_url: { url: `data:${mime};base64,${image}` } },
      ],
    },
  ]);

  const parsed = parseJson(response);
  const list = Array.isArray(parsed) ? parsed : parsed?.items;
  if (!Array.isArray(list)) {
    return json({ error: "could not parse model output", raw: String(response ?? "").slice(0, 500) }, 502);
  }
  const detected = list
    .filter((it) => it && typeof it.name === "string" && it.name.trim() && num(it.grams, 0) > 0)
    .slice(0, MAX_ITEMS)
    .map((it) => ({ ...it, name: it.name.trim() }));

  const items = await resolveNutrition(env, ai, detected);
  return json({ items, ...aiInfo(ai) });
}

async function analyzeLabel(body, ai, env, image, mime) {
  // Model override is for comparing models on a local `wrangler dev` only (ALLOW_MODEL_OVERRIDE is never deployed).
  const override = env.ALLOW_MODEL_OVERRIDE && typeof body.model === "string" ? body.model : null;
  const model = override ?? env.LABEL_MODEL ?? env.MODEL; // Workers AI model; Gemini uses GEMINI_MODEL
  const response = await ai.run([
    { role: "system", content: LABEL_PROMPT },
    {
      role: "user",
      content: [
        { type: "text", text: "Transcribe this nutrition information panel." },
        { type: "image_url", image_url: { url: `data:${mime};base64,${image}` } },
      ],
    },
  ], 700, { model });

  const parsed = parseJson(response);
  const item = labelToItem(parsed);
  const debug = override ? { raw: parsed ?? String(response ?? "").slice(0, 800) } : {};
  if (!item) {
    return json({ error: "no label", detail: "Couldn't read a nutrition panel in this photo.", ...debug }, 422);
  }
  return json({ items: [item], ...aiInfo(ai), ...debug });
}

async function barcode(body) {
  const code = typeof body.code === "string" ? body.code.trim() : "";
  if (!/^\d{8,14}$/.test(code)) return json({ error: "'code' must be an 8–14 digit barcode" }, 400);
  let item;
  try {
    item = await lookupBarcode(code);
  } catch (err) {
    return json({ error: "barcode lookup failed", detail: String(err) }, 502);
  }
  if (!item) return json({ error: "product not found", detail: `Barcode ${code} isn't in Open Food Facts.` }, 404);
  return json({ items: [item] });
}

async function lookup(body, env) {
  const name = typeof body.name === "string" ? body.name.trim() : "";
  const grams = num(body.grams, 1);
  if (!name) return json({ error: "missing 'name'" }, 400);
  if (!(grams > 0)) return json({ error: "'grams' must be > 0" }, 400);

  try {
    const unit = body.unit === "ml" ? "ml" : "g";
    const ai = makeAi(env, providerOf(body), devOptions(env, body));
    const [item] = await resolveNutrition(env, ai, [{ name, grams, unit }]);
    return json({ item, ...aiInfo(ai) });
  } catch (err) {
    if (err instanceof UnknownFoodError) return json({ error: "unknown food", detail: err.message }, 404);
    throw err;
  }
}

/** Local `wrangler dev` only: try a specific Gemini model. */
function devOptions(env, body) {
  return env.ALLOW_MODEL_OVERRIDE && typeof body.gemini_model === "string" ? { geminiModel: body.gemini_model } : {};
}

function providerOf(body) {
  return PROVIDERS.includes(body.provider) ? body.provider : "workers-ai";
}

/** Which AI answered (null if none was needed, e.g. every food matched without asking a model). */
function aiInfo(ai) {
  return { provider: ai.provider, fallback: ai.fellBack };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}
