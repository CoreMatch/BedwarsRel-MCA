# PENDING-DECISIONS

Items where the match-reporting implementation made a conservative default
choice and needs an author decision. Each item lists the current behavior and
the options. Nothing here blocks the current build.

(Former items 1 "draw results cannot be reported" and 2 "map name not
transmitted" were resolved by HA-Contract v1.4.0 and are no longer pending:
the plugin now reports all-draw games through the unified `teams` shape and
sends `map_name`. Former item 6 "dead-letter records need a recovery path" is
resolved by the administrative commands `/mca deadletter` and
`/mca replay [n]` (also reachable as `/bw mca`): `deadletter` prints the
number of undelivered records; `replay` re-enqueues the most recent records,
at most 50 per invocation (default 10), stops early when the delivery queue
is full and skips malformed entries. Handled records are removed from the
store, which stays bounded by `match-reporting.dead-letter-capacity` (default
200, oldest entries evicted first). Replay is safe because
MatchCoreArena-Server treats `game_id` as the idempotency key: an identical
re-report returns the existing match without settling it again.)

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
- **Settled (2026-11-08, option a)**: keep both layers. The ledger avoids a
  network round-trip for already-reported games and bounds restart replay;
  the server-side `game_id` key closes the duplicate-settlement window when
  the process dies between server accept and ledger write. The layers are
  complementary; dropping the ledger (b) would replay every historical
  report against the server on each restart for no gain.

## 4. Offline-mode servers produce unresolvable UUIDs

- Current: participants are identified by `player.getUniqueId()` — the same
  identity handling the plugin already uses everywhere.
- Why: on an offline-mode server Bukkit derives name-based UUIDs that HRPAuth
  does not know; the server then answers `404 mca_user_not_found` and the
  report is dropped (4xx, no retry).
- Options: (a) require online mode / Yggdrasil-provided UUIDs for servers
  using reporting (status quo); (b) add a configurable UUID source or a
  name→HA-UUID mapping lookup in the plugin.
- **Settled (2026-11-08, option a)**: servers using reporting must run
  online mode / Yggdrasil-provided UUIDs. Offline-mode name-based UUIDs are
  unresolvable by HRPAuth and the report is typed-rejected with
  `404 mca_user_not_found` (4xx, no retry), a clear and bounded failure
  surface. A mapping lookup (b) would introduce a second identity source
  into the settlement trust boundary; cost outweighs benefit. Document this
  deployment requirement wherever reporting is configured.

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
- **Settled (2026-11-08, option a)**: keep both credential forms in the
  plugin; deployments use form A (static service token, matching
  MatchCoreArena-Server `config/tokens.yaml`). Form B stays as
  protocol-level compatibility only — current servers reject it with
  `403 oauth_insufficient_scope` under the HA-Contract v1.4.0 OPS gate.
  Removing form B (b) or relaxing the server-side OPS gate (c) both touch
  the credential trust boundary and are out of scope for this batch; revisit
  if/when servers accept HRPAuth service tokens as OPS credentials.

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
- **Settled (2026-11-08, option a)**: accept zeroed kills/deaths for
  mid-game leavers (identical semantics to the previous reporter). Win/loss
  and survival time remain correct and staying players report exact stats.
  Snapshotting at game start or intercepting `storeStatistic` (b) would
  change the stats-read timing and diverge from the engine's settlement
  timing; not adopted now. Reopen as its own item if competitive stat
  accuracy ever demands it.
