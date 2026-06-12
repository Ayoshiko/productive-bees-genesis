package com.ayoshiko.productivebeesgenesis;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 资源蜜蜂：创世模组客户端专用初始化
 * <p>
 * 参考 Mekanism 的做法，使用仅客户端的 @Mod 类注册配置屏幕工厂，
 * 使模组界面中的"配置"按钮可点击打开配置文件界面。
 */
@Mod(value = ProductiveBeesGenesis.MOD_ID, dist = Dist.CLIENT)
public final class ProductiveBeesGenesisClient {

    public ProductiveBeesGenesisClient(ModContainer container) {
        // 注册配置屏幕工厂：ConfigurationScreen 会自动读取已注册的 CLIENT 配置
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
