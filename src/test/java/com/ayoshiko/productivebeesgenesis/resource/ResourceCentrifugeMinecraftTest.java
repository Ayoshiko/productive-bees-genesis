package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.RegisterResourceRecipesEvent;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.PoweredCentrifugeBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceCentrifugeMinecraftTest {
	private static final ResourceLocation BEE = ResourceLocation.parse("example:hydrogen");
	private static final ResourceLocation ID = ResourceLocation.parse("example:centrifuging");
	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

	private static ResourceCentrifugeRecipe recipe(long amount) {
		return new ResourceCentrifugeRecipe(BEE, List.of(new ResourceProductionRecipe.Output(
				ResourceLocation.parse("mekanism:hydrogen"), amount, 1)), 20);
	}

	@Test void advancingPaidWorkKeepsSnapshotsIndependentAndFrozen() {
		var input = recipe(100).input(true);
		input.set(DataComponents.CUSTOM_NAME, Component.literal("frozen component"));
		var work = new ResourceCentrifugeWork(ID, recipe(100), input, 7, 91, 5);
		var first = work.save(REGISTRIES);
		var next = work.advance(11, 92);
		first.getCompound("recipe").putString("corrupt", "external mutation");
		first.getCompound("input").remove("id");
		var restored = ResourceCentrifugeWork.load(next.save(REGISTRIES), REGISTRIES);
		assertEquals(11, restored.paidTicks);
		assertEquals(92, restored.seed);
		assertEquals(5, restored.revision);
		assertEquals(recipe(100), restored.recipe);
		assertTrue(ItemStack.isSameItemSameComponents(input, restored.input));
		assertEquals(7, ResourceCentrifugeWork.load(work.save(REGISTRIES), REGISTRIES).paidTicks);
	}

	@Test void ownCombAndBlockMatchExactBeeIdentityAndHaveFourUnits() {
		var recipe = recipe(100);
		assertEquals(1, recipe.units(recipe.input(false)));
		assertEquals(4, recipe.units(recipe.input(true)));
		var wrongBee = recipe.input(false);
		wrongBee.set(PbDataComponents.beeType(), ResourceLocation.parse("example:oxygen"));
		assertEquals(0, recipe.units(wrongBee));
		var untyped = recipe.input(false);
		untyped.remove(PbDataComponents.beeType());
		assertEquals(0, recipe.units(untyped));
		var fake = new ItemStack(Items.STONE);
		fake.set(PbDataComponents.beeType(), BEE);
		assertEquals(0, recipe.units(fake));
		assertEquals(0, recipe.units(ItemStack.EMPTY));
		var sample = recipe.input(false);
		sample.setCount(45);
		assertEquals(1, recipe.input(false).getCount());
	}

	@Test void compactEvidenceMatchesExistingFieldsWithoutSharingMutableSnapshots() {
		var recipe = new ResourceCentrifugeRecipe(BEE, recipe(100).outputs(), 20, true);
		var ordinary = new net.minecraft.nbt.CompoundTag();
		ordinary.putString("id", "example:ordinary");
		ordinary.put("items", new net.minecraft.nbt.ListTag());
		var work = new ResourceCentrifugeWork(ID, recipe, recipe.input(true), 7, 91, 5, ordinary);
		var expected = work.save(REGISTRIES);
		expected.remove("ordinary_recipe");
		expected.putString("ordinary_recipe_id", "example:ordinary");
		var evidence = work.evidence(REGISTRIES);
		assertEquals(expected, evidence);
		evidence.getCompound("input").remove("id");
		assertEquals(expected, work.evidence(REGISTRIES));
		assertEquals(ordinary, ResourceCentrifugeWork.load(work.save(REGISTRIES), REGISTRIES).ordinaryRecipe);
		assertEquals(ordinary, ResourceCentrifugeWork.load(work.advance(11, 92).save(REGISTRIES), REGISTRIES).ordinaryRecipe);
	}

	@Test void combinationIsExplicitAndSurvivesRecipeViewAndPaidWorkCodecs() {
		var legacy = recipe(100);
		assertFalse(legacy.includePbOutputs());
		var combined = new ResourceCentrifugeRecipe(BEE, legacy.outputs(), 20, true);
		var json = Recipe.CODEC.encodeStart(JsonOps.INSTANCE, combined).getOrThrow();
		assertEquals(combined, Recipe.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
		try {
			ResourceCentrifugeRecipe.STREAM_CODEC.encode(buf, combined);
			assertEquals(combined, ResourceCentrifugeRecipe.STREAM_CODEC.decode(buf));
			var view = new ResourceRecipeView(ID, BEE, combined.outputs(), 20, true);
			view.write(buf);
			assertEquals(view, ResourceRecipeView.read(buf));
		} finally { buf.release(); }
		var ordinary = new net.minecraft.nbt.CompoundTag(); ordinary.putString("evidence", "frozen PB selection");
		var work = new ResourceCentrifugeWork(ID, combined, combined.input(true), 7, 91, 5, ordinary);
		var restored = ResourceCentrifugeWork.load(work.save(REGISTRIES), REGISTRIES);
		assertEquals(ordinary, restored.ordinaryRecipe);
		assertTrue(restored.recipe.includePbOutputs());
		var oldSave = new ResourceCentrifugeWork(ID, legacy, legacy.input(false), 3, 17, 1).save(REGISTRIES);
		oldSave.putInt("version", 1);
		assertFalse(ResourceCentrifugeWork.load(oldSave, REGISTRIES).recipe.includePbOutputs());
	}

	@Test void recipeDispatchAndNetworkPreserveLongAmounts() {
		assertSame(ResourceCentrifugeRecipe.TYPE, BuiltInRegistries.RECIPE_TYPE.get(ResourceCentrifugeRecipe.ID));
		assertSame(ResourceCentrifugeRecipe.SERIALIZER, BuiltInRegistries.RECIPE_SERIALIZER.get(ResourceCentrifugeRecipe.ID));
		var recipe = recipe(Long.MAX_VALUE);
		var json = Recipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
		assertEquals(recipe, Recipe.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
		try {
			ResourceCentrifugeRecipe.STREAM_CODEC.encode(buf, recipe);
			assertEquals(recipe, ResourceCentrifugeRecipe.STREAM_CODEC.decode(buf));
			buf.clear();
			ResourceProductionRecipe.STREAM_CODEC.encode(buf, new ResourceProductionRecipe(BEE, recipe.outputs()));
			buf.writeVarInt(0);
			buf.writeBoolean(false);
			assertThrows(IllegalArgumentException.class, () -> ResourceCentrifugeRecipe.STREAM_CODEC.decode(buf));
		} finally { buf.release(); }
	}

	@Test void publicCentrifugePlanUsesUnitsAndCheckedAmounts() {
		var recipe = recipe(100);
		var plan = ResourceProductionApi.planCentrifuging(recipe, 2, 0, 91);
		assertEquals(800L, plan.sample(recipe.units(recipe.input(true))).get(ResourceAccountsMinecraftTest.HYDROGEN));
		assertEquals(plan.maximum(4), plan.sample(4));
		assertThrows(IllegalArgumentException.class, () -> ResourceProductionApi.planCentrifuging(recipe, 1, Double.NaN, 91));
		assertThrows(ArithmeticException.class, () -> ResourceProductionApi.planCentrifuging(recipe(Long.MAX_VALUE), 2, 0, 91));
	}

	@Test void reusablePlansKeepPerWorkSeedsAndRejectOverflowBeforeSampling() {
		var outputs = List.of(new ResourceProductionRecipe.Output(ResourceLocation.parse("mekanism:hydrogen"), 7, .37));
		var plan = new ResourceRollPlan(outputs, 2.5, 2, 0);
		for (long cycles : List.of(0L, 1L, 32L, 33L, 1_048_576L)) {
			for (long seed : List.of(0L, 91L, Long.MIN_VALUE)) {
				var independent = new ResourceRollPlan(outputs, 2.5, 2, seed);
				assertEquals(independent.sampleWithRolls(cycles), plan.sampleWithRolls(cycles, seed));
			}
		}
		var exact = new ResourceRollPlan(recipe(100).outputs(), 2, 2, 91);
		assertEquals(600L * 1_048_576, exact.sample(1_048_576).get(ResourceAccountsMinecraftTest.HYDROGEN));
		assertThrows(UnsupportedOperationException.class, () -> exact.maximum(1).clear());
		assertThrows(IllegalArgumentException.class, () -> exact.sample(-1));
		for (double chance : List.of(1.0, .37)) {
			var overflow = new ResourceRollPlan(List.of(new ResourceProductionRecipe.Output(
					ResourceLocation.parse("mekanism:hydrogen"), Long.MAX_VALUE, chance)), 1, 0, 91);
			assertThrows(ArithmeticException.class, () -> overflow.sample(2));
		}
	}

	@Test void indexChoosesStableRecipeAndOldSnapshotSurvivesReload() {
		var first = new RecipeHolder<>(ID, recipe(100));
		var later = new RecipeHolder<>(ResourceLocation.parse("example:z"), recipe(200));
		var old = ResourceRecipeIndex.build(1, List.of(), List.of(later, first));
		assertSame(first, old.centrifuge(recipe(1).input(false)));
		assertTrue(old.forBee(BEE).isEmpty());
		assertNull(ResourceRecipeIndex.build(2, List.of()).centrifuge(first.value().input(false)));
		assertEquals(100, old.centrifuge(first.value().input(false)).value().outputs().getFirst().amount());
		assertThrows(UnsupportedOperationException.class, () -> old.centrifugeRecipes().clear());
		assertThrows(IllegalArgumentException.class, () -> ResourceRecipeIndex.build(2, List.of(), List.of(first, first)));
	}

	@Test void javaRegistrationAndDatapackOverrideShareOneNamespace() {
		Consumer<RegisterResourceRecipesEvent> listener = event -> event.registerCentrifuging(ID, recipe(100));
		NeoForge.EVENT_BUS.addListener(listener);
		try {
			var defaults = ResourceProductionApi.collect(1, List.of());
			assertEquals(100, defaults.centrifuge(recipe(1).input(true)).value().outputs().getFirst().amount());
			var overridden = ResourceProductionApi.collect(2, List.of(), List.of(new RecipeHolder<>(ID, recipe(250))));
			assertEquals(250, overridden.centrifuge(recipe(1).input(false)).value().outputs().getFirst().amount());
			var direct = new ResourceProductionRecipe(BEE, recipe(500).outputs());
			var changedRoute = ResourceProductionApi.collect(3, List.of(new RecipeHolder<>(ID, direct)));
			assertNull(changedRoute.centrifuge(recipe(1).input(false)));
			assertEquals(1, changedRoute.forBee(BEE).size());
		} finally { NeoForge.EVENT_BUS.unregister(listener); }
		var event = new RegisterResourceRecipesEvent(4);
		event.registerCentrifuging(ID, recipe(100));
		assertThrows(IllegalArgumentException.class, () -> event.register(ID, BEE, recipe(100).outputs()));
		event.finish();
		assertThrows(IllegalStateException.class, () -> event.registerCentrifuging(ID, recipe(100)));
	}

	@Test void paidWorkKeepsAllComponentsRecipeSeedAndRevision() {
		var input = recipe(100).input(true);
		input.set(DataComponents.CUSTOM_NAME, Component.literal("retained input"));
		var work = new ResourceCentrifugeWork(ID, recipe(100), input, 7, 91, 5);
		input.remove(DataComponents.CUSTOM_NAME);
		var saved = work.save(REGISTRIES);
		var restored = ResourceCentrifugeWork.load(saved, REGISTRIES);
		assertEquals(7, restored.paidTicks);
		assertEquals(91, restored.seed);
		assertEquals(5, restored.revision);
		assertEquals(work.recipe, restored.recipe);
		assertTrue(ItemStack.isSameItemSameComponents(work.input, restored.input));
		assertFalse(ItemStack.isSameItemSameComponents(input, restored.input));
		saved.putInt("version", 3);
		assertThrows(IllegalArgumentException.class, () -> ResourceCentrifugeWork.load(saved, REGISTRIES));
	}

	@Test void progressAndOutputCommitOnceAndPersistTogether() {
		var store = new ResourceAccounts(() -> {});
		var account = ResourceAccountsMinecraftTest.account(store);
		var work = new ResourceCentrifugeWork(ID, recipe(100), recipe(100).input(false), 7, 91, 5);
		var ticket = account.reserve(account.reference(), 5, new net.minecraft.nbt.CompoundTag(),
				Map.of(ResourceAccountsMinecraftTest.HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(ResourceAccountsMinecraftTest.HYDROGEN, 100L));
		var next = work.save(REGISTRIES);
		account.commitCentrifuge(ticket, 0, next);
		next.putInt("paid_ticks", 999);
		assertEquals(7, account.centrifugeWork(0).getInt("paid_ticks"));
		assertThrows(IllegalStateException.class, () -> account.commitCentrifuge(ticket, 0, null));
		var loaded = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
		assertEquals(account.available(), loaded.available());
		assertEquals(account.centrifugeWork(0), loaded.centrifugeWork(0));
	}

	@Test void invalidLaneDoesNotPartiallyCommitFrozenOutput() {
		var store = new ResourceAccounts(() -> {});
		var account = ResourceAccountsMinecraftTest.account(store);
		var ticket = account.reserve(account.reference(), 1, new net.minecraft.nbt.CompoundTag(),
				Map.of(ResourceAccountsMinecraftTest.HYDROGEN, 100L));
		account.start(ticket);
		account.freeze(ticket, Map.of(ResourceAccountsMinecraftTest.HYDROGEN, 100L));
		var before = store.save();
		assertThrows(IllegalArgumentException.class, () -> account.commitCentrifuge(ticket, -1, null));
		assertEquals(before, store.save());
		assertTrue(account.available().isEmpty());
	}

	@Test void nativeCentrifugesKeepLegacyRecoveryButDoNotInterceptResourceRecipes() {
		for (String name : List.of("centrifuge", "powered_centrifuge", "heated_centrifuge")) {
			var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebees:" + name));
			var entity = ((net.minecraft.world.level.block.EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
			var centrifuge = assertInstanceOf(CentrifugeBlockEntity.class, entity);
			assertTrue(ResourceHiveHost.supports(centrifuge));
			assertFalse(PbResourceCentrifuge.ready(centrifuge));
			assertFalse(ResourceHiveHost.ready(centrifuge));
		}
	}
}
