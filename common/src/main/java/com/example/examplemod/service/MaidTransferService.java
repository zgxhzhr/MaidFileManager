package com.example.examplemod.service;

import com.example.examplemod.Constants;
import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidFileIo;
import com.example.examplemod.data.MaidInfo;
import com.example.examplemod.data.NbtMigration;
import com.example.examplemod.data.NbtVersion;
import com.example.examplemod.platform.Services;
import com.github.tartaricacid.touhoulittlemaid.entity.info.ServerCustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.LocalDateTime;
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
 *       这样导入后是完整 TLM 女仆，卸载本模组后不影响</li>
 *   <li>导出时清空背包内物品，但保留背包类型；强制工作状态为空闲；强制位置状态为站立</li>
 *   <li>导出落盘到 {@code maid_exports/}，文件名 {@code 中文名_时间_短UUID.maid}</li>
 *   <li>导入源目录为 {@code maid_imports/}（用户自行把 .maid 放到这里）</li>
 *   <li>导入时在玩家前方 {@value Constants#IMPORT_SPAWN_DISTANCE} 格寻找安全位置生成（避免卡在地下/墙里）</li>
 *   <li>主人匹配优先级：UUID &gt; 名字 &gt; 视为未驯服（用户可用蛋糕重新驯服）</li>
 * </ul>
 */
public final class MaidTransferService {
    /** 查找附近女仆的范围（半径，格） */
    private static final double SEARCH_RADIUS = 32.0;
    private static final int MAID_SEARCH_LIMIT = 64;
    /** 导入时向上查找安全位置的最大格数 */
    private static final int SPAWN_SAFE_MAX_UP = 8;

    /** 女仆最大血量（对应好感度等级3：80），超过则截断 */
    private static final double MAID_MAX_HEALTH = 80.0D;
    /** 女仆默认攻击伤害（对应好感度等级0：2），异常时回退到此值 */
    private static final double MAID_DEFAULT_ATTACK_DAMAGE = 2.0D;
    /** 攻击伤害合法上限（防止异常大值） */
    private static final double MAID_MAX_ATTACK_DAMAGE = 1024.0D;

    private MaidTransferService() {
    }

    /**
     * 通过反射从 Level 获取 RegistryAccess（MC 1.20.1+ 才有 Level.registryAccess()，MC 1.20 没有）。
     * 如果当前运行时没有 RegistryAccess 类或 Level 没有该方法，返回 null。
     */
    private static Object getRegistryAccess(Level level) {
        if (level == null) {
            return null;
        }
        try {
            Class<?> raClass = Class.forName("net.minecraft.core.RegistryAccess");
            try {
                java.lang.reflect.Method m = level.getClass().getMethod("registryAccess");
                Object ra = m.invoke(level);
                if (raClass.isInstance(ra)) {
                    return ra;
                }
            } catch (NoSuchMethodException ignored) {
                // MC 1.20 没有 Level.registryAccess()
            }
        } catch (ClassNotFoundException ignored) {
            // 运行时（MC 1.20）没有 RegistryAccess 类
        } catch (Throwable t) {
            Constants.LOG.debug("[maid_file_manager] getRegistryAccess failed: {}", t.toString());
        }
        return null;
    }

    /**
     * 反射双版本调用 EntityMaid.saveWithoutId：
     * 优先 MC 1.20.1+ 签名 saveWithoutId(RegistryAccess, CompoundTag)，
     * 失败回退 MC 1.20 签名 saveWithoutId(CompoundTag)。
     */
    private static CompoundTag invokeSaveWithoutId(EntityMaid maid, Object registryAccess, CompoundTag tag) {
        // 先试带 RegistryAccess 的 1.20.1+ 签名
        if (registryAccess != null) {
            try {
                Class<?> raClass = Class.forName("net.minecraft.core.RegistryAccess");
                java.lang.reflect.Method m = EntityMaid.class.getMethod("saveWithoutId", raClass, CompoundTag.class);
                Object result = m.invoke(maid, registryAccess, tag);
                if (result instanceof CompoundTag ct) {
                    return ct;
                }
            } catch (ClassNotFoundException ignored) {
                // fallthrough
            } catch (NoSuchMethodException ignored) {
                // fallthrough
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] invokeSaveWithoutId(RegistryAccess) failed, try fallback: {}",
                        t.toString());
            }
        }
        // 回退：单参 CompoundTag（MC 1.20 签名）
        return maid.saveWithoutId(tag);
    }

    /**
     * 反射双版本调用 EntityMaid.load：
     * 优先 MC 1.20.1+ 签名 load(RegistryAccess, CompoundTag)，
     * 失败回退 MC 1.20 签名 load(CompoundTag)。
     */
    private static void invokeLoadMaid(EntityMaid maid, Object registryAccess, CompoundTag tag) {
        if (registryAccess != null) {
            try {
                Class<?> raClass = Class.forName("net.minecraft.core.RegistryAccess");
                java.lang.reflect.Method m = EntityMaid.class.getMethod("load", raClass, CompoundTag.class);
                m.invoke(maid, registryAccess, tag);
                return;
            } catch (ClassNotFoundException ignored) {
                // fallthrough
            } catch (NoSuchMethodException ignored) {
                // fallthrough
            } catch (java.lang.reflect.InvocationTargetException ite) {
                // 真实执行异常，不是"方法不存在"，重新抛出保留根因
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error er) {
                    throw er;
                }
                throw new RuntimeException("load(RegistryAccess, CompoundTag) failed", cause);
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] invokeLoadMaid(RegistryAccess) failed, try fallback: {}",
                        t.toString());
            }
        }
        // 回退：单参 CompoundTag（MC 1.20 签名）
        maid.load(tag);
    }

    /**
     * 导入时校验并修正女仆属性：
     * <ul>
     *   <li>血量上限超过 80 时截断到 80</li>
     *   <li>攻击伤害异常（<=0 或过大）时回退到默认值 2</li>
     * </ul>
     */
    private static void validateMaidAttributes(EntityMaid maid) {
        // 校验血量上限
        double maxHealth = maid.getAttributeBaseValue(Attributes.MAX_HEALTH);
        if (maxHealth > MAID_MAX_HEALTH) {
            Constants.LOG.warn("[maid_file_manager] 导入女仆血量上限 {} 超过限制 {}, 截断到 {}",
                    maxHealth, MAID_MAX_HEALTH, MAID_MAX_HEALTH);
            maid.getAttribute(Attributes.MAX_HEALTH).setBaseValue(MAID_MAX_HEALTH);
            if (maid.getHealth() > MAID_MAX_HEALTH) {
                maid.setHealth((float) MAID_MAX_HEALTH);
            }
        }

        // 校验攻击伤害
        double attackDamage = maid.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        if (attackDamage <= 0 || attackDamage > MAID_MAX_ATTACK_DAMAGE) {
            Constants.LOG.warn("[maid_file_manager] 导入女仆攻击伤害 {} 异常, 回退到默认值 {}",
                    attackDamage, MAID_DEFAULT_ATTACK_DAMAGE);
            maid.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(MAID_DEFAULT_ATTACK_DAMAGE);
        }
    }

    private static void rebuildAttributesAndModel(EntityMaid maid, MaidFileData data) {
        int favorability = maid.getFavorability();

        int level;
        if (favorability < 64) {
            level = 0;
        } else if (favorability < 192) {
            level = 1;
        } else if (favorability < 384) {
            level = 2;
        } else {
            level = 3;
        }

        int healthByLevel = switch (level) {
            case 1 -> 30;
            case 2 -> 40;
            case 3 -> 80;
            default -> 20;
        };
        int attackByLevel = switch (level) {
            case 1 -> 3;
            case 2 -> 5;
            case 3 -> 7;
            default -> 2;
        };
        if (maid.isStruckByLightning()) {
            healthByLevel += 20;
        }

        net.minecraft.world.entity.ai.attributes.AttributeInstance health = maid.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(healthByLevel);
            if (maid.getHealth() > maid.getMaxHealth()) {
                maid.setHealth(maid.getMaxHealth());
            }
        }
        net.minecraft.world.entity.ai.attributes.AttributeInstance attack = maid.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attackByLevel);
        }

        Constants.LOG.info("[maid_file_manager] rebuildAttributes: favorability={} level={} health={} attack={}",
                favorability, level, healthByLevel, attackByLevel);

        if (data != null && data.getModelId() != null && !data.getModelId().isEmpty()) {
            String currentModel = maid.getModelId();
            if (!data.getModelId().equals(currentModel)) {
                maid.setModelId(data.getModelId());
                Constants.LOG.info("[maid_file_manager] restore modelId from MaidFileData: {} -> {}",
                        currentModel, data.getModelId());
            }
        }
    }

    /**
     * 列出附近属于指定玩家的女仆（玩家自己的女仆）。
     * 仅返回已驯服且 owner 匹配的女仆。
     */
    public static List<MaidInfo> listOwnMaids(ServerPlayer player) {
        Level level = player.level();
        Vec3 pos = player.position();
        AABB box = AABB.ofSize(pos, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
        List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class, box, m -> m.isOwnedBy(player));
        List<MaidInfo> result = new ArrayList<>();
        for (EntityMaid maid : maids) {
            if (result.size() >= MAID_SEARCH_LIMIT) {
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
        float maxHealth = (float) maid.getAttribute(Attributes.MAX_HEALTH).getValue();
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

    /**
     * 通过 TLM 服务端模型注册表获取模型显示名（中文名）。
     * 处理 GeckoLib 模型的 lang key（{@code {model.xxx.name}}）格式。
     * 找不到时回退到 modelId 的路径段。
     */
    public static String getDisplayName(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "未知模型";
        }
        try {
            Optional<?> infoOpt = ServerCustomPackLoader.SERVER_MAID_MODELS.getInfo(modelId);
            if (infoOpt.isPresent()) {
                Object info = infoOpt.get();
                try {
                    java.lang.reflect.Method m = info.getClass().getMethod("getName");
                    Object name = m.invoke(info);
                    if (name instanceof String s && !s.isEmpty()) {
                        // 处理 GeckoLib lang key：形如 {model.geckolib.winefox_nine_tailed.name}
                        if (s.startsWith("{") && s.endsWith("}")) {
                            String key = s.substring(1, s.length() - 1);
                            Constants.LOG.info("[maid_file_manager] getDisplayName: resolving lang key={}", key);
                            // 尝试用 Component.translatable 解析（集成服务器可用）
                            try {
                                net.minecraft.network.chat.Component translated =
                                        net.minecraft.network.chat.Component.translatable(key);
                                String result = translated.getString();
                                // 如果翻译结果不等于 key（说明找到了翻译），使用它
                                if (result != null && !result.equals(key) && !result.isEmpty()) {
                                    Constants.LOG.info("[maid_file_manager] getDisplayName: resolved={}", result);
                                    return result;
                                }
                            } catch (Exception e) {
                                Constants.LOG.debug("[maid_file_manager] getDisplayName: translation failed for {}", key);
                            }
                            // 翻译失败，用 modelId 路径段作为回退
                            Constants.LOG.info("[maid_file_manager] getDisplayName: using modelId fallback for lang key {}", key);
                            return fallbackNameFromModelId(modelId);
                        }
                        return s;
                    }
                } catch (NoSuchMethodException ignored) {
                    // fallthrough
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] getDisplayName failed for {}: {}", modelId, t.toString());
        }
        return fallbackNameFromModelId(modelId);
    }

    /** 从 modelId（如 touhou_little_maid:hakurei_reimu）回退出可读文件名 */
    private static String fallbackNameFromModelId(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "unknown_model";
        }
        int colon = modelId.indexOf(':');
        return colon >= 0 ? modelId.substring(colon + 1) : modelId;
    }

    /**
     * 导出指定女仆为 MaidFileData（不写文件，用于网络传输到客户端）。
     */
    public static MaidFileData exportMaidToData(ServerPlayer player, int entityId) {
        Constants.LOG.info("[maid_file_manager] exportMaidToData called: player={} entityId={}",
                player.getName().getString(), entityId);
        Entity entity = player.level().getEntity(entityId);
        if (!(entity instanceof EntityMaid maid)) {
            Constants.LOG.warn("[maid_file_manager] exportMaidToData: entityId={} is NOT EntityMaid", entityId);
            return null;
        }
        if (!maid.isOwnedBy(player)) {
            Constants.LOG.warn("[maid_file_manager] exportMaidToData: maid not owned by player");
            return null;
        }
        try {
            String modelId = maid.getModelId();
            Object registryAccess = getRegistryAccess(maid.level());
            CompoundTag fullNbt = invokeSaveWithoutId(maid, registryAccess, new CompoundTag());
            clearInventoryItems(fullNbt, EntityMaid.MAID_INVENTORY_TAG);
            clearInventoryItems(fullNbt, EntityMaid.MAID_BAUBLE_INVENTORY_TAG);
            clearInventoryItems(fullNbt, EntityMaid.MAID_HIDE_INVENTORY_TAG);
            clearInventoryItems(fullNbt, EntityMaid.MAID_TASK_INVENTORY_TAG);
            clearHandItems(fullNbt);
            fullNbt.remove("MaidBackpackData");
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
            data.setSourceMcVersion(Services.PLATFORM.getMcVersion());
            data.setDataVersion(NbtVersion.currentRuntime());
            data.setSourceTlmVersion(Services.PLATFORM.getModVersion("touhou_little_maid"));
            data.setTamed(tamed);
            data.setOwnerUuid(ownerUuid);
            data.setOwnerName(ownerName);
            data.setData(fullNbt);
            data.setModelId(modelId);
            data.setDisplayName(getDisplayName(modelId));

            Constants.LOG.info("[maid_file_manager] exportMaidToData: SUCCESS modelId={}", modelId);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] exportMaidToData FAILED: entityId={}", entityId, e);
            return null;
        }
    }

    /**
     * 从 MaidFileData 导入女仆到玩家附近的安全位置。
     */
    public static Component importMaidFromData(ServerPlayer player, MaidFileData data) {
        if (data == null || data.getData() == null) {
            return Component.translatable("maid_file_manager.import.fail.exception", "invalid maid data");
        }

        Level level = player.level();
        EntityMaid maid = new EntityMaid(level);
        Object registryAccess = getRegistryAccess(level);
        try {
            int sourceVersion = data.getDataVersion() > 0
                    ? data.getDataVersion()
                    : NbtVersion.fromMcVersion(data.getSourceMcVersion());
            int targetVersion = NbtVersion.currentRuntime();
            CompoundTag migratedData = NbtMigration.migrate(data.getData(), sourceVersion, targetVersion);
            invokeLoadMaid(maid, registryAccess, migratedData);
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] 导入女仆时加载 NBT 失败", e);
            return Component.translatable("maid_file_manager.import.fail.exception", e.getMessage());
        }

        rebuildAttributesAndModel(maid, data);
        validateMaidAttributes(maid);
        // 终极保险：rebuildAttributes 后强制 setHealth(maxHealth)，
        // 防止 load 阶段读到空 Health 或跨版本 DeathTime 残留导致 maid.isAlive()=false → 不存盘
        float fMax = maid.getMaxHealth();
        if (fMax > 0 && (maid.getHealth() <= 0 || maid.getHealth() > fMax)) {
            maid.setHealth(fMax);
        }
        maid.hurtTime = 0;
        maid.deathTime = 0;
        maid.clearFire();
        maid.setTicksFrozen(0);
        // 关键修复：SchedulePos.save() 对 workPos/idlePos/sleepPos 不判 null，
        // 跨版本如果读到 Optional.empty().orElse(null) → 存档阶段 NPE → EntityStorage
        // 明确拒绝持久化这个实体（重进就消失）。显式设置为当前生成位置。
        ensureSchedulePosNonNull(maid);
        Constants.LOG.info("[maid_file_manager] post-load state: isAlive={}, health={}/{}, uuid={}",
                maid.isAlive(), maid.getHealth(), fMax, maid.getUUID());

        Vec3 forward = player.getLookAngle().scale(Constants.IMPORT_SPAWN_DISTANCE);
        Vec3 basePos = player.position().add(forward);
        BlockPos safePos = findSafeSpawnPos(level,
                new BlockPos((int) basePos.x, (int) basePos.y, (int) basePos.z));
        if (safePos == null) {
            safePos = new BlockPos(player.blockPosition().above());
        }
        Constants.LOG.info("[maid_file_manager] importMaidFromData: spawnPos={}", safePos);
        maid.setPos(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        maid.setTask(TaskManager.getIdleTask());
        maid.setInSittingPose(false);
        maid.setOrderedToSit(false);
        boolean ownerMatched = matchOwner(maid, player, data);
        ensurePersistence(maid);
        if (!level.addFreshEntity(maid)) {
            return Component.translatable("maid_file_manager.import.fail.add_entity");
        }
        // addFreshEntity 成功后登记到 TLM 的全局 MaidWorldData（主人匹配的才登记），
        // 避免服务端认为这是个"未登记的野女仆"导致重进时被清
        registerMaidWorldData(maid);
        return ownerMatched
                ? Component.translatable("maid_file_manager.import.success")
                : Component.translatable("maid_file_manager.import.fail.not_found_owner");
    }

    /**
     * 从 .maid 文件导入女仆到玩家附近的安全位置。
     *
     * @return 导入结果消息（直接发给玩家）
     */
    public static Component importMaid(ServerPlayer player, String fileName) {
        Path gameDir = Services.PLATFORM.getGameDir().toAbsolutePath();
        Path dir = MaidFileIo.ensureImportsDir(gameDir);
        Path file = dir.resolve(fileName).normalize();
        // 路径越界检查，防止 ../ 逃逸
        if (!file.startsWith(dir)) {
            return Component.translatable("maid_file_manager.import.fail.exception", "invalid path");
        }
        if (!java.nio.file.Files.exists(file)) {
            return Component.translatable("maid_file_manager.import.fail.exception", "file not found");
        }
        MaidFileData data = MaidFileIo.readMaidFile(file);
        if (data == null || data.getData() == null) {
            return Component.translatable("maid_file_manager.import.fail.exception", "invalid maid file");
        }

        Level level = player.level();
        EntityMaid maid = new EntityMaid(level);
        Object registryAccess = getRegistryAccess(level);
        try {
            int sourceVersion = data.getDataVersion() > 0
                    ? data.getDataVersion()
                    : NbtVersion.fromMcVersion(data.getSourceMcVersion());
            int targetVersion = NbtVersion.currentRuntime();
            CompoundTag migratedData = NbtMigration.migrate(data.getData(), sourceVersion, targetVersion);
            invokeLoadMaid(maid, registryAccess, migratedData);
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] 导入女仆时加载 NBT 失败", e);
            return Component.translatable("maid_file_manager.import.fail.exception", e.getMessage());
        }

        rebuildAttributesAndModel(maid, data);
        validateMaidAttributes(maid);
        // 终极保险：rebuildAttributes 后强制 setHealth(maxHealth)，
        // 防止 load 阶段读到空 Health 或跨版本 DeathTime 残留导致 maid.isAlive()=false → 不存盘
        float fMax = maid.getMaxHealth();
        if (fMax > 0 && (maid.getHealth() <= 0 || maid.getHealth() > fMax)) {
            maid.setHealth(fMax);
        }
        maid.hurtTime = 0;
        maid.deathTime = 0;
        maid.clearFire();
        maid.setTicksFrozen(0);
        // 关键修复：SchedulePos.save() 对 workPos/idlePos/sleepPos 不判 null，
        // 跨版本如果读到 Optional.empty().orElse(null) → 存档阶段 NPE → EntityStorage
        // 明确拒绝持久化这个实体（重进就消失）。显式设置为当前生成位置。
        ensureSchedulePosNonNull(maid);
        Constants.LOG.info("[maid_file_manager] post-load state: isAlive={}, health={}/{}, uuid={}",
                maid.isAlive(), maid.getHealth(), fMax, maid.getUUID());

        // 计算生成位置：玩家前方 IMPORT_SPAWN_DISTANCE 格，向上找安全位置
        Vec3 forward = player.getLookAngle().scale(Constants.IMPORT_SPAWN_DISTANCE);
        Vec3 basePos = player.position().add(forward);
        BlockPos safePos = findSafeSpawnPos(level,
                new BlockPos((int) basePos.x, (int) basePos.y, (int) basePos.z));
        if (safePos == null) {
            // 找不到安全位置，回退到玩家正上方一格
            safePos = new BlockPos(player.blockPosition().above());
            Constants.LOG.warn("[maid_file_manager] importMaid: 未找到安全位置，回退到玩家上方 {}", safePos);
        }
        Constants.LOG.info("[maid_file_manager] importMaid: file={} spawnPos={}", fileName, safePos);
        // 实体放在方块中心稍上方，避免卡进方块
        maid.setPos(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        maid.setTask(TaskManager.getIdleTask());
        maid.setInSittingPose(false);
        maid.setOrderedToSit(false);
        // 处理主人匹配
        boolean ownerMatched = matchOwner(maid, player, data);
        ensurePersistence(maid);
        if (!level.addFreshEntity(maid)) {
            return Component.translatable("maid_file_manager.import.fail.add_entity");
        }
        registerMaidWorldData(maid);
        return ownerMatched
                ? Component.translatable("maid_file_manager.import.success")
                : Component.translatable("maid_file_manager.import.fail.not_found_owner");
    }

    /**
     * 登记女仆到 TLM 全局 MaidWorldData（owner 匹配时才登记）。
     */
    private static void registerMaidWorldData(EntityMaid maid) {
        try {
            if (maid.getOwnerUUID() != null) {
                MaidWorldData data = MaidWorldData.get(maid.level());
                if (data != null) {
                    data.addInfo(maid);
                    Constants.LOG.info("[maid_file_manager] registered MaidWorldData: maidUuid={}, ownerUuid={}",
                            maid.getUUID(), maid.getOwnerUUID());
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] registerMaidWorldData failed: {}", t.toString());
        }
    }

    /**
     * 确保 EntityMaid.schedulePos 的 3 个 BlockPos 字段不为 null。
     * <p>
     * Forge 1.20.1 / NeoForge 1.21 SchedulePos.save() 在保存阶段均不做 null 判断，
     * NbtUtils.writeBlockPos(null) → NPE → EntityStorage 捕获异常后：
     * "It WILL NOT PERSIST" → 重进世界女仆消失。
     */
    private static void ensureSchedulePosNonNull(EntityMaid maid) {
        try {
            BlockPos safePos = maid.blockPosition();
            var schedulePos = maid.getSchedulePos();
            schedulePos.setWorkPos(safePos);
            schedulePos.setIdlePos(safePos);
            schedulePos.setSleepPos(safePos);
            schedulePos.setDimension(maid.level().dimension().location());
            schedulePos.setConfigured(false);
            Constants.LOG.info("[maid_file_manager] ensureSchedulePosNonNull ok at {}, dim={}",
                    safePos, maid.level().dimension().location());
        } catch (Throwable t) {
            try {
                BlockPos safePos = maid.blockPosition();
                var schedulePos = maid.getSchedulePos();
                for (String field : new String[]{"workPos", "idlePos", "sleepPos"}) {
                    try {
                        var f = schedulePos.getClass().getDeclaredField(field);
                        f.setAccessible(true);
                        f.set(schedulePos, safePos);
                    } catch (Throwable ignored) {}
                }
                try {
                    var fDim = schedulePos.getClass().getDeclaredField("dimension");
                    fDim.setAccessible(true);
                    fDim.set(schedulePos, maid.level().dimension().location());
                } catch (Throwable ignored) {}
                try {
                    var fCfg = schedulePos.getClass().getDeclaredField("configured");
                    fCfg.setAccessible(true);
                    fCfg.set(schedulePos, false);
                } catch (Throwable ignored) {}
                Constants.LOG.info("[maid_file_manager] ensureSchedulePosNonNull (reflect) ok at {}", safePos);
            } catch (Throwable t2) {
                Constants.LOG.warn("[maid_file_manager] ensureSchedulePosNonNull BOTH FAILED: {} / {}",
                        t.toString(), t2.toString());
            }
        }
    }

    /**
     * 双保险：确保女仆被 Minecraft 判定为"持久化实体"（保存到存档）。
     * 如果 PersistenceRequired 丢失，玩家重进世界时会被 silently 删除。
     * <p>
     * API 注：Forge 1.20.1 Mob.setPersistenceRequired() 是<strong>无参</strong>方法，
     * 对应字段在 Entity 基类中设为 true；我们直接调用即可。
     */
    private static void ensurePersistence(EntityMaid maid) {
        maid.setPersistenceRequired();
        Constants.LOG.info("[maid_file_manager] ensurePersistence: tame={}, persistenceRequired={}",
                maid.isTame(), maid.isPersistenceRequired());
    }

    /**
     * 在指定坐标附近向上找一个"能站立的"安全位置（脚下方块为实心，脚部与头部为空气）。
     * 找不到时返回 null。
     */
    private static BlockPos findSafeSpawnPos(Level level, BlockPos start) {
        // 从起始位置向下两格向上扫描，确保能找到地板
        for (int dy = -2; dy <= SPAWN_SAFE_MAX_UP; dy++) {
            BlockPos feet = start.offset(0, dy, 0);
            BlockPos head = feet.above();
            BlockState feetBlock = level.getBlockState(feet);
            BlockState headBlock = level.getBlockState(head);
            // 脚下方块需要碰撞（实心或可碰撞），脚部/头部需要是空气或可替换
            if (feetBlock.isCollisionShapeFullBlock(level, feet)
                    && headBlock.isAir()
                    && level.getBlockState(head.above()).isAir()) {
                return feet.above(); // 实际站在 feet 的上一格
            }
        }
        return null;
    }

    /** 列出可导入的 .maid 文件名（按时间降序） */
    public static List<String> listImportableFiles(ServerPlayer player) {
        Path gameDir = Services.PLATFORM.getGameDir().toAbsolutePath();
        Path dir = MaidFileIo.ensureImportsDir(gameDir);
        return MaidFileIo.listMaidFiles(dir);
    }

    /**
     * 主人匹配：
     * <ol>
     *   <li>优先匹配 ownerUuid，若当前玩家 UUID 与之相同，则设置 owner 为当前玩家</li>
     *   <li>否则按 ownerName 匹配，若服务器中存在同名玩家，则设置 owner 为该玩家</li>
     *   <li>都不匹配则视为未驯服，玩家可用蛋糕重新驯服</li>
     * </ol>
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
                PlayerList playerList = player.server.getPlayerList();
                ServerPlayer owner = playerList.getPlayer(uuid);
                if (owner != null) {
                    maid.setOwnerUUID(uuid);
                    maid.setTame(true);
                    return true;
                }
            } catch (IllegalArgumentException e) {
                Constants.LOG.warn("[maid_file_manager] 无效的 owner UUID: {}", data.getOwnerUuid());
            }
        }
        if (data.getOwnerName() != null && !data.getOwnerName().isEmpty()) {
            PlayerList playerList = player.server.getPlayerList();
            ServerPlayer owner = playerList.getPlayerByName(data.getOwnerName());
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
     * 清空物品栏的物品（保留 Size），用于导出时去除背包内容。
     */
    private static void clearInventoryItems(CompoundTag root, String key) {
        if (!root.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag invTag = root.getCompound(key);
        invTag.put("Items", new ListTag());
    }

    /**
     * 清空主手和副手物品，防止恶意携带物品。
     * HandItems 是一个 ListTag，包含 2 个 ItemStack（主手、副手）。
     */
    private static void clearHandItems(CompoundTag root) {
        if (!root.contains("HandItems", Tag.TAG_LIST)) {
            return;
        }
        ListTag handItems = root.getList("HandItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < handItems.size(); i++) {
            CompoundTag empty = new CompoundTag();
            empty.putString("id", "minecraft:air");
            empty.putInt("count", 0);
            handItems.set(i, empty);
        }
    }

    public static Logger logger() {
        return Constants.LOG;
    }
}
