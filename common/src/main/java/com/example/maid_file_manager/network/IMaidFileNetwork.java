package com.example.maid_file_manager.network;

import com.example.maid_file_manager.data.MaidFileData;
import com.example.maid_file_manager.data.MaidInfo;
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

    /** 发送要导入的女仆文件数据给服务端（单条）；keepBaubles=true 表示客户端希望保留饰品导入 */
    void sendImportFile(MaidFileData data, boolean keepBaubles);

    /** 批量发送要导入的女仆文件数据给服务端；keepBaubles=true 表示客户端希望保留饰品导入 */
    void sendImportFiles(List<MaidFileData> dataList, boolean keepBaubles);

    /** 上报本客户端对「服务端统一导出」的同意状态（默认 false，服务端按 UUID 记录） */
    void sendClientConsent(boolean allow);

    /** 请求服务端修改服务端配置（服务端校验 OP 权限后写文件并广播同步） */
    void sendSetServerConfig(String key, boolean value);

    /** OP 请求服务端返回所有在线玩家的女仆列表（以各玩家为中心搜索，供统一导出浏览） */
    void sendRequestServerExportList();

    /** OP 请求服务端代为导出：按玩家分组的女仆 entityId，导出文件保存到服务端磁盘 maid_exports/<玩家名>/ */
    void sendServerExportBatch(List<PlayerExportRequest> groups);

    /** 统一导出浏览：某玩家为中心的女仆列表（服务端收集） */
    record PlayerMaidGroup(String playerName, boolean consented, List<MaidInfo> maids) {
    }

    /** 统一导出提交：某玩家名下要导出的女仆 entityId 集合 */
    record PlayerExportRequest(String playerName, List<Integer> entityIds) {
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

        /** OP 统一导出：收到服务端收集的所有在线玩家女仆列表 */
        void onServerExportListReceived(List<PlayerMaidGroup> groups);
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
