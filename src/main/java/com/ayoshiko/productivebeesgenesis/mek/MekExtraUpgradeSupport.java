package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.jerry.mekextras.api.ExtraUpgrade;
import mekanism.api.Upgrade;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentUpgrade;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntUnaryOperator;

/**
	 * MEKExtras升级支持构建器
	 * <br/>
	 * <b>类加载安全</b>：本类直接引用{@link ExtraUpgrade}（MEKExtras的API类），
	 * 仅在{@link MekCompatHooks#isMekanismExtrasLoaded()}为true时由
	 * {@link MekUpgradeSupport#forMachine()}委托加载。
	 * 未安装MEKExtras时本类不会被加载，避免NoClassDefFoundError。
	 * <p>
	 * MEKExtras通过Mixin向{@link Upgrade}枚举注入STACK/CREATIVE，
	 * 并在{@code Upgrade.<clinit>}的TAIL回调中设置{@link ExtraUpgrade}的静态字段。
	 * 本类调用时Mixin已应用，ExtraUpgrade字段已填充。
	 */
final class MekExtraUpgradeSupport {

	/** Conservative STACK cap used while the server balance snapshot is unavailable. */
	static final int DEFAULT_STACK_UPGRADE_LIMIT = 8;

	/** 升级查询同时由服务端与客户端展示调用；弱键不保留已卸载机器。 */
	private static final Map<TileEntityMekanism, QueryFailure> STACK_FAILURES = new WeakHashMap<>();
	private static final Map<TileEntityMekanism, QueryFailure> CREATIVE_FAILURES = new WeakHashMap<>();
	private static volatile boolean stackFailuresPresent;
	private static volatile boolean creativeFailuresPresent;

	private static final class QueryFailure {
		final Throwable cause;
		long repeats;
		long lastSummaryNanos = System.nanoTime();

		QueryFailure(Throwable cause) {
			this.cause = cause;
		}
	}

	private MekExtraUpgradeSupport() {}

	/**
	 * 创建包含MEKExtras升级的机器升级支持
	 * <br/>
	 * 升级集合：SPEED/ENERGY/MUFFLING（原版）+ STACK(max=8)/CREATIVE(max=1)（MEKExtras）
	 * <p>
	 * 防御性检查：ExtraUpgrade字段由ME Mixin注入，正常情况下非null。
	 * 若Mixin异常导致字段为null，回退到{@link AttributeUpgradeSupport#DEFAULT_MACHINE_UPGRADES}，
	 * 保证至少支持原版SPEED/ENERGY/MUFFLING升级。
	 *
	 * @return 包含MEKExtras升级的AttributeUpgradeSupport实例
	 */
	static AttributeUpgradeSupport createMachineUpgrades() {
		// 防御性检查：Mixin注入失败时安全降级
		if (ExtraUpgrade.STACK == null || ExtraUpgrade.CREATIVE == null) {
			return AttributeUpgradeSupport.DEFAULT_MACHINE_UPGRADES;
		}
		return AttributeUpgradeSupport.create(
				Upgrade.SPEED, Upgrade.ENERGY, Upgrade.MUFFLING,
				ExtraUpgrade.STACK, ExtraUpgrade.CREATIVE
		);
	}

	/**
	 * 收集MEKExtras专属升级（STACK/CREATIVE）到给定列表
	 * <br/>
	 * 供 {@link MekUpgradeSupport#forMachine()} 合并升级列表使用，避免公共类直接引用MEKExtras类。
	 * 防御性检查：ExtraUpgrade字段由ME Mixin注入，若为null则跳过对应升级。
	 *
	 * @param upgrades 升级列表（会被修改）
	 */
	static void collectExtraUpgrades(java.util.List<Upgrade> upgrades) {
		if (ExtraUpgrade.STACK != null && !upgrades.contains(ExtraUpgrade.STACK)) {
			upgrades.add(ExtraUpgrade.STACK);
		}
		collectCreativeUpgrade(upgrades);
	}

	/**
	 * 仅收集CREATIVE升级（不含STACK）到给定列表
	 * <br/>
	 * 供 {@link MekUpgradeSupport#forApiary()} 使用：蜂箱自v2.x起支持CREATIVE升级
	 * （产出管线已有20-tick批量聚合，每tick产出的TPS风险可控），
	 * 但STACK升级仍被排除（2^n次产出倍率对蜂箱产量体系过高）。
	 * 防御性检查：ExtraUpgrade.CREATIVE由ME Mixin注入，若为null则跳过。
	 *
	 * @param upgrades 升级列表（会被修改）
	 */
	static void collectCreativeUpgrade(java.util.List<Upgrade> upgrades) {
		if (ExtraUpgrade.CREATIVE != null && !upgrades.contains(ExtraUpgrade.CREATIVE)) {
			upgrades.add(ExtraUpgrade.CREATIVE);
		}
	}

	/**
	 * 查询MEKExtras堆叠升级已安装数量
	 * <br/>
	 * STACK升级影响机器并行处理数，公式{@code 2^stackUpgrades}（满级8=256倍）。
	 * 供蜂箱（生产力倍率）和离心机（operationsPerTick）间接调用。
	 *
	 * @param tile 机器方块实体
	 * @return STACK升级数量（0-8），MEKExtras字段未注入或查询异常时返回0
	 */
	static int getStackUpgrades(TileEntityMekanism tile) {
		if (tile == null || ExtraUpgrade.STACK == null) return 0;
		try {
			TileComponentUpgrade component = tile.getComponent();
			if (component == null) return 0;
			int installed = component.getUpgrades(ExtraUpgrade.STACK);
			int effective = cappedStackUpgrades(installed, configuredStackLimit());
			if (stackFailuresPresent) completeQueryRecovery(tile, STACK_FAILURES, "STACK");
			return effective;
		} catch (Exception failure) {
			reportQueryFailure(tile, STACK_FAILURES, "STACK", failure);
			return 0;
		}
	}

	/**
	 * Applies the same effective cap used by the installation mixin to runtime calculations.
	 * Old saves can contain more STACK upgrades than the current server policy, so this must
	 * be enforced at every consumer rather than only when a new item is installed.
	 */
	static int cappedStackUpgrades(int installed, int configuredLimit) {
		return cappedStackUpgrades(installed, configuredLimit, BalanceConfig::centrifugeStackLimit);
	}

	/**
	 * Pure STACK cap policy. The resolver represents the selected balance profile and is
	 * injectable so profile behaviour can be tested without constructing a NeoForge config.
	 */
	static int cappedStackUpgrades(int installed, int configuredLimit,
			IntUnaryOperator profileLimitResolver) {
		int safeInstalled = Math.max(0, installed);
		int safeConfigured = Math.max(DEFAULT_STACK_UPGRADE_LIMIT, configuredLimit);
		int effectiveLimit = DEFAULT_STACK_UPGRADE_LIMIT;
		if (profileLimitResolver != null) {
			try {
				effectiveLimit = profileLimitResolver.applyAsInt(safeConfigured);
			} catch (RuntimeException ignored) {
				// A malformed or not-yet-loaded config falls back to the conservative cap.
			}
		}
		// BalanceConfig currently guarantees a minimum of eight. Keep the same conservative
		// fallback if a future resolver returns an invalid negative value.
		return Math.min(safeInstalled, Math.max(DEFAULT_STACK_UPGRADE_LIMIT, effectiveLimit));
	}

	private static int configuredStackLimit() {
		try {
			if (ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeMaxStackUpgrades != null) {
				return ModConfig.SERVER.mekCentrifugeMaxStackUpgrades.get();
			}
		} catch (RuntimeException ignored) {
			// Configuration may be queried while the server spec is still loading.
		}
		return DEFAULT_STACK_UPGRADE_LIMIT;
	}

	/**
	 * 查询是否安装了MEKExtras创造升级
	 * <br/>
	 * CREATIVE升级提供零能量消耗（离心机由MEKExtras Mixin自动处理getEnergyPerTick=0），
	 * 蜂箱因使用独立能耗计算需手动判断。
	 *
	 * @param tile 机器方块实体
	 * @return true 如果安装了CREATIVE升级，MEKExtras字段未注入或查询异常时返回false
	 */
	static boolean hasCreativeUpgrade(TileEntityMekanism tile) {
		if (tile == null || ExtraUpgrade.CREATIVE == null) return false;
		try {
			TileComponentUpgrade component = tile.getComponent();
			boolean installed = component != null && component.getUpgrades(ExtraUpgrade.CREATIVE) > 0;
			if (creativeFailuresPresent) completeQueryRecovery(tile, CREATIVE_FAILURES, "CREATIVE");
			return installed;
		} catch (Exception failure) {
			reportQueryFailure(tile, CREATIVE_FAILURES, "CREATIVE", failure);
			return false;
		}
	}

	private static void reportQueryFailure(TileEntityMekanism tile, Map<TileEntityMekanism, QueryFailure> failures,
			String upgrade, Throwable failure) {
		QueryFailure first;
		boolean firstReport;
		long repeats;
		synchronized (failures) {
			first = failures.get(tile);
			firstReport = first == null;
			if (firstReport) {
				first = new QueryFailure(failure);
				failures.put(tile, first);
			} else {
				first.repeats++;
				long now = System.nanoTime();
				if (now - first.lastSummaryNanos < 60_000_000_000L) return;
				first.lastSummaryNanos = now;
			}
			repeats = first.repeats;
			if (failures == STACK_FAILURES) stackFailuresPresent = true;
			else creativeFailuresPresent = true;
		}
		if (firstReport) {
			LogThrottle.warnWithCooldown("mek_extra_upgrade_query", 60_000L,
					"查询 {} 升级失败，位置 {}；沿现有升级查询结果保留首因", upgrade, tile.getBlockPos(), first.cause);
		} else {
			LogThrottle.warnWithCooldown("mek_extra_upgrade_query", 60_000L,
					"查询 {} 升级持续失败，位置 {}，首因 {}，期间重复 {} 次",
					upgrade, tile.getBlockPos(), first.cause.toString(), repeats);
		}
	}

	private static void completeQueryRecovery(TileEntityMekanism tile, Map<TileEntityMekanism, QueryFailure> failures,
			String upgrade) {
		QueryFailure first;
		synchronized (failures) {
			first = failures.remove(tile);
			if (failures == STACK_FAILURES) stackFailuresPresent = !failures.isEmpty();
			else creativeFailuresPresent = !failures.isEmpty();
		}
		if (first != null) {
			LogThrottle.infoWithCooldown("mek_extra_upgrade_query_recovery", 60_000L,
					"查询 {} 升级已恢复，位置 {}，首因 {}，期间重复 {} 次",
					upgrade, tile.getBlockPos(), first.cause.toString(), first.repeats);
		}
	}

	/**
	 * 判断Upgrade对象是否为MEKExtras的CREATIVE升级
	 * <br/>
	 * 用于recalculateUpgrades中识别CREATIVE升级类型，避免直接引用ExtraUpgrade导致类加载风险。
	 *
	 * @param upgrade 升级类型
	 * @return true 如果是CREATIVE升级且MEKExtras已加载
	 */
	static boolean isCreativeUpgrade(Upgrade upgrade) {
		return ExtraUpgrade.CREATIVE != null && upgrade == ExtraUpgrade.CREATIVE;
	}

	/**
	 * 判断Upgrade对象是否为MEKExtras的STACK升级
	 *
	 * @param upgrade 升级类型
	 * @return true 如果是STACK升级且MEKExtras已加载
	 */
	static boolean isStackUpgrade(Upgrade upgrade) {
		return ExtraUpgrade.STACK != null && upgrade == ExtraUpgrade.STACK;
	}
}
