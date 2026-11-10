# Calorie App — Roadmap

Android app (Kotlin, Jetpack Compose, Material 3) that logs calories and macros from food photos.
Everything must stay **free to run**: Cloudflare Workers AI (free daily allowance), AFCD and USDA data,
no paid APIs, no Play Store fee.

- App: `app/` — CameraX, Room, Retrofit. Data stays on the phone.
- Worker: `worker/` — `POST /analyze` (photo) and `POST /lookup` (name + amount), deployed at
  `https://calorie-analyzer.calorie-analyzer.workers.dev`. Holds the USDA key as a Cloudflare secret.
- Nutrition source order: **AFCD** (Australian, bundled in the Worker) → **USDA FoodData Central** → **AI estimate**.

## Status

### Original steps

| Step | Status |
|---|---|
| 1. Camera screen | ✅ Done, tested on Pixel 7 Pro |
| 2. Photo → Worker → items | ✅ Done, tested |
| 3. Edit name/amount, live totals, Save to Room | ✅ Done, tested |
| 4. Daily log + calorie/macro goals | ⬜ Next |

### Added along the way

- ✅ AFCD-first nutrition lookup with USDA fallback; source shown per item (AFCD / USDA / AI estimate)
- ✅ Renaming an item looks up the new food's nutrition
- ✅ **Update nutrition** button so renamed items are reviewed before **Save**
- ✅ g / mL per item; drinks use AFCD's per-100 mL profiles (confirmed working)
- ✅ Room schema v3 with auto-migrations (existing meals preserved)

### Known gaps

- Nutrition-label photos give wrong results. The meal prompt doesn't read labels. Several label photos
  have been tried through the app. Test case kept outside the repo: Monster Ultra can, 500 mL.
  Per serving: 60 kJ / 14 Cal, protein 0, fat 0, carbs 7.0 g. Per 100 mL: 12 kJ / 3 Cal, carbs 1.4 g.
  The rows are skewed on the curved can.
- Portion sizes are the photo model's guess, so the amount field needs checking.
- Switching g ↔ mL relabels without rescaling (assumes ~1 g/mL).
- The Worker URL is open; anyone with it could use up the free daily allowance.
- There's no way to log a food without a photo, or to use an existing photo from the gallery.

## Plan

### Phase 0 — Housekeeping ✅
- [x] git repo + first commit
- [x] This roadmap

### Phase 2 — Step 4: Daily log *(doing first)*
- [ ] Log is the home screen: today's calories and macros against goals (rings/bars)
- [ ] Meals listed per day with their foods; delete meal / delete item
- [ ] Move between days (arrows / swipe), "Today" shortcut
- [ ] Camera button to add a meal; after Save, return to the log
- [ ] Settings: calorie + protein/carbs/fat goals, kcal / kJ display, Server URL
- [ ] About: data credits (FSANZ AFCD licence attribution, USDA)

### Phase 1 — Packaged foods & drinks
- [ ] Pick a photo from the gallery as well as taking one (also makes testing with saved images easy)
- [ ] **Label mode**: Meal / Label switch; read per-100 g/mL column + serving size; default one serving;
      warn when energy doesn't match 4P + 4C + 9F. Compare free vision models (Llama 4 Scout,
      Gemma 3, Mistral Small 3.1) on real label photos before choosing one
- [ ] **Barcode scanning**: ML Kit (on-device, free) + Open Food Facts (free, no key) for packaged items;
      label mode as fallback when a product isn't listed

### Phase 3 — Logging without photos
- [ ] Add a food by typing name + amount (uses `/lookup`)
- [ ] Edit saved meals (amounts, names, remove items)
- [ ] Recent foods and favourites for one-tap re-logging

### Phase 4 — Choice of AI model
- [ ] Settings choice: free Llama (Workers AI) vs Gemini API free tier (key as a Worker secret)
- [ ] Automatic fallback when a provider hits its daily limit

### Phase 5 — Sharing with friends
- [ ] Signed release APK to send directly (no Play Store)
- [ ] Guide for a friend to deploy their own free Worker + set Server URL
- [ ] Protect the Worker: app token and per-device rate limiting

### Phase 6 — Optional
- [ ] Weekly / monthly charts
- [ ] CSV export / backup
- [ ] Home-screen widget
- [ ] Accounts with cloud sync (largest item; last)

## Working notes

- Build: `./gradlew assembleDebug` · tests: `./gradlew testDebugUnitTest` · install: `./gradlew installDebug`
  (phone over wireless debugging).
- Worker: `cd worker && npx wrangler deploy`. Rebuild AFCD data after downloading new FSANZ files:
  `python3 -I scripts/build_afcd.py "data/AFCD Release 3 - Nutrient profiles.xlsx" src/afcd.json`.
- Room schema changes: bump the version, add an `AutoMigration`, and commit the new file in `app/schemas/`.
