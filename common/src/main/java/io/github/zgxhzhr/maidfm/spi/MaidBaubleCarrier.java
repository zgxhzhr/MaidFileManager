package io.github.zgxhzhr.maidfm.spi;

import io.github.zgxhzhr.maidfm.platform.Services;
import net.minecraft.resources.Identifier;

import java.util.Set;

/**
 * 女仆饰品携带声明接口（命名空间粒度）。
 *
 * <p>本模组默认<b>不携带</b>任何第三方饰品：无论是女仆自带饰品栏（{@code MaidBaubleInventory}）
 * 还是女仆的 Curios 槽位，凡未被声明的命名空间一律丢弃。模组作者如果认为自己的饰品
 * 应当随女仆迁移，实现本接口并注册即可。
 *
 * <p>使用方式（在你的模组初始化阶段）：
 * <pre>{@code
 * MaidBaubleCarrierRegistry.register(new MaidBaubleCarrier() {
 *     public Identifier getId() { return Identifier.fromNamespaceAndPath("yourmodid", "baubles"); }
 *     public String getDependencyModId() { return "yourmodid"; }
 *     public Set<String> carriedNamespaces() { return Set.of("yourmodid"); }
 * });
 * }</pre>
 *
 * <p><b>安全约束（注册时强制校验）</b>：{@link #carriedNamespaces()} 中的每一项
 * <b>必须等于 {@link #getId()} 的命名空间</b>，即只能声明自己的命名空间。
 * 试图声明他人命名空间会被注册表拒绝并记录 WARN——防止某个模组替别的模组放行物品、
 * 从而绕过携带限制。
 *
 * <p>本接口与 {@link MaidMigrationProvider} 相互独立：后者迁移的是"不在女仆实体 NBT 上的数据"
 * （玩家持久化数据、外部存储等），本接口声明的是"哪些命名空间的物品可以随女仆迁移"。
 * 两者可以各自单独接入，互不影响。
 */
public interface MaidBaubleCarrier {

    /**
     * 唯一标识，形如 {@code 你的modid:baubles}。
     * <p><b>其命名空间即"声明者身份"</b>，用于注册时的越权校验与日志留痕。
     */
    Identifier getId();

    /**
     * 依赖的 modId，用于软依赖检测。返回空串表示不依赖特定模组（始终可用）。
     */
    default String getDependencyModId() {
        return "";
    }

    /**
     * 声明可随女仆迁移的命名空间集合。
     * <p>每一项都必须等于 {@link #getId()} 的命名空间（只允许声明自己的）。
     *
     * @return 命名空间集合；为空表示不声明任何内容（等价于不注册）
     */
    Set<String> carriedNamespaces();

    /**
     * 声明可携带的 Curios 槽位名（<b>可选</b>）。
     *
     * <p>返回<b>空集</b>（默认）表示不限制槽位：只要命名空间被声明，该模组的物品在任何"非硬禁"槽位都可携带；
     * 返回<b>非空</b>表示<b>只有</b>出现在这些槽位里的物品才随女仆迁移，其它槽位一律丢弃。
     *
     * <p>建议关心槽位语义的模组填这个：Curios 槽位名无法穷举（TLM 与第三方模组都能给女仆新增槽位），
     * 档案管理器只能硬禁已知的容器类/数据容器槽位；如果你的槽位本身也是"有状态容器"，
     * 请在这里明确列出你允许携带的槽位——未列出的（含将来新增的）默认不会被携带。
     *
     * <p>写纯槽位名即可（如 {@code ring}、{@code my_trinket}），带命名空间前缀也会被规范化。
     */
    default Set<String> carriedCuriosSlots() {
        return Set.of();
    }

    /**
     * 该声明当前是否可用（依赖的模组是否已加载）。默认按 {@link #getDependencyModId()} 检测。
     */
    default boolean isAvailable() {
        String dep = getDependencyModId();
        return dep == null || dep.isEmpty() || Services.PLATFORM.get().isModLoaded(dep);
    }
}
