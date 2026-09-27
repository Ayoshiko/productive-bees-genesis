package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.patternprovider.PatternProviderTarget;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mekanism.api.RelativeSide;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class CentrifugePatternProviderMinecraftTest {

	@Test
	void cachedProviderSeesBufferedInputs() throws Exception {
		Fixture fixture = new Fixture();
		Class<?> cacheType = Class.forName("appeng.helpers.patternprovider.PatternProviderTargetCache");
		Object cache = org.mockito.Mockito.mock(cacheType, org.mockito.Mockito.CALLS_REAL_METHODS);
		var source = cacheType.getDeclaredField("src");
		source.setAccessible(true);
		source.set(cache, IActionSource.empty());
		var wrap = cacheType.getDeclaredMethod("wrapMeStorage", MEStorage.class);
		wrap.setAccessible(true);
		var cached = (PatternProviderTarget) wrap.invoke(cache, fixture.storage);
		AEItemKey iron = AEItemKey.of(Items.RAW_IRON);
		assertFalse(cached.containsPatternInput(Set.of(iron)));
		assertEquals(42, cached.insert(iron, 42, Actionable.SIMULATE));
		assertFalse(cached.containsPatternInput(Set.of(iron)));
		assertEquals(42, cached.insert(iron, 42, Actionable.MODULATE));
		assertTrue(cached.containsPatternInput(Set.of(iron)));
		fixture.config.setDataType(DataType.NONE, RelativeSide.TOP);
		assertFalse(cached.containsPatternInput(Set.of(iron)));
		assertEquals(0, cached.insert(iron, 1, Actionable.MODULATE));
		fixture.config.setDataType(DataType.INPUT, RelativeSide.TOP);
		assertTrue(cached.containsPatternInput(Set.of(iron)));
		assertEquals(0, fixture.storage.getAvailableStacks().get(iron));
		assertEquals(0, fixture.storage.extract(iron, 42, Actionable.MODULATE, IActionSource.empty()));
		fixture.inputs.forEach(BasicInventorySlot::setEmpty);
		assertFalse(cached.containsPatternInput(Set.of(iron)));
	}

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void unsetClockAllowsSingleInsertsToSpreadAcrossLanes() throws Exception {
		assertEquals(com.ayoshiko.productivebeesgenesis.util.ServerTickClock.UNSET,
				com.ayoshiko.productivebeesgenesis.util.ServerTickClock.now());
		Fixture fixture = new Fixture();
		AEItemKey iron = AEItemKey.of(Items.RAW_IRON);
		// 无服务器时钟的夹具只校验真实槽容量和游标轮转；真实 tick 限流另由 guard 测试与专服探针验证。
		int inserts = 20_000;
		long accepted = 0;
		for (int i = 0; i < inserts; i++) {
			accepted += fixture.target.insert(iron, 1, Actionable.MODULATE);
		}
		assertEquals(inserts, accepted, "逐份插入应全部被接收，不再被工作集截断");
		assertEquals(inserts, fixture.totalInput());
		for (var slot : fixture.inputs) {
			assertTrue(slot.getCount() > 0, "游标轮转应让每条产线都分到输入");
		}
	}

	@Test
	void externalBatchFillsRealSlotCapacityAndSimulationDoesNotConsumeSpace() throws Exception {
		Fixture fixture = new Fixture();
		AEItemKey iron = AEItemKey.of(Items.RAW_IRON);
		// 真实容量 = 17 槽 ×（64 × 278528）。移除工作集节流后，外部批次可一次填满机器真实容量（超大堆叠），
		// 让 ECO/EAEP/闪电 的大批次翻倍直达槽位，而不是被压小成滴流。
		long realCapacity = 17L * 64 * 278_528;
		assertEquals(realCapacity, fixture.target.insert(iron, Long.MAX_VALUE, Actionable.SIMULATE));
		assertEquals(0, fixture.totalInput(), "SIMULATE 不占用空间");
		assertEquals(realCapacity, fixture.target.insert(iron, Long.MAX_VALUE, Actionable.MODULATE));
		assertEquals(realCapacity, fixture.totalInput());
		// 填满真实容量后返回 0（缓冲满，非工作集节流）：剩余留在 sendList，等机器消耗后再进。
		assertEquals(0, fixture.target.insert(iron, 1, Actionable.MODULATE));
	}

	@Test
	void providerSeesInputForBlockingWithoutAdvertisingExtractableStock() throws Exception {
		Fixture fixture = new Fixture();
		AEItemKey iron = AEItemKey.of(Items.RAW_IRON);
		Set<appeng.api.stacks.AEKey> patternInputs = Set.of(iron.dropSecondary());
		assertFalse(fixture.target.containsPatternInput(patternInputs));
		assertEquals(1, fixture.target.insert(iron, 1, Actionable.SIMULATE));
		assertFalse(fixture.target.containsPatternInput(patternInputs));
		assertEquals(1, fixture.target.insert(iron, 1, Actionable.MODULATE));
		assertTrue(fixture.target.containsPatternInput(patternInputs));
		assertFalse(fixture.target.containsPatternInput(Set.of(AEItemKey.of(Items.GOLD_INGOT))));
		assertEquals(0, fixture.storage.getAvailableStacks().get(iron));
		assertEquals(0, fixture.storage.extract(iron, 1, Actionable.MODULATE, IActionSource.empty()));
		fixture.inputs.forEach(BasicInventorySlot::setEmpty);
		assertFalse(fixture.target.containsPatternInput(patternInputs));
	}

	@Test
	void blockingFollowsLiveSideConfigAndIgnoresItemComponentsLikeAe2() throws Exception {
		Fixture fixture = new Fixture();
		ItemStack iron = new ItemStack(Items.RAW_IRON, 10);
		iron.set(DataComponents.CUSTOM_NAME, Component.literal("named input"));
		fixture.inputs.getLast().setStack(iron);
		Set<appeng.api.stacks.AEKey> keys = Set.of(AEItemKey.of(Items.RAW_IRON).dropSecondary());
		assertTrue(fixture.target.containsPatternInput(keys));
		fixture.config.setDataType(DataType.NONE, RelativeSide.TOP);
		assertFalse(fixture.target.containsPatternInput(keys));
		assertEquals(0, fixture.target.insert(AEItemKey.of(Items.RAW_IRON), 1, Actionable.SIMULATE));
		fixture.config.setDataType(DataType.INPUT, RelativeSide.TOP);
		assertTrue(fixture.target.containsPatternInput(keys));
	}

	@Test
	void outputVisibilityRemainsDelegatedToOriginalAe2Target() throws Exception {
		Fixture fixture = new Fixture();
		fixture.config.setDataType(DataType.OUTPUT, RelativeSide.TOP);
		fixture.output.setStack(new ItemStack(Items.IRON_INGOT, 42));
		AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
		assertTrue(fixture.target.containsPatternInput(Set.of(iron)));
		assertEquals(42, fixture.storage.getAvailableStacks().get(iron));
		assertEquals(42, fixture.storage.extract(iron, 100, Actionable.MODULATE, IActionSource.empty()));
		assertFalse(fixture.target.containsPatternInput(Set.of(iron)));
	}

	@Test
	void unrelatedStorageRetainsItsOriginalTarget() throws Exception {
		Fixture fixture = new Fixture();
		MEStorage unrelated = new MEStorage() {
			@Override
			public Component getDescription() {
				return Component.literal("Unrelated storage");
			}
		};
		assertSame(fixture.target, CentrifugePatternProviderTarget.wrap(unrelated, fixture.target));
	}

	@Test
	void ecoScaledBatchFlattensToOneInputAndMachineTakesFullBatch() throws Exception {
		assumeTrue(ModList.get().isLoaded("neoecoae") && ModList.get().isLoaded("extendedae_plus"));
		long copies = 10_000_000L;
		// 10M < 真实容量（17×64×278528≈303M），移除工作集节流后，机器一次就把整批 10M 直达槽位。
		IPatternDetails scaled = ecoScaledPattern(copies);
		assertEquals(copies, scaled.getOutputs().getFirst().amount());
		for (boolean outputToAe : new boolean[]{false, true}) {
			for (boolean directToAe : new boolean[]{false, true}) {
				Fixture fixture = new Fixture();
				fixture.config.setDataType(DataType.INPUT_OUTPUT, RelativeSide.TOP);
				fixture.config.setEjecting(true);
				fixture.holder.setAeItemOutputEnabled(outputToAe);
				fixture.holder.setCentrifugeDirectAeOutputEnabled(directToAe);
				KeyCounter inputs = new KeyCounter();
				inputs.add(AEItemKey.of(Items.RAW_IRON), copies);
				AtomicInteger inserts = new AtomicInteger();
				scaled.pushInputsToExternalInventory(new KeyCounter[]{inputs}, (key, amount) -> {
					// ECO 的 scaled pattern 把千万份展平成单次输入提交（而非千万次调用）。
					assertEquals(copies, amount);
					// 机器一次接收整批（10M < 真实容量），不再被工作集截断成 1088。
					assertEquals(copies, fixture.target.insert(key, amount, Actionable.SIMULATE));
					assertEquals(0, fixture.totalInput());
					assertEquals(copies, fixture.target.insert(key, amount, Actionable.MODULATE));
					inserts.incrementAndGet();
				});
				assertEquals(1, inserts.get());
				assertEquals(copies, fixture.totalInput());
			}
		}
	}

	@Test
	void directOutputAndExternalExtractionOnlyTransferRemainingItems() throws Exception {
		for (boolean directFirst : new boolean[]{false, true}) {
			for (int directCapacity : new int[]{0, 13, 42}) {
				Fixture fixture = new Fixture();
				fixture.config.setDataType(DataType.INPUT_OUTPUT, RelativeSide.TOP);
				fixture.output.setStack(new ItemStack(Items.IRON_INGOT, 42));
				AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
				long external = directFirst ? 0 : fixture.storage.extract(
						iron, 17, Actionable.MODULATE, IActionSource.empty());
				AtomicInteger inserted = new AtomicInteger();
				MEStorage network = new MEStorage() {
					@Override
					public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
						assertEquals(iron, key);
						int accepted = (int) Math.min(amount, directCapacity);
						if (!mode.isSimulate()) inserted.addAndGet(accepted);
						return accepted;
					}

					@Override
					public Component getDescription() {
						return Component.literal("Counting network");
					}
				};
				var entry = new Ae2SlotEntry();
				entry.set(fixture.output, fixture.output.getStack(), iron, fixture.output.getCount(),
						0, 0, "iron");
				int direct = Ae2OutputCommitter.tryPushSlotDirect(entry, network, IActionSource.empty(),
						fixture.holder.getOutputLedger());
				external += fixture.storage.extract(iron, 42, Actionable.MODULATE, IActionSource.empty());
				assertEquals(inserted.get(), direct);
				assertEquals(42, direct + external);
				assertTrue(fixture.output.isEmpty());
				assertEquals(0, fixture.holder.getOutputLedger().size());
			}
		}
	}

	private static IPatternDetails ecoScaledPattern(long copies) throws Exception {
		var encoded = PatternDetailsHelper.encodeProcessingPattern(
				List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), 1)),
				List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)));
		var pattern = new AEProcessingPattern(AEItemKey.of(encoded));
		Class<?> aware = Class.forName("com.extendedae_plus.api.smartDoubling.ISmartDoublingAwarePattern");
		aware.getMethod("eap$setAllowScaling", boolean.class).invoke(pattern, true);
		aware.getMethod("eap$setMultiplierLimit", int.class).invoke(pattern, 0);
		Class<?> scaling = Class.forName("cn.dancingsnow.neoecoae.compat.extendedaeplus.ECOExtendedAEPlusScaling");
		var scaled = (IPatternDetails) scaling.getMethod("scale", IPatternDetails.class, long.class)
				.invoke(null, pattern, copies);
		assertNotNull(scaled);
		return scaled;
	}

	static final class Fixture {
		final List<InputInventorySlot> inputs = new ArrayList<>();
		final BasicInventorySlot output = BasicInventorySlot.at(null, 0, 0);
		final ConfigInfo config;
		final MEStorage storage;
		final PatternProviderTarget target;
		final Ae2OutputStateHolder holder = new Ae2OutputStateHolder();

		Fixture() throws Exception {
			var sideHost = ModBlockEntities.MEK_CENTRIFUGE.get().create(
					BlockPos.ZERO, ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
			assertNotNull(sideHost);
			config = sideHost.getConfig().getConfig(TransmissionType.ITEM);
			config.setDataType(DataType.INPUT, RelativeSide.TOP);
			for (int i = 0; i < 17; i++) {
				var slot = InputInventorySlot.at(stack -> stack.is(Items.RAW_IRON), null, 0, 0);
				((TieredInputSlot) slot).productivebeesgenesis$setInputStackMultiplier(() -> 278_528);
				((TieredInputSlot) slot).productivebeesgenesis$markInputSlot();
				inputs.add(slot);
			}
			var host = (IAe2OutputHostBase) Proxy.newProxyInstance(IAe2OutputHostBase.class.getClassLoader(),
					new Class<?>[]{IAe2OutputHostBase.class, IMekCentrifugeTile.class}, (proxy, method, args) ->
						switch (method.getName()) {
							case "productivebeesgenesis$getAe2StateHolder" -> holder;
							case "productivebeesgenesis$getInputSlotCount" -> inputs.size();
							case "productivebeesgenesis$getInputSlot" -> inputs.get((int) args[0]);
							case "productivebeesgenesis$isValidInput" -> ((ItemStack) args[0]).is(Items.RAW_IRON);
							case "processes" -> 1;
							case "primaryOutputSlot" -> output;
							case "secondaryOutputSlot", "tertiaryOutputSlot", "productivebeesgenesis$onAe2PushComplete",
									"productivebeesgenesis$markInputSortingNeeded" -> null;
							case "productivebeesgenesis$outputContentsVersion" -> (long) output.getCount();
							default -> throw new AssertionError("Unexpected call: " + method.getName());
						});
			storage = CentrifugeExternalAeStorage.getOrCreate(host, (IMekCentrifugeTile) host, sideHost, Direction.UP);
			// 执行 AE2 原生包装入口，包含实际应用的供应器 Mixin。
			var wrap = PatternProviderTarget.class.getDeclaredMethod("wrapMeStorage", MEStorage.class, IActionSource.class);
			wrap.setAccessible(true);
			target = (PatternProviderTarget) wrap.invoke(null, storage, IActionSource.empty());
		}

		long totalInput() {
			return inputs.stream().mapToLong(BasicInventorySlot::getCount).sum();
		}
	}
}
