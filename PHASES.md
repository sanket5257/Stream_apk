# StreamForge — Build Phases

**Project:** Android APK for streaming to YouTube Live from phone camera with custom image + text overlays.
**Team:** 2 developers, neither knows Kotlin. Both rely on an AI IDE to execute phases.
**App ID:** `com.streamforge.app`
**Stack:** Native Android (Kotlin) + Gradle + RootEncoder + RTMP.

---

## How to use this file with an AI IDE

This file is the **single source of truth**. When you want to do work, copy the **entire phase block** (from `## Phase NX` to the next `---`) and paste it into your AI IDE with the prompt:

> Execute this phase exactly as specified. Use the file paths, dependencies, and APIs given. After each file change, summarize what you did. Do not skip the Acceptance Criteria — run them and confirm output before claiming the phase is complete.

### Rules the AI IDE must follow
1. **Never invent file paths.** Use only the paths listed in "Files to create" / "Files to modify".
2. **Never invent library versions.** All versions live in `gradle/libs.versions.toml`. If a new dep is needed and not listed, the phase will say to add it there first.
3. **Never invent RootEncoder APIs.** Always cross-check against current examples at https://github.com/pedroSG94/RootEncoder/tree/master/app or the library's README. If unsure, stop and ask the user.
4. **Never push to `main`.** Always create the branch listed in the phase.
5. **Don't combine phases.** One phase = one branch = one PR.
6. **Stop on failure.** If Acceptance Criteria fail, do NOT claim done. Report what failed and ask.

### Rules for the humans (you two)
- Decide who is Person A and who is Person B *before* Phase 2. Stick to it.
- Always `git pull origin main` before starting a new phase.
- One phase = one branch = one PR. The *other* person reviews and merges.
- Comment on the PR with a screenshot or logcat output proving Acceptance Criteria pass.

---

## Status

- [x] Scaffolding (build files, Manifest, MainActivity stub, resources, .gitignore) — created by AI before Phase 0.
- [ ] Phase 0 — Tooling install
- [ ] Phase 1 — Bootstrap Gradle wrapper + first build
- [ ] Phase 2A — Camera preview (Track A)
- [ ] Phase 2B — Stream config UI (Track B)
- [ ] Phase 3A — RTMP streaming (Track A)
- [ ] Phase 3B — Overlay editor view (Track B)
- [ ] Phase 4A — Foreground service (Track A)
- [ ] Phase 4B — Image overlay (Track B)
- [ ] Phase 5B — Text overlay (Track B)
- [ ] Phase 6 — Integration
- [ ] Phase 7 — Polish
- [ ] Phase 8 — Release build

---

## Phase 0 — Tooling Setup

- **Owner:** Both (independently on each machine)
- **Branch:** none (local machine setup, no git changes)
- **Depends on:** —
- **Parallel with:** —
- **Goal:** Each developer can run `adb devices` and see their Android phone, and `javac -version` shows JDK 17.

### Steps

1. **Install JDK 17** (NOT 22 — Android Gradle Plugin 8.5.x doesn't fully support JDK 22).
   - Windows: download Temurin 17 LTS from https://adoptium.net/temurin/releases/?version=17
   - Choose the `.msi` installer. During install, tick "Set JAVA_HOME" and "Add to PATH".
   - Verify: open a NEW terminal, run `javac -version` — must show `javac 17.x.x`.

2. **Install Android command-line tools.**
   - Download from https://developer.android.com/studio#command-line-tools-only (look for "Command line tools only", Windows zip)
   - Extract to `C:\Android\cmdline-tools\latest\` (the folder structure must end with `cmdline-tools\latest\bin\`)
   - Set environment variables (System Properties → Environment Variables):
     - `ANDROID_HOME` = `C:\Android`
     - Add to `PATH`: `%ANDROID_HOME%\cmdline-tools\latest\bin` and `%ANDROID_HOME%\platform-tools`
   - Open a NEW terminal (env vars only apply to new shells).

3. **Install Android SDK packages.** In a new terminal:
   ```
   sdkmanager --licenses
   ```
   (Press `y` for every prompt.)
   ```
   sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```

4. **Enable USB debugging on your phone.**
   - Settings → About phone → tap "Build number" 7 times.
   - Back → System → Developer options → enable "USB debugging".
   - Plug phone into PC via USB. Tap "Allow USB debugging" prompt on the phone.

5. **Verify phone connection.**
   ```
   adb devices
   ```

### Acceptance criteria
- `javac -version` outputs `javac 17.x.x`
- `adb --version` works
- `adb devices` lists your phone with status `device` (not `unauthorized`)

### Pitfalls
- JDK 22 silently installed? Run `where.exe javac` — if it shows the wrong path, fix `PATH` order so JDK 17 comes first.
- `adb devices` shows `unauthorized`? Unplug, re-plug, accept the prompt on the phone.
- Antivirus blocking adb? Add `platform-tools` to the antivirus exception list.

---

## Phase 1 — Bootstrap Gradle Wrapper + Verify First Build

- **Owner:** Person A
- **Branch:** `chore/bootstrap-wrapper`
- **Depends on:** Phase 0 (both)
- **Parallel with:** —
- **Goal:** A working `./gradlew assembleDebug` produces an APK, installs on phone, launches showing "StreamForge".

### Background for the AI IDE
The repo already contains: `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `AndroidManifest.xml`, `MainActivity.kt`, resources. **Missing:** `gradle/wrapper/gradle-wrapper.jar` (binary, can't be created by AI). Person A bootstraps it once.

### Files to create
- `gradle/wrapper/gradle-wrapper.jar` (binary, generated by `gradle wrapper` command)
- `gradlew` (shell script, generated by `gradle wrapper`)
- `gradlew.bat` (Windows batch, generated by `gradle wrapper`)
- `local.properties` (NOT committed; lists SDK path)

### Files to modify
- None.

### Steps for the human (one-time bootstrap)
1. Install standalone Gradle 8.9 (one time, can uninstall after):
   - Easiest: `winget install Gradle.Gradle`
   - OR download from https://gradle.org/releases/ → extract → add `bin/` to PATH
2. Verify: `gradle --version` shows Gradle 8.9 and JVM 17.
3. In the project root (`E:\Youtube`), run:
   ```
   gradle wrapper --gradle-version 8.9
   ```
   This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.
4. Create `local.properties` in the project root with this single line (Windows path with forward slashes or escaped backslashes):
   ```
   sdk.dir=C:/Android
   ```
5. Build the debug APK:
   ```
   .\gradlew assembleDebug
   ```
   First run will download ~500 MB (Gradle, AGP, Kotlin, libraries). Be patient. If RootEncoder fails to resolve, see Pitfalls.
6. Install on phone:
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   If it says "already installed with different signature," uninstall first: `adb uninstall com.streamforge.app.debug`.

### Acceptance criteria
- `.\gradlew assembleDebug` ends with `BUILD SUCCESSFUL`.
- An APK exists at `app/build/outputs/apk/debug/app-debug.apk`.
- After `adb install`, the **StreamForge** app appears in the phone's launcher.
- Tapping the app shows a screen with the heading "StreamForge" and subtitle "Project scaffolding ready. Build phases up next."

### Pitfalls
- **RootEncoder dependency fails to resolve.** Reason: it's hosted on JitPack, not Maven Central. Verify `settings.gradle.kts` has `maven { url = uri("https://jitpack.io") }` in `dependencyResolutionManagement.repositories`. (It does — but check.)
- **`local.properties` accidentally committed.** It's in `.gitignore` — do not force-add it.
- **Gradle JVM mismatch.** If `gradle --version` shows JVM != 17, set `JAVA_HOME` to your JDK 17 install before running.
- **`sdkmanager` license errors during build.** Re-run `sdkmanager --licenses` and accept all.

### Hand-off
After this phase merges, BOTH devs do `git pull origin main`. Person B does NOT need to install standalone Gradle — they only run `./gradlew` (the wrapper).

---

## Phase 2A — Camera Preview

- **Owner:** Person A
- **Branch:** `feature/camera-preview`
- **Depends on:** Phase 1
- **Parallel with:** Phase 2B
- **Goal:** A new `StreamActivity` shows live camera preview using RootEncoder's `OpenGlView`. User can switch front/back camera. No streaming yet.

### Files to create
- `app/src/main/java/com/streamforge/app/StreamActivity.kt`
- `app/src/main/java/com/streamforge/app/util/PermissionHelper.kt`
- `app/src/main/res/layout/activity_stream.xml`

### Files to modify
- `app/src/main/AndroidManifest.xml` — register `StreamActivity`.
- `app/src/main/java/com/streamforge/app/MainActivity.kt` — temporary "Open Stream" button to launch StreamActivity (will be replaced in 2B).
- `app/src/main/res/layout/activity_main.xml` — add the temp button.
- `app/src/main/res/values/strings.xml` — add `open_stream`, `switch_camera`, `permission_required` strings.

### Dependencies
None new. Already pulled in by Phase 1.

### Implementation spec

**`activity_stream.xml`** — full-screen black background. Contains:
- `com.pedro.library.view.OpenGlView` with `android:id="@+id/openGlView"`, `match_parent` width and height. Set `keepAspectRatio="true"` and `aspectRatioMode="adjust"` as XML attributes.
- A `MaterialButton` with id `btnSwitchCamera` pinned bottom-right, text "Switch".

**`PermissionHelper.kt`** — small utility:
- Function `hasCameraAndAudio(ctx: Context): Boolean` returning true if both `CAMERA` and `RECORD_AUDIO` permissions are granted.
- Function `requestCameraAndAudio(activity: ComponentActivity, onResult: (granted: Boolean) -> Unit)` that registers an `ActivityResultLauncher` for `RequestMultiplePermissions` and invokes the callback.

**`StreamActivity.kt`** — extends `AppCompatActivity`:
- Uses ViewBinding (`ActivityStreamBinding`).
- In `onCreate`, check permissions; if missing, request them; if denied, finish() and toast `permission_required`.
- Initialize `RtmpCamera2` (or whichever RootEncoder camera class the current examples use — verify at https://github.com/pedroSG94/RootEncoder/tree/master/app) with:
  - `connectChecker`: a no-op implementation of `ConnectChecker` interface (we wire up real logic in Phase 3A).
  - The `OpenGlView` from binding.
- In `surfaceCreated` of the OpenGlView's holder, call `startPreview(CameraHelper.Facing.BACK)` (or equivalent).
- In `surfaceDestroyed`, call `stopPreview()`.
- `btnSwitchCamera` onClick → call `switchCamera()`.
- Keep screen on while activity is in foreground (`window.addFlags(FLAG_KEEP_SCREEN_ON)`).
- Lock orientation to portrait (already set in manifest).

**AndroidManifest changes** — add inside `<application>`:
```xml
<activity
    android:name=".StreamActivity"
    android:exported="false"
    android:screenOrientation="portrait"
    android:theme="@style/Theme.StreamForge.Fullscreen" />
```

**MainActivity change** — add a `MaterialButton` to `activity_main.xml` with id `btnOpenStream`. In `MainActivity.onCreate`, set its click listener to:
```kotlin
startActivity(Intent(this, StreamActivity::class.java))
```

### Acceptance criteria
1. `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeds.
3. Launch app → tap "Open Stream" → permission dialog appears → grant both.
4. Camera preview fills the screen (back camera by default).
5. Tap "Switch" → preview changes to front camera; tap again → back camera.
6. Lock the phone for 2 seconds, unlock → preview resumes without crash.
7. `adb logcat -s StreamForge:* AndroidRuntime:E` shows no crashes during the test.

### Pitfalls
- **Permission denied permanently** ("Don't ask again"): handle by showing a Snackbar with a button to open app settings via `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))`.
- **`OpenGlView` blank**: usually means `startPreview()` was called before the surface was ready. Always start preview in `surfaceCreated`, never in `onCreate`.
- **App crashes on resume**: make sure `stopPreview()` is called in `onPause`, not `onDestroy`.
- **RootEncoder API class name mismatch**: package paths in RootEncoder 2.x are `com.pedro.library.rtmp.RtmpCamera2` and `com.pedro.library.view.OpenGlView`. Verify against the library's example app.

### Hand-off
Phase 3A will reuse `StreamActivity` and the same `RtmpCamera2` instance to call `prepareVideo()`, `prepareAudio()`, `startStream(url)`. Keep the camera instance as a top-level `private lateinit var`.

---

## Phase 2B — Stream Config UI

- **Owner:** Person B
- **Branch:** `feature/config-screen`
- **Depends on:** Phase 1
- **Parallel with:** Phase 2A
- **Goal:** `MainActivity` becomes a real config form. User enters RTMP URL + stream key, picks resolution and bitrate, values persist in encrypted DataStore.

### Files to create
- `app/src/main/java/com/streamforge/app/storage/StreamPrefs.kt`
- `app/src/main/java/com/streamforge/app/storage/StreamConfig.kt`

### Files to modify
- `app/src/main/java/com/streamforge/app/MainActivity.kt` — full rewrite into a config form.
- `app/src/main/res/layout/activity_main.xml` — full rewrite as a scrollable form.
- `app/src/main/res/values/strings.xml` — add labels and hints.

### Dependencies
Already in `libs.versions.toml`:
- `androidx-datastore-preferences`
- `androidx-security-crypto`

### Implementation spec

**`StreamConfig.kt`** — pure data class:
```kotlin
data class StreamConfig(
    val rtmpUrl: String,
    val streamKey: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int,
)
```
With a `companion object` `DEFAULT` = `StreamConfig("rtmp://a.rtmp.youtube.com/live2/", "", 1280, 720, 30, 3500, 128)`.

**`StreamPrefs.kt`** — wraps DataStore + EncryptedSharedPreferences for the stream key:
- Uses `androidx.datastore.preferences` for non-secret fields (URL, dims, bitrate).
- Uses `EncryptedSharedPreferences` (from `androidx.security:security-crypto`) for the stream key ONLY.
- Public API: `suspend fun load(): StreamConfig` and `suspend fun save(config: StreamConfig)`.
- Key the DataStore file `stream_prefs` and the encrypted prefs file `secure_prefs`.

**`activity_main.xml`** — vertical `ScrollView` containing a `LinearLayout` with `TextInputLayout`s and `MaterialButton`:
- TextInput "RTMP URL" (pre-filled with default)
- TextInput "Stream key" (password input type, `inputType="textPassword"`)
- Spinner / MaterialAutoCompleteTextView for resolution: "1280x720", "1920x1080", "854x480"
- SeekBar or slider for video bitrate (1000–8000 kbps), label updates live
- MaterialButton "Save & Go Live" → validate inputs, save config, launch `StreamActivity`

**`MainActivity.kt`** rewrite:
- ViewBinding for `ActivityMainBinding`.
- Load saved config in `onResume` via `lifecycleScope.launch` (DataStore is suspend).
- Save on button click; validate URL is non-empty and starts with `rtmp://` or `rtmps://`, key is non-empty.
- Show errors via `TextInputLayout.error`.
- After successful save, `startActivity(Intent(this, StreamActivity::class.java))`.

### Acceptance criteria
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL.
2. Install + launch. Form appears with the default RTMP URL pre-filled.
3. Type a fake key (e.g. `test-123-test`), pick 1080p, slide bitrate to 5000, tap Save.
4. Kill app (`adb shell am force-stop com.streamforge.app.debug`), reopen.
5. Form re-shows with all the values you typed (the key dots will be hidden but stored).
6. Empty URL → tap Save → red error appears under the URL field.
7. Inspect with `adb shell run-as com.streamforge.app.debug ls files/datastore/` — `stream_prefs.preferences_pb` exists.

### Pitfalls
- **DataStore on UI thread** → ANR. Always use `lifecycleScope.launch { ... }`.
- **EncryptedSharedPreferences MasterKey** — use `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`. Don't roll your own.
- **Spinner change vs initial set** — guard against `OnItemSelectedListener` firing on programmatic set. Use a `firstRender` boolean flag.

### Hand-off
Phase 6 (Integration) will read `StreamPrefs.load()` from `StreamActivity` and feed it into `StreamManager` (built in 3A). The "Save & Go Live" button in this phase already launches `StreamActivity`, so the wiring just needs the config read added there.

---

## Phase 3A — RTMP Streaming

- **Owner:** Person A
- **Branch:** `feature/rtmp-streaming`
- **Depends on:** Phase 2A
- **Parallel with:** Phase 3B
- **Goal:** Tap "Go Live" in `StreamActivity` → app actually streams camera + mic to YouTube. Connection state shown on screen.

### Files to create
- `app/src/main/java/com/streamforge/app/stream/StreamManager.kt`
- `app/src/main/java/com/streamforge/app/stream/StreamState.kt`

### Files to modify
- `app/src/main/java/com/streamforge/app/StreamActivity.kt` — wire up StreamManager.
- `app/src/main/res/layout/activity_stream.xml` — add Go Live button + status label.
- `app/src/main/res/values/strings.xml` — add `go_live`, `stop`, `status_idle`, `status_connecting`, `status_live`, `status_failed`.

### Dependencies
None new.

### Implementation spec

**`StreamState.kt`** — sealed class:
```kotlin
sealed class StreamState {
    object Idle : StreamState()
    object Connecting : StreamState()
    object Live : StreamState()
    data class Failed(val reason: String) : StreamState()
}
```

**`StreamManager.kt`** — wraps `RtmpCamera2`, exposes `StateFlow<StreamState>`:
- Constructor takes the `RtmpCamera2` instance (created in StreamActivity).
- Implements `ConnectChecker` (RootEncoder's callback interface). The interface methods (`onConnectionSuccess`, `onConnectionFailed`, `onDisconnect`, `onAuthError`, `onAuthSuccess`, `onNewBitrate`) update the `MutableStateFlow<StreamState>`.
- Methods:
  - `fun startStream(config: StreamConfig)`:
    - Call `rtmpCamera.prepareVideo(width, height, fps, bitrateKbps * 1024, 2, /*rotation=*/0)`
    - Call `rtmpCamera.prepareAudio(audioBitrateKbps * 1024, 44100, true, false, false)`
    - Build URL: `config.rtmpUrl + config.streamKey`
    - Call `rtmpCamera.startStream(url)` on a background thread.
    - State → Connecting
  - `fun stopStream()`: `rtmpCamera.stopStream()`; State → Idle.

**StreamActivity changes:**
- Read `StreamConfig` from `StreamPrefs` in `onCreate` (suspend → `lifecycleScope.launch`).
- Wire Go Live button: if state is Idle, call `streamManager.startStream(config)`. If state is Live, call `stopStream()`.
- Collect `streamManager.state` flow with `repeatOnLifecycle(STARTED)`; update status label and button text based on state.
- Disable bitrate config UI while live.

**Status colors:**
- Idle: gray
- Connecting: amber
- Live: red with a pulsing dot
- Failed: red, show error text

### Acceptance criteria
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL.
2. Set up a real YouTube live stream:
   - youtube.com → Create → Go Live → "Stream"
   - Copy the stream key + the RTMP server URL.
3. Enter both into the StreamForge config screen. Save & Go Live.
4. Tap Go Live button.
5. Status label cycles: Connecting → Live within ~5 seconds.
6. YouTube Studio's preview shows your camera feed within 10-30 seconds.
7. Tap Stop → status returns to Idle. YouTube preview ends within a minute.
8. No crashes in `adb logcat`.

### Pitfalls
- **Wrong RTMP URL format.** Must be `rtmp://a.rtmp.youtube.com/live2/<key>` — no spaces, key appended directly after the trailing slash.
- **`startStream` on main thread blocks UI.** RootEncoder's `startStream` is fast (non-blocking), but resolution/bitrate prep can be slow on cold start. Wrap in `Dispatchers.IO` if you see jank.
- **YouTube rejects the stream** — usually means your channel hasn't been verified for live streaming (requires 24-hour wait for new channels). Test with an already-verified channel.
- **No audio on YouTube** — confirm `prepareAudio()` returned `true`. If RECORD_AUDIO permission is denied at runtime, audio prep silently fails.

### Hand-off
Phase 4A will move this into a `Service`. Keep `StreamManager` decoupled from `Activity` lifecycle — accept the camera instance via constructor, no Activity references inside.

---

## Phase 3B — Overlay Editor View

- **Owner:** Person B
- **Branch:** `feature/overlay-editor`
- **Depends on:** Phase 2B
- **Parallel with:** Phase 3A
- **Goal:** A custom View that sits over the camera preview and handles drag / pinch-scale / two-finger-rotate gestures. Renders dummy rectangles to prove gestures work. NOT wired to OpenGL yet.

### Files to create
- `app/src/main/java/com/streamforge/app/overlay/OverlayItem.kt`
- `app/src/main/java/com/streamforge/app/ui/OverlayEditorView.kt`

### Files to modify
- None yet — this is a self-contained component. We'll wire it into `StreamActivity` during Phase 6 (Integration).

### Implementation spec

**`OverlayItem.kt`** — sealed data class:
```kotlin
sealed class OverlayItem {
    abstract val id: String        // unique
    abstract var x: Float          // 0..1, fraction of view width
    abstract var y: Float          // 0..1, fraction of view height
    abstract var scale: Float      // multiplier
    abstract var rotation: Float   // degrees
    abstract var zIndex: Int

    data class Image(
        override val id: String,
        val uri: String,           // content:// URI
        override var x: Float,
        override var y: Float,
        override var scale: Float,
        override var rotation: Float,
        override var zIndex: Int,
    ) : OverlayItem()

    data class Text(
        override val id: String,
        val text: String,
        val fontSizeSp: Float,
        val colorArgb: Int,
        override var x: Float,
        override var y: Float,
        override var scale: Float,
        override var rotation: Float,
        override var zIndex: Int,
    ) : OverlayItem()
}
```

**`OverlayEditorView.kt`** — extends `View`:
- Holds `MutableList<OverlayItem>` and a `selectedId: String?`.
- `onDraw`: iterate sorted by `zIndex`, draw a dummy semi-transparent colored rect for each item (red if Image, blue if Text) at `(x*width, y*height)`, with `scale` and `rotation` applied via `Canvas.save() / translate / rotate / scale / restore`. Selected item gets a 2dp dashed border.
- `onTouchEvent`: use `ScaleGestureDetector` for pinch and a custom rotate detector for two-finger rotation. Single-finger drag updates `selectedId`'s `x` and `y`.
- Tap = select (hit test from top of zIndex stack).
- `invalidate()` after every change.
- Public API:
  - `fun addItem(item: OverlayItem)`
  - `fun removeItem(id: String)`
  - `fun updateItem(item: OverlayItem)`
  - `fun getItems(): List<OverlayItem>`
  - `fun setSelectionListener(listener: (id: String?) -> Unit)`
  - `fun setItemChangeListener(listener: (OverlayItem) -> Unit)` — fires after every drag/scale/rotate

### Acceptance criteria
- Create a small test harness Activity (`OverlayTestActivity`) — registered in Manifest with `android:exported="false"` and launched from a dev menu in MainActivity (long-press the title bar). NOT shipped to release.
- Activity has the `OverlayEditorView` filling the screen with 2 dummy items pre-added.
- Drag with one finger → item moves smoothly under finger.
- Pinch with two fingers → item scales between 0.2x and 5x.
- Two-finger rotation → item rotates.
- Tap an item → selection border appears.
- `./gradlew assembleDebug` → BUILD SUCCESSFUL.

### Pitfalls
- **Gesture detector consumes events** — make sure `onTouchEvent` returns `true` only when an event is actually handled, else parent ScrollView (if any) eats taps.
- **Canvas transforms compound** — always `save()` before transforms and `restore()` after each item, or items will drift.
- **Performance** — `invalidate()` on every move is fine for ≤20 items but use `invalidate(dirtyRect)` if profiling shows jank.

### Hand-off
Phase 6 will wrap each `OverlayItem` change into a call to `ImageObjectFilterRender.setPosition(...)` or `TextObjectFilterRender.setText(...)` on the RootEncoder pipeline.

---

## Phase 4A — Foreground Service

- **Owner:** Person A
- **Branch:** `feature/foreground-service`
- **Depends on:** Phase 3A
- **Parallel with:** Phase 4B
- **Goal:** Streaming continues when screen is off / app is backgrounded. Persistent notification shown while live with a "Stop" action.

### Files to create
- `app/src/main/java/com/streamforge/app/service/StreamService.kt`
- `app/src/main/java/com/streamforge/app/service/NotificationHelper.kt`

### Files to modify
- `app/src/main/AndroidManifest.xml` — register service.
- `app/src/main/java/com/streamforge/app/StreamActivity.kt` — start/stop via service.

### Implementation spec

**`NotificationHelper.kt`**:
- `fun ensureChannel(context: Context)`: creates `NotificationChannel` with id `streamforge_live` on API 26+. Importance `LOW` so it doesn't vibrate.
- `fun buildLiveNotification(context: Context, stopPendingIntent: PendingIntent, returnPendingIntent: PendingIntent): Notification`: builds the ongoing notification with title `notif_live_title`, content `notif_live_text`, small icon `ic_launcher_foreground`, action button "Stop" wired to `stopPendingIntent`, content intent → `returnPendingIntent`.

**`StreamService.kt`** — `Service` (not `IntentService`):
- `foregroundServiceType="camera|microphone"` (declared in manifest).
- Holds the `RtmpCamera2` instance moved out of `StreamActivity`.
- `onStartCommand` handles two actions: `ACTION_START` (extras: `StreamConfig` serialized) and `ACTION_STOP`.
- On `ACTION_START`:
  - Acquire partial WakeLock (`PowerManager.PARTIAL_WAKE_LOCK`).
  - Call `startForeground(NOTIF_ID, buildLiveNotification(...))`.
  - Call `streamManager.startStream(config)`.
- On `ACTION_STOP`:
  - `streamManager.stopStream()`, release wake lock, `stopSelf()`.
- Expose state via a `bindService`-able binder OR via a `MutableStateFlow` held in an `object Singleton` (simpler for now).
- Auto-reconnect: in `onConnectionFailed`, if `retryCount < 3`, schedule `startStream` again after exponential backoff (1s, 4s, 9s).

**StreamActivity changes:**
- Remove direct `RtmpCamera2` ownership; bind to `StreamService` instead.
- Go Live button → `Intent(this, StreamService::class.java).setAction(ACTION_START).putExtra(...)` then `ContextCompat.startForegroundService(this, intent)`.
- Observe service state via the singleton flow.
- IMPORTANT: The OpenGlView in the Activity still needs the camera instance attached for preview. Either keep two camera instances (one for preview-only when not streaming, one in the service when streaming) OR pass the SurfaceTexture across the binder. **Simpler: when streaming starts, the service takes over the existing `RtmpCamera2`; when streaming ends, control returns to the Activity.** Use the service binder pattern, not the singleton, for this.

### Acceptance criteria
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL.
2. Install, set up YouTube stream key, go live.
3. **Press Home button** — notification "Live on YouTube" appears in shade.
4. YouTube Studio preview keeps showing your camera (from inside your pocket — flip the phone face-down).
5. **Lock the screen for 30 seconds** — stream continues (verify on YouTube Studio).
6. Pull notification shade → tap "Stop" → stream ends within 3 seconds, notification disappears.
7. Toggle airplane mode briefly while live → notification shows "Reconnecting…" then "Live" again within ~10 seconds (or "Failed" after 3 retries).
8. Battery profile: `adb shell dumpsys batterystats --reset` then stream 5 min — no wake lock leaks reported.

### Pitfalls
- **Android 14 foreground service type enforcement.** Must declare `android:foregroundServiceType="camera|microphone"` AND request permission `FOREGROUND_SERVICE_CAMERA` AND `FOREGROUND_SERVICE_MICROPHONE` (already in manifest from Phase 0 setup).
- **`startForegroundService` with no `startForeground` call within 5s = crash.** Ensure first thing in `onStartCommand` is the foreground promotion.
- **Wake lock leak**: always release in `onDestroy` and on `ACTION_STOP`.
- **Audio focus** — request `AUDIOFOCUS_GAIN` for proper mic priority over music apps.

### Hand-off
Phase 6 Integration will plug overlays into the camera instance owned by the service.

---

## Phase 4B — Image Overlay

- **Owner:** Person B
- **Branch:** `feature/image-overlay`
- **Depends on:** Phase 3B
- **Parallel with:** Phase 4A
- **Goal:** Picker for choosing PNG/JPG → adds an `OverlayItem.Image` to the editor. List view shows active overlays with show/hide/delete. Saved across app restarts.

### Files to create
- `app/src/main/java/com/streamforge/app/overlay/OverlayStore.kt`
- `app/src/main/java/com/streamforge/app/ui/OverlayListAdapter.kt`
- `app/src/main/res/layout/item_overlay_row.xml`

### Files to modify
- `app/src/main/java/com/streamforge/app/storage/StreamPrefs.kt` — add overlay serialization (Json string of `List<OverlayItem>`).
- `app/src/main/java/com/streamforge/app/ui/OverlayEditorView.kt` — already supports adding Image items; nothing to change here.
- Create an `OverlayManagerActivity` OR add an `OverlayManagerFragment` to `StreamActivity` — your call. Recommend a bottom-sheet on top of StreamActivity (cleaner UX).

### Dependencies
Add to `gradle/libs.versions.toml`:
```toml
[versions]
kotlinx-serialization = "1.7.3"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```
Add to `app/build.gradle.kts`:
```kotlin
plugins {
    // ...existing
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // ...existing
    implementation(libs.kotlinx.serialization.json)
}
```
Mark `OverlayItem` and its subclasses with `@Serializable`.

### Implementation spec

**`OverlayStore.kt`**:
- Singleton object (or DI-friendly class).
- `suspend fun load(context: Context): List<OverlayItem>` — reads JSON string from DataStore, parses with `kotlinx.serialization`.
- `suspend fun save(context: Context, items: List<OverlayItem>)` — serializes and writes.

**Bottom sheet UI** (`overlay_manager_sheet.xml`):
- "Add Image" button → opens PhotoPicker via `ActivityResultContracts.PickVisualMedia(ImageOnly)`.
- "Add Text" button (greyed out — Phase 5B fills it in).
- RecyclerView of overlays, each row:
  - Thumbnail (Glide-style image load — use `ImageDecoder.createSource` since we have no Glide; or add Coil if needed)
  - Text "Image" / "Text"
  - Eye toggle (visible/hidden)
  - Bin button (delete)
  - Drag handle (reorder zIndex)

**Image picker handler**:
- Returns a `content://` URI.
- Persist URI permission: `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`.
- Create `OverlayItem.Image(id=UUID, uri=uri.toString(), x=0.5f, y=0.5f, scale=1f, rotation=0f, zIndex=items.size)`.
- Add to `OverlayEditorView` and save via `OverlayStore`.

### Acceptance criteria
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL.
2. Open StreamActivity → pull up overlay manager.
3. Tap "Add Image" → photo picker appears → select a PNG with transparency.
4. Image overlay appears centered on screen, can be dragged/scaled/rotated.
5. Add 2 more overlays.
6. Force-stop app, reopen → all 3 overlays restored at their last positions.
7. Tap bin on one row → that overlay disappears, others remain.
8. Toggle eye on a row → overlay hides (not deleted).

### Pitfalls
- **`content://` URI permission expires** if you forget `takePersistableUriPermission`. Always call it on pick.
- **Photo picker** requires API 33+ via the actual PhotoPicker; on API 24-32 the same `PickVisualMedia` contract falls back to the system file picker. Test on a real device.
- **Thumbnails on the RecyclerView** — loading raw URIs synchronously will jank. Use a tiny image decoder helper that runs on `Dispatchers.IO` and posts to the View on `Dispatchers.Main`.

### Hand-off
Phase 6 will pipe each visible `OverlayItem.Image` to RootEncoder's `ImageObjectFilterRender`:
```kotlin
val filter = ImageObjectFilterRender()
rtmpCamera.glInterface.addFilter(filter)
filter.setImage(bitmap)
filter.setPosition(x * 100f, y * 100f)   // percent of view
filter.setScale(scale * 10f, scale * 10f) // percent
```

---

## Phase 5B — Text Overlay

- **Owner:** Person B
- **Branch:** `feature/text-overlay`
- **Depends on:** Phase 4B
- **Parallel with:** —
- **Goal:** Same flow as image overlay but for text. Dialog asks for text content, font size, color. Persists alongside image overlays in the same list.

### Files to create
- `app/src/main/java/com/streamforge/app/ui/TextOverlayDialog.kt`
- `app/src/main/res/layout/dialog_text_overlay.xml`

### Files to modify
- Bottom sheet from 4B — enable "Add Text" button.
- `OverlayListAdapter` from 4B — show text preview instead of thumbnail for Text items.

### Implementation spec

**`dialog_text_overlay.xml`**:
- `TextInputLayout` for the text (multiline allowed)
- Slider 12-96 for font size sp
- Color picker — use a simple `GridLayout` of 12 preset colors as `ImageView` swatches. (Full ARGB picker is out of scope for v1.)
- Bold / Italic toggle (`MaterialButtonToggleGroup`)
- OK / Cancel buttons

**`TextOverlayDialog.kt`** — `DialogFragment`:
- Constructor takes optional existing `OverlayItem.Text` to edit (null = create new).
- On OK, calls back with the new/edited `OverlayItem.Text`.

### Acceptance criteria
1. `./gradlew assembleDebug` → BUILD SUCCESSFUL.
2. Open overlay manager → tap "Add Text" → dialog appears.
3. Type "LIVE", size 48, color red, tap OK → text overlay appears on the editor view at center.
4. Drag/scale/rotate works.
5. Tap the row in the list → dialog reopens with values pre-filled → change to "HELLO" → OK → editor updates.
6. Force-stop, reopen → text overlays restored.

### Pitfalls
- **Text rendering on the editor View** is dummy (drawn as a colored rect with text on top). The real OpenGL text rendering happens in Phase 6.
- **Color picker accessibility** — give each swatch a content description like "Red, hex E53935".

### Hand-off
Phase 6 will pipe text overlays to RootEncoder's `TextObjectFilterRender`:
```kotlin
val filter = TextObjectFilterRender()
rtmpCamera.glInterface.addFilter(filter)
filter.setText(textItem.text, textItem.fontSizeSp, textItem.colorArgb)
filter.setPosition(x * 100f, y * 100f)
```

---

## Phase 6 — Integration

- **Owner:** Both (PAIR on this — call / screen-share)
- **Branch:** `feature/integration`
- **Depends on:** Phase 4A AND Phase 5B
- **Parallel with:** —
- **Goal:** Overlays render on the actual outgoing stream. Edits in the editor view update the live stream in real-time. The end-to-end app works.

### Files to create
- `app/src/main/java/com/streamforge/app/overlay/OverlayRenderer.kt`

### Files to modify
- `app/src/main/java/com/streamforge/app/StreamActivity.kt` — wire OverlayEditorView changes → OverlayRenderer.
- `app/src/main/java/com/streamforge/app/service/StreamService.kt` — apply persisted overlays on stream start.

### Implementation spec

**`OverlayRenderer.kt`** — owns the mapping from `List<OverlayItem>` to RootEncoder filter instances.
- Holds a `Map<String, BaseFilterRender>` (id → filter).
- `fun applyOverlays(items: List<OverlayItem>)`:
  - For each item not already in the map: create the right filter (`ImageObjectFilterRender` or `TextObjectFilterRender`), load the bitmap/text, add to `rtmpCamera.glInterface`.
  - For each item already in the map but with changed properties: update position/scale/rotation on the filter.
  - For each id in the map but no longer in items: remove the filter via `glInterface.removeFilter(filter)`.
- `fun updateOverlay(item: OverlayItem)` — fast path for single live edits during dragging.

**StreamActivity wiring:**
- On Activity create: load overlays from `OverlayStore`, hand to `OverlayRenderer.applyOverlays`.
- Subscribe to `OverlayEditorView.itemChangeListener` → call `OverlayRenderer.updateOverlay(item)` (throttle to ~30fps via a coroutine flow if needed).
- On overlay add/delete from bottom sheet → save to `OverlayStore` AND call `applyOverlays`.

**Coordinate conversion** — `OverlayItem.x/y` are 0..1 fractions; RootEncoder filter `setPosition` takes percent (0..100). Multiply by 100.

### Acceptance criteria
End-to-end test, recorded with a friend watching the YouTube broadcast on a separate device:
1. Open StreamForge → enter real YouTube key → Save & Go Live.
2. Camera preview appears.
3. Open overlay manager → add a PNG logo → drag to top-right.
4. Add a text overlay "GOING LIVE 🔴" → drag to top-left.
5. Tap Go Live.
6. Confirm on YouTube Studio's preview that:
   - Camera feed is live
   - PNG logo is visible at top-right
   - Text "GOING LIVE 🔴" visible at top-left
   - Overlays are stable, not flickering
7. While live: drag the PNG to bottom-right → confirm it moves on YouTube within ~3 seconds.
8. While live: delete the text overlay → confirm it disappears on YouTube.
9. Lock phone → stream + overlays continue.
10. Stop stream → notification dismissed, preview ends.

### Pitfalls
- **Bitmap leak** — when removing an image filter, recycle its bitmap.
- **Filter z-order** — RootEncoder's filter list draws in order added. To respect `zIndex`, sort the items before calling `applyOverlays` and re-add everything if order changed.
- **Live-edit lag** — if dragging is choppy on the YouTube side, that's expected (1-3s RTMP latency). Local preview should remain smooth.
- **Filter API drift** — RootEncoder 2.x has slightly different method names than 1.x. If `setPosition(x, y)` doesn't exist, check `setPositionXY` or the example app for the current name.

### Hand-off
Phase 7 polishes the rough edges from real-world use.

---

## Phase 7 — Polish

- **Owner:** Either (split the list)
- **Branch:** one branch per item, e.g. `polish/stats-overlay`
- **Depends on:** Phase 6
- **Parallel with:** other polish items
- **Goal:** Production-feel app.

Pick from this list (skip any you don't want):

- [ ] **Stream stats HUD** — show current bitrate, fps, dropped frames, uptime in a small overlay in the Activity (not in the stream itself). Read from RootEncoder's `getBitrate()` etc.
- [ ] **Network quality indicator** — color dot showing connection health: green / amber / red. Tap to see detail.
- [ ] **Adaptive bitrate** — in `onNewBitrate` callback, accept RootEncoder's bitrate suggestions automatically.
- [ ] **Mute mic button** — toggles `rtmpCamera.disableAudio()` / `enableAudio()`.
- [ ] **Torch button** — toggles back-camera flash via `rtmpCamera.enableLantern()`.
- [ ] **Settings screen** — advanced: keyframe interval, audio sample rate, hardware codec preference.
- [ ] **App icon** — replace the placeholder vector launcher with a designed PNG via `mipmap-mdpi`/`hdpi`/`xhdpi`/`xxhdpi`/`xxxhdpi`.
- [ ] **Splash screen** — use the AndroidX SplashScreen API (`androidx.core:core-splashscreen`).
- [ ] **Crash reporting** — wire up Sentry or Firebase Crashlytics. Add their plugin in `libs.versions.toml`.

Each polish item = own branch + own PR. Don't bundle.

---

## Phase 8 — Release Build

- **Owner:** Person A
- **Branch:** `release/v1.0.0`
- **Depends on:** Phase 7 (or 6 if you skipped polish)
- **Parallel with:** —
- **Goal:** Signed release APK that can be shared / sideloaded.

### Steps
1. Generate a keystore (one-time, on Person A's machine):
   ```
   keytool -genkey -v -keystore streamforge-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias streamforge
   ```
   Save the password in a password manager. **NEVER commit `.jks`.**
2. Create `~/.gradle/streamforge-keystore.properties` (outside the repo):
   ```
   storeFile=C:/path/to/streamforge-release.jks
   storePassword=<your-pw>
   keyAlias=streamforge
   keyPassword=<your-pw>
   ```
3. Edit `app/build.gradle.kts` — add signing config that reads from this file:
   ```kotlin
   val keystorePropsFile = file(System.getProperty("user.home") + "/.gradle/streamforge-keystore.properties")
   val keystoreProps = java.util.Properties().apply {
       if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
   }
   android {
       signingConfigs {
           create("release") {
               if (keystorePropsFile.exists()) {
                   storeFile = file(keystoreProps["storeFile"] as String)
                   storePassword = keystoreProps["storePassword"] as String
                   keyAlias = keystoreProps["keyAlias"] as String
                   keyPassword = keystoreProps["keyPassword"] as String
               }
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.getByName("release")
               // ...existing minify settings
           }
       }
   }
   ```
4. Build:
   ```
   .\gradlew assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk`
5. Install on both devs' phones; test the full flow end-to-end.
6. Tag in git:
   ```
   git tag v1.0.0
   git push --tags
   ```
7. Create a GitHub release; attach the signed APK as a release asset.

### Pitfalls
- **R8 strips RootEncoder reflection** — `proguard-rules.pro` already has `-keep class com.pedro.**` rules. If you see runtime `NoSuchMethodException`, that's a missing keep.
- **Lost keystore** = lose the ability to ship updates. Back it up in your password manager and a second offline location.

---

## Reference

### Branch & PR rules
1. Never push directly to `main`.
2. Branch names: `feature/...`, `fix/...`, `chore/...`, `polish/...`, `release/...`.
3. One phase = one branch = one PR.
4. PR description must include: what changed, how to test, screenshot or logcat snippet showing Acceptance Criteria passed.
5. Other person reviews + merges. Never merge your own PR.
6. Delete branch after merge.

### Files NEVER to commit
- `local.properties` (SDK path)
- `*.jks`, `*.keystore` (signing keys)
- `streamforge-keystore.properties` (passwords)
- `.idea/`, `.gradle/`, `build/`
- Anything containing a real YouTube stream key

The `.gitignore` already covers these. Always check `git status` before committing.

### Dependency table

| Phase | Owner | Branch | Depends on | Parallel with |
|---|---|---|---|---|
| 0 Tooling | Both | — | — | — |
| 1 Bootstrap | A | chore/bootstrap-wrapper | 0 | — |
| 2A Camera | A | feature/camera-preview | 1 | 2B |
| 2B Config UI | B | feature/config-screen | 1 | 2A |
| 3A RTMP | A | feature/rtmp-streaming | 2A | 3B |
| 3B Overlay editor | B | feature/overlay-editor | 2B | 3A |
| 4A Service | A | feature/foreground-service | 3A | 4B |
| 4B Image overlay | B | feature/image-overlay | 3B | 4A |
| 5B Text overlay | B | feature/text-overlay | 4B | — |
| 6 Integration | Both | feature/integration | 4A + 5B | — |
| 7 Polish | Either | polish/* | 6 | each other |
| 8 Release | A | release/v1.0.0 | 7 | — |

### When something goes wrong
1. Read the error message — Gradle errors usually point at the exact line.
2. `./gradlew assembleDebug --stacktrace --info` for more detail.
3. `adb logcat -s StreamForge:* AndroidRuntime:E *:E` to see runtime crashes.
4. Search the error string in the RootEncoder issues: https://github.com/pedroSG94/RootEncoder/issues
5. If stuck for >30 minutes, paste the error in your PR description and the other person reviews.
