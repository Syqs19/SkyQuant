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
