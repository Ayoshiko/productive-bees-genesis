package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.me.storage.NetworkStorage;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.math.BigInteger;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 复用真实桥和玩家，在一次服务器场景内核算大数、组件、来源、配置与失效。 */
final class MeStorageAeFixture {
	static boolean verified;
	private static boolean running;
	static void prepare(NetworkCoreBlockEntity core, MeBridgeBlockEntity bridge, List<ServerPlayer> players) {
		running = core.productionRunning();
		require(core.setProductionRunning(true), "Cannot run storage host");
		require(!bridge.automation() && !bridge.toggleAutomation(players.get(1)), "Guest changed storage authorization");
		require(bridge.toggleAutomation(players.getFirst()) && bridge.automation(), "Owner could not authorize storage");
	}
	static boolean ready(MeBridgeBlockEntity bridge) {
		return RuntimeProductPolicies.peek((net.minecraft.server.level.ServerLevel) bridge.getLevel()) != null
				&& bridge.link() != null && bridge.link().storageAvailable();
	}
	static void exercise(NetworkCoreBlockEntity core, MeBridgeBlockEntity bridge, List<ServerPlayer> players) {
		var player = players.getFirst(); var source = IActionSource.ofPlayer(player);
		var node = (MeBridgeNode) bridge.link(); var storage = node.storage(); var grid = node.grid().getStorageService();
		var data = core.ownership().readyAuthority(); var before = data.checkpoint();
		var iron = AEItemKey.of(Items.RAW_IRON);
		var product = ProductKeyCodec.item(Items.RAW_IRON.getDefaultInstance(), player.registryAccess());
		var initial = before.ledger().balances().getOrDefault(product, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.ZERO).exact();
		long simulated = storage.insert(iron, 7, Actionable.SIMULATE, source);
		require(data.checkpoint() == before, "Simulation changed authority");
		require(simulated == 7, "Static raw iron simulation refused: " + simulated);
		require(storage.insert(iron, 1, Actionable.MODULATE, IActionSource.empty()) == 0, "Anonymous source authorized");
		require(storage.extract(iron, 1, Actionable.MODULATE, IActionSource.ofPlayer(players.get(1))) == 0, "Guest extracted stock");
		require(storage.insert(AEItemKey.of(Items.COMMAND_BLOCK), 1, Actionable.MODULATE, source) == 0, "Unrelated product admitted");
		require(grid.getInventory().insert(iron, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE, "Actual grid did not mount storage");
		require(storage.insert(iron, 1, Actionable.MODULATE, source) == 1, "Wide amount insertion failed");
		require(data.checkpoint().ledger().balances().get(product).exact().equals(initial.add(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.ONE)), "Long insertion narrowed exact balance");
		for (int i = 0; i < 128; i++) node.storageStep();
		require(grid.getCachedInventory().get(iron) == Long.MAX_VALUE, "Native grid projection overflowed");
		var extra = new MEStorage() {
			public Component getDescription() { return Component.literal("One extra iron"); }
			public void getAvailableStacks(KeyCounter out) { out.add(iron, 1); }
		};
		IStorageProvider provider = mounts -> mounts.mount(extra, -1);
		grid.addGlobalStorageProvider(provider); grid.invalidateCache();
		require(grid.getCachedInventory().get(iron) == Long.MAX_VALUE, "Actual grid overflowed across providers");
		grid.removeGlobalStorageProvider(provider); grid.invalidateCache();
		for (boolean first : List.of(false, true)) {
			var aggregate = new NetworkStorage();
			aggregate.mount(0, first ? storage : extra); aggregate.mount(0, first ? extra : storage);
			var counts = new KeyCounter(); aggregate.getAvailableStacks(counts);
			require(counts.get(iron) == Long.MAX_VALUE, "Mount order changed saturated aggregation");
		}
		var namedStack = Items.RAW_IRON.getDefaultInstance(); namedStack.set(DataComponents.CUSTOM_NAME, Component.literal("ME named iron"));
		var named = AEItemKey.of(namedStack);
		require(storage.insert(named, 3, Actionable.MODULATE, source) == 3, "Cosmetic product admission failed");
		require(storage.extract(named, 9, Actionable.MODULATE, source) == 3, "Components merged or finite extraction overpaid");
		var water = AEFluidKey.of(net.minecraft.core.registries.BuiltInRegistries.FLUID.get(net.minecraft.resources.ResourceLocation.parse("productivebees:honey")));
		long accepted = storage.insert(water, 1000, Actionable.SIMULATE, source);
		require(accepted == 1000, "Static fluid product not admitted");
		require(storage.insert(water, 1000, Actionable.MODULATE, source) == 1000 && storage.extract(water, 1000, Actionable.MODULATE, source) == 1000, "Fluid mB round trip failed");
		require(core.setProductionRunning(false), "Cannot pause storage host");
		require(storage.extract(iron, 1, Actionable.SIMULATE, source) == 0, "Paused host exposed stock");
		require(grid.getCachedInventory().get(iron) == 0, "Paused stock remained in same-tick cache");
		require(core.setProductionRunning(true), "Cannot resume host");
		ModConfig.SERVER.beeNetwork.meStorageSafeAggregation.set(false);
		try { node.tick(); require(storage.extract(iron, 1, Actionable.MODULATE, source) == 0, "Disabled aggregation still transfers"); }
		finally { ModConfig.SERVER.beeNetwork.meStorageSafeAggregation.set(true); node.tick(); }
		require(storage.extract(iron, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE, "Long extraction failed");
		require(storage.extract(iron, 1, Actionable.MODULATE, source) == 1, "Final wide insertion not returned");
		require(before.ledger().balances().equals(data.checkpoint().ledger().balances()), "ME round trips changed exact balances");
		require(bridge.toggleAutomation(player) && !bridge.automation(), "Cannot revoke automation");
		require(storage.insert(iron, 1, Actionable.MODULATE, source) == 0, "Revoked provider accepted transfer");
		require(bridge.toggleAutomation(player), "Cannot reauthorize");
		var saved = bridge.saveWithoutMetadata(player.registryAccess());
		var detached = new MeBridgeBlockEntity(bridge.getBlockPos(), bridge.getBlockState());
		detached.loadWithComponents(saved, player.registryAccess());
		require(detached.automation(), "Authorization codec lost state");
		var legacy = saved.copy(); legacy.getCompound("meBridge").putInt("schema", 1); legacy.getCompound("meBridge").remove("automation");
		detached.loadWithComponents(legacy, player.registryAccess());
		require(!detached.automation(), "Legacy bridge silently granted automation");
		// 负值在此独立聚合器内锁定故障，不破坏正在验证正常重启的真实网格。
		var bad = new NetworkStorage(); bad.mount(0, storage);
		bad.mount(0, new MEStorage() {
			public Component getDescription() { return Component.literal("Negative fixture"); }
			public void getAvailableStacks(KeyCounter out) { out.set(iron, -1); }
		});
		bad.mount(0, extra); var counts = new KeyCounter(); bad.getAvailableStacks(counts);
		require(counts.get(iron) < 0 && !((SafeStorageAggregation) (Object) bad).pbgSafeAggregationAvailable(), "Negative contribution was hidden");
		core.setProductionRunning(running); verified = true;
	}
	static void closed(MeBridgeLink old, ServerPlayer player) {
		require(((MeBridgeNode) old).storage().extract(AEItemKey.of(Items.IRON_INGOT), 1, Actionable.MODULATE, IActionSource.ofPlayer(player)) == 0,
				"Old storage reference survived host conflict");
	}
	private MeStorageAeFixture() { }
}
