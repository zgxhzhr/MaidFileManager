# Maid File Manager

Maid File Manager 是一个用于 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）模组的辅助工具，允许将女仆实体的完整数据导出为 `.maid` 文件，并在不同存档或不同 Minecraft 版本之间导入恢复。

## 功能特性

- **女仆数据导出**：将驯服的女仆实体导出为 `.maid` 文件，保存完整 NBT 数据
- **女仆数据导入**：从 `.maid` 文件导入女仆，保留原有属性
- **跨版本搬运**：支持在 Minecraft 1.20 ↔ 1.21 之间搬运女仆数据（NBT 标签跨版本一致）
- **药水效果跨版本迁移**：导出时保存原始药水数据 + 跨版本归一化重建数据；导入时按版本恢复药水效果，支持跨版本兼容
- **万法皆通附属保护**：禁药水模式下，按药水效果 ID 白名单（11 种万法皆通结构女仆固定的永久常驻效果）+ Duration=-1 双条件判定，保留常驻 buff，仅清理普通药水效果。不依赖女仆实体身份判定
- **AI 人设保留**：完整搬运女仆的 AI 对话人设（CustomSetting）
- **聊天历史保留**：搬运女仆的聊天历史（MaidHistoryChat，最多 512 条）+ 压缩摘要（MaidHistorySummary）
- **渡劫标记保留**：正确处理被雷劈渡劫的女仆（+20 HP 上限）
- **血量恢复**：导入时从源 NBT 恢复女仆实际血量，避免被重置为默认值
- **饰品导入**：导入时可选择恢复饰品（仅车万本体 + 万法皆通附属命名空间，全新无附魔耐久）
- **进度保留**：导入时可选择保留女仆的进度成就数据
- **导入后删除源文件**：导入 Tab 新增"导入后删除文件"开关（默认关闭），开启后仅删除服务端确认生成成功的文件，失败或被禁止的文件保留
- **服务端统一导出**：OP 可一键导出所有在线玩家的女仆，按玩家分组保存到服务端磁盘

## 支持版本

| Loader | Minecraft 版本 | JAR 文件名 |
|--------|---------------|-----------|
| Fabric | 1.20 | maid_file_manager-fabric-1.20-1.3.0.jar |
| Fabric | 1.20.1 | maid_file_manager-fabric-1.20.1-1.3.0.jar |
| Fabric | 1.21 | maid_file_manager-fabric-1.21-1.3.0.jar |
| Fabric | 1.21.1 | maid_file_manager-fabric-1.21.1-1.3.0.jar |
| Forge | 1.20 (46.x) | maid_file_manager-forge-1.20-1.3.0.jar |
| Forge | 1.20.1 (47.x) | maid_file_manager-forge-1.20.1-1.3.0.jar |
| NeoForge | 1.21 | maid_file_manager-neoforge-1.21-1.3.0.jar |
| NeoForge | 1.21.1 | maid_file_manager-neoforge-1.21.1-1.3.0.jar |

> 注：Forge 1.20 和 1.20.1 的 JAR 可以互换使用（API 兼容）。Fabric 1.20 与 1.20.1 同理。

## 安装

1. 安装对应版本的 Fabric / Forge / NeoForge
2. 安装 Touhou Little Maid 模组（前置依赖）
3. 将 Maid File Manager 的 JAR 文件放入 `.minecraft/mods` 文件夹
4. 启动游戏

## 使用方法

1. 右键打开 Maid File Manager 面板
2. **导出**：在女仆列表中勾选要导出的女仆，点击导出按钮，选择保存位置
3. **导入**：在文件列表中勾选要导入的 `.maid` 文件，点击导入按钮，女仆会在玩家附近生成
4. **导入后删除文件**：在导入 Tab 勾选"导入后删除文件"开关，导入成功后自动删除源文件（仅删除服务端确认生成成功的文件）
5. **统一导出（仅 OP）**：OP 可点击"统一导出"按钮，导出所有在线玩家的女仆到服务端磁盘

## 版本历史

### v1.3.0
- 新增：导入后删除源文件功能（默认关闭，仅删除服务端确认生成成功的文件）
- 新增：药水效果跨版本迁移（导出保存双份数据，导入按版本恢复，支持万法皆通常驻 buff 保护）
- 新增：饰品导入开关（仅车万本体 + 万法皆通，全新无附魔）
- 新增：进度导入开关
- 新增：Fabric 平台支持（1.20 / 1.20.1 / 1.21 / 1.21.1）
- 新增：服务端统一导出全员功能（仅 OP）
- 新增：服务端配置同步（允许导入 / 允许饰品 / 允许进度 / 允许药水效果）
- 改造：万法皆通药水效果判定逻辑——删除女仆身份判定（实体类型命名空间/持久化标记/复合条件），改为「效果 ID ∈ 白名单（11 种万法皆通结构女仆固定的永久常驻效果：minecraft:regeneration/strength/resistance/speed、irons_spellbooks:vigor/blight/true_invisibility/abyssal_shroud、goety:save_effects/leeching、youkaishomecoming:native_god_bless）AND Duration=-1」的纯效果判定。`MaidFileData.spellMaid` 字段与 `[万法皆通附属]` 文件名前缀一并删除，旧 v4 .maid 文件含 `spell_maid` 键时读取时忽略（向后兼容）

### v1.1.2
- 修复：导入后女仆血量被重置为 20 的问题（从源 NBT 恢复 Health 字段）
- 修复：导入后女仆残留生命恢复2药水效果的问题（导出删 + 导入清双保险）
- 修复：1.21/1.21.1 列表点击任意女仆都只能选中第一个的 UI bug（isMouseOver 缺少 mouseY 行高范围判断）

### v1.1.1
- 修复：渡劫（被雷劈）女仆导入后少 20 HP 且重新劈不生效
- 修复：渡劫女仆 maxHealth 被 validateMaidAttributes 硬 cap 80 截断
- 新增：AI 对话人设（CustomSetting）+ 聊天历史（MaidHistoryChat）+ 压缩摘要（MaidHistorySummary）跨版本导入导出
- 修复：UI 面板窄化、全域点击、四段式跑马灯、滚动条定位

### v1.0.0
- 初始版本：女仆数据导出/导入基础功能

## 致谢

- [TartaricAcid](https://github.com/TartaricAcid) - Touhou Little Maid 模组作者

## 许可证

CC0-1.0 (Creative Commons Zero / 公共领域 dedication)
