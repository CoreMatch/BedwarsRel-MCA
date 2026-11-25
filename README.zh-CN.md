<div align="center">
  <img src="logo.png" alt="BedwarsRel-MCA" width="220">
  <h1>BedwarsRel-MCA</h1>
  <p><strong>保留 BedwarsRel 玩法，接入 Arena 结算。</strong></p>
  <p><a href="./README.md">English</a></p>
</div>

BedwarsRel-MCA 是 BedwarsRel 的 MatchCore Arena 分支，面向希望保留熟悉玩法并接入 Arena 赛果结算的服务器运维人员，通过异步对局快照与分配驱动入场完成衔接。

## 为什么选择 BedwarsRel-MCA

- **保留经典玩法。** 延续 BedwarsRel 游戏体验，同时把已完成对局接入 Arena 结算。
- **稳定标识赛果。** 异步快照与稳定 ID 明确本次上报所对应的比赛。
- **每次尝试都有记录。** 上报账本持久记录提交情况，并以退避策略跟踪重试。
- **限制积压上报。** 死信设有明确上限，并提供供运维人员使用的重放路径。
- **按分配入场。** MatchCore 分配引导玩家进入预定对局。

## 进一步了解

- [Armorial Wiki](https://wiki.mcnb.dev/) — 生态文档
- [Armorial Contract](https://github.com/CoreMatch/HA-Contract) — 接口契约
- [MatchCoreArena Server](https://github.com/CoreMatch/MatchCoreArena-Server) — 结算核心
- [BedwarsRel 上游](https://github.com/BedwarsRel/BedwarsRel) — 原项目与上游问题追踪
- [本分支](https://github.com/CoreMatch/BedwarsRel-MCA/issues) — 分支特有问题请在此反馈

## 兼容性与限制

仅支持 Minecraft 1.8.x 与 1.12。有界上报缓冲区满后，溢出的上报可能被丢弃。

## 许可证与来源

依据 [GPL-3.0](LICENSE) 授权。本仓库是 [BedwarsRel](https://github.com/BedwarsRel/BedwarsRel) 的分支并保留上游署名；分支特有问题应提交到本仓库，而非上游。
