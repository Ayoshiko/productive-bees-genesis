package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.topology.TopologyScan;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.*;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** 核心的服务器会话；队列每次仅处理一台，权威资产始终留在世界目录与域中。 */
public final class CoreOwnershipController {
	public enum Status { STANDALONE, LOADING, JOINING, MANAGED, RETURNING, RECOVERY, REJECTED, UNSUPPORTED_MEMBER, EXTERNAL_PENDING, INVALID_ASSETS }
	private enum Action { RECOVER, JOIN, RETURN, IDLE }
	private final NetworkCoreBlockEntity core;
	private NetworkOpenHandle opening;
	private NetworkSavedData domain;
	private Action action = Action.RECOVER;
	private Status status = Status.STANDALONE;
	private Iterator<OwnedMachineRecord> records;
	private Iterator<TopologyScan.Node> candidates;
	private long topologyEpoch;
	private MemberClaim current;
	private OwnershipTransferService transfer;
	private String failure = "";
	private boolean scanClaims;
	public CoreOwnershipController(NetworkCoreBlockEntity core) { this.core = core; }
	public Status status() { return status; }
	public String failure() { return failure; }
	public boolean busy() { return core.network() != null && domain == null || transfer != null || records != null || candidates != null || scanClaims; }
	public boolean command(boolean join) {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || !core.validNetworkReference() || busy() || status == Status.RECOVERY) return false;
		var view = core.topology();
		if (join && (!ModConfig.SERVER.beeNetwork.enabled.get() || view == null || !view.valid())) return false;
		if (!join && (domain == null || domain.checkpoint().ownedMachines().activeCount() == 0)) return false;
		if (!join && domain.checkpoint().ownedMachines().values().stream().anyMatch(record -> record.bees() != null && !record.bees().drained()
				|| record.centrifuge() != null && !record.centrifuge().drained())) {
			failure = "Settle held production work before returning members"; status = Status.REJECTED; return false;
		}
		failure = "";
		if (core.network() == null) {
			var pos = core.getBlockPos();
			var identity = new NetworkIdentity(UUID.randomUUID(), core.controller(), core.owner(), 0,
					new Origin(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()));
			core.bindNetwork(identity); opening = NetworkPersistence.directory(level.getServer()).create(identity);
		}
		action = join ? Action.JOIN : Action.RETURN;
		if (join) { candidates = view.members().iterator(); topologyEpoch = view.epoch(); }
		else records = domain.checkpoint().ownedMachines().values().iterator();
		status = join ? Status.JOINING : Status.RETURNING; return true;
	}
	public void advance() {
		if (!(core.getLevel() instanceof ServerLevel level) || core.network() == null || status == Status.RECOVERY) return;
		try {
			var identity = core.network(); var pos = core.getBlockPos(); var origin = identity.origin();
			if (!identity.ownerId().equals(core.owner()) || !identity.controllerId().equals(core.controller())
					|| !origin.dimension().equals(level.dimension().location().toString()) || origin.x() != pos.getX() || origin.y() != pos.getY() || origin.z() != pos.getZ()) throw new IllegalStateException("Copied or displaced core identity");
			var directory = NetworkPersistence.directory(level.getServer());
			if (opening == null) { opening = directory.loadExisting(identity); status = Status.LOADING; }
			if (opening.pending()) return;
			if (domain == null) {
				domain = opening.ready();
				if (action == Action.RECOVER) { records = domain.checkpoint().ownedMachines().values().iterator(); scanClaims = true; }
			}
			if (transfer != null) { advanceTransfer(level); return; }
			if (records != null) {
				if (!records.hasNext()) { records = null; return; }
				var record = records.next(); var tile = tile(level, record.claim());
				if (record.phase() == OwnedMachineRecord.Phase.RETURNED && (tile == null || !tile.getPersistentData().contains(MemberBinding.TAG))) return;
				if (tile == null) throw new IllegalStateException("Owned member is missing; assets retained at " + record.claim().origin());
				current = record.claim(); transfer = OwnershipTransferService.resume(directory, domain, current, new BlockEntityOwnershipEndpoint(tile)); return;
			}
			if (scanClaims) {
				var view = core.topology(); if (view == null) return;
				candidates = view.members().iterator(); topologyEpoch = view.epoch(); scanClaims = false;
			}
			if (candidates != null) {
				var view = core.topology();
				if (view == null || view.epoch() != topologyEpoch || !view.valid()) { reject("Topology changed; completed transfers remain owned"); return; }
				if (!candidates.hasNext()) { candidates = null; return; }
				var candidate = candidates.next(); var memberPos = candidate.position();
				var memberOrigin = new Origin(level.dimension().location().toString(), memberPos.getX(), memberPos.getY(), memberPos.getZ());
				var claim = directory.claimAt(memberOrigin);
				if (claim != null) {
					if (!claim.network().equals(identity.networkId())) { reject("Member belongs to another network"); return; }
					if (domain.checkpoint().ownedMachines().get(claim.member()) != null) return;
					current = claim; transfer = OwnershipTransferService.resume(directory, domain, claim, new BlockEntityOwnershipEndpoint(Objects.requireNonNull(tile(level, claim)))); return;
				}
				if (action != Action.JOIN) return;
				if (!(level.getBlockEntity(memberPos) instanceof TileEntityMekanism member)) { reject("Member disappeared"); return; }
				if (!MachineAssetStore.supports(member)) { reject(Status.UNSUPPORTED_MEMBER, "No verified asset adapter for " + member.getBlockState()); return; }
				if (!new MachineAssetStore(member).prepared()) { reject(Status.EXTERNAL_PENDING, "External output settlement still pending"); return; }
				try {
					var endpoint = new BlockEntityOwnershipEndpoint(member);
					current = new MemberClaim(identity.networkId(), UUID.randomUUID(), UUID.randomUUID(), memberOrigin, BuiltInRegistries.BLOCK.getKey(member.getBlockState().getBlock()).toString());
					transfer = OwnershipTransferService.begin(directory, domain, current, endpoint);
				} catch (IllegalArgumentException | IllegalStateException error) { reject(Status.INVALID_ASSETS, error.getMessage()); }
				return;
			}
			if (action != Action.IDLE) {
				action = Action.IDLE;
				status = domain.checkpoint().ownedMachines().activeCount() == 0 ? Status.STANDALONE : Status.MANAGED;
			}
		} catch (RuntimeException error) {
			failure = error.toString(); status = Status.RECOVERY;
			com.mojang.logging.LogUtils.getLogger().error("Bee network core {} paused: {}", core.getBlockPos(), failure);
		}
	}
	private void advanceTransfer(ServerLevel level) {
		var member = tile(level, current); if (member == null) throw new IllegalStateException("Transfer member removed; ownership retained");
		var endpoint = new BlockEntityOwnershipEndpoint(member); transfer.advance(endpoint);
		if (transfer.step() == OwnershipTransferService.Step.RECOVERY) throw new IllegalStateException(transfer.failure());
		if (transfer.step() == OwnershipTransferService.Step.OWNED) {
			if (action == Action.RETURN) transfer.requestReturn(endpoint); else { transfer = null; current = null; }
		} else if (transfer.step() == OwnershipTransferService.Step.RETURNED) { transfer = null; current = null; }
	}
	private static TileEntityMekanism tile(ServerLevel level, MemberClaim claim) {
		var origin = claim.origin(); if (!origin.dimension().equals(level.dimension().location().toString()) || !level.hasChunk(origin.x() >> 4, origin.z() >> 4)) return null;
		return level.getBlockEntity(new BlockPos(origin.x(), origin.y(), origin.z())) instanceof TileEntityMekanism tile ? tile : null;
	}
	private void reject(String reason) { reject(Status.REJECTED, reason); }
	private void reject(Status state, String reason) {
		failure = reason; status = state; action = Action.IDLE; candidates = null; records = null;
		com.mojang.logging.LogUtils.getLogger().warn("Bee network core {} rejected transfer: {}", core.getBlockPos(), reason);
	}
}
