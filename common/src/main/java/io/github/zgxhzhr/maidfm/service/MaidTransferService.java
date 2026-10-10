package io.github.zgxhzhr.maidfm.service;

import io.github.zgxhzhr.maidfm.Constants;
import io.github.zgxhzhr.maidfm.config.MaidConfigManager;
import io.github.zgxhzhr.maidfm.data.ImportResult;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.NbtMigration;
import io.github.zgxhzhr.maidfm.data.NbtVersion;
import io.github.zgxhzhr.maidfm.platform.Services;
import io.github.zgxhzhr.maidfm.spi.MaidMigrationProvider;
import io.github.zgxhzhr.maidfm.spi.MaidMigrationRegistry;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.FavorabilityManager;
import com.github.tartaricacid.touhoulittlemaid.entity.info.ServerCustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 女仆导入导出业务逻辑层。所有方法仅在服务端调用。
 *
 * <p>设计要点：
 * <ul>
 *   <li>导出使用 {@link EntityMaid#saveWithoutId(CompoundTag)} 获取女仆完整 NBT 数据，
 *       导入后是完整 TLM 女仆，卸载本模组后不影响</li>
 *   <li>导出时清空背包/手持物品，但保留背包类型与饰品栏（饰品由导入端白名单恢复）；
 *       强制工作状态为空闲、强制站立</li>
 *   <li>导出落盘到 {@code maid_exports/}，导入源目录为 {@code maid_imports/}</li>
 *   <li>导入时在玩家前方 {@value Constants#IMPORT_SPAWN_DISTANCE} 格寻找安全位置生成</li>
 *   <li>TLM 交互全部使用公开 API；唯一的跨加载器差异（饰品栏容器类型）
 *       经 {@link io.github.zgxhzhr.maidfm.platform.services.IPlatformHelper} 下沉平台层</li>
 * </ul>
 */
public final class MaidTransferService {
    /** GUI 导出列表查询半径（格） */
    private static final double SEARCH_RADIUS = 128.0;
    /** 单次列表返回上限（防止超大数据包；正常玩家远低于此值） */
    private static final int MAID_SEARCH_LIMIT = 256;
    /**
     * 饰品栏扩容硬上限。槽位号来自网络 NBT 完全不可信，必须封顶，
     * 防止伪造 Slot=数千万 触发 ItemStackHandler.setSize 分配巨型数组（OOM 与存档永久膨胀）。
     * TLM 默认饰品栏 9 槽，256 足以容纳任何合理扩展而内存开销可忽略。
     */
    private static final int MAX_BAUBLE_SLOTS = 256;
    private static final int SPAWN_SAFE_MAX_UP = 8;
    /**
     * 女仆入世界后血量校准的重试窗口（tick）。
     * 饰品/词条类附属的 AttributeModifier 在实体入世界后的若干 tick 内陆续附加，时刻不固定；
     * 固定延迟一次校准无法保证晚于所有附属。改为在此窗口内每 tick 按当前最大血量补齐，
     * 无论 modifier 何时附加都能把满血女仆的血量追上来。20 tick ≈ 1 秒。
     */
    private static final int POST_IMPORT_HEAL_RETRY_TICKS = 20;
    /**
     * 万法皆通特殊女仆身上的永久常驻药水效果固定为这 11 种 ResourceLocation
     * （来自万法皆通结构模板 .nbt 预置，duration=-1）。
     * 判定依据：duration=-1 AND 效果 ID 命中此白名单。不依赖女仆实体身份，
     * 即普通女仆被施加白名单内的无限时长效果时同样适用此保护。
     */
    private static final java.util.Set<String> SPELL_PERMANENT_EFFECT_IDS = java.util.Set.of(
            "minecraft:regeneration", "minecraft:strength", "minecraft:resistance", "minecraft:speed",
            "irons_spellbooks:vigor", "irons_spellbooks:blight",
            "irons_spellbooks:true_invisibility", "irons_spellbooks:abyssal_shroud",
            "goety:save_effects", "goety:leeching",
            "youkaishomecoming:native_god_bless"
    );

    private MaidTransferService() {
    }

    // ============================ 导出 ============================

    public static List<MaidInfo> listOwnMaids(ServerPlayer player) {
        Level level = player.level();
        Vec3 pos = player.position();
        AABB box = AABB.ofSize(pos, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
        List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class, box, m -> m.isOwnedBy(player));
        List<MaidInfo> result = new ArrayList<>();
        for (EntityMaid maid : maids) {
            if (result.size() >= MAID_SEARCH_LIMIT) {
                Constants.LOG.warn("[maid_file_manager] 女仆数量超过列表上限 {}，仅返回前 {} 个",
                        MAID_SEARCH_LIMIT, MAID_SEARCH_LIMIT);
                break;
            }
            result.add(toInfo(maid));
        }
        return result;
    }

    private static MaidInfo toInfo(EntityMaid maid) {
        UUID ownerUuid = maid.getOwnerUUID();
        String ownerName = null;
        LivingEntity owner = maid.getOwner();
        if (owner != null) {
            ownerName = owner.getName().getString();
        }
        String customName = maid.hasCustomName() ? maid.getCustomName().getString() : null;
        AttributeInstance maxHealthAttr = maid.getAttribute(Attributes.MAX_HEALTH);
        float maxHealth = maxHealthAttr == null ? maid.getMaxHealth() : (float) maxHealthAttr.getValue();
        return new MaidInfo(
                maid.getId(),
                maid.getModelId(),
                getDisplayName(maid.getModelId()),
                maid.getFavorability(),
                maid.getHealth(),
                maxHealth,
                maid.isTame(),
                ownerUuid,
                ownerName,
                customName
        );
    }

    public static String getDisplayName(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "未知模型";
        }
        try {
            Optional<?> infoOpt = ServerCustomPackLoader.SERVER_MAID_MODELS.getInfo(modelId);
            if (infoOpt.isPresent()) {
                Object info = infoOpt.get();
                // IModelInfo 位于 client 包，专用服务端环境可能被裁剪，用反射只读 getName，找不到则回退
                try {
                    java.lang.reflect.Method m = info.getClass().getMethod("getName");
                    Object name = m.invoke(info);
                    if (name instanceof String s && !s.isEmpty()) {
                        if (s.startsWith("{") && s.endsWith("}")) {
                            String key = s.substring(1, s.length() - 1);
                            try {
                                String result = Component.translatable(key).getString();
                                if (result != null && !result.equals(key) && !result.isEmpty()) {
                                    return result;
                                }
                            } catch (Exception ignored) {
                            }
                            return fallbackNameFromModelId(modelId);
                        }
                        return s;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] getDisplayName 失败 modelId={}: {}", modelId, t.toString());
        }
        return fallbackNameFromModelId(modelId);
    }

    private static String fallbackNameFromModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "unknown_model";
        }
        int colon = modelId.indexOf(':');
        return colon >= 0 ? modelId.substring(colon + 1) : modelId;
    }

    public static MaidFileData exportMaidToData(ServerPlayer player, int entityId) {
        Entity entity = player.level().getEntity(entityId);
        if (!(entity instanceof EntityMaid maid)) {
            Constants.LOG.warn("[maid_file_manager] exportMaidToData: entityId={} 不是 EntityMaid", entityId);
            return null;
        }
        if (!maid.isOwnedBy(player)) {
            Constants.LOG.warn("[maid_file_manager] exportMaidToData: 女仆不属于该玩家");
            return null;
        }
        return doSerializeMaid(maid);
    }

    /**
     * 统一导出（OP 代导）专用序列化入口。
     * ownership 校验改为 ownerUUID 精确匹配，并使用「直接取 → 128 格搜索 → 全维度搜索」三级查找，
     * 只负责序列化，永不移除世界实体。
     */
    public static MaidFileData exportMaidOwnedBy(ServerPlayer expectedOwner, int entityId) {
        if (expectedOwner == null) {
            return null;
        }
        UUID ownerUuid = expectedOwner.getUUID();
        Level level = expectedOwner.level();
        Entity direct = level.getEntity(entityId);
        if (direct instanceof EntityMaid m && m.isTame() && ownerUuid.equals(m.getOwnerUUID())) {
            return doSerializeMaid(m);
        }
        Vec3 pos = expectedOwner.position();
        AABB near = AABB.ofSize(pos, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
        List<EntityMaid> nearMaids = level.getEntitiesOfClass(EntityMaid.class, near,
                m -> m.isTame() && ownerUuid.equals(m.getOwnerUUID()) && m.getId() == entityId);
        if (!nearMaids.isEmpty()) {
            return doSerializeMaid(nearMaids.get(0));
        }
        // 最后兜底：按实体 ID 在本维度世界边界范围内全量扫描。代价较高（分区实体逐个过谓词），
        // 但谓词含 m.getId() == entityId，且前两级查找已覆盖 99% 场景，仅在玩家与女仆极端远离时才走到
        for (EntityMaid m : level.getEntitiesOfClass(EntityMaid.class,
                new AABB(-30000000, -30000000, -30000000, 30000000, 30000000, 30000000),
                m -> m.isTame() && ownerUuid.equals(m.getOwnerUUID()) && m.getId() == entityId)) {
            return doSerializeMaid(m);
        }
        Constants.LOG.warn("[maid_file_manager] exportMaidOwnedBy: 未找到 entityId={} owner={}",
                entityId, expectedOwner.getName().getString());
        return null;
    }

    /**
     * 序列化核心：清空物品/残留 buff，保留饰品栏供导入端按配置恢复。
     * 不做 ownership 校验、不移除实体。
     */
    private static MaidFileData doSerializeMaid(EntityMaid maid) {
        try {
            String modelId = maid.getModelId();
            CompoundTag fullNbt = maid.saveWithoutId(new CompoundTag());
            clearInventoryItems(fullNbt, EntityMaid.MAID_INVENTORY_TAG);
            clearInventoryItems(fullNbt, EntityMaid.MAID_HIDE_INVENTORY_TAG);
            clearInventoryItems(fullNbt, EntityMaid.MAID_TASK_INVENTORY_TAG);
            // 手持/护甲直接移除容器标签（导出策略本就不带手持/护甲物品）
            fullNbt.remove("HandItems");
            fullNbt.remove("ArmorItems");
            fullNbt.remove("HandDropChances");
            fullNbt.remove("ArmorDropChances");
            fullNbt.remove("MaidBackpackData");
            // 药水效果：提取到 MaidFileData.effects 字段，始终序列化（不受配置影响）
            // 同时生成 normalized 标准化数据用于跨版本兼容。
            // 键名双兼容：1.20.x 为 "ActiveEffects"，1.21 起 LivingEntity 改存 "active_effects"，
            // 两个键都必须识别并从实体 NBT 移除（恢复统一由导入端按配置执行，避免 load 绕过配置）。
            CompoundTag effectsTag = null;
            ListTag rawEffectList = removeEffectList(fullNbt);
            if (rawEffectList != null) {
                effectsTag = new CompoundTag();
                // 原始 NBT 副本：同版本直读（MobEffectInstance.load）
                effectsTag.put("active_effects", rawEffectList.copy());
                // 标准化数据：跨版本重建 MobEffectInstance（按 ResourceLocation 查注册表）
                effectsTag.put("normalized", buildNormalizedEffects(rawEffectList));
            }
            // 检查实体持久化标签中是否有存储的效果（禁药水服务器再导出的场景）
            CompoundTag storedEffects = Services.PLATFORM.get().getStoredEffects(maid);
            if (storedEffects != null) {
                effectsTag = storedEffects;
            }
            fullNbt.putString("MaidTask", TaskManager.getIdleTask().getUid().toString());
            fullNbt.putByte("Sitting", (byte) 0);
            String ownerUuid = null;
            String ownerName = null;
            boolean tamed = maid.isTame();
            if (tamed && maid.getOwnerUUID() != null) {
                ownerUuid = maid.getOwnerUUID().toString();
            }
            LivingEntity owner = maid.getOwner();
            if (owner != null) {
                ownerName = owner.getName().getString();
            }
            MaidFileData data = new MaidFileData();
            data.setExportedAt(System.currentTimeMillis());
            data.setSourceMcVersion(Services.PLATFORM.get().getMcVersion());
            data.setDataVersion(NbtVersion.currentRuntime());
            data.setSourceTlmVersion(Services.PLATFORM.get().getModVersion("touhou_little_maid"));
            data.setTamed(tamed);
            data.setOwnerUuid(ownerUuid);
            // 源女仆实体 UUID 仅用于导入前查重；导入实体仍会被强制分配新 UUID（NbtMigration 恒删 UUID 键）
            data.setSourceMaidUuid(maid.getUUID().toString());
            data.setOwnerName(ownerName);
            data.setData(fullNbt);
            data.setModelId(modelId);
            data.setDisplayName(getDisplayName(modelId));
            // 自定义命名（命名牌所取），用于文件名拼接与列表显示
            if (maid.hasCustomName()) {
                String cn = maid.getCustomName().getString();
                if (cn != null && !cn.isEmpty()) {
                    data.setCustomName(cn);
                }
            }
            // 成就收集：服务端开启允许成就导入时，收集原主人 TLM 成就并按女仆属性过滤
            if (MaidConfigManager.isAdvancementsAllowed() && tamed && owner instanceof ServerPlayer serverPlayer) {
                try {
                    CompoundTag advData = AdvancementTransfer.collectForMaid(serverPlayer, maid);
                    if (!advData.isEmpty()) {
                        data.setAdvancements(advData);
                    }
                } catch (Throwable t) {
                    Constants.LOG.warn("[maid_file_manager] 成就收集失败（已忽略，不阻断导出）: {}", t.toString());
                }
            }
            // 药水效果
            if (effectsTag != null) {
                data.setEffects(effectsTag);
            }
            // 附属模组扩展数据：遍历已注册且可用的 provider
            CompoundTag extras = new CompoundTag();
            for (MaidMigrationProvider p : MaidMigrationRegistry.getAvailable()) {
                try {
                    CompoundTag tag = p.export(maid);
                    if (tag != null && !tag.isEmpty()) {
                        extras.put(p.getId().toString(), tag);
                    }
                } catch (Throwable t) {
                    Constants.LOG.warn("[maid_file_manager] provider {} 导出失败（已跳过）: {}",
                            p.getId(), t.toString());
                }
            }
            if (!extras.isEmpty()) {
                data.setExtras(extras);
            }
            // v7：导出女仆档案（照片/职业/个人资料/偏好/背景故事快照）。空档案不写入，保持文件精简。
            try {
                io.github.zgxhzhr.maidfm.data.MaidProfile profile = MaidProfileService.readFromMaid(maid);
                if (profile != null && !profile.isEmpty()) {
                    data.setProfile(profile);
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 档案导出失败（已忽略，不阻断导出）: {}", t.toString());
            }
            Constants.LOG.debug("[maid_file_manager] 序列化成功 modelId={} owner={}", modelId, ownerName);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] 序列化失败 maidId={}", maid.getId(), e);
            return null;
        }
    }

    private static void clearInventoryItems(CompoundTag root, String key) {
        if (!root.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        root.getCompound(key).put("Items", new ListTag());
    }

    /**
     * 效果列表在不同 MC 版本的实体 NBT 键名：
     * 1.20.x 为 PascalCase 的 "ActiveEffects"，1.21 起改为小写驼峰 "active_effects"。
     */
    private static final String[] EFFECT_LIST_KEYS = {"ActiveEffects", "active_effects"};

    /** 读取实体 NBT 中的效果列表（双键名兼容），不存在返回 null */
    private static ListTag getEffectList(CompoundTag root) {
        for (String key : EFFECT_LIST_KEYS) {
            if (root.contains(key, Tag.TAG_LIST)) {
                return root.getList(key, Tag.TAG_COMPOUND);
            }
        }
        return null;
    }

    /** 读取并从实体 NBT 中移除效果列表（双键名均清除），不存在返回 null */
    private static ListTag removeEffectList(CompoundTag root) {
        ListTag list = getEffectList(root);
        if (list != null) {
            for (String key : EFFECT_LIST_KEYS) {
                root.remove(key);
            }
        }
        return list;
    }

    /**
     * 从效果数据中过滤出万法皆通常驻效果：duration=-1 AND 效果 ID 命中白名单。
     * 用于禁药水服务器只保留万法皆通特殊女仆的常驻 buff，其他效果（含普通女仆被施加的非常驻效果、
     * 万法皆通结构女仆身上的短时情境效果如 nourishment/sober/caffeinated、超长时长效果如虚弱 V）
     * 一律丢弃。同步过滤 active_effects 与 normalized 两个列表并保持索引对齐
     * （导入恢复时按相同索引配对原始 NBT 与标准化数据）。
     *
     * @return 仅含白名单常驻效果的新 CompoundTag；无任何白名单常驻效果时返回 null
     */
    private static CompoundTag extractSpellPermanentEffects(CompoundTag effectsData) {
        if (effectsData == null || !effectsData.contains("active_effects", Tag.TAG_LIST)) {
            return null;
        }
        ListTag raw = effectsData.getList("active_effects", Tag.TAG_COMPOUND);
        ListTag norm = effectsData.contains("normalized", Tag.TAG_LIST)
                ? effectsData.getList("normalized", Tag.TAG_COMPOUND) : new ListTag();
        ListTag outRaw = new ListTag();
        ListTag outNorm = new ListTag();
        for (int i = 0; i < raw.size(); i++) {
            CompoundTag e = raw.getCompound(i);
            int dur;
            if (e.contains("Duration", Tag.TAG_INT)) {
                dur = e.getInt("Duration");
            } else if (i < norm.size()) {
                dur = norm.getCompound(i).getInt("duration");
            } else {
                dur = 0;
            }
            if (dur != -1) {
                continue;
            }
            // 效果 ID 命中白名单才保留（不依赖女仆身份判定）
            String effectId = e.contains("forge:id", Tag.TAG_STRING)
                    ? e.getString("forge:id")
                    : e.getString("id");
            if (effectId.isEmpty() && i < norm.size()) {
                effectId = norm.getCompound(i).getString("id");
            }
            if (effectId.isEmpty() || !SPELL_PERMANENT_EFFECT_IDS.contains(effectId)) {
                continue;
            }
            outRaw.add(e.copy());
            if (i < norm.size()) {
                outNorm.add(norm.getCompound(i).copy());
            }
        }
        if (outRaw.isEmpty()) {
            return null;
        }
        CompoundTag out = new CompoundTag();
        out.put("active_effects", outRaw);
        out.put("normalized", outNorm);
        return out;
    }

    /**
     * 构建标准化效果数据列表，用于跨 MC 版本重建 MobEffectInstance。
     * <p>跨版本差异点（1.20.x ↔ 1.21+）：
     * <ul>
     *   <li>1.20.x save：{@code Id(int)}、{@code Amplifier}、{@code Duration}、{@code Ambient}、
     *       {@code ShowParticles}、{@code ShowIcon}、Forge 额外写 {@code forge:id(string)}</li>
     *   <li>1.21+ save：{@code id(string)}、{@code amplifier}、{@code duration}、{@code ambient}、
     *       {@code show_particles}、{@code show_icon}（小写驼峰）</li>
     *   <li>跨版本直读 {@link net.minecraft.world.effect.MobEffectInstance#load} 会失败：
     *       1.20 不识别 1.21 的 id 字符串；1.21 的 byId 兜底依赖注册表数字 ID，跨版本不稳定</li>
     * </ul>
     * <p>normalized 统一存小写驼峰键名，导入端用 ResourceLocation 查 BuiltInRegistries.MOB_EFFECT 重建，
     * 不依赖任何版本特定的字段名或数字 ID。
     */
    private static ListTag buildNormalizedEffects(ListTag rawList) {
        ListTag out = new ListTag();
        for (int i = 0; i < rawList.size(); i++) {
            CompoundTag src = rawList.getCompound(i);
            CompoundTag item = new CompoundTag();
            // 解析效果 ResourceLocation 字符串
            //   1.20.x: 优先读 forge:id（Forge 写入），其次用 Id 数字 ID 反查
            //   1.21+:  直接读 id 字符串
            String effectId = "";
            if (src.contains("forge:id", Tag.TAG_STRING)) {
                effectId = src.getString("forge:id");
            } else if (src.contains("id", Tag.TAG_STRING)) {
                effectId = src.getString("id");
            }
            if (effectId.isEmpty() && src.contains("Id", Tag.TAG_ANY_NUMERIC)) {
                // 兜底：同版本导出时，数字 ID 反查 ResourceLocation
                int numericId = src.getInt("Id");
                net.minecraft.world.effect.MobEffect effect =
                        net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.byId(numericId);
                if (effect != null) {
                    net.minecraft.resources.ResourceLocation rl =
                            net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect);
                    if (rl != null) {
                        effectId = rl.toString();
                    }
                }
            }
            if (effectId.isEmpty()) {
                Constants.LOG.warn("[maid_file_manager] 药水效果 #{} 无法解析 ResourceLocation，已跳过", i);
                continue;
            }
            item.putString("id", effectId);
            // PascalCase 与小写驼峰双兼容读取（1.20 用 PascalCase，1.21 用小写驼峰）
            int amplifier = src.contains("Amplifier", Tag.TAG_INT)
                    ? src.getInt("Amplifier")
                    : src.getInt("amplifier");
            int duration = src.contains("Duration", Tag.TAG_INT)
                    ? src.getInt("Duration")
                    : src.getInt("duration");
            byte ambient = src.contains("Ambient", Tag.TAG_BYTE)
                    ? src.getByte("Ambient")
                    : src.getByte("ambient");
            byte showParticles = src.contains("ShowParticles", Tag.TAG_BYTE)
                    ? src.getByte("ShowParticles")
                    : src.getByte("show_particles");
            byte showIcon = src.contains("ShowIcon", Tag.TAG_BYTE)
                    ? src.getByte("ShowIcon")
                    : src.getByte("show_icon");
            item.putInt("amplifier", amplifier);
            item.putInt("duration", duration);
            item.putByte("ambient", ambient);
            item.putByte("show_particles", showParticles);
            item.putByte("show_icon", showIcon);
            out.add(item);
        }
        return out;
    }

    /**
     * 从 normalized 标准化数据重建 MobEffectInstance（跨版本恢复路径）。
     * 通过 ResourceLocation 查 BuiltInRegistries.MOB_EFFECT，避免数字 ID 跨版本漂移。
     * <p>1.21+: MobEffectInstance 构造器接受 {@code Holder<MobEffect>}。
     */
    private static net.minecraft.world.effect.MobEffectInstance rebuildEffectFromNormalized(CompoundTag item) {
        try {
            String idStr = item.getString("id");
            if (idStr.isEmpty()) return null;
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.tryParse(idStr);
            if (rl == null) return null;
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect =
                    net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(rl).orElse(null);
            if (effect == null) {
                Constants.LOG.warn("[maid_file_manager] 跨版本恢复：目标世界未注册效果 {}，已跳过", idStr);
                return null;
            }
            int duration = item.getInt("duration");
            int amplifier = item.getInt("amplifier");
            boolean ambient = item.getByte("ambient") != 0;
            boolean showParticles = item.getByte("show_particles") != 0;
            boolean showIcon = item.getByte("show_icon") != 0;
            return new net.minecraft.world.effect.MobEffectInstance(
                    effect, duration, amplifier, ambient, showParticles, showIcon);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] rebuildEffectFromNormalized 失败: {}", t.toString());
            return null;
        }
    }

    // ============================ 导入 ============================

    public static ImportResult importMaidFromData(ServerPlayer player, MaidFileData data, boolean keepBaubles,
                                                  boolean stripAttributes, List<String> blockedList) {
        if (data == null || data.getData() == null) {
            return ImportResult.failed(
                    Component.translatable("maid_file_manager.import.fail.invalid"));
        }
        // 闸门 1：服务端是否允许客户端导入
        if (!MaidConfigManager.isClientImportAllowed()) {
            Constants.LOG.info("[maid_file_manager] 导入被拒绝: allow_client_import=false, player={}",
                    player.getName().getString());
            return ImportResult.disallowed(
                    Component.translatable("maid_file_manager.import.fail.server_disallowed"));
        }
        // 闸门 2：饰品双开关
        boolean baublesRequested = keepBaubles;
        if (baublesRequested && !MaidConfigManager.isBaublesAllowed()) {
            keepBaubles = false;
        }

        Level level = player.level();
        // 快照必须在迁移之前从原始 NBT 读取（迁移会删除运行时标签）
        CompoundTag originalNbt = data.getData();
        int sourceFavorability = readSourceFavorability(originalNbt);
        boolean sourceStruckByLightning = originalNbt.contains("StruckByLightning", Tag.TAG_BYTE)
                && originalNbt.getBoolean("StruckByLightning");
        float sourceHealth = originalNbt.contains("Health", Tag.TAG_FLOAT)
                ? originalNbt.getFloat("Health") : -1f;
        // TLM 本体无敌（替身地藏赋予，TLM 自存 "Invulnerable" 键）：仅作快照，
        // 迁移器会删除该键，是否恢复由服务端 allow_invulnerable 配置决定
        boolean sourceInvulnerable = originalNbt.contains("Invulnerable", Tag.TAG_BYTE)
                && originalNbt.getBoolean("Invulnerable");

        // 导入实体 UUID 确定性派生（不在应用层做重复拦截）：
        //   同一玩家对同一源女仆文件的任意次导入，目标实体 UUID 恒定（见 mapImportUuid）；
        //   不同玩家（多人服分享同一文件）因玩家 UUID 参与哈希而得到不同 UUID，互不冲突。
        // 是否允许同一 UUID 的女仆入世界，完全交给服务端原生规则：同 UUID 实体已在已加载区块时
        // addFreshEntity 返回 false，走通用的"添加到世界失败"提示；原女仆已死亡 / 已移除
        // （导出时不保留）/ 区块未加载 / 收入魂符时均不拦截，导入正常进行。
        // 无法解析源 UUID 的极早期文件退回 new EntityMaid 的随机 UUID，不影响导入。
        UUID sourceMaidUuid = resolveSourceMaidUuid(data, originalNbt);
        UUID mappedEntityUuid = sourceMaidUuid != null
                ? mapImportUuid(sourceMaidUuid, player.getUUID()) : null;

        // 双胞胎拦截（针对"保留原女仆导出、再把同一文件导回同一世界"）：
        // 源 UUID 女仆在本世界仍存活（已加载实体；或虽不在已加载区块/已收入魂符但 MaidWorldData
        // 仍有存活登记）时直接拒绝。导出时勾选移除会在 discard 前显式清除存活登记，
        // 因此"导出并移除后再导入"与跨存档导入都不会被误伤。
        if (sourceMaidUuid != null && isSourceMaidAlive(player, sourceMaidUuid, data.getOwnerUuid())) {
            String maidLabel = data.getCustomName() != null ? data.getCustomName()
                    : data.getDisplayName() != null ? data.getDisplayName() : "?";
            Constants.LOG.info("[maid_file_manager] 导入被双胞胎拦截: 源女仆 {} 仍在本世界存活 player={}",
                    sourceMaidUuid, player.getName().getString());
            return ImportResult.failed(Component.translatable(
                    "maid_file_manager.import.fail.source_alive", maidLabel));
        }

        EntityMaid maid = new EntityMaid(level);
        // 覆盖为确定性目标 UUID。必须在 load 之前设置：后续 NBT 加载/饰品恢复等全部逻辑据此
        // UUID 运行；migrated NBT 已由 NbtMigration 删除 UUID 键，load 不会反向覆盖。
        if (mappedEntityUuid != null) {
            maid.setUUID(mappedEntityUuid);
        }
        // 饰品逐件处理说明（禁止携带/还原全新/无法解析等），透传到 ImportResult 反馈给客户端。
        // 声明在 try 之外，导入成功后仍可在返回前附加上备注。
        List<Component> baubleNotes = new ArrayList<>();
        try {
            int sourceVersion = data.getDataVersion() > 0
                    ? data.getDataVersion()
                    : NbtVersion.fromMcVersion(data.getSourceMcVersion());
            int targetVersion = NbtVersion.currentRuntime();
            // 仅当来源/目标版本都明确且相同时，才允许按原始 NBT 完整恢复饰品（附魔/组件）；
            // 版本未知或跨版本时，若源 Mojang DataVersion 明确且低于当前版本，经 DFU 升级后恢复；
            // 其余一律全新化（1.20 tag 结构与 1.21 components 结构互不兼容）
            boolean sameVersion = sourceVersion != NbtVersion.UNKNOWN
                    && targetVersion != NbtVersion.UNKNOWN
                    && sourceVersion == targetVersion;
            int sourceMojangDataVersion = NbtVersion.mojangDataVersion(sourceVersion);
            // migrate 返回清理后的副本，不修改原始 data
            CompoundTag migrated = NbtMigration.migrate(originalNbt, sourceVersion, targetVersion);
            CompoundTag tlmTag = extractTlmData(migrated);
            loadMaidNbt(maid, migrated, tlmTag, keepBaubles, sourceStruckByLightning, sameVersion,
                    stripAttributes, blockedList, sourceMojangDataVersion, baubleNotes);
        } catch (Exception e) {
            // 异常原文可能含内部类名/NBT 结构信息，只进日志；回执给通用文案，不把内部信息透传给客户端
            Constants.LOG.error("[maid_file_manager] 加载女仆 NBT 失败", e);
            return ImportResult.failed(
                    Component.translatable("maid_file_manager.import.fail.nbt_load"));
        }

        // 好感度保护网：当前为 0 而源数据 >0 时，用公开 API 强制恢复
        try {
            if (maid.getFavorability() == 0 && sourceFavorability > 0) {
                Constants.LOG.warn("[maid_file_manager] 好感度加载后为 0，按源数据恢复为 {}", sourceFavorability);
                maid.setFavorability(sourceFavorability);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 好感度保护网失败: {}", t.toString());
        }
        // 渡劫标记最终同步（必须在 rebuildAttributes 之前）
        maid.setStruckByLightning(sourceStruckByLightning);
        // TLM 本体无敌（替身地藏）：迁移器已删除 Invulnerable 键，按服务端配置决定是否恢复
        try {
            maid.setEntityInvulnerable(sourceInvulnerable && MaidConfigManager.isInvulnerableAllowed());
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 无敌状态恢复失败: {}", t.toString());
        }

        rebuildAttributesAndModel(maid, data, sourceStruckByLightning);
        // 入世界前的临时校准：此刻饰品/词条 modifier 尚未附加，maxHealth 只是白板口径，
        // 这里仅防止实体以越界血量加入世界；最终校准延迟到入世界后（见 schedulePostImportHealthSync）
        float preSpawnMaxHealth = maid.getMaxHealth();
        maid.setHealth(sourceHealth > 0 && sourceHealth <= preSpawnMaxHealth
                ? sourceHealth : preSpawnMaxHealth);

        try {
            maid.removeAllEffects();
            // 药水效果恢复逻辑：始终从 .maid 文件读取，按配置决定是否恢复到实体
            CompoundTag effectsData = data.getEffects();
            if (effectsData == null) {
                // 旧版本导出的文件可能没有顶层 effects 字段（1.21 初期版本漏识别小写键
                // "active_effects" 导致效果未提取）：效果仍留在原始实体 NBT 中，迁移前按
                // 双键名兜底提取。仅含原始列表（同版本直读可靠，无 normalized 跨版本路径）；
                // 跨版本恢复失败会被下方双路径逻辑跳过并告警，不会崩溃
                ListTag legacyEffects = getEffectList(originalNbt);
                if (legacyEffects != null) {
                    CompoundTag fallback = new CompoundTag();
                    fallback.put("active_effects", legacyEffects.copy());
                    effectsData = fallback;
                }
            }
            if (effectsData != null && effectsData.contains("active_effects", Tag.TAG_LIST)) {
                if (MaidConfigManager.isEffectsAllowed()) {
                    // 配置允许：恢复药水效果到实体
                    // 双路径恢复策略：
                    //   1. 先尝试原始 NBT 直读（MobEffectInstance.load）—— 同版本路径
                    //   2. 失败时（跨版本格式不匹配）从 normalized 重建—— 通过 ResourceLocation 查注册表
                    ListTag effectList = effectsData.getList("active_effects", Tag.TAG_COMPOUND);
                    ListTag normalized = effectsData.contains("normalized", Tag.TAG_LIST)
                            ? effectsData.getList("normalized", Tag.TAG_COMPOUND) : null;
                    int restored = 0;
                    int rebuildCount = 0;
                    for (int i = 0; i < effectList.size(); i++) {
                        CompoundTag effectNbt = effectList.getCompound(i);
                        net.minecraft.world.effect.MobEffectInstance instance = null;
                        try {
                            instance = net.minecraft.world.effect.MobEffectInstance.load(effectNbt);
                        } catch (Throwable t) {
                            // 同版本直读异常（跨版本字段名/格式不匹配），下面走 normalized 重建
                        }
                        if (instance == null && normalized != null && i < normalized.size()) {
                            instance = rebuildEffectFromNormalized(normalized.getCompound(i));
                            if (instance != null) {
                                rebuildCount++;
                            }
                        }
                        if (instance != null) {
                            maid.addEffect(instance);
                            restored++;
                        } else {
                            Constants.LOG.warn("[maid_file_manager] 药水效果 #{} 恢复失败（已跳过）", i);
                        }
                    }
                    Constants.LOG.debug("[maid_file_manager] 药水效果恢复: 成功 {} 个，其中跨版本重建 {} 个",
                            restored, rebuildCount);
                } else {
                    // 配置不允许药水效果：仅万法皆通特殊女仆的常驻效果（duration=-1 AND ID 命中白名单）
                    // 写入持久化标签保留，防止禁药水服务器再导出时常驻 buff 永久丢失；
                    // 普通女仆效果、特殊女仆的临时效果、超长时长效果（非 -1）一律丢弃
                    CompoundTag permanentOnly = extractSpellPermanentEffects(effectsData);
                    if (permanentOnly != null) {
                        Services.PLATFORM.get().storeEffects(maid, permanentOnly);
                        Constants.LOG.debug("[maid_file_manager] 禁药水：已保留万法皆通常驻效果到持久化标签");
                    }
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 药水效果处理失败: {}", t.toString());
        }
        maid.hurtTime = 0;
        maid.deathTime = 0;
        maid.clearFire();
        maid.setTicksFrozen(0);
        ensureSchedulePosNonNull(maid);

        Vec3 forward = player.getLookAngle().scale(Constants.IMPORT_SPAWN_DISTANCE);
        Vec3 basePos = player.position().add(forward);
        BlockPos safePos = findSafeSpawnPos(level,
                new BlockPos((int) basePos.x, (int) basePos.y, (int) basePos.z));
        if (safePos == null) {
            safePos = player.blockPosition().above();
        }
        maid.setPos(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        maid.setTask(TaskManager.getIdleTask());
        maid.setInSittingPose(false);
        maid.setOrderedToSit(false);
        boolean ownerMatched = matchOwner(maid, player, data);
        maid.setPersistenceRequired();
        if (!level.addFreshEntity(maid)) {
            // 失败原因细分：确定性目标 UUID 在已加载世界中已有女仆实体（最常见为同一文件重复导入
            // 且原女仆/前次副本就在身边）时，给出明确的重复提示；其他原因走通用添加失败文案。
            // 仅查已加载实体：未加载区块、魂符中的实体不会导致 addFreshEntity 失败，那些场景本就放行。
            if (mappedEntityUuid != null && isLoadedMaidWithUuid(player.getServer(), mappedEntityUuid)) {
                String maidLabel = data.getCustomName() != null ? data.getCustomName()
                        : data.getDisplayName() != null ? data.getDisplayName() : "?";
                Constants.LOG.info("[maid_file_manager] addFreshEntity 因同 UUID 女仆已存在被拒绝 uuid={} player={}",
                        mappedEntityUuid, player.getName().getString());
                return ImportResult.failed(Component.translatable(
                        "maid_file_manager.import.fail.duplicate", maidLabel));
            }
            Constants.LOG.error("[maid_file_manager] addFreshEntity 被拒绝 pos={}", safePos);
            return ImportResult.failed(
                    Component.translatable("maid_file_manager.import.fail.add_entity"));
        }
        // 注意：此处绝不能手动 MaidWorldData.addInfo(maid)。该表的 TLM 不变量是"只登记离开已加载世界
        // 但仍存活的女仆"：实体入世界时 onAddedToLevel 会 removeInfo，所在区块卸载时
        // onRemovedFromLevel 才 addInfo。对活着且在已加载世界的新女仆手动 addInfo 会留下幽灵登记，
        // 后续"导出并移除"（discard 时 isAlive=false，TLM 不触碰该表）后记录永久残留，
        // 反而被双胞胎拦截误判为源女仆仍存活。登记由 TLM 实体生命周期自行维护。
        // 成就合并：仅当导入者本人即为女仆原主人时才应用，防止伪造 .maid 文件给他人刷成就
        if (ownerMatched && MaidConfigManager.isAdvancementsAllowed() && data.getAdvancements() != null) {
            boolean isSelfImport = data.getOwnerUuid() != null
                    && data.getOwnerUuid().equals(player.getUUID().toString());
            if (isSelfImport) {
                try {
                    AdvancementTransfer.applyToPlayer(player, data.getAdvancements());
                } catch (Throwable t) {
                    Constants.LOG.warn("[maid_file_manager] 成就合并失败（已忽略）: {}", t.toString());
                }
            }
        }
        // 附属模组扩展数据导入：遍历 extras，按 provider id 匹配并写回
        CompoundTag extras = data.getExtras();
        if (extras != null) {
            for (String key : extras.getAllKeys()) {
                net.minecraft.resources.ResourceLocation rl =
                        net.minecraft.resources.ResourceLocation.tryParse(key);
                MaidMigrationProvider p = MaidMigrationRegistry.get(rl);
                if (p == null || !p.isAvailable()) {
                    // 软依赖：对应 provider 未注册或模组未加载，跳过该段数据
                    continue;
                }
                try {
                    p.importData(maid, extras.getCompound(key));
                } catch (Throwable t) {
                    Constants.LOG.warn("[maid_file_manager] provider {} 导入失败（已跳过）: {}", key, t.toString());
                }
            }
        }
        // v7：档案写回。背景故事的权威来源是 data 内的 MaidAIChat.CustomSetting（已由 maid.load 恢复），
        // 因此默认不覆盖 AI 人设；仅当实体恢复后背景故事为空而档案带快照时，才用快照回填，
        // 保证档案展示不缺内容。其余档案字段直接写入实体持久化标签。
        try {
            io.github.zgxhzhr.maidfm.data.MaidProfile profile = data.getProfile();
            if (profile != null) {
                String currentStory = MaidProfileService.readStory(maid);
                boolean storyEmpty = currentStory == null || currentStory.isEmpty();
                MaidProfileService.writeToMaid(maid, profile, storyEmpty);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 档案写回失败（已忽略，不阻断导入）: {}", t.toString());
        }
        // 最终血量校准必须延迟到实体入世界且附属 tick 附加 modifier 之后，
        // 否则满血女仆会因白板上限夹断而掉血
        schedulePostImportHealthSync(level, maid, sourceHealth);
        Constants.LOG.info("[maid_file_manager] 导入完成 modelId={} ownerMatched={} pos={}",
                maid.getModelId(), ownerMatched, safePos);

        Component message = ownerMatched
                ? Component.translatable("maid_file_manager.import.success")
                : Component.translatable("maid_file_manager.import.fail.not_found_owner");
        ImportResult result = ownerMatched
                ? ImportResult.ok(message)
                : ImportResult.okUntamed(message);
        // 请求了保留饰品但被服务端配置拦截 → 非静默提示
        if (baublesRequested && !keepBaubles) {
            result = result.withBaublesStripped();
        }
        // 饰品逐件处理说明：非空才附加（全部正常保留时为 List.of()，不加噪声）
        if (!baubleNotes.isEmpty()) {
            result = result.withBaubleNotes(baubleNotes);
        }
        return result;
    }

    private static int readSourceFavorability(CompoundTag originalNbt) {
        if (originalNbt.contains("MaidFavorability", Tag.TAG_INT)) {
            return originalNbt.getInt("MaidFavorability");
        }
        if (originalNbt.contains("MaidFavorabilityManagerCounter", Tag.TAG_INT)) {
            return originalNbt.getInt("MaidFavorabilityManagerCounter");
        }
        return -1;
    }

    /**
     * maid.load 主路径 + 失败兜底。
     *
     * <p>注意：从 Entity.class 反射取得的 load 方法 invoke 时仍走虚分派，
     * 必然落到 EntityMaid 的重写方法上，无法调到基类实现（已用 javap 验证），
     * 因此失败后不再做无意义的二次反射调用，只走 TLM 数据兜底恢复。
     */
    private static void loadMaidNbt(EntityMaid maid, CompoundTag migrated, CompoundTag tlmTag,
                                   boolean keepBaubles, boolean tagStruckByLightning, boolean sameVersion,
                                   boolean stripAttributes, List<String> blockedList,
                                   int sourceMojangDataVersion, List<Component> baubleNotes) {
        boolean loadOk = false;
        try {
            maid.load(migrated);
            loadOk = true;
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            Constants.LOG.error("[maid_file_manager] maid.load 失败，走 TLM 数据兜底恢复。根因: {}: {}",
                    root.getClass().getSimpleName(), root.getMessage());
        }
        restoreTlmData(maid, tlmTag, keepBaubles, sameVersion, stripAttributes, blockedList,
                sourceMojangDataVersion, baubleNotes);
        maid.setStruckByLightning(tagStruckByLightning);
        if (!loadOk) {
            Constants.LOG.warn("[maid_file_manager] 该女仆为兜底加载，非物品类 TLM 数据可能不完整（详见上方抽取日志）");
        }
    }

    /**
     * 从 tag 中移除会导致 maid.load 跨版本崩溃的复杂容器并返回其副本。
     * 简单值字段（MaidFavorability / MaidExperience / MaidHunger / ModelId 等）一律保留，
     * 交给 TLM 自己的 readAdditionalSaveData 还原。
     */
    private static CompoundTag extractTlmData(CompoundTag tag) {
        CompoundTag extracted = new CompoundTag();
        String[] keys = {
                // 物品容器
                "MaidBaubleInventory", "MaidInventory", "MaidHideInventory", "MaidTaskInventory",
                "MaidGameSkillData",
                // 跨版本结构可能不一致的复杂容器
                "MaidTaskDataMaps",
                "MaidAIChat", "MaidHistoryChat", "MaidHistorySummary", "MaidLastChatTokenUsage",
                "MaidConfig", "MaidSubConfig", "MaidWorldData",
                "MaidBackpackData", "MaidTask",
                "MaidGameRecord", "MaidKillRecord",
                "MaidSchedulePos", "YsmRoamingVars", "Brain",
        };
        for (String key : keys) {
            if (tag.contains(key)) {
                extracted.put(key, tag.get(key).copy());
                tag.remove(key);
            }
        }
        return extracted;
    }

    /**
     * 恢复被抽取的 TLM 数据：饰品、ModelId、好感度、AI 对话/人设。
     * 其余纯数据容器（任务记录/战绩/配置等）当前无安全回填入口，
     * 不做静默处理：逐条 WARN 日志明示丢失，绝不在成功提示中掩盖。
     */
    private static void restoreTlmData(EntityMaid maid, CompoundTag tlmData, boolean keepBaubles,
                                       boolean sameVersion, boolean stripAttributes, List<String> blockedList,
                                       int sourceMojangDataVersion, List<Component> baubleNotes) {
        restoreBaubles(maid, tlmData, keepBaubles, sameVersion, stripAttributes, blockedList,
                sourceMojangDataVersion, baubleNotes);

        if (tlmData.contains("ModelId", Tag.TAG_STRING)) {
            String modelId = tlmData.getString("ModelId");
            if (modelId != null && !modelId.isEmpty()
                    && (maid.getModelId() == null || maid.getModelId().isEmpty())) {
                maid.setModelId(modelId);
            }
        }

        if (tlmData.contains("MaidFavorability", Tag.TAG_INT)) {
            int fav = tlmData.getInt("MaidFavorability");
            if (maid.getFavorability() == 0 && fav > 0) {
                maid.setFavorability(fav);
            }
        }

        restoreAiChat(maid, tlmData);

        // 明示未能恢复的数据（不静默丢失）
        List<String> notRestored = new ArrayList<>();
        for (String key : tlmData.getAllKeys()) {
            if (!"MaidBaubleInventory".equals(key) && !"ModelId".equals(key)
                    && !"MaidFavorability".equals(key)
                    && !"MaidAIChat".equals(key) && !"MaidHistoryChat".equals(key)
                    && !"MaidHistorySummary".equals(key) && !"MaidLastChatTokenUsage".equals(key)) {
                notRestored.add(key);
            }
        }
        if (!notRestored.isEmpty()) {
            Constants.LOG.warn("[maid_file_manager] 以下 TLM 数据因跨版本安全策略未恢复: {}", notRestored);
        }
    }

        /**
     * AI 对话恢复双路径：
     * (A) 调本体 readFromTag 还原聊天历史/摘要/token；
     * (B) 反射给人设字段赋值，兼容旧名 MaidAIChatSerializable（llmSite 等 8 字段）
     *     与新名 MaidAIDataSerializable（chatSiteName 等 6 字段）。
     */
    private static void restoreAiChat(EntityMaid maid, CompoundTag tlmData) {
        boolean hasAiData = tlmData.contains("MaidAIChat", Tag.TAG_COMPOUND)
                || tlmData.contains("MaidHistoryChat")
                || tlmData.contains("MaidHistorySummary", Tag.TAG_STRING);
        if (!hasAiData) {
            return;
        }
        try {
            maid.getAiChatManager().readFromTag(tlmData);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] AI 聊天历史恢复失败: {}", t.toString());
        }
        if (!tlmData.contains("MaidAIChat", Tag.TAG_COMPOUND)) {
            return;
        }
        try {
            CompoundTag ai = tlmData.getCompound("MaidAIChat");
            Object persona = maid.getAiChatManager();
            // 每行：[NBT键名1, NBT键名2, 候选字段名1, 候选字段名2, ...]
            String[][] fieldMap = {
                    {"LLMSite",     "llmSite",     "llmSite",      "chatSiteName"},
                    {"LLMModel",    "llmModel",    "llmModel",     "chatModel"},
                    {"TTSSiteName", "ttsSiteName", "ttsSite",      "ttsSiteName"},
                    {"TTSModel",    "ttsModel",    "ttsModel",     "ttsModel"},
                    {"TTSLanguage", "ttsLanguage", "ttsLanguage",  "ttsLanguage"},
                    {"ChatLanguage","chatLanguage","chatLanguage"},
                    {"OwnerName",   "ownerName",   "ownerName"},
                    {"CustomSetting","customSetting","customSetting"},
            };
            int forced = 0;
            Class<?> clazz = persona.getClass();
            for (String[] row : fieldMap) {
                String value = null;
                if (row[0] != null && ai.contains(row[0], Tag.TAG_STRING)) {
                    value = ai.getString(row[0]);
                } else if (row[1] != null && ai.contains(row[1], Tag.TAG_STRING)) {
                    value = ai.getString(row[1]);
                }
                if (value == null || value.isEmpty()) {
                    continue;
                }
                boolean set = false;
                for (int i = 2; i < row.length && !set; i++) {
                    if (row[i] == null) continue;
                    try {
                        java.lang.reflect.Field f = findField(clazz, row[i]);
                        if (f != null) {
                            f.setAccessible(true);
                            f.set(persona, value);
                            set = true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (set) forced++;
            }
            Constants.LOG.debug("[maid_file_manager] AI 人设字段覆盖 {} 个", forced);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] AI 人设恢复失败: {}", t.toString());
        }
    }

    /** 在 clazz 及其父类中按名称查找字段（忽略访问修饰符）。 */
    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 饰品恢复。规则：
     * 双闸门（客户端勾选 + 服务端 allow_baubles）；白名单仅 touhou_little_maid /
     * touhou_little_maid_spell；目标世界缺物品安全跳过；槽位按数据最大槽位经平台接口扩容。
     * 判定顺序（对每件饰品）：
     * <ol>
     *   <li>命中禁用携带列表（按完整物品 ID）→ 直接丢弃；</li>
     *   <li>命名空间白名单前置（第三方物品直接丢弃，不进入后续恢复）；</li>
     *   <li>开启"丢弃饰品属性"（stripAttributes）→ 跳过完整状态恢复，直接全新化；</li>
     *   <li>同版本导入：按原始 NBT 完整解析，保留附魔、耐久、无法破坏、属性修饰符等全部状态；</li>
     *   <li>跨版本导入且源 Mojang DataVersion 明确：先经 DataFixerUpper 升级为当前版本格式再解析
     *       （同样保留属性），升级失败/不适用回退全新化；</li>
     *   <li>其余（版本未知/同版本解析失败/无法升级）：全新化重建（无附魔、满耐久）。</li>
     * </ol>
     * 全程异常兜底，不影响女仆本体导入。
     */
    private static void restoreBaubles(EntityMaid maid, CompoundTag tlmData, boolean keepBaubles,
                                       boolean sameVersion, boolean stripAttributes, List<String> blockedList,
                                       int sourceMojangDataVersion, List<Component> baubleNotes) {
        if (!tlmData.contains("MaidBaubleInventory", Tag.TAG_COMPOUND) || !keepBaubles
                || !MaidConfigManager.isBaublesAllowed()) {
            return;
        }
        try {
            ListTag items = tlmData.getCompound("MaidBaubleInventory").getList("Items", Tag.TAG_COMPOUND);
            if (items.isEmpty()) {
                return;
            }
            int currentSlots = Services.PLATFORM.get().baubleGetSlots(maid);
            if (currentSlots <= 0) {
                Constants.LOG.warn("[maid_file_manager] 饰品栏不可用，{} 件饰品全部跳过", items.size());
                return;
            }
            int needed = currentSlots;
            int oversized = 0;
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i);
                int slot = entry.contains("Slot", Tag.TAG_INT) ? entry.getInt("Slot") : i;
                // 槽位号取自网络 NBT，绝不可信：负值或越过硬上限按坏数据丢弃，绝不据此扩容
                if (slot < 0 || slot >= MAX_BAUBLE_SLOTS) {
                    oversized++;
                    continue;
                }
                if (slot + 1 > needed) {
                    needed = slot + 1;
                }
            }
            if (oversized > 0) {
                Constants.LOG.warn("[maid_file_manager] 饰品数据含 {} 个非法槽位号（硬上限 {} 槽），对应饰品已丢弃",
                        oversized, MAX_BAUBLE_SLOTS);
            }
            if (needed > currentSlots) {
                // setSize 必须在写入前一次性完成（会重建槽位列表）
                Services.PLATFORM.get().baubleResize(maid, needed);
                currentSlots = needed;
                Constants.LOG.debug("[maid_file_manager] 饰品栏扩容至 {} 槽", needed);
            }
            int restoredFull = 0;
            int restoredFresh = 0;
            int droppedBlocked = 0;
            int droppedForeign = 0;
            int droppedWhitelist = 0;
            int droppedMissing = 0;
            int droppedFailed = 0;
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i);
                try {
                    int slot = entry.contains("Slot", Tag.TAG_INT) ? entry.getInt("Slot") : i;
                    // 硬上限外的非法槽位已在第一遍计入 oversized 并 WARN，这里直接跳过，避免同一批条目被重复计数
                    if (slot < 0 || slot >= MAX_BAUBLE_SLOTS) {
                        continue;
                    }
                    if (slot >= currentSlots) {
                        droppedFailed++;
                        continue;
                    }
                    String id = entry.contains("id", Tag.TAG_STRING) ? entry.getString("id") : "";
                    // 条目 ID 解析出的物品（跨版本旧键可能解析失败，此时仅能按 ID 匹配）
                    Item preItem = id.isEmpty() ? null : resolveItem(id);
                    // 步骤 1：禁用携带清单命中（OP 配置或整合包黑名单，支持物品 ID 或中文显示名精确匹配）→ 直接丢弃
                    if (baubleMatchesList(id, preItem, blockedList)) {
                        droppedBlocked++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.blocked", id);
                        continue;
                    }
                    // 步骤 2：命名空间白名单前置。路径 A/DFU 解析后还会以实际物品键复核，
                    // 此处先按 entry 的 id 快速拦截第三方物品，避免无谓解析。
                    String namespace = namespaceOf(id);
                    if (!isBaubleAllowed(namespace)) {
                        droppedForeign++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.foreign", id);
                        continue;
                    }
                    // 步骤 2b：整合包白名单（bauble_import.json 启用时）：ID 或中文显示名未命中即拒绝
                    if (!MaidConfigManager.isBaubleWhitelistPass(id, displayNameOf(preItem))) {
                        droppedWhitelist++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.whitelist", id);
                        continue;
                    }
                    // 步骤 3~5：未开启"丢弃饰品属性"时尝试完整状态恢复（同版本直接解析；
                    // 跨版本先经平台层升级/降级到当前版本再解析，保留附魔/耐久/无法破坏/属性修饰符）。
                    ItemStack full = stripAttributes ? ItemStack.EMPTY
                            : tryParseFullBauble(maid, entry, sameVersion, sourceMojangDataVersion);
                    if (full != null && !full.isEmpty()) {
                        // 以解析后注册表中的实际物品键复核白名单，伪造 id 与实际物品不符也无法绕过
                        net.minecraft.resources.ResourceLocation fullKey =
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(full.getItem());
                        String fullId = fullKey.toString();
                        if (!isBaubleAllowed(fullKey.getNamespace())) {
                            // 完整解析得到非白名单物品：不得退回全新化（全新化仍是同一件第三方物品），直接丢弃
                            droppedForeign++;
                            addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.foreign", id);
                            continue;
                        }
                        // 解析后物品可能与条目 id 不一致：禁用清单与整合包白名单同步复核
                        if (baubleMatchesList(fullId, full.getItem(), blockedList)) {
                            droppedBlocked++;
                            addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.blocked", id);
                            continue;
                        }
                        if (!MaidConfigManager.isBaubleWhitelistPass(fullId, displayNameOf(full.getItem()))) {
                            droppedWhitelist++;
                            addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.whitelist", id);
                            continue;
                        }
                        Services.PLATFORM.get().baubleSetStack(maid, slot, full);
                        restoredFull++;
                        continue;
                    }
                    // 步骤 6：全新化重建（开启丢弃属性 / 跨版本无法转换 / 同版本解析失败）
                    // 白名单与禁用清单已在前面按条目 ID 复核，此处复用已解析的物品，避免重复查表
                    if (id.isEmpty()) {
                        droppedFailed++;
                        continue;
                    }
                    Item item = preItem;
                    if (item == null || Items.AIR.equals(item)) {
                        droppedMissing++;
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.missing_item", id);
                        continue;
                    }
                    int count = 1;
                    if (entry.contains("Count", Tag.TAG_ANY_NUMERIC)) {
                        count = entry.getInt("Count");
                    } else if (entry.contains("count", Tag.TAG_ANY_NUMERIC)) {
                        count = entry.getInt("count");
                    }
                    count = Math.max(1, count);
                    ItemStack fresh = new ItemStack(item, count);
                    fresh.setCount(Math.min(count, fresh.getMaxStackSize()));
                    Services.PLATFORM.get().baubleSetStack(maid, slot, fresh);
                    restoredFresh++;
                    // 全新化原因区分：开启丢弃属性 → 设置原因；否则 → 格式无法解析（跨版本转换失败/版本未知/同版本解析失败）
                    if (stripAttributes) {
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.stripped", id);
                    } else {
                        addBaubleNote(baubleNotes, "maid_file_manager.import.bauble.unparseable", id);
                    }
                } catch (Throwable t) {
                    droppedFailed++;
                    Constants.LOG.warn("[maid_file_manager] 单件饰品恢复失败，跳过: {}", t.toString());
                }
            }
            Constants.LOG.info("[maid_file_manager] 饰品恢复完成: 完整恢复={} 全新化={} 禁用清单={} 非TLM命名空间={} 未入整合包白名单={} 目标世界缺失={} 失败={} 非法槽位={} 同版本={}",
                    restoredFull, restoredFresh, droppedBlocked, droppedForeign, droppedWhitelist,
                    droppedMissing, droppedFailed, oversized, sameVersion);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品整体恢复失败（不影响女仆导入）: {}", t.toString());
        }
    }

    /**
     * 尝试完整状态恢复单件饰品（保留属性），返回空物品栈表示应回退全新化。
     * 同版本直接按原始 NBT 解析；跨版本时由平台层 {@code convertItemStackNbt}
     * 按源 DataVersion 相对当前版本的方向执行升级（DFU）或降级（手动组件→tag 转换）。
     * 任何失败返回空物品栈（日志留痕，非静默失败）。
     */
    private static ItemStack tryParseFullBauble(EntityMaid maid, CompoundTag entry,
                                                boolean sameVersion, int sourceMojangDataVersion) {
        try {
            if (sameVersion) {
                return Services.PLATFORM.get().parseItemStack(maid.level().registryAccess(), entry);
            }
            if (sourceMojangDataVersion > 0) {
                return Services.PLATFORM.get().convertItemStackNbt(
                        maid.level().registryAccess(), entry, sourceMojangDataVersion);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品完整状态恢复失败，回退全新化: {}", t.toString());
        }
        return ItemStack.EMPTY;
    }

    /** 饰品条目在提示语中的名称：物品可解析时用游戏内名称，否则回退原始 ID */
    private static Component baubleItemLabel(String id) {
        if (id != null && !id.isEmpty()) {
            Item item = resolveItem(id);
            if (item != null && !Items.AIR.equals(item)) {
                return item.getName(new ItemStack(item));
            }
            return Component.literal(id);
        }
        return Component.literal("?");
    }

    /**
     * 判断饰品条目是否命中清单（禁用清单 / 整合包白名单）。
     * <p>为兼顾整合包作者的书写习惯，支持<b>物品注册 ID 或物品中文显示名</b>两种写法精确匹配。
     */
    private static boolean baubleMatchesList(String id, Item item, List<String> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        if (id != null && !id.isEmpty() && list.contains(id)) {
            return true;
        }
        String name = displayNameOf(item);
        return name != null && list.contains(name);
    }

    /** 物品的游戏内显示名（含语言文件本地化）；无法解析时返回 null，绝不抛出 */
    private static String displayNameOf(Item item) {
        if (item == null || Items.AIR.equals(item)) {
            return null;
        }
        try {
            return item.getName(new ItemStack(item)).getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 追加一条饰品处理说明（translatable，由客户端按自身语言本地化） */
    private static void addBaubleNote(List<Component> notes, String langKey, String id) {
        if (notes == null) {
            return;
        }
        notes.add(Component.translatable(langKey, baubleItemLabel(id)));
    }

    /** 饰品白名单：仅允许 TLM 本体与 TLM 法术附属的物品进入饰品栏（防第三方物品经伪造文件刷入） */
    private static boolean isBaubleAllowed(String namespace) {
        return "touhou_little_maid".equals(namespace)
                || "touhou_little_maid_spell".equals(namespace);
    }

    /**
     * 服务端收窄校验：仅保留车万本体/法术附属两命名空间内的物品 ID。
     *
     * <p><b>仅用于 C2S 网络通道</b>（玩家提交的禁用清单可能被篡改；黑名单只能缩小可携带范围、
     * 绝不能扩大，故命名空间不符的 ID 一律剔除，并容忍空白项/重复项）。
     * <p>服务端配置（properties / 整合包 {@code bauble_import.json}）在读取时<b>不做</b>此收窄，
     * 因为整合包配置允许书写中文显示名（不含命名空间前缀），收窄会误删。
     */
    public static List<String> sanitizeBaubleBlockedList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            if (isBaubleAllowed(namespaceOf(trimmed)) && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(0, colon) : "minecraft";
    }

    private static Item resolveItem(String id) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            return rl == null ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 解析物品 {} 失败: {}", id, t.toString());
            return null;
        }
    }

    private static void rebuildAttributesAndModel(EntityMaid maid, MaidFileData data,
                                                  boolean sourceStruckByLightning) {
        int favorability = maid.getFavorability();
        // 好感等级与属性曲线直接委托 TLM 公开 API，避免硬编码表随 TLM 改版漂移
        FavorabilityManager manager = maid.getFavorabilityManager();
        int level = manager.getLevel();
        // 属性基础值无条件回到 TLM 白板值（该好感等级，含雷劫 +20 血量）。
        // 不保留源存档 base：全局生物血量倍率类模组（直接改 base 或写持久 modifier）会把
        // 女仆血量抬到离谱数值，跟着 .maid 跨存档迁移会破坏联机导入公平；持久 modifier 在
        // NbtMigration 阶段随 Attributes 标签整体清除。附属合法的饰品/词条/羁绊加成走
        // AttributeModifier，导入后饰品回栏、实体 tick 即自动附加，无需保留 base。
        double healthBase = manager.getHealthByLevel(level);
        double attackBase = manager.getAttackByLevel(level);
        if (maid.isStruckByLightning() || sourceStruckByLightning) {
            healthBase += 20;
        }

        AttributeInstance health = maid.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(healthBase);
            if (maid.getHealth() > maid.getMaxHealth()) {
                maid.setHealth(maid.getMaxHealth());
            }
        }
        AttributeInstance attack = maid.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attackBase);
        }
        Constants.LOG.debug("[maid_file_manager] 重建属性: fav={} level={} healthBase={} attackBase={}",
                favorability, level,
                health == null ? "null" : health.getBaseValue(),
                attack == null ? "null" : attack.getBaseValue());
        if (data != null && data.getModelId() != null && !data.getModelId().isEmpty()
                && !data.getModelId().equals(maid.getModelId())) {
            maid.setModelId(data.getModelId());
        }
    }

    /**
     * 入世界后的最终血量校准（链式重试，见 {@link #scheduleHealthSyncTick}）。
     *
     * <p>饰品/词条类附属在实体 tick 时才把 AttributeModifier 附加到属性上，
     * 而实体入世界前的 setHealth 只能按白板 maxHealth 夹断，会把满血女仆的当前血量
     * 压到白板值；待 modifier 生效后 maxHealth 升高，当前血量却停在低位——表现为导入后掉血。
     * 由于不同附属附加 modifier 的时刻不固定，采用有界窗口内逐 tick 重试补齐。
     */
    private static void schedulePostImportHealthSync(Level level, EntityMaid maid, float sourceHealth) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        UUID maidId = maid.getUUID();
        // 链式重试校准：附属（饰品/词条）在实体入世界后的 tick 中陆续附加 AttributeModifier，
        // 固定时刻校准无法保证晚于所有附属，曾出现满血肉盾女仆导入后被夹回白板血量的时序竞态。
        // 在有界窗口内每 tick 按"当前"最大血量重算目标并补齐，可追上任意时刻附加的 modifier。
        scheduleHealthSyncTick(serverLevel, maidId, sourceHealth, POST_IMPORT_HEAL_RETRY_TICKS);
    }

    /**
     * 入世界后血量校准的单次重试。每 tick 执行一次，链式续调到下一 tick，
     * 直到 {@code ticksLeft} 耗尽（约 {@value #POST_IMPORT_HEAL_RETRY_TICKS} tick / 1 秒）。
     *
     * <p>语义：只抬血不扣血；目标为 min(源血量, 当前最大血量)。旧文件无源血量时按满血处理。
     * 修饰符在窗口内任意 tick 附加都会使当前最大血量升高，下一次重试即可把血量补齐。
     */
    private static void scheduleHealthSyncTick(net.minecraft.server.level.ServerLevel serverLevel,
                                               UUID maidId, float sourceHealth, int ticksLeft) {
        net.minecraft.server.MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, () -> {
            Entity entity = serverLevel.getEntity(maidId);
            if (entity instanceof EntityMaid fresh && fresh.isAlive()) {
                float currentMax = fresh.getMaxHealth();
                float target = sourceHealth > 0 ? Math.min(sourceHealth, currentMax) : currentMax;
                if (fresh.getHealth() + 0.01F < target) {
                    fresh.setHealth(target);
                }
            }
            // 实体消失（死亡/被清除）则停止；否则在窗口内继续重试以覆盖晚到的修饰符
            if (ticksLeft > 0 && entity instanceof EntityMaid alive && alive.isAlive()) {
                scheduleHealthSyncTick(serverLevel, maidId, sourceHealth, ticksLeft - 1);
            }
        }));
    }

    private static void ensureSchedulePosNonNull(EntityMaid maid) {
        try {
            BlockPos pos = maid.blockPosition();
            SchedulePos schedulePos = maid.getSchedulePos();
            schedulePos.setWorkPos(pos);
            schedulePos.setIdlePos(pos);
            schedulePos.setSleepPos(pos);
            schedulePos.setDimension(maid.level().dimension().location());
            schedulePos.setConfigured(false);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] SchedulePos 初始化失败: {}", t.toString());
        }
    }

    /**
     * 导出并移除女仆（discard）前调用：清理该女仆在 MaidWorldData 存活登记表中的全部记录。
     *
     * <p>必要性：discard() 时实体 isAlive 已为 false，TLM 的 onRemovedFromLevel 不会新增登记，
     * 但也不会清理既有记录。正常生命周期内该表不应残留已加载女仆的记录，然而旧版本本模组曾在
     * 导入后手动 addInfo，产生过幽灵登记；实体被直接移除后这些记录会永久残留并误伤后续导入。
     * 移除实体前按 UUID 显式清除，使"导出并移除"语义与登记表保持一致。
     * 必须在服务端主线程、discard() 之前调用。
     */
    public static void unregisterMaidWorldData(Entity entity) {
        try {
            if (entity instanceof EntityMaid maid && maid.getOwnerUUID() != null) {
                MaidWorldData data = MaidWorldData.get(maid.level());
                if (data != null) {
                    data.removeInfo(maid);
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 清理 MaidWorldData 登记失败: {}", t.toString());
        }
    }

    /**
     * 解析源女仆实体 UUID：优先读 .maid 顶层 source_maid_uuid（v6 起写入）；
     * 旧文件无此字段时从实体根 NBT 兜底（1.9+ int 数组 "UUID"，或 1.8 的 UUIDMost/UUIDLeast）。
     * 注意必须在 {@link NbtMigration#migrate} 之前读取——迁移会恒删实体根 UUID 键。
     *
     * @return 源女仆 UUID；无法解析（极早期文件且 NBT 缺键）返回 null，调用方退回随机实体 UUID
     */
    private static UUID resolveSourceMaidUuid(MaidFileData data, CompoundTag originalNbt) {
        String topLevel = data.getSourceMaidUuid();
        if (topLevel != null) {
            try {
                return UUID.fromString(topLevel);
            } catch (IllegalArgumentException ignored) {
                // 顶层字段非法，落回实体 NBT 兜底
            }
        }
        if (originalNbt != null) {
            try {
                if (originalNbt.hasUUID("UUID")) {
                    return originalNbt.getUUID("UUID");
                }
                if (originalNbt.contains("UUIDMost", Tag.TAG_LONG)
                        && originalNbt.contains("UUIDLeast", Tag.TAG_LONG)) {
                    return new UUID(originalNbt.getLong("UUIDMost"), originalNbt.getLong("UUIDLeast"));
                }
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] 从实体 NBT 解析源女仆 UUID 失败: {}", t.toString());
            }
        }
        return null;
    }

    /**
     * 确定性导入 UUID 映射：同一玩家导入同一源女仆，永远得到同一目标 UUID；
     * 不同玩家（多人服分享文件）因玩家 UUID 参与哈希而得到不同 UUID，互不冲突。
     *
     * <p>使用 JDK 内置的 v3(MD5) 名字 UUID：固定命名前缀隔离用途，输出带版本/变体位，
     * 与原版随机 v4 实体 UUID 不会发生有意义的碰撞。该函数纯函数、无存储，故不存在映射累积问题。
     */
    private static UUID mapImportUuid(UUID sourceMaidUuid, UUID importerPlayerUuid) {
        String name = "maid_file_manager|import-v1|" + sourceMaidUuid + "|" + importerPlayerUuid;
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 判定指定 UUID 在已加载世界（任意维度）中是否已有女仆实体。
     * 仅供 addFreshEntity 失败后的原因细分：同 UUID 实体只有处在已加载区块时才会导致原生拒绝，
     * 未加载区块 / 魂符中的实体查不到也不应影响判定（那些场景导入本就成功）。
     * server 为 null 时返回 false，使调用方落回通用失败文案。
     */
    private static boolean isLoadedMaidWithUuid(MinecraftServer server, UUID entityId) {
        if (server == null) {
            return false;
        }
        for (ServerLevel serverLevel : server.getAllLevels()) {
            if (serverLevel.getEntity(entityId) instanceof EntityMaid) {
                return true;
            }
        }
        return false;
    }

    /**
     * 双胞胎判定：源女仆是否仍以本世界"存活实体"的身份存在。
     * <ol>
     *   <li>任意维度已加载区块中存在同 UUID 且存活的 EntityMaid；</li>
     *   <li>MaidWorldData 中仍有该女仆的存活登记——TLM 仅在女仆"离开已加载世界且仍然存活"
     *       （区块卸载保存、收入魂符等）时登记，于实体回到已加载世界（onAddedToLevel）时移除；
     *       正常死亡与导出移除（discard 时 isAlive=false）均不会新增登记。导出移除通道在
     *       discard 前还会显式 removeInfo，清除可能存在的历史幽灵登记。</li>
     * </ol>
     * 因此"导出时保留原女仆"后再导回同一世界会被拦截，而"导出并移除后再导入"、
     * 原女仆死亡后再导入、跨存档导入均不受影响。
     */
    private static boolean isSourceMaidAlive(ServerPlayer player, UUID sourceMaidUuid, String ownerUuidStr) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        for (ServerLevel serverLevel : server.getAllLevels()) {
            if (serverLevel.getEntity(sourceMaidUuid) instanceof EntityMaid maid && maid.isAlive()) {
                return true;
            }
        }
        try {
            UUID ownerUuid = null;
            if (ownerUuidStr != null) {
                try {
                    ownerUuid = UUID.fromString(ownerUuidStr);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (ownerUuid != null && player.level() instanceof ServerLevel serverLevel) {
                MaidWorldData worldData = MaidWorldData.get(serverLevel);
                if (worldData != null) {
                    List<com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo> infos =
                            worldData.getInfos(ownerUuid);
                    if (infos != null) {
                        for (com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo info : infos) {
                            if (sourceMaidUuid.equals(info.getEntityId())) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // TLM 世界数据异常不得阻断导入：放行并留痕，交由后续 addFreshEntity 原生规则兜底
            Constants.LOG.warn("[maid_file_manager] MaidWorldData 双胞胎查询失败（本次放行）: {}", t.toString());
        }
        return false;
    }

    private static BlockPos findSafeSpawnPos(Level level, BlockPos start) {
        for (int dy = -2; dy <= SPAWN_SAFE_MAX_UP; dy++) {
            BlockPos feet = start.offset(0, dy, 0);
            BlockPos head = feet.above();
            BlockState feetBlock = level.getBlockState(feet);
            BlockState headBlock = level.getBlockState(head);
            if (feetBlock.isCollisionShapeFullBlock(level, feet)
                    && headBlock.isAir()
                    && level.getBlockState(head.above()).isAir()) {
                return head;
            }
        }
        return null;
    }

    /**
     * 主人匹配：UUID 精确 > 在线玩家名 > 未驯服。
     * 1.20.x 的 setTame 为单参签名（仅是否驯服）。
     */
    private static boolean matchOwner(EntityMaid maid, ServerPlayer player, MaidFileData data) {
        if (!data.isTamed()) {
            maid.setTame(false, false);
            return false;
        }
        if (data.getOwnerUuid() != null) {
            try {
                UUID uuid = UUID.fromString(data.getOwnerUuid());
                if (uuid.equals(player.getUUID())) {
                    maid.setOwnerUUID(uuid);
                    maid.setTame(true, false);
                    return true;
                }
                ServerPlayer owner = player.getServer().getPlayerList().getPlayer(uuid);
                if (owner != null) {
                    maid.setOwnerUUID(uuid);
                    maid.setTame(true, false);
                    return true;
                }
            } catch (IllegalArgumentException e) {
                Constants.LOG.warn("[maid_file_manager] 无效的主人 UUID: {}", data.getOwnerUuid());
            }
        }
        if (data.getOwnerName() != null && !data.getOwnerName().isEmpty()) {
            ServerPlayer owner = player.getServer().getPlayerList().getPlayerByName(data.getOwnerName());
            if (owner != null) {
                maid.setOwnerUUID(owner.getUUID());
                maid.setTame(true, false);
                return true;
            }
        }
        maid.setTame(false, false);
        maid.setOwnerUUID(null);
        return false;
    }

    /**
     * 查找女仆原主人的在线 ServerPlayer（用于成就合并应用）。
     * 逻辑与 matchOwner 一致：UUID 精确 > 在线玩家名 > null。
     */
    private static ServerPlayer findOriginalOwner(ServerPlayer player, MaidFileData data) {
        if (data.getOwnerUuid() != null) {
            try {
                UUID uuid = UUID.fromString(data.getOwnerUuid());
                if (uuid.equals(player.getUUID())) {
                    return player;
                }
                return player.getServer().getPlayerList().getPlayer(uuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (data.getOwnerName() != null && !data.getOwnerName().isEmpty()) {
            return player.getServer().getPlayerList().getPlayerByName(data.getOwnerName());
        }
        return null;
    }

    // ============================ 批量结果汇总（全平台共用，禁止在平台层反解文案） ============================

    /**
     * 把多条结构化导入结果汇总为一条展示文案。
     * 成功/失败统计只基于 {@link ImportResult.State}，与语言无关。
     */
    public static Component buildBatchSummary(List<ImportResult> results) {
        int ok = 0;
        int untamed = 0;
        int blocked = 0;
        int failed = 0;
        boolean baublesStripped = false;
        for (ImportResult r : results) {
            switch (r.state()) {
                case OK -> ok++;
                case OK_UNTAMED -> untamed++;
                case SERVER_DISALLOWED -> blocked++;
                case FAILED -> failed++;
            }
            baublesStripped |= r.baublesStripped();
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "批量导入完成：成功 %d 个，失败 %d 个", ok + untamed, blocked + failed));
        if (untamed > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "（其中 %d 个未匹配到主人，已生成为野生女仆）", untamed));
        }
        if (blocked > 0) {
            sb.append("。失败原因：服务器已禁止导入女仆（管理员可在服务端设置中开启「允许客户端导入女仆」）");
        }
        if (baublesStripped) {
            sb.append("。服务端未开启饰品导入，本次导入的女仆均未携带饰品");
        }
        // 逐项失败详情：最多展示前 5 条（每条均保留 translatable，由客户端本地化为对应语言），
        // 超出部分只报数量。绝不能只给"失败 N 个"的统计而吞掉具体原因
        // （重复导入 / 文件损坏 / 实体生成被拒等）。
        List<Component> failDetails = new ArrayList<>();
        for (ImportResult r : results) {
            if (r.state() == ImportResult.State.FAILED) {
                failDetails.add(r.message());
            }
        }
        net.minecraft.network.chat.MutableComponent summary = Component.literal(sb.toString());
        if (!failDetails.isEmpty()) {
            net.minecraft.network.chat.MutableComponent detail = Component.literal("。失败详情：");
            int shown = Math.min(failDetails.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    detail.append("；");
                }
                detail.append(failDetails.get(i));
            }
            if (failDetails.size() > shown) {
                detail.append(String.format(java.util.Locale.ROOT, "等共 %d 个失败", failDetails.size()));
            }
            summary.append(detail);
        }
        // 饰品逐件处理说明：把全部结果的备注按出现顺序合并展示（最多 5 条，超出只报数量）。
        // 即便女仆本体导入成功，饰品被丢弃/还原全新也属于需要玩家知晓的非静默结果
        List<Component> baubleDetails = new ArrayList<>();
        for (ImportResult r : results) {
            if (r.baubleNotes() != null && !r.baubleNotes().isEmpty()) {
                baubleDetails.addAll(r.baubleNotes());
            }
        }
        if (!baubleDetails.isEmpty()) {
            net.minecraft.network.chat.MutableComponent detail = Component.literal("。饰品说明：");
            int shown = Math.min(baubleDetails.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    detail.append("；");
                }
                detail.append(baubleDetails.get(i));
            }
            if (baubleDetails.size() > shown) {
                detail.append(Component.translatable(
                        "maid_file_manager.import.bauble.more", baubleDetails.size() - shown));
            }
            summary.append(detail);
        }
        return summary;
    }

    /**
     * 单文件导入通道的展示文案：在结果消息后追加饰品逐件处理说明（最多 5 条，超出只报数量）。
     * 批量通道已由 {@link #buildBatchSummary} 合并，无需再调用本方法。
     */
    public static Component withBaubleDetails(ImportResult result) {
        List<Component> notes = result == null ? List.of() : result.baubleNotes();
        if (notes == null || notes.isEmpty()) {
            return result == null ? Component.empty() : result.message();
        }
        net.minecraft.network.chat.MutableComponent msg = result.message().copy();
        net.minecraft.network.chat.MutableComponent detail = Component.literal("。饰品说明：");
        int shown = Math.min(notes.size(), 5);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                detail.append("；");
            }
            detail.append(notes.get(i));
        }
        if (notes.size() > shown) {
            detail.append(Component.translatable(
                    "maid_file_manager.import.bauble.more", notes.size() - shown));
        }
        return msg.append(detail);
    }
}
