# Plan: Craft and Forge pages

Agreed plan and every measurement behind it. Written down so none of the research has to be
repeated.

> **Status, 18 August 2026: all four steps are built and in the game.** The recipe parser, the
> profit maths, the cached index and both pages exist; see `docs/PRICE_SCREENS.md` for what the
> screens show and why. Step 3 (auction pricing for recipe outputs) lives in `CraftProfit`, which
> falls back to `AuctionSellPrice` when a recipe output is not on the bazaar; step 4 (the
> forge-slot reader) is `ForgeTracker` and became the **Status tab**. This document is now a
> record of the reasoning, not a plan - the measurements below are still the ones the code is
> built on.
>
> Three corrections the build made to this document, all found by walking the whole repo rather
> than sampling it:
> - **A recipe with no `type` field is a crafting recipe**, and 2011 of the 2670 crafting recipes
>   are written that way. Filtering on `type == "crafting"` silently drops 79% of them.
> - **Quantities are not always integers** - 37 entries read like `"ENCHANTED_SUGAR:2500.0"`.
> - The open question below about fractional `count` is **answered: none exist.** Zero of 4487.
>
> One design decision was also changed after the fact: each profit is now paired with the cost
> *its own* trade pays, rather than both sharing the order cost. See the Craft and Forge section
> of `docs/PRICE_SCREENS.md`.

**All figures measured 15 August 2026** against the live bazaar, the live auction house and a
fresh copy of the NEU repository. Market numbers move; the counts and the shapes don't.

---

## What the two pages are

**Craft** - buy the ingredients on the bazaar, craft, sell the result. One level deep only:
the cost is the ingredients' own market price, never "what it would cost to craft *them*".
Decided deliberately - it matches an action you actually take, and the nested version needs
cycle handling and a second number in every row.

**Forge** - same trade, but the recipe occupies a forge slot for a stated time, so the figure
to rank on is **profit per hour**, not profit.

---

## Coverage, measured

| | Recipes in repo | Priceable, bazaar only | + auction outputs | Profitable now |
|---|---|---|---|---|
| Crafting | 2550 | 299 | **578** | ~123 |
| Forge | 120 | 30 | **53** | 30 of 30 |

"Priceable" means every ingredient has a price and so does the output. The rest involve items
no market trades - not a code limitation.

Sample of what the Craft page would show (order-to-order, net of 1% tax, weekly volume ≥ 50k):

| Item | Cost | Profit | Margin |
|---|---|---|---|
| Packed Ice | 0.9 | +58.2 | 6467% |
| Enchanted Wool | 16.0 | +805.6 | 5035% |
| Enchanted Bone | 16.0 | +577.6 | 3610% |
| Leather | 5.6 | +136.4 | 2435% |

Forge, ranked by profit per hour - the ordering is nothing like ranking by profit:

| Item | Cost | Profit | Time | Per hour |
|---|---|---|---|---|
| Tungsten Key | 2.38M | 259k | 30s | 31.1M |
| Skeleton Key | 27.1M | 3.08M | 30m | 6.2M |
| Gleaming Crystal | 65.6M | 11.65M | 6h | 1.94M |
| Pocket Iceberg | 200k | 5.18M | 6h | 863k |

---

## Recipe formats in the NEU repo

**Both `recipe` (singular) and `recipes` (array) must be read** - `DIAMOND` uses the first,
`ENCHANTED_DIAMOND` the second. Parsing only one loses roughly half the items.

Crafting is a 3×3 grid; the same ingredient repeats across cells and the counts add up:

```json
{ "type": "crafting", "A2": "DIAMOND:32", "B1": "DIAMOND:32", "B2": "DIAMOND:32",
  "B3": "DIAMOND:32", "C2": "DIAMOND:32", "count": 1 }
```

Forge is a flat input list with a duration in **seconds**:

```json
{ "type": "forge", "inputs": ["GOLDEN_PLATE:1", "FINE_AMBER_GEM:12"],
  "count": 1, "overrideOutputId": "AMBER_MATERIAL", "duration": 21600 }
```

Other fields seen: `overrideOutputId` (the real output when it differs from the file's own
item), `supercraftable`, `count` (output quantity, sometimes a float).

Forge durations across all 120 recipes: 30s ×22, 30m ×4, 1h ×6, 2h ×2, 3h ×2, 4h ×8, 4.5h ×4,
5h ×1, 6h ×12, 8h ×2, 10h ×3, 12h ×3, 14h ×1, 18h ×8, 20h ×14, 24h ×12, 30h ×2, 36h ×2, 40h ×1,
50h ×1, 72h ×3, 168h ×7. **30 seconds to a week** - which is why a bare profit column would
mislead, and why 30s must not round to "0h" on screen.

---

## Pricing rules

Same as the existing pages, and for the same reasons:

- Cost = each ingredient at the **cheapest standing ask** (`topAsk`), i.e. where a buy order
  fills. Never `quick_status`, which quotes prices nobody is offering.
- Revenue = output at the **best standing bid** (`topBid`), less `BazaarTax.rate`.
- Auction-priced outputs: **median of the 4 cheapest BIN listings**, not the bare minimum.
  If the lowest BIN sits below 60% of that median, flag the row - the minimum is an outlier.
- Rows whose price comes from the auction house carry a marker distinguishing them from
  bazaar-priced rows.

---

## Auction prices for recipe outputs

**Lowest BIN by exact display name works**, and needs no NBT decoding: what comes out of a
forge is the base item, with no reforge in its name. `Beacon V` → 51,000,000 (27 listed),
`Gemstone Gauntlet` → 19,999,998 (25 listed).

Bridge the ids through Hypixel's item catalogue: NEU has `DIVAN_DRILL`, the auction house has
`Divan's Drill`, and `/v2/resources/skyblock/items` maps one to the other. Matching on the id
alone finds nothing - that mistake cost a round of analysis.

**63 of the 78 non-bazaar forge outputs** were priceable this way; the other 15 simply had no
listings at that moment.

### ⚠ Superseded: the bulk download is probably unnecessary

Everything below this heading was written before Coflnet's per-item endpoint was found, and is
kept because it is still what a *bulk* fetch would cost. It is very likely no longer the plan.

`https://sky.coflnet.com/api/item/price/{id}/bin` returns the lowest and second-lowest BIN in
**87 bytes**, and `.../active/overview` returns every active listing for one item, sorted by
price - which is exactly what the "median of the 4 cheapest" rule needs, without downloading the
auction house. Both are already wired up in `AuctionBin` for the price graph.

Recipes are the natural unit here: a crafting page shows a few dozen rows, so a few dozen small
requests replace one 57 MB download, and only for items actually on screen.

Measured on `active/overview`, which returns 12 listings per item in 0.7-1.2 s:

| Item | 4 cheapest | Median of 4 | Lowest |
|---|---|---|---|
| Beacon V | 50.5M, 51.0M, 51.0M, 51.0M | 51.0M | 50.5M |
| Gemstone Gauntlet | 18.5M, 19.9M, 20.0M, 20.0M | 19.9M | 18.5M |
| **Divan's Drill** | **420.2M**, 1300M, 1379M, 1380M | **1339.5M** | 420.2M |

Divan's Drill is exactly the case the median-of-4 rule was written for: its lowest listing is 31%
of the median, so pricing a recipe off the bare minimum would report a profit that one mispriced
listing could absorb. The rule and this endpoint fit each other.

Still worth re-measuring before building: a page of forty rows means forty of these calls, and
1 s each is a second per row if they run in series.

### Download rules for a bulk fetch (the user's, adopted as given)

The full auction house is **48 pages, ~57 MB gzipped, ~105 MB parsed**, and carries **no ETag**,
so every refresh costs the whole download. Pages cannot be filtered: sampling 8 of the 48 found
the same item on pages 10 and 47. So the fetch would be rationed:

1. **Once** on first entering SkyBlock.
2. **Never automatically while a forge slot is running** - only the manual button. Read the
   forge state from the tab list (below).
3. **Craft page: at most hourly**, plus the manual button.
4. **Show when the data was last refreshed**, always.
5. **Show a progress bar while fetching** - page N of 48.

Two economies worth taking: request gzip (2.2 MB → 1.2 MB per page) and discard `item_bytes`
on arrival - it is **54% of the payload** and holds NBT nothing here reads.

`auctions_ended` (214 KB) carries real sale prices rather than asking prices, but identifies
items only by NBT. Noted as a future option, not used.

---

## Forge state from the tab list

Hypixel's `Forges:` tab widget gives every slot at once, without opening anything.

```
 1) Tungsten Plate: 1h 25m   read off a live game, 2026-08-17
 1) EMPTY                    read off a live game - note: no colon
 3) Drill Motor: 29h         past a day, still hours - never "1d 5h" (confirmed by the player)
 7) Refined Umber: Ready!    from Skyblocker's ForgeWidget, not observed here
 5) LOCKED                   from Skyblocker's ForgeWidget, not observed here
```

**Where each line came from is recorded on purpose.** An earlier version of this section listed
these as "confirmed in-game" when none of them had been read off a running game, which is the same
mistake that kept the price graph button broken for three sessions. The first two were surveyed
live; the last two come from a mod that has parsed this widget for years and are marked as
second-hand, so if one ever misbehaves it is clear which to doubt.

Four cases: `EMPTY`, `Ready!`, minutes, hours. Names carry rarity colour codes - strip
formatting before parsing, as everywhere else.

Counting the lines gives the number of slots owned, so the "forge slots" setting is only a
fallback.

**The widget can be off.** Widgets are configured per island via `/widgets` or `/tablist`, and
some unlock with progression; the official wiki closed in July 2026 and no source confirms the
default. So an absent `Forges:` panel means *unknown*, never "nothing is forging":

- Panel present → read the slots, block automatic refreshes while any is running.
- Panel absent → don't guess. The Forge page says to enable it with `/widgets`, the manual
  slot setting applies, and only rule 3's hourly timer stays in effect.

The tab list also reports `Area:`, which is a reliable way to know the player is on SkyBlock
for rule 1.

---

## Build order

1. ~~Recipe parser (3×3 grid + forge inputs, both `recipe` and `recipes`) and the profit maths,
   with tests. No UI yet.~~ **Done.**
2. ~~Craft and Forge pages over bazaar-priced recipes~~ **Done** - measured on live prices the
   day it was built: 30 crafting rows and 17 forge rows clear their costs.
3. ~~Auction pricing layer: median-of-4, outlier flag.~~ **Done** - and the bulk download was
   never needed, so the rationing rules below went unused. Coflnet's per-item endpoint answers in
   1.9KB and ~0.4s, four in parallel do twelve items in 0.6s. Requests are rationed a different
   way instead: candidates are ranked by their ingredient cost (known without asking anyone) and
   the dearest dozen are fetched per refresh, since there are 533 priceable outputs against a
   page of a hundred. Measured on the day: the 24 dearest yielded 13 profitable rows, the best
   clearing 16.2M.
4. ~~Tab list reader for forge state~~ **Done**, and it turned into more than a reader. The widget
   was surveyed on a live game in three states - every slot busy, every slot empty, and the widget
   switched off - which settled two things guessing would not have: the header is exactly
   `Forges:` and a slot line reads ` 1) Tungsten Plate: 1h 25m`, and with the widget off the
   section **disappears entirely** rather than emptying, so absent can never be read as idle.
   `LOCKED` and `Ready!` came from Skyblocker's `ForgeWidget`, which has parsed this for years.

   **Rule 2 was dropped rather than switched on.** It existed to protect a 57MB download that no
   longer happens; today a refresh is a handful of already-rationed Coflnet calls, and suspending
   them while a forge runs would be actively unhelpful - that is precisely when the player wants
   to know whether the next batch is still worth making.

   What the reader feeds is the **Status tab**: one expandable row per source of working capital,
   with the forge's cost captured when a job starts and never re-priced. `ForgeLedger` stores the
   finish *time* rather than the remaining text, so the page still answers from another island,
   marked `estimated` rather than `tracked` because the countdown is then derived rather than read.

5. Minions, auctions, Kat and Fann are listed on that page as `not yet read`. Listing them is
   deliberate: omitting a source would let the total read as complete when it isn't.

---

## Settings this adds

- **Forge slots** - default 2, range 1-7. Fallback only; the tab list overrides it when readable.
- Whatever the auction refresh needs (a manual button lives on the pages themselves).

## Open questions

- Exact wording of a running forge slot when the item name itself contains a colon - not yet seen.
  Live surveys have never produced one; the parser splits on the **last** colon so a name carrying
  one still reads correctly.
- ~~Whether any shop or recipe uses a fractional `count`~~ - **answered while building: none do**,
  zero of 4487. The field is parsed as `Double` anyway, since quantities like
  `"ENCHANTED_SUGAR:2500.0"` are written that way.
