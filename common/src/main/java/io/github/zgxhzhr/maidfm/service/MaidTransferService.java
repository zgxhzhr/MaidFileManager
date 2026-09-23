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
    /** 满血上限：满好感基础 80，渡劫额外 +20 */
    private static final double MAID_MAX_HEALTH = 80.0D;
    private static final double MAID_DEFAULT_ATTACK_DAMAGE = 2.0D;
    private static final double MAID_MAX_ATTACK_DAMAGE = 1024.0D;
    /** 属性安全硬上限，防止恶意外挂 NBT 把属性打穿 */
    private static final double ABSOLUTE_MAX_HEALTH_CAP = 256.0D;
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
            // 同时生成 normalized 标准化数据用于跨版本兼容
            CompoundTag effectsTag = null;
            if (fullNbt.contains("ActiveEffects", Tag.TAG_LIST)) {
                ListTag rawList = fullNbt.getList("ActiveEffects", Tag.TAG_COMPOUND);
                effectsTag = new CompoundTag();
                // 原始 NBT 副本：同版本直读（MobEffectInstance.load）
                effectsTag.put("active_effects", rawList.copy());
                // 标准化数据：跨版本重建 MobEffectInstance（按 ResourceLocation 查注册表）
                effectsTag.put("normalized", buildNormalizedEffects(rawList));
                fullNbt.remove("ActiveEffects");
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
                        net.minecraft.world.effect.MobEffect.byId(numericId);
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
     * <p>1.20.x: MobEffectInstance 构造器直接接受 {@code MobEffect}。
     */
    private static net.minecraft.world.effect.MobEffectInstance rebuildEffectFromNormalized(CompoundTag item) {
        try {
            String idStr = item.getString("id");
            if (idStr.isEmpty()) return null;
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.tryParse(idStr);
            if (rl == null) return null;
            net.minecraft.world.effect.MobEffect effect =
                    net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(rl);
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

    public static ImportResult importMaidFromData(ServerPlayer player, MaidFileData data, boolean keepBaubles) {
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

        EntityMaid maid = new EntityMaid(level);
        try {
            int sourceVersion = data.getDataVersion() > 0
                    ? data.getDataVersion()
                    : NbtVersion.fromMcVersion(data.getSourceMcVersion());
            int targetVersion = NbtVersion.currentRuntime();
            // migrate 返回清理后的副本，不修改原始 data
            CompoundTag migrated = NbtMigration.migrate(originalNbt, sourceVersion, targetVersion);
            CompoundTag tlmTag = extractTlmData(migrated);
            loadMaidNbt(maid, migrated, tlmTag, keepBaubles, sourceStruckByLightning);
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

        rebuildAttributesAndModel(maid, data, sourceStruckByLightning);
        validateMaidAttributes(maid);
        float maxHealth = maid.getMaxHealth();
        maid.setHealth(sourceHealth > 0 && sourceHealth <= maxHealth ? sourceHealth : maxHealth);

        try {
            maid.removeAllEffects();
            // 药水效果恢复逻辑：始终从 .maid 文件读取，按配置决定是否恢复到实体
            CompoundTag effectsData = data.getEffects();
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
            Constants.LOG.error("[maid_file_manager] addFreshEntity 被拒绝 pos={}", safePos);
            return ImportResult.failed(
                    Component.translatable("maid_file_manager.import.fail.add_entity"));
        }
        registerMaidWorldData(maid);
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
                                   boolean keepBaubles, boolean tagStruckByLightning) {
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
        restoreTlmData(maid, tlmTag, keepBaubles);
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
    private static void restoreTlmData(EntityMaid maid, CompoundTag tlmData, boolean keepBaubles) {
        restoreBaubles(maid, tlmData, keepBaubles);

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
     * touhou_little_maid_spell；全新化重建（无附魔、满耐久）；目标世界缺物品安全跳过；
     * 槽位按数据最大槽位经平台接口扩容。全程异常兜底，不影响女仆本体导入。
     */
    private static void restoreBaubles(EntityMaid maid, CompoundTag tlmData, boolean keepBaubles) {
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
            int restored = 0;
            int droppedForeign = 0;
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
                    if (id.isEmpty()) {
                        droppedFailed++;
                        continue;
                    }
                    String namespace = namespaceOf(id);
                    if (!"touhou_little_maid".equals(namespace)
                            && !"touhou_little_maid_spell".equals(namespace)) {
                        droppedForeign++;
                        continue;
                    }
                    Item item = resolveItem(id);
                    if (item == null || Items.AIR.equals(item)) {
                        droppedMissing++;
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
                    restored++;
                } catch (Throwable t) {
                    droppedFailed++;
                    Constants.LOG.warn("[maid_file_manager] 单件饰品恢复失败，跳过: {}", t.toString());
                }
            }
            Constants.LOG.info("[maid_file_manager] 饰品恢复完成: 成功={} 非白名单={} 目标世界缺失={} 失败={} 非法槽位={}",
                    restored, droppedForeign, droppedMissing, droppedFailed, oversized);
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 饰品整体恢复失败（不影响女仆导入）: {}", t.toString());
        }
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
        int healthByLevel = manager.getHealthByLevel(level);
        int attackByLevel = manager.getAttackByLevel(level);
        if (maid.isStruckByLightning() || sourceStruckByLightning) {
            healthByLevel += 20;
        }
        AttributeInstance health = maid.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(healthByLevel);
            if (maid.getHealth() > maid.getMaxHealth()) {
                maid.setHealth(maid.getMaxHealth());
            }
        }
        AttributeInstance attack = maid.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attackByLevel);
        }
        Constants.LOG.debug("[maid_file_manager] 重建属性: fav={} level={} health={} attack={}",
                favorability, level, healthByLevel, attackByLevel);
        if (data != null && data.getModelId() != null && !data.getModelId().isEmpty()
                && !data.getModelId().equals(maid.getModelId())) {
            maid.setModelId(data.getModelId());
        }
    }

    private static void validateMaidAttributes(EntityMaid maid) {
        double baseCap = MAID_MAX_HEALTH + (maid.isStruckByLightning() ? 20.0D : 0.0D);
        double currentMax = maid.getAttributeValue(Attributes.MAX_HEALTH);
        double effectiveCap = Math.min(Math.max(baseCap, currentMax), ABSOLUTE_MAX_HEALTH_CAP);
        AttributeInstance healthAttr = maid.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null) {
            double maxHealth = healthAttr.getBaseValue();
            if (maxHealth > effectiveCap) {
                Constants.LOG.warn("[maid_file_manager] 血量上限 {} 超限，截断到 {}", maxHealth, effectiveCap);
                healthAttr.setBaseValue(effectiveCap);
                if (maid.getHealth() > effectiveCap) {
                    maid.setHealth((float) effectiveCap);
                }
            }
        }
        AttributeInstance attackAttr = maid.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackAttr != null) {
            double attackDamage = attackAttr.getBaseValue();
            if (attackDamage <= 0 || attackDamage > MAID_MAX_ATTACK_DAMAGE) {
                Constants.LOG.warn("[maid_file_manager] 攻击伤害 {} 异常，回退默认值 {}",
                        attackDamage, MAID_DEFAULT_ATTACK_DAMAGE);
                attackAttr.setBaseValue(MAID_DEFAULT_ATTACK_DAMAGE);
            }
        }
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

    private static void registerMaidWorldData(EntityMaid maid) {
        try {
            if (maid.getOwnerUUID() != null) {
                MaidWorldData data = MaidWorldData.get(maid.level());
                if (data != null) {
                    data.addInfo(maid);
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] 注册 MaidWorldData 失败: {}", t.toString());
        }
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
            maid.setTame(false);
            return false;
        }
        if (data.getOwnerUuid() != null) {
            try {
                UUID uuid = UUID.fromString(data.getOwnerUuid());
                if (uuid.equals(player.getUUID())) {
                    maid.setOwnerUUID(uuid);
                    maid.setTame(true);
                    return true;
                }
                ServerPlayer owner = player.getServer().getPlayerList().getPlayer(uuid);
                if (owner != null) {
                    maid.setOwnerUUID(uuid);
                    maid.setTame(true);
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
                maid.setTame(true);
                return true;
            }
        }
        maid.setTame(false);
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
        return Component.literal(sb.toString());
    }
}
