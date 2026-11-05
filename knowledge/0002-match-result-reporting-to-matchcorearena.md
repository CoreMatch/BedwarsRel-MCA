# ADR-0002: Match Result Reporting to MatchCoreArena

- **Status**: accepted

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
- Supporting data collection: game start timestamp + initial team snapshot,
  and per-player survival time recorded on leave.

## Consequences

- The receiving endpoint is `POST /api/matches` on MatchCoreArena-Server, which
  requires the `match:report` scope, resolves `uuid`→`uid`, computes Elo-based
  deltas, and updates `rank_score`/rankings in one transaction. The plugin's
  field names match the server's expected struct, including the full `uuid`
  string field that the server requires.
- Cost/limitation: `perf_tweak` is hard-coded `1.0` (its semantics relative to
  the scoring model are not verified); reporting silently no-ops without a
  configured token, so a misconfigured server produces no errors and no scores.

## Evidence

- Reporter class:
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::MatchReporter`.
- Endpoint and auth:
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::sendReport`
  (reads `api-url`/`access-token`, sends `Authorization: Bearer`, skips on
  missing/placeholder token); defaults
  `common/src/main/resources/config.yml (field: api-url)`,
  `common/src/main/resources/config.yml (field: access-token)`.
- Trigger:
  `common/src/main/java/io/github/bedwarsrel/game/GameCycle.java::runGameOver`
  (call after non-cancelled `BedwarsGameOverEvent`); async thread
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::report`.
- Payload fields:
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::buildJson`
  (JSON build, `duration_seconds`, timestamps),
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::appendTeamInfo`
  (team fields),
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::appendParticipantInfo`
  (`uuid`, `survival_time`, kills/deaths, `perf_tweak` hard-coded 1.0),
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::getMatchType`
  (`match_type`),
  `common/src/main/java/io/github/bedwarsrel/utils/MatchReporter.java::formatIso8601`
  (ISO-8601 UTC).
- Data collection:
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::startedAt`
  (start snapshot),
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::playerSurvivalTimes`
  (survival time on leave), getters
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::getStartedAt`,
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::getPlayerSurvivalTimes`,
  `common/src/main/java/io/github/bedwarsrel/game/Game.java::getParticipants`.
- Receiving side: MatchCoreArena-Server
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/router.go::Report`
  (`api.POST("/matches", matchH.Report)` under `/api` auth group
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/router.go:L56-L57`);
  scope requirement
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/handler/match.go::Report`
  (`match:report`); bearer check
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/api/middleware/auth.go::extractToken`;
  expected struct
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/service/match.go::TeamReportInput`
  (fields incl. `uuid` `binding:"required"` in
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/service/match.go::ParticipantReport`);
  processing
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/service/match.go::Report`
  (resolveUID, Elo deltas, `rank_score` update, rankings),
  `/home/lnb/Desktop/HA/MatchCoreArena-Server/internal/service/match.go::resolveUID`.
- Not verified: the production `api-url`/token actually deployed (deployment
  config is not in this repo); the relationship of `perf_tweak` to the scoring
  model.
