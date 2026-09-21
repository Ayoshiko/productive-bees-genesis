package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 服务端网络开关及共享工作预算；原生配置界面自动显示同一 spec。 */
public final class NetworkConfigSection {
	public final ModConfigSpec.BooleanValue enabled;
	public final ModConfigSpec.IntValue topologyNodes;
	public final ModConfigSpec.IntValue topologyMicros;
	public final ModConfigSpec.LongValue energyCapacity;
	public final ModConfigSpec.IntValue runtimeSteps;
	public final ModConfigSpec.IntValue runtimeMicros;
	public final ModConfigSpec.IntValue totalSteps;
	public final ModConfigSpec.IntValue totalMicros;
	NetworkConfigSection(ModConfigSpec.Builder builder) {
		builder.translation("productivebeesgenesis.configuration.bee_network").push("bee_network");
		enabled = builder.translation("productivebeesgenesis.configuration.bee_network.enabled").comment("启用蜂业控制核心；关闭后托管成员继续暂停，不自动恢复独立生产。").define("enabled", false);
		totalSteps = builder.translation("productivebeesgenesis.configuration.bee_network.totalSteps").comment("全服网络加载、拓扑、交接和生产共同消费的每 tick 检查次数；包含空队列检查。").defineInRange("totalSteps", 512, 1, 8192);
		totalMicros = builder.translation("productivebeesgenesis.configuration.bee_network.totalMicros").comment("全服网络后台工作总软时间预算，单位微秒；跨子系统轮转，单步不可抢占，不包含世界保存和停服等待。").defineInRange("totalMicros", 4000, 100, 20000);
		energyCapacity = builder.translation("productivebeesgenesis.configuration.bee_network.energyCapacity").comment("核心共享 FE 容量；降低到现存余额以下时保留原容量，待余额降低后应用。").defineInRange("energyCapacity", 64_000_000L, 1L, Long.MAX_VALUE);
		runtimeSteps = builder.translation("productivebeesgenesis.configuration.bee_network.runtimeSteps").comment("全服务器每个真实 tick 共享的生产调度步数；包含成员发现与激活。").defineInRange("runtimeSteps", 256, 1, 4096);
		runtimeMicros = builder.translation("productivebeesgenesis.configuration.bee_network.runtimeMicros").comment("全服务器生产调度软时间预算，单位微秒；单步不可抢占。").defineInRange("runtimeMicros", 2000, 100, 10000);
		topologyNodes = builder.translation("productivebeesgenesis.configuration.bee_network.topologyNodes").comment("每个服务器 tick 共享的拓扑候选节点预算。").defineInRange("topologyNodes", 256, 1, 4096);
		topologyMicros = builder.translation("productivebeesgenesis.configuration.bee_network.topologyMicros").comment("拓扑扫描软时间预算，单位微秒；单次节点读取不可抢占。").defineInRange("topologyMicros", 2000, 100, 10000);
		builder.pop();
	}
}
