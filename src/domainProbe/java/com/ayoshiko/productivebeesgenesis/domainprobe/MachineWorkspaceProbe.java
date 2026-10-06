package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.multiblock.world.WirelessMachineFixture;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MachineWorkspaceProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.machineWorkspace"); }
	private static int stages;
	static boolean complete() { return stages == 8; }
	static void seed(ServerPlayer player) { WirelessMachineFixture.seedWorkspace(player); }
	static int advance(ServerPlayer player, int stage) { WirelessMachineFixture.checkWorkspace(player, stage); stages++; return stage == 757 ? 509 : stage + 1; }
	static void report(com.google.gson.JsonObject report) { require(complete(), "Incomplete machine workspace checks"); report.addProperty("machineWorkspaceVerified", true); report.addProperty("machineWorkspaceStages", stages); }
}
