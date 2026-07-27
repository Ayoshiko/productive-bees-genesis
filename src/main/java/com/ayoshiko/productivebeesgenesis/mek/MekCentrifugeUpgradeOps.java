package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;

import mekanism.api.Upgrade;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.common.util.MekanismUtils;

import net.minecraft.network.chat.Component;

/**
 * 基础MEK离心机升级运算辅助类
 * <br/>
 * 从 {@link TileEntityMekCentrifuge} 抽取的 STACK/CREATIVE 升级相关计算逻辑，包括：
 * <ul>
 *   <li>{@link #calcOperationsPerTick} — STACK 升级并行倍率计算（2^stackUpgrades）</li>
 *   <li>{@link #calcTicksForBase} — CREATIVE 零耗时判定 + PB 时间倍率应用</li>
 *   <li>{@link #handleCreativeEnergy} — CREATIVE 无限电容量应用（recalculateUpgrades 委托）</li>
 *   <li>{@link #configureCachedRecipe} — CachedRecipe 能量需求与并行基数配置</li>
 *   <li>{@link #hasCreativeZeroTicks} — CREATIVE 瞬间完成判定（getTicksRequired 委托）</li>
 *   <li>{@link #getUpgradeInfo} — STACK/CREATIVE 升级显示信息统一入口</li>
 *   <li>{@link #hasCreativeUpgrade} — CREATIVE 升级安装检查门面</li>
 * </ul>
 * <p>
 * 设计原则：纯计算静态方法，不持有状态，参数显式传入（策略 A）。
 * 与 {@link MekUpgradeSupport} 的差异：MekUpgradeSupport 是跨机器类型的升级查询门面，
 * 本类专注基础离心机的升级运算编排，聚合 MekUpgradeSupport 和 {@link MekCreativeEnergyHelper}。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
final class MekCentrifugeUpgradeOps {

	private MekCentrifugeUpgradeOps() {}

	/**
	 * 计算 operationsPerTick — MEKExtras STACK 升级并行倍率
	 * <br/>
	 * maxOperations = 2^stackUpgrades（满级8=256倍并行处理）。
	 * 未安装 MEKExtras 时 stackUpgrades=0，maxOps=1，退化为原版行为。
	 *
	 * @param tile 离心机方块实体
	 * @return 每 tick 操作次数
	 */
	static int calcOperationsPerTick(TileEntityMekCentrifuge tile) {
		int maxOps = 1;
		int stackUpgrades = MekUpgradeSupport.getStackUpgrades(tile);
		if (stackUpgrades > 0) {
			// 位运算替代 Math.pow：stackUpgrades 最大 16，1 << 16 = 65536 不会溢出
			maxOps = 1 << stackUpgrades;
		}
		return MekanismUtils.getOperationsPerTick(tile, tile.baseTicksRequired(), maxOps);
	}

	/**
	 * 计算 getTicksForBase — CREATIVE 升级时返回0瞬间完成，否则应用 PB 时间倍率
	 * <br/>
	 * CREATIVE 升级时 PB 路径瞬间完成（processingTime=0，pbOperatingTicks++后1>=0立即成立）。
	 * 非 CREATIVE 时取 Mekanism 原版 ticks 与 PB 时间倍率的乘积，下限为1。
	 *
	 * @param tile            离心机方块实体
	 * @param baseTime        基础处理时间
	 * @param timeMultiplier  PB 时间倍率（来自 PB 升级处理器）
	 * @return 受升级影响的实际处理时间
	 */
	static int calcTicksForBase(TileEntityMekCentrifuge tile, int baseTime, double timeMultiplier) {
		if (MekUpgradeSupport.hasCreativeUpgrade(tile)) return 0;
		int mekTicks = MekanismUtils.getTicks(tile, baseTime);
		return Math.max(1, (int) Math.floor(mekTicks * timeMultiplier));
	}

	/**
	 * 处理 CREATIVE 升级的无限电容量应用
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#recalculateUpgrades} 在调用 super 后委托。
	 * <p>
	 * 两种触发路径：
	 * <ol>
	 *   <li>CREATIVE 升级刚安装：立即设置无限电容量并返回（1:1复刻 MEKExtras
	 *       MixinMachineEnergyContainer.mekanism_Extras$extraRecalculateUpgrades）</li>
	 *   <li>CREATIVE 已安装时 SPEED/ENERGY 升级变动：super.recalculateUpgrades(ENERGY)
	 *       会覆盖 Long.MAX_VALUE，需恢复无限电容量</li>
	 * </ol>
	 *
	 * @param tile    离心机方块实体
	 * @param upgrade 触发重计算的升级类型
	 */
	static void handleCreativeEnergy(TileEntityMekCentrifuge tile, Upgrade upgrade) {
		if (MekUpgradeSupport.isCreativeUpgrade(upgrade) && MekUpgradeSupport.hasCreativeUpgrade(tile)) {
			MekCreativeEnergyHelper.applyCreativeMaxEnergy(tile.energyContainer());
			return;
		}
		if (MekUpgradeSupport.hasCreativeUpgrade(tile)) {
			MekCreativeEnergyHelper.applyCreativeMaxEnergy(tile.energyContainer());
		}
	}

	/**
	 * 配置 CachedRecipe 的能量需求与并行基数
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#createNewCachedRecipe} 在调用 super 创建
	 * CachedRecipe 后委托。
	 * <ul>
	 *   <li>setEnergyRequirements — CREATIVE 升级时零能耗，否则使用 energyContainer.getEnergyPerTick()</li>
	 *   <li>setBaselineMaxOperations — 委托 getOperationsPerTick 使 STACK 并行生效</li>
	 * </ul>
	 *
	 * @param tile   离心机方块实体
	 * @param cached super.createNewCachedRecipe 返回的 CachedRecipe 实例
	 * @return 配置完成的 CachedRecipe
	 */
	static CachedRecipe<ItemStackToItemStackRecipe> configureCachedRecipe(
			TileEntityMekCentrifuge tile,
			CachedRecipe<ItemStackToItemStackRecipe> cached) {
		return cached
				.setEnergyRequirements(() -> MekUpgradeSupport.hasCreativeUpgrade(tile) ? 0L
						: tile.energyContainer().getEnergyPerTick(), tile.energyContainer())
				.setBaselineMaxOperations(tile::getOperationsPerTick);
	}

	/**
	 * 判定 CREATIVE 升级时是否应返回0 ticks（瞬间完成）
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#getTicksRequired} 委托。
	 * CREATIVE 安装时返回0瞬间完成（1:1复刻 MEKExtras TileEntityExtraFactory）。
	 *
	 * @param tile 离心机方块实体
	 * @return true 如果安装了 CREATIVE 升级
	 */
	static boolean hasCreativeZeroTicks(TileEntityMekCentrifuge tile) {
		return MekUpgradeSupport.hasCreativeUpgrade(tile);
	}

	/**
	 * 获取升级显示信息 — 委托 {@link MekUpgradeSupport#getUpgradeInfo}
	 * <br/>
	 * 统一 STACK/CREATIVE 升级显示（与工厂版共用）。
	 *
	 * @param tile    离心机方块实体
	 * @param upgrade 升级类型
	 * @return 升级信息组件列表
	 */
	static List<Component> getUpgradeInfo(TileEntityMekCentrifuge tile, Upgrade upgrade) {
		return MekUpgradeSupport.getUpgradeInfo(tile, upgrade);
	}

	/**
	 * 检查是否安装了 CREATIVE 升级 — 门面方法
	 * <br/>
	 * 手动检查零能耗，不依赖 MEKExtras Mixin（Mixin 加载时序可能失效）。
	 * 通过 {@link MekUpgradeSupport#hasCreativeUpgrade} 间接访问，
	 * MEKExtras 未加载时安全返回 false。
	 *
	 * @param tile 离心机方块实体
	 * @return true 如果安装了 CREATIVE 升级
	 */
	static boolean hasCreativeUpgrade(TileEntityMekCentrifuge tile) {
		return MekUpgradeSupport.hasCreativeUpgrade(tile);
	}
}
