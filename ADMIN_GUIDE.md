# StreamForge — Running the Business

**How you manage everything, day to day.** Written 2026-09-06, for the direct-APK model
(no app store, no payment gateway).

---

## The short answer: you already have an admin site

**Supabase's dashboard is your admin panel.** You don't need to build one.

> https://supabase.com/dashboard → your project

It gives you a real web UI — a table browser you can search, sort, filter and edit in place —
over the same database the app talks to. Everything below is done there, in a browser, on a
phone or a laptop, with no deploy and no code.

| What you need to do | Where |
|---|---|
| See who's signed up | Table Editor → `users` |
| Issue / revoke / extend a licence | Table Editor → `licenses` |
| Generate a batch of codes | SQL Editor → one query |
| Create invite codes for new signups | Table Editor → `invite_codes` |
| Move a licence to a customer's new phone | Table Editor → `licenses`, clear `device_id` |
| Ship an app update | GitHub Releases + `version.json` |
| Add a new scoreboard / graphic | A JSON file in the repo + a new APK |

The only two things that need you to touch the project rather than a browser are **app
updates** and **new graphics packs**. Everything about customers and money is the dashboard.

---

## 1. One-time setup

1. Open Supabase → **SQL Editor** → **New query**.
2. Paste the whole of **`LICENSE_SYSTEM_SETUP.sql`** and run it.
   That creates the `licenses` table, the two functions the app calls, and a code generator.
3. In `local.properties`, add how customers reach you:
   ```properties
   SUPPORT_WHATSAPP=919876543210   # digits only, with country code, no + or spaces
   SUPPORT_PHONE=+919876543210
   SUPPORT_EMAIL=you@example.com
   ```
   Any line you leave blank simply hides that button on the Upgrade screen.
4. Rebuild the APK.

Until step 3 is done the Upgrade screen says, plainly, that no contact details are configured —
it doesn't pretend to work.

---

## 2. Selling a licence — the whole loop

**What the customer does**

1. Installs the APK, signs up with an invite code (this part already existed).
2. Taps **Upgrade**, reads the plans, taps **Message on WhatsApp**.
3. A pre-written message opens, already containing the plan they picked, their phone model
   and their app version — so you're not asking three questions before you can help.

**What you do**

4. Take payment however you like — UPI, bank transfer, cash. Nothing about this is in the app.
5. Generate a code in the Supabase **SQL Editor**:
   ```sql
   select * from public.generate_licenses('PRO', 30, 1);
   -- → SF-PRO-K7M2QX9B
   ```
6. Send them the code on WhatsApp.
7. Record the sale, so future-you knows who this is:
   ```sql
   update public.licenses
   set issued_to = 'Ramesh — 98xxxxxx21', notes = 'UPI ₹499, 6 Sep'
   where code = 'SF-PRO-K7M2QX9B';
   ```

**What the customer does**

8. Types the code into Upgrade → **Activate**. Pro unlocks immediately.

The code binds to **their account AND their phone**, so forwarding it to a friend doesn't work.

---

## 3. The five commands you'll actually use

Run these in the Supabase **SQL Editor**.

```sql
-- Make 10 monthly Pro codes to have ready
select * from public.generate_licenses('PRO', 30, 10);

-- Make yearly Studio codes
select * from public.generate_licenses('STUDIO', 365, 5);

-- A lifetime licence (0 days = never expires)
select * from public.generate_licenses('PRO', 0, 1);

-- Who has what, and when it runs out
select code, tier, issued_to, activated_at, expires_at, is_active
from public.licenses
order by created_at desc;

-- Renew someone for another month
update public.licenses
set expires_at = greatest(expires_at, now()) + interval '30 days'
where code = 'SF-PRO-K7M2QX9B';
```

**Revoking** (chargeback, refund, abuse):
```sql
update public.licenses set is_active = false where code = 'SF-PRO-K7M2QX9B';
```
They keep working for up to 7 more days — that's the deliberate offline grace window, so a
streamer at a ground with no signal is never cut off mid-match. After that they drop to Free.

**Customer changed phones:**
```sql
update public.licenses set device_id = null where code = 'SF-PRO-K7M2QX9B';
```
Then they re-enter the same code on the new phone.

---

## 4. Who expires soon — your renewal list

Save this as a query in Supabase (SQL Editor → Save) and open it once a week. It's the
closest thing to a revenue dashboard you need at this size:

```sql
select code, tier, issued_to,
       expires_at::date as expires,
       (expires_at::date - current_date) as days_left
from public.licenses
where is_active = true
  and expires_at is not null
  and expires_at between now() and now() + interval '10 days'
order by expires_at;
```

Message each of them a few days before. Renewal is the cheapest revenue you will ever get.

---

## 5. Shipping an app update

Unchanged from before — see `RELEASING.md`. In short:

1. Bump `versionCode` **and** `versionName` in `app/build.gradle.kts`.
   (`versionCode` is the only number Android compares. If it doesn't increase, the install is
   rejected as a downgrade.)
2. `./gradlew assembleRelease` → `app/build/outputs/apk/release/streamforge.apk`
3. Upload it to a GitHub release.
4. Update `version.json` (the manifest the app polls) with the new version and the download URL.

Everyone's app notices within a day and offers the update. You don't have to chase anyone.

---

## 6. Adding a new graphic

This is the one thing that still needs a rebuild — but it needs **no Kotlin**.

A graphics pack is a single JSON file in `app/src/main/assets/packs/`. To add, say, a
volleyball scoreboard, copy `kabaddi_scoreboard.json`, change the id, name, fields, buttons and
layout, and rebuild. The format is documented in `app/src/main/java/com/streamforge/app/packs/PackModels.kt`.

Anatomy of a pack:

| Section | What it is |
|---|---|
| `canvas` | The graphic's own pixel grid, e.g. 1200 × 160. Everything is positioned in it. |
| `fields` | What the user fills in — team names, scores, a logo. |
| `actions` | The live buttons: "+4", "Wicket", "Next headline". This is the paid feature. |
| `elements` | How it's drawn: rectangles, text bound to `{fieldKey}`, images. |
| `tier` | `FREE`, `PRO` or `STUDIO` — which plan can use it. |

**Later, if pack sales become a thing:** because packs are already data and not code, moving
the catalogue to a URL the app fetches is a small change. Then a new scoreboard is something
you upload, not something you release. That's worth doing once you have enough customers that
"wait for the next APK" is a real complaint — not before.

---

## 7. What ships in this build

**Multistream.** YouTube + Facebook + a custom RTMP server, simultaneously, from one encode.
One camera pass, one encoder, several RTMP clients — so a second destination costs upload
bandwidth, not battery. Bitrate is automatically eased back when more than one destination is
active, because three streams at 6 Mbps needs 18 Mbps of upload that no phone has.

**Instagram is listed and marked "Coming soon"** — deliberately visible rather than hidden.
Instagram has no open RTMP ingest any more; the endpoints that survive need a key minted per
broadcast through a Graph API flow this app doesn't have. Showing it with an explanation
answers the question people will otherwise message you about.

**Graphics packs.** Cricket, football/hockey, kabaddi, lower third, headline bar, wedding
title, logo bug. The cricket and kabaddi boards have live scoring buttons on the camera screen —
tap **+4** without leaving the stream. That is the single feature most likely to justify a
monthly price to a local sports streamer.

**Scenes.** Pre-show / Live / Break / Outro, switched with one tap. Switching changes only
overlay opacity in the GL pipeline, so it is instant and never interrupts the broadcast.

**Licences.** Free / Pro / Studio, granted by codes you issue by hand, verified against
Supabase, cached with a 7-day offline grace window.

---

## 8. What is honestly still weak

Worth knowing before you sell against it:

- **The licence check is bypassable by a determined user with a rooted phone.** Device binding
  stops the realistic problem (one code shared round a WhatsApp group). It won't stop someone
  who reverse-engineers the APK. For a direct-sale product to a known customer list, that's an
  acceptable place to be — just don't assume otherwise.
- **New graphics still need an APK release.** See §6 for when to fix that.
- **Facebook stream keys expire.** Customers will hit this and think the app is broken. The
  destination editor warns them, but expect the support message anyway.
- **No YouTube OAuth.** Users still paste a stream key by hand. Connecting the account would
  let the app create the broadcast, show viewer count and pull chat — the biggest remaining
  jump in perceived value after the scoreboards.
