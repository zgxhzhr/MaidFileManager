package io.github.zgxhzhr.maidfm.data;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出流程内的「附加数据未带上」累积器。
 *
 * <p><b>为什么需要它</b>：{@code MaidMigrationProvider.export()} 抛异常时，框架只能跳过
 * 那一段并继续导出（不能让一个附属模组的数据缺失把整次迁移搞失败）。但跳过之后
 * 玩家完全不知道自己的归档没跟过来——导出成功提示照常显示。
 *
 * <p>难点在于 {@code doSerializeMaid} 返回的是 {@code MaidFileData}，没有地方承载
 * 「哪几段没带上」这种 UI 信息（往数据对象里塞非序列化字段会让语义混乱）。
 * 所以用一个按线程的累积器：导出过程中往里记，导出结束点取出并汇总成一条反馈。
 *
 * <p><b>为什么是 ThreadLocal</b>：导出在主线程跑，但 ThreadLocal 能避免任何意外串场。
 * 用法与 {@link CuriosBaubleFilter#batch()} 一致。
 *
 * <p><b>生命周期</b>：流程开头 {@link #discard()} 清掉上一轮残留，结束点 {@link #take()}
 * 取出并清零。两个动作都不能省——只 take 不 discard 时，失败路径的残留会串到下一位玩家。
 */
public final class MigrationNoteCollector {

    private static final ThreadLocal<List<Component>> PENDING =
            ThreadLocal.withInitial(ArrayList::new);

    private MigrationNoteCollector() {
    }

    /** 记一条「这段附加数据没带上」的说明。note 为 null 时忽略。 */
    public static void add(Component note) {
        if (note != null) {
            PENDING.get().add(note);
        }
    }

    /** 取出并清零（流程结束点调用）。没有记录时返回空列表。 */
    public static List<Component> take() {
        List<Component> list = PENDING.get();
        // 用 remove() 而不是 set(new ArrayList<>())：ThreadLocal 会随线程存活，
        // set 会一直留一个空列表在线程上；remove() 让下次 get 重新创建。
        PENDING.remove();
        return list.isEmpty() ? List.of() : List.copyOf(list);
    }

    /** 丢弃残留（流程开头调用，防止上一轮失败路径的残留串进来）。 */
    public static void discard() {
        PENDING.remove();
    }
}
