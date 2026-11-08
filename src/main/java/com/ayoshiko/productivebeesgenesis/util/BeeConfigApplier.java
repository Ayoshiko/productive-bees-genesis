package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.ServerConfigRuntime;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.FastColor;

/**
	 * 蜜蜂属性配置覆盖工具类
	 * <p>
	 * 被 {@link com.ayoshiko.productivebeesgenesis.mixin.BeeConfigReloadMixin} 和主类事件处理器调用。
	 * 配置事件只构造封闭快照，当前会话在游戏线程写入 BeeReloadListener 的 CompoundTag。
	 */
public final class BeeConfigApplier {

	private BeeConfigApplier() {}

	/** 数据包重新装载后，在当前会话的游戏线程重用已发布配置。 */
	public static void applyOverrides() {
		ServerConfigRuntime.reapplyBeeOverrides();
	}

	/** 只读取配置并构造独占数据，不接触 PB 的共享蜜蜂 NBT。 */
	public static Snapshot captureOverrides() {
		if (!ModConfig.areServerSpecsLoaded() || !ModConfig.SERVER.myriadCreationsEnabled.get()) {
			return new Snapshot(null);
		}
		var config = ModConfig.SERVER;
		CompoundTag data = new CompoundTag();
		// 外观
		data.putInt("primaryColor", parseHexColor(config.primaryColor.get()));
		data.putInt("secondaryColor", parseHexColor(config.secondaryColor.get()));
		data.putInt("particleColor", parseHexColor(config.particleColor.get()));
		data.putInt("glowColor", parseHexColor(config.glowColor.get()));
		var appearance = config.beeAttributes();
		data.putInt("tertiaryColor", appearance.tertiaryColor.get().isEmpty()
				? data.getInt("primaryColor") : parseHexColor(appearance.tertiaryColor.get()));
		data.putFloat("pollinatedSize", appearance.pollinatedSize.get() == 0
				? config.size.get().floatValue() : appearance.pollinatedSize.get().floatValue());
		data.putBoolean("useGlowLayer", appearance.useGlowLayer.get());
		optional(data, "attackResponse", appearance.attackResponse.get().equals("none") ? "" : appearance.attackResponse.get());
		data.putString("renderTransform", appearance.renderTransform.get());
		ListTag immunity = new ListTag();
		appearance.invulnerability.get().forEach(value -> immunity.add(StringTag.valueOf(value)));
		data.put("invulnerability", immunity);
		data.putString("renderer", appearance.renderer.get());
		optional(data, "model", appearance.model.get());
		optional(data, "animation", appearance.animation.get());
		optional(data, "beeTexture", appearance.beeTexture.get());
		data.putString("particleType", appearance.particleType.get());
		optional(data, "nestingPreference", appearance.nestingPreference.get());
		optional(data, "postPollination", appearance.postPollination.get().equals("none") ? "" : appearance.postPollination.get());
		appearance.traits.forEach((key, value) -> data.putBoolean(key, value.get()));

		// 授粉
		for (String field : new String[]{"flowerItem", "flowerBlock", "flowerTag", "flowerFluid"}) {
			data.remove(field);
		}
		data.putBoolean("inverseFlower", false);
		data.putString("flowerType", appearance.flowerType.get());
		if (!appearance.flowerTag.get().isEmpty()) {
			String tag = appearance.flowerTag.get();
			data.putString("flowerTag", tag.startsWith("!") ? tag.substring(1) : tag);
			data.putBoolean("inverseFlower", tag.startsWith("!"));
		} else if (!appearance.flowerBlock.get().isEmpty()) {
			data.putString("flowerBlock", appearance.flowerBlock.get());
		} else if (!appearance.flowerFluid.get().isEmpty()) {
			data.putString("flowerFluid", appearance.flowerFluid.get());
		} else {
			data.putString("flowerItem", config.flowerItem.get());
		}

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
		return new Snapshot(data);
	}

	/** 内部 NBT 不向调用方暴露；仅由当前会话在游戏线程应用。 */
	public static final class Snapshot {
		private final CompoundTag values;

		private Snapshot(CompoundTag values) {
			this.values = values;
		}

		public void apply() {
			if (values == null) {
				return;
			}
			CompoundTag data = BeeReloadListener.INSTANCE.getData(PBConstants.MYRIADCREATIONS_TYPE);
			if (data == null) {
				DevLog.warn("config_apply", "无法获取万象创世蜜蜂数据 (类型 {} 未注册)，跳过配置覆盖",
						PBConstants.MYRIADCREATIONS_TYPE);
				return;
			}
			for (String key : OPTIONAL_FIELDS) {
				data.remove(key);
			}
			data.merge(values.copy());
		}
	}

	private static final String[] OPTIONAL_FIELDS = {
			"attackResponse", "model", "animation", "beeTexture", "nestingPreference", "postPollination",
			"flowerItem", "flowerBlock", "flowerTag", "flowerFluid"
	};

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

	private static void optional(CompoundTag data, String key, String value) {
		if (value.isEmpty()) {
			data.remove(key);
		} else {
			data.putString(key, value);
		}
	}
}
