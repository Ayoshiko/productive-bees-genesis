package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileComponentEjector的Accessor Mixin
 * <br/>
 * 提供对私有字段tickDelay和tile的访问，用于输出槽弹出速度优化。
 * <p>
 * 原理：Mekanism的TileComponentEjector硬编码tickDelay=10（半秒），
 * 通过此Accessor可在Mixin中修改tickDelay，实现MEK离心机的快速弹出。
 */
@Mixin(TileComponentEjector.class)
public interface TileEntityEjectorAccessor {

	/** 获取当前tickDelay值 */
	@Accessor("tickDelay")
	int productivebeesgenesis$getTickDelay();

	/** 设置tickDelay值 */
	@Accessor("tickDelay")
	void productivebeesgenesis$setTickDelay(int value);

	/** 获取关联的TileEntity */
	@Accessor("tile")
	TileEntityMekanism productivebeesgenesis$getTile();
}
