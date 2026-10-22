# Maid File Manager — Minecraft 26.1.2 (NeoForge)

> **状态：Alpha 测试（`1.7.0-snapshot`）**
> TLM（车万女仆）26.1.2 目前发布的是 snapshot 版本，本端随之标记为 Alpha。
> 1.20.1 / 1.21.1 的四个工程与本工程**互不影响**，版本号也不共用。

女仆档案管理：把车万女仆的自动备份导出为 `.maid` 文件，并可跨存档 / 跨服务器导入。

## 目标环境

| 项 | 值 |
|---|---|
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.7-beta |
| Java | 25（Gradle toolchain） |
| Gradle | 9.2.0 |
| 前置 | 车万女仆（Touhou Little Maid）26.1.2 snapshot |

## 构建

```powershell
# JDK 25（Gradle toolchain 会自动选）
.\gradlew.bat :neoforge:build --offline --no-daemon
```

产物：`neoforge/build/libs/maid_file_manager-neoforge-26.1.2-1.7.0-snapshot.jar`

依赖已缓存在本机 Gradle 仓库，可离线构建。TLM 编译期依赖为 `libs/touhoulittlemaid-26.1.2-neoforge.jar`
（`compileOnly`，不打进产物；运行期由 TLM 本体提供）。

## 移植进度

详见 `PORTING-26.1.md`（含 26.1 API 变化对照表与构建诊断命令）。

| 层 | 状态 |
|---|---|
| 工程骨架 / 构建 / 坐标 / 包名 | ✅ |
| `common`：spi / platform / data / network / service / config | 🟡 编译错误收敛中 |
| `client`（GUI，约 8,000 行） | ⬜ 待移植 |
| TLM 备份新 `index.dat`（Component Codec）适配 | ⬜ 待做 |
| 语言文件（6 种） | ✅ 已就位 |

## 与其它分支的关系

| 工程 | MC | 加载器 | 版本 |
|---|---|---|---|
| `maid_file_manager-forge-1.20.1` | 1.20.1 | Forge | 1.6.5 |
| `maid_file_manager-fabric-1.20.1` | 1.20.1 | Fabric | 1.6.5 |
| `maid_file_manager-neoforge-1.21.1` | 1.21.1 | NeoForge | 1.6.5 |
| `maid_file_manager-fabric-1.21.1` | 1.21.1 | Fabric | 1.6.5 |
| **本工程** | **26.1.2** | **NeoForge** | **1.7.0-snapshot** |

## 许可

代码与文档为 MIT（见 `LICENSE`），但随包曲目 `ui_music.ogg` 为保留所有权利，见 `NOTICE`。
