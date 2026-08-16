# Research: how to build a Hypixel Skyblock mod in 2026

Research date: August 2026. This document summarizes the technical choices
made for the project and why. If anything below looks outdated, re-verify
with a web search before trusting it (especially dependency version
numbers, which change often).

## 1. Minecraft versioning (important, changed recently)

In early 2026 Mojang replaced the old `1.XX.Y` scheme with a year-based one:
`year.drop.patch`.

- `26.1` = first quarterly "drop" of 2026
- `26.1.2` = second patch/hotfix of the 26.1 drop (released April 9, 2026)
- Test snapshots keep the old weekly format: `26w05a` = snapshot from week 5
  of 2026.
- Applies to both Java Edition and Bedrock Edition.

Sources:
[minecraft.net - new version numbering system](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system),
[Minecraft Wiki - Version formats](https://minecraft.wiki/w/Version_formats),
[Minecraft Wiki - 26.1.2](https://minecraft.wiki/w/Java_Edition_26.1.2)

Relevant technical note: **from 26.1 onward Minecraft's code is no longer
obfuscated**, so there's no longer a need to apply Mojang Mappings to
"de-obfuscate" class names (previously required alongside Yarn/Intermediary
mappings). This simplifies modding work.

## 2. Mod loader: Fabric

Chose **Fabric** over Forge/NeoForge because:

- It's the de facto standard for Hypixel Skyblock mods: SkyHanni, Skyblocker,
  Firmament and the historic NotEnoughUpdates are/were all on Fabric.
- It updates to new Minecraft versions faster than Forge/NeoForge — important
  because Hypixel/Mojang release often.
- On Minecraft 26.2, Fabric has already overtaken Forge in number of
  available mods.
- Note: Fabric and NeoForge are **incompatible with each other**, a mod for
  one won't run on the other without porting.

Sources:
[Fabric for Minecraft 26.1](https://fabricmc.net/2026/03/14/261.html),
[mcreference.com - loaders](https://mcreference.com/loaders)

## 3. The "make the mod last over time" problem: Stonecutter

The project uses **Stonecutter** (https://stonecutter.kikugie.dev/), a
Gradle plugin that lets you keep a **single codebase** that compiles for
multiple Minecraft versions. It works via special comments in the code
(`//? if <condition>`) that get processed before compilation to adapt the
code to the active version.

Why this is the right choice here: you explicitly asked for a mod that's
"easily upgradable to future versions". Without Stonecutter, every new
Minecraft version would require duplicating (or manually rewriting) all the
code. With Stonecutter, you add a version with a few lines of configuration
and only adapt the spots in the code that actually changed between versions
(marked with `//? if` comments).

If NeoForge support alongside Fabric is ever needed, it combines with
**Architectury API** to share common code between different loaders (not
included in this initial scaffold, since it currently only targets Fabric).

Sources:
[Stonecutter docs](https://stonecutter.kikugie.dev/),
[stonecutter-template-fabric (used as the base for this project)](https://github.com/stonecutter-versioning/stonecutter-template-fabric)

## 4. Toolchain used in this project

- **Fabric Loom** — Gradle plugin for the dev environment: downloads
  Minecraft, applies mappings, generates run configurations to launch a
  test client/server.
- **loom-back-compat** — a Loom variant that automatically handles the
  differences between pre/post 26.1 Minecraft versions (obfuscated vs not),
  so the same `build.gradle.kts` works on both.
- **Mixin** — the standard technique for "injecting" code into Minecraft's
  classes without having to decompile and redistribute them modified.
  Included by default in Fabric Loader.
- **Fabric API** — base library with events, rendering hooks, networking,
  etc. In the scaffold, only the modules actually used are downloaded (not
  the whole package) to speed up syncing.

### Kotlin vs Java: final decision

The project uses **Kotlin** for all of the mod's code, except for mixins
(see below). How we got there:

- First phase of the scaffold: started in Java, because the official
  Stonecutter+Fabric template is in Java and was the safest base to validate
  the configuration without risking errors.
- **Both SkyHanni and Firmament — two of the reference mods you'll study to
  see how things are done (see section 5) — are written in Kotlin.** This
  is the deciding factor: being able to read/adapt their code directly,
  sharing the same language reduces friction, especially while just
  starting out with programming.
- Kotlin cuts down on boilerplate compared to Java (no explicit
  `getter`/`setter`, no semicolons, `object` instead of hand-written
  singletons, null-safety built into the language) — less repetitive code
  to write and learn from means fewer chances for mistakes.
- **fabric-language-kotlin** (https://github.com/FabricMC/fabric-language-kotlin)
  is the official Fabric module that lets you write mods in Kotlin: it
  bundles the Kotlin runtime once (shared across all mods that use it, not
  duplicated), so it doesn't add extra weight to game startup compared to
  having multiple Kotlin mods installed together (a common situation, since
  many Hypixel Skyblock mods require it anyway).
- Kotlin is **fully interoperable with Java**: a future class can always be
  written in Java if needed (and mixins *must* stay Java, see below),
  without having to convert everything or nothing.

**Important technical limitation**: **mixins can't be written in Kotlin** —
Mixin's transformer doesn't support them, Java must be used (optionally
calling into `@JvmStatic` functions written in a Kotlin `object`/
`companion object` if logic needs to be shared). This is why in the
scaffold `ExampleMixin` stays a `.java` file in
`src/main/java/dev/syqs/skyquant/mixin/`, while the rest of the code lives
in `src/main/kotlin/`.

Setup used: `org.jetbrains.kotlin.jvm` Gradle plugin, entrypoint in
`fabric.mod.json` with `"adapter": "kotlin"`, `fabric-language-kotlin`
dependency declared both in `build.gradle.kts` and as a `depends` entry in
`fabric.mod.json` (so Fabric Loader warns if it's missing, instead of an
unclear crash at runtime).

Sources:
[Fabric Language Kotlin (GitHub)](https://github.com/FabricMC/fabric-language-kotlin),
[Using Kotlin with Fabric (Fabric Wiki)](https://wiki.fabricmc.net/tutorial:kotlin)

## 5. Reference mods (open source, worth studying)

**Update (repeated research): Skytils is no longer a good reference mod.**
It no longer receives regular updates and in practice doesn't work on
recent Minecraft/Hypixel versions — it was included in the first round of
research because it was historically relevant, but should be dropped as an
example to follow. In its place, the actively maintained mods updated for
Minecraft 26.x as of August 2026 are:

- **SkyHanni** — https://github.com/hannibal002/SkyHanni — Kotlin, Fabric,
  **uses Stonecutter for multi-version support exactly like this project**
  (a good confirmation that the choice made for the scaffold is the right
  one). Covers a huge number of features (farming, combat, mining, events
  like Diana, customizable HUDs). Very active development (~9700 commits,
  active Discord community). LGPL-2.1 license. Probably the single mod with
  the biggest impact in the Skyblock community today: a good first read to
  see how a large Kotlin mod is structured.
- **Skyblocker** — https://github.com/SkyblockerMod/Skyblocker — Java,
  Fabric, updated up to Minecraft 26.2 (so also 26.1.2). Requires both
  Fabric API and Fabric Language Kotlin as dependencies (despite being
  written mainly in Java: useful to see how it interoperates with Kotlin
  libraries). Dungeon ESP, solvers, 300+ quality-of-life features. LGPL-3.0
  license, active community (~300 stars, 60+ contributors, ongoing
  development).
- **Firmament** — https://github.com/FirmamentMC/Firmament — Kotlin,
  Fabric, uses the NEU data repository for items/recipes, modular HUD
  editor (Jarvis), GPL-3.0 license. Written by the developer who used to
  maintain NEU: good for understanding the storage overlay and item/recipe
  data handling.

Worth reading their code to understand common patterns: how they hook into
game events, how they parse Hypixel's scoreboard/tab list, where they get
item data from. Of the three, **SkyHanni is probably the most useful first
read**, since it shares both the language (Kotlin) and the multi-version
approach (Stonecutter) of this project.

Sources:
[SkyHanni (GitHub)](https://github.com/hannibal002/SkyHanni),
[Skyblocker (GitHub)](https://github.com/SkyblockerMod/Skyblocker),
[Firmament (GitHub)](https://github.com/FirmamentMC/Firmament)

## 6. Game data sources

- **Hypixel's public API** (https://api.hypixel.net) — requires a free API
  key, requested in-game with `/api new`. Gives access to
  profile/economy/stats data, BUT **not** real-time information like
  player positions or the state of an ongoing dungeon.
- **NEU data repository** (SkyblockClient/NotEnoughUpdates-REPO on GitHub)
  — a community-maintained, up-to-date database of Skyblock items/recipes,
  used by Firmament and other mods. Avoids having to maintain a database by
  hand.
- **Coflnet** (https://sky.coflnet.com) — price *history* per product, which
  the Hypixel API doesn't provide: it only ever returns the current book.
  This is what the bazaar chart is drawn from, over an hour/day/week window.
- For data not present in the official API (real-time dungeon events,
  etc.), mods parse the scoreboard/tab list/chat client-side — the standard
  approach in this space, but one to handle carefully (see rules below).

**What the mod uses today** (August 2026): the bazaar endpoint of the
Hypixel API, which needs no key and returns every product in one call, plus
Coflnet for history. Both go through `util/HttpJson.kt`; live prices are
shared through `BazaarLivePrices` on a 60-second snapshot, so a screen or an
overlay showing twenty items still costs one request, not twenty.

## 7. Hypixel rules to respect

Source: [support.hypixel.net - Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
(page unreachable via automated fetch at research time, verified via web
search — **re-read it yourself before releasing any feature**, especially
automation-related ones).

Key points gathered:

- **Explicitly allowed**: cosmetic HUD modifications (without adding extra
  information), brightness/gamma adjustment, performance/optimization mods.
- **Banned**: heavily modified clients or ones that inject third-party code
  in a way that compromises the game's security/integrity.
- **General rule**: any modification that gives a **significant advantage**
  is against the rules. There's a fine line to judge case by case here: a
  purely informational overlay (e.g. showing already-visible data in a more
  readable way) is generally accepted in practice by the community (see
  SkyHanni/Skyblocker/Firmament, all actively used by hundreds of thousands
  of players), while automating game actions (macros, solvers that play for
  you) is risky.

**Recommendation**: before implementing any "borderline" feature (especially
automatic dungeon solvers), re-read the official page and check how
SkyHanni/Skyblocker/Firmament behave on the same feature.

## 8. Development environment

**Choice made for this project: VS Code**, not IntelliJ IDEA. Reason:
IntelliJ IDEA + the "Minecraft Development" plugin remains the most common
standard in the community (mixin-specific autocompletion, navigating
Minecraft's decompiled code) and is an option worth keeping in mind if
mixin work gets more complex in the future and VS Code starts feeling
limiting. But since the project is already open and managed from VS Code
here, I preferred not to add a second, heavy IDE to install and learn: for
the current stage (writing Kotlin code, managing Gradle) the installed
extensions are enough:
- **Extension Pack for Java** (vscjava.vscode-java-pack) — Java support,
  debugger, Gradle.
- **Kotlin** (fwcd.kotlin) — Kotlin language support.
- **Gradle for Java** (vscjava.vscode-gradle) — Gradle panel in the sidebar
  to launch tasks (e.g. `runClient`) without a terminal.

- **DevAuth Neo** (https://modrinth.com/mod/dev-auth-neo) — an updated fork
  of the classic DevAuth, the only one compatible with Minecraft 26.1.2 at
  research time (the original DevAuth has no releases for this version).
  Lets you authenticate with a real Microsoft account in the dev
  environment: needed to actually test the mod on Hypixel (without it, you
  can't join online servers). Already downloaded into `run/mods/` and
  enabled via the `-Ddevauth.enabled=1` JVM flag configured in
  `build.gradle.kts`. On the test client's first launch, follow the
  instructions that appear in the terminal/log to complete the login; the
  credentials then stay saved in `run/microsoft_accounts.json` (a folder
  already excluded from git).
- **Java 25** required to compile/run on Minecraft 26.1.x (already present
  on the system).

## 9. Distribution

- **Modrinth** and **CurseForge** are the standard platforms for
  publishing Fabric mods. There's no need to obfuscate the jar (Fabric mods
  are legal and don't violate the Minecraft EULA as such; the only risk is
  around respecting Hypixel's server rules, see point 7).
- The Stonecutter template used already includes the hooks to publish via
  `mod-publish-plugin` to Modrinth/CurseForge when you're ready (currently
  disabled in the scaffold, enable them when needed).
