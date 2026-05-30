# 🛠️ HJH Database 管理员指令手册

本手册包含了 `HJH Database` 插件当前源码中可用的管理员与调试指令。该指令集主要用于服务器日常管理、系统测试、剧情推进、地图搭建、副本维护以及玩家数据修正。

> **⚠️ 注意事项**
> * **源码版本**：本手册按当前项目源码整理，以 `plugin.yml` 与各 Command 类实现为准。
> * **权限要求**：大部分管理指令需要 `OP`、`hjh.admin`、`hjh.op` 或模块专属权限。若权限插件与 OP 判断同时存在，请以实际执行结果为准。
> * **基础指令**：输入 `/hjhadmin` 可在游戏内查看简易版帮助菜单。
> * **参数说明**：`< >` 表示必填参数，`[ ]` 表示可选参数。
> * **在线限制**：多数修改玩家数据的指令只支持在线玩家。

---

## 📌 0. 项目简要说明

`HJH Database` 是一个基于 Kotlin + Paper API 的大型 RPG 核心插件，主要模块包括玩家属性、职业/种族、资源物品、武器技能、医术、炼丹、锻造、开物术、任务、剧情 NPC、传送、刷怪、副本、金宝箱、个人仓库和重华晶等。

| 项目项 | 当前情况 |
| :--- | :--- |
| 插件入口 | `com.hjh_database.Hjh_database` |
| API 版本 | `1.21` / Paper `1.21.3` 相关依赖 |
| 数据库 | 当前源码实际使用 SQLite，并在插件数据目录生成 `hjh_rpg.db` |
| 构建工具 | Gradle Kotlin DSL |
| 核心管理入口 | `/hjhadmin` |

---

## ⚙️ 1. 基础系统管理

用于重载配置或管理核心资源系统。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin reload` | 重载插件内多套配置与管理器，包括 Resource、医术、NPC、炼丹、传送点、重华晶、武器、技能等。 | `/hjhadmin reload` |
| `/hjh resourcereload` | 单独重载 Resource 物品配置，并刷新在线玩家物品。 | `/hjh resourcereload` |

---

## 👤 2. 玩家属性与状态管理

用于直接干预玩家等级、职业、种族、剧情状态和任务进度。

> **📊 职业与种族映射表**
> * **职业 (Job)**：`战士` / `弓箭手` / `术士` / `医师`
> * **种族 (Race)**：`神` / `仙` / `人` / `战神` / `妖`
> * 源码底层仍使用数字存储，但 `/hjhadmin job` 与 `/hjhadmin race` 当前指令参数使用中文名称。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin level <玩家>` | 查看目标玩家当前等级。 | `/hjhadmin level Notch` |
| `/hjhadmin level <玩家> set <数值>` | 设置目标玩家等级，最低会被修正为 1。 | `/hjhadmin level Notch set 50` |
| `/hjhadmin level <玩家> add <数值>` | 增加目标玩家等级，可填负数但最终最低为 1。 | `/hjhadmin level Notch add 5` |
| `/hjhadmin job <玩家> <职业>` | 设置玩家职业。 | `/hjhadmin job Notch 战士` |
| `/hjhadmin race <玩家> <种族>` | 设置玩家种族。 | `/hjhadmin race Notch 人` |
| `/hjhadmin status <玩家>` | 查看玩家当前剧情状态及描述。 | `/hjhadmin status Notch` |
| `/hjhadmin status <玩家> set <数值>` | 修改玩家剧情状态并保存到数据库。 | `/hjhadmin status Notch set 2` |
| `/hjhstats` | 玩家查看自己的属性面板。 | `/hjhstats` |

---

## 📦 3. 物品与装备获取
 
用于快速获取自定义物品、测试装备、功能方块和仓库相关物品。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin get <物品ID/名称> [数量]` | 获取 Resource 系统物品，默认数量为 1。 | `/hjhadmin get mijingyaoshi 1` |
| `/hjhadmin gettestgear <玩家>` | 给目标玩家发放 novice 测试武器与护甲，并刷新属性。 | `/hjhadmin gettestgear Notch` |
| `/hjhadmin givetoken <玩家>` | 给予目标玩家一枚“天机令”。 | `/hjhadmin givetoken Notch` |
| `/hjhadmin getwarehouse` | 获取个人仓库方块，放置后生成仓库交互点。 | `/hjhadmin getwarehouse` |
| `/hjhadmin openwarehouse <玩家>` | 强制打开并查看目标玩家的个人仓库 GUI。 | `/hjhadmin openwarehouse Notch` |
| `/hjhweapon reload` | 重载武器配置。 | `/hjhweapon reload` |
| `/hjhweapon get <武器ID>` | 获取指定武器。 | `/hjhweapon get taomujian` |

---

## 📜 4. 任务与剧情 NPC

用于调试任务线、推进剧情状态以及生成剧情实体。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin quest <玩家> <任务ID> <状态> [进度]` | 修改玩家任务状态与进度。状态可选：`LOCKED`、`IN_PROGRESS`、`COMPLETED`。 | `/hjhadmin quest Notch main_ren_1 IN_PROGRESS 5` |
| `/hjhadmin gennpc <ID\|ALL>` | 生成指定剧情 NPC，或使用 `ALL` 生成全部剧情 NPC。会清理旧实体并重新生成。 | `/hjhadmin gennpc ALL` |

---

## ⚔️ 5. 副本与宝箱系统 (Dungeon)

用于管理四圣兽试炼副本、通关状态、金宝箱和掉落记录。

> **副本类型**：`qinglong`、`baihu`、`zhuque`、`xuanwu`

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin dungeon trigger <副本类型>` | 获取对应副本的试炼触发器，材质为灵魂灯笼。 | `/hjhadmin dungeon trigger qinglong` |
| `/hjhadmin dungeon set <玩家> <副本类型> <0\|1>` | 强制设置玩家某个四圣兽试炼完成状态，`0` 为未完成，`1` 为已完成。 | `/hjhadmin dungeon set Notch qinglong 1` |
| `/hjhadmin dungeon getchest <副本ID>` | 获取指定副本的金宝箱方块，放置后成为奖励宝库。当前已注册示例：`dragon_test`、`zhuque_test`。 | `/hjhadmin dungeon getchest dragon_test` |
| `/hjhadmin dungeon info <玩家> <副本ID>` | 查看玩家在某副本的历史通关数、历史开箱数和剩余可开箱次数。 | `/hjhadmin dungeon info Notch dragon_test` |
| `/hjhadmin dungeon resetdrop <玩家> <副本ID> <物品ID>` | 重置玩家某物品掉落记录，同时清空对应保底垫数。 | `/hjhadmin dungeon resetdrop Notch dragon_test huishi` |
| `/hjhadmin dungeon addclear <玩家> <副本ID> [数量]` | 增加通关数，并同步增加可开箱次数。 | `/hjhadmin dungeon addclear Notch dragon_test 1` |
| `/hjhadmin dungeon addopen <玩家> <副本ID> [数量]` | 增加历史开箱数。 | `/hjhadmin dungeon addopen Notch dragon_test 1` |
| `/hjhadmin dungeon addavail <玩家> <副本ID> [数量]` | 额外赠送可开箱次数，不影响历史通关数。 | `/hjhadmin dungeon addavail Notch dragon_test 5` |

---

## ⚕️ 6. 医术系统 (Medical)

用于医术秘籍、绘制台和医术试炼记录管理。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin medical <技能ID>` | 获取指定 ID 的医术秘籍。 | `/hjhadmin medical yuhehua` |
| `/hjhadmin getstation` | 获取医术绘制台方块。 | `/hjhadmin getstation` |
| `/hjhadmin medicaltest <玩家> view` | 查看目标玩家已完成的医术试炼 ID 列表。 | `/hjhadmin medicaltest Notch view` |
| `/hjhadmin medicaltest <玩家> add <试炼ID>` | 增加玩家某项医术试炼完成记录并保存。 | `/hjhadmin medicaltest Notch add shanshenmiao` |
| `/hjhadmin medicaltest <玩家> remove <试炼ID>` | 移除玩家某项医术试炼完成记录并保存。 | `/hjhadmin medicaltest Notch remove shanshenmiao` |

---

## 🧪 7. 炼丹、重华晶与术士阵法

用于特定职业玩法、功能方块和阵法等级的管理。

| 系统 | 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- | :--- |
| **炼丹** | `/hjhadmin alchemy getcauldron` | 获取一口特殊的“冶药锅”方块。 | `/hjhadmin alchemy getcauldron` |
| **术士阵法** | `/hjhadmin getarrayblock` | 获取特殊的阵法升级紫水晶块。 | `/hjhadmin getarrayblock` |
| **阵法等级** | `/zfset setlevel <玩家> <元素类型> <等级>` | 设置玩家五行阵法等级，元素类型为 `METAL`、`WOOD`、`WATER`、`FIRE`、`EARTH`，等级为 `1-5`。 | `/zfset setlevel Notch FIRE 3` |
| **重华晶** | `/hjhadmin chonghua crystal <区域>` | 获取主界面区域传送门方块，可选：`EAST`、`SOUTH`、`WEST`、`NORTH`。 | `/hjhadmin chonghua crystal EAST` |
| **重华晶** | `/hjhadmin chonghua checkin <地点ID>` | 获取特定地点的打卡点方块。 | `/hjhadmin chonghua checkin east_01` |

> **提示**：`/hjhadmin alchemy list/give` 当前只出现在帮助或补全逻辑中，源码里实际处理的炼丹子指令只有 `getcauldron`。

---

## 🔨 8. 锻造系统 (Dz)

用于锻造台获取、配方编辑、配置重载以及玩家锻造数据修改。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhdz station` | 获取锻造台方块。 | `/hjhdz station` |
| `/hjhdz edit` | 打开锻造配方编辑/管理 GUI。 | `/hjhdz edit` |
| `/hjhdz reload` | 重载锻造等级配置和配方。 | `/hjhdz reload` |
| `/hjhdz admin set <玩家> level <数值>` | 设置玩家锻造等级。 | `/hjhdz admin set Notch level 10` |
| `/hjhdz admin set <玩家> exp <数值>` | 设置玩家锻造经验。 | `/hjhdz admin set Notch exp 1000` |
| `/hjhdz admin set <玩家> license <数值>` | 设置玩家锻造资质 ID。 | `/hjhdz admin set Notch license 3` |

---

## 🌿 9. 开物术系统 (KaiWu)

用于开物术节点配置重载和玩家开物术数据修改。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhkw reload` | 重载开物术节点配置。 | `/hjhkw reload` |
| `/hjhkw setlevel <玩家> <等级>` | 设置玩家开物术等级。 | `/hjhkw setlevel Notch 5` |
| `/hjhkw setenergy <玩家> <数值>` | 设置玩家开物术精力。 | `/hjhkw setenergy Notch 100` |

> **权限提示**：`plugin.yml` 中 `/hjhkw` 写的是 `hjh.op`，但命令代码实际检查 `hjh.kaiwu.op`。如果权限插件无法执行，请同时检查这两个权限节点。

---

## 🌍 10. 世界工具 (传送与刷怪)

用于地图搭建时的传送触发器、刷怪点配置以及测伤测试。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin gettp <方块材质> <传送点ID>` | 制作传送触发方块。玩家交互该方块后会传送到指定 ID 配置的坐标。 | `/hjhadmin gettp STONE_PRESSURE_PLATE town_center` |
| `/hjhadmin spawner get <MobID> [x] [y] [z]` | 获取定点刷怪笼。不填坐标时绑定管理员当前所在位置。 | `/hjhadmin spawner get ceshijiangshi` |
| `/hjhadmin spawner button <MobID> [x] [y] [z]` | 获取手动测试方块。放置后右键生成指定怪物，并带 3 秒冷却。 | `/hjhadmin spawner button qinglongshiwei 100 64 100` |
| `/hjhadmin spawner fast <MobID>` | 获取快速铺怪笼。放置时按实际位置动态绑定刷怪点。 | `/hjhadmin spawner fast ceshijiangshi` |
| `/testmob <血量> [护甲]` | 在当前位置生成测伤人偶。 | `/testmob 1000 50` |
| `/testmob clear [半径]` | 清除附近带专属标签的测伤人偶，默认半径 10。 | `/testmob clear 20` |

---

## 🧾 11. 独立命令速查

以下命令不是 `/hjhadmin` 子指令，但也属于常用管理或测试入口。

| 指令入口 | 权限/限制 | 说明 |
| :--- | :--- | :--- |
| `/hjhadmin` | `OP` / `hjh.admin` | 管理员总控入口。 |
| `/hjh` | `hjh.admin` | 当前用于 `/hjh resourcereload`。 |
| `/hjhdz` | `hjh.admin`，部分子命令还要求 `OP` | 锻造系统入口。 |
| `/zfset` | `hjh.admin` | 五行阵法等级管理入口。 |
| `/hjhweapon` | `hjh.op` 且代码检查 `OP` | 武器获取与重载入口。 |
| `/hjhkw` | `hjh.op` / `hjh.kaiwu.op` | 开物术管理入口。 |
| `/testmob` | `hjh.op` 且代码检查 `OP` | 测伤人偶生成与清理。 |
| `/hjhstats` | 玩家指令 | 查看自己的属性面板。 |

---

## 📝 12. 已知差异与维护提醒

| 项目 | 说明 |
| :--- | :--- |
| `/hjhadmin alchemy list/give` | 补全逻辑里有提示，但当前源码没有实际执行分支。 |
| `/hjhkw` 权限 | `plugin.yml` 与代码检查的权限节点不一致，建议后续统一。 |
| `/jobtrial` | `JobTrialManager` 中尝试注册 `/jobtrial`，但 `plugin.yml` 当前未声明该命令，因此默认不可用。 |
| 数据库配置 | `config.yml` 保留了 MySQL 字段，但当前 `DatabaseManager` 实际连接 SQLite。 |
