# DesiDubAnime TV — Fix Prompt

## App Overview

DesiDubAnime TV is an Android TV app (Kotlin + Jetpack Compose for TV) that streams anime from two websites:
- **DDA** (desidubanime.me): WordPress REST API, romaji titles, CLOUD/Mirror embed servers
- **ADH** (animedubhindi.link): WordPress REST API, English titles, HubCloud download servers

The app merges both catalogs. ADH is the PRIMARY source — it has English names and HubCloud servers. DDA adds romaji titles and embed servers. The app plays streams via ExoPlayer (native) or WebView (embed fallback).

Website links are NOT hardcoded — both DDA and ADH base URLs are editable at runtime in the Settings screen (domains rotate every few months). Current: animedubhindi.link, desidubanime.me.

The app has two OkHttp clients: `Network.client` (fast, for click-time requests) and `Network.bulk` (low-priority, for background work like catalog fetches and sweeps).

---

## Remaining Issues (MUST FIX ALL)

### Issue 1: Romaji Names Still Showing

Many anime names are still in romaji instead of English. Examples: "My Dress-Up Darling", "World's Best Assassin reincarnation" (actual English names may differ slightly). English names should show everywhere — on the home grid, detail page, search results, everywhere.

**Why it happens:**
- The DDA WordPress REST API only returns romaji titles. English names must be scraped from the Kiranime theme's HTML pages (homepage, archive listing, detail pages) which render dual-language spans.
- The background title sweep only covers 8 pages of the DDA archive (240 anime out of 600+). Older anime never get their English names scraped.
- The feed load fetches English names for only 12 anime per load (MAX_TITLE_LOOKUPS = 12). If 30 anime are in the feed and 20 are missing English names, only 12 get fixed.
- The feed load BLOCKS on title scraping — it waits for all title fetches to complete before showing the feed. This is wrong — title scraping is cosmetic and should never block the feed.
- English titles ARE persisted via EnglishTitleStore, but a fresh install has nothing persisted.

**What to fix:**
1. In `AnimeRepository.kt` → `fetchHomeFeed()`: Don't block the feed load on title scraping. Fire the title scraping as a background job on the bulk client and return the feed immediately with whatever English titles are already cached. The next feed refresh will show the newly learned names.
2. In `AnimeRepository.kt` → `startTitleSweep()`: Increase the page range from 8 to 50 (cover the full archive). Break early when a page has nothing new or is empty (archive end). After learning new titles, invalidate the feed cache and trigger a silent background feed refresh so the newly learned English names appear.
3. In `AnimeRepository.kt` → `anime()`: Always fetch the English name from the DDA detail page when opening an anime — not just when the name is missing from the map. The map might have a wrong/stale name.
4. Remove the MAX_TITLE_LOOKUPS constant entirely — it's no longer needed since title scraping is fire-and-forget.

---

### Issue 2: 20-30 Second Delay on Clicks After App Open

After the app opens and the home screen appears, clicking on an anime (detail page, continue watching, player) takes 20-30 seconds before anything happens. It should be near-instant (under 3 seconds).

**Why it happens:**
- The feed load (`fetchHomeFeed()`) makes 40+ network requests on the FAST client (`Network.client`), which blocks click-time requests. OkHttp's default per-host concurrency is 5, so 40 requests queue up in batches of 5.
- Specifically, `mergeAdh()` Pass 2 fetches up to 40 adhlinks pages on the fast client. Pass 3 fetches up to 40 DDA anime by slug on the fast client. These are background/catalog operations that should use the bulk client.
- `findAdhFor()` (called when opening a detail page) fetches adhlinks pages for up to 24 candidates on the fast client, blocking other click-time requests.
- `HomeViewModel.init` calls `totalAnimePages()` which triggers `mergedCatalog()` — 18 requests (full ADH catalog + DDA catalog) that run in parallel with the feed load and consume network resources.

**What to fix:**
1. In `AnimeRepository.kt` → `mergeAdh()`: Move Pass 2 (adhlinks page fetches) and Pass 3 (DDA slug fetches) to the bulk client. Pass 2 should use `adhEpisodePage(url, Network.bulk)` instead of `adhEpisodePage(url)`. Pass 3 should use `wpBulk.animeBySlug()` instead of `wp.animeBySlug()`.
2. In `AnimeRepository.kt` → `findAdhFor()`: Reduce the candidate adhlinks page fetches from 24 to 8. Use the bulk client for these adhlinks page fetches. Keep the DDA detail page fetch and ADH search on the fast client (they're click-time requests).
3. In `HomeViewModel.kt` → `init`: Remove the `totalAnimePages()` call. Only call `load()` at app start. The default page count estimate of 20 is fine.
4. In `HomeViewModel.kt` → `selectPage()`: Fetch `totalAnimePages()` lazily on the first sidebar navigation (when the user first scrolls to a numbered page). Check if the current value is still the default estimate before fetching.

---

### Issue 3: Priority — New Anime/Episodes First, Then Clicks

When the app opens, the FIRST priority should be showing new anime/episodes on the home screen. After the home screen is visible, clicks (continue watching, detail page, player) should be responsive. Background work (slug sweep, title sweep, full catalog fetch) should run AFTER the feed is visible.

**What to fix:**
1. The feed load should be minimal — only 3 essential requests on the fast client: DDA latest anime, DDA episode feed, ADH latest posts (or cached catalog). Everything else (title scraping, adhlinks page fetches, slug learning) should be fire-and-forget on the bulk client.
2. Consider skipping `mergeAdh()` Pass 2 and Pass 3 entirely during the feed load — use only cached slugs for matching. The slug sweep (background, bulk client) will learn new slugs and invalidate the feed cache for the next refresh. This makes the feed load much faster.
3. If skipping Pass 2/Pass 3 is too aggressive, at minimum move them to the bulk client so they don't block the fast client.
4. After the slug sweep learns new slugs, trigger a silent background feed refresh so the newly-learned slugs appear in the merged feed (HubCloud servers + English names).
5. The `HomeViewModel.refresh()` method (called on ON_RESUME) should only refresh the feed — it should NOT fetch `totalAnimePages()`.

---

### Issue 4: Don't Load All Pages at Once

Only the first page (home) should load/update at app start. Sidebar pages should load on demand when the user navigates to them. This makes new anime/episodes appear quickly and keeps clicks responsive.

**What to fix:**
1. Don't call `totalAnimePages()` at app start (already covered in Issue 2).
2. Don't build the full merged catalog at app start. The `mergedCatalog()` function fetches the entire DDA catalog (10 pages of 100) + entire ADH catalog (8 pages of 100) = 18 requests. This should only run when the user navigates to a sidebar page.
3. In `AdhScraper.kt` → `latestPosts()`: Add a `client` parameter (defaulting to `Network.client`) so the fallback in `adhPostsForMerge()` can pass `Network.bulk`.
4. In `AnimeRepository.kt` → `adhPostsForMerge()`: Make the `latestPosts()` fallback use `Network.bulk`.

---

### Issue 5: Android TV Performance & RAM Optimization

Android TV boxes have limited RAM (often 1-2GB) and weak CPUs. All changes must be memory-efficient.

**What to fix:**
1. In `PlayerScreen.kt` → `rememberStreamPlayer()`: Reduce the ExoPlayer max buffer from 180 seconds to 60 seconds, and min buffer from 50 seconds to 30 seconds. The 180s buffer uses ~120MB RAM on a 5Mbps stream — too much for low-RAM TV boxes. 60s is enough for most streams.
2. In `App.kt` → `newImageLoader()`: Reduce the image disk cache from 150MB to 100MB. TV boxes have limited flash storage too.
3. In `Network.kt` → `bulk` client: Reduce `maxRequests` from 8 to 6 and `maxRequestsPerHost` from 4 to 3. This leaves more network capacity for the fast client.
4. In `AnimeRepository.kt`: Reduce `MAX_SLUG_LOOKUPS` from 40 to 20. The slug sweep handles the rest in the background.
5. Keep `PARALLEL_RESOLVES = 2` in `PlayerViewModel.kt` — 2 parallel WebViews is already conservative. 1 would make startup too slow.

---

## Key Architecture Notes

1. **ADH is the PRIMARY source** — English titles from ADH always win over DDA-scraped names. HubCloud servers are tried first in the player. The `mergeAdh()` function always learns the ADH title via `learnTitle()`, overriding any DDA-scraped name.

2. **The slug sweep is the key to everything** — It learns the ADH→DDA slug map in the background. Once it completes, all anime have HubCloud servers and English names. It runs on the bulk client and persists results via `AdhSlugStore`, so it only costs the full catalog once per install.

3. **The title sweep is the key to English names** — It scrapes the DDA archive for English+romaji name pairs. Once it completes, all anime have English names. It runs on the bulk client and persists results via `EnglishTitleStore`.

4. **Fire-and-forget is the key to fast feed load** — Title scraping and adhlinks page fetches should NEVER block the feed load. The feed should return immediately with whatever data is cached, and background work should update the cache for the next refresh.

5. **Two OkHttp clients** — `Network.client` (fast, with disk cache, for click-time requests) and `Network.bulk` (low-priority, no disk cache, for background work). Background work must NEVER use the fast client — it would block click-time requests.

---

## Files to Modify

1. **`AnimeRepository.kt`** — Main changes: `fetchHomeFeed()`, `mergeAdh()`, `findAdhFor()`, `startTitleSweep()`, `startSlugSweep()`, `anime()`, `adhPostsForMerge()`, constants
2. **`HomeViewModel.kt`** — Remove `totalAnimePages()` from `init`, add lazy fetch in `selectPage()`
3. **`AdhScraper.kt`** — Add `client` parameter to `latestPosts()`
4. **`Network.kt`** — Reduce bulk client concurrency
5. **`PlayerScreen.kt`** — Reduce ExoPlayer buffer
6. **`App.kt`** — Reduce image disk cache

---

## Testing Checklist

After implementing all changes, verify:

- [ ] App opens → home feed loads in under 5 seconds (was 20-30s)
- [ ] Click on an anime → detail page opens in under 3 seconds (was 20-30s)
- [ ] Continue Watching → player opens in under 3 seconds (was 20-30s)
- [ ] "My Dress-Up Darling" shows English name (not romaji) after first detail open
- [ ] "World's Best Assassin" shows English name after first detail open
- [ ] HubCloud servers show for "Easygoing Territory Defence" (already fixed, verify)
- [ ] Sidebar pages load on demand (not at app start)
- [ ] App doesn't crash on low-RAM TV box (test with 1GB RAM if possible)
- [ ] Background sweeps (slug + title) complete without blocking UI
- [ ] After 5-10 minutes, all anime on home screen show English names
- [ ] Player doesn't stutter with reduced buffer (test on slow connection)
- [ ] Images still load from disk cache on second app open
- [ ] Build succeeds: `.\gradlew.bat :app:assembleDebug --no-daemon`