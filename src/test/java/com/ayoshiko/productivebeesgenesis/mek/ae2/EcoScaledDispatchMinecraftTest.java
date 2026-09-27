package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.pattern.AEProcessingPattern;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.minecraft.nbt.CompoundTag;
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

/**
 * 用实际加载的 ECO 发配器验证批次爬升与数量守恒；有限调用数不代替真实服务器耗时测量。
 */
@Tag("minecraft")
class EcoScaledDispatchMinecraftTest {

	private static final String EXECUTION = "cn.dancingsnow.neoecoae.crafting.execution.";

	@BeforeAll
	static void loadConfigs() {
		boolean loaded = ModList.get().isLoaded("neoecoae") && ModList.get().isLoaded("extendedae_plus");
		if (Boolean.getBoolean("productivebeesgenesis.test.ecoCompat")) assertTrue(loaded);
		assumeTrue(loaded);
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
	void ecoScaledDispatchToCentrifugeStaysLowPushCount() throws Exception {
		Fixture fixture = new Fixture();
		long dispatched = 0;
		// ECO beta6 每刻上限为一百万份，后续批次还受自身探测记忆影响；验证有界完成及守恒。
		for (int tick = 0; tick < 20 && dispatched < 10_000_000; tick++) {
			if (tick > 0) com.ayoshiko.productivebeesgenesis.util.ServerTickClock.tick();
			long accepted = fixture.dispatch(3100 + tick, 10_000_000 - dispatched);
			assertTrue(accepted > 0 && accepted <= 1_000_000L);
			dispatched += accepted;
			assertEquals(dispatched, fixture.machine.totalInput());
			assertEquals(10_000_000L, dispatched
					+ fixture.inventory.extract(fixture.input, Long.MAX_VALUE, Actionable.SIMULATE));
		}
		assertEquals(10_000_000L, dispatched, "容量足够时应跨刻完整发配千万份订单");
		assertEquals(dispatched, fixture.machine.totalInput());
		assertEquals(dispatched, fixture.dispatched);
		assertEquals(0, fixture.inventory.extract(fixture.input, Long.MAX_VALUE, Actionable.SIMULATE));
		// 锁定批次爬升不会退化成逐份循环；耗时由独立运行探针记录。
		assertTrue(fixture.calls <= 256,
				"ECO 对本机的单次调度推送次数应保持对数级（自身有界），实际 " + fixture.calls);
	}

	/** ECO beta6 即便未开启 EAEP 智能翻倍也会批量发配；本机预算启用时仍保留其批次大小。 */
	@Test
	void ecoPrioritizesAdaptiveBatchEvenWithoutEapSmartDoubling() throws Exception {
		Fixture fixture = new Fixture(false); // EAEP 在场但该样板未开启智能翻倍
		long acceptedCrafts = fixture.dispatch(3200, 10_000_000);
		assertTrue(acceptedCrafts > 1L,
				"新版 ECO 即便未开 EAEP 智能翻倍也应自适应批量（>1 份），实际 " + acceptedCrafts);
		assertEquals(acceptedCrafts, fixture.dispatched,
				"本机应完整吃下 ECO 自适应批量下发的每一份（与派发来源无关）");
	}

	@Test
	void ecoRetainsThrottledInputsInActualProviderAndResumesWithoutLoss() throws Exception {
		Fixture fixture = new Fixture();
		for (int i = 0; i < CentrifugeDispatchScope.MIN_BUDGET; i++) {
			assertEquals(1, fixture.machine.target.insert(fixture.input, 1, Actionable.MODULATE));
		}
		long owned = fixture.dispatch(3300, 10_000_000L);
		assertTrue(owned > 0);
		assertTrue(fixture.actualProvider.logic.isBusy());
		assertEquals(owned, fixture.actualProvider.pending(fixture.input));
		assertEquals(CentrifugeDispatchScope.MIN_BUDGET, fixture.machine.totalInput());
		assertEquals(10_000_000L, fixture.inventory.extract(fixture.input, Long.MAX_VALUE, Actionable.SIMULATE)
				+ fixture.actualProvider.pending(fixture.input));
		assertEquals(0, fixture.dispatch(3300, 10_000_000L - owned));
		assertFalse(fixture.actualProvider.drain());
		assertEquals(owned, fixture.actualProvider.pending(fixture.input));

		com.ayoshiko.productivebeesgenesis.util.ServerTickClock.tick();
		assertTrue(fixture.actualProvider.drain());
		assertFalse(fixture.actualProvider.logic.isBusy());
		assertEquals(0, fixture.actualProvider.pending(fixture.input));
		assertEquals(owned + CentrifugeDispatchScope.MIN_BUDGET, fixture.machine.totalInput());
		assertFalse(fixture.actualProvider.drain());
		long resumed = fixture.dispatch(3301, 10_000_000L - owned);
		assertTrue(resumed > 0);
		assertEquals(owned + resumed + CentrifugeDispatchScope.MIN_BUDGET, fixture.machine.totalInput());
		assertEquals(10_000_000L, fixture.inventory.extract(fixture.input, Long.MAX_VALUE, Actionable.SIMULATE)
				+ fixture.machine.totalInput() - CentrifugeDispatchScope.MIN_BUDGET
				+ fixture.actualProvider.pending(fixture.input));
	}

	private static final class Fixture {
		final AEItemKey input = AEItemKey.of(Items.RAW_IRON);
		final AEItemKey output = AEItemKey.of(Items.IRON_INGOT);
		final AEProcessingPattern pattern;
		final ListCraftingInventory inventory = new ListCraftingInventory(key -> {});
		final Level level = mock(Level.class);
		final CentrifugePatternProviderMinecraftTest.Fixture machine =
				new CentrifugePatternProviderMinecraftTest.Fixture();
		final ActualPatternProviderFixture actualProvider = new ActualPatternProviderFixture(machine.target);
		final Object dispatcher;
		final Object job;
		final Object push;
		final IEnergyService energy;
		final ICraftingProvider provider;
		final Method dispatch;
		long tick;
		long dispatched;
		int calls;

		Fixture() throws Exception {
			this(true);
		}

		Fixture(boolean allowScaling) throws Exception {
			pattern = new AEProcessingPattern(AEItemKey.of(PatternDetailsHelper.encodeProcessingPattern(
					List.of(new GenericStack(input, 1)), List.of(new GenericStack(output, 1)))));
			Class<?> aware = Class.forName("com.extendedae_plus.api.smartDoubling.ISmartDoublingAwarePattern");
			aware.getMethod("eap$setAllowScaling", boolean.class).invoke(pattern, allowScaling);
			aware.getMethod("eap$setMultiplierLimit", int.class).invoke(pattern, 0);
			provider = actualProvider.logic;
			var plan = new CraftingPlan(new GenericStack(output, 10_000_000), 1, false, false,
					new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(pattern, 10_000_000L));
			var linkData = new CompoundTag();
			UUID id = UUID.randomUUID();
			linkData.putUUID("craftId", id);
			linkData.putBoolean("req", false);
			ICraftingCPU cpu = (ICraftingCPU) proxy(ICraftingCPU.class, (instance, method, args) -> null);
			Class<?> listener = Class.forName(EXECUTION + "ExecutingCraftingJob$CraftingDifferenceListener");
			job = construct(EXECUTION + "ExecutingCraftingJob", 4, plan,
					proxy(listener, (instance, method, args) -> null), new CraftingLink(linkData, cpu), null);
			Object context = construct("cn.dancingsnow.neoecoae.api.me.lifecycle.ECOCraftingJobContext", 5,
					cpu, id, plan.finalOutput(), 10_000_000L, 10_000_000L);
			Object accounting = construct(EXECUTION + "ECOCraftingDispatchAccounting", 4,
					(Consumer<Object>) key -> {}, (Runnable) () -> {},
					(Function<Object, Object>) ignored -> context, (Consumer<Object>) event -> {});
			Object transaction = construct(EXECUTION + "ECOCraftingEnergyTransaction", 2,
					(Runnable) () -> {}, (LongSupplier) () -> tick);
			dispatcher = construct(EXECUTION + "ECOProcessingPatternDispatcher", 3, null, transaction, accounting);
			energy = (IEnergyService) proxy(IEnergyService.class, (instance, method, args) -> {
				if (method.getName().equals("extractAEPower")) return args[0];
				if (method.getName().equals("injectPower")) return 0.0;
				throw new AssertionError(method.getName());
			});
			Class<?> pushType = Class.forName(EXECUTION + "ECOCraftingProviderDispatcher$ECOCraftingNormalPush");
			push = proxy(pushType, (instance, method, args) -> {
				calls++;
				IPatternDetails scaled = (IPatternDetails) invoke(args[0], "pattern");
				KeyCounter[] counters = (KeyCounter[]) invoke(args[0], "inputs");
				long offered = counters[0].get(input);
				boolean owned = actualProvider.push(scaled, counters);
				if (owned) dispatched += offered;
				return owned;
			});
			dispatch = dispatcher.getClass().getDeclaredMethod("tryScaledDispatch",
					Class.forName(EXECUTION + "ECOCraftingDispatchRequest"), ICraftingProvider.class,
					double.class, IEnergyService.class, Consumer.class, pushType);
			dispatch.setAccessible(true);
			inventory.insert(input, 10_000_000, Actionable.MODULATE);
		}

		long dispatch(long currentTick, long allowed) throws Exception {
			tick = currentTick;
			Method beginTick = dispatcher.getClass().getDeclaredMethod("beginTick", long.class);
			beginTick.setAccessible(true);
			beginTick.invoke(dispatcher, tick);
			var inputs = new KeyCounter();
			inputs.add(input, 1);
			var outputs = new KeyCounter();
			outputs.add(output, 1);
			Object request = construct(EXECUTION + "ECOCraftingDispatchRequest", 9, job, null, pattern,
					new KeyCounter[]{inputs}, outputs, new KeyCounter(), allowed, inventory, level);
			Object result = dispatch.invoke(dispatcher, request, provider, 1.0, energy,
					(Consumer<ICraftingProvider>) ignored -> {}, push);
			return result == null ? 0 : (long) invoke(result, "acceptedCrafts");
		}
	}

	private static Object construct(String name, int arity, Object... args) throws Exception {
		constructors:
		for (var constructor : Class.forName(name).getDeclaredConstructors()) {
			if (constructor.getParameterCount() != arity) continue;
			Class<?>[] parameters = constructor.getParameterTypes();
			for (int i = 0; i < arity; i++) {
				if (args[i] != null && !parameters[i].isPrimitive() && !parameters[i].isInstance(args[i])) {
					continue constructors;
				}
			}
			constructor.setAccessible(true);
			return constructor.newInstance(args);
		}
		throw new AssertionError("Missing constructor: " + name);
	}

	private static Object proxy(Class<?> type, java.lang.reflect.InvocationHandler handler) {
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
	}

	private static Object invoke(Object instance, String name) throws Exception {
		Method method = instance.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(instance);
	}
}
