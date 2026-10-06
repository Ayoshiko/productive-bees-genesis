package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.ClientTerminalStockFixture;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class WorkspaceProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.workspace"); }
	private static ProductKey iron;
	private static int stages;
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		var player = players.getFirst(); iron = ProductKeyCodec.item(new ItemStack(Items.IRON_INGOT), player.registryAccess());
		ClientTerminalStockFixture.seed(core.ownership().readyAuthority(), Map.of(iron, ProductAmount.of(65), ProductKeyCodec.item(new ItemStack(Items.GOLD_INGOT), player.registryAccess()), ProductAmount.of(3)));
		player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2)); player.getInventory().setItem(4, ItemStack.EMPTY);
		player.containerMenu.broadcastChanges();
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		stages++; var player = players.getFirst();
		require(core.ownership().readyAuthority().checkpoint().ledger().available(iron).equals(ProductAmount.of(stage >= 701 ? 64 : 65)), "Workspace transfer or rejection changed ledger");
		if (stage == 704) require(((NetworkCoreMenu)player.containerMenu).craftingItem(0).is(Items.OAK_LOG), "Workspace material not retained");
		if (stage >= 705) require(player.getInventory().countItem(Items.OAK_LOG) == 2 && ((NetworkCoreMenu)player.containerMenu).craftingItem(0).isEmpty(), "Workspace clear lost materials");
		return stage == 707 ? -1 : stage + 1;
	}
	static void report(com.google.gson.JsonObject report) { require(stages == 8, "Incomplete workspace stages"); report.addProperty("workspaceVerified", true); report.addProperty("workspaceStages", stages); }
}
