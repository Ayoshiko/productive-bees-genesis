package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * bee_type 数据组件解析的接线校验（源码级断言，不需要 Minecraft 运行时）。
 * <p>
 * 依据 spark 采样（NeoForge 21.1.214 / MC 1.21.1 / 45 mods，各 60s）：
 * <ul>
 *   <li>AHlDkwd9n9：{@code DeferredHolder.get} 自耗 884ms / 1.47%，为服务端线程第 2 热方法
 *       （仅次于 {@code Unsafe.park} 的空闲等待）；</li>
 *   <li>NKn1ZLQN2W：同方法 468ms / 0.78%。</li>
 * </ul>
 * 调用栈显示它来自 {@code Ae2InputPuller$PullEntry.matchesComponents} 等热路径里对
 * {@code ModDataComponents.BEE_TYPE.get()} 的重复注册表查找（单次调用内最多 4 次）。
 * <p>
 * {@code DataComponentType} 在静态注册表中注册一次即恒定，因此改为经
 * {@link PbDataComponents#beeType()} 一次性解析。该优化被改回原样时行为完全正确、
 * 只是开销回涨，纯逻辑测不出来，故用源码断言把热路径调用点钉住。
 */
class PbDataComponentsWiringTest {

	/** 必须走缓存访问器的热路径文件（每 tick 或每槽 × 每类型被调用）。 */
	private static final List<String> HOT_PATH_FILES = List.of(
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/CombFuzzyMatcher.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeFinder.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MekCentrifugeFactoryHelper.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/IMekCentrifugeTile.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/MyriadBatchPlanner.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/MyriadCreationsEventHandler.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/util/InputValidationCache.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/util/InputOutputCompatibilityCache.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/util/RecipeCacheManager.java");

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("热路径不得直接调用 DeferredHolder 的 BEE_TYPE.get()")
	void hotPathsUseCachedComponentType() throws Exception {
		List<String> offenders = new ArrayList<>();
		for (String path : HOT_PATH_FILES) {
			String source = read(path);
			if (source.contains("ModDataComponents.BEE_TYPE.get()")) {
				offenders.add(path);
			}
			assertTrue(source.contains("PbDataComponents.beeType()"),
					path + " 必须经 PbDataComponents.beeType() 解析组件类型");
		}
		assertTrue(offenders.isEmpty(),
				() -> "以下热路径仍在直接做注册表查找（spark 中 DeferredHolder.get 曾占 1.47%）: "
						+ offenders);
	}

	@Test
	@DisplayName("PullEntry.matchesComponents 单次调用只解析一次组件类型")
	void componentMatchResolvesTypeOnce() throws Exception {
		String source = read(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		int methodStart = source.indexOf("boolean matchesComponents(");
		assertTrue(methodStart > 0, "找不到 matchesComponents 方法");
		int methodEnd = source.indexOf("\n\t\t/**", methodStart);
		String method = source.substring(methodStart, methodEnd > 0 ? methodEnd : source.length());
		assertTrue(method.contains("DataComponentType<ResourceLocation> beeTypeComponent"),
				"必须把组件类型提到局部变量，避免同一方法内重复解析");
		assertEquals(1, countOccurrences(method, "PbDataComponents.beeType()"),
				"matchesComponents 内只应解析一次组件类型（原实现连续 4 次 DeferredHolder.get）");
	}

	@Test
	@DisplayName("解析器惰性求值且不设失效入口（静态注册表恒定）")
	void accessorIsLazyAndNeedsNoInvalidation() throws Exception {
		String source = read(
				"src/main/java/com/ayoshiko/productivebeesgenesis/util/PbDataComponents.java");
		assertTrue(source.contains("private static volatile DataComponentType<ResourceLocation> beeType"),
				"缓存字段须为 volatile，供 tick 线程与渲染线程并发读");
		assertFalse(source.contains("static {"),
				"不得在类初始化时解析：注册完成前调用 DeferredHolder.get 会抛异常");
		assertFalse(source.contains("invalidate"),
				"DataComponentType 属静态注册表，不随数据包重载变化，不应有失效入口");
	}

	@Test
	@DisplayName("注册期与展示期的组件写入保持原样，不引入无谓间接层")
	void registrationPathsKeepDirectHolder() throws Exception {
		// ModItems 在注册期构造默认组件，此时 DeferredHolder 尚未绑定，必须保留原调用形式
		String modItems = read(
				"src/main/java/com/ayoshiko/productivebeesgenesis/init/ModItems.java");
		assertTrue(modItems.contains("ModDataComponents.BEE_TYPE.get()"),
				"注册期路径应保留 DeferredHolder，不走缓存访问器");
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int index = haystack.indexOf(needle);
		while (index >= 0) {
			count++;
			index = haystack.indexOf(needle, index + needle.length());
		}
		return count;
	}

	@Test
	@DisplayName("热路径文件均存在（防止重构改名后断言静默失效）")
	void hotPathFilesExist() {
		List<String> missing = Stream.of(HOT_PATH_FILES.toArray(new String[0]))
				.filter(p -> !Files.isRegularFile(Path.of(p)))
				.toList();
		assertTrue(missing.isEmpty(), () -> "热路径文件缺失，断言已失去意义: " + missing);
	}
}
