# Manime vs Vega TV — feature audit

Read-only comparison of `D:\code\Dubanime` (Manime / DesiDubAnime TV) against
`D:\code\vega-tv`. Vega was never modified.

Legend: **✅ parity** · **＋ Manime ahead** · **✗ missing in Manime** · **– n/a**

## Browse & content

| Feature | Vega TV | Manime | Verdict |
|---|---|---|---|
| Home rows (trending / latest / per-source) | yes, provider-driven | yes, merged from 4 scrapers | ✅ ＋ merge is broader |
| Hero banner with rotating picks | yes | yes, with honest badge + resume | ✅ |
| Continue Watching | yes | yes (position, server, audio, sub, quality) | ✅ ＋ restores full playback setup |
| NEW episode badges | no | yes, first-run baselined | ＋ Manime only |
| Search | text field + results grid | D-pad on-screen keyboard + adaptive grid | ＋ Manime ahead on a remote-only device |
| Dedicated Catalog screen | yes (`CatalogScreen`) | no — sidebar jumps per source | ✗ low value (Home + per-source rows already cover it) |
| Watchlist | yes (`WatchlistStore` + screen) | no | ✗ **biggest functional gap** — see "Deferred" |
| Family filter | yes | – (anime dub catalogue) | – |
| Provider extensions (JS plugins) | yes (`ProviderManager`, `ExtensionStore`) | no — 4 compiled-in scrapers | ✗ architectural, not a drop-in |

## Playback

| Feature | Vega TV | Manime | Verdict |
|---|---|---|---|
| Native ExoPlayer | yes | yes | ✅ |
| Device-sized buffers | partial | `isLowRamDevice` → 32/64 MB, back buffer 0/10 s | ＋ Manime ahead |
| Resume before first frame | yes | yes | ✅ |
| Track / server menu | yes | yes (server, quality, audio, subs) | ✅ |
| Speed control, persisted | no | yes (`PlaybackSession.preferredSpeed`) | ＋ Manime only |
| Up Next countdown | no | yes, 10 s + Play now / Cancel | ＋ Manime only |
| External player (VLC/MX) | yes | no | ✗ cheap to add, deferred |
| Multi-connection MKV (`ParallelRangeDataSource`) | yes | no | ✗ large; only helps big MKV on fast links |
| Matroska chapters | no | yes (`MkvChapters`) | ＋ Manime only |
| DRM (Widevine) | yes (`DrmSupport`) | no | ✗ not needed for these sources |
| Crash / last-playback diagnostics | file + Settings card | crash handler + visible server log | ✅ |

## Network & sourcing

| Feature | Vega TV | Manime | Verdict |
|---|---|---|---|
| Per-lane OkHttp dispatchers | yes (`NetClients`) | yes (`client`/`feed`/`bulk`/`images`) | ✅ |
| Background work yields to clicks | yes (`ProviderRateLimiter`) | yes (`InteractionGate` two-counter) | ✅ ＋ feed is its own hold |
| Background work pauses during playback | yes (`AppVisibility`) | yes (`BackgroundYield`) | ✅ |
| DNS over HTTPS | yes (`okhttp-dnsoverhttps`) | no | ✗ ISP-level blocking only; deferred |
| Cloudflare challenge UI | yes (`WafScreen`) | yes — `TwQrScreen` + `FileHostBrowser` WebView | ✅ |
| Runtime-editable source domains | yes (provider settings) | yes, 4 URLs applied instantly | ✅ |
| Image lane for Coil | yes | yes | ✅ |
| Disk response cache | yes | yes (20 MB click + 8 MB feed) | ✅ |

## Settings

| Feature | Vega TV | Manime | Verdict |
|---|---|---|---|
| Domain / provider URLs | yes | yes | ✅ |
| Version display | yes | **added now** | ✅ |
| Check for updates | yes | **added now** | ✅ |
| Clear cache | yes | **added now** (image + network) | ✅ |
| Clear watch history | no (erase all only) | **added now**, granular | ＋ Manime only |
| Reset NEW badges | no | **added now** | ＋ Manime only |
| Subtitle style, max fps, audio language, TMDB key, sync folder | yes | no | ✗ deferred (see below) |

## Deferred, and why

- **Watchlist** — needs a store, a row on Home and a toggle on Detail. Highest
  user value of everything left; deserves its own pass so the Home layout is
  re-tuned around an extra row instead of bolted on.
- **External player** — one intent + one setting, but it can only be verified on
  a device with VLC installed. Left for a session with a box present.
- **Sync** — Vega syncs through a user-chosen SAF folder. Manime has no accounts
  and no folder picker; adding one changes the app's data model.
- **TMDB metadata / subtitle APIs** — both need a user-supplied API key.
- **Provider extensions** — Vega's plugin JS runtime is a different architecture,
  not a feature that can be ported line by line.
- **DoH, multi-connection MKV, DRM** — only pay off on specific networks/devices;
  each adds a dependency and risk for no gain on the current sources.

---

# Self-update (implemented)

Modelled on Vega's GitHub-release updater, with the failure modes it leaves open
closed.

## Flow

```
App.onCreate
  └─ appScope.launch { delay(5s); UpdateManager.checkOnLaunch() }
        │
        ├─ player open?  → stop (a dialog over a film is worse than a stale version)
        ├─ checked < 6 h ago?  → offer only from the cached release body
        └─ UpdateApi.latest()
              ├─ If-None-Match: <etag>   → 304 reuses the cached body (free, no rate limit)
              ├─ 404                     → NoRelease → "Up to date"
              ├─ 403/429                 → "GitHub rate limit — try again later"
              └─ 200                     → parse tag, notes, APK asset + sha256 digest
                    └─ newer than BuildConfig.VERSION_NAME?
                          → UpdateState.Available + offered (launch notice)
```

`UpdateManager` is a single state machine shared by the launch notice and the
Settings card, so a download started in one is visible in the other and cannot
run twice (`UpdateState`: Idle → Checking → Available → Downloading → Ready →
Failed).

```
"Update now"
  └─ ApkUpdater.download()            cache/updates/*.part → *.apk
        ├─ 128 KB chunks, ensureActive() per chunk (Cancel stops the transfer)
        ├─ SHA-256 computed while streaming, compared to the release digest
        └─ progress throttled to 150 ms (no per-chunk recomposition on a TV GPU)
  └─ ApkUpdater.problem()             package name + versionCode must be ours and newer
  └─ startInstall()
        ├─ API 26+ and no permission → Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        │                              MainActivity.onResume finishes the install
        └─ otherwise                 → FileProvider content:// → system installer
```

## What it does better than the reference

| Vega TV | Manime |
|---|---|
| One request per check, plain `releases/latest` | Conditional `If-None-Match`; a 304 costs no rate-limit quota |
| Download logic duplicated in the prompt and in Settings | One `UpdateManager` state machine for both |
| No integrity check — a truncated APK reaches the installer | SHA-256 against the release's own asset digest |
| No package check — a wrong asset installs or fails obscurely | Package name + versionCode verified before the installer opens |
| Progress state set per 64 KB chunk | Throttled to 150 ms |
| `Dialog` (focus leaves the TV focus tree) | In-app overlay — one focus graph, D-pad works immediately |
| Cancelled download may still open the installer | Dismiss = cancel, and the state returns to the offer |

## Publishing a release

The app reads `github.com/aashish1332/Manime-tv` → `releases/latest`.

```bash
# 1. bump app/build.gradle.kts  (versionCode +1, versionName "x.y.z")
./gradlew.bat :app:assembleDebug --console=plain --offline
# 2. publish
gh release create v1.1.1 \
  app/build/outputs/apk/debug/app-debug.apk \
  --repo aashish1332/Manime-tv --title "Manime v1.1.1" --notes "…"
```

Rules:

1. `versionName` **must** equal the tag without the `v`, or the app will keep
   offering an update it already has.
2. `versionCode` must increase, or the package manager rejects the install.
3. Sign every release with the same key. The debug keystore is fine for
   sideloading, but a *different* key can never update an installed app.
4. The asset name is free-form; the updater takes the first `.apk` asset.
