package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

/**
 * 万象创世等待状态的日志管理器，仅从服务器处理线程调用。
 * <p>
 * 预热和输出背压属于正常等待，默认静默，分别由 bee_cache 和 centrifuge_batch
 * 开发者日志开关控制。每类使用固定全局键和单调时钟冷却，跨机器、进程和维度
 * 每分钟至多一条，不随时间加速增加；不保留世界、机器或进程引用。
 * 已就绪但过滤结果为空仍记录限频 WARN，避免真正无法生产的配置问题被隐藏。
 */
final class MyriadCreationsLogger {

	private static final long COOLDOWN_MS = 60_000L;

	/** 日志前缀（区分原版/ME/EME 工厂） */
	private final String logPrefix;

	MyriadCreationsLogger(String logPrefix) {
		this.logPrefix = logPrefix;
	}

	/** 仅记录状态；调用方负责保留进度和资产。 */
	void logEmptyCacheAndPreserve(int processIndex) {
		if (MyriadCreationsEventHandler.isBeeTypeCacheWarmupComplete()) {
			LogThrottle.warnWithCooldown("myriad_empty_filter", COOLDOWN_MS,
					"{}进程{}万象创世蜜蜂数据已就绪但当前过滤结果为空，保留进度等待配置或数据包变化",
					logPrefix, processIndex);
		} else if (DevModeManager.isLoggingEnabled("bee_cache")) {
			LogThrottle.infoWithCooldown("myriad_cache_warmup", COOLDOWN_MS,
					"[DEV][bee_cache] {}进程{}万象创世类型缓存未就绪（预热中），保留进度等待缓存构建",
					logPrefix, processIndex);
		}
	}

	/** 物品与流体受阻共用冷却；开关关闭时不分配日志参数数组。 */
	void logOutputBlocked(int processIndex, int batchSize, boolean fluid) {
		if (!DevModeManager.isLoggingEnabled("centrifuge_batch")) {
			return;
		}
		LogThrottle.infoWithCooldown("myriad_output_blocked", COOLDOWN_MS,
				"[DEV][centrifuge_batch] {}万象创世{}空间不足，保留进度等待输出：进程{} batchSize={}",
				logPrefix, fluid ? "流体槽" : "产物槽", processIndex, batchSize);
	}
}
