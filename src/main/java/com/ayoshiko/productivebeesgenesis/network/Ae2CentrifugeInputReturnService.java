package com.ayoshiko.productivebeesgenesis.network;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2PendingItemBuffer;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2PushBackoff;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.function.Predicate;

/**
 * AE2 专用离心机输入返还实现。
 * 调用方必须先确认 AE2 已安装；本类不得被无条件数据包注册路径主动加载。
 */
public final class Ae2CentrifugeInputReturnService {

	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2CentrifugeInputReturnService() {
	}

	/** 先从生产槽接管到返还缓冲；网络断线或拒收不会继续占用输入槽。 */
	public static boolean retainUnprocessableInput(BlockEntity entity, IInventorySlot slot, int lane) {
		if (!(entity instanceof IAe2InputHost host) || !(entity instanceof IAe2OutputHostBase)) return false;
		var holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null || entity.getLevel() == null) return false;
		var input = slot.getStack().copy();
		if (input.isEmpty()) return true;
		var key = AEItemKey.of(input);
		if (key == null) return false;
		String fingerprint = Ae2ItemFingerprint.encode(key, entity.getLevel().registryAccess());
		var custody = holder.getPendingItemBuffer();
		if (custody.isBlocked() || !custody.canAcceptReturn(fingerprint, input.getCount())) return false;
		var transfer = custody.transfer();
		transfer.beginSlotWithdrawal(() -> fingerprint, input.getCount(), lane);
		try {
			int withdrawn = slot.shrinkStack(input.getCount(), Action.EXECUTE);
			transfer.extracted(withdrawn);
			transfer.finish(custody, entity.getLevel().getGameTime(), true);
		} finally {
			host.productivebeesgenesis$markAe2StateChanged();
			holder.invalidateInputInventoryViewCache();
		}
		return slot.isEmpty();
	}

	static CentrifugeAeReturnResult transfer(BlockEntity blockEntity,
			List<IInventorySlot> inputSlots) {
		if (!(blockEntity instanceof IAe2InputHost host)
				|| !(blockEntity instanceof IAe2OutputHostBase outputHost)) {
			return CentrifugeAeReturnResult.offline();
		}
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null || Ae2GridNodeManager.getGridNodeState(outputHost)
				!= Ae2GridNodeManager.STATE_ONLINE) {
			return CentrifugeAeReturnResult.offline();
		}
		MEStorage storage = Ae2GridNodeManager.getCachedMeStorage(holder, outputHost);
		if (storage == null) return CentrifugeAeReturnResult.offline();

		CentrifugeAeReturnResult result = returnInputSlots(host, holder, storage, inputSlots, stack -> true);
		if (result.returnedToAe() > 0L || result.pending() > 0L) {
			host.productivebeesgenesis$markAe2StateChanged();
			holder.invalidateInputInventoryViewCache();
		}
		return result;
	}

	/**
	 * Returns inputs that became invalid when smelting compatibility was turned off. A rejected
	 * network insert leaves the source stack in its slot, and the return backoff prevents a hot retry.
	 *
	 * @return true while invalid inputs remain in slots or in the pending return buffer
	 */
	public static boolean returnUnprocessableInputs(BlockEntity blockEntity,
			List<IInventorySlot> inputSlots) {
		if (!(blockEntity instanceof IAe2InputHost host)
				|| !(blockEntity instanceof IAe2OutputHostBase outputHost)) {
			return false;
		}
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return false;
		if (holder.getPendingItemBuffer().isBlocked()) return true;
		if (MekCentrifugeFactoryHelper.isSmeltingCompatEnabled(outputHost)) {
			holder.setUnprocessableInputReturnCheckPending(false);
			return false;
		}
		if (!holder.isUnprocessableInputReturnCheckPending()) return false;
		var level = host.productivebeesgenesis$getAe2Level();
		if (level == null || level.isClientSide) return false;
		Ae2PushBackoff backoff = holder.getPushState().getReturnBackoff();
		long now = System.nanoTime();
		if (backoff.shouldSkip(now)) return true;

		UnprocessableInputPredicate unprocessable = new UnprocessableInputPredicate(host);
		long reclassified = reclassifyUnprocessablePendingInputs(holder, level, unprocessable);
		if (reclassified > 0L) host.productivebeesgenesis$markAe2StateChanged();
		if (!hasMatchingInput(inputSlots, unprocessable)) {
			if (unprocessable.validationFailed()) return true;
			holder.setUnprocessableInputReturnCheckPending(false);
			host.productivebeesgenesis$markAe2StateChanged();
			return false;
		}

		if (Ae2GridNodeManager.getGridNodeState(outputHost) != Ae2GridNodeManager.STATE_ONLINE) {
			backoff.recordFailure(now);
			return true;
		}
		MEStorage storage = Ae2GridNodeManager.getCachedMeStorage(holder, outputHost);
		if (storage == null) {
			backoff.recordFailure(now);
			return true;
		}

		CentrifugeAeReturnResult result = returnInputSlots(host, holder, storage, inputSlots, unprocessable);
		if (result.returnedToAe() > 0L || result.pending() > 0L) {
			host.productivebeesgenesis$markAe2StateChanged();
			holder.invalidateInputInventoryViewCache();
		}
		boolean remains = hasMatchingInput(inputSlots, unprocessable);
		if (unprocessable.validationFailed()) return true;
		if (remains) {
			backoff.recordFailure(now);
		} else if (!remains && result.pending() == 0L && result.returnedToAe() > 0L) {
			backoff.recordSuccess();
		}
		if (!remains) {
			holder.setUnprocessableInputReturnCheckPending(false);
			host.productivebeesgenesis$markAe2StateChanged();
		}
		return remains || result.pending() > 0L;
	}

	static CentrifugeAeReturnResult returnInputSlots(IAe2InputHost host,
			Ae2OutputStateHolder holder, MEStorage storage, List<IInventorySlot> inputSlots,
			Predicate<ItemStack> shouldReturn) {
		long returnedToAe = 0L;
		long pending = 0L;
		var level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return CentrifugeAeReturnResult.offline();
		if (holder.getPendingItemBuffer().isBlocked()) {
			return CentrifugeAeReturnResult.online(0, holder.getPendingItemBuffer().getTotalAmount());
		}
		for (int slotIndex = 0; slotIndex < inputSlots.size(); slotIndex++) {
			IInventorySlot slot = inputSlots.get(slotIndex);
			if (slot == null || slot.isEmpty()) continue;
			ItemStack current = slot.getStack();
			if (!shouldReturn.test(current)) continue;
			AEItemKey key = AEItemKey.of(current);
			if (key == null) continue;
			String fingerprint;
			try {
				fingerprint = Ae2ItemFingerprint.encode(key, level.registryAccess());
			} catch (LinkageError | RuntimeException e) {
				LogThrottle.warn("centrifuge_input_return_fingerprint",
						"离心机输入返还生成物品指纹失败，跳过槽位: {}", e.toString());
				continue;
			}
			if (fingerprint.isBlank() || !holder.getPendingItemBuffer().canRegister(fingerprint)) {
				LogThrottle.warn("centrifuge_input_return_pending_full",
						"离心机输入返还缓冲已满，保留源槽物品 key={}", key);
				continue;
			}

			int requested = Math.max(0, current.getCount());
			long acceptedByStorage;
			try {
				acceptedByStorage = storage.insert(key, requested, Actionable.SIMULATE, ACTION_SOURCE);
				if (acceptedByStorage < 0 || acceptedByStorage > requested) {
					throw new IllegalStateException("Invalid ME return simulation count");
				}
			} catch (LinkageError | RuntimeException e) {
				LogThrottle.warn("centrifuge_input_return_insert_simulate",
						"离心机输入返还模拟插入 AE2 失败，跳过槽位: {}", e.toString());
				continue;
			}
			if (acceptedByStorage <= 0L) continue;

			int requestedExtract = SaturatingMath.saturatingToInt(acceptedByStorage);
			try {
				requestedExtract = Math.min(requestedExtract,
						slot.shrinkStack(requestedExtract, Action.SIMULATE));
			} catch (RuntimeException e) {
				LogThrottle.warn("centrifuge_input_return_extract_simulate",
						"离心机输入返还模拟提取失败，跳过槽位: {}", e.toString());
				continue;
			}
			if (requestedExtract <= 0) continue;
			var custody = holder.getPendingItemBuffer();
			var transfer = custody.transfer();
			transfer.beginSlotWithdrawal(() -> fingerprint, requestedExtract, slotIndex);
			try {
				int extractedCount = slot.shrinkStack(requestedExtract, Action.EXECUTE);
				transfer.extracted(extractedCount);
				if (extractedCount > 0) {
					transfer.offer(extractedCount, -1);
					long inserted = storage.insert(key, extractedCount, Actionable.MODULATE, ACTION_SOURCE);
					transfer.accepted(inserted);
					returnedToAe = SaturatingMath.saturatingAdd(returnedToAe, inserted);
				}
				long knownRemainder = transfer.knownAmount();
				transfer.finish(custody, level.getGameTime(), true);
				pending = SaturatingMath.saturatingAdd(pending, knownRemainder);
			} catch (LinkageError | RuntimeException error) {
				LogThrottle.error("centrifuge_input_return_unknown",
						"离心机输入返还待核验；停止重试 request={} target={} known={} offered={}: {}",
						transfer.request(), transfer.destination(), transfer.knownAmount(), transfer.offeredAmount(), error.toString());
				pending = SaturatingMath.saturatingAdd(pending, transfer.retainedAmount());
			}
			host.productivebeesgenesis$markAe2StateChanged();
			if (custody.isBlocked()) break;
		}

		return CentrifugeAeReturnResult.online(returnedToAe, pending);
	}

	static long reclassifyUnprocessablePendingInputs(IAe2InputHost host,
			Ae2OutputStateHolder holder) {
		var level = host.productivebeesgenesis$getAe2Level();
		return level == null ? 0L : reclassifyUnprocessablePendingInputs(
				holder, level, new UnprocessableInputPredicate(host));
	}

	private static long reclassifyUnprocessablePendingInputs(Ae2OutputStateHolder holder,
			Level level,
			UnprocessableInputPredicate shouldReturn) {
		long moved = 0L;
		for (Ae2PendingItemBuffer.PendingItem entry : holder.getPendingItemBuffer().snapshot(Long.MAX_VALUE)) {
			if (entry.amount() <= 0L) continue;
			AEItemKey key = Ae2ItemFingerprint.decode(entry.fingerprint(), level.registryAccess());
			if (key == null || !shouldReturn.test(key.toStack(1))) continue;
			moved = SaturatingMath.saturatingAdd(moved,
					holder.getPendingItemBuffer().moveToReturnToNetwork(
						entry.fingerprint(), entry.amount(), level.getGameTime()));
		}
		return moved;
	}

	private static boolean hasMatchingInput(List<IInventorySlot> inputSlots,
			Predicate<ItemStack> shouldReturn) {
		if (inputSlots == null) return false;
		for (IInventorySlot slot : inputSlots) {
			if (slot != null && !slot.isEmpty() && shouldReturn.test(slot.getStack())) return true;
		}
		return false;
	}

	private static final class UnprocessableInputPredicate implements Predicate<ItemStack> {
		private final IAe2InputHost host;
		private boolean validationFailed;

		private UnprocessableInputPredicate(IAe2InputHost host) {
			this.host = host;
		}

		@Override
		public boolean test(ItemStack stack) {
			if (stack.isEmpty()) return false;
			try {
				return !host.productivebeesgenesis$canProcessInput(stack);
			} catch (LinkageError | RuntimeException e) {
				validationFailed = true;
				LogThrottle.warn("centrifuge_auto_return_validation",
						"离心机输入可处理性检查失败，保留物品并稍后重试: {}", e.toString());
				return false;
			}
		}

		private boolean validationFailed() {
			return validationFailed;
		}
	}

}
