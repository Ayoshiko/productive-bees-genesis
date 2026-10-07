package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.world.*;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MeBridgeProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.meBridge"); }
	static boolean ae() { return MeBridgeIntegration.installed(); }
	static boolean storage() { return Boolean.getBoolean("pbg.concurrent.meStorage"); }
	private static int stages;
	private static MeBridgeBlockEntity bridge, machineBridge;
	private static MeBridgeLink old;
	private static boolean recovered;
	static MeBridgeStatus expected(int stage, boolean owner) {
		if (!ae()) return MeBridgeStatus.ABSENT;
		if (!owner) return MeBridgeStatus.OWNER_ONLY;
		return switch (stage) { case 500 -> MeBridgeStatus.NO_BRIDGE; case 501 -> MeBridgeStatus.OFFLINE; case 503 -> MeBridgeStatus.NO_CHANNEL; case 505 -> MeBridgeStatus.CONFLICT; default -> MeBridgeStatus.ONLINE; };
	}
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		CraftingProbe.open(core, players, false);
	}
	private static MeBridgeBlockEntity place(ServerPlayer player, BlockPos pos) {
		var level = player.serverLevel(); level.setBlockAndUpdate(pos, NetworkContent.ME_BRIDGE.get().defaultBlockState());
		var value = (MeBridgeBlockEntity) level.getBlockEntity(pos); value.initializeOwner(player.getUUID()); return value;
	}
	private static void power(MeBridgeBlockEntity value, Direction side) {
		if (ae()) value.getLevel().setBlockAndUpdate(value.getBlockPos().relative(side), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("ae2:creative_energy_cell")).defaultBlockState());
	}
	static boolean ready(int stage) {
		if (storage() && ae() && stage == 504 && !MeStorageAeFixture.ready(bridge)) return false;
		return stage != 508 || WirelessMachineFixture.ready();
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		var player = players.getFirst(); var level = player.serverLevel();
		if (stage <= 506 || stage == 511) {
			require(MeBridgeTarget.status(core, player) == expected(stage, true), "Wrong bridge state at " + stage + ": " + MeBridgeTarget.status(core, player));
			require(MeBridgeTarget.status(core, players.get(1)) == expected(stage, false), "Guest gained ME access");
		}
		if (stage == 500) bridge = place(player, core.getBlockPos().above());
		if (stage == 501) { require(!ae() ? bridge.link() == null : bridge.link() != null, "Optional node lifecycle mismatch"); power(bridge, Direction.UP); }
		if (stage == 502) {
			var before = bridge.saveWithoutMetadata(level.registryAccess());
			for (int i = 0; i < 5; i++) MeBridgeTarget.status(core, player);
			require(before.equals(bridge.saveWithoutMetadata(level.registryAccess())), "Read-only query changed node data");
			if (storage() && ae()) MeStorageAeFixture.prepare(core, bridge, players);
			if (ae()) MeBridgeAeFixture.overload(bridge);
		}
		if (stage == 503 && ae()) MeBridgeAeFixture.clear();
		if (stage == 504) { if (storage() && ae()) MeStorageAeFixture.exercise(core, bridge, players); old = bridge.link(); place(player, core.getBlockPos().south()); }
		if (stage == 505) {
			require(MeBridgeTarget.inspect(bridge).status() == MeBridgeStatus.CONFLICT, "Two bridges selected an arbitrary grid");
			if (ae()) { MeBridgeAeFixture.closed(old); if (storage()) MeStorageAeFixture.closed(old, player); }
			level.setBlockAndUpdate(core.getBlockPos().south(), Blocks.AIR.defaultBlockState());
		}
		if (stage == 506) {
			if (ae()) require(bridge.link() != old, "Reconnect revived old link");
			var previous = bridge.link(); ModConfig.SERVER.beeNetwork.enabled.set(false);
			try { require(MeBridgeTarget.inspect(bridge).status() == MeBridgeStatus.DISABLED, "Disabled host stayed available"); if (ae()) MeBridgeAeFixture.closed(previous); }
			finally { ModConfig.SERVER.beeNetwork.enabled.set(true); }
			codec(player);
			player.closeContainer(); player.getInventory().selected = 8;
			var device = NetworkContent.WIRELESS_COMBINED.get().getDefaultInstance(); player.getInventory().setItem(8, device);
			require(NetworkContent.WIRELESS_COMBINED.get().bind(player, device, core), "Cannot bind bridge test device");
			device.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(50_000, false);
			player.inventoryMenu.broadcastChanges(); player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(8));
		}
		if (stage == 507) {
			require(player.containerMenu instanceof NetworkCoreMenu menu && menu.wirelessTerminal() && menu.meStatus() == expected(stage, true), "Wireless menu used another grid");
			WirelessMachineFixture.place(player);
		}
		if (stage == 508) {
			var machine = WirelessMachineFixture.controller(); var side = machine.getBlockState().getValue(MachinePartBlock.FACING);
			machineBridge = place(player, machine.getBlockPos().relative(side)); power(machineBridge, side);
			player.closeContainer(); var pos = machine.getBlockPos(); player.connection.teleport(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 0, 0);
			require(NetworkContent.WIRELESS_COMBINED.get().bindMachine(player, player.getMainHandItem(), machine), "Machine bridge binding failed");
			player.connection.teleport(8.5, 100, 10.5, 0, 0); player.inventoryMenu.broadcastChanges();
		}
		if (stage == 509) {
			require(player.containerMenu instanceof MachineMenu menu && menu.wireless() && menu.meStatus() == expected(stage, true), "Machine menu did not use its own bridge");
			if (MachineWorkspaceProbe.enabled() && !MachineWorkspaceProbe.complete()) { MachineWorkspaceProbe.seed(player); return 750; }
			old = machineBridge.link(); WirelessMachineFixture.breakStructure();
		}
		if (stage == 510) {
			require(!(player.containerMenu instanceof MachineMenu) && MeBridgeTarget.inspect(machineBridge).status() == MeBridgeStatus.HOST_UNAVAILABLE, "Broken machine retained ME access");
			if (ae()) MeBridgeAeFixture.closed(old);
			require(NetworkContent.WIRELESS_COMBINED.get().bind(player, player.getMainHandItem(), core), "Cannot return to network after machine invalidation");
			CraftingProbe.open(core, players, false);
		}
		stages++;
		if (stage == 511 && WorkspaceProbe.enabled()) { WorkspaceProbe.seed(core, players); return 700; }
		if (stage == 511 && MeCraftingProbe.enabled()) { MeCraftingProbe.seed(core, players); return 600; }
		return stage == 511 ? -1 : stage + 1;
	}
	private static void codec(ServerPlayer player) {
		var detached = new MeBridgeBlockEntity(new BlockPos(1, 1, 1), NetworkContent.ME_BRIDGE.get().defaultBlockState());
		var corrupt = new CompoundTag(); corrupt.putString("meBridge", "preserve-invalid-type");
		detached.loadWithComponents(corrupt, player.registryAccess());
		require(detached.status() == MeBridgeStatus.FAILED && detached.saveWithoutMetadata(player.registryAccess()).get("meBridge").equals(corrupt.get("meBridge")), "Invalid bridge data was cleared");
	}
	static void capture(NetworkCoreBlockEntity core, CompoundTag manifest) {
		require(stages == 12, "ME bridge stages incomplete");
		manifest.put("me-bridge", bridge.saveWithoutMetadata(core.getLevel().registryAccess()));
	}
	static boolean recoveryReady(NetworkCoreBlockEntity core) {
		var pos = core.getBlockPos().above(); var tile = core.getLevel().getBlockEntity(pos);
		return tile instanceof MeBridgeBlockEntity value && value.status() == (ae() ? MeBridgeStatus.ONLINE : MeBridgeStatus.ABSENT);
	}
	static void recovered(NetworkCoreBlockEntity core, List<ServerPlayer> players, CompoundTag manifest) {
		bridge = (MeBridgeBlockEntity) core.getLevel().getBlockEntity(core.getBlockPos().above());
		var expected = manifest.getCompound("me-bridge").getCompound("meBridge");
		require(bridge.owner().equals(expected.getUUID("owner")) && bridge.owner().equals(players.getFirst().getUUID()), "Bridge owner changed across restart");
		require(ae() ? bridge.link() != null : bridge.link() == null, "Restart optional dependency boundary failed");
		require(bridge.automation() == expected.getBoolean("automation"), "Storage authorization changed across restart");
		recovered = true;
	}
	static void report(JsonObject report, boolean reader) {
		require(reader ? recovered : stages == 12, "ME bridge evidence incomplete");
		if (storage() && ae()) { require(reader || MeStorageAeFixture.verified, "Missing ME storage evidence"); report.addProperty("meStorageVerified", true); }
		report.addProperty("meBridgeConnectionAndRecovery", true); report.addProperty("meBridgeStages", stages);
	}
}
