# Project Map

Map of where everything lives, meant for quickly getting oriented (human or AI).

## Build configuration (project root)

| File | What it contains |
|---|---|
| `settings.gradle.kts` | List of supported Minecraft versions (the `stonecutter { create(rootProject) { ... } }` block). **Starting point for adding a new version.** |
| `stonecutter.gradle.kts` | The version currently "active" in the editor (`stonecutter active "..."`) and the substitutions (`swaps`) used in the code's `//? if` comments. |
| `stonecutter.properties.toml` | Centralized values: mod id/name/group/version, and for each Minecraft version a table `["26.1.2"]` with the compatible Fabric API/Loader versions and the compatibility range. **This is where you add the new version's data when supporting a new one.** |
| `build.gradle.kts` | The actual build logic: dependencies, Java requirements, Loom tasks, the `buildAndCollect` task. |
| `gradle.properties` | Only JVM/Gradle options (memory, cache). Don't touch for mod configuration: that goes in `stonecutter.properties.toml`. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle Wrapper, don't edit by hand. |

## Tests

`./gradlew test` runs them; they take a few seconds and need no game.

They cover the logic that can run outside Minecraft - number formatting, config migration,
market rankings, curve maths, recipe parsing and craft profits, the trend store, JSON
persistence. Anything touching a
Minecraft class can't be tested this way, which is a reason to keep such logic in its own
class rather than inside a `Screen`.

Two rules that make them worth having:

- **Write the test so it fails first.** A test that passes against broken code is worse
  than none, because it reads as proof.
- **Test the behaviour that was actually wrong once.** Every test here guards a real bug:
  the Italian decimal comma, axis labels collapsing to the same text, Coflnet timestamps
  parsing as null, Gson returning null for an empty file, settings lost on a key rename,
  a side panel reporting its last point as if it were the whole window.

A worked example of the first rule, from `PriceSeries.summarize()`: the test for "quiet hours
don't drag the average" passed even with the guard deliberately removed, because an hour with
zero volume contributes zero weight anyway - the filter it was supposedly testing did nothing.
Breaking the code is what exposed that the *test* was wrong, not just the code. It was rewritten
to check that a quiet hour still counts toward the high and the low, which is a real rule with a
real failure mode, and the redundant filter was deleted.

**When a feature is silently absent, make it say why before guessing again.** The price graph
button was "fixed" three times from inspection and stayed broken each time, because every one of
its four checks fails the same way: no button, no error, nothing in the log. What finally settled
it was reading the version that used to work out of the session transcript and diffing the two -
the fault was a check added since. `BazaarGraphButton.diagnose()` now names the check that
rejected it, in the log and in chat, behind a setting.

Two corollaries worth keeping: geometry and layout should be **worked out on paper before
editing** - a "fix" to the panel border made it twice as thick because the chamfer's arithmetic
was assumed rather than derived, and a second pass through the same maths showed the original was
right. And a `NoClassDefFoundError` for a class that plainly exists usually means the game was
running while Gradle rewrote it; check the crash timestamp against the `.class` mtime before
looking for a bug.

**A test can only be as right as the fact it encodes.** `BazaarGraphButtonTest` asserted for
three sessions that a bazaar menu's title contains "bazaar", with cases like `"Bazaar Orders"`
and `"Farming Bazaar"`. Every one passed. None had ever been read off a running game, and the
real title of a product page is the item's name - so the check rejected every page it existed to
serve, and the green suite was evidence of nothing but its own assumption. When a test asserts
something about an external system, the value in it comes from where the example was obtained,
not from the assertion.

**Gate on what a screen offers to do, not on what it is called.** The price graph button was
gated on the menu title three times and was wrong three times: requiring "bazaar" silenced it
everywhere (Hypixel names a product page after the *item*), and excluding "forge" to keep it out
of The Forge also killed "Reforge Stones", which merely contains the word. A blocklist can only
name menus already seen, and a title carries nothing stable to match on. What settled it was
logging the contents of 23 real menus (`BazaarGraphButton.surveyMenu`, behind
`logMenuSurvey`): both "Buy Instantly" and "Sell Instantly" appear in exactly the 6 product pages
and in none of the other 17. That rule needs no exceptions because it restates what the screen is
*for* - and the survey took one session, against three spent guessing.

If a class can't be tested because it reaches for a singleton or the system clock, pass
that in as a defaulted parameter (`quotes: Collection<Quote> = BazaarLivePrices.allQuotes()`)
rather than leaving it untested.

## Source code

```
src/main/kotlin/dev/syqs/skyquant/
├── SkyQuantMod.kt              <- mod entrypoint (onInitialize), everything starts here
├── gui/
│   └── Palette.kt                     <- every colour the mod draws, named by role
├── util/
│   ├── HttpJson.kt                    <- async JSON over HTTP, shared by anything calling an API
│   ├── JsonFile.kt                    <- a JSON file of the mod's own state, with safe load/save
│   ├── GitHubArchive.kt               <- downloads a repo tarball and walks it without unpacking
│   └── Hotkey.kt                      <- a polled key that fires once per press, not per tick
├── config/
│   ├── SkyQuantConfig.kt               <- the settings themselves (MoulConfig annotations)
│   ├── SkyQuantConfigManager.kt        <- load/save + `/skyquant` config screen
│   ├── ReminderSettings.kt            <- style/sound/text block shared by every reminder
│   ├── ConfigMigration.kt             <- moves renamed keys before the file is parsed
│   └── gui/                           <- custom MoulConfig widgets
├── reminder/
│   ├── Reminder.kt                    <- fires chat/toast/title/sound for one reminder
│   └── ReminderTicker.kt              <- creates reminders and ticks them to clear titles
├── hud/
│   ├── HudElement.kt                  <- what an overlay must implement to be placeable
│   ├── HudRegistry.kt                 <- registered overlays + their saved position and scale
│   └── HudEditorScreen.kt             <- drag to move, scroll to resize, all overlays at once
└── feature/
    ├── rift/
    │   └── UbikCubeReminder.kt        <- chat/toast/sound reminder for Ubik's Cube in the Rift
    ├── pickaxe/
    │   └── PickaxeAbilityReminder.kt  <- chat/toast/sound reminder for pickaxe ability cooldown
    └── bazaar/
        ├── BazaarGraphCommand.kt      <- `/skyquant bazaar` (and `/sq`), with id completion
        ├── BazaarGraphShortcut.kt     <- key press on the item under the cursor, in any container
        ├── BazaarGraphButton.kt       <- "Price graph" button beside Hypixel's product pages
        │                                 (gated on the menu OFFERING both "Buy Instantly" and
        │                                  "Sell Instantly" - never on the title, which carries
        │                                  nothing stable: a product page is named after the ITEM.
        │                                  Item identity comes from bazaarProductOf)
        ├── BazaarHomeShortcut.kt      <- key that opens the terminal while playing
        ├── BazaarOverlayRenderer.kt   <- registers the overlay on the HUD and on container screens
        ├── BazaarTaxCalibration.kt    <- watches containers for the Community Shop and NPC shops
        ├── BazaarFlipperDetector.kt   <- reads the tax rate out of that menu's text
        ├── NpcStockReader.kt          <- reads "Stock: 640 remaining" off shop entries
        ├── SkyblockItemId.kt          <- reads Hypixel's internal item id off an ItemStack
        ├── ProductName.kt             <- ENCHANTED_DIAMOND -> "Enchanted Diamond", full or short
        ├── data/
        │   ├── BazaarLivePrices.kt    <- shared 60s snapshot of every product's current price
        │   ├── BazaarHistory.kt       <- price history from Coflnet; Range says which market serves which window
        │   ├── AuctionHistory.kt      <- sale history for items the bazaar doesn't trade (day/week/month)
        │   ├── AuctionLivePrices.kt   <- last hour's *average sale*, from history. Not a price
        │   │                             you can pay: it once drove the HUD row and read
        │   │                             391.50M where the listing was 334.00M (Hyperion was
        │   │                             out by 143%). Use AuctionBin for anything actionable
        │   ├── AuctionBin.kt          <- cheapest live listing (87 bytes from Coflnet, not
        │   │                             Hypixel's 57MB auction dump) + lone-listing check
        │   ├── PriceVerdict.kt        <- live price vs usual price, the auction screen's
        │   │                             one-line answer. Thresholds measured, not chosen
        │   ├── PriceSeries.kt         <- one drawable shape for either market, plus summarize()
        │   │                             (three Kinds, not two: BAZAAR_DAILY is a fungible
        │   │                              market with no order book. Ask hasOrderBook /
        │   │                              hasVariants, never `kind == AUCTION` - that reads
        │   │                              correctly and is wrong for the month window)
        │   ├── BazaarPriceTrend.kt    <- rolling session history, recorded off those snapshots
        │   ├── BazaarWatchlist.kt     <- tracked and pinned products, saved to their own file
        │   ├── BazaarMarketSummary.kt <- rankings computed from the current snapshot
        │   ├── BazaarTax.kt           <- the cut the bazaar takes, detected or configured
        │   ├── NpcSellPrices.kt       <- what shops *pay*, from Hypixel's item resource
        │   ├── NpcShopPrices.kt       <- what shops *charge*, from the NEU repo, cached on disk
        │   ├── NpcDailyLimit.kt       <- units buyable per day; assumed, not read from any API
        │   ├── NpcFlipSummary.kt      <- both NPC trades, each priced instantly and on an order
        │   ├── Recipe.kt              <- one crafting/forge recipe, parsed from the NEU repo
        │   │                             (a recipe with NO `type` field is a crafting one:
        │   │                              2011 of 2670 are written that way, so requiring
        │   │                              type == "crafting" silently drops 79% of them)
        │   ├── RecipeIndex.kt         <- every recipe, cached on disk with a FORMAT_VERSION so a
        │   │                             parser fix can't be masked by a still-matching etag
        │   ├── AuctionSellPrice.kt    <- what a crafted item fetches at auction: median of the 4
        │   │                             cheapest listings (never the cheapest), with the
        │   │                             auction's own TIERED tax - not BazaarTax
        │   ├── CraftProfit.kt         <- prices one recipe: ingredients at topAsk, output net of tax
        │   ├── CraftSummary.kt        <- the ranked rows behind both pages (per hour for forge)
        │   ├── Liquidity.kt           <- whether a price describes a market at all. Judged on the
        │   │                             OUTPUT's weekly volume, which the ingredient figure in
        │   │                             CraftProfit.weeklyVolume says nothing about
        │   ├── ForgeState.kt          <- parses Hypixel's `Forges:` tab widget; null = UNKNOWN,
        │   │                             never "idle" (the widget vanishes when switched off)
        │   ├── ForgeLedger.kt         <- remembers each slot's job, its cost and its FINISH TIME
        │   │                             (not the remaining text), so it survives leaving the isle
        │   ├── ForgeJobPricing.kt     <- a forge job's ingredient cost and duration, from NEU
        │   ├── WorkingCapital.kt      <- the Status page's model: value, cost, profit per source
        │   └── WorkingCapitalSummary.kt <- builds those rows from the widget or from the ledger
        └── gui/
            ├── BazaarHomeScreen.kt    <- the terminal: seven tabs - Status, Watchlist, Flip, the
            │                             two NPC directions, Craft and Forge. Craft/Forge figures
            │                             are PAIRS ("fast/patient"), each half priced with the
            │                             cost its own trade pays; see docs/PRICE_SCREENS.md.
            │                             Status is the odd one out: it reports the PLAYER's own
            │                             position rather than a market ranking, one expandable
            │                             row per source of working capital
            ├── BazaarGraphScreen.kt   <- the chart screen: loading, layout, buttons, side panel
            ├── PriceChart.kt          <- draws curves, axes, volume and hover inside a rectangle
            │                             (an hour whose price sits above the scale is left OUT
            │                              of the line and drawn as a detached dot - see the
            │                              "Known issues" note on outliers)
            ├── BazaarOverlay.kt       <- the pinned-price panel drawn over the game
            ├── DataTable.kt           <- column-aligned table shared by the terminal's tabs
            ├── NumberFormats.kt       <- compact prices/volumes/percentages, shared by all screens
            ├── Sparkline.kt           <- word-sized price curve, no axes or labels
            ├── LineRenderState.kt     <- thick antialiased polyline
            ├── RoundedRectRenderState.kt <- filled rounded rectangle
            └── CurveInterpolation.kt  <- Catmull-Rom smoothing for the price curve

src/main/java/dev/syqs/skyquant/mixin/
└── ExampleMixin.java          <- example mixin, replace/extend as needed
```

- **Custom GUI drawing**: since 1.21.8 GUI rendering is split-phase - `GuiGraphicsExtractor`
  only queues work, and anything beyond rectangles and text has to be submitted as a
  `GuiElementRenderState` (see `LineRenderState`/`RoundedRectRenderState`). The pipeline
  draws **quads only**, and a quad whose perimeter crosses itself renders as nothing, so
  vertices must walk the shape in one consistent direction.

- **Outlining a rounded panel**: draw a slightly larger plate behind it and let the panel
  cover all but a hairline. Two things the shapes won't tell you: the outer radius must grow
  with the plate (`radius + width`), or the two curves run at different offsets and the
  border thins to `1/√2` of a pixel along a corner and disappears; and the plate wants
  `softEdges = false`, since the anti-stair fringe fades the very line being drawn.

- **Base package**: `dev.syqs.skyquant`. All mod classes go in here (or in
  topic-specific sub-packages, e.g. `feature/`, `config/`, `hud/`, as the
  project grows).
- **Language: Kotlin**, except for mixins. The mod's "normal" code is written
  in Kotlin under `src/main/kotlin/`; see `docs/RESEARCH.md` section 4 for
  why.
- **Mixins stay in Java** under `src/main/java/.../mixin/` — this is a
  technical limitation of Fabric/Mixin, Kotlin files can't be used as mixin
  classes (see `docs/RESEARCH.md`). They must be registered in
  `src/main/resources/skyquant.mixins.json`.
- **Code specific to a Minecraft version**: marked with Stonecutter comments,
  e.g.:
  ```java
  //? if <1.21 {
  /*code for old versions*/
  //?} else
  code for new versions
  ```
  See [RESEARCH.md](docs/RESEARCH.md) section 3 for the general concept, and
  the [Stonecutter guide](https://stonecutter.kikugie.dev/wiki/start/) for
  the full syntax.

## Mod resources

```
src/main/resources/
├── fabric.mod.json        <- mod manifest (name, id, entrypoint, dependencies)
├── skyquant.mixins.json     <- list of mixins to load
├── skyquant.ct              <- access widener / class tweaker (access to Minecraft's private fields/methods)
└── assets/skyquant/         <- mod icon and other assets
```

## Documentation

`README.md` and `PROJECT_MAP.md` live in the root (they're the entry points
for getting oriented in the project). Everything else that isn't needed for
the mod to actually run lives in `docs/`.

| File | Purpose |
|---|---|
| `README.md` (root) | Quick setup, how to build, pre-release checklist. Entry point for anyone opening the project. |
| `PROJECT_MAP.md` (root) | This file. |
| `docs/RESEARCH.md` | Why the technical choices were made (Fabric, Stonecutter, Kotlin, etc.), reference mods, Hypixel rules, sources. Check this before proposing stack changes. |
| `docs/API_RESOURCES.md` | What every data source actually returns, which fields are used and which are not, rate limits, and how data is kept fresh without a mod update. Read before assuming a figure is available. |
| `docs/PRICE_SCREENS.md` | What each figure on the price graph and the NPC tabs means, and why. Every threshold in it was measured against live data, and several replaced a rule that looked sound and was wrong in game. Read before changing what a screen shows. |
| `LICENSE` (root) | Full text of GPL-3.0-or-later, the license chosen for the project. |

## Auto-generated folders (not version-controlled, see `.gitignore`)

| Folder | Content |
|---|---|
| `build/` | Compilation output, final jars in `build/libs/<version>/`. |
| `versions/<version>/build/` | Per-version compilation output generated by Stonecutter (e.g. `versions/26.1.2/build/libs/`, contains that version's mod jar). |
| `run/` | Minecraft test environment (worlds, logs, account saved by DevAuth). |
| `.gradle/` | Gradle cache. |

## Known issues

Small, deliberately deferred, and recorded so they are not rediscovered as new.

| Issue | Where | Notes |
|-------|-------|-------|
| *(none currently recorded)* | | |

## Most-used Gradle tasks

| Task | What it does |
|---|---|
| `runClient` | Launches a Minecraft dev client with the mod loaded. |
| `buildAndCollect` | Compiles and copies the jar (+ sources jar) to `build/libs/<mod version>/`. |
| "Set active project to ..." | (generated by Stonecutter) Switches the active Minecraft version in the IDE, to work on/test a different version. |

## Where to add new things (quick guide)

- **New mod feature** (e.g. an overlay, a tracker) -> new package under
  `src/main/kotlin/dev/syqs/skyquant/`, registered/initialized from
  `SkyQuantMod.onInitialize()`.
- **Anything that draws** -> take colours from `gui/Palette.kt`, never a literal. They're
  named by role (`POSITIVE`, `MUTED`) rather than by hue, so a screen asks for the meaning
  and the palette decides how that looks - which is also what lets the player swap themes
  without a screen knowing themes exist. Product names come from `ProductName`, numbers
  from `NumberFormats`, and a key polled while playing from `util/Hotkey.kt`.
- **A new colour** -> add a role to `Palette` *and* a value in every `Theme`. The compiler
  enforces this: `Theme` has no defaults, so a theme can't be left half-defined. Adding a
  theme means filling the whole table, deliberately.
- **The visual language of a screen** (financial/dense - the bazaar):
  depth from stacked surfaces (`BACKGROUND` -> `SURFACE` -> `RAISED`), not drawn frames;
  thin `RULE` separators; the accent reserved for what is live, selected or interactive.
  Never let colour carry meaning alone - a rise is green *and* an arrow, the selected row is
  tinted *and* has a caret. That rule is what makes the colour-blind themes work, and the
  check is: strip every colour out and the screen must still read.
  A sparse, non-financial screen has no house style yet; design it when the first one exists
  rather than stretching the dense layout over it.
- **Anything persisting state of its own** (not a setting) -> `util/JsonFile.kt`, which
  handles the folder, the logging and the fallback. Note Gson returns null for an empty or
  truncated file *without throwing*, so `runCatching` alone doesn't protect against it -
  `JsonFile` falls back to the default for that case too.
  **Never build the object to save inside `apply`/`also` when the field and the source share a
  name**: `Stored().apply { stock = stock.toMutableMap() }` resolves *both* sides to the
  receiver's own field, so the object is handed its own empty map. It compiles without a
  warning and the screen keeps showing the right figures all session - the loss only appears
  after a restart. Assign to a local first, then set the fields.
  And if the state expires (shop stock resets at 00:00 GMT), store the day alongside it and
  drop it on load when the day has turned. Stale data wearing a "freshly read" marker is worse
  than no data, because it invites more trust than the fallback does.
- **Anything reading an external API** -> reuse `util/HttpJson.kt`; results arrive on a
  worker thread, so hop back with `Minecraft.getInstance().execute { }` before touching
  the game. For bazaar prices reuse `BazaarLivePrices` rather than fetching again: one
  request already covers every product. **What each source actually contains, and which parts
  are unused, is catalogued in [docs/API_RESOURCES.md](docs/API_RESOURCES.md)** - read that
  before assuming a figure is or isn't available.

- **Anything reading a bulk data set** (the NEU repo, any GitHub-hosted database) ->
  `util/GitHubArchive.kt` walks a repo tarball in one streaming pass without unpacking it, and
  takes an ETag so an unchanged repo answers 304 in a few hundred bytes instead of 9MB. Derive
  the small index you need and cache *that* through `JsonFile`, not the archive. Note the mod's
  rule that no data may be frozen into the jar: a player must never need a mod update to get
  current figures.

- **Anything reading the NEU repo's recipes** -> `Recipe.parseAll`, and read **both** `recipe`
  (singular) and `recipes` (array): DIAMOND uses the first, ENCHANTED_DIAMOND the second, and 178
  items carry several. Three traps, all measured across the repo's 4487 recipes rather than
  assumed: a recipe with **no `type` field at all is a crafting recipe** (2011 of 2670 are written
  that way, so filtering on `type == "crafting"` drops 79% of them and the page simply shows fewer
  rows); quantities are **not always integers** (`"ENCHANTED_SUGAR:2500.0"` - 37 of them, and
  `toInt()` throws); and the repo holds **seven** recipe kinds, of which `drops`, `npc_shop`,
  `katgrade` and `trade` are not trades against a market - parsing a `drops` entry as ingredients
  yields mob names and colour-coded lore lines. Cache the derived index through `JsonFile` with a
  **format version**: without one, a parser fix never reaches an existing user, because the stored
  etag still matches and the repo is never re-read.

- **Anything pricing a sale at auction** -> `AuctionSellPrice`, and note the two ways it differs
  from a bazaar sale. The price is the **median of the four cheapest listings**, never the
  cheapest: undercutting is how an auction sells, so the bottom listing is where the market is
  heading, and Divan's Drill's lowest was 31% of its median when this was written. And the cut is
  **tiered** - 1%/2%/2.5% to list by price band, plus 1% to claim above a million (above, not at:
  the fee is capped so it can't take a sale below a million). Using `BazaarTax` here understates
  the cut by more than double on exactly the expensive items this exists to price.
  **Fetch a few at a time**: there are 533 priceable auction outputs and each is its own request,
  so rank the candidates by their ingredient cost - known without asking anyone - and request the
  dearest dozen per refresh, letting the cache fill the rest over later passes.

- **Anything drawing a value the chart's scale can't reach** -> leave it out of the line and draw
  it as a detached dot, never as part of the curve. An auction hour with a single sale is often a
  *different product wearing the same name* - a well-enchanted Titanium Drill at 620M among
  ordinary ones at 340M - so joining it to its neighbours draws a price movement that never
  happened. Three approaches were tried in game before this one: no clipping at all (the line ran
  over the tooltip and the side panel), clipping the finished polyline (one outlier drags its two
  neighbouring segments out too, so the line broke in three places), and capping the value before
  smoothing (continuous, but drew a rounded peak climbing to the ceiling - which reads as a rise
  to 400M that never occurred). The last one was the best looking and the worst: **a plausible
  false reading is worse than a visibly broken one**, because nothing tells the reader to doubt
  it. Guard the skip so it can't empty the chart: below two remaining points, skip nothing.

- **Anything ranking by a price** -> ask `Liquidity` whether that price is a market first. The
  Forge page led with AMBER_MATERIAL on a 10.0M ask against a 1.1M best bid, on something that
  sold **15 units in a week**: every figure was read correctly, the row was worthless, and because
  the page ranks on profit the most meaningless price is exactly the one that reaches the top.
  Note the filter that already existed did not catch it - `CraftProfit.weeklyVolume` measures the
  scarcest **ingredient**, and this recipe has liquid ingredients and an illiquid output, so
  `outputWeeklyVolume` is a separate figure. Thin rows are **marked and sorted last, never
  dropped**: a real demand spike looks identical from here, which is also how Coflnet handles it
  (they flag, and hiding is opt-in).

- **Anything computing a profit** -> a bazaar *sale* is taxed (`BazaarTax`), a sale to an NPC
  is not. **A new screen must reuse this, not re-derive it**: the Status page priced its output
  gross and with `sellPrice` instead of `topBid`, overstating the profit on screen by 12% in the
  flattering direction - a whole page written without looking at how the Forge page next to it
  already did the same sum.

- **Anything called from a `draw`** runs ~60 times a second, so it must not save, allocate or
  parse unconditionally. `ForgeLedger.record` wrote the file on every frame the Status tab was
  open (serialise + temp file + atomic move, sixty times a second) until it was made to compare
  first, and `ForgeTracker.state` re-sorted a hundred tab list entries twice per frame until it
  was given a half-second cache. And `sellPrice` is the **lower** of the two bazaar prices - it is what buyers bid, so
  it is both what you receive selling instantly and what a *buy order* fills at. Getting that
  backwards silently inverts every figure on the screen.
  For an **order-to-order flip** price from the order book (`topAsk`/`topBid`), not from
  `quick_status`: the summary can quote a price nobody is offering, and one stale order made
  SHARD_DRYBARK look ten times better than it was.

- **Anything showing a percentage the player can check against the game** -> `exactPercent`,
  not `percent`. The tax is 1.25% and 1.125%; at one decimal those print as "1.3%" and "1.1%",
  disagreeing with what Hypixel shows on the very screen the figure came from. For a percentage
  that can grow without bound - an order-to-order margin reaches six figures - use
  `percentCompact`, or it prints through the column beside it.

- **A cell holding two figures** -> `DataTable.Cell.of(...)` with a `Part` each, coloured by
  `ProfitColor.of`. One colour for the pair means a loss and a gain share it: "-2.6k/92.5k"
  shipped entirely in green. And note where the test for this has to live - a test asserting
  that `DataTable` *can* hold two colours passes happily while the screen uses one, which is
  why the rule sits in `ProfitColor` where it can be exercised directly.

- **A defaulted parameter that reads config or game state** -> pass it as a function
  (`taxRate: () -> Double = { BazaarTax.rate }`), not a value. A plain default is evaluated at
  the call site, so a test that omits it drags in the whole game and dies on `Not bootstrapped`
  - even when the value is never actually used.
- **Anything reading Hypixel's menus** -> `SkyblockItemId` for the item id. Identify a
  screen by what its items *say* (lore) rather than by which slots they sit in; slot
  layouts differ between menu types and change without notice. Note the container arrives
  empty and is filled a moment later, so read it per frame, not once on open.
  But **lore alone cannot tell you which screen you are on**: the "Price graph" button appeared
  in The Forge because slot 13 there holds Refined Umber - a real bazaar product whose lore
  says "Currently making", passing every item-level check. Anything the mod *draws by itself*
  must gate on `screen.title` as well; a keypress the player asked for can stay permissive.
  Two traps that each cost a debugging round: lore is
  `stack.get(DataComponents.LORE)?.lines()` **with the parentheses** - without them it compiles,
  returns the raw component and yields no text; and menu strings carry `§` colour codes inline,
  so `§dBazaar Flipper §fII` reads as `§fii` and matches nothing. Strip formatting *inside* the
  parser rather than at the call site, or the helper works only when called from one place.
  Prefer a figure the menu states outright (`Your Tax Rate: 1%`) over one derived from a tier:
  it is what the server actually applies, and it survives Hypixel re-balancing the tiers.
- **New mixin** -> `src/main/java/dev/syqs/skyquant/mixin/` (Java,
  not Kotlin), then add it to the `mixins` array in `skyquant.mixins.json`.
- **New view in the bazaar terminal** -> add an entry to `BazaarHomeScreen.Tab` and a
  draw method; build its table with `DataTable` rather than laying out text by hand.
  Views are tabbed, never side by side: the terminal handles growing complexity by
  splitting it across focused full-width views, which is also what lets every column
  carry a heading. Numbers right-align in fixed columns, units go in the header.
  **Work the column widths out on paper before drawing.** Summing the fixed columns against the
  panel's 480px of content finds a collision in one pass, where a screenshot finds it after a
  restart - that arithmetic is what showed the Forge tab leaving 114px for a name needing 144,
  and what justified dropping two columns rather than shrinking everything. Remember the sort
  arrow: it is appended to the active heading and has to fit *inside* that column.
  **A cell can hold a pair** (`Cell.of` with a `Part` each, coloured by `ProfitColor.of`), which
  is how two related figures share one column - but colour each half separately, or a loss and a
  gain end up the same colour.
- **New on-screen overlay** -> implement `HudElement` and call `HudRegistry.register` at
  startup. It then appears in the HUD editor with drag and scroll-to-resize for free, and
  its position and scale are saved to `config/skyquant_hud.json`. Lay out at natural size
  and ignore the scale: the caller applies it to the matrix before calling `draw`.
- **Anything that prints a number** -> `NumberFormats`, never `String.format` directly.
  It pins `Locale.ROOT`; the system default prints "1,40k" on an Italian machine, where
  the comma reads as a thousands separator. And note the game's font is proportional, so
  columns must be drawn as separate calls - padding with spaces does not align anything.
- **New setting** -> add an annotated field to `SkyQuantConfig`; MoulConfig builds the
  widget from the annotation. It edits the live instance but never writes it out on its
  own, so anything that changes settings outside the config screen must call
  `SkyQuantConfigManager.save()`. **Renaming or moving an existing key** needs an entry in
  `ConfigMigration`: Gson drops what it doesn't recognise, so the setting would silently
  come back as its default instead of failing. A field holding **another settings object**
  needs `@Accordion` (or `@Category` at the top level): MoulConfig demands an editor for
  every field, and one it can't build for crashes the game while it starts. The build and
  the tests still pass - that check only runs at launch - so run the client after adding one.
- **New reminder** -> declare one `ReminderSettings` field in the config and build it with
  `ReminderTicker.create(...)`, passing the default wording. Style, sound, title duration
  and custom text come with it; the feature only decides *when* to call `fire()`. Pass
  `"{placeholder}" to value` pairs to `fire` for anything known only at that moment.
- **New client command** -> mount it under the existing `/skyquant` in
  `SkyQuantConfigManager`, not as a second registration: Brigadier keeps only the last
  registration of a literal, so a separate `/skyquant` silently replaces the first.
- **New supported Minecraft version** -> see the guide comment at the bottom
  of `stonecutter.properties.toml` + `settings.gradle.kts`.
- **External APIs** (Hypixel API, NEU data repo, Coflnet) -> see docs/RESEARCH.md
  section 6 for where to find them. Call them through `util/HttpJson.kt`, which
  already handles the worker thread and the JSON parsing.
