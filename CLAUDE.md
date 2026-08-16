# CLAUDE.md — SkyQuant

Guida per Claude Code su questo progetto. Per sapere *dove sta cosa* (build, test, struttura),
leggi `PROJECT_MAP.md`: qui non lo duplichiamo.

## Ambiente

| | |
|---|---|
| Minecraft | **26.1.2** (unica versione target, vedi `settings.gradle.kts`) |
| Loader | Fabric Loader `0.19.3` |
| Fabric API | `0.155.2+26.1.2` — build fissata al **patch esatto**, mai al generico `+26.1` |
| Java | 25 (Temurin) |
| Linguaggio | Kotlin `2.4.10`; i mixin restano in Java |
| Multi-versione | Stonecutter — i valori per versione stanno in `stonecutter.properties.toml` |

Il progetto **non è un repository git**. Niente commit, niente branch: se serve versionare,
chiedi prima.

## MCP disponibili

Configurati in `.mcp.json` (scope progetto). Al primo avvio interattivo di Claude Code vanno
approvati una volta.

### `mcmodding` — documentazione Fabric/NeoForge
Server stdio (`mcmodding-mcp`), indicizza wiki.fabricmc.net, docs.fabricmc.net e
docs.neoforged.net.

**Usalo per:** concetti Fabric — entrypoint, mixin, eventi, networking, cicli di rendering,
convenzioni di modding.

**Limite da tenere a mente:** l'indice è tarato su **1.21.x**, non su 26.1.2. Dà il *concetto*
giusto, non la firma esatta del metodo. Non copiare mai una signature da qui e darla per buona
su 26.1.2 — vedi la regola sotto.

### `minecraft-java` — mondo (porta 8765)
### `minecraft-java-client` — ispezione client (porta 8766)
Entrambi serviti dal mod `minecraft-fabric-mcp-1.1.0+26.1.2.jar` in `run/mods/`. Il mod ospita
un server HTTP **dentro** Minecraft: in singleplayer un solo processo serve entrambe le porte.

**Usali per:** verificare il comportamento reale a gioco avviato. Il client (8766) è quello che
conta qui — `view_capture` restituisce uno screenshot in prima persona, utile per controllare
GUI, HUD e overlay invece di dedurli dal codice.

**Non sono disponibili se Minecraft non è in esecuzione.** Un errore di connessione su 8765/8766
significa "gioco spento", non "codice rotto": avvia il gioco prima di concludere qualsiasi cosa.

### Avviare il gioco
```
./gradlew :26.1.2:runClient
```
Il jar del mod MCP è già in `run/mods/` e si carica da solo. È pinnato a 26.1.2 e **rifiuta di
caricarsi** su un'altra versione — se non parte, il primo sospetto è un cambio di versione.

## Regole

**Verifica sempre le API sulla versione esatta.** Mojang cambia firme interne anche tra patch
dello stesso drop — è già successo su questo progetto (crash del 2026-08-13, vedi il commento in
`stonecutter.properties.toml`). Quando `mcmodding` ti dà un'API, confermala contro le sorgenti
decompilate o i mapping di 26.1.2 prima di usarla. Una risposta plausibile ma tarata su 1.21 è
il modo più veloce per introdurre un bug che compila.

**Una feature è completa solo dopo il test a gioco avviato.** L'ordine:

1. Analizza il codice esistente.
2. Consulta l'MCP di documentazione appropriato.
3. Verifica che l'API sia valida su 26.1.2.
4. Implementa.
5. `./gradlew build` — deve passare.
6. `./gradlew test` — deve passare (vedi `PROJECT_MAP.md` per cosa coprono).
7. Avvia il client e verifica il comportamento reale via `minecraft-java-client`.
8. Se qualcosa non torna, analizza e correggi.
9. Ricompila e ritesta.
10. Solo ora la feature è finita.

Compilare non è testare. Su questo progetto ci sono già stati "fix" dichiarati funzionanti tre
volte di fila senza che nessuno avesse mai guardato il gioco — e la suite verde era la prova di
nulla se non delle proprie assunzioni.

**Quando una feature è silenziosamente assente, falle dire perché.** Non tirare a indovinare due
volte sullo stesso sintomo: aggiungi una diagnostica che nomini il controllo che ha rifiutato,
come fa `BazaarGraphButton.diagnose()`.
