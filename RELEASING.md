# Releasing StreamForge

There is no app store. Distribution works in exactly two steps, and step 1 happens **once per
device, ever**:

1. **First install.** You hand the customer `streamforge.apk` (WhatsApp, a download link, a
   USB cable). This is the only time an APK is installed by hand.
2. **Every release after that** reaches them through the in-app updater. You upload the new
   APK and edit one JSON file; their phone offers the update within a day.

That means a shipped build has to be able to *reach* its own future. Two mistakes break it
permanently, and the build now refuses to produce a release APK if either is true:

- **No update channel** — the APK polls nothing, so those installs are stranded on that
  version with no way to reach them except asking each user to sideload again.
- **Wrong or missing signing key** — Android rejects an update signed with a different key.
  Users would have to uninstall first, losing their settings *and* their licence binding.

## The one versioning rule

`versionCode` **can never go down, or even repeat.** It is the only number Android compares;
`versionName` is a label for humans.

The 1.0.0 release is `versionCode = 4`, not 1, because builds already in the field are on
`versionCode = 3`. Naming it "1.0.0" while resetting the code to 1 would make every existing
install reject the update forever. **Let the name restart; never the code.**

| Release | versionName | versionCode |
| --- | --- | --- |
| 0.2.0 | 0.2.0 | 2 |
| 0.2.1 | 0.2.1 | 3 |
| **1.0.0** | **1.0.0** | **4** |
| next | 1.0.1 / 1.1.0 | 5 |

## One-time setup

1. **Back up the signing keystore.** It lives wherever
   `~/.gradle/streamforge-keystore.properties` points. Copy that keystore *and* the properties
   file somewhere off this machine.

   Android refuses to install an update signed with a different key. If this keystore is lost,
   every existing user has to uninstall (losing their settings) before they can install again.
   There is no recovery path.

2. **Pick where the manifest lives.** Any HTTPS URL that serves a static file works — a GitHub
   repo's raw URL, object storage, your own web server. GitHub Releases is the usual choice
   because it hosts the APK too.

3. **The app already points at it.** `app/build.gradle.kts` defaults
   `UPDATE_MANIFEST_URL` to:

   ```
   https://raw.githubusercontent.com/sanket5257/Stream_apk/main/version.json
   ```

   You only need to set `UPDATE_MANIFEST_URL` in `local.properties` if you host the manifest
   somewhere else. It has a default precisely so a forgotten line can't produce an APK that
   never checks for updates.

   The URL must be reachable **before** you ship a build that uses it — installed apps keep
   polling whatever URL they were compiled with, forever.

## Every release

1. **Bump both numbers** in `app/build.gradle.kts`:

   ```kotlin
   versionCode = 5          // MUST increase — the only value Android compares
   versionName = "1.0.1"    // shown to users
   ```

   Android rejects an APK whose `versionCode` is equal to or lower than the installed one. A
   forgotten bump looks like "the update silently didn't install".

2. **Build the signed release:**

   ```bash
   ./gradlew :app:assembleRelease
   ```

   Output: `app/build/outputs/apk/release/streamforge.apk`

3. **Verify it's signed with the right key** (paranoia is cheap here):

   ```bash
   "$LOCALAPPDATA/Android/Sdk/build-tools/<ver>/apksigner.bat" verify --print-certs \
     app/build/outputs/apk/release/streamforge.apk
   ```

   The SHA-256 must match previous releases. If it doesn't, stop — publishing it would strand
   every existing user.

4. **Upload the APK** to a GitHub release tagged `v1.0.1` (or wherever you host).

5. **Update the manifest** at `UPDATE_MANIFEST_URL`. That URL resolves to **`version.json` at
   the repo root on `main`** — that exact file is what every installed app polls, so it has to
   be committed and pushed, not just edited locally.

   > `release/` is in `.gitignore`, so a manifest left in `release/version.json` is invisible
   > to the updater. Edit the root `version.json`.

   ```json
   {
     "versionCode": 5,
     "versionName": "1.0.1",
     "apkUrl": "https://github.com/sanket5257/Stream_apk/releases/download/v1.0.1/streamforge.apk",
     "notes": "What changed, one bullet per line",
     "mandatory": false
   }
   ```

   | Field | Meaning |
   | --- | --- |
   | `versionCode` | Must match the APK's. The app compares this against its own `BuildConfig.VERSION_CODE`. |
   | `versionName` | Shown in the update dialog title. |
   | `apkUrl` | Direct HTTPS link to the APK. Redirects are followed, including cross-host. |
   | `notes` | Changelog shown in the dialog. `\n` for line breaks. |
   | `mandatory` | `true` removes "Later" and makes the dialog non-dismissable. Use sparingly. |

   Publish the manifest **after** the APK is live — the app checks the JSON first and will
   fail the download if the URL 404s.

## What users see

- **On launch** (Home screen): a silent check, at most once per 12 hours. It only interrupts
  when there's a newer version, and remembers a "Later" so the same version isn't re-offered.
- **Profile → App updates**: an explicit check that always answers, including "You're on the
  latest version".
- Downloading shows progress, then Android's own install screen. The app cannot install
  silently — the user confirms every time, by design.
- **First update only**: Android 8+ asks the user to allow installs from StreamForge. The app
  detects this and offers to open the right settings page; they tap Update again afterwards.

## Notes

- The downloaded APK goes to the app's cache dir and is shared with the installer through a
  `FileProvider` (`${applicationId}.updates`) — no storage permission involved.
- Play Protect may show a "scan this app?" prompt on first sideload. Normal for non-Play apps.
- If you later publish to Google Play, Play App Signing re-signs with a **different** key, so
  sideloaded users would have to uninstall first. If Play is on the roadmap, enroll in Play
  App Signing from the start and distribute builds of that same upload key.
