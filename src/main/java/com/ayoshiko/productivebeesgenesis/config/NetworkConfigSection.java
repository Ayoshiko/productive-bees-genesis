package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 服务端网络开关及共享工作预算；原生配置界面自动显示同一 spec。 */
public final class NetworkConfigSection {
	public final ModConfigSpec.BooleanValue enabled;
	public final ModConfigSpec.IntValue topologyNodes;
	public final ModConfigSpec.IntValue topologyMicros;
	public final ModConfigSpec.LongValue energyCapacity;
	NetworkConfigSection(ModConfigSpec.Builder builder) {
		builder.translation("productivebeesgenesis.configuration.bee_network").push("bee_network");
		enabled = builder.translation("productivebeesgenesis.configuration.bee_network.enabled").comment("启用蜂业控制核心；关闭后托管成员继续暂停，不自动恢复独立生产。").define("enabled", false);
		energyCapacity = builder.translation("productivebeesgenesis.configuration.bee_network.energyCapacity").comment("核心共享 FE 容量；降低到现存余额以下时保留原容量，待余额降低后应用。").defineInRange("energyCapacity", 64_000_000L, 1L, Long.MAX_VALUE);
		topologyNodes = builder.translation("productivebeesgenesis.configuration.bee_network.topologyNodes").comment("每个服务器 tick 共享的拓扑候选节点预算。").defineInRange("topologyNodes", 256, 1, 4096);
		topologyMicros = builder.translation("productivebeesgenesis.configuration.bee_network.topologyMicros").comment("拓扑扫描软时间预算，单位微秒；单次节点读取不可抢占。").defineInRange("topologyMicros", 2000, 100, 10000);
		builder.pop();
	}
}
