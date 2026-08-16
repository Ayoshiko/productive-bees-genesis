package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.ISlotInfo;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Native AE2 storage view used by adjacent interfaces for centrifuge item and fluid IO.
 * It avoids rebuilding generic capability facades and shares snapshots across all queried sides.
 */
final class CentrifugeExternalAeStorage implements MEStorage {

	private static final Component DESCRIPTION = Component.translatable(
			"block.productivebeesgenesis.mek_centrifuge");

	private final SharedState shared;
	private final Direction side;

	private CentrifugeExternalAeStorage(SharedState shared, Direction side) {
		this.shared = shared;
		this.side = side;
	}

	static MEStorage getOrCreate(IAe2OutputHostBase host, IMekCentrifugeTile centrifuge,
			ISideConfiguration sideConfiguration, Direction side) {
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		Object cached = holder.getCentrifugeExternalStorageCache();
		Cache cache = cached instanceof Cache existing ? existing : null;
		if (cache == null) {
			synchronized (holder) {
				cached = holder.getCentrifugeExternalStorageCache();
				cache = cached instanceof Cache existing ? existing : null;
				if (cache == null) {
					cache = new Cache(new SharedState(host, centrifuge, sideConfiguration));
					holder.setCentrifugeExternalStorageCache(cache);
				}
			}
		}
		int index = side.ordinal();
		CentrifugeExternalAeStorage storage = cache.bySide[index];
		if (storage == null) {
			synchronized (cache) {
				storage = cache.bySide[index];
				if (storage == null) {
					storage = new CentrifugeExternalAeStorage(cache.shared, side);
					cache.bySide[index] = storage;
				}
			}
		}
		return storage;
	}

	@Override
	public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
		MEStorage.checkPreconditions(what, amount, mode, source);
		if (!(what instanceof AEItemKey itemKey)
				|| !allows(TransmissionType.ITEM, false)) {
			return 0L;
		}
		ItemStack probe = itemKey.toStack();
		if (probe.isEmpty() || !shared.centrifuge.productivebeesgenesis$isValidInput(probe)) {
			return 0L;
		}

		int slotCount = shared.centrifuge.productivebeesgenesis$getInputSlotCount();
		if (slotCount <= 0) return 0L;
		int start = Math.floorMod(shared.inputCursor, slotCount);
		Action action = mode.isSimulate() ? Action.SIMULATE : Action.EXECUTE;
		long inserted = 0L;
		int lastUsed = -1;
		for (int offset = 0; offset < slotCount && inserted < amount; offset++) {
			int index = (start + offset) % slotCount;
			IInventorySlot slot = shared.centrifuge.productivebeesgenesis$getInputSlot(index);
			if (slot == null) continue;
			int offeredCount = SaturatingMath.saturatingToInt(amount - inserted);
			if (offeredCount <= 0) break;
			ItemStack offered = itemKey.toStack(offeredCount);
			ItemStack remainder = slot.insertItem(offered, action, AutomationType.EXTERNAL);
			int remainderCount = remainder.isEmpty() ? 0 : Math.min(offeredCount, remainder.getCount());
			int insertedNow = offeredCount - remainderCount;
			if (insertedNow > 0) {
				inserted = SaturatingMath.saturatingAdd(inserted, insertedNow);
				lastUsed = index;
			}
		}
		if (!mode.isSimulate() && inserted > 0 && lastUsed >= 0) {
			shared.inputCursor = (lastUsed + 1) % slotCount;
		}
		return Math.min(inserted, amount);
	}

	@Override
	public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
		MEStorage.checkPreconditions(what, amount, mode, source);
		if (what instanceof AEItemKey itemKey && allows(TransmissionType.ITEM, true)) {
			return extractItem(itemKey, amount, mode);
		}
		if (what instanceof AEFluidKey fluidKey && allows(TransmissionType.FLUID, true)) {
			return extractFluid(fluidKey, amount, mode);
		}
		return 0L;
	}

	@Override
	public void getAvailableStacks(KeyCounter out) {
		if (allows(TransmissionType.ITEM, true)) {
			refreshItemSnapshot();
			out.addAll(shared.itemSnapshot);
		}
		if (allows(TransmissionType.FLUID, true)) {
			int tankCount = Math.max(0, shared.host.fluidOutputTankCount());
			for (int i = 0; i < tankCount; i++) {
				IExtendedFluidTank tank = shared.host.fluidOutputTank(i);
				if (tank == null || tank.isEmpty()) continue;
				FluidStack stack = tank.getFluid();
				if (stack.isEmpty() || stack.getAmount() <= 0) continue;
				AEFluidKey key = getCachedFluidKey(i, stack);
				if (key != null) out.add(key, stack.getAmount());
			}
		}
	}

	@Override
	public Component getDescription() {
		return DESCRIPTION;
	}

	private long extractItem(AEItemKey key, long amount, Actionable mode) {
		int slotCount = Math.max(0, shared.host.processes()) * 3;
		if (slotCount <= 0) return 0L;
		int start = Math.floorMod(shared.itemExtractCursor, slotCount);
		long extracted = 0L;
		int lastUsed = -1;
		for (int offset = 0; offset < slotCount && extracted < amount; offset++) {
			int index = (start + offset) % slotCount;
			IInventorySlot slot = outputSlot(index);
			if (slot == null || slot.isEmpty()) continue;
			ItemStack stack = slot.getStack();
			if (stack.isEmpty() || !key.matches(stack)) continue;
			int requested = Math.min(stack.getCount(), SaturatingMath.saturatingToInt(amount - extracted));
			if (requested <= 0) break;
			int extractedNow = mode.isSimulate()
					? requested
					: Math.max(0, Math.min(requested, slot.shrinkStack(requested, Action.EXECUTE)));
			if (extractedNow > 0) {
				extracted = SaturatingMath.saturatingAdd(extracted, extractedNow);
				lastUsed = index;
			}
		}
		if (!mode.isSimulate() && extracted > 0) {
			shared.itemSnapshotVersion = Long.MIN_VALUE;
			if (lastUsed >= 0) shared.itemExtractCursor = (lastUsed + 1) % slotCount;
			shared.host.productivebeesgenesis$onAe2PushComplete(
					SaturatingMath.saturatingToInt(extracted));
		}
		return Math.min(extracted, amount);
	}

	private long extractFluid(AEFluidKey key, long amount, Actionable mode) {
		int tankCount = Math.max(0, shared.host.fluidOutputTankCount());
		if (tankCount <= 0) return 0L;
		int start = Math.floorMod(shared.fluidExtractCursor, tankCount);
		long extracted = 0L;
		int lastUsed = -1;
		for (int offset = 0; offset < tankCount && extracted < amount; offset++) {
			int index = (start + offset) % tankCount;
			IExtendedFluidTank tank = shared.host.fluidOutputTank(index);
			if (tank == null || tank.isEmpty()) continue;
			FluidStack stack = tank.getFluid();
			if (stack.isEmpty() || !key.matches(stack)) continue;
			int requested = Math.min(stack.getAmount(), SaturatingMath.saturatingToInt(amount - extracted));
			if (requested <= 0) break;
			int extractedNow;
			if (mode.isSimulate()) {
				extractedNow = requested;
			} else {
				int before = tank.getFluidAmount();
				tank.shrinkStack(requested, Action.EXECUTE);
				extractedNow = Math.max(0, Math.min(requested, before - tank.getFluidAmount()));
			}
			if (extractedNow > 0) {
				extracted = SaturatingMath.saturatingAdd(extracted, extractedNow);
				lastUsed = index;
			}
		}
		if (!mode.isSimulate() && extracted > 0) {
			if (lastUsed >= 0) shared.fluidExtractCursor = (lastUsed + 1) % tankCount;
			shared.host.productivebeesgenesis$onAe2FluidPushComplete();
		}
		return Math.min(extracted, amount);
	}

	private void refreshItemSnapshot() {
		long version = shared.centrifuge.productivebeesgenesis$outputContentsVersion();
		if (version == shared.itemSnapshotVersion) return;
		shared.itemSnapshot.clear();
		int slotCount = Math.max(0, shared.host.processes()) * 3;
		for (int i = 0; i < slotCount; i++) {
			IInventorySlot slot = outputSlot(i);
			if (slot == null || slot.isEmpty()) continue;
			ItemStack stack = slot.getStack();
			if (stack.isEmpty() || stack.getCount() <= 0) continue;
			AEItemKey key = AEItemKey.of(stack);
			if (key != null) shared.itemSnapshot.add(key, stack.getCount());
		}
		shared.itemSnapshotVersion = version;
	}

	private IInventorySlot outputSlot(int flatIndex) {
		int process = flatIndex / 3;
		return switch (flatIndex % 3) {
			case 0 -> shared.host.primaryOutputSlot(process);
			case 1 -> shared.host.secondaryOutputSlot(process);
			default -> shared.host.tertiaryOutputSlot(process);
		};
	}

	private AEFluidKey getCachedFluidKey(int index, FluidStack stack) {
		Ae2OutputStateHolder holder = shared.host.productivebeesgenesis$getAe2StateHolder();
		Object fluid = stack.getFluid();
		boolean componentsEmpty = stack.isComponentsPatchEmpty();
		int componentsHash = componentsEmpty ? 0 : stack.getComponents().hashCode();
		if (holder.getCachedFluidPushKeyFluid(index) == fluid
				&& holder.isCachedFluidPushKeyComponentsEmpty(index) == componentsEmpty
				&& (componentsEmpty
						|| holder.getCachedFluidPushKeyComponentsHash(index) == componentsHash)
				&& holder.getCachedFluidPushKey(index) instanceof AEFluidKey existing) {
			return existing;
		}
		AEFluidKey created = AEFluidKey.of(stack);
		holder.setCachedFluidPushKey(index, created, fluid, componentsEmpty, componentsHash);
		return created;
	}

	private boolean allows(TransmissionType transmission, boolean output) {
		TileComponentConfig component = shared.sideConfiguration.getConfig();
		if (component == null) return false;
		ConfigInfo config = component.getConfig(transmission);
		if (config == null) return false;
		RelativeSide relativeSide = RelativeSide.fromDirections(
				shared.sideConfiguration.getDirection(), side);
		if (!config.isSideEnabled(relativeSide)) return false;
		DataType dataType = config.getDataType(relativeSide);
		ISlotInfo slotInfo = config.getSlotInfo(relativeSide);
		if (dataType == null || slotInfo == null || !slotInfo.isEnabled()) return false;
		return output
				? dataType.canOutput() && slotInfo.canOutput()
				: canInput(dataType) && slotInfo.canInput();
	}

	private static boolean canInput(DataType dataType) {
		return dataType == DataType.INPUT
				|| dataType == DataType.INPUT_1
				|| dataType == DataType.INPUT_2
				|| dataType == DataType.INPUT_OUTPUT;
	}

	private static final class Cache {
		private final SharedState shared;
		private final CentrifugeExternalAeStorage[] bySide =
				new CentrifugeExternalAeStorage[Direction.values().length];

		private Cache(SharedState shared) {
			this.shared = shared;
		}
	}

	private static final class SharedState {
		private final IAe2OutputHostBase host;
		private final IMekCentrifugeTile centrifuge;
		private final ISideConfiguration sideConfiguration;
		private final KeyCounter itemSnapshot = new KeyCounter();
		private long itemSnapshotVersion = Long.MIN_VALUE;
		private int itemExtractCursor;
		private int fluidExtractCursor;
		private int inputCursor;

		private SharedState(IAe2OutputHostBase host, IMekCentrifugeTile centrifuge,
				ISideConfiguration sideConfiguration) {
			this.host = host;
			this.centrifuge = centrifuge;
			this.sideConfiguration = sideConfiguration;
		}
	}
}
