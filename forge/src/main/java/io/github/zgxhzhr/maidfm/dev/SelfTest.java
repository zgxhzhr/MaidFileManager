package io.github.zgxhzhr.maidfm.dev;

import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.data.NbtVersion;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.service.UuidAllocator;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import net.minecraft.nbt.CompoundTag;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import io.github.zgxhzhr.maidfm.util.ModelDisplayNames;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;

/**
 * 项目自带的离线自检，用 {@code ./gradlew :forge:selfTest} 运行。
 *
 * <p><b>为什么不用 JUnit：</b>本项目在完全离线的环境下构建，本地 Maven 缓存里只有
 * {@code junit 3.8.2}（无注解支持），拿不到 JUnit 5 的制品。所以这里只用一个
 * {@code main} + 手写断言，零外部依赖。
 *
 * <p><b>为什么不放在 {@code common/src/test}：</b>{@code common} 只是源码集合
 * （{@code multiloader-common} 插件，不产出 jar），没有独立的编译产物，也没有 test
 * sourceSet。真正的模组模块是 {@code forge}，这里放在它的 {@code dev} 包里，由
 * {@code jar} 任务排除出最终产物。
 *
 * <p><b>测什么：</b>只测**纯函数**——不启动 Minecraft、不需要游戏资源、不需要网络。
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    private SelfTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== Maid File Manager 离线自检 ===");

        testModelDisplayNames();
        testNbtVersion();
        testPacketCodec();
        testUuidAllocator();
        testFilenameSanitize();
        testPlayerNameSanitize();
        testResolvePlayerDir();
        testExportFileName();

        System.out.println();
        System.out.printf("结果：%d 通过，%d 失败%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ============================ 断言工具 ============================

    private static void check(String name, BooleanSupplier condition) {
        try {
            if (condition.getAsBoolean()) {
                passed++;
                System.out.println("  [OK]   " + name);
            } else {
                failed++;
                System.out.println("  [FAIL] " + name);
            }
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + " —— 抛异常: " + t);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!ok) {
            System.out.println("        期望 [" + expected + "] 实际 [" + actual + "]");
        }
        check(name, () -> ok);
    }

    /** 断言某操作抛 IllegalArgumentException（用于目录穿越防护）。 */
    private static void throwsIAE(String name, Runnable action) {
        try {
            action.run();
            failed++;
            System.out.println("  [FAIL] " + name + " —— 未抛异常");
        } catch (IllegalArgumentException e) {
            passed++;
            System.out.println("  [OK]   " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("  [FAIL] " + name + " —— 抛了 " + t.getClass().getSimpleName());
        }
    }

    // ============================ 用例 ============================

    private static void testNbtVersion() {
        System.out.println("\n-- data.NbtVersion（版本号换算） --");

        // ---- fromMcVersion ----
        eq("1.20.1 -> MC_1_20_1", NbtVersion.MC_1_20_1, NbtVersion.fromMcVersion("1.20.1"));
        eq("1.20.6 -> MC_1_20_6", NbtVersion.MC_1_20_6, NbtVersion.fromMcVersion("1.20.6"));
        eq("1.20.5 -> MC_1_20_6", NbtVersion.MC_1_20_6, NbtVersion.fromMcVersion("1.20.5"));
        eq("1.20（无补丁号）-> MC_1_20_1", NbtVersion.MC_1_20_1, NbtVersion.fromMcVersion("1.20"));
        eq("1.21 -> MC_1_21", NbtVersion.MC_1_21, NbtVersion.fromMcVersion("1.21"));
        eq("1.21.1 -> MC_1_21_1", NbtVersion.MC_1_21_1, NbtVersion.fromMcVersion("1.21.1"));
        eq("1.21.1-pre1 按前缀匹配到 1.21.1", NbtVersion.MC_1_21_1,
                NbtVersion.fromMcVersion("1.21.1-pre1"));
        eq("1.21.4 会先命中 1.21 分支", NbtVersion.MC_1_21, NbtVersion.fromMcVersion("1.21.4"));
        eq("未知版本 -> UNKNOWN", NbtVersion.UNKNOWN, NbtVersion.fromMcVersion("1.19.2"));
        eq("空串 -> UNKNOWN", NbtVersion.UNKNOWN, NbtVersion.fromMcVersion(""));
        eq("null -> UNKNOWN", NbtVersion.UNKNOWN, NbtVersion.fromMcVersion(null));

        // ---- mojangDataVersion ----
        eq("MC_1_20_1 -> 3465", 3465, NbtVersion.mojangDataVersion(NbtVersion.MC_1_20_1));
        eq("MC_1_20_6 -> 3837", 3837, NbtVersion.mojangDataVersion(NbtVersion.MC_1_20_6));
        eq("MC_1_21 -> 3953", 3953, NbtVersion.mojangDataVersion(NbtVersion.MC_1_21));
        eq("MC_1_21_1 -> 3955", 3955, NbtVersion.mojangDataVersion(NbtVersion.MC_1_21_1));
        eq("UNKNOWN -> -1", -1, NbtVersion.mojangDataVersion(NbtVersion.UNKNOWN));
        eq("任意未知数值 -> -1", -1, NbtVersion.mojangDataVersion(999));

        // ---- fromMojangDataVersion：边界要逐个压 ----
        eq("3465 -> MC_1_20_1", NbtVersion.MC_1_20_1, NbtVersion.fromMojangDataVersion(3465));
        eq("3464（1.20 之前）-> UNKNOWN", NbtVersion.UNKNOWN,
                NbtVersion.fromMojangDataVersion(3464));
        eq("3836（差 1 不足 1.20.6）-> MC_1_20_1", NbtVersion.MC_1_20_1,
                NbtVersion.fromMojangDataVersion(3836));
        eq("3837 -> MC_1_20_6", NbtVersion.MC_1_20_6, NbtVersion.fromMojangDataVersion(3837));
        eq("3952（差 1 不足 1.21）-> MC_1_20_6", NbtVersion.MC_1_20_6,
                NbtVersion.fromMojangDataVersion(3952));
        eq("3953 -> MC_1_21", NbtVersion.MC_1_21, NbtVersion.fromMojangDataVersion(3953));
        eq("3954（差 1 不足 1.21.1）-> MC_1_21", NbtVersion.MC_1_21,
                NbtVersion.fromMojangDataVersion(3954));
        eq("3955 -> MC_1_21_1", NbtVersion.MC_1_21_1, NbtVersion.fromMojangDataVersion(3955));
        eq("4000（更新版本）-> MC_1_21_1", NbtVersion.MC_1_21_1,
                NbtVersion.fromMojangDataVersion(4000));
        eq("0 -> UNKNOWN", NbtVersion.UNKNOWN, NbtVersion.fromMojangDataVersion(0));
        eq("负数 -> UNKNOWN", NbtVersion.UNKNOWN, NbtVersion.fromMojangDataVersion(-7));

        // ---- 往返一致性：这是历史上出过事故的那条链 ----
        check("mojangDataVersion / fromMojangDataVersion 对四个已知版本往返一致", () -> {
            int[] known = {NbtVersion.MC_1_20_1, NbtVersion.MC_1_20_6, NbtVersion.MC_1_21,
                    NbtVersion.MC_1_21_1};
            for (int v : known) {
                int mojang = NbtVersion.mojangDataVersion(v);
                if (mojang <= 0) {
                    return false;
                }
                if (NbtVersion.fromMojangDataVersion(mojang) != v) {
                    System.out.println("        往返失败于 " + v + " -> " + mojang);
                    return false;
                }
            }
            return true;
        });

        check("同版本不会被判成跨版本（3465 -> 1.20.1 且 mojangDataVersion 回 3465）", () -> {
            int v = NbtVersion.fromMojangDataVersion(3465);
            return v == NbtVersion.MC_1_20_1 && NbtVersion.mojangDataVersion(v) == 3465;
        });
    }

    private static void testPacketCodec() {
        System.out.println("\n-- network.MaidFilePackets（编解码往返） --");

        // ---- checkSize：纯数值边界 ----
        eq("checkSize 正常值原样返回", 5, MaidFilePackets.checkSize(5, 10, "x"));
        eq("checkSize 边界值（等于上限）通过", 10, MaidFilePackets.checkSize(10, 10, "x"));
        eq("checkSize 0 通过", 0, MaidFilePackets.checkSize(0, 10, "x"));
        throwsIAE("checkSize 负数拒绝", () -> MaidFilePackets.checkSize(-1, 10, "x"));
        throwsIAE("checkSize 超上限拒绝", () -> MaidFilePackets.checkSize(11, 10, "x"));

        // ---- writeStringList / readStringList 往返 ----
        FriendlyByteBuf b1 = new FriendlyByteBuf(Unpooled.buffer());
        List<String> src = List.of("a", "b", "草东", "");
        MaidFilePackets.writeStringList(b1, src);
        eq("字符串列表往返相等", src, MaidFilePackets.readStringList(b1));

        FriendlyByteBuf b2 = new FriendlyByteBuf(Unpooled.buffer());
        MaidFilePackets.writeStringList(b2, List.of());
        eq("空列表往返为空", List.of(), MaidFilePackets.readStringList(b2));

        FriendlyByteBuf b3 = new FriendlyByteBuf(Unpooled.buffer());
        MaidFilePackets.writeStringList(b3, null);
        eq("null 列表读回为空", List.of(), MaidFilePackets.readStringList(b3));

        // 历史事故点：写端截断 256 但遍历全部 -> 整包错位。这里验证条数一致。
        FriendlyByteBuf b4 = new FriendlyByteBuf(Unpooled.buffer());
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            big.add("s" + i);
        }
        MaidFilePackets.writeStringList(b4, big);
        List<String> backBig = MaidFilePackets.readStringList(b4);
        eq("超 256 条时被截断到 256", 256, backBig.size());
        eq("截断后首元素正确", "s0", backBig.get(0));
        eq("截断后末元素是第 256 条", "s255", backBig.get(255));

        // null 元素写成空串
        FriendlyByteBuf b5 = new FriendlyByteBuf(Unpooled.buffer());
        List<String> withNull = new ArrayList<>();
        withNull.add("x");
        withNull.add(null);
        withNull.add("y");
        MaidFilePackets.writeStringList(b5, withNull);
        eq("null 元素变成空串", List.of("x", "", "y"), MaidFilePackets.readStringList(b5));

        // ---- writeIntList / readIntList ----
        FriendlyByteBuf b6 = new FriendlyByteBuf(Unpooled.buffer());
        List<Integer> ints = List.of(1, 2, 3, -4, 0);
        MaidFilePackets.writeIntList(b6, ints);
        eq("整数列表往返相等", ints, MaidFilePackets.readIntList(b6));

        FriendlyByteBuf b7 = new FriendlyByteBuf(Unpooled.buffer());
        MaidFilePackets.writeIntList(b7, List.of());
        eq("空整数列表往返为空", List.of(), MaidFilePackets.readIntList(b7));

        // ---- writeBooleanList / readBooleanList ----
        FriendlyByteBuf b8 = new FriendlyByteBuf(Unpooled.buffer());
        List<Boolean> bools = List.of(true, false, true);
        MaidFilePackets.writeBooleanList(b8, bools);
        eq("布尔列表往返相等", bools, MaidFilePackets.readBooleanList(b8));

        // ---- 协议版本必须有值且非空（构建期也有正则校验，这里补运行期断言） ----
        check("PROTOCOL_VERSION 非空", () -> {
            String v = MaidFilePackets.PROTOCOL_VERSION;
            return v != null && !v.isEmpty();
        });
    }

    private static void testUuidAllocator() {
        System.out.println("\n-- service.UuidAllocator（UUID 派生与解析） --");

        UUID source = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID alice = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID bob = UUID.fromString("99999999-8888-7777-6666-555555555555");

        // ---- mapImportUuid：纯函数，必须确定性 ----
        UUID a1 = UuidAllocator.mapImportUuid(source, alice);
        UUID a2 = UuidAllocator.mapImportUuid(source, alice);
        eq("同一输入两次派生结果相同（确定性）", a1, a2);

        UUID b1 = UuidAllocator.mapImportUuid(source, bob);
        check("不同玩家派生出不同 UUID（多人服共享文件互不冲突）", () -> !a1.equals(b1));

        UUID otherSource = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID a3 = UuidAllocator.mapImportUuid(otherSource, alice);
        check("不同源女仆派生出不同 UUID", () -> !a1.equals(a3));

        check("派生结果是 v3(MD5 名字) UUID", () -> a1.version() == 3);
        check("派生结果不是源 UUID（默认路径才保留源）", () -> !a1.equals(source));

        // ---- resolveSourceMaidUuid：顶层字段优先 ----
        MaidFileData data = new MaidFileData();
        data.setSourceMaidUuid(source.toString());
        eq("顶层 source_maid_uuid 被正确解析", source,
                UuidAllocator.resolveSourceMaidUuid(data, null));

        // 顶层字段非法 -> 落回 NBT 兜底；NBT 也没有 -> null
        MaidFileData bad = new MaidFileData();
        bad.setSourceMaidUuid("not-a-uuid");
        eq("顶层字段非法且无 NBT 兜底 -> null", null,
                UuidAllocator.resolveSourceMaidUuid(bad, null));

        // NBT 兜底：UUID 整型数组
        CompoundTag nbt = new CompoundTag();
        nbt.putUUID("UUID", source);
        eq("NBT 的 UUID 键作为兜底", source,
                UuidAllocator.resolveSourceMaidUuid(new MaidFileData(), nbt));

        // NBT 兜底：UUIDMost/UUIDLeast（1.8 格式）
        CompoundTag old = new CompoundTag();
        old.putLong("UUIDMost", source.getMostSignificantBits());
        old.putLong("UUIDLeast", source.getLeastSignificantBits());
        eq("NBT 的 UUIDMost/UUIDLeast 作为兜底", source,
                UuidAllocator.resolveSourceMaidUuid(new MaidFileData(), old));

        // 顶层优先于 NBT
        MaidFileData withBoth = new MaidFileData();
        withBoth.setSourceMaidUuid(alice.toString());
        eq("顶层字段优先于 NBT 兜底", alice,
                UuidAllocator.resolveSourceMaidUuid(withBoth, nbt));

        // ---- 查询类方法：server 为 null 时必须安全返回 false ----
        check("isUuidOccupied(null, uuid) 返回 false",
                () -> !UuidAllocator.isUuidOccupied(null, source));
        check("isUuidOccupied(server, null) 返回 false",
                () -> !UuidAllocator.isUuidOccupied(null, null));
        check("isLoadedMaidWithUuid(null, uuid) 返回 false",
                () -> !UuidAllocator.isLoadedMaidWithUuid(null, source));
    }

    private static void testModelDisplayNames() {
        System.out.println("\n-- util.ModelDisplayNames.prettify --");

        eq("空串 -> 空串", "", ModelDisplayNames.prettify(""));
        eq("null -> 空串", "", ModelDisplayNames.prettify(null));
        eq("单段下划线转空格 + 首字母大写",
                "Wine Fox", ModelDisplayNames.prettify("wine_fox"));
        eq("多段", "Nineteen Nine Tailed",
                ModelDisplayNames.prettify("nineteen_nine_tailed"));
        eq("已含空格时保持", "Wine Fox", ModelDisplayNames.prettify("Wine Fox"));
        eq("首尾下划线被 trim 后不留空格",
                "Ab", ModelDisplayNames.prettify("_ab_"));
        eq("中文字符不被改动", "草东", ModelDisplayNames.prettify("草东"));
    }

    private static void testFilenameSanitize() {
        System.out.println("\n-- data.MaidFileIo.sanitize（文件名清洗） --");

        eq("空串 -> unknown", "unknown", MaidFileIo.sanitize(""));
        eq("null -> unknown", "unknown", MaidFileIo.sanitize(null));
        eq("纯空白 -> unknown", "unknown", MaidFileIo.sanitize("   "));
        eq("正常名保持不变", "my_maid", MaidFileIo.sanitize("my_maid"));
        // Windows 非法字符：\ / : * ? " < > | 以及控制字符
        eq("反斜杠 -> 下划线", "a_b", MaidFileIo.sanitize("a\\b"));
        eq("正斜杠 -> 下划线", "a_b", MaidFileIo.sanitize("a/b"));
        eq("冒号 -> 下划线", "a_b", MaidFileIo.sanitize("a:b"));
        eq("星号问号 -> 下划线", "a_b_c", MaidFileIo.sanitize("a*b?c"));
        eq("尖括号引号竖线 -> 下划线", "a_b_c_d", MaidFileIo.sanitize("a<b>c\"d|"));
        eq("方括号 -> 下划线", "a_b", MaidFileIo.sanitize("a[b]".replace("]", "")));
        eq("控制字符 -> 下划线", "a_b", MaidFileIo.sanitize("a\u0001b"));
        eq("连续非法字符折叠为一个下划线", "a_b", MaidFileIo.sanitize("a///b"));
        eq("首尾下划线被去除", "abc", MaidFileIo.sanitize("___abc___"));
        eq("中文保留", "草东", MaidFileIo.sanitize("草东"));
        eq("空格被 trim", "a b", MaidFileIo.sanitize("  a b  "));
        // 超长截断：上限 180 字节，UTF-8 中文 3 字节/字 -> 60 字
        String longName = "测".repeat(100);
        String cut = MaidFileIo.sanitize(longName);
        check("超长名按 UTF-8 字节截断到 <=180 字节",
                () -> cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 180);
        check("截断后不产生半个字符（长度是 3 的倍数）", () -> cut.length() % 3 == 0);
    }

    private static void testPlayerNameSanitize() {
        System.out.println("\n-- data.MaidFileIo.sanitizePlayerName（玩家名清洗） --");

        eq("空串 -> unknown", "unknown", MaidFileIo.sanitizePlayerName(""));
        eq("null -> unknown", "unknown", MaidFileIo.sanitizePlayerName(null));
        // 目录项穿越
        eq("单点 -> unknown", "unknown", MaidFileIo.sanitizePlayerName("."));
        eq("双点 -> unknown", "unknown", MaidFileIo.sanitizePlayerName(".."));
        // 三个点也被当作尾随点循环去光 -> 空 -> unknown（安全，不是合法目录名）
        eq("全是点的名字 -> unknown", "unknown", MaidFileIo.sanitizePlayerName("..."));
        // Windows 保留设备名
        eq("CON -> unknown", "unknown", MaidFileIo.sanitizePlayerName("CON"));
        eq("con 小写 -> unknown", "unknown", MaidFileIo.sanitizePlayerName("con"));
        eq("NUL -> unknown", "unknown", MaidFileIo.sanitizePlayerName("NUL"));
        eq("COM1 -> unknown", "unknown", MaidFileIo.sanitizePlayerName("COM1"));
        eq("LPT9 -> unknown", "unknown", MaidFileIo.sanitizePlayerName("LPT9"));
        eq("CON.txt -> unknown（带扩展名也算）",
                "unknown", MaidFileIo.sanitizePlayerName("CON.txt"));
        eq("COM0 不是保留名（只有 1-9）", "COM0", MaidFileIo.sanitizePlayerName("COM0"));
        // 单人下划线不能被吞掉（注释里明确指出的历史问题）
        eq("单独下划线保留为 _", "_", MaidFileIo.sanitizePlayerName("_"));
        eq("两个下划线保留", "__", MaidFileIo.sanitizePlayerName("__"));
        // 尾随点
        eq("尾随点被去除", "name", MaidFileIo.sanitizePlayerName("name."));
        eq("多个尾随点被去除", "name", MaidFileIo.sanitizePlayerName("name..."));
        // 非法字符
        eq("正斜杠 -> 下划线", "a_b", MaidFileIo.sanitizePlayerName("a/b"));
        eq("正常玩家名保持不变", "Notch", MaidFileIo.sanitizePlayerName("Notch"));
    }

    private static void testResolvePlayerDir() {
        System.out.println("\n-- data.MaidFileIo.resolvePlayerDir（目录穿越防护） --");

        Path root = Paths.get("/tmp/maid_exports_test");
        Path dir = MaidFileIo.resolvePlayerDir(root, "Notch");
        check("正常玩家名解析为根下子目录", () -> dir.startsWith(root.toAbsolutePath().normalize()));
        check("子目录名等于清洗后的玩家名", () -> dir.getFileName().toString().equals("Notch"));

        // 恶意输入应被清洗成 unknown，仍落在根内（而不是抛异常）
        Path evil = MaidFileIo.resolvePlayerDir(root, "../..");
        check("路径穿越输入被清洗后仍在根内",
                () -> evil.startsWith(root.toAbsolutePath().normalize()));

        Path dotdot = MaidFileIo.resolvePlayerDir(root, "..");
        check(".. 被清洗后仍在根内", () -> dotdot.startsWith(root.toAbsolutePath().normalize()));
    }

    private static void testExportFileName() {
        System.out.println("\n-- data.MaidFileIo.buildExportFileName（导出文件名） --");

        LocalDateTime t = LocalDateTime.of(2026, 10, 31, 9, 30, 0);
        String owner = "12345678-1111-2222-3333-444455556666";

        String f1 = MaidFileIo.buildExportFileName("草东", "cao_dong:001", owner, t);
        check("文件名含清洗后的显示名", () -> f1.startsWith("草东_"));
        check("文件名含时间戳 20261031-093000", () -> f1.contains("20261031-093000"));
        check("文件名含 8 位短 UUID", () -> f1.contains("12345678"));
        check("扩展名是 .maid", () -> f1.endsWith(".maid"));

        // 显示名为空时回退到 modelId 的 path 段
        String f2 = MaidFileIo.buildExportFileName("", "cao_dong:001", owner, t);
        check("显示名为空时回退到 modelId path", () -> f2.startsWith("001_"));

        // ownerUuid 为 null 时用 nouuid
        String f3 = MaidFileIo.buildExportFileName("x", "m:n", null, t);
        check("ownerUuid 为 null 时用 nouuid", () -> f3.contains("nouuid"));

        // 显示名含非法字符时被清洗
        String f4 = MaidFileIo.buildExportFileName("a/b:c", "m:n", owner, t);
        check("非法字符不进文件名", () -> !f4.contains("/") && !f4.contains(":"));
    }
}
