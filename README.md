# DesiDubAnime TV

An Android TV app for **desidubanime.me** — browse the catalog with a remote and
play episodes with a native player (seek, autoplay-next, server switching).

Built with Kotlin, Jetpack **Compose for TV**, **Media3/ExoPlayer**, and a WebView
stream-interceptor. No backend access to the site is required — the app reads the
public WordPress REST API and the site's own AJAX endpoints. See `docs/API.md` for
the full reverse-engineered data map.

## How it works

| Concern | Approach |
|---|---|
| Catalog / search | `wp-json/wp/v2/anime` (with `_embed` for covers) |
| Episode list | `admin-ajax.php?action=get_episodes&anime_id=…` (the site's own endpoint) |
| Servers per episode | scrape the `/watch/` page, decode `data-embed-id="base64(name):base64(url)"`, grouped SUB / DUB |
| CLOUD sub-servers | parse `const sources=[SERVER 1, SERVER 2…]` from the CLOUD player page |
| **Playback** | load the embed in an **offscreen WebView**, intercept the `.m3u8/.mp4` request, then play it in **Media3** (native seek + track menus). Falls back to a visible WebView embed when no direct stream can be captured (e.g. P2P servers). |
| Fastest-by-default | candidates are ordered (own CLOUD first) and auto-resolved; the first that yields a real stream is used |
| Autoplay next | Media3 `STATE_ENDED` → load next episode in the queue |

## Remote (D-pad) controls

- **D-pad / OK** — navigate grids and menus, select
- **Back** — go back (closes the player menu first if open)
- **Play/Pause, FF/RW, seek** — Media3 player controls (native)
- **MENU** (or options button) — open the in-player panel: **switch server**,
  **next / previous episode**

## Build & run

Requires **Android Studio** (Ladybug+). It manages the SDK for you.

```bash
# from a terminal, if you have the SDK + local.properties set:
./gradlew :app:assembleDebug
# install on a connected Android TV / emulator:
./gradlew :app:installDebug
```

Or just open the project in Android Studio and press ▶ with an Android TV
emulator (API 34) or a real device selected.

- **minSdk 21**, **targetSdk 34**
- The app declares `LEANBACK_LAUNCHER` so it appears on the Android TV home screen,
  and also `LAUNCHER` so you can test it on a phone/tablet.

## Project layout

```
app/src/main/java/me/desidubanime/tv/
├── data/            REST api, models, repository
├── scrape/          episode-page server decoding + CLOUD resolver
├── player/          StreamResolver (WebView intercept), PlayerViewModel, session
└── ui/              home · detail · player · search · theme
```

## Notes / limitations

- Servers are third-party and obfuscated, so an individual server can break. The
  app keeps a full multi-server fallback list so playback keeps working — press
  MENU to pick another server if one stalls.
- Direct-stream (native) playback gives the best remote experience. Servers that
  only work as embeds (e.g. P2P) play in a WebView with the embed's own controls.
