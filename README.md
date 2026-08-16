# SkyQuant

Client-side mod for Hypixel Skyblock. Built on **Fabric** + **Stonecutter**
(see [RESEARCH.md](docs/RESEARCH.md) for the reasoning behind the technical choices).

- Target Minecraft version: **26.1.2**
- Mod loader: **Fabric**
- Language: **Kotlin** (no mixins: the mod hooks in through Fabric API events and a
  read-only access widener, see `src/main/resources/skyquant.ct`)
- License: **GPL-3.0-or-later**

## Quick setup

Already set up in this project: Java 25 (detected on the system), VS Code
extensions (Extension Pack for Java, Kotlin, Gradle for Java) and the
**DevAuth Neo** mod for Microsoft login in the test client (in `run/mods/`).
See `docs/RESEARCH.md` section 8 for details.

1. Open this folder in VS Code (if it isn't already open).
2. Wait for the Gradle extension to finish its first sync: it downloads
   Minecraft, mappings and dependencies — can take a few minutes the
   first time, needs a stable internet connection.
3. To launch a test client: **Gradle** panel in the VS Code sidebar ->
   `SkyQuant` -> `Tasks` -> `fabric` -> `runClient`
   (or from a terminal: `./gradlew runClient`).
4. On the client's first launch, DevAuth will guide you through the
   terminal/log to sign in with a real Microsoft account (needed to join
   Hypixel). Once logged in, the credentials stay saved in
   `run/microsoft_accounts.json` and you won't need to do it again every time.

## Build

```
./gradlew buildAndCollect
```

The final jar ends up in `build/libs/<mod version>/`.

## Project documents

- [RESEARCH.md](docs/RESEARCH.md) — research on tech stack, tools, reference
  mods and Hypixel rules.
- [PROJECT_MAP.md](PROJECT_MAP.md) — map of where everything lives in the
  project.

## Data sources and credits

SkyQuant displays public market data. It does not automate any game action.

- Live bazaar and item data — [Hypixel API](https://api.hypixel.net) (no API key required,
  and none is ever asked of the player)
- Price history and auction listings — [SkyCofl](https://sky.coflnet.com/data)
- Recipes and NPC shop data — [NotEnoughUpdates repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) (MIT)
- Bundles [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig) (LGPL-3.0)

## What the mod does and does not do

Stated plainly because it is what a reviewer, and Hypixel's rules, will want to know:

- **Does**: fetch public market data from public APIs, and draw it. Reads the *open* menu's
  tooltips for two figures no API carries — the player's bazaar tax and an NPC shop's remaining
  stock — both of which are already on screen when it reads them.
- **Does not**: automate any action, place or cancel orders, click, move, or play for the player.
  No solvers, no macros, no ESP, no reading of state the player cannot already see.

Every figure it shows is obtainable by anyone with a browser at
[sky.coflnet.com](https://sky.coflnet.com/data) or Hypixel's own public API, so it is not an
advantage that depends on having the mod.

Attribution is a condition of use for SkyCofl and NEU, not a courtesy — see
[docs/API_RESOURCES.md](docs/API_RESOURCES.md) for what each source requires and how it is met.
The credit lines shown in-game come from `DataCredits`; don't remove them.

**Not an official Minecraft product. Not affiliated with or endorsed by Hypixel or Mojang.**

## To do before the first public release

Checked 16 August 2026. Everything not listed here has been verified — see
[API_RESOURCES.md](docs/API_RESOURCES.md) for the terms each data source imposes and how each
one is met.

- [x] Fabric API is on the latest build for 26.1.2 (`0.155.2+26.1.2`, confirmed against Modrinth)
- [x] `skyquant` is free as a Modrinth slug (no project, no similar name)
- [x] Repository linked: [Syqs19/SkyQuant](https://github.com/Syqs19/SkyQuant) — public, issues
      enabled, verified reachable
- [ ] **Add the `LICENSE` file to the GitHub repository.** It exists in this working copy but the
      repo reports no licence, so GitHub shows the code as all-rights-reserved — which contradicts
      the GPL-3.0-or-later declared in `fabric.mod.json` and on the Modrinth page. Modrinth
      requires metadata consistent with what is found elsewhere
- [ ] Point `homepage` at the Modrinth page once it exists (it currently points at the repo, since
      a link that 404s is worse than one that merely duplicates `sources`)
- [ ] Re-read the official Hypixel rules on allowed modifications —
      [support.hypixel.net](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
      (the page blocks automated fetching, so this one has to be read by hand)
