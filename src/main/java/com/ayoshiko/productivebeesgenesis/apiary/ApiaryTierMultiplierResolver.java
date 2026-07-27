package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.Optional;
import java.util.function.IntSupplier;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.common.tier.FactoryTier;

/**
 * 蜂箱等级堆叠倍率解析器
 * <br/>
 * 根据蜂箱类型（EME/ME/原版工厂/基础版）返回对应 IntSupplier 动态读取配置值，
 * 确保配置变更后无需重启即生效。构造期单次调用，线程安全。
 * <p>
 * <b>兼容性隔离设计</b>：ME/EME 等级的 instanceof 检查和 tier 字段访问会触发可选依赖类
 * （{@code ExtraFactoryTier}/{@code EMExtraFactoryTier}）的解析。为避免未安装对应模组时
 * {@code NoClassDefFoundError}，ME/EME 的具体逻辑移至隔离类
 * （{@link ApiaryTierMultiplierResolverMEDelegate}/{@link ApiaryTierMultiplierResolverDelegate}），
 * 仅在 {@link MekCompatHooks} 模组守卫通过后才调用，利用 JVM 延迟类加载保证安全。
 *
 * @since 1.9.0
 */
final class ApiaryTierMultiplierResolver {

	private ApiaryTierMultiplierResolver() {
	}

	/**
	 * 根据蜂箱等级获取堆叠倍率供应商
	 * <br/>
	 * 按蜂箱类型（EME/ME/原版工厂/基础版）返回对应 IntSupplier 动态读取配置值，
	 * 确保配置变更后无需重启即生效。构造期单次调用，线程安全。
	 * <p>
	 * <b>模组守卫顺序</b>：EME → ME → 原版工厂 → 默认。EME/ME 通过 {@link MekCompatHooks}
	 * 守卫后才调用隔离类，避免触发可选依赖类加载。
	 *
	 * @param tile 蜂箱方块实体
	 * @return 堆叠倍率供应商，传入 TieredOutputInventorySlot
	 */
	static IntSupplier getStackMultiplierForTier(TileEntityMekApiary tile) {
		// EME 等级：仅在 emextras 模组加载时调用隔离类
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			Optional<IntSupplier> emeSupplier = ApiaryTierMultiplierResolverDelegate.getStackMultiplierForEme(tile);
			if (emeSupplier.isPresent()) return emeSupplier.get();
		}
		// ME 等级：仅在 mekanism_extras 模组加载时调用隔离类
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			Optional<IntSupplier> meSupplier = ApiaryTierMultiplierResolverMEDelegate.getStackMultiplierForMe(tile);
			if (meSupplier.isPresent()) return meSupplier.get();
		}
		// 原版工厂等级 + EM 工厂等级（mekanism 必选依赖，无守卫需求）
		// EM 工厂复用 TileEntityMekApiaryFactory，但 tier 是 Mixin 扩展后的 FactoryTier（ordinal 4-8），
		// 编译时 EM 枚举常量不存在，故落入 default 分支，需在 default 中按 ordinal 偏移量判断
		if (tile instanceof TileEntityMekApiaryFactory factory) {
			FactoryTier t = factory.getTier();
			if (t != null) {
				return switch (t) {
					case BASIC -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackBasic.get());
					case ADVANCED -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackAdvanced.get());
					case ELITE -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackElite.get());
					case ULTIMATE -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackUltimate.get());
					default -> {
						// EM 工厂（Mixin 扩展 FactoryTier，ordinal 4-8）：仅在 EM 加载时访问 EM 配置
						// EM 未加载时安全降级返回 basic 倍率（理论上不会走到，因 ordinal 不会 >= 4）
						if (t.ordinal() >= 4 && MekCompatHooks.isEvolvedMekanismLoaded()) {
							yield switch (t.ordinal() - 4) {
								case 1 -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmQuantum.get());
								case 2 -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmDense.get());
								case 3 -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmMultiversal.get());
								case 4 -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmCreative.get());
								default -> () -> configStackValue(() -> ModConfig.SERVER.apiaryStackEmOverclocked.get());
							};
						}
						yield () -> configStackValue(() -> ModConfig.SERVER.apiaryStackBasic.get());
					}
				};
			}
		}
		// 默认：基础版蜂箱（TileEntityMekApiary 非工厂）或工厂构造期间tier未初始化
		return () -> configStackValue(() -> ModConfig.SERVER.apiaryStackBasic.get());
	}

	/**
	 * 配置读取守卫 — ModConfig.SERVER 为 null（配置 reload 期间）时返回默认值 1
	 *
	 * @param source 实际读取配置值的供应商（仅在 ModConfig.SERVER 非 null 时求值）
	 * @return 配置值；配置未就绪时返回 1（默认倍率）
	 */
	private static int configStackValue(IntSupplier source) {
		if (ModConfig.SERVER == null) return 1;
		return source.getAsInt();
	}
}
