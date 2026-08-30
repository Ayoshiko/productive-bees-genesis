package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 输出槽的读取、账本结算与实际 ME 提交（从 {@code Ae2OutputPusher} 拆出，SRP）
 * <p>
 * 职责边界：本类只做「读槽 → 预留账本 → insert → 按实际接收量扣减 → 确认账本」，
 * 不做任何预算/退避/配额决策 —— 那些属于 {@link Ae2OutputSlotPass} 与
 * {@link Ae2OutputMergedPass}。这样「怎么提交」与「何时提交多少」互不牵连。
 * <p>
 * <b>不丢不复制的关键</b>：{@link Ae2OutputLedger} 先预留（带槽位指纹），insert 成功后按
 * 实际接收量 commitAccepted，再扣减源槽并 confirm。中途异常时记录保留，由
 * {@link #settleOutputLedger} 下 tick 重试；指纹不匹配则冻结该槽并报错，绝不盲扣。
 */
final class Ae2OutputCommitter {

	private Ae2OutputCommitter() {
	}

	/** 收集非空槽位到复用列表（AEItemKey 优先取 per-slot 缓存，避免每 tick 重建） */
	static void collectSlot(Ae2PushBuffers buffers, int process, int slotIdx,
			@Nullable IInventorySlot slot, @Nullable AeItemKeyCache cache,
			HolderLookup.Provider registries) {
		if (slot == null) return;
		ItemStack stack = slot.getStack();
		if (stack.isEmpty()) return;

		AEItemKey key;
		if (cache != null) {
			key = cache.get(process * AeItemKeyCache.SLOTS_PER_PROCESS + slotIdx, stack);
		} else {
			key = AEItemKey.of(stack);
		}
		if (key == null) return;

		Ae2SlotEntry entry;
		if (buffers.entryPoolCursor < buffers.entryPool.size()) {
			entry = buffers.entryPool.get(buffers.entryPoolCursor++);
		} else {
			entry = new Ae2SlotEntry();
			buffers.entryPool.add(entry);
			buffers.entryPoolCursor++;
		}
		entry.set(slot, stack, key, stack.getCount(), process, slotIdx,
				buffers.fingerprintCache.get(key, registries));
		buffers.entries.add(entry);
	}

	/**
	 * 结算上一 tick 遗留的账本记录（已入 AE 但源槽未扣减的窗口）。
	 *
	 * @return 本次确认扣减的物品总数
	 */
	static int settleOutputLedger(IAe2OutputHostBase host, Ae2OutputLedger ledger,
			HolderLookup.Provider registries) {
		int confirmed = 0;
		for (Ae2OutputLedger.Settlement settlement : ledger.snapshot()) {
			try {
				IInventorySlot slot = outputSlot(host, settlement.process(), settlement.slot());
				if (slot == null) continue;
				ItemStack current = slot.getStack();
				if (current.isEmpty()) {
					ledger.confirm(settlement.process(), settlement.slot(), settlement.remaining());
					confirmed += settlement.remaining();
					continue;
				}
				AEItemKey currentKey = AEItemKey.of(current);
				if (currentKey == null || !settlement.fingerprint().equals(
						Ae2ItemFingerprint.encode(currentKey, registries))) {
					LogThrottle.error("ae2_output_ledger_conflict",
							"AE2 输出账本与源槽指纹冲突，槽位已冻结 process={} slot={} remaining={}",
							settlement.process(), settlement.slot(), settlement.remaining());
					continue;
				}
				int remaining = Math.min(settlement.remaining(), current.getCount());
				if (remaining <= 0) continue;
				ItemStack updated = current.copy();
				updated.shrink(remaining);
				slot.setStack(updated);
				ledger.confirm(settlement.process(), settlement.slot(), remaining);
				confirmed += remaining;
			} catch (RuntimeException e) {
				LogThrottle.warn("ae2_output_ledger_settle",
						"AE2 输出账本结算异常，保留记录等待重试 process={} slot={}: {}",
						settlement.process(), settlement.slot(), e.toString());
			}
		}
		return confirmed;
	}

	@Nullable
	static IInventorySlot outputSlot(IAe2OutputHostBase host, int process, int slotIdx) {
		return switch (slotIdx) {
			case 0 -> host.primaryOutputSlot(process);
			case 1 -> host.secondaryOutputSlot(process);
			case 2 -> host.tertiaryOutputSlot(process);
			default -> null;
		};
	}

	/** 直接推送单个槽位（少量槽位场景，避免 Map 开销） */
	static int tryPushSlotDirect(Ae2SlotEntry entry, MEStorage meStorage, IActionSource actionSource,
			Ae2OutputLedger ledger) {
		IInventorySlot slot = entry.slot;
		int originalCount = entry.count;
		long inserted = 0;
		if (!ledger.reserve(entry.process, entry.slotIdx, entry.fingerprint, originalCount)) return 0;
		try {
			inserted = SaturatingMath.clampToRequest(
					meStorage.insert(entry.key, originalCount, Actionable.MODULATE, actionSource),
					originalCount);
			if (inserted <= 0) {
				ledger.cancel(entry.process, entry.slotIdx);
				return 0;
			}
			ledger.commitAccepted(entry.process, entry.slotIdx, (int) inserted);

			if (inserted >= originalCount) {
				slot.setStack(ItemStack.EMPTY);
			} else {
				ItemStack current = slot.getStack();
				if (current.isEmpty()) {
					ledger.confirm(entry.process, entry.slotIdx, (int) inserted);
					return (int) inserted;
				}
				if (!entry.key.matches(current)) return 0;
				ItemStack updated = current.copy();
				updated.shrink((int) inserted);
				slot.setStack(updated);
			}
			ledger.confirm(entry.process, entry.slotIdx, (int) inserted);
			return (int) inserted;
		} catch (Exception e) {
			if (inserted <= 0) ledger.cancel(entry.process, entry.slotIdx);
			Ae2PushExceptionLog.handle(e, entry.process, entry.slotIdx, entry.stack, originalCount);
			// v9-L4 修复：insert 已成功但 setStack 异常时，物品已进入 AE2 不可撤回。
			// 返回 0 时账本仍保留记录，由 settleOutputLedger 下 tick 补扣，不会复制。
			return 0;
		}
	}

	/**
	 * 批量推送相同 key 的多个槽位
	 * <p>
	 * 对合并后的 totalCount 调用一次 insert，然后按顺序清空槽位。
	 * 部分成功时从第一个槽位开始依次清空，直到分配完 inserted 数量。
	 */
	static int pushBatchKey(AEItemKey key, long totalCount, List<Ae2SlotEntry> slotEntries,
			MEStorage meStorage, IActionSource actionSource, Ae2OutputLedger ledger) {
		int reserved = 0;
		for (Ae2SlotEntry entry : slotEntries) {
			if (!ledger.reserve(entry.process, entry.slotIdx, entry.fingerprint, entry.count)) {
				for (int i = 0; i < reserved; i++) {
					Ae2SlotEntry reservedEntry = slotEntries.get(i);
					ledger.cancel(reservedEntry.process, reservedEntry.slotIdx);
				}
				return 0;
			}
			reserved++;
		}
		try {
			long inserted = SaturatingMath.clampToRequest(
					meStorage.insert(key, totalCount, Actionable.MODULATE, actionSource), totalCount);
			if (inserted <= 0) {
				for (Ae2SlotEntry entry : slotEntries) ledger.cancel(entry.process, entry.slotIdx);
				return 0;
			}

			int[] acceptedBySlot = new int[slotEntries.size()];
			long remainingToAssign = inserted;
			for (int i = 0; i < slotEntries.size(); i++) {
				Ae2SlotEntry entry = slotEntries.get(i);
				if (remainingToAssign <= 0) {
					ledger.cancel(entry.process, entry.slotIdx);
					continue;
				}
				int assigned = (int) Math.min(entry.count, remainingToAssign);
				acceptedBySlot[i] = assigned;
				ledger.commitAccepted(entry.process, entry.slotIdx, assigned);
				remainingToAssign -= assigned;
			}

			int confirmed = 0;
			for (int i = 0; i < slotEntries.size(); i++) {
				Ae2SlotEntry entry = slotEntries.get(i);
				int toRemove = acceptedBySlot[i];
				if (toRemove <= 0) continue;
				IInventorySlot slot = entry.slot;
				try {
					ItemStack current = slot.getStack();
					if (current.isEmpty()) {
						ledger.confirm(entry.process, entry.slotIdx, toRemove);
						confirmed += toRemove;
						continue;
					}
					if (!key.matches(current) || current.getCount() < toRemove) break;
					if (toRemove >= current.getCount()) {
						slot.setStack(ItemStack.EMPTY);
					} else {
						ItemStack updated = current.copy();
						updated.shrink(toRemove);
						slot.setStack(updated);
					}
					ledger.confirm(entry.process, entry.slotIdx, toRemove);
					confirmed += toRemove;
				} catch (Exception e) {
					Ae2PushExceptionLog.handle(e, entry.process, entry.slotIdx, entry.stack, entry.count);
					break;
				}
			}
			return confirmed;
		} catch (Exception e) {
			// v9-L1 修复：外层 catch 仅在 insert 抛出时触发（内层循环异常已被内层 catch 处理），
			// 此时槽位尚未被修改，无需回滚。移除死回滚代码避免误导。
			Ae2SlotEntry first = slotEntries.get(0);
			for (Ae2SlotEntry entry : slotEntries) ledger.cancel(entry.process, entry.slotIdx);
			Ae2PushExceptionLog.handle(e, first.process, first.slotIdx, first.stack, first.count);
			return 0;
		}
	}
}
