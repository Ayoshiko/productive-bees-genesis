package com.ayoshiko.productivebeesgenesis.network;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2LeftoverReturner;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * AE2 专用离心机输入返还实现。
 * 调用方必须先确认 AE2 已安装；本类不得被无条件数据包注册路径主动加载。
 */
final class Ae2CentrifugeInputReturnService {

	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2CentrifugeInputReturnService() {
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

		CentrifugeAeReturnResult result = returnInputSlots(host, holder, storage, inputSlots);
		if (result.returnedToAe() > 0L || result.pending() > 0L) {
			host.productivebeesgenesis$markAe2StateChanged();
			holder.invalidateInputInventoryViewCache();
		}
		return result;
	}

	private static CentrifugeAeReturnResult returnInputSlots(IAe2InputHost host,
			Ae2OutputStateHolder holder, MEStorage storage, List<IInventorySlot> inputSlots) {
		long returnedToAe = 0L;
		long pending = 0L;
		var level = host.productivebeesgenesis$getAe2Level();
		if (level == null) return CentrifugeAeReturnResult.offline();
		for (IInventorySlot slot : inputSlots) {
			if (slot == null || slot.isEmpty()) continue;
			ItemStack current = slot.getStack();
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
				acceptedByStorage = SaturatingMath.clampToRequest(
						storage.insert(key, requested, Actionable.SIMULATE, ACTION_SOURCE), requested);
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
			int extractedCount;
			try {
				extractedCount = slot.shrinkStack(requestedExtract, Action.EXECUTE);
			} catch (RuntimeException e) {
				LogThrottle.warn("centrifuge_input_return_extract",
						"离心机输入返还执行提取失败，跳过槽位: {}", e.toString());
				continue;
			}
			extractedCount = Math.max(0, Math.min(requestedExtract, extractedCount));
			if (extractedCount <= 0) continue;
			int inserted;
			try {
				inserted = SaturatingMath.saturatingToInt(SaturatingMath.clampToRequest(
						storage.insert(key, extractedCount, Actionable.MODULATE, ACTION_SOURCE),
						extractedCount));
			} catch (LinkageError | RuntimeException e) {
				LogThrottle.warn("centrifuge_input_return_insert",
						"离心机输入返还执行插入 AE2 失败，转入兜底回送: {}", e.toString());
				inserted = 0;
			}
			returnedToAe = SaturatingMath.saturatingAdd(returnedToAe, inserted);
			if (inserted < extractedCount) {
				ItemStack remainder = key.toStack(extractedCount - inserted);
				int remaining = returnRemainder(host, holder, storage, inputSlots, key, remainder);
				if (remaining > 0) {
					long queued = holder.getPendingItemBuffer().enqueue(
							fingerprint, remaining, level.getGameTime());
					pending = SaturatingMath.saturatingAdd(pending, queued);
				}
			}
		}
		return CentrifugeAeReturnResult.online(returnedToAe, pending);
	}

	private static int returnRemainder(IAe2InputHost host, Ae2OutputStateHolder holder,
			MEStorage storage, List<IInventorySlot> inputSlots, AEItemKey key, ItemStack remainder) {
		int before = remainder.getCount();
		int remaining = Ae2LeftoverReturner.returnLeftoverToMe(storage, key, remainder,
				ACTION_SOURCE, holder.getPushState().getReturnBackoff(),
				host.productivebeesgenesis$getAe2Level(), host.productivebeesgenesis$getAe2BlockPos(),
				inputSlots);
		return Math.max(0, Math.min(before, remaining));
	}
}
