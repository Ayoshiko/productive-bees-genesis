package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络通信包注册
 * <br/>
 * MEK离心机重构后暂无自定义网络包，Mekanism基类自带侧面配置/升级同步。
 * 后续Phase如需自定义包再添加。
 */
public final class ModNetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ProductiveBeesGenesis.MOD_ID)
                .versioned("1");
        // 后续Phase按需注册自定义网络包
    }

    private ModNetwork() {}
}
