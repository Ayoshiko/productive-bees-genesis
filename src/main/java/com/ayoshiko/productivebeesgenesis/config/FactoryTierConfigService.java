package com.ayoshiko.productivebeesgenesis.config;

/** 发布当前世界或远端连接首次完整同步的容量快照，普通配置重载不改变已冻结容量。 */
public final class FactoryTierConfigService {

	private static volatile FactoryTierConfigSnapshot current = FactoryTierConfigSnapshot.defaults();

	private FactoryTierConfigService() {
	}

	static void publish(FactoryTierConfigSnapshot snapshot) {
		current = snapshot;
	}

	public static FactoryTierConfigSnapshot current() {
		return current;
	}

	/** 会话终止或重新连接时撤销上一份容量镜像。 */
	public static void resetToDefaults() {
		current = FactoryTierConfigSnapshot.defaults();
	}
}
