package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import java.util.List;
import mekanism.common.attachments.containers.ContainerType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@org.junit.jupiter.api.Tag("minecraft")
class MachinePatternStateMinecraftTest extends MachinePatternTestSupport {

	@ParameterizedTest(name = "{0}")
	@MethodSource("centrifuges")
	void activePausedAndCommittedPaidWorkSurvivesMenuEncodingAndPlacement(Item item) {
		for (boolean committed : new boolean[]{false, true}) {
			var original = tile(item);
			populate(original);
			var source = dismantled(original, item);
			var nbt = source.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
			String key;
			if (committed) {
				key = "productivebeesgenesis_pb_committed_pending";
				var pending = new CompoundTag();
				pending.putInt("version", 2);
				pending.putInt("process", 0);
				var output = (CompoundTag) content(Items.EMERALD, 1, 501).save(registries);
				output.putLong("productivebeesgenesis_count", 3_000_000_000L);
				var items = new ListTag();
				items.add(output);
				pending.put("items", items);
				pending.put("fluid", honey(1, 501).save(registries));
				pending.putLong("fluid_amount", 2_500_000_000L);
				var all = new ListTag();
				all.add(pending);
				nbt.put(key, all);
			} else {
				key = "productivebeesgenesis_pb_paid_work";
				var root = nbt.getCompound(key);
				var input = ContainerType.ITEM.getOrEmpty(source).containers().getFirst().copyWithCount(1);
				var active = paidWork(input);
				active.putInt("process", 0);
				root.getList("entries", 10).add(active);
				var paused = new CompoundTag();
				paused.put("work", paidWork(content(Items.HONEYCOMB, 1, 502)));
				paused.putInt("progress", 7);
				paused.putInt("processing_time", 20);
				root.getList("paused", 10).add(paused);
				int lanes = ((PbRecipeContext) original).processes();
				var progress = new int[lanes];
				progress[0] = 7;
				var processing = new byte[lanes];
				processing[0] = 1;
				var duration = new int[lanes];
				duration[0] = 20;
				nbt.putIntArray("productivebeesgenesis_pb_progress", progress);
				nbt.putByteArray("productivebeesgenesis_pb_processing", processing);
				nbt.putIntArray("productivebeesgenesis_pb_processing_time", duration);
			}
			source.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(nbt));
			restore(original, source);
			assertTrue(((IMekCentrifugeTile) original).productivebeesgenesis$canUpgradeProduction());
			var saved = dismantled(original, item);
			assertEquals(nbt.get(key), saved.get(DataComponents.BLOCK_ENTITY_DATA).copyTag().get(key));
			var before = saved.copy();
			var recipe = recipe(item, item, "A");
			var level = level(recipe);
			var input = CraftingInput.of(1, 1, List.of(saved));
			assertTrue(recipe.matches(input, level));
			var result = recipe.assemble(input, registries);
			assertStack(before, result);
			assertEncoded(recipe, saved, result, level);
			var restored = tile(item);
			restore(restored, roundTrip(result));
			assertTrue(((IMekCentrifugeTile) restored).productivebeesgenesis$canUpgradeProduction());
			assertStack(before, dismantled(restored, item));
			assertStack(before, saved);
		}
	}

	private static CompoundTag paidWork(ItemStack input) {
		var snapshot = new CompoundTag();
		snapshot.putInt("version", 2);
		snapshot.putString("id", "productivebeesgenesis:frozen_pattern_test");
		var output = new CompoundTag();
		output.put("stack", content(Items.DIAMOND, 1, 500).save(registries));
		output.putIntArray("amounts", new int[]{2, 2, Float.floatToRawIntBits(1)});
		var outputs = new ListTag();
		outputs.add(output);
		snapshot.put("items", outputs);
		snapshot.put("fluid", honey(100, 500).save(registries));
		var work = new CompoundTag();
		work.put("input", input.save(registries));
		work.putInt("base_ticks", 20);
		work.putBoolean("myriad", false);
		work.put("recipe", snapshot);
		return work;
	}
}
