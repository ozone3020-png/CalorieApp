# Calorie App

An Android app for logging what you eat. Photograph a meal, read a nutrition label or scan a barcode; check
and edit what it found; and it tracks your calories (or kilojoules) and macros against your goals.

It's free to run: no subscriptions, no paid APIs and no account. Your log stays on your phone.

**[Download the latest APK](https://github.com/ozone3020-png/CalorieApp/releases/latest)** · Android 8.0 or newer

---

## Contents

- [Install](#install)
- [Using the app](#using-the-app)
- [How it works](#how-it-works)
- [Run your own server (free)](#run-your-own-server-free)
- [Google Gemini API key (optional)](#google-gemini-api-key-optional)
- [Building the app yourself](#building-the-app-yourself)
- [Privacy](#privacy)
- [Licences and data sources](#licences-and-data-sources)

## Install

1. On your Android phone, open the [latest release](https://github.com/ozone3020-png/CalorieApp/releases/latest)
   and download the `.apk` file.
2. Open the downloaded file. Android asks to allow installs from your browser (or Files app): allow it, then
   tap **Install**.
3. If **Google Play Protect** says the app is from an unknown developer, tap **More details → Install anyway**.
   This happens because the app isn't on the Play Store, not because something is wrong with it.
4. Open **Calorie App**, tap **⚙** and set your daily goals.

**Updating:** install the newer `.apk` over the old one. Your log is kept.

## Using the app

### The log (home screen)

Today's energy is shown as a ring against your goal, with protein, carbs and fat bars beneath and every meal
listed by time (Breakfast, Lunch, …).

- **‹ ›** or tap the date to move between days. Anything you add while viewing a past day is logged on that day.
- **Tap a meal** to edit it: change amounts or names, add or remove foods.
- **🗑 / ⊖** delete a meal or a single food. **Undo** appears for a few seconds.
- **📊** opens Trends; **⚙** opens Settings.

### Adding food

Tap **Add meal** to open the camera. It has three ways in:

| | How | Best for |
|---|---|---|
| **Meal** | Photograph your plate | Home cooking, restaurant meals |
| **Label** | Switch to *Label* and photograph the nutrition information panel, flat on and filling the frame | Packaged food not in the barcode database |
| **Barcode** | Tap the barcode button (bottom right) | Most packaged food and drinks |

The gallery button (bottom left) uses a photo you've already taken.

Or tap **✎** on the log to **type a food in** ("banana", "flat white", "rice") or pick one of your
★ **favourites** or **recent** foods for one-tap logging.

### Checking the results

You'll always see what was found before anything is saved:

- **Amounts** are the AI's guess from the photo. Check them: portion size is the biggest source of error.
  Tap **g / mL** to switch units.
- **Rename** a wrong item (say "mashed potatoes" that was really rice), then tap **Update nutrition** to get the
  new food's numbers. Review them, then **Save**.
- Under each item you can see **where its numbers came from**: *AFCD*, *USDA*, *Label*, *Open Food Facts* or
  *AI estimate*. A red warning means label or barcode numbers didn't add up: check them against the package.

### Trends

Daily energy for 7, 30 or 90 days against your goal line, your average per logged day, days within goal and
average macros. Tap a bar to see that day, then **Open day** to jump to it.

### Widget

Long-press your home screen → **Widgets** → **Calorie App** → **Today's calories**. It shows today's total,
what's left and your macros, and updates as you log.

### Settings

- **AI model**: *Llama 4 Scout* or *Gemini Flash* (usually more accurate). If one is unavailable or over its free
  limit, the other is used automatically.
- **Energy unit**: kcal or kJ, used everywhere.
- **Daily goals**: default to the reference values Australian labels use (8700 kJ, 50 g protein, 310 g carbs,
  70 g fat). Set your own.
- **Server**: leave blank to use the built-in server, or enter [your own](#run-your-own-server-free).
- **Sync across devices**: see [Privacy](#privacy).
- **Your data**: **Back up** (a file you save anywhere, e.g. Google Drive), **Restore**, and **Export
  spreadsheet (CSV)**.

## How it works

```
 Phone (the app)                                Cloudflare Worker (free)                    Data
 ───────────────                                ────────────────────────                    ────
 photo ── shrunk to ~1024 px ──► /analyze ──► vision AI names the foods + guesses grams
                                              each food is matched against ─────────────► AFCD (Australian, bundled)
                                                                                         ► USDA FoodData Central
                                              no match? the AI estimates it
 label photo (1600 px) ────────► /analyze ──► AI transcribes the panel; numbers are cross-checked
 barcode ──────────────────────► /barcode ──► ──────────────────────────────────────────► Open Food Facts
 typed food ───────────────────► /lookup  ──► same matching as photos
 encrypted log ────────────────► /sync    ──► stored as unreadable ciphertext ──────────► Cloudflare D1
```

- **The AI only names foods and guesses amounts.** Calories and macros come from real food databases when
  possible: first the **Australian Food Composition Database**, then **USDA**. Only foods found in neither use
  an AI estimate, and those are labelled.
- **AI models** run on Cloudflare Workers AI (Meta's open Llama 4 Scout, free daily allowance) or Google Gemini
  (free API tier). Each is the other's fallback.
- **No API keys are in the app.** They live as encrypted secrets in the Worker.
- **The log is a database on your phone** (Room/SQLite). The server never holds it, except as encrypted sync
  data if you turn sync on.

## Run your own server (free)

By default the app uses a shared server, which means sharing its free daily AI allowance. Running your own
takes about 10 minutes and costs nothing.

**You need:** a computer with [Node.js](https://nodejs.org) 20 or newer, and a free
[Cloudflare account](https://dash.cloudflare.com/sign-up). No credit card is needed.

```bash
git clone https://github.com/ozone3020-png/CalorieApp.git
cd CalorieApp/worker
npm install
npx wrangler login            # opens a browser to sign in to Cloudflare
```

**1. Sync database (needed once).** Create it, then replace the `database_id` in `wrangler.toml` with the id it
prints:

```bash
npx wrangler d1 create calorie-sync
npx wrangler d1 execute calorie-sync --remote --file schema.sql
```

**2. API keys (optional, both free).** Each command asks you to paste the key; it's stored encrypted on
Cloudflare, never in the code:

```bash
npx wrangler secret put USDA_API_KEY      # https://fdc.nal.usda.gov/api-key-signup (better nutrition matches)
npx wrangler secret put GEMINI_API_KEY    # see the next section
```

Without a USDA key, foods not in the Australian database fall back to AI estimates. Without a Gemini key, the
app uses Llama only.

**3. Deploy.**

```bash
npx wrangler deploy
```

It prints your address, e.g. `https://calorie-analyzer.<your-name>.workers.dev`.

**4. Point the app at it.** In the app: **⚙ Settings → Server → Server URL**, paste the address, tap **Test
connection** (it should say *Working*), then **Save settings**.

> Don't set an `APP_TOKEN` secret on your own server. That's for locking a server to one specific build of the
> app; a server without it accepts the app normally.

**Free limits**, which are plenty for personal use: Workers AI includes 10,000 "neurons" a day (roughly a
hundred photos), resetting at 00:00 UTC. Workers and D1 have generous free daily request quotas. On the free
plans nothing is ever charged; at worst a limit is reached until the next day.

## Google Gemini API key (optional)

Gemini is generally faster and more accurate than Llama at identifying food and reading labels.

1. Go to **[Google AI Studio → API keys](https://aistudio.google.com/apikey)** and sign in with a Google account.
2. Click **Create API key** and copy it. No card is needed for the free tier.
3. Add it to your server: `npx wrangler secret put GEMINI_API_KEY` (in the `worker` folder), then paste the key.
4. In the app: **⚙ Settings → AI model → Gemini Flash → Save settings**.

Notes:
- A Gemini *app* subscription (Google AI Pro) doesn't include API access. The free API tier is separate and has
  its own daily limit. Once it's reached, the app falls back to Llama until the limit resets.
- Google may use free-tier API requests to improve its products.
- Google retires older models for new keys. The model is set by `GEMINI_MODEL` in `worker/wrangler.toml`
  (a comma-separated list, tried in order). If Gemini stops working, the server log
  (`npx wrangler tail`) shows Google's error, which usually names the model to use instead.

## Building the app yourself

Needs JDK 17 and the Android SDK (platform 35). Android Studio has both.

```bash
# point the app at your server (must end with /)
echo 'WORKER_URL=https://calorie-analyzer.<your-name>.workers.dev/' >> gradle.properties

./gradlew installDebug          # build and install on a phone connected with USB debugging
./gradlew testDebugUnitTest     # run the unit tests
```

Release builds (`./gradlew assembleRelease`) need your own signing key in a `keystore.properties` file. See
`SHARING.md` for that and for sharing the app with friends. `ROADMAP.md` records how the app was built and
what's planned.

**Layout:** `app/` is the Android app (Kotlin, Jetpack Compose, Material 3, CameraX, Room, Retrofit, Glance).
`worker/` is the Cloudflare Worker (JavaScript), with `scripts/` to rebuild the bundled food database from
FSANZ's files.

## Privacy

- **Your log stays on your phone.** There's no account, sign-in or analytics.
- **Photos** are shrunk and sent to the server for analysis, then discarded. They aren't stored. The app deletes
  its own copy once you save or discard the meal.
- **Sync (optional)** works with a *sync key* that the app generates, which acts as your account. Your log is
  **encrypted on the phone** (AES-256-GCM) before upload, so the server, and whoever runs it, sees only
  unreadable data. Enter the same key on another phone to sync it. **Keep the key safe and private:** it's the
  only way back to your synced log, and anyone with it can read your log.
- **Gemini:** if you choose it, photos and food names are sent to Google's API, whose free tier may use them to
  improve Google's products.

## Licences and data sources

- **Code:** [MIT License](LICENSE).
- **Australian Food Composition Database:** Food Standards Australia New Zealand (2025), *Australian Food
  Composition Database – Release 3*. Licensed by FSANZ under a licence based on CC BY-SA 3.0 AU. The bundled
  derivative `worker/src/afcd.json` is under the same licence.
- **USDA FoodData Central:** U.S. Department of Agriculture, public domain.
- **Open Food Facts:** openfoodfacts.org, Open Database License. It's crowd-sourced, so check barcode results
  against the package.

Nutrition figures are estimates, especially portion sizes from photos. This app isn't medical advice.
