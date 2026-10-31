package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.me.storage.ExternalStorageFacade;
import com.ayoshiko.productivebeesgenesis.inventory.BulkItemPullScope;
import java.util.concurrent.atomic.AtomicInteger;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.BinInventorySlot;
import mekanism.common.tier.BinTier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.jupiter.api.*;

@Tag("minecraft")
class MekBinBulkPullMinecraftTest {

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@Test
	void creativeBinServesMillionsInOneBatchWithoutChangingItsTemplate() {
		var bin = BinInventorySlot.create(null, BinTier.CREATIVE);
		bin.setStackUnchecked(new ItemStack(Items.RAW_IRON, Integer.MAX_VALUE));
		var handler = new CountingHandler(bin);
		var storage = ExternalStorageFacade.of(handler);
		var key = AEItemKey.of(Items.RAW_IRON);

		assertEquals(64, bin.extractItem(1_000_000, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
		assertEquals(1_000_000, storage.extract(key, 1_000_000, Actionable.MODULATE, IActionSource.empty()));
		assertEquals(15_626, handler.calls); // 对照：普通 AE2 抽取仍按 64 件逐组循环。
		for (int i = 0; i < 3; i++) {
			handler.calls = 0;
			assertEquals(10_000_000, Ae2InputPuller.extractFromNetwork(storage, key, 10_000_000, IActionSource.empty()));
			assertEquals(2, handler.calls); // 一次批量取出，AE2 再以零请求结束循环。
			assertEquals(Integer.MAX_VALUE, bin.getCount());
		}
		assertEquals(Integer.MAX_VALUE, Ae2InputPuller.extractFromNetwork(
				storage, key, Integer.MAX_VALUE, IActionSource.empty()));
		assertEquals(Integer.MAX_VALUE, bin.getCount());
		assertEquals(0, Ae2InputPuller.extractFromNetwork(storage, AEItemKey.of(Items.DIAMOND),
				1000, IActionSource.empty()));
		assertEquals(64, bin.extractItem(1000, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
	}

	@Test
	void finiteBinRemovesExactlyTheRequestedAmountAndNotifiesOnce() {
		var changes = new AtomicInteger();
		var bin = BinInventorySlot.create(changes::incrementAndGet, BinTier.ULTIMATE);
		bin.setStackUnchecked(new ItemStack(Items.RAW_IRON, 100_000));
		changes.set(0);
		var handler = new CountingHandler(bin);
		var storage = ExternalStorageFacade.of(handler);
		var key = AEItemKey.of(Items.RAW_IRON);
		assertEquals(80_000, Ae2InputPuller.extractFromNetwork(storage, key, 80_000, IActionSource.empty()));
		assertEquals(20_000, bin.getCount());
		assertEquals(1, changes.get());
		assertEquals(2, handler.calls);
		assertEquals(20_000, Ae2InputPuller.extractFromNetwork(storage, key, 80_000, IActionSource.empty()));
		assertTrue(bin.isEmpty());
		assertEquals(2, changes.get());
	}

	@Test
	void simulationAndOtherSlotsKeepTheirOwnershipAndExtractionRules() {
		var bin = BinInventorySlot.create(null, BinTier.ULTIMATE);
		bin.setStackUnchecked(new ItemStack(Items.RAW_IRON, 1000));
		var storage = ExternalStorageFacade.of(new CountingHandler(bin));
		var template = new ItemStack(Items.RAW_IRON);
		assertEquals(1000, BulkItemPullScope.extract(template,
				() -> storage.extract(AEItemKey.of(template), 2000, Actionable.SIMULATE, IActionSource.empty())));
		assertEquals(1000, bin.getCount());
		var ordinary = BasicInventorySlot.at(null, 0, 0);
		ordinary.setStackUnchecked(new ItemStack(Items.RAW_IRON, 1000));
		BulkItemPullScope.extract(template, () -> {
			assertEquals(64, ordinary.extractItem(1000, Action.SIMULATE, AutomationType.EXTERNAL).getCount());
			assertEquals(64, bin.extractItem(1000, Action.SIMULATE, AutomationType.MANUAL).getCount());
			return 0;
		});
		assertEquals(1000, ordinary.getCount());
	}

	@Test
	void exactComponentsNestedScopesAndExceptionsDoNotLeakBulkPermission() {
		var bin = BinInventorySlot.create(null, BinTier.CREATIVE);
		var named = new ItemStack(Items.RAW_IRON, 1000);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("configured"));
		bin.setStackUnchecked(named);
		BulkItemPullScope.extract(new ItemStack(Items.RAW_IRON), () -> {
			assertEquals(64, bin.extractItem(1000, Action.SIMULATE, AutomationType.EXTERNAL).getCount());
			return 0;
		});
		assertThrows(IllegalStateException.class, () -> BulkItemPullScope.extract(named, () -> {
			assertEquals(1000, bin.extractItem(1000, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
			BulkItemPullScope.extract(new ItemStack(Items.DIAMOND), () -> {
				assertEquals(64, bin.extractItem(1000, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
				return 0;
			});
			assertEquals(1000, bin.extractItem(1000, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
			throw new IllegalStateException("fixture");
		}));
		assertEquals(64, bin.extractItem(1000, Action.EXECUTE, AutomationType.EXTERNAL).getCount());
		assertEquals(1000, bin.getCount());
		assertTrue(ItemStack.isSameItemSameComponents(named, bin.getStack()));
	}

	private static final class CountingHandler implements IItemHandler {
		private final BasicInventorySlot slot;
		private int calls;

		private CountingHandler(BasicInventorySlot slot) {
			this.slot = slot;
		}

		@Override public int getSlots() { return 1; }
		@Override public ItemStack getStackInSlot(int index) { return slot.getStack(); }
		@Override public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
			return slot.insertItem(stack, simulate ? Action.SIMULATE : Action.EXECUTE, AutomationType.EXTERNAL);
		}
		@Override public ItemStack extractItem(int index, int amount, boolean simulate) {
			calls++;
			return slot.extractItem(amount, simulate ? Action.SIMULATE : Action.EXECUTE, AutomationType.EXTERNAL);
		}
		@Override public int getSlotLimit(int index) { return slot.getLimit(slot.getStack()); }
		@Override public boolean isItemValid(int index, ItemStack stack) { return slot.isItemValid(stack); }
	}
}
