package com.ayoshiko.productivebeesgenesis.storageprototype.compat;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.storageprototype.compat.PersistenceProbe.require;

public final class NetworkProbe {
	private NetworkProbe() { }

	public static JsonObject run(IGrid grid) {
		var service = grid.getStorageService();
		var data = new PrototypeSavedData();
		AEKey gold = AEItemKey.of(Items.GOLD_INGOT);
		AEKey variant = PersistenceProbe.key(7);
		AEKey stone = AEItemKey.of(Items.STONE);
		AEKey fluid = AEFluidKey.of(BuiltInRegistries.FLUID.get(ResourceLocation.parse("productivebees:honey")));
		var allowed = Set.of(gold, variant, fluid);
		boolean[] enabled = {true};
		var store = new PrototypeStorage(data, allowed::contains,
				() -> enabled[0] && grid.getEnergyService().isNetworkPowered(), service::invalidateCache);
		IStorageProvider provider = mounts -> mounts.mount(store);
		service.addGlobalStorageProvider(provider);
		service.invalidateCache();
		var source = IActionSource.empty();
		var network = service.getInventory();
		JsonObject result = new JsonObject();
		try {
			require(grid.getEnergyService().isNetworkPowered(), "Unpowered AE test grid");
			require(network.insert(gold, 41, Actionable.SIMULATE, source) == 41, "Simulated insert rejected");
			require(data.revision() == 0 && !data.isDirty() && data.amounts().size() == 0, "Simulation mutated domain");
			require(network.insert(stone, 64, Actionable.MODULATE, source) == 0, "Illegal product accepted");
			require(network.insert(gold, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE, "Insert truncated");
			require(network.insert(gold, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE, "Overflow insert rejected");
			BigInteger total = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
			require(data.amounts().exact(gold).equals(total), "Exact quantity overflow");
			require(service.getCachedInventory().get(gold) == Long.MAX_VALUE, "Single bridge projection incorrect");
			long revision = data.revision();
			require(network.extract(gold, 21, Actionable.SIMULATE, source) == 21, "Simulated extract failed");
			require(data.revision() == revision && data.amounts().exact(gold).equals(total), "Simulated extract mutated inventory");
			require(network.extract(gold, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE, "Real extract failed");
			require(network.extract(gold, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE, "Remaining exact amount lost");
			require(data.amounts().exact(gold).signum() == 0, "Over-extraction or stale zero");
			require(network.insert(variant, 17, Actionable.MODULATE, source) == 17, "Component variant failed");
			require(network.extract(gold, 1, Actionable.MODULATE, source) == 0, "Components merged");
			require(network.insert(fluid, 2500, Actionable.MODULATE, source) == 2500, "Fluid insertion failed");
			require(network.extract(fluid, 1000, Actionable.MODULATE, source) == 1000, "Fluid extraction failed");
			result.addProperty("bidirectionalExactComponentsItemsFluids", true);
			enabled[0] = false;
			service.invalidateCache();
			require(network.extract(variant, 1, Actionable.MODULATE, source) == 0, "Offline extraction succeeded");
			require(network.insert(variant, 1, Actionable.SIMULATE, source) == 0, "Offline simulation advertised availability");
			require(service.getCachedInventory().isEmpty(), "Offline inventory still advertised");
			enabled[0] = true;
			service.invalidateCache();
			require(service.getCachedInventory().get(variant) == 17, "Re-enable lost inventory");
			service.removeGlobalStorageProvider(provider);
			result.addProperty("nativeUnmountRetainedSameTickCache", service.getCachedInventory().get(variant) == 17);
			service.invalidateCache();
			require(service.getCachedInventory().get(variant) == 0, "Unmount retained inventory");
			service.addGlobalStorageProvider(provider);
			service.invalidateCache();
			require(service.getCachedInventory().get(variant) == 17, "Remount lost inventory");
			result.addProperty("gateUnmountRemountCacheRefresh", true);

			network.insert(gold, Long.MAX_VALUE, Actionable.MODULATE, source);
			var second = StorageCells.getCellInventory(AEItems.ITEM_CELL_256K.stack(), null);
			require(second != null && second.insert(gold, 1, Actionable.MODULATE, source) == 1, "Native cell not available");
			IStorageProvider secondProvider = mounts -> mounts.mount(second);
			service.addGlobalStorageProvider(secondProvider);
			service.invalidateCache();
			try {
				long aggregate = service.getCachedInventory().get(gold);
				result.addProperty("nativeAggregateMaxLongPlusOne", aggregate);
				require(aggregate == Long.MIN_VALUE, "AE2 aggregation changed; update projection design");
				require(data.amounts().exact(gold).equals(BigInteger.valueOf(Long.MAX_VALUE)), "Native display changed ledger");
				service.removeGlobalStorageProvider(provider);
				service.addGlobalStorageProvider(provider);
				service.invalidateCache();
				long reverseOrder = service.getCachedInventory().get(gold);
				result.addProperty("safeBridgeLastAggregate", reverseOrder);
				require(reverseOrder == Long.MAX_VALUE, "Own saturation failed");
			} finally {
				service.removeGlobalStorageProvider(secondProvider);
				service.invalidateCache();
			}
			result.addProperty("simulationPure", true);
			return result;
		} finally {
			service.removeGlobalStorageProvider(provider);
			service.invalidateCache();
		}
	}
}
