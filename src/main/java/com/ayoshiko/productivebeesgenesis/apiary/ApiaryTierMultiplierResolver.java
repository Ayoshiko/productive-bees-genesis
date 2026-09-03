package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.config.FactoryTierConfigService;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierKey;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.common.tier.FactoryTier;

import java.util.Optional;
import java.util.function.IntSupplier;

/**
	 * 蜂箱等级堆叠倍率解析器
	 * <br/>
	 * 根据蜂箱类型（EME/ME/原版工厂/基础版）返回对应 IntSupplier 读取当前游戏会话快照。
	 * 快照通过原子引用发布，配置重载不会改变本次会话的容量倍率。
	 * <p>
	 * <b>兼容性隔离设计</b>：ME/EME 等级的 instanceof 检查和 tier 字段访问会触发可选依赖类
	 * （{@code ExtraFactoryTier}/{@code EMExtraFactoryTier}）的解析。为避免未安装对应模组时
	 * {@code NoClassDefFoundError}，ME/EME 的具体逻辑移至隔离类
	 * （{@link ApiaryTierMultiplierResolverMEDelegate}/{@link ApiaryTierMultiplierResolverDelegate}），
	 * 仅在 {@link MekCompatHooks} 模组守卫通过后才调用，利用 JVM 延迟类加载保证安全。
	 *
	 * @since 2.0.0
	 */
final class ApiaryTierMultiplierResolver {

	private ApiaryTierMultiplierResolver() {
	}

	private static IntSupplier supplier(FactoryTierKey tier) {
		return () -> FactoryTierConfigService.current().apiaryOutputStack(tier);
	}

	/**
	 * 根据蜂箱等级获取堆叠倍率供应商
	 * <br/>
	 * 按蜂箱类型（EME/ME/原版工厂/基础版）返回对应 IntSupplier 读取当前游戏会话快照。
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
				if (t.ordinal() >= 4 && MekCompatHooks.isEvolvedMekanismLoaded()) {
					return supplier(FactoryTierKey.evolvedMekanismFactory(t.ordinal() - 4));
				}
				return supplier(FactoryTierKey.vanillaFactory(t.ordinal()));
			}
		}
		// 默认：基础版蜂箱（TileEntityMekApiary 非工厂）或工厂构造期间tier未初始化
		return supplier(FactoryTierKey.BASIC);
	}
}
