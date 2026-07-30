package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;

/**
 * 离心机 PB 升级处理器宿主接口
 * <br/>
 * 抽象 {@link MekCentrifugePbUpgradeHandler} 所需的最小访问能力，
 * 使基础离心机（{@link TileEntityMekCentrifuge}）与三个工厂版离心机
 * （{@link AbstractMekCentrifugeFactory}、
 * {@link TileEntityExtraMekCentrifugeFactory}、
 * {@link TileEntityEMExtraMekCentrifugeFactory}）
 * 可共用同一个 handler，遵循依赖倒置原则。
 * <p>
 * {@link BlockEntity} 子类自动满足
 * {@link #getLevel()} 和 {@link #setChanged()} 方法签名，只需声明 implements。
 * <p>
 * F3 修复：新增 {@link #getMekSpeedUpgrades()} 默认方法，
 * 通过 cast 到 {@link TileEntityMekanism} 访问升级组件，避免 4 个实现类重复编写相同代码。
 */
public interface IMekCentrifugePbUpgradeHost {

	/** 获取方块实体所在世界 */
	Level getLevel();

	/** 标记方块实体已变更（持久化） */
	void setChanged();

	/**
	 * 获取 MEK 速度升级已安装数量
	 * <br/>
	 * 默认实现通过 cast 到 {@link TileEntityMekanism} 访问升级组件。
	 * 所有实现类均继承自 Mekanism 机器基类，cast 总是成功。
	 *
	 * @return 速度升级数量（0-8），非 Mekanism 机器时返回 0
	 */
	default int getMekSpeedUpgrades() {
		if (this instanceof BlockEntity be && be instanceof TileEntityMekanism mekTile) {
			var component = mekTile.getComponent();
			if (component != null) {
				return component.getUpgrades(Upgrade.SPEED);
			}
		}
		return 0;
	}
}
