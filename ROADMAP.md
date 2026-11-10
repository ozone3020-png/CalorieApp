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
| 4. Daily log + calorie/macro goals | ✅ Done, checked on the Pixel's screen |

### Added along the way

- ✅ AFCD-first nutrition lookup with USDA fallback; source shown per item (AFCD / USDA / AI estimate)
- ✅ Renaming an item looks up the new food's nutrition
- ✅ **Update nutrition** button so renamed items are reviewed before **Save**
- ✅ g / mL per item; drinks use AFCD's per-100 mL profiles (confirmed working)
- ✅ Room schema v3 with auto-migrations (existing meals preserved)

### Known gaps

- Label mode is tested on one real label so far (Monster Ultra can, 500 mL: 60 kJ / 14 Cal, carbs 7.0 g
  per serving; rows skewed on the curved can). It reads it exactly. The photo is kept outside the repo.
- Portion sizes are the photo model's guess, so the amount field needs checking.
- Switching g ↔ mL relabels without rescaling (assumes ~1 g/mL).
- The Worker URL is open; anyone with it could use up the free daily allowance.
- Meals saved before schema v2 have no source and show no tag.
- There's no way to log a food without a photo, or to use an existing photo from the gallery.

## Plan

### Phase 0 — Housekeeping ✅
- [x] git repo + first commit
- [x] This roadmap

### Phase 2 — Step 4: Daily log ✅
- [x] Log is the home screen: energy ring + protein/carbs/fat bars against goals
- [x] Meals per day (labelled Breakfast/Lunch/… by time) with foods, amounts, macros, source
- [x] Delete meal / delete food, with Undo (~10 s)
- [x] Previous/next day arrows and a date picker (no future days)
- [x] Add meal → camera → results → Save returns to the log on today
- [x] Settings: kcal / kJ everywhere, goals (default: Australian label reference 8700 kJ / 50 P / 310 C / 70 F),
      Server URL with Test connection, About with FSANZ/USDA credits

### Phase 1 — Packaged foods & drinks
- [x] Pick a photo from the gallery as well as taking one (Android photo picker, no permission needed)
- [x] **Label mode**: Meal / Label switch; read per-100 g/mL column + serving size; default one serving;
      warn when energy doesn't match 4P + 4C + 9F. Compare free vision models (Llama 4 Scout,
      Gemma 3, Mistral Small 3.1) on real label photos before choosing one
      → Done. On the Monster can, Llama 4 Scout and Mistral Small read every value correctly at 1600 px
        (1024 px misread sugars); Gemma 3 isn't available to this account. Kept Llama 4 Scout, labels
        upload at 1600 px. Renaming a label item keeps the label's numbers. More label photos would help
        confirm the choice.
- [x] **Barcode scanning**: Google code scanner (Play services, no permission) + Open Food Facts via the
      Worker's `/barcode`. Drinks ≤ 750 mL default to the whole can/bottle, other foods to one serving (or 100 g).
      OFF is crowd-sourced: contradictory kJ/kcal resolve to whichever matches the macros, and energy that's way
      off the macros is replaced from them, both with a warning. Some records are simply wrong (e.g. Vegemite),
      so items are tagged "Open Food Facts"; label mode is the fallback. Scanner UI checked; a real scan not yet.

### Phase 3 — Logging without photos ✅
- [x] ✎ button on the log: Add food sheet. Type a food → lookup (AFCD → USDA → AI), or tap a recent/favourite
- [x] Tap a meal in the log to edit it (amounts, names, add/remove foods); Save changes keeps its time
- [x] Recent foods (latest version of each, most recently added first) and ★ favourites (DB v4)
- [x] New meals go on the day being viewed (now for today, 12:00 for a past day), so missed days can be filled in
- [x] Closing only asks to confirm when something would be lost

### Phase 4 — Choice of AI model
- [x] Settings choice: Llama 4 Scout (Workers AI) vs Gemini Flash (Google free tier, key as Worker secret
      `GEMINI_API_KEY`); sent as `provider` on each request
- [x] Automatic fallback to the other provider on any failure (limit, outage, no key); results say which AI ran
- [x] Tested (10 Nov 2026). gemini-2.5-flash is retired for new keys; 3.7/3.8-flash timed out on the free
      tier; **gemini-3.5-flash** answers in 2–7 s. `GEMINI_MODEL = "gemini-3.5-flash,gemini-3.8-flash"`
      (tried in order on 404/503). Spaghetti photo: Gemini 556 kcal (200 g pasta, bolognese as one sauce)
      vs Llama 989 kcal (300 g pasta, 50 g parmesan); both read the Monster label exactly; Gemini 3–5x faster.

### Phase 5 — Sharing with friends
- [x] Signed release APK (key in ~/.android, passwords in gitignored keystore.properties; shrunk to ~3.4 MB).
      `staging` build type = the release build installable alongside, for testing (checked on the Pixel)
- [x] SHARING.md: building, installing on a friend's phone, their own Worker, how the Worker is protected
- [x] Worker protection: `APP_TOKEN` secret checked against the app's X-App-Token (off until the secret is
      set); ~20 requests/min per device via Cloudflare's rate limiting binding
- [x] APP_TOKEN set (10 Nov): no/wrong token → 401; the app's Test connection → Working
- [ ] Owner: back up ~/.android/calorieapp-release.jks and keystore.properties

### Phase 6 — Extras ✅
- [x] Backup (JSON: meals, favourites, settings) / restore (skips meals already present) / CSV export, via the
      system file picker. Checked on the Pixel: backup of 5 meals; restoring it added 0 (duplicate check)
- [x] Trends: 7/30/90-day energy bars vs goal line (tap a bar → that day), averages, days within goal, macros
- [x] Home-screen widget (Glance): today's energy vs goal, left/over, macros; refreshes on any log change
- [x] Cloud sync. The sync key is the account; records are AES-256-GCM encrypted on the phone (server sees a
      hash of the key and ciphertext only). Worker `/sync` on Cloudflare D1 (`calorie-sync`, schema.sql),
      last-write-wins, tombstones for deletions; DB v5 (manual migration, tested on a real copy first).
      Checked end to end: test app ⇄ an independent Node client (add, edit, delete both ways)
- [ ] Owner: add the widget to the home screen; decide whether to turn sync on (and save the key safely)

### 0.6 — Typed meals, meal times, fibre and fruit & veg ✅
- [x] Type a whole meal in words (`/describe`): split into foods + amounts, then the usual AFCD → USDA → AI matching
- [x] "When" on every meal: Breakfast/Lunch/Dinner/Snack buttons, exact time and day; saved meals can be moved
- [x] Fibre everywhere (AFCD, USDA, Open Food Facts, labels, AI); goal 30 g
- [x] Fruit & veg serves (ADG: 150 g fruit, 30 g dried, 75 g veg/legumes; goals 2 and 5). AFCD food groups decide
      when matched (juice, hot chips, banana chips excluded), otherwise the AI; tap to correct
- [x] DB v6 (auto-migration, checked on the Pixel: log unchanged). Foods logged before 0.6 have 0 fibre and no group

### Ideas for later
- Sign in with Google as an alternative to the sync key (needs a Google Cloud project)
- Swipe between days on the log; weight tracking; reminders to log meals

## Working notes

- Build: `./gradlew assembleDebug` · tests: `./gradlew testDebugUnitTest` · install: `./gradlew installDebug`
  (phone over wireless debugging).
- Worker: `cd worker && npx wrangler deploy`. Rebuild AFCD data after downloading new FSANZ files:
  `python3 -I scripts/build_afcd.py "data/AFCD Release 3 - Nutrient profiles.xlsx" src/afcd.json`.
- Room schema changes: bump the version, add an `AutoMigration`, and commit the new file in `app/schemas/`.
- UI testing over adb: don't test delete/Undo against the user's real log. The Undo bar can time out
  before a scripted tap lands, and the tap then hits whatever is underneath. Use a test day/meal, or
  back up the database first (`adb exec-out run-as com.calorieapp cat databases/calorie.db`).
