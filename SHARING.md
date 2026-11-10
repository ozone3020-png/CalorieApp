# Sharing Calorie App with a friend

Everything a friend logs stays on their own phone: no account, no sign-in, nothing shared with you.
By default their app uses **your** Worker (your free AI allowance and USDA key). That's fine for a few
friends. If they use it a lot, they can run **their own** free Worker instead (part 3).

## 1. Build the app to send (you)

    ./gradlew assembleRelease
    cp app/build/outputs/apk/release/app-release.apk release/CalorieApp-<version>.apk

The APK is signed with your release key (`~/.android/calorieapp-release.jks`; its passwords are in
`keystore.properties`, which git ignores).

> **Back up both files** somewhere safe (a password manager, or an encrypted USB stick).
> Updates only install over the old app if they're signed with the same key. If the key is lost, friends
> have to uninstall (losing their log) to install a newer version.

To check a release before sending it, `./gradlew installStaging` installs the same build as a separate
"Calorie App (test)" app with its own empty data. It doesn't touch your own log.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each new version you send.

## 2. Installing it (your friend)

1. Send them the `.apk` file (Google Drive, email, Messenger: anything that delivers a file).
2. On their Android phone (Android 8 or newer), open the file. Android asks to allow installs from
   that app (e.g. Files or Chrome): allow it, then tap **Install**.
3. Google Play Protect may say the app is from an unknown developer. Tap **More details → Install anyway**.
   This happens because the app isn't on the Play Store, not because something is wrong with it.
4. Open **Calorie App**, then **⚙ Settings** to set their goals and kcal/kJ.

**Updates:** send the new `.apk`; installing it over the old one keeps their log.

## 3. Their own Worker (optional, ~10 minutes)

This gives them their own free AI allowance, so neither of you uses up the other's.

What they need: a computer with Node.js 20+, a free Cloudflare account, and the Worker files
(`release/calorie-worker-<version>.zip`, which you send them).

    unzip calorie-worker-<version>.zip -d calorie-worker && cd calorie-worker
    npm install
    npx wrangler login                     # opens the browser: sign in or create a free Cloudflare account
    npx wrangler deploy                    # prints https://calorie-analyzer.<their-name>.workers.dev

Optional but recommended (both free):

    npx wrangler secret put USDA_API_KEY   # key from https://fdc.nal.usda.gov/api-key-signup
    npx wrangler secret put GEMINI_API_KEY # key from https://aistudio.google.com/apikey

Then in the app: **Settings → Server → Server URL**, paste their `https://…workers.dev` address,
tap **Test connection** (it should say *Working*), then **Save settings**.

They should **not** set `APP_TOKEN` on their Worker: the app already sends one, and a Worker without the secret
accepts it.

## How your Worker is protected

- **App token.** With the `APP_TOKEN` secret set, your Worker only answers requests carrying the token built
  into the app (from `appToken` in `local.properties`). Someone who only finds the URL gets "unauthorized".
  The token is inside the APK, so a determined person could extract it. It's a lock on the front door, not a
  safe.
- **Rate limit.** Each device (or IP address) gets about 20 requests a minute. That stops runaway scripts.
- **Your bill stays at $0.** Free plans don't charge. The worst case is the daily free AI allowance running
  out, after which requests fall back to the other provider or fail until the next day.

If the token ever leaks, generate a new one (`appToken` in `local.properties`), put it in the Worker
(`npx wrangler secret put APP_TOKEN`), rebuild, and send friends the new APK.
