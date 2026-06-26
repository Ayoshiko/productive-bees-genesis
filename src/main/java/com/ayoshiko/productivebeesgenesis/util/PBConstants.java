package com.ayoshiko.productivebeesgenesis.util;

import net.minecraft.resources.ResourceLocation;

/**
 * Productive Bees 模组相关公共常量
 * <br/>
 * 统一存放与 ProductiveBees 交互中重复使用的字符串与 ResourceLocation 常量，
 * 消除多处重复定义造成的维护成本与不一致风险。
 * <p>
 * 设计原则：单一职责（SRP），仅存放常量，不包含逻辑方法。
 *
 * @since 1.0.0
 */
public final class PBConstants {

	/** Productive Bees 模组 ID */
	public static final String PRODUCTIVE_BEES_MOD_ID = "productivebees";

	/** 原版蜜蜂类型 ID（fallback 序列化使用） */
	public static final String VANILLA_BEE_TYPE = "minecraft:bee";

	/**
	 * 万象创世蜜蜂类型 ResourceLocation
	 * <br/>
	 * 用于与 ProductiveBees 的 ResourceLocation 类型 API 交互
	 * （如 BeeIngredient.getBeeType()、配置数据组件等）。
	 */
	public static final ResourceLocation MYRIADCREATIONS_TYPE =
			ResourceLocation.fromNamespaceAndPath(PRODUCTIVE_BEES_MOD_ID, "myriadcreations");

	/**
	 * 万象创世蜜蜂类型字符串形式
	 * <br/>
	 * 用于与 ProductiveBees 的 String 类型 API 交互
	 * （如 BeeIngredientFactory.getOrCreateList() 返回的 Map key）。
	 * 与 {@link #MYRIADCREATIONS_TYPE} 等价，避免运行时重复调用 toString()。
	 */
	public static final String MYRIADCREATIONS_TYPE_STRING = MYRIADCREATIONS_TYPE.toString();

	private PBConstants() {
		// 常量类禁止实例化
	}
}
