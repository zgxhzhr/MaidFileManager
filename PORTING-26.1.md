# 26.1.2 NeoForge 移植笔记（v1.7.0-snapshot）

> 目标：把 `maid_file_manager` 移植到 Minecraft **26.1.2** / NeoForge **26.1.2.7-beta**。
> 版本号 `1.7.0-snapshot`：**TLM 26.1.2 目前是 snapshot，本端随之标记为 Alpha 测试**。
> 其它四个工程（1.20.1 forge/fabric、1.21.1 neoforge/fabric）**不动**。

## 一、工程基线

| 项 | 值 |
|---|---|
| 目录 | `E:\Mod\maid_file_manager-neoforge-26.1.2` |
| 模板来源 | `E:\Mod\MultiLoader-Template-26.1.2`（保留 build-logic，已移除 fabric 模块） |
| MC / NeoForge | 26.1.2 / 26.1.2.7-beta（NeoForm 26.1.2-1） |
| Gradle / JDK | 9.2.0 / **JDK 25**（`java_version=25`，toolchain） |
| mod id / group | `maid_file_manager` / `io.github.zgxhzhr` |
| 包名 | `io.github.zgxhzhr.maidfm` |
| TLM 依赖 | `libs/touhoulittlemaid-26.1.2-neoforge.jar`（`compileOnly`，来源：`I:\PCL\.minecraft\versions\26.1.2-NeoForge测试\mods\touhoulittlemaid-2.0.0-neoforge+mc26.1.2-snapshot.jar`） |
| 构建命令 | `.\gradlew.bat :neoforge:build --offline --no-daemon`（依赖已缓存，可离线） |
| 骨架状态 | **BUILD SUCCESSFUL**（1m24s，产物 `maid_file_manager-neoforge-26.1.2-1.7.0-snapshot.jar`） |

## 二、26.1 API 变化对照表（已实测确认）

### 1. `ResourceLocation` → `Identifier`

```java
// 1.21.1
import net.minecraft.resources.ResourceLocation;
new ResourceLocation(ns, path)
ResourceLocation.tryParse(s)
ResourceLocation.fromNamespaceAndPath(ns, path)

// 26.1
import net.minecraft.resources.Identifier;
Identifier.fromNamespaceAndPath(ns, path)   // 原 new ResourceLocation(ns, path)
Identifier.tryParse(s)
Identifier.fromNamespaceAndPath(ns, path)
```

> 已对 common 全量改写：5 处 import + 71 处引用，`ResourceLocation` 残留 0。

### 2. `CompoundTag` getter 全面 Optional 化

旧 getter 的缺键默认值（`""` / `0` / `false` / 空 Compound / 空 List）与新 `...Or` 等价，可直接替换：

| 1.21.1 | 26.1 |
|---|---|
| `contains(k, Tag.TAG_X)` | `contains(k)` |
| `getCompound(k)` → CompoundTag | `getCompoundOrEmpty(k)` |
| `getList(k, Tag.TAG_X)` → ListTag | `getListOrEmpty(k)` |
| `getString(k)` → String | `getStringOr(k, "")` |
| `getInt(k)` → int | `getIntOr(k, 0)` |
| `getBoolean(k)` → boolean | `getBooleanOr(k, false)` |
| `getLong / getDouble / getFloat / getByte / getShort` | `getXxxOr(k, 0L / 0.0 / 0.0f / (byte) 0 / (short) 0)` |
| `getByteArray(k)` → byte[] | `getByteArray(k)` → **`Optional<byte[]>`**（用 `.orElse(new byte[0])`） |
| `getIntArray / getLongArray` | 同上，返回 `Optional<...>` |
| `getAllKeys()` | `keySet()` |

`Tag.TAG_*` 常量仍在（TAG_END/BYTE/SHORT/INT/LONG/FLOAT/DOUBLE/BYTE_ARRAY/STRING/LIST/COMPOUND/INT_ARRAY/LONG_ARRAY），
**`Tag.TAG_ANY_NUMERIC` 已被移除** —— 改成 `contains(k)` 即可。

### 3. `StringTag` / `NumericTag` 变成 record / 结构调整

```java
// 1.21.1
stringTag.getAsString()
numericTag.getAsInt()

// 26.1
stringTag.value()          // StringTag 是 record StringTag(String value)
// NumericTag 不再是可 instanceof 的类型，需按具体 Tag 类型分支（ByteTag/IntTag/...）
```

### 4. 玩家消息发送

```java
// 1.21.1
player.displayClientMessage(component, false)

// 26.1
player.sendSystemMessage(component)              // 无 overlay
player.sendSystemMessage(component, true)        // overlay（actionbar）
```

### 5. TLM 侧 API 变化（`EntityMaid`）

```java
// 1.21.1
maid.getOwnerUUID()            → 26.1: maid.getOwnerReference().getUUID()   // EntityReference<LivingEntity>
maid.isYsmModel() / getYsmModelName() / getYsmModelId()  → 26.1 未找到，待确认替代方案
maid.saveWithoutId(tag)        → 26.1: maid.saveAsPassenger(TagValueOutput)
maid.getStringUUID()           → 仍可用
```

### 6. TLM 备份目录结构（重要）

**路径未变**，仍在 world 的 `dataStorage.dataFolder` 下：

```
<world>/data/maid_backups/<ownerUUID>/<maidUUID>/<yyyy-MM-dd-HH-mm-ss>.dat
<world>/data/maid_backups/<ownerUUID>/index.dat
```

**但 `index.dat` 的内容变了**：TLM 26.1 用 **Codec** 编码，字段名为
`Name` / `Pos` / `Dimension` / `Timestamp`，其中 **`Name` 不再是 String，而是 `ComponentSerialization.CODEC` 编码的组件**
（1.20.1 时 `Name` 是 String）。备份体也用 `saveAsPassenger(TagValueOutput)` 序列化，不再走 `saveWithoutId`。

我们侧需要：
1. `Name` 解析改为按 Component 结构取（`{"translate": ...}` / `{"text": ...}`）；
2. 备份 `.dat` 读取改用 `NbtIo.readCompressed(path, NbtAccounter.create(...))`；
3. 路径解析走 `server.overworld().getDataStorage().dataFolder`。

## 三、移植进度

| 层 | 状态 |
|---|---|
| 工程骨架（构建/包名/坐标/入口） | ✅ 完成，BUILD SUCCESSFUL |
| common：`spi` / `platform` / `data` / `network` / `service` / `config` | ✅ **`:common:compileJava` 通过（0 错误）** |
| `ResourceLocation` → `Identifier` | ✅ 全量改写，残留 0 |
| NBT getter Optional 化 | ✅ 全量改写（9 个文件） |
| `client` 包（12 文件 / 8,080 行） | ⬜ 未移植 |
| `MaidTransferService` 的 TLM API 适配 | ⬜ 84 处待改（getOwnerUUID / YSM 系列） |
| neoforge 端（5 文件 / 2,255 行：入口/payload/网络/平台/客户端注册） | 🟡 待移植（当前仅 2 处错误：平台实现缺 `serializeItemStack`） |
| GUI（26.1 渲染 API） | ⬜ 待移植 |
| 备份读取（新 index.dat 结构） | ⬜ 待适配 |
| 语言文件 / verifyAssets / 打包 | 🟡 语言文件已复制（6 种），verify 任务未移植 |

## 四、构建诊断命令

```powershell
cd E:\Mod\maid_file_manager-neoforge-26.1.2
$env:JAVA_HOME='D:\Program Files\Java\jdk-25'
.\gradlew.bat :common:compileJava --offline --no-daemon --console=plain 2>&1 |
    Out-File -FilePath build-errors.log -Encoding utf8
# 统计
Select-String -Path build-errors.log -Pattern '\.java:\d+:\s*错误'
```

> 注意：Gradle/javac 错误输出是 GBK；PowerShell 直接接对象流会乱，**必须重定向到文件再读**。

## 五、可用参考资料

- MC 26.1 sources（可直接读真实签名）：
  `common\build\moddev\artifacts\vanilla-26.1.2-1-sources.jar`（`net/minecraft/**`）
- NeoForge 端 sources：`neoforge\build\moddev\artifacts\minecraft-patched-26.1.2.7-beta-sources.jar`
- TLM 26.1 源码：`E:\Mod\TouhouLittleMaid-26.1-master`
- TLM 备份实现：`world/backups/MaidBackupsManager.java`
## 六、踩过的坑（务必遵守）

1. **PowerShell 嵌套数组会被展开**
   `@(@('a','b'))` 不产生二维数组，而是 `@('a','b')`；循环时 `$pr[0]` 变成取字符串第 0 个字符。
   曾因此把 `'p'→'l'` 全量替换，损坏 `MaidTransferService` / `MaidServerCommands`（已从 1.21.1 工程恢复）。
   **对策**：把替换对写成 `[ordered]@{ 'old' = 'new' }` 字典，或直接 `$t.Replace('old','new')` 硬编码。

2. **PowerShell `-replace` 不区分大小写**
   `setOwnerUUID\(...\)` 会同时匹配 `setOwnerUuid(...)`（Java 里是两个不同方法），
   导致 `MaidFileData.setOwnerUuid` 被误改成 `setOwnerUUID`。
   **对策**：Java 标识符相关替换一律用 `-creplace` 或 `String.Replace`（精确、大小写敏感）。

3. **Gradle/javac 输出是 GBK**
   PowerShell 直接接管道会把错误行变成 ErrorRecord 对象，需 `2>&1 | Out-File -Encoding utf8` 落盘后再读。

4. **编译错误统计要按「错误:」行数**，不能只看「找不到符号」——后者只覆盖一类错误（类型不兼容不在其中）。
## 七、当前剩余编译错误（22 处，2026-10-22）

`:common:compileJava` 已 **0 错误**；`:neoforge:compileJava` 剩 22 处，全部集中在两个文件：

### `network/NeoForgeNetwork.java`
| 行 | 问题 | 方向 |
|---|---|---|
| L123 | `ChatComponent.addMessage` 签名不匹配 | 26.1 客户端聊天栏 API 变化，需查 `Gui.getChat()` 的 addMessage 参数（可能要 `Component` + `MessageSignature` 或改用 `mc.gui.setOverlayMessage`） |

### `platform/NeoForgePlatformHelper.java`
| 行 | 问题 | 方向 |
|---|---|---|
| L100/110/121/131 | `BaubleItemHandler` 无法转为 `ItemStackHandler` | TLM 26.1 饰品栏容器类型改名/换实现，需查 TLM jar 的饰品 API |
| L146/169/188 | 找不到符号 | 逐个看缺失符号（可能是 TLM 方法改名） |
| L210/211 | `contains(k,type)` / `getCompound` 未改 | 漏改的 NBT getter（`contains(String)`、`getCompoundOrEmpty`） |
| L253 | `Optional<Boolean>` 未改 | `getBooleanOr(k,false)` |
| 4 处 | `orElse(ItemStack)` | `DataResult` 没有 `orElse`，应写 `.result().orElse(...)` |

### 已修复的 neoforge 端 API（本轮）
- 客户端发包：`PacketDistributor.sendToServer`（26.1 已移除）→ `net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer`
- 权限：`sp.hasPermissions(2)` → `sp.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)`
- `ServerPlayer.server` 字段私有 → `((ServerLevel) sp.level()).getServer()`
- Component 序列化：`Component.Serializer.toJson/fromJson` → `ComponentSerialization.CODEC` + `JsonOps`
- `@EventBusSubscriber` 改为 `net.neoforged.fml.common.EventBusSubscriber`，**移除 `bus = ...` 参数**
- `FMLLoader.isProduction()` → `FMLLoader.getCurrent().isProduction()`
- `displayClientMessage` 客户端侧已移除

### 待办：`ClientRegistration.java` 已临时改名为 `.java.disabled`
它引用 `client` 包的 `MaidBackupBrowserScreen` 等类；等 `client` 包移植完成后改名回来。
## 八、阻塞点：TLM 26.1 饰品栏改用 NeoForge Transfer API（需专项处理）

`NeoForgePlatformHelper` 的 4 处 `BaubleItemHandler` 转换失败，根因是 TLM 26.1 重写了饰品容器：

```
// TLM 26.1
public class BaubleItemHandler extends net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler
```

也就是说它**不再是 Forge 的 `ItemStackHandler`**，旧的四件套方法全部不存在了：

| 旧（1.21.1，ItemStackHandler） | 26.1 |
|---|---|
| `getSlots()` | Transfer API（需查 `ItemStacksResourceHandler` 的槽位访问方式） |
| `setSize(n)` | 可能改为构造时固定，或 `ResourceHandler` 的容量 API |
| `getStackInSlot(i)` | Transfer API（`ItemResource`/`TransactionContext` 体系） |
| `setStackInSlot(i, stack)` | Transfer API（`insert`/`extract` + `TransactionContext`） |

同样，`EntityMaid#getMaidBauble()` 在 26.1 的返回类型也需要重新确认（javap 未直接匹配到）。

**适配方向（待做）**：
1. `javap` 完整读 `net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler` 与 `ResourceHandler`/`ItemResource` 的方法；
2. 用 Transfer API 重写 `baubleGetSlots / baubleResize / baubleSetStack / baubleGetStack` 四个平台方法；
3. 若 Transfer API 不支持「扩容」，则 `baubleResize` 退化为空实现 + 明确日志（导入时饰品栏槽位不足的饰品按丢弃处理，与现有 `MAX_BAUBLE_SLOTS` 逻辑一致）。

## 九、另外两处未定位的 API

| 位置 | 问题 | 待查 |
|---|---|---|
| `NeoForgeNetwork` L123 | `ChatComponent.addMessage` 参数不匹配 | 26.1 客户端聊天栏写入的正确签名（`Gui#getChat()` 返回类型也可能变了） |
| `NeoForgePlatformHelper` L188 | `ItemStack.save(RegistryAccess)` 找不到 | 26.1 的物品序列化改为 `ValueOutput`/`CODEC`，需查 `ItemStack` 的保存入口 |
## 十、里程碑：编译 + 打包链路已通（2026-10-22）

```
:common:compileJava   → 0 错误
:neoforge:compileJava → 0 错误
:neoforge:build       → BUILD SUCCESSFUL
产物：neoforge/build/libs/maid_file_manager-neoforge-26.1.2-1.7.0-snapshot.jar（3,783 KB）
     含 6 种语言、neoforge.mods.toml、mixins 配置、sounds 资源
```

**注意**：当前 jar **还没有客户端界面**（`client` 包尚未移植，`ClientRegistration.java` 处于 `.disabled`），
只能验证「模组能加载、服务端逻辑与网络层可用」。GUI 移植完成后才具备完整功能。

### 顺手修掉的构建问题
- `javadoc` 任务对未注释代码在 doclint 处失败 → 根 `build.gradle` 已禁用 `javadoc` / `sourcesJar`（与 1.20.1/1.21.1 四端一致）
- 饰品栏在 26.1 改为 NeoForge Transfer API 且实体不再暴露访问器 →
  平台层四个方法改为**反射 + 失败降级**：拿不到 handler 时 `baubleGetSlots` 返回 0，
  整个饰品恢复流程按现有逻辑自动跳过；`baubleResize` 明确抛 `UnsupportedOperationException`
  （Transfer API 容器槽位数固定，不支持运行期扩容）
## 十一、client GUI：26.1 的渲染架构变化（评估结论）

复制 1.21.1 的 `client` 包（12 文件 / 8,080 行）后编译得到 138 处错误，根因是 **GUI 渲染架构重构**，不是简单改名：

| 1.21.1 | 26.1 |
|---|---|
| `GuiGraphics`（绘图上下文） | **已移除**；替代为 `net.minecraft.client.gui.GuiGraphicsExtractor` |
| `Screen#render(GuiGraphics, int, int, float)` | **`Screen#extractRenderState(GuiGraphicsExtractor, int, int, float)`**（extractRenderState 模式） |
| `Screen#renderBackground(GuiGraphics)` | 语义并入 extract 流程（需逐个确认） |
| `graphics.drawString(...)` | `GuiGraphicsExtractor` 只查到 `pose()/fill()/fillGradient()/blit()`；文字绘制入口待定位（可能在 `Font` 或 extractor 的其它方法） |

**影响**：8,080 行 GUI 代码需要系统性改写（渲染入口改名 + 绘图调用换 API + 布局/滚动逻辑复核），
工作量远大于服务端部分，需作为独立阶段推进。

**当前处置**：`client` 包已移到 `pending-client/`（不参与编译），`ClientRegistration.java` 保持 `.disabled`。
工程维持「`:common` + `:neoforge` 编译与打包通过」的稳定状态，可先验证服务端/网络层功能。

### GUI 移植的下一步
1. 用 `GuiGraphicsExtractor` 的完整公开方法清单建立「旧绘图调用 → 新调用」映射表（`fill/blit` 已确认可直接对应）；
2. 先移植 **1 个最小 Screen**（如 `MaidConfigScreen`，394 行）跑通 `extractRenderState` 模式；
3. 再按文件逐个迁移（建议顺序：Config → Bauble → Backup → Profile → Manager 主界面）；
4. 每次迁移后立即 `:common:compileJava` 验证，避免一次性搬完后再排查。
## 十二、GUI 迁移进度（第二轮评估）

### 已确认的完整映射表（`GuiGraphicsExtractor`，92 个 public 方法）

| 1.21.1 `GuiGraphics` | 26.1 `GuiGraphicsExtractor` |
|---|---|
| `drawString(font, s, x, y, col, shadow)` | `text(font, s, x, y, col, dropShadow)` |
| `drawString(font, s, x, y, col)` | `text(font, s, x, y, col)` |
| `drawCenteredString(...)` | `centeredText(...)` |
| `fill / fillGradient / blit / enableScissor / disableScissor / pose()` | 同名，可直接用 |
| `hLine / vLine` | `horizontalLine / verticalLine` |
| `renderOutline` | `outline` |
| 宽度/高度 | `guiWidth() / guiHeight()` |

### 已完成的机械迁移（7 / 12 文件）
`GuiGraphics` → `GuiGraphicsExtractor`、`drawString` → `text`、`drawCenteredString` → `centeredText`、
`hLine/vLine` → `horizontalLine/verticalLine`、`renderOutline` → `outline`、
`render(...)` → `extractRenderState(...)`。

### 仍未迁移的第二层（widget / 事件 API）
全量编译后暴露（约 200 处）：

| 位置 | 问题 | 说明 |
|---|---|---|
| `AbstractButton` 子类（如 `WoodButton`） | 未覆盖 `extractContents(GuiGraphicsExtractor, int, int, float)` | 26.1 把 widget 渲染入口从 `renderWidget/render` 改为 `extractContents` |
| `mouseClicked` | 签名不匹配 | 26.1 改为事件对象参数（需查 `ContainerEventHandler#mouseClicked` 新签名） |
| `MaidBaubleExtraBlockScreen` 等 | `net.minecraft.resources.ResourceLocation` 全限定名残留 | 未走 import，需单独替换 |

### 结论
GUI 迁移需分两阶段：
1. **图形 API 层**（已做映射，7 文件可机械替换）
2. **widget / 事件层**（`extractContents` + 事件签名，需逐文件改）

当前 `client` 包再次移到 `pending-client/`（保留已做的阶段 1 迁移结果），`ClientRegistration.java` 保持 `.disabled`，
工程维持 `:neoforge:build` SUCCESSFUL 的稳定状态。
### GUI 阶段 2 剩余 API 清单（全量编译实测，约 200 处）

| 缺失符号 | 处数 | 26.1 对应写法 |
|---|---|---|
| `render(GuiGraphicsExtractor,int,int,float)` | 14 | `super.render(...)` 已无此重载；widget 走 `extractContents`，Screen 走 `extractRenderState` |
| `hasPermissions(int)` | 12 | `permissions().hasPermission(Permissions.COMMANDS_MODERATOR)` |
| `displayClientMessage(Component,boolean)` | 14 | 客户端已移除；聊天用 `mc.gui.getChat().addMessage(...)`，actionbar 用 `mc.gui.setOverlayMessage(comp, false)` |
| `getScrollAmount()` | 8 | `AbstractScrollWidget`/`ScrollableWidget` 的滚动 API 改名（需查 26.1） |
| `pushPose()/popPose()` | 8 | 改为 `graphics.pose().pushMatrix()/popMatrix()`（`Matrix3x2fStack`） |
| `renderTooltip(Font, List, Optional, int, int)` | 4 | Screen 的 tooltip API 变化，需查 `extractRenderStateWithTooltipAndSubtitles` 用法 |

**已完成的阶段 2 迁移**（脚本化）：
- widget 渲染入口：`public void render(GuiGraphicsExtractor,...)` → `protected void extractContents(GuiGraphicsExtractor,...)`
- 鼠标事件：`mouseClicked(double,double,int)` → `mouseClicked(MouseButtonEvent, boolean)`，
  并在方法开头用 `event.x()/event.y()/event.button()` 还原旧变量（6 个文件已迁移）
- `net.minecraft.resources.ResourceLocation` 全限定名残留 → `Identifier`
### GUI 阶段 2 深层 API（第三轮实测，全量编译）

完成 4 类映射（`pushPose/popPose` → `pose().pushMatrix/popMatrix`、权限、`displayClientMessage`、`ResourceLocation`）后，
剩余错误集中在以下**深层 API**（每类需单独查文档）：

| 缺失符号 | 处数 | 说明 / 方向 |
|---|---|---|
| `render(GuiGraphicsExtractor,int,int,float)` | 14 | 父类/组件的 render 重载已无；需确认是 `super.render` 还是自定义控件 |
| `getScrollAmount()` | 8 | `AbstractScrollArea` 的 `scrollAmount` 为私有，需找 26.1 的滚动值 getter |
| `pose()` | 8 | 替换后仍有调用点，说明部分 `graphics` 变量类型不是 `GuiGraphicsExtractor`（可能仍是旧类型或局部变量命名不同） |
| `minecraft`（变量） | 4 | `Screen#minecraft` 字段访问方式变化（可能改为 `getMinecraft()` 或私有） |
| `renderItem(ItemStack,int,int)` | 4 | 物品渲染 API 变化（26.1 走 render state） |
| `renderTooltip(...)` | 6 | Screen/GuiGraphicsExtractor 的 tooltip 入口变化（`extractRenderStateWithTooltipAndSubtitles` 相关） |
| `getList(String,byte)` | 2 | NBT 漏改（`getListOrEmpty`） |
| `copy()` | 2 | 某组件的 copy 语义变化 |

**结论**：GUI 迁移的绝大部分是机械映射（已完成两轮），剩余的是「渲染上下文对象获取方式」与「物品/提示渲染」两处架构差异，
建议下一步：把 26.1 的一个**官方 Screen 实现**（如 `AbstractContainerScreen`）通读一遍，照抄其 `extractContents`/`extractRenderState`
与物品/提示渲染的调用方式，再回头改我们的 12 个文件——比逐个查 API 快得多。
### GUI 阶段 2 第四轮实测（全量编译 196 处）

已完成映射（本轮新增）：
| 旧 | 26.1 |
|---|---|
| `graphics.renderItem(stack, x, y)` | **`graphics.item(stack, x, y)`** |
| `graphics.renderItemDecorations(...)` | **`graphics.itemDecorations(...)`** |
| `graphics.renderTooltip(font, list, opt, x, y)` | **`graphics.setTooltipForNextFrame(font, texts, optionalImage, x, y)`** |
| `graphics.renderTooltip(font, stack, x, y)` | **`graphics.setTooltipForNextFrame(font, itemStack, x, y)`** |

剩余 9 类（196 处）：
| 符号 | 处数 |
|---|---|
| `render(GuiGraphicsExtractor,int,int,float)` | 14 |
| `render(GuiGraphicsExtractor,...,float)`（9 参重载） | 2 |
| `getScrollAmount()` | 8 |
| `pose()` | 8 |
| `minecraft`（字段） | 4 |
| `hasControlDown()` | 2 |
| `setFilter(boolean,boolean)` | 2 |
| `copy()` | 2 |
| `getList(String,byte)` | 2 |

**下一步建议**：这 9 类需要读 26.1 的 `AbstractScrollArea`（滚动）、`EditBox`（setFilter/hasControlDown）、
`Screen` 子类（minecraft 字段与 render 重载）三处源码，一次性把剩余映射补齐。
### GUI 阶段 2 第五轮（178 处，剩余 6 类）

本轮修掉：
- `graphics.pose().pose()` 双写（前几轮映射造成的副作用）→ `graphics.pose()`
- `getScrollAmount()` → **`scrollAmount()`**（`AbstractScrollArea`）
- `getCompound(i)` → `getCompoundOrEmpty(i)`、`getList(k, Tag)` → `getListOrEmpty(k)`（NBT 漏改）

剩余 6 类：
| 符号 | 处数 | 方向 |
|---|---|---|
| `render(GuiGraphicsExtractor,int,int,float)` | 14 | 需看是否是 `super.render(...)` 调用（父类已无此重载）或自定义控件方法 |
| `minecraft`（变量） | 4 | 具体行待看（Screen 仍有 `protected final Minecraft minecraft`） |
| `getListOrEmpty(String)` | 2 | 调用对象类型不对（可能是 `ListTag` 而非 `CompoundTag`） |
| `hasControlDown()` | 2 | 26.1 的 `Screen.hasControlDown()` 已移除，事件侧用 `event.hasControlDownWithQuirk()` |
| `render(...)` 9 参重载 | 2 | 需定位具体方法 |
| `setFilter(boolean,boolean)` | 2 | `AbstractTexture.setFilter` 签名变化（可能改枚举） |

**评估**：GUI 剩余已从 200 降到 178，但每类都需要读具体源码行才能定；
按当前节奏还需 3-5 轮才能归零。工程始终保持可构建（`:neoforge:build` SUCCESSFUL）。
### GUI 阶段 3（本轮：178 → 132）

修掉（脚本化）：
- 父类/子控件事件：`super.mouseClicked(mouseX, mouseY, button)` → `super.mouseClicked(event, doubleClick)`；
  `super.mouseReleased(mouseX, mouseY, button)` → `super.mouseReleased(event)`；`entry.*` 同理
- 父类 render 重载已无：删除 `super.render(graphics, mouseX, mouseY, partialTick);` 行
- 键盘/字符：`super.keyPressed(keyCode, scanCode, modifiers)` → `super.keyPressed(event)`；
  `super.charTyped(codePoint, modifiers)` → `super.charTyped(event)`
- NBT 漏改：`contains(k,Tag)` / `getInt(k)` / `getBoolean(k)` / `getCompound(k)`

剩余 132 处，主要三类：
| 问题 | 处数 | 说明 |
|---|---|---|
| `变量 event` 作用域 | 14 | 部分方法的 mouseClicked 签名没被前一轮正则匹配到（参数名不同），方法体里却已引用 `event`；需按方法逐个补签名或回退该行 |
| `AbstractButton` 内部类未覆盖 `extractContents` | 多 | 内部类（WoodButton / ToggleSwitch / StripToggle）的方法名与参数名不统一，需用更宽匹配（`render\(GuiGraphicsExtractor` 任意参数名）统一改为 `extractContents` |
| `ItemStack(Optional<Reference<Item>>)` | 2 | `Registries.ITEM.get(...)` 返回 Optional，需 `.map(Holder::value).orElse(null)`（与平台层同法） |
| `hasControlDown()` / `setFilter` / 9 参 render | 6 | 需单独查 26.1 对应 API |
### GUI 阶段 3 续（132 → 128）

新增修复：`mouseDragged` 签名迁移（`MouseButtonEvent` + dx/dy）、`ItemStack(Optional)` 改 `.map(Holder::value)`。

**卡住的地方**（诚实记录）：
- `render(GuiGraphicsExtractor, int, int, float)` 的宽匹配替换对内层 widget 类**未生效**——
  说明这些内部类重写的并不是这个方法（可能是 `renderWidget`，或 26.1 的 `AbstractWidget` 子类要求别的方法名）
- `变量 event` 14 处：`super.mouseClicked(event, doubleClick)` 落在**签名未被迁移**的方法里
  （前一轮的正则只匹配了参数名为 `mouseX/mouseY/button` 的形式，实际有变体）

**下一步必须做**（不要再用正则猜）：
1. 打开一个内部类（如 `MaidProfileScreen.WoodButton`）看它到底重写了什么方法；
2. 对照 26.1 的 `AbstractButton`（`extractContents` + `onPress(InputWithModifiers)`）逐个手工改；
3. 再逐个方法核对 `mouseClicked/mouseDragged` 的实际签名；
4. 剩下的 `hasControlDown / setFilter / 9 参 render / KeyMapping CATEGORY` 单独查。

**结论**：GUI 剩余部分需要**人工逐方法核对**，正则批量替换的收益已到极限（128 处里大部分是签名级差异）。
### GUI 阶段 4（128 → 90，本轮降 38）

**关键发现**：内部类 widget 的正确迁移方式是
```java
// 1.21.1
@Override public void onPress() { ... }
@Override public void renderWidget(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { ... }

// 26.1
@Override public void onPress(InputWithModifiers input) { ... }
@Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) { ... }
```
即 `onPress` **多一个 `InputWithModifiers` 参数**、`renderWidget` → `extractContents`。这一条修掉了 6 个内部类（WoodButton ×3 / StripToggle / ToggleSwitch ×2）。

另修：`Minecraft.getInstance().gui.getChat().addMessage(x)` → `gui.setOverlayMessage(x, false)`（26.1 的 ChatComponent 无可用 addMessage 重载）。

### 剩余 90 处
| 符号 | 处数 |
|---|---|
| `变量 event` | 14（`super.mouseClicked(event,...)` 落在签名未迁移的方法里） |
| `minecraft`（变量） | 4 |
| `hasControlDown()` | 2 |
| `render(...)` 9 参重载 | 2 |
| `setFilter(boolean,boolean)` | 2 |
| 其余「方法不会覆盖」等 | 约 66 |

**下一步**：`变量 event` 需逐个方法核对 `mouseClicked` 实际签名；`minecraft`/`hasControlDown`/`setFilter`/9 参 render 各 2-4 处单独查即可。
### GUI 阶段 5（90 → 86）

- `Screen.hasControlDown()` 已移除 → `MultiLineEditBox` 的 Ctrl 检测降级为恒真（`Ctrl+滚动加速/多选` 功能关闭，加注释说明）
- `KeyMapping` 分类：`String CATEGORY` → **`KeyMapping.Category` 对象**
  （`new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "main"))`），26.1 的构造函数只接受 Category

### 剩余 45 个去重错误行（86 处）分布
| 文件 | 主要问题 |
|---|---|
| `MaidFileManagerScreen` | `super.mouseDragged(mouseX,...)`（该方法签名未迁移）、`rows.get(i).render(...)` 自定义行渲染、多个 `@Override` |
| `MaidProfileScreen` | `DynamicTexture` + `setFilter(bool,bool)`、`pose().translate/scale`、`blit(...)` 9 参、多个 `@Override` |
| `MultiLineEditBox` | `@Override`（widget 事件方法） |
| 其它 Screen | 剩余 `@Override`（方法签名） |

**下一步**：按文件逐个核对 `@Override` 方法的 26.1 签名（`mouseDragged`/`keyPressed`/`charTyped` 等），
以及 `DynamicTexture`、`Matrix3x2fStack.translate/scale`、`blit` 的新签名——这些都需要读 26.1 源码对照。
### GUI 阶段 6（86 → 72）

- `super.mouseDragged(mouseX, mouseY, button, dragX, dragY)` → `super.mouseDragged(event, dragX, dragY)`
- `charTyped(char codePoint, int modifiers)` → **`charTyped(CharacterEvent event)`**，方法体 `codePoint` → `event.character()`
- `keyPressed(int keyCode, int scanCode, int modifiers)` → **`keyPressed(KeyEvent event)`**，方法体 `keyCode` → `event.key()`

剩余 72 处：`变量 event` 8、`codePoint` 4（本轮已修部分）、`minecraft` 4、
`render(...)` 9 参 2、`setFilter` 2，其余为 `@Override` 签名问题（`DynamicTexture` 构造器变化等）。
### GUI 阶段 7（72 → 66）

修掉：
- `CharacterEvent` 的方法是 **`codepoint()`**（不是 `character()`），`KeyEvent` 是 `key()`
- `new DynamicTexture(image)` → **`new DynamicTexture(Supplier<String>, NativeImage)`**
  即 `new DynamicTexture(() -> "maid_manager_photo", image)`
- `pose().translate(x, y, z)` / `scale(x, y, z)` 的第三参数被移除 → 2 参形式

剩余 66 处（关键几类）：
| 问题 | 说明 |
|---|---|
| `Matrix3x2fStack.translate/scale` 仍报「float 不能转 Matrix3x2f」 | 说明这两个方法签名不是 `(float,float)`，需查 `Matrix3x2fStack` 实际方法（可能是 `translate(Matrix3x2f)` 或 `translate(float,float)` 但接收者类型不同） |
| `方法不会覆盖`（约 15 处） | 剩余 `@Override` 方法签名（`mouseScrolled`/`charTyped`/`render` 等） |
| `ChatComponent.addMessage`（3 处） | 多行调用的正则未命中，需行级改 |
| `isAllowedChar(event.codepoint())` | `codepoint()` 返回 `int`，需 `(char)` 强转 |
| `blit(...)` 参数类型 | 26.1 的 blit 参数顺序/类型变化 |
| `rows.get(i).render(...)`、`entry.mouseReleased(event)` | 自定义 ListRow/Entry 的方法名或签名需对齐 |
### GUI 阶段 8（66 → 60）

| 修复 | 内容 |
|---|---|
| `isAllowedChar(event.codepoint())` | → `isAllowedChar((char) event.codepoint())`（`codepoint()` 返回 int） |
| 多行 `gui.getChat().addMessage(` | → `gui.setOverlayMessage(`（补 `, false`） |
| 照片渲染 | 旧 `blit(rl, x, y, u, v, w, h, texW, texH)` → **26.1 `blit(Identifier, x0, y0, x1, y1, u0, u1, v0, v1)`**（目标矩形 + 归一化 UV）：`blit(photoRl, 0, 0, photoTexW, photoTexH, 0f, 1f, 0f, 1f)`；`scale(x,y,1f)` → `scale(x,y)` |

> ⚠️ 照片的 UV 归一化假设需**实机验证**（`0f,1f,0f,1f` 表示整张纹理）；若显示异常，改用 12 参 `blit(RenderPipeline, Identifier, x, y, u, v, w, h, texW, texH)` 版本。

剩余 60 处：`方法不会覆盖`（~15 个 `@Override` 方法与 26.1 签名不符）、
`Matrix3x2fStack.translate`（签名待查，可能在 blaze3d 库中）、
`rows.get(i).render(...)` / `entry.mouseReleased(event)` 等自定义组件方法对齐。
### GUI 阶段 9（60 处，未降）

本轮尝试：
- 照片渲染块改为灰底占位（绕开 `Matrix3x2fStack.translate/scale` + 新 `blit` 语义）
- 想删除一处零平移 `pose().translate(0.0F, 0.0F)`（未命中，实际形式不同）

**未下降原因**：`MaidFileManagerScreen` 里还有 ~15 处 `@Override`（父类方法签名不符），
以及 `rows.get(i).render(...)`、`entry.mouseReleased(event)` 等自定义组件调用；
这些与照片渲染无关，属独立问题。

**遗留待办**：
1. `Matrix3x2fStack.translate/scale` 的真实签名（不在 MC sources，需查 `com.mojang.blaze3d` 库 jar，建议用 `javap` 对 neoform 产物里的 blaze3d）
2. `MaidFileManagerScreen.MaidListWidget.HeaderEntry/Entry` 未覆盖 `Entry` 抽象方法（列表行渲染签名）
3. `entry.mouseReleased(event)` / `rows.get(i).render(...)` 自定义组件方法对齐
4. `setOverlayMessage` 在 `MaidConfigScreen:94` 仍报参数不符（多行调用）
### GUI 阶段 10（60 → 38，降 22）—— 突破！

**关键发现**：26.1 的列表行渲染语义完全变了：

```java
// 1.21.1
public void render(GuiGraphics graphics, int index, int top, int left,
                   int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick)

// 26.1：AbstractSelectionList.Entry 的抽象方法
public abstract void extractContent(final GuiGraphicsExtractor graphics,
                                    int mouseX, int mouseY, boolean hovered, float a);
```

行矩形不再作为参数传入，而是通过 `LayoutElement` 的 `getX()/getY()/getWidth()/getHeight()` 获取。
**改写方式**（方法体无需改动）：
```java
public void extractContent(final GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float a) {
    int top = getY();
    int left = getX();
    int width = getWidth();
    int height = getHeight();
    ... 原方法体 ...
}
```

另修：`mouseReleased(double, double, int)` → **`mouseReleased(MouseButtonEvent event)`**，体内用 `event.x()/event.y()/event.button()` 还原。

### 剩余 38 处
`@Override` 方法签名（`renderListBackground`、`mouseScrolled`、`getRowWidth` 等）、
`setOverlayMessage` 多行调用、`Matrix3x2fStack.translate`（1 处）、少量 `找不到符号`。
### GUI 阶段 11（38 → 28）

修复：
| 问题 | 处理 |
|---|---|
| `Screen.renderBackground(...)` 26.1 已移除（6 处） | 改名为 `private renderBackgroundDisabled(...)` 并删除残留 `@Override` |
| `getHovered(double, double)` | 26.1 的 `getHovered()` 无参 → 改名 `getHoveredRow(double,double)`，并同步两处调用点 |
| `MultiLineEditBox` 的 `extractContents` | 它是 `AbstractWidget` 子类 → 必须是 **`extractWidgetRenderState`**；但 `AbstractButton` 子类必须保持 `extractContents`（**两类不能混**） |
| 多行 `gui.setOverlayMessage(...)` | 补 `, false`（正则支持嵌套括号） |

> ⚠️ 教训：`AbstractButton` 子类实现 `extractContents`，`AbstractWidget` 子类实现 `extractWidgetRenderState`——用一条宽规则统一改会把 Button 改坏（本轮踩过一次，错误从 38 反弹到 66，已回退）。

剩余 28 处：`rows.get(i).render(...)`（2 处自定义行渲染调用）、`photoTexture.setFilter`、
少量 `@Override` 与 `setOverlayMessage`。
## 十三、里程碑：GUI 编译归零 + 完整打包（2026-10-22）

```
:common:compileJava   → 0 错误
:neoforge:compileJava → 0 错误
:neoforge:build       → BUILD SUCCESSFUL
产物：maid_file_manager-neoforge-26.1.2-1.7.0-snapshot.jar（3,940.5 KB，含 client 包）
```

已下发至 `I:\PCL\.minecraft\versions\26.1.2-NeoForge测试\mods`。

### GUI 迁移全过程错误数曲线
138（初）→ 200（全量）→ 178 → 132 → 128 → 90 → 86 → 72 → 66 → 60 → 50 → 38 → 28 → 12 → 6 → **0**

### 迁移过程中踩的坑（全部已修正）
1. `XxxScreen.this.minecraft` 被正则误改成 `XxxScreen.net.minecraft.client.Minecraft.getInstance()`（语法错误）→ 回退
2. `this.minecraft` 误改成 `Minecraft.getInstance()`（26.1 实际**保留**了 `Screen.minecraft` 字段）→ 回退
3. `AbstractButton` 子类必须实现 `extractContents`，`AbstractWidget` 子类必须实现 `extractWidgetRenderState`——混改导致反弹
4. 内部类（`ToggleSwitch`）里 `this.minecraft` 指向内部类，需用外部类引用或局部 `mc` 变量

### 仍待完成
- TLM 备份新 `index.dat`（Component Codec）适配
- `verifyAssets` 校验任务移植
- 实机验证（26.1.2-NeoForge测试 实例）
## 十四、备份适配 + verifyAssets 完成（2026-10-22）

### 1) TLM 备份 `index.dat` 新结构适配
新增 `MaidBackupService.readNameTag(CompoundTag)`：
- **26.1 路径**：`Name` 用 `ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, nameTag)` 解析 → `getString()`
- **旧格式兜底**：`StringTag` 里是组件 JSON → 走原有 `extractText`
- 其它类型：`nameTag.toString()` 再解析

`readIndexNames` 已改为调用 `readNameTag`，两种格式都能读出女仆名。

### 2) `verifyAssets` 移植完成
从 1.21.1 工程移植校验任务（注意：源 build.gradle 开头有 `plugins {}` 块，追加时必须去掉，否则 Gradle 报「只允许 plugins 块在最前」）。

现在构建期自动检查：
- 资源 JSON 严格性（尾逗号/括号/UTF-8）
- `PROTOCOL_VERSION == "2"`
- 通道 ID 唯一性
- `MaidFileIo` 中生效的 `Files.move(`
- **6 种语言键集一致性**

### 3) 最终状态
```
:neoforge:build → BUILD SUCCESSFUL ✅
verifyAssets   → 通过 ✅
产物：maid_file_manager-neoforge-26.1.2-1.7.0-snapshot.jar（3,941 KB）
已下发：I:\PCL\.minecraft\versions\26.1.2-NeoForge测试\mods
       E:\Mod\v1.7.0\
```

### 唯一剩余：实机验证
jar 已就位，等待启动游戏验证：
- 模组能否加载
- GUI 能否打开（U 键）
- 界面渲染是否正常（`extractRenderState`/`extractContent` 迁移的视觉效果）
- 导出/导入/备份功能
## 十五、运行时验证：服务端启动成功（2026-10-22）

用 `.\gradlew.bat :neoforge:runServer --offline` 实际启动 MC 26.1.2 服务端，日志关键行：

```
[maid_file_manager] Mod constructor START (NeoForge)
[maid_file_manager] Platform & Network services registered
[maid_file_manager] Mod constructor END
[maid_file_manager] CustomPayload handlers registered: 16 C2S + 10 S2C
[maid_file_manager] /maidfile exportall command registered
Starting minecraft server version 26.1.2
Done (1.824s)! For help, type "help"
```

**验证通过的项**：
- ✅ 模组加载（`Maid File Manager 1.7.0-snapshot (maid_file_manager)`）
- ✅ 平台服务与网络服务注册
- ✅ **16 个 C2S + 10 个 S2C payload 全部注册成功**（网络层完整）
- ✅ `/maidfile exportall` 命令注册
- ✅ 服务端启动到 `Done`，**无异常、无崩溃**
- ✅ 没有 client-only 类被服务端误加载（`Screen`/`Gui` 等未出现在服务端 classpath 错误中）

> 说明：这次 runServer 是**运行时验证**，比编译通过更有说服力——它真实加载了模组并初始化了所有注册项。

### 唯一剩余
客户端 GUI 的**视觉验证**（需要人眼看渲染效果：`extractRenderState` / `extractContent` 迁移后的布局、
列表行、按钮、滚动条、照片占位）。jar 已下发到 `26.1.2-NeoForge测试`。
## 十六、运行时验证：客户端启动成功（2026-10-22）

用 `.\gradlew.bat :neoforge:runClient --offline` 实际启动 MC 26.1.2 客户端，日志关键行：

```
[maid_file_manager] Mod constructor START (NeoForge)
[maid_file_manager] Platform & Network services registered
[maid_file_manager] Mod constructor END
[maid_file_manager] KeyMapping registered              ← 键位（U）注册成功
[maid_file_manager] client network impl registered     ← 客户端网络实现注册
Reloading ResourceManager: vanilla, mod_resources, mod/maid_file_manager, ...
[maid_file_manager] CustomPayload handlers registered: 16 C2S + 10 S2C
Setting user: Dev
Backend library: LWJGL version 3.4.1+2
OpenAL initialized / Sound engine started
TextureAtlas: Created 512x256x0 minecraft:textures/atlas/particles.png-atlas
```

**验证通过**：
- ✅ 客户端模组加载
- ✅ **KeyMapping 注册**（打开界面的 U 键）
- ✅ **客户端网络实现注册**
- ✅ 16 C2S + 10 S2C payload
- ✅ 资源包加载（`mod/maid_file_manager`）
- ✅ 声音引擎启动 + 纹理图集创建（渲染初始化正常）
- ✅ **全程无异常、无崩溃**

**唯一警告**（非本次引入）：
```
WARN Invalid path in datapack: maid_file_manager:sounds/LICENSE.txt, ignoring
```
该文件四端都有（放在 `assets/.../sounds/` 下供版权声明随包分发），datapack 解析器会跳过非 JSON 文件——这是既有行为，为保持四端一致**不单独改**。

### 结论
服务端与客户端**均已通过运行时验证**。GUI 的**视觉布局**仍建议人工确认一次（新渲染架构的坐标/尺寸观感）。
## 十七、实机反馈修复（2026-10-22）

### 修复 A：界面只有自绘内容，所有控件不显示
**现象**：打开界面只看到标题、复选框、底部提示（都是 `graphics.fill/text` 自绘），
标签页 / 按钮 / 列表 / 输入框全部不见。

**根因**：26.1 的 `Screen.extractRenderState` 由**父类遍历渲染所有 widget**。
迁移时把 `render(...)` 改名成 `extractRenderState(...)`，但方法体里没有调用 `super`，
而原有那行 `super.render(graphics, mouseX, mouseY, partialTick);` 在早前一轮迁移中被删除
（当时编译报「父类已无 render 重载」），导致父类的控件遍历完全被跳过。

**修复**：给 10 处渲染方法在**方法末尾**插入 `super` 调用（末尾 = 控件画在自绘内容之上）：
- `MaidFileManagerScreen` / `MaidBackupBrowserScreen` / `MaidProfileScreen` /
  `MaidConfigScreen` / `MaidBaubleImportScreen` / `MaidBaubleExtraBlockScreen` 的 `extractRenderState`
- `MaidListWidget` / `FileListWidget` 的 `extractWidgetRenderState`
- `MultiLineEditBox` 的 `extractWidgetRenderState`

**实机确认**：界面显示正常。

### 修复 B：找不到女仆备份 —— 26.1 备份路径确实变了
**用户提供的实际路径**：
```
saves/<世界>/dimensions/minecraft/overworld/data/maid_backups/<主人UUID>/<女仆UUID>/<时间>.dat
```
而 1.20.1 / 1.21.1 是 `saves/<世界>/data/maid_backups/...`。

**修复**：`MaidBackupService.backupsDir()` 改为**优先探测维度路径、回退旧路径**：
```java
Path dimensionPath = worldRoot.resolve("dimensions").resolve("minecraft")
        .resolve("overworld").resolve("data").resolve(MAID_BACKUPS_DIR);
if (Files.isDirectory(dimensionPath)) return dimensionPath;
Path legacyPath = worldRoot.resolve("data").resolve(MAID_BACKUPS_DIR);
return Files.isDirectory(legacyPath) ? legacyPath : dimensionPath;
```
`scanAllWorlds()`（主菜单遍历所有存档）同样经由此函数，两种布局都覆盖。

> 这印证了本轮早期的判断：**备份内容格式（index.dat 的 Name → Component Codec）和存放位置都变了**，
> 两者都已在代码里兼容。
## 十八、版本号定稿（2026-10-22）

版本从 `1.7.0-alpha` 改为 **`1.7.0-snapshot`**，与依赖的 TLM
（`touhoulittlemaid-2.0.0-neoforge+mc26.1.2-snapshot`）的**稳定性标记对齐**。

**为什么不用 TLM 的完整格式** `1.7.0-neoforge+mc26.1.2-snapshot`：
本工程用多加载器模板，jar 名本身已含平台与 MC 版本
（`maid_file_manager-neoforge-26.1.2-<version>.jar`），再拼一遍会变成
`maid_file_manager-neoforge-26.1.2-1.7.0-neoforge+mc26.1.2-snapshot.jar`——`neoforge` 与 `26.1.2` 各出现两次。
另外 `+` 在 Maven/Gradle 依赖解析里有通配符语义，作为版本字符串有潜在歧义。

改动：
- `gradle.properties`: `version=1.7.0-snapshot`
- `PORTING-26.1.md` / `README.md` 中的版本引用（10 处）
- `neoforge.mods.toml` 用 `${version}` 占位符，无需改动

产物：`maid_file_manager-neoforge-26.1.2-1.7.0-snapshot.jar`
## 十九、实机联调修复（2026-10-22 晚）

依据用户实机反馈 + 游戏日志逐条定位并修复：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 界面只有标题/复选框，按钮标签页全无 | `extractRenderState` 未调 `super` → 父类不遍历渲染 widget | 10 处补 `super.extractRenderState/extractWidgetRenderState` |
| 2 | 工具按钮左侧勾选框不显示 | `super` 调用在勾选框绘制**之后**，按钮把勾选框盖住 | 把 `super` 移到 `drawButtonCheckBox` 之前 |
| 3 | 勾选框与「档案」按钮上下错位 | 绘制用 `getHeight()`，26.1 的 Entry 布局未按 `setHeight(ROW_HEIGHT)` 生效 | 绘制统一用常量 `ROW_HEIGHT` |
| 4 | 「档案」按钮落到选中边框外 | 行背景用 `listW`，按钮用 `listW - 5`（右侧 5px 是滚动条区） | 6 处 `rowW` + 2 处 `rowRight` 统一 `listW - 5` |
| 5 | 模型名显示 `winefox_magical_b073...` | ① 26.1 模型名键改为 `model.geckolib.<id>.name`；② `model_id` 带 `_<32位hex>` UUID 后缀；③ `model_id` 移入 NeoForge attachment | 加 4 种键候选 + `stripUuidSuffix()` + `readModelIdTag()` 递归读 attachment |
| 6 | 导入后主手/护甲物品未清除 | 26.1 装备键改为 `LivingEntity.TAG_EQUIPMENT = "equipment"`，旧 `HandItems` 已不存在 | 导出时 `fullNbt.remove("equipment")` |
| 7 | 导入后 AI 聊天记录丢失 | TLM 26.1 的读入口改为 `MaidAIChatData.read(ValueInput)`，旧反射找的 `readFromTag(CompoundTag)` 不存在 | `restoreAiChat` 反射改调 `read(ValueInput)` |
| 8 | 备份界面恒显示「未找到」 | `applyServerOwners`（单人/服务端分支）只填 `worlds` 没调 `rebuildRows()`，而界面遍历的是 `rows` | 补 `rebuildRows()` |
| 9 | 备份界面列出了本机所有世界 | 兜底 `scanAllWorlds` 扫全部存档 | 兜底改为只匹配 `server.getWorldData().getLevelName()` |
| 10 | 导入日志误报 `TLM 数据版本不安全: [MaidTask]` | `MaidTask` 是设计上就有意丢弃（回归空闲），却被算进 notRestored | 加入白名单 |

### 仍然存在的降级/未完成项
1. **饰品栏（Curios/baubles）**：26.1 TLM 改用 NeoForge Transfer API 且实体不再暴露访问器 → 平台层改为反射 + 失败降级（拿不到 handler 时返回 0，整段饰品迁移跳过）；`baubleResize` 抛 `UnsupportedOperationException`
2. **女仆档案照片**：26.1 的 `Matrix3x2fStack` 与 `blit` 语义变化 → 暂时改为灰底占位（代码留 `TODO(26.1)`）
3. **Ctrl 多选**：`Screen.hasControlDown()` 已移除 → 降级关闭
4. **备份扫描主路径**：`server.getWorldPath(LevelResource.ROOT)` 的 26.1 语义仍待确认，当前依赖「只匹配当前存档」的兜底