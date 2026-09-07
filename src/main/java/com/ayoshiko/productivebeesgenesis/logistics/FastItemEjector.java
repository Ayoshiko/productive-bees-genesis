package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.mek.SameTickFailureGate;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.item.CursedTransporterItemHandler;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.ISlotInfo;
import mekanism.common.tile.component.config.slot.InventorySlotInfo;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 自研高性能物品弹出通道（每台机器一份）。
 * <p>
 * <b>为什么不用 Mekanism 的 {@code outputItems}：</b>原版实现每个输出面每次只送走<b>一种</b>物品
 * （{@code TransitRequest.addToInventoryUnchecked} 插入成功即返回），构建弹出清单还要复制列表、
 * 洗牌、再用 {@code indexOf} 反查下标（O(n²)）。18 进程工厂有 54 个物品输出槽、同时产出十几种蜜脾，
 * 原版语义下每刻只能送走几种，其余积压到「输出满」直接停机——早期版本因此堆了十来个节流配置
 * 去缓解症状。本通道改为<b>一次遍历全部输出槽、全部种类一次送完</b>，并且：
 * <ul>
 *   <li><b>先模拟再放入</b>（{@link ItemPushHelper}）：先算出目标能吃多少，再精确取出、插入，
 *       避免「取出后塞不下」的回填往返。</li>
 *   <li><b>轮转起点</b>：替代原版洗牌，同样避免靠后槽位饿死，但零分配、顺序跨刻可预测。</li>
 *   <li><b>本刻拒收备忘</b>：某个物品类型被目标拒收后，同一目标同一刻内不再为同类型槽位
 *       重复模拟，把「目标满」场景从 O(槽数 × 目标槽数) 压到常数级。</li>
 *   <li><b>自适应阻塞退避</b>：连续多刻搬不动且输出内容没有变化时短暂降频，
 *       产出版本一变立刻恢复满速。这些阈值是内置常量，不再暴露为配置。</li>
 * </ul>
 * <p>
 * <b>与 Mekanism 物流管道的兼容：</b>目标是 {@link CursedTransporterItemHandler}（逻辑运输管道）
 * 时必须走原版 {@code TransitRequest} 协议才能带上颜色与路由信息，本通道跳过该方向并请调用方
 * 回退到原版 {@code outputItems}。
 * <p>
 * 线程安全：仅服务端 tick 线程访问。
 *
 * @since 2.1.0
 */
public final class FastItemEjector {

	/** 连续多少刻搬不动后进入退避 */
	private static final int IDLE_BACKOFF_THRESHOLD = 3;

	/** 退避跳过的刻数（产出版本变化会立即解除） */
	private static final int IDLE_BACKOFF_TICKS = 10;

	/** 本刻拒收备忘容量（产物种类通常 ≤ 十几种，超出部分退化为正常模拟） */
	private static final int REJECT_MEMO_CAPACITY = 12;

	/** 目标解析与能力缓存 */
	private final NeighborItemTargets targets;

	/** 输出槽轮转游标 */
	private int slotCursor;

	/** 本刻拒收备忘（每个目标独立清空） */
	private final ItemStack[] rejectedTypes = new ItemStack[REJECT_MEMO_CAPACITY];
	private int rejectedCount;

	/** 连续搬不动的刻数 */
	private int consecutiveIdleTicks;

	/** 退避到期刻 */
	private long backoffUntilTick = Long.MIN_VALUE;

	/** 进入退避时的输出内容版本（版本变化立即解除退避） */
	private long backoffVersion = Long.MIN_VALUE;

	/** 同刻重复调用拦截（时间加速模组会在同一游戏刻内多次 tick 机器） */
	private final SameTickFailureGate sameTickGate = new SameTickFailureGate();

	/**
	 * @param tile 所属机器
	 */
	public FastItemEjector(TileEntityMekanism tile) {
		this.targets = new NeighborItemTargets(tile);
	}

	/** 侧面配置变更时调用。 */
	public void onConfigChanged() {
		targets.invalidate();
		sameTickGate.clear();
		clearBackoff();
	}

	/**
	 * 逐刻弹出：把所有输出槽的物品送往所有输出面的相邻容器。
	 *
	 * @param tile          机器
	 * @param ejector       弹出器组件（用于判断自动弹出是否开启）
	 * @param itemConfig    ITEM 侧面配置
	 * @param gameTime      当前游戏刻
	 * @param outputVersion 输出槽内容版本（用于退避解除与同刻拦截）
	 * @param hasOutput     输出槽当前是否有物品（O(1) 读取）
	 * @return true 表示存在必须交给 Mekanism 原版处理的目标（逻辑运输管道），调用方应回退
	 */
	public boolean tick(TileEntityMekanism tile, TileComponentEjector ejector,
			@Nullable ConfigInfo itemConfig, long gameTime, long outputVersion, boolean hasOutput) {
		if (itemConfig == null || !ejector.isEjecting(itemConfig, TransmissionType.ITEM)) return false;
		if (!hasOutput) {
			consecutiveIdleTicks = 0;
			clearBackoff();
			sameTickGate.clear();
			return false;
		}
		if (isBackingOff(gameTime, outputVersion)) return false;
		if (sameTickGate.shouldSkip(gameTime, outputVersion)) return false;

		List<Direction> sides = targets.outputSides(itemConfig);
		if (sides.isEmpty()) return false;

		boolean needsVanillaFallback = false;
		long moved = 0L;
		for (DataType dataType : itemConfig.getSupportedDataTypes()) {
			if (!dataType.canOutput()) continue;
			ISlotInfo slotInfo = itemConfig.getSlotInfo(dataType);
			if (!(slotInfo instanceof InventorySlotInfo inventorySlotInfo) || slotInfo.isEmpty()) continue;
			List<IInventorySlot> slots = inventorySlotInfo.getSlots();
			if (slots.isEmpty()) continue;
			for (Direction side : sides) {
				if (itemConfig.getDataType(mekanism.api.RelativeSide.fromDirections(
						tile.getDirection(), side)) != dataType) {
					continue; // 该方向配置的是别的 DataType，槽位集合不同
				}
				IItemHandler target = targets.handler(side);
				if (target == null) continue;
				if (target instanceof CursedTransporterItemHandler) {
					needsVanillaFallback = true;
					continue;
				}
				moved += pushSlots(slots, target);
			}
		}

		if (moved > 0L) {
			consecutiveIdleTicks = 0;
			clearBackoff();
			sameTickGate.clear();
		} else if (!needsVanillaFallback) {
			sameTickGate.recordFailure(gameTime, outputVersion);
			if (++consecutiveIdleTicks >= IDLE_BACKOFF_THRESHOLD) {
				consecutiveIdleTicks = 0;
				backoffUntilTick = gameTime + IDLE_BACKOFF_TICKS;
				backoffVersion = outputVersion;
			}
		}
		return needsVanillaFallback;
	}

	/**
	 * 产物直通：先模拟再放入，把还没进输出槽的产物直接送给相邻容器。
	 *
	 * @param ejector    弹出器组件
	 * @param itemConfig ITEM 侧面配置
	 * @param stack      待推送产物（不修改）
	 * @return 实际被接收的数量
	 */
	public int pushDirect(TileComponentEjector ejector, @Nullable ConfigInfo itemConfig, ItemStack stack) {
		if (stack.isEmpty() || itemConfig == null) return 0;
		if (!ejector.isEjecting(itemConfig, TransmissionType.ITEM)) return 0;
		List<Direction> sides = targets.outputSides(itemConfig);
		if (sides.isEmpty()) return 0;

		int total = stack.getCount();
		int inserted = 0;
		for (Direction side : sides) {
			if (inserted >= total) break;
			IItemHandler target = targets.handler(side);
			// 逻辑运输管道必须走原版 TransitRequest 协议（颜色/路由），直通路径跳过，
			// 这部分产物回落输出槽后由原版弹出接管。
			if (target == null || target instanceof CursedTransporterItemHandler) continue;
			ItemStack remaining = stack.copyWithCount(total - inserted);
			int accepted = ItemPushHelper.simulateInsert(target, remaining);
			if (accepted <= 0) continue;
			ItemStack leftover = ItemPushHelper.insert(target, remaining.copyWithCount(accepted));
			inserted += accepted - (leftover.isEmpty() ? 0 : leftover.getCount());
		}
		if (inserted > 0) {
			// 直通成功说明目标有空间：解除可能残留的退避，让逐刻弹出立刻跟进
			consecutiveIdleTicks = 0;
			clearBackoff();
			sameTickGate.clear();
		}
		return Math.max(0, Math.min(total, inserted));
	}

	/** 把一组输出槽尽量送进一个目标，返回搬运数量。 */
	private long pushSlots(List<IInventorySlot> slots, IItemHandler target) {
		int slotCount = slots.size();
		int start = RoundRobinSlotTraversal.normalize(slotCursor, slotCount);
		slotCursor = RoundRobinSlotTraversal.advance(start, slotCount);
		rejectedCount = 0;
		long moved = 0L;
		for (int offset = 0; offset < slotCount; offset++) {
			IInventorySlot slot = slots.get(RoundRobinSlotTraversal.index(start, offset, slotCount));
			if (slot == null || slot.isEmpty()) continue;
			// 与 Mekanism 一致：用 EXTERNAL 模拟抽取判定真实可弹出量（尊重槽位抽取谓词）
			ItemStack available = slot.extractItem(slot.getCount(), Action.SIMULATE, AutomationType.EXTERNAL);
			if (available.isEmpty() || isRejected(available)) continue;

			int accepted = ItemPushHelper.simulateInsert(target, available);
			if (accepted <= 0) {
				rememberRejected(available);
				continue;
			}
			ItemStack taken = slot.extractItem(accepted, Action.EXECUTE, AutomationType.EXTERNAL);
			if (taken.isEmpty()) continue;
			ItemStack leftover = ItemPushHelper.insert(target, taken);
			moved += taken.getCount() - (leftover.isEmpty() ? 0 : leftover.getCount());
			if (!leftover.isEmpty()) returnHome(slots, slot, leftover);
		}
		return moved;
	}

	/**
	 * 目标违反 simulate/execute 一致性时把剩余物品放回机器，绝不丢物。
	 * <br/>
	 * 先放回原槽（刚抽走必然有空间），再尝试同组其它槽；使用 INTERNAL 以通过输出槽的
	 * 「仅内部可插入」谓词。
	 */
	private static void returnHome(List<IInventorySlot> slots, IInventorySlot source, ItemStack leftover) {
		ItemStack remaining = source.insertItem(leftover, Action.EXECUTE, AutomationType.INTERNAL);
		for (int i = 0; i < slots.size() && !remaining.isEmpty(); i++) {
			IInventorySlot slot = slots.get(i);
			if (slot == null || slot == source) continue;
			remaining = slot.insertItem(remaining, Action.EXECUTE, AutomationType.INTERNAL);
		}
		if (!remaining.isEmpty()) {
			LogThrottle.warn("fast_eject_return",
					"目标容器的模拟与执行结果不一致，且产物无法放回输出槽: {} x{}",
					remaining.getItem(), remaining.getCount());
		}
	}

	private boolean isRejected(ItemStack stack) {
		for (int i = 0; i < rejectedCount; i++) {
			if (ItemStack.isSameItemSameComponents(rejectedTypes[i], stack)) return true;
		}
		return false;
	}

	private void rememberRejected(ItemStack stack) {
		if (rejectedCount >= REJECT_MEMO_CAPACITY) return;
		rejectedTypes[rejectedCount++] = stack.copyWithCount(1);
	}

	private boolean isBackingOff(long gameTime, long outputVersion) {
		if (gameTime >= backoffUntilTick) return false;
		if (outputVersion != backoffVersion) {
			clearBackoff();
			return false;
		}
		return true;
	}

	private void clearBackoff() {
		backoffUntilTick = Long.MIN_VALUE;
		backoffVersion = Long.MIN_VALUE;
	}
}
