# Releasing StreamForge without the Play Store

The app updates itself from a JSON manifest you host. Publishing a release is: bump the
version, build, upload the APK, edit the JSON.

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

3. **Point the app at it.** Add to `local.properties` (not committed):

   ```properties
   UPDATE_MANIFEST_URL=https://raw.githubusercontent.com/<you>/<repo>/main/release/version.json
   ```

   Left unset, the updater stays completely silent — no checks, no UI noise. So this must be
   set for the build you distribute, and the URL must already be reachable when that build
   ships, since older installs will keep polling whatever URL they were built with.

## Every release

1. **Bump both numbers** in `app/build.gradle.kts`:

   ```kotlin
   versionCode = 2        // MUST increase — this is the only value Android compares
   versionName = "0.2.0"  // shown to users
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

4. **Upload the APK** to a GitHub release tagged `v0.2.0` (or wherever you host).

5. **Update the manifest** at `UPDATE_MANIFEST_URL` — `release/version.json` in this repo is
   the template:

   ```json
   {
     "versionCode": 2,
     "versionName": "0.2.0",
     "apkUrl": "https://github.com/<you>/<repo>/releases/download/v0.2.0/streamforge.apk",
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
