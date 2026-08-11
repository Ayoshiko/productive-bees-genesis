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
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2NetworkInventoryView;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerPlayer;
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
	 * 安全模型：每个 handler 均校验玩家身份（{@link ServerPlayer}）、
	 * 方块实体类型与 8 格 GUI 交互距离（8² = 64），防止恶意客户端远距离操作。
	 * <p>
	 * 依赖倒置：通过 {@link IAe2InputHost} / {@link IAe2OutputHostBase} 接口多态判定，
	 * 避免直接引用 ME/EME 可选依赖的具体子类（其中 IAe2InputHost 由 Mixin 运行时注入）。
	 */
final class Ae2PayloadHandlers {
	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2PayloadHandlers() {
	}

	/**
	 * 容器位置一致性校验 — 防止 IDOR 类权限提升
	 * <br/>
	 * 玩家当前打开的 containerMenu 必须是 MekanismTileContainer 且其绑定的方块坐标
	 * 与 payload.pos() 一致。防止玩家打开任意容器（如工作台、背包）后，
	 * 在 8 格交互距离内远程操作他人方块的 AE2 开关与过滤器（IDOR 攻击）。
	 * <p>
	 * 与 {@link ApiaryPayloadHandlers} 保持一致的强制校验策略。
	 *
	 * @param serverPlayer 服务端玩家
	 * @param targetPos    payload 中的目标方块坐标
	 * @param logKey       日志节流键
	 * @return true 表示校验通过，false 表示校验失败（调用方应直接 return）
	 */
	static boolean validateContainerMatch(ServerPlayer serverPlayer,
			net.minecraft.core.BlockPos targetPos, String logKey) {
		if (serverPlayer.containerMenu == null) return false;
		if (!(serverPlayer.containerMenu instanceof MekanismTileContainer<?> tileContainer)) {
			return false;
		}
		if (!tileContainer.getTileEntity().getBlockPos().equals(targetPos)) {
			LogThrottle.warn(logKey,
					"玩家 {} 当前打开容器位置 {} 与目标 AE2 操作方块位置 {} 不一致",
					serverPlayer.getName().getString(),
					tileContainer.getTileEntity().getBlockPos(),
					targetPos);
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_output_pos_mismatch")) {
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_input_pull_pos_mismatch")) {
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "smelting_compat_pos_mismatch")) {
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_input_nbt_pos_mismatch")) {
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_input_filter_mode_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_filter_cycle", NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_output_slot_pos_mismatch")) return;
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
		net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) serverPlayer.level();
		long now = sl.getGameTime();
		boolean ignoreNbt = holder.isAeInputNbtIgnore();
		long visible = Ae2NetworkInventoryView.visibleAmount(holder, now,
				storageService.getCachedInventory(), meStorage, key, Long.MAX_VALUE, ACTION_SOURCE);
		long cap = filter.getDirectPullLimit(key, visible, ignoreNbt,
				serverPlayer.level().registryAccess());
		if (cap >= 0L) visible = Math.min(visible, cap);
		if (payload.shift()) {
			// Quick move: extract up to a full stack into the player inventory.
			long maxExtract = Math.min(visible, key.getMaxStackSize());
			if (maxExtract > 0L) {
				long extracted = meStorage.extract(key, maxExtract, Actionable.MODULATE, ACTION_SOURCE);
				if (extracted > 0L) {
					net.minecraft.world.item.ItemStack stack = key.toStack(
							(int) Math.min(extracted, Integer.MAX_VALUE));
					if (!serverPlayer.getInventory().add(stack)) {
						serverPlayer.drop(stack, false);
					}
				}
			}
		} else {
			net.minecraft.world.item.ItemStack carried = serverPlayer.containerMenu.getCarried();
			if (carried.isEmpty()) {
				// Extract into the cursor (right click = half).
				long maxExtract = Math.min(visible, key.getMaxStackSize());
				if (payload.rightClick()) maxExtract = Math.max(1L, (maxExtract + 1L) / 2L);
				if (maxExtract > 0L) {
					long extracted = meStorage.extract(key, maxExtract, Actionable.MODULATE, ACTION_SOURCE);
					if (extracted > 0L) {
						serverPlayer.containerMenu.setCarried(key.toStack(
								(int) Math.min(extracted, Integer.MAX_VALUE)));
					}
				}
			} else {
				// Insert the carried stack into the network (right click = one).
				AEItemKey carriedKey = AEItemKey.of(carried);
				if (carriedKey == null) return;
				int toInsert = payload.rightClick() ? 1 : carried.getCount();
				long inserted = meStorage.insert(carriedKey, toInsert, Actionable.MODULATE, ACTION_SOURCE);
				if (inserted > 0L) {
					carried.shrink((int) inserted);
					if (carried.isEmpty()) {
						serverPlayer.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
					}
				}
			}
		}
		// Refresh the GUI stock display after a successful mutation.
		Ae2FilterPayloadHandlers.syncFilterToClient(be, serverPlayer);
	}
}
