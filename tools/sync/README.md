# 六端同步工具

`sync.py` 用来解决这个项目最消耗精力的一件事：**同一份 `common/` 源码要在六个仓库里保持一致**。

六端：`forge-1.20.1` / `fabric-1.20.1` / `neoforge-1.21.1` / `fabric-1.21.1` / `neoforge-26.1.2` / `fabric-26.1.2`。
母工程是 `forge-1.20.1`，其余五个从它同步。

## 首次使用

```bash
cp sync.example.json sync.local.json
# 编辑 sync.local.json，填入本机路径
```

`sync.local.json` 已列入 `.gitignore`，**里面是机器相关配置，不要提交**：
仓库根目录、各端 JDK 路径、`GRADLE_USER_HOME`。

## 命令

| 命令 | 作用 |
|---|---|
| `python sync.py verify` | **提交前必跑**：编译六端 + 四端 selfTest + 架构检查 |
| `python sync.py check` | 报告哪些文件该同步而没同步 |
| `python sync.py copy` | 把该同步的文件复制过去（只复制，不改写内容） |
| `python sync.py arch` | 只跑包依赖架构检查（母工程） |
| `python sync.py arch --all` | 架构检查 + 六端包结构一致性 |
| `python sync.py report` | 详细分类清单 |

## 分类判据（`check` / `copy`）

不靠人工维护「哪些文件该同步」的清单，用内容哈希自动分类：

| 类型 | 判据 | 处理 |
|---|---|---|
| `copy` | 五端互相一致，母工程领先 | 复制到全部五端 |
| `behind` | 部分端内容 == 母工程上个提交 | 只同步落后的端 |
| `all-behind` | 五端互相一致，但都与母工程不同 | 五端一致地落后，同步 |
| `divergent` | 差异既不等于母工程当前，也不等于上个提交 | **平台差异，不动** |

外加**语义哈希**（去掉整行注释 / 空行 / 行首缩进后再比）识别「注释漂移」——这类漂移对功能零影响，但会让文件被误判成平台差异。

> ⚠️ **`divergent` 不是错误**。26.1.2 的 NBT API（`getXxx` 返回 `Optional`、`Identifier`）与渲染 API（`GuiGraphicsExtractor`）与其它四端本质不同，那些文件**必须**逐端不同。当前 74 个 common 文件里，35 个已与母工程一致（可自动同步那类），39 个是逐端平台差异。

## 架构检查（`arch`）

**为什么需要它**：`compileJava` 和 `selfTest` 都发现不了「包依赖成环」。

真实教训：曾把业务编排层 `ServerPacketHandlers` 放进 `network` 包，它依赖 `service` 和 `config`，而这两层都依赖 `network` → 产生 3 个环。**六端编译通过、396 个断言全绿的与此同时，架构已经坏了**，直到几轮后手动查图才发现。

检查四项：

1. **环检测** —— 有环即失败
2. **层级方向** —— 出现禁止的依赖边失败；新增的未登记边只警告（架构会演进，硬失败会逼人绕过检查）
3. **空包** —— 空包目录只警告
4. **六端包结构一致** —— `--all` 时检查各端包集合是否相同

### 扫描方式是关键

**同时扫 `import` 语句和全限定名引用**（`io.github.zgxhzhr.maidfm.xxx.Yyy.zzz(...)`）。

只扫 `import` 会**假通过**——本项目大量使用全限定名，真实的环全藏在那些地方。这个缺陷是在第一版 `arch` 上发现的：用手写的探针文件测试时，探针用全限定名引用、检查没抓到，才意识到扫描面不全。

同时排除 `io.github.zgxhzhr.maidfm.<ClassName>` 形式的**类名**（`Constants` 等是 `<top>` 包的类，不是包）。

### 预期依赖图

```
client   -> config, data, network, platform, service, spi, util
config   -> network
data     -> platform, spi
network  -> data, platform
platform -> （无出边）
service  -> config, data, network, platform, spi, util
spi      -> platform
util     -> （无）
```

**两条关键约束**：

- **`platform` 必须无出边**。它只提供环境能力（`isModLoaded` / `getGameDir` / `getMcVersion`），一旦它开始认识业务类型（`MaidInfo` / `PlayerMaidGroup`），就会和 `data` / `network` 互相依赖成环。
- **`spi` 只依赖 `platform`**。`spi` 是对外契约（第三方模组实现），不牵连实现层。

新增依赖边时，确认方向合理后补登记到 `sync.py` 的 `EXPECTED_EDGES`。

## 开发约定

### 每次改动

```bash
# 1. 改母工程
# 2. 同步
python sync.py check     # 看有没有该同步而没同步的
python sync.py copy      # 同步能自动同步的
python sync.py verify    # 编译 + 断言 + 架构 全绿
# 3. 六端各自提交（同一提交信息），最后统一推送
```

### `verify` 是唯一闸门

它的最终输出必须是：

```
编译: 全部通过    selfTest: 全部通过    架构: 通过
```

**三项任一失败都 exit 1**。缺任何一项都不算完成——尤其是架构检查，它抓的是编译和断言都看不见的那类退化。

### 推送后必须确认

```bash
git log --format=%h -1              # 本地
git log --format=%h -1 origin/<branch>   # 远端
```

**两个 hash 必须相同**。不要只看 `git push` 输出里有没有 `->`——错误信息里也有箭头，会误判成成功。

### 同步脚本的纪律

- **不要临时写一次性 Python 脚本**。需要的能力就加到 `sync.py` 里。
- 临时脚本**必须删干净**，别被 `git add -A` 一起提交（这个已经发生过一次）。
- **改代码结构用括号计数 / 结构定位，不要用跨行正则**。跨行正则 `[\s\S]*?` 曾经一次吞掉 1300 行代码。
- **不要轻易用 `git checkout` 恢复文件**——它会连带丢掉同一文件里其它已完成的改动。局部回退就精确删那几行。

### 26.1.2 的适配

26.1.2 两个端与其它四端的 API 差异**必须逐端处理**，不能机械复制：

| 类别 | 其它四端 | 26.1.2 |
|---|---|---|
| NBT 读取 | `getCompound` / `getString` | 返回 `Optional`，用 `getCompoundOrEmpty` |
| 资源标识 | `ResourceLocation` | `Identifier` |
| 渲染 | `GuiGraphics` / `drawString` | `GuiGraphicsExtractor` / `text` |
| 描边 | `renderOutline` | `outline` |
| 按钮 | `renderWidget` / `onPress()` | `extractContents` / `onPress(InputWithModifiers)` |
| 权限 | `player.hasPermissions(2)` | `player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)` |
| 服务端实例 | `player.server` | `((ServerLevel) level()).getServer()` |

最后两项已收敛进 `service/ServerMessaging` 的 `hasOpPermission` / `serverOf`，业务层不用再关心。

同步这类文件时，**从本端原有代码里提取实现**，只改必须改的部分。
