<div align="center">
  <img src="logo.png" alt="BedwarsRel-MCA" width="220">
  <h1>BedwarsRel-MCA</h1>
  <p><strong>BedwarsRel gameplay, connected to Arena settlement.</strong></p>
  <p><a href="./README.zh-CN.md">简体中文</a></p>
</div>

BedwarsRel-MCA is a MatchCore Arena fork of BedwarsRel for server operators who want familiar Bedwars gameplay with Arena result settlement. It combines asynchronous match snapshots with assignment-driven entry.

## Why BedwarsRel-MCA

- **Preserve the game.** Keep BedwarsRel gameplay while connecting completed matches to Arena settlement.
- **Capture a stable result.** Asynchronous snapshots and stable IDs identify the match being reported.
- **Persist every attempt.** A report ledger records submissions and tracks retries with backoff.
- **Bound stalled reports.** Dead letters have explicit limits and a replay path for operator action.
- **Enter by assignment.** MatchCore assignments guide players into the intended match.

## Learn more

- [HA Wiki](https://wiki.mcnb.dev/) — ecosystem documentation
- [HA Contract](https://github.com/CoreMatch/HA-Contract) — interface contracts
- [MatchCoreArena Server](https://github.com/CoreMatch/MatchCoreArena-Server) — settlement core
- [BedwarsRel upstream](https://github.com/BedwarsRel/BedwarsRel) — original project and upstream issues
- [This fork](https://github.com/CoreMatch/BedwarsRel-MCA/issues) — report fork-specific issues here

## Compatibility and limits

Supports Minecraft 1.8.x and 1.12 only. If the bounded report buffer fills, overflow reports can be dropped.

## License and provenance

Licensed under [GPL-3.0](LICENSE). This repository is a fork of [BedwarsRel](https://github.com/BedwarsRel/BedwarsRel) and retains upstream attribution; fork-specific issues belong in this repository, not upstream.
