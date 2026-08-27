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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>导入源目录为 {@code maid_imports/}（自行把 .maid 放到这里）</li>
 *   <li>导入时在玩家前方 {@value Constants#IMPORT_SPAWN_DISTANCE} 格寻找安全位置生成（避免卡在地下/墙里）</li>
 *   <li>主人匹配优先级：UUID > 名字 > 视为未驯服（可用蛋糕重新驯服）</li>
 * </ul>
 */
public final class MaidTransferService {
    private static final double SEARCH_RADIUS = 32.0;
    private static final int MAID_SEARCH_LIMIT = 64;
    private static final int SPAWN_SAFE_MAX_UP = 8;
    private static final double MAID_MAX_HEALTH = 80.0D;
    private static final double MAID_DEFAULT_ATTACK_DAMAGE = 2.0D;
    private static final double MAID_MAX_ATTACK_DAMAGE = 1024.0D;

    // 监控已导入实体的状态，用于追踪实体是否被意外移除
    // 使用实体引用而不是 UUID，避免 UUID 查找可能的问题
    private static final Map<EntityMaid, Long> monitoredMaids = new ConcurrentHashMap<>();
    private static int tickCounter = 0;
    private static boolean tickListenerRegistered = false;

    private MaidTransferService() {
    }

    /**
     * 获取 RegistryAccess：先试 Level.registryAccess()（1.20.1+），
     * 回退 RegistryAccess.FROZEN（1.20 也有）。
     */
    private static Object getRegistryAccess(Level level) {
        if (level != null) {
            try {
                Class<?> raClass = Class.forName("net.minecraft.core.RegistryAccess");
                java.lang.reflect.Method m = level.getClass().getMethod("registryAccess");
                Object ra = m.invoke(level);
                if (raClass.isInstance(ra)) {
                    return ra;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                Constants.LOG.debug("[maid_file_manager] getRegistryAccess(level) failed: {}", t.toString());
            }
        }
        try {
            Class<?> raClass = Class.forName("net.minecraft.core.RegistryAccess");
            java.lang.reflect.Field frozenField = raClass.getDeclaredField("FROZEN");
            frozenField.setAccessible(true);
            Object frozen = frozenField.get(null);
            if (frozen != null) {
                Constants.LOG.info("[maid_file_manager] using RegistryAccess.FROZEN as fallback");
                return frozen;
            }
        } catch (ClassNotFoundException ignored) {
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] getRegistryAccess(FROZEN) failed: {}", t.toString());
        }
        return null;
    }

    /**
     * 调用 EntityMaid.saveWithoutId：优先 2 参（1.20.1+），回退 1 参（1.20）。
     */
    private static CompoundTag invokeSaveWithoutId(EntityMaid maid, Object registryAccess, CompoundTag tag) {
        if (registryAccess != null) {
            try {
                Class<?> raClass = Class.forName("net.minecraft.core.RegistryAccess");
                java.lang.reflect.Method m = EntityMaid.class.getMethod("saveWithoutId", raClass, CompoundTag.class);
                Object result = m.invoke(maid, registryAccess, tag);
                if (result instanceof CompoundTag ct) {
                    Constants.LOG.info("[maid_file_manager] saveWithoutId via RegistryAccess succeeded");
                    return ct;
                }
            } catch (ClassNotFoundException ignored) {
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] invokeSaveWithoutId(RegistryAccess) failed: {}, try fallback", t.toString());
            }
        }
        CompoundTag result = maid.saveWithoutId(tag);
        Constants.LOG.info("[maid_file_manager] saveWithoutId(CompoundTag) succeeded");
        return result;
    }

    /**
     * 调用 EntityMaid.load(tag) 从 NBT 恢复实体。
     * <p>
     * 关键策略：预处理 tag，移除 TLM 专属的 ListTag 避免数组越界，
     * 让 Entity.load(CompoundTag) 成功执行，然后再单独恢复 TLM 专属数据。
     */
    private static void invokeLoadMaid(EntityMaid maid, Object registryAccess, CompoundTag tag) {
        Constants.LOG.info("[maid_file_manager] invokeLoadMaid: tag keys={}", tag.getAllKeys());

        // 从原始传入 tag 先读出 StruckByLightning 渡劫标记，后续两条路径都用它强制同步
        // （注意：extractTlmData 会移除 tag 里的键，所以必须在 extract 之前读好）
        boolean tagStruckByLightning = false;
        if (tag.contains("StruckByLightning", Tag.TAG_BYTE)) {
            tagStruckByLightning = tag.getBoolean("StruckByLightning");
            Constants.LOG.info("[maid_file_manager] invokeLoadMaid: source StruckByLightning from tag: {}", tagStruckByLightning);
        }

        // 预初始化基础字段
        preInitBaseFields(maid, tag);

        // 修复 BaubleItemHandler 数组大小（运行时 TLM 1.2.1 只有 9 槽，数据有 10 槽）
        fixBaubleItemHandler(maid);

        // 预处理 tag：保存 TLM 专属数据并移除，避免数组越界
        CompoundTag tlmTag = extractTlmData(tag);
        Constants.LOG.info("[maid_file_manager] Preprocessed tag: removed TLM keys, remaining={}", tag.getAllKeys());

        // 尝试 1: 直接调用 maid.load(tag) —— TLM override（tag 已预处理）
        try {
            Constants.LOG.info("[maid_file_manager] trying maid.load(preprocessedTag) ...");
            maid.load(tag);
            Constants.LOG.info("[maid_file_manager] maid.load(tag) succeeded");
            // 成功后恢复 TLM 专属数据
            restoreTlmData(maid, tlmTag);
            // 渡劫标记强制同步（路径 1）
            maid.setStruckByLightning(tagStruckByLightning);
            Constants.LOG.info("[maid_file_manager] invokeLoadMaid(path1): sync StruckByLightning -> {}", tagStruckByLightning);
            postLoadFixes(maid);
            return;
        } catch (Throwable t) {
            // 输出完整堆栈
            Constants.LOG.error("[maid_file_manager] maid.load(tag) failed with full stack:", t);
            // 解包 ReportedException 找到根因并输出完整堆栈
            Throwable cause = t;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            Constants.LOG.error("[maid_file_manager] ROOT CAUSE ({}): {}", cause.getClass().getName(), cause.getMessage());
            Constants.LOG.error("[maid_file_manager] ROOT CAUSE STACK:", cause);
        }

        // 尝试 2: 反射调用基类 Entity.load(TAG) —— 只处理基础数据
        Constants.LOG.info("[maid_file_manager] Attempting base Entity load via reflection...");
        tryLoadBaseEntity(maid, tag);

        // 恢复 TLM 专属数据
        restoreTlmData(maid, tlmTag);
        // 渡劫标记强制同步（路径 2 —— fallback 路径不会触发 TLM readAdditionalSaveData，必须手动同步）
        maid.setStruckByLightning(tagStruckByLightning);
        Constants.LOG.info("[maid_file_manager] invokeLoadMaid(path2 fallback): sync StruckByLightning -> {}", tagStruckByLightning);
        postLoadFixes(maid);

        // 验证实体状态
        Constants.LOG.info("[maid_file_manager] post-load: alive={}, health={}, uuid={}, struckByLightning={}",
                maid.isAlive(), maid.getHealth(), maid.getUUID(), maid.isStruckByLightning());
    }

    /**
     * 修复 BaubleItemHandler 数组大小。
     * 运行时 TLM 1.2.1 的 BaubleItemHandler 只有 9 个槽位，
     * 但导入的数据可能有 10 个槽位（来自 TLM 1.5.3）。
     * 通过反射扩展内部数组。
     */
    private static void fixBaubleItemHandler(EntityMaid maid) {
        try {
            // 查找 EntityMaid 中的 BaubleItemHandler 字段
            Class<?> baubleHandlerClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.inventory.handler.BaubleItemHandler");

            for (java.lang.reflect.Field f : EntityMaid.class.getDeclaredFields()) {
                if (baubleHandlerClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object handler = f.get(maid);
                    if (handler != null) {
                        Constants.LOG.info("[maid_file_manager] Found BaubleItemHandler: {}.{}", EntityMaid.class.getSimpleName(), f.getName());
                        // 扩展 handler 内部数组
                        expandHandlerArray(handler, baubleHandlerClass);
                    }
                }
            }

            // 也搜索父类字段
            Class<?> searchClass = EntityMaid.class.getSuperclass();
            while (searchClass != null && searchClass != Object.class) {
                for (java.lang.reflect.Field f : searchClass.getDeclaredFields()) {
                    if (baubleHandlerClass.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object handler = f.get(maid);
                        if (handler != null) {
                            Constants.LOG.info("[maid_file_manager] Found BaubleItemHandler in superclass: {}.{}", searchClass.getSimpleName(), f.getName());
                            expandHandlerArray(handler, baubleHandlerClass);
                        }
                    }
                }
                searchClass = searchClass.getSuperclass();
            }
        } catch (Exception e) {
            Constants.LOG.warn("[maid_file_manager] fixBaubleItemHandler failed: {}", e.toString());
        }
    }

    /**
     * 扩展 ItemHandler 的内部数组大小。
     * 查找所有数组字段，如果数组长度 < 10，则扩展到 10。
     */
    private static void expandHandlerArray(Object handler, Class<?> handlerClass) {
        try {
            for (java.lang.reflect.Field f : handlerClass.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType().isArray()) {
                    Object array = f.get(handler);
                    if (array != null) {
                        int length = java.lang.reflect.Array.getLength(array);
                        Constants.LOG.info("[maid_file_manager] Found array field: {} (type={}, length={})", f.getName(), f.getType().getComponentType().getSimpleName(), length);

                        if (length < 10) {
                            // 扩展数组到 10
                            Class<?> componentType = f.getType().getComponentType();
                            Object newArray = java.lang.reflect.Array.newInstance(componentType, 10);
                            // 复制旧数据
                            System.arraycopy(array, 0, newArray, 0, length);
                            // 新位置保持 null（空槽位）
                            f.set(handler, newArray);
                            Constants.LOG.info("[maid_file_manager] Extended array {} from {} to 10", f.getName(), length);
                        }
                    }
                }
            }

            // 也检查父类
            Class<?> superClass = handlerClass.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                for (java.lang.reflect.Field f : superClass.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType().isArray()) {
                        Object array = f.get(handler);
                        if (array != null) {
                            int length = java.lang.reflect.Array.getLength(array);
                            Constants.LOG.info("[maid_file_manager] Found superclass array: {}.{} (length={})", superClass.getSimpleName(), f.getName(), length);

                            if (length < 10) {
                                Class<?> componentType = f.getType().getComponentType();
                                Object newArray = java.lang.reflect.Array.newInstance(componentType, 10);
                                System.arraycopy(array, 0, newArray, 0, length);
                                f.set(handler, newArray);
                                Constants.LOG.info("[maid_file_manager] Extended superclass array from {} to 10", length);
                            }
                        }
                    }
                }
                superClass = superClass.getSuperclass();
            }
        } catch (Exception e) {
            Constants.LOG.warn("[maid_file_manager] expandHandlerArray failed: {}", e.toString());
        }
    }

    /**
     * 从 tag 中移除会导致 maid.load 崩溃的复杂容器。
     * <p>
     * 设计原则（极重要）：
     * - 只移除会触发 BaubleItemHandler.onContentsChanged 数组越界的**物品容器**
     *   和 SynchedEntityData/复杂结构容器（ListTag<CompoundTag> 存储 ItemStack）。
     * - 简单值字段（MaidFavorability / MaidExperience / MaidHunger 等 TAG_INT）
     *   一律**不移除**，留在 tag 里给 TLM 自己的 readAdditionalSaveData 正确还原。
     *   这是修复"导入 1.20 好感度归零"的关键。
     */
    private static CompoundTag extractTlmData(CompoundTag tag) {
        CompoundTag tlmData = new CompoundTag();

        // ---------- 第一类：物品容器（会导致 BaubleItemHandler 数组越界） ----------
        // BaubleItemHandler.setBaubleInSlot 是唯一的越界来源，根因是 9 槽 vs 10 槽
        // 这些都是 ListTag<CompoundTag>，存储 ItemStack，会引发 onContentsChanged 回调
        String[] inventoryContainers = {
                // TLM 专属饰品/背包（导致越界的直接原因）
                "MaidBaubleInventory",
                "MaidInventory",
                "MaidHideInventory",
                "MaidTaskInventory",
                // GameSkill 里也是物品列表
                "MaidGameSkillData",
                // 原版但会触发 SynchedEntityData 更新
                "HandItems",
                "ArmorItems",
        };

        // ---------- 第二类：会触发 NBT 结构不兼容的复杂 CompoundTag 容器 ----------
        // 跨版本（TLM 1.5.3 数据 → TLM 1.2.1 解析）时这些 CompoundTag 结构可能不一致
        // 为了确保 maid.load 不抛异常，先抽出来，之后再通过反射尽力恢复
        String[] complexStructures = {
                // 任务数据 Maps（复杂结构，ListTag 里有 compound，跨版本字段差异大）
                "MaidTaskDataMaps",
                // AI 对话数据
                "MaidAIChat",
                // 配置（MaidConfig 里有各种子结构）
                "MaidConfig",
                "MaidSubConfig",
                "MaidWorldData",
                // 背包数据（CompoundTag 物品）
                "MaidBackpackData",
                // 任务 CompoundTag
                "MaidTask",
                // 游戏记录
                "MaidGameRecord",
                "MaidKillRecord",
                // 日程位置
                "MaidSchedulePos",
                // YSM 扩展字段
                "YsmRoamingVars",
                // 行为 Brain
                "Brain",
        };

        // ---------- 第三类：ModelId / SoundPackId（String，安全起见保留在 tag 中） ----------
        // 这些是简单 String 值，不会导致任何崩溃。TLM 自己的 load 能正确解析。
        // 如果 maid.load(tag) 走成功路径，这些字段会被原生还原，不需要我们反射恢复。

        // 执行移除
        for (String key : inventoryContainers) {
            if (tag.contains(key)) {
                try {
                    tlmData.put(key, tag.get(key).copy());
                    tag.remove(key);
                    Constants.LOG.info("[maid_file_manager] Extracted INV: {}", key);
                } catch (Exception e) {
                    Constants.LOG.warn("[maid_file_manager] Failed to extract {}: {}", key, e.toString());
                }
            }
        }

        for (String key : complexStructures) {
            if (tag.contains(key)) {
                try {
                    tlmData.put(key, tag.get(key).copy());
                    tag.remove(key);
                    Constants.LOG.info("[maid_file_manager] Extracted CPLX: {}", key);
                } catch (Exception e) {
                    Constants.LOG.warn("[maid_file_manager] Failed to extract {}: {}", key, e.toString());
                }
            }
        }

        Constants.LOG.info("[maid_file_manager] Extraction done. remaining={}", tag.getAllKeys());
        return tlmData;
    }

    /**
     * 恢复 TLM 专属数据到实体。
     * <p>
     * 简单值字段（MaidFavorability / MaidExperience / MaidHunger / ModelId / SoundPackId 等）
     * 现在留在 tag 里由 TLM 自己的 readAdditionalSaveData 还原，不在这里处理。
     * 本方法只处理复杂容器的尽力恢复（例如 ModelId 兜底、Favorability 双重检查）。
     */
    private static void restoreTlmData(EntityMaid maid, CompoundTag tlmData) {
        Constants.LOG.info("[maid_file_manager] Restoring TLM data ({} keys)...", tlmData.getAllKeys().size());

        // ---------- ModelId 兜底：只有当 tag 里没写 ModelId 时才反射恢复 ----------
        // 如果 maid.load 成功，TLM 已经正确设置了 ModelId
        // 只有当 maid.load 失败走 tryLoadBaseEntity 路径，才需要这里手动恢复
        boolean modelIdAlreadySet = false;
        try {
            java.lang.reflect.Method getModelId = EntityMaid.class.getMethod("getModelId");
            String currentModelId = (String) getModelId.invoke(maid);
            if (currentModelId != null && !currentModelId.isEmpty()) {
                modelIdAlreadySet = true;
                Constants.LOG.debug("[maid_file_manager] ModelId already restored by TLM: {}", currentModelId);
            }
        } catch (Throwable ignored) {
        }

        if (!modelIdAlreadySet && tlmData.contains("ModelId", Tag.TAG_STRING)) {
            String modelId = tlmData.getString("ModelId");
            try {
                java.lang.reflect.Method setModelId = EntityMaid.class.getMethod("setModelId", String.class);
                setModelId.invoke(maid, modelId);
                Constants.LOG.info("[maid_file_manager] Restored ModelId (fallback): {}", modelId);
            } catch (Exception e) {
                try {
                    java.lang.reflect.Field modelIdField = EntityMaid.class.getDeclaredField("modelId");
                    modelIdField.setAccessible(true);
                    modelIdField.set(maid, modelId);
                    Constants.LOG.info("[maid_file_manager] Restored ModelId via field: {}", modelId);
                } catch (Exception ex) {
                    Constants.LOG.warn("[maid_file_manager] Cannot restore ModelId");
                }
            }
        }

        // ---------- Favorability 双重检查 ----------
        // 如果 maid.load(tag) 走成功路径，TLM 已经读了 MaidFavorability TAG_INT 设好值
        // 但这里再检查一次，防止走 fallback 路径或某些字段跨版本未识别导致 0
        int currentFavorability = 0;
        boolean favNeedFix = false;
        try {
            currentFavorability = maid.getFavorability();
            if (currentFavorability == 0) {
                // 可能 TLM 自己没读成功（走了 fallback base loading），尝试找 tag 里的 fav
                favNeedFix = true;
            }
        } catch (Throwable ignored) {
            favNeedFix = true;
        }

        if (favNeedFix) {
            // 尝试从 tlmData 里恢复 MaidFavorability（虽然 extractTlmData 不再抽走这个，
            // 但万一 tlmData 里有，或者 NbtMigration 删了再意外塞回来）
            int favToRestore = -1;
            if (tlmData.contains("MaidFavorability", Tag.TAG_INT)) {
                favToRestore = tlmData.getInt("MaidFavorability");
            }
            // 如果 tlmData 没有，也可以从传入的 tag 中检查
            // （注意：这里拿到的是已经被 extractTlmData 处理过的 tag，但 MaidFavorability 没被抽走）
            // 但因为这个方法只拿到 tlmData，所以我们通过实体类重新扫描
            if (favToRestore < 0) {
                favToRestore = 0;  // 保底 0
            }

            // 只有 favToRestore > 0 时才去设
            if (favToRestore > 0) {
                restoreFavorability(maid, favToRestore);
            } else {
                Constants.LOG.info("[maid_file_manager] MaidFavorability=0 (new maid / TLM self-loaded OK)");
            }
        } else {
            Constants.LOG.info("[maid_file_manager] MaidFavorability self-loaded OK: {}", currentFavorability);
        }

        // ---------- AI 对话数据：聊天历史（本体API）+ 人设8字段（反射强塞双保险） ----------
        // 实机验证（1.21.1→1.20.1）：聊天 MaidHistoryChat 能还原，人设 CustomSetting 过不去
        // 根因：MaidAIChatSerializable.readFromTag 对 tag.contains("MaidAIChat") 层级判空
        //       在跨版本 NBT 包装不一致时条件不通过，虽 tlmData 里有值但字段仍为空。
        // 修复策略：双路径
        //   (A) 仍调用 aiChatManager.readFromTag(tlmData) —— 还原聊天历史 / 摘要 / token
        //       （这部分验证成功，历史靠 MaidAIChatData override 的 readFromTag 独立读取）
        //   (B) 反射强塞 MaidAIChatSerializable 的 8 个公开 String 字段：
        //       llmSite / llmModel / ttsSite / ttsModel / ttsLanguage / chatLanguage / ownerName / customSetting
        //       键名兼容 CamelCase（TLM 1.20/1.21 本体常量）+ 小写驼峰双重兜底，
        //       只在源 NBT 有非空值时才覆盖，避免把已正确还原的值清空。
        try {
            boolean hasAiData = tlmData.contains("MaidAIChat", Tag.TAG_COMPOUND)
                    || tlmData.contains("MaidHistoryChat")
                    || tlmData.contains("MaidHistorySummary", Tag.TAG_STRING);
            if (hasAiData) {
                // ----- 路径 A：本体 API 还原聊天历史 -----
                maid.getAiChatManager().readFromTag(tlmData);
                int historyCount = -1;
                if (tlmData.contains("MaidHistoryChat")) {
                    historyCount = tlmData.getList("MaidHistoryChat", Tag.TAG_COMPOUND).size();
                }

                // ----- 路径 B：反射强塞人设 8 字段（双保险，不依赖本体 contains 判空层级） -----
                String customSettingBefore = "";
                String customSettingAfter = "";
                int forcedFields = 0;
                try {
                    Object serializable = maid.getAiChatManager();
                    Class<?> serialClass = Class.forName(
                            "com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable");
                    java.lang.reflect.Field csField = serialClass.getDeclaredField("customSetting");
                    csField.setAccessible(true);
                    Object csBeforeVal = csField.get(serializable);
                    if (csBeforeVal instanceof String s) {
                        customSettingBefore = s;
                    }
                    if (tlmData.contains("MaidAIChat", Tag.TAG_COMPOUND)) {
                        CompoundTag ai = tlmData.getCompound("MaidAIChat");
                        String[][] fieldMap = {
                                {"llmSite",       "LLMSite",       "llmSite"},
                                {"llmModel",      "LLMModel",      "llmModel"},
                                {"ttsSite",       "TTSSiteName",   "ttsSiteName"},
                                {"ttsModel",      "TTSModel",      "ttsModel"},
                                {"ttsLanguage",   "TTSLanguage",   "ttsLanguage"},
                                {"chatLanguage",  "ChatLanguage",  "chatLanguage"},
                                {"ownerName",     "OwnerName",     "ownerName"},
                                {"customSetting", "CustomSetting", "customSetting"},
                        };
                        for (String[] row : fieldMap) {
                            String javaField = row[0];
                            String camelKey  = row[1];
                            String lowerKey  = row[2];
                            String value = "";
                            if (ai.contains(camelKey, Tag.TAG_STRING)) {
                                value = ai.getString(camelKey);
                            } else if (ai.contains(lowerKey, Tag.TAG_STRING)) {
                                value = ai.getString(lowerKey);
                            }
                            if (value != null && !value.isEmpty()) {
                                try {
                                    java.lang.reflect.Field f = serialClass.getDeclaredField(javaField);
                                    f.setAccessible(true);
                                    f.set(serializable, value);
                                    forcedFields++;
                                    if ("customSetting".equals(javaField)) {
                                        customSettingAfter = value;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Constants.LOG.warn("[maid_file_manager] AI persona force-assign via reflection failed: {}", t.toString());
                }

                // ----- 汇总日志（三元组 + 反射强塞前后对比，1.21.1→1.20.1 排错专用） -----
                String settingReport;
                if (customSettingAfter != null && !customSettingAfter.isEmpty()) {
                    settingReport = "FORCE-ASSIGN len=" + customSettingAfter.length()
                            + " (before=" + (customSettingBefore == null ? 0 : customSettingBefore.length()) + ")";
                } else if (customSettingBefore != null && !customSettingBefore.isEmpty()) {
                    settingReport = "API-RESTORE len=" + customSettingBefore.length();
                } else {
                    settingReport = "NO";
                }
                Constants.LOG.info("[maid_file_manager] AI data restored: persona={}, historyMessages={}, hasSummary={}, forcedFields={}",
                        settingReport,
                        historyCount,
                        tlmData.contains("MaidHistorySummary", Tag.TAG_STRING) ? "YES" : "NO",
                        forcedFields);
            } else {
                Constants.LOG.info("[maid_file_manager] No AI dialog data in source (no persona / chat history to restore)");
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Failed to restore AI chat data (persona/history), skipped: {}", t.toString());
        }

        for (String key : tlmData.getAllKeys()) {
            Constants.LOG.debug("[maid_file_manager] TLM pending container: {}", key);
        }
    }

    /**
     * 恢复女仆好感度。多策略兜底：
     * 1. 调用 setFavorability(int) setter
     * 2. 反射设置 favorability 字段
     * 3. 反射设置 favorabilityManager 内部 counter 字段
     */
    private static void restoreFavorability(EntityMaid maid, int favorability) {
        Constants.LOG.info("[maid_file_manager] Restoring MaidFavorability: {}", favorability);
        // 策略 1: 调用 setter
        try {
            java.lang.reflect.Method setFav = EntityMaid.class.getMethod("setFavorability", int.class);
            setFav.invoke(maid, favorability);
            Constants.LOG.info("[maid_file_manager] Restored MaidFavorability via setter: {}", favorability);
            return;
        } catch (NoSuchMethodException nsme) {
            Constants.LOG.debug("[maid_file_manager] setFavorability not found, trying field fallback");
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] setFavorability failed: {}", t.toString());
        }
        // 策略 2: 反射设置 int 字段
        if (trySetIntField(maid, EntityMaid.class, "favorability", favorability)) return;
        // 策略 3: 反射设置 favorabilityManager 的 counter 字段
        try {
            for (java.lang.reflect.Field f : EntityMaid.class.getDeclaredFields()) {
                if (f.getName().toLowerCase().contains("favorability")
                        && f.getName().toLowerCase().contains("manager")) {
                    f.setAccessible(true);
                    Object manager = f.get(maid);
                    if (manager != null) {
                        // 在 manager 对象中查找 int counter 字段
                        for (java.lang.reflect.Field cf : manager.getClass().getDeclaredFields()) {
                            if (cf.getType() == int.class) {
                                cf.setAccessible(true);
                                cf.setInt(manager, favorability);
                                Constants.LOG.info("[maid_file_manager] Restored MaidFavorability via manager.{}: {}",
                                        cf.getName(), favorability);
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Cannot restore MaidFavorability via manager: {}", t.toString());
        }
        Constants.LOG.warn("[maid_file_manager] Cannot restore MaidFavorability (no method/field)");
    }

    /**
     * 通过反射在类层次中查找 int 字段并赋值。
     */
    private static boolean trySetIntField(Object target, Class<?> startClass, String fieldName, int value) {
        Class<?> clazz = startClass;
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    f.setInt(target, value);
                    Constants.LOG.info("[maid_file_manager] Set int field {}.{} = {}", clazz.getSimpleName(), fieldName, value);
                    return true;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] Failed to set field {}.{}: {}", clazz.getSimpleName(), fieldName, t.toString());
                return false;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * 通过反射在类层次中查找 setter 方法并调用。
     */
    private static boolean trySetIntViaSetter(Object target, Class<?> startClass, String methodName, int value) {
        Class<?> clazz = startClass;
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Method m = clazz.getDeclaredMethod(methodName, int.class);
                m.setAccessible(true);
                m.invoke(target, value);
                Constants.LOG.info("[maid_file_manager] Set via {}.{}({})", clazz.getSimpleName(), methodName, value);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Constants.LOG.warn("[maid_file_manager] Failed to call {}.{}: {}", clazz.getSimpleName(), methodName, t.toString());
                return false;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * 加载后的修复：设置持久化标志等。
     */
    private static void postLoadFixes(EntityMaid maid) {
        // 通过反射直接设置 persistenceRequired 字段
        try {
            setPersistenceDirect(maid, true);
            Constants.LOG.info("[maid_file_manager] Persistence set via direct field access");
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Failed to set persistence: {}", t.toString());
            // 回退：使用方法调用
            try {
                maid.setPersistenceRequired();
                Constants.LOG.info("[maid_file_manager] Persistence set via method call");
            } catch (Throwable t2) {
                Constants.LOG.warn("[maid_file_manager] Failed to set persistence via method: {}", t2.toString());
            }
        }
    }

    /**
     * 通过反射直接设置 Entity 的 persistenceRequired 字段。
     * Forge 1.20 中 setPersistenceRequired() 方法可能不生效。
     * persistenceRequired 定义在 Mob.class 中，需要遍历类层次查找。
     */
    private static void setPersistenceDirect(Entity entity, boolean persistent) throws Exception {
        // 遍历 Mob → LivingEntity → Entity 类层次
        Class<?>[] classHierarchy = {
                net.minecraft.world.entity.Mob.class,
                net.minecraft.world.entity.LivingEntity.class,
                Entity.class
        };

        for (Class<?> clazz : classHierarchy) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    String name = f.getName();
                    if (name.equals("persistenceRequired") || name.toLowerCase().contains("persistent")) {
                        f.setAccessible(true);
                        f.setBoolean(entity, persistent);
                        Constants.LOG.info("[maid_file_manager] Set {}.{} = {}", clazz.getSimpleName(), name, persistent);
                        return;
                    }
                }
            }
        }

        // 回退：遍历类层次中所有 boolean 字段
        for (Class<?> clazz : classHierarchy) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("save") || name.contains("chunk") || name.contains("removable")) {
                        f.setAccessible(true);
                        f.setBoolean(entity, persistent);
                        Constants.LOG.info("[maid_file_manager] Set {}.{} = {} (fallback)", clazz.getSimpleName(), f.getName(), persistent);
                        return;
                    }
                }
            }
        }

        throw new NoSuchFieldException("No persistence field found in Mob/LivingEntity/Entity hierarchy");
    }

    /**
     * 尝试加载基础实体数据（反射调用 Entity 的 load 方法）。
     * 如果失败则手动加载基础字段。
     */
    private static void tryLoadBaseEntity(EntityMaid maid, CompoundTag tag) {
        // 策略：遍历 Entity 类及其父类，查找接受 CompoundTag 的 load 方法
        try {
            Constants.LOG.info("[maid_file_manager] Trying to find Entity.load(CompoundTag) method...");

            // 查找所有接受 CompoundTag 的公共/私有方法
            java.lang.reflect.Method targetMethod = null;
            Class<?> currentClass = Entity.class;
            while (currentClass != null && currentClass != Object.class) {
                for (java.lang.reflect.Method m : currentClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == CompoundTag.class) {
                        String name = m.getName();
                        if (name.equals("load") || name.contains("load") || name.contains("Load")) {
                            targetMethod = m;
                            Constants.LOG.info("[maid_file_manager] Found method: {}.{}", currentClass.getSimpleName(), name);
                            break;
                        }
                    }
                }
                if (targetMethod != null) break;
                currentClass = currentClass.getSuperclass();
            }

            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                targetMethod.invoke(maid, tag);
                Constants.LOG.info("[maid_file_manager] Base Entity data loaded successfully via {}.{}",
                        targetMethod.getDeclaringClass().getSimpleName(), targetMethod.getName());
            } else {
                Constants.LOG.warn("[maid_file_manager] No Entity.load method found, using manual loading");
                manualLoadBaseFields(maid, tag);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Base Entity.load failed: {}", t.toString());
            manualLoadBaseFields(maid, tag);
        }
    }

    /**
     * 手动加载 Entity 基础字段（如果 Entity.load 反射失败）。
     * 这个方法只加载关键字段用于实体存活。
     */
    private static void manualLoadBaseFields(EntityMaid maid, CompoundTag tag) {
        Constants.LOG.info("[maid_file_manager] Manual base field loading...");

        // 生命值
        if (tag.contains("Health", Tag.TAG_FLOAT)) {
            float health = tag.getFloat("Health");
            maid.setHealth(health);
            Constants.LOG.info("[maid_file_manager] Set health to {}", health);
        }

        // motion
        if (tag.contains("Motion", Tag.TAG_COMPOUND)) {
            CompoundTag motion = tag.getCompound("Motion");
            double mx = motion.getDouble("x");
            double my = motion.getDouble("y");
            double mz = motion.getDouble("z");
            maid.setDeltaMovement(mx, my, mz);
        }

        // OnGround
        if (tag.contains("OnGround", Tag.TAG_BYTE)) {
            maid.setOnGround(tag.getBoolean("OnGround"));
        }

        // Rotation (使用反射设置私有字段)
        if (tag.contains("Rotation", Tag.TAG_LIST)) {
            ListTag rotation = tag.getList("Rotation", Tag.TAG_FLOAT);
            if (rotation.size() >= 2) {
                float yRot = rotation.getFloat(0);
                float xRot = rotation.getFloat(1);
                setEntityRotation(maid, yRot, xRot);
            }
        }

        Constants.LOG.info("[maid_file_manager] Manual base fields loaded");
    }

    /**
     * 使用反射设置实体旋转角度。
     */
    private static void setEntityRotation(Entity entity, float yRot, float xRot) {
        try {
            java.lang.reflect.Method setRot = Entity.class.getMethod("setRot", float.class, float.class);
            setRot.invoke(entity, yRot, xRot);
            return;
        } catch (Exception ignored) {
        }

        try {
            java.lang.reflect.Field yRotField = Entity.class.getDeclaredField("yRot");
            yRotField.setAccessible(true);
            yRotField.setFloat(entity, yRot);
        } catch (Exception e) {
            // 忽略
        }
        try {
            java.lang.reflect.Field xRotField = Entity.class.getDeclaredField("xRot");
            xRotField.setAccessible(true);
            xRotField.setFloat(entity, xRot);
        } catch (Exception e) {
            // 忽略
        }
    }

    private static void preInitBaseFields(EntityMaid maid, CompoundTag tag) {
        if (tag.contains("Pos", Tag.TAG_COMPOUND)) {
            CompoundTag pos = tag.getCompound("Pos");
            double px = pos.getDouble("x");
            double py = pos.getDouble("y");
            double pz = pos.getDouble("z");
            maid.setPos(px, py, pz);
            Constants.LOG.info("[maid_file_manager] pre-setPos({}, {}, {})", px, py, pz);
        }
        if (tag.contains("UUIDLeast") && tag.contains("UUIDMost")) {
            long least = tag.getLong("UUIDLeast");
            long most = tag.getLong("UUIDMost");
            maid.setUUID(new java.util.UUID(most, least));
            Constants.LOG.info("[maid_file_manager] pre-setUUID ok");
        }
        if (tag.contains("CustomName", Tag.TAG_STRING)) {
            String cn = tag.getString("CustomName");
            if (cn != null && !cn.isEmpty()) {
                maid.setCustomName(Component.literal(cn));
            }
        }
        if (tag.contains("Pose", Tag.TAG_STRING)) {
            try {
                net.minecraft.world.entity.Pose pose = net.minecraft.world.entity.Pose.valueOf(tag.getString("Pose"));
                maid.setPose(pose);
            } catch (Throwable ignored) {}
        }
    }

    private static void validateMaidAttributes(EntityMaid maid) {
        // MAX_HEALTH 上限动态判断：渡劫 +20 HP（满好感 80 → 100），未渡劫 80
        // （修复 v1.1.0/v1.1.1：硬 cap 80D 直接砍掉渡劫 +20，导致反馈「导入后还是少了 20 血」）
        double healthCap = MAID_MAX_HEALTH + (maid.isStruckByLightning() ? 20.0D : 0.0D);
        double maxHealth = maid.getAttributeBaseValue(Attributes.MAX_HEALTH);
        if (maxHealth > healthCap) {
            Constants.LOG.warn("[maid_file_manager] 导入女仆血量上限 {} 超过限制 {} (struck={}), 截断到 {}",
                    maxHealth, healthCap, maid.isStruckByLightning(), healthCap);
            maid.getAttribute(Attributes.MAX_HEALTH).setBaseValue(healthCap);
            if (maid.getHealth() > healthCap) {
                maid.setHealth((float) healthCap);
            }
        } else {
            Constants.LOG.info("[maid_file_manager] validateMaidAttributes: MAX_HEALTH={} cap={} struck={}",
                    maxHealth, healthCap, maid.isStruckByLightning());
        }
        double attackDamage = maid.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        if (attackDamage <= 0 || attackDamage > MAID_MAX_ATTACK_DAMAGE) {
            Constants.LOG.warn("[maid_file_manager] 导入女仆攻击伤害 {} 异常, 回退到默认值 {}",
                    attackDamage, MAID_DEFAULT_ATTACK_DAMAGE);
            maid.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(MAID_DEFAULT_ATTACK_DAMAGE);
        }
    }

    private static void rebuildAttributesAndModel(EntityMaid maid, MaidFileData data, boolean sourceStruckByLightning) {
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
        // 渡劫 +20 HP：双重保险判断——实体 SynchedEntityData 值 OR 源 NBT 原始值，任一为 true 都加 20
        // （避免同步时机差异导致判断失误，bug「少了 20 点被闪电劈中的生命值」根因）
        boolean struckEffective = maid.isStruckByLightning() || sourceStruckByLightning;
        if (struckEffective) {
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
        Constants.LOG.info("[maid_file_manager] rebuildAttributes: favorability={} level={} health={} attack={} struck(ent={}|src={}|eff={})",
                favorability, level, healthByLevel, attackByLevel,
                maid.isStruckByLightning(), sourceStruckByLightning, struckEffective);
        if (data != null && data.getModelId() != null && !data.getModelId().isEmpty()) {
            String currentModel = maid.getModelId();
            if (!data.getModelId().equals(currentModel)) {
                maid.setModelId(data.getModelId());
                Constants.LOG.info("[maid_file_manager] restore modelId from MaidFileData: {} -> {}",
                        currentModel, data.getModelId());
            }
        }
    }

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
                        if (s.startsWith("{") && s.endsWith("}")) {
                            String key = s.substring(1, s.length() - 1);
                            Constants.LOG.info("[maid_file_manager] getDisplayName: resolving lang key={}", key);
                            try {
                                net.minecraft.network.chat.Component translated =
                                        net.minecraft.network.chat.Component.translatable(key);
                                String result = translated.getString();
                                if (result != null && !result.equals(key) && !result.isEmpty()) {
                                    Constants.LOG.info("[maid_file_manager] getDisplayName: resolved={}", result);
                                    return result;
                                }
                            } catch (Exception e) {
                                Constants.LOG.debug("[maid_file_manager] getDisplayName: translation failed for {}", key);
                            }
                            Constants.LOG.info("[maid_file_manager] getDisplayName: using modelId fallback for lang key {}", key);
                            return fallbackNameFromModelId(modelId);
                        }
                        return s;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] getDisplayName failed for {}: {}", modelId, t.toString());
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
            // 删除残留药水效果（反馈：导出后女仆带「生命恢复2」让其误以为是重新驯服的另一个女仆）
            // 驯服自带的 buff 由 TLM 本体在驯服流程里动态加，不应在 .maid 文件里持久化残留
            fullNbt.remove("ActiveEffects");
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
            Constants.LOG.info("[maid_file_manager] exportMaidToData: SUCCESS modelId={}", modelId);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("[maid_file_manager] exportMaidToData FAILED: entityId={}", entityId, e);
            return null;
        }
    }

    public static Component importMaidFromData(ServerPlayer player, MaidFileData data) {
        if (data == null || data.getData() == null) {
            return Component.translatable("maid_file_manager.import.fail.exception", "invalid maid data");
        }
        Level level = player.level();
        EntityMaid maid = new EntityMaid(level);
        Object registryAccess = getRegistryAccess(level);

        // ---------- 最终保护网：从原始 data.getData() 读出源值，供后续二次恢复 ----------
        // NbtMigration.migrate 会删一些东西，extractTlmData 又会再删，
        // 但 data.getData() 是原始导出 NBT，好感度 & 渡劫标记一定在这里面。
        CompoundTag originalNbt = data.getData();
        int sourceFavorability = -1;
        if (originalNbt.contains("MaidFavorability", Tag.TAG_INT)) {
            sourceFavorability = originalNbt.getInt("MaidFavorability");
            Constants.LOG.info("[maid_file_manager] FINAL SAFETY NET: source favorability from NBT: {}", sourceFavorability);
        } else if (originalNbt.contains("MaidFavorabilityManagerCounter", Tag.TAG_INT)) {
            sourceFavorability = originalNbt.getInt("MaidFavorabilityManagerCounter");
            Constants.LOG.info("[maid_file_manager] FINAL SAFETY NET: source fav counter from NBT: {}", sourceFavorability);
        }
        // 渡劫标记 FINAL SAFETY NET：从原始未处理 NBT 读出，无论后续加载流程怎么折腾都用这个值最终兜底
        boolean sourceStruckByLightning = false;
        if (originalNbt.contains("StruckByLightning", Tag.TAG_BYTE)) {
            sourceStruckByLightning = originalNbt.getBoolean("StruckByLightning");
            Constants.LOG.info("[maid_file_manager] FINAL SAFETY NET: source StruckByLightning from NBT: {}", sourceStruckByLightning);
        }
        // 源血量 FINAL SAFETY NET：反馈「女仆血量>20但导出后只有20血」
        // 根因：maid.load 失败走 fallback 路径时 Health 字段未还原，getHealth() 停留 Mob 默认初始 20
        float sourceHealth = -1f;
        if (originalNbt.contains("Health", Tag.TAG_FLOAT)) {
            sourceHealth = originalNbt.getFloat("Health");
            Constants.LOG.info("[maid_file_manager] FINAL SAFETY NET: source Health from NBT: {}", sourceHealth);
        }

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

        // ---------- 最终保护网 1/2：如果 maid.getFavorability() == 0，但源数据有 > 0 的值，强制恢复 ----------
        try {
            int currentFav = maid.getFavorability();
            if (currentFav == 0 && sourceFavorability > 0) {
                Constants.LOG.warn("[maid_file_manager] FINAL FIX: favorability was 0 (lost), restoring to source={}", sourceFavorability);
                restoreFavorability(maid, sourceFavorability);
            } else {
                Constants.LOG.info("[maid_file_manager] FINAL CHECK: current fav={}, source fav={}", currentFav, sourceFavorability);
            }
        } catch (Throwable t) {
            if (sourceFavorability > 0) {
                Constants.LOG.warn("[maid_file_manager] FINAL FIX: cannot read current fav, fallback force restore source={}", sourceFavorability, t);
                restoreFavorability(maid, sourceFavorability);
            }
        }

        // ---------- 最终保护网 2/2：渡劫标记 StruckByLightning 最终强制同步（bug 根因：少 20 HP + 重新劈不生效）
        // 同步时机必须在 invokeLoadMaid 之后、rebuildAttributesAndModel 之前，确保 rebuild 中 isStruckByLightning() 读到正确值
        try {
            boolean currentStruck = maid.isStruckByLightning();
            if (currentStruck != sourceStruckByLightning) {
                Constants.LOG.warn("[maid_file_manager] FINAL FIX: StruckByLightning mismatch (ent={}, src={}) — force sync to src",
                        currentStruck, sourceStruckByLightning);
                maid.setStruckByLightning(sourceStruckByLightning);
            } else {
                Constants.LOG.info("[maid_file_manager] FINAL CHECK: StruckByLightning consistent (ent={}, src={})",
                        currentStruck, sourceStruckByLightning);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] FINAL FIX: cannot read current StruckByLightning, fallback force sync to src={}",
                    sourceStruckByLightning, t);
            maid.setStruckByLightning(sourceStruckByLightning);
        }

        rebuildAttributesAndModel(maid, data, sourceStruckByLightning);
        validateMaidAttributes(maid);
        float fMax = maid.getMaxHealth();
        // 血量恢复优先级：源 NBT Health（截断到 [1, fMax]）> fMax 满血兜底
        // bug 根因：maid.load 失败导致 getHealth() 停留默认 20，rebuild 后未恢复
        float finalHealth;
        if (sourceHealth > 0 && sourceHealth <= fMax) {
            finalHealth = sourceHealth;
        } else {
            finalHealth = fMax;  // 源血量超过新上限或异常，默认满血
        }
        maid.setHealth(finalHealth);
        Constants.LOG.info("[maid_file_manager] Restored health: source={}, maxHealth={}, final={}", sourceHealth, fMax, finalHealth);
        // 清空残留药水效果（双保险：导出已删 ActiveEffects，导入再清一次防止 maid.load 路径读到）
        // 反馈「生命恢复2是驯服自带的，残留让玩家误以为不是同一个女仆」
        try {
            maid.removeAllEffects();
            Constants.LOG.info("[maid_file_manager] Cleared all active effects (avoid stale buffs like Regeneration II)");
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] removeAllEffects failed: {}", t.toString());
        }
        maid.hurtTime = 0;
        maid.deathTime = 0;
        maid.clearFire();
        maid.setTicksFrozen(0);
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
        // 调试日志：记录实体状态
        postImportDebug(maid, level);
        registerMaidWorldData(maid);
        return ownerMatched
                ? Component.translatable("maid_file_manager.import.success")
                : Component.translatable("maid_file_manager.import.fail.not_found_owner");
    }

    public static Component importMaid(ServerPlayer player, String fileName) {
        Path gameDir = Services.PLATFORM.get().getGameDir().toAbsolutePath();
        Path dir = MaidFileIo.ensureImportsDir(gameDir);
        Path file = dir.resolve(fileName).normalize();
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

        // 渡劫标记 FINAL SAFETY NET：从原始未处理 NBT 读出（与 importMaidFromData 完全一致）
        CompoundTag originalNbt = data.getData();
        boolean sourceStruckByLightning = false;
        if (originalNbt.contains("StruckByLightning", Tag.TAG_BYTE)) {
            sourceStruckByLightning = originalNbt.getBoolean("StruckByLightning");
            Constants.LOG.info("[maid_file_manager] importMaid(file) SAFETY NET: source StruckByLightning from NBT: {}", sourceStruckByLightning);
        }
        // 源血量 FINAL SAFETY NET（与 importMaidFromData 完全一致）
        float sourceHealth = -1f;
        if (originalNbt.contains("Health", Tag.TAG_FLOAT)) {
            sourceHealth = originalNbt.getFloat("Health");
            Constants.LOG.info("[maid_file_manager] importMaid(file) SAFETY NET: source Health from NBT: {}", sourceHealth);
        }

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

        // 渡劫标记 FINAL FIX：invokeLoadMaid 后 rebuild 前显式同步（与 importMaidFromData 完全一致）
        try {
            boolean currentStruck = maid.isStruckByLightning();
            if (currentStruck != sourceStruckByLightning) {
                Constants.LOG.warn("[maid_file_manager] importMaid(file) FINAL FIX: StruckByLightning mismatch (ent={}, src={}) — force sync",
                        currentStruck, sourceStruckByLightning);
                maid.setStruckByLightning(sourceStruckByLightning);
            } else {
                Constants.LOG.info("[maid_file_manager] importMaid(file) FINAL CHECK: StruckByLightning consistent (ent={}, src={})",
                        currentStruck, sourceStruckByLightning);
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] importMaid(file) FINAL FIX: cannot read current, fallback sync src={}",
                    sourceStruckByLightning, t);
            maid.setStruckByLightning(sourceStruckByLightning);
        }

        rebuildAttributesAndModel(maid, data, sourceStruckByLightning);
        validateMaidAttributes(maid);
        float fMax = maid.getMaxHealth();
        // 血量恢复优先级：源 NBT Health（截断到 [1, fMax]）> fMax 满血兜底（与 importMaidFromData 完全一致）
        float finalHealth;
        if (sourceHealth > 0 && sourceHealth <= fMax) {
            finalHealth = sourceHealth;
        } else {
            finalHealth = fMax;
        }
        maid.setHealth(finalHealth);
        Constants.LOG.info("[maid_file_manager] importMaid(file) Restored health: source={}, maxHealth={}, final={}", sourceHealth, fMax, finalHealth);
        // 清空残留药水效果（双保险，与 importMaidFromData 完全一致）
        try {
            maid.removeAllEffects();
            Constants.LOG.info("[maid_file_manager] importMaid(file) Cleared all active effects");
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] importMaid(file) removeAllEffects failed: {}", t.toString());
        }
        maid.hurtTime = 0;
        maid.deathTime = 0;
        maid.clearFire();
        maid.setTicksFrozen(0);
        ensureSchedulePosNonNull(maid);
        Constants.LOG.info("[maid_file_manager] post-load state: isAlive={}, health={}/{}, uuid={}",
                maid.isAlive(), maid.getHealth(), fMax, maid.getUUID());
        Vec3 forward = player.getLookAngle().scale(Constants.IMPORT_SPAWN_DISTANCE);
        Vec3 basePos = player.position().add(forward);
        BlockPos safePos = findSafeSpawnPos(level,
                new BlockPos((int) basePos.x, (int) basePos.y, (int) basePos.z));
        if (safePos == null) {
            safePos = new BlockPos(player.blockPosition().above());
            Constants.LOG.warn("[maid_file_manager] importMaid: 未找到安全位置，回退到玩家上方 {}", safePos);
        }
        Constants.LOG.info("[maid_file_manager] importMaid: file={} spawnPos={}", fileName, safePos);
        maid.setPos(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5);
        maid.setTask(TaskManager.getIdleTask());
        maid.setInSittingPose(false);
        maid.setOrderedToSit(false);
        boolean ownerMatched = matchOwner(maid, player, data);
        ensurePersistence(maid);
        if (!level.addFreshEntity(maid)) {
            return Component.translatable("maid_file_manager.import.fail.add_entity");
        }
        postImportDebug(maid, level);
        registerMaidWorldData(maid);
        return ownerMatched
                ? Component.translatable("maid_file_manager.import.success")
                : Component.translatable("maid_file_manager.import.fail.not_found_owner");
    }

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

    private static void ensurePersistence(EntityMaid maid) {
        maid.setPersistenceRequired();
        Constants.LOG.info("[maid_file_manager] ensurePersistence: tame={}, persistenceRequired={}",
                maid.isTame(), maid.isPersistenceRequired());
    }

    /**
     * 导入后调试：记录实体状态并启动 tick 监控。
     * Forge 1.20 中实体可能在 tick 阶段被移除（TLM 内部逻辑）。
     */
    private static void postImportDebug(EntityMaid maid, Level level) {
        try {
            Constants.LOG.info("[maid_file_manager] postImportDebug: uuid={}, alive={}, removed={}, pos={}",
                    maid.getUUID(), maid.isAlive(), maid.isRemoved(), maid.blockPosition());

            // 注册到监控系统（使用实体引用）
            monitoredMaids.put(maid, System.currentTimeMillis());
            Constants.LOG.info("[maid_file_manager] Entity registered for tick monitoring: uuid={}", maid.getUUID());

            // 启动 tick 监听器（如果尚未启动）
            if (!tickListenerRegistered) {
                registerTickListener();
            }
        } catch (Throwable t) {
            Constants.LOG.error("[maid_file_manager] postImportDebug failed", t);
        }
    }

    /**
     * 注册服务端 tick 监听器。
     * 由于 Forge 1.20 事件总线的复杂性，这里使用更简单的方式：
     * 在 importMaid 中直接记录 tick 时间戳，由 Mod 主类在 ServerTickEvent 中检查。
     */
    private static synchronized void registerTickListener() {
        if (tickListenerRegistered) return;
        try {
            // 不直接注册事件监听器，而是设置一个标记
            // Forge 事件注册将在 MaidFileModForge 中完成
            tickListenerRegistered = true;
            Constants.LOG.info("[maid_file_manager] Tick monitoring system initialized (listeners registered in mod constructor)");
        } catch (Throwable t) {
            Constants.LOG.error("[maid_file_manager] registerTickListener failed: {}", t.toString());
        }
    }

    /**
     * 处理服务端 tick，检查监控中的实体状态。
     * 由 MaidFileModForge.onServerTick() 调用。
     */
    public static void onServerTick() {
        handleServerTick();
    }

    /**
     * 处理服务端 tick，检查监控中的实体状态（内部实现）。
     * 关键：如果实体被标记为 removed，立即清除其 removal reason。
     */
    private static void handleServerTick() {
        tickCounter++;
        // 每 5 tick (0.25秒) 检查一次，更频繁地保护
        if (tickCounter % 5 != 0) return;

        if (monitoredMaids.isEmpty()) return;

        Constants.LOG.info("[maid_file_manager] === Tick Check (tick={}, monitored={}) ===",
                tickCounter, monitoredMaids.size());

        List<EntityMaid> toRemove = new ArrayList<>();
        for (EntityMaid maid : monitoredMaids.keySet()) {
            try {
                // 检查引用是否仍然有效
                if (maid == null) {
                    Constants.LOG.warn("[maid_file_manager] MONITORED MAID: null reference!");
                    toRemove.add(maid);
                    continue;
                }

                // 检查实体状态
                boolean alive = maid.isAlive();
                boolean removed = maid.isRemoved();
                boolean persistent = checkPersistence(maid);

                Constants.LOG.info("[maid_file_manager] MONITORED MAID: uuid={}, alive={}, removed={}, persistent={}",
                        maid.getUUID(), alive, removed, persistent);

                // 如果实体被标记为 removed，立即清除
                if (removed) {
                    Constants.LOG.warn("[maid_file_manager] MAID IS REMOVED! uuid={}", maid.getUUID());
                    clearRemovalReason(maid);
                    // 清除后再次检查
                    removed = maid.isRemoved();
                    Constants.LOG.info("[maid_file_manager] After clearing: removed={}", removed);
                }

                // 如果实体不健康，恢复生命值
                if (alive && maid.getHealth() < maid.getMaxHealth() * 0.5f) {
                    maid.setHealth(maid.getMaxHealth());
                    Constants.LOG.info("[maid_file_manager] Restored health for uuid={}", maid.getUUID());
                }

                // 强制设置持久化
                if (!persistent) {
                    forcePersistence(maid);
                }

                // 检查实体是否仍在 Level 中
                if (maid.level() != null) {
                    // Forge 1.20: getEntity(int) 接受的是 entityId 而不是 UUID
                    // 直接使用实体引用检查是否仍然有效
                    if (maid.isRemoved()) {
                        Constants.LOG.warn("[maid_file_manager] MAID IS MARKED REMOVED! uuid={}", maid.getUUID());
                    }
                }

            } catch (Throwable t) {
                Constants.LOG.error("[maid_file_manager] Error checking maid: {}", t.toString(), t);
            }
        }

        // 清理已死亡的实体引用（但保持监控以尝试复活）
        // 不立即移除监控，给实体一个恢复的机会
    }

    /**
     * 检查实体的持久化状态。
     */
    private static boolean checkPersistence(EntityMaid maid) {
        try {
            // 方法1: 使用公共方法
            return maid.isPersistenceRequired();
        } catch (Throwable ignored) {
        }

        try {
            // 方法2: 反射检查字段
            for (java.lang.reflect.Field f : Entity.class.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("persistent") || name.contains("save")) {
                        f.setAccessible(true);
                        return f.getBoolean(maid);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * 强制设置实体持久化。
     */
    private static void forcePersistence(EntityMaid maid) {
        // 方法1: 公共方法
        try {
            maid.setPersistenceRequired();
            Constants.LOG.info("[maid_file_manager] Force persistence via method: {}", maid.isPersistenceRequired());
            if (maid.isPersistenceRequired()) return;
        } catch (Throwable ignored) {
        }

        // 方法2: 直接设置字段
        try {
            setPersistenceDirect(maid, true);
            Constants.LOG.info("[maid_file_manager] Force persistence via field: {}", checkPersistence(maid));
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] forcePersistence failed: {}", t.toString());
        }
    }

    /**
     * 清除实体的 removal reason。
     */
    private static void clearRemovalReason(EntityMaid maid) {
        // 方法1: 尝试 setRemovalReason(null)
        try {
            java.lang.reflect.Method m = Entity.class.getMethod("setRemovalReason", net.minecraft.world.entity.Entity.RemovalReason.class);
            m.invoke(maid, (Object) null);
            Constants.LOG.info("[maid_file_manager] Cleared removal via setRemovalReason(null)");
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] setRemovalReason failed: {}", t.toString());
        }

        // 方法2: 查找任何设置 removal 的方法
        try {
            for (java.lang.reflect.Method m : Entity.class.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("removal") && m.getParameterCount() <= 1) {
                    m.setAccessible(true);
                    if (m.getParameterCount() == 0) {
                        m.invoke(maid);
                    } else {
                        m.invoke(maid, (Object) null);
                    }
                    Constants.LOG.info("[maid_file_manager] Cleared removal via {}.{}", Entity.class.getSimpleName(), m.getName());
                    return;
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Method search failed: {}", t.toString());
        }

        // 方法3: 直接修改 removed 字段
        try {
            for (java.lang.reflect.Field f : Entity.class.getDeclaredFields()) {
                if (f.getType() == boolean.class && (f.getName().contains("removed") || f.getName().contains("Removal"))) {
                    f.setAccessible(true);
                    f.setBoolean(maid, false);
                    Constants.LOG.info("[maid_file_manager] Set removed=false via field: {}", f.getName());
                    return;
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Field modification failed: {}", t.toString());
        }

        // 方法4: 搜索所有 boolean 字段中与 removed 相关的
        try {
            for (java.lang.reflect.Field f : Entity.class.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("remove") || name.contains("despawn") || name.contains("discard")) {
                        f.setAccessible(true);
                        f.setBoolean(maid, false);
                        Constants.LOG.info("[maid_file_manager] Set {}=false via field search", f.getName());
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Constants.LOG.warn("[maid_file_manager] Fallback field search failed: {}", t.toString());
        }
    }

    /**
     * 通过 UUID 在所有服务器级别中查找实体。
     */
    private static Entity findEntityByUUID(UUID uuid) {
        try {
            // 获取 MinecraftServer 的所有级别
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = null;

            // 尝试获取服务器实例
            try {
                java.lang.reflect.Method getServer = serverClass.getMethod("getInstance");
                server = getServer.invoke(null);
            } catch (NoSuchMethodException ignored) {
                // 回退：通过字段查找
                for (java.lang.reflect.Field f : serverClass.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == serverClass) {
                        f.setAccessible(true);
                        server = f.get(null);
                        break;
                    }
                }
            }

            if (server == null) {
                return null;
            }

            // 获取所有级别
            java.lang.reflect.Method getLevels = serverClass.getMethod("getAllLevels");
            Iterable<?> levels = (Iterable<?>) getLevels.invoke(server);

            for (Object level : levels) {
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    Entity entity = serverLevel.getEntity(uuid);
                    if (entity != null) {
                        return entity;
                    }
                }
            }
        } catch (Throwable t) {
            Constants.LOG.debug("[maid_file_manager] findEntityByUUID failed: {}", t.toString());
        }
        return null;
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
                return feet.above();
            }
        }
        return null;
    }

    public static List<String> listImportableFiles(ServerPlayer player) {
        Path gameDir = Services.PLATFORM.get().getGameDir().toAbsolutePath();
        Path dir = MaidFileIo.ensureImportsDir(gameDir);
        return MaidFileIo.listMaidFiles(dir);
    }

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
                PlayerList playerList = player.server.getPlayerList();
                ServerPlayer owner = playerList.getPlayer(uuid);
                if (owner != null) {
                    maid.setOwnerUUID(uuid);
                    maid.setTame(true, false);
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
                maid.setTame(true, false);
                return true;
            }
        }
        maid.setTame(false, false);
        maid.setOwnerUUID(null);
        return false;
    }

    private static void clearInventoryItems(CompoundTag root, String key) {
        if (!root.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag invTag = root.getCompound(key);
        invTag.put("Items", new ListTag());
    }

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
