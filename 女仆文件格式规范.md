# .maid 文件格式规范

**规范版本**：对应格式版本 7（MaidFileManager 1.6.0）
**文档性质**：格式权威定义。本文档描述的所有读取行为均以 MaidFileManager 实现为准；产出方与读取方均应遵循本文档的必选/可选约定。

---

## 1. 概述

`.maid` 是 MaidFileManager（MODID：`maid_file_manager`）定义的女仆数据交换格式，用于在以下场景之间完整迁移《Touhou Little Maid》（车万女仆，下称 TLM）的女仆实体数据：

- 同一 Minecraft 版本内跨存档迁移；
- 跨 Minecraft 大版本迁移（1.20.x ↔ 1.21.x）；
- 跨整合包迁移。

### 1.1 物理存储

- 文件内容为 **gzip 压缩的 NBT 二进制**（与 Minecraft `NbtIo.writeCompressed` 产物一致）；
- 扩展名为 `.maid`；
- NBT 根标签为复合标签（`CompoundTag`），其结构见第 2 节。

### 1.2 设计原则

1. **实体数据原样承载**：女仆实体 NBT 整体放入 `data` 字段，同版本导入时直接经 `EntityMaid.load` 恢复，保证导入结果是完整的 TLM 女仆；
2. **元信息离散化**：版本、主人、模型、时间等关键信息同时以独立顶层字段存在，使跨版本迁移不依赖对实体内部结构的猜测；
3. **单文件自足**：单个文件包含描述女仆身份与来源所需的全部信息，不依赖任何外部索引；
4. **可扩展**：`extras` 字段为其他附属模组提供按标识符隔离的标准化扩展区（见第 6 节）；
5. **向后兼容**：读取端对历史版本文件、缺少可选字段的文件一律容错（见第 8 节）。

---

## 2. 顶层结构

| 字段 | NBT 类型 | 必选性 | 含义 |
|---|---|---|---|
| `format_version` | `Int` | 必选 | 格式自身的版本号，与 Minecraft 数据版本无关；当前为 `7` |
| `exported_at` | `Long` | 必选 | 产出时间，Unix 时间戳（毫秒） |
| `source_mc_version` | `String` | 必选 | 产出时的 Minecraft 版本字符串，如 `1.21.1` |
| `source_tlm_version` | `String` | 必选（允许空串） | 产出时的 TLM 模组版本字符串 |
| `mod_id` | `String` | 必选 | 产出工具的标识；MaidFileManager 产出为 `maid_file_manager` |
| `data_version` | `Int` | 必选 | Minecraft 数据版本编码，取值见第 7.2 节 |
| `tamed` | `Byte`（布尔） | 必选 | 女仆在产出时是否已驯服 |
| `owner_uuid` | `String` | 可选 | 主人玩家的 UUID 字符串；未驯服时省略 |
| `source_maid_uuid` | `String` | 可选 | 源女仆实体的 UUID 字符串；格式版本 6 起写入 |
| `owner_name` | `String` | 可选 | 主人玩家名称 |
| `data` | `Compound` | 必选 | 女仆实体 NBT，语义见第 3 节 |
| `model_id` | `String` | 可选 | 模型标识，如 `touhou_little_maid:hakurei_reimu` |
| `display_name` | `String` | 可选 | 模型显示名称，用于文件命名与界面展示 |
| `custom_name` | `String` | 可选 | 女仆自定义名称（命名牌所取） |
| `advancements` | `Compound` | 可选 | TLM 成就数据，格式版本 3 起；结构见第 4 节 |
| `effects` | `Compound` | 可选 | 药水效果数据，格式版本 4 起；结构见第 5 节 |
| `extras` | `Compound` | 可选 | 附属模组扩展数据，格式版本 5 起；结构见第 6 节 |
| `profile` | `Compound` | 可选 | 女仆档案快照（照片 / 职业 / 生日 / 个人资料 / 偏好 / 背景故事），格式版本 7 起；结构见第 12 节 |

### 2.1 结构示例

```text
{
  format_version: 7,
  exported_at: 1780000000000L,
  source_mc_version: "1.21.1",
  source_tlm_version: "1.5.3",
  mod_id: "maid_file_manager",
  data_version: 1210100,
  tamed: 1b,
  owner_uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  source_maid_uuid: "1f2e3d4c-5b6a-7988-97a6-b5c4d3e2f100",
  owner_name: "Steve",
  data: { /* 女仆实体 NBT，见第 3 节 */ },
  model_id: "touhou_little_maid:hakurei_reimu",
  display_name: "博丽灵梦",
  custom_name: "灵梦",
  advancements: { /* 见第 4 节 */ },
  effects: { /* 见第 5 节 */ },
  extras: { /* 见第 6 节 */ },
  profile: { /* 女仆档案快照，见第 12 节 */ }
}
```

---

## 3. `data`：女仆实体 NBT

### 3.1 内容

`data` 为 TLM `EntityMaid` 经 `saveWithoutId` 序列化的实体根 NBT，包含 TLM `addAdditionalSaveData` 写入的全部业务字段：模型、好感度、任务、饰品栏（`MaidBaubleInventory`）、日程、AI 对话数据等。

### 3.2 产出端清理

MaidFileManager 产出时对 `data` 执行以下处理：

1. **物品恒清空**：`MaidInventory`、`MaidHideInventory`、`MaidTaskInventory` 内的 `Items` 列表清空；`HandItems`、`ArmorItems`、`HandDropChances`、`ArmorDropChances`、`MaidBackpackData` 移除；
2. **药水效果提取**：`ActiveEffects` 从实体 NBT 移出，转存到顶层 `effects` 字段（见第 5 节）；
3. **运行态复位**：`MaidTask` 重置为空闲任务，`Sitting` 置 0。

> 饰品栏 `MaidBaubleInventory` 不在清理范围内：是否随女仆保留由导出/导入配置决定，饰品物品原样保留。

### 3.3 读取端迁移（NbtMigration）

导入时读取端在 `data` 的**副本**上执行迁移，不修改文件原始内容。迁移分两类。

#### 3.3.1 与版本无关的卫生清理（任何来源文件一律执行）

**运行时状态标签**（删除）：

```text
Health, HurtTime, DeathTime, HurtByTimestamp,
Fire, Air, LifeTicks, PortalCooldown, FallDistance,
FallFlying, NoGravity, Glowing, Invulnerable,
HasVisualFire, TicksFrozen, FrozenTicks,
Attributes, ActiveEffects, Effects, AbsorptionAmount,
SleepingX, SleepingY, SleepingZ, Brain, Saddle,
Bukkit.updateLevel, Bukki.values,
Motion, Rotation, FallHurtDistance,
Leash, UUID, UUIDLeast, UUIDMost,
Pos,
CanPickUpLoot,
Passengers
```

**物品容器标签**（删除，由目标端重建合法空容器）：

```text
MaidInventory, MaidHideInventory, MaidTaskInventory,
BackpackData, MaidBackpackData,
HandItems, ArmorItems, HandDropChances, ArmorDropChances
```

**日程与家园位置标签**（删除，位置由导入主流程按出生点重建）：

```text
MaidSchedulePos, MaidWorkPos, MaidIdlePos, MaidSleepPos,
MaidRestrictPos, MaidRestrictCenter, MaidRestrictRadius,
BedPosition, HomePos
```

#### 3.3.2 仅在确为跨版本时执行的结构改名

| 来源 → 目标 | 改名 |
|---|---|
| 1.20.x → 1.21.x | 实体根 `ModelId` → `model_id`；移除 `YsmModelName` |
| 1.21.x → 1.20.x | 实体根 `model_id` → `ModelId` |

仅当来源与目标版本都可明确识别且确实不同时执行；同版本导入不改名。

---

## 4. `advancements`：成就数据

### 4.1 结构

```text
{
  "<完整成就 ID>": {
    criteria: [ "<已达成条件名>", ... ]    // StringTag 列表
  },
  ...
}
```

- 键为成就的完整 ID（命名空间:路径），如 `touhou_little_maid:challenge/maid_100_healthy`；
- 值中的 `criteria` 为该成就已完成条件（criterion）的名称列表。

### 4.2 收集规则

1. 仅收集命名空间为 `touhou_little_maid` 的成就；
2. 仅收集源玩家已完成（progress 为 done）的成就；
3. **属性绑定型成就按当前女仆实际属性过滤**：

   | 成就路径 | 收集条件 |
   |---|---|
   | `favorability/favorability_increased_max` | 女仆好感度 ≥ 384 |
   | `challenge/maid_100_healthy` | 女仆最大生命值 ≥ 100 |
   | `challenge/lightning_bolt` | 女仆带有渡劫标记 |

   其余 TLM 成就为事件触发型，无法按女仆属性过滤，已完成即收集；
4. **开局赠送型进度一律排除**，路径为：

   ```text
   give_smart_slab
   grant_book_on_first_join
   grant_patchouli_book
   ```

   排除理由：这类进度的奖励物品（女仆魂符、指引手册）受 TLM 配置开关守卫，直接补发会绕过开关；且不同 Minecraft 版本中手册进度的路径不同（1.20.x 为 `grant_book_on_first_join`，1.21.x 为 `grant_patchouli_book`），故按路径过滤。

### 4.3 合并规则

导入时把成就合并到目标玩家，策略为**只补未完成**：

1. 目标玩家已完成的成就不动；
2. 未完成的成就按 `criteria` 列表逐项授予；
3. 目标服务端不存在的成就 ID 安全跳过（跨版本兼容）；
4. 第 4.2 节第 4 条的开局赠送型路径在合并端同样排除，旧文件包含时也不补发。

---

## 5. `effects`：药水效果数据

### 5.1 结构

```text
{
  active_effects: [ <MobEffectInstance NBT>, ... ],   // 原始 NBT 列表
  normalized: [                                       // 标准化列表
    {
      id: "minecraft:speed",          // 效果完整 ID（String）
      amplifier: 1,                  // 等级（Int）
      duration: 1200,                // 时长（tick，Int）
      ambient: 0b,                   // 是否信标环境效果（Byte）
      show_particles: 1b,            // 是否显示粒子（Byte）
      show_icon: 1b                  // 是否显示图标（Byte）
    },
    ...
  ]
}
```

### 5.2 双路径语义

| 路径 | 用途 | 可靠性 |
|---|---|---|
| `active_effects` | 保存产出时 `MobEffectInstance` 的原始序列化 NBT，同版本导入时直接反序列化 | 同版本可靠；跨版本可能因字段名/数字 ID 变化而失败 |
| `normalized` | 不依赖数字 ID 的标准化表示，按效果完整 ID 经注册表重建 | 跨版本可靠；目标世界未注册的效果跳过 |

`normalized` 中效果 ID 的解析规则：优先读取字符串 ID（含加载器扩展的命名空间键），缺失时按产出端注册表由数字 ID 反查；两列表按索引一一对应。

导入端先尝试 `active_effects` 直读，失败时按相同索引回退到 `normalized` 重建。是否实际恢复药水效果受服务端配置控制；禁止药水的服务器上，合法的常驻效果子集由平台层持久化保存，不因清效果而丢失。

---

## 6. `extras`：附属模组扩展数据

### 6.1 结构

```text
{
  "<提供者标识>": <CompoundTag，内容由该附属模组自定义>,
  ...
}
```

- 键为迁移提供者（`MaidMigrationProvider`）的唯一标识（命名空间:路径）；
- 值为该附属模组自行序列化的数据复合标签，格式由其自行定义并自行负责版本兼容；
- MaidFileManager 不解释 `extras` 中各值的内部结构，仅按标识路由。

### 6.2 SPI 约定

附属模组通过实现 MaidFileManager 的迁移服务提供者接口（SPI）接入：

| 接口方法 | 职责 |
|---|---|
| `getId()` | 返回唯一标识，作为 `extras` 的键 |
| `getDependencyModId()` | 返回软依赖模组 ID，用于可用性检测；空串表示无特定依赖 |
| `export(EntityMaid)` | 导出：从女仆或其关联存储收集数据，返回复合标签；无数据返回 `null` |
| `importData(EntityMaid, CompoundTag)` | 导入：女仆已加入世界、UUID 已确定后调用，把数据写回女仆或关联系统 |

SPI 的隔离保证：

1. **异常隔离**：单个提供者导出/导入失败只记录并跳过，不阻断整体流程；
2. **软依赖**：依赖模组未加载时该提供者自动跳过，不报错；
3. **数据位置透明**：接口不限制数据存储位置（实体 NBT、玩家持久数据、独立 SavedData、Capability 等均可），由提供者自行处理 UUID 变化带来的关联重映射；
4. **无冗余约定**：已存在于女仆实体 NBT 上的数据不应再经 `extras` 重复存储。

---

## 7. 版本与版本号

### 7.1 格式版本（`format_version`）历史

| 格式版本 | 要点 |
|---|---|
| 1–2 | 早期版本：实体 NBT 壳与离散元信息的基本结构 |
| 3 | 新增 `advancements` 成就字段 |
| 4 | 新增 `effects` 药水效果字段；早期临时键 `spell_maid` 已废弃，读取时忽略 |
| 5 | 新增 `extras` 扩展字段与 SPI 机制 |
| 6 | 实体根 NBT 不再携带 `UUID`/`UUIDLeast`/`UUIDMost`（迁移时统一删除）；源女仆 UUID 以顶层 `source_maid_uuid` 记录；`source_health_base`/`source_attack_base` 曾短暂存在后废弃，读取时忽略 |
| 7（当前） | 新增顶层 `profile` 女仆档案快照字段（照片 / 职业 / 生日 / 个人资料 / 偏好 / 背景故事）；`story_snapshot` 仅为背景故事的冗余快照，权威内容仍是女仆实体的 `MaidAIChat.CustomSetting` |

### 7.2 数据版本（`data_version`）编码

`data_version` 是 Minecraft 版本的紧凑整数编码，用于跨版本迁移判定，取值：

| 值 | 含义 |
|---|---|
| `1200100` | Minecraft 1.20 / 1.20.1 |
| `1200600` | Minecraft 1.20.5 / 1.20.6 |
| `1210000` | Minecraft 1.21 |
| `1210100` | Minecraft 1.21.1 |
| `-1` | 版本未知：读取端只执行与版本无关的卫生清理，不做结构改名 |

版本未知时不做任何基于版本的猜测性转换。

### 7.3 导入实体 UUID 规则

**源女仆 UUID 解析顺序**：

1. 顶层 `source_maid_uuid`；
2. 实体 NBT（`data`）的 `UUID` 键；
3. 实体 NBT 的 `UUIDMost` + `UUIDLeast`；
4. 均无法解析时，放弃派生，使用构造器随机 UUID。

**目标实体 UUID**：当源女仆 UUID 可解析时，目标实体 UUID 由以下字节串经 **UUIDv3（MD5 命名派生）**确定性生成：

```text
maid_file_manager|import-v1|<源女仆 UUID>|<导入玩家 UUID>
```

效果：同一玩家对同一文件的任意次导入，目标实体 UUID 恒定；不同玩家导入同一文件时因玩家 UUID 参与派生而互不冲突。该 UUID 在 `EntityMaid.load` 之前设置；迁移后的 NBT 已删除 UUID 键，加载不会反向覆盖。

### 7.4 属性基础值策略

导入实体的生命值、攻击力基础值**无条件回归 TLM 白板基础值**（含渡劫加成），不保留源存档的基础值；全局生物属性倍率类模组造成的基础值污染不随文件迁移。饰品、词条等合法加成以临时属性修饰符形式在实体加入世界后自动重新附加。

实体加入世界后启动 **20 tick（约 1 秒）血量校准窗口**：每 tick 按当前最大生命值校准，只抬高、不扣减，以追上任意时刻附加的修饰符。

---

## 8. 读取端容错规则

读取端遵循以下兼容规则（对历史文件及按本规范子集产出的文件均适用）：

| 情形 | 读取行为 |
|---|---|
| `format_version` 缺失或非整数 | 按当前最新格式版本解释 |
| `mod_id` 缺失 | 视为 `maid_file_manager` |
| `data_version` 缺失 | 先按 `source_mc_version` 推导；若根标签存在大写 `DataVersion` 整数键，则读取该键 |
| `model_id` 缺失 | 从 `data` 实体 NBT 回退读取（`model_id` 优先，其次 `ModelId`），仅用于展示 |
| 任一可选字段（`owner_uuid`、`owner_name`、`advancements`、`effects`、`extras`、`profile` 等）缺失 | 视为空，不报错 |
| UUID 字符串格式非法 | 该字段视为缺失，继续尝试下一解析来源 |
| `effects` 缺失但实体 NBT 含 `ActiveEffects` 列表 | 将该列表作为原始效果提取（仅同版本直读可靠，无标准化跨版本路径） |
| 已废弃键（`spell_maid`、`source_health_base`、`source_attack_base`）存在 | 直接忽略 |
| NBT 整体无法解析/解压 | 该文件判定为不可读，给出明确失败反馈，不静默跳过 |

---

## 9. 文件命名与目录约定

### 9.1 导出文件名

MaidFileManager 导出的文件名规则：

```text
<名称>_<yyyyMMdd-HHmmss>_<短 UUID>.maid
```

- `<名称>`：存在自定义名称时为「自定义名称_模型显示名」，否则为模型显示名；模型显示名缺失时回退为模型 ID 的路径段；
- `<短 UUID>`：主人或女仆 UUID 去除连字符后的前 8 位，无法获取时为 `nouuid`；
- 文件名中的非法字符（`\ / : * ? " < > | [ ]` 及控制字符）替换为下划线，并折叠连续下划线；
- 同一目录重名时追加 `_1`、`_2` 递增。

读取端解析文件名时间戳时，同时接受**纯时间戳命名** `yyyy-MM-dd-HH-mm-ss.maid`。

### 9.2 目录

| 目录 | 用途 |
|---|---|
| `maid_file/maid_exports/` | 导出文件写入目录 |
| `maid_file/maid_imports/` | 导入文件来源目录：玩家把 `.maid` 文件放入此处后由游戏内界面读取 |
| `maid_file/photos/` | 女仆档案头像图片目录（不属 `.maid` 格式，仅供档案界面挑选后打包进档案） |

导出与导入目录严格分离，三者统一收纳在游戏根目录下的 `maid_file/` 内。旧版本散落在根目录的 `maid_exports/`、`maid_imports/` 与 `config/maid_file_manager/photos/`，会在启动时**一次性自动搬运并入** `maid_file/` 下：目标不存在时整目录改名移动，已存在时逐项合并、同名文件保留新目录版本，迁移结束后清理已搬空的旧目录（幂等，无旧目录则跳过）。

本地联机中导出文件写入客户端游戏目录；服务器统一导出按玩家名分子目录保存于服务器端，并对玩家名做目录穿越防护。

---

## 10. 安全模型

1. **物品恒清空**：任何来源的文件导入时物品容器一律清空/重建，防止跨存档复制物品；
2. **运行态不迁移**：生命值、死亡状态、属性修饰符、骑乘者等运行时标签不进入目标世界；
3. **UUID 确定性派生**：目标实体 UUID 可重算、可核对，跨玩家不冲突；同 UUID 实体能否加入世界完全交由服务端原生规则判定；
4. **服务端权限闸门**：客户端导入、饰品保留、成就转移、药水恢复均由服务端配置控制，客户端只能发起请求；
5. **原子写入**：导出先写临时文件再同目录移动替换，防止崩溃产生半截文件；
6. **解压配额**：读取文件（含本地文件）时施加显式 NBT 解压大小配额。

---

## 11. 由车万女仆自动备份导出为 .maid

除直接导出存活女仆外，MaidFileManager 亦可读取 TLM 自动产出的备份，并按本规范组装为 `.maid` 文件。TLM 的自动备份位于存档根目录下的 `data/maid_backups/<主人 UUID>/<女仆 UUID>/*.dat`（同目录的 `index.dat` 记录女仆名）；其单个备份文件的存储内容与本规范 `data` 字段同构（顶层即女仆实体 NBT）。因此备份只存在于持有存档的一端。

### 11.1 读取路径

| 场景 | 数据来源 | 说明 |
|---|---|---|
| 本机（单人世界 / 局域网主机 / 游戏主菜单） | 客户端直接读取本机存档 `saves/<存档>/data/maid_backups` | 游戏主菜单没有当前存档上下文，会合并展示本机所有存档的备份 |
| 专业服务器 | 服务端代读 | 备份位于服务器磁盘，客户端无法直读；客户端发起请求，服务端读取自身存档后回传，客户端再组装并写入本地文件。服务端只读，不写、不删任何数据 |

### 11.2 权限模型（专业服务器）

- **OP（权限等级 2）**：可见并导出全部玩家的备份；
- **非 OP**：仅可见 / 导出主人 UUID 与请求者一致（即自己名下）的备份。

### 11.3 导出产物

无论数据来自本机还是专业服务器，导出的 `.maid` 文件一律落在**客户端**的 `maid_file/maid_exports/<玩家名>/` 目录，文件名规则与普通导出一致（见第 9.1 节）。浏览列表按玩家分组、玩家下再按女仆分组，最内层为各时间点的备份文件；玩家名解析与离线玩家名匹配沿用既有机制。

---

## 12. `profile`：女仆档案快照

格式版本 7 起，`.maid` 顶层新增可选的 `profile` 复合标签，承载与游戏内「女仆档案」界面一致的档案快照。字段如下：

| 子字段 | NBT 类型 | 必选性 | 含义 |
|---|---|---|---|
| `profile_version` | `Int` | 必选 | 档案结构版本，当前为 `1` |
| `photo` | `ByteArray` | 可选 | 1:1 裁切并缩放至 128×128 的 PNG 字节；超过上限时读取端丢弃 |
| `occupation` | `String` | 可选 | 职业；空表示默认「女仆」 |
| `birthday` | `String` | 可选 | 生日 |
| `personal_note` | `String` | 可选 | 其他个人资料 |
| `preferences` | `String` | 可选 | 偏好与特长 |
| `story_snapshot` | `String` | 可选 | 背景故事的冗余快照；权威内容仍是女仆实体的 `MaidAIChat.CustomSetting`，本字段仅供无实体场景（如导入前预览）展示 |

全部子字段均可空：旧文件缺 `profile` 或缺少某些子键时，读取端一律按默认值处理。档案同时保存在女仆实体的自定义标签 `maid_file_manager:profile` 内，导出时落到顶层 `profile`，导入时随实体一并恢复。
