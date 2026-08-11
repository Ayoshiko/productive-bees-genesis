package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.core.BlockPos;

/**
	 * PB 升级提供者接口
	 * <br/>
	 * 抽象蜂箱与离心机共同的 PB 升级访问能力，使
	 * {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeTab}、
	 * {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeWindow}、
	 * {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeList}
	 * 等通用 GUI 组件可跨方块实体复用，遵循依赖倒置原则。
	 * <p>
	 * 实现方需保证：
	 * <ul>
	 *   <li>{@link #getPbUpgradeInstalledCount} 在客户端返回同步缓存值，服务端返回真实值</li>
	 *   <li>{@link #getBlockPos} 返回方块坐标（用于网络包发送，BlockEntity 子类自动满足）</li>
	 * </ul>
	 */
public interface IPbUpgradeProvider {

	/**
	 * 获取指定类型 PB 升级的已安装数量
	 * <br/>
	 * 客户端返回容器同步值，服务端返回 EnumMap 真实值。
	 * 内置升级（SIMULATION）返回 1，未安装返回 0。
	 *
	 * @param type 升级类型
	 * @return 已安装数量
	 */
	int getPbUpgradeInstalledCount(PbUpgradeType type);

	/**
	 * 获取指定类型 PB 升级的最大安装数量
	 *
	 * @param type 升级类型
	 * @return 最大安装数量（null 返回 0）
	 */
	int getPbUpgradeLimit(PbUpgradeType type);

	/**
	 * 获取安装进度（0.0~1.0，供 GUI 进度条使用）
	 *
	 * @return 安装进度
	 */
	float getClientInstallingProgress();

	/**
	 * 获取卸载进度（0.0~1.0，供 GUI 进度条使用）
	 * <br/>
	 * 卸载为瞬时操作时返回 0。
	 *
	 * @return 卸载进度
	 */
	float getClientUninstallingProgress();

	/**
	 * 是否支持指定升级类型
	 * <br/>
	 * 用于 {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbSupportedUpgrades}
	 * 过滤显示的升级类型。蜂箱支持所有非内置升级，离心机仅支持产量与时间系列。
	 *
	 * @param type 升级类型
	 * @return true 如果该方块实体支持此升级类型
	 */
	boolean isPbUpgradeSupported(PbUpgradeType type);

	/**
	 * 获取方块坐标（用于网络包发送）
	 * <br/>
	 * BlockEntity 子类自动满足此方法。
	 *
	 * @return 方块坐标
	 */
	BlockPos getBlockPos();

	/**
	 * 卸载指定类型的 PB 升级到输出槽
	 * <br/>
	 * 将一个已安装的指定类型 PB 升级卸载到机器的输出槽，供玩家拾取。
	 * 所有蜂箱和离心机实现类均已提供此方法，将其提升到接口以支持多态调用，
	 * 消除对 ME/EME 具体子类的 instanceof 硬引用（依赖倒置原则）。
	 *
	 * @param type 升级类型
	 * @return true 如果成功卸载一个升级；false 如果无升级可卸载或输出槽已满
	 * @since 2.0.0
	 */
	boolean extractPbUpgradeByType(PbUpgradeType type);

	/**
	 * 批量安装 PB 升级 — shift+右键时一次填满到上限
	 * <br/>
	 * 参照 MEK {@code TileComponentUpgrade.addUpgrades(upgrade, maxAvailable)} 实现，
	 * 由 {@code Math.min(limit - current, maxAvailable)} 决定实际安装数。
	 * 由 {@code AbstractUpgradeItemMixin} 拦截 PB 原版 {@code AbstractUpgradeItem.useOn} 后调用。
	 * <p>
	 * 默认返回 0（不安装），各机器实现类覆盖此方法以提供实际安装逻辑。
	 * 作为 default 方法以避免破坏既有实现，并使 Mixin 可通过 {@code instanceof IPbUpgradeProvider}
	 * 统一调用，无需引用具体子类（避免 ME/EME 类加载依赖）。
	 *
	 * @param type         升级类型
	 * @param maxAvailable 手上持有的最大数量（{@code stack.getCount()}）
	 * @return 实际安装数量（0 表示未安装）
	 * @since 2.0.0
	 */
	default int installPbUpgradeBulk(PbUpgradeType type, int maxAvailable) {
		return 0;
	}
}
