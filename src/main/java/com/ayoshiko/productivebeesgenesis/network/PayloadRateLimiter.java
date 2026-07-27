package com.ayoshiko.productivebeesgenesis.network;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * 网络包频次限制器 — 防止恶意客户端高频触发服务端广播
 * <br/>
 * 针对 {@link Ae2PayloadHandlers} 中触发 {@code syncFilterToClients} 广播的 payload，
 * 按 per-player per-payload-type 维度限制最小调用间隔。
 * <br/>
 * <b>设计原因</b>：恶意客户端可高频发送触发服务端全量广播的 payload，
 * 导致流量放大攻击（一个客户端请求 → 服务端向所有在线玩家广播）。
 * 500ms 间隔足以满足正常 GUI 交互响应，同时阻止恶意高频请求。
 * <p>
 * <b>数据结构</b>：使用 {@link ConcurrentHashMap} 嵌套结构，外层 key 为玩家 UUID，
 * 内层 key 为限制器标识。per-key 结构使不同 payload 类型独立限流，
 * 允许玩家在 500ms 内连续执行不同类型的 GUI 操作（如先添加条目再切换模式）。
 * <p>
 * <b>线程安全</b>：服务端网络包处理在主线程执行，{@link ConcurrentHashMap} 作为
 * 可见性与并发安全冗余，与 {@link com.ayoshiko.productivebeesgenesis.util.LogThrottle} 的设计风格一致。
 * <p>
 * <b>内存管理</b>：服务器停止时通过 {@link #clearAll()} 清理整个映射，
 * 防止跨存档数据残留。
 *
 * @since 1.0.0
 */
public final class PayloadRateLimiter {

	/**
	 * 外层映射：玩家 UUID → (限制器 key → 上次接受时间戳)
	 * <br/>
	 * 内层使用独立 {@link ConcurrentHashMap} 保证 per-key 读写原子性。
	 */
	private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> LAST_ACCEPT = new ConcurrentHashMap<>();

	private PayloadRateLimiter() {
	}

	/**
	 * 检查并更新玩家的频次限制
	 * <br/>
	 * 若距离上次同一 key 的请求时间小于 {@code intervalMs}，则拒绝（返回 false）；
	 * 否则更新时间戳并允许通过（返回 true）。
	 *
	 * @param player     发起请求的服务端玩家
	 * @param key        限制器 key（如 "ae_input_filter_set"），不同 key 独立限流
	 * @param intervalMs 最小调用间隔（毫秒）
	 * @return true 如果允许通过，false 如果被节流
	 */
	public static boolean tryAccept(ServerPlayer player, String key, long intervalMs) {
		ConcurrentHashMap<String, Long> playerMap = LAST_ACCEPT.computeIfAbsent(
				player.getUUID(), k -> new ConcurrentHashMap<>());
		// 使用 compute 原子更新，消除 get + put 之间的 TOCTOU 窗口
		// 闭包内 now 取值与判定在同一原子段内完成，并发请求不会同时通过校验
		boolean[] allowed = {false};
		playerMap.compute(key, (k, old) -> {
			long now = System.currentTimeMillis();
			if (old != null && (now - old) < intervalMs) {
				allowed[0] = false;
				return old;
			}
			allowed[0] = true;
			return now;
		});
		return allowed[0];
	}

	/**
	 * 清理指定玩家的全部频次记录
	 * <br/>
	 * 玩家下线时调用，避免长期运行服务器的映射累积。
	 *
	 * @param playerId 玩家 UUID
	 */
	public static void clear(UUID playerId) {
		LAST_ACCEPT.remove(playerId);
	}

	/**
	 * 玩家登出事件清理入口（语义化别名，委托至 {@link #clear}）
	 * <br/>
	 * 由 {@code PlayerEvent.PlayerLoggedOutEvent} 监听器调用，
	 * 方法名显式表达"玩家登出"语义，便于在事件处理类中识别清理意图。
	 * 与 {@link ModPayloads#clearFilterSyncRateLimit} 配合，统一在玩家退出时释放频次限制状态。
	 *
	 * @param playerId 玩家 UUID
	 */
	public static void onPlayerLogout(UUID playerId) {
		clear(playerId);
	}

	/**
	 * 清理全部玩家的频次记录
	 * <br/>
	 * 服务器停止时调用，防止跨存档数据残留。
	 */
	public static void clearAll() {
		LAST_ACCEPT.clear();
	}
}
