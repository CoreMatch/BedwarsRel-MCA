# ADR-0002: Match Result Reporting to MatchCoreArena

- **Status**: accepted

## Context

The HA ecosystem runs a competitive Arena with a player scoring/ranking system.
Bedwars matches played on this server must feed their results into that system
so player ratings update. The fork adds this reporting without touching
gameplay, and it must survive real-game conditions: the game-over sequence
mutates game state, the server must never block or crash on reporting, and a
result must not settle twice.

## Decision

On game over the plugin snapshots the result synchronously and hands it to an
asynchronous, idempotent report pipeline in the `io.github.bedwarsrel.mca`
package:

- Trigger: after `BedwarsGameOverEvent` fires and is not cancelled, inside
  `GameCycle::runGameOver`. The snapshot (`MatchSummaryBuilder.fromGame`) is
  built on the server main thread before any cleanup, then queued; network IO
  never touches the main thread or live game state.
- Summary contents: region name as map name, start/finish timestamps,
  duration, per team `team_id`/`initial_count`/`alive_count`/win-loss and per
  participant UUID, name, survival time, kills, deaths. Members are sorted by
  UUID and teams winner-first then by team id, so every derived artifact is
  deterministic.
- Idempotency: `GameIdGenerator` derives a stable `game_id` (name-based UUID
  over start time, map, match type and team/member UUIDs — volatile stats are
  excluded). Successfully reported ids are persisted in a bounded ledger
  (`ReportLedger`, file `mca-reported-matches.log` in the plugin data folder,
  size `match-reporting.ledger-capacity`); ledgered games are never reported
  again, including across restarts. The same `game_id` is also sent in the
  payload, so MatchCoreArena-Server deduplicates re-reports server-side
  (knowledge 0011); see PENDING-DECISIONS.md item 3.
- Delivery: a single daemon worker drains a bounded queue
  (`match-reporting.retry.queue-capacity`; overflow is dropped with a
  warning) and POSTs the HA-Contract `POST /api/matches` payload built by
  `MatchReportPayload` (contract fields only, RFC 3339 UTC timestamps, plus
  the stable `game_id` idempotency key so the server deduplicates re-reports).
  Connect/read timeouts are enforced (`match-reporting.timeout-millis`).
- Failure classification (`ReportClassifier`): 2xx marks the ledger; 4xx is
  logged with the response body and never retried; 5xx and network failures
  are retried with exponential backoff up to
  `match-reporting.retry.max-attempts`, then dropped with a SEVERE log
  (PENDING-DECISIONS.md item 6).
- Credentials (`TokenProvider`), read from `match-reporting.*` config:
  - form A — static service token (`credentials.service-token`, or the legacy
    `access-token` key) registered in MatchCoreArena-Server
    `config/tokens.yaml` with the `match:report` scope;
  - form B — OAuth2 `client_credentials` exchange
    (`credentials.token-url/client-id/client-secret`, scope default
    `match:report`); the access token is cached until shortly before expiry
    and re-fetched after a 401. Form A wins when both are configured.
  Missing endpoint or credentials disable reporting with a warning; the
  placeholder `your-access-token-here` counts as missing. Reporting never
  throws into the game flow.
- Games without a winner (draw) are skipped with a warning: the game-report
  contract requires `winner_team` (PENDING-DECISIONS.md item 1).

## Consequences

- The receiving endpoint is `POST /api/matches` on MatchCoreArena-Server,
  which requires the `match:report` scope, resolves `uuid`→HRPAuth UID,
  computes Elo-based deltas and updates `rank_score`/rankings in one
  transaction. The payload matches the server's `TeamReportInput` struct
  exactly; unknown fields would be ignored, so none are sent.
- Participants are identified by `player.getUniqueId()`, the plugin's
  existing identity handling; offline-mode implications are tracked in
  PENDING-DECISIONS.md item 4.
- `perf_tweak` is hard-coded `1.0` (its semantics relative to the scoring
  model are not verified).
- Kills/deaths of players who left before game over are reported as zero
  because their current stats were merged and reset on leave
  (PENDING-DECISIONS.md item 7).
- Unit tests cover summary mapping, idempotency-key stability, payload shape,
  failure classification, retry behavior, ledger persistence and credential
  degradation (`common/src/test/java/io/github/bedwarsrel/mca/`).

## Evidence

- Pipeline package:
  `common/src/main/java/io/github/bedwarsrel/mca/MatchReportService.java::MatchReportService`
  (queue, worker, retry loop, init/shutdown),
  `common/src/main/java/io/github/bedwarsrel/mca/MatchSummaryBuilder.java::fromGame`
  (main-thread snapshot),
  `common/src/main/java/io/github/bedwarsrel/mca/GameIdGenerator.java::generate`
  (idempotency key),
  `common/src/main/java/io/github/bedwarsrel/mca/ReportLedger.java::ReportLedger`
  (persistent ledger),
  `common/src/main/java/io/github/bedwarsrel/mca/MatchReportPayload.java::toJson`
  (contract payload),
  `common/src/main/java/io/github/bedwarsrel/mca/ReportClassifier.java::classifyStatus`
  (4xx no-retry / 5xx+network retry),
  `common/src/main/java/io/github/bedwarsrel/mca/TokenProvider.java::TokenProvider`
  (service token + client_credentials forms),
  `common/src/main/java/io/github/bedwarsrel/mca/MatchReportConfig.java::load`
  (config + legacy `access-token` fallback).
- Trigger:
  `common/src/main/java/io/github/bedwarsrel/game/GameCycle.java::runGameOver`
  (call after non-cancelled `BedwarsGameOverEvent`); lifecycle wiring
  `common/src/main/java/io/github/bedwarsrel/BedwarsRel.java::onEnable`
  (init) and `common/src/main/java/io/github/bedwarsrel/BedwarsRel.java::onDisable`
  (shutdown).
- Configuration defaults:
  `common/src/main/resources/config.yml (section: match-reporting)` and
  `common/src/main/java/io/github/bedwarsrel/updater/ConfigUpdater.java::addConfigs`
  (block `mca-match-reporting`).
- Data sources:
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::startedAt`,
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::playerSurvivalTimes`,
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::getParticipants`,
  `common/src/main/java/io/github/bedwarsrel/statistics/PlayerStatistic.java::getCurrentKills`.
- Receiving side: MatchCoreArena-Server
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/router.go::Report`
  (`api.POST("/matches", matchH.Report)` under the `/api` auth group);
  scope requirement
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/handler/match.go::Report`
  (`match:report`); bearer check
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/middleware/auth.go::extractToken`;
  service-token store
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/auth/verifier.go::Verify`
  (tokens.yaml lookup; HRPAuth-verified tokens get scope `user.read` only —
  PENDING-DECISIONS.md item 5);
  expected struct
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/service/match.go::TeamReportInput`;
  settlement without deduplication
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/service/match.go::Report`.
- Contract: HA-Contract
  `/home/lnb/Desktop/HA/HA-Contract/docs/api/openapi/matchcorearena-business.yaml`
  (path `/api/matches`, schemas `TeamReportInput`/`TeamReportInfo`/
  `ParticipantReport`) and
  `/home/lnb/Desktop/HA/HA-Contract/docs/api/error-codes.md`
  (`mca_user_not_found`, `mca_user_not_provisioned`, `oauth_hrpauth_down`,
  `oauth_insufficient_scope`).
- Open items: `PENDING-DECISIONS.md` (draws, map_name, server-side
  idempotency, offline UUIDs, client_credentials scope, dead letter, leaver
  stats).
- Not verified: the production `api-url`/token actually deployed (deployment
  config is not in this repo); the relationship of `perf_tweak` to the
  scoring model.
