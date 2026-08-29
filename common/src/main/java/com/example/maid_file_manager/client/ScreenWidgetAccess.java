package com.example.maid_file_manager.client;

import com.example.maid_file_manager.Constants;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨版本 / 跨平台访问 {@link Screen#renderables}（private / package-private）。
 *
 * <p>为什么不用 {@code @Accessor} interface mixin：
 * NeoForge 1.21 / 1.21.1 环境下即便 mixin 配置正确写入 client 数组，
 * interface accessor 也可能因 MODULE / classloader 可见性隔离而在运行时触发
 * {@code ClassCastException: MaidFileManagerScreen cannot be cast to AccessorScreenRenderables}，
 * 导致打开管理界面瞬间世界崩溃。反射是 100% 运行时安全的兜底方案。
 *
 * <p>访问策略（只做一次 static lookup，失败返回空 List，绝不崩溃渲染线程）：
 * <ol>
 *   <li>优先查找 Mojang/Parchment 的 field 名：{@code renderables}</li>
 *   <li>找不到再回退查 SRG：{@code f_96543_}（1.20/1.21 常见）</li>
 *   <li>都失败：打一条 warn，后续 render 重绘阶段跳过 widget 重画（只是模糊不能彻底根治，不会崩）</li>
 * </ol>
 */
public final class ScreenWidgetAccess {

    private static final Field RENDERABLES_FIELD;
    private static final boolean AVAILABLE;

    static {
        Field f = null;
        for (String candidate : new String[]{"renderables", "f_96543_"}) {
            try {
                f = Screen.class.getDeclaredField(candidate);
                f.setAccessible(true);
                break;
            } catch (NoSuchFieldException ignored) { /* 试下一个 */ }
        }
        RENDERABLES_FIELD = f;
        AVAILABLE = (f != null);
        if (!AVAILABLE) {
            Constants.LOG.warn("[maid_file_manager] Screen.renderables 反射未找到（字段名变了？）。" +
                    "管理/设置界面的重绘阶段将跳过 widget 重画（最多轻微 blur 不会崩）。");
        }
    }

    private ScreenWidgetAccess() {}

    /**
     * 取出 Screen 实例的 renderables（widget 列表）。任何异常都退化为空 List，绝不抛。
     * 重绘阶段调用方遍历返回值逐个调用 {@link Renderable#render} 即可。
     */
    @SuppressWarnings("unchecked")
    public static List<Renderable> getRenderables(Screen screen) {
        if (!AVAILABLE || screen == null) return List.of();
        try {
            Object v = RENDERABLES_FIELD.get(screen);
            if (v instanceof List<?> list) {
                // 运行时泛型擦除；MC 内部保证这是 List<Renderable>
                return (List<Renderable>) list;
            }
        } catch (Throwable t) {
            // IllegalAccess / SecurityManager / Module 导出限制 — 任何错只 warn 一次。
            Constants.LOG.warn("[maid_file_manager] ScreenWidgetAccess 读取 renderables 失败（已忽略，不崩溃）：{}", t.toString());
        }
        return List.of();
    }

    /** 兼容 ArrayList 需要的重载（性能敏感场景下可直接返回原 list 引用，避免复制）。 */
    public static List<Renderable> getRenderablesCopy(Screen screen) {
        return new ArrayList<>(getRenderables(screen));
    }
}
