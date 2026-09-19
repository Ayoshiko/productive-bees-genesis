package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import java.util.UUID;
import mekanism.common.lib.security.ISecurityTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class TopologyProbe {
	private static final BlockPos POS = new BlockPos(8, 80, 8);
	private static final UUID OWNER = UUID.randomUUID();
	private static NetworkCoreBlockEntity core;
	private static int phase, started, previousNodes;
	private static boolean previousEnabled, enabled;
	static void start(MinecraftServer server) {
		var config = ModConfig.SERVER.beeNetwork; previousEnabled = config.enabled.get(); previousNodes = config.topologyNodes.get();
		config.enabled.set(true); config.topologyNodes.set(1); enabled = true; phase = 0; started = server.getTickCount();
		var level = server.overworld(); level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
		core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(OWNER);
		level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_APIARY.get().defaultBlockState()); owner(level, POS.east(), OWNER);
		level.setBlockAndUpdate(POS.south(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState()); owner(level, POS.south(), OWNER);
		level.setBlockAndUpdate(POS.above(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState()); owner(level, POS.above(), UUID.randomUUID());
	}
	private static void owner(ServerLevel level, BlockPos pos, UUID owner) { ((ISecurityTile) level.getBlockEntity(pos)).setOwnerUUID(owner); }
	static boolean advance(MinecraftServer server, JsonObject report) {
		if (!enabled) return true;
		require(server.getTickCount() - started < 500, "Topology failed to converge within budget");
		var view = core.topology(); if (view == null) return false;
		var level = server.overworld();
		if (phase == 0) {
			require(view.valid() && view.members().size() == 2 && view.denied() == 1 && view.lanes() == 1 && view.beeSlots() > 0, "Owner or capability preview mismatch");
			require(server.getTickCount() - started > 3, "One-node budget did not span ticks");
			level.setBlockAndUpdate(POS.below(), NetworkContent.CORE.get().defaultBlockState());
			((NetworkCoreBlockEntity) level.getBlockEntity(POS.below())).initializeOwner(OWNER);
			require(core.topology() == null, "Mutation retained old executable topology"); phase++; return false;
		}
		if (phase == 1) {
			require(!view.valid() && view.cores() == 2, "Dual controller did not conflict"); core.toggleFace(Direction.DOWN); phase++; return false;
		}
		if (phase == 2) {
			require(view.valid(), "Closed core face still connects"); level.removeBlock(POS.east(), false); phase++; return false;
		}
		require(view.valid() && view.members().size() == 1 && view.beeSlots() == 0, "Removed member retained capacity");
		report.addProperty("budgetedTopologyAcrossRealTicks", true); report.addProperty("topologyOwnerAndDualCoreGuards", true);
		report.addProperty("topologyTicks", server.getTickCount() - started); close(); return true;
	}
	static void close() {
		if (enabled) { ModConfig.SERVER.beeNetwork.enabled.set(previousEnabled); ModConfig.SERVER.beeNetwork.topologyNodes.set(previousNodes); }
		enabled = false; core = null;
	}
	private TopologyProbe() { }
}
