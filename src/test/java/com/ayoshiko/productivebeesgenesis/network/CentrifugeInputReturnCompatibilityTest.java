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

/** Issue #8 可选依赖类加载与网络注册检查。 */
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
			String version = Files.readAllLines(Path.of("gradle.properties")).stream()
					.filter(line -> line.startsWith("neo_version=")).findFirst().orElseThrow().split("=", 2)[1];
			mergedNeoForge = artifacts
					.filter(path -> path.getFileName().toString().equals("neoforge-" + version + "-merged.jar"))
					.findFirst()
					.orElseThrow();
		}
		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
				Path.of("build/classes/java/main").toUri().toURL(),
				mergedNeoForge.toUri().toURL(),
				Path.of("libs/Mekanism-1.21.1-10.7.19.85.jar").toUri().toURL()
		}, null)) {
			try (URLClassLoader installed = new URLClassLoader(new java.net.URL[] {
					Path.of("libs/appliedenergistics2-1.21.1-19.2.17.jar").toUri().toURL()
			}, loader)) {
				Class.forName("appeng.api.networking.IGrid", false, installed);
			}
			assertThrows(ClassNotFoundException.class,
					() -> Class.forName("appeng.api.networking.IGrid", false, loader),
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

	private static String readClassBytes(String internalName) throws Exception {
		try (InputStream stream = CentrifugeInputReturnCompatibilityTest.class.getClassLoader()
				.getResourceAsStream(internalName + ".class")) {
			assertNotNull(stream, internalName + ".class 未生成");
			return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
