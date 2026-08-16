# API Resources

What every data source the mod can reach actually returns, which parts are in use, and which
are not. Written by querying the live endpoints rather than reading their documentation -
figures below are measured, and dated so a later reader knows when they were true.

**Last verified: 15 August 2026.**

Related: [RESEARCH.md](RESEARCH.md) section 6 covers *why* these sources were chosen;
this file covers *what they contain*. Prices quoted as examples were true at that moment and
move constantly - they show orders of magnitude, not values to rely on.

---

## The rule this file exists to protect

**No one should have to update the mod to get fresh data - not the player, not us.**

Every source here is fetched at runtime. Nothing is frozen into the jar. Where a source is
large or slow-moving, the mod caches it on disk and asks "has this changed?" before
downloading it again, which costs a few hundred bytes rather than megabytes.

Concretely that rules out one shortcut that keeps looking attractive: baking a generated index
of NPC prices into the mod's resources. It is 24 KB and would start instantly, but it goes
stale the day Hypixel patches a shop price, and only a mod release could fix it. Not acceptable
under the rule above.

---

## 1. Hypixel Bazaar

`https://api.hypixel.net/v2/skyblock/bazaar` - **no API key**

Every bazaar product in one call. Hypixel caches it for 60s (`Cache-Control: max-age=60`), so
polling faster returns identical bytes.

| | |
|---|---|
| Products | 2124 |
| Refresh | 60s server-side |
| Size | ~1.5 MB |

Per product: `quick_status` (summary) plus the full order book.

| Field | Meaning |
|---|---|
| `buyPrice` | what you pay to buy **instantly** |
| `sellPrice` | what you receive selling **instantly**; also what a *buy order* fills at |
| `buyVolume` / `sellVolume` | units currently sitting in orders on each side |
| `buyMovingWeek` / `sellMovingWeek` | units traded over the past week |
| `buyOrders` / `sellOrders` | how many distinct orders make up each side |
| `buy_summary` | **30 price levels** deep, each with amount and order count |
| `sell_summary` | **25 price levels** deep, same shape |

**In use**: `quick_status` throughout, plus the **top of each order book** (`buy_summary[0]` and
`sell_summary[0]`) for the Flip ranking and the depth column.

**Why the book and not the summary.** The summary prices can sit a long way from anything
tradeable. Measured live, `SHARD_DRYBARK` reported `sellPrice: 22.7` while the cheapest seller
in the book was asking **7002** - an abandoned lowball order that made it the best-looking flip
on the bazaar by a factor of ten. Pricing from the top of book removes that whole class of
phantom row without needing a spread ceiling to paper over it.

**Not in use**: levels 2-30 of each book. They would answer "how much can I buy before I move
the price myself" more precisely than the top level alone; today the depth column reports the
thinner of the two top levels, which is enough to spot a margin that only exists for eight
units.

Note the two names are counter-intuitive. `sellPrice` is the *lower* of the two: it is what
buyers are bidding, so it is both what you get for selling instantly and what your buy order
will eventually fill at. Getting this backwards silently inverts every profit figure.

---

## 2. Hypixel item catalogue

`https://api.hypixel.net/v2/resources/skyblock/items` - **no API key**

Every item in the game with its static properties. Slow-moving; fetched once per session.

| | |
|---|---|
| Items | 5646 |
| Size | 4.9 MB |
| Fields per item | ~45 |

Most-populated fields, with how many items carry each:

| Field | Count | What it is |
|---|---|---|
| `material`, `name`, `id` | 5646 | present on everything |
| `tier` | 4775 | rarity |
| `category` | 2569 | weapon, armor, … |
| **`npc_sell_price`** | **2434** | **what an NPC pays you** |
| `stats` | 1376 | combat/gear statistics |
| `museum_data` | 1060 | museum eligibility |
| `requirements` | 1003 | skill/level gates |
| `upgrade_costs` | 545 | essence costs to upgrade |
| `gemstone_slots` | 499 | sockets |
| `motes_sell_price` | 73 | Rift currency price |

**In use**: `id` and `npc_sell_price`, through `NpcSellPrices`. Only these two are kept -
holding all 45 fields would cost memory for data nothing reads, and keeping just the pair is what
makes the disk cache tens of KB instead of 4.9 MB.

**Not in use**: everything else. `tier` and `category` could label or filter rows; `stats` and
`requirements` belong to a gear feature that does not exist.

**Trap**: `npc_sell_price` of exactly `1` is a placeholder for "not really sellable" rather
than a real one-coin price, and it appears on furniture and quest items. 96 bazaar products
carry it. `NpcSellPrices` drops these on load so no caller has to know.

**What it does not have**: the price an NPC *charges you*. That is only in the NEU repo, below.

---

## 3. NEU data repository

`https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO` - **no API key**

Community-maintained database of items, recipes and shops. The same source SkyHanni and
Firmament use. One JSON file per item.

| | |
|---|---|
| Item files | 8745 |
| Download | 9.2 MB gzipped, ~2.5s |
| Updated | most days - usually pets and skins, rarely NPC prices |
| Useful subset | 24 KB |

Recipes live under either `recipe` (singular, old grid format) or `recipes` (an array of typed
objects). **Both must be parsed** - `DIAMOND` uses the first, `ENCHANTED_DIAMOND` the second,
and reading only one loses roughly half the items.

Recipe types across the repo:

| Type | Count | Feeds |
|---|---|---|
| `crafting` | 2544 | the Crafting page |
| `npc_shop` | 1093 | the NPC → Bazaar page |
| `drops` | 429 | mob drops - not a bazaar concern |
| `katgrade` | 217 | pet upgrades |
| `forge` | 120 | the Forge page |
| `trade` | 78 | item-for-item NPC trades |

An `npc_shop` entry is stored on the **NPC's** file, not the item's:

```json
{ "type": "npc_shop", "cost": ["SKYBLOCK_COIN:8.0"], "result": "ROTTEN_FLESH:1" }
```

Of the 1093, only **524 are priced in coins**; the other 569 want coupons or tokens, which are
not a profit measurable in coins and must be excluded. After dropping those and de-duplicating
items sold by several NPCs (Wheat and Coal each appear twice - the lowest price wins),
**474 items** remain.

`forge` recipes carry `duration` in seconds (Refined Mithril: 21600, i.e. 6 hours). Without it
the Forge page would be meaningless, since 10M profit over six hours and 10M over five minutes
are not the same opportunity. The figure to rank on is **profit per hour**.

**In use**: nothing yet.

**Not in use**: all of it. This is the next piece of work.

`constants/` also holds ~40 prepared files (sacks, bonuses, gemstones, essence costs, garden,
bestiary) that no current feature needs.

---

## 4. Coflnet

`https://sky.coflnet.com/api/bazaar/{productId}/history/{hour|day|week}` - **no API key**

Price history, which Hypixel does not offer at all - its API only ever returns the current
book. One product per request.

Each point: `buy`, `sell`, `buyVolume`, `sellVolume`, `buyMovingWeek`, `sellMovingWeek`,
`timestamp`. The hour window returns ~179 points. Cached 300s.

**In use**: all of it, through `BazaarHistory` - this is what the chart screen draws.

**Trap already hit**: timestamps parse as null unless handled explicitly; there is a test
guarding this.

### Auction history - the same host, a different endpoint

`https://sky.coflnet.com/api/item/price/{itemId}/history/{day|week|month}` - **no API key**

Covers everything the bazaar doesn't trade: weapons, armour, drills, forge outputs. Each point
is one hour of *completed sales* - `min`, `max`, `avg`, `volume` - rather than two live sides of
an order book.

| | |
|---|---|
| Verified working on | Hyperion, Divan's Drill, Titanium Drill DR-X655, Beacon V, Superior Dragon Helmet |
| Returns nothing for | items no market trades, e.g. Rookie Pickaxe - correctly |
| Points | 21 for a day, 144 for a week, 31 for a month |

**In use**: through `AuctionHistory`, as the price graph's fallback when the bazaar has no
history for an item, and through `AuctionLivePrices` for pinned HUD rows.

#### Which windows exist, per market

Measured against the live API rather than assumed, because the two markets differ in both
directions and a wrong guess produces a lit button over an empty chart:

| Window | Bazaar | Auction |
|---|---|---|
| `hour` | 200 | **404** |
| `day` | 200 | 200 |
| `week` | 200 | 200 |
| `month` | **404** | 200 (one point per *day*) |
| `full` | 404 | 200, but stale - newest point was days old when measured |

**The item-price endpoint is not auction-only.** Asked for a bazaar product it answers normally:
`ENCHANTED_DIAMOND` returns 1250-1380 against a live quote of 1289-1343. That is what makes the
`30d` button possible on *both* markets despite `/api/bazaar/.../month` returning 404.

The cost is that those points carry `min`/`max`/`avg` instead of `buy`/`sell`, so the month view
draws one curve where the shorter windows draw two, and reports no spread. That suits the window:
a spread is a decision about right now, and nobody reads thirty days to decide whether to place an
order this minute. It is also why `PriceSeries.Kind` has a third case, `BAZAAR_DAILY` - a fungible
market whose data has no sides. Filing it under `AUCTION` would apply the base-price line to goods
that have no variants; filing it under `BAZAAR` would have the chart look for two curves that
aren't there.

Point order is **not dependable on either endpoint** and varies by window - `/api/bazaar/.../day`
came back newest-first while `/api/item/price/.../day` came back oldest-first, and the item-price
`month` window was newest-first. Both parsers sort by timestamp, which is what makes this a
footnote rather than a bug; without it a chart would draw time running backwards.

`full` reaches back to 2021 but is deliberately not offered - its newest point was days old when
measured, so it would draw a chart that looks current and isn't.

Three differences from the bazaar endpoint that each cost a mistake:

- **The timestamp field is called `time`, not `timestamp`.** Reusing the bazaar's response class
  parses every point as having no time, and a series with no timestamps draws as nothing.
- **There is no `hour` window** - it answers 404. This was previously served the day's data
  instead, which left the `1h` button lit above a chart showing a different window entirely.
  The button is now greyed out with a hover reason.
- **The last point is not a summary of the window.** An hour in which one item sold reports that
  sale as its `min`, `max` and `avg` alike. Reading it as "the current price range" put three
  identical figures on the side panel, which reads as a broken display rather than a thin market.
  `PriceSeries.summarize()` covers the whole window instead, with the average weighted by volume.

#### Auction items are not fungible, and the chart has to account for it

A bazaar product is one thing with one price. An auction item is a family: a Titanium Drill with
good reforges and enchantments sells for far more than a bare one, under the same name. So the
raw min/max/avg answer a question nobody asked - "what did *any* example go for" - when what a
player wants is what an unmodified one costs.

Three corrections, each calibrated against the live API rather than chosen by feel:

| Correction | Rule | Why that rule |
|---|---|---|
| Base price line | cheapest sale per hour, floored at 50% of the window's median | Across 5 items and 619 traded hours, the floor removes exactly two absurdities (a 1.0M drill against a 336M median, a 0.04M Daedalus Axe against 12.8M) and touches nothing else |
| Band top | the hour's average, not its maximum | Over a week of Titanium Drill the maximum varies 26.5% against the minimum's 19.2% - the top of the range is mostly a record of how well-equipped the luckiest seller was |
| Axis scale | ignores hours with a single sale | 4 of 22 Titanium Drill hours had one sale; excluding them from the scale takes the axis from 301M tall to 134M. Those hours are still drawn, just clipped |

A fixed ceiling on how far a price may sit above the median was tried first and **rejected on the
evidence**: at 1.5x it clipped 21 of Hyperion's 23 hours, where the spread is genuine and worth
seeing. Thin hours mislead; wide ones are information. The volume test separates them; a price
threshold cannot.

Those three corrections still only approximate the live price, because history cannot answer
"what does one cost right now". That question has its own endpoint - see below.

**A trap inside the outlier floor.** The median it derives from must come from hours that
actually traded. Computed over every hour, the standing prices Coflnet reports for quiet hours -
which can sit far above anything paid - pulled the median up, and with it the floor, until
genuine cheap hours fell below it and were "corrected" to the median. The guard against outliers
was manufacturing them. It only surfaced because a test asserted a number worked out by hand.

#### Four prices are not an answer

The measured problem, once all of the above worked: the auction screen showed a cheapest listing,
a window average, a top and a low, and none of them said whether to buy. A bazaar product arrives
with its answer built in - two prices, and the gap is the margin - so its chart only has to say
whether now is a good moment. The auction screen had more figures and less opinion.

One comparison restores it: the live price against `PriceSeries.Summary.usual`, the **median** of
the window's base prices (median, not minimum - the cheapest hour of a window drifts lower the
longer the window, so the same item would look dearer on 7d than on 1d without anything having
changed). Sampled across eleven items the differences fell at -18.0, -17.4, -16.3, -7.3, -1.1,
-0.4, 0.0, +1.1, +2.8, +9.1 and +90.0 percent - a clean gap between -7.3 and -16.3 with nothing
in it, so `PriceVerdict` calls anything past ±10% notable and everything else ordinary.

### Lowest BIN - what it costs to buy one now

`https://sky.coflnet.com/api/item/price/{itemId}/bin` - **no API key**

```json
{"lowest":333999998, "secondLowest":333999999}
```

**87 bytes.** Also `.../active/overview`, which lists every active auction for the item sorted by
price, with seller and end time.

**In use**: through `AuctionBin`, for the graph screen's "Buy now" row and its dashed reference
line. Refreshed every 60s, matching how fast the book turns over.

**Why not Hypixel's own auction endpoint** - measured, then rejected:

| | |
|---|---|
| Size | 48 pages, 47,293 auctions, ~57MB gzipped / 105MB parsed |
| Wasted payload | 54% is `item_bytes`, never read; 13% is `item_lore` |
| Caching | `max-age=60`, **no ETag** - every check costs the full download |
| Filterable | No. Sampling 8 pages found the same item on pages 10 and 47 - wanted items are spread across all of them |

Coflnet computes the same answer server-side and serves it in 87 bytes. The only thing Hypixel's
endpoint offers that this doesn't is per-auction NBT, which nothing here needs.

**`secondLowest` is what makes the figure usable.** A cheapest listing well below the next one is
one underpriced item, not a market. Sampled live across twelve items:

| Gap to 2nd cheapest | Items |
|---|---|
| under 1% | Hyperion, Necron's Handle, Titanium Drill, Juju Shortbow, Terminator, Shadow Fury, Midas Sword, Aspect of the Dragon |
| 6% | Divan's Drill |
| 11.2% | Livid Dagger (widest ordinary case) |
| **28.6%** | **Daedalus Axe** (the one genuine outlier) |

A 15% threshold separates those cleanly - it is the only value that flags Daedalus Axe while
leaving Livid Dagger alone. Flagged prices are shown with a ⚠ alongside the second price, not
hidden: the price is real, it just isn't a going rate.

Two shapes to handle: Coflnet answers **200 with `lowest: 0`** rather than 404 when nothing is
listed (taken at face value that puts a price of zero coins on screen), and sends no
`secondLowest` when an item has exactly one listing.

The two are unified by `PriceSeries` before reaching the chart, which keeps `if (auction)` out
of the drawing code. What the lines *mean* still differs, so the series carries its kind and the
chart labels accordingly: "Buy/Sell/Gap" on the bazaar, "High/Low/Range" at auction. An auction
series also draws a faint band between the extremes, because an hour of sales is a scatter -
three items might go for 480M, 900M and 1.2B - and a single average line would claim a precision
the data doesn't have.

---

## 5. Hypixel auctions

`https://api.hypixel.net/v2/skyblock/auctions` - **no API key**

| | |
|---|---|
| Auctions | 47,293 |
| Pages | 48 (1000 per page) |
| Buy-it-now share | 89% on the sampled page |
| Size | ~57MB gzipped, ~105MB parsed |
| Cache headers | `max-age=60`, **no ETag** |

Per auction: `item_name`, `item_lore`, `category`, `tier`, `starting_bid`,
`highest_bid_amount`, `bin`, `start`, `end`, `bids`, plus `item_bytes` (the item's NBT, base64).

**In use**: nothing - and that is a decision taken on measurements, not an omission.

The mod does need live auction prices, and gets them from Coflnet's `/bin` endpoint instead (see
section 4). This endpoint could answer the same question, and was measured before being ruled
out:

- **Size.** 48 pages at ~1.2MB gzipped each. 54% of the payload is `item_bytes` that nothing
  here reads, and another 13% is `item_lore`.
- **No ETag.** With only `max-age=60` there is no cheap "has anything changed" request; every
  refresh costs the whole download again.
- **Not filterable.** Sampling 8 of the 48 pages found Necron's Handle on pages 10 and 40, and
  Titanium Drill on 10 and 47. Wanted items are scattered across every page, so there is no
  partial fetch that would work.

Coflnet computes the lowest BIN server-side and serves it in **87 bytes**. The only thing this
endpoint offers that Coflnet's does not is per-auction NBT, which no current feature needs. If
one ever does - filtering by a specific enchantment, say - this is where it would have to come
from, and the download budget above is what it would cost.

---

## 6. Player profile - the one that is gated

`https://api.hypixel.net/v2/skyblock/profiles?uuid=…` - **requires an API key**

Returns `{"success":false,"cause":"Missing API-Key header"}` without one.

Holds `community_upgrades`, and therefore the player's **Bazaar Flipper** level - the exact
bazaar tax they pay.

**In use**: nothing, and deliberately so. A personal key means visiting developer.hypixel.net,
generating one and pasting it into the config - a real barrier for a figure worth at most
0.25%. The mod reads the perk level from the Community Center menu in-game instead, with a
manual setting as an override.

---

## Bazaar tax

**1.25% on every bazaar sale**, reduced by the Bazaar Flipper community upgrade to as low as
**1%**. It does *not* apply when selling to an NPC.

It is small but not ignorable: on a 500k flip it is 6250 coins, and a terminal that shows gross
profit is lying. Every profit figure the mod displays is net of it.

The rate is read from the Community Shop menu, whose Bazaar Flipper entry states it outright:

```
Bazaar Flipper II
Account Upgrade

Manage more orders at the same time
and reduce the Bazaar tax.

Your Limit: 14 + 14 orders
Your Tax Rate: 1%

Each tier: +7 orders & -0.125% tax

Maxed out!
```

Taking the stated rate beats deriving one from the tier: it is the number the server will
charge, and it survives Hypixel re-balancing what a tier is worth. Note the lore also contains
`-0.125%`, which parses as a rate if the "Your Tax Rate" label isn't required - there is a test
for that.

Two traps in reading any Hypixel menu, both of which cost a debugging round here:

- **`stack.get(DataComponents.LORE)?.lines()`, with the parentheses.** Without them the
  expression compiles and returns the record's raw component, yielding no text at all. The
  detector shipped broken this way while every test passed, because the tests called the
  parser directly and never went through the item.
- **Menu text carries `§` colour codes inline.** `§dBazaar Flipper §fII` reads as `§fii` and
  matches nothing. Strip formatting before parsing.

Source: [Hypixel SkyBlock Wiki - Bazaar](https://hypixel-skyblock.fandom.com/wiki/Bazaar).

---

## NPC shop stock - read from the menu, not from an API

Shops sell **640 units of an item per day** (ten stacks), resetting at 00:00 GMT. Mayor Diaz's
*Shopping Spree* raises this **tenfold, to 6400**.

No API carries it, and the NEU repo's `npc_shop` entries hold only a price and a result. But the
shop tooltip states it:

```
Cost
12 Coins

Stock
640 remaining
```

So the mod reads it whenever a shop is open and remembers it per item - better than the default
twice over, since it is that item's real figure and it is what is *left today*. The default
covers everything not yet looked at, and no player needs to visit any shop for the figures to be
broadly right.

**Stock is per shop *and* per item.** Tested in-game: buying out the Mine Merchant's iron leaves
the Iron Forger's iron untouched. The wiki's claim that merchants share a pool did not survive
that test. 42 of the 474 buyable items have more than one seller, and for those a day's buying
is worth the sum of their stocks - so the total multiplies by the seller count.

**The number counts units, not purchases.** Shops sell in stacks (`GOLD_INGOT:2`,
`TORCH:16`, `IRON_INGOT:4` at the Mine Merchant) and each purchase draws its full stack off the
640. Also confirmed in-game. A per-unit profit therefore multiplies by the stock figure
directly; reading it as a purchase count would overstate torches by a factor of sixteen.

The total column is headed with the number in use (`Tot 640`, `Tot 6.4k`) rather than just
`Total`, so the multiplier behind the figure is always visible. Diaz is a setting:
`resources/skyblock/election` does report the sitting mayor, but knowing that still would not
give the per-item limit.

Two parsing notes, both of which the tests caught first: the label and the figure sit on
**separate lore lines**, so a parser requiring both words on one line finds nothing; and the
tooltip's cost line comes *before* the stock line, so anything scanning for the first digits
prices the row off "12 Coins".

Sources: [Shops - Hypixel SkyBlock Wiki](https://hypixelskyblock.minecraft.wiki/w/Shops),
[Diaz - Hypixel SkyBlock Wiki](https://hypixelskyblock.minecraft.wiki/w/Diaz), and in-game
testing.

---

## Keeping it fresh without a mod update

GitHub supports conditional requests, which is what makes the rule at the top affordable:

```
ETag: W/"a6a05a33…"          returned with every response
If-None-Match: W/"a6a05a33…" sent on the next check
-> HTTP 304 Not Modified      no body transferred
```

So "has the repo changed?" costs a few hundred bytes. The 9.2 MB download happens only when the
answer is yes. Unauthenticated GitHub allows **60 requests per hour per IP**, which a check
every few hours does not come close to.

Summary of what each source needs:

| Source | Changes | Strategy |
|---|---|---|
| Bazaar | every 60s | fetch on demand, 60s throttle (matches server cache) |
| Item catalogue | rarely | **disk cache, re-fetched after 24h** |
| NEU repo | daily, rarely the parts we read | disk cache + ETag check, re-download only on 304-miss |
| Coflnet | continuously | fetch per chart view |

**The catalogue's cache is an interval, not an etag, and that is forced.** Hypixel's resource
endpoint sends no `ETag`, so unlike the NEU repo there is no few-hundred-byte way to ask whether
anything changed - checking costs the full 4.9 MB. A day is far shorter than the weeks between the
game patches that actually move these prices, and it means a player who plays daily downloads this
once. `NpcSellPrices` keeps only the ~2400 priced ids, so what lands on disk is tens of KB rather
than the 4.9 MB it was parsed from.

**Everything above is warmed on joining a world**, not when the terminal opens - see
`MarketDataPreload`. Starting on the terminal's first frame meant the player opened it and *then*
waited through 1.5 MB of prices and, on a cold cache, 4.9 MB of catalogue. The preload touches only
Hypixel and GitHub, so it never spends the Coflnet budget a chart is about to need.

---

## Rate limits

| Source | Limit | Headroom |
|---|---|---|
| Hypixel (keyless) | no published per-IP limit; `max-age=60` | one shared 60s snapshot serves every screen |
| GitHub (unauthenticated) | 60 requests/hour/IP | a check every few hours is negligible |
| Coflnet | **30 req/10s and 100 req/min per IP, both at once** | see below - the 10s window is the binding one |

**Coflnet's limits are published, and tighter than they look.** Verified 16 August 2026 against
[their API terms](https://sky.coflnet.com/wiki/api): the two windows apply *concurrently*, so a
burst of 31 requests inside ten seconds is rejected even while the per-minute count is nowhere
near 100. Exceeding them returns **429** with a `Retry-After` header, and an IP that accumulates
**500 rate-limit violations is blocked automatically**.

That last figure is why the mod paces itself rather than relying on the server to say no: the
cost of getting this wrong is not a failed request, it is a player's IP being unable to reach
Coflnet at all.

The binding constraint is the ten-second window, and a cap on *concurrent* requests does not
enforce it - four in flight completing in 100ms each is forty requests in ten seconds, which is
over. `CoflnetRateLimit` therefore paces by time, and `HttpJson` surfaces 429 as a distinct
failure so a rate-limited item is not cached as "nothing listed".

The bazaar snapshot is shared through `BazaarLivePrices` precisely for this: an overlay showing
twenty pinned items costs one request, not twenty.

---

## Terms each source imposes

Rate limits are the engineering constraint; these are the legal ones. Verified 16 August 2026.
They are conditions of use, not courtesies - `DataCredits` exists to satisfy the first two.

| Source | Condition | How it is met |
|---|---|---|
| Coflnet | attribution wherever their data is shown | credit line in the terminal title bar and the graph footer |
| NEU repo | MIT - notice travels with redistribution | credited in `DataCredits`, `fabric.mod.json` and the README |
| Hypixel | must be clear the mod is not affiliated or endorsed | stated in `fabric.mod.json` and the Modrinth description |
| MoulConfig | LGPL-3.0, bundled into the jar | compatible with our GPL-3.0; disclosed in `fabric.mod.json` |
| LibNinePatch | MPL-2.0, arrives inside MoulConfig | notice kept as `LICENSE-MoulConfig.txt` |

**Licence files are renamed on the way into the jar, and it matters.** MoulConfig ships a bare
`LICENSE` (MPL-2.0, LibNinePatch's), which shadow copied to the root of our jar - where that
filename reads as *SkyQuant's* licence and flatly contradicts the GPL-3.0-or-later in
`fabric.mod.json`. Worse, our own GPL text wasn't in the jar at all, because the name was already
taken. Both are now carried under names that say whose they are, which is what GPL-3.0 and
MPL-2.0 each ask for. Deleting the dependency's notice would have been simpler and wrong: its
code is still in the jar.

---

## How the established mods handle the same questions

Checked 16 August 2026 against Skyblocker, SkyHanni, Firmament and NEU, because "what does
everyone else do" is the only available benchmark where the written rules are ambiguous.

| | SkyQuant | Skyblocker | SkyHanni | Firmament | NEU |
|---|---|---|---|---|---|
| Licence | GPL-3.0 | LGPL-3.0 | LGPL-2.1 | GPL-3.0 | LGPL-3.0 |
| Mojang disclaimer | yes | yes | yes | yes | yes |
| Ban / rules warning | **a line of context** | none | none | none | none |
| Sends player data | **no** | waypoints over WebSocket | uuid + profile uuid to a third party | - | - |
| Asks for an API key | **no** | no | no | no | no |

Three things fall out of it.

**Nobody warns about bans, and neither should this.** All four carry the Mojang non-affiliation
notice and nothing else. SkyQuant's README adds one paragraph of context - Hypixel certifies no
mod, so this is at your own risk like all of them - which is a little more than the field does and
stops short of implying an approval that does not exist.

**Sending nothing is the strictest position here, not the normal one.** SkyHanni documents sending
your player uuid and SkyBlock profile uuid to `api.eliteskyblock.com`, and Skyblocker shares
waypoints between players over a WebSocket. Both are disclosed and neither is improper. SkyQuant
makes only GETs carrying item ids, which is why the README can state plainly that nothing about
the player leaves the client - a claim worth making precisely because it is not the default.

**Attribution is where the field is weakest, and it is a licence condition.** SkyHanni's Modrinth
page credits no data source at all. That is not a model to copy: Coflnet's terms require the
credit wherever their data is shown, so `DataCredits` stays where it is.

Three further points worth keeping in view:

- **No API key is ever handled.** Hypixel's policy forbids entering keys into a mod
  ("Do not share your API key with 3rd parties, such as entering them into a website or a mod"),
  so the decision in section 6 not to ask for one is a matter of policy as much as convenience.
  Anything that would later require a key needs this paragraph re-read first.
- **Coflnet asks commercial products to fund them** via a Premium+ subscription. The mod is free,
  so this does not bite today - but enabling Modrinth's revenue sharing would put it in scope.
- **`/v2/skyblock/bazaar` is not on Hypixel's published keyless list**, which names
  `/v2/skyblock/news` but not the bazaar. It answers without a key today (verified), so this is a
  risk to the mod's data supply rather than a compliance problem - nothing is being circumvented.

Auction prices cannot be shared that way - there is no all-items endpoint, so `AuctionLivePrices`
is a per-item cache and each pinned auction item costs its own request. Three things keep that
affordable: only pinned items are ever fetched, the refresh interval is 10 minutes rather than
the bazaar's 60 seconds (the underlying figure is hourly, so asking more often returns the same
number), and an item that turns out to have no auction history at all is remembered for 30
minutes so it isn't re-requested forever.
