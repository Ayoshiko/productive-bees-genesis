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
	boolean references(MachineDirectory.Binding expected) { return binding == expected; }
	void publishFormed(boolean value) {
		if (!(level instanceof net.minecraft.server.level.ServerLevel server) || isRemoved()) return;
		if (!server.getServer().isSameThread()) throw new IllegalStateException("Part visuals require server thread");
		var state = getBlockState();
		if (state.getValue(MachinePartBlock.FORMED) != value) server.setBlock(worldPosition,
				state.setValue(MachinePartBlock.FORMED, value), net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
	}
	@Override public void onLoad() { super.onLoad(); publishFormed(bound()); }
	@Override public void onChunkUnloaded() { binding = null; super.onChunkUnloaded(); }
	public boolean bound() { return !isRemoved() && MachineWorldService.active(level, binding); }
	@Override public void setRemoved() { binding = null; MachineWorldService.changed(level, worldPosition); super.setRemoved(); }
}
