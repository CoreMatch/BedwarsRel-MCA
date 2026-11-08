# 0004. Room Assignment Integration Spec (MCA Room Layer) — Implemented

## Status
implemented (2026-11-08; written per HA-Contract 1.10.0 and MCA room
reservations, MatchCoreArena-Server knowledge 0019). The implementation
lives in `common/src/main/java/io/github/bedwarsrel/mca/RoomAssignmentService.java`
(join routing, bungee mode only),
`common/src/main/java/io/github/bedwarsrel/mca/ReportTransport.java::get`
(GET capability), the `matchmaking_group_id` round trip in
`common/src/main/java/io/github/bedwarsrel/mca/MatchSummary.java::getMatchmakingGroupId`
/ `common/src/main/java/io/github/bedwarsrel/mca/MatchReportPayload.java::toJson`,
and the bungee join hook in
`common/src/main/java/io/github/bedwarsrel/listener/PlayerListener.java::onJoin`.
This document keeps the decision record; where prose below says "must be
added", it has been added.

## Context
Delivery is layered: MCA forms matches and reserves a room per formed
group; the Velocity proxy (MatchCoreArena-VelocityGate) routes the
player to the bound sub-server; THIS plugin must then place the player
into the concrete arena (room) inside the sub-server and later echo the
group id back in the match report so MCA can release the reservation.

## Decision (specification for the future implementation)

### 1. Query MCA after the player lands on the sub-server
- Trigger: player join on this sub-server (the proxy only sends players
  here when MCA reported a valid assignment).
- Call `GET /api/matchmaking/assignment/{uuid}` (HA-Contract 1.10.0)
  with an OPS credential — reuse the `match-reporting` credential block
  (`MatchReportConfig` service token / client_credentials,
  `mca/TokenProvider.java`).
- Threading: HTTP MUST run off the main thread. Existing pattern:
  `mca/MatchReportService.java` daemon worker + bounded queue; transport
  is `mca/UrlConnectionTransport.java` implementing
  `mca/ReportTransport.java` — note `ReportTransport` is POST-only
  today, a GET capability must be added. Result handling MUST return to
  the main thread via `BukkitRunnable` (existing scheduling examples:
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::playerJoins` uses `runTaskLater` at L1380/L1405,
  `GameLobbyCountdown` is a `runTaskTimer` at `game/Game.java` L1346).
- Query failure or timeout: degrade to "no assignment" — send the
  player back to the proxy lobby with one readable message; never block
  login, never kick without a message.

### 2. Entering the room
- The MCA `room` value is a BedwarsRel GAME NAME: resolution is
  `BedwarsRel.getInstance().getGameManager().getGame(room)`
  (`common/src/main/java/io/github/bedwarsrel/game/GameManager.java::getGame` L51, matches `Game.getName()`,
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::getName` L214 — NOT the region name). MCA's static
  inventory must use exactly these names.
- Join entry point: `common/src/main/java/io/github/bedwarsrel/game/Game.java::playerJoins` (L1357) — the
  same path `/bw join <game>` uses (`commands/JoinGameCommand.java`
  L21-69, permission level "base").
- Preconditions enforced by existing code (the spec relies on them, does
  not re-implement them):
  - `GameState` (`game/GameState.java`: RUNNING/WAITING/STOPPED):
    STOPPED rejects; RUNNING rejects unless spectating is enabled, then
    joins as spectator (`Game.java` L1359-1369, L1417-1419).
  - Capacity: `SingleGameCycle.java::onPlayerJoins` L159 /
    `BungeeGameCycle.java::onPlayerJoins` L208 reject full games unless
    `bw.vip.joinfull` (VIP override kicks a non-VIP).
  - `BedwarsPlayerJoinEvent` is cancellable (`Game.java` L1375-1391).
- Under MCA decision 3C (reserve-at-formation) the room is expected to
  be WAITING and not full; the failure branches below are the rare path.

### 3. Failure branches — always back to the proxy lobby with a message
Room unknown to `getGame`, `playerJoins` returns false (stopped /
running-no-spectate / full / event cancelled), plugin disabled, or MCA
answered `assigned:false` / `reason:no_room_available`: send the player
to the hub via the existing bungee pattern
(`BungeeGameCycle.java::bungeeSendToServer` used at L214/L297,
`Game.java` L1383-1386; hub name from `bungeecord.hubserver`,
`BedwarsRel.java::getBungeeHub` L298) and deliver one readable message
afterwards (the delayed-message pattern at `BungeeGameCycle.java`
L215-222 is the precedent).

### 4. Group-id round trip on reporting
- When the assignment-driven join succeeds, remember the pair
  (game/room → `group_id` from the assignment response) in plugin
  memory for the lifetime of that game.
- At game over (`common/src/main/java/io/github/bedwarsrel/game/GameCycle.java::runGameOver` L290 →
  `common/src/main/java/io/github/bedwarsrel/mca/MatchReportService.java::reportGameEnd` L125 → snapshot via
  `common/src/main/java/io/github/bedwarsrel/mca/MatchSummaryBuilder.java::fromGame` L152) attach the remembered
  `matchmaking_group_id` so the payload carries it
  (`common/src/main/java/io/github/bedwarsrel/mca/MatchReportPayload.java::toJson` — sent fields are listed in its
  class javadoc L16-19; `matchmaking_group_id` must be ADDED there and
  in `mca/MatchSummary.java`).
- Effect on MCA: the settlement releases the room reservation and links
  match↔group (contract 1.10.0 round-trip). Reports without a group id
  remain valid (TTL release is the backstop).

## Consequences
- Requires this repository to build first (currently blocked on the four
  out-of-band `org.spigotmc:minecraft-server` jars — see root
  PENDING-DECISIONS and knowledge 0001's out-of-band note).
- `ReportTransport` grows a GET; the assignment query reuses report
  credentials (no new secret surface).
- Timing gap: between the proxy's assignment query and this plugin's
  query the reservation can expire (MCA room TTL); the failure branches
  above are exactly that case and must stay user-visible.
- One assignment drives one room join; re-joins after an in-game
  disconnect are BedwarsRel's own reconnect/lobby behavior, out of scope
  here.

## Evidence
- common/src/main/java/io/github/bedwarsrel/game/Game.java::playerJoins (L1357), getName (L214), getRegionName (L310), bungee kick-to-hub (L1383-1386)
- common/src/main/java/io/github/bedwarsrel/game/GameManager.java::getGame (L51), addGamePlayer (L43)
- common/src/main/java/io/github/bedwarsrel/commands/JoinGameCommand.java::execute (L21-69)
- common/src/main/java/io/github/bedwarsrel/game/GameState.java (RUNNING/WAITING/STOPPED)
- common/src/main/java/io/github/bedwarsrel/game/SingleGameCycle.java::onPlayerJoins (L159); BungeeGameCycle.java::onPlayerJoins (L208), bungeeSendToServer usages (L214/L231/L273/L297)
- common/src/main/java/io/github/bedwarsrel/BedwarsRel.java::getBungeeHub (L298), isBungee (L550)
- common/src/main/java/io/github/bedwarsrel/game/GameCycle.java::runGameOver (L290, calls MatchReportService.reportGameEnd at L300)
- common/src/main/java/io/github/bedwarsrel/mca/MatchReportService.java (worker thread L69-76, reportGameEnd L125, onGameOver L158)
- common/src/main/java/io/github/bedwarsrel/mca/MatchSummaryBuilder.java::fromGame (L152, map_name = region name L189)
- common/src/main/java/io/github/bedwarsrel/mca/MatchReportPayload.java sent-fields javadoc (L16-19)
- common/src/main/java/io/github/bedwarsrel/mca/ReportTransport.java (POST + GET since the 2026-11-08 implementation)
- HA-Contract docs/api/openapi/matchcorearena-business.yaml 1.10.0 (assignment room/reason, matchmaking_group_id round-trip)
