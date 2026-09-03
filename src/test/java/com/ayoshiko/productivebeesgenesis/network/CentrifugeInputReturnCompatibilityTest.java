package com.ayoshiko.productivebeesgenesis.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #8 可选依赖类加载与离线输出接线回归检查。 */
class CentrifugeInputReturnCompatibilityTest {

	private static final String NETWORK_SOURCE =
			"src/main/java/com/ayoshiko/productivebeesgenesis/network/";

	@Test
	@DisplayName("无条件加载的返还类常量池不含 AE2 或 Applied Flux 类型")
	void unconditionalClassesHaveNoOptionalApiSymbols() throws Exception {
		for (String className : List.of(
				"com/ayoshiko/productivebeesgenesis/network/CentrifugeInputReturnPayloadHandler",
				"com/ayoshiko/productivebeesgenesis/network/CentrifugeAeReturnResult",
				"com/ayoshiko/productivebeesgenesis/network/CentrifugeConfiguredOutputService",
				"com/ayoshiko/productivebeesgenesis/network/ReturnCentrifugeInputPayload",
				"com/ayoshiko/productivebeesgenesis/client/screen/CentrifugeInputReturnButton")) {
			String constantPool = readClassBytes(className);
			assertFalse(constantPool.contains("appeng/"), className + " 不得硬引用 AE2 API");
			assertFalse(constantPool.contains("com/glodblock/"), className + " 不得硬引用 Applied Flux API");
			if (className.endsWith("CentrifugeInputReturnPayloadHandler")) {
				assertFalse(constantPool.contains("Ae2CentrifugeInputReturnService"),
						"核心 handler 不得直接解析 AE2 专用服务类");
			}
			if (className.endsWith("CentrifugeConfiguredOutputService")) {
				assertFalse(constantPool.contains("net/minecraft/world/entity/item/ItemEntity"),
						"本地输出服务不得在任何异常路径生成物品实体");
			}
		}
		assertTrue(readClassBytes(
				"com/ayoshiko/productivebeesgenesis/network/Ae2CentrifugeInputReturnService")
				.contains("appeng/"), "AE2 类型应只存在于延迟加载的专用服务");
	}

	@Test
	@DisplayName("AE2 不在测试运行时类路径时核心返还类仍可完成 JVM 加载验证")
	void coreClassesLoadWithoutAe2() throws Exception {
		Path mergedNeoForge;
		try (var artifacts = Files.list(Path.of("build/moddev/artifacts"))) {
			mergedNeoForge = artifacts
					.filter(path -> path.getFileName().toString().endsWith("-merged.jar"))
					.findFirst()
					.orElseThrow();
		}
		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
				Path.of("build/classes/java/main").toUri().toURL(),
				mergedNeoForge.toUri().toURL(),
				Path.of("libs/Mekanism-1.21.1-10.7.19.85.jar").toUri().toURL()
		}, null)) {
			assertThrows(ClassNotFoundException.class,
					() -> Class.forName("appeng.api.AEApi", false, loader),
					"隔离类加载器不得包含 AE2");
			for (String className : List.of(
					"com.ayoshiko.productivebeesgenesis.network.CentrifugeInputReturnPayloadHandler",
					"com.ayoshiko.productivebeesgenesis.network.CentrifugeAeReturnResult",
					"com.ayoshiko.productivebeesgenesis.network.CentrifugeConfiguredOutputService",
					"com.ayoshiko.productivebeesgenesis.network.ReturnCentrifugeInputPayload")) {
				Class.forName(className, false, loader);
			}
		}
	}

	@Test
	@DisplayName("返还包无条件注册，AE2 服务调用受加载检测保护")
	void packetRegistrationAndAeCallAreGuarded() throws Exception {
		String payloads = Files.readString(Path.of(NETWORK_SOURCE + "ModPayloads.java"));
		int returnRegistration = payloads.indexOf("ReturnCentrifugeInputPayload.TYPE");
		int aeGuard = payloads.indexOf("if (Ae2IntegrationLoader.isAe2Loaded()) {");
		assertTrue(returnRegistration >= 0 && aeGuard >= 0 && returnRegistration < aeGuard,
				"返还包必须在 AE2 条件注册块之外注册");

		String handler = Files.readString(Path.of(
				NETWORK_SOURCE + "CentrifugeInputReturnPayloadHandler.java"));
		int loaderCheck = handler.indexOf("if (!Ae2IntegrationLoader.isAe2Loaded())");
		int aeServiceCall = handler.indexOf("Ae2Access.transfer");
		assertTrue(loaderCheck >= 0 && aeServiceCall > loaderCheck,
				"含 AE2 API 的服务只能在加载检测之后触达");
		assertFalse(handler.contains("Ae2PayloadHandlers.validateContainerMatch"),
				"核心 handler 不得经由带 AE2 类型的 handler 做容器校验");
	}

	@Test
	@DisplayName("全部输入批量返还和 Mekanism 配置输出链路保持接线")
	void allInputAndConfiguredOutputStayWired() throws Exception {
		String handler = Files.readString(Path.of(
				NETWORK_SOURCE + "CentrifugeInputReturnPayloadHandler.java"));
		assertFalse(handler.contains("MAX_ITEMS_PER_REQUEST"));
		assertTrue(handler.contains("CentrifugeConfiguredOutputService.transfer"));

		String output = Files.readString(Path.of(
				NETWORK_SOURCE + "CentrifugeConfiguredOutputService.java"));
		assertFalse(output.contains("remainingLimit"));
		assertFalse(output.contains("maxItems"));
		assertTrue(output.contains("getConfig(TransmissionType.ITEM)"));
		assertTrue(output.contains("dataType.canOutput()"));
		assertTrue(output.contains("Capabilities.ItemHandler.BLOCK"));
		assertTrue(output.contains("worldSide.getOpposite()"));
		int simulateInsert = output.indexOf("ItemHandlerHelper.insertItemStacked(target, candidate, true)");
		int executeExtract = output.indexOf("source.shrinkStack(accepted, Action.EXECUTE)");
		assertTrue(simulateInsert >= 0 && executeExtract > simulateInsert,
				"必须先模拟目标接收量，再实际提取源槽物品");
		assertTrue(output.contains("newSetFromMap(new IdentityHashMap<>())"));
		assertFalse(output.contains("Containers.dropItemStack"),
				"超大堆叠异常回滚不得生成世界掉落物");
		assertFalse(output.contains("ItemEntity"),
				"即使回滚异常也不得生成单个物品实体");
		assertTrue(output.contains("sourceSnapshot"));
		assertTrue(output.contains("source.setStack(expected.copy())"),
				"目标实际接收不足时必须按操作前快照恢复源槽");
		assertFalse(handler.contains("recovery_drop"));

		String ae2 = Files.readString(Path.of(
				NETWORK_SOURCE + "Ae2CentrifugeInputReturnService.java"));
		assertFalse(ae2.contains("remainingLimit"));
		assertFalse(ae2.contains("maxItems"));
		assertTrue(ae2.contains("int requested = Math.max(0, current.getCount())"));
		int aeSimulateInsert = ae2.indexOf("storage.insert(key, requested, Actionable.SIMULATE");
		int aeSimulateExtract = ae2.indexOf("slot.shrinkStack(requestedExtract, Action.SIMULATE)");
		assertTrue(aeSimulateInsert >= 0 && aeSimulateExtract > aeSimulateInsert,
				"AE2 路径必须先模拟网络接收量，再探测源槽可提取量");

		String english = Files.readString(Path.of(
				"src/main/resources/assets/productivebeesgenesis/lang/en_us.json"));
		String chinese = Files.readString(Path.of(
				"src/main/resources/assets/productivebeesgenesis/lang/zh_cn.json"));
		assertTrue(english.contains("\"Return input-slot items\""));
		assertTrue(chinese.contains("\"返还输入槽中的物品\""));
		assertFalse(english.contains("recovery_drop"));
		assertFalse(chinese.contains("recovery_drop"));
	}

	private static String readClassBytes(String internalName) throws Exception {
		try (InputStream stream = CentrifugeInputReturnCompatibilityTest.class.getClassLoader()
				.getResourceAsStream(internalName + ".class")) {
			assertNotNull(stream, internalName + ".class 未生成");
			return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
