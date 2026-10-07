# Block Log

Server-side Fabric mod for Minecraft 26.3. It writes every block change to a SQLite file in the world folder, so you can search what happened and roll it back.

Mod id `block-log`, version 1.0.0. Needs Fabric Loader 0.19.5 or newer, Fabric API, and Java 25 or newer. The mod is server-only (`"environment": "server"`), so players can join without installing it. License is CC0-1.0. Author is VWS Digital.

`main` matches Minecraft 26.3. Older releases live on `mc/26.1`, `mc/26.1.1`, `mc/26.1.2`, and `mc/26.2`.

## What gets logged

Anything that goes through `Level#setBlock` in a server world is recorded right after the chunk changes. Each row stores the dimension id, x, y, z, the full old block state, the full new block state, the player UUID, the player name, and the time in epoch milliseconds. A state looks like `minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]`. The full old state is what lets rollback put back blocks a player cannot normally place, such as bedrock.

Who it gets blamed on:

- Breaking a block, using an item on a block (placing, flint and steel, and similar), or using an item (buckets) is the player.
- Explosions are the player who caused them when that player is known (for example, they lit the TNT). Otherwise the source is `#explosion`.
- Everything else is `#world`. To keep the log smaller, non-player changes are stored only when the block type changes, and air-to-fluid flow is skipped. Crop growth, redstone power changes, and water spreading are skipped. Set `logNonPlayerChanges=false` if you only want player and explosion changes.
- `/blog rollback` writes its own changes as `#rollback`.

## Database

The file is `<world folder>/block-log/blocklog.sqlite`. SQLite runs in WAL mode, so you will also see `-wal` and `-shm` files while the server is up. The SQLite JDBC driver (`org.xerial:sqlite-jdbc`) is bundled in the mod jar.

The server thread does not touch the database. Each change goes onto a bounded in-memory queue and the game event returns. A `BlockLog-Writer` thread drains that queue in batches of up to 1,000 rows per transaction.

The queue cap defaults to 20,000. If it fills up, the server thread waits until the writer frees a slot, and an error is logged every 5 seconds. The queue cannot grow past the cap. If the writer thread has died, changes are dropped with an error so the server does not freeze. A batch that fails to write is kept and retried every 2 seconds.

On shutdown, after the worlds are saved, the mod flushes until the queue is empty. There is no time limit. Then it checkpoints the WAL and closes the database.

Config lives in `config/block-log.properties`: `queueCapacity`, `batchSize`, and `logNonPlayerChanges`. Restart the server to apply changes.

## Commands

Every `/blog` command needs permission level 2 (gamemaster).

| Command | What it does |
|---|---|
| `/blog status` | Queue size and cap, last successful write, rows written this session, last DB error, DB path |
| `/blog flush` | Waits off the server thread until the queue is empty, then reports |
| `/blog search [params]` | Count plus the newest matching changes (default 15, `limit:` up to 100) |
| `/blog rollback <params>` | Puts the old blocks back for every matching change. Requires `after:` |
| `/blog inspect` | History of the block at your feet. Air counts. |
| `/blog inspect <x y z>` | History of any block. `~ ~ ~` and `~ ~-1 ~` work. |

Params can be in any order. All of them are optional except `after:` on rollback:

- `source:<name>` is a player name (case-insensitive), or `#world`, `#explosion`, `#rollback`
- `after:<time>` and `before:<time>` take `30s`, `15m`, `2h`, `3d`, `1w`, `1d12h` (that long ago), `2026-10-05T14:30`, `2026-10-05` (server local time), or epoch milliseconds
- `range:<blocks>` is a cube of plus or minus N blocks around you, from 0 to 512
- `world:<id>` defaults to the dimension you are in. Examples: `world:the_nether`, `world:all`. Range is ignored for other worlds.
- `limit:<n>` is how many search or inspect rows to show

Search, inspect, and rollback flush the queue first, so a change from a moment ago is included.

### Rollback

Matching rows are read oldest first. For each position, only the oldest matching change is used, and the block is set back to that change's old state. A later change, such as someone placing air or water afterwards, does not replace that restore.

Blocks are restored 4,096 per tick so the server does not freeze. You get a message when it finishes.

Example: `/blog rollback source:Griefer123 after:6h range:64`

Block-entity contents (chest items, sign text) are not stored, so a restored block comes back empty. Entities are not logged. Changes from other mods that skip `Level#setBlock` are not seen.
