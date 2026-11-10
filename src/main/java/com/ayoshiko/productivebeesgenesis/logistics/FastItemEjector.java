package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.mek.SameTickFailureGate;
import com.ayoshiko.productivebeesgenesis.util.RoundRobinSlotTraversal;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.item.CursedTransporterItemHandler;
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
import java.util.function.LongSupplier;

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
 *   <li><b>目标请求退避</b>：完整物品身份和数量相同的零接收请求等待 50–200ms；
 *       记录有界，新请求、目标成功或能力失效立即恢复尝试。</li>
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

	private static final int TRANSPORTER_IDLE_THRESHOLD = 3;

	private static final int TRANSPORTER_BACKOFF_TICKS = 10;

	/** 目标解析与能力缓存 */
	private final NeighborItemTargets targets;
	private final TileEntityMekanism owner;
	private final LongSupplier nanoClock;

	/** 每个输出面独立计费，避免慢目标阻塞其它方向的普通容器。 */
	private final NeighborItemTransferState[] transferStates = new NeighborItemTransferState[6];
	private final TransporterRetry[] transporterRetries = new TransporterRetry[6];
	private long targetVersion;

	/** 同刻重复调用拦截（时间加速模组会在同一游戏刻内多次 tick 机器） */
	private final SameTickFailureGate sameTickGate = new SameTickFailureGate();

	/** 逻辑运输管道请求使用的槽位视图，实例复用以避免每次包装列表。 */
	private final SlotListItemHandler transporterSource = new SlotListItemHandler();
	private int transporterCursor;

	/**
	 * @param tile 所属机器
	 */
	public FastItemEjector(TileEntityMekanism tile) {
		this(tile, System::nanoTime);
	}

	FastItemEjector(TileEntityMekanism tile, LongSupplier nanoClock) {
		this.targets = new NeighborItemTargets(tile, this::onTargetInvalidated);
		this.owner = tile;
		this.nanoClock = nanoClock;
	}

	/** 侧面配置变更时调用。 */
	public void onConfigChanged() {
		targets.invalidate();
		for (Direction side : Direction.values()) onTargetInvalidated(side);
	}

	/**
	 * 逐刻弹出：把所有输出槽的物品送往所有输出面的相邻容器。
	 *
	 * @param tile          机器
	 * @param ejector       弹出器组件（用于判断自动弹出是否开启）
	 * @param itemConfig    ITEM 侧面配置
	 * @param gameTime      当前游戏刻
	 * @param outputVersion 输出槽内容版本（用于同刻拦截和运输管道重试）
	 * @param hasOutput     输出槽当前是否有物品（O(1) 读取）
	 */
	public void tick(TileEntityMekanism tile, TileComponentEjector ejector,
			@Nullable ConfigInfo itemConfig, long gameTime, long outputVersion, boolean hasOutput) {
		if (OutputCustody.of(owner).blocked() || itemConfig == null || !ejector.isEjecting(itemConfig, TransmissionType.ITEM)) return;
		if (!hasOutput) {
			Arrays.fill(transporterRetries, null);
			sameTickGate.clear();
			return;
		}
		List<Direction> sides = targets.outputSides(itemConfig);
		if (sides.isEmpty()) return;
		if (sameTickGate.shouldSkip(gameTime, outputVersion)) return;

		long observedTargetVersion = targetVersion;
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
				IItemHandler target;
			try { target = targets.handler(side); } catch (RuntimeException error) { continue; }
				if (target == null) continue;
				if (target instanceof CursedTransporterItemHandler) {
					TransporterRetry retry = transporterRetry(side, target);
					if (retry.shouldSkip(gameTime, outputVersion)) continue;
					long transferred = pushToTransporter(tile, ejector, slots, target);
					retry.record(transferred, gameTime, outputVersion);
					moved += transferred;
					continue;
				}
				moved += pushSlots(slots, transferState(side, target), gameTime);
			}
		}

		if (moved > 0L || observedTargetVersion != targetVersion) {
			sameTickGate.clear();
		} else {
			sameTickGate.recordFailure(gameTime, outputVersion);
		}
	}

	/** 通过 Mekanism 公共路由协议向逻辑运输管道发送一种物品。 */
	private long pushToTransporter(TileEntityMekanism tile, TileComponentEjector ejector,
			List<IInventorySlot> slots, IItemHandler target) {
		var custody = OutputCustody.of(owner);
		if (custody.blocked()) return 0;
		for (int offset = 0; offset < slots.size(); offset++) {
			int index = RoundRobinSlotTraversal.index(transporterCursor, offset, slots.size());
			IInventorySlot slot = slots.get(index);
			ItemStack taken = slot.extractItem(slot.getCount(), Action.EXECUTE, AutomationType.EXTERNAL);
			if (taken.isEmpty()) continue;
			transporterCursor = RoundRobinSlotTraversal.advance(index, slots.size());
			var request = TransitRequest.simple(taken.copy());
			int handed = custody.handoffItem(taken, "transporter", () -> {
				var response = request.eject(tile, target, 0, ignored -> ejector.getOutputColor());
				return response.isEmpty() ? 0 : response.getSendingAmount();
			});
			ItemStack rest = taken.copyWithCount(taken.getCount() - handed);
			if (!rest.isEmpty()) returnHome(slots, slot, rest);
			return handed;
		}
		return 0;
	}

	/**
	 * 产物直通：把还没进输出槽的产物直接送给相邻容器。
	 * <p>
	 * 产物仍由调用方缓冲持有，直接插入拷贝并按剩余量记账，省去重复模拟。
	 * 拒收备忘跨真实刻复用，耗时预算在本刻共享；未接收部分继续回落本地输出。
	 *
	 * @param ejector    弹出器组件
	 * @param itemConfig ITEM 侧面配置
	 * @param stack      待推送产物（不修改）
	 * @param gameTime   当前游戏刻（用于共享该刻的耗时预算）
	 * @return 实际被接收的数量
	 */
	public int pushDirect(TileComponentEjector ejector, @Nullable ConfigInfo itemConfig,
			ItemStack stack, long gameTime) {
		return pushDirect(ejector, itemConfig, stack, stack.getCount(), gameTime);
	}

	public int pushDirect(TileComponentEjector ejector, @Nullable ConfigInfo itemConfig,
			ItemStack stack, int amount, long gameTime) {
		if (OutputCustody.of(owner).blocked() || stack.isEmpty() || amount <= 0 || itemConfig == null) return 0;
		if (!ejector.isEjecting(itemConfig, TransmissionType.ITEM)) return 0;
		List<Direction> sides = targets.outputSides(itemConfig);
		if (sides.isEmpty()) return 0;

		int total = amount;
		int inserted = 0;
		for (Direction side : sides) {
			if (inserted >= total || OutputCustody.of(owner).blocked()) break;
			IItemHandler target;
			try { target = targets.handler(side); } catch (RuntimeException error) { continue; }
			// 逻辑运输管道必须从真实输出槽构建 TransitRequest；未入槽产物先回落输出槽，
			// 再由逐刻快速通道按颜色与路由协议发送。
			if (target == null || target instanceof CursedTransporterItemHandler) continue;
			NeighborItemTransferState state = transferState(side, target);
			if (!state.budget.canAttempt(gameTime, true)) continue;
			int want = total - inserted;
			if (state.isRejected(stack, want, nanoClock.getAsLong())) continue;
			ItemStack offered = stack.copyWithCount(want);
			long started = System.nanoTime();
			try {
				ItemStack leftover = ItemPushHelper.insert(target, offered, OutputCustody.of(owner),
						side.toString(), state::isValid);
				int accepted = Math.max(0, want - (leftover.isEmpty() ? 0 : leftover.getCount()));
				inserted += accepted;
				if (accepted == 0) {
					state.rememberRejected(offered, nanoClock.getAsLong());
				} else if (!OutputCustody.of(owner).blocked()) {
					state.recordSuccess();
				}
			} finally {
				state.budget.record(gameTime, true, System.nanoTime() - started);
			}
		}
		if (inserted > 0) {
			sameTickGate.clear();
		}
		return Math.max(0, Math.min(total, inserted));
	}

	/** 把一组输出槽尽量送进一个目标，返回搬运数量。 */
	private long pushSlots(List<IInventorySlot> slots, NeighborItemTransferState state, long gameTime) {
		int slotCount = slots.size();
		state.prepareSlots(slots);
		int start = RoundRobinSlotTraversal.normalize(state.slotCursor, slotCount);
		long moved = 0L;
		for (int offset = 0; offset < slotCount; offset++) {
			if (!state.isValid() || OutputCustody.of(owner).blocked()
					|| !state.budget.canAttempt(gameTime, false)) break;
			int slotIndex = RoundRobinSlotTraversal.index(start, offset, slotCount);
			state.slotCursor = RoundRobinSlotTraversal.advance(slotIndex, slotCount);
			IInventorySlot slot = slots.get(slotIndex);
			var binding = state.binding(slotIndex, slot);
			if (slot == null || slot.isEmpty()) continue;
			if (state.isRejected(binding, nanoClock.getAsLong())) continue;
			// 与 Mekanism 一致：用 EXTERNAL 模拟抽取判定真实可弹出量（尊重槽位抽取谓词）
			ItemStack available = slot.extractItem(slot.getCount(), Action.SIMULATE, AutomationType.EXTERNAL);
			if (available.isEmpty() || state.isRejected(available, nanoClock.getAsLong(), binding)) continue;

			long started = System.nanoTime();
			try {
				int accepted = ItemPushHelper.simulateInsert(state.target, available, state::isValid);
				if (accepted <= 0) {
					state.rememberRejected(available, nanoClock.getAsLong(), binding);
					continue;
				}
				if (!state.isValid()) break;
				ItemStack taken = slot.extractItem(accepted, Action.EXECUTE, AutomationType.EXTERNAL);
				if (taken.isEmpty()) continue;
				ItemStack leftover = ItemPushHelper.insert(state.target, taken, OutputCustody.of(owner),
						"neighbor", state::isValid);
				int transferred = taken.getCount() - (leftover.isEmpty() ? 0 : leftover.getCount());
				moved += transferred;
				if (transferred > 0 && !OutputCustody.of(owner).blocked()) {
					state.recordSuccess();
				} else if (transferred == 0) {
					state.rememberRejected(taken, nanoClock.getAsLong());
				}
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
	private void returnHome(List<IInventorySlot> slots, IInventorySlot source, ItemStack leftover) {
		ItemStack remaining = leftover.copy();
		for (int i = -1; i < slots.size() && !remaining.isEmpty(); i++) {
			IInventorySlot slot = i < 0 ? source : slots.get(i);
			if (slot == null || i >= 0 && slot == source) continue;
			ItemStack offered = remaining.copy();
			int handed = OutputCustody.of(owner).handoffItem(offered, "return-home", () ->
					ItemPushHelper.accepted(offered, slot.insertItem(offered.copy(), Action.EXECUTE, AutomationType.INTERNAL)));
			remaining.shrink(handed);
		}
		if (!remaining.isEmpty()) OutputCustody.of(owner).retainItem(remaining, "return-home-full");
	}

	private NeighborItemTransferState transferState(Direction side, IItemHandler target) {
		int index = side.ordinal();
		NeighborItemTransferState state = transferStates[index];
		if (state == null || state.target != target) {
			if (state != null) state.invalidate();
			state = new NeighborItemTransferState(target);
			transferStates[index] = state;
		}
		return state;
	}

	private void onTargetInvalidated(Direction side) {
		int index = side.ordinal();
		NeighborItemTransferState state = transferStates[index];
		if (state != null) state.invalidate();
		transferStates[index] = null;
		transporterRetries[index] = null;
		targetVersion++;
		sameTickGate.clear();
	}

	private TransporterRetry transporterRetry(Direction side, IItemHandler target) {
		int index = side.ordinal();
		TransporterRetry retry = transporterRetries[index];
		if (retry == null || retry.target != target) {
			retry = new TransporterRetry(target);
			transporterRetries[index] = retry;
		}
		return retry;
	}

	private static final class TransporterRetry {
		private final IItemHandler target;
		private int idleTicks;
		private long untilTick = Long.MIN_VALUE;
		private long version = Long.MIN_VALUE;
		private long failedTick = Long.MIN_VALUE;

		private TransporterRetry(IItemHandler target) {
			this.target = target;
		}

		private boolean shouldSkip(long gameTime, long outputVersion) {
			return gameTime < untilTick && outputVersion == version;
		}

		private void record(long moved, long gameTime, long outputVersion) {
			if (moved > 0) {
				idleTicks = 0;
				untilTick = Long.MIN_VALUE;
				failedTick = Long.MIN_VALUE;
			} else if (failedTick != gameTime) {
				failedTick = gameTime;
				if (++idleTicks >= TRANSPORTER_IDLE_THRESHOLD) {
					idleTicks = 0;
					untilTick = gameTime + TRANSPORTER_BACKOFF_TICKS;
					version = outputVersion;
				}
			}
		}
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
