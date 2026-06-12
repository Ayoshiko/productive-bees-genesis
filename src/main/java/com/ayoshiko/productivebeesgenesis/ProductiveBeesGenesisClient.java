package com.ayoshiko.productivebeesgenesis;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 资源蜜蜂：创世模组客户端专用初始化
 * <p>
 * 负责：
 * <ul>
 *   <li>配置屏幕工厂注册</li>
 * </ul>
 * 蜜脾材质覆盖：通过 assets/productivebees/ 下的模型文件自动覆盖PB材质
 */
@Mod(value = ProductiveBeesGenesis.MOD_ID, dist = Dist.CLIENT)
public final class ProductiveBeesGenesisClient {

    public ProductiveBeesGenesisClient(ModContainer container) {
        // 注册配置屏幕工厂
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}