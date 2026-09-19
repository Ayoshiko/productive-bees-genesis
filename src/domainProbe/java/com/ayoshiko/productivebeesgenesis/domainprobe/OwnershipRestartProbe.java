package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;
import static com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnershipTransferService.Step.*;

/** 第一进程在八个阶段正常停服；第二进程只读取保存的世界并完成一次交还。 */
final class OwnershipRestartProbe {
	private static final class Fixture {
		NetworkOpenHandle opening;
		NetworkSavedData domain;
		NetworkIdentity identity;
		MemberClaim claim;
		AssetImage image;
		OwnershipTransferService.Step target;
		OwnershipTransferService transfer;
		boolean finished;
	}
	private static final List<Fixture> fixtures = new ArrayList<>();
	private static boolean reading;
	private static int started, cursor;
	static void start(MinecraftServer server) throws Exception {
		reading = "read".equals(System.getProperty("pbg.ownership.mode")); started = server.getTickCount();
		var level = server.overworld(); level.setChunkForced(3, 0, true); level.getChunk(3, 0);
		var directory = NetworkPersistence.directory(server);
		if (reading) {
			var root = NbtIo.readCompressed(manifest(server), NbtAccounter.unlimitedHeap());
			for (var raw : root.getList("fixtures", Tag.TAG_COMPOUND)) {
				var tag = (CompoundTag) raw; var fixture = new Fixture(); fixture.identity = NetworkCheckpointCodec.readIdentity(tag.getCompound("network"));
				fixture.claim = new MemberClaim(fixture.identity.networkId(), tag.getUUID("member"), tag.getUUID("transfer"), new Origin("minecraft:overworld", tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), tag.getString("machine"));
				fixture.target = OwnershipTransferService.Step.valueOf(tag.getString("stage")); fixture.image = new AssetImage(tag.getCompound("image"));
				fixture.opening = directory.loadExisting(fixture.identity); fixtures.add(fixture);
			}
			require(fixtures.size() == 16, "Restart fixtures missing");
			return;
		}
		for (var stage : List.of(PREPARE, SEAL, OWNED, RETURN_INTENT, RETURN_WRITE, RETURN_RECEIPT, RELEASE_CLAIM, RELEASE_BINDING)) {
			for (String path : List.of("mek_apiary", "mek_centrifuge")) {
				var fixture = new Fixture(); int index = fixtures.size(); var pos = new BlockPos(48 + index % 8, 120 + index / 8, 8);
				var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", path));
				level.setBlockAndUpdate(pos, block.defaultBlockState()); var tile = (TileEntityMekanism) level.getBlockEntity(pos);
				var owner = UUID.randomUUID(); tile.setOwnerUUID(owner); OwnershipProbe.seed(tile, level);
				fixture.identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), owner, 0, new Origin("minecraft:overworld", pos.getX(), pos.getY() + 10, pos.getZ()));
				fixture.claim = new MemberClaim(fixture.identity.networkId(), UUID.randomUUID(), UUID.randomUUID(), new Origin("minecraft:overworld", pos.getX(), pos.getY(), pos.getZ()), BuiltInRegistries.BLOCK.getKey(block).toString());
				fixture.target = stage; fixture.image = new BlockEntityOwnershipEndpoint(tile).capture();
				MemberBinding.begin(tile, fixture.identity, fixture.claim.member(), fixture.claim.transfer());
				fixture.opening = directory.create(fixture.identity); fixtures.add(fixture);
			}
		}
	}
	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		require(server.getTickCount() - started < 4000, "Restart probe timed out");
		if (fixtures.stream().allMatch(f -> f.finished)) {
			if (!reading) saveManifest(server);
			report.addProperty(reading ? "restartStagesReturned" : "shutdownStagesPrepared", fixtures.size());
			report.addProperty("normalShutdownCheckpointSaved", true); report.addProperty("passed", true); return true;
		}
		var fixture = fixtures.get(cursor++ % fixtures.size()); if (fixture.finished || fixture.opening.pending()) return false;
		var directory = NetworkPersistence.directory(server); if (fixture.domain == null) fixture.domain = fixture.opening.ready();
		var origin = fixture.claim.origin(); var pos = new BlockPos(origin.x(), origin.y(), origin.z());
		var tile = (TileEntityMekanism) server.overworld().getBlockEntity(pos); require(tile != null, "Saved BE missing");
		var endpoint = new BlockEntityOwnershipEndpoint(tile);
		if (fixture.transfer == null) {
			if (reading) {
				if (fixture.target == SEAL) {
					var expected = fixture.image.copy(); var actual = endpoint.capture().copy();
					for (String key : expected.getAllKeys()) if (!Objects.equals(expected.get(key), actual.get(key)))
						com.mojang.logging.LogUtils.getLogger().error("RESTART_ASSET_DIFF {} expected={} actual={}", key, expected.get(key), actual.get(key));
				}
				fixture.transfer = OwnershipTransferService.resume(directory, fixture.domain, fixture.claim, endpoint);
			}
			else {
				MemberBinding.release(tile, MemberBinding.read(tile));
				fixture.transfer = OwnershipTransferService.begin(directory, fixture.domain, fixture.claim, endpoint);
			}
		}
		fixture.transfer.advance(endpoint);
		require(fixture.transfer.step() != RECOVERY, fixture.target + ": " + fixture.transfer.failure());
		if (!reading && fixture.transfer.step() == fixture.target) { fixture.finished = true; return false; }
		if (fixture.transfer.step() == OWNED && (reading || fixture.target.ordinal() > OWNED.ordinal())) fixture.transfer.requestReturn(endpoint);
		if (!reading && fixture.transfer.step() == fixture.target) fixture.finished = true;
		if (reading && fixture.transfer.step() == RETURNED) {
			require(endpoint.capture().equals(fixture.image), "Assets differ after real process restart at " + fixture.target);
			require(directory.claimAt(fixture.claim.origin()) == null && !MemberBinding.isolated(tile), "Restart return still claimed");
			require(fixture.domain.checkpoint().ownedMachines().get(fixture.claim.member()).assets().isEmpty(), "Restart left executable network copy");
			server.overworld().removeBlock(pos, false); fixture.finished = true;
		}
		return false;
	}
	private static Path manifest(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("ownership-probe.dat"); }
	private static void saveManifest(MinecraftServer server) throws Exception {
		var root = new CompoundTag(); var list = new ListTag();
		for (var fixture : fixtures) {
			var tag = new CompoundTag(); tag.put("network", NetworkCheckpointCodec.identity(fixture.identity));
			tag.putUUID("member", fixture.claim.member()); tag.putUUID("transfer", fixture.claim.transfer()); var pos = fixture.claim.origin();
			tag.putInt("x", pos.x()); tag.putInt("y", pos.y()); tag.putInt("z", pos.z()); tag.putString("machine", fixture.claim.machine());
			tag.putString("stage", fixture.target.name()); tag.put("image", fixture.image.copy()); list.add(tag);
		}
		root.put("fixtures", list); NbtIo.writeCompressed(root, manifest(server));
	}
	private OwnershipRestartProbe() { }
}
