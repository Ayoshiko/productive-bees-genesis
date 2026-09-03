package com.ayoshiko.productivebeesgenesis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/** 原子发布工厂等级倍率快照，隔离持久化配置与运行时读取。 */
public final class FactoryTierConfigService {

	private static final Logger LOGGER = LoggerFactory.getLogger(
			"ProductiveBeesGenesis/FactoryTierConfig");
	private static final AtomicReference<FactoryTierConfigSnapshot> CURRENT =
			new AtomicReference<>(FactoryTierConfigSnapshot.defaults());

	private FactoryTierConfigService() {
	}

	/** 在服务端配置首次加载时构建并原子替换完整快照。 */
	public static void load(ServerConfig config) {
		try {
			CURRENT.set(FactoryTierConfigSnapshot.from(config));
		} catch (RuntimeException exception) {
			CURRENT.set(FactoryTierConfigSnapshot.defaults());
			LOGGER.error("工厂等级倍率快照加载失败，当前游戏会话回退到默认值", exception);
		}
	}

	public static FactoryTierConfigSnapshot current() {
		return CURRENT.get();
	}

	/** 服务端停止时清除存档级快照，防止跨存档保留旧值。 */
	public static void resetToDefaults() {
		CURRENT.set(FactoryTierConfigSnapshot.defaults());
	}
}
