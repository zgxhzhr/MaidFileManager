package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 网络抽象层：客户端发送 C2S 包的接口。
 * 由 Forge/Fabric 各自实现，common 模块通过此接口发送包。
 */
public interface IMaidFileNetwork {
    /** 请求服务端返回附近属于当前玩家的女仆列表 */
    void sendRequestMaidList();

    /** 请求服务端批量导出指定 entityId 列表的女仆；removeAfter=true 表示导出成功后在世界中移除女仆 */
    void sendExportMaids(List<Integer> entityIds, boolean removeAfterExport);

    /**
     * 批量发送要导入的女仆文件数据给服务端。
     *
     * <p>饰品导入策略（是否丢弃属性、禁用携带清单）由服务端配置决定，客户端不再上传，
     * 避免非 OP 绕过（服务端在导入处理时读取自身配置）。
     *
     * @param keepBaubles       true=客户端希望保留饰品导入
     * @param deleteAfterImport true=导入成功后删除对应的本地源文件（默认关闭）；
     *                          服务端会额外回传逐项 spawned 结果，客户端只删除确实成功的文件
     */
    void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles, boolean deleteAfterImport);

    /** 上报本客户端对「服务端统一导出」的同意状态（默认 false，服务端按 UUID 记录） */
    void sendClientConsent(boolean allow);

    /** 请求服务端修改服务端配置（服务端校验 OP 权限后写文件并广播同步） */
    void sendSetServerConfig(String key, boolean value);

    /** OP 请求服务端修改「禁用携带的饰品 ID 列表」（服务端校验 OP 权限后写文件并广播同步） */
    void sendSetServerBaubleBlockedList(List<String> ids);

    /** OP 请求服务端返回所有在线玩家的女仆列表（以各玩家为中心搜索，供统一导出浏览） */
    void sendRequestServerExportList();

    /** OP 请求服务端代为导出：按玩家分组的女仆 entityId，导出文件保存到服务端磁盘 maid_exports/<玩家名>/ */
    void sendServerExportBatch(List<PlayerExportRequest> groups);

    /** 请求某女仆的档案：服务端读取后回发 {@link ClientHandler#onMaidProfileReceived} */
    void sendRequestMaidProfile(int entityId);

    /** 保存女仆档案：服务端校验归属后写入实体并回执（复用 ID_FEEDBACK） */
    void sendSaveMaidProfile(int entityId, MaidProfile profile);

    /**
     * 备份管理：请求服务端返回可浏览的车万女仆自动备份列表。
     *
     * <p>服务端读取存档下的 {@code data/maid_backups} 并按权限过滤：OP 可见全部玩家，
     * 非 OP 仅可见自己（主人 UUID 与请求者一致）的备份。
     */
    void sendRequestBackupList();

    /**
     * 备份管理：请求服务端导出某条备份。
     *
     * <p>服务端校验权限（OP 或该备份主人）后读取 .dat 并回传为 {@link MaidFileData}，
     * 由客户端写入本地 {@code maid_file/maid_exports/}。服务端不写任何文件、不移除任何数据。
     */
    void sendRequestBackupExport(String ownerUuid, String maidUuid, String fileName);

    /** 统一导出浏览：某玩家为中心的女仆列表（服务端收集） */
    record PlayerMaidGroup(String playerName, boolean consented, List<MaidInfo> maids) {
    }

    /** 统一导出提交：某玩家名下要导出的女仆 entityId 集合 */
    record PlayerExportRequest(String playerName, List<Integer> entityIds) {
    }

    /** 备份管理：某主人名下的女仆备份节点 */
    record BackupMaid(String maidUuid, String maidName, List<String> files) {
    }

    /** 备份管理：某主人（按主人 UUID 分组）的全部女仆备份 */
    record BackupOwner(String ownerUuid, List<BackupMaid> maids) {
    }

    /**
     * 备份管理：某个存档下的全部主人备份。
     *
     * <p>仅用于客户端本机浏览：游戏主菜单没有「当前存档」上下文，需要先按存档分组再逐层展开；
     * 专业服务端只持有其当前存档的数据，不使用本结构（界面会包成单个「当前服务器」节点）。
     */
    record BackupWorld(String worldName, List<BackupOwner> owners) {
    }

    /**
     * 全局网络实现持有者，由 Forge/Fabric 在初始化时设置。
     * 客户端代码（Screen 等）通过此获取网络发送接口。
     */
    final class Holder {
        private static IMaidFileNetwork instance;

        public static IMaidFileNetwork get() {
            return instance;
        }

        public static void set(IMaidFileNetwork impl) {
            instance = impl;
        }

        private Holder() {
        }
    }

    /**
     * 服务端到客户端的回调接口。
     * 当前活跃的 Screen 实现此接口，由 Forge/Fabric 在收到 S2C 包时调用对应方法。
     */
    interface ClientHandler {
        void onMaidListReceived(List<MaidInfo> list);

        void onExportResultReceived(List<MaidFileData> dataList);

        void onFeedbackReceived(Component message);

        /**
         * 批量导入逐项结果（仅在请求了「导入后删除文件」时收到）。
         *
         * @param summary 汇总文案（直接显示）
         * @param spawned 与请求顺序严格对齐的成功标志；true=实体已生成，可安全删除对应本地文件
         */
        void onImportBatchResultReceived(Component summary, List<Boolean> spawned);

        /** OP 统一导出：收到服务端收集的所有在线玩家女仆列表 */
        void onServerExportListReceived(List<PlayerMaidGroup> groups);

        /** 收到女仆档案视图（请求档案后由服务端返回） */
        void onMaidProfileReceived(MaidProfileView view);

        /**
         * 备份管理：收到服务端返回的可浏览备份列表（已按权限过滤）。
         * 默认空实现，避免与备份界面无关的活跃界面被迫实现。
         */
        default void onBackupListReceived(List<BackupOwner> owners) {
        }

        /**
         * 备份管理：收到服务端回传的单条备份数据（{@code null} 表示权限不足或读取失败）。
         * 默认空实现。
         */
        default void onBackupExportReceived(MaidFileData data) {
        }
    }

    /**
     * 当前活跃的 ClientHandler 持有者。
     * Forge/Fabric 收到 S2C 包时，通过此获取当前活跃 Screen 并调用回调。
     */
    final class ClientHandlerHolder {
        private static ClientHandler instance;

        public static ClientHandler get() {
            return instance;
        }

        public static void set(ClientHandler handler) {
            instance = handler;
        }

        private ClientHandlerHolder() {
        }
    }
}
