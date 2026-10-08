package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.me.storage.ExternalStorageFacade;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("minecraft")
@EnabledIfSystemProperty(named = "productivebeesgenesis.test.infiniteStorage", matches = "true")
class CreativeSourceCompatibilityMinecraftTest {

	@Test
	void actualCreateCratePullIsBulkAndTracksTemplateChanges() throws Exception {
		var source = new AtomicReference<>(new ItemStack(Items.RAW_IRON));
		var calls = new AtomicInteger();
		Supplier<ItemStack> template = () -> {
			calls.incrementAndGet();
			return source.get();
		};
		var type = Class.forName("com.simibubi.create.content.logistics.crate.BottomlessItemHandler");
		var handler = (IItemHandler) type.getConstructor(Supplier.class).newInstance(template);
		var storage = ExternalStorageFacade.of(handler);
		var key = AEItemKey.of(Items.RAW_IRON);
		assertEquals(64, handler.extractItem(0, 10_000_000, false).getCount());
		calls.set(0);
		assertEquals(10_000_000, Ae2InputPuller.extractFromNetwork(storage, key, 10_000_000, IActionSource.empty()));
		assertTrue(calls.get() <= 4, "Actual Create/AE2 path must not loop once per stack");
		assertEquals(1, source.get().getCount());
		assertTrue(handler.extractItem(1, 1000, false).isEmpty());
		assertEquals(64, handler.extractItem(0, 1000, true).getCount());
		source.set(new ItemStack(Items.DIAMOND));
		assertEquals(0, Ae2InputPuller.extractFromNetwork(storage, key, 1000, IActionSource.empty()));
		assertEquals(1000, Ae2InputPuller.extractFromNetwork(storage, AEItemKey.of(Items.DIAMOND), 1000, IActionSource.empty()));
		source.set(ItemStack.EMPTY);
		assertEquals(0, Ae2InputPuller.extractFromNetwork(storage, key, 1000, IActionSource.empty()));
	}

	@Test
	void actualInfinitySuppliersReuseKeysAndRefreshAfterReload() throws Exception {
		var type = Class.forName("net.yxiao233.meinfinitycell.common.compact.kubejs.helper.AEKeyHelper");
		var item = supplier(type, "item", "minecraft:raw_iron");
		var fluid = supplier(type, "fluid", "minecraft:water");
		AEKey first = item.get();
		for (int i = 0; i < 1000; i++) assertSame(first, item.get());
		assertSame(fluid.get(), fluid.get());
		ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
		assertEquals(first, item.get());
		assertNotSame(first, item.get());
		var components = DataComponentMap.builder()
				.set(DataComponents.CUSTOM_NAME, Component.literal("configured")).build();
		@SuppressWarnings("unchecked")
		var named = (Supplier<AEKey>) type.getMethod("item", ResourceLocation.class, DataComponentMap.class)
				.invoke(null, ResourceLocation.parse("minecraft:raw_iron"), components);
		var stack = new ItemStack(Items.RAW_IRON);
		stack.applyComponents(components);
		assertEquals(AEItemKey.of(stack), named.get());
		assertSame(named.get(), named.get());
	}

	@Test
	void extendedAeInfinityCellAlreadyAcceptsWholeRequests() throws Exception {
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
				ResourceLocation.parse("extendedae:infinity_cobblestone_cell"));
		assertNotEquals(Items.AIR, item);
		var type = Class.forName("com.glodblock.github.extendedae.common.inventory.InfinityCellInventory");
		var cell = (appeng.api.storage.MEStorage) type.getConstructor(ItemStack.class).newInstance(new ItemStack(item));
		var cobblestone = AEItemKey.of(Items.COBBLESTONE);
		for (var mode : Actionable.values()) {
			assertEquals(10_000_000, cell.extract(cobblestone, 10_000_000, mode, IActionSource.empty()));
			assertEquals(0, cell.extract(AEItemKey.of(Items.DIAMOND), 10_000_000, mode, IActionSource.empty()));
		}
		assertEquals(10_000_000, Ae2InputPuller.extractFromNetwork(cell, cobblestone, 10_000_000, IActionSource.empty()));
	}

	@Test
	void arbitraryDynamicKeyListSuppliersStayDynamic() throws Exception {
		var type = Class.forName("net.yxiao233.meinfinitycell.common.utils.KeyList");
		var list = type.getConstructor().newInstance();
		var current = new AtomicReference<AEKey>(AEItemKey.of(Items.RAW_IRON));
		type.getMethod("add", Supplier.class).invoke(list, (Supplier<AEKey>) current::get);
		var contains = type.getMethod("contains", AEKey.class);
		assertEquals(true, contains.invoke(list, AEItemKey.of(Items.RAW_IRON)));
		current.set(AEItemKey.of(Items.DIAMOND));
		assertEquals(false, contains.invoke(list, AEItemKey.of(Items.RAW_IRON)));
		assertEquals(true, contains.invoke(list, AEItemKey.of(Items.DIAMOND)));
	}

	@SuppressWarnings("unchecked")
	private static Supplier<AEKey> supplier(Class<?> type, String method, String id) throws Exception {
		return (Supplier<AEKey>) type.getMethod(method, ResourceLocation.class)
				.invoke(null, ResourceLocation.parse(id));
	}
}
