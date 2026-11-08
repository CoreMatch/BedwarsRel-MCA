[![CircleCI](https://circleci.com/gh/BedwarsRel/BedwarsRel.svg?style=shield)](https://circleci.com/gh/BedwarsRel/BedwarsRel)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/df02ec6676b8457aa7fcb4be1ba44729)](https://www.codacy.com/app/BedwarsRel/BedwarsRel)
[![Gitter](https://badges.gitter.im/BedwarsRel/BedwarsRel.svg)](https://gitter.im/BedwarsRel/BedwarsRel)

# BedwarsRel - Minecraft Bedwars Plugin
![](https://github.com/BedwarsRel/BedwarsRel/blob/master/logo.png)

Bedwars is a [Minecraft](http://www.minecraft.net) minigame where teams (max. 15) try to destroy the other teams' beds. But there is more: On the whole map there are ressource spawners spawning items. Some items are more worth than others and with these you can buy different things in the so called "Villager Shop". You will find powerful weapons or potions or simple blocks to get to the other bases. Get to the others bases? Yes, every team starts on an island and you have to get to the others with blocks which you can buy in the "Villager Shop". When you meet a enemy you have to try to kill him so he'll lose all the equipment which he had in his inventory. As soon as a team's bed is destroyed they cannot respawn again and last team standing wins.

# Server Owners
For general information about BedwarsRel **[go to www.spigotmc.org](https://www.spigotmc.org/resources/bedwars-rel.6799/)**. For more information about configuring and running the plugin on your server, please **[have a look on the wiki](https://github.com/BedwarsRel/BedwarsRel/wiki)**.

If you are experiencing any problems with BedwarsRel, please open a new issue. Remember the content of the wiki-page **["HowTo: Write an issue"](https://github.com/BedwarsRel/BedwarsRel/wiki/HowTo:-Write-an-Issue)** you already read before.

BedwarsRel is compatible with `CraftBukkit/Spigot 1.8 - 1.8.8`, `CraftBukkit/Spigot 1.9 - 1.9.4`, `CraftBukkit/Spigot 1.10 - 1.10.2`, `CraftBukkit/Spigot 1.11 - 1.11.2` and `CraftBukkit/Spigot 1.12`.

# Developers
## Contributing to BedwarsRel
If you would like to contribute to this repository, feel free to [fork the repo](https://help.github.com/articles/fork-a-repo/) and then [create a pull request](https://help.github.com/articles/creating-a-pull-request/) to our current `dev` branch. This project uses [Project Lombok](https://projectlombok.org), so you will need to have this [installed in your IDE](https://projectlombok.org/download.html). For code formatting, we recommand using the [Google Java Style](https://google.github.io/styleguide/javaguide.html) ([Eclipse Profile](https://raw.githubusercontent.com/google/styleguide/gh-pages/eclipse-java-google-style.xml)).

## Building

**Shortest reproducible path** from a fresh full clone — two commands, no
need to read any script:

```
build/get-dependencies.sh
mvn clean package
```

Loadable plugin jar: `plugin/target/BedwarsRel-1.3.6-SNAPSHOT.jar`.

Any modern JDK works: the build is pinned to `source`/`target` 1.8 (bytecode
major version 52) and was verified up to JDK 25; Maven 3.9+ is required.

The NMS modules (`v1_8_r1`, `v1_8_r2`, `v1_8_r3`, `v1_12_r1`) — and therefore
also the shaded `plugin` module that bundles them — depend on CraftBukkit
server jars that are gitignored at HEAD. `build/get-dependencies.sh` makes
them available and installs them into the local Maven repository:

1. If a jar (`craftbukkit-1.8-R0.1-SNAPSHOT.jar`, `craftbukkit-1.8.3.jar`,
   `craftbukkit-1.8.8.jar`, `craftbukkit-1.12.jar`) already sits in the
   repository root, it is used as-is.
2. Otherwise the jar is extracted from this repository's own git history —
   the jars were tracked until commit `8e7f60d3`, so any **full clone** still
   carries the bytes — and verified against pinned SHA-256 checksums. A
   shallow clone has no such history; the script fails loudly and expects the
   jars to be placed in the repository root manually.

The script installs each jar with a minimal POM on purpose: the jars embed
their original BuildTools POM, which declares a dependency on
`org.spigotmc:minecraft-server` — an artifact that no longer exists in any
public Maven repository and that this build never uses (every class the NMS
modules compile against is already inside the CraftBukkit jars).

The shaded, loadable plugin jar is written to
`plugin/target/BedwarsRel-1.3.6-SNAPSHOT.jar`. `build.sh` chains both steps
(install dependencies, then `mvn clean install -DskipTests`).

The GitHub Actions workflow (`.github/workflows/ci.yml`) checks out full
history, runs the same script, and builds and tests **all** modules.

## Implementing BedwarsRel
```xml
<dependency>
  <groupId>io.github.bedwarsrel</groupId>
  <artifactId>BedwarsRel</artifactId>
  <version>1.3.6</version>
</dependency>
```