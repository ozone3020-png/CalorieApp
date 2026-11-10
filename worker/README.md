# calorie-analyzer worker

Backend for the app. It needs no API key in the app and runs on Cloudflare's free plan.

| Endpoint | Body | Returns |
|---|---|---|
| `POST /analyze` | `{ "image": "<base64 jpeg>" }` | `{ items: [{ name, grams, unit, calories, protein_g, carbs_g, fat_g, source, match }] }` |
| `POST /lookup` | `{ "name": "rice", "grams": 180, "unit": "g" }` | `{ item: { …same fields… } }`, or 404 `unknown food` |

`unit` is `"g"` or `"ml"` (then `grams` holds millilitres). `source` says where the numbers came from:

1. **`afcd`**: the Australian Food Composition Database (bundled as `src/afcd.json`). Drinks use its per-100 mL profiles.
2. **`usda`**: USDA FoodData Central (Foundation + SR Legacy), used when AFCD has no good match.
3. **`ai`**: the model's own estimate, used when neither database has the food.

The vision model (Workers AI, `MODEL` in `wrangler.toml`) only identifies foods and estimates amounts.
A short text-only call then picks the best database entry from the search candidates.

## Deploy (free Cloudflare account)

    npm install
    npx wrangler login
    npx wrangler secret put USDA_API_KEY   # free key: https://fdc.nal.usda.gov/api-key-signup
    npx wrangler deploy

Put the printed `https://…workers.dev/` URL into `WORKER_URL` in the root `gradle.properties`.

## AFCD data

`src/afcd.json` is generated from FSANZ's Excel downloads. You have to accept FSANZ's licence on their site,
so those files aren't in the repo. To regenerate after a new release, download **Nutrient profiles** from
https://www.foodstandards.gov.au/science-data/food-nutrient-databases/afcd/data-files into `data/`, then run:

    python3 -I scripts/build_afcd.py "data/AFCD Release 3 - Nutrient profiles.xlsx" src/afcd.json

Attribution: Food Standards Australia New Zealand (2025). *Australian Food Composition Database – Release 3*.
Licensed by FSANZ under a licence based on CC BY-SA 3.0 AU.
`src/afcd.json` is a derivative of that database and is distributed under the same licence (CC BY-SA 3.0 AU);
the rest of this repository is not covered by it.

## Test

    curl -s -X POST https://calorie-analyzer.<you>.workers.dev/lookup \
         -H 'content-type: application/json' -d '{"name":"rice","grams":180}'

## Cost

Workers AI includes a free daily allowance (10,000 neurons/day, resets 00:00 UTC). A photo uses roughly
50–100 neurons and a lookup much less. When the allowance runs out, requests fail until the reset; the free
plan never charges you. The USDA key allows 1,000 requests/hour.
