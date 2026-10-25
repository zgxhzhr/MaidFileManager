# Maid File Manager — Minecraft 26.1.2 (Fabric)

> **状态：Snapshot 测试（`1.7.0-snapshot`）**
> TLM（车万女仆）26.1.2 目前发布的是 snapshot 版本，本端随之标记为 snapshot。
> 1.20.1 / 1.21.1 的四个工程与本工程**互不影响**，版本号也不共用。
> 26.1.2 的 NeoForge 端（`maid_file_manager-neoforge-26.1.2`）与本工程共享同一份 common 代码。

女仆档案管理：把车万女仆的自动备份导出为 `.maid` 文件，并可跨存档 / 跨服务器导入。

## 目标环境

| 项 | 值 |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.2 |
| Fabric API | 0.149.1+26.1.2 |
| Java | 25（Gradle toolchain） |
| Loom | 1.16-SNAPSHOT |
| 前置 | 车万女仆（Touhou Little Maid）26.1.2 snapshot |

## 构建

```powershell
.\gradlew.bat :fabric:build --offline --no-daemon
```

产物：`fabric/build/libs/maid_file_manager-fabric-26.1.2-1.7.0-snapshot.jar`

依赖已缓存在本机 Gradle 仓库，可离线构建。TLM 编译期依赖为 `libs/touhoulittlemaid-fabric-26.1.2.jar`
（`compileOnly`，不打进产物；运行期由 TLM 本体提供）。`libs/` 不入库（22MB）。

构建期会自动执行 `verifyAssets`：资源 JSON 严格性 / `PROTOCOL_VERSION` / 通道 ID 唯一性 /
写盘关键路径 / 6 种语言键集一致性。

## 移植状态

详见 `PORTING-26.1.md`（含 26.1 API 变化对照表、GUI 渲染架构迁移记录、实机联调修复清单）。

| 层 | 状态 |
|---|---|
| 工程骨架 / 构建 / 坐标 / 包名 | ✅ |
| `common`：spi / platform / data / network / service / config | ✅ 编译通过 |
| `client`（GUI，约 8,000 行） | ✅ 编译通过 + 实机可用 |
| TLM 备份新 `index.dat`（Component Codec）适配 | ✅ |
| 备份目录新位置（`dimensions/<ns>/<path>/data/`） | ✅ |
| 语言文件（6 种） | ✅ |
| 服务端 / 客户端运行时启动验证 | ✅ 无崩溃 |

## 26.1 相对 1.20.1 / 1.21.1 的功能差异

| 功能 | 状态 | 说明 |
|---|---|---|
| 导出 / 导入 / 备份浏览 | ✅ 完整 | |
| AI 聊天记录随 `.maid` 迁移 | ✅ 完整 | 26.1 走 `MaidAIChatData.read(ValueInput)` |
| 饰品（女仆自带饰品栏） | ✅ 完整 | 同版本交给 TLM 反序列化；跨版本经 DFU 升级后重注入 |
| 手持 / 护甲物品 | ✅ 按设计不携带 | 26.1 装备键为 `equipment` |
| 女仆档案照片显示 | ✅ 完整 | 26.1 的 `blit` 直接接收目标矩形，无需 PoseStack 变换 |
| Ctrl 多选 / 加速滚动 | ✅ 完整 | 26.1 用 `InputConstants.isKeyDown(Window, key)` 直接查键状态 |

## 与其它分支的关系

| 工程 | MC | 加载器 | 版本 |
|---|---|---|---|
| `maid_file_manager-forge-1.20.1` | 1.20.1 | Forge | 1.7.0 |
| `maid_file_manager-fabric-1.20.1` | 1.20.1 | Fabric | 1.7.0 |
| `maid_file_manager-neoforge-1.21.1` | 1.21.1 | NeoForge | 1.7.0 |
| `maid_file_manager-fabric-1.21.1` | 1.21.1 | Fabric | 1.7.0 |
| **本工程** | **26.1.2** | **Fabric** | **1.7.0-snapshot** |

## 许可

代码与文档为 MIT（见 `LICENSE`），但随包曲目 `ui_music.ogg` 为保留所有权利，见 `NOTICE`。