package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalHost;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MeCraftingProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.meCrafting"); }
	private static int stages;
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) { if (MeBridgeProbe.ae()) MeCraftingAeFixture.seed(core); }
	static boolean ready(int stage) {
		if (!MeBridgeProbe.ae()) return true;
		if (stage==600) return MeCraftingAeFixture.ready();
		if (stage==605) return MeCraftingAeFixture.jobs()==0 && MeCraftingAeFixture.stone()==64;
		if (stage==607) return MeCraftingAeFixture.held==4;
		if (stage==608) return MeCraftingAeFixture.jobs()==0 && MeCraftingAeFixture.iron()==2;
		return true;
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		stages++; if (!MeBridgeProbe.ae()) return -1;
		if (stage==601 || stage==602) require(MeCraftingAeFixture.stone()==64 && MeCraftingAeFixture.jobs()==0,"Planning consumed materials");
		if (stage==603 || stage==604) require(MeCraftingAeFixture.stone()==56 && MeCraftingAeFixture.jobs()==1 && MeCraftingAeFixture.held==0,"Submission duplicated or changed material custody");
		if (stage==605) require(MeCraftingAeFixture.stone()==64 && MeCraftingAeFixture.held==0,"Cancellation lost reserved materials");
		if (stage==606) MeCraftingAeFixture.busy=false;
		if (stage==607) MeCraftingAeFixture.deliver();
		if (stage>=608) require(MeCraftingAeFixture.stone()==60 && MeCraftingAeFixture.iron()==2 && MeCraftingAeFixture.jobs()==0,"Completed or rejected command changed outputs");
		if (stage==610 || stage==612) require(!((MeTerminalHost)players.getFirst().containerMenu).meTerminal().active(),"Closed page retained plan backend");
		if (stage==612) { MeCraftingAeFixture.close(); return -1; }
		return stage+1;
	}
	static void report(JsonObject report, boolean reader) {
		require(reader || stages==(MeBridgeProbe.ae()?13:1),"Incomplete ME crafting stages");
		report.addProperty("meCraftingVerified",true); report.addProperty("meCraftingStages",stages);
	}
}
