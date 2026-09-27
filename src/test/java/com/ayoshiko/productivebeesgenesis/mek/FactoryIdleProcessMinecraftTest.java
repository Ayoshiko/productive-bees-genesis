package com.ayoshiko.productivebeesgenesis.mek;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.atomic.AtomicInteger;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class FactoryIdleProcessMinecraftTest {

	@Test
	void persistedCommittedItemsSurviveIdleRetriesAndDrainAfterOutputUnblocks() {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var output = BasicInventorySlot.at(null, 0, 0);
		output.setStack(new ItemStack(Items.EMERALD, 64));
		PbRecipeContext context = (PbRecipeContext) Proxy.newProxyInstance(
				PbRecipeContext.class.getClassLoader(), new Class<?>[]{PbRecipeContext.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "processes" -> 1;
					case "primaryOutputSlot" -> output;
					case "secondaryOutputSlot", "tertiaryOutputSlot" -> null;
					case "productivebeesgenesis$beginOutputBatch", "productivebeesgenesis$endOutputBatch",
							"productivebeesgenesis$onProcessDeactivated", "productivebeesgenesis$updateOutputSlotFlags" -> null;
					default -> {
						if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
						throw new AssertionError("Unexpected call: " + method.getName());
					}
				});
		PbRecipeCompleter source = new PbRecipeCompleter(context);
		CompoundTag pending = new CompoundTag();
		ListTag items = new ListTag();
		CompoundTag item = (CompoundTag) new ItemStack(Items.DIAMOND).save(registries);
		item.putInt("productivebeesgenesis_count", 12);
		items.add(item);
		pending.put("items", items);
		source.loadCommittedPending(pending, registries);
		assertTrue(source.hasCommittedPendingOutputs());
		var processor = new PbRecipeProcessor(context, "test");
		CompoundTag saved = new CompoundTag();
		ListTag processes = new ListTag();
		pending.putInt("process", 0);
		processes.add(pending);
		saved.put("productivebeesgenesis_pb_committed_pending", processes);
		processor.loadAdditional(saved, registries);
		for (int retry = 0; retry < 10; retry++) processor.updateFactoryInputState(0, true);
		CompoundTag blocked = new CompoundTag();
		processor.saveAdditional(blocked, registries);
		assertTrue(blocked.contains("productivebeesgenesis_pb_committed_pending"));
		output.setStack(ItemStack.EMPTY);
		processor.updateFactoryInputState(0, true);
		assertEquals(12, output.getCount());
		assertTrue(output.getStack().is(Items.DIAMOND));
		processor.updateFactoryInputState(0, true);
		assertEquals(12, output.getCount());
	}

	@Test
	void emptyLanesResetOnceButContinueDrainingCommittedOutputs() {
		AtomicInteger deactivated = new AtomicInteger();
		PbRecipeContext context = (PbRecipeContext) Proxy.newProxyInstance(
				PbRecipeContext.class.getClassLoader(), new Class<?>[]{PbRecipeContext.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "processes" -> 2;
					case "productivebeesgenesis$onProcessDeactivated" -> { deactivated.incrementAndGet(); yield null; }
					default -> throw new AssertionError("Unexpected call: " + method.getName());
				});
		CountingProcessor processor = new CountingProcessor(context);
		for (int i = 0; i < 1024; i++) processor.updateFactoryInputState(0, true);
		assertEquals(1, deactivated.get());
		assertEquals(1, processor.resets);
		assertEquals(1024, processor.drains);
		processor.updateFactoryInputState(1, true);
		assertEquals(2, deactivated.get());
		processor.updateFactoryInputState(0, false);
		processor.updateFactoryInputState(0, true);
		assertEquals(3, deactivated.get());
		assertEquals(3, processor.resets);
		processor.loadAdditional(new CompoundTag(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		processor.updateFactoryInputState(0, true);
		assertEquals(4, deactivated.get());
	}

	private static final class CountingProcessor extends PbRecipeProcessor {
		int drains;
		int resets;

		CountingProcessor(PbRecipeContext context) { super(context, "test"); }

		@Override
		public boolean drainCommittedPendingOutputs(int process) {
			drains++;
			return super.drainCommittedPendingOutputs(process);
		}

		@Override
		public void resetPbState(int process) {
			resets++;
			super.resetPbState(process);
		}
	}
}
