# Maid File Manager

Maid File Manager 是一个用于 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）模组的辅助工具，允许将女仆实体的完整数据导出为 `.maid` 文件，并在不同存档或不同 Minecraft 版本之间导入恢复。

## 功能特性

- **女仆数据导出**：将驯服的女仆实体导出为 `.maid` 文件，保存完整 NBT 数据
- **女仆数据导入**：从 `.maid` 文件导入女仆，保留原有属性
- **跨版本搬运**：支持在 Minecraft 1.20 ↔ 1.21 之间搬运女仆数据（NBT 标签跨版本一致）
- **AI 人设保留**：完整搬运女仆的 AI 对话人设（CustomSetting）
- **聊天历史保留**：搬运女仆的聊天历史（MaidHistoryChat，最多 512 条）+ 压缩摘要（MaidHistorySummary）
- **渡劫标记保留**：正确处理被雷劈渡劫的女仆（+20 HP 上限）
- **血量恢复**：导入时从源 NBT 恢复女仆实际血量，避免被重置为默认值
- **药水效果清理**：导入时清空残留药水效果，避免把驯服自带的 buff 带到新存档

## 支持版本

| Loader | Minecraft 版本 | JAR 文件名 |
|--------|---------------|-----------|
| Forge | 1.20 (46.x) / 1.20.1 (47.x) | maid_file_manager-forge-1.20-1.1.2.jar |
| NeoForge | 1.21 | maid_file_manager-neoforge-1.21-1.1.2.jar |
| NeoForge | 1.21.1 | maid_file_manager-neoforge-1.21.1-1.1.2.jar |

> 注：Forge 1.20 和 1.20.1 的 JAR 可以互换使用（API 兼容）。

## 安装

1. 安装对应版本的 Forge / NeoForge
2. 安装 Touhou Little Maid 模组（前置依赖）
3. 将 Maid File Manager 的 JAR 文件放入 `.minecraft/mods` 文件夹
4. 启动游戏

## 使用方法

1. 右键打开 Maid File Manager 面板
2. **导出**：在女仆列表中勾选要导出的女仆，点击导出按钮，选择保存位置
3. **导入**：在文件列表中勾选要导入的 `.maid` 文件，点击导入按钮，女仆会在玩家附近生成

## 版本历史

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
