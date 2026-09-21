package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.mek.SameTickFailureGate;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.item.CursedTransporterItemHandler;
import mekanism.common.lib.inventory.HandlerTransitRequest;
import mekanism.common.lib.inventory.TransitRequest;
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

import java.util.Arrays;
import java.util.List;

/**
 * 自研高性能物品弹出通道（每台机器一份）。
 * <p>
 * <b>为什么不用 Mekanism 的 {@code outputItems}：</b>原版实现每个输出面每次只送走<b>一种</b>物品
 * （{@code TransitRequest.addToInventoryUnchecked} 插入成功即返回），构建弹出清单还要复制列表、
 * 洗牌、再用 {@code indexOf} 反查下标（O(n²)）。18 进程工厂有 54 个物品输出槽、同时产出十几种蜜脾，
 * 原版语义下每刻只能送走几种，其余容易积压。本通道批量遍历输出槽，昂贵目标用耗时预算
 * 控制后续调用，并且：
 * <ul>
 *   <li><b>先模拟再放入</b>（{@link ItemPushHelper}）：先算出目标能吃多少，再精确取出、插入，
 *       避免「取出后塞不下」的回填往返。</li>
 *   <li><b>轮转起点</b>：替代原版洗牌，同样避免靠后槽位饿死，但零分配、顺序跨刻可预测。</li>
 *   <li><b>本刻拒收备忘</b>：同一目标拒收相同组件、数量的请求后，本刻不再重复尝试；
 *       记录有界，缩小批量后仍可重试。</li>
 *   <li><b>自适应阻塞退避</b>：连续多刻搬不动且输出内容没有变化时短暂降频，
 *       产出版本一变立刻恢复满速。这些阈值是内置常量，不再暴露为配置。</li>
 * </ul>
 * <p>
 * <b>与 Mekanism 物流管道的兼容：</b>目标是 {@link CursedTransporterItemHandler}（逻辑运输管道）
 * 时直接使用公开的 {@link TransitRequest} 协议，保留输出颜色与路由信息，不修改原版弹出器。
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

	/** 目标解析与能力缓存 */
	private final NeighborItemTargets targets;

	/** 每个输出面独立计费，避免慢目标阻塞其它方向的普通容器。 */
	private final NeighborItemTransferState[] transferStates = new NeighborItemTransferState[6];

	/** 连续搬不动的刻数 */
	private int consecutiveIdleTicks;

	/** 退避到期刻 */
	private long backoffUntilTick = Long.MIN_VALUE;

	/** 进入退避时的输出内容版本（版本变化立即解除退避） */
	private long backoffVersion = Long.MIN_VALUE;

	/** 同刻重复调用拦截（时间加速模组会在同一游戏刻内多次 tick 机器） */
	private final SameTickFailureGate sameTickGate = new SameTickFailureGate();

	/** 逻辑运输管道请求使用的槽位视图，实例复用以避免每次包装列表。 */
	private final SlotListItemHandler transporterSource = new SlotListItemHandler();
	private int transporterCursor;

	/**
	 * @param tile 所属机器
	 */
	public FastItemEjector(TileEntityMekanism tile) {
		this.targets = new NeighborItemTargets(tile);
	}

	/** 侧面配置变更时调用。 */
	public void onConfigChanged() {
		targets.invalidate();
		Arrays.fill(transferStates, null);
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
	 */
	public void tick(TileEntityMekanism tile, TileComponentEjector ejector,
			@Nullable ConfigInfo itemConfig, long gameTime, long outputVersion, boolean hasOutput) {
		if (itemConfig == null || !ejector.isEjecting(itemConfig, TransmissionType.ITEM)) return;
		if (!hasOutput) {
			consecutiveIdleTicks = 0;
			clearBackoff();
			sameTickGate.clear();
			return;
		}
		if (isBackingOff(gameTime, outputVersion)) return;
		if (sameTickGate.shouldSkip(gameTime, outputVersion)) return;

		List<Direction> sides = targets.outputSides(itemConfig);
		if (sides.isEmpty()) return;

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
					moved += pushToTransporter(tile, ejector, slots, target);
					continue;
				}
				moved += pushSlots(slots, transferState(side, target), gameTime);
			}
		}

		if (moved > 0L) {
			consecutiveIdleTicks = 0;
			clearBackoff();
			sameTickGate.clear();
		} else {
			sameTickGate.recordFailure(gameTime, outputVersion);
			if (++consecutiveIdleTicks >= IDLE_BACKOFF_THRESHOLD) {
				consecutiveIdleTicks = 0;
				backoffUntilTick = gameTime + IDLE_BACKOFF_TICKS;
				backoffVersion = outputVersion;
			}
		}
	}

	/** 通过 Mekanism 公共路由协议向逻辑运输管道发送一种物品。 */
	private long pushToTransporter(TileEntityMekanism tile, TileComponentEjector ejector,
			List<IInventorySlot> slots, IItemHandler target) {
		transporterSource.setSlots(slots);
		HandlerTransitRequest request = new HandlerTransitRequest(transporterSource);
		transporterCursor = EjectItemMapBuilder.build(request, slots, transporterCursor);
		if (request.isEmpty()) return 0L;
		try {
			TransitRequest.TransitResponse response = request.eject(
					tile, target, 0, ignored -> ejector.getOutputColor());
			if (response.isEmpty()) return 0L;
			int sendingAmount = response.getSendingAmount();
			response.useAll();
			return sendingAmount;
		} catch (Exception exception) {
			LogThrottle.warn("fast_eject_transporter",
					"逻辑运输管道路由异常，已停止本次发送: {}", exception.toString());
			return 0L;
		}
	}

	/**
	 * 产物直通：把还没进输出槽的产物直接送给相邻容器。
	 * <p>
	 * 产物仍由调用方缓冲持有，直接插入拷贝并按剩余量记账，省去重复模拟。
	 * 拒收备忘与耗时预算跨本刻的配方重试复用；未接收部分继续回落本地输出。
	 *
	 * @param ejector    弹出器组件
	 * @param itemConfig ITEM 侧面配置
	 * @param stack      待推送产物（不修改）
	 * @param gameTime   当前游戏刻（用于拒收备忘的跨迭代复用与换刻清空）
	 * @return 实际被接收的数量
	 */
	public int pushDirect(TileComponentEjector ejector, @Nullable ConfigInfo itemConfig,
			ItemStack stack, long gameTime) {
		if (stack.isEmpty() || itemConfig == null) return 0;
		if (!ejector.isEjecting(itemConfig, TransmissionType.ITEM)) return 0;
		List<Direction> sides = targets.outputSides(itemConfig);
		if (sides.isEmpty()) return 0;

		int total = stack.getCount();
		int inserted = 0;
		for (Direction side : sides) {
			if (inserted >= total) break;
			IItemHandler target = targets.handler(side);
			// 逻辑运输管道必须从真实输出槽构建 TransitRequest；未入槽产物先回落输出槽，
			// 再由逐刻快速通道按颜色与路由协议发送。
			if (target == null || target instanceof CursedTransporterItemHandler) continue;
			NeighborItemTransferState state = transferState(side, target);
			if (!state.budget.canAttempt(gameTime, true)) continue;
			int want = total - inserted;
			if (state.isRejected(stack, want, gameTime)) continue;
			ItemStack offered = stack.copyWithCount(want);
			long started = System.nanoTime();
			try {
				ItemStack leftover = ItemPushHelper.insert(target, offered);
				int accepted = Math.max(0, want - (leftover.isEmpty() ? 0 : leftover.getCount()));
				inserted += accepted;
				if (accepted == 0) state.rememberRejected(offered, gameTime);
			} finally {
				state.budget.record(gameTime, true, System.nanoTime() - started);
			}
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
	private long pushSlots(List<IInventorySlot> slots, NeighborItemTransferState state, long gameTime) {
		int slotCount = slots.size();
		int start = RoundRobinSlotTraversal.normalize(state.slotCursor, slotCount);
		long moved = 0L;
		for (int offset = 0; offset < slotCount; offset++) {
			if (!state.budget.canAttempt(gameTime, false)) break;
			int slotIndex = RoundRobinSlotTraversal.index(start, offset, slotCount);
			state.slotCursor = RoundRobinSlotTraversal.advance(slotIndex, slotCount);
			IInventorySlot slot = slots.get(slotIndex);
			if (slot == null || slot.isEmpty()) continue;
			// 与 Mekanism 一致：用 EXTERNAL 模拟抽取判定真实可弹出量（尊重槽位抽取谓词）
			ItemStack available = slot.extractItem(slot.getCount(), Action.SIMULATE, AutomationType.EXTERNAL);
			if (available.isEmpty() || state.isRejected(available, gameTime)) continue;

			long started = System.nanoTime();
			try {
				int accepted = ItemPushHelper.simulateInsert(state.target, available);
				if (accepted <= 0) {
					state.rememberRejected(available, gameTime);
					continue;
				}
				ItemStack taken = slot.extractItem(accepted, Action.EXECUTE, AutomationType.EXTERNAL);
				if (taken.isEmpty()) continue;
				ItemStack leftover = ItemPushHelper.insert(state.target, taken);
				moved += taken.getCount() - (leftover.isEmpty() ? 0 : leftover.getCount());
				if (!leftover.isEmpty()) returnHome(slots, slot, leftover);
			} finally {
				state.budget.record(gameTime, false, System.nanoTime() - started);
			}
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

	private NeighborItemTransferState transferState(Direction side, IItemHandler target) {
		int index = side.ordinal();
		NeighborItemTransferState state = transferStates[index];
		if (state == null || state.target != target) {
			state = new NeighborItemTransferState(target);
			transferStates[index] = state;
		}
		return state;
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

	/** 将输出槽列表适配为 TransitRequest 所需的 IItemHandler。 */
	private static final class SlotListItemHandler implements IItemHandler {
		private List<IInventorySlot> slots = List.of();

		private void setSlots(List<IInventorySlot> slots) {
			this.slots = slots;
		}

		@Override
		public int getSlots() {
			return slots.size();
		}

		@Override
		public ItemStack getStackInSlot(int slot) {
			return valid(slot) ? slots.get(slot).getStack() : ItemStack.EMPTY;
		}

		@Override
		public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
			return stack;
		}

		@Override
		public ItemStack extractItem(int slot, int amount, boolean simulate) {
			if (!valid(slot) || amount <= 0) return ItemStack.EMPTY;
			return slots.get(slot).extractItem(amount,
					simulate ? Action.SIMULATE : Action.EXECUTE, AutomationType.EXTERNAL);
		}

		@Override
		public int getSlotLimit(int slot) {
			if (!valid(slot)) return 0;
			IInventorySlot inventorySlot = slots.get(slot);
			return inventorySlot.getLimit(inventorySlot.getStack());
		}

		@Override
		public boolean isItemValid(int slot, ItemStack stack) {
			return false;
		}

		private boolean valid(int slot) {
			return slot >= 0 && slot < slots.size() && slots.get(slot) != null;
		}
	}
}
