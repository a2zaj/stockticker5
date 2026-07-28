# StockTicker — Spigot Plugin

Displays live stock quotes (works for NASDAQ tickers like AAPL, MSFT, TSLA, NVDA, etc.)
on a scoreboard sidebar and on in-world signs, refreshed on a timer.

## Easiest way to get a built .jar: GitHub Actions (no local Java/Maven needed)

1. Create a new **public or private** GitHub repo.
2. Upload everything in this folder to it (including the `.github/` folder — it's
   hidden in Finder/Explorer by default, make sure it comes along).
3. Push to `main`. Go to the repo's **Actions** tab — a build will start automatically.
4. When it finishes (few minutes — it's compiling Spigot from source under the hood),
   open the completed run and download the `StockTicker-jar` artifact. Unzip it —
   that's `StockTicker.jar`.
5. Drop it into your server's `plugins/` folder.

No Java, Maven, or BuildTools install on your own machine required — GitHub's
servers do all of it.

## Alternative: build it locally


## 1. Get a free API key
Sign up at https://finnhub.io/register (free tier, ~60 requests/minute — plenty for
a handful of tickers refreshed every 20-30s). Paste the key into `config.yml`.

## 2. Build
Requires Java 25 and Maven.

> Note: Spigot 1.21.8 itself only requires Java 21 to run — targeting 25 here just
> means you need JDK 25 installed to compile the plugin, and your server's JVM
> must also be JDK 25+ at runtime (Spigot server jars generally run fine on newer
> JDKs than their stated minimum, but check your server's `java -version` before
> deploying).

```bash
cd stockticker
mvn clean package
```

This downloads the Spigot API dependency from the Spigot Maven repo (needs internet
access) and produces `target/StockTicker.jar`.

> If you don't already have the Spigot API artifact installed locally, you may need
> to run BuildTools first (https://www.spigotmc.org/wiki/buildtools/) to install
> `spigot-api-1.21.8-R0.1-SNAPSHOT` into your local Maven repo, OR just switch the
> pom.xml dependency to a public mirror if you use one.

## 3. Install
1. Drop `target/StockTicker.jar` into your server's `plugins/` folder.
2. Start the server once to generate `plugins/StockTicker/config.yml`.
3. Edit that config.yml: set your API key and the tickers you want.
4. Run `/stocks reload` in-game (or restart) to apply.

## Scoreboard sidebar (optional, off by default)
The always-on sidebar showing a fixed list of tickers is **disabled by default**
now — stock signs are the main way to display prices. If you want the sidebar
back, set `enable-scoreboard: true` in `config.yml` and list tickers under
`tickers:`.

## Scrolling ticker tape (multiple stocks, moving marquee)
For a stock-exchange-style scrolling ticker showing several stocks in a loop,
use `/stockdisplay tape create`:

```
/stockdisplay tape create 8 3 AAPL,MSFT,TSLA,NVDA
/stockdisplay tape create 10 2 AAPL,MSFT,TSLA,NVDA 90
```

- First two numbers are the size in blocks: `<width> <height>`.
- Then a comma-separated ticker list, **no spaces** (spaces would split it into
  separate command arguments) — `AAPL,MSFT,TSLA,NVDA`, mixing regular tickers
  and `=F` futures symbols is fine.
- Optional last number is a rotation offset in degrees, same as `/stockdisplay create`.

It uses the same wall-snapping placement as regular displays: look at a wall
within 6 blocks and it mounts flush against it, or falls back to floating in
front of you if there's no wall in range.

The tape cycles through `TICKER $price ±change%` for each stock in your list,
separated by `|`, looping continuously right-to-left. Scroll speed is set
server-wide via `tape-scroll-ticks` in `config.yml` (default `4` — every 4 ticks,
about 5 times a second).

**Rotate** and **remove** work exactly like they do for regular big displays —
`/stockdisplay rotate [degrees]` and `/stockdisplay remove [radius]` both check
for the nearest tracked object of *either* type (single display or tape) and act
on whichever is closer, so you don't need to remember which command family a
given screen belongs to.

Notes and known limitations:
- Sizing in blocks is approximate. Minecraft's font isn't literally block-metric,
  so the width/height you give are converted to a character count using a tuned
  estimate — it should be in the right ballpark, but if a tape looks too cramped
  or too sparse, try adjusting the width/height (or see `CHARS_PER_BLOCK_AT_SCALE_1`
  in `TickerTapeManager.java` if you want to tune the conversion itself).
- This is a **character-window scroll**, not a smoothly-sliding one: since
  Minecraft's text displays have no clipping/masking, continuously moving the
  entity through space would eventually show it drifting outside the "sign"
  area rather than looking framed. Instead, the plugin cycles which slice of a
  longer string is currently visible, stepping one character at a time — this
  reads as continuous scrolling in-game but is technically a stepped animation,
  not a literal smooth translation.
- Tape text is intentionally single-color (white) rather than red/green
  per-stock — color codes are two-character sequences, and slicing a scrolling
  window through a string containing them could cut a code in half right at the
  window edge, which would flash a stray character. A plain `^`/`v` arrow marks
  direction instead.
- Persisted the same way as everything else, in `plugins/StockTicker/tickertapes.yml`.

## Big black-background displays (bigger than signs, custom color)
Regular Minecraft signs can't have a solid black background or arbitrary size —
that's a texture limitation of the block itself. For a bigger, styled display,
use the `/stockdisplay` command instead, which spawns a floating "big screen"
(a TextDisplay entity) with a solid black background and white/colored text,
sized however big you want.

**Create one:**
```
/stockdisplay create MNQ=F
/stockdisplay create AAPL 5.0
/stockdisplay create AAPL 5.0 90
```
- Second number (optional) is scale — default `3.0`, try `5.0`-`8.0` for
  something wall-sized.
- Third number (optional) is a rotation offset in degrees, applied on top of
  whatever facing it picks below — use it to fine-tune the angle.

**Where it appears:** if you're looking at a nearby wall (within 6 blocks) when
you run the command, the display mounts flush against that wall's surface,
facing outward, instead of floating in midair or clipping into the block. If
there's no wall in range — or you're looking at a floor/ceiling/diagonal
surface it can't flatten against — it falls back to floating a couple of
blocks in front of you at eye height, facing back toward you, same as before.
Chat tells you which one happened.

**Rotate one:** stand near it (within 5 blocks), then run `/stockdisplay rotate`
to turn it 90° from however it's currently facing. Pass your own amount for a
different turn, e.g. `/stockdisplay rotate 45` or `/stockdisplay rotate -30`;
`/stockdisplay rotate 180` flips it to face the opposite way. This works the
same whether the display is wall-mounted or floating.

**Remove one:** stand near it, then run `/stockdisplay remove`. This removes
whichever tracked display is closest to you, as long as one is within 5 blocks
(pass a different radius if you need to, e.g. `/stockdisplay remove 10`).

**List what's tracked:** `/stockdisplay list`

These persist across restarts the same way signs do (tracked in
`plugins/StockTicker/displays.yml`), refresh on the same cycle, and support
the same tickers — including `=F` futures symbols like `NQ=F` / `MNQ=F`.

Notes:
- Uses `stockticker.display` permission, default: everyone allowed.
- These are entities, not blocks — you can't accidentally break them by mining;
  removal only happens via `/stockdisplay remove`.
- Displays are static (no billboard rotation) — once placed, they stay locked
  to whatever orientation they were created or rotated to. They don't turn to
  track players walking past.

## Live stock signs (compact, wood-textured)
You can now place physical signs in the world that show a live price and update
automatically:

1. Place a sign.
2. Line 1: `[stock]`
3. Line 2: a ticker symbol, e.g. `AAPL`
4. Leave lines 3-4 blank — the plugin fills those in and keeps them refreshed.

The plugin will confirm creation in chat and the sign will show `...` until the
next refresh cycle, then something like:

```
[stock]
AAPL
$213.44
+1.32%
```

Notes:
- Signs are tracked in `plugins/StockTicker/signs.yml` and survive restarts.
- Breaking a stock sign automatically stops tracking it.
- Signs only update while their chunk is loaded — the plugin won't force-load
  chunks just to refresh a sign nobody's near.
- Ticker symbols used on signs are automatically added to the same fetch cycle
  as the scoreboard tickers (no duplicate API calls for the same symbol).
- By default anyone can place a stock sign (`stockticker.sign` permission,
  default `true`). Restrict it with a permissions plugin if you only want
  certain players/ranks creating them.

### Futures (NQ, MNQ, etc.)
Any ticker ending in `=F` — Yahoo Finance's futures symbol convention — is
automatically routed to Yahoo's unofficial data endpoint instead of Finnhub.
Use symbols like:
- `NQ=F` — E-mini Nasdaq-100 futures
- `MNQ=F` — Micro E-mini Nasdaq-100 futures (if Yahoo has current data for it —
  micro contracts are less consistently covered than the standard E-minis)

Just place a sign with `NQ=F` on line 2, same as any stock ticker. The sign
will show price, % change, and the **time the underlying data is actually
from** (not necessarily "now" — see caveat below), e.g.:

```
[stock]
NQ=F
$21,842.50
+0.8% 14:32
```

**Important caveat:** unlike Finnhub, Yahoo's futures endpoint is *unofficial* —
there's no API key, no published rate limits, and no guarantee it keeps
working (Yahoo can change or block it without notice, and it has been known to
report "data not available" for some contracts at times). This is fine for a
"just show something roughly current" server feature, but don't treat it as a
reliable or accurate real-time futures feed — that's what the on-sign timestamp
is for: so players can see exactly how fresh the shown price actually is.

## Config options (`config.yml`)
- `api-key` — your Finnhub API key (used if `api-keys` below is empty)
- `api-keys` — optional list of multiple Finnhub keys, round-robin'd across
  requests for a higher effective rate limit (e.g. three free-tier keys ≈
  180 calls/minute instead of 60). Each needs its own free Finnhub signup.
  If this list has entries, `api-key` is ignored.
- `refresh-seconds` — how often to poll (keep ≥20 per key for a handful of tickers)
- `tape-scroll-ticks` — scroll speed for `/stockdisplay tape` displays, in server
  ticks between steps (default `4`; lower = faster)
- `tickers` — list of ticker symbols to track
- `scoreboard-title` — sidebar title, supports `&`-color codes

## Notes / things you might want to extend
- Currently shows a scoreboard sidebar to all players. If you'd rather show it in
  the action bar, boss bar, or only to specific players, that logic all lives in
  `renderScoreboard()` — swap it out for `player.sendActionBar(...)` etc.
- Finnhub's free tier gives real-time-ish quotes for US exchanges (NASDAQ/NYSE).
  Some other providers delay data by 15+ minutes on free tiers — check before
  swapping providers if "live" matters to you.
- If you add many tickers, raise `refresh-seconds` proportionally to stay under
  the 60 calls/minute free-tier cap per key (each ticker = 1 API call per refresh
  cycle), or add more keys under `api-keys` in `config.yml` instead.
- The scoreboard "fake entries" trick (colored `ChatColor` codes as unique
  scoreboard entries) is a longstanding Bukkit pattern for building sidebar lines
  that aren't tied to real player names.
