# ADR-0003: Version Support Narrowed to 1.8.x and 1.12

- **Status**: accepted
- **Date**: 2026-11-04

## Context

Upstream BedwarsRel supports a wide range of Minecraft server versions through
per-version NMS compatibility modules. Maintaining all of them while
modernizing the build (dropping lombok, dropping HologramAPI, offline jars) is
expensive. The fork narrowed the supported matrix.

## Decision

The fork supports **only Minecraft 1.8.x and 1.12** server releases:

- `3f9b7d55` deleted the NMS modules for 1.9–1.11 (and initially 1.8 too).
- `aefd8fc1` re-added the three **1.8** NMS modules (v1_8_r1/r2/r3, i.e.
  1.8 / 1.8.3 / 1.8.8 Spigot/Paper). These restored modules are
  **byte-identical to upstream** — a restoration of code removed one commit
  earlier, not new compatibility work.
- Net effect: supported = 1.8.x + 1.12; removed = 1.9–1.11.
- The build was made offline-friendly: dependency scripts use locally committed
  craftbukkit jars instead of downloading via wget (`f4a30bb2`).

## Consequences

- The plugin will not run on 1.9–1.11 servers; deployments must use 1.8.x or
  1.12.
- The repository carries ~96MB of committed craftbukkit jars to enable
  reproducible offline builds (accepted size cost).
- Because the 1.8 modules are identical to upstream, future upstream 1.8 fixes
  can be merged cleanly for those versions; 1.9–1.11 support is intentionally
  abandoned.

## Evidence

- Drop pre-1.12: commit `3f9b7d55` ("drop support for pre-1.12 Minecraft
  versions"), `git show --stat` = 73 files +55/-4120 (removes v1_8_r1/r2/r3,
  v1_9_r1/r2, v1_10_r1, v1_11_r1 modules).
- Re-add 1.8: commit `aefd8fc1` ("add support for 1.8.x Minecraft server
  releases"; message: "Add three new NMS compatibility modules for 1.8, 1.8.3
  and 1.8.8 Spigot/Paper releases"), 31 files +1549/-13.
- Restored 1.8 modules identical to upstream: `git diff upstream/master
  aefd8fc1 -- v1_8_r1 v1_8_r2 v1_8_r3` is empty.
- Offline build: commit `f4a30bb2` ("update dependency scripts to use local
  jars and remove wget requirement"), commits ~57MB of craftbukkit 1.8 jars;
  related 39.7MB `craftbukkit-1.12.jar` committed in `07bfb360`.
- Not verified: runtime verification on an actual 1.8/1.12 server was not
  performed (read-only research).
