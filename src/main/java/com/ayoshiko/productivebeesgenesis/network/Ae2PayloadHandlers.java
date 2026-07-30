package com.ayoshiko.productivebeesgenesis.network;

import java.util.ArrayList;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.base.TileEntityMekanism;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
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
	private static boolean validateContainerMatch(ServerPlayer serverPlayer,
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
		syncFilterToClients(be);
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
		if (payload.slotIndex() < 0 || payload.slotIndex() > NetworkSecurityConstants.MAX_AE_INPUT_FILTER_SLOTS) return;
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.level() == null) {
			return;
		}
		// 强制容器一致性校验：防止 IDOR 攻击（特别重要：CLEAR 操作可清空他人过滤器）
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_input_filter_entry_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_filter_set", NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
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
		// 防御性字符串长度校验：StreamCodec 已限制 256 字节，此处冗余校验防止协议层变更后绕过
		if (beeType != null && beeType.toString().length() > NetworkSecurityConstants.MAX_BEE_TYPE_KEY_LENGTH) {
			LogThrottle.warn("ae2_filter_bee_too_long", "玩家 {} 尝试设置过长的蜜蜂类型键：长度 {}",
					serverPlayer.getName().getString(), beeType.toString().length());
			return;
		}
		switch (payload.operation()) {
			// V15: setEntryAt 直接覆盖目标位置（位置固定语义，拖到哪格放哪格）
			case ADD -> filter.setEntryAt(payload.slotIndex(), beeType, payload.isBlock());
			case REMOVE -> filter.removeEntryAt(payload.slotIndex());
			case CLEAR -> filter.clearEntries();
		}
		// 标记 dirty 确保过滤器条目修改持久化
		if (be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		// 推送完整条目列表到客户端（tracker 无法同步集合数据）
		syncFilterToClients(be);
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_input_precise_mode_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_precise_mode_toggle", NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
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
		syncFilterToClients(be);
	}

	/**
	 * 服务端处理：请求打开 per-tile AE2 输入配置窗口
	 * <br/>
	 * 校验玩家身份、8 格交互距离、方块实体是否为 {@link IAe2InputHost} 后，
	 * 调用 {@link #syncFilterToClients} 推送当前过滤器状态到客户端。
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
		if (!validateContainerMatch(serverPlayer, payload.pos(), "ae2_input_config_pos_mismatch")) {
			return;
		}
		// 频次限制：防止恶意客户端高频触发 syncFilterToClients 广播（流量放大攻击）
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_config_open", NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
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
		// 推送当前过滤器状态到客户端，确保打开 Screen 时显示最新数据
		// syncFilterToClients 内部会再次校验 be instanceof IAe2InputHost，此处显式校验仅为防御性编程
		syncFilterToClients(be);
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
		// 清空并按 index 重建客户端过滤器条目（保留位置固定语义）
		filter.clearEntries();
		List<Integer> indices = payload.indices();
		List<String> entries = payload.entries();
		// 平行数组防御性校验：防止恶意服务端发送不一致的 indices/entries
		if (indices.size() != entries.size()) return;
		int count = Math.min(indices.size(), entries.size());
		for (int i = 0; i < count; i++) {
			int idx = indices.get(i);
			String entry = entries.get(i);
			// 防御性校验：防止恶意服务端发送越界索引（与 handleSetAeInputFilterEntry 上限一致）
			if (idx < 0 || idx > NetworkSecurityConstants.MAX_AE_INPUT_FILTER_SLOTS) continue;
			// 防御性校验：防止恶意服务端推送过长条目占用客户端内存（StreamCodec 已限制 256 字符，冗余校验）
			if (entry != null && entry.length() > NetworkSecurityConstants.MAX_FILTER_ENTRY_LENGTH) {
				LogThrottle.warn("ae2_sync_entry_too_long", "收到服务端推送的过长过滤条目：长度 {}", entry.length());
				continue;
			}
			filter.setEntryAtIndex(idx, entry);
		}
		// 设置 filterMode（幂等，与 SyncableInt 的 setter 重复设置安全）
		Ae2InputFilter.FilterMode[] modes = Ae2InputFilter.FilterMode.values();
		if (payload.filterMode() >= 0 && payload.filterMode() < modes.length) {
			filter.setFilterMode(modes[payload.filterMode()]);
		}
		// V13: 同步 preciseMode
		filter.setPreciseMode(payload.preciseMode());
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
	public static void syncFilterToClients(BlockEntity be) {
		if (be == null || be.getLevel() == null || be.getLevel().isClientSide) {
			return;
		}
		if (!(be instanceof IAe2InputHost host)) {
			return;
		}
		Ae2InputFilter filter = host.productivebeesgenesis$getAeInputFilter();
		if (filter == null) {
			return;
		}
		// V15: 使用非空条目（含 index），构建平行数组同步位置信息
		List<Ae2InputFilter.IndexedEntry> nonEmpty = filter.getNonEmptyEntries();
		List<Integer> indices = new ArrayList<>(nonEmpty.size());
		List<String> entries = new ArrayList<>(nonEmpty.size());
		for (Ae2InputFilter.IndexedEntry ie : nonEmpty) {
			indices.add(ie.index());
			entries.add(ie.entry());
		}
		SyncAeInputFilterEntriesPayload payload = new SyncAeInputFilterEntriesPayload(
				be.getBlockPos(),
				filter.getFilterMode().ordinal(),
				filter.isPreciseMode(),
				indices,
				entries
		);
		// Task 6：定向发送给跟踪该区块的玩家，避免全服广播
		net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) be.getLevel();
		net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(be.getBlockPos());
		PacketDistributor.sendToPlayersTrackingChunk(serverLevel, chunkPos, payload);
	}
}
