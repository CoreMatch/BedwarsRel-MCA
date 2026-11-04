# ADR-0001: Fork Scope — Gameplay Is Upstream, Local Commits Are Reporting + Build

- **Status**: accepted
- **Date**: 2026-11-04

## Context

BedwarsRel-MCA is a fork of upstream BedwarsRel/BedwarsRel. Maintainers and
integrators need to know exactly what this fork adds on top of upstream and
what remains upstream's responsibility. A common shorthand — "the fork only
does match-result reporting" — needs to be checked against the actual history.

## Decision

This is a statement of verified fact about the fork's scope:

- The **bedwars gameplay is 100% upstream**. The fork sits on upstream's tip
  (merge-base `9d271c6d`, "Add autojoin command (#1028)", which is
  upstream/master HEAD) and is exactly **7 commits ahead** of
  `upstream/master`.
- Those 7 commits are **not all match reporting**. Verified per-commit:
  - Match-result reporting (2): `36aa815a` (add reporting), `620601be` (uuid
    field fix).
  - Build / dependency / version-support modernization (5): `3f9b7d55` (drop
  pre-1.12 NMS modules), `07bfb360` (drop HologramAPI, replace lombok, update
  build), `dc60f723` (clean code, fix stat manager), `aefd8fc1` (re-add 1.8.x
  NMS modules), `f4a30bb2` (local jars, drop wget).
- In the total diff vs upstream, every added line in gameplay files is lombok
  getter/setter expansion or reporting state-tracking; there is **no gameplay
  logic change**.

## Consequences

- The claim "7 commits, all match reporting" is inaccurate; the accurate
  statement is "2 reporting commits + 5 build/platform commits, no gameplay
  change."
- The fork carries large committed binary jars (~96MB of craftbukkit jars) for
  offline builds — a repository-size cost accepted for build reproducibility.
- Gameplay fixes/features must come from merging upstream; the fork does not
  own gameplay behavior.

## Evidence

- Fork relationship: `git merge-base master upstream/master` =
  `9d271c6d6ab396d4fb19bd594fbb8c28ffe5e079`; upstream default branch is
  **master** (`upstream/HEAD -> upstream/master`);
  `git rev-list --count upstream/master..master` = 7.
- Per-commit nature (verified via `git show --stat`):
  - `36aa815a` — reporting feature: adds `MatchReporter.java` (+190), `Game.java`
    tracking, `GameCycle.java` trigger, `config.yml` block (6 files +224/-1).
  - `620601be` — reporting fix: `MatchReporter.java` only (+1/-3, uuid field).
  - `3f9b7d55` — version support: deletes 1.8–1.11 NMS modules (73 files
    +55/-4120).
  - `07bfb360` — build: deletes `HologramAPIInteraction.java`, lombok→handwritten,
    commits 39.7MB `craftbukkit-1.12.jar` (14 files +591/-293).
  - `dc60f723` — cleanup (3 files +2/-38).
  - `aefd8fc1` — version support: re-adds v1_8_r1/r2/r3 NMS modules (31 files
    +1549/-13).
  - `f4a30bb2` — build: local jars, commits ~57MB of craftbukkit 1.8 jars,
    removes wget (5 files +20/-27).
- No gameplay logic change: line-filtered diff of gameplay files
  (Game/Team/ResourceSpawner/PlayerStatistic/GameLobbyCountdown) shows only
  lombok boilerplate + reporting hooks.
- Not verified: none material to the scope claim.
