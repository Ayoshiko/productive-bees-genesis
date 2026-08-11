package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.config.CentrifugeConfigSection;
import com.ayoshiko.productivebeesgenesis.config.FluidTankMultiplierConfigSection;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import java.util.function.IntSupplier;

/**
	 * 离心机流体罐容量倍率助手
	 * <br/>
	 * 按离心机等级和工厂类型提供对应的 {@link IntSupplier}，
	 * 供 {@link com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper#createFluidOutputHolder} 使用。
	 * <p>
	 * 与 {@link CentrifugeOutputStackMultipliers} / {@link CentrifugeInputStackMultipliers} 对称，
	 * 但用于流体输出罐的容量倍率，而非输入/输出槽堆叠倍率。
	 * <p>
	 * 容量公式：{@code fluidTankCapacity × processes × fluidTankMultiplier}
	 * （最终容量被 {@link Integer#MAX_VALUE} 截断）。
	 * <p>
	 * 默认倍率按 2^N 递增以匹配 STACK 升级的 2^16 倍并行：
	 * <ul>
	 *   <li>原版工厂：BASIC=1, ADVANCED=2, ELITE=4, ULTIMATE=8</li>
	 *   <li>ME 工厂：ABSOLUTE=16, SUPREME=32, COSMIC=64, INFINITE=128</li>
	 *   <li>EM 工厂：OVERCLOCKED=256, QUANTUM=512, DENSE=1024, MULTIVERSAL=2048, CREATIVE=4096</li>
	 *   <li>EME 工厂：ABSOLUTE_OVERCLOCKED=4096, SUPREME_QUANTUM=8192, COSMIC_DENSE=16384, INFINITE_MULTIVERSAL=32768</li>
	 * </ul>
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 * @see CentrifugeOutputStackMultipliers 输出槽堆叠倍率版本
	 * @see CentrifugeInputStackMultipliers 输入槽堆叠倍率版本
	 */
public final class CentrifugeFluidTankMultipliers {

	private CentrifugeFluidTankMultipliers() {
	}

	/**
	 * 获取离心机流体罐倍率子段(从 ServerConfig 委托链中查询)。
	 * <p>
	 * 历史访问路径 {@code ModConfig.SERVER.mekCentrifugeFluidTankXxx} 在 v2.0.0 子段抽取后,
	 * 改为通过 {@code ModConfig.SERVER.centrifuge().fluidTankMultiplier.mekCentrifugeFluidTankXxx} 访问。
	 */
	private static FluidTankMultiplierConfigSection fluidTankMultiplier() {
		CentrifugeConfigSection centrifuge = ModConfig.SERVER.centrifuge();
		return centrifuge.fluidTankMultiplier;
	}

	/**
	 * 原版工厂流体罐倍率（按 FactoryTier.ordinal 索引）
	 *
	 * @param ordinal FactoryTier 序号（0=BASIC, 1=ADVANCED, 2=ELITE, 3=ULTIMATE）
	 */
	public static IntSupplier forVanillaFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankAdvanced.get();
			case 2 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankElite.get();
			case 3 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankUltimate.get();
			default -> () -> fluidTankMultiplier().mekCentrifugeFluidTankBasic.get();
		};
	}

	/**
	 * ME 工厂流体罐倍率（按 ExtraFactoryTier.ordinal 索引）
	 *
	 * @param ordinal ExtraFactoryTier 序号（0=ABSOLUTE, 1=SUPREME, 2=COSMIC, 3=INFINITE）
	 */
	public static IntSupplier forMEFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankMeSupreme.get();
			case 2 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankMeCosmic.get();
			case 3 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankMeInfinite.get();
			default -> () -> fluidTankMultiplier().mekCentrifugeFluidTankMeAbsolute.get();
		};
	}

	/**
	 * EME 工厂流体罐倍率（按 EMExtraFactoryTier.ordinal 索引）
	 *
	 * @param ordinal EMExtraFactoryTier 序号（0=ABSOLUTE_OVERCLOCKED, 1=SUPREME_QUANTUM, 2=COSMIC_DENSE, 3=INFINITE_MULTIVERSAL）
	 */
	public static IntSupplier forEMEFactory(int ordinal) {
		return switch (ordinal) {
			case 1 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmeSupremeQuantum.get();
			case 2 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmeCosmicDense.get();
			case 3 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmeInfiniteMultiversal.get();
			default -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmeAbsoluteOverclocked.get();
		};
	}

	/**
	 * EM 工厂流体罐倍率（按 EM 扩展的 FactoryTier ordinal 偏移量索引）
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
			case 1 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmQuantum.get();
			case 2 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmDense.get();
			case 3 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmMultiversal.get();
			case 4 -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmCreative.get();
			default -> () -> fluidTankMultiplier().mekCentrifugeFluidTankEmOverclocked.get();
		};
	}
}
