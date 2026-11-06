# PENDING-DECISIONS

Items where the match-reporting implementation made a conservative default
choice and needs an author decision. Each item lists the current behavior and
the options. Nothing here blocks the current build.

(Former items 1 "draw results cannot be reported" and 2 "map name not
transmitted" were resolved by HA-Contract v1.4.0 and are no longer pending:
the plugin now reports all-draw games through the unified `teams` shape and
sends `map_name`.)

## 3. Idempotency is plugin-side ledger plus server-side game_id

- Current: a stable `game_id` (name-based UUID over start time, map, match
  type and team/member UUIDs) is recorded in a persistent ledger
  (`plugins/BedwarsRel/mca-reported-matches.log`, bounded by
  `match-reporting.ledger-capacity`, default 2000) **and** sent in the
  `POST /api/matches` payload. A game whose id is ledgered is never reported
  again, including across restarts.
- Why: MatchCoreArena-Server honors `game_id` as the idempotency key
  (knowledge 0011): an identical re-report returns the existing match
  (HTTP 200, no re-settlement), and a conflicting reuse is rejected with
  `409 mca_game_id_conflict`. The plugin ledger is a fast-path guard that
  avoids the network round-trip for already-reported games.
- Residual risk: if the plugin process dies between the server accepting the
  report and the ledger write, a restart re-sends the same `game_id`; the
  server now deduplicates it (returns the existing record), so no duplicate
  settlement occurs. The ledger write still lags the server accept by
  milliseconds, but the server-side key closes the previous duplicate-settle
  window.
- Options: (a) keep both layers (status quo); (b) drop the plugin ledger and
  rely solely on server-side `game_id` deduplication (simpler, but every
  restart replays old reports against the server until they 409/200).

## 4. Offline-mode servers produce unresolvable UUIDs

- Current: participants are identified by `player.getUniqueId()` — the same
  identity handling the plugin already uses everywhere.
- Why: on an offline-mode server Bukkit derives name-based UUIDs that HRPAuth
  does not know; the server then answers `404 mca_user_not_found` and the
  report is dropped (4xx, no retry).
- Options: (a) require online mode / Yggdrasil-provided UUIDs for servers
  using reporting (status quo); (b) add a configurable UUID source or a
  name→HA-UUID mapping lookup in the plugin.

## 5. client_credentials form vs. the v1.4.0 OPS gate

- Current: the plugin supports both credential forms — static service token
  (recommended, matches MatchCoreArena-Server `config/tokens.yaml`) and an
  OAuth2 `client_credentials` exchange (`credentials.token-url/client-id/
  client-secret`).
- Why: HA-Contract v1.4.0 requires an **OPS-level credential** for reporting
  (maintenance token or a `tokens.yaml` service token) and rejects ordinary
  tokens with `403 oauth_insufficient_scope` even when the scope is present.
  An HRPAuth-issued `client_credentials` access token is verified through the
  HRPAuth path (scope set hard-coded to `user.read` in
  `internal/auth/verifier.go::Verify`) and is not an OPS credential, so form
  B is rejected by current servers.
- Options: (a) keep both plugin forms and deploy form A (status quo);
  (b) remove form B from the plugin until servers can accept it; (c) make
  the MCA server accept HRPAuth service tokens as OPS credentials (server
  change).

## 6. Dead-letter records need a recovery path

- Current: reports whose retries are exhausted (5xx/network only; 4xx is
  final) are written to the bounded dead-letter file
  (`plugins/BedwarsRel/mca-dead-letter.log`, bounded by
  `match-reporting.dead-letter-capacity`, default 200) as one JSON line per
  game carrying the timestamp, `game_id`, failure reason and the full report
  payload. Startup warns when undelivered records exist. Nothing is lost
  silently; oldest entries are evicted beyond the capacity.
- Open: there is no replay mechanism — recovery today means manually
  resubmitting the stored payload (any replay is safe: the server
  deduplicates identical `game_id` payloads).
- Options: (a) keep manual inspection/replay (status quo); (b) add an admin
  command or tool that replays dead-letter entries against the endpoint.

## 7. Mid-game leavers report zeroed per-game stats

- Current: kills/deaths are read from `PlayerStatistic.getCurrentKills()/
  getCurrentDeaths()` at game over. A player who left the game before game
  over already had their current stats merged into totals and reset
  (`Game.playerLeave` → `storeStatistic` → `addCurrentValues`), so they are
  reported with `kills=0, deaths=0`. Win/loss and survival time are still
  correct; players who stayed (including eliminated spectators) report exact
  stats.
- Options: (a) accept (status quo, identical to the previous reporter);
  (b) snapshot each participant's current stats at game start or intercept
  `storeStatistic` for mid-game leavers.
