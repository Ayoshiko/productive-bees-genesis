package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineVisualInbox;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineVisualSnapshot;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineActivityInbox;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** 只保存控制器身份；加载后必须重新扫描，不能从 NBT 恢复 FORMED 资格。 */
public final class MachineControllerEntity extends BlockEntity {
	private UUID machine = UUID.randomUUID(), owner;
	private long generation = 1;
	private boolean invalidIdentity;
	private MachineVisualSnapshot publishedVisual;
	private final MachineVisualInbox visualInbox = new MachineVisualInbox();
	private final MachineActivityInbox visualActivity = new MachineActivityInbox();
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
			boolean visualChanged = publishVisual((ServerLevel) level, projected, formed);
			if (projected != state) {
				// 纯展示位不改变形状；邻居形状查询会把边界外已卸载区块重新取回。
				level.setBlock(worldPosition, projected, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			} else if (visualChanged) level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		}
	}
	private boolean publishVisual(ServerLevel server, BlockState state, boolean formed) {
		int variant = -1;
		if (formed) {
			String id = handle.binding().orElseThrow().variant();
			var candidates = CombinedApiaryDefinition.DEFINITION.candidates();
			for (int i=0;i<candidates.size();i++) if (candidates.get(i).variant().equals(id)) { variant = i; break; }
		}
		var facing = state.getValue(MachinePartBlock.FACING); var status = state.getValue(MachineControllerBlock.STATUS);
		if (publishedVisual != null && publishedVisual.describes(machine, generation, variant, facing, status)) return false;
		long revision = MachineWorldService.nextVisualRevision(server);
		publishedVisual = revision == 0 ? null : new MachineVisualSnapshot(revision, machine, generation, variant, facing, status);
		return true;
	}
	public Optional<MachineVisualSnapshot> visualSnapshot() {
		if (level == null || !level.isClientSide || isRemoved()) return Optional.empty();
		// 客户端视距中心先移动，旧缓存槽可能稍后才触发卸载回调。
		var chunk = level.getChunkSource().getChunk(worldPosition.getX() >> 4, worldPosition.getZ() >> 4, ChunkStatus.FULL, false);
		if (chunk == null || chunk.getBlockEntity(worldPosition) != this) { visualInbox.clear(); visualActivity.invalidate(); return Optional.empty(); }
		return visualInbox.current().filter(frame -> frame.facing() == getBlockState().getValue(MachinePartBlock.FACING)
				&& frame.state() == getBlockState().getValue(MachineControllerBlock.STATUS)
				&& (frame.variant() >= 0) == getBlockState().getValue(MachinePartBlock.FORMED));
	}
	/** 只含展示事件的收件槽，永不编码进权威或区块同步 NBT。 */
	public MachineActivityInbox visualActivity() { return visualActivity; }
	@Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return publishedVisual == null ? new CompoundTag() : publishedVisual.encode();
	}
	@Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
	@Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
		if (level != null && level.isClientSide && !isRemoved()) visualInbox.accept(tag);
	}
	@Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
		handleUpdateTag(packet.getTag(), registries);
	}
	@Override public void onLoad() { super.onLoad(); MachineWorldService.watch(this); }
	@Override public void onChunkUnloaded() { visualInbox.clear(); visualActivity.invalidate(); publishedVisual = null; super.onChunkUnloaded(); }
	@Override public void setRemoved() { MachineWorldService.remove(this); visualInbox.clear(); visualActivity.invalidate(); publishedVisual = null; super.setRemoved(); }
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
