package io.github.zgxhzhr.maidfm.util;

/**
 * 模型名显示的纯文本处理。
 *
 * <p><b>为什么单独成类：</b>原先这段逻辑在 {@code client.ModelNames} 里，而
 * {@code service.MaidTransferService} 需要在服务端兜底调用它。结果 {@code service} 反向
 * import 了 {@code client} 包——而 {@code ModelNames} 又 {@code import
 * net.minecraft.client.Minecraft}。在专用服务器上，Forge 的 {@code RuntimeDistCleaner}
 * 会拒绝加载常量池里带 {@code net.minecraft.client.*} 的类，这条调用链存在风险。
 *
 * <p>这段逻辑本身只是字符串变换，与 Minecraft 无关，因此下沉到这里，让服务端也能安全调用。
 *
 * <p>{@code client.ModelNames.prettifyModelPath} 保留为委托，避免改动客户端调用点。
 */
public final class ModelDisplayNames {

    private ModelDisplayNames() {
    }

    /**
     * 把 model_id 的 path 部分美化成可读文本，作为「本地没有该模型资源」时的兜底显示。
     *
     * <p>典型场景：服务端/别的客户端没装某个玩家的私有模型包，翻译键与模型表都查不到，
     * 只能拿到 {@code namespace:path}。直接把 {@code my_private_maid} 摆出来很难看，
     * 这里转成 {@code My Private Maid}，至少能区分「这是谁的模型」而不至于全变成同一个占位名。
     *
     * @param path 模型 ID 的 path 部分（下划线分隔）
     * @return 每个词首字母大写、下划线换成空格的文本；入参为空时返回空串
     */
    public static String prettify(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String s = path.replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ') {
                upper = true;
                sb.append(ch);
            } else if (upper) {
                sb.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
