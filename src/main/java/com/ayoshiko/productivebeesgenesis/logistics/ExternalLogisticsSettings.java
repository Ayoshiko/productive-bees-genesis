package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 外部物流互操作设置。
 * <p>
 * 本模组的弹出行为<b>固定为最大速度</b>：多流体槽按刻轮转、输出边沿唤醒、一次遍历送完全部产物、
 * 阻塞自适应退避，全部内置常量，不再暴露为配置（早期为缓解 Mekanism 弹出器性能而堆积的
 * 十余个节流参数已全部移除）。这里只缓存唯一一个会改变产物落点、需要玩家决定的开关：
 * 产物直通。
 * <p>
 * <b>为什么要缓存：</b>该开关会在配方提交路径上被读取，时间加速下每真实刻可达上千次；
 * 直读 NeoForge 配置的累计开销可观。100 刻 CAS 缓存 + volatile 发布，配置重载时立即失效。
 *
 * @since 2.1.0
 */
public final class ExternalLogisticsSettings {

	/** 输出「空→非空」两次唤醒之间的最小间隔（tick） */
	public static final int NEIGHBOR_WAKE_MIN_INTERVAL = 4;

	/** 配置缓存刷新间隔（tick） */
	private static final int CONFIG_REFRESH_INTERVAL = 100;

	/** 上次刷新配置的游戏刻 */
	private static final AtomicLong LAST_REFRESH_TICK = new AtomicLong(-CONFIG_REFRESH_INTERVAL);

	/** 产物直通开关（默认与配置默认值一致） */
	private static volatile boolean directContainerOutput = true;

	private ExternalLogisticsSettings() {
	}

	/**
	 * 产物是否直通相邻容器（跳过输出槽缓存）。
	 *
	 * @param gameTime 当前游戏刻（驱动缓存刷新）
	 * @return true 表示启用直通
	 */
	public static boolean directContainerOutput(long gameTime) {
		refresh(gameTime);
		return directContainerOutput;
	}

	/** 强制下次读取重新加载配置（配置重载时调用）。 */
	public static void invalidate() {
		LAST_REFRESH_TICK.set(-CONFIG_REFRESH_INTERVAL);
	}

	private static void refresh(long gameTime) {
		long lastRefresh = LAST_REFRESH_TICK.get();
		if (gameTime - lastRefresh < CONFIG_REFRESH_INTERVAL) return;
		// CAS 失败说明其他线程已经推进过时间戳，本线程直接读旧值即可
		if (!LAST_REFRESH_TICK.compareAndSet(lastRefresh, gameTime)) return;
		try {
			directContainerOutput = ModConfig.SERVER.externalDirectContainerOutput.get();
		} catch (NullPointerException | IllegalStateException e) {
			// 构造早期/切换存档瞬间配置可能未加载：保留上次缓存值
			DevLog.warn("external_logistics", "服务端配置未加载，沿用外部物流缓存设置");
		}
	}
}
