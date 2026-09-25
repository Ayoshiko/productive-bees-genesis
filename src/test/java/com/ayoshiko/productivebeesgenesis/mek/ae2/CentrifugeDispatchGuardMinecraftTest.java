package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor;
import com.ayoshiko.productivebeesgenesis.util.ServerTickClock;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.*;

@Tag("minecraft")
class CentrifugeDispatchGuardMinecraftTest {
	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@BeforeEach
	void beginTick() {
		CentrifugeDispatchScope.reset();
		ServerTickClock.reset();
		ServerTickTimeMonitor.getInstance().invalidate();
		ServerTickClock.tick();
	}

	@AfterEach
	void releaseState() {
		CentrifugeDispatchScope.reset();
		ServerTickClock.reset();
		ServerTickTimeMonitor.getInstance().invalidate();
	}

	@Test
	void repeatedSimulationDoesNotSpendBudgetAndLastPermitAcceptsWholeBatch() throws Exception {
		var machine = new CentrifugePatternProviderMinecraftTest.Fixture();
		var key = AEItemKey.of(Items.RAW_IRON);
		for (int i = 0; i < 20_000; i++) {
			assertEquals(10_000_000L, machine.target.insert(key, 10_000_000L, Actionable.SIMULATE));
		}
		assertEquals(0, machine.totalInput());
		for (int i = 0; i < CentrifugeDispatchScope.MIN_BUDGET - 1; i++) {
			assertEquals(1, machine.target.insert(key, 1, Actionable.MODULATE));
		}
		assertEquals(10_000_000L, machine.target.insert(key, 10_000_000L, Actionable.MODULATE));
		assertEquals(0, machine.target.insert(key, 1, Actionable.MODULATE));
		assertEquals(10_000_255L, machine.totalInput());
		assertEquals(10_000_000L, machine.target.insert(key, 10_000_000L, Actionable.SIMULATE));
	}

	@Test
	void budgetIsSharedAcrossOwnTargetsButOrdinaryStorageDoesNotSpendIt() throws Exception {
		var first = new CentrifugePatternProviderMinecraftTest.Fixture();
		var second = new CentrifugePatternProviderMinecraftTest.Fixture();
		var key = AEItemKey.of(Items.RAW_IRON);
		for (int i = 0; i < 1000; i++) {
			assertEquals(1, first.storage.insert(key, 1, Actionable.MODULATE, IActionSource.empty()));
		}
		for (int i = 0; i < CentrifugeDispatchScope.MIN_BUDGET; i++) {
			var machine = (i & 1) == 0 ? first : second;
			assertEquals(1, machine.target.insert(key, 1, Actionable.MODULATE));
		}
		assertEquals(0, first.target.insert(key, 1, Actionable.MODULATE));
		assertEquals(0, second.target.insert(key, 1, Actionable.MODULATE));
		assertEquals(1, second.storage.insert(key, 1, Actionable.MODULATE, IActionSource.empty()));
		ServerTickClock.tick();
		assertEquals(1, first.target.insert(key, 1, Actionable.MODULATE));
	}

	@Test
	void actualProviderRetainsRejectedBatchAndDrainsItNextTickWithoutLoss() throws Exception {
		var machine = new CentrifugePatternProviderMinecraftTest.Fixture();
		var key = AEItemKey.of(Items.RAW_IRON);
		var pattern = new AEProcessingPattern(AEItemKey.of(PatternDetailsHelper.encodeProcessingPattern(
				List.of(new GenericStack(key, 1)),
				List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)))));
		var node = mock(IManagedGridNode.class, RETURNS_SELF);
		when(node.isActive()).thenReturn(true);
		var host = mock(PatternProviderLogicHost.class);
		when(host.getTargets()).thenAnswer(call -> EnumSet.of(Direction.NORTH));
		var blockEntity = mock(BlockEntity.class);
		when(blockEntity.getBlockPos()).thenReturn(BlockPos.ZERO);
		when(host.getBlockEntity()).thenReturn(blockEntity);
		var provider = new PatternProviderLogic(node, host);
		@SuppressWarnings("unchecked")
		var patterns = (List<IPatternDetails>) field("patterns").get(provider);
		patterns.add(pattern);
		var cacheType = Class.forName("appeng.helpers.patternprovider.PatternProviderTargetCache");
		var cache = mock(cacheType, call -> call.getMethod().getName().equals("find")
				? machine.target : RETURNS_DEFAULTS.answer(call));
		var caches = (Object[]) field("targetCaches").get(provider);
		caches[Direction.NORTH.ordinal()] = cache;
		for (int i = 0; i < CentrifugeDispatchScope.MIN_BUDGET; i++) {
			assertEquals(1, machine.target.insert(key, 1, Actionable.MODULATE));
		}
		KeyCounter inputs = new KeyCounter();
		inputs.add(key, 10_000_000L);
		// 仅隔离世界能力查找；pushPattern、sendList、busy 和排空逻辑均执行真实 AE2 实现。
		try (var craftingMachineLookup = mockStatic(ICraftingMachine.class)) {
			assertTrue(provider.pushPattern(pattern, new KeyCounter[]{inputs}));
		}
		assertTrue(provider.isBusy());
		@SuppressWarnings("unchecked")
		var pending = (List<GenericStack>) field("sendList").get(provider);
		assertEquals(10_000_000L, pending.stream().mapToLong(GenericStack::amount).sum());
		assertEquals(256, machine.totalInput());
		assertFalse(provider.pushPattern(pattern, new KeyCounter[]{inputs}));
		var drain = PatternProviderLogic.class.getDeclaredMethod("sendStacksOut");
		drain.setAccessible(true);
		assertFalse((boolean) drain.invoke(provider));
		ServerTickClock.tick();
		assertTrue((boolean) drain.invoke(provider));
		assertFalse(provider.isBusy());
		assertTrue(pending.isEmpty());
		assertEquals(10_000_256L, machine.totalInput());
		assertFalse((boolean) drain.invoke(provider));
		assertEquals(10_000_256L, machine.totalInput());
	}

	@Test
	void serverCleanupReleasesExhaustionEvenWhenClockNumberRepeats() throws Exception {
		var machine = new CentrifugePatternProviderMinecraftTest.Fixture();
		var key = AEItemKey.of(Items.RAW_IRON);
		for (int i = 0; i < CentrifugeDispatchScope.MIN_BUDGET; i++) {
			assertEquals(1, machine.target.insert(key, 1, Actionable.MODULATE));
		}
		assertEquals(0, machine.target.insert(key, 1, Actionable.MODULATE));
		Ae2IntegrationLoader.clearServerCaches();
		assertEquals(1, machine.target.insert(key, 1, Actionable.MODULATE));
	}

	private static Field field(String name) throws ReflectiveOperationException {
		var field = PatternProviderLogic.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
