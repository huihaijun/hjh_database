# 🛠️ HJH Database 管理员指令手册

本手册包含了 `HJH Database` 插件的所有管理员 (`OP`) 专属指令。该指令集主要用于服务器的日常管理、系统测试、剧情推进以及副本维护等。

> **⚠️ 注意事项**
> * **权限要求**：所有 `/hjhadmin` 开头的指令均需要玩家拥有 `OP` 权限才能执行。
> * **基础指令**：输入 `/hjhadmin` 即可在游戏内查看简易版的帮助菜单。
> * **参数说明**：`< >` 表示必填参数，`[ ]` 表示可选参数。

---

## ⚙️ 1. 基础系统管理

用于重载配置或管理核心系统数据。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin reload` | 重载所有配置文件（涵盖Resource、医术、NPC数据、炼药、传送点、重华晶等）。 | `/hjhadmin reload` |


---

## 👤 2. 玩家属性与状态管理

用于直接干预玩家的等级、职业、种族和剧情状态。

> **📊 职业与种族代码映射表**
> 在系统底层和指令参数中，职业与种族对应了固定的代码映射。
> * **职业 (Job) 映射**：`0 = 战士` \| `1 = 弓箭手` \| `2 = 术士` \| `3 = 医师`
> * **种族 (Race) 映射**：`0 = 神` \| `1 = 仙` \| `2 = 人` \| `3 = 战神` \| `4 = 妖`

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin level <玩家>` | 查看目标玩家的当前等级。 | `/hjhadmin level Notch` |
| `/hjhadmin level <玩家> <set\|add> <数值>` | 修改（设定或增加）目标玩家的等级，数值必须为整数且最小为1。 | `/hjhadmin level Notch set 50` |
| `/hjhadmin job <玩家> <职业>` | 设定玩家职业。请直接输入职业名称（战士/弓箭手/术士/医师）。 | `/hjhadmin job Notch 战士` |
| `/hjhadmin race <玩家> <种族>` | 设定玩家种族。请输入种族代码（0-4）。 | `/hjhadmin race Notch 0`（设定为神族） |
| `/hjhadmin status <玩家>` | 查看玩家当前的剧情状态及描述。 | `/hjhadmin status Notch` |
| `/hjhadmin status <玩家> set <数值>` | 修改玩家的剧情状态数值并自动同步保存到数据库。 | `/hjhadmin status Notch set 2` |

## 📦 3. 物品与装备获取

用于快速获取自定义系统的物品、测试装备或个人仓库。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin get <物品ID/名称> [数量]` | 获取指定 ID 的 Resource 系统物品，默认数量为 1。 | `/hjhadmin get iron_sword 1` |
| `/hjhadmin gettestgear <玩家>` | 已废除 | `==================` |
| `/hjhadmin givetoken <玩家>` | 给予目标玩家一枚“天机令”。 | `/hjhadmin givetoken Notch` |
| `/hjhadmin getwarehouse` | 获取一个个人仓库方块（放置后生成交互点）。 | `/hjhadmin getwarehouse` |
| `/hjhadmin openwarehouse <玩家>` | 强制打开并查看目标玩家的个人仓库 GUI。 | `/hjhadmin openwarehouse Notch` |

---

## 📜 4. 任务与剧情 NPC

用于调试任务线以及生成剧情实体。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin quest <玩家> <ID> <状态> [进度]` | 修改玩家的任务状态与进度。状态可选：`LOCKED`, `IN_PROGRESS`, `COMPLETED`。 | `/hjhadmin quest Notch quest_01 IN_PROGRESS 5` |
| `/hjhadmin gennpc <ID\|ALL>` | 生成指定的剧情 NPC，或使用 `ALL` 生成所有配置的 NPC。会自动清理旧实体。 | `/hjhadmin gennpc ALL` |

---

## ⚔️ 5. 副本与宝箱系统 (Dungeon)

用于管理四大神兽试炼副本及其相关的金宝箱、掉落记录。**副本当选类型**：`qinglong`, `baihu`, `zhuque`, `xuanwu`。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin dungeon trigger <副本类型>` | 获取对应副本的试炼触发器（灵魂灯笼材质），放置后玩家右键即可触发。 | `/hjhadmin dungeon trigger qinglong` |
| `/hjhadmin dungeon set <玩家> <副本类型> <0\|1>` | 强制设置玩家某副本的通关状态（0为未完成，1为已完成）。 | `/hjhadmin dungeon set Notch baihu 1` |
| `/hjhadmin dungeon getchest <副本ID>` | 获取指定副本的金宝箱方块，直接放置即可成为奖励宝库。 | `/hjhadmin dungeon getchest qinglong_chest` |
| `/hjhadmin dungeon info <玩家> <副本ID>` | 查看玩家在某副本的通关数、历史开箱数以及剩余可开箱次数。 | `/hjhadmin dungeon info Notch qinglong` |
| `/hjhadmin dungeon resetdrop <玩家> <副本ID> <物品ID>` | 重置玩家某物品的掉落记录，用于测试“此生仅一次”或保底机制。 | `/hjhadmin dungeon resetdrop Notch qinglong rare_sword` |
| `/hjhadmin dungeon <addclear\|addopen\|addavail> <玩家> <副本ID> [数量]` | 增加玩家的通关数(`addclear`)、历史开箱数(`addopen`)或额外开箱次数(`addavail`)。 | `/hjhadmin dungeon addavail Notch qinglong 5` |

---

## ⚕️ 6. 医术系统 (Medical)

用于医术试炼、技能书及绘制台的发放。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin medical <技能ID>` | 获取指定 ID 的医术秘籍。 | `/hjhadmin medical heal_01` |
| `/hjhadmin getstation` | 获取医术绘制台方块。 | `/hjhadmin getstation` |
| `/hjhadmin medicaltest <玩家> view` | 查看目标玩家已完成的医术试炼 ID 列表。 | `/hjhadmin medicaltest Notch view` |
| `/hjhadmin medicaltest <玩家> <add\|remove> <试炼ID>` | 增加或移除玩家的某项医术试炼完成记录，并同步数据库。 | `/hjhadmin medicaltest Notch add trial_01` |

---

## 🧪 7. 炼丹、重华晶与术士阵法

特定职业及玩法的专项管理指令。

| 系统 | 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- | :--- |
| **炼丹** | `/hjhadmin alchemy getcauldron` | 获取一口特殊的“冶药锅”方块。 | `/hjhadmin alchemy getcauldron` |
| **术士** | `/hjhadmin getarrayblock` | 获取特殊的阵法升级紫水晶块。 | `/hjhadmin getarrayblock` |
| **重华晶** | `/hjhadmin chonghua crystal <区域>` | 获取主界面区域传送门方块 (黄绿粘土)。可选：`EAST`, `SOUTH`, `WEST`, `NORTH`。 | `/hjhadmin chonghua crystal EAST` |
| **重华晶** | `/hjhadmin chonghua checkin <地点ID>` | 获取特定地点的打卡点方块 (红色粘土)。 | `/hjhadmin chonghua checkin east_01` |

---

## 🌍 8. 世界工具 (传送与刷怪)

用于地图构建时的触发器及刷怪笼配置。

| 指令用法 | 功能描述 | 示例 |
| :--- | :--- | :--- |
| `/hjhadmin gettp <方块材质> <传送点ID>` | 制作一个传送触发器。玩家交互该方块后会传送至指定 ID 配置的坐标。 | `/hjhadmin gettp STONE_PRESSURE_PLATE town_center` |
| `/hjhadmin spawner get <MobID> [x] [y] [z]` | 获取一个**定点刷怪笼**。不填坐标则默认绑定为管理员输入指令时站立的坐标。 | `/hjhadmin spawner get zombie_01` |
| `/hjhadmin spawner button <MobID> [x] [y] [z]` | 获取一个**手动测试方块**。放置后右键即可生成指定的怪物，自带3秒冷却以防刷屏。 | `/hjhadmin spawner button boss_dragon 100 64 100` |
