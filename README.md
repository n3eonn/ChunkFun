# ChunkFun

A survival minigame plugin for **Paper 26.2**. Players survive on a single chunk floating in the void, and the
environment rotates between Overworld, Nether, End and an Ancient City every 8 minutes.

- Paper API `26.2.build.135-stable`
- Java 25 (Minecraft 26.x requires it)
- Gradle 9.8.1 (wrapper included)
- No dependencies besides Paper

## Build

```bash
./gradlew build          # Windows: gradlew.bat build
```

The plugin jar is written to `build/libs/ChunkFun-1.0.0.jar`.

## Install

1. Copy `ChunkFun-1.0.0.jar` into the server's `plugins/` folder (Paper 26.2, Java 25).
2. Start the server once. `plugins/ChunkFun/config.yml` is created.
3. Run `/chunkfun start` as an operator, or set `game.auto-start: true`.

The main server world acts as the lobby. ChunkFun never touches it. All game worlds are temporary and are
stored under `world/dimensions/chunkfun/`.

## Commands and permissions

| Command | Permission | Default |
|---|---|---|
| `/chunkfun start` | `chunkfun.command.start` | op |
| `/chunkfun stop` | `chunkfun.command.stop` | op |
| `/chunkfun next` | `chunkfun.command.next` | op |
| `/chunkfun event [tnt_rain|sheep_adventure|flood|meteor_attack|lightning_storm|anvil_rain|low_gravity]` |\|sheep_adventure\|flood\|meteor_attack]` | `chunkfun.command.event` | op |
| `/chunkfun supply [legendary\|rare\|common]` (drop lucky blocks) | `chunkfun.command.supply` | op |
| `/chunkfun mob` | `chunkfun.command.mob` | op |
| `/chunkfun info` | `chunkfun.command.info` | everyone |
| `/chunkfun reload` | `chunkfun.command.reload` | op |

`chunkfun.admin` grants every node. The command has the alias `/cf` and tab completion.

## How it works

### Worlds and generation
Every environment is a fresh world (`chunkfun:<variant>_<id>`) that uses `MaskedVanillaGenerator`. Paper asks
a plugin generator, chunk by chunk, whether to run each vanilla stage: noise, surface, caves, decorations,
initial mobs and structure starts. The generator answers *yes* only for the playable chunk(s), so that chunk
gets real vanilla terrain (ores, deepslate, caves, aquifers, trees, lava lakes, structure pieces) and every
other chunk is empty void. Structure starts stay enabled everywhere. They are pure bookkeeping, so structures
centred next to the chunk still extend into it, and structures can be located before any terrain exists.

The playable chunk is chosen **before** anything is generated:

- **Overworld**: the nearest preferred biome (forest, birch, taiga and similar), found by sampling the biome
  source. If the chunk generates without a tree, a vanilla tree is grown there.
- **Nether**: a located bastion remnant or nether fortress. The playable chunk is the one with the largest
  structure-piece volume. If vanilla did not place any ancient debris, quartz or lava in the chunk, a small
  embedded vein or a lava spring is added.
- **End (75%)**: an End City with an End Ship. The ship piece is recognised by the size of the vanilla
  `end_city/ship` template. The ship's chunks are generated, and the chunk holding the **elytra item frame**
  becomes the playable chunk. The other ship chunks are cleared back to void.
- **End island (25%)**: the complete main island with obsidian pillars, End crystals and the vanilla dragon
  fight (26.2 creates a real `EnderDragonFight` for every world with the End dimension type). It stays active
  for 12 minutes.
- **Ancient City**: a located city in the deep dark; the densest chunk (usually the centre) is used. Spawns,
  mobs, supplies and hazards are placed inside the city (below `ancient-city.scan-ceiling-y`), never on top of
  sculk shriekers or sensors.

By default there is **no world border**: players can fly out of the chunk and build in the void around it.
Portals never lead out of an environment, and fluids never pour from the area into the void. With
`game.world-border: true` the area is closed again (border plus block, piston and teleport limits).

### Rotation
The next environment is generated 60 seconds ahead (`rotation.prepare-ahead-seconds`) on Paper's worldgen
threads, then verified (safe spawn, border, chunk tickets). The 5-second title countdown only moves players
once that environment is ready. If it is still generating, players see "Preparing...". If generation fails 3
times, the rotation is postponed. After the switch, the old world is evacuated, unloaded **without saving** and
deleted. The next visit generates a new world from the same configuration, so resources can be farmed again.

Where players arrive:

- **Overworld / End**: on top of the surface, never in a cave. The chunk is only chosen if it lies completely in a
  preferred land biome, so it is not cut by a river or ocean.
- **Nether**: on a cavern floor above the lava sea with at least 5 blocks of headroom, never on or right below the
  bedrock roof.
- **Ancient City**: inside the city, never on sculk shriekers or sensors.
- If no natural spot exists, a small platform is built on the very top (on water: as a raft).

Joining, disconnecting and dying are handled during every phase:

- Joining players spawn directly into the active environment (via `AsyncPlayerSpawnLocationEvent`).
- Respawns go to the active environment unless the bed or anchor is in it.
- Dead players in a retiring world are respawned into the new one.

Inventories, armour, XP and ender chests are untouched (a plain teleport; open inventories are closed first so
no container stays bound to a deleted world). Players get 3 seconds of damage immunity after arriving (the void
still kills).

### Events
- **Mob events** (every 60 s): one weighted mob type and one weighted amount. Spawns go on safe surface spots
  with enough head room for that mob (Wither 4 blocks, Warden 3). If there are fewer spots than mobs, spots are
  shared; if there are none, the verified player spawn is used. Mobs found inside a block one tick later are
  moved.
- **Special events** (every 2 min, 60 s each): start title, boss bar with countdown, and the Ender Dragon roar.
  - *TNT Rain*: one normal primed TNT per second above a random column (it stays below ceilings in the Nether).
  - *Sheep Adventure*: 10 red, glowing, enlarged, named rams. A custom Paper `Goal` replaces all vanilla sheep AI:
    the sheep path to the nearest survival player and ram for damage plus strong knockback.
  - *The Flood*: water rises layer by layer, replacing **only air**, then recedes. Every placed block is recorded
    and turned back into air if it still holds water. Flow is suppressed while the | `/chunkfun event [tnt_rain|sheep_adventure|flood|meteor_attack|lightning_storm|anvil_rain|low_gravity]` | is active. The work is
    budgeted per tick.
  - *Meteor Attack*: block-display meteors with particle trails strike random spots: an explosion, fire,
    magma crater and sometimes lava. There is no physics entity, so meteors cannot be deflected out of the
    area. At most 4 are in the air at once.
  - *Lightning Storm*: real lightning strikes random spots (damage, fire, charged creepers).
  - *Anvil Rain*: falling anvils that hurt whoever they hit; landed anvils stay as loot.
  - *Low Gravity*: everyone gets strong Jump Boost and Slow Falling, removed when the event ends.
- **Lucky blocks** (random 30-150 s, independent of special events): 10 lucky blocks land on safe surface spots.
  A lucky block is a raw gold block (`supply.lucky-block`); raw gold blocks never generate naturally, and only
  blocks placed by a drop are lucky (they are tracked by position). Each has an animated, colour-cycling
  "? LUCKY BLOCK ?" hologram and a sparkle aura. Explosions and pistons cannot destroy or move them.
  Breaking one rolls an outcome (`supply.outcomes`):
  - **GOOD (60%)**: loot of a random category and tier (`supply.tiers`, default 15% Legendary / 35% Rare /
    50% Common). The loot is nerfed: about half of the category's set (food: 4 stacks), with halved stacks. The
    elytra category always contains its elytra.
  - **NEUTRAL (15%)**: an XP shower, a firework show, a 30 s buff or a cake party.
  - **BAD (25%)**: TNT, an ambush of 3 mobs, lightning, a falling anvil, silverfish, levitation or blindness.

  | Category | Legendary set | Rare set | Common set |
  |---|---|---|---|
  | Armor | netherite, Protection IV + extras | diamond, Protection II, Unbreaking II | iron |
  | Tools | netherite, best enchantments | diamond, Efficiency/Sharpness III, Unbreaking II | iron |
  | Elytra | elytra, 2x64 rockets, 4 totems | elytra, 32 rockets, 1 totem | worn elytra (~15%), 8 rockets |
  | Food | full stack of every food | 16 of every food | bread, potatoes, carrots, apples, beef, chicken |
  | Crystals | 64 each | 16 crystals, 32 obsidian, 8 anchors, 32 glowstone | 4 / 8 / 2 / 8 |

  Enchantments are validated against each item when built.

### Visual effects
- Every player in the game **glows**; the outline colour (`effects.glow-color`) comes from a scoreboard team.
- Arrival in a new environment: totem particles, a ring of light and a firework above each player.
- Countdown: a shrinking particle ring around each player (portal, then soul fire for the last 3 seconds).
- Special events: a firework and sparks per player; mob events: soul and smoke particles where mobs appear.
- Ambient particles per environment, sent only to the player they surround: spore blossoms (Overworld), ash
  and crimson spores (Nether), portal sparkles (End), sculk souls (Ancient City).
- All effect fireworks are tagged and never deal damage. Every effect can be switched off under `effects`.

### Performance
ChunkFun registers no `PlayerMoveEvent` listener. While moving, the server's only extra work is loading the
void chunks around players. Once an environment is prepared, structure starts are only computed within 8
chunks of the playable area, so flying through the void does not make the world-generation threads evaluate
villages or cities for every empty chunk.

### Scoreboard
One sidebar per player with one objective that is created once. Lines are fixed score entries whose
*custom names* change only when their text changes, so there is no flicker and objectives are never
re-created. It shows: player, environment, total playtime (vanilla statistic), next mob event, next special
event (or the running one) and next chunk.

## Known limitations and design decisions

- **Food loot**: the stackable foods (36 in 26.2) are read from the item registry at runtime; a lucky block hands
  out 4 of them.
- **End chunk**: an End City plus ship is much larger than 16x16 blocks, so the End chunk is a *part* of a city: the
  chunk with the ship's elytra. The elytra is therefore normally guaranteed. If no city with a ship is found within
  `end.city-search-attempts`, a city chunk without a ship is used (logged).
- **Nether structures**: a bastion or fortress also spans several chunks; the densest chunk is used.
- **Main End island**: the area is the island's full size (21x21 chunks by default), not one chunk. Mobs,
  supplies, TNT and meteors are placed near players (`game.large-area-focus-radius`). The | `/chunkfun event [tnt_rain|sheep_adventure|flood|meteor_attack|lightning_storm|anvil_rain|low_gravity]` | floods the
  surroundings of players, with depth limited by `| `/chunkfun event [tnt_rain|sheep_adventure|flood|meteor_attack|lightning_storm|anvil_rain|low_gravity]` |.max-blocks`.
- **Structure lookup** runs once per prepared environment on the main thread. It loads the structure's start
  chunk to the `STRUCTURE_REFERENCES` status (no terrain, typically well under a second) during preparation, not
  during the countdown. World creation itself is also synchronous (a Paper requirement).
- **Mob events at Peaceful difficulty**: hostile mobs are removed by vanilla. Game worlds copy the lobby's
  difficulty.
- **Leftover worlds** after a crash are deleted on the next start (tracked in `plugins/ChunkFun/managed-worlds.txt`).
  Deletion only ever touches folders below the world container that contain a `chunkfun` path segment and are
  not used by a loaded world.
- World-management plugins (Multiverse and similar) may notice the temporary worlds being created; configure
  them to ignore worlds starting with `chunkfun_`.
