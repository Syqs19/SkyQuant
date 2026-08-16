# Price screens - what each figure means, and why

This covers the price graph and the terminal's NPC tabs: which number answers which question, and
the reasoning behind the ones that are not obvious. `API_RESOURCES.md` covers where the data comes
from; this covers what is done with it.

Almost every rule below was arrived at by measuring live data, and several replaced a rule that
looked sound but was wrong in game. Where that happened it is recorded, because the wrong version
is usually the one that comes to mind first.

---

## The one difference that shapes everything

**Bazaar goods are fungible; auction items are not.**

One Enchanted Diamond is every other Enchanted Diamond, so its price is a single number and the
bazaar quotes two sides of it. A Titanium Drill with good reforges and enchantments is a
different product wearing the same name as a bare one, and the auction house reports them
together.

This is why the two markets get different screens rather than one screen with different labels:

| | Bazaar | Auction |
|---|---|---|
| Price is | two sides of an order book | a scatter of what people paid |
| The chart draws | buy and sell curves | one line, the base price |
| The gap means | a margin you can trade | how much extras are worth |
| Live figure | buy / sell price | lowest BIN |
| The screen's job | is now a good moment? | is this listing fair? |

---

## The price graph

### The verdict row

One line at the top: `Cheap -17.4%`, `Normal -1.4%`, `Expensive +90.0%`.

It compares the **lowest BIN now** against the **usual price** - the median of the window's base
prices. Everything below it is the working.

This exists because the screen had stopped answering the question it is opened for. It had grown
to four prices (cheapest listing, window average, top, low) and none of them said whether to buy.
A bazaar product arrives with its answer built in; an auction item does not, and more figures made
that worse rather than better.

**Threshold: ±10%.** Measured across eleven items, the differences fell at -18.0, -17.4, -16.3,
-7.3, -1.1, -0.4, 0.0, +1.1, +2.8, +9.1 and +90.0 percent. There is a clean gap between -7.3 and
-16.3 with nothing in it, so 10 lands in empty space and splits no cluster. At 5% it would have
called Hyperion cheap at -7.3%, which is inside its ordinary drift.

**"Usual" is a median, not a minimum.** The cheapest hour of a window drifts lower the longer the
window, so a minimum would make the same item look dearer on 7d than on 1d with nothing having
changed.

No "Price" prefix on the row: `Price Expensive +90.0%` needs 128px of a 104px column, so the word
would be dropped exactly when the answer is least ordinary.

### The base price line

The chart's line follows the **cheapest sale each hour**, not the average - that is the closest
the data comes to "an unmodified one of these".

Three corrections make it usable, each calibrated against live data:

| Correction | Rule | Why that rule |
|---|---|---|
| Outlier floor | ignore hours whose minimum is below 50% of the window median | Across 5 items and 619 traded hours this removes exactly two absurdities - a 1.0M drill against a 336M median, a 0.04M Daedalus Axe against 12.8M - and touches nothing else |
| Axis scale | ignore hours with a single sale | 4 of 22 Titanium Drill hours had one sale; excluding them takes the axis from 301M tall to 134M. They are still drawn, just clipped |
| Median source | only hours that traded | Including quiet hours pulled the median up, and with it the floor, until genuine cheap hours fell below it and were "corrected" away - the guard was manufacturing outliers |

A **fixed ceiling** on how far above the median a price may sit was tried first and rejected on
the evidence: at 1.5x it clipped 21 of Hyperion's 23 hours, where the spread is genuine. Thin
hours mislead; wide ones are information. Volume separates them, a price threshold cannot.

### What is drawn, and what was removed

- **Line**: base price (auction) or buy/sell curves (bazaar).
- **Dashed rule + "BIN"**: the cheapest live listing. Dashed because it is the only thing on the
  chart that is not history. Dropped when it falls outside the charted range rather than pinned
  to an edge, where it would claim a level it does not have.
- **Volume strip**: how many sold, on its own scale.
- **Removed**: the base-to-average band. It showed how much variation there is, which is true and
  was still the wrong thing to draw - it filled the plot with exactly the noise that makes an
  auction chart hard to read, competing with the two lines that answer the question. The premium
  is still in the hover tooltip, where it can be asked for.

### The side panel

`Cheap -17.4%` / `Buy now` / `Usual 1d` / `Sold 1d` / `Trend 1d`

- **Buy now** is the lowest BIN, flagged `⚠` when it sits more than 15% below the second cheapest
  listing - one underpriced item is not a going rate. Sampled across twelve items, nine sit within
  1% of the second cheapest, Livid Dagger at 11.2% is the widest ordinary case, and Daedalus Axe
  at 28.6% is the one real outlier. The second price is shown only when the flag fires; otherwise
  it repeats the first.
- **Trend**, not a bare window label. It and the verdict are both percentages and they routinely
  disagree: a drill priced normally today (+4.7% on usual) sat inside a month that fell 25.9%.
  Both true, different questions - unlabelled and side by side they read as a contradiction.
- **Removed**: `Top` and `Low`. They say what reforges and enchantments are worth, which is real
  and is the wrong answer to "should I buy this".

### Windows

`1h` `1d` `7d` `30d`, greyed out where the market has no data, with the reason on hover. See
`API_RESOURCES.md` for which endpoint serves which - the short version is that auctions have no
hourly data and the bazaar's own endpoint has no monthly, so 30d comes from the item-price
endpoint for both markets and draws one curve instead of two.

---

## The NPC tabs

Two directions, and they are not symmetrical:

| | NPC → BZ | BZ → NPC |
|---|---|---|
| Buy from | shop, **640/day** | bazaar, no cap |
| Sell to | bazaar (taxed) | NPC, no cap |
| Profit shown | per day (× stock) | **per unit** |
| Stock column | yes | no |

Both tabs were built from one shared column set, so `BZ → NPC` inherited the shop's daily cap. It
showed as a `Stock 640` column reading identically on every row - a column that does not vary
distinguishes nothing - and, worse, silently multiplied every profit by 640, inventing a ceiling
that does not exist.

Removing the multiplier then made the `Profit` column equal to the `Now` and `Order` columns by
construction (`-45.5 / +949.3` followed by `-45/949`), so that column is gone from that tab too.
What actually bounds this direction is the order book's depth, not a daily stock.

Row cells are built from the column list rather than a fixed count: a row with more cells than
columns silently shifts every figure after it under the wrong heading.

---

## The Craft and Forge tabs

Both answer the same question - is making this worth more than buying it - and differ only in
what they rank on. Recipes come from the NEU repository, prices from the live bazaar.

**One level deep.** An ingredient costs what the market charges for it, never what it would cost
to craft *that*. It matches the action a player actually takes, and the nested version needs
cycle handling and a second number in every row before it says anything this doesn't.

### Every figure is a pair

`105.4k/98.9k` - left of the slash is the fast trade, right is the patient one:

| | Left (fast) | Right (patient) |
|---|---|---|
| Buying | outright, at `buyPrice` | a buy order, filling at `topAsk` |
| Selling | into the standing bids | a sell offer, filling at `topBid` |

**Each profit pairs the cost its own trade pays.** They used to share the order cost, which made
the fast figure a hybrid - quick on the way out, patient on the way in - describing a trade
nobody can make, and flattering it. Measured on live data: **8 of the 29 rows then on screen turn
from a gain into a loss** once the fast purchase is priced honestly. GOLDEN_TOOTH went from
`+1.2k` to `-5.6k/+1.2k`.

The margins divide by their own outlay too. The fast trade puts up more money for less return,
and a shared denominator hides half of that.

**The other two crosses are deliberately absent.** Buying outright and then waiting on a sell
offer means paying a premium for speed you immediately give back - a mistake, not a strategy.

### Why the gap matters even though it is usually zero

Across 622 liquid products the median gap between the two purchase prices is **0%** - for most
items the pair reads as the same number twice. The tail is what justifies the column: it falls on
cheap raw materials, which recipes use by the hundred. GRAVEL costs **19x** more bought outright,
CHUM 7x, ENCHANTED_RUBY_VEILSHROOM 3.3x.

### Rows priced at auction

Marked `AH` after the name. The bazaar covers materials; weapons, armour, talismans and most
forge plates sell at auction instead, and **502 crafting recipes and 33 forge recipes** have
ingredients the bazaar prices and an output it doesn't. Without this layer every one of them was
dropped without trace - and they are where the large profits are: measured the day it was built,
the best auction-priced rows cleared 3.5M to 16.2M against a best of 115k on the bazaar-only page.

**The price is the median of the four cheapest listings**, not the cheapest. Undercutting is how
an auction sells, so the bottom listing is where the market is heading rather than where it is,
and a whole page built on it would rest on whatever mistake somebody made this minute. Divan's
Drill was listed at 420.2M against a median of 1339.5M - **31%** - while Beacon V sat at 99%.

`!` beside the `AH` marker means exactly that: the cheapest listing is below 60% of the median,
so the market underneath the estimate is thin. The row is flagged rather than hidden, since the
listing is real and worth checking.

**The auction's cut is tiered and is not the bazaar's.** A BIN costs 1% under 10M, 2% up to 100M
and 2.5% above, plus 1% to claim anything over a million - so a 200M forge output loses 3.5%,
against the bazaar's 1.25%. The claim fee starts *above* a million rather than at it, because it
is capped so it can never take a sale below one.

**An auction row carries one sale price, not two.** There is no order book to be fast or patient
against; listing is the only way to sell. The row's fast/patient pair then describes how the
*ingredients* were bought, which is still a real distinction and the one that varies.

**Prices are fetched a few at a time.** There are 533 such outputs against a page showing a
hundred, and each is its own request (~0.4s, 1.9KB). Fetching them all would be half a minute
every time the tab opens. So candidates are ranked by something already known without asking -
**what their ingredients cost** - and the dearest dozen are requested per refresh. An expensive
recipe is not necessarily profitable, but it is where the large profits live: a 40M talisman can
clear millions, a 400-coin one cannot whatever its margin. Verified: the 24 dearest candidates
yielded 13 profitable rows.

### What each page ranks on, and what it drops

| | Craft | Forge |
|---|---|---|
| Ranked on | profit per craft | **profit per hour** |
| Volume floor | 50k weekly | none |
| Margin column | yes | no |
| Duration column | n/a | no |

**Forge ranks per hour because durations run from 30 seconds to a week.** Tungsten Key makes 259k
in 30 seconds; Gleaming Crystal makes 11.65M in six hours. Ranked on profit the crystal wins by
45x, ranked on the rate the key wins by 16x - and only one of those is advice worth acting on.
`NumberFormats.duration` must never round 30s to "0h": 22 of the 120 forge recipes take 30
seconds and they are the best trades on the page.

The **duration column is gone** because the per-hour figure already contains it, and the
**margin column with it** - a percentage of capital says nothing about what a slot is worth, and
two recipes with equal margins can differ tenfold per hour. Both removals bought the name column
72px, from 114 to 186.

The **volume floor applies to the scarcest ingredient**, not the output: that is the side that
has to be bought, and it is what caps how often the trade can be repeated. Forge has no floor -
its outputs trade in tens rather than tens of thousands, so the crafting threshold would empty
the page, and a recipe you can run twice a day is still worth running.

---

## Rules that apply to both

- **Colour never carries meaning alone.** A verdict has a word, a flagged listing has a glyph, a
  profit has an arrow.
- **A figure that cannot be named is worse than a row that wraps.** The side panel used to drop a
  label when it did not fit beside its value, which silently anonymised every large number -
  `Buy now 334.00M` needs 82px of what was a 72px column. The panel is now 128px wide and wraps
  rather than dropping.
- **Say when there is no answer.** "Not traded on the bazaar or the auction house", "none listed",
  a greyed window with its reason. An empty screen reads as a broken mod.
- **A decoration that takes width has to be paid for out of the column.** The sort arrow appended
  to the active heading made `PER HOUR ▼` 57px wide in a 54px column, so it ran into its
  neighbour - on that one column, only while it was the active sort, which is why it survived
  every other tab. The title is truncated against the width left over after the arrow, never the
  other way round: the arrow is what says which column you are sorted by.
