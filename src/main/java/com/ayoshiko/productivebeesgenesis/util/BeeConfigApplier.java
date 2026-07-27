package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.FastColor;

/**
 * 蜜蜂属性配置覆盖工具类
 * <p>
 * 被 {@link com.ayoshiko.productivebeesgenesis.mixin.BeeConfigReloadMixin} 和主类事件处理器调用。
 * 将 ModConfig 中的值写入 BeeReloadListener 已加载的 CompoundTag 上。
 */
public final class BeeConfigApplier {

	private BeeConfigApplier() {}

	/** 应用配置覆盖到万象创世蜜蜂数据 */
	public static void applyOverrides() {
		// 配置未加载时跳过（首次启动时常见）
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			return;
		}

		// 万象创世功能被禁用时，跳过配置覆盖
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) return;
		var config = ModConfig.SERVER;

		// 同步获取 data 引用并修改，防止 TOCTOU 竞态
		// （重载事件可能在 getData 之后、synchronized 之前替换数据，导致修改写入旧数据）
		synchronized (BeeConfigApplier.class) {
			CompoundTag data = BeeReloadListener.INSTANCE.getData(PBConstants.MYRIADCREATIONS_TYPE);
			if (data == null) {
				// 不静默返回：记录警告便于排查 BeeReloadListener 未加载或类型未注册的问题
				DevLog.warn("config_apply", "无法获取万象创世蜜蜂数据 (BeeReloadListener 未加载或类型 {} 未注册)，跳过配置覆盖",
						PBConstants.MYRIADCREATIONS_TYPE);
				return;
			}

			// 外观
			data.putInt("primaryColor", parseHexColor(config.primaryColor.get()));
			data.putInt("secondaryColor", parseHexColor(config.secondaryColor.get()));
			data.putInt("particleColor", parseHexColor(config.particleColor.get()));
			data.putInt("glowColor", parseHexColor(config.glowColor.get()));

			// 授粉
			data.putString("flowerItem", config.flowerItem.get());

			// PB 属性
			data.putString("weather_tolerance", config.weatherTolerance.get());
			data.putString("temper", config.temper.get());
			data.putString("behavior", config.behavior.get());
			data.putString("endurance", config.endurance.get());
			data.putString("productivity", config.productivity.get());

			// 基础属性
			data.putBoolean("createComb", config.createComb.get());
			data.putFloat("size", config.size.get().floatValue());
			data.putFloat("speed", config.speed.get().floatValue());
			data.putDouble("attack", config.attack.get());

			// 繁殖
			data.putString("breedingItem", config.breedingItem.get());
			data.putInt("breedingItemCount", config.breedingItemCount.get());
			data.putBoolean("selfbreed", config.selfbreed.get());

			// 环境耐受
			data.putBoolean("waterproof", config.waterproof.get());
			data.putBoolean("fireproof", config.fireproof.get());
		}
	}

	private static int parseHexColor(String hex) {
		try {
			String hexStr = hex.replace("#", "");
			if (hexStr.length() < 6) {
				DevLog.warn("config_apply", "无效的颜色值 '{}'，长度不足6位，使用默认金色", hex);
				return 0xFFFFD700; // 默认金色
			}
			int r = Integer.parseInt(hexStr.substring(0, 2), 16);
			int g = Integer.parseInt(hexStr.substring(2, 4), 16);
			int b = Integer.parseInt(hexStr.substring(4, 6), 16);
			return FastColor.ARGB32.color(r, g, b);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析颜色值 '{}' 失败，使用默认金色", hex, e);
			return 0xFFFFD700; // 默认金色
		}
	}
}