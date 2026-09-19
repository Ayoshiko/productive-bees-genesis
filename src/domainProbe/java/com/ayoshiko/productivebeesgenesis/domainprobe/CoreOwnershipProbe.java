package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.google.gson.JsonObject;
import java.util.UUID;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class CoreOwnershipProbe {
	private static final BlockPos POS = new BlockPos(24, 100, 8);
	private static NetworkCoreBlockEntity core;
	private static int phase, started;
	static void start(MinecraftServer server) {
		var level = server.overworld(); level.setChunkForced(1, 0, true);
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
		var owner = UUID.randomUUID(); core.initializeOwner(owner);
		level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
		var member = (TileEntityMekanism) level.getBlockEntity(POS.east()); member.setOwnerUUID(owner);
		((com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext) member).primaryOutputSlot(0).setStack(new ItemStack(Items.DIAMOND, 13));
		started = server.getTickCount();
	}
	static boolean advance(MinecraftServer server, JsonObject report) {
		if (phase == 5) return true;
		require(server.getTickCount() - started < 500, "Core ownership timeout: " + core.ownership().status() + " " + core.ownership().failure());
		var level = server.overworld(); var view = core.topology();
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY && core.ownership().status() != CoreOwnershipController.Status.REJECTED, core.ownership().failure());
		if (phase == 0) {
			ModConfig.SERVER.beeNetwork.enabled.set(true);
			if (view == null) return false;
			require(view.valid() && view.members().size() == 1, "Core fixture topology incorrect");
			require(core.ownership().command(true), "Core takeover command rejected"); phase++; return false;
		}
		if (core.ownership().busy() || core.ownership().status() != (phase == 3 ? CoreOwnershipController.Status.STANDALONE : CoreOwnershipController.Status.MANAGED)) return false;
		if (phase == 1) {
			var member = (TileEntityMekanism) level.getBlockEntity(POS.east());
			require(new MachineAssetStore(member).empty() && MemberBinding.isolated(member), "Core did not seal member");
			var saved = core.saveWithFullMetadata(server.registryAccess());
			var copyPos = POS.south(3); level.setBlockAndUpdate(copyPos, NetworkContent.CORE.get().defaultBlockState());
			var copy = (NetworkCoreBlockEntity) level.getBlockEntity(copyPos); copy.loadWithComponents(saved, server.registryAccess()); copy.ownership().advance();
			require(copy.ownership().status() == CoreOwnershipController.Status.RECOVERY, "Copied core acquired original authority"); level.removeBlock(copyPos, false);
			var replacement = new NetworkCoreBlockEntity(POS, core.getBlockState()); replacement.loadWithComponents(saved, server.registryAccess());
			level.removeBlockEntity(POS); level.setBlockEntity(replacement); core = replacement; phase++; return false;
		}
		if (phase == 2) {
			require(core.ownership().command(false), "Recovered core return command rejected"); phase++; return false;
		}
		if (phase == 3) {
			require(!core.ownership().command(false), "Empty network accepted another return");
			var member = (TileEntityMekanism) level.getBlockEntity(POS.east());
			require(!MemberBinding.isolated(member), "Core return left member isolated");
			require(((com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext) member).primaryOutputSlot(0).getCount() == 13, "Core return lost inventory");
			report.addProperty("coreTakeoverRestartReturn", true); report.addProperty("copiedCoreRejected", true);
			level.removeBlock(POS.east(), false); level.removeBlock(POS, false); level.setChunkForced(1, 0, false); phase = 5; return true;
		}
		return false;
	}
	private CoreOwnershipProbe() { }
}
