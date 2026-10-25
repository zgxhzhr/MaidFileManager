# 26.1.2 Fabric 移植笔记（v1.7.0-snapshot）

> 本工程从 `maid_file_manager-neoforge-26.1.2` 复制而来，MC 版本完全相同（26.1.2）。
> 因此**所有与加载器无关的 26.1 API 适配都是原样复用的**，本文只记录 Fabric 专有的差异。
> NeoForge 侧的完整踩坑记录见 `PORTING-26.1.md`（保留未改，作历史参考）。

## 一、工程基线

| 项 | 值 |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.2 |
| Fabric API | 0.149.1+26.1.2 |
| Loom | 1.16-SNAPSHOT |
| JDK | 25 |
| TLM 依赖 | `libs/touhoulittlemaid-fabric-26.1.2.jar`（compileOnly，来自 `E:\Mod\TouhouLittleMaid-Orihime-26.1`） |

## 二、复用了什么（一行没改）

`common/` 下的 **43 个文件 / 约 17,000 行**全部原样通过编译，包括：

- `client/` 12 个 GUI 文件（`GuiGraphicsExtractor` / `extractRenderState` / `extractContent` 那一整套 26.1 迁移）
- `data/`（`MaidFileData` / `NbtMigration` / `CuriosBaubleFilter` / `NbtVersion`）
- `service/`（`MaidTransferService` 约 1,700 行 / `MaidBackupService` / `MaidProfileService`）
- `config/` / `network/` 的加载器无关部分（`IMaidFileNetwork` / `MaidFilePackets` / `ChunkedUploadAssembler`）

**结论**：26.1 的 API 迁移（GUI 渲染模型、NBT getter Optional 化、`Identifier` 重命名等）是 MC 层面的，与加载器无关。第二个 26.1 平台只需要写 **8 个文件 / 约 2,270 行**适配层。

## 三、Fabric 专有的 5 处 API 差异

### 1. 负载注册方法名（最容易踩）

```java
// ❌ 旧版 Fabric API
PayloadTypeRegistry.playC2S().register(type, codec);
PayloadTypeRegistry.playS2C().register(type, codec);

// ✅ 26.1 的 Fabric API 0.149.1
PayloadTypeRegistry.serverboundPlay().register(type, codec);
PayloadTypeRegistry.clientboundPlay().register(type, codec);
```

**验证方式**：直接看 TLM 自己的 `NetworkHandler.java`（同一套 Fabric API），比猜 API 快得多。

### 2. 服务端/客户端入口必须物理分离

```
FabricPayloadRegistration       ← 服务端：PayloadTypeRegistry + ServerPlayNetworking
FabricClientPayloadRegistration ← 客户端：ClientPlayNetworking（引用 Minecraft / client 类）
MaidFileModFabric               ← ModInitializer（main 入口）
MaidFileClientFabric            ← ClientModInitializer（client 入口）
```

**混在一起会让专用服务器在类加载阶段崩**（`NoClassDefFoundError: net/minecraft/client/Minecraft`）。
NeoForge 用 `@Mod(dist = Dist.CLIENT)` 隔离，Fabric 用双入口 + 双 `fabric.mod.json` entrypoint 隔离。

### 3. 饰品容器换成了 Fabric Transfer API

| | NeoForge | Fabric |
|---|---|---|
| 容器类 | `net.neoforged.neoforge.items.ItemStackHandler` | `cn.sh1rocu.touhoulittlemaid.util.transfer.ItemStacksResourceHandler`（TLM 的 shim，基于 `net.fabricmc.fabric.api.transfer.v1`） |
| 资源类型 | `ItemResource` | `ItemVariant` |
| 槽位写入 | `set(slot, resource, amount)` | `set(slot, variant, amount, TransactionContext)` ← **必须开事务** |
| 槽位读取 | `getResource(slot).value()` → `Item` → `new ItemStack(item, amount)` | `getResource(slot)` + `getAmountAsInt(slot)` → **`ItemVariant.toStack(int)`** |

**Fabric 侧反而更好**：`ItemVariant.toStack(int)` 会带上完整数据组件，而 NeoForge 侧按 `Item` 重建会丢附魔/耐久。

事务写法：

```java
Object tx = txCls.getMethod("openOuter").invoke(null);
try {
    handler.getClass().getMethod("set", int.class, variantCls, int.class, txCls)
            .invoke(handler, slot, variant, amount, tx);
    txCls.getMethod("commit").invoke(tx);
} finally {
    txCls.getMethod("close").invoke(tx);
}
```

### 4. `PayloadCtx` 抽象（免去写两份 handle）

NeoForge 的 `IPayloadContext` 在 C2S 与 S2C 共用一套接口（`player()` 返回 `Player`）。Fabric 的接收器签名不同，且 C2S 的 player 恒为 `ServerPlayer`。

做法：在 `network/PayloadCtx.java` 定义一个最小接口（`player()` + 默认直跑的 `enqueueWork`），这样：

- `MaidFilePayloads.java` 里的 26 个 `handle(PayloadCtx)` **一行没改**
- 只在注册处分别构造 `ServerCtx`（包装 `ctx.player()`）与 `ClientCtx`（从 `Minecraft.getInstance().player` 取）

### 5. `IMaidFileNetwork.set()` 在内部类里

```java
IMaidFileNetwork.set(new FabricNetwork());          // ❌ 找不到符号
IMaidFileNetwork.Holder.set(new FabricNetwork());   // ✅
```

## 四、DFU 物品升级的泛型坑（两平台相同）

```java
Dynamic<?> upgraded = DataFixers.getDataFixer().update(...);
// ❌ 直接传 Dynamic 给 CODEC：类型推断失败
// ✅ 取出底层 CompoundTag 再解析
if (upgraded.getValue() instanceof CompoundTag tag) {
    return ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag)
            .result().orElse(ItemStack.EMPTY);
}
```

## 五、构建

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-25'
cd E:\Mod\maid_file_manager-fabric-26.1.2

# 代理参数必须逐个加引号，否则 Gradle 会把它当任务名
.\gradlew.bat :fabric:build --console=plain `
    "-Dhttp.proxyHost=127.0.0.1" "-Dhttp.proxyPort=10808" `
    "-Dhttps.proxyHost=127.0.0.1" "-Dhttps.proxyPort=10808"
```

产物：`fabric/build/libs/maid_file_manager-fabric-26.1.2-1.7.0-snapshot.jar`（约 3.86 MB）

## 六、TLM 依赖的三个坑

1. **必须构建 TLM Fabric 本体**才能拿到 jar：
   ```powershell
   cd E:\Mod\TouhouLittleMaid-Orihime-26.1
   $env:JAVA_HOME='D:\Program Files\Java\jdk-25'
   .\gradlew.bat build -x test --console=plain "-Dhttp.proxyHost=..." ...
   ```
2. **`build/libs/` 里有两个 jar，必须拿 `-sources.jar` 之外的那个**：
   - `touhoulittlemaid-fabric-1.0.0-neo2.0.0+mc26.1.2-sources.jar`（20.4 MB，1,642 个 `.java`，**0 个 `.class`**）← 拿错会报 70 个「EntityMaid 找不到」
   - `touhoulittlemaid-fabric-1.0.0-neo2.0.0+mc26.1.2.jar`（23.1 MB，2,458 个 `.class`）← 正确的
3. TLM Fabric 的包名与 NeoForge 版**完全一致**（`com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid`），所以 common 层不用改 import。

## 七、测试实例（已建）

```
I:\PCL\.minecraft\versions\26.1.2-Fabric测试\
  26.1.2-Fabric测试.json      132 个库（124 原版 + 8 fabric/asm）
  26.1.2-Fabric测试.jar       36.3 MB（原版 26.1.2 客户端）
  mods\
    touhoulittlemaid-fabric-1.0.0-neo2.0.0+mc26.1.2.jar   23.1 MB
    maid_file_manager-fabric-26.1.2-1.7.0-snapshot.jar     3.9 MB
    fabric-api-0.149.1+26.1.2.jar                          2.4 MB
```

构建方式（PCL 里新建版本太慢，这里手工组装）：
1. 从 `26.1.2-NeoForge测试.json` 取原版 124 个库，过滤掉 `neoforge|minecraftforge|net.neoforged|cpw.mods`
2. 从 `1.21.1-Fabric 0.19.5.json` 取 `net.fabricmc*` 与 `org.ow2.asm*` 共 8 个，`intermediary:1.21.1` 改为 `26.1.2`
3. `mainClass` 改为 `net.fabricmc.loader.impl.launch.knot.KnotClient`，`arguments` 去掉 `--fml.*` / DFML 参数
4. 另需下载 `intermediary-26.1.2.jar`（已放到 `libraries/net/fabricmc/intermediary/26.1.2/`）与 `fabric-api-0.149.1+26.1.2.jar`

## 八、静态验证结果

用 `javap` 检查字节码常量池（专用服务器最怕客户端类泄漏）：

| 类 | `net/minecraft/client/` 引用数 |
|---|---|
| `MaidFileModFabric`（主入口） | **0** ✅ |
| `FabricPayloadRegistration`（服务端注册） | **0** ✅ |
| `FabricClientPayloadRegistration`（客户端注册） | 0（客户端类经 `Minecraft` FQN 引用，不在 `net/minecraft/client/` 前缀统计内，需实际启动验证） |

## 九、TLM Fabric 的运行期依赖（必须装，否则启动直接报 Incompatible mods）

TLM Fabric 的 `fabric.mod.json` 硬依赖 `forgeconfigapiport`，另有三个功能依赖。这些 jar 都能从 TLM 自己的 Gradle 缓存里取（`GRADLE_USER_HOME=E:\gradle`）：

| 依赖 | 版本 | 大小 | 必需性 |
|---|---|---|---|
| forgeconfigapiport-fabric | 26.1.4 | 0.6 MB | **硬依赖**（fabric.mod.json depends） |
| fabric-language-kotlin | 1.13.11+kotlin.2.3.21 | 7.5 MB | TLM 有 Kotlin 代码，必需 |
| trinkets | 4.0.0-beta.1+26.1 | 0.5 MB | 饰品系统（Curios 的 Fabric 替代） |
| cloth-config-fabric | 26.1.154 | 1.1 MB | 配置界面 |

缓存路径前缀：`E:\gradle\caches\modules-2\files-2.1\`

## 十、runServer 冒烟的结论（放弃）

Loom 的 `runServer` 在本工程不可行：

1. **首次 remap 极慢**——要 remap 整个 MC 26.1.2 + TLM（23 MB、含 Kotlin），实测 600 秒仍未进入服务器启动阶段。
2. **runServer 只认依赖声明**——手动往 `runs/server/mods` 放 jar 无效，必须写进 `modRuntimeOnly`。
3. 为了冒烟还踩了三个工具链坑：Loom 版本（1.15.5 → 1.16-SNAPSHOT，否则读不了 TLM 的 class-tweaker v2）、Gradle 版本（9.2.0 → 9.4.0，loom 1.16.3 要求）、`modRuntimeOnly` 在旧 Loom 下不存在。

**因此 :fabric:build 产物 + 静态验证（服务端入口 0 客户端类引用）+ 用户实机启动**是当前唯一的验证路径。

## 十一、待办

- [ ] **实机启动验证**（启动 `26.1.2-Fabric测试`，看是否正常进游戏 + 模组加载成功）
- [ ] 与 neoforge-26.1.2 的行为对齐验证（饰品、好感度、AI 聊天、备份、GUI）
- [ ] `gradlew :fabric:runServer` 专用服务器冒烟（验证无客户端类泄漏）
## `.maid` 格式兼容字段（26.1 端独有）

26.1 的 TLM 换了两个存储格式，为了让 `.maid` 能**降级**到 1.20.1 / 1.21.1，导出时会额外写两份兼容副本：

| 字段 | 位置 | 作用 | 旧端如何用 |
|---|---|---|---|
| `MaidFavorability`（int） | `data` 内 | 好感度数值副本 | 1.20.1 / 1.21.1 的 TLM 直接读这个 int 字段 |
| `MaidBaubleInventory.Items`（List） | `data` 内 | 1.20 风格饰品副本，只含 `id` + `Count` | 旧端 TLM 用 `Items` 键恢复饰品（无附魔） |

**为什么不升 `format_version`**：这两个字段是加在 `data`（实体 NBT）**内部**的额外键，不是文件结构变化；旧端读到未知键会忽略，不会报错。`format_version` 仍为 `7`。

**安全边界**：`MaidFavorability` 是可被手工篡改的 int。导入时**只在源文件没有 `FavorabilityManagerCounter` 时**才用它兜底（即真正的跨版本 / 旧端导出文件）；26.1 自己导出的文件一定带 `FavorabilityManagerCounter`，TLM 从该结构恢复好感度，此时不信任这个 int 副本。

**降级损失**：饰品的**附魔 / 耐久 / 自定义组件无法反向转换**（Mojang DFU 只升不降），所以 `Items` 副本只保留 `id` + `Count` —— 降级后饰品会以全新物品出现，而不是整栏消失。

**升级方向无影响**：1.20.1 / 1.21.1 导出的 `.maid` 本来就有 `MaidFavorability` 与 `Items`，26.1 导入走 DFU 升级，不依赖这两个副本。