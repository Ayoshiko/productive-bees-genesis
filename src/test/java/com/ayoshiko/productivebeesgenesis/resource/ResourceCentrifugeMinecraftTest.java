package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.RegisterResourceRecipesEvent;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceCentrifugeMinecraftTest {
	private static final ResourceLocation BEE = ResourceLocation.parse("example:hydrogen");
	private static final ResourceLocation ID = ResourceLocation.parse("example:centrifuging");
	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

	private static ResourceCentrifugeRecipe recipe(long amount) {
		return new ResourceCentrifugeRecipe(BEE, List.of(new ResourceProductionRecipe.Output(
				ResourceLocation.parse("mekanism:hydrogen"), amount, 1)), 20);
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


	@Test void realTickDebitsOnceAndPublishesOnlyAfterDeferredConfirmation() throws ReflectiveOperationException {
		try (var fixture = new ProcessorFixture(true)) {
			for (var lane : fixture.lanes) {
				assertEquals(ResourceCentrifugeProcessor.Result.WORKING, ResourceCentrifugeProcessor.tick(lane, 25));
				assertEquals(3, lane.input.getCount());
				assertEquals(750L, lane.energy);
				assertEquals(1, lane.inputDebits);
				assertEquals(1, lane.energyDebits);
				assertEquals(5, lane.paidTicks);
				assertEquals(20, lane.requiredTicks);
				assertEquals(1, lane.localHandoffs);
				assertNotNull(lane.completion);
				assertNull(fixture.account.centrifugeWork(lane.lane));
				assertTrue(fixture.account.laneSuspended(lane.lane));
			}
			assertTrue(fixture.account.available().isEmpty());
			assertEquals(200L, fixture.account.frozenProduction().get(ResourceAccountsMinecraftTest.HYDROGEN));
			assertEquals(BigInteger.ZERO, fixture.produced());
			var waiting = fixture.store.save();
			assertEquals(ResourceCentrifugeProcessor.Result.BLOCKED,
					ResourceCentrifugeProcessor.tick(fixture.lanes[0], 25));
			assertEquals(waiting, fixture.store.save());
			assertEquals(750L, fixture.lanes[0].energy);
			for (var lane : fixture.lanes) lane.completion.complete();
			assertEquals(200L, fixture.account.available().get(ResourceAccountsMinecraftTest.HYDROGEN));
			assertEquals(BigInteger.valueOf(200), fixture.produced());
			for (var lane : fixture.lanes) {
				assertFalse(fixture.account.laneSuspended(lane.lane));
				var work = ResourceCentrifugeWork.load(fixture.account.centrifugeWork(lane.lane), REGISTRIES);
				assertEquals(5, work.paidTicks);
				assertTrue(ItemStack.isSameItemSameComponents(lane.input, work.input));
				assertEquals(fixture.recipe, work.recipe);
				assertEquals(fixture.ordinary, work.ordinaryRecipe);
				assertEquals(1, lane.inputDebits);
				assertEquals(1, lane.energyDebits);
			}
			assertThrows(IllegalStateException.class, fixture.lanes[0].completion::complete);
			assertEquals(BigInteger.valueOf(200), fixture.produced());
			verify(fixture.host, times(2)).status("ready");
		}
	}

	@Test void realTickUnknownLaneKeepsItsRequestWhileOtherLaneCompletes() throws ReflectiveOperationException {
		try (var fixture = new ProcessorFixture(true)) {
			for (var lane : fixture.lanes) {
				assertEquals(ResourceCentrifugeProcessor.Result.WORKING, ResourceCentrifugeProcessor.tick(lane, 25));
			}
			var firstTicket = fixture.account.productionTicket();
			fixture.lanes[0].completion.uncertain("Shared AE key receipt unknown");
			fixture.lanes[1].completion.complete();
			assertTrue(fixture.account.laneSuspended(0));
			assertFalse(fixture.account.laneSuspended(1));
			assertEquals(firstTicket, fixture.account.productionTicket());
			assertEquals(100L, fixture.account.available().get(ResourceAccountsMinecraftTest.HYDROGEN));
			assertEquals(BigInteger.valueOf(100), fixture.produced());
			assertFalse(fixture.lanes[0].working);
			assertTrue(fixture.lanes[1].working);
			var saved = fixture.store.save();
			assertEquals(ResourceCentrifugeProcessor.Result.BLOCKED,
					ResourceCentrifugeProcessor.tick(fixture.lanes[0], 25));
			assertEquals(saved, fixture.store.save());
			assertEquals(1, fixture.lanes[0].inputDebits);
			assertEquals(1, fixture.lanes[0].energyDebits);
			var restoredStore = ResourceAccounts.load(saved, () -> {});
			assertFalse(restoredStore.hasInvalidData());
			var restored = restoredStore.find(firstTicket.source().sourceId());
			assertTrue(restored.laneSuspended(0));
			assertEquals(5, restored.centrifugeWork(1).getInt("paid_ticks"));
			restored.resolveProduction(ResourceAccountsMinecraftTest.OWNER, firstTicket, true);
			assertEquals(200L, restored.available().get(ResourceAccountsMinecraftTest.HYDROGEN));
			assertEquals(5, ResourceCentrifugeWork.load(restored.centrifugeWork(0), REGISTRIES).paidTicks);
		}
	}

	@Test void realTickSynchronousFallbackMatchesDeferredAmountsFeesAndPaidWork() throws ReflectiveOperationException {
		CompoundTag[] deferredWork = new CompoundTag[2];
		long[] deferredEnergy = new long[2];
		int[] deferredInputs = new int[2];
		Map<ResourceKey, Long> deferredAmounts;
		try (var fixture = new ProcessorFixture(true)) {
			for (var lane : fixture.lanes) {
				assertEquals(ResourceCentrifugeProcessor.Result.WORKING, ResourceCentrifugeProcessor.tick(lane, 25));
			}
			for (var lane : fixture.lanes) {
				lane.completion.complete();
				deferredWork[lane.lane] = fixture.account.centrifugeWork(lane.lane).copy();
				deferredEnergy[lane.lane] = lane.energy;
				deferredInputs[lane.lane] = lane.input.getCount();
			}
			deferredAmounts = fixture.account.available();
		}
		try (var fixture = new ProcessorFixture(false)) {
			for (var lane : fixture.lanes) {
				assertEquals(ResourceCentrifugeProcessor.Result.WORKING, ResourceCentrifugeProcessor.tick(lane, 25));
				assertNull(lane.completion);
				assertEquals(1, lane.localHandoffs);
				assertEquals(deferredEnergy[lane.lane], lane.energy);
				assertEquals(deferredInputs[lane.lane], lane.input.getCount());
				assertEquals(deferredWork[lane.lane], fixture.account.centrifugeWork(lane.lane));
			}
			assertNull(fixture.account.productionTicket());
			assertEquals(deferredAmounts, fixture.account.available());
			assertEquals(BigInteger.valueOf(200), fixture.produced());
		}
	}

	private static final class ProcessorFixture implements AutoCloseable {
		private final ResourceCentrifugeRecipe recipe = new ResourceCentrifugeRecipe(BEE, recipe(100).outputs(), 20, true);
		private final ResourceAccounts store = new ResourceAccounts(() -> {});
		private final ResourceAccount account = ResourceAccountsMinecraftTest.account(store);
		private final ResourceProductionMonitor monitor = new ResourceProductionMonitor(() -> 1000L);
		private final ResourceHiveHost host = mock(ResourceHiveHost.class);
		private final BlockEntity entity = mock(BlockEntity.class);
		private final ServerLevel level = mock(ServerLevel.class);
		private final MinecraftServer server = mock(MinecraftServer.class);
		private final CompoundTag ordinary = new CompoundTag();
		private final ProcessorLane[] lanes = new ProcessorLane[2];
		private final MockedStatic<ResourceRuntime> runtime;
		private final MockedStatic<ResourceHiveHost> hosts;

		private ProcessorFixture(boolean deferred) throws ReflectiveOperationException {
			var random = RandomSource.create(81237L);
			var randomField = Level.class.getField("random");
			randomField.setAccessible(true);
			randomField.set(level, random);
			assertSame(random, level.random);
			when(level.getRandom()).thenReturn(random);
			when(level.getServer()).thenReturn(server);
			when(level.registryAccess()).thenReturn(REGISTRIES);
			when(level.getGameTime()).thenReturn(100L);
			when(entity.getLevel()).thenReturn(level);
			when(entity.getBlockPos()).thenReturn(BlockPos.ZERO);
			when(host.reference()).thenReturn(account.reference());
			when(host.account(entity)).thenReturn(account);
			when(host.capacity()).thenReturn(10_000L);
			when(host.status()).thenReturn("");
			when(host.productionMonitor()).thenReturn(monitor);
			when(host.fitsExact(same(account), anyMap())).thenAnswer(call -> {
				Map<ResourceKey, BigInteger> maximum = call.getArgument(1);
				var occupied = account.reservedCapacity();
				for (var entry : maximum.entrySet()) {
					if (occupied.getOrDefault(entry.getKey(), BigInteger.ZERO).add(entry.getValue())
							.compareTo(BigInteger.valueOf(10_000L)) > 0) return false;
				}
				return account.canReserve(account.reference(), maximum);
			});
			ordinary.putString("id", "example:ordinary");
			var index = ResourceRecipeIndex.build(7, List.of(), List.of(new RecipeHolder<>(ID, recipe)));
			for (int lane = 0; lane < lanes.length; lane++) lanes[lane] = new ProcessorLane(this, lane, deferred);
			runtime = mockStatic(ResourceRuntime.class);
			runtime.when(() -> ResourceRuntime.centrifugeAdmission(eq(server), any(ItemStack.class)))
					.thenReturn(ResourceRuntime.CentrifugeAdmission.READY);
			runtime.when(() -> ResourceRuntime.recipes(server)).thenReturn(index);
			runtime.when(() -> ResourceRuntime.available(eq(server), any(ResourceKey.class))).thenReturn(true);
			hosts = mockStatic(ResourceHiveHost.class);
			hosts.when(() -> ResourceHiveHost.ensureMechanical(entity)).thenReturn(host);
			hosts.when(() -> ResourceHiveHost.existing(entity)).thenReturn(host);
		}

		private BigInteger produced() {
			return monitor.snapshot(100).entries().stream()
					.map(ResourceProductionSnapshot.Entry::produced).reduce(BigInteger.ZERO, BigInteger::add);
		}

		@Override
		public void close() {
			hosts.close();
			runtime.close();
			store.close();
		}
	}

	private static final class ProcessorLane implements ResourceCentrifugeProcessor.Access {
		private final ProcessorFixture fixture;
		private final int lane;
		private final boolean deferred;
		private final ItemStack input;
		private long energy = 1000;
		private int inputDebits;
		private int energyDebits;
		private int localHandoffs;
		private int paidTicks;
		private int requiredTicks;
		private boolean working;
		private ResourceCentrifugeProcessor.LocalBatch.Completion completion;

		private ProcessorLane(ProcessorFixture fixture, int lane, boolean deferred) {
			this.fixture = fixture;
			this.lane = lane;
			this.deferred = deferred;
			input = fixture.recipe.input(false).copyWithCount(4);
			input.set(DataComponents.CUSTOM_NAME, Component.literal("lane " + lane));
		}

		@Override public BlockEntity entity() { return fixture.entity; }
		@Override public int lane() { return lane; }
		@Override public ItemStack input() { return input; }
		@Override public long energy() { return energy; }
		@Override public long energyPerTick() { return 10; }
		@Override public int requiredTicks(int base) { return base; }
		@Override public int parallel() { return 1; }
		@Override public int productivity() { return 1; }
		@Override public double stability() { return 0; }
		@Override public boolean canOperate() { return true; }
		@Override public CompoundTag ordinaryRecipe() { return fixture.ordinary.copy(); }

		@Override
		public ItemStack consume(int count, boolean simulate) {
			int consumed = Math.min(count, input.getCount());
			var result = input.copyWithCount(consumed);
			if (!simulate) {
				input.shrink(consumed);
				inputDebits++;
			}
			return result;
		}

		@Override
		public long consumeEnergy(long amount, boolean simulate) {
			long consumed = Math.min(amount, energy);
			if (!simulate) {
				energy -= consumed;
				energyDebits++;
			}
			return consumed;
		}

		@Override
		public void progress(int paid, int required, boolean active) {
			paidTicks = paid;
			requiredTicks = required;
			working = active;
		}

		@Override
		public ResourceCentrifugeProcessor.LocalPlan localPlan(CompoundTag recipe) {
			assertEquals(fixture.ordinary, recipe);
			return new ResourceCentrifugeProcessor.LocalPlan() {
				@Override public int maximumInputs(int limit) { return limit; }

				@Override
				public ResourceCentrifugeProcessor.LocalBatch prepare(int inputs, long seed) {
					return new ResourceCentrifugeProcessor.LocalBatch() {
						private boolean handed;

						@Override
						public CompoundTag snapshot() {
							var evidence = new CompoundTag();
							evidence.putInt("inputs", inputs);
							evidence.putLong("seed", seed);
							return evidence;
						}

						@Override
						public void commit() {
							assertFalse(handed);
							handed = true;
							localHandoffs++;
						}

						@Override
						public boolean defer(Supplier<Completion> factory) {
							if (!deferred) return false;
							commit();
							completion = factory.get();
							return true;
						}
					};
				}
			};
		}
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
