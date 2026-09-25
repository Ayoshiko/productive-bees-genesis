package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 使用实际依赖的倍增实现，接收端经过本模组目标及真实槽位插入。 */
@Tag("minecraft")
class AdaptiveBatchDispatchMinecraftTest {
	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@BeforeEach
	void beginDispatchTick() {
		CentrifugeDispatchScope.reset();
		com.ayoshiko.productivebeesgenesis.util.ServerTickClock.reset();
		com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor.getInstance().invalidate();
		com.ayoshiko.productivebeesgenesis.util.ServerTickClock.tick();
	}

	@AfterEach
	void resetDispatchTick() {
		CentrifugeDispatchScope.reset();
		com.ayoshiko.productivebeesgenesis.util.ServerTickClock.reset();
		com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor.getInstance().invalidate();
	}

	@Test
	void lightningAdaptiveRampTransfersTenMillionWithoutPerCopyLoop() throws Exception {
		requireOptionalMod("ae2lt", "productivebeesgenesis.test.ae2ltCompat");
		var machine = new CentrifugePatternProviderMinecraftTest.Fixture();
		Class<?> targetType = Class.forName("com.moakiee.ae2lt.logic.ProviderTarget");
		Object provider = targetType.getConstructor(ResourceKey.class, BlockPos.class, Direction.class)
				.newInstance(Level.OVERWORLD, BlockPos.ZERO, Direction.UP);
		Class<?> chunkType = Class.forName("com.moakiee.ae2lt.logic.ProviderTarget$BatchChunk");
		var chunk = chunkType.getConstructor(long.class, boolean.class, boolean.class);
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger largestBatch = new AtomicInteger();
		AEItemKey input = AEItemKey.of(Items.RAW_IRON);
		IntFunction<Object> push = copies -> {
			assertTrue(calls.incrementAndGet() <= 64, "自适应倍增不得退化成千万次逐份调用");
			largestBatch.accumulateAndGet(copies, Math::max);
			long simulated = machine.target.insert(input, copies, Actionable.SIMULATE);
			assertEquals(copies, simulated);
			long accepted = machine.target.insert(input, copies, Actionable.MODULATE);
			assertEquals(copies, accepted);
			try {
				return chunk.newInstance(accepted, accepted == copies, false);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
		};
		Object result = targetType.getMethod("pushPattern", IPatternDetails.class, long.class,
				boolean.class, BooleanSupplier.class, IntFunction.class)
				.invoke(provider, pattern(), 10_000_000L, true, (BooleanSupplier) () -> false, push);
		assertEquals(10_000_000L, result.getClass().getMethod("ownedCopies").invoke(result));
		assertEquals(10_000_000L, machine.totalInput());
		assertTrue(largestBatch.get() > 1_000_000, "应爬升到大批次");
		assertFalse((boolean) result.getClass().getMethod("globalAbort").invoke(result));
	}

	@Test
	void lightningRejectsUnownedBatchAndResumesWithInputsConserved() throws Exception {
		requireOptionalMod("ae2lt", "productivebeesgenesis.test.ae2ltCompat");
		var machine = new CentrifugePatternProviderMinecraftTest.Fixture();
		var input = AEItemKey.of(Items.RAW_IRON);
		for (int i = 0; i < CentrifugeDispatchScope.MIN_BUDGET; i++) {
			assertEquals(1, machine.target.insert(input, 1, Actionable.MODULATE));
		}
		var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
		inventory.insert(input, 10_000_000L, Actionable.MODULATE);
		Class<?> targetType = Class.forName("com.moakiee.ae2lt.logic.ProviderTarget");
		Object provider = targetType.getConstructor(ResourceKey.class, BlockPos.class, Direction.class)
				.newInstance(Level.OVERWORLD, BlockPos.ZERO, Direction.UP);
		Class<?> acceptanceType = Class.forName("com.moakiee.ae2lt.logic.PatternInputAcceptance");
		Object completeBatch = acceptanceType.getField("COMPLETE_BATCH").get(null);
		var insert = Class.forName("com.moakiee.ae2lt.logic.AE2NativeMachineAdapter")
				.getDeclaredMethod("pushPlannedInputs", appeng.helpers.patternprovider.PatternProviderTarget.class,
						List.class, int.class, acceptanceType);
		insert.setAccessible(true);
		var chunk = Class.forName("com.moakiee.ae2lt.logic.ProviderTarget$BatchChunk")
				.getConstructor(long.class, boolean.class, boolean.class);
		AtomicInteger calls = new AtomicInteger();
		IntFunction<Object> push = copies -> {
			assertTrue(calls.incrementAndGet() <= 65, "拒收与恢复仍须保持有界批次调用");
			try {
				// 真正执行闪电的模拟、提交与零接收归属判断；源库存只扣它确认接管的份数。
				Object result = insert.invoke(null, machine.target,
						List.of(new GenericStack(input, copies)), copies, completeBatch);
				int owned = (int) result.getClass().getMethod("acceptedCopies").invoke(result);
				var overflow = (List<?>) result.getClass().getMethod("overflow").invoke(result);
				assertTrue(overflow.isEmpty(), "单原料整批只能完全接收或保持未接管");
				assertEquals(owned, inventory.extract(input, owned, Actionable.MODULATE));
				assertEquals(10_000_000L, inventory.extract(input, Long.MAX_VALUE, Actionable.SIMULATE)
						+ machine.totalInput() - CentrifugeDispatchScope.MIN_BUDGET);
				return chunk.newInstance((long) owned, owned == copies, false);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
		};
		var dispatch = targetType.getMethod("pushPattern", IPatternDetails.class, long.class,
				boolean.class, BooleanSupplier.class, IntFunction.class);
		IPatternDetails pattern = pattern();
		Object rejected = dispatch.invoke(provider, pattern, 10_000_000L, true,
				(BooleanSupplier) () -> false, push);
		assertEquals(0L, rejected.getClass().getMethod("ownedCopies").invoke(rejected));
		assertEquals(10_000_000L, inventory.extract(input, Long.MAX_VALUE, Actionable.SIMULATE));
		assertEquals(CentrifugeDispatchScope.MIN_BUDGET, machine.totalInput());

		com.ayoshiko.productivebeesgenesis.util.ServerTickClock.tick();
		Object resumed = dispatch.invoke(provider, pattern, 10_000_000L, true,
				(BooleanSupplier) () -> false, push);
		assertEquals(10_000_000L, resumed.getClass().getMethod("ownedCopies").invoke(resumed));
		assertFalse((boolean) resumed.getClass().getMethod("globalAbort").invoke(resumed));
		assertEquals(0L, inventory.extract(input, Long.MAX_VALUE, Actionable.SIMULATE));
		assertEquals(10_000_000L + CentrifugeDispatchScope.MIN_BUDGET, machine.totalInput());
	}

	@Test
	void eaepScaledPatternTransfersWholeBatchInOneInsertion() throws Exception {
		requireOptionalMod("extendedae_plus", "productivebeesgenesis.test.ecoCompat");
		var machine = new CentrifugePatternProviderMinecraftTest.Fixture();
		long copies = 10_000_000L;
		IPatternDetails scaled = (IPatternDetails) Class
				.forName("com.extendedae_plus.util.smartDoubling.PatternScaler")
				.getMethod("createScaled", IPatternDetails.class, long.class).invoke(null, pattern(), copies);
		assertEquals(copies, scaled.getOutputs().getFirst().amount());
		KeyCounter input = new KeyCounter();
		input.add(AEItemKey.of(Items.RAW_IRON), copies);
		AtomicInteger calls = new AtomicInteger();
		scaled.pushInputsToExternalInventory(new KeyCounter[]{input}, (key, amount) -> {
			assertEquals(copies, amount);
			assertEquals(amount, machine.target.insert(key, amount, Actionable.SIMULATE));
			assertEquals(0, machine.totalInput());
			assertEquals(amount, machine.target.insert(key, amount, Actionable.MODULATE));
			calls.incrementAndGet();
		});
		assertEquals(1, calls.get());
		assertEquals(copies, machine.totalInput());
	}

	private static IPatternDetails pattern() {
		return new AEProcessingPattern(AEItemKey.of(PatternDetailsHelper.encodeProcessingPattern(
				List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), 1)),
				List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)))));
	}

	private static void requireOptionalMod(String mod, String requiredProperty) {
		boolean loaded = ModList.get().isLoaded(mod);
		if (Boolean.getBoolean(requiredProperty)) assertTrue(loaded, "Required test mod: " + mod);
		assumeTrue(loaded);
	}
}
