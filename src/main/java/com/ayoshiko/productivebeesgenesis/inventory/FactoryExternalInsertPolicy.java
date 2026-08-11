package com.ayoshiko.productivebeesgenesis.inventory;

import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
	 * Fair external-input policy shared by all process slots in one centrifuge factory.
	 * Each item type gets one stable target slot per game tick. This prevents an AE external
	 * storage facade from filling every process slot with its first oversized request while
	 * still allowing a single type to spread across the factory over subsequent ticks.
	 * The policy never changes the slot's real capacity: external capability simulations must
	 * see the same limit as execution, otherwise pipes and storage buses cache a false 16-item cap.
	 */
public final class FactoryExternalInsertPolicy implements ExternalInsertPolicy {

	static final int MIN_WORKING_SET = 64;
	static final int BUFFER_TICKS = 4;

	private final LongSupplier gameTickSupplier;
	private final IntSupplier workingSetSupplier;
	private final List<BasicInventorySlot> slots = new ArrayList<>();
	private final Map<StackFingerprint, Admission> admissions = new HashMap<>();
	private long cachedTick = Long.MIN_VALUE;

	public FactoryExternalInsertPolicy(LongSupplier gameTickSupplier, IntSupplier workingSetSupplier) {
		this.gameTickSupplier = gameTickSupplier;
		this.workingSetSupplier = workingSetSupplier;
	}

	public void register(BasicInventorySlot slot) {
		slots.add(slot);
		((TieredInputSlot) slot).productivebeesgenesis$setExternalInsertPolicy(this);
	}

	@Override
	public int getInsertLimit(BasicInventorySlot slot, ItemStack stack, int normalLimit,
			mekanism.api.Action action) {
		if (normalLimit <= 0 || stack.isEmpty() || slots.isEmpty()) return 0;
		long gameTick = gameTickSupplier.getAsLong();
		if (gameTick != cachedTick) {
			cachedTick = gameTick;
			admissions.clear();
		}

		StackFingerprint fingerprint = StackFingerprint.of(stack);
		Admission admission = admissions.computeIfAbsent(fingerprint,
				ignored -> new Admission(selectTargetSlot(stack, fingerprint, gameTick)));
		int target = admission.targetSlot;
		if (target < 0 || target >= slots.size() || slots.get(target) != slot) return 0;

		int workingSet = Math.max(MIN_WORKING_SET, workingSetSupplier.getAsInt());
		int currentCount = slot.getStack().getCount();
		long effectiveLimit = effectiveSlotLimit(normalLimit, currentCount);
		return (int) Math.min(Integer.MAX_VALUE, effectiveLimit);
	}

	/** Keeps external simulation aligned with the slot's actual, possibly upgraded, capacity. */
	static long effectiveSlotLimit(int normalLimit, int currentCount) {
		return Math.max((long) Math.max(0, currentCount), Math.max(0, normalLimit));
	}

	@Override
	public void onInserted(BasicInventorySlot slot, ItemStack stack, int amount) {
		if (amount <= 0 || stack.isEmpty()) return;
		Admission admission = admissions.get(StackFingerprint.of(stack));
		if (admission != null && admission.targetSlot >= 0
				&& admission.targetSlot < slots.size() && slots.get(admission.targetSlot) == slot) {
			admission.inserted = saturatedAdd(admission.inserted, amount);
		}
	}

	private int selectTargetSlot(ItemStack incoming, StackFingerprint fingerprint, long gameTick) {
		int size = slots.size();
		int start = Math.floorMod((long) fingerprint.hashCode() + gameTick, size);
		int workingSet = Math.max(MIN_WORKING_SET, workingSetSupplier.getAsInt());

		// Prefer refilling an existing matching lane, then claim a rotating empty lane.
		for (int offset = 0; offset < size; offset++) {
			int index = (start + offset) % size;
			BasicInventorySlot candidate = slots.get(index);
			ItemStack current = candidate.getStack();
			if (!current.isEmpty()
					&& current.getCount() < Math.min(candidate.getLimit(incoming), workingSet)
					&& ItemStack.isSameItemSameComponents(current, incoming)
					&& candidate.isItemValidForInsertion(incoming, AutomationType.EXTERNAL)) {
				return index;
			}
		}
		for (int offset = 0; offset < size; offset++) {
			int index = (start + offset) % size;
			BasicInventorySlot candidate = slots.get(index);
			if (candidate.isEmpty()
					&& candidate.isItemValidForInsertion(incoming, AutomationType.EXTERNAL)) {
				return index;
			}
		}
		return -1;
	}

	/** Four real ticks of demand keeps accelerated lanes fed without exposing the full backlog capacity. */
	public static int recommendedWorkingSet(int operationsPerTick, int accelerationMultiplier,
			int productivityParallel) {
		long operations = Math.max(1, operationsPerTick);
		long acceleration = Math.max(1, accelerationMultiplier);
		long productivity = Math.max(1, productivityParallel);
		long demand = saturatedMultiply(operations, acceleration);
		demand = saturatedMultiply(demand, productivity);
		long buffered = saturatedMultiply(demand, BUFFER_TICKS);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(MIN_WORKING_SET, buffered));
	}

	/** One tick of demand lets later AE2LT connections receive the remainder in the same scheduler pass. */
	static int insertionQuantum(int workingSet) {
		return Math.max(16, (Math.max(1, workingSet) + BUFFER_TICKS - 1) / BUFFER_TICKS);
	}

	private static int saturatedAdd(int left, int right) {
		return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
	}

	private static long saturatedMultiply(long left, long right) {
		if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
		return left * right;
	}

	private record StackFingerprint(Item item, DataComponentMap components) {
		private static StackFingerprint of(ItemStack stack) {
			return new StackFingerprint(stack.getItem(), stack.getComponents());
		}
	}

	private static final class Admission {
		private final int targetSlot;
		private int inserted;

		private Admission(int targetSlot) {
			this.targetSlot = targetSlot;
		}
	}
}
