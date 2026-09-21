package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 本模组机器的组件维护入口；生产批次仍由 ticker/JDTE 共享的门控负责。 */
public final class GenesisMachineTicker {

	private GenesisMachineTicker() {}

	public static void tickServer(Level level, BlockPos pos, BlockState state, TileEntityMekanism tile) {
		if (tile instanceof IAe2OutputHostBase host
				&& !host.productivebeesgenesis$getAe2StateHolder().getTickAccelTracker()
						.beginMachineTick(level.getGameTime())) {
			return;
		}
		// 独立于生产门控：JDTE 先 flush 时，本刻仍必须执行一次组件维护。
		TileEntityMekanism.tickServer(level, pos, state, tile);
	}
}
