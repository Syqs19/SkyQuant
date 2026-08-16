<div align="center">

# SkyQuant

### A market terminal for Hypixel SkyBlock

**Know what something is worth before you buy, craft or forge it.**

`B` opens the terminal · `G` charts the item you're pointing at · `/sq` if you prefer typing

</div>

![The Flip tab, ranking the bazaar by profit](https://raw.githubusercontent.com/Syqs19/SkyQuant/main/docs/assets/screenshots/2-flip-ranking.png)

---

## 📊 The terminal

The game shows you one item at a time, in its own menu. This shows fifty at once, sorted.

| Tab | What it answers |
|---|---|
| **Watchlist** | What are my items doing right now? |
| **Flip** | Where's the widest margin on the bazaar? |
| **NPC → Bazaar** | What can I buy from a shop and sell for more? |
| **Craft** | Is this worth more made than bought? |
| **Forge** | What's the best use of a forge slot for the next 6 hours? |

> **Every profit figure is net of bazaar tax.** The mod reads your real Bazaar Flipper rate from
> the Community Shop menu, so the numbers match what you'll actually be charged — not a gross
> figure that quietly overstates every trade.

**Flip prices from the top of the order book, not the summary.** Measured live, one item reported
a sell price of 22.7 while the cheapest actual seller wanted 7,002 — a single abandoned order that
made it look like the best flip on the bazaar by a factor of ten. Pricing from the book removes
that whole class of phantom row.

![The Craft tab, with profits shown net of tax](https://raw.githubusercontent.com/Syqs19/SkyQuant/main/docs/assets/screenshots/5-craft-profit.png)

*The Craft tab. Losses are shown as readily as gains — a page that only ever showed profits would
be hiding half of what you need to decide.*

![The Forge tab, ranked by profit per hour](https://raw.githubusercontent.com/Syqs19/SkyQuant/main/docs/assets/screenshots/6-forge-per-hour.png)

**The Forge ranks on profit per hour, not profit.** Measured: Tungsten Key makes 259k in thirty
seconds, Gleaming Crystal 11.65M in six hours. Ranked on profit the crystal wins by a factor of
45; ranked on the rate the key wins by 16. Only one of those is the advice worth acting on when a
forge slot is the thing you're spending.

![The NPC to Bazaar tab, with each shop's daily stock](https://raw.githubusercontent.com/Syqs19/SkyQuant/main/docs/assets/screenshots/7-npc-to-bazaar.png)

*NPC → Bazaar, with each shop's remaining daily stock — the figure that decides whether a margin
is worth the trip.*

---

## 📈 Price charts

Point at any item in a bazaar or auction menu and press **G**.

![A bazaar price chart with buy and sell curves](https://raw.githubusercontent.com/Syqs19/SkyQuant/main/docs/assets/screenshots/3-bazaar-chart.png)

- **1h · 1d · 7d · 30d** — on both the bazaar *and* the auction house
- **Buy and sell curves** with the spread between them
- **Lowest BIN** for auction items — flagged when the cheapest listing stands well clear of the
  next one, because that's one underpriced item, not a market price
- **A verdict**, not just numbers: the current price against the window's usual, so the chart
  tells you something instead of leaving you to squint at it

![An auction price chart with the lowest-BIN line](https://raw.githubusercontent.com/Syqs19/SkyQuant/main/docs/assets/screenshots/4-auction-chart.png)

Auction items aren't fungible — a well-reforged drill and a bare one sell under the same name — so
the chart draws a base-price line from the cheapest sale per hour rather than pretending one
average means anything. The dashed line is what one costs to buy *right now*.

---

## 🖥 Price HUD

Pin any item and its price stays on screen while you play. Drag it where you want, scale it with
the scroll wheel, pick from four colour themes.

Twenty pinned bazaar items cost **one** request, not twenty — the whole bazaar arrives in a single
call and is shared between every screen.

---

## ✅ What it does — and doesn't

**Does**
- Fetches public market data from public APIs and draws it
- Reads the tooltips of a menu *you already have open*, for two figures no API carries: your
  bazaar tax rate and an NPC shop's remaining stock

**Doesn't**
- ❌ Automate anything — no macros, no solvers, no auto-buying, no clicking or moving for you
- ❌ Touch packets — client-side only, every request is a plain HTTP `GET` to a public API
- ❌ **Send anything about you** — no account name, no UUID, no profile data ever leaves your
  client. Requests carry item ids and nothing else.

Every figure it shows is something anyone can look up in a browser at
[sky.coflnet.com](https://sky.coflnet.com/data) or Hypixel's own public API. It isn't an advantage
that depends on having the mod — it just saves you the alt-tab.

---

## 📦 Requirements

| | |
|---|---|
| **Minecraft** | 26.1.2 |
| **Fabric Loader** | 0.17+ |
| **Fabric Language Kotlin** | required |
| [**Mod Menu**](https://modrinth.com/mod/modmenu) | optional — adds a Config button. Without it, `/skyquant` opens the same screen |

**No Hypixel API key needed**, and the mod never asks you for one.

---

## 🙏 Data sources

- **Live bazaar prices and item data** — the [Hypixel API](https://api.hypixel.net)
- **Price history, auction listings and lowest BIN** — [SkyCofl](https://sky.coflnet.com/data)
- **Recipes and NPC shop prices** — the
  [NotEnoughUpdates repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) (MIT)

*Prices provided by SkyCofl.* Attribution to SkyCofl and the NEU repository is a condition of
their terms, and is shown in-game as well as here.

Bundles [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig) (LGPL-3.0), which includes
LibNinePatch (MPL-2.0). Both licences travel inside the jar.

---

## ⚠️ A note on Hypixel's rules

Hypixel doesn't approve or certify individual mods — its
[allowed-modifications page](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
lists categories rather than naming mods, and using any mod is at your own risk.

The two things it rules out in absolute terms are **automation** and **anything that alters how
your client talks to the server**. SkyQuant does neither.

---

<div align="center">

**[Source on GitHub](https://github.com/Syqs19/SkyQuant)** · GPL-3.0-or-later

Bug reports and suggestions welcome on the
[issue tracker](https://github.com/Syqs19/SkyQuant/issues).

<sub>NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
Not affiliated with or endorsed by Hypixel.</sub>

</div>
