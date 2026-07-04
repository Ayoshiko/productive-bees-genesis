package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.me.helpers.BaseActionSource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * AE2 输出推送器
 * <br/>
 * 封装 {@link StorageHelper#poweredInsert} 调用逻辑，将离心机输出槽物品推送到 AE2 网络。
 * <p>
 * <b>推送流程</b>：
 * <ol>
 *   <li>检查集成启用 + 网格节点非空 + 网格已连接</li>
 *   <li>空输出短路：{@link IAe2OutputHost#productivebeesgenesis$hasOutputItems()} 为 false 时直接返回</li>
 *   <li>获取 {@link IStorageService} 的 {@link MEStorage} 作为目标存储</li>
 *   <li>创建 {@link MekEnergyToAeAdapter} 包装离心机能量容器为 AE2 能量源</li>
 *   <li>扫描所有进程的 primary/secondary/tertiary 输出槽，按 AEItemKey 分组</li>
 *   <li>对每个 AEItemKey 调用一次 poweredInsert（批量合并，减少 AE2 网络调用次数）</li>
 *   <li>按比例清空对应槽位</li>
 * </ol>
 * <p>
 * <b>容错策略</b>：推送失败（网络无空间或能量不足）时不阻塞，由 Ejector 兜底输出。
 * 单个 key 异常不影响其他 key 推送。
 * <p>
 * <b>性能优化</b>：
 * <ul>
 *   <li>同 key 批量合并：多个槽位有相同 AEItemKey 时合并为一次 poweredInsert 调用，
 *       减少 extendedae_plus InfinityBigIntegerCellInventory.getUUID 等昂贵操作的调用次数</li>
 *   <li>空输出短路：标志位 O(1) 检查，避免遍历 {@code processes × 3} 个槽位</li>
 *   <li>AEItemKey 缓存：通过 {@link AeItemKeyCache} 避免重复调用 AEItemKey.of(stack)</li>
 * </ul>
 * 此前曾尝试 tick 级节流（每游戏刻只推送 1 次），但导致离心机输出槽积压 →
 * areOutputSlotsFull → MyriadCreationsHandler 暂停 → 产出链停滞的 bug，已移除。
 */
public final class Ae2OutputPusher {

	/** AE2 到 Mekanism 能量转换比例：1 AE = 2 FE（AE2 标准比例） */
	private static final double AE_TO_FE_RATIO = 2.0;

	/**
	 * 异常日志限流计数器 — 避免高频异常导致日志洪水
	 * <p>
	 * 256× 加速下若 AE2 网络持续异常，每 tick 可能触发数千次异常。
	 * 使用 AtomicLong 计数，仅在首次异常和每 1024 次异常时记录一次日志。
	 */
	private static final AtomicLong PUSH_EXCEPTION_COUNTER = new AtomicLong(0);
	private static final long LOG_INTERVAL = 1024L;

	/**
	 * 批量合并的最小槽位数阈值
	 * <p>
	 * 当非空槽位数 <= 此阈值时，直接逐槽推送，避免 HashMap 分配开销。
	 * 仅当有足够多相同 key 的槽位时，批量合并才有收益。
	 */
	private static final int BATCH_MERGE_THRESHOLD = 3;

	private Ae2OutputPusher() {}

	/**
	 * 推送宿主所有输出槽的物品到 AE2 网络
	 * <br/>
	 * 集成未启用、节点未创建或网格未连接时安全短路。
	 * 输出槽全空时通过 {@code hasOutputItems()} 短路，避免遍历 {@code processes × 3} 个槽位。
	 * <p>
	 * 批量合并：扫描所有槽位后按 AEItemKey 分组，对每个 key 调用一次 poweredInsert。
	 * 在万象创世蜜脾离心场景下，多进程的输出槽常有相同类型蜜脾，合并后可大幅减少
	 * extendedae_plus InfinityBigIntegerCellInventory.getUUID 等昂贵操作的调用次数。
	 *
	 * @param host 输出宿主（离心机方块实体）
	 */
	public static void pushOutputs(IAe2OutputHost host) {
		// 1. 集成开关检查
		if (!Ae2IntegrationLoader.isIntegrationEnabled()) return;

		// 2. 空输出短路：标志位由 OutputSlotFlagManager/MekCentrifugeSlotManager O(1) 维护，
		//    AE2 推送清空槽位后 onAe2PushComplete 已调用 updateOutputSlotFlags() 保证同步
		if (!host.productivebeesgenesis$hasOutputItems()) return;

		// 3. 获取网格节点
		Object nodeObj = host.productivebeesgenesis$getAe2GridNode();
		if (!(nodeObj instanceof IManagedGridNode managedNode)) return;

		// 4. 获取已连接的网格
		IGrid grid = managedNode.getGrid();
		if (grid == null) return;

		// 5. 获取存储服务
		IStorageService storageService = grid.getService(IStorageService.class);
		MEStorage meStorage = storageService.getInventory();

		// 6. 创建能量适配器和操作源
		IEnergySource energySource = new MekEnergyToAeAdapter(host.productivebeesgenesis$getAe2EnergySource());
		IActionSource actionSource = new BaseActionSource() {};

		// 7. 获取 AEItemKey 缓存（Task 7：减少 AEItemKey.of(stack) 重复调用）
		Object cacheObj = host.productivebeesgenesis$getAeItemKeyCache();
		AeItemKeyCache keyCache = cacheObj instanceof AeItemKeyCache ? (AeItemKeyCache) cacheObj : null;

		// 8. 第一遍扫描：收集所有非空槽位
		int processes = host.processes();
		List<SlotEntry> entries = new ArrayList<>(processes * 3);
		for (int i = 0; i < processes; i++) {
			collectSlot(entries, i, 0, host.primaryOutputSlot(i), keyCache);
			collectSlot(entries, i, 1, host.secondaryOutputSlot(i), keyCache);
			collectSlot(entries, i, 2, host.tertiaryOutputSlot(i), keyCache);
		}

		if (entries.isEmpty()) return;

		// 9. 少量槽位时直接逐槽推送，避免 HashMap 分配开销
		if (entries.size() <= BATCH_MERGE_THRESHOLD) {
			int pushedItems = 0;
			for (SlotEntry entry : entries) {
				pushedItems += tryPushSlotDirect(entry, energySource, meStorage, actionSource);
			}
			if (pushedItems > 0) {
				host.productivebeesgenesis$onAe2PushComplete(pushedItems);
			}
			return;
		}

		// 10. 批量合并：按 AEItemKey 分组
		Map<AEItemKey, List<SlotEntry>> keyToEntries = new HashMap<>();
		Map<AEItemKey, Long> keyToTotalCount = new HashMap<>();
		for (SlotEntry entry : entries) {
			keyToEntries.computeIfAbsent(entry.key, k -> new ArrayList<>()).add(entry);
			keyToTotalCount.merge(entry.key, (long) entry.count, Long::sum);
		}

		// 11. 对每个 key 调用一次 poweredInsert，按比例清空槽位
		int pushedItems = 0;
		for (Map.Entry<AEItemKey, Long> keyEntry : keyToTotalCount.entrySet()) {
			AEItemKey key = keyEntry.getKey();
			long totalCount = keyEntry.getValue();
			pushedItems += pushBatchKey(key, totalCount, keyToEntries.get(key),
					energySource, meStorage, actionSource);
		}

		if (pushedItems > 0) {
			host.productivebeesgenesis$onAe2PushComplete(pushedItems);
		}
	}

	/**
	 * 收集非空槽位到列表
	 */
	private static void collectSlot(List<SlotEntry> entries, int process, int slotIdx,
									@Nullable IInventorySlot slot, @Nullable AeItemKeyCache cache) {
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

		entries.add(new SlotEntry(slot, stack, key, stack.getCount(), process, slotIdx));
	}

	/**
	 * 直接推送单个槽位（少量槽位场景，避免 HashMap 开销）
	 */
	private static int tryPushSlotDirect(SlotEntry entry, IEnergySource energySource,
										 MEStorage meStorage, IActionSource actionSource) {
		IInventorySlot slot = entry.slot;
		int originalCount = entry.count;
		try {
			long inserted = StorageHelper.poweredInsert(
					energySource, meStorage, entry.key, originalCount, actionSource, Actionable.MODULATE);
			if (inserted <= 0) return 0;

			if (inserted >= originalCount) {
				slot.setStack(ItemStack.EMPTY);
			} else {
				ItemStack current = slot.getStack();
				if (current.isEmpty()) return (int) inserted;
				current.shrink((int) inserted);
				slot.setStack(current);
			}
			return (int) inserted;
		} catch (Exception e) {
			handlePushException(e, entry.process, entry.slotIdx, entry.stack, originalCount);
			return 0;
		}
	}

	/**
	 * 批量推送相同 key 的多个槽位
	 * <p>
	 * 对合并后的 totalCount 调用一次 poweredInsert，然后按顺序清空槽位。
	 * 部分成功时从第一个槽位开始依次清空，直到分配完 inserted 数量。
	 */
	private static int pushBatchKey(AEItemKey key, long totalCount, List<SlotEntry> slotEntries,
									 IEnergySource energySource, MEStorage meStorage,
									 IActionSource actionSource) {
		try {
			long inserted = StorageHelper.poweredInsert(
					energySource, meStorage, key, totalCount, actionSource, Actionable.MODULATE);
			if (inserted <= 0) return 0;

			// 按顺序清空槽位
			long remaining = inserted;
			for (SlotEntry entry : slotEntries) {
				if (remaining <= 0) break;
				IInventorySlot slot = entry.slot;
				try {
					ItemStack current = slot.getStack();
					if (current.isEmpty()) continue;
					int toRemove = (int) Math.min(current.getCount(), remaining);
					if (toRemove <= 0) continue;
					if (toRemove >= current.getCount()) {
						slot.setStack(ItemStack.EMPTY);
					} else {
						current.shrink(toRemove);
						slot.setStack(current);
					}
					remaining -= toRemove;
				} catch (Exception e) {
					// 单个槽位清空异常不影响其他槽位
					handlePushException(e, entry.process, entry.slotIdx, entry.stack, entry.count);
				}
			}
			return (int) inserted;
		} catch (Exception e) {
			// 整个 key 推送异常，回滚所有槽位（防御性检查）
			for (SlotEntry entry : slotEntries) {
				try {
					ItemStack current = entry.slot.getStack();
					if (current.getCount() > entry.count) {
						current.setCount(entry.count);
						entry.slot.setStack(current);
					}
				} catch (Exception ignored) {
					// 回滚失败不影响其他槽位
				}
			}
			// 记录第一个槽位的信息作为日志代表
			SlotEntry first = slotEntries.get(0);
			handlePushException(e, first.process, first.slotIdx, first.stack, first.count);
			return 0;
		}
	}

	/**
	 * 异常处理：限流日志 + InterruptedException 恢复中断
	 */
	private static void handlePushException(Exception e, int process, int slotIdx,
											ItemStack stack, int originalCount) {
		long count = PUSH_EXCEPTION_COUNTER.incrementAndGet();
		if (count == 1 || count % LOG_INTERVAL == 0) {
			ProductiveBeesGenesis.LOGGER.error(
					"AE2 推送异常 (第 {} 次，每 {} 次记录一次) - process={}, slotIdx={}, item={}, count={}",
					count, LOG_INTERVAL, process, slotIdx,
					stack.getItem(), originalCount, e);
		}
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 槽位条目 — 缓存扫描结果，避免重复读取
	 */
	private static final class SlotEntry {
		final IInventorySlot slot;
		final ItemStack stack;
		final AEItemKey key;
		final int count;
		final int process;
		final int slotIdx;

		SlotEntry(IInventorySlot slot, ItemStack stack, AEItemKey key, int count, int process, int slotIdx) {
			this.slot = slot;
			this.stack = stack;
			this.key = key;
			this.count = count;
			this.process = process;
			this.slotIdx = slotIdx;
		}
	}

	/**
	 * Mekanism 能量容器到 AE2 能量源的适配器
	 * <br/>
	 * 将 {@link MachineEnergyContainer}（实现 Mekanism {@code IEnergyContainer}）
	 * 包装为 AE2 的 {@link IEnergySource}，供 {@link StorageHelper#poweredInsert} 使用。
	 * <p>
	 * <b>能量转换</b>：AE2 的 1 AE = {@link #AE_TO_FE_RATIO} FE（Mekanism 能量单位）。
	 * poweredInsert 每次插入消耗的能量很少（主要为传输成本），离心机自身 FE 供能。
	 * <p>
	 * <b>线程安全</b>：适配器本身无状态，所有操作委托给 {@link MachineEnergyContainer}
	 * （其内部使用原子类型保证线程安全）。
	 */
	private static final class MekEnergyToAeAdapter implements IEnergySource {

		private final MachineEnergyContainer<?> container;

		MekEnergyToAeAdapter(MachineEnergyContainer<?> container) {
			this.container = container;
		}

		@Override
		public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
			// AE 能量 → FE 能量（应用 multiplier 和转换比例）
			double scaled = multiplier.multiply(amount);
			long feAmount = (long) Math.ceil(scaled * AE_TO_FE_RATIO);
			if (feAmount <= 0) return 0;

			// Mekanism Action：MODULATE → EXECUTE，SIMULATE → SIMULATE
			mekanism.api.Action mekAction = mode.isSimulate()
					? mekanism.api.Action.SIMULATE
					: mekanism.api.Action.EXECUTE;
			long extracted = container.extract(feAmount, mekAction, AutomationType.INTERNAL);
			if (extracted <= 0) return 0;

			// FE 能量 → AE 能量（反向转换并应用 multiplier 除法）
			return multiplier.divide(extracted / AE_TO_FE_RATIO);
		}
	}
}
