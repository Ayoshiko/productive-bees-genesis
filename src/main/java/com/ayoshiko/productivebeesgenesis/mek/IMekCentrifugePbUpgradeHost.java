package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.level.Level;

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
 * {@link net.minecraft.world.level.block.entity.BlockEntity} 子类自动满足
 * {@link #getLevel()} 和 {@link #setChanged()} 方法签名，只需声明 implements。
 */
public interface IMekCentrifugePbUpgradeHost {

	/** 获取方块实体所在世界 */
	Level getLevel();

	/** 标记方块实体已变更（持久化） */
	void setChanged();
}
