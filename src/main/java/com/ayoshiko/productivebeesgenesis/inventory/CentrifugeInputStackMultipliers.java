package com.ayoshiko.productivebeesgenesis.inventory;

import java.util.function.IntSupplier;

import com.ayoshiko.productivebeesgenesis.config.CentrifugeConfigSection;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.StackMultiplierConfigSection;

/**
 * 离心机输入槽堆叠倍率助手
 * <br/>
 * 按离心机等级和工厂类型提供对应的 {@link IntSupplier}，
 * 供 {@link TieredInputSlot#productivebeesgenesis$setInputStackMultiplier} 使用。
 * <p>
 * 四类工厂使用不同的 tier 枚举：
 * <ul>
 *   <li>原版工厂 — {@code FactoryTier}（BASIC/ADVANCED/ELITE/ULTIMATE，ordinal 0-3）</li>
 *   <li>ME 工厂 — {@code ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE，ordinal 0-3）</li>
 *   <li>EME 工厂 — {@code EMExtraFactoryTier}（ABSOLUTE_OVERCLOCKED/...，ordinal 0-3）</li>
 *   <li>EM 工厂 — {@code FactoryTier}（Mixin 扩展，OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE，ordinal 4-8）</li>
 * </ul>
 * 通过 ordinal 索引统一映射，避免在核心类中引用 ME/EME 的 tier 枚举类。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP：仅负责 tier→config 的映射，不涉及槽位创建或 Mixin 逻辑</li>
 *   <li>DIP：返回 IntSupplier 而非固定值，支持配置热重载</li>
 * </ul>
 */
public final class CentrifugeInputStackMultipliers {

	private CentrifugeInputStackMultipliers() {
	}

	/**
	 * 获取离心机堆叠倍率子段(从 ServerConfig 委托链中查询)。
	 * <p>
	 * 历史访问路径 {@code ModConfig.SERVER.mekCentrifugeInputStackXxx} 在 v1.13.0 子段抽取后,
	 * 改为通过 {@code ModConfig.SERVER.centrifuge().stackMultiplier.mekCentrifugeInputStackXxx} 访问。
	 */
	private static StackMultiplierConfigSection stackMultiplier() {
		CentrifugeConfigSection centrifuge = ModConfig.SERVER.centrifuge();
		return centrifuge.stackMultiplier;
	}

	/** 基础离心机输入槽倍率 */
	public static IntSupplier forBasic() {
		return () -> stackMultiplier().mekCentrifugeInputStackBasic.get();
	}

	/**
	 * 原版工厂输入槽倍率（按 FactoryTier.ordinal 索引）
	 *
	 * @param ordinal FactoryTier 序号（0=BASIC, 1=ADVANCED, 2=ELITE, 3=ULTIMATE）
	 */
	public static IntSupplier forVanillaFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeInputStackAdvanced.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeInputStackElite.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeInputStackUltimate.get();
			default -> () -> stackMultiplier().mekCentrifugeInputStackBasic.get();
		};
	}

	/**
	 * ME 工厂输入槽倍率（按 ExtraFactoryTier.ordinal 索引）
	 *
	 * @param ordinal ExtraFactoryTier 序号（0=ABSOLUTE, 1=SUPREME, 2=COSMIC, 3=INFINITE）
	 */
	public static IntSupplier forMEFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeInputStackMeSupreme.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeInputStackMeCosmic.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeInputStackMeInfinite.get();
			default -> () -> stackMultiplier().mekCentrifugeInputStackMeAbsolute.get();
		};
	}

	/**
	 * EME 工厂输入槽倍率（按 EMExtraFactoryTier.ordinal 索引）
	 *
	 * @param ordinal EMExtraFactoryTier 序号（0=ABSOLUTE_OVERCLOCKED, 1=SUPREME_QUANTUM, 2=COSMIC_DENSE, 3=INFINITE_MULTIVERSAL）
	 */
	public static IntSupplier forEMEFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeInputStackEmeSupremeQuantum.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeInputStackEmeCosmicDense.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeInputStackEmeInfiniteMultiversal.get();
			default -> () -> stackMultiplier().mekCentrifugeInputStackEmeAbsoluteOverclocked.get();
		};
	}

	/**
	 * EM 工厂输入槽倍率（按 EM 扩展的 FactoryTier ordinal 偏移量索引）
	 * <p>
	 * EM 通过 Mixin 扩展 FactoryTier 枚举，添加 OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE，
	 * ordinal 4-8。此方法接收相对 ordinal（0-4），由调用方减去 4 后传入。
	 * <p>
	 * <b>线程安全</b>：仅在 {@code MekCompatHooks.isEvolvedMekanismLoaded()} 为 true 时调用，
	 * 调用方负责守卫，此方法不做 null 检查（EM 未加载时对应配置字段为 null）。
	 *
	 * @param ordinal EM 工厂相对序号（0=OVERCLOCKED, 1=QUANTUM, 2=DENSE, 3=MULTIVERSAL, 4=CREATIVE）
	 */
	public static IntSupplier forEMFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeInputStackEmQuantum.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeInputStackEmDense.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeInputStackEmMultiversal.get();
			case 4 -> () -> stackMultiplier().mekCentrifugeInputStackEmCreative.get();
			default -> () -> stackMultiplier().mekCentrifugeInputStackEmOverclocked.get();
		};
	}
}
