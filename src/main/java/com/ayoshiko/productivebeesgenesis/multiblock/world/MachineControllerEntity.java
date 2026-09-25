package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 只保存控制器身份；加载后必须重新扫描，不能从 NBT 恢复 FORMED 资格。 */
public final class MachineControllerEntity extends BlockEntity {
	private UUID machine = UUID.randomUUID(), owner;
	private long generation = 1;
	private boolean invalidIdentity;
	MachineDirectory.Handle handle;
	boolean registrationFailed;
	long auditedAt;
	public MachineControllerEntity(BlockPos pos, BlockState state) { super(MachineContent.CONTROLLER_TILE.get(), pos, state); }
	public UUID machineId() { return machine; }
	public UUID ownerId() { return owner; }
	public long generation() { return generation; }
	public boolean readyIdentity() { return owner != null && !invalidIdentity && !registrationFailed; }
	public void initializeOwner(UUID value) { if (owner == null && !invalidIdentity) { owner = value; setChanged(); MachineWorldService.watch(this); } }
	public boolean allowed(Player player) { return owner != null && !invalidIdentity && !isRemoved() && player.level() == level && !player.isRemoved() && owner.equals(player.getUUID()) && player.distanceToSqr(worldPosition.getCenter()) <= 64; }
	public MachineDirectory.State status() { return invalidIdentity || registrationFailed ? MachineDirectory.State.RECOVERY : handle == null ? MachineDirectory.State.UNFORMED : handle.state(); }
	public boolean formed() { return handle != null && handle.binding().filter(binding -> MachineWorldService.active(level, binding)).isPresent(); }
	void publishState() {
		if (level != null && !level.isClientSide && !isRemoved() && level.hasChunk(worldPosition.getX() >> 4, worldPosition.getZ() >> 4) && level.getBlockEntity(worldPosition) == this) {
			var state = getBlockState(); boolean formed = formed();
			var projected = state.setValue(MachinePartBlock.FORMED, formed).setValue(MachineControllerBlock.STATUS, MachineVisualState.from(status()));
			if (projected != state) {
				// 纯展示位不改变形状；邻居形状查询会把边界外已卸载区块重新取回。
				level.setBlock(worldPosition, projected, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
	}
	@Override public void onLoad() { super.onLoad(); MachineWorldService.watch(this); }
	@Override public void setRemoved() { MachineWorldService.remove(this); super.setRemoved(); }
	@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries); tag.putUUID("machine", machine); tag.putLong("generation", generation);
		if (owner != null) tag.putUUID("owner", owner); tag.putBoolean("invalidIdentity", invalidIdentity); tag.putInt("layout", 1);
	}
	@Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		MachineWorldService.remove(this);
		super.loadAdditional(tag, registries);
		invalidIdentity = tag.getBoolean("invalidIdentity") || !tag.hasUUID("machine") || !tag.hasUUID("owner")
				|| !tag.contains("generation", Tag.TAG_LONG) || tag.getLong("generation") < 1
				|| !tag.contains("layout", Tag.TAG_INT) || tag.getInt("layout") != 1;
		if (tag.hasUUID("machine")) machine = tag.getUUID("machine"); generation = Math.max(1, tag.getLong("generation"));
		owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null; handle = null; registrationFailed = false; MachineWorldService.watch(this);
	}
}
