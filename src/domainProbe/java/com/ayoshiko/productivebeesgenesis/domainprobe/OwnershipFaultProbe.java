package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class OwnershipFaultProbe {
	private record Fixture(BlockPos pos, MemberClaim claim, CompoundTag original, AssetImage image) { }
	private static final List<Fixture> fixtures = new ArrayList<>();
	private static NetworkOpenHandle opening;
	private static NetworkSavedData domain;
	private static OwnershipTransferService transfer;
	private static int index, started;
	static void start(MinecraftServer server) {
		var level = server.overworld(); level.setChunkForced(2, 0, true); var owner = UUID.randomUUID();
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), owner, 0, new Origin("minecraft:overworld", 33, 130, 8));
		for (int i = 0; i < 8; i++) {
			var block = i < 4 ? ModBlocks.MEK_CENTRIFUGE.get() : ModBlocks.MEK_APIARY.get();
			var pos = new BlockPos(33 + i, 110, 8); level.setBlockAndUpdate(pos, block.defaultBlockState());
			var tile = (TileEntityMekanism) level.getBlockEntity(pos); tile.setOwnerUUID(owner); OwnershipProbe.seed(tile, level);
			var endpoint = new BlockEntityOwnershipEndpoint(tile); var image = endpoint.capture();
			var impossible = image.copy(); impossible.putLong("energy", Long.MAX_VALUE); boolean rejected = false;
			try { endpoint.validateReturn(new AssetImage(impossible)); } catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected && endpoint.capture().equals(image), "Capacity preflight modified physical source");
			var claim = new MemberClaim(identity.networkId(), UUID.randomUUID(), UUID.randomUUID(), new Origin("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ()), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString());
			fixtures.add(new Fixture(pos, claim, tile.saveWithFullMetadata(level.registryAccess()), image));
			MemberBinding.begin(tile, identity, claim.member(), claim.transfer());
		}
		opening = NetworkPersistence.directory(server).create(identity); started = server.getTickCount();
	}
	static boolean advance(MinecraftServer server, JsonObject report) {
		if (index == fixtures.size()) { report.addProperty("ownershipFaultsIsolated", index); report.addProperty("capacityPreflightPreservesSource", true); return true; }
		require(server.getTickCount() - started < 800, "Ownership fault probe timeout");
		if (opening.pending()) return false; if (domain == null) domain = opening.ready();
		var fixture = fixtures.get(index); var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		var tile = (TileEntityMekanism) level.getBlockEntity(fixture.pos()); var endpoint = new BlockEntityOwnershipEndpoint(tile);
		if (transfer == null) { MemberBinding.release(tile, MemberBinding.read(tile)); transfer = OwnershipTransferService.begin(directory, domain, fixture.claim(), endpoint); }
		transfer.advance(endpoint); require(transfer.step() != OwnershipTransferService.Step.RECOVERY, transfer.failure());
		int fault = index % 4;
		var target = fault == 0 ? OwnershipTransferService.Step.SEAL : OwnershipTransferService.Step.OWNED;
		if (transfer.step() != target) return false;
		if (fault == 0) {
			var replacement = tile.getType().create(fixture.pos(), tile.getBlockState()); replacement.loadWithComponents(fixture.original(), server.registryAccess());
			level.removeBlockEntity(fixture.pos()); level.setBlockEntity(replacement); tile = (TileEntityMekanism) replacement; endpoint = new BlockEntityOwnershipEndpoint(tile);
			require(!tile.getPersistentData().contains(MemberBinding.TAG) && MemberBinding.isolated(tile), "Old unbound BE escaped directory isolation");
			require(Block.getDrops(tile.getBlockState(), level, fixture.pos(), tile).isEmpty(), "Old sealed source generated drops");
			transfer = OwnershipTransferService.resume(directory, domain, fixture.claim(), endpoint);
		} else if (fault == 1) {
			var binding = tile.getPersistentData().getCompound(MemberBinding.TAG); var network = binding.getCompound("network"); network.putLong("generation", domain.identity().generation() + 1);
			transfer = OwnershipTransferService.resume(directory, domain, fixture.claim(), endpoint);
		} else if (fault == 2) {
			require(Block.getDrops(tile.getBlockState(), level, fixture.pos(), tile).isEmpty(), "Managed source generated drops");
			level.destroyBlock(fixture.pos(), true); transfer.advance(endpoint);
		} else {
			tile.setOwnerUUID(UUID.randomUUID()); transfer.advance(endpoint);
		}
		require(transfer.step() == OwnershipTransferService.Step.RECOVERY, "Ownership fault was accepted");
		require(directory.claimAt(fixture.claim().origin()).equals(fixture.claim()) && domain.checkpoint().ownedMachines().get(fixture.claim().member()).assets().equals(fixture.image()), "Fault discarded authority assets");
		index++; transfer = null; return false;
	}
	private OwnershipFaultProbe() { }
}
