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

/** 桥不保管材料；保存所有者、显式自动化授权和 AE2 节点元数据。 */
public final class MeBridgeBlockEntity extends BlockEntity {
	private UUID owner;
	private CompoundTag savedNode = new CompoundTag();
	private net.minecraft.nbt.Tag invalidData;
	private MeBridgeLink link;
	private MeBridgeTarget connected;
	private boolean failed, automation;
	public MeBridgeBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.ME_BRIDGE_TILE.get(), pos, state); }
	public UUID owner() { return owner; }
	public void initializeOwner(UUID value) { if (owner == null && invalidData == null && !failed) { owner = value; setChanged(); } }
	public MeBridgeLink link() { return link; }
	public boolean automation() { return automation; }
	public boolean toggleAutomation(net.minecraft.server.level.ServerPlayer player) {
		if (!MeBridgeTarget.live(this) || owner == null || !owner.equals(player.getUUID()) || player.level() != level
				|| !player.isAlive() || player.isSpectator() || player.distanceToSqr(worldPosition.getCenter()) > 64
				|| !level.mayInteract(player, worldPosition)) return false;
		var target = MeBridgeTarget.inspect(this);
		if (!automation && (target.status() != MeBridgeStatus.ONLINE || !(target.host() instanceof com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity)
				|| !level.mayInteract(player, target.host().getBlockPos()))) return false;
		automation = !automation; setChanged();
		if (link != null) {
			try { link.tick(); } catch (RuntimeException | LinkageError error) { isolate(error); }
		}
		return true;
	}
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
		if (target.status() != MeBridgeStatus.ONLINE || failed) return;
		if (link != null) {
			try { link.tick(); if (automation) MeBridgeStorageService.watch(this); }
			catch (RuntimeException | LinkageError error) { fail(error); disconnect(); }
			return;
		}
		try {
			connected = target; link = MeBridgeIntegration.create(this, savedNode.copy());
			if (link != null) { invalidateCapabilities(); link.connect(); }
		} catch (RuntimeException | LinkageError error) { fail(error); disconnect(); }
	}
	public void isolate(Throwable error) { fail(error); disconnect(); }
	private void fail(Throwable error) {
		if (!failed) com.mojang.logging.LogUtils.getLogger().error("ME bridge isolated at {}", worldPosition, error);
		failed = true;
	}
	private void disconnect() {
		MeBridgeStorageService.remove(this);
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
		var data = new CompoundTag(); data.putInt("schema", 2); data.putBoolean("automation", automation); data.putUUID("owner", owner); data.put("node", savedNode.copy()); data.putBoolean("failed", failed); tag.put("meBridge", data);
	}
	@Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		disconnect(); super.loadAdditional(tag, registries); owner = null; savedNode = new CompoundTag(); invalidData = null; failed = false; automation = false;
		if (!tag.contains("meBridge")) return;
		try {
			var data = StrictNbt.compound(tag, "meBridge");
			int schema = StrictNbt.integer(data, "schema");
			if (schema != 1 && schema != 2 || !data.getAllKeys().equals(schema == 1 ? Set.of("schema", "owner", "node", "failed")
					: Set.of("schema", "owner", "node", "failed", "automation"))) throw new IllegalArgumentException("Unsupported ME bridge data");
			automation = schema == 2 && StrictNbt.bool(data, "automation");
			owner = StrictNbt.uuid(data, "owner"); savedNode = StrictNbt.compound(data, "node").copy(); failed = StrictNbt.bool(data, "failed");
		} catch (RuntimeException error) { invalidData = tag.get("meBridge").copy(); fail(error); }
	}
}
