package com.example.examplemod.network;

import com.example.examplemod.data.MaidFileData;
import com.example.examplemod.data.MaidInfo;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 网络抽象层：客户端发送 C2S 包的接口。
 * 由 Forge/Fabric 各自实现，common 模块通过此接口发送包。
 */
public interface IMaidFileNetwork {
    /** 请求服务端返回附近属于当前玩家的女仆列表 */
    void sendRequestMaidList();

    /** 请求服务端导出指定 entityId 的女仆（服务端返回 MaidFileData） */
    void sendExportMaid(int entityId);

    /** 发送要导入的女仆文件数据给服务端 */
    void sendImportFile(MaidFileData data);

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

        void onExportResultReceived(MaidFileData data);

        void onFeedbackReceived(Component message);
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
