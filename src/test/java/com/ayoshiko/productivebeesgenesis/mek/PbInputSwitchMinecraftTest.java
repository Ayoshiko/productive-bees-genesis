package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class PbInputSwitchMinecraftTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	private static final String WORK = "productivebeesgenesis_pb_paid_work";
	private static final String PROGRESS = "productivebeesgenesis_pb_progress";

	@BeforeAll
	static void configs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unload() {
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	@Test
	void replacementStartsFromZeroAndOriginalCompletesWithOnlyItsRemainingEnergy() {
		Fixture fixture = new Fixture(1);
		ItemStack first = named(Items.HONEYCOMB, "first");
		ItemStack replacement = named(Items.HONEYCOMB, "replacement");
		RecipeHolder<CentrifugeRecipe> firstRecipe = recipe("first", Items.DIAMOND);
		RecipeHolder<CentrifugeRecipe> replacementRecipe = recipe("replacement", Items.EMERALD);
		fixture.inputs[0].setStack(first.copy());
		assertTrue(fixture.run(0, firstRecipe, 7));
		assertEquals(70, fixture.spent);
		assertEquals(7, fixture.save().getIntArray(PROGRESS)[0]);

		fixture.inputs[0].setStack(replacement.copy());
		assertTrue(fixture.run(0, replacementRecipe, 4));
		CompoundTag switched = fixture.save();
		assertEquals(4, switched.getIntArray(PROGRESS)[0]);
		assertEquals(7, switched.getCompound(WORK).getList("paused", 10).getCompound(0).getInt("progress"));
		assertEquals(110, fixture.spent);

		fixture.inputs[0].setStack(first.copy());
		assertTrue(fixture.run(0, firstRecipe, 13));
		assertTrue(fixture.inputs[0].isEmpty());
		assertEquals(1, fixture.outputs[0].getCount());
		assertTrue(fixture.outputs[0].getStack().is(Items.DIAMOND));
		assertEquals(240, fixture.spent);
		assertEquals(4, fixture.save().getCompound(WORK).getList("paused", 10).getCompound(0).getInt("progress"));
	}

	@Test
	void returnedInputAndRestartRetainOriginalRecipeUntilOnePaidCycleFinishes() {
		Fixture fixture = new Fixture(1);
		ItemStack first = named(Items.HONEYCOMB, "original components");
		RecipeHolder<CentrifugeRecipe> oldRecipe = recipe("old", Items.DIAMOND);
		fixture.inputs[0].setStack(first.copy());
		assertTrue(fixture.run(0, oldRecipe, 7));
		fixture.inputs[0].shrinkStack(1, Action.EXECUTE);
		fixture.processor.resetPbState(0);
		CompoundTag saved = fixture.save();
		assertEquals(0, saved.getIntArray(PROGRESS)[0]);
		assertEquals(1, saved.getCompound(WORK).getList("paused", 10).size());

		Fixture restored = new Fixture(1);
		restored.processor.loadAdditional(saved, REGISTRIES);
		assertTrue(restored.processor.hasRetainedInput(first));
		assertFalse(restored.processor.hasRetainedInput(named(Items.HONEYCOMB, "changed components")));
		restored.inputs[0].setStack(first.copyWithCount(2));
		assertTrue(restored.run(0, recipe("new", Items.EMERALD), 40));
		assertEquals(1, restored.inputs[0].getCount());
		assertTrue(restored.outputs[0].getStack().is(Items.DIAMOND));
		assertEquals(130, restored.spent);
		assertFalse(restored.processor.hasRetainedInput(first));
		restored.outputs[0].setStack(ItemStack.EMPTY);
		assertTrue(restored.run(0, recipe("new", Items.EMERALD), 20));
		assertTrue(restored.outputs[0].getStack().is(Items.EMERALD));
		assertEquals(330, restored.spent);
	}

	@Test
	void clearingTransientCachesPreservesActivePausedAndCommittedOutputAssets() {
		Fixture fixture = new Fixture(2);
		ItemStack original = named(Items.HONEYCOMB, "paused original");
		ItemStack replacement = named(Items.HONEYCOMB, "active replacement");
		var originalRecipe = recipe("original", Items.DIAMOND);
		fixture.inputs[0].setStack(original.copy());
		assertTrue(fixture.run(0, originalRecipe, 7));
		fixture.inputs[0].setStack(replacement.copy());
		assertTrue(fixture.run(0, recipe("replacement", Items.EMERALD), 4));
		for (var slot : List.of(fixture.context.primaryOutputSlot(1),
				fixture.context.secondaryOutputSlot(1), fixture.context.tertiaryOutputSlot(1))) {
			slot.setStack(new ItemStack(Items.STONE, 64));
		}
		when(fixture.context.productivebeesgenesis$isDirectAeOutputEnabled()).thenReturn(true);
		fixture.inputs[1].setStack(named(Items.HONEYCOMB, "completed pending"));
		assertTrue(fixture.run(1, recipe("pending", Items.IRON_INGOT), 20));
		CompoundTag beforeClear = fixture.save();
		assertEquals(1, beforeClear.getCompound(WORK).getList("entries", 10).size());
		assertEquals(1, beforeClear.getCompound(WORK).getList("paused", 10).size());
		assertEquals(1, beforeClear.getList("productivebeesgenesis_pb_committed_pending", 10).size());
		assertEquals(310, fixture.spent);

		fixture.processor.clearTransientCaches();
		fixture.processor.clearTransientCaches();
		assertEquals(beforeClear, fixture.save());
		assertEquals(310, fixture.spent);
		assertTrue(ItemStack.matches(replacement, fixture.inputs[0].getStack()));
		assertTrue(fixture.inputs[1].isEmpty());

		fixture.outputs[1].setStack(ItemStack.EMPTY);
		assertTrue(fixture.processor.drainCommittedPendingOutputs(1));
		assertTrue(fixture.outputs[1].getStack().is(Items.IRON_INGOT));
		assertEquals(1, fixture.outputs[1].getCount());
		assertEquals(310, fixture.spent);
		fixture.inputs[0].setStack(original.copy());
		assertTrue(fixture.run(0, originalRecipe, 13));
		assertTrue(fixture.outputs[0].getStack().is(Items.DIAMOND));
		assertEquals(1, fixture.outputs[0].getCount());
		assertEquals(440, fixture.spent);
		assertEquals(4, fixture.save().getCompound(WORK).getList("paused", 10).getCompound(0).getInt("progress"));
	}

	@Test
	void sameIdentityAcrossLanesClaimsEachPausedCycleOnce() {
		Fixture fixture = new Fixture(2);
		ItemStack first = named(Items.HONEYCOMB, "same identity");
		RecipeHolder<CentrifugeRecipe> recipe = recipe("shared", Items.DIAMOND);
		fixture.inputs[0].setStack(first.copy());
		fixture.inputs[1].setStack(first.copy());
		assertTrue(fixture.run(0, recipe, 5));
		assertTrue(fixture.run(1, recipe, 9));
		fixture.inputs[0].setStack(ItemStack.EMPTY);
		fixture.inputs[1].setStack(ItemStack.EMPTY);
		fixture.processor.reconcileInputWork();
		assertEquals(2, fixture.save().getCompound(WORK).getList("paused", 10).size());

		fixture.inputs[0].setStack(first.copy());
		fixture.inputs[1].setStack(first.copy());
		fixture.processor.reconcileInputWork();
		CompoundTag resumed = fixture.save();
		assertArrayEquals(new int[] {5, 9}, resumed.getIntArray(PROGRESS));
		assertTrue(resumed.getCompound(WORK).getList("paused", 10).isEmpty());
		fixture.processor.reconcileInputWork();
		assertEquals(resumed, fixture.save());
		assertTrue(fixture.run(0, recipe, 15));
		assertTrue(fixture.run(1, recipe, 11));
		assertEquals(400, fixture.spent);
		assertEquals(1, fixture.outputs[0].getCount());
		assertEquals(1, fixture.outputs[1].getCount());
	}

	@Test
	void laterLaneClaimsWorkParkedEarlierInTheSameReconciliationPass() {
		Fixture fixture = new Fixture(2);
		ItemStack original = named(Items.HONEYCOMB, "moved paid input");
		ItemStack replacement = named(Items.HONEYCOMB, "new lane input");
		var selected = recipe("moved", Items.DIAMOND);
		fixture.inputs[0].setStack(original.copy());
		assertTrue(fixture.run(0, selected, 7));
		assertTrue(fixture.save().getCompound(WORK).getList("paused", 10).isEmpty());
		fixture.inputs[0].setStack(replacement.copy());
		fixture.inputs[1].setStack(original.copy());

		fixture.processor.reconcileInputWork();
		CompoundTag reconciled = fixture.save();
		assertArrayEquals(new int[]{0, 7}, reconciled.getIntArray(PROGRESS));
		assertTrue(reconciled.getCompound(WORK).getList("paused", 10).isEmpty());
		ListTag entries = reconciled.getCompound(WORK).getList("entries", 10);
		assertEquals(1, entries.size());
		assertEquals(1, entries.getCompound(0).getInt("process"));
		assertEquals(70, fixture.spent);
		assertTrue(ItemStack.matches(replacement, fixture.inputs[0].getStack()));
		assertTrue(ItemStack.matches(original, fixture.inputs[1].getStack()));
		fixture.processor.reconcileInputWork();
		assertEquals(reconciled, fixture.save());

		assertTrue(fixture.run(1, selected, 13));
		assertEquals(200, fixture.spent);
		assertTrue(fixture.inputs[1].isEmpty());
		assertTrue(fixture.outputs[1].getStack().is(Items.DIAMOND));
		assertEquals(1, fixture.outputs[1].getCount());
	}

	@Test
	void legacyProgressCanSwitchWithoutFollowingTheReplacementIdentity() {
		Fixture fixture = new Fixture(1);
		ItemStack original = named(Items.HONEYCOMB, "legacy original");
		fixture.inputs[0].setStack(original.copy());
		CompoundTag legacy = new CompoundTag();
		legacy.putIntArray(PROGRESS, new int[] {7});
		legacy.putIntArray("productivebeesgenesis_pb_processing_time", new int[] {20});
		fixture.processor.loadAdditional(legacy, REGISTRIES);
		fixture.inputs[0].setStack(named(Items.HONEYCOMB, "new material"));
		assertTrue(fixture.run(0, recipe("new", Items.EMERALD), 4));
		assertEquals(4, fixture.save().getIntArray(PROGRESS)[0]);
		fixture.inputs[0].setStack(original.copy());
		assertTrue(fixture.run(0, recipe("old", Items.DIAMOND), 13));
		assertTrue(fixture.outputs[0].getStack().is(Items.DIAMOND));
		assertEquals(170, fixture.spent);
	}

	@Test
	void versionOneActiveWorkCanSwitchAndRoundTripAsVersionTwo() {
		Fixture fixture = new Fixture(1);
		ItemStack original = named(Items.HONEYCOMB, "version one");
		fixture.inputs[0].setStack(original.copy());
		assertTrue(fixture.run(0, recipe("old", Items.DIAMOND), 7));
		CompoundTag legacy = fixture.save();
		legacy.getCompound(WORK).putInt("version", 1);
		legacy.getCompound(WORK).remove("paused");

		Fixture restored = new Fixture(1);
		restored.inputs[0].setStack(original.copy());
		restored.processor.loadAdditional(legacy, REGISTRIES);
		restored.inputs[0].setStack(named(Items.HONEYCOMB, "replacement"));
		assertTrue(restored.run(0, recipe("new", Items.EMERALD), 2));
		CompoundTag changed = restored.save();
		assertEquals(2, changed.getCompound(WORK).getInt("version"));
		assertEquals(7, changed.getCompound(WORK).getList("paused", 10).getCompound(0).getInt("progress"));
		assertFalse(restored.processor.hasUnresolvedWork());
	}

	@Test
	void unfinishedPaymentStillBlocksReplacementAndKeepsItsRequest() {
		Fixture fixture = new Fixture(1);
		ItemStack original = named(Items.HONEYCOMB, "payment original");
		fixture.inputs[0].setStack(original);
		when(fixture.energy.extract(anyLong(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL)))
				.thenThrow(new IllegalStateException("controlled payment failure"));
		assertFalse(fixture.run(0, recipe("old", Items.DIAMOND), 7));
		var request = fixture.processor.paymentStatus().getFirst();
		fixture.inputs[0].setStack(named(Items.HONEYCOMB, "replacement"));
		fixture.processor.reconcileInputWork();
		assertFalse(fixture.run(0, recipe("new", Items.EMERALD), 20));
		assertEquals(request, fixture.processor.paymentStatus().getFirst());
		assertTrue(fixture.save().getCompound(WORK).getList("paused", 10).isEmpty());
		assertEquals(1, fixture.inputs[0].getCount());
		verify(fixture.energy).extract(70, Action.EXECUTE, AutomationType.INTERNAL);
	}

	@Test
	void frozenUnconsumedOutputCannotBeDetachedByNormalInputSwitch() {
		Fixture fixture = new Fixture(1);
		ItemStack original = named(Items.HONEYCOMB, "frozen original");
		fixture.inputs[0].setStack(original.copy());
		PbRecipeCompleter owner = new PbRecipeCompleter(fixture.context);
		owner.accumulatePbRecipeOutputsBatch(recipe("frozen", Items.DIAMOND).value(), 0, 1, 1);
		owner.freezeCompletedBatch(original);
		CompoundTag frozen = owner.saveCommittedPending(REGISTRIES);
		frozen.putInt("process", 0);
		ListTag pending = new ListTag();
		pending.add(frozen);
		CompoundTag save = fixture.save();
		save.put("productivebeesgenesis_pb_committed_pending", pending);
		fixture.processor.loadAdditional(save, REGISTRIES);
		fixture.inputs[0].setStack(named(Items.HONEYCOMB, "replacement"));
		fixture.processor.reconcileInputWork();
		assertFalse(fixture.run(0, recipe("new", Items.EMERALD), 20));
		assertEquals(pending, fixture.save().getList("productivebeesgenesis_pb_committed_pending", 10));
		assertEquals(0, fixture.spent);
		assertEquals(1, fixture.inputs[0].getCount());
	}

	@Test
	void unknownPausedFieldsPreserveTheWholeOriginalTagAndBlockProduction() {
		Fixture fixture = new Fixture(1);
		ItemStack original = named(Items.HONEYCOMB, "saved original");
		fixture.inputs[0].setStack(original);
		assertTrue(fixture.run(0, recipe("old", Items.DIAMOND), 3));
		fixture.inputs[0].setStack(ItemStack.EMPTY);
		fixture.processor.reconcileInputWork();
		CompoundTag saved = fixture.save();
		saved.getCompound(WORK).getList("paused", 10).getCompound(0).putInt("unknown", 1);
		CompoundTag originalTag = saved.getCompound(WORK).copy();
		Fixture restored = new Fixture(1);
		restored.processor.loadAdditional(saved, REGISTRIES);
		restored.inputs[0].setStack(named(Items.HONEYCOMB, "replacement"));
		assertFalse(restored.run(0, recipe("new", Items.EMERALD), 20));
		assertTrue(restored.processor.hasUnresolvedWork());
		assertEquals(originalTag, restored.save().getCompound(WORK));
		assertEquals(0, restored.spent);
	}

	@Test
	void workBudgetIsReservedBeforePaymentAndReleasedAfterCompletion() {
		Fixture fixture = new Fixture(1);
		ItemStack oversized = named(Items.HONEYCOMB, "oversized component");
		CompoundTag component = new CompoundTag();
		component.putByteArray("payload", new byte[(int) PbPausedWorkBuffer.MAX_WORK_BYTES]);
		oversized.set(DataComponents.CUSTOM_DATA, CustomData.of(component));
		fixture.inputs[0].setStack(oversized);
		assertFalse(fixture.run(0, recipe("oversized", Items.DIAMOND), 7));
		assertEquals(0, fixture.spent);
		assertEquals(0, fixture.save().getIntArray(PROGRESS)[0]);
		assertFalse(fixture.processor.hasRetainedInput(oversized));
		fixture.inputs[0].setStack(named(Items.HONEYCOMB, "normal"));
		assertTrue(fixture.run(0, recipe("normal", Items.DIAMOND), 20));
		assertEquals(200, fixture.spent);
		assertTrue(fixture.save().getCompound(WORK).getList("entries", 10).isEmpty());
	}

	@Test
	void completedCyclesRefreshSameIdRecipeWithoutChangingCostsOrInputIdentity() {
		Fixture fixture = new Fixture(1);
		ItemStack input = named(Items.HONEYCOMB, "stable identity");
		RecipeHolder<CentrifugeRecipe> original = recipe("same_id", Items.DIAMOND);
		fixture.inputs[0].setStack(input.copyWithCount(3));
		assertTrue(fixture.run(0, original, 20));
		assertTrue(fixture.run(0, original, 20));
		assertEquals(2, fixture.outputs[0].getCount());
		assertEquals(400, fixture.spent);
		fixture.outputs[0].setStack(ItemStack.EMPTY);
		assertTrue(fixture.run(0, recipe("same_id", Items.EMERALD), 20));
		assertTrue(fixture.outputs[0].getStack().is(Items.EMERALD));
		assertEquals(1, fixture.outputs[0].getCount());
		assertEquals(600, fixture.spent);
		assertTrue(fixture.inputs[0].isEmpty());
		assertFalse(fixture.processor.hasRetainedInput(input));
	}

	@Test
	void repeatedTemplateStillReservesEveryPausedCycleAndReleasesOnlyCompletedWork() {
		ItemStack input = named(Items.HONEYCOMB, "large but valid component");
		CompoundTag component = new CompoundTag();
		component.putByteArray("payload", new byte[1_100_000]);
		input.set(DataComponents.CUSTOM_DATA, CustomData.of(component));
		var cache = new PbPaidWorkCache(1);
		var selected = recipe("budget", Items.DIAMOND);
		var first = cache.get(0, input, selected, 20, REGISTRIES);
		var buffer = new PbPausedWorkBuffer(1);
		assertTrue(buffer.reserve(0, first, REGISTRIES));
		assertTrue(buffer.park(0, 7, 20));
		var second = cache.get(0, input.copy(), selected, 20, REGISTRIES);
		assertFalse(buffer.reserve(0, second, REGISTRIES));
		assertEquals(1, buffer.save().size());
		cache.clear();
		var resumed = buffer.take(0, input);
		assertNotNull(resumed);
		assertEquals(7, resumed.progress);
		assertNull(buffer.take(0, input));
		buffer.release(0);
		assertTrue(buffer.reserve(0, second, REGISTRIES));
		assertTrue(buffer.park(0, 3, 20));
		CompoundTag saved = buffer.save().getCompound(0);
		saved.getCompound("work").getCompound("input").putInt("count", 99);
		assertEquals(1, buffer.save().getCompound(0).getCompound("work").getCompound("input").getInt("count"));
		assertEquals(3, buffer.save().getCompound(0).getInt("progress"));
	}

	private static ItemStack named(Item item, String name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}

	private static RecipeHolder<CentrifugeRecipe> recipe(String path, Item output) {
		var result = new ChancedOutput(Ingredient.of(output), 1, 1, 1);
		CentrifugeRecipe recipe = new CentrifugeRecipe(Ingredient.of(Items.HONEYCOMB), List.of(result), null, 20) {
			@Override
			public FluidStack getFluidOutputs() {
				return FluidStack.EMPTY;
			}
		};
		return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", path), recipe);
	}

	private static final class Fixture {
		final PbRecipeContext context = mock(PbRecipeContext.class);
		final MachineEnergyContainer<?> energy = mock(MachineEnergyContainer.class);
		final BasicInventorySlot[] inputs;
		final BasicInventorySlot[] outputs;
		final PbRecipeProcessor processor;
		long spent;

		Fixture(int lanes) {
			Level level = mock(Level.class);
			when(level.registryAccess()).thenReturn(REGISTRIES);
			when(level.getGameTime()).thenReturn(1L);
			when(context.level()).thenReturn(level);
			when(context.processes()).thenReturn(lanes);
			when(context.canFunction()).thenReturn(true);
			doReturn(energy).when(context).energyContainer();
			when(context.operationsPerTick()).thenReturn(1);
			when(context.productivityModifier()).thenReturn(1);
			when(context.productivityParallelModifier()).thenReturn(1);
			when(context.getTicksForBase(anyInt())).thenAnswer(call -> call.getArgument(0));
			when(context.baseTicksRequired()).thenReturn(20);
			when(energy.getEnergy()).thenAnswer(call -> 1_000_000L - spent);
			when(energy.getEnergyPerTick()).thenReturn(10L);
			when(energy.extract(anyLong(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL))).thenAnswer(call -> {
				long amount = call.getArgument(0);
				spent += amount;
				return amount;
			});
			inputs = new BasicInventorySlot[lanes];
			outputs = new BasicInventorySlot[lanes];
			for (int lane = 0; lane < lanes; lane++) {
				inputs[lane] = BasicInventorySlot.at(null, 0, 0);
				outputs[lane] = BasicInventorySlot.at(null, 0, 0);
				when(context.inputSlot(lane)).thenReturn(inputs[lane]);
				when(context.primaryOutputSlot(lane)).thenReturn(outputs[lane]);
				when(context.secondaryOutputSlot(lane)).thenReturn(BasicInventorySlot.at(null, 0, 0));
				when(context.tertiaryOutputSlot(lane)).thenReturn(BasicInventorySlot.at(null, 0, 0));
			}
			processor = new PbRecipeProcessor(context, "input-switch-test");
		}

		boolean run(int lane, RecipeHolder<CentrifugeRecipe> recipe, int ticks) {
			processor.refreshEnergyAndOpsCache(context);
			processor.setTickMultiplier(ticks);
			return processor.tryProcessPbRecipe(lane, recipe);
		}

		CompoundTag save() {
			CompoundTag result = new CompoundTag();
			processor.saveAdditional(result, REGISTRIES);
			return result;
		}
	}
}
