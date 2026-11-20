# DesiDubAnime — Reverse-engineered API map

Platform: WordPress + **Kiranime Pro** theme. Base host: `https://www.desidubanime.me`.
No auth needed for read. Cloudflare fronts everything — always send a real browser
`User-Agent` and `Referer: https://www.desidubanime.me/`.

## 1. Catalog (clean JSON)
- List anime: `GET /wp-json/wp/v2/anime?per_page=30&page=N&_embed`
  - `_embed` pulls `wp:featuredmedia` so we get the cover image inline.
- One anime: `GET /wp-json/wp/v2/anime/{id}`
  - Fields: `title.rendered`, `content.rendered` (synopsis), `genre` (term ids),
    `anime_status`, `anime_type`, `premiered`, `featured_media`.
- Search: `GET /wp-json/wp/v2/anime?search={q}&_embed`
- Genres: `GET /wp-json/wp/v2/genre` (taxonomy terms)

## 2. Episode list  (THE endpoint the site's JS actually uses)
`GET /wp-admin/admin-ajax.php?action=get_episodes&anime_id={id}&page={n}&order=asc`
Response:
```
{ "success": true,
  "data": {
     "episodes": [
        { "id": 27276, "number": "Episode 1", "title": "A New Challenge",
          "thumbnail": "https://image.tmdb.org/...jpg",
          "url": "https://www.desidubanime.me/watch/....-episode-1/",
          "post_title": "...", "duration":"24M", "released":"2023-10-01" }
     ],
     "max_episodes_page": <int>   // total pages -> loop 1..max
  } }
```
Order asc = natural episode order (perfect for the "next episode" queue).

## 3. Servers for an episode  (scrape the /watch/ page)
Each server button carries:  `data-embed-id="BASE64(name):BASE64(url)"`
Decode both halves. Servers are grouped by CSS container:
  - `.player-selection.player-sub`  -> SUB servers
  - `.player-selection.player-dub`  -> DUB servers
Observed servers: Mirrordub (gdmirrorbot — itself a multi-mirror aggregator),
Streamp2pdub (p2pplay), Abyssdub, VMolydub (vidmoly), CLOUD (own host).

## 4. CLOUD server -> sub-servers ("mirror ke andar sub-servers")
CLOUD url = `https://cloud.desidubanime.me/external/{hex}`
That HTML contains:  `const sources = [{ "name":"SERVER 1","url":"/play/{hex}"}, ...]`
Each `/play/{hex}` renders an **obfuscated JWPlayer**. The real `.m3u8` is built at
runtime with a token — NOT extractable from static HTML. => we must run it in a
WebView and intercept the network request.

## 5. Getting the actual stream (playback strategy)
Static scraping CANNOT get the final m3u8 (obfuscated + token). Reliable path:
  - Load the chosen embed URL in an **offscreen WebView**.
  - `WebViewClient.shouldInterceptRequest` — watch for `.m3u8` / `.mp4` / `.ts`
    requests; capture URL + request headers (Referer/UA/Cookie).
  - Hand that to **Media3 ExoPlayer** with the same headers => native seek,
    autoplay-next, track selection.
  - If nothing is intercepted within a timeout (e.g. pure p2p) => fall back to
    playing the embed visibly in the WebView.
This survives obfuscation changes because we never parse their JS — only watch
what URLs it requests.

## 6. Mirrordub (gdmirrorbot) -> sub-servers
Mirror url = `https://gdmirrorbot.nl/embed/{sid}` (host changes over time).
Its JS POSTs to `{origin}/embedhelper.php` with form `sid={sid}&UserFavSite=&currentDomain=["<host>",...]`.
NOTE: the endpoint 302s to another host (e.g. pro.iqsmartgames.com) and the POST
must be RE-SENT to the Location (plain redirect-following downgrades to GET).
Response JSON:
  - `siteUrls`:          { key -> base url }          e.g. "strmtp": "https://streamtape.site/e/"
  - `mresult` (base64):  { key -> file id }           e.g. "strmtp": "M01MJpLdljCmKAP"
  - `siteFriendlyNames`: { key -> readable name }     e.g. "strmtp": "streamtape"
Sub-server embed url = siteUrls[key] + mresult[key], for keys present in BOTH maps.
Observed hosts: rpmshare, abyss, streamhg, streamp2p, upnshare, voe, streamtape,
mixdrop, doodstream, dropload, streamruby, krakenfiles, earnvids, player4me, seekstreaming.
