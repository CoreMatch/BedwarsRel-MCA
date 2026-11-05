# Knowledge Base

## What This Is

This knowledge base is **BedwarsRel-MCA's memo to itself**. It serves
self-hosting and maintenance of this repository: what this fork is relative to
upstream BedwarsRel, what the local changes actually do, and how match results
reach the Arena scoring system. It is maintained alongside the code and is not
meant to make external projects accommodate this one. Entries describe the
project's current state only.

## Order of Authority

This knowledge base **does not define outward behavior**. The bedwars gameplay
itself is defined by upstream [BedwarsRel](https://github.com/BedwarsRel/BedwarsRel)
and is not re-defined here; the receiving side of match reporting is defined by
the MatchCoreArena service, and ecosystem outward behavior by
[HA-Contract](https://github.com/CoreMatch/HA-Contract) where applicable. If an
entry here ever appears to contradict a contract, **the contract wins and the
entry is stale — fix the entry, never the contract**, and surface the conflict
to the owner.

## Format

Each entry is one page in ADR style:

- **Status** (`accepted` / `superseded` / ...)
- **Context** — the problem and constraints
- **Decision** — what was chosen
- **Consequences** — including costs and limitations
- **Evidence** — `file:line` and protocol entries. Every piece of evidence
  must have been actually checked; anything that could not be verified is
  marked "not verified". Do not fabricate.

## Entries

| # | Title | Status |
|---|-------|--------|
| [0001](0001-fork-scope-gameplay-upstream-local-commits.md) | Fork Scope: Gameplay Is Upstream, Local Commits Are Reporting + Build | accepted |
| [0002](0002-match-result-reporting-to-matchcorearena.md) | Match Result Reporting to MatchCoreArena | accepted |
| [0003](0003-version-support-narrowed-to-1.8-and-1.12.md) | Version Support Narrowed to 1.8.x and 1.12 | accepted |
