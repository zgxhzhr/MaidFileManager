package io.github.zgxhzhr.maidfm.data;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 单个女仆导入的结构化结果。
 *
 * <p>批量导入的成功/失败统计必须基于 {@link #state()} 枚举，
 * 严禁反解展示文案（旧实现 {@code message.getString().contains("成功")}
 * 在非中文语言下会把全部成功误判为失败）。
 *
 * @param state           结果状态
 * @param message         用于 FEEDBACK 展示的文案（已按服务端语言本地化）
 * @param baublesStripped 是否发生了「请求保留饰品但被服务端配置剥离」
 * @param baubleNotes     饰品逐件处理说明（禁止携带/还原全新/无法解析等），为空表示全部正常保留
 */
public record ImportResult(State state, Component message, boolean baublesStripped, List<Component> baubleNotes) {

    public enum State {
        /** 导入成功且主人关系已匹配 */
        OK,
        /** 实体已生成，但未匹配到主人（野生女仆，可用蛋糕重新驯服） */
        OK_UNTAMED,
        /** 服务端配置禁止客户端导入 */
        SERVER_DISALLOWED,
        /** 导入失败（数据损坏、生成实体被世界拒绝等） */
        FAILED
    }

    public static ImportResult ok(Component message) {
        return new ImportResult(State.OK, message, false, List.of());
    }

    public static ImportResult okUntamed(Component message) {
        return new ImportResult(State.OK_UNTAMED, message, false, List.of());
    }

    public static ImportResult disallowed(Component message) {
        return new ImportResult(State.SERVER_DISALLOWED, message, false, List.of());
    }

    public static ImportResult failed(Component message) {
        return new ImportResult(State.FAILED, message, false, List.of());
    }

    public ImportResult withBaublesStripped() {
        return new ImportResult(state, message, true, baubleNotes);
    }

    public ImportResult withBaubleNotes(List<Component> notes) {
        return new ImportResult(state, message, baublesStripped, notes == null ? List.of() : List.copyOf(notes));
    }

    /** 实体是否确实进入了世界（OK / OK_UNTAMED 都算导入成功） */
    public boolean spawned() {
        return state == State.OK || state == State.OK_UNTAMED;
    }
}
