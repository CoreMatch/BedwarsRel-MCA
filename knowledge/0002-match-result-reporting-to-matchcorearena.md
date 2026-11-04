# ADR-0002: Match Result Reporting to MatchCoreArena

- **Status**: accepted
- **Date**: 2026-11-04

## Context

The HA ecosystem runs a competitive Arena with a player scoring/ranking system.
Bedwars matches played on this server must feed their results into that system
so player ratings update. The fork adds this reporting without touching
gameplay.

## Decision

On game over, the plugin **asynchronously POSTs a match report** to the
MatchCoreArena API:

- `MatchReporter` reads `match-reporting.api-url` (default
  `https://api.matchcorearena.com/api/matches`) and
  `match-reporting.access-token` from config, and sends
  `Authorization: Bearer <token>`. If the token is missing or still the
  placeholder `your-access-token-here`, reporting is skipped silently.
- Trigger: after `BedwarsGameOverEvent` fires and is not cancelled, inside
  `runGameOver(winner)`, on a separate thread to avoid blocking the server.
- Payload (hand-built JSON): `match_type` (e.g. `1v1v1`), `winner_team` and
  `loser_teams` (each with `team_id`, `initial_count`, `alive_count`,
  `members[]`), `duration_seconds`, `started_at`/`finished_at` (ISO-8601 UTC),
  and per member `uuid`, `survival_time`, `kills`, `deaths`, and
  `perf_tweak` (hard-coded `1.0`).
- Data collection added alongside: game start timestamp + initial team
  snapshot, and per-player survival time recorded on leave.

## Consequences

- The receiving endpoint is `POST /api/matches` on MatchCoreArena-Server, which
  requires the `match:report` scope, resolves `uuid`→`uid`, computes Elo-based
  deltas, and updates `rank_score`/rankings in one transaction. The plugin's
  field names match the server's expected struct, including the corrected
  `uuid` string field.
- The `620601be` fix changed the member identifier from a numeric `uid` (only
  the high 64 bits of the UUID) to the full `uuid` string — required by the
  server.
- Cost/limitation: `perf_tweak` is hard-coded `1.0` (its semantics relative to
  the scoring model are not verified); reporting silently no-ops without a
  configured token, so a misconfigured server produces no errors and no scores.

## Evidence

- Reporter class: `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java:17`.
- Endpoint and auth: `MatchReporter.java:37-38` (read `api-url`/`access-token`),
  `:48` (`Authorization: Bearer`), `:40-42` (skip on missing/placeholder token);
  defaults `common/src/main/resources/config.yml:969` (api-url), `:971`
  (access-token).
- Trigger: `common/src/main/java/io/github/bedwarsrel/GameCycle.java:290-298`
  (`runGameOver`, call after non-cancelled `BedwarsGameOverEvent`); async
  thread `MatchReporter.java:24-33`.
- Payload fields: `MatchReporter.java:64-98` (JSON build), `:90`
  (`duration_seconds`), `:92-94` (timestamps), `:102-106` (team fields),
  `:130` (`uuid`), `:133-137` (`survival_time`), `:142-148` (kills/deaths),
  `:151` (`perf_tweak` hard-coded 1.0), `:166-181` (`match_type`), `:183-187`
  (ISO-8601 UTC).
- Data collection: `common/src/main/java/io/github/bedwarsrel/Game.java`
  (start snapshot ~`:1690-1696`, survival time on leave ~`:1865-1868`, getters
  `:358,262,270`); introduced by `36aa815a`.
- uuid fix: commit `620601be` (`uid` numeric high-bits → `uuid` full string).
- Receiving side: MatchCoreArena-Server `api/router.go:84`
  (`api.POST("/matches", matchH.Report)` under `/api` auth group `:56-57`);
  scope requirement `api/handler/match.go:27` (`match:report`); bearer check
  `api/middleware/auth.go:66-68`; expected struct
  `internal/service/match.go:43-65` (fields incl. `uuid` `binding:"required"`
  `:60`); processing `internal/service/match.go:100-251` (resolveUID, Elo
  deltas, `rank_score` update, rankings), `:271-295` (`resolveUID`).
- Commits: `36aa815a`, `620601be`.
- Not verified: the production `api-url`/token actually deployed (deployment
  config is not in this repo); the relationship of `perf_tweak` to the scoring
  model.
