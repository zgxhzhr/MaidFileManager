package io.github.zgxhzhr.maidfm;

import io.github.zgxhzhr.maidfm.network.FabricClientPayloadRegistration;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口：只挂 S2C 接收器。
 *
 * <p>与服务端入口分开是必须的——这个类引用客户端类，专用服务器不得加载它。
 */
public class MaidFileClientFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Constants.LOG.info("[maid_file_manager] Client initializer START (Fabric)");
        FabricClientPayloadRegistration.registerClientReceivers();
        Constants.LOG.info("[maid_file_manager] Client initializer END");
    }
}