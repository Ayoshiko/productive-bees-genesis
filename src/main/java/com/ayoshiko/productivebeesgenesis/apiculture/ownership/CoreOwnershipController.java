package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.topology.TopologyScan;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.*;
import net.minecraft.world.level.block.entity.BlockEntity;
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
	public NetworkSavedData readyAuthority() { return busy() || status == Status.RECOVERY || domain == null || !domain.active() ? null : domain; }
	public boolean busy() { return core.network() != null && domain == null || transfer != null || records != null || candidates != null || scanClaims; }
	public boolean command(boolean join) {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || !core.validNetworkReference() || busy() || status == Status.RECOVERY) return false;
		var view = core.topology();
		if (join && (!ModConfig.SERVER.beeNetwork.enabled.get() || view == null || !view.valid())) return false;
		if (!join && (domain == null || domain.checkpoint().ownedMachines().activeCount() == 0)) return false;
		if (!join && domain.checkpoint().ownedMachines().values().stream().anyMatch(record -> !readyToReturn(level, record))) {
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
			var energyConfigured = domain.checkpoint().configureEnergy(ModConfig.SERVER.beeNetwork.energyCapacity.get());
			if (energyConfigured != domain.checkpoint()) { domain.publish(energyConfigured); directory.requestSave(domain); }
			if (transfer != null) { advanceTransfer(level); return; }
			if (records != null) {
				if (!records.hasNext()) { records = null; return; }
				var record = records.next(); var tile = tile(level, record.claim());
				if (record.phase() == OwnedMachineRecord.Phase.RETURNED && (tile == null || !OwnershipEndpoints.bound(tile))) return;
				if (tile == null) throw new IllegalStateException("Owned member is missing; assets retained at " + record.claim().origin());
				current = record.claim(); transfer = OwnershipTransferService.resume(directory, domain, current, OwnershipEndpoints.of(tile)); return;
			}
			if (scanClaims) {
				var view = core.topology(); if (view == null || !view.valid()) return;
				candidates = view.members().iterator(); topologyEpoch = view.epoch(); scanClaims = false;
			}
			if (candidates != null) {
				var view = core.topology();
				if (view == null || view.epoch() != topologyEpoch || !view.valid()) {
					if (action == Action.RECOVER) {
						// 加载期间的邻接事件可能使恢复扫描过期；等待新视图重扫，不能永久停在 REJECTED。
						candidates = null; scanClaims = true; status = Status.LOADING; return;
					}
					reject("Topology changed; completed transfers remain owned"); return;
				}
				if (!candidates.hasNext()) { candidates = null; return; }
				var candidate = candidates.next(); var memberPos = candidate.memberPosition();
				var memberOrigin = new Origin(level.dimension().location().toString(), memberPos.getX(), memberPos.getY(), memberPos.getZ());
				var claim = directory.claimAt(memberOrigin);
				if (claim != null) {
					if (!claim.network().equals(identity.networkId())) { reject("Member belongs to another network"); return; }
					if (domain.checkpoint().ownedMachines().get(claim.member()) != null) return;
					current = claim; transfer = OwnershipTransferService.resume(directory, domain, claim, OwnershipEndpoints.of(Objects.requireNonNull(tile(level, claim)))); return;
				}
				if (action != Action.JOIN) return;
				var member = level.getBlockEntity(memberPos);
				if (member == null) { reject("Member disappeared"); return; }
				try {
					var endpoint = OwnershipEndpoints.of(member);
					current = new MemberClaim(identity.networkId(), OwnershipEndpoints.memberId(member), UUID.randomUUID(), memberOrigin, BuiltInRegistries.BLOCK.getKey(member.getBlockState().getBlock()).toString());
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
		var endpoint = OwnershipEndpoints.of(member); transfer.advance(endpoint);
		if (transfer.step() == OwnershipTransferService.Step.RECOVERY) throw new IllegalStateException(transfer.failure());
		if (transfer.step() == OwnershipTransferService.Step.OWNED) {
			if (action == Action.RETURN) transfer.requestReturn(endpoint); else { transfer = null; current = null; }
		} else if (transfer.step() == OwnershipTransferService.Step.RETURNED) { transfer = null; current = null; }
	}
	private static boolean readyToReturn(ServerLevel level, OwnedMachineRecord record) {
		if (record.bees() != null && !record.bees().drained() || record.centrifuge() != null && !record.centrifuge().drained()) return false;
		if (record.combined() != null && !record.combined().drained()) return false;
		if (record.combined() == null && (record.bees() == null || record.bees().bees().stream().noneMatch(bee -> bee.progress() > 0))) return true;
		var member = tile(level, record.claim());
		if (member == null) return false;
		try { return OwnershipEndpoints.of(member).readyToReturn(record); }
		catch (IllegalArgumentException | IllegalStateException invalid) { return false; }
	}
	private static BlockEntity tile(ServerLevel level, MemberClaim claim) {
		var origin = claim.origin(); if (!origin.dimension().equals(level.dimension().location().toString()) || !level.hasChunk(origin.x() >> 4, origin.z() >> 4)) return null;
		return level.getBlockEntity(new BlockPos(origin.x(), origin.y(), origin.z()));
	}
	private void reject(String reason) { reject(Status.REJECTED, reason); }
	private void reject(Status state, String reason) {
		failure = reason; status = state; action = Action.IDLE; candidates = null; records = null;
		com.mojang.logging.LogUtils.getLogger().warn("Bee network core {} rejected transfer: {}", core.getBlockPos(), reason);
	}
}
