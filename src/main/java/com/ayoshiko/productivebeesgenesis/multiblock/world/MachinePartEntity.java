package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 部件只持瞬态引用；每次访问复核目录，不保存或 tick 另一份机器。 */
public final class MachinePartEntity extends BlockEntity {
	private MachineDirectory.Binding binding;
	public MachinePartEntity(BlockPos pos, BlockState state) { super(MachineContent.PART_TILE.get(), pos, state); }
	public void bind(MachineDirectory.Binding binding) { this.binding = binding; }
	public boolean bound() { return !isRemoved() && MachineWorldService.active(level, binding); }
	@Override public void setRemoved() { binding = null; MachineWorldService.changed(level, worldPosition); super.setRemoved(); }
}
