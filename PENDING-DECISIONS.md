# PENDING-DECISIONS

Items where the match-reporting implementation made a conservative default
choice and needs an author decision. Each item lists the current behavior and
the options. Nothing here blocks the current build.

## 1. Draw results cannot be reported on the game path

- Current: a game that ends without a winner is skipped with a warning log.
- Why: HA-Contract `POST /api/matches` (`TeamReportInput`) requires
  `winner_team`; MatchCoreArena-Server validation rejects an empty winner and
  its settlement code divides by `initial_count`, so a draw payload is not
  representable. The contract's `ParticipantDetail.is_draw` is documented as
  "game reports are always false".
- Options: (a) keep skipping draws (status quo); (b) extend the contract with
  draw support on the game path (requires HA-Contract + server changes);
  (c) report draws through `POST /api/matches/virtual` (contract already
  supports all-draw, but mixes real-game reporting with the virtual source).

## 2. Map name is collected but not transmitted

- Current: the summary carries `map_name` (region name) and logs it, but the
  JSON payload omits it.
- Why: `TeamReportInput` has no `map_name` field; the server's game-report
  INSERT does not write the existing `matches.map_name` column (the virtual
  path does). Sending the field would be silently ignored.
- Options: (a) keep omitting (status quo); (b) add `map_name` to
  `TeamReportInput` in HA-Contract and to the server INSERT (column already
  exists), then the plugin only needs to add one payload field.

## 3. Idempotency is plugin-side only

- Current: a stable `game_id` (name-based UUID over start time, map, match
  type and team/member UUIDs) is recorded in a persistent ledger
  (`plugins/BedwarsRel/mca-reported-matches.log`, bounded by
  `match-reporting.ledger-capacity`, default 2000). A game whose id is
  ledgered is never reported again, including across restarts.
- Why: the settlement endpoint performs no deduplication and the contract has
  no client-supplied idempotency field; duplicate POSTs settle twice.
- Residual risk: if the plugin process dies between the server accepting the
  report and the ledger write, a restart can re-report once. The window is
  milliseconds; the consequence is one duplicated settlement.
- Options: (a) keep plugin-side ledger (status quo); (b) add a
  `client_match_id` field to `TeamReportInput` plus a unique constraint in
  MatchCoreArena-Server for true server-side idempotency (HA-Contract +
  server change; plugin already has the id to send).

## 4. Offline-mode servers produce unresolvable UUIDs

- Current: participants are identified by `player.getUniqueId()` — the same
  identity handling the plugin already uses everywhere.
- Why: on an offline-mode server Bukkit derives name-based UUIDs that HRPAuth
  does not know; the server then answers `404 mca_user_not_found` and the
  report is dropped (4xx, no retry).
- Options: (a) require online mode / Yggdrasil-provided UUIDs for servers
  using reporting (status quo); (b) add a configurable UUID source or a
  name→HA-UUID mapping lookup in the plugin.

## 5. client_credentials form vs. server scope check

- Current: the plugin supports both credential forms — static service token
  (recommended, matches MatchCoreArena-Server `config/tokens.yaml`) and an
  OAuth2 `client_credentials` exchange (`credentials.token-url/client-id/
  client-secret`).
- Why: MatchCoreArena-Server's token verifier hard-codes the scope set
  `["user.read"]` for any HRPAuth-verified token
  (`internal/auth/verifier.go::Verify`), so a token obtained via
  `client_credentials` currently fails the `match:report` scope check with
  `403 oauth_insufficient_scope`. Only `tokens.yaml` service tokens (and the
  maintenance token) pass today.
- Options: (a) keep both plugin forms and use form A in deployments
  (status quo); (b) make the MCA server honor service-token scopes returned
  by HRPAuth so form B works end-to-end (server change).

## 6. Reports that exhaust retries are dropped, not spilled

- Current: after `match-reporting.retry.max-attempts` failed attempts
  (5xx/network only; 4xx is never retried) the report is dropped with a
  SEVERE log naming the `game_id`. The game is not ledgered, so a later
  server restart does not re-send it either (the summary only exists at
  game over).
- Options: (a) accept the drop with loud logging (status quo); (b) persist a
  dead-letter file of failed payloads that an admin tool or a future plugin
  version can replay.

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
