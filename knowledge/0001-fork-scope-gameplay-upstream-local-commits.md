# ADR-0001: Fork Scope — Gameplay Is Upstream, Local Commits Are Reporting + Build

- **Status**: accepted

## Context

BedwarsRel-MCA is a fork of upstream BedwarsRel/BedwarsRel. Maintainers and
integrators need to know exactly what this fork adds on top of upstream and
what remains upstream's responsibility. A common shorthand — "the fork only
does match-result reporting" — understates the scope.

## Decision

This is a statement of verified fact about the fork's scope:

- The **bedwars gameplay is 100% upstream**. The fork sits directly on
  upstream's tip (master's merge-base with `upstream/master` is
  `upstream/master`'s HEAD) and adds no gameplay logic of its own.
- The local code changes on top of upstream fall into two groups:
  match-result reporting, and build / dependency / version-support
  modernization.
- In the total diff vs upstream, gameplay files gain only handwritten
  getter/setter expansion of upstream's lombok annotations and reporting
  state-tracking; there is **no gameplay logic change**.

## Consequences

- The shorthand "the fork only does match-result reporting" is inaccurate; the
  accurate statement is "reporting plus build/platform work, no gameplay
  change."
- The fork keeps large craftbukkit jars (~96MB) out of the working tree: they
  are gitignored at HEAD but remain in git history (untracked by commit
  8e7f60d3), and `build/get-dependencies.sh` materializes them from history
  (SHA-256 verified) and installs them into the local Maven repository, so
  any full clone builds offline; shallow clones must supply the jars by hand.
- Gameplay fixes/features must come from merging upstream; the fork does not
  own gameplay behavior.

## Evidence

- Fork relationship: `git merge-base master upstream/master` equals
  `git rev-parse upstream/master`; upstream default branch is **master**
  (`upstream/HEAD -> upstream/master`).
- No gameplay logic change: line-filtered `git diff upstream/master..master`
  of gameplay files (Game/Team/ResourceSpawner/PlayerStatistic/
  GameLobbyCountdown) shows only accessor boilerplate + reporting hooks.
- Reporting code:
  `common/src/main/java/io/github/bedwarsrel/mca/MatchReportService.java::MatchReportService`.
- Offline-build jars: gitignored at HEAD, present in git history until commit
  8e7f60d3: `craftbukkit-1.12.jar`, `craftbukkit-1.8.3.jar`,
  `craftbukkit-1.8.8.jar`, `craftbukkit-1.8-R0.1-SNAPSHOT.jar` (~96MB total);
  `build/get-dependencies.sh` extracts (when absent) and installs them with a
  minimal POM, because the POM embedded in each jar references
  `org.spigotmc:minecraft-server`, which no longer exists in any public
  repository and is not used for compilation.
- Not verified: none material to the scope claim.
