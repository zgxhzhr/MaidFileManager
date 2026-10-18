# 界面背景音乐：回滚说明（试验性功能）

本功能为**试验性**，可整体移除，**不涉及存档数据、网络协议或 `.maid` 文件格式**。移除后旧存档、旧文件均照常使用。

## 涉及范围

| 类型 | 文件 |
|---|---|
| 新增（代码） | `common/src/main/java/io/github/zgxhzhr/maidfm/client/UiMusicManager.java` |
| 新增（资源） | `common/src/main/resources/assets/maid_file_manager/sounds.json` |
| 新增（资源） | `common/src/main/resources/assets/maid_file_manager/sounds/ui_music.ogg`（2:02，见《音乐版权声明》） |
| 新增（资源） | `common/src/main/resources/assets/maid_file_manager/sounds/LICENSE.txt` |
| 新增（文档） | `音乐版权声明.md`、本文件 |
| 修改 | `config/MaidConfigManager.java`（2 个配置键 + 字段 + load/save/getter + `parseVolume`） |
| 修改 | 6 个界面类（各 2 处：`init()` 首行 `enter`、新增 `removed()` 覆写的 `leave`） |

6 个界面类：`MaidFileManagerScreen`、`MaidBaubleImportScreen`、`MaidBaubleExtraBlockScreen`、`MaidConfigScreen`、`MaidProfileScreen`、`MaidBackupBrowserScreen`。

## 移除步骤

1. 删除上表 4 个新增文件（以及可选的 `音乐版权声明.md` / 本文件）。
2. 删除 6 个界面里的 hook：
   - `init()` 首行的 `UiMusicManager.enter(this);`
   - 整个 `removed()` 覆写方法（含 `UiMusicManager.leave(this); super.removed();`）
3. `MaidConfigManager` 中移除：
   - 常量 `KEY_UI_MUSIC` / `KEY_UI_MUSIC_VOLUME`
   - 字段 `clientUiMusic` / `clientUiMusicVolume`
   - `loadClient()` 中读取这两项的两行、`saveClient()` 中写入这两项的两行
   - 方法 `isClientUiMusic()` / `getClientUiMusicVolume()` / `setClientUiMusic()` / `parseVolume(...)`

## 一步还原

改动已单独提交（提交信息含"界面背景音乐"），直接：

```
git revert <该提交哈希>
```

即可还原，无需手工删改。

## 运行时配置（玩家侧）

客户端配置文件 `config/maid_file_manager-client.properties`：

```properties
ui_music=true
ui_music_volume=0.15
```

- `ui_music`：`false` 即**不再播放**（不必回滚代码）。
- `ui_music_volume`：`0.0` - `1.0`，越界会被钳制；设为 `0.0` 等价于静音。
