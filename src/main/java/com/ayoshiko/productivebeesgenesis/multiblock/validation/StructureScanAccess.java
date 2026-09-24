package com.ayoshiko.productivebeesgenesis.multiblock.validation;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 只读查询边界；实现不得加载区块或发出世界变更，全部调用限同一主线程。 */
public interface StructureScanAccess {
	enum Availability { LOADED, UNLOADED, OUTSIDE_WORLD }
	record State(StructureRole role, Direction facing) {
		public State { Objects.requireNonNull(role); }
	}
	StructureScanStamp stamp();
	/** 先检查世界高度／边界，再检查该格所在区块；均不可触发区块加载。 */
	Availability availability(BlockPos position);
	/** 仅在 availability 返回 LOADED 后调用；无关方块使用 OTHER。 */
	State read(BlockPos position);
}
