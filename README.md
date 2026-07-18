# BlueArcade - Lucky Pillars

This resource is a **BlueArcade 3 module** and requires the core plugin to run.
Get BlueArcade 3 here: https://store.blueva.net/resources/resource/1-blue-arcade/

## Description
Spawn on bedrock pillars, receive random blocks/items, and be the last player or team standing.

## Game type notes
This is a **Minigame**: it is designed for standalone arenas, but it can also be used inside party rotations. Minigames usually provide longer, feature-rich rounds.

## What you get with BlueArcade 3 + this module
- Party system (lobbies, queues, and shared party flow).
- Store-ready menu integration and vote menus.
- Victory effects and end-game celebrations.
- Scoreboards, timers, and game lifecycle management.
- Player stats tracking and placeholders.
- XP system, leaderboards, and achievements.
- Arena management tools and setup commands.

## Features
- Team size and team count configuration.
- Single-category modifier voting with 10 modifier options.
- Random block/item distribution during the match.
- Spawn cages with cosmetic support.
- Optional regeneration region for arena cleanup.
- PvP elimination flow with stats and achievements.

## Arena setup
### Common steps
Use these steps to register the arena and attach the module:

- `/baa create [id] <standalone|party>` — Create a new arena in standalone or party mode.
- `/baa arena [id] setname [name]` — Give the arena a friendly display name.
- `/baa arena [id] setlobby` — Set the lobby spawn for the arena.
- `/baa arena [id] minplayers [amount]` — Define the minimum players required to start.
- `/baa arena [id] maxplayers [amount]` — Define the maximum players allowed.
- `/baa game [arena_id] add [minigame]` — Attach this minigame module to the arena.
- `/baa stick` — Get the setup tool to select regions.
- `/baa game [arena_id] [minigame] bounds set` — Save the game bounds for this arena.
- ~~`/baa game [arena_id] [minigame] spawn add`~~ — Not used in Lucky Pillars.
  Use **`/baa game [arena_id] lucky_pillars team spawn add`** and **`/baa game [arena_id] lucky_pillars team spawn set <team_id>`** to configure team spawns.
- `/baa game [arena_id] [minigame] time [minutes]` — Set the match duration.

### Module-specific steps
Finish the setup with the commands below:
- `/baa game [arena_id] lucky_pillars team count <value>` — Set the number of teams.
- `/baa game [arena_id] lucky_pillars team size <value>` — Set the players per team.
- `/baa game [arena_id] lucky_pillars team spawn add` — Add a spawn point to the next free slot (run once per team).
- `/baa game [arena_id] lucky_pillars team spawn set <team_id>` — Overwrite the spawn point for a specific team (1, 2, ..., N).
- `/baa game [arena_id] lucky_pillars team spawn remove <team_id>` — Remove the spawn point for a specific team (1, 2, ..., N).
- `/baa game [arena_id] lucky_pillars region set` — Select and save the regeneration region.

## Vote permissions
Lucky Pillars only supports **modifier voting**. Use the following format:

`bluearcade.lucky_pillars.votes.<modifier>`

### Available modifier permissions
- `bluearcade.lucky_pillars.votes.none`
- `bluearcade.lucky_pillars.votes.elytra`
- `bluearcade.lucky_pillars.votes.swap`
- `bluearcade.lucky_pillars.votes.speed`
- `bluearcade.lucky_pillars.votes.slow_fall`
- `bluearcade.lucky_pillars.votes.invisibility`
- `bluearcade.lucky_pillars.votes.double_health`
- `bluearcade.lucky_pillars.votes.one_heart`
- `bluearcade.lucky_pillars.votes.unbreakable`
- `bluearcade.lucky_pillars.votes.ultra_jump`
- `bluearcade.lucky_pillars.votes.rising_lava`

### Global wildcard
- `bluearcade.lucky_pillars.votes.*`

## Technical details
- **Minigame ID:** `lucky_pillars`
- **Module Type:** `MINIGAME`

## Links & Support
- Website: https://www.blueva.net
- Documentation: https://docs.blueva.net/books/blue-arcade
- Support: https://discord.com/invite/CRFJ32NdcK
