package io.github.zgxhzhr.maidfm.dev;

import io.github.zgxhzhr.maidfm.data.MaidFileIo;
import io.github.zgxhzhr.maidfm.data.NbtMigration;
import io.github.zgxhzhr.maidfm.data.NbtVersion;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.network.MaidProfileCodec;
import io.github.zgxhzhr.maidfm.network.MaidBackupCodec;
import io.github.zgxhzhr.maidfm.network.MaidInfoCodec;
import io.github.zgxhzhr.maidfm.service.MaidEffectCodec;
import io.github.zgxhzhr.maidfm.service.TlmDataCodec;
import io.github.zgxhzhr.maidfm.service.UuidAllocator;
import io.github.zgxhzhr.maidfm.config.BaubleImportConfig;
import io.github.zgxhzhr.maidfm.config.ClientConfig;
import io.github.zgxhzhr.maidfm.config.ServerConfig;
import io.github.zgxhzhr.maidfm.config.ConfigFileIo;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
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
 *
 * <p><b>测不了的：</b>{@code NbtDowngrade.downgradeItemStackNbt} 返回 {@code ItemStack}，
 * 而 {@code ItemStack} 的静态初始化依赖 Minecraft 引导（{@code NoClassDefFoundError:
 * Could not initialize class net.minecraft.world.item.ItemStack}），离线环境跑不了。
 * 那部分只能靠游戏内实测覆盖，不在这里硬凑。
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
        testMaidFileCodecs();
        testEffectCodec();
        testUuidAllocator();
        testNbtMigration();
        testConfigKeys();
        testConfigFileIo();
        testBaubleImportConfig();
        testClientConfig();
        testServerConfig();
        testTlmDataCodec();
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

    private static void testNbtMigration() {
        System.out.println("\n-- data.NbtMigration（导入裁剪） --");

        // 构造一个「什么都带一点」的实体 NBT
        CompoundTag src = new CompoundTag();
        src.putFloat("Health", 20f);                 // 运行时状态 -> 删
        src.putShort("HurtTime", (short) 0);         // 运行时状态 -> 删
        src.putFloat("FallDistance", 0f);            // 运行时状态 -> 删
        src.putString("MaidInventory", "x");         // 物品容器 -> 删
        src.putString("HandItems", "x");             // 物品容器 -> 删
        src.putString("MaidBackpackData", "x");      // 物品容器 -> 删
        src.putString("BedPosition", "x");           // 日程位置 -> 删
        src.putString("MaidRestrictRadius", "x");    // 日程位置 -> 删
        src.putString("CustomName", "草东");          // 业务键 -> 保留
        src.putString("OwnerUUID", "uuid-here");     // 业务键 -> 保留
        src.putString("ModelId", "cao_dong:001");    // 业务键 -> 保留
        src.putString("SomethingElse", "keep");      // 未知键 -> 保留

        CompoundTag out = NbtMigration.migrate(src, NbtVersion.MC_1_20_1, NbtVersion.MC_1_20_1,
                null, null, false);

        check("migrate 返回非 null", () -> out != null);
        check("Health 被裁掉", () -> !out.contains("Health"));
        check("HurtTime 被裁掉", () -> !out.contains("HurtTime"));
        check("FallDistance 被裁掉", () -> !out.contains("FallDistance"));
        check("MaidInventory 被裁掉", () -> !out.contains("MaidInventory"));
        check("HandItems 被裁掉", () -> !out.contains("HandItems"));
        check("MaidBackpackData 被裁掉", () -> !out.contains("MaidBackpackData"));
        check("BedPosition 被裁掉", () -> !out.contains("BedPosition"));
        check("MaidRestrictRadius 被裁掉", () -> !out.contains("MaidRestrictRadius"));
        check("CustomName 保留", () -> out.contains("CustomName"));
        check("OwnerUUID 保留", () -> out.contains("OwnerUUID"));
        check("ModelId 保留（同版本不改名）", () -> out.contains("ModelId"));
        check("未知键保留（不做白名单过滤）", () -> out.contains("SomethingElse"));
        eq("CustomName 内容不变", "草东", out.getString("CustomName"));
        check("不修改入参（在原对象上仍能读到 Health）", () -> src.contains("Health"));

        // 跨版本：ModelId -> model_id，且 YSM 一组键整体清空
        CompoundTag cross = new CompoundTag();
        cross.putString("ModelId", "cao_dong:001");
        cross.putByte("IsYsmModel", (byte) 1);
        cross.putString("YsmModelId", "Wine Fox/02 New Year");
        cross.putString("YsmModelName", "酒尾狐");
        cross.putString("YsmModelTexture", "tex");
        cross.putString("YsmRoamingVars", "roam");   // 漫游数据，与 YSM 换模无关 -> 保留

        CompoundTag migrated = NbtMigration.migrate(cross, NbtVersion.MC_1_20_1, NbtVersion.MC_1_21,
                null, null, false);
        check("跨版本 ModelId 改名为 model_id", () -> !migrated.contains("ModelId") && migrated.contains("model_id"));
        check("跨版本 IsYsmModel 清空", () -> !migrated.contains("IsYsmModel"));
        check("跨版本 YsmModelId 清空", () -> !migrated.contains("YsmModelId"));
        check("跨版本 YsmModelName 清空", () -> !migrated.contains("YsmModelName"));
        check("跨版本 YsmModelTexture 清空", () -> !migrated.contains("YsmModelTexture"));
        check("YsmRoamingVars 不受影响", () -> migrated.contains("YsmRoamingVars"));

        // 同版本：YSM 键不应被动
        CompoundTag sameVer = new CompoundTag();
        sameVer.putByte("IsYsmModel", (byte) 1);
        sameVer.putString("YsmModelName", "酒尾狐");
        CompoundTag kept = NbtMigration.migrate(sameVer, NbtVersion.MC_1_20_1, NbtVersion.MC_1_20_1,
                null, null, false);
        check("同版本 YSM 键保留", () -> kept.contains("IsYsmModel") && kept.contains("YsmModelName"));

        // 版本未知：只做卫生清理，不做结构改名
        CompoundTag unknownVer = new CompoundTag();
        unknownVer.putString("ModelId", "m:p");
        unknownVer.putFloat("Health", 1f);
        CompoundTag u = NbtMigration.migrate(unknownVer, NbtVersion.UNKNOWN, NbtVersion.UNKNOWN,
                null, null, false);
        check("版本未知仍做卫生清理", () -> !u.contains("Health"));
        check("版本未知不改名 ModelId", () -> u.contains("ModelId") && !u.contains("model_id"));

        // null 输入
        eq("null 输入返回 null", null, NbtMigration.migrate(null, NbtVersion.MC_1_20_1,
                NbtVersion.MC_1_20_1, null, null, false));
    }

    private static void testConfigKeys() {
        System.out.println("\n-- config.MaidConfigManager（键映射） --");

        // getServerConfigByKey 是纯读，不写盘；未初始化时返回各字段默认值
        check("null 键返回 false", () -> !MaidConfigManager.getServerConfigByKey(null));
        check("未知键返回 false", () -> !MaidConfigManager.getServerConfigByKey("no_such_key_xyz"));
        check("空串键返回 false", () -> !MaidConfigManager.getServerConfigByKey(""));

        // 已知键不应抛异常，返回值只需是布尔（默认值取决于实现）
        for (String key : new String[]{
                "allow_client_import", "allow_baubles", "allow_advancements",
                "allow_effects", "allow_invulnerable", "bauble_strip_attributes", "carry_curios"}) {
            check("已知键 " + key + " 可读且不抛异常", () -> {
                try {
                    MaidConfigManager.getServerConfigByKey(key);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
        }

        // 白名单判定：默认未强制时任何 ID 都应放行
        check("默认配置下 isBaubleWhitelistPass 不抛异常", () -> {
            try {
                MaidConfigManager.isBaubleWhitelistPass("touhou_little_maid:maid_soul_stone", null);
                return true;
            } catch (Throwable t) {
                return false;
            }
        });

        // 强制拦截清单：默认应为空（内置声明之外）
        check("getBaubleBlockedList 返回非 null", () -> MaidConfigManager.getBaubleBlockedList() != null);
        check("getBaubleBlacklist 返回非 null", () -> MaidConfigManager.getBaubleBlacklist() != null);
        check("getBaubleWhitelist 返回非 null", () -> MaidConfigManager.getBaubleWhitelist() != null);
    }

    private static void testConfigFileIo() {
        System.out.println("\n-- config.ConfigFileIo（配置读写纯工具） --");

        // ==================== parse ====================
        Properties p = new Properties();
        p.setProperty("t", "true");
        p.setProperty("tu", "TRUE");
        p.setProperty("f", "false");
        p.setProperty("one", "1");
        p.setProperty("empty", "");
        eq("parse: \"true\" -> true", true, ConfigFileIo.parse(p, "t", false));
        eq("parse: \"TRUE\" 大小写不敏感", true, ConfigFileIo.parse(p, "tu", false));
        eq("parse: \"false\" -> false", false, ConfigFileIo.parse(p, "f", true));
        eq("parse: \"1\" 不算 true", false, ConfigFileIo.parse(p, "one", true));
        eq("parse: 空串回退默认值", true, ConfigFileIo.parse(p, "empty", true));
        eq("parse: 缺失回退默认值", true, ConfigFileIo.parse(p, "missing", true));

        // ==================== parseIdList ====================
        Properties p2 = new Properties();
        p2.setProperty("list", " a , b ,, a , C ");
        eq("parseIdList: 逗号分割 + 去空白 + 去重（大小写保留）",
                List.of("a", "b", "C"), ConfigFileIo.parseIdList(p2, "list"));
        eq("parseIdList: 缺失 -> 空列表", List.of(), ConfigFileIo.parseIdList(p2, "nope"));

        // ==================== sanitizeList ====================
        eq("sanitizeList: 小写化 + 去空白 + 去重保序",
                List.of("abc", "def"), ConfigFileIo.sanitizeList(List.of("ABC", " Def ", "abc")));
        eq("sanitizeList: null -> 空列表", List.of(), ConfigFileIo.sanitizeList(null));
        eq("sanitizeList: 空列表 -> 空列表", List.of(), ConfigFileIo.sanitizeList(List.of()));
        eq("parseSanitizedList: parseIdList + sanitizeList 组合",
                List.of("a", "b", "c"), ConfigFileIo.parseSanitizedList(p2, "list"));

        // ==================== parseVolume ====================
        Properties p3 = new Properties();
        p3.setProperty("v1", "0.5");
        p3.setProperty("v2", "-1");
        p3.setProperty("v3", "2.5");
        p3.setProperty("v4", "NaN");
        p3.setProperty("v5", "abc");
        eq("parseVolume: 0.5 原样", 0.5F, ConfigFileIo.parseVolume(p3, "v1", 0.15F));
        eq("parseVolume: 负数钳到 0", 0F, ConfigFileIo.parseVolume(p3, "v2", 0.15F));
        eq("parseVolume: >1 钳到 1", 1F, ConfigFileIo.parseVolume(p3, "v3", 0.15F));
        eq("parseVolume: NaN -> 0", 0F, ConfigFileIo.parseVolume(p3, "v4", 0.15F));
        eq("parseVolume: 非数字回退默认值", 0.15F, ConfigFileIo.parseVolume(p3, "v5", 0.15F));

        // ==================== mergeLists ====================
        eq("mergeLists: 合并去重保序",
                List.of("a", "b", "c"), ConfigFileIo.mergeLists(List.of("a", "b"), List.of("b", "c")));
        eq("mergeLists: 第二张为空 -> 原样返回第一张",
                List.of("a"), ConfigFileIo.mergeLists(List.of("a"), List.of()));
        eq("mergeLists: 第一张为 null",
                List.of("a"), ConfigFileIo.mergeLists(null, List.of("a")));
        eq("mergeLists: 两张都空", List.of(), ConfigFileIo.mergeLists(List.of(), List.of()));

        // ==================== readStringArray ====================
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add("A");
        arr.add("b");
        arr.add(123);      // 数字元素应被忽略
        arr.add(true);     // 布尔元素应被忽略
        arr.add("a");      // 与 "A" 小写后重复，应去重
        root.add("list", arr);
        eq("readStringArray: 只收字符串、小写去重保序",
                List.of("a", "b"), ConfigFileIo.readStringArray(root, "list"));
        eq("readStringArray: 字段缺失 -> 空列表",
                List.of(), ConfigFileIo.readStringArray(root, "nope"));
        check("readStringArray: 字段类型不是数组时抛异常（失败关闭）", () -> {
            JsonObject bad = new JsonObject();
            bad.addProperty("list", "not-an-array");
            try {
                ConfigFileIo.readStringArray(bad, "list");
                return false;   // 没抛 -> 失败
            } catch (Throwable t) {
                return true;
            }
        });

        // ==================== store + load 往返（临时目录） ====================
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("maidfm_selftest");
            Path file = tmpDir.resolve("sub").resolve("test.properties");

            Properties out = new Properties();
            out.setProperty("alpha", "true");
            out.setProperty("beta", "hello 世界");
            out.setProperty("gamma", "0.42");
            check("store: 首次写入成功（父目录自动创建）", () -> ConfigFileIo.store(out, file));
            check("store: 目标文件已存在", () -> Files.isRegularFile(file));
            check("store: 不留下 .tmp 残file", () -> {
                try (var s = Files.list(file.getParent())) {
                    return s.noneMatch(x -> x.getFileName().toString().endsWith(".tmp"));
                } catch (Exception e) {
                    return false;
                }
            });

            Properties in = ConfigFileIo.load(file, "测试配置");
            eq("load: alpha 读回", "true", in.getProperty("alpha"));
            eq("load: 中文值读回", "hello 世界", in.getProperty("beta"));
            eq("load: 浮点值读回", "0.42", in.getProperty("gamma"));

            // 覆盖写 + 注释保留
            Files.writeString(file, "# 手写注释：这一行必须保留\n" + Files.readString(file));
            Properties again = new Properties();
            again.setProperty("alpha", "false");
            check("store: 覆盖写成功", () -> ConfigFileIo.store(again, file));
            String text = Files.readString(file);
            check("store: 手写注释被保留", () -> text.contains("手写注释"));
            Properties in2 = ConfigFileIo.load(file, "测试配置");
            eq("store: 覆盖后值生效", "false", in2.getProperty("alpha"));
            eq("store: 覆盖后未声明的旧键自然消失", null, in2.getProperty("beta"));

            // 读不存在的文件 -> 空实例
            Properties missing = ConfigFileIo.load(tmpDir.resolve("nope.properties"), "不存在的配置");
            eq("load: 文件不存在返回空 Properties", 0, missing.size());
        } catch (Exception e) {
            check("store/load 往返用例整体执行", () -> false);
            System.out.println("        异常: " + e);
        } finally {
            if (tmpDir != null) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void testMaidFileCodecs() {
        System.out.println("\n-- network.MaidFilePackets（主体编解码往返） --");

        UUID ownerUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // ==================== MaidInfo 单条 ====================
        MaidInfo full = new MaidInfo(42, "touhou_little_maid:model_a", "显示名", 7,
                19.5F, 20.0F, true, ownerUuid, "主人名", "自定义名", true);
        FriendlyByteBuf b1 = new FriendlyByteBuf(Unpooled.buffer());
        MaidInfoCodec.writeMaidInfo(b1, full);
        MaidInfo r1 = MaidInfoCodec.readMaidInfo(b1);
        eq("MaidInfo: entityId", 42, r1.entityId());
        eq("MaidInfo: modelId", "touhou_little_maid:model_a", r1.modelId());
        eq("MaidInfo: displayName", "显示名", r1.displayName());
        eq("MaidInfo: favorability", 7, r1.favorability());
        eq("MaidInfo: health", 19.5F, r1.health());
        eq("MaidInfo: maxHealth", 20.0F, r1.maxHealth());
        eq("MaidInfo: tamed", true, r1.tamed());
        eq("MaidInfo: ownerUuid", ownerUuid, r1.ownerUuid());
        eq("MaidInfo: ownerName", "主人名", r1.ownerName());
        eq("MaidInfo: customName", "自定义名", r1.customName());
        eq("MaidInfo: ysmModel", true, r1.ysmModel());

        // 三个可空字段为 null
        MaidInfo minimal = new MaidInfo(1, "m:p", "d", 0, 1F, 1F, false, null, null, null, false);
        FriendlyByteBuf b2 = new FriendlyByteBuf(Unpooled.buffer());
        MaidInfoCodec.writeMaidInfo(b2, minimal);
        MaidInfo r2 = MaidInfoCodec.readMaidInfo(b2);
        eq("MaidInfo: ownerUuid null 往返", null, r2.ownerUuid());
        eq("MaidInfo: ownerName null 往返", null, r2.ownerName());
        eq("MaidInfo: customName null 往返", null, r2.customName());
        eq("MaidInfo: tamed false 往返", false, r2.tamed());

        // ==================== MaidInfo 列表 ====================
        FriendlyByteBuf b3 = new FriendlyByteBuf(Unpooled.buffer());
        MaidInfoCodec.writeMaidInfoList(b3, List.of(full, minimal));
        List<MaidInfo> infoList = MaidInfoCodec.readMaidInfoList(b3);
        eq("MaidInfo 列表: 长度", 2, infoList.size());
        eq("MaidInfo 列表: 首元素 entityId", 42, infoList.get(0).entityId());
        eq("MaidInfo 列表: 次元素 modelId", "m:p", infoList.get(1).modelId());

        // 超 256 条：写端截断、读端也读 256 -> 不错位
        List<MaidInfo> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(new MaidInfo(i, "m:p", "d" + i, 0, 1F, 1F, false, null, null, null, false));
        }
        FriendlyByteBuf b4 = new FriendlyByteBuf(Unpooled.buffer());
        MaidInfoCodec.writeMaidInfoList(b4, many);
        List<MaidInfo> manyBack = MaidInfoCodec.readMaidInfoList(b4);
        eq("MaidInfo 列表: 超 256 截断为 256", 256, manyBack.size());
        eq("MaidInfo 列表: 截断后首元素正确", 0, manyBack.get(0).entityId());

        // ==================== BackupOwner / BackupMaid ====================
        IMaidFileNetwork.BackupMaid m1 = new IMaidFileNetwork.BackupMaid(
                "uuid-1", "女仆甲", "m:a", true, List.of("a.dat", "b.dat"));
        IMaidFileNetwork.BackupMaid m2 = new IMaidFileNetwork.BackupMaid(
                "uuid-2", null, null, false, List.of());
        IMaidFileNetwork.BackupOwner o1 = new IMaidFileNetwork.BackupOwner(
                "owner-uuid", "主名", List.of(m1, m2));
        IMaidFileNetwork.BackupOwner o2 = new IMaidFileNetwork.BackupOwner(
                "owner-uuid-2", null, List.of());
        FriendlyByteBuf b5 = new FriendlyByteBuf(Unpooled.buffer());
        MaidBackupCodec.writeBackupOwners(b5, List.of(o1, o2));
        List<IMaidFileNetwork.BackupOwner> owners = MaidBackupCodec.readBackupOwners(b5);
        eq("Backup: owner 数量", 2, owners.size());
        eq("Backup: owner0 uuid", "owner-uuid", owners.get(0).ownerUuid());
        eq("Backup: owner0 名", "主名", owners.get(0).ownerName());
        eq("Backup: owner0 maid 数", 2, owners.get(0).maids().size());
        eq("Backup: maid0 uuid", "uuid-1", owners.get(0).maids().get(0).maidUuid());
        eq("Backup: maid0 名", "女仆甲", owners.get(0).maids().get(0).maidName());
        eq("Backup: maid0 modelId", "m:a", owners.get(0).maids().get(0).modelId());
        eq("Backup: maid0 ysmModel", true, owners.get(0).maids().get(0).ysmModel());
        eq("Backup: maid0 文件数", 2, owners.get(0).maids().get(0).files().size());
        eq("Backup: maid0 文件名", "a.dat", owners.get(0).maids().get(0).files().get(0));
        // 写端把 null 归一为空串；读端把空 ownerName 归一为 null
        eq("Backup: owner1 空名归一为 null", null, owners.get(1).ownerName());
        eq("Backup: maid1 modelId null 归一为空串", "", owners.get(0).maids().get(1).modelId());
        eq("Backup: maid1 files 空", 0, owners.get(0).maids().get(1).files().size());
        eq("Backup: maid1 ysmModel false", false, owners.get(0).maids().get(1).ysmModel());

        // 读端超限拒绝（写端截断、读端自律）
        FriendlyByteBuf tooMany = new FriendlyByteBuf(Unpooled.buffer());
        tooMany.writeVarInt(MaidFilePackets.MAX_BACKUP_OWNERS + 1);
        throwsIAE("Backup: 读端 owner 数超限直接拒绝",
                () -> MaidBackupCodec.readBackupOwners(tooMany));

        FriendlyByteBuf tooManyMaids = new FriendlyByteBuf(Unpooled.buffer());
        tooManyMaids.writeVarInt(1);
        tooManyMaids.writeUtf("u", MaidFilePackets.MAX_TEXT_LEN);
        tooManyMaids.writeUtf("n", MaidFilePackets.MAX_TEXT_LEN);
        tooManyMaids.writeVarInt(MaidFilePackets.MAX_BACKUP_MAIDS + 1);
        throwsIAE("Backup: 读端 maid 数超限直接拒绝",
                () -> MaidBackupCodec.readBackupOwners(tooManyMaids));

        // ==================== MaidProfile（可空 + 非空） ====================
        FriendlyByteBuf b6 = new FriendlyByteBuf(Unpooled.buffer());
        MaidProfileCodec.writeMaidProfile(b6, null);
        eq("MaidProfile: null 往返保持 null", null, MaidProfileCodec.readMaidProfile(b6));

        FriendlyByteBuf b7 = new FriendlyByteBuf(Unpooled.buffer());
        MaidProfileCodec.writeMaidProfile(b7, new MaidProfile());
        check("MaidProfile: 空实例往返非 null", () -> MaidProfileCodec.readMaidProfile(b7) != null);

        // ==================== MaidProfileView ====================
        MaidProfileView view = new MaidProfileView(99, "uuid-x", "主人", "m:p", "显示",
                null, 10F, 20F, 3F, 5, false, null, List.of(), true);
        FriendlyByteBuf b8 = new FriendlyByteBuf(Unpooled.buffer());
        MaidProfileCodec.writeMaidProfileView(b8, view);
        MaidProfileView rv = MaidProfileCodec.readMaidProfileView(b8);
        eq("ProfileView: entityId", 99, rv.entityId());
        eq("ProfileView: maidUuid", "uuid-x", rv.maidUuid());
        eq("ProfileView: ownerName", "主人", rv.ownerName());
        eq("ProfileView: modelId", "m:p", rv.modelId());
        eq("ProfileView: displayName", "显示", rv.displayName());
        eq("ProfileView: customName null 往返", null, rv.customName());
        eq("ProfileView: health", 10F, rv.health());
        eq("ProfileView: attackDamage", 3F, rv.attackDamage());
        eq("ProfileView: favorability", 5, rv.favorability());
        eq("ProfileView: struckByLightning", false, rv.struckByLightning());
        eq("ProfileView: profile null 往返", null, rv.profile());
        eq("ProfileView: 饰品空", 0, rv.baubleItems().size());
        eq("ProfileView: ysmModel", true, rv.ysmModel());
    }

    private static void testEffectCodec() {
        System.out.println("\n-- service.MaidEffectCodec（药水效果跨版本序列化） --");

        // ==================== getEffectList ====================
        ListTag sample = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putString("forge:id", "minecraft:speed");
        sample.add(e);

        CompoundTag r1 = new CompoundTag();
        r1.put("ActiveEffects", sample.copy());
        eq("getEffectList: 读 ActiveEffects（1.20 键名）", 1, MaidEffectCodec.getEffectList(r1).size());

        CompoundTag r2 = new CompoundTag();
        r2.put("active_effects", sample.copy());
        eq("getEffectList: 读 active_effects（1.21 键名）", 1, MaidEffectCodec.getEffectList(r2).size());

        eq("getEffectList: 两个键都没有 -> null", null,
                MaidEffectCodec.getEffectList(new CompoundTag()));

        // ==================== removeEffectList ====================
        CompoundTag r3 = new CompoundTag();
        r3.put("ActiveEffects", sample.copy());
        r3.put("active_effects", sample.copy());
        check("removeEffectList: 返回非 null", () -> MaidEffectCodec.removeEffectList(r3) != null);
        check("removeEffectList: ActiveEffects 已删除", () -> !r3.contains("ActiveEffects"));
        check("removeEffectList: active_effects 已删除", () -> !r3.contains("active_effects"));

        CompoundTag r4 = new CompoundTag();
        eq("removeEffectList: 无效果时返回 null", null, MaidEffectCodec.removeEffectList(r4));

        // ==================== buildNormalizedEffects：1.20 Forge 格式 ====================
        ListTag raw120 = new ListTag();
        CompoundTag s120 = new CompoundTag();
        s120.putString("forge:id", "minecraft:speed");
        s120.putInt("Amplifier", 2);
        s120.putInt("Duration", 600);
        s120.putByte("Ambient", (byte) 1);
        s120.putByte("ShowParticles", (byte) 1);
        s120.putByte("ShowIcon", (byte) 0);
        raw120.add(s120);

        ListTag n1 = MaidEffectCodec.buildNormalizedEffects(raw120);
        eq("buildNormalized: 1.20 格式 -> 1 条", 1, n1.size());
        CompoundTag n1e = n1.getCompound(0);
        eq("buildNormalized: id 取自 forge:id", "minecraft:speed", n1e.getString("id"));
        eq("buildNormalized: amplifier 转小写键", 2, n1e.getInt("amplifier"));
        eq("buildNormalized: duration 转小写键", 600, n1e.getInt("duration"));
        eq("buildNormalized: ambient 转小写键", (byte) 1, n1e.getByte("ambient"));
        eq("buildNormalized: show_particles 转小写键", (byte) 1, n1e.getByte("show_particles"));
        eq("buildNormalized: show_icon 转小写键", (byte) 0, n1e.getByte("show_icon"));
        check("buildNormalized: 不再保留 PascalCase 键",
                () -> !n1e.contains("Amplifier") && !n1e.contains("Duration") && !n1e.contains("Ambient"));

        // ==================== buildNormalizedEffects：1.21 格式 ====================
        ListTag raw121 = new ListTag();
        CompoundTag s121 = new CompoundTag();
        s121.putString("id", "minecraft:regeneration");
        s121.putInt("amplifier", 1);
        s121.putInt("duration", 100);
        s121.putByte("ambient", (byte) 0);
        s121.putByte("show_particles", (byte) 1);
        s121.putByte("show_icon", (byte) 1);
        raw121.add(s121);

        ListTag n2 = MaidEffectCodec.buildNormalizedEffects(raw121);
        eq("buildNormalized: 1.21 格式 -> 1 条", 1, n2.size());
        eq("buildNormalized: 1.21 id 保留", "minecraft:regeneration", n2.getCompound(0).getString("id"));
        eq("buildNormalized: 1.21 amplifier", 1, n2.getCompound(0).getInt("amplifier"));

        // ==================== 边界 ====================
        ListTag noId = new ListTag();
        noId.add(new CompoundTag());
        eq("buildNormalized: 无任何 ID 的条目被跳过",
                0, MaidEffectCodec.buildNormalizedEffects(noId).size());

        eq("buildNormalized: 空列表 -> 空",
                0, MaidEffectCodec.buildNormalizedEffects(new ListTag()).size());

        // 1.20 只有 Amplifier 没 ID：同样跳过（不能凭数字 ID 猜，那是跨版本不稳定的路径）
        ListTag onlyPascal = new ListTag();
        CompoundTag noId2 = new CompoundTag();
        noId2.putInt("Amplifier", 3);
        onlyPascal.add(noId2);
        eq("buildNormalized: 只有 Amplifier 无 ID -> 跳过",
                0, MaidEffectCodec.buildNormalizedEffects(onlyPascal).size());

        // ==================== rebuildEffectFromNormalized：失败路径 ====================
        CompoundTag emptyId = new CompoundTag();
        emptyId.putString("id", "");
        eq("rebuild: 空 id -> null", null, MaidEffectCodec.rebuildEffectFromNormalized(emptyId));

        CompoundTag badId = new CompoundTag();
        badId.putString("id", ":::not-a-valid-id:::");
        eq("rebuild: 非法 id -> null（不抛异常）", null,
                MaidEffectCodec.rebuildEffectFromNormalized(badId));

        CompoundTag unknown = new CompoundTag();
        unknown.putString("id", "somemod:definitely_not_registered_effect");
        eq("rebuild: 未注册效果 -> null（不抛异常）", null,
                MaidEffectCodec.rebuildEffectFromNormalized(unknown));
    }

    private static void testBaubleImportConfig() {
        System.out.println("\n-- config.BaubleImportConfig（整合包饰品配置 + 失败关闭） --");

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("maidfm_bic");

            // ============ 1. 文件不存在 ============
            BaubleImportConfig.load(tmpDir.resolve("nope.json"));
            eq("文件不存在: managed=false", false, BaubleImportConfig.isManaged());
            eq("文件不存在: enforced=false", false, BaubleImportConfig.isWhitelistEnforced());
            eq("文件不存在: whitelist 空", List.of(), BaubleImportConfig.whitelist());

            // ============ 2. 正常配置（非空白名单） ============
            Path ok = tmpDir.resolve("ok.json");
            Files.writeString(ok, "{\"whitelist\":[\"minecraft:stone\",\"钻石\"],"
                    + "\"blacklist\":[\"bad:item\"],"
                    + "\"blocked_namespaces\":[\"EvilMod\"],"
                    + "\"blocked_curios_slots\":[\"Ring\"]}");
            BaubleImportConfig.load(ok);
            eq("正常配置: managed=true", true, BaubleImportConfig.isManaged());
            eq("正常配置: enforced=true（白名单非空）", true, BaubleImportConfig.isWhitelistEnforced());
            eq("正常配置: whitelist 小写归一", List.of("minecraft:stone", "钻石"), BaubleImportConfig.whitelist());
            eq("正常配置: blacklist", List.of("bad:item"), BaubleImportConfig.blacklist());
            eq("正常配置: blockedNamespaces 小写归一", List.of("evilmod"), BaubleImportConfig.blockedNamespaces());
            eq("正常配置: blockedCuriosSlots 小写归一", List.of("ring"), BaubleImportConfig.blockedCuriosSlots());

            // ============ 3. isWhitelistPass（强制状态） ============
            eq("白名单: 命中 ID", true, BaubleImportConfig.isWhitelistPass("minecraft:stone", null));
            eq("白名单: 命中 ID（大小写不敏感）", true,
                    BaubleImportConfig.isWhitelistPass("MINECRAFT:STONE", null));
            eq("白名单: 命中中文显示名", true, BaubleImportConfig.isWhitelistPass("whatever:id", "钻石"));
            eq("白名单: 未命中 -> false", false,
                    BaubleImportConfig.isWhitelistPass("mod:other", "别的东西"));
            eq("白名单: null 输入 -> false", false, BaubleImportConfig.isWhitelistPass(null, null));
            eq("白名单: 空串输入 -> false", false, BaubleImportConfig.isWhitelistPass("", ""));

            // ============ 4. isBlacklisted ============
            eq("黑名单: 命中 ID", true, BaubleImportConfig.isBlacklisted("bad:item", null));
            eq("黑名单: 大小写不敏感", true, BaubleImportConfig.isBlacklisted("BAD:ITEM", null));
            eq("黑名单: 未命中", false, BaubleImportConfig.isBlacklisted("good:item", null));

            // ============ 5. 空白名单 -> enforced=false -> 全放行 ============
            Path emptyWl = tmpDir.resolve("empty_wl.json");
            Files.writeString(emptyWl, "{\"whitelist\":[],\"blacklist\":[]}");
            BaubleImportConfig.load(emptyWl);
            eq("空白名单: managed=true", true, BaubleImportConfig.isManaged());
            eq("空白名单: enforced=false", false, BaubleImportConfig.isWhitelistEnforced());
            eq("空白名单: 任何输入都放行", true, BaubleImportConfig.isWhitelistPass("any:thing", null));

            // ============ 6. 非法 JSON -> 失败关闭 ============
            Path broken = tmpDir.resolve("broken.json");
            Files.writeString(broken, "{ this is not json ");
            BaubleImportConfig.load(broken);
            eq("非法 JSON: managed=true", true, BaubleImportConfig.isManaged());
            eq("非法 JSON: enforced=true（失败关闭）", true, BaubleImportConfig.isWhitelistEnforced());
            eq("非法 JSON: whitelist 清空", List.of(), BaubleImportConfig.whitelist());
            eq("非法 JSON: 拒绝全部饰品", false,
                    BaubleImportConfig.isWhitelistPass("minecraft:stone", null));

            // ============ 7. 字段类型不符 -> 失败关闭 ============
            Path badType = tmpDir.resolve("badtype.json");
            Files.writeString(badType, "{\"whitelist\":\"not-an-array\"}");
            BaubleImportConfig.load(badType);
            eq("字段类型不符: managed=true", true, BaubleImportConfig.isManaged());
            eq("字段类型不符: enforced=true（失败关闭）", true, BaubleImportConfig.isWhitelistEnforced());
            eq("字段类型不符: 拒绝全部饰品", false,
                    BaubleImportConfig.isWhitelistPass("minecraft:stone", null));

            // ============ 8. applyServerSync ============
            BaubleImportConfig.applyServerSync(List.of("a:b"), List.of("c:d"),
                    List.of("EvilMod"), List.of("Ring"), true, true);
            eq("同步后: whitelist", List.of("a:b"), BaubleImportConfig.whitelist());
            eq("同步后: blacklist", List.of("c:d"), BaubleImportConfig.blacklist());
            eq("同步后: blockedNamespaces 归一", List.of("evilmod"), BaubleImportConfig.blockedNamespaces());
            eq("同步后: blockedCuriosSlots 归一", List.of("ring"), BaubleImportConfig.blockedCuriosSlots());
            eq("同步后: 命中白名单放行", true, BaubleImportConfig.isWhitelistPass("a:b", null));
            eq("同步后: 未命中拒绝", false, BaubleImportConfig.isWhitelistPass("x:y", null));

            // 同步 null 清单不应抛异常
            BaubleImportConfig.applyServerSync(null, null, null, null, false, false);
            eq("同步 null 清单: whitelist 空", List.of(), BaubleImportConfig.whitelist());
            eq("同步 null 清单: enforced=false", false, BaubleImportConfig.isWhitelistEnforced());
        } catch (Exception e) {
            check("BaubleImportConfig 用例整体执行", () -> false);
            System.out.println("        异常: " + e);
        } finally {
            // 重置静态状态，避免污染后续用例
            BaubleImportConfig.load(null);
            if (tmpDir != null) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void testClientConfig() {
        System.out.println("\n-- config.ClientConfig（客户端配置持久化） --");

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("maidfm_cc");
            Path file = tmpDir.resolve("client.properties");

            // ============ 1. 首次（文件不存在）-> 默认值 ============
            ClientConfig.init(file);
            eq("默认: allowServerExport=false", false, ClientConfig.isAllowServerExport());
            eq("默认: uiMusic=true", true, ClientConfig.isUiMusic());
            eq("默认: volume=0.15", 0.15F, ClientConfig.getUiMusicVolume());
            eq("默认: skipRemoveConfirm=false", false, ClientConfig.isSkipRemoveConfirm("world1"));

            // ============ 2. 改值并落盘 ============
            ClientConfig.setUiMusic(false);
            ClientConfig.setAllowServerExport(true);
            ClientConfig.setSkipRemoveConfirm("worldA", true);
            ClientConfig.setSkipRemoveConfirm("worldB", true);
            check("落盘后文件存在", () -> Files.isRegularFile(file));

            // ============ 3. 重新加载（模拟重启） ============
            ClientConfig.init(file);
            eq("重启后: uiMusic=false", false, ClientConfig.isUiMusic());
            eq("重启后: allowServerExport=true", true, ClientConfig.isAllowServerExport());
            eq("重启后: worldA 跳过确认", true, ClientConfig.isSkipRemoveConfirm("worldA"));
            eq("重启后: worldB 跳过确认", true, ClientConfig.isSkipRemoveConfirm("worldB"));
            eq("重启后: worldC 从未设置", false, ClientConfig.isSkipRemoveConfirm("worldC"));

            // ============ 4. 取消跳过确认（应从文件移除该键） ============
            ClientConfig.setSkipRemoveConfirm("worldA", false);
            ClientConfig.init(file);
            eq("取消后: worldA=false", false, ClientConfig.isSkipRemoveConfirm("worldA"));
            eq("取消后: worldB 仍为 true", true, ClientConfig.isSkipRemoveConfirm("worldB"));
            check("取消后: 文件里不再有 worldA 的键", () -> {
                try {
                    return !Files.readString(file).contains("skip_remove_confirm.worldA");
                } catch (Exception e) {
                    return false;
                }
            });

            // ============ 5. 音量越界钳制（端到端读回） ============
            Files.writeString(file, "ui_music_volume=5.0\n");
            ClientConfig.init(file);
            eq("音量 5.0 钳到 1.0", 1F, ClientConfig.getUiMusicVolume());

            Files.writeString(file, "ui_music_volume=-1\n");
            ClientConfig.init(file);
            eq("音量 -1 钳到 0", 0F, ClientConfig.getUiMusicVolume());

            Files.writeString(file, "ui_music_volume=abc\n");
            ClientConfig.init(file);
            eq("非法音量回退默认 0.15", 0.15F, ClientConfig.getUiMusicVolume());

            // ============ 6. 非法转义不应抛异常（保留已解析部分） ============
            Files.writeString(file, "ui_music=false\nui_music_volume=\\uZZZZ\n");
            ClientConfig.init(file);
            eq("非法转义: 已解析的 ui_music 保留", false, ClientConfig.isUiMusic());
        } catch (Exception e) {
            check("ClientConfig 用例整体执行", () -> false);
            System.out.println("        异常: " + e);
        } finally {
            // 重置静态状态：指向不存在的路径（load 得到默认值），且 save 不会再写盘
            ClientConfig.init(tmpDir != null ? tmpDir.resolve("reset.properties") : null);
            if (tmpDir != null) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void testServerConfig() {
        System.out.println("\n-- config.ServerConfig（服务端配置持久化与键校验） --");

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("maidfm_sc");
            Path file = tmpDir.resolve("server.properties");

            // ============ 1. 首次（文件不存在）-> 默认值 ============
            ServerConfig.init(file);
            eq("默认: allowClientImport=true", true, ServerConfig.isAllowClientImport());
            eq("默认: allowBaubles=true", true, ServerConfig.isAllowBaubles());
            eq("默认: allowAdvancements=true", true, ServerConfig.isAllowAdvancements());
            eq("默认: allowEffects=true", true, ServerConfig.isAllowEffects());
            eq("默认: allowInvulnerable=false", false, ServerConfig.isAllowInvulnerable());
            eq("默认: baubleStripAttributes=false", false, ServerConfig.isBaubleStripAttributes());
            eq("默认: carryCurios=false", false, ServerConfig.isCarryCurios());
            eq("默认: OP 清单空", List.of(), ServerConfig.opBlockedNamespaces());

            // ============ 2. setByKey 合法键 ============
            eq("setByKey(allow_baubles,false) -> true", true,
                    ServerConfig.setByKey(ServerConfig.KEY_ALLOW_BAUBLES, false));
            eq("setByKey(allow_invulnerable,true) -> true", true,
                    ServerConfig.setByKey(ServerConfig.KEY_ALLOW_INVULNERABLE, true));
            eq("setByKey(carry_curios,true) -> true", true,
                    ServerConfig.setByKey(ServerConfig.KEY_CARRY_CURIOS, true));

            // ============ 3. setByKey 未知键 -> false ============
            eq("setByKey(未知键) -> false", false,
                    ServerConfig.setByKey("no_such_key", true));
            eq("setByKey(null) -> false", false, ServerConfig.setByKey(null, true));

            // ============ 4. setListByKey ============
            eq("setListByKey(namespaces) -> true", true,
                    ServerConfig.setListByKey(ServerConfig.KEY_BAUBLE_BLOCKED_NAMESPACES,
                            List.of("EvilMod", "  AnotherMod  ", "evilmod")));
            eq("清单归一（小写+去重+trim）", List.of("evilmod", "anothermod"),
                    ServerConfig.opBlockedNamespaces());
            eq("setListByKey(未知键) -> false", false,
                    ServerConfig.setListByKey("no_such_list", List.of("x")));

            // ============ 5. 重新 init（模拟重启）-> 全部读回 ============
            ServerConfig.init(file);
            eq("重启后: allowBaubles=false", false, ServerConfig.isAllowBaubles());
            eq("重启后: allowInvulnerable=true", true, ServerConfig.isAllowInvulnerable());
            eq("重启后: carryCurios=true", true, ServerConfig.isCarryCurios());
            eq("重启后: 清单保留", List.of("evilmod", "anothermod"),
                    ServerConfig.opBlockedNamespaces());

            // ============ 6. getByKey ============
            eq("getByKey(allow_baubles) -> false", false,
                    ServerConfig.getByKey(ServerConfig.KEY_ALLOW_BAUBLES));
            eq("getByKey(allow_client_import) -> true", true,
                    ServerConfig.getByKey(ServerConfig.KEY_ALLOW_CLIENT_IMPORT));
            eq("getByKey(null) -> false", false, ServerConfig.getByKey(null));
            eq("getByKey(未知键) -> false", false, ServerConfig.getByKey("nope"));

            // ============ 7. applyServerSync（客户端缓存） ============
            ServerConfig.applyServerSync(false, true, false, true, true, false, true,
                    List.of("SyncMod"), List.of("Ring"), List.of("bad:item"));
            eq("同步后: allowClientImport=false", false, ServerConfig.isAllowClientImport());
            eq("同步后: allowAdvancements=false", false, ServerConfig.isAllowAdvancements());
            eq("同步后: allowInvulnerable=true", true, ServerConfig.isAllowInvulnerable());
            eq("同步后: carryCurios=false", false, ServerConfig.isCarryCurios());
            eq("同步后: 清单归一", List.of("syncmod"), ServerConfig.opBlockedNamespaces());
            eq("同步后: 槽位清单归一", List.of("ring"), ServerConfig.opBlockedCuriosSlots());
            eq("同步后: OP 禁用清单", List.of("bad:item"), ServerConfig.opBlockedList());

            // null 清单容错
            ServerConfig.applyServerSync(true, true, true, true, false, false, false,
                    null, null, null);
            eq("同步 null 清单: 清单清空", List.of(), ServerConfig.opBlockedNamespaces());
            eq("同步 null 清单: 不抛异常且值生效", false, ServerConfig.isAllowInvulnerable());
        } catch (Exception e) {
            check("ServerConfig 用例整体执行", () -> false);
            System.out.println("        异常: " + e);
        } finally {
            // 重置静态状态：指向不存在的路径（load 得默认值，save 不会再写盘）
            ServerConfig.init(tmpDir != null ? tmpDir.resolve("reset.properties") : null);
            if (tmpDir != null) {
                try (var s = Files.walk(tmpDir)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void testTlmDataCodec() {
        System.out.println("\n-- service.TlmDataCodec（TLM 数据抽取规则） --");

        // 构造一份「什么都有」的实体 NBT
        java.util.function.Supplier<CompoundTag> makeFull = () -> {
            CompoundTag t = new CompoundTag();
            // 始终抽取的物品容器
            t.putString("MaidBaubleInventory", "x");
            t.putString("MaidInventory", "x");
            t.putString("MaidHideInventory", "x");
            t.putString("MaidTaskInventory", "x");
            // 仅跨版本抽取的复杂容器
            t.putString("MaidSubConfig", "x");
            t.putString("MaidGameSkillData", "x");
            t.putString("MaidTaskDataMaps", "x");
            t.putString("YsmRoamingVars", "x");
            t.putString("Brain", "x");
            t.putString("MaidSchedulePos", "x");
            // 简单值字段：永远保留在 NBT 里（由 restoreTlmData 单独处理）
            t.putString("ModelId", "m:p");
            t.putInt("MaidFavorability", 5);
            // 无关键
            t.putString("CustomName", "草东");
            return t;
        };

        // ==================== 同版本：只抽 4 个物品容器 ====================
        CompoundTag same = makeFull.get();
        CompoundTag out1 = TlmDataCodec.extract(same, true);
        eq("同版本: 抽出 4 个物品容器", 4, out1.getAllKeys().size());
        eq("同版本: 抽出 MaidBaubleInventory", true, out1.contains("MaidBaubleInventory"));
        eq("同版本: 抽出 MaidInventory", true, out1.contains("MaidInventory"));
        eq("同版本: 抽出 MaidHideInventory", true, out1.contains("MaidHideInventory"));
        eq("同版本: 抽出 MaidTaskInventory", true, out1.contains("MaidTaskInventory"));

        // 关键回归断言：跨版本专用的键必须**留在** NBT 里（历史 bug 就是这里被误抽）
        eq("同版本: MaidSubConfig 保留在 NBT（历史 bug 回归）", true, same.contains("MaidSubConfig"));
        eq("同版本: MaidGameSkillData 保留在 NBT", true, same.contains("MaidGameSkillData"));
        eq("同版本: MaidTaskDataMaps 保留在 NBT", true, same.contains("MaidTaskDataMaps"));
        eq("同版本: YsmRoamingVars 保留在 NBT", true, same.contains("YsmRoamingVars"));
        eq("同版本: Brain 保留在 NBT", true, same.contains("Brain"));
        eq("同版本: MaidSchedulePos 保留在 NBT", true, same.contains("MaidSchedulePos"));

        // 抽出后入参确实被移除
        eq("同版本: 入参已移除 MaidBaubleInventory", false, same.contains("MaidBaubleInventory"));
        eq("同版本: 入参已移除 MaidInventory", false, same.contains("MaidInventory"));

        // 简单值与无关键都还在
        eq("同版本: ModelId 保留", true, same.contains("ModelId"));
        eq("同版本: MaidFavorability 保留", true, same.contains("MaidFavorability"));
        eq("同版本: CustomName 保留", true, same.contains("CustomName"));

        // ==================== 跨版本：两档都抽 ====================
        CompoundTag cross = makeFull.get();
        CompoundTag out2 = TlmDataCodec.extract(cross, false);
        eq("跨版本: 抽出物品容器 + 跨版本键（4+6）", 10, out2.getAllKeys().size());
        eq("跨版本: 抽出 MaidSubConfig", true, out2.contains("MaidSubConfig"));
        eq("跨版本: 抽出 MaidGameSkillData", true, out2.contains("MaidGameSkillData"));
        eq("跨版本: 抽出 YsmRoamingVars", true, out2.contains("YsmRoamingVars"));
        eq("跨版本: 抽出 Brain", true, out2.contains("Brain"));
        eq("跨版本: 入参已移除 MaidSubConfig", false, cross.contains("MaidSubConfig"));

        // 简单值仍在（它们由 restoreTlmData 单独处理，不在抽取清单里）
        eq("跨版本: ModelId 仍保留", true, cross.contains("ModelId"));
        eq("跨版本: MaidFavorability 仍保留", true, cross.contains("MaidFavorability"));

        // ==================== 边界 ====================
        CompoundTag empty = new CompoundTag();
        eq("空 NBT: 抽出为空", 0, TlmDataCodec.extract(empty, false).getAllKeys().size());
        eq("空 NBT: 不抛异常且入参仍为空", 0, empty.getAllKeys().size());

        CompoundTag onlyUnknown = new CompoundTag();
        onlyUnknown.putString("SomethingElse", "x");
        eq("只有无关键: 抽出为空", 0, TlmDataCodec.extract(onlyUnknown, true).getAllKeys().size());
        eq("只有无关键: 入参未变", 1, onlyUnknown.getAllKeys().size());

        // ==================== 26.1+ Transfer API 模式 ====================
        // 26.1 起饰品容器走 Transfer API，同版本+允许携带时必须把 MaidBaubleInventory
        // 留在 NBT 里让 TLM 自己反序列化；抽出来再反射回填在 26.1 会失败导致饰品丢失。
        CompoundTag ta1 = makeFull.get();
        CompoundTag out3 = TlmDataCodec.extract(ta1, true, true, true);
        eq("TransferAPI+同版本+带饰品: 只抽 3 个", 3, out3.getAllKeys().size());
        eq("TransferAPI+同版本+带饰品: 不抽 MaidBaubleInventory", false,
                out3.contains("MaidBaubleInventory"));
        eq("TransferAPI+同版本+带饰品: 饰品容器留在 NBT", true, ta1.contains("MaidBaubleInventory"));
        eq("TransferAPI+同版本+带饰品: 其它物品容器已抽走", false, ta1.contains("MaidInventory"));

        // 不允许携带饰品时，即使走 Transfer API 也要抽走 MaidBaubleInventory
        CompoundTag ta2 = makeFull.get();
        CompoundTag out4 = TlmDataCodec.extract(ta2, true, false, true);
        eq("TransferAPI+同版本+不带饰品: 抽 4 个", 4, out4.getAllKeys().size());
        eq("TransferAPI+同版本+不带饰品: 抽走 MaidBaubleInventory", true,
                out4.contains("MaidBaubleInventory"));

        // 跨版本时无论是否走 Transfer API，饰品容器都要抽（结构可能不兼容）
        CompoundTag ta3 = makeFull.get();
        CompoundTag out5 = TlmDataCodec.extract(ta3, false, true, true);
        eq("TransferAPI+跨版本: 抽走 MaidBaubleInventory", true,
                out5.contains("MaidBaubleInventory"));
        eq("TransferAPI+跨版本: 同时抽跨版本键", true, out5.contains("MaidSubConfig"));

        // baubleUsesTransferApi=false 时，同版本+带饰品仍然是 4 个（20/21 版语义）
        CompoundTag ta4 = makeFull.get();
        eq("非 TransferAPI+同版本+带饰品: 抽 4 个", 4,
                TlmDataCodec.extract(ta4, true, true, false).getAllKeys().size());
        eq("transferApiSafeKeys 返回 3 个", 3, TlmDataCodec.transferApiSafeKeys().length);

        // 键表只读副本：改动返回的数组不应影响内部
        String[] keys = TlmDataCodec.alwaysExtractKeys();
        eq("alwaysExtractKeys 返回 4 个", 4, keys.length);
        keys[0] = "TAMPERED";
        eq("改动副本不影响内部", "MaidBaubleInventory", TlmDataCodec.alwaysExtractKeys()[0]);
        eq("crossVersionOnlyKeys 返回 16 个", 16, TlmDataCodec.crossVersionOnlyKeys().length);
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
