package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 资源蜜蜂：创世模组主类
 * <br/>
 * 为资源蜜蜂模组添加万象创世蜜蜂，可产出所有其他蜜蜂的蜜脾
 * 通过Mixin注入原版离心机实现随机蜜脾产出
 *
 * @author Ayoshiko
 * @since 1.0.0
 */
@Mod(ProductiveBeesGenesis.MOD_ID)
public final class ProductiveBeesGenesis {
	public static final String MOD_ID = "productivebeesgenesis";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final String PRODUCTIVE_BEES_MOD_ID = "productivebees";

	public ProductiveBeesGenesis(IEventBus eventBus, ModContainer modContainer) {
		LOGGER.info("资源蜜蜂：创世模组初始化中...");

		// 注册客户端配置文件（模组界面可交互修改）
		modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);

		// 配置文件加载/重载时重新应用蜜蜂属性覆盖
		eventBus.addListener((ModConfigEvent.Loading event) -> {
			if (event.getConfig().getSpec() == ModConfig.CLIENT_SPEC) {
				BeeConfigApplier.applyOverrides();
			}
		});
		eventBus.addListener((ModConfigEvent.Reloading event) -> {
			if (event.getConfig().getSpec() == ModConfig.CLIENT_SPEC) {
				BeeConfigApplier.applyOverrides();
			}
		});

		eventBus.addListener(this::onCommonSetup);
		LOGGER.info("资源蜜蜂：创世模组初始化完成");
	}

	private void onCommonSetup(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			checkProductiveBeesCompatibility();
		});
	}

	private static void checkProductiveBeesCompatibility() {
		try {
			if (!net.neoforged.fml.ModList.get().isLoaded(PRODUCTIVE_BEES_MOD_ID)) {
				LOGGER.error("未检测到资源蜜蜂模组 (Productive Bees)，模组无法正常工作！");
				return;
			}
			LOGGER.info("资源蜜蜂模组兼容性检查通过");
		} catch (Exception e) {
			LOGGER.warn("检查资源蜜蜂模组兼容性时发生错误", e);
		}
	}
}
