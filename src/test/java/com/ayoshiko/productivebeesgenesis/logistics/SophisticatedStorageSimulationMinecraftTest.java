package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ayoshiko.productivebeesgenesis.compat.sophisticated.SophisticatedStorageMixinContract;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("minecraft")
@EnabledIfSystemProperty(named = "productivebeesgenesis.test.sophisticatedStorage", matches = "true")
class SophisticatedStorageSimulationMinecraftTest {

	private static final String STORAGE_ENTITY = "net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity";
	private static final String CACHED_INSERT = "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler";

	@BeforeAll
	static void actualDependenciesMeetTheSimulationContract() {
		assertTrue(SophisticatedStorageMixinContract.supported());
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadDefaultServerConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	@Test
	void actualBarrelSimulationFindsLateCapacityAndPreservesComponentsAndAssets() throws Exception {
		var barrel = barrel();
		var iron = new ItemStack(Items.IRON_INGOT, 8192);
		int[] limits = fill(barrel.inventory(), iron);
		assertTrue(limits.length > 1);
		assertInstanceOf(WholeInventoryItemSimulation.class, barrel.internal());
		assertInstanceOf(WholeInventoryItemSimulation.class, barrel.external());

		var full = contents(barrel.inventory());
		assertEquals(0, ItemPushHelper.simulateInsert(barrel.external(), iron));
		assertEquals(0, ItemPushHelper.simulateInsert(barrel.external(), iron, () -> true));
		assertContents(full, barrel.inventory());

		int last = limits.length - 1;
		assertTrue(limits[last] > 7);
		barrel.inventory().setStackInSlot(last, iron.copyWithCount(limits[last] - 7));
		var partial = contents(barrel.inventory());
		assertEquals(7, ItemPushHelper.simulateInsert(barrel.external(), iron));
		assertEquals(7, ItemPushHelper.simulateInsert(barrel.external(), iron, () -> true));
		assertEquals(7, ItemPushHelper.simulateInsert(barrel.internal(), iron, () -> true));
		assertContents(partial, barrel.inventory());

		var named = iron.copy();
		named.set(DataComponents.CUSTOM_NAME, Component.literal("component-distinct iron"));
		var originalNamed = named.copy();
		assertEquals(0, ItemPushHelper.simulateInsert(barrel.external(), named));
		assertEquals(0, ItemPushHelper.simulateInsert(barrel.external(), named, () -> true));
		assertTrue(ItemStack.matches(originalNamed, named));
		assertContents(partial, barrel.inventory());
		assertEquals(8192, iron.getCount());

		var custody = new OutputCustody(() -> {});
		var remaining = ItemPushHelper.insert(barrel.external(), iron.copyWithCount(7), custody,
				"actual-sophisticated-barrel", () -> true);
		assertTrue(remaining.isEmpty());
		assertFalse(custody.blocked());
		assertContents(full, barrel.inventory());
		assertEquals(8192, iron.getCount());
	}

	@Test
	void retainedBarrelCapabilityRefreshesItsFilterWhenLockedAndUnlocked() throws Exception {
		var barrel = barrel();
		barrel.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
		var diamond = new ItemStack(Items.DIAMOND, 16);
		var iron = new ItemStack(Items.IRON_INGOT, 16);
		assertTrue(stackLimit(barrel.inventory(), 0, iron) >= 17);
		assertTrue(barrel.inventory().getSlots() > 1);
		assertTrue(stackLimit(barrel.inventory(), 1, diamond) >= diamond.getCount());
		var original = contents(barrel.inventory());
		var unlockedInternal = barrel.internal();

		assertEquals(16, ItemPushHelper.simulateInsert(barrel.external(), diamond, () -> true));
		barrel.toggleLock();
		assertTrue(barrel.locked());
		assertSame(barrel.external(), barrel.currentExternal());
		var lockedInternal = barrel.internal();
		assertNotSame(unlockedInternal, lockedInternal);
		assertInstanceOf(WholeInventoryItemSimulation.class, lockedInternal);
		assertEquals(0, ItemPushHelper.simulateInsert(barrel.external(), diamond));
		assertEquals(0, ItemPushHelper.simulateInsert(barrel.external(), diamond, () -> true));
		assertEquals(16, ItemPushHelper.simulateInsert(barrel.external(), iron));
		assertEquals(16, ItemPushHelper.simulateInsert(barrel.external(), iron, () -> true));
		assertContents(original, barrel.inventory());

		barrel.toggleLock();
		assertFalse(barrel.locked());
		assertSame(barrel.external(), barrel.currentExternal());
		assertNotSame(lockedInternal, barrel.internal());
		assertEquals(16, ItemPushHelper.simulateInsert(barrel.external(), diamond));
		assertEquals(16, ItemPushHelper.simulateInsert(barrel.external(), diamond, () -> true));
		assertContents(original, barrel.inventory());
		assertEquals(16, diamond.getCount());
		assertEquals(16, iron.getCount());
	}

	@Test
	void actualCachedWrapperReadsCurrentDelegateAndFallsBackWhenItsBridgeIsAbsent() throws Exception {
		var empty = barrel();
		var full = barrel();
		var request = new ItemStack(Items.IRON_INGOT, 16);
		fill(full.inventory(), request);
		var originalEmpty = contents(empty.inventory());
		var originalFull = contents(full.inventory());
		var delegate = new AtomicReference<IItemHandler>(empty.internal());
		var type = Class.forName(CACHED_INSERT);
		Supplier<IItemHandler> supplier = delegate::get;
		var wrapper = (IItemHandler) type.getConstructor(Supplier.class, LongSupplier.class)
				.newInstance(supplier, (LongSupplier) () -> 0L);
		var bridge = assertInstanceOf(WholeInventoryItemSimulation.class, wrapper);

		assertEquals(16, ItemPushHelper.simulateInsert(wrapper, request, () -> true));
		delegate.set(full.internal());
		assertEquals(0, ItemPushHelper.simulateInsert(wrapper, request, () -> true));

		var ordinary = new ItemStackHandler(1);
		int ordinaryLimit = Math.min(ordinary.getSlotLimit(0), request.getMaxStackSize());
		assertTrue(ordinaryLimit > 3);
		ordinary.setStackInSlot(0, request.copyWithCount(ordinaryLimit - 3));
		var ordinaryBefore = contents(ordinary);
		delegate.set(unbridgedHandler(ordinary));
		assertEquals(WholeInventoryItemSimulation.UNAVAILABLE,
				bridge.productivebeesgenesis$simulateWholeItemInsert(request));
		assertEquals(3, ItemPushHelper.simulateInsert(wrapper, request));
		assertEquals(3, ItemPushHelper.simulateInsert(wrapper, request, () -> true));
		assertContents(ordinaryBefore, ordinary);

		delegate.set(empty.internal());
		assertEquals(16, ItemPushHelper.simulateInsert(wrapper, request, () -> true));
		assertContents(originalEmpty, empty.inventory());
		assertContents(originalFull, full.inventory());
		assertEquals(16, request.getCount());
	}

	private static Barrel barrel() throws Exception {
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("sophisticatedstorage:barrel"));
		assertNotEquals(Blocks.AIR, block);
		BlockState state = block.defaultBlockState();
		var type = Class.forName("net.p3pp3rf1y.sophisticatedstorage.block.BarrelBlockEntity");
		var tile = (BlockEntity) type.getConstructor(BlockPos.class, BlockState.class)
				.newInstance(BlockPos.ZERO, state);
		var level = mock(Level.class);
		when(level.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		when(level.getBlockState(BlockPos.ZERO)).thenReturn(state);
		when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(tile);
		tile.setLevel(level);
		var storageType = Class.forName(STORAGE_ENTITY);
		Object storage = storageType.getMethod("getStorageWrapper").invoke(tile);
		var wrapperType = Class.forName("net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper");
		var inventory = (IItemHandlerModifiable) wrapperType.getMethod("getInventoryHandler").invoke(storage);
		var external = (IItemHandler) storageType.getMethod("getExternalItemHandler", Direction.class)
				.invoke(tile, Direction.NORTH);
		assertEquals(CACHED_INSERT, external.getClass().getName());
		return new Barrel(tile, inventory, external);
	}

	private static int[] fill(IItemHandlerModifiable inventory, ItemStack template) throws Exception {
		int[] limits = new int[inventory.getSlots()];
		for (int slot = 0; slot < limits.length; slot++) {
			limits[slot] = stackLimit(inventory, slot, template);
			assertTrue(limits[slot] > 0);
			inventory.setStackInSlot(slot, template.copyWithCount(limits[slot]));
			assertEquals(limits[slot], inventory.getStackInSlot(slot).getCount());
		}
		return limits;
	}

	private static int stackLimit(IItemHandlerModifiable inventory, int slot, ItemStack template) throws Exception {
		var type = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler");
		return (int) type.getMethod("getStackLimit", int.class, ItemStack.class).invoke(inventory, slot, template);
	}

	private static List<ItemStack> contents(IItemHandler inventory) {
		var result = new ArrayList<ItemStack>(inventory.getSlots());
		for (int slot = 0; slot < inventory.getSlots(); slot++) {
			result.add(inventory.getStackInSlot(slot).copy());
		}
		return result;
	}

	private static void assertContents(List<ItemStack> expected, IItemHandler inventory) {
		assertEquals(expected.size(), inventory.getSlots());
		for (int slot = 0; slot < expected.size(); slot++) {
			assertTrue(ItemStack.matches(expected.get(slot), inventory.getStackInSlot(slot)),
					"Simulation changed slot " + slot);
		}
	}

	private static IItemHandler unbridgedHandler(ItemStackHandler inventory) throws Exception {
		var blockOverride = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.IInsertBlockOverride");
		return (IItemHandler) Proxy.newProxyInstance(blockOverride.getClassLoader(),
				new Class<?>[]{IItemHandlerModifiable.class, blockOverride}, (proxy, method, arguments) -> {
					if (method.getName().equals("isInsertBlocked")) return false;
					try {
						return method.invoke(inventory, arguments);
					} catch (InvocationTargetException failure) {
						throw failure.getCause();
					}
				});
	}

	private record Barrel(BlockEntity tile, IItemHandlerModifiable inventory, IItemHandler external) {

		private IItemHandler internal() throws Exception {
			return (IItemHandler) externalMethod().invoke(tile, new Object[]{null});
		}

		private IItemHandler currentExternal() throws Exception {
			return (IItemHandler) externalMethod().invoke(tile, Direction.NORTH);
		}

		private void toggleLock() throws Exception {
			Class.forName(STORAGE_ENTITY).getMethod("toggleLock").invoke(tile);
		}

		private boolean locked() throws Exception {
			return (boolean) Class.forName(STORAGE_ENTITY).getMethod("isLocked").invoke(tile);
		}

		private Method externalMethod() throws Exception {
			return Class.forName(STORAGE_ENTITY).getMethod("getExternalItemHandler", Direction.class);
		}
	}
}
