# Block Log (Fabric, server-side) — Minecraft 26.3

Logs every block change to a local SQLite database so griefing can be searched and rolled back — including blocks
players cannot normally place back, such as bedrock and netherrack, because the **full old block state** is stored.

- Mod id: `block-log` · Version 1.0.0 · Minecraft 26.3 · Fabric Loader ≥ 0.19.5 · Fabric API · Java 25+
- Server-only (`"environment": "server"`); clients don't need it.
- License: CC0-1.0. Author: VWS Digital.

## What is recorded

Every change that goes through `Level#setBlock` in a server world is recorded **right after** the chunk was changed:
world (dimension id), x, y, z, old block state, new block state (full state strings, e.g.
`minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]`), player UUID, player name, and time (epoch ms).

Attribution:
- Breaking blocks, using an item on a block (placing, flint & steel, …) and using items (buckets) → the player.
- Explosions → the player who caused them (e.g. lit the TNT) if known, otherwise `#explosion`.
- Everything else → `#world`. To keep volume sane, non-player changes are only logged when the **block type** changes
  and it isn't just air↔fluid flow (crop growth, redstone power, water spreading are skipped).
  Set `logNonPlayerChanges=false` to log player/explosion changes only.
- Changes made by `/blog rollback` are logged as `#rollback`.

## Database & write path (why this doesn't repeat Ledger's 78k-in-memory failure)

- DB file: `<world folder>/block-log/blocklog.sqlite` (WAL mode, plus `-wal`/`-shm` files while running).
- The SQLite JDBC driver (`org.xerial:sqlite-jdbc`) is bundled inside the mod jar.
- The server thread **never** touches the DB: each change is pushed onto a **bounded** in-memory queue and the event returns.
- A dedicated `BlockLog-Writer` thread drains the queue in batches of up to **1,000** rows per transaction.
- Queue cap (default **20,000**): if it is ever full, the server thread **blocks** until the writer frees room and an
  ERROR is logged every 5 s. Memory can never grow past the cap. (If the writer thread itself died, changes are dropped with an
  error instead of freezing the server forever.) A failing batch is kept and retried every 2 s.
- On server shutdown (after the worlds are saved) the mod **flushes until the queue is empty — no time limit** — then
  checkpoints the WAL and closes the DB.

Config: `config/block-log.properties` (`queueCapacity`, `batchSize`, `logNonPlayerChanges`). Restart to apply.

## Commands (all op-only — permission level 2 / gamemaster)

| Command | What it does |
|---|---|
| `/blog status` | Queue size / cap, last successful write time, rows written this session, last DB error, DB path |
| `/blog flush` | Waits (off the server thread) until the queue is 0, then reports |
| `/blog search [params]` | Count + newest matching changes (default 15, `limit:` up to 100) |
| `/blog rollback <params>` | Puts the OLD blocks back for every matching change (requires `after:`) |
| `/blog inspect` | History of the block at your feet (air works) |
| `/blog inspect <x y z>` | History of any block; `~ ~ ~`, `~ ~-1 ~` etc. work |

Params (any order, all optional except `after:` for rollback):
- `source:<name>` — player name (case-insensitive) or `#world`, `#explosion`, `#rollback`
- `after:<time>` / `before:<time>` — `30s`, `15m`, `2h`, `3d`, `1w`, `1d12h` (= that long ago), `2026-10-05T14:30`, `2026-10-05` (server local time), or epoch ms
- `range:<blocks>` — cube of ±N blocks around you (0–512)
- `world:<id>` — default is the dimension you're in; e.g. `world:the_nether`, `world:all` (range is ignored for other worlds)
- `limit:<n>` — search/inspect rows shown

Every search/inspect/rollback first waits for the queue to flush, so results include changes made a moment ago.

### Rollback semantics
Matching rows are read **oldest first**. For each position, only the **oldest** matching change is used and the block is set
back to that change's *old* state — so a later change (e.g. the griefer placing air/water afterwards) can't win.
Blocks are restored 4,096 per tick to avoid a freeze; you get a message when it's done.
Example: `/blog rollback source:Griefer123 after:6h range:64`

Limitations: block-entity contents (chest items, sign text) are not stored or restored — the block comes back empty.
Entities are not logged. Changes made directly by other mods that bypass `Level#setBlock` are not seen.
