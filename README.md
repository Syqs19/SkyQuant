<div align="center">

<img src="docs/assets/icon-skyquant-modrinth.png" width="140" alt="SkyQuant">

# SkyQuant

### A market terminal for Hypixel SkyBlock

**Know what something is worth before you buy, craft or forge it.**

[![Modrinth](https://img.shields.io/modrinth/dt/skyquant?logo=modrinth&label=Modrinth&color=00AF5C&style=for-the-badge)](https://modrinth.com/mod/skyquant)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-62B47A?style=for-the-badge)](https://modrinth.com/mod/skyquant)
[![Fabric](https://img.shields.io/badge/Fabric-Client--side-DBD0B4?style=for-the-badge)](https://fabricmc.net)
[![Licence](https://img.shields.io/badge/Licence-GPL--3.0--or--later-4C8BF5?style=for-the-badge)](LICENSE)

<kbd>B</kbd> opens the terminal · <kbd>G</kbd> charts the item you're pointing at · `/sq` if you prefer typing

</div>

<br>

<div align="center">
<img src="docs/assets/screenshots/2-flip-ranking.png" width="90%" alt="The Flip tab, ranking the bazaar by profit">
</div>

<br>

---

## What it's for

The game already tells you a lot. The bazaar has a chart, an order book and a week of volume; the
auction house shows recent sales. What it doesn't do is put any of it side by side.

Every item is its own menu. Comparing two flips means closing one and opening the other. Ranking
fifty is not on the table. And the figures are gross — the tax comes off when you click, not when
you're deciding.

SkyQuant reads the same public data and lays it out as one screen: sorted, ranked, and net of what
you'll actually be charged.

<br>

<table>
<tr>
<td width="50%" valign="top">

### 📊 Six views, one screen

**Watchlist** — your items, live
**Flip** — the bazaar by margin
**NPC → Bazaar** — buy cheap, sell dear
**Bazaar → NPC** — and the reverse
**Craft** — worth more made than bought?
**Forge** — best use of a slot, per hour

</td>
<td width="50%" valign="top">

### 📈 Charts that answer

**1h · 1d · 7d · 30d** on both markets
**Buy and sell curves** with the spread
**Lowest BIN** — what one costs *now*
**A verdict** — dear, cheap, or ordinary

</td>
</tr>
</table>

<br>

> ### Every figure is net of tax
>
> SkyQuant reads your real Bazaar Flipper rate from the Community Shop menu, so what you see is
> what you keep.
>
> It matters more than it sounds: on a 500k flip, the tax is 6,250 coins. A profit column that
> ignores it is quietly wrong on every single row.

<br>

---

## Three things it does differently

<table>
<tr>
<td width="33%" valign="top">

#### 🎯 Real prices, not phantom ones

One item showed a sell price of **22.7**. The cheapest seller actually wanted **7,002**.

The gap was a single abandoned order — enough to make it look like the best flip on the bazaar,
ten times over.

SkyQuant prices from the top of the order book, so that row never appears.

</td>
<td width="33%" valign="top">

#### ⏱ Forge ranked by the hour

Tungsten Key: **259k in thirty seconds**.
Gleaming Crystal: **11.65M in six hours**.

By profit, the crystal wins 45×. By the hour, the key wins 16×.

A forge slot is time. Ranking it any other way recommends the wrong craft.

</td>
<td width="33%" valign="top">

#### 🔍 Thin markets, flagged

A cheapest listing far below the next one isn't a price. It's one person underselling.

Across twelve items, nine sat within 1% of the second cheapest. Daedalus Axe sat at **28.6%**.

SkyQuant shows the gap instead of hiding it — the price is real, it just isn't the going rate.

</td>
</tr>
</table>

<br>

---

## See it

<div align="center">

<img src="docs/assets/screenshots/3-bazaar-chart.png" width="90%" alt="A bazaar price chart with buy and sell curves">

*A bazaar chart: buy and sell curves, the spread between them, volume below.*

<br>

<img src="docs/assets/screenshots/6-forge-per-hour.png" width="90%" alt="The Forge tab ranked by profit per hour">

*The Forge, ranked on what a slot actually earns you per hour.*

<br>

<img src="docs/assets/screenshots/7-npc-to-bazaar.png" width="90%" alt="The NPC to Bazaar tab with each shop's daily stock">

*NPC → Bazaar, with each shop's remaining daily stock — the figure that decides whether a margin is worth the trip.*

</div>

<details>
<summary><b>More screenshots</b></summary>
<br>
<div align="center">

<img src="docs/assets/screenshots/1-watchlist.png" width="85%" alt="Watchlist">

*Watchlist — your tracked items, with the spread on each.*

<img src="docs/assets/screenshots/4-auction-chart.png" width="85%" alt="Auction chart">

*Auction chart — the dashed line is what one costs to buy right now.*

<img src="docs/assets/screenshots/5-craft-profit.png" width="85%" alt="Craft profit">

*Craft — losses shown as readily as gains.*

<img src="docs/assets/screenshots/8-bazaar-to-npc.png" width="85%" alt="Bazaar to NPC">

*Bazaar → NPC, with both the instant price and what a buy order would fill at.*

</div>
</details>

<br>

---

## Install

| | |
|---|---|
| **Minecraft** | 26.1.2 |
| **Fabric Loader** | 0.17+ |
| **Fabric Language Kotlin** | required |
| [**Mod Menu**](https://modrinth.com/mod/modmenu) | optional — adds a Config button. Without it, `/skyquant` opens the same screen |

**[⬇ Download from Modrinth](https://modrinth.com/mod/skyquant)**

No Hypixel API key is needed, and the mod never asks you for one.

<br>

---

## What it does — and doesn't

**Does**
- Fetches public market data from public APIs and draws it
- Reads the tooltips of a menu *you already have open*, for two figures no API carries: your bazaar
  tax rate and an NPC shop's remaining stock

**Doesn't**
- ❌ Automate anything — no macros, no solvers, no auto-buying, no clicking or moving for you
- ❌ Touch packets — client-side only, every request is a plain HTTP `GET` to a public API
- ❌ **Send anything about you** — no account name, no UUID, no profile data leaves your client.
  Requests carry item ids and nothing else.

Every figure it shows is something anyone can look up in a browser at
[sky.coflnet.com](https://sky.coflnet.com/data) or Hypixel's own public API. It isn't an advantage
that depends on having the mod — it just saves you the alt-tab.

Hypixel doesn't approve or certify individual mods — its
[allowed-modifications page](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
lists categories rather than naming mods, and using any mod is at your own risk. The two things it
rules out in absolute terms are **automation** and **anything that alters how your client talks to
the server**. SkyQuant does neither.

<br>

---

<br>

# Technical

<br>

## Stack

| | |
|---|---|
| **Language** | Kotlin |
| **Loader** | Fabric |
| **Multi-version** | [Stonecutter](https://stonecutter.kikugie.dev) |
| **Config UI** | MoulConfig, shaded and relocated |
| **Mixins** | **none** — Fabric API events plus a four-line read-only access widener |
| **Tests** | 310, no game required |

Roughly 11,000 lines of Kotlin, and `./gradlew test` covers them in a few seconds. Anything that
doesn't touch a Minecraft class can be tested without launching the game — which is the reason the
arithmetic lives apart from the drawing.

<br>

## Layout

```
src/main/kotlin/dev/syqs/skyquant/
├── feature/bazaar/
│   ├── data/          fetching, caching, and the maths that decides what a row says
│   └── gui/           the terminal, the charts, the HUD overlay
├── config/            MoulConfig wiring, migrations, legacy file rename
├── hud/               draggable overlay registry and editor
└── util/              HTTP, JSON on disk, GitHub archive streaming, rate limiting
```

Full map in **[PROJECT_MAP.md](PROJECT_MAP.md)**.

<br>

## Data sources

| Source | What it gives | Cached |
|---|---|---|
| [Hypixel API](https://api.hypixel.net) | live bazaar, item catalogue | 60s / 24h on disk |
| [SkyCofl](https://sky.coflnet.com/data) | price history, auction listings, lowest BIN | per chart view |
| [NEU repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) | recipes, NPC shop prices | disk + ETag |

**Nothing is baked into the jar.** Everything is fetched at runtime, so when Hypixel patches a shop
price you get it without waiting for a mod update. Large or slow-moving sources are cached on disk
and checked with an ETag first — asking "has this changed?" costs a few hundred bytes instead of
megabytes.

*Prices provided by SkyCofl.* Crediting SkyCofl and the NEU repository is a **condition of their
terms**, not a courtesy. The lines come from `DataCredits` and appear in-game — don't remove them.

Full breakdown of what each source requires in **[docs/API_RESOURCES.md](docs/API_RESOURCES.md)**.

<br>

## Rate limiting

Coflnet allows **30 requests per 10 seconds and 100 per minute**, per IP, both limits at once.
Break them 500 times and the IP is **blocked automatically** — so the cost of getting this wrong
isn't a failed chart, it's a player losing access entirely.

`CoflnetRateLimit` counts requests **over time**, shared by every caller. Capping concurrent
requests instead looks like pacing but isn't: four at a time, each taking 100ms, is forty requests
in ten seconds — over the limit.

A `429` is treated as its own kind of failure. It says the *IP* was refused, not that the item has
no data, and conflating the two would cache "nothing listed" against a perfectly tradeable item
for the whole backoff period.

<br>

## Build

```bash
./gradlew buildAndCollect    # jar lands in build/libs/<version>/
./gradlew test               # 434 tests
./gradlew runClient          # test client
```

`runClient` needs a real Microsoft account to reach Hypixel, which
[DevAuth Neo](https://modrinth.com/mod/dev-auth-neo) in `run/mods` handles — it opens a login
browser on first launch and remembers you afterwards. Java 25 is required from Minecraft 26.1
onward; the Gradle toolchain will fetch it if you don't have it.

Only the eight Fabric API modules the mod actually uses are on the compile classpath. The full
package comes in as `modRuntimeOnly`, for the test client alone: development mods dropped in
`run/mods` often demand the `fabric-api` umbrella and refuse to load without it.

> **Pin Fabric API to the exact patch** — `+26.1.2`, never a bare `+26.1`. Mojang changes internal
> signatures between patches of the same release, and a generic build breaks Fabric API's mixins at
> runtime. Learned the hard way.

<br>

## Licence

**GPL-3.0-or-later.** Bundles [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig)
(LGPL-3.0), which itself includes LibNinePatch (MPL-2.0).

Both licences ship inside the jar, named `LICENSE-SkyQuant.txt` and `LICENSE-MoulConfig.txt` so
each says whose it is. A bare `LICENSE` at a jar's root reads as the *mod's* licence — which, when
it's a dependency's, states the wrong terms to anyone who opens it.

<br>

---

<div align="center">

**[Modrinth](https://modrinth.com/mod/skyquant)** · **[Report a bug](https://github.com/Syqs19/SkyQuant/issues)** · **[API notes](docs/API_RESOURCES.md)** · **[Project map](PROJECT_MAP.md)**

<sub>NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.<br>
Not affiliated with or endorsed by Hypixel.</sub>

</div>
