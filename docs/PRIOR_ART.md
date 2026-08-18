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
