package com.ayoshiko.productivebeesgenesis.network;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.helpers.BaseActionSource;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2FilterProcessabilityView;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ItemFingerprint;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2NetworkInventoryView;
import com.ayoshiko.productivebeesgenesis.mek.ae2.CombFuzzyMatcher;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * AE2 输入过滤器相关数据包的服务端/客户端处理集合
 * <br/>
 * 从 {@link Ae2PayloadHandlers} 拆分而来，职责（SRP）：仅处理过滤器条目的
 * 增删改查、精确模式切换、拉取数量编辑、配置窗口状态同步与客户端快照应用。
 * <p>
 * 安全模型与 {@link Ae2PayloadHandlers} 一致：每个 handler 均校验玩家身份、
 * 当前打开容器与目标方块一致（防 IDOR）、方块实体类型与 8 格交互距离，
 * 并对触发广播的请求施加频次限制。
 */
final class Ae2FilterPayloadHandlers {

	private static final IActionSource ACTION_SOURCE = new BaseActionSource() {};

	private Ae2FilterPayloadHandlers() {
	}

	/** Applies one direct-entry pull amount or network-stock reserve after validating the active container. */
	static void handleSetAeInputFilterAmount(SetAeInputFilterAmountPayload payload, IPayloadContext context) {
		if (payload.slotIndex() < 0
				|| payload.slotIndex() >= NetworkSecurityConstants.MAX_AE_INPUT_FILTER_SLOTS
				|| payload.amount() < 0L
				|| payload.amount() > (payload.reserve()
						? Ae2InputFilter.MAX_DIRECT_RESERVE_AMOUNT : Ae2InputFilter.getMaxDirectAmount())) return;
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_filter_amount_pos_mismatch")) return;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_filter_amount",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;

		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)
				|| serverPlayer.distanceToSqr(payload.pos().getCenter())
						> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) return;
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null || !filter.isDirectEntry(payload.slotIndex())) return;

		if (payload.reserve()) {
			filter.setDirectReserveAmountAt(payload.slotIndex(), payload.amount());
		} else {
			filter.setDirectAmountAt(payload.slotIndex(), payload.amount());
		}
		if (be instanceof TileEntityMekanism mek) mek.markForSave();
		syncFilterToClient(be, serverPlayer);
	}

	/**
	 * 服务端处理：批量设置全部直连条目的单次拉取数量
	 * <br/>
	 * 由全局齿轮按钮（无需标记物品）调用，安全校验与单条数量编辑一致。
	 */
	static void handleSetAllAeInputFilterAmount(SetAllAeInputFilterAmountPayload payload, IPayloadContext context) {
		if (payload.amount() < 0L || payload.amount() > (payload.reserve()
				? Ae2InputFilter.MAX_DIRECT_RESERVE_AMOUNT : Ae2InputFilter.getMaxDirectAmount())) return;
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_all_amount_pos_mismatch")) return;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_all_amount",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)
				|| serverPlayer.distanceToSqr(payload.pos().getCenter())
						> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) return;
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) return;
		int changed;
		if (payload.reserve()) {
			long previous = filter.getGlobalReserveAmount();
			filter.setGlobalReserveAmount(payload.amount());
			changed = previous == filter.getGlobalReserveAmount() ? 0 : 1;
		} else {
			changed = filter.setAllDirectAmounts(payload.amount());
		}
		if (changed > 0 && be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		syncFilterToClient(be, serverPlayer);
	}

	/** 服务端处理：切换过滤后所有允许蜜脾的全量无限拉取。 */
	static void handleToggleAllAeInputFilterUnlimited(ToggleAllAeInputFilterUnlimitedPayload payload,
			IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_all_unlimited_pos_mismatch")) return;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_all_unlimited",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)
				|| serverPlayer.distanceToSqr(payload.pos().getCenter())
						> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) return;
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) return;
		filter.toggleUnlimitedAllFallback();
		if (be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		syncFilterToClient(be, serverPlayer);
	}

	/** Toggles the filter-level global stock mode without changing unlimited-pull flags. */
	static void handleToggleAllAeInputFilterNetworkStock(
			ToggleAllAeInputFilterNetworkStockPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_all_network_stock_pos_mismatch")) return;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_all_network_stock",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)
				|| serverPlayer.distanceToSqr(payload.pos().getCenter())
						> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) return;
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) return;
		filter.toggleGlobalNetworkStock();
		if (be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		syncFilterToClient(be, serverPlayer);
	}

	/**
	 * 服务端处理：添加、移除或清空 per-tile AE2 输入过滤条目
	 * <br/>
	 * 过滤器为 null 时（基础离心机未启用过滤）安全返回，不执行任何操作。
	 * CLEAR 操作忽略 beeType，清空所有条目。
	 * V15：ADD 使用 setEntryAt 直接覆盖目标位置（位置固定语义，不做交换），REMOVE 使用 removeEntryAt（仅清空不移位）。
	 * 修改后调用 markForSave 持久化，并推送 SyncAeInputFilterEntriesPayload 同步完整条目列表到客户端。
	 */
	static void handleSetAeInputFilterEntry(SetAeInputFilterEntryPayload payload, IPayloadContext context) {
		// slotIndex 边界校验：防止恶意客户端发送越界索引导致数组访问异常
		if (payload.slotIndex() < 0 || payload.slotIndex() >= NetworkSecurityConstants.MAX_AE_INPUT_FILTER_SLOTS) return;
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击（特别重要：CLEAR 操作可清空他人过滤器）
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_filter_entry_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		String rateLimitKey = payload.operation() == SetAeInputFilterEntryPayload.OperationType.TOGGLE_UNLIMITED
				|| payload.operation() == SetAeInputFilterEntryPayload.OperationType.TOGGLE_NETWORK_STOCK
				? "ae_input_filter_stock" : "ae_input_filter_set";
		if (!PayloadRateLimiter.tryAccept(serverPlayer, rateLimitKey,
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_input_filter_entry_distance", "玩家 {} 尝试远距离修改 AE2 输入过滤条目：距离 {} 格",
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
		ResourceLocation beeType = payload.beeType().orElse(null);
		String directFingerprint = payload.directKey().orElse(null);
		// Defensive length checks mirror the payload codecs.
		if (beeType != null && beeType.toString().length() > NetworkSecurityConstants.MAX_BEE_TYPE_KEY_LENGTH) {
			LogThrottle.warn("ae2_filter_bee_too_long", "玩家 {} 尝试设置过长的蜜蜂类型键：长度 {}",
					serverPlayer.getName().getString(), beeType.toString().length());
			return;
		}
		if (directFingerprint != null
				&& directFingerprint.length() > NetworkSecurityConstants.MAX_AE_ITEM_FINGERPRINT_LENGTH) {
			LogThrottle.warn("ae2_filter_direct_too_long", "AE2 direct item fingerprint is too long: {}",
				directFingerprint.length());
			return;
		}
		AEItemKey directKey = null;
		if (directFingerprint != null && !directFingerprint.isBlank()) {
			directKey = Ae2ItemFingerprint.decode(directFingerprint, serverPlayer.registryAccess());
			if (directKey == null) return;
			if (CombFuzzyMatcher.isCombItem(directKey)) {
				ResourceLocation actualBeeType = CombFuzzyMatcher.getBeeType(directKey);
				if (beeType == null || !beeType.equals(actualBeeType)
						|| payload.isBlock() != CombFuzzyMatcher.isCombBlock(directKey)) return;
				// 蜜脾保持 fuzzy 语义；兼容旧客户端同时发送 direct key 的请求。
				directFingerprint = null;
				directKey = null;
			} else {
				if (beeType != null || payload.isBlock() || !isSmeltingInput(serverPlayer, directKey)) return;
				directFingerprint = Ae2ItemFingerprint.encode(directKey, serverPlayer.registryAccess());
				if (directFingerprint.isBlank()
						|| directFingerprint.length() > NetworkSecurityConstants.MAX_AE_ITEM_FINGERPRINT_LENGTH) return;
			}
		}
		switch (payload.operation()) {
			// V15: setEntryAt 直接覆盖目标位置（位置固定语义，拖到哪格放哪格）
			case ADD -> {
				if (directFingerprint != null && !directFingerprint.isBlank()) {
					filter.setDirectEntryFingerprintAt(payload.slotIndex(), directFingerprint);
					if (directKey != null) filter.resolveDirectKey(payload.slotIndex(), directKey);
				} else if (beeType != null) {
					filter.setEntryAt(payload.slotIndex(), beeType, payload.isBlock());
				}
			}
			case REMOVE -> filter.removeEntryAt(payload.slotIndex());
			case CLEAR -> filter.clearEntries();
			case TOGGLE_UNLIMITED -> filter.toggleDirectUnlimitedAt(payload.slotIndex());
			case TOGGLE_NETWORK_STOCK -> filter.toggleDirectNetworkStockAt(payload.slotIndex());
		}
		// 标记 dirty 确保过滤器条目修改持久化
		if (be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		// 推送完整条目列表到客户端（tracker 无法同步集合数据）
		syncFilterToClient(be, serverPlayer);
	}

	private static boolean isSmeltingInput(ServerPlayer player, AEItemKey key) {
		try {
			return MekanismRecipeType.SMELTING.getInputCache().containsInput(player.level(), key.getReadOnlyStack());
		} catch (LinkageError | RuntimeException error) {
			LogThrottle.warn("ae2_filter_server_smelting",
					"服务端 AE2 过滤器 SMELTING 配方校验异常，拒绝 key={}: {}", key, error.toString());
			return false;
		}
	}

	/**
	 * 服务端处理：切换 per-tile AE2 输入精确模式
	 * <br/>
	 * 精确模式开启时区分蜜脾和蜜脾块（同种 beeType 但物品类型不同），
	 * 关闭时同种 beeType 的蜜脾和蜜脾块一起拉取。
	 */
	static void handleToggleAeInputPreciseMode(ToggleAeInputPreciseModePayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_precise_mode_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_precise_mode_toggle",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_input_exact_distance", "玩家 {} 尝试远距离切换 AE2 输入精确模式：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof IAe2InputHost host)) {
			return;
		}
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) {
			return;
		}
		filter.togglePreciseMode();
		if (be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		syncFilterToClient(be, serverPlayer);
	}

	/**
	 * 服务端处理：请求打开 per-tile AE2 输入配置窗口
	 * <br/>
	 * 校验玩家身份、8 格交互距离、方块实体是否为 {@link IAe2InputHost} 后，
	 * 推送当前过滤器状态到客户端。
	 * <p>
	 * <b>设计原因</b>：NeoForge 中服务端无法直接打开客户端 Screen，故客户端按钮点击时
	 * 已立即在客户端打开 {@link com.ayoshiko.productivebeesgenesis.client.screen.GuiAeInputConfig}，
	 * 此 handler 仅负责推送最新过滤器状态，确保客户端 Screen 显示与服务端一致。
	 * 客户端 Screen 通过 {@link SyncAeInputFilterEntriesPayload} 接收推送并刷新本地副本。
	 */
	static void handleOpenAeInputConfig(OpenAeInputConfigPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_config_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_config_open",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be == null) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("ae2_input_config_distance", "玩家 {} 尝试远距离打开 AE2 输入配置：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (!(be instanceof IAe2InputHost)) {
			return;
		}
		// Polling an open GUI only needs to update its owner, not every player tracking the chunk.
		syncFilterToClient(be, serverPlayer);
		// 同步标签过滤表达式：GUI 打开时客户端需要拿到当前表达式文本用于回显
		Ae2TagFilterPayloadHandlers.syncTagFilterToClient(be, serverPlayer);
	}

	/**
	 * 客户端处理：接收服务端推送的完整过滤器条目列表
	 * <br/>
	 * V15：indices + entries 为平行数组，仅包含非空槽位。
	 * 客户端先 clearEntries，再按 index 调用 setEntryAtIndex 保留位置固定语义。
	 * 同时同步 preciseMode 和 filterMode。
	 */
	static void handleSyncAeInputFilterEntries(SyncAeInputFilterEntriesPayload payload, IPayloadContext context) {
		if (context.player().level() == null) {
			return;
		}
		BlockEntity be = context.player().level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)) {
			return;
		}
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) {
			return;
		}
		List<Integer> indices = payload.indices();
		List<String> entries = payload.entries();
		List<Long> amounts = payload.amounts();
		List<Long> reserveAmounts = payload.reserveAmounts();
		List<Long> visibleAmounts = payload.visibleAmounts();
		List<Boolean> unlimited = payload.unlimited();
		List<Boolean> networkStock = payload.networkStock();
		List<Boolean> processable = payload.processable();
		// 平行数组防御性校验：防止恶意服务端发送不一致的 indices/entries
		if (indices.size() != entries.size() || amounts.size() != entries.size()
				|| reserveAmounts.size() != entries.size()
				|| visibleAmounts.size() != entries.size()
				|| unlimited.size() != entries.size()
				|| networkStock.size() != entries.size()
				|| processable.size() != entries.size()) return;
		int count = entries.size();
		for (int i = 0; i < count; i++) {
			int idx = indices.get(i);
			String entry = entries.get(i);
			// 防御性校验：防止恶意服务端发送越界索引（与 handleSetAeInputFilterEntry 上限一致）
			if (idx < 0 || idx >= NetworkSecurityConstants.MAX_AE_INPUT_FILTER_SLOTS) return;
			// Defensive length check mirrors the component-aware fingerprint codec limit.
			if (entry != null && entry.length() > NetworkSecurityConstants.MAX_FILTER_ENTRY_LENGTH) {
				LogThrottle.warn("ae2_sync_entry_too_long", "收到服务端推送的过长过滤条目：长度 {}", entry.length());
				return;
			}
		}
		Ae2InputFilter.FilterMode[] modes = Ae2InputFilter.FilterMode.values();
		if (payload.filterMode() < 0 || payload.filterMode() >= modes.length) return;
		filter.replaceClientSnapshot(modes[payload.filterMode()], payload.preciseMode(),
				indices, entries, amounts, reserveAmounts, visibleAmounts, unlimited, networkStock,
				processable,
				payload.unlimitedAllFallback(), payload.globalNetworkStock(), payload.globalReserveAmount());
	}

	/**
	 * 推送完整过滤器条目列表到所有客户端
	 * <br/>
	 * V15：使用 getNonEmptyEntries() 获取非空槽位（index + entry），
	 * 构建平行数组 indices + entries 同步位置信息，同步 preciseMode。
	 * <p>
	 * Task 6：使用 {@link PacketDistributor#sendToPlayersTrackingChunk} 定向发送给
	 * 跟踪该区块的玩家，替代 {@code sendToAllPlayers} 全服广播，减少无关玩家的网络包开销。
	 *
	 * @param be 目标方块实体，必须实现 {@link IAe2InputHost}
	 */
	private static SyncAeInputFilterEntriesPayload createFilterSyncPayload(BlockEntity be) {
		if (be == null || be.getLevel() == null || be.getLevel().isClientSide) {
			return null;
		}
		if (!(be instanceof IAe2InputHost host)) {
			return null;
		}
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) {
			return null;
		}
		var holder = host.productivebeesgenesis$getAe2StateHolder();
		var storageService = holder == null ? null : Ae2GridNodeManager.getCachedStorage(holder, host);
		KeyCounter cachedInventory = storageService == null ? null : storageService.getCachedInventory();
		var network = holder == null ? null : Ae2GridNodeManager.getCachedMeStorage(holder, host);

		List<Ae2InputFilter.IndexedEntry> nonEmpty = filter.getNonEmptyEntries();
		List<Integer> indices = new ArrayList<>(nonEmpty.size());
		List<String> entries = new ArrayList<>(nonEmpty.size());
		List<Long> amounts = new ArrayList<>(nonEmpty.size());
		List<Long> reserveAmounts = new ArrayList<>(nonEmpty.size());
		List<Long> visibleAmounts = new ArrayList<>(nonEmpty.size());
		List<Boolean> unlimited = new ArrayList<>(nonEmpty.size());
		List<Boolean> networkStock = new ArrayList<>(nonEmpty.size());
		List<Boolean> processable = new ArrayList<>(nonEmpty.size());
		List<Ae2InputFilter.DirectEntry> directEntries = filter.hasDirectEntries()
				? filter.getDirectEntries() : List.of();
		var resolvedKeys = Ae2ItemFingerprint.resolve(
				directEntries, cachedInventory, be.getLevel().registryAccess());
		for (Ae2InputFilter.DirectEntry direct : directEntries) {
			if (direct.key() != null) continue;
			AEItemKey resolved = resolvedKeys.get(direct.fingerprint());
			if (resolved != null) filter.resolveDirectKey(direct.index(), resolved);
		}

		for (Ae2InputFilter.IndexedEntry ie : nonEmpty) {
			indices.add(ie.index());
			entries.add(ie.entry());
			long amount = 0L;
			long reserveAmount = 0L;
			long visibleAmount = 0L;
			Ae2InputFilter.EntryInfo info = filter.getEntryAt(ie.index());
			if (info != null && info.directFingerprint != null) {
				amount = filter.getDirectAmountAt(ie.index());
				reserveAmount = filter.getDirectReserveAmountAt(ie.index());
				AEItemKey key = filter.getResolvedDirectKey(ie.index());
				if (key != null && cachedInventory != null) {
					visibleAmount = filter.isDirectNetworkStockAt(ie.index())
							? Ae2NetworkInventoryView.visibleAmount(holder, be.getLevel().getGameTime(),
									cachedInventory, network, key, Long.MAX_VALUE, ACTION_SOURCE)
							: Ae2NetworkInventoryView.cachedAmount(cachedInventory, key, Long.MAX_VALUE);
				}
			}
			amounts.add(Math.max(0L, amount));
			reserveAmounts.add(Math.max(0L, reserveAmount));
			visibleAmounts.add(Math.max(0L, visibleAmount));
			unlimited.add(filter.isDirectUnlimitedAt(ie.index()));
			networkStock.add(filter.isDirectNetworkStockAt(ie.index()));
			// 本机可加工性：与拉取器同一判定入口（宿主 canProcessInput），供界面灰显提示。
			// 必须在服务端算：客户端的万象 bee_type 缓存与 per-tile 熔炼开关未必同步，
			// 自行判定会给出与实际拉取行为不一致的假红。
			processable.add(Ae2FilterProcessabilityView.canProcess(host, filter, ie.index()));
		}
		return new SyncAeInputFilterEntriesPayload(
				be.getBlockPos(),
				filter.getFilterMode().ordinal(),
				filter.isPreciseMode(),
				indices,
				entries,
				amounts,
				reserveAmounts,
				visibleAmounts,
				unlimited,
				networkStock,
				processable,
				filter.isUnlimitedAllFallback(), filter.isGlobalNetworkStock(), filter.getGlobalReserveAmount());
	}

	static void syncFilterToClient(BlockEntity be, ServerPlayer player) {
		SyncAeInputFilterEntriesPayload payload = createFilterSyncPayload(be);
		if (payload != null) PacketDistributor.sendToPlayer(player, payload);
	}

	public static void syncFilterToClients(BlockEntity be) {
		SyncAeInputFilterEntriesPayload payload = createFilterSyncPayload(be);
		if (payload == null) return;
		// Task 6：定向发送给跟踪该区块的玩家，避免全服广播
		net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) be.getLevel();
		net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(be.getBlockPos());
		PacketDistributor.sendToPlayersTrackingChunk(serverLevel, chunkPos, payload);
	}
}
