package com.ayoshiko.productivebeesgenesis.network;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputTransfer;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2NetworkInventoryView;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PlayerInventoryTransfer;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
	 * AE2 相关数据包的服务端与客户端处理集合
	 * <p>
	 * 从 {@link ModPayloads} 拆分而来，职责：
	 * <ol>
	 *   <li>切换 per-tile AE2 输出开关（{@link CycleAeOutputPayload}）</li>
	 *   <li>切换 per-tile AE2 输入拉取 / NBT 忽略 / 精确模式（{@link ToggleAeInputPayload} 等）</li>
	 *   <li>循环切换输入过滤模式（{@link CycleAeInputFilterModePayload}）</li>
	 *   <li>增删过滤条目（{@link SetAeInputFilterEntryPayload}）</li>
	 *   <li>请求打开配置窗口（{@link OpenAeInputConfigPayload}）</li>
	 *   <li>同步过滤器条目到客户端（{@link SyncAeInputFilterEntriesPayload}）</li>
	 * </ol>
	 * 所有方法包级可见，由 {@link ModPayloads#register} 通过方法引用挂载到对应数据包。
	 * <p>
	 * 所有服务端操作先通过 {@link MachineMenuAccess} 复核当前菜单和实时权限，
	 * 再校验对应宿主类型、过滤器状态和操作预算。
	 * <p>
	 * 依赖倒置：通过 {@link IAe2InputHost} / {@link IAe2OutputHostBase} 接口多态判定，
	 * 避免直接引用 ME/EME 可选依赖的具体子类（其中 IAe2InputHost 由 Mixin 运行时注入）。
	 */
final class Ae2PayloadHandlers {
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2PayloadHandlers() {
	}

	static boolean validateInputFilterAvailable(ServerPlayer player, BlockPos pos) {
		if (!MachineMenuAccess.canAccess(player, pos)) {
			return false;
		}
		if (player.level().getBlockEntity(pos) instanceof IAe2InputHost host
				&& host.productivebeesgenesis$getAe2StateHolder().hasDeferredInputFilter()) {
			player.displayClientMessage(Component.translatable("command.productivebeesgenesis.output.input_filter"), true);
			return false;
		}
		return true;
	}

	/**
	 * 服务端处理：切换 per-tile AE2 物品/流体输出开关
	 * <br/>
	 * 校验玩家身份、方块实体类型、8格交互距离后，按输出类型调用对应 toggle 方法。
	 * 支持所有实现 {@link IAe2OutputHostBase} 的方块实体（蜂箱含工厂版子类、离心机含工厂版子类），
	 * 通过接口多态统一调用 toggle 方法，避免引用 ME/EME 可选依赖的具体子类（依赖倒置原则）。
	 * 切换后通过 SyncableBoolean tracker 自动同步到客户端，markForSave 持久化到 NBT。
	 */
	static void handleCycleAeOutput(CycleAeOutputPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击（打开自己方块后远程操作 8 格内他人方块）
		if (!MachineMenuAccess.canAccess(serverPlayer, payload.pos())) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		// 距离校验：标准 8 格 GUI 交互距离（8² = 64）
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_output_distance", "玩家 {} 尝试远距离切换 AE2 输出：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		// 接口多态判定 — 所有蜂箱和离心机均实现 IAe2OutputHostBase
		// 通过接口统一调用 toggle 方法，避免引用 ME/EME 可选依赖的具体子类
		if (!(be instanceof IAe2OutputHostBase host)) {
			return;
		}
		if (payload.outputType() == CycleAeOutputPayload.OutputType.ITEM) {
			host.toggleAeItemOutput();
		} else if (payload.outputType() == CycleAeOutputPayload.OutputType.FLUID) {
			host.toggleAeFluidOutput();
		} else if (payload.outputType() == CycleAeOutputPayload.OutputType.APIARY_DIRECT
				&& be instanceof com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary apiary) {
			apiary.toggleDirectAeOutput();
		} else if (payload.outputType() == CycleAeOutputPayload.OutputType.APIARY_CENTRIFUGE_PRIORITY
				&& be instanceof com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary apiary) {
			apiary.toggleCentrifugePriority();
		} else if (payload.outputType() == CycleAeOutputPayload.OutputType.CENTRIFUGE_DIRECT
				&& be instanceof com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile) {
			host.productivebeesgenesis$getAe2StateHolder().toggleCentrifugeDirectAeOutputEnabled();
			if (be instanceof TileEntityMekanism mekTile) {
				mekTile.markForSave();
			}
		}
	}

	/**
	 * 服务端处理：切换 per-tile AE2 输入拉取开关
	 * <br/>
	 * 校验玩家身份、8 格交互距离后，通过 instanceof 检查 IAe2InputHost 接口。
	 * IAe2InputHost 由 Mixin 运行时注入到 ME/EME 工厂类，编译时不可见，
	 * 但 instanceof 在运行时有效（基础离心机和 AbstractMekCentrifugeFactory 直接 implements）。
	 */
	static void handleToggleAeInput(ToggleAeInputPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击
		if (!MachineMenuAccess.canAccess(serverPlayer, payload.pos())) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_input_pull_distance", "玩家 {} 尝试远距离切换 AE2 输入拉取：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof IAe2InputHost host)) {
			return;
		}
		host.productivebeesgenesis$toggleAeItemInput();
	}

	/**
	 * 服务端处理：切换 per-tile 离心机电力熔炼炉配方兼容开关
	 * <br/>
	 * 校验玩家身份、容器匹配、8 格交互距离与全局总开关
	 * （{@code mekCentrifugeSmeltingCompatEnabled} 关闭时拒绝切换）。
	 */
	static void handleToggleSmeltingCompat(ToggleSmeltingCompatPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		if (!MachineMenuAccess.canAccess(serverPlayer, payload.pos())) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("smelting_compat_distance", "玩家 {} 尝试远距离切换熔炉配方兼容：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile centrifuge)
				|| !(be instanceof IAe2OutputHostBase host)) {
			return;
		}
		// 全局总开关关闭时不可切换（与 GUI active 状态一致）
		if (ModConfig.SERVER == null || ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled == null
				|| !ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled.get()) {
			return;
		}
		host.productivebeesgenesis$getAe2StateHolder().toggleSmeltingCompatEnabled();
		centrifuge.productivebeesgenesis$onSmeltingCompatChanged();
		if (be instanceof TileEntityMekanism mekTile) {
			mekTile.markForSave();
		}
	}

	/**
	 * 服务端处理：切换 per-tile AE2 输入 NBT 忽略开关
	 * <br/>
	 * 校验玩家身份、8 格交互距离后，通过 instanceof 检查 IAe2InputHost 接口。
	 * NBT 忽略开关决定拉取时是否区分蜜脾的 NBT 数据。
	 */
	static void handleToggleAeInputNbtIgnore(ToggleAeInputNbtIgnorePayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击
		if (!MachineMenuAccess.canAccess(serverPlayer, payload.pos())) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_input_nbt_distance", "玩家 {} 尝试远距离切换 AE2 输入 NBT 忽略：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof IAe2InputHost host)) {
			return;
		}
		host.productivebeesgenesis$toggleAeInputNbtIgnore();
	}

	/**
	 * 服务端处理：循环切换 per-tile AE2 输入过滤模式
	 * <br/>
	 * DISABLED → WHITELIST → BLACKLIST → DISABLED。
	 * 过滤器为 null 时（基础离心机未启用过滤）安全返回，不执行切换。
	 */
	static void handleCycleAeInputFilterMode(CycleAeInputFilterModePayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击
		if (!validateInputFilterAvailable(serverPlayer, payload.pos())) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_filter_cycle",
			NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_input_filter_mode_distance", "玩家 {} 尝试远距离切换 AE2 输入过滤模式：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof IAe2InputHost host)) {
			return;
		}
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) {
			return; // 基础离心机未启用过滤，安全返回
		}
		// 循环切换模式：DISABLED → WHITELIST → BLACKLIST → DISABLED
		Ae2InputFilter.FilterMode[] modes = Ae2InputFilter.FilterMode.values();
		int nextOrdinal = (filter.getFilterMode().ordinal() + 1) % modes.length;
		filter.setFilterMode(modes[nextOrdinal]);
		// 标记 dirty 确保过滤器修改持久化（与 toggleAeItemInput 的持久化模式一致）
		if (be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		// 推送完整条目列表到客户端（filterMode 已有 SyncableInt 同步，
		// 但 entries 也需要确保一致，合并推送）
		Ae2FilterPayloadHandlers.syncFilterToClient(be, serverPlayer);
	}

	/**
	 * Server handling for AE2LT-style virtual output slots: extract into the
	 * player cursor/inventory or insert the carried stack into the ME network.
	 * Mirrors OverloadedInterfaceMenu#handlePickup / handleQuickMove.
	 */
	static void handleAeInputOutputSlot(AeInputOutputSlotPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) return;
		if (serverPlayer.level() == null) return;
		if (!MachineMenuAccess.canAccess(serverPlayer, payload.pos())) return;
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) return;
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_output_slot_distance",
					"玩家 {} 尝试远距离操作 AE2 输入输出槽：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof IAe2InputHost host)) return;
		Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
		if (holder == null) return;
		String operation = payload.shift() ? "shift" : (payload.rightClick() ? "right" : "left");
		String rateKey = "ae_input_output_slot:" + payload.pos().asLong() + ":" + operation;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, rateKey,
			NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		Ae2InputFilter filter = holder.getOrCreateInputFilter();
		int slot = payload.slotIndex();
		if (filter == null || !filter.isDirectEntry(slot)) return;
		AEItemKey key = filter.getResolvedDirectKey(slot);
		if (key == null && serverPlayer.level().registryAccess() != null) {
			Ae2InputFilter.EntryInfo entryInfo = filter.getEntryAt(slot);
			if (entryInfo == null || entryInfo.directFingerprint == null) return;
			key = Ae2ItemFingerprint.decode(
					entryInfo.directFingerprint,
					serverPlayer.level().registryAccess());
			if (key != null) filter.resolveDirectKey(slot, key);
		}
		if (key == null) return;
		IStorageService storageService = Ae2GridNodeManager.getCachedStorage(holder, host);
		if (storageService == null) return;
		MEStorage meStorage = Ae2GridNodeManager.getCachedMeStorage(holder, host);
		if (meStorage == null) return;
		var pending = holder.getPendingItemBuffer();
		if (pending.isBlocked()) return;
		long now = serverPlayer.level().getGameTime();
		ItemStack carried = serverPlayer.containerMenu.getCarried();
		var transfer = pending.transfer();
		AEItemKey selectedKey = key;
		String fingerprint = Ae2ItemFingerprint.encode(selectedKey, serverPlayer.registryAccess());
		if (!pending.canAcceptReturn(fingerprint, selectedKey.getMaxStackSize())) return;
		try {
			if (!payload.shift() && !carried.isEmpty()) {
				AEItemKey carriedKey = AEItemKey.of(carried);
				if (carriedKey == null) return;
				int requested = payload.rightClick() ? 1 : carried.getCount();
				String carriedFingerprint = Ae2ItemFingerprint.encode(carriedKey, serverPlayer.registryAccess());
				if (!pending.canAcceptReturn(carriedFingerprint, requested)) return;
				transfer.beginKnown(() -> carriedFingerprint, requested);
				transfer.playerContext(serverPlayer.getUUID(), true);
				carried.shrink(requested);
				if (carried.isEmpty()) serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
				transfer.offer(requested, -1);
				host.productivebeesgenesis$markAe2StateChanged();
				long inserted = meStorage.insert(carriedKey, requested, Actionable.MODULATE, ACTION_SOURCE);
				transfer.accepted(inserted);
				if (transfer.knownAmount() > 0) {
					deliverKnownToPlayer(serverPlayer, transfer, carriedKey, false);
				}
			} else {
				// 紧邻真实提取重新查询库存；显示缓存不能授权取走保留线以内的物品。
				long visible = meStorage.extract(selectedKey, Long.MAX_VALUE, Actionable.SIMULATE, ACTION_SOURCE);
				long cap = filter.getDirectPullLimit(selectedKey, visible, holder.isAeInputNbtIgnore(),
						serverPlayer.registryAccess());
				if (cap >= 0) visible = Math.min(visible, cap);
				long requested = Math.min(Math.max(0, visible), selectedKey.getMaxStackSize());
				if (!payload.shift() && payload.rightClick() && requested > 0) {
					requested = (requested + 1) / 2;
				}
				if (payload.shift()) requested = Math.min(requested, freeInventoryCapacity(serverPlayer, selectedKey.toStack(1)));
				if (requested <= 0) return;
				transfer.beginExtract(() -> fingerprint, requested);
				transfer.playerContext(serverPlayer.getUUID(), !payload.shift());
				host.productivebeesgenesis$markAe2StateChanged();
				long extracted = meStorage.extract(selectedKey, requested, Actionable.MODULATE, ACTION_SOURCE);
				transfer.extracted(extracted);
				if (extracted > 0) deliverKnownToPlayer(serverPlayer, transfer, selectedKey, payload.shift());
			}
			transfer.finish(pending, now, true);
		} catch (RuntimeException | LinkageError error) {
			LogThrottle.error("ae2_player_transfer",
					"AE2 玩家交接待核验 pos={} player={} request={} phase={}: {}",
					payload.pos(), serverPlayer.getUUID(), transfer.request(), transfer.destination(), error.toString());
			serverPlayer.displayClientMessage(Component.translatable(
					"command.productivebeesgenesis.output.failed", "AE2 transfer requires reconciliation"), true);
		} finally {
			host.productivebeesgenesis$markAe2StateChanged();
			holder.invalidateInputInventoryViewCache();
		}
		Ae2FilterPayloadHandlers.syncFilterToClient(be, serverPlayer);
	}

	private static void deliverKnownToPlayer(ServerPlayer player,
			Ae2InputTransfer transfer, AEItemKey key, boolean inventory) {
		int amount = (int) transfer.knownAmount();
		if (amount <= 0) return;
		ItemStack delivered = key.toStack(amount);
		if (inventory) {
			PlayerInventoryTransfer.Plan plan = PlayerInventoryTransfer.prepare(player, List.of(delivered));
			if (plan == null) return;
			transfer.offerToPlayer(amount);
			transfer.accepted(plan.commit() ? amount : 0);
		} else {
			ItemStack current = player.containerMenu.getCarried();
			if (!current.isEmpty() && (!ItemStack.isSameItemSameComponents(current, delivered)
					|| current.getCount() > current.getMaxStackSize() - amount)) return;
			transfer.offerToPlayer(amount);
			player.containerMenu.setCarried(delivered.copyWithCount(current.getCount() + amount));
			transfer.accepted(amount);
		}
	}

	/**
	 * 计算玩家背包（36 主槽）可容纳指定物品的数量
	 * <br/>
	 * 遍历主背包与快捷栏：空槽按整组容量计，同物品同组件槽按剩余空间计。
	 *
	 * @param player 服务器玩家
	 * @param probe  用于比较的物品栈（count 无关）
	 * @return 可容纳总数（0 表示无空间）
	 */
	private static int freeInventoryCapacity(net.minecraft.world.entity.player.Player player,
			net.minecraft.world.item.ItemStack probe) {
		net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
		int capacity = 0;
		// 只遍历主背包 items（36 槽）：Inventory.add() 只向主背包槽放入物品，
		// 不包含盔甲（4）与副手（1），遍历 getContainerSize()（41）会高估容量
		for (int i = 0; i < inventory.items.size(); i++) {
			net.minecraft.world.item.ItemStack slotStack = inventory.getItem(i);
			if (slotStack.isEmpty()) {
				capacity += probe.getMaxStackSize();
			} else if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(slotStack, probe)) {
				capacity += Math.max(0, slotStack.getMaxStackSize() - slotStack.getCount());
			}
			if (capacity >= probe.getMaxStackSize()) {
				break;
			}
		}
		return capacity;
	}
}
