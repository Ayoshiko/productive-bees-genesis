package com.ayoshiko.productivebeesgenesis.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PB 全局 Mixin 注册范围。 */
class MixinBoundaryConventionTest {

	@Test
	@DisplayName("不得用全局性能 mixin 改写未安装本模组升级的 PB 高级蜂箱")
	void productiveBeesHiveOptimizationsStayOutOfGlobalTargets() throws Exception {
		String config = Files.readString(Path.of("src/main/resources/productivebeesgenesis.mixins.json"));
		for (String forbidden : List.of(
				"AdvancedBeehiveBlockEntityAbstractSimCacheMixin",
				"AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin",
				"AdvancedBeehiveInventoryDebounceMixin",
				"BlockEntityItemStackHandlerDebounceMixin")) {
			assertFalse(config.contains(forbidden),
					forbidden + " 会改变所有 PB 高级蜂箱的扫描或存盘语义，不能全局登记");
		}
	}

}
