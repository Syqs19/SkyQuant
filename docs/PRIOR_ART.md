# Prior art register

What we looked for before building something, where we looked, and what we
decided. Filled in before any non-trivial change - the procedure is in
[PROJECT_MAP.md](../PROJECT_MAP.md), under "Working rule: check prior art first".

Entries are newest last. A "we looked and found nothing" entry is worth keeping:
it stops the same ground being covered twice, and it records that a design was
ours by necessity rather than by choice.

The reference mods are listed with their licences in
[RESEARCH.md](RESEARCH.md) §5. Their approaches are studied, never their code —
see the skill for why that line matters.

---

## 2026-08-18 — Reading minion state

**Question.** The Status tab lists minions as `not yet read`. Minions do not
appear in the tab list, which is where the forge widget comes from, so where does
the data come from at all?

*Not yet researched.* Recorded here as the next question the register is meant to
answer, so the gap is visible rather than forgotten.

---

## 2026-08-18 — Kat and Fann timers

**Question.** Both are listed as `not yet read` on the Status tab. Do the
reference mods read them from the tab list, and in what format?

*Not yet researched.* Blocked on a survey with a pet actually in upgrade — see
the menu survey tool for how the real screens get read.

---

## 2026-08-18 - Changelog format

**Question.** How do the established SkyBlock mods write release notes, so ours
can be pasted into a Modrinth release without rewriting?

**Where we looked.** SkyHanni's `docs/CHANGELOG.md` on the `beta` branch (the
root-level path 404s, it moved under `docs/`), its GitHub releases API, its
Modrinth version pages, and its `CONTRIBUTING.md` for how entries are
categorised.

**What we found.** `## Version X`, then `### New Features / Improvements / Fixes
/ Technical Details / Removed Features`, then one line per change starting with
`+` and a past-tense verb - Added, Improved, Made, Fixed. Sub-bullets only when a
single feature has several parts. Each line ends with an author credit and a pull
request link. Releases with many entries also group by island (Fishing, Dungeon,
Foraging) under the category heading.

**What survived.** The version heading, the four category names, the `+` prefix,
the past-tense verb and the one-line rule. Dropped: author credits and PR links
(one contributor here, so they are noise), and the per-island sub-grouping
(SkyHanni ships 17 features a release and we ship a handful - the headings would
outnumber the lines). `Technical Details` replaced our invented "Under the hood",
since the community already reads the former.

**Decided.** `CHANGELOG.md` rewritten to that shape, and the rule in
`PROJECT_MAP.md` updated to match. Their format was studied, not their text -
SkyHanni is LGPL-2.1.

---

## 2026-08-18 — A Basic / Advanced toggle for the terminal

**Question.** The roadmap's last item is a Basic / Advanced toggle. How do the
established mods let a player choose how much information a dense screen shows —
is there a global "simple mode", and does anyone ship a two-mode switch?

**Where we looked.** SkyHanni's config tree (GitHub code search for
`advancedMode`, `Compact Display`, `extraInfo`, `ConfigEditorDraggableList`),
specifically `CustomScoreboardConfig`, `InformationFilteringConfig`,
`TrophyFishDisplayConfig` and the crop-milestone `NextConfig`; Skyblocker's
`TooltipInfoType` and `GeneralConfig.ItemTooltip`; Firmament (searched for
compact/advanced config, no hits). Plus the general UX literature on progressive
disclosure (NN/g and follow-ons).

**What we found.** **Nobody ships a global two-mode switch.** Three different
answers instead, all per-view:

- **Per-display "Compact Display" booleans** (SkyHanni: golden fish timer,
  composter, best crop time). Scoped to one display, described by what it removes
  — "removing the crop name and exp, hide the # number".
- **A draggable list of which lines appear, and in what order** (SkyHanni's
  Custom Scoreboard `scoreboardEntries`). The player composes the view rather
  than picking a preset, with a Reset button back to the default.
- **One boolean per figure** (Skyblocker: `enableNPCPrice`, `enableBazaarPrice`,
  `enableLowestBIN`, `enableAvgBIN`…). Notably, each flag gates *fetching* the
  data as well as drawing it, so turning a figure off costs one fewer download.
- **Automatic filtering rather than a mode** (SkyHanni's
  `InformationFilteringConfig`): hide lines with no info, hide lines not relevant
  to the current location. The screen thins itself out by context, with no
  setting for the player to have chosen wrongly.

**What survived verification.** SkyHanni is LGPL-2.1 and Skyblocker LGPL-3.0, so
approaches only — no code. All findings are current (both target 26.x). The
applicability caveat: every case above is an **HUD overlay**, a few lines drawn
over the game, where ours is a full-screen table with headed columns. Their
"compact" removes labels because an overlay has no header row to carry them; our
columns already name themselves, so that specific trick does not transfer.

What does transfer is the negative result: **the two-mode preset is nobody's
answer**, and the literature agrees — a mode is a hidden state, and the player
who most needs the simpler view is the one least likely to find the switch. The
context filtering is the strongest positive finding, because it needs no setting.

**Decided.** **A per-table column chooser, controlled from inside the terminal** —
not the Basic / Advanced preset pair the roadmap had planned. Three options were
put to the user: the chooser, automatic context filtering, and the preset pair.

The preset pair was dropped on two grounds. The register above says no reference
mod ships one, and it fights a decision this terminal already took: one view at a
time, so a second axis would give seven tabs two layouts each, of which the Basic
half would be the least used and least tested. Automatic filtering was dropped as
the sole answer because a column that appears and vanishes on its own is harder
to read than one the player put there, though the idea survives as a candidate
for particular columns (Stock on BZ → NPC reads 640 all the way down).

The chooser follows SkyHanni'''s scoreboard  in shape — the
player composes the view, with a Reset — transposed from rows to columns. It is
placed in the terminal rather than in  so the effect is visible while
choosing, and because a player who never opens the config would otherwise never
find it. Skyblocker'''s trick of having the same flag gate the download is noted
but does not apply: our columns are read off one snapshot we already fetch.

**What building it changed.** Reading our own code first, per phase 3, turned up the real
blocker: `DataTable.drawRow` paired cells to columns *by position*, and `BazaarHomeScreen`
already carried a workaround for it on the NPC tabs. Hiding columns would have made that
silent mis-filing the ordinary case on every table, so cells were keyed to their columns
before the chooser was written - which also deleted the workaround. Two further things the
code decided rather than the research: each layout hands the item-name column whatever is
left over, so hiding a column has to *re-solve* that width or the table stops short of the
panel edge; and the column carrying the active sort has to override the player's choice,
since a ranking with no visible column to explain it has no way back.

**Found in game afterwards.** The Status page's progress bar was positioned off the *right*
edge of the name column - correct until that column became the one absorbing whatever the
player hides, at which point the bar walked across the row, away from the item it belongs to.
The general lesson: **anything drawn beside a name belongs off the left edge of its column**,
because the left edge is the one that stays put. The button was also renamed from "Columns"
to "Filters" and its hidden-count badge dropped, both on the user's judgement after using it.

---

## 2026-08-18 - Item icons beside names

**Question.** The terminal names every item in text. Can we draw the item's own
icon beside it, including the ones whose texture comes from Hypixel's own
resource pack (`ItemModel: hypixel_skyblock:item/...`) or from a player head
carrying a base64 texture?

**Where we looked.** Firmament on branch `mc-26.1` - our exact Minecraft version -
`repo/ItemCache.kt`, `util/mc/SkullItemData.kt`, `events/CustomItemModelEvent.kt`
and `features/texturepack/CustomSkyBlockTextures.kt`. SkyHanni on `beta`, starting
from the garden's money-per-hour display the user remembered
(`features/garden/farming/CropMoneyDisplay.kt`) and following it down through
`RenderableCollectionUtils.addItemStack` -> `NeuItems.getItemStack` ->
`api/enoughupdates/EnoughUpdatesManager.neuItemToStack` ->
`utils/ComponentUtils.convertToComponents`. Real item files from the NEU repo
(`ENCHANTED_DIAMOND_BLOCK`, `TUNGSTEN_PLATE`, `HYPERION`,
`SUPERIOR_DRAGON_HELMET`, `ENCHANTED_LAPIS_LAZULI`) to see what the data actually
carries rather than assuming.

**What survived verification.** Both mods set the same two components, in one line
each: `DataComponents.ITEM_MODEL` from the NBT's `ItemModel` string, and
`DataComponents.PROFILE` from `SkullOwner`. Two independent codebases with
different architectures arriving at the identical answer is the strongest signal
available, and it settles the question the research opened with: Hypixel has
shipped an official server resource pack since SkyBlock 0.26, so those textures
are **already in the client** while playing there. Nothing is downloaded, cached
or shipped by us. Skull textures resolve through Minecraft's own skin cache for
the same reason - the earlier plan to fetch textures over the network and cache
them on disk was wrong and is abandoned.

**What did not survive.** Firmament runs the whole 1.8.9 NBT through Mojang's
`DataFixer` and pre-builds all ~8000 items across four threads. Correct for them,
since they reconstruct the full item with stats and lore for a recipe browser. We
draw a 16x16 icon for the ~20 rows on screen, so both are machinery for three
fields. SkyHanni's lighter route - read the fields that matter, map the legacy id
by hand - is the one that fits.

**The real work, which the data revealed rather than the mods.** NEU stores 1.8.9
item ids: `minecraft:skull` for every head, `minecraft:dye` with `damage: 4` for
lapis, `red_flower` plus damage for each flower. SkyHanni carries a hand-written
conversion table for these. Ours will be **derived from the repo itself** - every
distinct `itemid`+`damage` pair the data actually contains - in keeping with how
this project sets thresholds, and covered by a test that resolves each mapping
against Minecraft's item registry, so an id that does not exist fails the build
instead of drawing a plausible-but-wrong icon silently.

**Licences.** SkyHanni is LGPL-2.1 and Firmament GPL-3.0, against our
GPL-3.0-or-later. The approach was studied; no code is taken, and the id table is
rebuilt from the NEU data rather than copied from theirs.

**Decisions taken with the user.** Icons scale to the existing 12px row rather
than growing it, so no tab loses a third of its visible rows - the same tradeoff
SkyHanni takes with its `ITEM_FONT_SIZE`. An item with no resolvable icon draws
**nothing**, leaving the name aligned with its neighbours, rather than a
placeholder that adds noise or a base item that would render several forge
products as identical sheets of paper. Firmament's existence check before using a
model is kept, for the player who declines the server resource pack.

---

## 2026-08-18 - Icons outside SkyBlock

**Question.** Item icons only show their real textures while connected to
Hypixel. Could the client cache them once and draw them anywhere - in
singleplayer, on another server, before joining?

**What the logs and the disk say.** Two different mechanisms are involved, and
they answer differently. Heads already work everywhere: they are drawn from
`PROFILE` with the texture blob out of our own NEU cache, so they owe Hypixel
nothing at runtime. Hypixel's own textures are another matter - the resource pack
mounts as `server/00000000/242681e6-…` in the resource reload, and that `server/`
prefix is the whole story: the client mounts it while connected to that server and
unmounts it on leaving. The **file itself persists** in `run/downloads/`
(17.8 MB, 1087 item definitions and 1351 textures under `assets/hypixel_skyblock/`),
so nothing has to be re-downloaded - it is simply not mounted.

**Prior art.** Firmament registers a resource pack of its own through Fabric's
`ModPackResources` (`repo/RepoModResourcePack.kt`), pointed at its NEU checkout.
The same mechanism could be pointed at Hypixel's downloaded pack, so the approach
exists and is proven. Note what Firmament actually ships that way, though: **its
own repository data**, not the server's pack.

**Decided: leave it as it is.** Four costs, none of them hypothetical:

1. It would redistribute Hypixel's assets from a mod published on Modrinth,
   outside the context Hypixel serves them in. See `docs/API_RESOURCES.md` on how
   carefully this project treats attribution and terms - this is the same class of
   question and deserved the same caution.
2. The path is not stable. `242681e6-…` is a UUID and `8a8e1b34…` a content hash,
   both of which change when Hypixel updates the pack; finding it would mean
   guessing at the newest file in a directory, with no way to be right if two ever
   sat there.
3. A permanently mounted pack applies to the **whole game**, not to our screens.
   Hypixel's textures would appear in singleplayer and on unrelated servers, which
   is a much larger change than the one being asked for.
4. Textures would age silently: a texture Hypixel revised would stay stale in our
   copy until the player reconnected, and nothing would say so.

**What the player gets instead.** Outside SkyBlock the terminal still reads:
prices, names and every head icon are there, since all three come from our own
cache. Hypixel's textures fill in the moment they join. The user chose this after
the trade-offs were laid out.
