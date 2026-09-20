package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.feeding.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.api.SerializerHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class FeedingNetworkProbe {
	static void beforeProduction(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, UUID member, JsonObject report) {
		var state = data.checkpoint().ownedMachines().get(member).bees(); var access = new NetworkBeeService(data, directory); var service = new NetworkFeedingService(data, directory);
		require(state.feeding().slots().size() == 3 && state.feeding().slots().get(0).count() == 1, "Expected three finite slots and one sample");
		var initial = data.checkpoint();
		require(access.advance(level, member, 1, 0, 0, 0, 10, 1, false) == BeeWorkExecutor.Status.FLOWER && initial == data.checkpoint(), "Unlinked bee consumed another slot sample");
		var groups = state.feeding().groups(List.of(0, 0, 2));
		require(service.apply(level, member, state.feeding().revision(), groups, true) && initial == data.checkpoint(), "Feeding simulation changed authority");
		require(service.apply(level, member, state.feeding().revision(), groups, false), "Shared group rejected");
		require(!service.apply(level, member, state.feeding().revision(), groups, false), "Duplicate feeding plan accepted");
		state = data.checkpoint().ownedMachines().get(member).bees();
		require(service.apply(level, member, state.feeding().revision(), state.feeding().disabled(0, true), false), "Disable sample failed");
		var disabled = data.checkpoint();
		require(access.advance(level, member, 0, 0, 0, 0, 10, 1, false) == BeeWorkExecutor.Status.FLOWER && disabled == data.checkpoint(), "Disabled shared sample remained active");
		state = data.checkpoint().ownedMachines().get(member).bees();
		require(service.apply(level, member, state.feeding().revision(), state.feeding().disabled(0, false), false), "Enable sample failed");
		preflight(level, data.checkpoint().ownedMachines().get(member).returnImage());
		report.addProperty("feedingThreeSlotsDefaultIsolationSharedGroupAndDisable", true);
		report.addProperty("feedingMigrationCapacityComponentsAndFlags", true);
	}
	static void afterProduction(ServerLevel level, NetworkSavedData data, NetworkDirectory directory, UUID member, JsonObject report) {
		var service = new NetworkFeedingService(data, directory); var access = new NetworkBeeService(data, directory);
		var original = data.checkpoint().ownedMachines().get(member).bees(); var beeId = original.bee(1).id();
		require(service.moveBee(level, member, original.revision(), 1, 2, false, false), "Bee move rejected");
		var moved = data.checkpoint().ownedMachines().get(member).bees();
		require(moved.bee(2).id().equals(beeId) && moved.feeding().slots().get(0).count() == 1, "Bee move changed identity or food");
		require(access.advance(level, member, 2, moved.bee(2).revision(), 0, 0, 1, 1, false) == BeeWorkExecutor.Status.FLOWER, "Empty destination inherited a flower");
		require(service.moveBee(level, member, moved.revision(), 2, 1, false, false), "Bee move back failed");
		var state = data.checkpoint().ownedMachines().get(member).bees();
		require(service.apply(level, member, state.feeding().revision(), state.feeding().groups(List.of(0, 1, 2)), false), "Ungroup failed");
		state = data.checkpoint().ownedMachines().get(member).bees();
		require(service.moveBee(level, member, state.revision(), 0, 2, true, false), "Bee and food move failed");
		state = data.checkpoint().ownedMachines().get(member).bees();
		require(state.feeding().slots().get(0).count() == 0 && state.feeding().slots().get(2).count() == 1, "Food duplicated during bee move");
		require(service.moveBee(level, member, state.revision(), 2, 0, true, false), "Bee and food move back failed");
		report.addProperty("feedingBeeMoveKeepsIdentityAndTransfersFoodOnlyExplicitly", true);
	}
	private static void preflight(ServerLevel level, AssetImage source) {
		var overflowing = source.copy(); var list = new ListTag();
		for (var item : List.of(Items.IRON_BLOCK, Items.POPPY, Items.DIAMOND, Items.EGG)) {
			var slot = new CompoundTag(); slot.put("item", SerializerHelper.saveOversized(level.registryAccess(), new ItemStack(item))); list.add(slot);
		}
		while (list.size() < 9) list.add(new CompoundTag()); overflowing.getCompound("extra").put(FeedingAssetProjection.SLOTS, list);
		var image = new AssetImage(overflowing); boolean rejected = false;
		try { StaticFeedingAdapter.migrate(image, level.registryAccess()); } catch (IllegalArgumentException expected) { rejected = true; }
		require(rejected && image.copy().equals(overflowing), "Over-capacity migration changed source");
		list.getCompound(0).put("item", SerializerHelper.saveOversized(level.registryAccess(), new ItemStack(Items.IRON_BLOCK, 32)));
		list.getCompound(1).put("item", SerializerHelper.saveOversized(level.registryAccess(), new ItemStack(Items.IRON_BLOCK, 32)));
		var packed = StaticFeedingAdapter.migrate(new AssetImage(overflowing), level.registryAccess());
		require(packed.slots().stream().mapToInt(slot -> slot.count()).sum() == 66, "Mergeable old stacks falsely exceeded feeding capacity");
		var late = source.copy(); list = new ListTag(); for (int i = 0; i < 9; i++) list.add(new CompoundTag());
		var stack = new ItemStack(Items.IRON_BLOCK, 5); stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("喂食样本"));
		list.getCompound(8).put("item", SerializerHelper.saveOversized(level.registryAccess(), stack));
		late.getCompound("extra").put(FeedingAssetProjection.SLOTS, list); late.getCompound("extra").putLongArray(FeedingAssetProjection.DISABLED, new long[]{1L << 8});
		var migrated = StaticFeedingAdapter.migrate(new AssetImage(late), level.registryAccess());
		require(migrated.slots().get(0).count() == 5 && migrated.slots().get(0).disabled(), "Late disabled source lost count or flag");
		var enabled = migrated.disabled(0, false).apply(migrated);
		require(StaticFeedingAdapter.flower(enabled, 0, ResourceLocation.parse("productivebees:iron"), level.registryAccess()), "PB iron flower matcher changed");
		var restored = FeedingAssetProjection.attach(FeedingAssetProjection.detach(new AssetImage(late), migrated), migrated).copy();
		require(restored.getCompound("extra").getList(FeedingAssetProjection.SLOTS, 10).getCompound(0).get("item").equals(SerializerHelper.saveOversized(level.registryAccess(), stack)), "Feeding components lost during return");
		var unknown = late.copy(); unknown.getCompound("extra").getList(FeedingAssetProjection.SLOTS, 10).getCompound(8).getCompound("item").putString("id", "missing:feeding_item"); rejected = false;
		try { StaticFeedingAdapter.migrate(new AssetImage(unknown), level.registryAccess()); } catch (RuntimeException expected) { rejected = true; }
		require(rejected, "Unknown feeder item silently became empty");
	}
	private FeedingNetworkProbe() { }
}
