package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.jerry.mekextras.api.ExtraUpgrade;
import mekanism.api.Upgrade;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentUpgrade;

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

	/** 升级查询异常日志限流器（ms 模式，5 秒冷却）— 静态工具类无 Level 访问 */
	private static final LogThrottle UPGRADE_WARN_THROTTLE = new LogThrottle(100L, 5000L);

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
			return component == null ? 0 : component.getUpgrades(ExtraUpgrade.STACK);
		} catch (Exception e) {
			UPGRADE_WARN_THROTTLE.tryLogMs(System.currentTimeMillis(), suppressed ->
					ProductiveBeesGenesis.LOGGER.warn("查询 STACK 升级数量失败，返回 0（已抑制 {} 次类似警告）", suppressed, e));
			return 0;
		}
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
			return component != null && component.getUpgrades(ExtraUpgrade.CREATIVE) > 0;
		} catch (Exception e) {
			UPGRADE_WARN_THROTTLE.tryLogMs(System.currentTimeMillis(), suppressed ->
					ProductiveBeesGenesis.LOGGER.warn("查询 CREATIVE 升级失败，返回 false（已抑制 {} 次类似警告）", suppressed, e));
			return false;
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
