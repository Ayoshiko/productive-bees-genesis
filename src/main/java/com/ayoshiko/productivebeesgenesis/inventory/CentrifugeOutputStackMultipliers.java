package com.ayoshiko.productivebeesgenesis.inventory;

import java.util.function.IntSupplier;

import com.ayoshiko.productivebeesgenesis.config.CentrifugeConfigSection;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.StackMultiplierConfigSection;

/**
 * 离心机输出槽堆叠倍率助手
 * <br/>
 * 按离心机等级和工厂类型提供对应的 {@link IntSupplier}，
 * 供 {@link TieredInputSlot#productivebeesgenesis$setInputStackMultiplier} 使用。
 * <p>
 * 与 {@link CentrifugeInputStackMultipliers} 对称，但使用输出槽的 {@code stack_multiplier}
 * 配置值（而非 {@code input_stack_multiplier}）。输出槽倍率默认为输入槽的 4 倍，
 * 因为蜜脾处理有成倍产物产出。
 * <p>
 * <b>注意</b>：{@link TieredInputSlot} 接口名称虽含 "Input"，但实际是通用的堆叠倍率
 * 注入机制，对输入槽和输出槽均适用。输出槽使用同一接口设置倍率，避免引入冗余的
 * 接口和 Mixin。
 * <p>
 * 四类工厂使用不同的 tier 枚举：
 * <ul>
 *   <li>原版工厂 — {@code FactoryTier}（BASIC/ADVANCED/ELITE/ULTIMATE，ordinal 0-3）</li>
 *   <li>ME 工厂 — {@code ExtraFactoryTier}（ABSOLUTE/SUPREME/COSMIC/INFINITE，ordinal 0-3）</li>
 *   <li>EME 工厂 — {@code EMExtraFactoryTier}（ABSOLUTE_OVERCLOCKED/...，ordinal 0-3）</li>
 *   <li>EM 工厂 — {@code FactoryTier}（Mixin 扩展，OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE，ordinal 4-8）</li>
 * </ul>
 *
 * @since 1.13.0
 * @author Ayoshiko
 * @see CentrifugeInputStackMultipliers 输入槽版本
 */
public final class CentrifugeOutputStackMultipliers {

	private CentrifugeOutputStackMultipliers() {
	}

	/**
	 * 获取离心机堆叠倍率子段(从 ServerConfig 委托链中查询)。
	 * <p>
	 * 历史访问路径 {@code ModConfig.SERVER.mekCentrifugeStackXxx} 在 v1.13.0 子段抽取后,
	 * 改为通过 {@code ModConfig.SERVER.centrifuge().stackMultiplier.mekCentrifugeStackXxx} 访问。
	 */
	private static StackMultiplierConfigSection stackMultiplier() {
		CentrifugeConfigSection centrifuge = ModConfig.SERVER.centrifuge();
		return centrifuge.stackMultiplier;
	}

	/**
	 * 原版工厂输出槽倍率（按 FactoryTier.ordinal 索引）
	 *
	 * @param ordinal FactoryTier 序号（0=BASIC, 1=ADVANCED, 2=ELITE, 3=ULTIMATE）
	 */
	public static IntSupplier forVanillaFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeStackAdvanced.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeStackElite.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeStackUltimate.get();
			default -> () -> stackMultiplier().mekCentrifugeStackBasic.get();
		};
	}

	/**
	 * ME 工厂输出槽倍率（按 ExtraFactoryTier.ordinal 索引）
	 *
	 * @param ordinal ExtraFactoryTier 序号（0=ABSOLUTE, 1=SUPREME, 2=COSMIC, 3=INFINITE）
	 */
	public static IntSupplier forMEFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeStackMeSupreme.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeStackMeCosmic.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeStackMeInfinite.get();
			default -> () -> stackMultiplier().mekCentrifugeStackMeAbsolute.get();
		};
	}

	/**
	 * EME 工厂输出槽倍率（按 EMExtraFactoryTier.ordinal 索引）
	 *
	 * @param ordinal EMExtraFactoryTier 序号（0=ABSOLUTE_OVERCLOCKED, 1=SUPREME_QUANTUM, 2=COSMIC_DENSE, 3=INFINITE_MULTIVERSAL）
	 */
	public static IntSupplier forEMEFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> stackMultiplier().mekCentrifugeStackEmeSupremeQuantum.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeStackEmeCosmicDense.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeStackEmeInfiniteMultiversal.get();
			default -> () -> stackMultiplier().mekCentrifugeStackEmeAbsoluteOverclocked.get();
		};
	}

	/**
	 * EM 工厂输出槽倍率（按 EM 扩展的 FactoryTier ordinal 偏移量索引）
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
			case 1 -> () -> stackMultiplier().mekCentrifugeStackEmQuantum.get();
			case 2 -> () -> stackMultiplier().mekCentrifugeStackEmDense.get();
			case 3 -> () -> stackMultiplier().mekCentrifugeStackEmMultiversal.get();
			case 4 -> () -> stackMultiplier().mekCentrifugeStackEmCreative.get();
			default -> () -> stackMultiplier().mekCentrifugeStackEmOverclocked.get();
		};
	}
}
