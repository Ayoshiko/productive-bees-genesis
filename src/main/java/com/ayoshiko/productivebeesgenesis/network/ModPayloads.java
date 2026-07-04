package com.ayoshiko.productivebeesgenesis.network;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 模组网络数据包注册与服务端处理（Task 12）
 * <p>
 * 职责：
 * <ol>
 *   <li>注册 {@link FilterConfigSyncPayload} 为 play 阶段 Client→Server 数据包</li>
 *   <li>服务端处理：权限校验 → 数据校验 → 写入配置 → 持久化 → 失效缓存</li>
 * </ol>
 * 原理：NeoForge 的 SERVER 配置在客户端是只读同步副本，客户端修改不会回传服务端。
 * 通过自定义数据包将修改请求发送到服务端，服务端写入后由 NeoForge 原生 ConfigSync
 * 自动同步到所有客户端。单机模式下集成服务器同样走此流程（内存连接，无额外网络开销）。
 * <p>
 * 注：NeoForge 1.21.1 的 {@code @EventBusSubscriber} 会根据事件类型自动判定总线，
 * {@code RegisterPayloadHandlersEvent} 实现 {@code IModBusEvent}，自动挂载到模组事件总线。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ModPayloads {

	/** 修改服务端配置所需权限等级（与原版配置界面一致：OP 2 级） */
	private static final int REQUIRED_PERMISSION_LEVEL = 2;

	private ModPayloads() {
	}

	/**
	 * 注册数据包 — 由 {@code @EventBusSubscriber} 自动挂载到模组事件总线
	 */
	@SubscribeEvent
	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1");
		registrar.playToServer(
				FilterConfigSyncPayload.TYPE,
				FilterConfigSyncPayload.STREAM_CODEC,
				ModPayloads::handleFilterConfigSync
		);
	}

	/**
	 * 服务端处理：万象创世过滤配置同步
	 * <p>
	 * 默认在主线程执行（{@link PayloadRegistrar} 默认 {@code HandlerThread.MAIN}），
	 * 保证配置写入与 {@code ModConfigEvent} 事件回调的线程一致性。
	 * <p>
	 * 处理流程：
	 * <ol>
	 *   <li>权限校验 — 非 OP 玩家拒绝，发送聊天提示</li>
	 *   <li>配置加载状态校验 — SERVER_SPEC 未加载时拒绝</li>
	 *   <li>过滤模式校验 — 枚举名称必须可解析</li>
	 *   <li>蜜蜂类型列表校验 — 逐条 ResourceLocation 格式校验 + 去重</li>
	 *   <li>写入配置 + spec.save() 持久化</li>
	 *   <li>失效过滤缓存 — 让下次 tick 重建反映最新配置</li>
	 * </ol>
	 * NeoForge 原生 ConfigSync 会在 spec.save() 后自动将变更同步到所有客户端。
	 */
	private static void handleFilterConfigSync(FilterConfigSyncPayload payload, IPayloadContext context) {
		// 仅服务端处理（playToServer 保证方向，防御性 instanceof 检查）
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}

		// 1. 权限校验
		if (!serverPlayer.hasPermissions(REQUIRED_PERMISSION_LEVEL)) {
			serverPlayer.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.config.sync.permission_denied"));
			ProductiveBeesGenesis.LOGGER.warn("玩家 {} 尝试修改服务端过滤配置但权限不足",
					serverPlayer.getName().getString());
			return;
		}

		// 2. 配置加载状态校验（防御性，理论上 join 后已加载）
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			serverPlayer.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.config.sync.not_loaded"));
			ProductiveBeesGenesis.LOGGER.warn("收到过滤配置同步包但 SERVER_SPEC 未加载");
			return;
		}

		// 3. 过滤模式校验
		ModConfig.FilterMode filterMode;
		try {
			filterMode = ModConfig.FilterMode.valueOf(payload.filterModeName());
		} catch (IllegalArgumentException e) {
			serverPlayer.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.config.sync.invalid_mode", payload.filterModeName()));
			ProductiveBeesGenesis.LOGGER.warn("收到无效的过滤模式: {}", payload.filterModeName());
			return;
		}

		// 4. 蜜蜂类型列表校验（格式 + 去重）
		List<String> validated = validateAndDeduplicate(payload.beeTypes(), serverPlayer);
		if (validated == null) {
			// 校验失败已通过 sendSystemMessage 通知玩家
			return;
		}

		// 5. 写入配置并持久化
		try {
			ModConfig.SERVER.myriadCreationsFilteredBeeTypes.set(validated);
			ModConfig.SERVER.myriadCreationsFilterMode.set(filterMode);
			ModConfig.SERVER_SPEC.save();
			// 6. 失效过滤缓存
			MyriadCreationsEventHandler.invalidateFilterCache();
			ProductiveBeesGenesis.LOGGER.info("已通过同步包保存万象创世过滤配置：模式={}, 条目数={}",
					filterMode, validated.size());
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("处理过滤配置同步包时发生异常", e);
			serverPlayer.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.config.sync.error"));
		}
	}

	/**
	 * 校验蜜蜂类型列表并去重
	 *
	 * @param beeTypes     待校验列表
	 * @param serverPlayer 用于发送错误提示的玩家
	 * @return 校验通过的去重列表；校验失败返回 null（已发送错误提示）
	 */
	private static List<String> validateAndDeduplicate(List<String> beeTypes, ServerPlayer serverPlayer) {
		// 使用 LinkedHashSet 去重：O(1) 查询替代 ArrayList.contains() 的 O(N)，整体从 O(N²) 降为 O(N)
		// 同时保持插入顺序（与原 ArrayList 行为一致），最后转为 ArrayList 返回保持可变性
		LinkedHashSet<String> validated = new LinkedHashSet<>(beeTypes.size());
		for (String raw : beeTypes) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String trimmed = raw.trim();
			if (!ModConfig.isValidBeeTypeEntry(trimmed)) {
				serverPlayer.sendSystemMessage(Component.translatable(
						"productivebeesgenesis.config.sync.invalid_type", trimmed));
				ProductiveBeesGenesis.LOGGER.warn("收到无效的蜜蜂类型: {}", trimmed);
				return null;
			}
			validated.add(trimmed);
		}
		return new ArrayList<>(validated);
	}
}
