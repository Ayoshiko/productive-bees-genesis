package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 桥不保存或移动材料；仅保存所有者和 AE2 自己的节点元数据。 */
public final class MeBridgeBlockEntity extends BlockEntity {
	private UUID owner;
	private CompoundTag savedNode = new CompoundTag();
	private net.minecraft.nbt.Tag invalidData;
	private MeBridgeLink link;
	private MeBridgeTarget connected;
	private boolean failed;
	public MeBridgeBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.ME_BRIDGE_TILE.get(), pos, state); }
	public UUID owner() { return owner; }
	public void initializeOwner(UUID value) { if (owner == null && invalidData == null && !failed) { owner = value; setChanged(); } }
	public MeBridgeLink link() { return link; }
	public boolean currentLink(MeBridgeLink expected) {
		if (failed || link != expected || connected == null) return false;
		var target = MeBridgeTarget.inspect(this);
		return target.status() == MeBridgeStatus.ONLINE && target.host() == connected.host() && Objects.equals(target.generation(), connected.generation());
	}
	public MeBridgeStatus status() {
		if (failed || invalidData != null) return MeBridgeStatus.FAILED;
		if (!MeBridgeIntegration.installed()) return MeBridgeStatus.ABSENT;
		var target = MeBridgeTarget.inspect(this); if (target.status() != MeBridgeStatus.ONLINE) return target.status();
		return link == null || !currentLink(link) ? MeBridgeStatus.BOOTING : link.status();
	}
	public void serverTick() {
		if (!MeBridgeTarget.live(this)) return;
		if (failed || invalidData != null) { disconnect(); return; }
		if (!MeBridgeIntegration.installed()) return;
		var target = MeBridgeTarget.inspect(this);
		if (target.status() != MeBridgeStatus.ONLINE || connected != null && (connected.host() != target.host() || !Objects.equals(connected.generation(), target.generation()))) disconnect();
		if (target.status() != MeBridgeStatus.ONLINE || failed || link != null) return;
		try {
			connected = target; link = MeBridgeIntegration.create(this, savedNode.copy());
			if (link != null) { invalidateCapabilities(); link.connect(); }
		} catch (RuntimeException | LinkageError error) { fail(error); disconnect(); }
	}
	private void fail(Throwable error) {
		if (!failed) com.mojang.logging.LogUtils.getLogger().error("ME bridge isolated at {}", worldPosition, error);
		failed = true;
	}
	private void disconnect() {
		var old = link; link = null; connected = null;
		if (old == null) return;
		try { savedNode = old.save(); } catch (RuntimeException | LinkageError error) { fail(error); }
		try { old.close(); } catch (RuntimeException | LinkageError error) { fail(error); }
		invalidateCapabilities(); setChanged();
	}
	@Override public void onChunkUnloaded() { disconnect(); super.onChunkUnloaded(); }
	@Override public void setRemoved() { disconnect(); super.setRemoved(); }
	@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		if (invalidData != null) { tag.put("meBridge", invalidData.copy()); return; }
		if (owner == null) return;
		if (link != null) { try { savedNode = link.save(); } catch (RuntimeException | LinkageError error) { fail(error); } }
		var data = new CompoundTag(); data.putInt("schema", 1); data.putUUID("owner", owner); data.put("node", savedNode.copy()); data.putBoolean("failed", failed); tag.put("meBridge", data);
	}
	@Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		disconnect(); super.loadAdditional(tag, registries); owner = null; savedNode = new CompoundTag(); invalidData = null; failed = false;
		if (!tag.contains("meBridge")) return;
		try {
			var data = StrictNbt.compound(tag, "meBridge");
			if (!data.getAllKeys().equals(Set.of("schema", "owner", "node", "failed")) || StrictNbt.integer(data, "schema") != 1) throw new IllegalArgumentException("Unsupported ME bridge data");
			owner = StrictNbt.uuid(data, "owner"); savedNode = StrictNbt.compound(data, "node").copy(); failed = StrictNbt.bool(data, "failed");
		} catch (RuntimeException error) { invalidData = tag.get("meBridge").copy(); fail(error); }
	}
}
