# Dubanime TV — performance & playback flow notes

Session goal: kill the "app feels slow/laggy while it refreshes" feel at startup,
make start-up refresh automatic, and make the player smooth and crash-free.
`D:/code/vega-tv` was read-only reference (never modified).

---

## 1. Why it used to lag

At launch the app did everything at once, on one network budget:

| Job | Cost |
|---|---|
| Home feed merge | ~50 requests (DDA anime + episodes, ADH/AD/TW latest-100 with a Jsoup parse per post, title listings, up to 20 `adhlinks` pages + 20 DDA slug fetches) |
| Catalog warm | 3 scrapers fired immediately |
| Slug sweep | ~600 pages at concurrency 3 |
| Title sweep | 50 pages |
| Re-merge | 3 catalog landings each triggered a full re-merge |
| Posters | Coil shared the **click-lane** OkHttp dispatcher |

OkHttp opens only 5 connections per host, so a detail-open queued behind
hundreds of sweep pages → "click does nothing for a few seconds", and the
Compose thread fought the Jsoup parses → stutter.

---

## 2. The pipeline (how it works now)

### Network lanes — `data/api/Network.kt`

Four clients, four dispatchers, four connection pools. Nothing queues behind
anything else:

| Lane | Used by | Timeouts |
|---|---|---|
| `Network.client` | click path (detail, episodes, server resolve) | 20s connect / 25s read / 35s call |
| `Network.feed` | home feed merge | 12s / 18s / 20s (fails fast, feed must not hang) |
| `Network.bulk` | full catalog, slug sweep, title sweep, catalog paging | small per-host concurrency, no disk cache |
| `Network.images` | Coil | own queue, same browser headers |

### InteractionGate — `data/api/InteractionGate.kt`

Two independent counters, both clamping at zero:

- `priority { }` — a **click** is being served.
- `holdFeed { }` — the **home feed build** is being served. Same "user is
  waiting" weight, but deliberately *not* a click, because the feed lane's own
  interceptor waits for clicks (`awaitIdle`). Counting the feed as a click made
  every feed request wait for the feed to finish (self-deadlock).
- `awaitIdle()` — used by the click lane. Waits for clicks only.
- `awaitForeground()` — used by the bulk lane. Waits for clicks **and** feed
  builds.
- Wait is capped at 8s so a dead host cannot wedge background work forever.

Result: while a click or the feed is in flight, every sweep request parks.

### BackgroundYield — `data/api/BackgroundYield.kt`

`paused` flag held by the player. Every sweep checks `waitIfPaused()` between
pages (750ms sleep). This pauses the *job*, not the HTTP client, so the player's
own requests are untouched. Pauses on playback, resumes on exit.

### Repository — `data/repo/AnimeRepository.kt`

- `fetchHomeFeed()` = `holdFeed { fetchHomeFeedInner() }`.
- Feed slug budget: `FEED_SLUG_LOOKUPS = 6` at `FEED_LOOKUP_PARALLELISM = 4`
  (was 20). The sweep finishes the rest off the critical path.
- English-title listings are awaited on the feed path only when the learned-name
  map is cold (`TITLE_WARM_MIN = 200`).
- Background re-merge is debounced: `RE_MERGE_DEBOUNCE_MS = 2_500` on one
  `remergeJob` (was: every catalog landing re-merged).
- Catalog warm-ups are staggered `CATALOG_STAGGER_MS = 1_500` apart.
- Title sweep starts after `TITLE_SWEEP_DELAY_MS = 4_000`.
- Sweep pages breathe `SWEEP_PAGE_GAP_MS = 120` apart; semaphore 3 → 2.

### App / UI

- `App.onCreate` prefetches `repository.homeFeed()` on `ServiceLocator.appScope`
  → **refresh happens automatically on start**, no Refresh click needed.
- Splash 1500ms → 800ms.
- Coil: no crossfade (that animation was the visible "pop-in" jank), images on
  `Network.images`.
- Home: sidebar loading state collected only inside `Sidebar`, so a feed refresh
  no longer recomposes the whole screen.
- `KEYCODE_SEARCH` opens Search directly.

### Player

- `DefaultLoadControl` sized by `ActivityManager.isLowRamDevice`:
  32MB / 45s max, back buffer 0 (low RAM) — 64MB / 75s / 10s otherwise;
  `setTargetBufferBytes` set to match.
- `setEnableDecoderFallback(true)` — bad HW decoder falls back instead of crashing.
- Resume seek applied **before** first frame (no flash of episode start).
- Buffering spinner delayed 350ms (no flicker on short stalls).
- 45s no-frame watchdog only fires when `playWhenReady`.
- "Up Next" card: `Overlay.NEXT`, `NEXT_COUNTDOWN_S = 10`, Play now / Cancel.
- Playback speed persisted in `PlaybackSession.preferredSpeed`.
- Audio attributes + `C.WAKE_MODE_NETWORK`; `WAKE_LOCK` in the manifest.
- History written every 15s (was 5s) + exact write on exit via `snapshotProgress`.

### Detail / Search

- Detail: primary "▶ Play / Resume" button with landing focus, per-episode
  progress bar, ✓ watched badge, source line, `resumePositionFor(index)`.
- Search: rewritten D-pad keyboard (A–Z, SPACE, DEL, CLR, Search), adaptive
  results grid, idle/empty/error states, stale-result guard.

---

## 3. Rules to keep (do not regress)

1. Never put the feed build inside `priority` — it deadlocks the feed lane.
2. Background work always goes through `Network.bulk` + `awaitForeground()`.
3. New long loops check `BackgroundYield.waitIfPaused()` between pages.
4. Never let Coil or any background job use `Network.client`.
5. Feed-path work must stay bounded and fail fast (6 lookups, 20s call timeout).
6. Anything that re-merges the feed must go through the 2.5s debounce.

---

## 4. Build

```
./gradlew.bat :app:compileDebugKotlin --console=plain --offline
./gradlew.bat :app:assembleDebug      --console=plain --offline
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## 5. Self-update

The app updates itself from `github.com/aashish1332/Manime-tv` releases. The
full flow, the failure handling and the publishing checklist live in
`FEATURE_AUDIT.md` (section "Self-update"). Two rules matter for performance:

1. The launch check runs 5 s behind the feed build on its own OkHttp client,
   so it can never compete with the home refresh for the launch network.
2. Inside the 6-hour quiet window it reads the cached release body from disk
   and makes **no request at all**.

---

## 6. Still to verify on a real TV / box

No adb device or emulator is available on this machine, so everything below is
**compile + package verified only**, not runtime verified.

- [ ] Cold start: Home populates by itself, no Refresh click, no stutter.
- [ ] Open a detail immediately after launch — should be instant, not queued.
- [ ] Play an episode: scrubbing, resume position, speed, Up Next countdown.
- [ ] Background sweeps pause during playback (`BackgroundYield`) and resume after.
- [ ] Search with the D-pad keyboard.
- [ ] Low-RAM box: buffer sizes chosen by `isLowRamDevice`.
