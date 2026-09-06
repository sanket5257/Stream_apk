# StreamForge v1.0 — Rebuild Brief

**Status:** planning · **Written:** 2026-09-06 · **Supersedes:** positioning in `PHASES.md`

---

## 1. Why we're doing this

v0.2.1 is a technically sound app that sold once (₹7,000 lifetime). The problem is not
the streaming engine — it's that "stream to YouTube with overlays" is given away free by
Prism Live Studio, Streamlabs Mobile, and Larix Broadcaster. A stranger cannot see
₹7,000 of difference.

**The pivot:** stop selling a generic broadcaster. Sell a *professional graphics system*
for two verticals that have real money and no good mobile tool:

- **Local sports streamers** — cricket, kabaddi, football. Need a live scoreboard.
- **News / event / wedding streamers** — need lower thirds, tickers, branded packages.

Both want the same underlying thing: **their stream should look like television.**
That is what justifies a subscription. Overlays-as-drag-a-PNG does not.

**Model change:** lifetime ₹7,000 → monthly subscription with a free watermarked tier.

---

## 2. What we are NOT doing

| Rejected | Why |
|---|---|
| **React Native / Flutter rewrite** | The entire value is the Camera2 → OpenGL filter chain → hardware encoder → RTMP path. RN cannot touch that layer; we'd write it all as a native Kotlin module anyway and add a JS bridge on top. Strictly worse than today. |
| **iOS now** | The streaming core would be HaishinKit — a second native codebase regardless of framework. Revisit after ~50 paying Android users. |
| **Throwing away the streaming core** | `StreamManager`, `OverlayRenderer`, `StreamService`, and the crash-hardening work are the hardest 60% of the product and they work. Keep them. |
| ~~Multistream~~ | **Reversed — it shipped.** RootEncoder 2.4.5 has `MultiRtpCamera2`, which fans a single encode out to several RTMP clients. No relay server, no second encode: the cost is upload bandwidth, not battery. YouTube + Facebook + custom RTMP are live; Instagram is listed as "Coming soon" because it has no open RTMP ingest. |

**Stack decision: stay Kotlin. Rebuild the UI layer in Jetpack Compose, keep and extend
the native streaming/overlay core.**

---

## 3. Current codebase — what we keep, change, and add

### Keep as-is (do not refactor without cause)
- `stream/StreamManager.kt`, `stream/StreamState.kt` — RTMP lifecycle, adaptive bitrate,
  encoder-profile fallback, watchdog
- `overlay/OverlayRenderer.kt` — the GL bridge to RootEncoder's filter pipeline. This is
  subtle code (independent width%/height% `setScale`, aspect correction, text-bitmap
  signature caching). Extend it; don't rewrite it.
- `overlay/OverlayItem.kt` — the primitive model. **Add** to the sealed class, don't reshape it.
- `service/StreamService.kt`, `util/CrashReporter.kt`, `util/SafeCoroutines.kt`
- `auth/*` (Supabase) — reuse for subscription entitlement storage
- `update/*` — the sideload update channel; this is now the ONLY distribution path

### Replace
- All XML layouts + `viewBinding` → Jetpack Compose + Material 3
- `MainActivity` / `HomeActivity` / `ProfileActivity` / `StreamActivity` → single-activity
  Compose navigation, with `StreamActivity`'s camera surface preserved (see §4 note)
- `auth/InviteCodeManager` stays for signup; licence entitlement is a separate table

### Add (the three bets)
1. Graphics Pack template engine — §5.1
2. Scenes — §5.2
3. Licence-based tiers — §5.3

---

## 4. Technical constraints that will bite

These are the traps. Read before writing code.

1. **The camera preview must stay a native `SurfaceView`.** RootEncoder's `OpenGlView`
   cannot be reimplemented in Compose. Host it with
   `AndroidView(factory = { OpenGlView(it) })` and keep Compose UI *above* it in a
   `Box`. Do not put it inside anything that recomposes frequently.
2. **`OverlayEditorView` stays a custom `View` initially.** It's a working multi-touch
   drag/pinch/rotate surface. Wrap it in `AndroidView`. Porting gestures to Compose is a
   Phase 2 nicety, not a v1.0 requirement.
3. **`setScale` takes independent width% and height%.** Passing equal values distorts
   non-square overlays. `OverlayRenderer` already handles this via `contentAspect` —
   any new template code must go through the same path.
4. **Kotlin 1.9.24 → 2.0.21** to use the Compose compiler Gradle plugin
   (`org.jetbrains.kotlin.plugin.compose`). AGP 8.5.2 supports this. Verify the
   `kotlinx.serialization` and Supabase/ktor stack still builds after the bump — that is
   the single riskiest step in the whole plan; do it first, on its own branch.
5. **`isMinifyEnabled = false` in release** (deliberate, per the build file comment).
   You'll want R8 on eventually for a smaller APK — but do NOT enable it in the same
   change as anything else. It has historically broken Supabase/ktor here.
6. **`compileSdk`/`targetSdk` stay at 34.** The Play Store target-API deadline does not
   apply to a directly-distributed APK, and android-35 is not installed on this machine.
   Bump it when there is a reason to, not on a store schedule.
7. **Overlay state is serialized** (`@Serializable`, DataStore). Any new fields need
   defaults so existing users' saved overlays still deserialize.

---

## 5. The three bets, in detail

### 5.1 Graphics Packs — the differentiator

Today a user drags a PNG and types text. That's a tool. A **Graphics Pack** is a product:
a named, pre-designed, data-driven overlay group the user fills in and controls live.

**New model layer** (sits above `OverlayItem`, compiles down to it):

```
GraphicsPack
  id, name, category (SPORTS | NEWS | EVENT | WEDDING)
  fields: List<PackField>        // typed, user-filled: TEAM_A_NAME, SCORE_A, TITLE, SUBTITLE…
  elements: List<PackElement>    // each binds fields to an OverlayItem template
  theme: PackTheme               // colors, font, corner radius — user-overridable
```

A pack **renders to** a set of `OverlayItem`s with a shared `packId`, so `OverlayRenderer`
needs no structural change — it still sees ordinary overlays. When a field value changes,
re-render only the affected elements.

**Ship with these packs:**

| Pack | Fields | Vertical |
|---|---|---|
| **Cricket scoreboard** | teams, runs, wickets, overs, striker, target | Sports |
| **Football / kabaddi score** | teams, score, period, clock | Sports |
| **Lower third** | name, title, accent color | News / Event |
| **News ticker** | headline list (cycles), channel logo | News |
| **Wedding title card** | couple names, date, ornament style | Wedding |
| **Logo bug + clock** | logo, position, live time | All |

**The critical requirement — Live Control Panel.** A cricket streamer cannot leave the
stream screen to add a run. There must be a compact, always-reachable control strip over
the live preview: `+1 run · +4 · +6 · wicket · over`. Same idea for news: tap to advance
the ticker headline, tap to show/hide a lower third.

If you build only one thing from this document, build this. It is the entire reason
someone pays monthly.

**Design note:** ship packs as JSON assets, not hardcoded Kotlin. Then new packs are a
content update — eventually a server-fetched catalog — rather than an app release.

### 5.2 Scenes

A **Scene** is a saved snapshot: which overlays are visible, their positions, which
camera, and which graphics pack fields. One tap switches between them mid-stream with no
interruption to the RTMP connection.

Default scenes: `Pre-show` · `Live` · `Break / Ad` · `Replay` · `Outro`

Implementation: a Scene stores overlay `visible` flags + `zIndex` + pack field values,
keyed by overlay id. Switching = applying that state to the current overlay list and
letting `OverlayRenderer.updateOverlay()` reconcile. **Do not** tear down and rebuild GL
filters on scene switch — that will stutter the stream. Toggle visibility only.

### 5.3 Subscription via manual licence codes

> **Decision changed mid-build (2026-09-06):** no app store, no payment gateway. The app is
> distributed as a direct APK and licences are issued by hand. Play Billing was removed.
> The operational side of this lives in **`ADMIN_GUIDE.md`**; the schema is
> **`LICENSE_SYSTEM_SETUP.sql`**.

- **Tiers:**
  - **Free** — 720p, StreamForge watermark, free packs only (lower third + logo bug), 1 scene
  - **Pro (₹499/mo)** — 1080p, no watermark, all graphics packs, unlimited scenes
  - **Studio (₹999/mo)** — everything + multistream to 3 destinations
  - *(Prices are a starting hypothesis — validate against what the sports/news buyers
    actually say, and consider an annual plan at ~2 months' discount.)*
- **How money moves:** the Upgrade screen shows the plans, then hands off to WhatsApp / call /
  email with a pre-filled message. Payment happens outside the app. You then generate a code
  in the Supabase dashboard and send it over.
- **Entitlement is verified server-side** by `app_check_license`, and each code binds to one
  user **and** one device. The local cache carries a 7-day offline grace window so a bad
  signal at a ground never downgrades a paying customer mid-match.
- The existing invite-code path stays for signup.
- **Honest limitation:** a rooted user can bypass the local cache. Device binding stops the
  realistic problem — one code shared around a WhatsApp group — not a determined attacker.
  Acceptable for direct sales to a known customer list; revisit if revenue justifies it.

---

## 6. Suggested sequence (6–8 weeks)

| Phase | Work | Why this order |
|---|---|---|
| **0** | Kotlin 2.0.21 + Compose compiler plugin. Build green, app unchanged. | Riskiest dependency step, isolated. |
| **1** | Compose shell: navigation, home, settings, profile. `StreamActivity` untouched. | Proves the Compose migration without risking the streaming path. |
| **2** | Stream screen in Compose — `OpenGlView` + `OverlayEditorView` via `AndroidView`, controls in Compose. | The delicate one. Test on real hardware, long sessions. |
| **3** | Graphics Pack engine + JSON pack format + 3 packs (cricket, lower third, ticker). | The differentiator. |
| **4** | **Live Control Panel.** | Makes packs actually usable while streaming. |
| **5** | Scenes. | Builds on the overlay state work from 3–4. |
| **6** | Licence codes + Supabase entitlement + tier gating. | Needs the features to exist before it can gate them. |
| **7** | R8 (carefully), signed release APK, `version.json` update. | Release engineering last. |

**Ship Phase 3–4 to the one existing customer and 5 prospects before building Phase 5+.**
If the scoreboard doesn't make them say "when can I get this," the plan is wrong and
better to learn it in week 4 than week 8.

---

## 7. Phase 2 (post-launch) backlog

- **YouTube OAuth** — create/schedule the broadcast in-app instead of pasting a stream
  key; unlocks live chat overlay and viewer count. Big perceived-value jump.
- **Live chat overlay + alerts**
- **Second phone as a remote camera** (two-angle streaming — nobody does this well on mobile)
- **Local recording while live** (small effort, frequently requested — could pull into v1.0)
- **Server-fetched pack catalog** — sell or subscribe to graphics packs individually

---

## 8. Non-code work that decides whether this succeeds

The build is the easy half. In parallel:

1. **Interview the customer who paid ₹7,000.** What was he doing before? Why did he pay?
   What would he pay monthly for? This is the highest-value hour available.
2. **Find 15 more like him.** Local cricket league organizers, wedding videographers,
   regional news YouTube channels. DM them. Show a 60-second scoreboard demo video.
3. **Make one demo reel** of a cricket match with the live scoreboard. This sells the app
   better than any feature list or landing page.
4. **Install the licence SQL and set your contact details** so the Upgrade screen can
   actually sell — see `ADMIN_GUIDE.md`.
