# ADR-0003: Version Support Narrowed to 1.8.x and 1.12

- **Status**: accepted

## Context

Upstream BedwarsRel supports a wide range of Minecraft server versions through
per-version NMS compatibility modules. Maintaining all of them while
modernizing the build is expensive. The fork narrowed the supported matrix.

## Decision

The fork supports **only Minecraft 1.8.x and 1.12** server releases:

- Supported NMS modules: v1_8_r1/r2/r3 (1.8 / 1.8.3 / 1.8.8 Spigot/Paper) and
  v1_12_r1. There are no modules for 1.9–1.11.
- The 1.8 modules are byte-identical to upstream.
- The build is offline-friendly: dependency scripts use locally committed
  craftbukkit jars.
- The supported matrix reflects the author's own local deployment needs; no
  further interpretation is attached.

## Consequences

- The plugin will not run on 1.9–1.11 servers; deployments must use 1.8.x or
  1.12.
- The repository carries ~96MB of committed craftbukkit jars to enable
  reproducible offline builds (accepted size cost).
- Because the 1.8 modules are identical to upstream, future upstream 1.8 fixes
  can be merged cleanly for those versions.

## Evidence

- Module set: the only NMS compatibility modules at the repository root are
  `v1_8_r1`, `v1_8_r2`, `v1_8_r3` and `v1_12_r1`; there is no 1.9–1.11 module.
- 1.8 modules identical to upstream:
  `git diff upstream/master master -- v1_8_r1 v1_8_r2 v1_8_r3` is empty.
- Offline build: craftbukkit jars committed at the repository root
  (`craftbukkit-1.12.jar`, `craftbukkit-1.8.3.jar`, `craftbukkit-1.8.8.jar`,
  `craftbukkit-1.8-R0.1-SNAPSHOT.jar`, ~96MB total); the build scripts have no
  download step.
- Not verified: runtime verification on an actual 1.8/1.12 server was not
  performed (read-only research).
